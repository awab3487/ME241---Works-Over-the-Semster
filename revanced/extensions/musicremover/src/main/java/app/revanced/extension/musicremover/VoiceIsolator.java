package app.revanced.extension.musicremover;

import java.util.Arrays;

/**
 * Streaming voice isolator that suppresses background music while keeping speech.
 *
 * The audio is analysed with a short-time Fourier transform (Hann window, 75% overlap)
 * and every frequency bin gets a gain built from three cues:
 * <ul>
 *     <li>Stereo position: voices are almost always mixed in the center, while music is
 *     usually spread across the stereo field. Bins whose left and right channels are not
 *     coherent are attenuated.</li>
 *     <li>Steadiness: sustained tones (chords, pads, held notes) build up a slowly rising
 *     background estimate that is subtracted, while short speech syllables pass.</li>
 *     <li>Frequency range: bass/kick drums below and cymbals above the speech band are
 *     attenuated.</li>
 * </ul>
 *
 * The processor has a fixed latency of {@link #getLatencyFrames()} frames and outputs
 * exactly as many frames as it receives. When disabled it cross-fades to the delayed dry
 * signal, so it can be switched on and off while playing without clicks or A/V drift.
 */
final class VoiceIsolator {
    private static final float EPSILON = 1e-12f;
    private static final float TINY = 1e-20f;
    private static final float STATS_SECONDS = 0.04f;
    private static final float BACKGROUND_FALL_SECONDS = 0.1f;
    private static final float CROSSFADE_SECONDS = 0.05f;
    private static final float GAIN_SMOOTHING = 0.5f;
    private static final int OVERLAP = 4;

    private final int sampleRate;
    private final int channelCount;
    private final boolean stereo;
    private final int fftSize;
    private final int hopSize;
    /** Samples shared by two consecutive frames. */
    private final int overlapLength;
    private final int binCount;
    private final FFT fft;
    private final float[] window;
    private final float synthesisScale;

    private final float[][] inFifo;
    private final float[][] outFifo;
    private final float[][] dryDelay;
    private final float[][] outAccumulator;
    private final float[] re;
    private final float[] im;

    private final float[] powerLeft;
    private final float[] powerRight;
    private final float[] powerCross;
    private final float[] background;
    private final float[] gains;
    private final float[] bandGains;

    private final float statsCoefficient;
    private final float backgroundFallCoefficient;
    private final float mixStep;
    private float backgroundRiseCoefficient;

    private MusicRemovalStrength strength;
    private boolean enabled = true;
    private boolean processing;
    private int warmupFramesLeft;
    private float mix;
    private int rover;
    private int dryIndex;

    VoiceIsolator(int sampleRate, int channelCount, MusicRemovalStrength strength) {
        if (sampleRate <= 0) throw new IllegalArgumentException("Invalid sample rate " + sampleRate);
        if (channelCount != 1 && channelCount != 2) {
            throw new IllegalArgumentException("Only mono and stereo audio is supported, got " + channelCount + " channels");
        }
        this.sampleRate = sampleRate;
        this.channelCount = channelCount;
        this.stereo = channelCount == 2;

        fftSize = sampleRate >= 32000 ? 2048 : sampleRate >= 16000 ? 1024 : 512;
        hopSize = fftSize / OVERLAP;
        overlapLength = fftSize - hopSize;
        binCount = fftSize / 2 + 1;
        fft = new FFT(fftSize);

        window = new float[fftSize];
        float windowPowerSum = 0f;
        for (int i = 0; i < fftSize; i++) {
            window[i] = (float) (0.5 - 0.5 * Math.cos(2.0 * Math.PI * i / fftSize));
            windowPowerSum += window[i] * window[i];
        }
        // Analysis and synthesis both use the window, so the overlapping squared windows have to sum up to 1
        synthesisScale = hopSize / windowPowerSum;

        inFifo = new float[channelCount][fftSize];
        outFifo = new float[channelCount][hopSize];
        outAccumulator = new float[channelCount][fftSize];
        dryDelay = new float[channelCount][fftSize];
        re = new float[fftSize];
        im = new float[fftSize];

        powerLeft = new float[binCount];
        powerRight = new float[binCount];
        powerCross = new float[binCount];
        background = new float[binCount];
        gains = new float[binCount];
        bandGains = new float[binCount];

        statsCoefficient = coefficient(STATS_SECONDS);
        backgroundFallCoefficient = coefficient(BACKGROUND_FALL_SECONDS);
        mixStep = 1f / (CROSSFADE_SECONDS * sampleRate);

        setStrength(strength);
        reset();
    }

    int getLatencyFrames() {
        return fftSize;
    }

    void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    void setStrength(MusicRemovalStrength strength) {
        if (strength == null) strength = MusicRemovalStrength.DEFAULT;
        if (strength == this.strength) return;
        this.strength = strength;

        backgroundRiseCoefficient = coefficient(strength.backgroundRiseSeconds);
        float highPass4 = pow4(strength.highPassHz);
        float lowPass4 = pow4(strength.lowPassHz);
        for (int k = 0; k < binCount; k++) {
            float frequency4 = pow4((float) k * sampleRate / fftSize);
            float highPass = frequency4 / (frequency4 + highPass4);
            float lowPass = lowPass4 / (frequency4 + lowPass4);
            bandGains[k] = highPass * lowPass;
        }
    }

    /** Clears all buffered audio, e.g. after a seek. */
    void reset() {
        for (int ch = 0; ch < channelCount; ch++) {
            Arrays.fill(inFifo[ch], 0f);
            Arrays.fill(outFifo[ch], 0f);
            Arrays.fill(outAccumulator[ch], 0f);
            Arrays.fill(dryDelay[ch], 0f);
        }
        resetAnalysis();
        rover = overlapLength;
        dryIndex = 0;
        // Silence is a valid processed state, so no warm-up is needed after a reset
        processing = enabled;
        warmupFramesLeft = 0;
        mix = enabled ? 1f : 0f;
    }

    /**
     * Processes interleaved samples in place.
     *
     * @param samples interleaved samples in the range [-1, 1]
     * @param offset  index of the first sample to process
     * @param frames  number of frames (samples per channel) to process
     */
    void process(float[] samples, int offset, int frames) {
        int index = offset;
        for (int frame = 0; frame < frames; frame++) {
            float target = enabled && warmupFramesLeft == 0 ? 1f : 0f;
            if (mix < target) {
                mix = Math.min(target, mix + mixStep);
            } else if (mix > target) {
                mix = Math.max(target, mix - mixStep);
            }

            int outIndex = rover - overlapLength;
            for (int ch = 0; ch < channelCount; ch++) {
                float input = samples[index];
                float dry = dryDelay[ch][dryIndex];
                float wet = outFifo[ch][outIndex];
                dryDelay[ch][dryIndex] = input;
                inFifo[ch][rover] = input;
                samples[index++] = dry + mix * (wet - dry);
            }

            if (++dryIndex == fftSize) dryIndex = 0;
            if (++rover == fftSize) {
                rover = overlapLength;
                processFrame();
            }
        }
    }

    private void processFrame() {
        if (enabled || mix > 0f) {
            if (!processing) {
                // Output of the previous frames is missing, fade in once the overlap is complete again
                processing = true;
                warmupFramesLeft = OVERLAP;
                resetAnalysis();
            }
            analyzeAndSynthesize();
            if (warmupFramesLeft > 0) warmupFramesLeft--;
        } else if (processing) {
            processing = false;
            for (int ch = 0; ch < channelCount; ch++) {
                Arrays.fill(outFifo[ch], 0f);
                Arrays.fill(outAccumulator[ch], 0f);
            }
        }

        for (int ch = 0; ch < channelCount; ch++) {
            System.arraycopy(inFifo[ch], hopSize, inFifo[ch], 0, overlapLength);
        }
    }

    private void analyzeAndSynthesize() {
        // Both channels are transformed at once: left in the real part, right in the imaginary part
        float[] left = inFifo[0];
        float[] right = stereo ? inFifo[1] : null;
        for (int i = 0; i < fftSize; i++) {
            re[i] = left[i] * window[i];
            im[i] = stereo ? right[i] * window[i] : 0f;
        }
        fft.forward(re, im);

        MusicRemovalStrength strength = this.strength;
        int mask = fftSize - 1;
        for (int k = 0; k < binCount; k++) {
            int mirror = (fftSize - k) & mask;
            float leftRe, leftIm, rightRe, rightIm;
            if (stereo) {
                leftRe = 0.5f * (re[k] + re[mirror]);
                leftIm = 0.5f * (im[k] - im[mirror]);
                rightRe = 0.5f * (im[k] + im[mirror]);
                rightIm = 0.5f * (re[mirror] - re[k]);
            } else {
                leftRe = rightRe = re[k];
                leftIm = rightIm = im[k];
            }

            float gain = 1f;
            if (stereo) {
                float pLeft = leftRe * leftRe + leftIm * leftIm;
                float pRight = rightRe * rightRe + rightIm * rightIm;
                float pCross = leftRe * rightRe + leftIm * rightIm;
                powerLeft[k] = flushTiny(powerLeft[k] + statsCoefficient * (pLeft - powerLeft[k]));
                powerRight[k] = flushTiny(powerRight[k] + statsCoefficient * (pRight - powerRight[k]));
                powerCross[k] = flushTiny(powerCross[k] + statsCoefficient * (pCross - powerCross[k]));

                // 1 when both channels carry the same signal (center), 0 for wide, one-sided or out of phase sound
                float center = 2f * powerCross[k] / (powerLeft[k] + powerRight[k] + EPSILON);
                center = Math.max(0f, Math.min(1f, center));
                float centerWeight = center;
                for (int i = 1; i < strength.centerExponent; i++) centerWeight *= center;
                gain = strength.sideGain + (1f - strength.sideGain) * centerWeight;
            }

            float midRe = 0.5f * (leftRe + rightRe);
            float midIm = 0.5f * (leftIm + rightIm);
            float pMid = midRe * midRe + midIm * midIm;
            float coefficient = pMid > background[k] ? backgroundRiseCoefficient : backgroundFallCoefficient;
            background[k] = flushTiny(background[k] + coefficient * (pMid - background[k]));
            float steadyGain = 1f - strength.overSubtraction * background[k] / (pMid + EPSILON);
            gain *= Math.max(strength.minGain, steadyGain);

            gain *= bandGains[k];
            gain = gains[k] = GAIN_SMOOTHING * gains[k] + (1f - GAIN_SMOOTHING) * gain;

            // The gain is real and symmetric, so applying it to the packed spectrum filters both channels
            re[k] *= gain;
            im[k] *= gain;
            if (mirror != k) {
                re[mirror] *= gain;
                im[mirror] *= gain;
            }
        }

        fft.inverse(re, im);

        for (int ch = 0; ch < channelCount; ch++) {
            float[] output = ch == 0 ? re : im;
            float[] accumulator = outAccumulator[ch];
            for (int i = 0; i < fftSize; i++) {
                accumulator[i] += output[i] * window[i] * synthesisScale;
            }
            System.arraycopy(accumulator, 0, outFifo[ch], 0, hopSize);
            System.arraycopy(accumulator, hopSize, accumulator, 0, overlapLength);
            Arrays.fill(accumulator, overlapLength, fftSize, 0f);
        }
    }

    private void resetAnalysis() {
        Arrays.fill(powerLeft, 0f);
        Arrays.fill(powerRight, 0f);
        Arrays.fill(powerCross, 0f);
        Arrays.fill(background, 0f);
        Arrays.fill(gains, 1f);
    }

    /** One-pole smoothing coefficient for a time constant, evaluated once per hop. */
    private float coefficient(float seconds) {
        return (float) (1.0 - Math.exp(-hopSize / (seconds * sampleRate)));
    }

    private static float pow4(float value) {
        float squared = value * value;
        return squared * squared;
    }

    private static float flushTiny(float value) {
        return Math.abs(value) < TINY ? 0f : value;
    }
}
