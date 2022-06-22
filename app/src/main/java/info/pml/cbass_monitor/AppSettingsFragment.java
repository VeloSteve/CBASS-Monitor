package info.pml.cbass_monitor;

import android.content.SharedPreferences;
import android.os.Bundle;
import android.text.InputFilter;
import android.text.InputType;
import android.widget.EditText;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.preference.EditTextPreference;
import androidx.preference.PreferenceFragmentCompat;

public class AppSettingsFragment extends PreferenceFragmentCompat implements SharedPreferences.OnSharedPreferenceChangeListener {
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
        } else if (key.equals("connect_PIN")) {
            String pin = sharedPreferences.getString("connect_PIN", "");
            int len = pin.length();
            if (len < 4 || len > 15) {
                Toast.makeText(getActivity(), "CBASS only accepts PINS from 4 to 15 characters.", Toast.LENGTH_LONG).show();
            }
        }
    }

    @Override
    public void onResume() {
        super.onResume();
        getPreferenceScreen().getSharedPreferences()
                .registerOnSharedPreferenceChangeListener(this);
        // There seems to be no way to enforce PIN length from XML.  Trying this from
        // https://stackoverflow.com/questions/56128888/how-to-set-maximal-length-of-edittextpreference-of-androidx-library
        EditTextPreference pin = findPreference("connect_PIN");
        pin.setOnBindEditTextListener(new EditTextPreference.OnBindEditTextListener() {
            @Override
            public void onBindEditText(@NonNull EditText editText) {
                editText.setInputType(InputType.TYPE_CLASS_TEXT);
                editText.selectAll(); // select all text
                int maxLength = 15;
                editText.setFilters(new InputFilter[]{new InputFilter.LengthFilter(maxLength)});
            }
        });
    }

    @Override
    public void onPause() {
        getPreferenceScreen().getSharedPreferences().unregisterOnSharedPreferenceChangeListener(this);
        super.onPause();
    }
}