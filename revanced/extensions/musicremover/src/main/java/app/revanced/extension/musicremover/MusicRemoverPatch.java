package app.revanced.extension.musicremover;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.SharedPreferences;
import android.media.AudioTrack;
import android.util.Log;

import java.nio.ByteBuffer;
import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * Hooks called instead of the AudioTrack methods by the patched app.
 */
@SuppressWarnings("unused")
public final class MusicRemoverPatch {
    private static final String TAG = "revanced-music-remover";
    private static final String PREFERENCES_NAME = "revanced_music_remover";
    private static final String KEY_ENABLED = "enabled";
    private static final String KEY_STRENGTH = "strength";

    /** Marks tracks whose audio format cannot be processed. */
    private static final Object UNSUPPORTED = new Object();
    private static final Map<AudioTrack, Object> processors = Collections.synchronizedMap(new WeakHashMap<>());

    private static volatile boolean enabled = true;
    private static volatile MusicRemovalStrength strength = MusicRemovalStrength.DEFAULT;

    static {
        SharedPreferences preferences = getPreferences(getApplicationContext());
        if (preferences != null) {
            enabled = preferences.getBoolean(KEY_ENABLED, true);
            strength = MusicRemovalStrength.fromString(preferences.getString(KEY_STRENGTH, null));
        }
    }

    private MusicRemoverPatch() {
    }

    /**
     * Injection point. Replaces {@link AudioTrack#write(ByteBuffer, int, int)}.
     */
    public static int write(AudioTrack track, ByteBuffer buffer, int sizeInBytes, int writeMode) {
        TrackProcessor processor = getProcessor(track);
        if (processor == null) {
            return track.write(buffer, sizeInBytes, writeMode);
        }

        try {
            return processor.write(buffer, sizeInBytes, writeMode, enabled, strength, track::write);
        } catch (Exception ex) {
            Log.e(TAG, "Failed to remove music, playing the original audio", ex);
            processors.put(track, UNSUPPORTED);
            return track.write(buffer, sizeInBytes, writeMode);
        }
    }

    /**
     * Injection point. Replaces {@link AudioTrack#flush()}.
     */
    public static void flush(AudioTrack track) {
        track.flush();
        Object processor = processors.get(track);
        if (processor instanceof TrackProcessor) {
            ((TrackProcessor) processor).reset();
        }
    }

    /**
     * Injection point. Replaces {@link AudioTrack#release()}.
     */
    public static void release(AudioTrack track) {
        processors.remove(track);
        track.release();
    }

    public static boolean isEnabled() {
        return enabled;
    }

    public static MusicRemovalStrength getStrength() {
        return strength;
    }

    public static void setEnabled(Context context, boolean enabled) {
        MusicRemoverPatch.enabled = enabled;
        SharedPreferences preferences = getPreferences(context);
        if (preferences != null) preferences.edit().putBoolean(KEY_ENABLED, enabled).apply();
    }

    public static void setStrength(Context context, MusicRemovalStrength strength) {
        MusicRemoverPatch.strength = strength;
        SharedPreferences preferences = getPreferences(context);
        if (preferences != null) preferences.edit().putString(KEY_STRENGTH, strength.name()).apply();
    }

    private static TrackProcessor getProcessor(AudioTrack track) {
        Object processor = processors.get(track);
        if (processor == null) {
            // Tracks that started while turned off keep playing untouched, so they are never delayed.
            // Tracks being processed keep running while turned off, so switching back on is seamless.
            if (!enabled) return null;
            processor = createProcessor(track);
            processors.put(track, processor);
        }
        return processor instanceof TrackProcessor ? (TrackProcessor) processor : null;
    }

    private static Object createProcessor(AudioTrack track) {
        try {
            int encoding = track.getAudioFormat();
            int channelCount = track.getChannelCount();
            if (!TrackProcessor.isSupported(encoding, channelCount)) {
                Log.i(TAG, "Unsupported audio format: encoding " + encoding + ", " + channelCount + " channels");
                return UNSUPPORTED;
            }
            return new TrackProcessor(track.getSampleRate(), channelCount, encoding, enabled, strength);
        } catch (Exception ex) {
            Log.e(TAG, "Failed to set up music removal", ex);
            return UNSUPPORTED;
        }
    }

    private static SharedPreferences getPreferences(Context context) {
        if (context == null) return null;
        return context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE);
    }

    @SuppressLint("PrivateApi")
    private static Context getApplicationContext() {
        try {
            return (Context) Class.forName("android.app.ActivityThread")
                    .getMethod("currentApplication")
                    .invoke(null);
        } catch (Exception ex) {
            Log.e(TAG, "Failed to get the application context", ex);
            return null;
        }
    }
}
