package app.revanced.extension.musicremover;

import android.os.Build;
import android.service.quicksettings.Tile;
import android.service.quicksettings.TileService;

/**
 * Quick Settings tile to turn the music remover on and off while watching.
 * Long pressing the tile opens {@link MusicRemoverSettingsActivity}.
 */
@SuppressWarnings("unused")
public class MusicRemoverTileService extends TileService {
    @Override
    public void onStartListening() {
        updateTile();
    }

    @Override
    public void onClick() {
        MusicRemoverPatch.setEnabled(this, !MusicRemoverPatch.isEnabled());
        updateTile();
    }

    private void updateTile() {
        Tile tile = getQsTile();
        if (tile == null) return;

        boolean enabled = MusicRemoverPatch.isEnabled();
        tile.setState(enabled ? Tile.STATE_ACTIVE : Tile.STATE_INACTIVE);
        tile.setLabel(Strings.title());
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            tile.setSubtitle(enabled ? Strings.strength(MusicRemoverPatch.getStrength()) : Strings.off());
        }
        tile.updateTile();
    }
}
