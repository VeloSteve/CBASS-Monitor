package info.pml.cbass_monitor;

import static android.view.View.GONE;
import static android.view.View.VISIBLE;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.AlertDialog;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.location.LocationManager;
import android.os.AsyncTask;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import androidx.annotation.NonNull;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentManager;
import androidx.fragment.app.ListFragment;
import androidx.preference.PreferenceManager;

import android.util.Log;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;

import java.util.ArrayList;


/**
 * show list of BLE devices
 */
public class DevicesFragment extends ListFragment {

    private final String TAG = "DevicesFragment";
    private enum ScanState { NONE, LE_SCAN, DISCOVERY, DISCOVERY_FINISHED }
    private ScanState scanState = ScanState.NONE;
    private static final long LE_SCAN_PERIOD = 10000; // similar to bluetoothAdapter.startDiscovery
    private int cbassCount = 0; // So we can put non-CBASS named items before unnamed items.
    private final Handler leScanStopHandler = new Handler();
    private final BluetoothAdapter.LeScanCallback leScanCallback;
    private final BroadcastReceiver discoveryBroadcastReceiver;
    private final IntentFilter discoveryIntentFilter;

    private Menu menu;
    private BluetoothAdapter bluetoothAdapter;
    private final ArrayList<BluetoothDevice> listItems = new ArrayList<BluetoothDevice>();
    private ArrayAdapter<BluetoothDevice> listAdapter;

    // Messing with the UI:
    ImageView iconView;

    public DevicesFragment() {
        leScanCallback = (device, rssi, scanRecord) -> {
            if(device != null && getActivity() != null) {
                getActivity().runOnUiThread(() -> { updateScan(device); });
            }
        };
        discoveryBroadcastReceiver = new BroadcastReceiver() {
            @SuppressLint("MissingPermission")
            @Override
            public void onReceive(Context context, Intent intent) {
                if(BluetoothDevice.ACTION_FOUND.equals(intent.getAction())) {
                    BluetoothDevice device = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
                    if(device.getType() != BluetoothDevice.DEVICE_TYPE_CLASSIC && getActivity() != null) {
                        getActivity().runOnUiThread(() -> updateScan(device));
                    }
                }
                if(BluetoothAdapter.ACTION_DISCOVERY_FINISHED.equals(intent.getAction())) {
                    scanState = ScanState.DISCOVERY_FINISHED; // don't cancel again
                    stopScan();
                }
            }
        };
        discoveryIntentFilter = new IntentFilter();
        discoveryIntentFilter.addAction(BluetoothDevice.ACTION_FOUND);
        discoveryIntentFilter.addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED);
    }

    /*
     * UI.  First use the list fragment layout mentioned in docs to be sure the original
     * function is the same.  Then add...
     * Note that doing this disables setEmptyText, so I'll have to make a substitute.
     */
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        View view = super.onCreateView(inflater,container,savedInstanceState);

        View ev = inflater.inflate(R.layout.custom_empty_view, container, false);
        iconView = ev.findViewById(R.id.mpty);
        //FrameLayout existing= (FrameLayout)view;
        FrameLayout innerFrame = (FrameLayout)(((FrameLayout)view).getChildAt(1));


        // Aargh.  Can't add to vg until it's removed from the useless but required container it comes in.
        ((LinearLayout)iconView.getParent()).removeView(iconView);
        innerFrame.addView(iconView );

        return view;
    }

    public void setMyEmptyText(CharSequence text) {
        //myEmptyText.setText(text);
        setEmptyText(text);
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setHasOptionsMenu(true);
        if(getActivity().getPackageManager().hasSystemFeature(PackageManager.FEATURE_BLUETOOTH))
            bluetoothAdapter = BluetoothAdapter.getDefaultAdapter();
        // Used in listAdapter.getView()
        SharedPreferences sp = PreferenceManager.getDefaultSharedPreferences(getContext());

        listAdapter = new ArrayAdapter<BluetoothDevice>(getActivity(), 0, listItems) {
            @SuppressLint("MissingPermission")
            @NonNull
            @Override
            public View getView(int position, View convertView, @NonNull ViewGroup parent) {
                BluetoothDevice device = listItems.get(position);

                // Try ignoring convertView and just making a fresh View every time.  Docs don't explain the difference.
                View view = getActivity().getLayoutInflater().inflate(R.layout.device_list_item, parent, false);

                // Returning a null view causes trouble.  Can we make it invisible?
                // GONE still leaves empty spaces in the list.
                //if (device.getName() == null || device.getName().isEmpty()) {
                //    view.setVisibility(View.GONE);
                //    return view;  // Return now so it doesn't contain the TextViews.
                //}

                // Preferences determine whether to show non-CBASS devices.
                //SharedPreferences sp = PreferenceManager.getDefaultSharedPreferences(getContext());
                String style = sp.getString("device_style", "named");
                TextView text1 = view.findViewById(R.id.text1);
                TextView text2 = view.findViewById(R.id.text2);
                String dn = device.getName(); // Not used, but make obvious for debugging.
                if(device.getName() == null || device.getName().isEmpty()) {
                    // Always hide unnamed devices.
                    //text1.setText("<unnamed>");
                    //text1.setText("");
                    //text2.setText("");
                    //view.setVisibility(View.GONE);
                    Log.d(TAG, "hide nameless at " + position);
                    text1.setVisibility(GONE);
                    text2.setVisibility(GONE);
               } else if (style.equals("CBASS") && !device.getName().contains("CBASS")) {
                    // If we require CBASS in the name, hide anything else.
                    Log.d(TAG, "hide NON");
                    text1.setVisibility(GONE);
                    text2.setVisibility(GONE);
                } else if (style.equals("autoconnect") && device.getName().contains("CBASS")) {
                    Log.d(TAG, "Moving on with first CBASS.  Is not returning okay?");
                    useThisDevice(device);
                } else {
                    Log.d(TAG, "show dev at " + position);
                    text1.setText(device.getName());  // Learn: appending a string appears in device list
                    text2.setText(device.getAddress()); // Originally was outside the if/else
                }
                return view;
            }
        };
    }

    @Override
    public void onActivityCreated(Bundle savedInstanceState) {
        super.onActivityCreated(savedInstanceState);
        setListAdapter(null);
        View header = getActivity().getLayoutInflater().inflate(R.layout.device_list_header, null, false);
        getListView().addHeaderView(header, null, false);
        setMyEmptyText("initializing...");
        ((TextView) getListView().getEmptyView()).setTextSize(18);
        setListAdapter(listAdapter);
    }

    @Override
    public void onCreateOptionsMenu(@NonNull Menu menu, MenuInflater inflater) {
        inflater.inflate(R.menu.menu_devices, menu);
        this.menu = menu;
        if (bluetoothAdapter == null) {
            menu.findItem(R.id.bt_settings).setEnabled(false);
            menu.findItem(R.id.ble_scan).setEnabled(false);
        } else if(!bluetoothAdapter.isEnabled()) {
            menu.findItem(R.id.ble_scan).setEnabled(false);
        }
    }

    @Override
    public void onResume() {
        super.onResume();
        Log.d(TAG, "registering receiver");
        getActivity().registerReceiver(discoveryBroadcastReceiver, discoveryIntentFilter);
        if(bluetoothAdapter == null) {
            setMyEmptyText("<bluetooth LE not supported>");
        } else if(!bluetoothAdapter.isEnabled()) {
            setMyEmptyText("<bluetooth is disabled>");
            if (menu != null) {
                listItems.clear();
                listAdapter.notifyDataSetChanged();
                menu.findItem(R.id.ble_scan).setEnabled(false);
            }
        } else {
            setMyEmptyText("CBASS Monitor\n\n\n\n\n\n<Use SCAN to find devices>");
            if (menu != null)
                menu.findItem(R.id.ble_scan).setEnabled(true);
        }
        // When we back up to this fragment we don't want the decorative image on top of the list.
        if (!listItems.isEmpty()) {
            iconView.setVisibility(GONE);
        }

    }

    @Override
    public void onPause() {
        super.onPause();
        stopScan();
        Log.d(TAG, "UNregistering receiver");
        getActivity().unregisterReceiver(discoveryBroadcastReceiver);
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        menu = null;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        int id = item.getItemId();
        if (id == R.id.ble_scan) {
            startScan();
            return true;
        } else if (id == R.id.ble_scan_stop) {
            stopScan();
            return true;
        } else if (id == R.id.bt_settings) {
            Intent intent = new Intent();
            intent.setAction(android.provider.Settings.ACTION_BLUETOOTH_SETTINGS);
            startActivity(intent);
            return true;
        } else {
            return super.onOptionsItemSelected(item);
        }
    }

    @SuppressLint("StaticFieldLeak") // AsyncTask needs reference to this fragment
    private void startScan() {
        if(scanState != ScanState.NONE)
            return;
        scanState = ScanState.LE_SCAN;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (getActivity().checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                scanState = ScanState.NONE;
                AlertDialog.Builder builder = new AlertDialog.Builder(getActivity());
                builder.setTitle(R.string.location_permission_title);
                builder.setMessage(R.string.location_permission_message);
                builder.setPositiveButton(android.R.string.ok,
                        (dialog, which) -> requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION}, 0));
                builder.show();
                return;
            }
            LocationManager locationManager = (LocationManager) getActivity().getSystemService(Context.LOCATION_SERVICE);
            boolean         locationEnabled = false;
            try {
                locationEnabled = locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER);
            } catch(Exception ignored) {}
            try {
                locationEnabled |= locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER);
            } catch(Exception ignored) {}
            if(!locationEnabled)
                scanState = ScanState.DISCOVERY;
            // Starting with Android 6.0 a bluetooth scan requires ACCESS_COARSE_LOCATION permission, but that's not all!
            // LESCAN also needs enabled 'location services', whereas DISCOVERY works without.
            // Most users think of GPS as 'location service', but it includes more, as we see here.
            // Instead of asking the user to enable something they consider unrelated,
            // we fall back to the older API that scans for bluetooth classic _and_ LE
            // sometimes the older API returns less results or slower
        }
        cbassCount = 0;
        listItems.clear();
        listAdapter.notifyDataSetChanged();
        setMyEmptyText("<scanning...>");
        menu.findItem(R.id.ble_scan).setVisible(false);
        menu.findItem(R.id.ble_scan_stop).setVisible(true);
        if(scanState == ScanState.LE_SCAN) {
            leScanStopHandler.postDelayed(this::stopScan, LE_SCAN_PERIOD);
            new AsyncTask<Void, Void, Void>() {
                @Override
                protected Void doInBackground(Void[] params) {
                    bluetoothAdapter.startLeScan(null, leScanCallback);
                    return null;
                }
            }.execute(); // start async to prevent blocking UI, because startLeScan sometimes take some seconds
        } else {
            bluetoothAdapter.startDiscovery();
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        // ignore requestCode as there is only one in this fragment
        if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            new Handler(Looper.getMainLooper()).postDelayed(this::startScan,1); // run after onResume to avoid wrong empty-text
        } else {
            AlertDialog.Builder builder = new AlertDialog.Builder(getActivity());
            builder.setTitle(getText(R.string.location_denied_title));
            builder.setMessage(getText(R.string.location_denied_message));
            builder.setPositiveButton(android.R.string.ok, null);
            builder.show();
        }
    }

    private void updateScan(BluetoothDevice device) {
        if(scanState == ScanState.NONE) return;
        if(!listItems.contains(device)) {
            Log.d("DEVICES", "got one ");
            iconView.setVisibility(GONE);
            // Look at the device names (and UUIDs?) to put CBASS at the top.
            // There seem to be multiple calls per device, so it's best to add non-CBASS
            // devices rather than omit them completely.
            // The original example code had a sort, but it doesn't seem important, and it
            // would make it harder to keep CBASS at the top.
            if (device.getName() != null && device.getName().contains("CBASS")) {
                Log.d("DEVICES", "Adding CBASS");
                cbassCount++;
                listItems.add(0, device);
                //Collections.sort(listItems, DevicesFragment::compareTo);
            } else  {
                // Lesson: it is a bad idea to filter out unwanted devices here, because
                // the system keeps trying to add them for a while, possibly prolonging
                // the scan.  They can be filtered from view in listAdapter getView.
                // Also try putting unnamed devices last.
                if (device.getName() == null || device.getName().isEmpty()) {
                    Log.d("DEVICES", "Adding unnamed");
                    listItems.add(device);  // Last
                } else {
                    Log.d("DEVICES", "Adding non-CBASS");
                    listItems.add(cbassCount, device);  // After cbass, before unnamed
                }
                //Collections.sort(listItems, DevicesFragment::compareTo);
            }
            listAdapter.notifyDataSetChanged();
        }
    }

    private void stopScan() {
        if(scanState == ScanState.NONE)
            return;
        setMyEmptyText("<no bluetooth devices found>");
        if (listItems.size() == 0) iconView.setVisibility(VISIBLE);

        if(menu != null) {
            menu.findItem(R.id.ble_scan).setVisible(true);
            menu.findItem(R.id.ble_scan_stop).setVisible(false);
        }
        switch(scanState) {
            case LE_SCAN:
                leScanStopHandler.removeCallbacks(this::stopScan);
                bluetoothAdapter.stopLeScan(leScanCallback);
                break;
            case DISCOVERY:
                bluetoothAdapter.cancelDiscovery();
                break;
            default:
                // already canceled
        }
        scanState = ScanState.NONE;

    }

    @Override
    public void onListItemClick(@NonNull ListView l, @NonNull View v, int position, long id) {
        stopScan();
        BluetoothDevice device = listItems.get(position - 1);
        useThisDevice(device);
    }

    private void useThisDevice(BluetoothDevice device) {
        /* creates duplicates?
        Bundle args = new Bundle();
        args.putString("device", device.getAddress());
        Fragment fragment = new MonitorFragment();
        fragment.setArguments(args);
        getParentFragmentManager().beginTransaction().replace(R.id.fragment, fragment, "monitor").addToBackStack(null).commit();
         */

        Log.d(TAG, "Going to graph with newly chosen device.");
        Fragment fragment = getParentFragmentManager().findFragmentByTag("MonitorFragment");

        // XXX for debug only:
        FragmentManager fm = getParentFragmentManager();

        if (fragment == null) {
            Bundle args = new Bundle();
            args.putString("device", device.getAddress());
            fragment = new MonitorFragment();
            fragment.setArguments(args);
            getParentFragmentManager()
                    .beginTransaction()
                    .replace(R.id.fragment, fragment, "MonitorFragment")
                    .addToBackStack("MonitorFragment")
                    .commit();
        } else {
            getParentFragmentManager()
                    .beginTransaction()
                    .replace(R.id.fragment, fragment, "MonitorFragment")
                    .commit();
        }
    }

    /**
     * sort by name, then address. sort named devices first
     */
    static int compareTo(BluetoothDevice a, BluetoothDevice b) {
        boolean aValid = a.getName()!=null && !a.getName().isEmpty();
        boolean bValid = b.getName()!=null && !b.getName().isEmpty();
        if(aValid && bValid) {
            int ret = a.getName().compareTo(b.getName());
            if (ret != 0) return ret;
            return a.getAddress().compareTo(b.getAddress());
        }
        if(aValid) return -1;
        if(bValid) return +1;
        return a.getAddress().compareTo(b.getAddress());
    }
}