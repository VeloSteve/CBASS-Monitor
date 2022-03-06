package info.pml.cbass_monitor;

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

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.drawable.DrawableCompat;
import androidx.fragment.app.Fragment;

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

    private String TAG = "BLEFragment";
    enum Connected { False, Pending, True }

    private String deviceAddress;
    private String deviceName;
    private SerialService service;

    MenuItem bleStatus;
    TextView diagnostics;

    Connected connected = Connected.False;
    private boolean initialStart = true;
    private final String newline = TextUtil.newline_crlf;

    private String msgBuffer = "";

    enum ExpectBLEData {
        Nothing,
        Batch
    }
    ExpectBLEData expectBLE = ExpectBLEData.Nothing;

    Timer connCheckTimer; // For noticing when the connection is dropped.

    /*
     * Lifecycle
     */
    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setHasOptionsMenu(true);
        setRetainInstance(true);
        // TODO: exit if deviceAddress is null?
        // Maybe getActivity().getFragmentManager().beginTransaction().remove(this).commit();
        deviceAddress = getArguments().getString("device");
    }

    @Override
    public void onPause() {
        // Set app name back to default (from device name)
        ((AppCompatActivity) getActivity()).getSupportActionBar().setTitle(getResources().getString(R.string.app_name));
        super.onPause();
    }

    @Override
    public void onDestroy() {
        if (connected != Connected.False)
            disconnect();
        getActivity().stopService(new Intent(getActivity(), SerialService.class));
        super.onDestroy();
    }

    @Override
    public void onStart() {
        super.onStart();
        if(service != null)
            service.attach(this);
        else
            getActivity().startService(new Intent(getActivity(), SerialService.class)); // prevents service destroy on unbind from recreated activity caused by orientation change
    }

    @Override
    public void onStop() {
        if(service != null && !getActivity().isChangingConfigurations())
            service.detach();
        super.onStop();
    }

    @SuppressWarnings("deprecation") // onAttach(context) was added with API 23. onAttach(activity) works for all API versions
    @Override
    public void onAttach(@NonNull Activity activity) {
        super.onAttach(activity);
        Log.d(TAG, "Binding in onAttach.");
        getActivity().bindService(new Intent(getActivity(), SerialService.class), this, Context.BIND_AUTO_CREATE);
    }

    @Override
    public void onDetach() {
        Log.d(TAG, "UNBinding in onDetach.");
        try { getActivity().unbindService(this); } catch(Exception ignored) {}
        super.onDetach();
    }

    @Override
    public void onResume() {
        super.onResume();
        if(initialStart && service != null) {
            initialStart = false;
            getActivity().runOnUiThread(this::connect);
        }
        // Put the name if the connected CBASS at the top.
        if (deviceName != null) {
            ((AppCompatActivity) getActivity()).getSupportActionBar().setTitle(deviceName);
        }
    }

    @Override
    public void onServiceConnected(ComponentName name, IBinder binder) {
        Log.d(TAG, "Getting service from binder in onServiceConnected.");
        service = ((SerialService.SerialBinder) binder).getService();
        service.attach(this);
        if(initialStart && isResumed()) {
            initialStart = false;
            getActivity().runOnUiThread(this::connect);
        }
    }

    @Override
    public void onServiceDisconnected(ComponentName name) {
        Log.d(TAG, "Setting service null in onServiceDisconnected.");
        service = null;
    }

    /*
     * UI
     */
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_ble, container, false);
        diagnostics = view.findViewById(R.id.diagnostics);

        // Button Actions (what to send)

        return view;
    }


    @Override
    public void onCreateOptionsMenu(@NonNull Menu menu, MenuInflater inflater) {
        inflater.inflate(R.menu.menu_graph, menu);

        bleStatus = menu.findItem(R.id.ble_status);
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        int id = item.getItemId();
        if (id == R.id.ble_status) {
            Toast.makeText(getActivity(), "Trying to reconnect.", Toast.LENGTH_SHORT).show();
            Log.d(TAG, "Connection check from onOptionsItemSelected");
            if (connected == Connected.False) connect();
            return true;
        } else {
            return super.onOptionsItemSelected(item);
        }
    }

    /*
     * Serial + UI
     */
    void connect() {
        Log.d(TAG, "In connect");
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
    }

    private void disconnect() {
        Log.d(TAG, "In disconnect");
        connected = Connected.False;
        service.disconnect();
    }

    void send(String str) {
        if(connected != Connected.True) {
            Log.e(TAG, "connected False in send.");
            // problem when called by Timer thread? Toast.makeText(getActivity(), "not connected", Toast.LENGTH_SHORT).show();
            return;
        }
        try {
            String msg;
            byte[] data;
            msg = str;
            data = (str + newline).getBytes();
            SpannableStringBuilder spn = new SpannableStringBuilder(msg + '\n');
            spn.setSpan(new ForegroundColorSpan(getResources().getColor(R.color.colorSendText)), 0, spn.length(), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
            service.write(data);
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

            // Never used??? boolean pendingNewline = msg.charAt(msg.length() - 1) == '\r';
        }
        diagnostics.append(msg);
    }

    /*
     * SerialListener
     */
    @Override
    public void onSerialConnect() {
        connected = Connected.True;
        updateButtons(true);

        // Set up a timer to notice if the connection is lost.  It is not meant to automatically
        // retry, but only change appearance and activity of buttons.
        connCheckTimer = new Timer();
        TimerTask monTask = new TimerTask() {
            @Override
            public void run() {
                if (connected != Connected.True) {
                    updateButtons(false);
                }
            }
        };
        // Check every 5 seconds.  If we do this by sending messages, it should be much slower.
        connCheckTimer.schedule(monTask, 1000, 5000);

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

    /**
     * Change color and activity status of all buttons affected by whether there is a current
     * connection.
     * @param isConnected
     */
    void updateButtons(boolean isConnected) {
        Drawable nd = bleStatus.getIcon();
        Drawable wd = DrawableCompat.wrap(nd);
        if (isConnected) {
            DrawableCompat.setTint(wd, getContext().getResources().getColor(android.R.color.holo_blue_bright));
        } else {
            // Multiple ifs for debugging only...
            if (getContext() != null) {
                if (getContext().getResources() != null) {
                    DrawableCompat.setTint(wd, getContext().getResources().getColor(android.R.color.holo_orange_dark));
                }
            }
        }
    }

}