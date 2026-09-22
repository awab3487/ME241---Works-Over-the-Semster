package app.revanced.extension.musicremover;

/**
 * Tuning presets for {@link VoiceIsolator}. Higher strengths remove more music
 * at the cost of voices sounding thinner.
 */
public enum MusicRemovalStrength {
    //   centerExponent, sideGain, overSubtraction, minGain, backgroundRiseSeconds, highPassHz, lowPassHz
    LOW(1, 0.30f, 1.0f, 0.35f, 2.0f, 60f, 12000f),
    MEDIUM(2, 0.12f, 1.5f, 0.15f, 1.2f, 90f, 9000f),
    HIGH(3, 0.03f, 2.2f, 0.05f, 0.7f, 120f, 7000f);

    public static final MusicRemovalStrength DEFAULT = MEDIUM;

    /** How sharply sound that is not in the center of the stereo image is removed. */
    final int centerExponent;
    /** Gain kept for sound that is fully outside of the stereo center. */
    final float sideGain;
    /** How aggressively the steady (sustained) background is subtracted. */
    final float overSubtraction;
    /** Lowest gain the steady-background suppression may apply. */
    final float minGain;
    /** How long a sound has to be sustained before it is treated as background music. */
    final float backgroundRiseSeconds;
    /** Frequencies below this (bass, kick drums) are attenuated. */
    final float highPassHz;
    /** Frequencies above this (cymbals, hi-hats) are attenuated. */
    final float lowPassHz;

    MusicRemovalStrength(int centerExponent, float sideGain, float overSubtraction, float minGain,
                         float backgroundRiseSeconds, float highPassHz, float lowPassHz) {
        this.centerExponent = centerExponent;
        this.sideGain = sideGain;
        this.overSubtraction = overSubtraction;
        this.minGain = minGain;
        this.backgroundRiseSeconds = backgroundRiseSeconds;
        this.highPassHz = highPassHz;
        this.lowPassHz = lowPassHz;
    }

    public static MusicRemovalStrength fromString(String value) {
        if (value == null) return DEFAULT;
        for (MusicRemovalStrength strength : values()) {
            if (strength.name().equalsIgnoreCase(value)) return strength;
        }
        return DEFAULT;
    }
}
