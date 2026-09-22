package fi.vanced.libraries.youtube.ui;

import static fi.razerman.youtube.XGlobals.debug;
import static pl.jakubweg.StringRef.str;

import android.content.Context;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Toast;

import fi.vanced.libraries.youtube.musicremover.MusicRemover;
import fi.vanced.utils.VancedUtils;

public class MusicRemoverButton extends SlimButton {
    public static final String TAG = "VI - MusicRemoverButton";

    public MusicRemoverButton(Context context, ViewGroup container) {
        super(context, container, SlimButton.SLIM_METADATA_BUTTON_ID, MusicRemover.isButtonVisible(context));

        initialize();
    }

    private void initialize() {
        this.button_text.setText(str("action_music_remover"));
        changeEnabled(MusicRemover.isEnabled);
    }

    public void changeEnabled(boolean enabled) {
        if (debug) {
            Log.d(TAG, "changeEnabled " + enabled);
        }
        int icon = VancedUtils.getIdentifier(enabled ? "vanced_yt_music_remover_on" : "vanced_yt_music_remover_off", "drawable");
        if (icon != 0) {
            this.button_icon.setImageResource(icon);
        }
    }

    @Override
    public void onClick(View view) {
        boolean enabled = !MusicRemover.isEnabled;
        MusicRemover.setEnabled(this.context, enabled);
        changeEnabled(enabled);

        String message;
        if (!enabled) {
            message = str("vanced_music_remover_disabled");
        } else if (MusicRemover.isProcessingAudio()) {
            message = str("vanced_music_remover_enabled");
        } else {
            message = str("vanced_music_remover_enabled_next_video");
        }
        Toast.makeText(this.context, message, Toast.LENGTH_SHORT).show();
    }
}
