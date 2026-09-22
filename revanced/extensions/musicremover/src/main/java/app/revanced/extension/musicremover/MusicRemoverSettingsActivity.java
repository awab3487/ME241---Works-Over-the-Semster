package app.revanced.extension.musicremover;

import android.app.Activity;
import android.app.AlertDialog;
import android.os.Bundle;

/**
 * Dialog to choose how strongly music is removed, or to turn the music remover off.
 * Opened by long pressing the Quick Settings tile.
 */
@SuppressWarnings("unused")
public class MusicRemoverSettingsActivity extends Activity {
    private static final MusicRemovalStrength[] STRENGTHS = MusicRemovalStrength.values();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        String[] items = new String[STRENGTHS.length + 1];
        items[0] = Strings.off();
        for (int i = 0; i < STRENGTHS.length; i++) {
            items[i + 1] = Strings.strengthDescription(STRENGTHS[i]);
        }
        int checked = MusicRemoverPatch.isEnabled() ? MusicRemoverPatch.getStrength().ordinal() + 1 : 0;

        new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle(Strings.title())
                .setSingleChoiceItems(items, checked, (dialog, which) -> {
                    if (which == 0) {
                        MusicRemoverPatch.setEnabled(this, false);
                    } else {
                        MusicRemoverPatch.setStrength(this, STRENGTHS[which - 1]);
                        MusicRemoverPatch.setEnabled(this, true);
                    }
                    dialog.dismiss();
                })
                .setOnDismissListener(dialog -> finish())
                .show();
    }
}
