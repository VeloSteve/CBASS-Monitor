package info.pml.cbass_monitor;

import static java.lang.Integer.parseInt;
import static java.lang.Long.parseLong;

import android.content.ServiceConnection;
import android.graphics.Color;
import android.os.Bundle;
import android.text.method.ScrollingMovementMethod;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.InputMismatchException;

import de.kai_morich.simple_bluetooth_le_terminal.SerialListener;
import de.kai_morich.simple_bluetooth_le_terminal.TextUtil;

public class TestCBASSFragment extends BLEFragment implements ServiceConnection, SerialListener {

    private final String TAG = "TestCBASSFragment";

    // private String deviceAddress;
    private Menu menu;

    private View logButton;
    private View f3Button;
    private View l3Button;

    private final int numTests = 4;
    private TextView[] rowLabel = new TextView[numTests];
    private TextView[] rowResult = new TextView[numTests];
    private byte outputRow;
    private final byte generalRow = numTests - 1;
    private TextView receiveText;

    // The log test gets information useful for the other tests.
    // If running a log test, discard the old data so it's a valid
    // new tests, but for other tests we can re-use it.
    private boolean logTestRun = false;
    private long t0, tMax; // Minutes since 2000

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
    // testType probably belongs inside this class.
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
        deviceAddress = getArguments().getString("device");

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
        //receiveText.setText("");
        msgBuffer = "";
        testType = TestType.Log;
        rowResult[outputRow].setBackgroundColor(Color.YELLOW);
        rowResult[outputRow].setText("Test in progress.");

        Log.d(TAG, "Sending in runCBASSLogTest");
        send("r", ExpectBLEData.Ack);
    }

    /**
     * Request a specified set of log lines so the response can be checked.
     * The argument is the number of lines, negative to work backward.
     * LogLog (ll) tells whether to start from the beginning, middle, or
     * end.  Of course some combinations cannot give the number of lines
     * requested, such as going backward from the start.
     */
    private void runCBASSBatchTest(int lines, LogLoc ll) {
        if (currentTest.active) {
            generalError("Previous test is still running!  Not started.");
            return;
        }
        // In this case we need values from an earlier test.
        if (!logTestRun && ll == LogLoc.Middle) {
            runCBASSLogTest();
            rowResult[outputRow].setBackgroundColor(Color.CYAN);
            rowResult[outputRow].setText("Running log test. Please repeat.");
        }

        savedData.clear();  // Start fresh.
        //receiveText.setText("");
        msgBuffer = "";
        testType = TestType.Lines;
        rowResult[outputRow].setBackgroundColor(Color.YELLOW);
        rowResult[outputRow].setText("Test in progress.");
        // Set the start location in milliseconds.
        // TODO: for Middle, get the actual range first to compute a better value.
        // This will also allow better checks for requests that normally return no lines.

        // Old code uses milliseconds, but now we have days from a baseline (around 8200) and
        // minutes from midnight (0-1439)
        // For end:
        int startD = 20000; // about 2054
        int startM = 1439;
        if (ll == LogLoc.Start) { startD = 7000; startM = 0; } // About 2019
        else if (ll == LogLoc.Middle) {
            /*
            if (d0 == dMax && t0 == tMax) {
                startD = d0; startM = t0;
            } else if (d0 == dMax) {
                startD = d0;
                startM = (int) (t0 + tMax)/2;
            } else {
                long st = (d0*1440 + t0 + dMax*1440 + tMax) / 2;
                startD = (int) (st / 1440);
                startM = (int) (st % 1440);
            }
             */
        }

        // Save test information for use when checking the result:
        currentTest.active = true;
        currentTest.linesRequested = Math.abs(lines);
        currentTest.linesExpected = currentTest.linesRequested;
        currentTest.forward = lines > 0;
        currentTest.logLoc = ll;
        currentTest.linesReturned = 0;

        // These are only some of the cases where we may expect fewer than requested lines.
        if (ll == LogLoc.Start && !currentTest.forward) currentTest.linesExpected = 0;
        if (ll == LogLoc.End && currentTest.forward) currentTest.linesExpected = 0;


        Log.d(TAG, "Sending in runCBASSLogTest");
        send("b," + lines + "," + startD + "," + startM, ExpectBLEData.Batch);
    }

    @Override
    public void onCreateOptionsMenu(@NonNull Menu menu, MenuInflater inflater) {
        super.onCreateOptionsMenu(menu, inflater);
        this.menu = menu;
        // Hide this fragment's own icon.
        menu.findItem(R.id.CBASS_tests).setVisible(false);

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
            // Note that this test was initially failing on the CBASS-R version, but after
            // deleting the GRAPHPTS.TXT file and restarting it was okay.
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
                // For these 2 use class variables so they can be referenced by other tests.
                t0 = parseLong(parts[0]);
                tMax = parseLong(parts[1]);
                int byteCount = parseInt(parts[2]);
                int lineSize = parseInt(parts[3]);
                rowLabel[outputRow].setText("File and timestamps");
                rowResult[outputRow].setBackgroundColor(Color.GREEN);

                rowResult[outputRow].setText("");
                // Typical time 0708099300
                boolean okay = myAssert(t0 > 708000000, "t0 too small: " + t0, rowResult[outputRow]);
                okay = okay && myAssert(t0 < 708000000+2*365*24*3600, "t0 too large: " + t0, rowResult[outputRow]);
                okay = okay && myAssert(byteCount % lineSize == 0, " File size " + byteCount + " is not a multiple of line size " + lineSize, rowResult[outputRow]);
                if (okay) rowResult[outputRow].setText("PASS");
                testType = TestType.Nothing;
                currentTest.reset();
                msgBuffer = "";
                logTestRun = true;
            }
        } else if (testType == TestType.Lines) {
            msgBuffer = msgBuffer + msg;
            // If the message is complete, check results.  Otherwise, just exit until more arrives.
            if (msgBuffer.endsWith("BatchDone")) {
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
                // Whether the test succeeds or not, reset state so another request/response won't
                // be blocked.
                testType = TestType.Nothing;
                msgBuffer = "";
                currentTest.reset();  // This sets active to false (and resets other things).
            }
        } else {
            generalError("response when none was expected");
            currentTest.reset();
            testType = TestType.Nothing;
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

        // A typical input is
        // T08188,0816,22.1,22.1,22.1,21.8,30.0,30.0,30.0,30.0
        // But prototype code may omit the first zero.
        // Also note that we may switch to seconds rather than minutes in the second
        // position, requiring one more digit.
        // For now, assume that we need at least
        final int lineLen = 5+5+5*8-1;
        int count = 0;
        String[] points;

        // Check some basic requirements.
        Log.d("PARSE", "Full input: " + buf);
        if (!buf.startsWith("T")) {
            throw new InputMismatchException("Batch must start with a T.");
        } else if (buf.length() < lineLen) {
            throw new InputMismatchException("Not enough data in buffer for a graph point. >" + buf + "<");
        }
        if (!buf.endsWith("BatchDone")) {
            throw new InputMismatchException("Batch must end with BatchDone.");
        }
        // Remove the leading T and trailing BatchDone.
        buf = buf.substring(1, buf.indexOf("B"));
        // Parse out the individual time points, but let TempPoint parse the time and temperatures.
        points = buf.split("T", 0);
        Log.d("PARSE", "Building " + points.length + " points.");
        for (String p: points) {
            // Let TempPoint do the remaining parsing so the code is the same here and in GraphFragment
            Log.d("PARSE", "Single line: " + p);
            TempPoint incomingPoint = new TempPoint(p);  // substring is inclusive/exclusive
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