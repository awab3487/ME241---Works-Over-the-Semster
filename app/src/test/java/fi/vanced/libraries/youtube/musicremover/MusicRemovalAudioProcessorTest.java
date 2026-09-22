package fi.vanced.libraries.youtube.musicremover;

import static fi.vanced.libraries.youtube.musicremover.TestSignals.SAMPLE_RATE;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.media.AudioFormat;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Random;

public class MusicRemovalAudioProcessorTest {
    private MusicRemovalAudioProcessor processor;

    @Before
    public void setUp() {
        MusicRemover.isEnabled = true;
        MusicRemover.strength = MusicRemovalStrength.DEFAULT;
        processor = new MusicRemovalAudioProcessor();
    }

    @After
    public void tearDown() {
        MusicRemover.isEnabled = false;
        MusicRemover.strength = MusicRemovalStrength.DEFAULT;
    }

    @Test
    public void configure_isInactiveWhenDisabled() {
        MusicRemover.isEnabled = false;
        assertFalse(processor.configure(SAMPLE_RATE, 2, AudioFormat.ENCODING_PCM_16BIT));
        assertFalse(processor.isActive());
    }

    @Test
    public void configure_isInactiveForUnsupportedFormats() {
        assertFalse(processor.configure(SAMPLE_RATE, 6, AudioFormat.ENCODING_PCM_16BIT));
        assertFalse(processor.configure(SAMPLE_RATE, 2, AudioFormat.ENCODING_PCM_8BIT));
        assertTrue(processor.configure(SAMPLE_RATE, 2, AudioFormat.ENCODING_PCM_16BIT));
        assertTrue(processor.configure(SAMPLE_RATE, 1, AudioFormat.ENCODING_PCM_FLOAT));
        assertEquals(SAMPLE_RATE, processor.getOutputSampleRateHz());
        assertEquals(1, processor.getOutputChannelCount());
        assertEquals(AudioFormat.ENCODING_PCM_FLOAT, processor.getOutputEncoding());
    }

    @Test
    public void switchedOffWhileActive_outputsIdenticalPcm16() {
        assertTrue(processor.configure(SAMPLE_RATE, 2, AudioFormat.ENCODING_PCM_16BIT));
        MusicRemover.isEnabled = false;
        processor.flush();

        byte[] input = pcm16(TestSignals.noise(SAMPLE_RATE * 2, 5));
        assertArrayEquals(input, run(input, 4));
    }

    @Test
    public void switchedOffWhileActive_outputsIdenticalFloat() {
        assertTrue(processor.configure(SAMPLE_RATE, 1, AudioFormat.ENCODING_PCM_FLOAT));
        MusicRemover.isEnabled = false;
        processor.flush();

        byte[] input = pcmFloat(TestSignals.noise(SAMPLE_RATE, 6));
        assertArrayEquals(input, run(input, 4));
    }

    @Test
    public void enabled_keepsLengthAndTimingAfterFlush() {
        assertTrue(processor.configure(SAMPLE_RATE, 2, AudioFormat.ENCODING_PCM_16BIT));
        run(pcm16(TestSignals.noise(SAMPLE_RATE, 7)), 4);
        processor.flush();

        float[] voice = TestSignals.voice(SAMPLE_RATE * 3);
        byte[] input = pcm16(TestSignals.interleave(voice, voice));
        byte[] output = run(input, 4);

        assertEquals(input.length, output.length);
        // The voice is kept without any delay
        float[] processed = fromPcm16(output);
        float[] original = fromPcm16(input);
        double voiceGain = TestSignals.retainedGain(processed, original, 0, SAMPLE_RATE * 2);
        double shiftedGain = TestSignals.retainedGain(processed, original, 2 * 64, SAMPLE_RATE * 2);
        assertTrue("voice gain " + voiceGain, voiceGain > 0.6);
        assertTrue("shifted gain " + shiftedGain, shiftedGain < voiceGain);
    }

    @Test
    public void enabled_removesWideMusic() {
        assertTrue(processor.configure(SAMPLE_RATE, 2, AudioFormat.ENCODING_PCM_FLOAT));
        float[][] music = TestSignals.wideMusic(SAMPLE_RATE * 4, 8);
        float[] input = TestSignals.interleave(music[0], music[1]);

        float[] output = fromPcmFloat(run(pcmFloat(input), 8));

        assertEquals(input.length, output.length);
        double musicGain = TestSignals.retainedGain(output, input, 0, SAMPLE_RATE * 4);
        assertTrue("music gain " + musicGain, musicGain < 0.2);
    }

    @Test
    public void endOfStream_waitsUntilPendingOutputIsConsumed() {
        assertTrue(processor.configure(SAMPLE_RATE, 1, AudioFormat.ENCODING_PCM_16BIT));
        byte[] input = pcm16(TestSignals.noise(SAMPLE_RATE / 10, 9));
        processor.queueInput(nativeBuffer(input));
        ByteBuffer pending = processor.getOutput();
        processor.queueEndOfStream();

        assertTrue(pending.hasRemaining());
        assertFalse(processor.isEnded());
        assertFalse(processor.getOutput().hasRemaining());

        int bytes = pending.remaining();
        pending.position(pending.limit());
        bytes += processor.getOutput().remaining();
        assertTrue(processor.isEnded());
        assertEquals(input.length, bytes);
    }

    /** Feeds the input in random chunks, drains the processor and returns everything it output. */
    private byte[] run(byte[] input, int frameSize) {
        Random random = new Random(input.length);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        int position = 0;
        while (position < input.length) {
            int chunk = Math.min(input.length - position, frameSize * (1 + random.nextInt(2048)));
            ByteBuffer buffer = nativeBuffer(input, position, chunk);
            processor.queueInput(buffer);
            assertFalse(buffer.hasRemaining());
            drainInto(output);
            position += chunk;
        }
        processor.queueEndOfStream();
        while (!processor.isEnded()) {
            drainInto(output);
        }
        return output.toByteArray();
    }

    private void drainInto(ByteArrayOutputStream output) {
        ByteBuffer buffer = processor.getOutput();
        byte[] bytes = new byte[buffer.remaining()];
        buffer.get(bytes);
        output.write(bytes, 0, bytes.length);
    }

    private static ByteBuffer nativeBuffer(byte[] bytes) {
        return nativeBuffer(bytes, 0, bytes.length);
    }

    private static ByteBuffer nativeBuffer(byte[] bytes, int offset, int length) {
        ByteBuffer buffer = ByteBuffer.allocateDirect(length).order(ByteOrder.nativeOrder());
        buffer.put(bytes, offset, length);
        buffer.flip();
        return buffer;
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

    private static float[] fromPcmFloat(byte[] bytes) {
        ByteBuffer buffer = ByteBuffer.wrap(bytes).order(ByteOrder.nativeOrder());
        float[] samples = new float[bytes.length / 4];
        for (int i = 0; i < samples.length; i++) samples[i] = buffer.getFloat();
        return samples;
    }
}
