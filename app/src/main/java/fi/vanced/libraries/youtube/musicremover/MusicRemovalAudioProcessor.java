package fi.vanced.libraries.youtube.musicremover;

import android.media.AudioFormat;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;

/**
 * Removes background music from decoded PCM audio before it is played.
 *
 * This class follows the contract of ExoPlayer's AudioProcessor (configure, isActive, queueInput,
 * getOutput, queueEndOfStream, isEnded, flush, reset), so the patch only needs a thin adapter that
 * implements YouTube's obfuscated AudioProcessor interface and delegates every call to an instance
 * of this class. The output format is always the same as the input format.
 *
 * The processor delays the audio internally but drops the same amount of silence at the start and
 * outputs the delayed tail on end of stream, so audio and video stay in sync.
 */
public final class MusicRemovalAudioProcessor {
    private static final ByteBuffer EMPTY_BUFFER = ByteBuffer.allocateDirect(0).order(ByteOrder.nativeOrder());

    private int sampleRateHz = -1;
    private int channelCount = -1;
    private int encoding = -1;
    private boolean active;
    private VoiceIsolator isolator;

    private float[] samples = new float[0];
    private ByteBuffer buffer = EMPTY_BUFFER;
    private ByteBuffer outputBuffer = EMPTY_BUFFER;
    private boolean inputEnded;
    private boolean drainPending;
    private int prerollFramesLeft;

    public static boolean isSupported(int channelCount, int encoding) {
        return (channelCount == 1 || channelCount == 2)
                && (encoding == AudioFormat.ENCODING_PCM_16BIT || encoding == AudioFormat.ENCODING_PCM_FLOAT);
    }

    /**
     * Configures the processor for the given input format.
     *
     * @param encoding {@link AudioFormat#ENCODING_PCM_16BIT} or {@link AudioFormat#ENCODING_PCM_FLOAT}
     * @return whether the processor is active. Inactive processors must not receive any audio.
     */
    public boolean configure(int sampleRateHz, int channelCount, int encoding) {
        this.sampleRateHz = sampleRateHz;
        this.channelCount = channelCount;
        this.encoding = encoding;
        // Only take part in the audio chain while the music remover is turned on, so it costs nothing otherwise.
        // While active it can still be switched on and off instantly.
        active = MusicRemover.isEnabled && sampleRateHz > 0 && isSupported(channelCount, encoding);
        isolator = active ? new VoiceIsolator(sampleRateHz, channelCount, MusicRemover.strength) : null;
        flush();
        return active;
    }

    public boolean isActive() {
        return active;
    }

    public int getOutputSampleRateHz() {
        return sampleRateHz;
    }

    public int getOutputChannelCount() {
        return channelCount;
    }

    public int getOutputEncoding() {
        return encoding;
    }

    /** Consumes all complete frames of the input buffer. */
    public void queueInput(ByteBuffer input) {
        int bytesPerSample = getBytesPerSample();
        int frameSize = bytesPerSample * channelCount;
        int frames = input.remaining() / frameSize;
        if (frames == 0) return;

        int sampleCount = frames * channelCount;
        ensureSampleCapacity(sampleCount);
        int position = input.position();
        if (encoding == AudioFormat.ENCODING_PCM_FLOAT) {
            for (int i = 0; i < sampleCount; i++) {
                samples[i] = input.getFloat(position + i * bytesPerSample);
            }
        } else {
            for (int i = 0; i < sampleCount; i++) {
                samples[i] = input.getShort(position + i * bytesPerSample) * (1f / 32768f);
            }
        }
        input.position(position + frames * frameSize);

        process(frames);
        MusicRemover.onAudioProcessed();
    }

    public void queueEndOfStream() {
        if (inputEnded) return;
        inputEnded = true;
        // The delayed tail is written once the output that is still being played has been consumed
        drainPending = active;
    }

    public ByteBuffer getOutput() {
        if (drainPending && !buffer.hasRemaining()) {
            drainPending = false;
            int frames = isolator.getLatencyFrames();
            ensureSampleCapacity(frames * channelCount);
            Arrays.fill(samples, 0, frames * channelCount, 0f);
            process(frames);
        }

        ByteBuffer output = outputBuffer;
        outputBuffer = EMPTY_BUFFER;
        return output;
    }

    public boolean isEnded() {
        return inputEnded && !drainPending && outputBuffer == EMPTY_BUFFER;
    }

    /** Clears all buffered audio, e.g. when seeking. */
    public void flush() {
        // Output that was not consumed yet is discarded by the player
        buffer.position(buffer.limit());
        outputBuffer = EMPTY_BUFFER;
        inputEnded = false;
        drainPending = false;
        if (isolator != null) {
            isolator.setEnabled(MusicRemover.isEnabled);
            isolator.setStrength(MusicRemover.strength);
            isolator.reset();
            prerollFramesLeft = isolator.getLatencyFrames();
        } else {
            prerollFramesLeft = 0;
        }
    }

    public void reset() {
        flush();
        buffer = EMPTY_BUFFER;
        samples = new float[0];
        isolator = null;
        active = false;
        sampleRateHz = -1;
        channelCount = -1;
        encoding = -1;
    }

    private void process(int frames) {
        isolator.setEnabled(MusicRemover.isEnabled);
        isolator.setStrength(MusicRemover.strength);
        isolator.process(samples, 0, frames);

        // The first frames after a flush are the silence the isolator is delayed by
        int skippedFrames = Math.min(prerollFramesLeft, frames);
        prerollFramesLeft -= skippedFrames;
        writeOutput(skippedFrames * channelCount, (frames - skippedFrames) * channelCount);
    }

    private void writeOutput(int firstSample, int sampleCount) {
        int bytes = sampleCount * getBytesPerSample();
        if (buffer.capacity() < bytes) {
            buffer = ByteBuffer.allocateDirect(bytes).order(ByteOrder.nativeOrder());
        } else {
            buffer.clear();
        }

        int end = firstSample + sampleCount;
        if (encoding == AudioFormat.ENCODING_PCM_FLOAT) {
            for (int i = firstSample; i < end; i++) {
                buffer.putFloat(samples[i]);
            }
        } else {
            for (int i = firstSample; i < end; i++) {
                int value = Math.round(samples[i] * 32768f);
                buffer.putShort((short) Math.max(Short.MIN_VALUE, Math.min(Short.MAX_VALUE, value)));
            }
        }
        buffer.flip();
        outputBuffer = buffer.hasRemaining() ? buffer : EMPTY_BUFFER;
    }

    private int getBytesPerSample() {
        return encoding == AudioFormat.ENCODING_PCM_FLOAT ? 4 : 2;
    }

    private void ensureSampleCapacity(int sampleCount) {
        if (samples.length < sampleCount) {
            samples = new float[sampleCount];
        }
    }
}
