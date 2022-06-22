package info.pml.cbass_monitor;

import static androidx.constraintlayout.widget.ConstraintSet.INVISIBLE;
import static java.lang.Integer.parseInt;


import android.app.AlertDialog;
import android.app.TimePickerDialog;
import android.content.ComponentName;
import android.content.DialogInterface;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.IBinder;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.TimePicker;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.preference.PreferenceManager;

import java.text.SimpleDateFormat;
import java.util.Date;

public class CBASSControlFragment extends BLEFragment implements ServiceConnection {

    //private enum Connected { False, Pending, True }
    private final String TAG = "ControlFragment";
    //private String deviceAddress;
    private Menu menu;

    SharedPreferences sp;
//    private EditText startTime;
    private TextView cbass_reply;
    private Button chooseTime;
    //private Button sendTime;
    //private android.widget.LinearLayout buttonPair;
    private TextView relativeText;
    private TextView relativeDisabled;
    private TimePickerDialog tpd;

    // Status area:
    private TextView cbassTime, localTime, cbassStart;

    private int startInMinutes;
    // Arduino DateTime likes two arguments in text form, so create them with
    // a separating comma for simple use on that side.
    SimpleDateFormat sdf = new SimpleDateFormat("MMM d yyyy,HH:mm:ss");
   /*
     * Lifecycle
     */
    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Log.d(TAG, "in onCreate");
    }


    @Override
    public void onDetach() {
        try { getActivity().unbindService(this); } catch(Exception ignored) {}
        super.onDetach();
    }

    /*
     * UI
     * Note that this is BEFORE connection to the BLE service, which starts during onStart but
     * isn't completed until onServiceConnected is called.
     * Do not attempt to send anything to BLE yet.
     */
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        Log.d(TAG, "in onCreateView");
        View view = inflater.inflate(R.layout.fragment_control, container, false);
        //receiveText = view.findViewById(R.id.receive_text);                          // TextView performance decreases with number of spans
        //receiveText.setTextColor(getResources().getColor(R.color.colorReceiveText)); // set as default color to reduce number of spans
        //receiveText.setMovementMethod(ScrollingMovementMethod.getInstance());

        //startTime = view.findViewById(R.id.cbass_time);
        //sendTime = view.findViewById(R.id.send_time_btn);
        cbass_reply = view.findViewById(R.id.cbass_reply);
        //buttonPair = view.findViewById(R.id.start_buttons);
        relativeText = view.findViewById(R.id.ramp_text);
        relativeDisabled = view.findViewById(R.id.relative_disabled);

        localTime = view.findViewById(R.id.local_time_value);
        cbassTime = view.findViewById(R.id.cbass_time_value);
        cbassStart = view.findViewById(R.id.cbass_start_value);

        sp = PreferenceManager.getDefaultSharedPreferences(getContext());

        // Button Actions

        // Send the current device time to set the CBASS clock.
        View clockBtn = view.findViewById(R.id.clock_btn);
        clockBtn.setOnClickListener(    // OLD: v -> send("t"));
            new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    String pin = sp.getString("connect_PIN", "OFF");
                    String now = sdf.format(new Date());
                    Log.d(TAG, "New date raw " + new Date());
                    Log.d(TAG, "New date fmt " + sdf.format(new Date()));
                    send("C," + pin + "," + now, ExpectBLEData.Ack);

            }
        });

        /*
        sendTime.setOnClickListener(    // OLD: v -> send("t"));
                new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        String pin = sp.getString("connect_PIN", "OFF");
                        Log.d(TAG, "Sending ramp time " + startInMinutes);
                        send("S," + pin + "," + startInMinutes, ExpectBLEData.Ack);
                    }
                });
         */

        // Start time picker, but note that it can't show the current time on
        // CBASS until after there's a connection, which is after this.
        chooseTime = (Button)view.findViewById(R.id.choose_time_btn);
        chooseTime.setOnClickListener(
            new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    tpd = new TimePickerDialog(getContext(),
                            new TimePickerDialog.OnTimeSetListener() {
                                @Override
                                public void onTimeSet(TimePicker view, int hourOfDay, int minute) {
                                    startInMinutes = hourOfDay * 60 + minute;
                                    //sendTime.setEnabled(true);
                                    // TODO show in UI.
                                    // Oddly, with the time dialog can be created directly, the AlertDialog
                                    // requires a builder.
                                    AlertDialog.Builder builder = new AlertDialog.Builder(getContext());
                                    builder.setTitle("Send change to CBASS")
                                            .setCancelable(true);

                                    builder.setNegativeButton("Cancel", new DialogInterface.OnClickListener() {
                                        @Override
                                        public void onClick(DialogInterface dialog, int which) {
                                            dialog.dismiss();
                                        }
                                    });

                                    builder.setPositiveButton("Send to CBASS", new DialogInterface.OnClickListener() {
                                        @Override
                                        public void onClick(DialogInterface dialog, int which) {
                                            String pin = sp.getString("connect_PIN", "OFF");
                                            Log.d(TAG, "Sending new ramp time " + startInMinutes);
                                            send("S," + pin + "," + startInMinutes, ExpectBLEData.Ack);
                                        }
                                    });
                                    builder.create().show();
                                }
                            },

                            startInMinutes/60, startInMinutes % 60, true);
                    // Send a message to get the existing start time.  We should should set the
                    // correct time in the dialog, perhaps delayed until the response returns.
                    // StartMinutes is for the time setter, StartMinutes2 for the text display.
                    send("s", ExpectBLEData.StartMinutes);
                    tpd.show();
                }
            }
        );

        return view;
    }

    /**
     *
     */
    @Override
    public void onResume() {
        super.onResume();
        Log.d(TAG, "in onResume");
    }

    @Override
    public void onStart() {
        super.onStart();
        Log.d(TAG, "in onStart");
        // Clear any old expectations from the superclass???
        // Need to review how values set in a different subclass work.
        expectBLE = ExpectBLEData.Nothing;
    }

    @Override
    public void onCreateOptionsMenu(@NonNull Menu menu, MenuInflater inflater) {
        super.onCreateOptionsMenu(menu, inflater);
        this.menu = menu;
        // Hide this fragment's own icon.
        menu.findItem(R.id.to_control).setVisible(false);

    }

    @Override
    public void onServiceConnected(ComponentName name, IBinder binder) {
        super.onServiceConnected(name, binder);
        Log.d(TAG, "onServiceConnected()");
        updateStatusRows();
    }

    // Receive incoming bytes over BLE, and handle them based on the expected information,
    // which is in turn based on the last command sent.
    void receive(byte[] data) {
        String msg = new String(data);
        Log.d(TAG, "Received message " + msg + " expect: " + expectBLE.toString());
        // Note that this only handles a single response <= 20 bytes.
        // The Arduino code is responsible for respecting that limit.
        switch (expectBLE) {
            case StartMinutes:
                // Update time picker or show notice if not in auto mode.
                if (msg.equals("NA")) {
                    startInMinutes = -1;
                    if (tpd != null && tpd.isShowing()) tpd.hide();
                    //startTime.setVisibility(View.GONE);
                    relativeText.setVisibility(View.GONE);
                    relativeDisabled.setVisibility(View.VISIBLE);
                } else {
                    startInMinutes = parseInt(msg);
                    tpd.updateTime(startInMinutes / 60, startInMinutes % 60);
                    //startTime.setVisibility(View.VISIBLE);
                    relativeText.setVisibility(View.VISIBLE);
                    relativeDisabled.setVisibility(View.GONE);
                }
                expectBLE = ExpectBLEData.Nothing;
                break;
            case StartMinutes2:
                // Same info as StartMinutes, but requested for status display.
                if (msg.equals("NA")) {
                    startInMinutes = -1;
                    cbassStart.setText("Not using relative start.");
                } else {
//HERE - parseInt on date-time string!
// not what was expected.
                    startInMinutes = parseInt(msg);
                    cbassStart.setText(startInMinutes / 60 + ":" + startInMinutes % 60);
                }
                expectBLE = ExpectBLEData.Nothing;
                break;
            case TimeOfDay:
                // Expect yyyy/mm/dd hh:mm:ss
                cbassTime.setText(msg.split(" ")[1]);
                expectBLE = ExpectBLEData.Nothing;
                // So we don't need to deal the threads or synchronization, ask
                // for ramp start time only after time of day returns.
                Log.d(TAG, "Updating status area for Start time.");
                send("s", ExpectBLEData.StartMinutes2);
                break;
            case Nothing:
                cbass_reply.setText("With nothing expected, received\n   >" + msg + "<\n  This may indicate a bug.");
                expectBLE = ExpectBLEData.Nothing;
                break;
            case Ack:
                cbass_reply.setText("CBASS response:\n" + msg);
                cbass_reply.setVisibility(View.VISIBLE);
                expectBLE = ExpectBLEData.Nothing;
                // All(?) commands will result in a change of state visible in the status area.
                updateStatusRows();
                break;
            default:
                cbass_reply.setText("BLE query state >" + expectBLE.toString() + "< invalid in " + TAG);
                expectBLE = ExpectBLEData.Nothing;
        }
    }

    /**
     * Query status for its actual time and ramp start information so we
     * have a reference before and after changes.
     */
    void updateStatusRows() {
        // XXX HERE: be sure not to send one message while another is pending!
        Log.d(TAG, "Updating status area for CBASS time.");
        send("t,1", ExpectBLEData.TimeOfDay);

        localTime.setText(sdf.format(new Date()));


    }

}
