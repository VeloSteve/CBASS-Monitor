package info.pml.cbass_monitor;

import static java.lang.Math.min;

import android.app.Activity;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.os.Bundle;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.preference.ListPreference;
import androidx.preference.PreferenceManager;

import com.jjoe64.graphview.GraphView;
import com.jjoe64.graphview.GridLabelRenderer;
import com.jjoe64.graphview.helper.DateAsXAxisLabelFormatter;
import com.jjoe64.graphview.series.DataPoint;
import com.jjoe64.graphview.series.LineGraphSeries;

import static java.text.DateFormat.SHORT;
import java.text.DateFormat;
import java.util.Collections;
import java.util.Date;
import java.util.InputMismatchException;
import java.util.Map;
import java.util.Random;
import java.util.Timer;
import java.util.TimerTask;

import de.kai_morich.simple_bluetooth_le_terminal.TextUtil;

public class GraphFragment extends BLEFragment implements ServiceConnection {

    //private enum Connected { False, Pending, True }
    private final String TAG = "GraphFragment";
    //private String deviceAddress;

    //private Menu menu;

    private Button updateBtn;
    private Button repeatBtn;

    private String msgBuffer = "";

    private boolean addToEnd = true;
    private boolean noMoreOldData = false;


    // Don't update forever - it slows the temperature checks a bit.
    private final int maxUpdates = 65;
    private int updatesLeft = 0;  // Initialize to 0 so we don't think we are mid-repeat when getting an empty return.
    private final int fillDataMillis = 60000;  // 1/minute is planned, debugging w/ 10 seconds, 5 updates.

    // We can re-use the preferences object, but must get the values at the time of use in case of changes.
    SharedPreferences sharedPreferences;
    int oldMaxHistory = 0;  // So we can check backward if the maximum history value increases.
    int maxHistory = 0;  // So we can check backward if the maximum history value increases.
    Timer monTimer;  // For repeated calls for data
    TimerTask monTask;  // The repeated task.
    //Timer connCheckTimer; // For noticing when the connection is dropped.

    // Set up parameters for the data to show in a default graph.  After this works,
    // make a set of optional graphs.  For example, last 15 minutes in detail, full run to now, and full run including future plan.
    private TemperatureData savedData;
    private final int bytesPerReturnedLine = 50;
    // Data as used by the graph.
    private LineGraphSeries<DataPoint>[] graphData = new LineGraphSeries[8];
    private GraphView graph;
    private boolean[] seriesVisible = {true, true, true, true, true, true, true, true, true};
    private boolean usingDummy = false;

    /*
     * Lifecycle
     */

    /**
     * Save data for use after rotations, fragment swaps, and so on.
     */
    @Override
    public void onSaveInstanceState(@NonNull Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putSerializable("saved data", savedData);
        outState.putBoolean("dummy flag", usingDummy);
    }

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setHasOptionsMenu(true);
        // Best practice seems to be NOT to use the next line, at least unless there are AsyncTasks.
        // setRetainInstance(true); // When true onCreate is only called once.
        // TODO: exit if deviceAddress is null?
        // Maybe getActivity().getParentFragmentManager().beginTransaction().remove(this).commit();
        deviceAddress = getArguments().getString("device");

        // Some graphing options are stored as preferences.  Get the reference here, but
        // check values as they are needed.
        sharedPreferences = PreferenceManager.getDefaultSharedPreferences(getContext());
    }

    @Override
    public void onPause() {
        // Don't leave timers running.
        if (monTimer != null) monTimer.cancel();
        if (monTask != null) monTask.cancel();
        monTimer = null;
        monTask = null;
        // Set app name back to default (from device name)
        ((AppCompatActivity) getActivity()).getSupportActionBar().setTitle(getResources().getString(R.string.app_name));
        super.onPause();
    }

    @Override
    public void onStart() {
        super.onStart();
    }

    @Override
    public void onStop() {
        super.onStop();
    }

    @Override
    public void onAttach(@NonNull Activity activity) {
        super.onAttach(activity);
    }

    @Override
    public void onDetach() {
        try { getActivity().unbindService(this); } catch(Exception ignored) {}
        super.onDetach();
    }

    @Override
    public void onResume() {
        super.onResume();
        startUpdateTimer();
    }

    /*
     * UI
     */
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_graph, container, false);
        graph = view.findViewById(R.id.graph);

        // Three cases:
        // 1) First time here, we need to create some dummy data.
        // 2) On orientation changes we have a savedInstanceState from which data must be obtained.
        // 3) On switching back to this fragment after visiting another the data is apparently in
        //    place without a savedInstanceState.  Just move on.
        // Maybe I don't understand 2)!  We seem to get the savedInstanceState, but savedData is
        // not null.  Why restore it?
        if (savedInstanceState != null) {
            savedData = (TemperatureData) savedInstanceState.getSerializable("saved data");
            usingDummy = savedInstanceState.getBoolean("dummy flag");
        } else if (savedData == null) {
            // Just to exercise the new classes, build some points manually and then
            // use them to graph.
            // This can also be a "placeholder" until real data is obtained.
            savedData = new TemperatureData();
            addDummySavedData();
            usingDummy = true;
            // Add empty series in not already in state - we fill them later.
            /*
            if (graphData[0] == null) {
                for (int i = 0; i < 8; i++) {
                    graphData[i] = new LineGraphSeries<DataPoint>();
                    graph.addSeries(graphData[i]);
                }
                addDummyGraphData();
            }
             */
        }

        // Axis labels
        graph.getGridLabelRenderer().setHorizontalAxisTitle("Time of Day");
        graph.getGridLabelRenderer().setVerticalAxisTitle("Temperature (°C)");
        // Time formatting on horizontal axis
        DateFormat df = DateFormat.getTimeInstance(SHORT);
        graph.getGridLabelRenderer().setLabelFormatter(new DateAsXAxisLabelFormatter(getActivity(), df));
        graph.getGridLabelRenderer().setNumHorizontalLabels(3); // only 4 because of the space
        // Internal grids.
        graph.getGridLabelRenderer().setGridStyle(GridLabelRenderer.GridStyle.BOTH);
        graph.getGridLabelRenderer().setHumanRounding(false, true);

        Toast.makeText(getActivity(), graph.getViewport().getMinX(true) + " to " + graph.getViewport().getMaxX(true), Toast.LENGTH_LONG).show();
        graph.setTitle("Example");

        // Button Actions (what to send)

        // In the basic monitor update means to get the latest temperatures.  Here it means to
        // fetch as much data as needed to bring the graph up to date.
        updateBtn = view.findViewById(R.id.update_btn);
        updateBtn.setOnClickListener(    // OLD: v -> send("t"));
            new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    monTimer = null; // In case a repeating update was still running.
                    msgBuffer = ""; // start empty.  TODO: grey out the button while a batch is in progress
                    Log.d(TAG, "updateBtn send()");
                    send(getDataRequest(), ExpectBLEData.Batch);

            }
        });

        repeatBtn = view.findViewById(R.id.repeat_btn);
        repeatBtn.setOnClickListener(    // OLD: v -> send("t"));
                new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        expectBLE = ExpectBLEData.Batch;
                        updatesLeft = maxUpdates;
                        startUpdateRepeats(fillDataMillis);

                    }
                });

        Button measuredToggle = view.findViewById(R.id.buttonT1);
        setToggle(measuredToggle,0);
        measuredToggle = view.findViewById(R.id.buttonT2);
        setToggle(measuredToggle,1);
        measuredToggle = view.findViewById(R.id.buttonT3);
        setToggle(measuredToggle,2);
        measuredToggle = view.findViewById(R.id.buttonT4);
        setToggle(measuredToggle,3);

        CheckBox planToggle = view.findViewById(R.id.checkBox1);
        setToggle(planToggle, 4);
        planToggle = view.findViewById(R.id.checkBox2);
        setToggle(planToggle, 5);
        planToggle = view.findViewById(R.id.checkBox3);
        setToggle(planToggle, 6);
        planToggle = view.findViewById(R.id.checkBox4);
        setToggle(planToggle, 7);


        // Not always needed.  Helps on restore.
        updateGraph();
        return view;
    }

    void setToggle(Button b, int n) {
        b.setOnClickListener(
                new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        seriesVisible[n] = !seriesVisible[n];
                        if (seriesVisible[n]) b.setAlpha(1.0F);
                        else b.setAlpha(0.5F);

                        updateGraph();
                    }
                });
    }

    void startUpdateRepeats(long iv) {
        if (monTask != null) monTask.cancel();
        monTask = new TimerTask() {
            @Override
            public void run() {
                Log.d(TAG, "timerTask send()");
                send(getDataRequest(), ExpectBLEData.Batch);
                updatesLeft--;
            }
        };
        if (monTimer == null) {
            monTimer = new Timer();
        }
        monTimer.schedule(monTask, 5, iv);
    }

    /**
     * Depending on the graph option being shown, ask CBASS for any data needed to update the
     * graph.  For new we don't attempt to find and fill gaps in the data, only adding to the
     * beginning or end of the sequence in memory.
     *
     * The protocol is a comma-separated list of
     * - the letter "b"
     * - the maximum number of data points to return, negative if stepping back in time.
     * - the first timestamp of interest in seconds since 2000, typically starting one
     *   unit beyond the the existing data.
     *
     * @return the request to be sent to CBASS
     */
    private String getDataRequest() {
        // If we have no saved data we don't know where the CBASS timer might be.  Ask
        // for the newest lines.
        // Otherwise, we need to add to the start or end.  When a graph is new, work backwards
        // until the full time range required is needed.  When that is satisfied, append from the
        // last point forward.
        // TODO: detect and fill gaps in the sequence of locally saved data.
        long startSecond;

        // Requests take 84 ms constant plus 14.1 ms per point.
        // Using 50 lines, this is about 790 ms.  When using a default of one point every
        // 30 seconds this covers 25 minutes per request.
        // Note that if debug printing in the app is turned on this can be much slower.
        // Actual timings 13 Jun 2022: 50 lines, 668 ms; 50 lines 661 ms.
        // 100 lines, 1373 ms; 100 lines, 1413 ms.
        // New estimated rate: 58 ms constant, 14.5 ms per point

        long lines = 60;

        // If the historical time to save has increased, we have to check backwards in time even
        // if we previous obtained all desired old data and started moving forward.
        // First, see whether the graphed time has increased beyond the stored time, in which case
        // the stored time should increase.
       // int maxStore = Integer.parseInt(sharedPreferences.getString("displayed_history", "17"));
        maxHistory = Integer.parseInt(sharedPreferences.getString("maximum_history", "17"));

        /*
         * Handle the case where we want to graph more than we store?
        int maxDisp = Integer.parseInt(sharedPreferences.getString("displayed_history", "17"));
        if (maxHistory < maxDisp) {

        }
         */


        if (maxHistory > oldMaxHistory && !usingDummy) {
            noMoreOldData = false;
        }
        oldMaxHistory = maxHistory;


        if (savedData.size() > 0 && !usingDummy) {
            //long spanStored = savedData.getLastTime() - savedData.getFirstTime();
            // See if we need older or newer data and offset the start by one
            // unit from the last point in the chosen direction.
            if (noMoreOldData) {
                startSecond = savedData.getLastTime() + 1;
                addToEnd = true;
            } else {
                lines = -lines;
                startSecond = savedData.getFirstTime() - 1;
                // So we know where to put incoming lines.
                addToEnd = false;
            }
        } else {
            // Starting at a huge time, working backward.
            noMoreOldData = false;  // Starting assumptions
            addToEnd = false;
            startSecond = 999999999; // about 2031
            lines = -lines;
        }
        return "b," + lines + "," + startSecond;
    }

    @Override
    public void onCreateOptionsMenu(@NonNull Menu menu, MenuInflater inflater) {
        super.onCreateOptionsMenu(menu, inflater);
        //this.menu = menu;
        // Hide this fragment's own icon.
        menu.findItem(R.id.graph).setVisible(false);

    }


    @Override
    void receive(byte[] data) {

        String msg = new String(data);
        //private Connected connected = Connected.False;
        //String newline = TextUtil.newline_crlf;
        //if(newline.equals(TextUtil.newline_crlf) && msg.length() > 0) {
        // removed "if" - always active.
        if(msg.length() > 0) {
            // don't show CR as ^M if directly before LF
            msg = msg.replace(TextUtil.newline_crlf, TextUtil.newline_lf);
        }

        if (expectBLE == ExpectBLEData.Batch) {
            // Append to a buffer to collect all the data for one batch.
            msgBuffer = msgBuffer + msg;
            Log.d("COLLECT", "msg is now >" + msg + "<");
            if (msgBuffer.endsWith("BatchDone")) {
                expectBLE = ExpectBLEData.Nothing;
                // If it also starts with BatchDone, no lines matched the request.
                // Otherwise parse, normally expecting a > 0 response.
                if (!msgBuffer.startsWith("BatchDone") && parseBatch(msgBuffer) > 0) {
                    updateGraph();
                } else {
                    // No new lines.  If we are in the state of adding to the end, no action.
                    // There may just be nothing new yet.
                    // Otherwise, "turn" around and send a forward request.  This should happen only
                    // once per graphs.
                    if (!addToEnd) {
                        addToEnd = true;
                        noMoreOldData = true;

                        msgBuffer = "";
                        // Restart multiple requests if in progress, otherwise send
                        // one request to get data from the end, if any.
                        if (updatesLeft > 0) {
                            // 1/minute is planned, debugging w/ 30 seconds, 5 updates.
                            int appendDataMillis = Integer.parseInt(sharedPreferences.getString("update_rate", "17"));
                            Log.d("COLLECT", "Changing repeat interval to " + appendDataMillis + " ms.");
                            startUpdateRepeats(appendDataMillis);
                        } else {
                            send(getDataRequest(), ExpectBLEData.Batch);
                        }
                    }
                }
                msgBuffer = "";
            }
        } else {
            Log.w(TAG, "Unexpected data expectation for this class: " + expectBLE.toString());
        }
    }

    /**
     * The input should be a complete batch of data to be graphed, consisting of lines
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
            if (p.length() < bytesPerReturnedLine) {
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
        // If more data is stored than we want to allow, delete the oldest points.
        // Assumes that maxHistory doesn't change (in preferences) between a request and response.
        int rem = savedData.trimRange(maxHistory);
        // If points were deleted the next request should look forward in time.
        if (rem > 0) noMoreOldData = true;  // no need to set addToEnd, which is set at each new request.

        Toast.makeText(getActivity(), savedData.size() + " points, " + count + " added, " + rem + " removed.", Toast.LENGTH_LONG).show();
        Log.d(TAG, savedData.size() + " points, " + count + " added, " + rem + " removed.");
        return count;
    }

    private void updateGraph() {
        // Update all curves.  Note that the appendData method only accepts points at the end of
        // the array in correct order.  It should be possible to implement a "prependData" method,
        // but to get started just replace the series.
        // Even replacement may be a problem!  Just rebuild the whole graph when working backwards
        // in time.  Appending when working forwards should be much smoother.

            //LineGraphSeries<DataPoint>[] s = new LineGraphSeries[8];
        //Collections.sort(savedData, Collections.reverseOrder());  // The LineGraphSeries must be built in ascending order.
        Collections.sort(savedData);  // The LineGraphSeries must be built in ascending order.
        if (savedData.size() > 1) {
            Log.d("UPDATE", "sD[0] timestamp = " + savedData.get(0).seconds2000());
            Log.d("UPDATE", "sD[1] timestamp = " + savedData.get(1).seconds2000());
        }

            // Does emptying the original copy of the series erase or "break" the graph?
        for (int i = 0; i < 8; i++) {
            graphData[i] = new LineGraphSeries<DataPoint>();
            graphData[i].setDrawDataPoints(true);

        }
        // Note that DataPoint expects x (if it is a time) in milliseconds since 1970, but as a double.
        TempPoint p;
        Date d = new Date(); // Re-use since DataPoint just extracts the time again.
        // We only want to look back some maximum number of minutes.  Whether this should be
        // from present moment or the last data point is arguable, but this is easy and may be what's
        // wanted.
        int maxMinutesDisplayed = Integer.parseInt(sharedPreferences.getString("displayed_history", "17"));
        long cutoff = savedData.getLastTime() - (long)maxMinutesDisplayed * 60;
        for (int j = 0; j < savedData.size(); j++) {
            p = savedData.get(j);
            // Skip any points before the window we want to display.
            if (p.seconds2000() < cutoff) {
                continue;
            }
            d.setTime(p.msec70());
            for (int i = 0; i < 4; i++) {
                // maxDataPoints is just set to a large value because we manage that elsewhere.
                graphData[i].appendData(new DataPoint(d, p.measured[i]), true, 5000, true);
                graphData[i + 4].appendData(new DataPoint(d, p.target[i]), true, 5000, true);
            }
        }
        graph.removeAllSeries();
        // XXX why check for size after removeAllSeries?
        if (graph.getSeries().size() == 0) {
            for (int i = 0; i < 8; i++) {
                if (seriesVisible[i]) {
                    graph.addSeries(graphData[i]);
                    // Didn't work when called during series creation.  Maybe after adding is better?
                    switch (i % 4) {
                        case 0:
                            //graphData[i].setColor(getResources().getColor(R.color.colorTank1));
                            graphData[i].setColor(gc(R.color.colorTank1));
                            break;
                        case 1:
                            graphData[i].setColor(getResources().getColor(R.color.colorTank2));
                            break;
                        case 2:
                            graphData[i].setColor(getResources().getColor(R.color.colorTank3));
                            break;
                        case 3:
                            graphData[i].setColor(getResources().getColor(R.color.colorTank4));
                            break;
                        default:
                            graphData[i].setColor(Color.RED);
                    }
                    graphData[i].setDrawDataPoints(i < 4);
                }

            }
        } else if (graph.getSeries().size() != 8) {
            Toast.makeText(getActivity(), "bad series count.  Always 0 or 8.  Got " + graph.getSeries().size(), Toast.LENGTH_LONG).show();
        }

        // Auto-scaling the X axis doesn't work well for dates.  Try choosing limits that will
        // work well and align nice-looking values with the grids.
        // We currently display 15, 60, or 180 minutes, and larger, always up to current time.
        if (!usingDummy) {
            graph.getViewport().setXAxisBoundsManual(true);
            graph.getGridLabelRenderer().setNumHorizontalLabels(3); // only 4 because of the space
            Date now = new Date();
            Log.d(TAG, "Scaling graph based on current time " + now);
            Date limit = now;
            // Round up to minutes.
            if (limit.getSeconds() > 0) {
                limit.setMinutes(limit.getMinutes() + 1);
                limit.setSeconds(0);
            }
            int minutes = limit.getMinutes();
            if (maxMinutesDisplayed < 60) {
                // Up to nearest 5 minutes
                if (minutes > 55) {
                    limit.setMinutes(0);
                    limit.setHours(limit.getHours() + 1);
                } else if (minutes % 5 > 0) {
                    limit.setMinutes(minutes + (5 - minutes % 5));
                }
                Log.d(TAG, "Scaling graph to right limit  " + limit);
                graph.getViewport().setMaxX(limit.getTime());
                // Now set the lower limit.  If we go 5 minutes longer than the
                // specified range this will always include the requested data and not jump
                // between (say) 15 and 20 minute windows.
                // We don't need to count minutes and hours because the value is in ms.
                graph.getViewport().setMinX(limit.getTime() - (maxMinutesDisplayed + 5) * 60000);
            } else if (maxMinutesDisplayed == 60) {
                // Up to nearest 15 minutes for 60-minute graph.
                if (minutes % 5 > 0) {
                    limit.setMinutes(minutes + (15 - minutes % 15));
                }
                Log.d(TAG, "Scaling graph to right limit  " + limit);
                graph.getViewport().setMaxX(limit.getTime());
                // Now set the lower limit cover 75 minutes.
                graph.getViewport().setMinX(limit.getTime() - (maxMinutesDisplayed + 15) * 60000);
            } else if (maxMinutesDisplayed > 60) {
                // Up to nearest hour minutes graphs longer than an hour.
                if (minutes > 0) {
                    limit.setHours(1 + limit.getHours());
                    limit.setMinutes(0);
                }
                Log.d(TAG, "Scaling graph to right limit  " + limit);
                graph.getViewport().setMaxX(limit.getTime());
                // Now set the lower limit cover 75 minutes.
                graph.getViewport().setMinX(limit.getTime() - (maxMinutesDisplayed + 60) * 60000);
            } else {
                Log.e(TAG, "Unsupported x axis range");
            }
        }


        graph.onDataChanged(true, false);
    }

    /**
     * A little helper for getColor, since the original was deprecated and I don't want
     * to assume that the workaround will always be acceptable.
     */
    int gc(int id) {
        //getResources().getColor(id);
        return ContextCompat.getColor(getContext(), id);
    }

    /**
     * Change color and activity status of all buttons affected by whether there is a current
     * connection.
     * @param isConnected true if Bluetooth LE is connected
     */
    @Override
    void updateButtons(boolean isConnected) {
        super.updateButtons(isConnected);
        if (updateBtn == null || repeatBtn == null) return;
        // Without the thread this causes a crash!  Something about looper threads...
        requireActivity().runOnUiThread(new Runnable() {
            @Override
            public void run() {
                updateBtn.setEnabled(isConnected);
                repeatBtn.setEnabled(isConnected);
            }
        });
    }

    void addDummySavedData() {
        int sample = 20;
        // From RTClib: #define SECONDS_FROM_1970_TO_2000 946684800
        long secs = 708020880; // seconds since 2000, approximately
        Random r = new Random();
        TempPoint tp;
        float t = 24;
        for (int j = 0; j < sample; j++) {
            tp = new TempPoint(secs, t, t+1, t+2, t+3, t, t-1, t-2, t-3);
            savedData.add(tp);
            secs = secs + 20*60;
            t = t + r.nextInt(5) - 2;
        }
    }
}