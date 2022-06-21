package info.pml.cbass_monitor;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.os.IBinder;
import android.text.Spannable;
import android.text.SpannableStringBuilder;
import android.text.style.ForegroundColorSpan;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.Toolbar;

import androidx.activity.OnBackPressedCallback;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.drawable.DrawableCompat;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentManager;

import java.util.Timer;
import java.util.TimerTask;

import de.kai_morich.simple_bluetooth_le_terminal.SerialListener;
import de.kai_morich.simple_bluetooth_le_terminal.SerialService;
import de.kai_morich.simple_bluetooth_le_terminal.SerialSocket;
import de.kai_morich.simple_bluetooth_le_terminal.TextUtil;

/**
 * BLEFragment is designed to contain all the methods needed by any Fragment using Bluetooth
 * Low Energy (BLE).  It will have a basic view for testing purposes, but otherwise
 * it is not meant to be useful on its own.
 * The class relies heavily on the kai_morich bluetooth LE terminal example.
 */
public class BLEFragment extends Fragment implements ServiceConnection, SerialListener {

    private final String TAG = "BLEFragment";

    enum Connected {False, Pending, True}

    Menu menu;
    protected String deviceAddress;
    protected String deviceName;
    protected SerialService service;

    //Toolbar toolbar;
    MenuItem bleStatus;
    TextView diagnostics;

    Connected connected = Connected.False;
    private boolean initialStart = true;
    private final String newline = TextUtil.newline_crlf;

    // Ideally each subclass would have its own set of states. This
    // Can be done by overriding the variable, but it seems error prone.
    // Instead, list all states here, even though this seems like detail
    // that belongs in subclasses.
    enum ExpectBLEData {
        Nothing,
        Ack,
        Batch,
        StartMinutes,
        StartMinutes2,
        TimeOfDay,
        SetPoints,
        CurrentTemps
    }

    ExpectBLEData expectBLE = ExpectBLEData.Nothing;

    Timer connCheckTimer; // For noticing when the connection is dropped.


    /*
     * Lifecycle
     *    Not all calls are documented at https://developer.android.com/guide/fragments/lifecycle
     *    Instead see https://github.com/xxv/android-lifecycle
     */
    @SuppressWarnings("deprecation")
    // onAttach(context) was added with API 23. onAttach(activity) works for all API versions
    @Override
    public void onAttach(@NonNull Activity activity) {
        super.onAttach(activity);
        Log.d(TAG, "Binding in onAttach.");
        getActivity().bindService(new Intent(getActivity(), SerialService.class), this, Context.BIND_AUTO_CREATE);
    }

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setHasOptionsMenu(true);
        // Not recommended in most situations, but we need it for keeping the service connection.
        setRetainInstance(true);
        // TODO: exit if deviceAddress is null?
        // Maybe getActivity().getParentFragmentManager().beginTransaction().remove(this).commit();
        deviceAddress = getArguments().getString("device");


    }

    /*
     * UI
     */
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_ble, container, false);
        diagnostics = view.findViewById(R.id.diagnostics);
        //Log.d(TAG, "Toolbar found? " + (toolbar != null));
        // Button Actions (what to send)

        return view;
    }

    @Override
    public void onStart() {
        super.onStart();
        Log.d(TAG, "in onStart");
    }

    /**
     * This is the last _documented_ lifecycle step as a Fragment becomes active.
     * BUT onCreateOptionsMenu comes later!
     */
    @Override
    public void onResume() {
        super.onResume();
        Log.d(TAG, "in onResume");
        if (initialStart && service != null) {
            initialStart = false;
            getActivity().runOnUiThread(this::connect);
        }
        // Put the name if the connected CBASS at the top.
        if (deviceName != null) {
            ((AppCompatActivity) getActivity()).getSupportActionBar().setTitle(deviceName);
        }
        // The lines below were in onStart.  See if moving them here solves the icon tinting problem.
        if (service != null) {
            service.attach(this);
            // After rotations we may or may not be connected, and the icon states
            // are not set.
            if (!service.isConnected()) {
                connect();
            } else {
                connected = Connected.True;
                startUpdateTimer();
                updateButtons(true);
            }
        } else {
            getActivity().startService(new Intent(getActivity(), SerialService.class)); // prevents service destroy on unbind from recreated activity caused by orientation change
        }
    }

    @Override
    public void onCreateOptionsMenu(@NonNull Menu menu, MenuInflater inflater) {
        super.onCreateOptionsMenu(menu, inflater);
        inflater.inflate(R.menu.menu_ble_shared, menu);
        Log.d(TAG, "in onCreateOptionsMenu");
        this.menu = menu;
        // This method is not documented on the Fragment lifecycle page.
        // Originally checked for bleStatus of null, be it seems that we need a fresh copy
        // when switching between landscape and portrait!
        // A reliable-looking source says that all view references must be
        // set in onCreateView, but this icon doesn't exist at that point because it's
        // in the menu view.
        bleStatus = menu.findItem(R.id.ble_status);
        // Do we need to update even if new?  Probably.  else
        updateButtons();  // An existing button seems to lose its Tint.  Re-color it.
    }

    @Override
    public void onPause() {
        // Set app name back to default (from device name)
        ((AppCompatActivity) getActivity()).getSupportActionBar().setTitle(getResources().getString(R.string.app_name));
        // We may be running more than one timer if a paused fragment doesn't cancel its timers.
        eliminateTimer();
        super.onPause();
    }

    // This call may drift to any place before onDestroy!
    @Override
    public void onSaveInstanceState(@NonNull Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putSerializable("dev name", deviceName);
        outState.putSerializable("dev address", deviceAddress);
    }

    @Override
    public void onStop() {
        super.onStop();
        if (service != null && !getActivity().isChangingConfigurations())
            service.detach();
    }

    @Override
    public void onDestroy() {
        if (connected != Connected.False)
            disconnect();
        getActivity().stopService(new Intent(getActivity(), SerialService.class));
        super.onDestroy();
    }

    @Override
    public void onDetach() {
        Log.d(TAG, "UNBinding in onDetach.");
        try {
            getActivity().unbindService(this);
        } catch (Exception ignored) {
        }
        super.onDetach();
    }

    /*
     * End Lifecycle as documented in Fragment.
     *
     * Begin calls related to connecting and disonnecting to the service or a BLE device.
     */

    @Override
    public void onServiceConnected(ComponentName name, IBinder binder) {
        Log.d(TAG, "Getting service from binder in onServiceConnected.");
        service = ((SerialService.SerialBinder) binder).getService();
        service.attach(this);
        if (initialStart && isResumed()) {
            initialStart = false;
            getActivity().runOnUiThread(this::connect);
        }
    }

    @Override
    public void onServiceDisconnected(ComponentName name) {
        Log.d(TAG, "Setting service null in onServiceDisconnected.");
        service = null;
    }


    /**
     * Switch between fragments based on menu icons.  Note that only GraphFragment and MonitorFragment
     * are re-used and have restored state (aside from things done automatically in settings pages).
     *
     * @param item the menu icon touched
     * @return
     */
    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        int id = item.getItemId();
        // TODO use switch/case instead of if.
        if (id == R.id.ble_status) {
            if (connected == Connected.False) {
                Toast.makeText(getActivity(), "Trying to reconnect.", Toast.LENGTH_SHORT).show();
                connect();
            } else {
                // TODO: Connected may be true when icon shows false.  It's a bug elsewhere, but see if
                // we can just fix the icon.

                Toast.makeText(getActivity(), "You may select a new CBASS.", Toast.LENGTH_LONG).show();
                // If we don't disconnect, a new scan won't show the existing CBASS.
                disconnect();
                switchTo("DevicesFragment");
                /*
                Fragment fragment = new DevicesFragment();
                getParentFragmentManager().beginTransaction().replace(R.id.fragment, fragment, "DevicesFragment").addToBackStack(null).commit();
                 */
            }
            return true;
        } else if (id == R.id.monitor) {
            switchTo("MonitorFragment");
            return true;
        } else if (id == R.id.graph) {
            switchTo("GraphFragment");
            return true;
        } else if (id == R.id.CBASS_tests) {
            switchTo("TestCBASSFragment");
            return true;
        } else if (id == R.id.to_settings) {
            switchTo("AppSettingsFragment");
            return true;
        } else if (id == R.id.to_control) {
            switchTo("CBASSControlFragment");
            return true;
        } else {
            return super.onOptionsItemSelected(item);
        }
    }

    void switchTo(String fragName) {
        Log.d(TAG, "Switching to fragment " + fragName);

        // Just for debug, not used:
        FragmentManager fm = getParentFragmentManager();


        Fragment fragment = getParentFragmentManager().findFragmentByTag(fragName);
        if (fragment == null) {
            Bundle args = new Bundle();
            args.putString("device", deviceAddress);
            try {
                fragment = (Fragment) Class.forName("info.pml.cbass_monitor." + fragName).newInstance();
            } catch (IllegalAccessException e) {
                e.printStackTrace();
            } catch (java.lang.InstantiationException e) {
                e.printStackTrace();
            } catch (ClassNotFoundException e) {
                e.printStackTrace();
            }
            fragment.setArguments(args);
            getParentFragmentManager()
                    .beginTransaction()
                    .replace(R.id.fragment, fragment, fragName)
                    .addToBackStack(fragName)  // was null
                    .commit();
        } else {
            getParentFragmentManager()
                    .beginTransaction()
                    .replace(R.id.fragment, fragment, fragName)
                    .addToBackStack(fragName)  // was null
                    .commit();
        }
    }

    /*
     * Serial + UI
     */
    @SuppressLint("MissingPermission")
    void connect() {
        Log.d(TAG, "In connect()");
        // Try this - if we already have a connected service, just return.

        try {
            BluetoothAdapter bluetoothAdapter = BluetoothAdapter.getDefaultAdapter();
            BluetoothDevice device = bluetoothAdapter.getRemoteDevice(deviceAddress);
            // Put the name of the connected CBASS at the top.
            deviceName = device.getName();
            if (deviceName != null) {
                ((AppCompatActivity) getActivity()).getSupportActionBar().setTitle(deviceName);
            }
            if (!service.isConnected()) {
                Log.d(TAG, "In connect, connecting service.");
                connected = Connected.Pending;
                SerialSocket socket = new SerialSocket(getActivity().getApplicationContext(), device);
                service.connect(socket);
            } else {
                Log.d(TAG, "In connect, service already connected.");
                connected = Connected.True;
            }
        } catch (Exception e) {
            Log.d(TAG, "Exception in connect.");
            e.printStackTrace();
            onSerialConnectError(e);
        }
        Log.d(TAG, "Leaving connect()");
    }

    private void disconnect() {
        Log.d(TAG, "In disconnect");
        connected = Connected.False;
        // This was working well without the null check until
        // an "extra" connect call failed and had a Fatal exception
        if (service != null)  service.disconnect();
    }

    void send(String str, ExpectBLEData newX) {
        Log.d(TAG, "In send with " + str + " expecting " + newX.toString());
        // First be sure we are connected.
        if (connected == Connected.Pending) {
            // When a fragment is first started, it is easy to call for data
            // before the connection is complete, even if it is done in onResume or
            // possibly later.  This won't fix all cases, but see if a half-second
            // delay helps.
            try {
                Log.d(TAG, "Wait 500ms for pending connection.");
                Thread.sleep(500);
            } catch (InterruptedException e) {
                // Probably okay, since the interrupt is likely to be a move
                // to a different fragment or app shutdown.
                e.printStackTrace();
                // Drop through to the basic connection check, just in case.
            }
        }
        if(connected != Connected.True) {
            Log.e(TAG, "connected False in send.");
            // problem when called by Timer thread? Toast.makeText(getActivity(), "not connected", Toast.LENGTH_SHORT).show();
            return;
        }

        // Also, if another send is pending results, it is not safe to send another.
        // Wait a little, but if it's too long assume a failure.
        int tries = 10;
        while (tries > 0 && expectBLE != ExpectBLEData.Nothing) {
            tries--;
            try {
                Log.d(TAG, "wait 500ms for previous send to return");
                Thread.sleep(500);  // 5 seconds in 10 tries.
            } catch (InterruptedException e) {
                Log.d(TAG, "Interruped during wait for previous command. Not sending " + str);
                e.printStackTrace();
                return;
            }
        }
        if (expectBLE != ExpectBLEData.Nothing) {
            // This could be risky, but we can't let things hang forever.  Just be sure
            // no operations absolutely depend on a response.
            Log.i(TAG, "Old pending send did not clear.  Abandoning it.");
            //return;
        }
        // Okay to start the new send, with the new state specified.
        expectBLE = newX;

        try {
            String msg;
            byte[] data;
            msg = str;
            data = (str + newline).getBytes();
            SpannableStringBuilder spn = new SpannableStringBuilder(msg + '\n');
            spn.setSpan(new ForegroundColorSpan(getResources().getColor(R.color.colorSendText)), 0, spn.length(), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
            service.write(data);
            Log.d(TAG, "Sent " + str);
        } catch (Exception e) {
            onSerialIoError(e);
        }
    }

    /**
     * In this class anything received will simply be echoed to a diagnostic view.  Inheriting
     * classes should override this (and not call the parent method) to do something useful.
     *
     * There may be a future version of this method designed to collect long returns into a single
     * string.
     *
     * @param data  // Data received as bytes.
     */
    void receive(byte[] data) {
        String msg = new String(data);
        if(newline.equals(TextUtil.newline_crlf) && msg.length() > 0) {
            // don't show CR as ^M if directly before LF
            msg = msg.replace(TextUtil.newline_crlf, TextUtil.newline_lf);
        }
        diagnostics.append(msg);
    }

    /*
     * SerialListener
     */
    @Override
    public void onSerialConnect() {
        connected = Connected.True;
        Log.d(TAG, "connected set True in onSerialConnect");
        updateButtons(true);
        startUpdateTimer();
    }

    /**
     * Upon initial connection this starts a periodic check that things are okay.  It
     * gets shut down in onPause.  This is normally called 1) When a connection is made.
     * 2) When a Fragment resumes and already has a good connection (in onResume).
     */
    void startUpdateTimer() {

        // Set up a timer to notice if the connection is lost.  It is not meant to automatically
        // retry the connection, but only change appearance and activity of buttons.
        eliminateTimer();
        connCheckTimer = new Timer();
        TimerTask monTask = new TimerTask() {
            @Override
            public void run() {
                Log.d(TAG, "=================== TimerTask updating buttons.");
                updateButtons();
            }
        };
        // Check every 5 seconds.  If we do this by sending messages, it should be much slower.
        connCheckTimer.schedule(monTask, 1000, 8000);

    }
    void eliminateTimer() {
        if (connCheckTimer != null) {
            // Probably overkill, but be sure we're not getting excess timer tasks.
            connCheckTimer.cancel();
            connCheckTimer.purge();
            connCheckTimer = null;
        }
    }

    @Override
    public void onSerialConnectError(Exception e) {
        disconnect();
        updateButtons(false);


    }

    @Override
    public void onSerialRead(byte[] data) {
        receive(data);
    }

    @Override
    public void onSerialIoError(Exception e) {
        disconnect();
        updateButtons(false);
    }

    void updateButtons() {
        if (service == null) updateButtons(false);
        else {
            // Was just
            //updateButtons(service.isConnected());
            // Do some checks.
            if ((connected == Connected.True) != service.isConnected()) {
                Log.e(TAG, "Connected status from flag not what service reports.");
                if (service.isConnected()) {
                    // Assume it's just out of sync. Still a bug!
                    connected = Connected.True;
                }
            }
            // Use our flag, so if it's pending we show it as disconnected.
            updateButtons(connected == Connected.True);
        }
    }
    /**
     * Change color and activity status of all buttons affected by whether there is a current
     * connection.
     * @param isConnected
     */
    void updateButtons(boolean isConnected) {
        Log.d(TAG, "Updating top icons for BLE status. " + isConnected);
        if (bleStatus == null || bleStatus.getIcon() == null) {
            // Not ready - just exit for now.
            Log.d(TAG, "-- no bleStatus icon to update --");
            return;
        }
        Drawable nd = bleStatus.getIcon();
        Drawable wd = DrawableCompat.wrap(nd);
        if (isConnected) {
            DrawableCompat.setTint(wd, getContext().getResources().getColor(android.R.color.holo_blue_bright));
            Log.d(TAG, "-- BLUE --");
        } else {
            // Multiple ifs for debugging only...
            if (getContext() != null) {
                if (getContext().getResources() != null) {
                    DrawableCompat.setTint(wd, getContext().getResources().getColor(android.R.color.holo_orange_dark));
                    Log.d(TAG, "-- ORANGE --");
                }
            }
        }
        // Sometimes we get color, sometimes not.  See if this helps:
        // menu isn't always owned by the current view.  Can we go direct to the icon?
        // menu.findItem(R.id.ble_status).setIcon(wd);
        // but this version doesn't keep the color updated!

        /* XXX  turn off while debugging disappearance of UI after 2nd fragment switch!
        This WAS the problem.  There must be another way.
        try {
            bleStatus.setIcon(wd);
        } catch (Exception e) {
            Log.w(TAG, "Icon color setting called from wrong thread. Trying UI thread.");
            requireActivity().runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    bleStatus.setIcon(wd);
                }
            });
        }
         */
    }

}