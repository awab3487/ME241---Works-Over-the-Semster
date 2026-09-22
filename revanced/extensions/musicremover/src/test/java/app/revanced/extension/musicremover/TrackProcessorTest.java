package app.revanced.extension.musicremover;

import static app.revanced.extension.musicremover.TestSignals.SAMPLE_RATE;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.media.AudioFormat;

import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;
import java.util.Random;

public class TrackProcessorTest {
    private static final int WRITE_NON_BLOCKING = 1;

    @Test
    public void disabled_writesDelayedOriginalAudio_withPartialWrites() {
        TrackProcessor processor = new TrackProcessor(SAMPLE_RATE, 2, AudioFormat.ENCODING_PCM_16BIT,
                false, MusicRemovalStrength.DEFAULT);
        byte[] input = pcm16(TestSignals.noise(SAMPLE_RATE * 2, 1));

        byte[] output = play(processor, input, 4, false, new FakeTrack(2));

        int delay = 2048 * 4;
        assertEquals(input.length, output.length);
        assertArrayEquals(new byte[delay], Arrays.copyOf(output, delay));
        assertArrayEquals(Arrays.copyOf(input, input.length - delay), Arrays.copyOfRange(output, delay, output.length));
    }

    @Test
    public void disabled_float_writesDelayedOriginalAudio() {
        TrackProcessor processor = new TrackProcessor(SAMPLE_RATE, 1, AudioFormat.ENCODING_PCM_FLOAT,
                false, MusicRemovalStrength.DEFAULT);
        byte[] input = pcmFloat(TestSignals.noise(SAMPLE_RATE, 2));

        byte[] output = play(processor, input, 4, false, new FakeTrack(3));

        int delay = 2048 * 4;
        assertEquals(input.length, output.length);
        assertArrayEquals(Arrays.copyOf(input, input.length - delay), Arrays.copyOfRange(output, delay, output.length));
    }

    @Test
    public void enabled_keepsVoiceAndLength_withPartialWrites() {
        TrackProcessor processor = new TrackProcessor(SAMPLE_RATE, 2, AudioFormat.ENCODING_PCM_16BIT,
                true, MusicRemovalStrength.DEFAULT);
        float[] voice = TestSignals.voice(SAMPLE_RATE * 3);
        float[][] music = TestSignals.wideMusic(SAMPLE_RATE * 3, 4);
        float[] voiceStereo = TestSignals.interleave(voice, voice);
        float[] musicStereo = TestSignals.interleave(music[0], music[1]);
        byte[] input = pcm16(TestSignals.add(voiceStereo, musicStereo));

        byte[] output = play(processor, input, 4, true, new FakeTrack(5));

        assertEquals(input.length, output.length);
        float[] processed = fromPcm16(output);
        int delay = 2048 * 2;
        int from = SAMPLE_RATE * 2;
        double voiceGain = TestSignals.retainedGain(processed, voiceStereo, delay, from);
        double musicGain = TestSignals.retainedGain(processed, musicStereo, delay, from);
        assertTrue("voice gain " + voiceGain, voiceGain > 0.5);
        assertTrue("music gain " + musicGain, musicGain < 0.1);
    }

    @Test
    public void callerBufferIsNeverModified() {
        TrackProcessor processor = new TrackProcessor(SAMPLE_RATE, 2, AudioFormat.ENCODING_PCM_16BIT,
                true, MusicRemovalStrength.DEFAULT);
        byte[] input = pcm16(TestSignals.noise(SAMPLE_RATE, 6));
        byte[] copy = input.clone();

        // Read only buffers would throw if the processor tried to write into them
        play(processor, input, 4, true, new FakeTrack(7));

        assertArrayEquals(copy, input);
    }

    @Test
    public void reset_dropsAudioThatWasNotWrittenYet() {
        TrackProcessor processor = new TrackProcessor(SAMPLE_RATE, 1, AudioFormat.ENCODING_PCM_16BIT,
                false, MusicRemovalStrength.DEFAULT);
        FakeTrack track = new FakeTrack(8);
        track.randomAmounts = false;

        ByteBuffer first = ByteBuffer.wrap(pcm16(TestSignals.noise(4096, 9))).asReadOnlyBuffer();
        track.maxBytesPerWrite = 100;
        int written = processor.write(first, first.remaining(), WRITE_NON_BLOCKING, false, MusicRemovalStrength.DEFAULT, track);
        assertEquals(100, written);

        // After a flush the player writes new audio instead of the rest of the old buffer
        processor.reset();
        track.maxBytesPerWrite = Integer.MAX_VALUE;
        byte[] second = pcm16(TestSignals.noise(4096, 10));
        ByteBuffer buffer = ByteBuffer.wrap(second).asReadOnlyBuffer();
        written = processor.write(buffer, buffer.remaining(), WRITE_NON_BLOCKING, false, MusicRemovalStrength.DEFAULT, track);

        assertEquals(second.length, written);
        assertEquals(second.length, buffer.position());
        // The new audio starts after the delay of the isolator, which only holds silence after the reset
        byte[] output = track.output.toByteArray();
        byte[] afterReset = Arrays.copyOfRange(output, 100, output.length);
        assertArrayEquals(new byte[2048 * 2], Arrays.copyOf(afterReset, 2048 * 2));
        assertArrayEquals(Arrays.copyOf(second, second.length - 2048 * 2), Arrays.copyOfRange(afterReset, 2048 * 2, afterReset.length));
    }

    /**
     * Writes the input like ExoPlayer does: random sized buffers that are written again
     * until the track accepted all of their bytes.
     */
    private static byte[] play(TrackProcessor processor, byte[] input, int frameSize, boolean enabled, FakeTrack track) {
        Random random = new Random(input.length);
        int position = 0;
        while (position < input.length) {
            int size = Math.min(input.length - position, frameSize * (1 + random.nextInt(4096)));
            ByteBuffer buffer = ByteBuffer.wrap(input, position, size).slice().asReadOnlyBuffer();
            while (buffer.hasRemaining()) {
                int remaining = buffer.remaining();
                int written = processor.write(buffer, remaining, WRITE_NON_BLOCKING, enabled, MusicRemovalStrength.DEFAULT, track);
                assertTrue(written >= 0 && written <= remaining);
                assertEquals(remaining - written, buffer.remaining());
            }
            position += size;
        }
        return track.output.toByteArray();
    }

    /** Accepts a random amount of the data per write, like a non-blocking AudioTrack. */
    private static final class FakeTrack implements TrackProcessor.Output {
        final ByteArrayOutputStream output = new ByteArrayOutputStream();
        final Random random;
        boolean randomAmounts = true;
        int maxBytesPerWrite = Integer.MAX_VALUE;

        FakeTrack(long seed) {
            random = new Random(seed);
        }

        @Override
        public int write(ByteBuffer buffer, int sizeInBytes, int writeMode) {
            int accepted = !randomAmounts ? sizeInBytes : random.nextInt(4) == 0 ? 0 : random.nextInt(sizeInBytes + 1);
            accepted = Math.min(accepted, maxBytesPerWrite);
            byte[] bytes = new byte[accepted];
            buffer.get(bytes);
            output.write(bytes, 0, bytes.length);
            return accepted;
        }
    }

    private static byte[] pcm16(float[] samples) {
        ByteBuffer buffer = ByteBuffer.allocate(samples.length * 2).order(ByteOrder.nativeOrder());
        for (float sample : samples) {
            buffer.putShort((short) Math.max(Short.MIN_VALUE, Math.min(Short.MAX_VALUE, Math.round(sample * 32768f))));
        }
        return buffer.array();
    }

    private static byte[] pcmFloat(float[] samples) {
        ByteBuffer buffer = ByteBuffer.allocate(samples.length * 4).order(ByteOrder.nativeOrder());
        for (float sample : samples) buffer.putFloat(sample);
        return buffer.array();
    }

    private static float[] fromPcm16(byte[] bytes) {
        ByteBuffer buffer = ByteBuffer.wrap(bytes).order(ByteOrder.nativeOrder());
        float[] samples = new float[bytes.length / 2];
        for (int i = 0; i < samples.length; i++) samples[i] = buffer.getShort() / 32768f;
        return samples;
    }
}
