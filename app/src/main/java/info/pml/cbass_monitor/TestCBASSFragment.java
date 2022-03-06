package info.pml.cbass_monitor;

import android.content.ServiceConnection;
import android.graphics.Color;
import android.os.Bundle;
import android.text.method.ScrollingMovementMethod;
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

import java.util.Arrays;
import java.util.InputMismatchException;

import de.kai_morich.simple_bluetooth_le_terminal.SerialListener;
import de.kai_morich.simple_bluetooth_le_terminal.TextUtil;

public class TestCBASSFragment extends BLEFragment implements ServiceConnection, SerialListener {

    private final String TAG = "TestCBASSFragment";

    private View logButton;
    private View f3Button;
    private View l3Button;

    private final int numTests = 4;
    private TextView[] rowLabel = new TextView[numTests];
    private TextView[] rowResult = new TextView[numTests];
    private byte outputRow;
    private byte generalRow = numTests - 1;
    private TextView receiveText;

    //  use inherited version. private Connected connected = Connected.False;
    private final byte maxRetries = 3;
    private byte connectRetries = maxRetries;
    private boolean initialStart = true;
    private boolean pendingNewline = false;
    private String newline = TextUtil.newline_crlf;

    private String msgBuffer = "";

    private enum LogLoc {
        Start,
        End,
        Middle
    }

    private enum TestType {
        Log,
        Lines,
        Nothing
    }
    TestType testType = TestType.Nothing;
    private boolean addToEnd = true;
    private boolean noMoreOldData = false;

    // Imitate a struct with a bare-bones java class.
    private class TestInfo {
        boolean active;
        long startMillis;
        int linesRequested;
        int linesExpected;
        boolean forward;
        LogLoc logLoc;
        int linesReturned;

        void reset() {
            active = false;
            startMillis = 0;
            linesRequested = 0;
            linesExpected = 0;
            forward = true;
            logLoc = LogLoc.Start;
            linesReturned = 0;
        }
    }
    TestInfo currentTest = new TestInfo();



    // Set up parameters for the data to show in a default graph.  After this works,
    // make a set of optional graphs.  For example, last 15 minutes in detail, full run to now, and full run including future plan.
    private TemperatureData savedData;

    /*
     * Lifecycle
     */
    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setHasOptionsMenu(true);
        setRetainInstance(true);

    }


    /*
     * UI
     */
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_test_cbass, container, false);


        // Just to exercise the new classes, build some points manually and then
        // use them to graph.
        savedData = new TemperatureData();

        // Button Actions (what to send)

        // In the basic monitor update means to get the latest temperatures.  Here it means to
        // fetch as much data as needed to bring the graph up to date.
        logButton = view.findViewById(R.id.test_log);
        logButton.setOnClickListener(
                new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        outputRow = 0;
                        runCBASSLogTest();
                    }
                });
        f3Button = view.findViewById(R.id.test_first3F);
        f3Button.setOnClickListener(
                new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        outputRow = 1;
                        runCBASSBatchTest( 3, LogLoc.Start);
                    }
                });
        l3Button = view.findViewById(R.id.test_last3B);
        l3Button.setOnClickListener(
                new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        outputRow = 2;
                        runCBASSBatchTest( -3, LogLoc.End);
                    }
                });

        rowLabel[0] = view.findViewById((R.id.rowLabel0));
        rowLabel[1] = view.findViewById((R.id.rowLabel1));
        rowLabel[2] = view.findViewById((R.id.rowLabel2));
        rowLabel[3] = view.findViewById((R.id.rowLabel3));

        rowResult[0] = view.findViewById((R.id.result0));
        rowResult[1] = view.findViewById((R.id.result1));
        rowResult[2] = view.findViewById((R.id.result2));
        rowResult[3] = view.findViewById((R.id.result3));
        
        for (int i=0; i<4; i++) {
            rowLabel[i].setTextColor(Color.BLACK);
            rowResult[i].setTextColor(Color.BLACK);
        }

        receiveText = view.findViewById(R.id.receive_text);                          // TextView performance decreases with number of spans
        receiveText.setTextColor(getResources().getColor(R.color.colorReceiveText)); // set as default color to reduce number of spans
        receiveText.setMovementMethod(ScrollingMovementMethod.getInstance());

        return view;
    }

    /**
     * Make several requests to CBASS, check the results, and display in the result table.
     */
    private void runCBASSLogTest() {
        // Get the first and last times, byte count, and row size.
        receiveText.setText("");
        msgBuffer = "";
        testType = TestType.Log;
        rowResult[outputRow].setBackgroundColor(Color.YELLOW);
        rowResult[outputRow].setText("Test in progress.");

        Log.d(TAG, "Sending in runCBASSLogTest");
        send("r");
    }

    /**
     * Request a specified set of log lines so the response can be checked.
     */
    private void runCBASSBatchTest(int lines, LogLoc ll) {
        if (currentTest.active) {
            generalError("Previous test is still running!  Not started.");
            return;
        }

        savedData.clear();  // Start fresh.
        receiveText.setText("");
        msgBuffer = "";
        testType = TestType.Lines;
        rowResult[outputRow].setBackgroundColor(Color.YELLOW);
        rowResult[outputRow].setText("Test in progress.");
        // Set the start location in milliseconds.
        // TODO: for Middle, get the actual range first to compute a better value.
        // This will also allow better checks for requests that normally return no lines.
        // Note: on the Arduino the max values are
        //        32,767 for int
        //        65,535 for unsigned int
        // 2,147,483,647 for long  <--- use this, which is expected on the Arduino side.
        // 4,294,967,295 for unsigned long
        long start = Math.min(2147483647, Long.MAX_VALUE);

        if (ll == LogLoc.Start) start = 0;
        else if (ll == LogLoc.Middle) start = 100000; // Assume at least 100 seconds after Arduino start.

        // Save test information for use when checking the result:
        currentTest.active = true;
        currentTest.startMillis = start;
        currentTest.linesRequested = Math.abs(lines);
        currentTest.linesExpected = currentTest.linesRequested;
        currentTest.forward = lines > 0;
        currentTest.logLoc = ll;
        currentTest.linesReturned = 0;

        // This is only some of the cases where we may expect fewer than requested lines.
        if (ll == LogLoc.Start && !currentTest.forward) currentTest.linesExpected = 0;
        if (ll == LogLoc.End && currentTest.forward) currentTest.linesExpected = 0;


        Log.d(TAG, "Sending in runCBASSLogTest");
        send("b," + lines + "," + start);
    }

    @Override
    public void onCreateOptionsMenu(@NonNull Menu menu, MenuInflater inflater) {
        inflater.inflate(R.menu.menu_testcbass, menu);
        bleStatus = menu.findItem(R.id.ble_status);
        // Tried connecting here, but service isn't ready.
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        int id = item.getItemId();
        if (id == R.id.ble_status) {
            Toast.makeText(getActivity(), "Trying to reconnect.", Toast.LENGTH_SHORT).show();
            Log.d("BLE", "Calling connect on icon click.");
            connectRetries = maxRetries;
            if (connected == Connected.False) connect();
            return true;
        } else {
            return super.onOptionsItemSelected(item);
        }
    }


    @Override
    void receive(byte[] data) {

        String msg = new String(data);
        if (msg.startsWith("Coll")) {
            receiveText.append("Who sets " + msg + "?");
        }
        String[] parts;
        if(newline.equals(TextUtil.newline_crlf) && msg.length() > 0) {
            // don't show CR as ^M if directly before LF
            msg = msg.replace(TextUtil.newline_crlf, TextUtil.newline_lf);

            pendingNewline = msg.charAt(msg.length() - 1) == '\r';
        }

        rowResult[outputRow].setBackgroundColor(Color.LTGRAY);

        receiveText.append(msg);
        if (testType == TestType.Log) {
            msgBuffer = msgBuffer + msg;
            // If the message is complete, check results.  Otherwise, just exit until more arrives.
            if (msgBuffer.endsWith(",D")) {
                receiveText.append("\n");
                parts = msgBuffer.split(",", 15);  // 4 values, "D" and any leftovers
                if (parts.length != 5) {
                    rowResult[outputRow].setText(" ERROR: stats should have exactly 5 items, including D at end. Got " + parts.length);
                    rowResult[outputRow].setBackgroundColor(Color.RED);
                    return;
                }
                long t0 = Long.parseLong(parts[0]);
                long tMax = Long.parseLong(parts[1]);
                int byteCount = Integer.parseInt(parts[2]);
                int lineSize = Integer.parseInt(parts[3]);
                rowLabel[outputRow].setText("File and timestamps");
                rowResult[outputRow].setBackgroundColor(Color.GREEN);

                rowResult[outputRow].setText("");
                boolean okay = myAssert(t0 < 20000, "t0 too large: " + t0, rowResult[outputRow]);
                okay = okay && myAssert(t0 < tMax, "tMax " + tMax + " <= t0: " + t0, rowResult[outputRow]);
                okay = okay && myAssert(byteCount % lineSize == 0, " File size " + byteCount + " is not a multiple of line size " + lineSize, rowResult[outputRow]);
                okay = okay && myAssert(tMax > 10000, " tMax too small: " + tMax, rowResult[outputRow]);
                if (okay) rowResult[outputRow].setText("PASS");
                testType = TestType.Nothing;
                msgBuffer = "";
            }
        } else if (testType == TestType.Lines) {
            msgBuffer = msgBuffer + msg;
            // If the message is complete, check results.  Otherwise, just exit until more arrives.
            if (msgBuffer.endsWith(",BatchDone")) {
                receiveText.append("\n");
                // This just puts the results into savedData.
                int lineCount = parseBatch(msgBuffer);

                /* Typical starting values:
                currentTest.active = true;
                currentTest.startMillis = start;
                currentTest.linesRequested = Math.abs(lines);
                currentTest.forward = lines > 0;
                currentTest.logLoc = ll;
                currentTest.linesReturned = 0;
                 */
                TextView rr = rowResult[outputRow];
                rr.setText("");
                boolean okay = myAssert(currentTest.active, "Response with no current batch test.", rr);
                okay = okay && myAssert(savedData.size() == currentTest.linesExpected, "Expected " + currentTest.linesExpected + ", got " + savedData.size(), rr);
                okay = okay && myAssert(savedData.size() == lineCount, "Parser saw " + lineCount + " lines, got " + savedData.size() + " in saved Data.", rr);
                okay = okay && myAssert(savedData.isSorted(), "Saved data is not in sorted order.", rr);


                if (okay) {
                    rr.setText("PASS");
                    if (lineCount > 0) {
                        rr.append(" times from " + savedData.getFirstTime() + " to " + savedData.getLastTime());
                    } else {
                        rr.append(" No data, as expected");
                    }
                }

                testType = TestType.Nothing;
                msgBuffer = "";
                currentTest.reset();
            }
        } else {
            generalError("response when none was expected");
        }

    }


    boolean myAssert(boolean okay, String s, TextView t) {
        if (!okay) {
            if (s.length() < 1) {
                s = "Failed assertion, no text.";
            }
            t.append(s);
            t.setBackgroundColor(Color.RED);
        }
        return okay;
    }

    void generalError(String t) {
        receiveText.append(" ERROR: " + t);
        rowResult[generalRow].setText("ERROR: " + t);
        rowResult[generalRow].setBackgroundColor(Color.RED);
    }


    /**
     * The input should be a complete batch of data suitable for graphing, consisting of lines
     * looking like.
     * T00012345,M12.0,13.0,14.0,15.0,P10.0,15.0,20.0,25.0,
     * There T -> time in milliseconds, M -> measured temperatures, P -> planned temperatures
     *
     * This will parse out the values, build TempPoints and append them to TemperatureData (tempData).
     *
     * Note that there has been no attempt to reduce data copying or memory use, on the assumption
     * that this is fast compared to BLE data transfer.  Check that assumption some time!
     * @param buf  All data received from the last BLE receipt.
     */
    private int parseBatch(String buf) {

        int timeLen = 8;    // One 8-character long
        int tSetLen = 4*5;  // Four 4-character times with commas
        String[] parts;
        int count = 0;

        // Each point starts with T.
        while (buf.length() > 0 && !buf.startsWith(("BatchDone"))) {
            Log.d("PARSE", buf);
            if (!buf.startsWith("T")) {
                throw new InputMismatchException("Batch must start with a T.");
            } else if (buf.length() < timeLen + 2 * tSetLen + 2) {
                throw new InputMismatchException("Not enough data in buffer for a graph point. >" + buf + "<");
            }
            TempPoint incomingPoint = new TempPoint(buf.substring(1, timeLen + 1));  // substring is inclusive/exclusive
            buf = buf.substring(timeLen + 2);  // drop T12345678,

            parts = buf.split(",", 5);  // 4 temps and leftovers
            incomingPoint.setMeasured(Arrays.copyOfRange(parts, 0, 4));
            buf = buf.substring(tSetLen);

            parts = buf.split(",", 5);  // 4 temps and leftovers
            incomingPoint.setTarget(Arrays.copyOfRange(parts, 0, 4));
            buf = buf.substring(tSetLen);

            count++;
            if (addToEnd) {
                Log.d("PARSE", "Saving point at end " + incomingPoint);
                savedData.add(incomingPoint);
            } else {
                Log.d("PARSE", "Saving point at start " + incomingPoint);

                // Could be slow - if so save up incoming batches and do a single add/sort per batch.
                savedData.add(0, incomingPoint);
            }
        }
        return count;
    }



    /**
     * Change color and activity status of all buttons affected by whether there is a current
     * connection.
     * @param isConnected
     */
    void updateButtons(boolean isConnected) {
        super.updateButtons(isConnected);
        if (isConnected) {
            f3Button.setActivated(true);
            l3Button.setActivated(true);
            logButton.setActivated(true);
        } else {
            if (f3Button != null) f3Button.setActivated(false);
            if (l3Button != null) l3Button.setActivated(false);
            if (logButton != null) logButton.setActivated(false);
        }
    }

}