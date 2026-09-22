package app.revanced.extension.musicremover;

import android.media.AudioFormat;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Removes music from the PCM audio written to one AudioTrack.
 *
 * The processed audio has exactly as many bytes as the original, delayed by the latency of
 * the {@link VoiceIsolator}. Non-blocking writes may only accept part of the data. The caller then
 * writes the remaining bytes of its buffer again, so the processed bytes that were not accepted yet
 * are kept and written first instead of processing the same audio twice.
 */
final class TrackProcessor {
    interface Output {
        /** Same contract as AudioTrack.write(ByteBuffer, int, int). */
        int write(ByteBuffer buffer, int sizeInBytes, int writeMode);
    }

    private final int channelCount;
    private final boolean isFloat;
    private final int bytesPerSample;
    private final int frameSize;
    private final VoiceIsolator isolator;

    private float[] samples = new float[0];
    /** Processed audio that belongs to the start of the caller's buffer and was not written yet. */
    private ByteBuffer pending = ByteBuffer.allocateDirect(0).order(ByteOrder.nativeOrder());

    static boolean isSupported(int encoding, int channelCount) {
        return (channelCount == 1 || channelCount == 2)
                && (encoding == AudioFormat.ENCODING_PCM_16BIT || encoding == AudioFormat.ENCODING_PCM_FLOAT);
    }

    TrackProcessor(int sampleRate, int channelCount, int encoding, boolean enabled, MusicRemovalStrength strength) {
        this.channelCount = channelCount;
        isFloat = encoding == AudioFormat.ENCODING_PCM_FLOAT;
        bytesPerSample = isFloat ? 4 : 2;
        frameSize = bytesPerSample * channelCount;
        isolator = new VoiceIsolator(sampleRate, channelCount, strength);
        isolator.setEnabled(enabled);
        isolator.reset();
    }

    synchronized int write(ByteBuffer buffer, int sizeInBytes, int writeMode,
                           boolean enabled, MusicRemovalStrength strength, Output output) {
        if (pending.remaining() > sizeInBytes) {
            // The caller is not retrying the previous data, so it is not going to be written anymore
            pending.position(pending.limit());
        }

        if (!pending.hasRemaining()) {
            int frames = sizeInBytes / frameSize;
            if (frames == 0) return output.write(buffer, sizeInBytes, writeMode);
            process(buffer, frames, enabled, strength);
        }

        int written = output.write(pending, pending.remaining(), writeMode);
        if (written > 0) {
            buffer.position(buffer.position() + written);
        }
        return written;
    }

    /** Drops all buffered audio, e.g. when the track is flushed after seeking. */
    synchronized void reset() {
        pending.position(pending.limit());
        isolator.reset();
    }

    private void process(ByteBuffer buffer, int frames, boolean enabled, MusicRemovalStrength strength) {
        int sampleCount = frames * channelCount;
        if (samples.length < sampleCount) {
            samples = new float[sampleCount];
        }

        // AudioTrack reads samples in native byte order, whatever order the buffer is set to
        ByteBuffer input = buffer.duplicate().order(ByteOrder.nativeOrder());
        int position = input.position();
        if (isFloat) {
            for (int i = 0; i < sampleCount; i++) {
                samples[i] = input.getFloat(position + i * bytesPerSample);
            }
        } else {
            for (int i = 0; i < sampleCount; i++) {
                samples[i] = input.getShort(position + i * bytesPerSample) * (1f / 32768f);
            }
        }

        isolator.setEnabled(enabled);
        isolator.setStrength(strength);
        isolator.process(samples, 0, frames);

        int bytes = sampleCount * bytesPerSample;
        if (pending.capacity() < bytes) {
            pending = ByteBuffer.allocateDirect(bytes).order(ByteOrder.nativeOrder());
        } else {
            pending.clear();
        }

        if (isFloat) {
            for (int i = 0; i < sampleCount; i++) {
                pending.putFloat(samples[i]);
            }
        } else {
            for (int i = 0; i < sampleCount; i++) {
                int value = Math.round(samples[i] * 32768f);
                pending.putShort((short) Math.max(Short.MIN_VALUE, Math.min(Short.MAX_VALUE, value)));
            }
        }
        pending.flip();
    }
}
