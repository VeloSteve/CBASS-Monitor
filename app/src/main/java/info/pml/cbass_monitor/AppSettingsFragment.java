package info.pml.cbass_monitor;

import android.content.SharedPreferences;
import android.os.Bundle;
import android.util.Log;
import android.widget.Toast;

import androidx.preference.Preference;
import androidx.preference.PreferenceFragmentCompat;

public class AppSettingsFragment extends PreferenceFragmentCompat implements SharedPreferences.OnSharedPreferenceChangeListener {
    private final String TAG = "AppSettingsFragment";
    @Override
    public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
        setPreferencesFromResource(R.xml.root_preferences, rootKey);
    }

    @Override
    public void onSharedPreferenceChanged(SharedPreferences sharedPreferences, String key)
    {
        if (key.equals("displayed_history")) {
            // Wanted to auto-edit one when the other changes, but it's not working.  A good
            // solution may require a handmade preferences screen.
            // The two preferences are both ListPreferences, so I have to work with Strings
            // even though integers make more sense.
            String disp = sharedPreferences.getString("displayed_history", "60");
            String stored = sharedPreferences.getString("maximum_history", "60");
            if (Integer.parseInt(disp) > Integer.parseInt(stored)) {
                Toast.makeText(getActivity(), "Please keep the stored time at least as large as the graphed time.", Toast.LENGTH_LONG).show();
            }
        }
    }

    @Override
    public void onResume() {
        super.onResume();
        getPreferenceScreen().getSharedPreferences()
                .registerOnSharedPreferenceChangeListener(this);
    }

    @Override
    public void onPause() {
        getPreferenceScreen().getSharedPreferences().unregisterOnSharedPreferenceChangeListener(this);
        super.onPause();
    }
}