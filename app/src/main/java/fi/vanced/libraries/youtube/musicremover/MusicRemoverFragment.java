package fi.vanced.libraries.youtube.musicremover;

import static fi.vanced.libraries.youtube.musicremover.MusicRemoverSettings.PREFERENCES_KEY_BUTTON;
import static fi.vanced.libraries.youtube.musicremover.MusicRemoverSettings.PREFERENCES_KEY_ENABLED;
import static fi.vanced.libraries.youtube.musicremover.MusicRemoverSettings.PREFERENCES_KEY_STRENGTH;
import static fi.vanced.libraries.youtube.musicremover.MusicRemoverSettings.PREFERENCES_NAME;
import static pl.jakubweg.StringRef.str;

import android.app.Activity;
import android.content.Context;
import android.os.Bundle;
import android.preference.ListPreference;
import android.preference.Preference;
import android.preference.PreferenceCategory;
import android.preference.PreferenceFragment;
import android.preference.PreferenceScreen;
import android.preference.SwitchPreference;

import fi.vanced.utils.SharedPrefUtils;

public class MusicRemoverFragment extends PreferenceFragment {
    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getPreferenceManager().setSharedPreferencesName(PREFERENCES_NAME);

        final Activity context = this.getActivity();

        PreferenceScreen preferenceScreen = getPreferenceManager().createPreferenceScreen(context);
        setPreferenceScreen(preferenceScreen);

        // Music remover enable toggle
        {
            SwitchPreference preference = new SwitchPreference(context);
            preferenceScreen.addPreference(preference);
            preference.setKey(PREFERENCES_KEY_ENABLED);
            preference.setDefaultValue(false);
            preference.setChecked(SharedPrefUtils.getBoolean(context, PREFERENCES_NAME, PREFERENCES_KEY_ENABLED));
            preference.setTitle(str("vanced_music_remover_title"));
            preference.setSummaryOn(str("vanced_music_remover_summary_on"));
            preference.setSummaryOff(str("vanced_music_remover_summary_off"));
            preference.setOnPreferenceChangeListener((pref, newValue) -> {
                final boolean value = (Boolean) newValue;
                MusicRemover.onEnabledChange(value);
                return true;
            });
        }

        // Strength
        {
            ListPreference preference = new ListPreference(context);
            preferenceScreen.addPreference(preference);
            preference.setKey(PREFERENCES_KEY_STRENGTH);
            preference.setDefaultValue(MusicRemovalStrength.DEFAULT.name());
            preference.setTitle(str("vanced_music_remover_strength_title"));
            preference.setDialogTitle(str("vanced_music_remover_strength_title"));
            preference.setEntries(new String[]{
                    str("vanced_music_remover_strength_low"),
                    str("vanced_music_remover_strength_medium"),
                    str("vanced_music_remover_strength_high"),
            });
            preference.setEntryValues(new String[]{
                    MusicRemovalStrength.LOW.name(),
                    MusicRemovalStrength.MEDIUM.name(),
                    MusicRemovalStrength.HIGH.name(),
            });
            preference.setValue(MusicRemovalStrength.fromString(
                    SharedPrefUtils.getString(context, PREFERENCES_NAME, PREFERENCES_KEY_STRENGTH)).name());
            preference.setSummary(preference.getEntry());
            preference.setOnPreferenceChangeListener((pref, newValue) -> {
                final MusicRemovalStrength value = MusicRemovalStrength.fromString((String) newValue);
                MusicRemover.onStrengthChange(value);
                ListPreference listPreference = (ListPreference) pref;
                listPreference.setSummary(listPreference.getEntries()[listPreference.findIndexOfValue(value.name())]);
                return true;
            });
        }

        // Button under the player
        {
            SwitchPreference preference = new SwitchPreference(context);
            preferenceScreen.addPreference(preference);
            preference.setKey(PREFERENCES_KEY_BUTTON);
            preference.setDefaultValue(true);
            preference.setChecked(MusicRemover.isButtonVisible(context));
            preference.setTitle(str("vanced_music_remover_button_title"));
            preference.setSummary(str("vanced_music_remover_button_summary"));
        }

        // About category
        addAboutCategory(context, preferenceScreen);
    }

    private void addAboutCategory(Context context, PreferenceScreen screen) {
        PreferenceCategory category = new PreferenceCategory(context);
        screen.addPreference(category);
        category.setTitle(str("about"));

        {
            Preference preference = new Preference(context);
            screen.addPreference(preference);
            preference.setTitle(str("vanced_music_remover_about_title"));
            preference.setSummary(str("vanced_music_remover_about_summary"));
            preference.setSelectable(false);
        }
    }
}
