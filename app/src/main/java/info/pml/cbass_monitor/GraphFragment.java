package info.pml.cbass_monitor;

import android.app.Activity;
import android.content.ServiceConnection;
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

import com.jjoe64.graphview.GraphView;
import com.jjoe64.graphview.series.DataPoint;
import com.jjoe64.graphview.series.LineGraphSeries;

import java.util.Arrays;
import java.util.Collections;
import java.util.InputMismatchException;
import java.util.Timer;
import java.util.TimerTask;

import de.kai_morich.simple_bluetooth_le_terminal.TextUtil;

public class GraphFragment extends BLEFragment implements ServiceConnection {

    //private enum Connected { False, Pending, True }

    private String deviceAddress;

    private Menu menu;

    private View updateBtn;
    private View repeatBtn;

    //private Connected connected = Connected.False;
    private boolean initialStart = true;
    private boolean pendingNewline = false;
    private String newline = TextUtil.newline_crlf;

    private String msgBuffer = "";

    private enum ExpectBLEData {
        Nothing,
        Batch
    }
    private ExpectBLEData expectBLE = ExpectBLEData.Nothing;

    private enum BatchState {
        Time,
        Measured,
        Planned,
        Nothing
    }
    BatchState batchState = BatchState.Time;
    private boolean addToEnd = true;
    private boolean noMoreOldData = false;


    // Don't update forever - it slows the temperature checks a bit.
    private final int maxUpdates = 65;
    private int updatesLeft = maxUpdates;
    private final int fillDataMillis = 10000;  // 1/minute is planned, debugging w/ 10 seconds, 5 updates.
    private final int appendDataMillis = 60000;  // 1/minute is planned, debugging w/ 10 seconds, 5 updates.
    Timer monTimer;  // For repeated calls for data
    TimerTask monTask;  // The repeated task.
    Timer connCheckTimer; // For noticing when the connection is dropped.

    // Set up parameters for the data to show in a default graph.  After this works,
    // make a set of optional graphs.  For example, last 15 minutes in detail, full run to now, and full run including future plan.
    private TemperatureData savedData;
    private final int graphedDuration = 60 * 30; // 30 minutes, in seconds.
    // Data as used by the graph.
    private LineGraphSeries<DataPoint>[] graphData = new LineGraphSeries[8];
    private GraphView graph;
    private boolean seriesVisible[] = {true, true, true, true, true, true, true, true, true};

    /*
     * Lifecycle
     */
    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setHasOptionsMenu(true);
        setRetainInstance(true);
        // TODO: exit if deviceAddress is null?
        // Maybe getActivity().getParentFragmentManager().beginTransaction().remove(this).commit();
        deviceAddress = getArguments().getString("device");
    }

    @Override
    public void onPause() {
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

    @SuppressWarnings("deprecation") // onAttach(context) was added with API 23. onAttach(activity) works for all API versions
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
    }

    /*
     * UI
     */
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_graph, container, false);


        // Just to exercise the new classes, build some points manually and then
        // use them to graph.
        savedData = new TemperatureData();

        graph = (GraphView) view.findViewById(R.id.graph);
        // Add empty series - we fill them later.
        for (int i = 0; i < 8; i++) {
            graphData[i] = new LineGraphSeries<DataPoint>();
            graph.addSeries(graphData[i]);
        }
        graph.getGridLabelRenderer().setHorizontalAxisTitle("Arduino Run Time (min)");
        graph.getGridLabelRenderer().setVerticalAxisTitle("Temperature (°C)");


        // TODO: this fixes the failure of GraphView to correctly scale the X axis, but it messes
        // up the labels.  Preferably, fix the autoscaling and remove the next two lines. Second choice, fix the labels.
        //graph.getViewport().setXAxisBoundsManual(true);
        //graph.getViewport().setMaxX(savedData.getLastTime());

        // Shows correct X range, but when it is 1000 to 2500 the graph only plots 1000 to 1800!
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
                    expectBLE = ExpectBLEData.Batch;
                    msgBuffer = ""; // start empty.  TODO: grey out the button while a batch is in progress
                    send(getDataRequest());

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
                expectBLE = ExpectBLEData.Batch;
                send(getDataRequest());
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
     * graph.  The protocol is a comma-separated list of
     * - the letter "m"
     * - the maximum number of data points to return, negative if stepping back in time.
     * - the first timestamp of interest in CBASS milliseconds. Typically one more or less
     *   than in the existing data.
     *
     * @return
     */
    private String getDataRequest() {
        // If we have no saved data we don't know where the CBASS timer might be.  Ask
        // for the newest lines.
        // Otherwise, we need to add to the start or end.  When a graph is new, work backwards
        // until the full time range required is needed.  When that is satisfied, append from the
        // last point forward.
        // TODO: detect and fill gaps in the sequence of locally saved data.
        long start = 100000000;  // this may have been excessive: Long.MAX_VALUE;
        // A 50-line request works fine (minimal testing), but takes 5324 millis, which is
        // perhaps longer than we should block other CBASS actions.  But that was with debug prints.
        // Without debug the rate is quite linear with 84 ms constant plus 14.1 ms per point.
        // Use 50 lines, which should take about 790 ms.  When using a default of one point every
        // 30 seconds this covers 25 minutes per request.
        long lines = 50;
        if (savedData.size() > 0) {
            // See if we need older data.
            long spanStored = savedData.getLastTime() - savedData.getFirstTime();
            if (graphedDuration * 1000 > spanStored && !noMoreOldData) {
                lines = -lines;
                // So we know where to put incoming lines.
                addToEnd = false;
                start = savedData.getFirstTime() - 1;
            } else {
                addToEnd = true;
                start = savedData.getLastTime() + 1;
            }
        } else {
            // Starting at a huge time, working backward.
            lines = -lines;
        }
        return "b," + lines + "," + start;
    }

    @Override
    public void onCreateOptionsMenu(@NonNull Menu menu, MenuInflater inflater) {
        super.onCreateOptionsMenu(menu, inflater);
        this.menu = menu;
        // Hide this fragment's own icon.
        menu.findItem(R.id.graph).setVisible(false);

    }


    @Override
    void receive(byte[] data) {

        String msg = new String(data);
        if(newline.equals(TextUtil.newline_crlf) && msg.length() > 0) {
            // don't show CR as ^M if directly before LF
            msg = msg.replace(TextUtil.newline_crlf, TextUtil.newline_lf);

            pendingNewline = msg.charAt(msg.length() - 1) == '\r';
        }

        if (expectBLE == ExpectBLEData.Batch) {
            // Append to a buffer to collect all the data for one batch.
            msgBuffer = msgBuffer + msg;
            Log.d("COLLECT", "msg is now >" + msg + "<");
            if (msgBuffer.endsWith("BatchDone")) {
                if (parseBatch(msgBuffer) > 0) {
                    updateGraph();
                } else {
                    // No new lines.  If we are in the state of adding to the end, no action.
                    // There may just be nothing new yet.
                    // Otherwise, "turn" around and send a forward request.  This should happen only
                    // once per graphs.
                    if (!addToEnd) {
                        addToEnd = true;
                        noMoreOldData = true;

                        expectBLE = ExpectBLEData.Batch;
                        msgBuffer = "";
                        // Restart multiple requests if in progress, otherwise send
                        // one request to get data from the end, if any.
                        if (updatesLeft > 0) {
                            Log.d("COLLECT", "Changing repeat interval to " + appendDataMillis + " ms.");
                            startUpdateRepeats(appendDataMillis);
                        } else {
                            send(getDataRequest());
                        }
                    }
                }
                msgBuffer = "";
                expectBLE = ExpectBLEData.Nothing;
            }

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
            Log.d("UPDATE", "sD[0] millis = " + savedData.get(0).millis);
            Log.d("UPDATE", "sD[1] millis = " + savedData.get(1).millis);
        }

            // Does emptying the original copy of the series erase or "break" the graph?
        for (int i = 0; i < 8; i++) {
            graphData[i] = new LineGraphSeries<DataPoint>();
            graphData[i].setDrawDataPoints(true);

        }
        TempPoint p;
        for (int i = 0; i < 4; i++) {
            for (int j = 0; j < savedData.size(); j++) {
                p = savedData.get(j);
                graphData[i].appendData(new DataPoint((double) p.getMinutes(), p.measured[i]), true, 500, true);
                graphData[i + 4].appendData(new DataPoint((double) p.getMinutes(), p.target[i]), true, 500, true);
            }
        }
        graph.removeAllSeries();
        //}
        if (graph.getSeries().size() == 0) {
            for (int i = 0; i < 8; i++) {
                if (seriesVisible[i]) {
                    graph.addSeries(graphData[i]);
                    // Didn't work when called during series creation.  Maybe after adding is better?
                    switch (i % 4) {
                        case 0:
                            graphData[i].setColor(getResources().getColor(R.color.colorTank1));
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
        // Toast.makeText(getActivity(), "Updating Graph", Toast.LENGTH_LONG).show();

        graph.onDataChanged(true, false);
    }


    /**
     * Change color and activity status of all buttons affected by whether there is a current
     * connection.
     * @param isConnected
     */
    @Override
    void updateButtons(boolean isConnected) {
        if (isConnected) {
            updateBtn.setActivated(true);
            repeatBtn.setActivated(true);
        } else {
            if (updateBtn != null) updateBtn.setActivated(false);
            if (repeatBtn != null) repeatBtn.setActivated(false);
        }
        super.updateButtons(isConnected);
    }

}