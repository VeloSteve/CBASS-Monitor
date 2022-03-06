package info.pml.cbass_monitor;

import android.os.Build;

import androidx.annotation.RequiresApi;

import java.util.ArrayList;
import java.util.Date;
import java.util.Iterator;

public class TemperatureData extends ArrayList<TempPoint> {
    private long firstTime = Long.MAX_VALUE;
    private long lastTime = Long.MIN_VALUE;
    private long localAddTime;  // Time at which data was received on this device.

    @Override
    public boolean add(TempPoint e) {
        firstTime = Math.min(firstTime, e.millis);
        lastTime = Math.max(lastTime, e.millis);
        Date date = new java.util.Date();
        localAddTime = date.getTime();
        return super.add(e);
    }
    @Override
    public void add(int index, TempPoint e) {
        super.add(index, e);
        firstTime = Math.min(firstTime, e.millis);
        lastTime = Math.max(lastTime, e.millis);
        Date date = new java.util.Date();
        localAddTime = date.getTime();
        return;
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
}
