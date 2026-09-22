package fi.vanced.libraries.youtube.musicremover;

import static fi.razerman.youtube.XGlobals.debug;
import static fi.vanced.libraries.youtube.musicremover.MusicRemoverSettings.PREFERENCES_KEY_BUTTON;
import static fi.vanced.libraries.youtube.musicremover.MusicRemoverSettings.PREFERENCES_KEY_ENABLED;
import static fi.vanced.libraries.youtube.musicremover.MusicRemoverSettings.PREFERENCES_KEY_STRENGTH;
import static fi.vanced.libraries.youtube.musicremover.MusicRemoverSettings.PREFERENCES_NAME;

import android.content.Context;
import android.util.Log;

import com.google.android.apps.youtube.app.YouTubeTikTokRoot_Application;

import java.util.concurrent.TimeUnit;

import fi.vanced.utils.SharedPrefUtils;

public class MusicRemover {
    public static final String TAG = "VI - MusicRemover";
    private static final long PROCESSING_TIMEOUT_NANOS = TimeUnit.SECONDS.toNanos(1);

    public static volatile boolean isEnabled;
    public static volatile MusicRemovalStrength strength = MusicRemovalStrength.DEFAULT;
    private static volatile long lastProcessedNanos;

    static {
        Context context = YouTubeTikTokRoot_Application.getAppContext();
        if (context != null) {
            isEnabled = SharedPrefUtils.getBoolean(context, PREFERENCES_NAME, PREFERENCES_KEY_ENABLED, false);
            strength = MusicRemovalStrength.fromString(SharedPrefUtils.getString(context, PREFERENCES_NAME, PREFERENCES_KEY_STRENGTH));
        }
    }

    public static void onEnabledChange(boolean enabled) {
        if (debug) {
            Log.d(TAG, "onEnabledChange - " + enabled);
        }
        isEnabled = enabled;
    }

    public static void onStrengthChange(MusicRemovalStrength newStrength) {
        if (debug) {
            Log.d(TAG, "onStrengthChange - " + newStrength);
        }
        strength = newStrength;
    }

    /** Changes and persists the enabled state, e.g. from the button under the player. */
    public static void setEnabled(Context context, boolean enabled) {
        onEnabledChange(enabled);
        SharedPrefUtils.saveBoolean(context, PREFERENCES_NAME, PREFERENCES_KEY_ENABLED, enabled);
    }

    public static boolean isButtonVisible(Context context) {
        return SharedPrefUtils.getBoolean(context, PREFERENCES_NAME, PREFERENCES_KEY_BUTTON, true);
    }

    /**
     * Whether the audio of the current video is running through a {@link MusicRemovalAudioProcessor}.
     * If not, enabling the music remover only takes effect once the player sets up its audio again
     * (next video or seek).
     */
    public static boolean isProcessingAudio() {
        long last = lastProcessedNanos;
        return last != 0 && System.nanoTime() - last < PROCESSING_TIMEOUT_NANOS;
    }

    static void onAudioProcessed() {
        lastProcessedNanos = System.nanoTime();
    }
}
