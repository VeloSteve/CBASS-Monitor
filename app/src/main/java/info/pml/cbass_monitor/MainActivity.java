package info.pml.cbass_monitor;

import android.os.Bundle;
import android.util.Log;

import androidx.fragment.app.FragmentManager;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;


public class MainActivity extends AppCompatActivity implements FragmentManager.OnBackStackChangedListener {
    public Toolbar toolbar;
    private final String TAG = "MainActivity";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        getSupportFragmentManager().addOnBackStackChangedListener(this);
        if (savedInstanceState == null)
            getSupportFragmentManager().beginTransaction().add(R.id.fragment, new info.pml.cbass_monitor.DevicesFragment(), "DevicesFragment").addToBackStack("DevicesFragment").commit();
        else
            onBackStackChanged();
    }

    @Override
    public void onBackStackChanged() {
        getSupportActionBar().setDisplayHomeAsUpEnabled(getSupportFragmentManager().getBackStackEntryCount()>0);
    }

    /*
    Can we just rely on super methods?  The back button is causing crashes.
    No.  With out the next two methods the back button does nothing.
     */
    @Override
    public boolean onSupportNavigateUp() {
        onBackPressed();
        return true;
    }

    // We sometimes get an extra copy of a fragment (e.g. GraphFragment).  Does this help?
    // From https://stackoverflow.com/questions/5448653/how-to-implement-onbackpressed-in-fragments
    @Override
    public void onBackPressed() {
        FragmentManager fm = getSupportFragmentManager();
        int count = fm.getBackStackEntryCount();

        if (count == 0) {
            Log.d(TAG, "Calling super.onBackPressed");
            super.onBackPressed();
        } else if (count == 1 && fm.findFragmentByTag("DevicesFragment") != null) {
            // do nothing - this is the start fragment
        } else {
            Log.d(TAG, "Popping back stack w/o calling super.");
            fm.popBackStack();
        }
    }


}
