package info.pml.cbass_monitor;

import static java.lang.Integer.parseInt;
import static java.lang.Long.parseLong;
import static java.lang.Math.min;

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
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.Collections;
import java.util.InputMismatchException;

import de.kai_morich.simple_bluetooth_le_terminal.SerialListener;
import de.kai_morich.simple_bluetooth_le_terminal.TextUtil;

public class TestCBASSFragment extends BLEFragment implements ServiceConnection, SerialListener {

    private final String TAG = "TestCBASSFragment";

    private View logButton;
    private View f3Button;
    private View l3Button;

    private final int numTests = 4;
    private final TextView[] rowLabel = new TextView[numTests];
    private final TextView[] rowResult = new TextView[numTests];
    private byte outputRow;
    private TextView receiveText;

    // The log test gets information useful for the other tests.
    // If running a log test, discard the old data so it's a valid
    // new tests, but for other tests we can re-use it.
    private boolean logTestRun = false;


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
    private static class TestInfo {
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
        // Time is seconds from 2000, about 0708099300 as this is written.

        // For end:
        long start = 708099300 + 10*365*24*3600; // about 2032
        if (ll == LogLoc.Start) { start = 608099300; } // well in the past
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
        send("b," + lines + "," + start, ExpectBLEData.Batch);
    }

    @Override
    public void onCreateOptionsMenu(@NonNull Menu menu, MenuInflater inflater) {
        super.onCreateOptionsMenu(menu, inflater);
        // Hide this fragment's own icon.
        menu.findItem(R.id.CBASS_tests).setVisible(false);

    }


    @Override
    void receive(byte[] data) {
        long t0, tMax; // Minutes since 2000
        String msg = new String(data);
        if (msg.startsWith("Coll")) {
            receiveText.append("Who sets " + msg + "?");
        }
        String[] parts;
        //  use inherited version. private Connected connected = Connected.False;
        String newline = TextUtil.newline_crlf;
        if(newline.equals(TextUtil.newline_crlf) && msg.length() > 0) {
            // don't show CR as ^M if directly before LF
            msg = msg.replace(TextUtil.newline_crlf, TextUtil.newline_lf);
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
                Collections.sort(savedData);  // The LineGraphSeries must be built in ascending order.

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
                    rr.setBackgroundColor(Color.GREEN);
                    if (lineCount > 0) {
                        rr.append(" times from " + savedData.getFirstTime() + " to " + savedData.getLastTime());
                    } else {
                        rr.append(" No data, as expected");
                    }
                } else {
                    rr.setBackgroundColor(Color.RED);
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

    /*
     * Errors not tied to a specific test go in a special row.
     */
    void generalError(String t) {
        receiveText.append(" ERROR: " + t);
        rowResult[numTests-1].setText("ERROR: " + t);
        rowResult[numTests-1].setBackgroundColor(Color.RED);
    }


    /**
     * NOTE: this should be the same code used in GraphFragment.  For better testing it would be
     * literally the same code from a shared class, but for now this is a copy.
     *
     * The input should be a complete batch of data to be graphed, consisting of lines
     * looking like.
     * T0709201449,22.3,21.7,21.9,21.6,30.0,30.0,30.0,30.0
     * There T -> time in seconds since 1970, next 4 -> measured temperatures, last 4 -> planned temperatures
     * They are received at this point without linefeeds, so "T" is the delimiter.
     *
     * This will parse out the lines, build TempPoints and append them to TemperatureData (tempData).
     *
     * Note that there has been no attempt to reduce data copying or memory use, on the assumption
     * that this is fast compared to BLE data transfer.  Check that assumption some time!
     * @param buf  All data received from the last BLE receipt.
     */
    private int parseBatch(String buf) {
        boolean usingDummy = false;
        final int lineLen = 5+5+5*8-1;
        String[] points;
        int count = 0;

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
        // Remove the leading T and trailing BatchDone  There may also be an "AT+BLEUARTTX=".
        if (buf.indexOf("AT") > 0) {
            buf = buf.substring(1, min(buf.indexOf("B"), buf.indexOf("AT")));
        } else {
            buf = buf.substring(1, buf.indexOf("B"));
        }
        Log.d("PARSE", "Input substring: " + buf);
        // Parse out the individual time points, but let TempPoint parse the time and temperatures.
        points = buf.split("T", 0);
        Log.d("PARSE", "Building " + points.length + " points.");
        if (usingDummy && !(points.length == 0)) {
            // New CBASS data must replace old "dummy" data from startup.
            savedData.clear();
            usingDummy = false;
        }
        for (String p: points) {
            // Let TempPoint do the remaining parsing so the code is the same here and in GraphFragment
            Log.d("PARSE", "Single line: " + p);
            if (p.length() < GraphFragment.bytesPerReturnedLine) {
                Log.w("PARSE", "Skipping a short temperature log line.");
            } else {
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
        }
        /* Not for these tests:
        // If more data is stored than we want to allow, delete the oldest points.
        // Assumes that maxHistory doesn't change (in preferences) between a request and response.
        int rem = savedData.trimRange(maxHistory);
        // If points were deleted the next request should look forward in time.
        if (rem > 0) noMoreOldData = true;  // no need to set addToEnd, which is set at each new request.

        Toast.makeText(getActivity(), savedData.size() + " points, " + count + " added, " + rem + " removed.", Toast.LENGTH_LONG).show();
        Log.d(TAG, savedData.size() + " points, " + count + " added, " + rem + " removed.");
         */
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