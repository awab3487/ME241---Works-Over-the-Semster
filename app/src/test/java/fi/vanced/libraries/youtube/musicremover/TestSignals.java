package fi.vanced.libraries.youtube.musicremover;

import java.util.Random;

/** Synthetic voice and music signals for the music remover tests. */
final class TestSignals {
    static final int SAMPLE_RATE = 48000;

    private TestSignals() {}

    /** Speech-like signal: harmonics of a gliding pitch, shaped by formants and cut into syllables. */
    static float[] voice(int frames) {
        float[] voice = new float[frames];
        double phase = 0;
        for (int t = 0; t < frames; t++) {
            double time = (double) t / SAMPLE_RATE;
            double pitch = 140 + 40 * Math.sin(2 * Math.PI * 0.7 * time) + 20 * Math.sin(2 * Math.PI * 2.3 * time);
            phase += pitch / SAMPLE_RATE;

            double syllable = Math.max(0, Math.sin(2 * Math.PI * 3.5 * time));
            double word = time % 1.2 < 0.9 ? 1 : 0;
            double envelope = syllable * syllable * word;
            if (envelope == 0) continue;

            double sample = 0;
            for (int harmonic = 1; harmonic <= 20; harmonic++) {
                double frequency = harmonic * pitch;
                sample += formant(frequency) / harmonic * Math.sin(2 * Math.PI * harmonic * phase);
            }
            voice[t] = (float) (sample * envelope);
        }
        return normalize(voice, 0.1f);
    }

    /** Sustained chords, each note panned to the sides or out of phase between the channels. */
    static float[][] wideMusic(int frames, long seed) {
        Random random = new Random(seed);
        float[] left = new float[frames];
        float[] right = new float[frames];
        double[][] chords = {
                {220.0, 261.63, 329.63, 440.0, 523.25},
                {174.61, 220.0, 261.63, 349.23, 440.0},
                {196.0, 246.94, 293.66, 392.0, 493.88},
        };
        int chordLength = 2 * SAMPLE_RATE;
        for (int start = 0; start < frames; start += chordLength) {
            double[] chord = chords[(start / chordLength) % chords.length];
            for (double frequency : chord) {
                double phase = random.nextDouble() * 2 * Math.PI;
                double stereoOffset = Math.PI / 2 + random.nextDouble() * Math.PI;
                double leftAmplitude = 0.5 + random.nextDouble();
                double rightAmplitude = 0.5 + random.nextDouble();
                for (int t = start; t < Math.min(frames, start + chordLength); t++) {
                    double angle = 2 * Math.PI * frequency * t / SAMPLE_RATE + phase;
                    left[t] += (float) (leftAmplitude * Math.sin(angle));
                    right[t] += (float) (rightAmplitude * Math.sin(angle + stereoOffset));
                }
            }
        }
        float scale = 0.1f / rms(concat(left, right));
        for (int t = 0; t < frames; t++) {
            left[t] *= scale;
            right[t] *= scale;
        }
        return new float[][]{left, right};
    }

    /** Sustained notes and a bass line, identical in both channels. */
    static float[] monoMusic(int frames) {
        float[] music = new float[frames];
        double[] notes = {55.0, 110.0, 329.63, 440.0, 659.25, 880.0};
        for (int t = 0; t < frames; t++) {
            double sample = 0;
            for (double frequency : notes) {
                sample += Math.sin(2 * Math.PI * frequency * t / SAMPLE_RATE);
            }
            music[t] = (float) sample;
        }
        return normalize(music, 0.1f);
    }

    static float[] noise(int samples, long seed) {
        Random random = new Random(seed);
        float[] noise = new float[samples];
        for (int i = 0; i < samples; i++) {
            noise[i] = (float) (random.nextGaussian() * 0.2);
        }
        return noise;
    }

    static float[] interleave(float[] left, float[] right) {
        float[] interleaved = new float[left.length * 2];
        for (int t = 0; t < left.length; t++) {
            interleaved[2 * t] = left[t];
            interleaved[2 * t + 1] = right[t];
        }
        return interleaved;
    }

    static float[] add(float[] a, float[] b) {
        float[] sum = new float[a.length];
        for (int i = 0; i < a.length; i++) sum[i] = a[i] + b[i];
        return sum;
    }

    /**
     * How much of a reference signal is left in the output, as the least squares gain of the
     * reference in the output. The output is expected to be delayed by {@code delay} samples and
     * only samples from {@code from} on are compared.
     */
    static double retainedGain(float[] output, float[] reference, int delay, int from) {
        double dot = 0;
        double energy = 0;
        for (int i = from; i + delay < output.length; i++) {
            dot += (double) output[i + delay] * reference[i];
            energy += (double) reference[i] * reference[i];
        }
        return dot / energy;
    }

    static float rms(float[] signal) {
        double sum = 0;
        for (float sample : signal) sum += sample * sample;
        return (float) Math.sqrt(sum / signal.length);
    }

    private static double formant(double frequency) {
        return 1 / (1 + sq((frequency - 500) / 300))
                + 0.7 / (1 + sq((frequency - 1500) / 400))
                + 0.4 / (1 + sq((frequency - 2500) / 500));
    }

    private static double sq(double value) {
        return value * value;
    }

    private static float[] normalize(float[] signal, float targetRms) {
        float scale = targetRms / rms(signal);
        for (int i = 0; i < signal.length; i++) signal[i] *= scale;
        return signal;
    }

    private static float[] concat(float[] a, float[] b) {
        float[] result = new float[a.length + b.length];
        System.arraycopy(a, 0, result, 0, a.length);
        System.arraycopy(b, 0, result, a.length, b.length);
        return result;
    }
}
