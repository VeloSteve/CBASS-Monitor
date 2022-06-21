package info.pml.cbass_monitor;

import android.util.Log;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.Iterator;

/**
 * A list of temperature points typically in time order. Points can be added at
 * the end or at a specified index.  Those points have timestamps in minutes and
 * days, which this converts to minutes to keep track of the time of the first and
 * last point stored.  For future use, this also keeps track of when data was last added.
 */
public class TemperatureData extends ArrayList<TempPoint> {
    static final String TAG = "TemperatureData";

    // Time here will be in
    // Arduino-style seconds since 2000.
    private long firstTime = Long.MAX_VALUE;
    private long lastTime = Long.MIN_VALUE;
    private long localAddTime;  // Time at which data was received on this device.

    @Override
    public boolean add(TempPoint e) {
        firstTime = Math.min(firstTime, e.seconds2000());
        lastTime = Math.max(lastTime, e.seconds2000());
        Date date = new java.util.Date();
        localAddTime = date.getTime();
        return super.add(e);
    }
    @Override
    public void add(int index, TempPoint e) {
        super.add(index, e);
        firstTime = Math.min(firstTime, e.seconds2000());
        lastTime = Math.max(lastTime, e.seconds2000());
        Date date = new java.util.Date();
        localAddTime = date.getTime();
    }

    public long getFirstTime() {
        return firstTime;
    }

    public long getLastTime() {
        return lastTime;
    }

    public boolean isSorted() {
        Iterator<TempPoint> i = this.iterator();
        if (i.hasNext()) {
            TempPoint previous = i.next();
            while (i.hasNext()) {
                TempPoint current = i.next();
                if (previous.compareTo(current) > 0) {
                    return false;
                }
                previous = current;
            }
        }
        return true;
    }

    /**
     * Remove the oldest points to keep data storage bounded.
     * Work with time in seconds from 2000.
     *
     * @param maxMinutes the maximum minutes of historical data to keep
     * @return the number of points removed
     */
    public int trimRange(int maxMinutes) {
        long cutoff;
        if (this.size() > 0) {
            cutoff = lastTime - (long) maxMinutes * 60;
            Log.d(TAG, "Trimming with  cutoff = " + cutoff + ". maxMinutes = " + maxMinutes);
        } else {
            // We can't calculate a valid cutoff if the arraylist starts empty, so accept anything.
            //cutoff = Long.MIN_VALUE;
            // BETTER: use the current time
            Date date = new java.util.Date();
            long t = date.getTime(); // ms since 1970
            cutoff = t/1000 - TempPoint.SECONDS_FROM_1970_TO_2000 - (long)maxMinutes * 60;
            Log.d(TAG, "Trimming with date " + date + ", t = " + t + ", cutoff = " + cutoff + ". maxMinutes = " + maxMinutes);
        }

        int removed = 0;
        // Oldest first so we don't have to traverse the whole list.  Would
        // it be faster to reverse sort, delete from the end, and the sort again?
        Collections.sort(this);

        Iterator<TempPoint> it = this.iterator();
        TempPoint tp;
        while (it.hasNext()) {
            tp = (TempPoint)it.next();
            if(tp.seconds2000() < cutoff) {
                Log.d(TAG, "Removing point with time " + tp.seconds2000() + " with cutoff " + cutoff);
                it.remove();
                removed++;
            } else {
                break;  // There can be no more items below the cutoff.
            }
        }
        return removed;
    }

    @Override
    public void clear() {
        super.clear();
        firstTime = Long.MAX_VALUE;
        lastTime = Long.MIN_VALUE;
        localAddTime = 0;
    }
}
