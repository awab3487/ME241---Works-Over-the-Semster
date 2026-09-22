package app.revanced.extension.musicremover;

import static app.revanced.extension.musicremover.TestSignals.SAMPLE_RATE;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Arrays;
import java.util.Random;

public class VoiceIsolatorTest {
    private static final int FRAMES = 6 * SAMPLE_RATE;
    /** The background estimate needs time to settle, so quality is measured from here on. */
    private static final int MEASURE_FROM_FRAME = 2 * SAMPLE_RATE;

    @Test
    public void disabled_passesAudioThroughDelayed() {
        VoiceIsolator isolator = new VoiceIsolator(SAMPLE_RATE, 2, MusicRemovalStrength.HIGH);
        isolator.setEnabled(false);
        isolator.reset();

        float[] input = TestSignals.noise(SAMPLE_RATE * 2, 1);
        float[] output = processInChunks(isolator, input, 2, 7);

        int delay = isolator.getLatencyFrames() * 2;
        assertArrayEquals(new float[delay], Arrays.copyOf(output, delay), 0f);
        assertArrayEquals(Arrays.copyOf(input, input.length - delay), Arrays.copyOfRange(output, delay, output.length), 0f);
    }

    @Test
    public void enabled_keepsCenteredVoiceAndRemovesWideMusic() {
        float[] voice = TestSignals.voice(FRAMES);
        float[][] music = TestSignals.wideMusic(FRAMES, 2);
        float[] voiceStereo = TestSignals.interleave(voice, voice);
        float[] musicStereo = TestSignals.interleave(music[0], music[1]);

        Separation separation = separate(MusicRemovalStrength.MEDIUM, 2, voiceStereo, musicStereo);

        assertTrue("voice gain " + separation.voiceGain, separation.voiceGain > 0.5);
        assertTrue("improvement " + separation.improvementDb(), separation.improvementDb() > 15);
    }

    @Test
    public void enabled_removesSustainedMusicFromMonoAudio() {
        float[] voice = TestSignals.voice(FRAMES);
        float[] music = TestSignals.monoMusic(FRAMES);

        Separation separation = separate(MusicRemovalStrength.MEDIUM, 1, voice, music);

        assertTrue("voice gain " + separation.voiceGain, separation.voiceGain > 0.6);
        assertTrue("improvement " + separation.improvementDb(), separation.improvementDb() > 10);
    }

    @Test
    public void higherStrength_removesMoreMusic() {
        float[] voice = TestSignals.voice(FRAMES);
        float[][] music = TestSignals.wideMusic(FRAMES, 3);
        float[] voiceStereo = TestSignals.interleave(voice, voice);
        float[] musicStereo = TestSignals.interleave(music[0], music[1]);

        Separation low = separate(MusicRemovalStrength.LOW, 2, voiceStereo, musicStereo);
        Separation medium = separate(MusicRemovalStrength.MEDIUM, 2, voiceStereo, musicStereo);
        Separation high = separate(MusicRemovalStrength.HIGH, 2, voiceStereo, musicStereo);

        assertTrue(low.improvementDb() + " < " + medium.improvementDb(), low.improvementDb() < medium.improvementDb());
        assertTrue(medium.improvementDb() + " < " + high.improvementDb(), medium.improvementDb() < high.improvementDb());
    }

    @Test
    public void switchingOff_returnsToUnprocessedAudio() {
        VoiceIsolator isolator = new VoiceIsolator(SAMPLE_RATE, 2, MusicRemovalStrength.MEDIUM);
        float[] input = TestSignals.noise(SAMPLE_RATE * 2, 4);
        float[] output = input.clone();

        int half = input.length / 2;
        isolator.process(output, 0, half / 2);
        isolator.setEnabled(false);
        isolator.process(output, half, half / 2);

        // After the cross-fade the output is the delayed input again
        int delay = isolator.getLatencyFrames() * 2;
        int settled = half + SAMPLE_RATE / 5 * 2;
        assertArrayEquals(Arrays.copyOfRange(input, settled - delay, input.length - delay),
                Arrays.copyOfRange(output, settled, output.length), 0f);
    }

    @Test
    public void switchingOn_fadesInWithoutJumps() {
        VoiceIsolator isolator = new VoiceIsolator(SAMPLE_RATE, 1, MusicRemovalStrength.MEDIUM);
        isolator.setEnabled(false);
        isolator.reset();

        float[] input = TestSignals.monoMusic(SAMPLE_RATE * 2);
        float[] output = input.clone();
        int half = input.length / 2;
        isolator.process(output, 0, half);
        isolator.setEnabled(true);
        isolator.process(output, half, half);

        float maxStep = 0f;
        for (int i = 1; i < output.length; i++) {
            maxStep = Math.max(maxStep, Math.abs(output[i] - output[i - 1]));
        }
        float maxInputStep = 0f;
        for (int i = 1; i < input.length; i++) {
            maxInputStep = Math.max(maxInputStep, Math.abs(input[i] - input[i - 1]));
        }
        assertTrue("max step " + maxStep + " input " + maxInputStep, maxStep <= maxInputStep * 1.5f);
    }

    @Test
    public void latency_dependsOnSampleRate() {
        assertEquals(2048, new VoiceIsolator(48000, 2, MusicRemovalStrength.MEDIUM).getLatencyFrames());
        assertEquals(2048, new VoiceIsolator(44100, 2, MusicRemovalStrength.MEDIUM).getLatencyFrames());
        assertEquals(1024, new VoiceIsolator(22050, 1, MusicRemovalStrength.MEDIUM).getLatencyFrames());
        assertEquals(512, new VoiceIsolator(8000, 1, MusicRemovalStrength.MEDIUM).getLatencyFrames());
    }

    private static Separation separate(MusicRemovalStrength strength, int channels, float[] voice, float[] music) {
        VoiceIsolator isolator = new VoiceIsolator(SAMPLE_RATE, channels, strength);
        float[] output = processInChunks(isolator, TestSignals.add(voice, music), channels, 11);
        int delay = isolator.getLatencyFrames() * channels;
        int from = MEASURE_FROM_FRAME * channels;
        return new Separation(
                TestSignals.retainedGain(output, voice, delay, from),
                TestSignals.retainedGain(output, music, delay, from));
    }

    private static float[] processInChunks(VoiceIsolator isolator, float[] input, int channels, long seed) {
        Random random = new Random(seed);
        float[] output = input.clone();
        int frames = input.length / channels;
        int frame = 0;
        while (frame < frames) {
            int chunk = Math.min(frames - frame, 1 + random.nextInt(4096));
            isolator.process(output, frame * channels, chunk);
            frame += chunk;
        }
        return output;
    }

    private static final class Separation {
        final double voiceGain;
        final double musicGain;

        Separation(double voiceGain, double musicGain) {
            this.voiceGain = voiceGain;
            this.musicGain = musicGain;
        }

        /** How much the voice to music ratio improved. */
        double improvementDb() {
            return 20 * Math.log10(voiceGain / Math.max(Math.abs(musicGain), 1e-9));
        }
    }
}
