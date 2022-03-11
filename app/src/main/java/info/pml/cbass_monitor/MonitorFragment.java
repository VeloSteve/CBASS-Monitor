package info.pml.cbass_monitor;

import android.content.ServiceConnection;
import android.os.Bundle;
import android.text.Editable;
import android.text.Spannable;
import android.text.SpannableStringBuilder;
import android.text.method.ScrollingMovementMethod;
import android.text.style.ForegroundColorSpan;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.Timer;
import java.util.TimerTask;

import de.kai_morich.simple_bluetooth_le_terminal.TextUtil;

public class MonitorFragment extends BLEFragment implements ServiceConnection {

    //private enum Connected { False, Pending, True }

    private String deviceAddress;
    private Menu menu;

    private TextView receiveText;
    private final byte nTemps = 4;
    /* private EditText mon_temp1;
    private EditText mon_temp2;
    private EditText mon_temp3;
    private EditText mon_temp4;
     */
    private EditText[] mon_temp = new EditText[nTemps];
    //private Connected connected = Connected.False;
    private boolean initialStart = true;
    private boolean pendingNewline = false;
    private String newline = TextUtil.newline_crlf;

    private final int expectOneSet = 0;
    private final int expectSeries = 1;
    private int expectBLE = expectOneSet;
    // Don't update forever - it slows the temperature checks a bit.
    private final int maxUpdates = 5;
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
        setRetainInstance(true);
        deviceAddress = getArguments().getString("device");
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
        receiveText = view.findViewById(R.id.receive_text);                          // TextView performance decreases with number of spans
        receiveText.setTextColor(getResources().getColor(R.color.colorReceiveText)); // set as default color to reduce number of spans
        receiveText.setMovementMethod(ScrollingMovementMethod.getInstance());

        mon_temp[0] = view.findViewById((R.id.mon_temp1));
        mon_temp[1] = view.findViewById((R.id.mon_temp2));
        mon_temp[2] = view.findViewById((R.id.mon_temp3));
        mon_temp[3] = view.findViewById((R.id.mon_temp4));

        // Button Actions (what to send)
        View updateBtn = view.findViewById(R.id.update_btn);
        updateBtn.setOnClickListener(    // OLD: v -> send("t"));
            new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    monTimer = null; // In case a repeating update was still running.
                    expectBLE = expectOneSet;
                    send("m");

            }
        });

        View repeatBtn = view.findViewById(R.id.repeat_btn);
        repeatBtn.setOnClickListener(    // OLD: v -> send("t"));
                new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        expectBLE = expectSeries;
                        updatesLeft = maxUpdates;
                        monTimer = new Timer();
                        TimerTask monTask = new TimerTask() {
                            @Override
                            public void run() {
                                send("m");
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
        this.menu = menu;
        // Hide this fragment's own icon.
        menu.findItem(R.id.monitor).setVisible(false);

    }



    void receive(byte[] data) {

        String msg = new String(data);
        if(newline.equals(TextUtil.newline_crlf) && msg.length() > 0) {
            // don't show CR as ^M if directly before LF
            msg = msg.replace(TextUtil.newline_crlf, TextUtil.newline_lf);
            // special handling if CR and LF come in separate fragments
            if (pendingNewline && msg.charAt(0) == '\n') {
                Editable edt = receiveText.getEditableText();
                if (edt != null && edt.length() > 1)
                    edt.replace(edt.length() - 2, edt.length(), "");
            }
            pendingNewline = msg.charAt(msg.length() - 1) == '\r';
        }
        String[] parts;
        if (expectBLE == expectOneSet) {
            // In first try, gets 0.0,40 at this point.  Are the data chunks predictable???
            // Apparently 20 bytes is a common, but not rigid limit.  Either make the
            // data smaller than that or send it in chunks with some kind of known count.
            // With the time in milliseconds the message is too long, but with 4 values
            // such as 10.0,20.0,30.0,40.0 it fits and works fine!
            parts = msg.split(",");
            if (parts.length != 4) {
                Toast.makeText(getActivity(), "bad response - didn't get 4 temperatures. Got " + parts.length, Toast.LENGTH_LONG).show();
            } else {
                for (int i=0; i<nTemps; i++) {
                    mon_temp[i].setText(parts[i]);
                }
            }
        } else if (expectBLE == expectSeries) {
            // Get rid of the timer after some number of updates, rather than running forever.
            // The counter is decremented along with the timer-triggered send call.
            if (updatesLeft < 1) {
                monTimer.cancel();
                // monTimer = null;
            }
            parts = msg.split(",");
            if (parts.length != 4) {
                Toast.makeText(getActivity(), "bad response - didn't get 4 temperatures. Got " + parts.length, Toast.LENGTH_LONG).show();
            } else {
                //Toast.makeText(getActivity(), "updating, " + updatesLeft + " more", Toast.LENGTH_SHORT).show();
                Toast.makeText(getActivity(), msg, Toast.LENGTH_SHORT).show();
                for (int i=0; i<nTemps; i++) {
                    if (0 == (updatesLeft % 2)) {
                        mon_temp[i].setText(" ");
                    } else {
                        mon_temp[i].setText("");
                    }
                    mon_temp[i].append(parts[i]);
                }
            }
        } else {
            receiveText.append(TextUtil.toCaretString(msg, newline.length() != 0));
        }
    }

    private void status(String str) {
        SpannableStringBuilder spn = new SpannableStringBuilder(str + '\n');
        spn.setSpan(new ForegroundColorSpan(getResources().getColor(R.color.colorStatusText)), 0, spn.length(), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
        receiveText.append(spn);
    }
}
