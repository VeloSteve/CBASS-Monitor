package info.pml.cbass_monitor;

import android.content.ServiceConnection;
import android.os.Bundle;
import android.text.Editable;
import android.text.Spannable;
import android.text.SpannableStringBuilder;
import android.text.method.ScrollingMovementMethod;
import android.text.style.ForegroundColorSpan;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.lifecycle.Lifecycle;

import java.util.Timer;
import java.util.TimerTask;

import de.kai_morich.simple_bluetooth_le_terminal.TextUtil;

public class MonitorFragment extends BLEFragment implements ServiceConnection {

    //private enum Connected { False, Pending, True }
    private final String TAG = "MonitorFragment";
    private String deviceAddress;
    //private Menu menu;

    //private TextView receiveText;
    private final byte nTemps = 4;

    private TextView[] mon_temp = new TextView[nTemps];
    private TextView[] mon_setpoint = new TextView[nTemps];
    private TextView cbass_time;

    // Copies of the incoming strings for use on restart.
    private String [] latestTemps, latestTime, latestSetpoints;


    private Button updateButton, repeatButton;
    //private Connected connected = Connected.False;
    private boolean initialStart = true;
    private boolean pendingNewline = false;
    private String newline = TextUtil.newline_crlf;

    private boolean expectSeries = false;
    // Don't update forever - it slows the temperature checks a bit.
    private final int maxUpdates = 30;  // with 1/minute, run for 30 minutes.
    private int updatesLeft = maxUpdates;
    private final int updateMillis = 10000;  // 1/minute is planned, debugging w/ 10 seconds, 5 updates.
    Timer monTimer;

    /*
     * Lifecycle
     */
    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setHasOptionsMenu(true);
        // not recommended - interferes with lifecycle calls.  Especially onCreate and onDestroy
        // are skipped.   setRetainInstance(true);
        deviceAddress = getArguments().getString("device");
    }

    @Override
    public void onPause() {
        super.onPause();
    }

    /**
     * Save data for use after rotations, fragment swaps, and so on.
     */
    @Override
    public void onSaveInstanceState(@NonNull Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putSerializable("last CBASS time", latestTime);
        outState.putSerializable("measured temps", latestTemps);
        outState.putSerializable("target temps", latestSetpoints);
    }

    @Override
    public void onDetach() {
        try { getActivity().unbindService(this); } catch(Exception ignored) {}
        super.onDetach();
    }

    /*
     * UI
     */
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_monitor, container, false);
        //receiveText = view.findViewById(R.id.receive_text);                          // TextView performance decreases with number of spans
        //receiveText.setTextColor(getResources().getColor(R.color.colorReceiveText)); // set as default color to reduce number of spans
        //receiveText.setMovementMethod(ScrollingMovementMethod.getInstance());

        cbass_time = view.findViewById(R.id.cbass_time);
        mon_temp[0] = view.findViewById((R.id.mon_temp1));
        mon_temp[1] = view.findViewById((R.id.mon_temp2));
        mon_temp[2] = view.findViewById((R.id.mon_temp3));
        mon_temp[3] = view.findViewById((R.id.mon_temp4));
        mon_setpoint[0] = view.findViewById((R.id.mon_set1));
        mon_setpoint[1] = view.findViewById((R.id.mon_set2));
        mon_setpoint[2] = view.findViewById((R.id.mon_set3));
        mon_setpoint[3] = view.findViewById((R.id.mon_set4));

        if (savedInstanceState != null) {
            // We can populate the views above with the most recent info.
            latestTime = (String []) savedInstanceState.getSerializable("last CBASS time");
            latestTemps = (String []) savedInstanceState.getSerializable("measured temps");
            latestSetpoints = (String []) savedInstanceState.getSerializable("target temps");
        }
        // After switching to another Fragment and back we may have data to put in the UI, and it
        // may not have been in a instance state.
        if (latestTime != null) {
            // Don't display temps unless there's a time to show when they applied.
            messageToScreen(latestTime, false, ExpectBLEData.TimeOfDay);
            if (latestTemps != null) messageToScreen(latestTemps, false, ExpectBLEData.CurrentTemps);
            if (latestSetpoints != null) messageToScreen(latestSetpoints, false, ExpectBLEData.SetPoints);
        }

        // Button Actions (what to send)
        updateButton = view.findViewById(R.id.update_btn);
        updateButton.setOnClickListener(    // OLD: v -> send("t"));
            new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    if (monTimer != null) monTimer.cancel();
                    monTimer = null; // In case a repeating update was still running.
                    expectSeries = false;
                    send("t,1", ExpectBLEData.TimeOfDay);
                    toggleButtons();

            }
        });

        repeatButton = view.findViewById(R.id.repeat_btn);
        repeatButton.setText(updateMillis/1000 + " sec repeat");
        repeatButton.setOnClickListener(    // OLD: v -> send("t"));
                new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        expectSeries = true;
                        updatesLeft = maxUpdates;
                        if (monTimer != null) monTimer.cancel();  // in case one is running
                        monTimer = new Timer();
                        TimerTask monTask = new TimerTask() {
                            @Override
                            public void run() {
                                Log.d(TAG, "Requesting update.");
                                send("t,1", ExpectBLEData.TimeOfDay);
                                toggleButtons();
                                updatesLeft--;
                            }
                        };
                        monTimer.schedule(monTask, 5, updateMillis);
                    }
                });

        return view;
    }

    @Override
    public void onCreateOptionsMenu(@NonNull Menu menu, MenuInflater inflater) {
        super.onCreateOptionsMenu(menu, inflater);
        //this.menu = menu;
        // Hide this fragment's own icon.
        menu.findItem(R.id.monitor).setVisible(false);

    }


    // Receive incoming bytes over BLE, and handle them based on the expected information,
    // which is in turn based on the last command sent.
    void receive(byte[] data) {
        String msg = new String(data);
        if(newline.equals(TextUtil.newline_crlf) && msg.length() > 0) {
            // don't show CR as ^M if directly before LF
            msg = msg.replace(TextUtil.newline_crlf, TextUtil.newline_lf);
            // special handling if CR and LF come in separate fragments
            // We don't have this view now.  Is the containing "if" even needed?
            /*
            if (pendingNewline && msg.charAt(0) == '\n') {
                Editable edt = receiveText.getEditableText();
                if (edt != null && edt.length() > 1)
                    edt.replace(edt.length() - 2, edt.length(), "");
            }
             */
            pendingNewline = msg.charAt(msg.length() - 1) == '\r';
        }
        // Get rid of the timer after some number of updates, rather than running forever.
        if (expectSeries && updatesLeft < 1) {
            monTimer.cancel();
        }
        Log.d(TAG, "Incoming msg = " + msg + " expecting " + expectBLE.toString());
        String[] parts;
        if (expectBLE == ExpectBLEData.TimeOfDay) {
            parts = msg.split(" ");
            if (parts.length != 2) {
                Toast.makeText(getActivity(), "bad response - didn't get a date time in message: " + msg, Toast.LENGTH_LONG).show();
                if (expectSeries) {
                    Toast.makeText(getActivity(), "Canceling updates.  Try again.", Toast.LENGTH_LONG).show();
                }
                expectSeries = false;
                expectBLE = ExpectBLEData.Nothing;
                toggleButtons();
                return;
            }
            latestTime = parts;
        } else if (expectBLE == ExpectBLEData.SetPoints || expectBLE == ExpectBLEData.CurrentTemps) {
            // If it's not time it's 4 temperatures or setpoints.
            parts = msg.split(",");
            if (parts.length != 4) {
                Toast.makeText(getActivity(), "Please try again.", Toast.LENGTH_LONG).show();
                //Toast.makeText(getActivity(), "bad response - didn't get 4 temperatures. Got " + parts.length, Toast.LENGTH_LONG).show();
                //Toast.makeText(getActivity(), "MSG: " + msg, Toast.LENGTH_LONG).show();
                expectBLE = ExpectBLEData.Nothing;
                toggleButtons();
                return;
            }
            if (expectBLE == ExpectBLEData.CurrentTemps) {
                latestTemps = parts;
            } else {
                latestSetpoints = parts;
            }
        } else {
            Log.w(TAG, "Unsupported expectation " + expectBLE.toString());
            expectBLE = ExpectBLEData.Nothing;
            toggleButtons();
            return;
        }

        // Use a separate function so it can also be called on fragment restarts.
        messageToScreen(parts, true, null);
        toggleButtons();
    }

    /**
     * Draw the received time or temperatures to the screen, sending the next request
     * if we are only partially done with a set of incoming data.  The flag can be
     * set false for filling the Views on restart without sending BLE requests.
     * When false we don't call send and don't modify the global expectBle value.
     */
    void messageToScreen(String[] parts, boolean canSend, ExpectBLEData tempExpect) {
        // Date is assumed to arrive in a single chunk of <= 20 bytes, thus using 3 calls for 1 screen.
        // expectBLE is the class variable.  Only modify it in "canSend" mode.
        // Also refer to the global value if sending, but if just updating the screen use the temp value.
        ExpectBLEData localExpect = canSend ? expectBLE : tempExpect;
        /*
        if (canSend) {
            localExpect = expectBLE;  // use the global,  provided value is typically null
        } else {
            localExpect = tempExpect; // use the provided value
        }
         */
        if (localExpect == ExpectBLEData.TimeOfDay) {
            cbass_time.setText(parts[1]);  // part 0 is yyyy/mm/dd.  part 1 is hh:mm:ss
            if (canSend) {
                expectBLE = ExpectBLEData.Nothing;
                // After displaying the time we want to update the temperatures.
                send("t,2", ExpectBLEData.CurrentTemps);
            }
        } else if (localExpect == ExpectBLEData.SetPoints) {
            for (int i = 0; i < nTemps; i++) {
                mon_setpoint[i].setText(parts[i]);
            }
            if (canSend) expectBLE = ExpectBLEData.Nothing;
        } else if (localExpect == ExpectBLEData.CurrentTemps){
            for (int i = 0; i < nTemps; i++) {
                if (0 == (updatesLeft % 2)) {
                    mon_temp[i].setText(parts[i] + " ");
                } else {
                    mon_temp[i].setText(parts[i]);
                }
            }
            if (canSend) {
                expectBLE = ExpectBLEData.Nothing;
                // After displaying the temperatures we want to update the setpoints.
                send("t,3", ExpectBLEData.SetPoints);
            }
        } else {
            Log.w(TAG, "Unsupported data expectation " + localExpect.toString());
            if (canSend) expectBLE = ExpectBLEData.Nothing;
            toggleButtons();
        }
    }

    /**
     * Keep send buttons active only when connected.  Super handles the icons at the top,
     * this does the main buttons.
     *
     * @param isConnected
     */
    @Override
    void updateButtons(boolean isConnected) {
        super.updateButtons(isConnected);
        Log.d("BLEFrag", "updateButtons in Monitor");
        if (updateButton == null || repeatButton == null) return;
        // Without the UI thread this causes a crash!  Something about looper threads...
        requireActivity().runOnUiThread(new Runnable() {
                                            @Override
                                            public void run() {
                                                updateButton.setEnabled(expectBLE == ExpectBLEData.Nothing);
                                                repeatButton.setEnabled(expectBLE == ExpectBLEData.Nothing);
                                            }
        });
    }

    // This should only be used when connected, to locally turn off buttons while
    // a series of requests is active.
    void toggleButtons() {
        // Without the thread this causes a crash!  Something about looper threads...
        requireActivity().runOnUiThread(new Runnable() {
            @Override
            public void run() {
                updateButton.setEnabled(expectBLE == ExpectBLEData.Nothing);
                repeatButton.setEnabled(expectBLE == ExpectBLEData.Nothing);
            }
        });
    }
}
