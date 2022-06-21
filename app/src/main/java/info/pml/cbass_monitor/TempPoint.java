package info.pml.cbass_monitor;

import android.os.Parcel;
import android.os.Parcelable;
import android.util.Log;

import androidx.annotation.NonNull;

import java.util.Arrays;
import java.util.Date;
import java.util.InputMismatchException;
import java.util.TimeZone;

/**
 * Temperatures at a single point in time during an experiment.  This stores the time in
 * seconds since 1/1/2000 (Arduino style), measured temperatures, and target temperatures.
 *
 * Methods suffixed "2k" will use this as-is with no time zone awareness.
 * Methods suffixed "70" will offset the value to GMT using the local time zone of the
 *   device so that methods based on Date, Calendar, and the like will give the expect
 *   human-readable times.
 *
 * Times will be converted to unix-style seconds since 1970, or Date methods can be used
 * to get human-friendly forms.  NOTE that the Arduino library doesn't know about time
 * zones, so we'll apply the correction here, assuming that the Android device and the
 * Arduino are displaying the same time, and are in the same time zone.
 */
public class TempPoint implements Comparable<TempPoint>, Parcelable {
    static final String TAG = "TempPoint";
    // Private so be sure all access understands which time basis is wanted.
    private final long seconds2k;
    private final static long msOffset;

    final byte nTemps = 4;
    float[] measured = new float[nTemps];
    float[] target = new float[nTemps];
    // From RTClib:
    public static final long SECONDS_FROM_1970_TO_2000 = 946684800;

    /*
     * Set the default time zone and its offset in seconds.  Static so it's only run once.
     */
    static  {
        //zone = (SimpleTimeZone) SimpleTimeZone.getDefault();

        // The ms to add to UTC to get the current zone.
        // We will subtract to get UTC from current times.
        msOffset = TimeZone.getDefault().getOffset(System.currentTimeMillis());
        Log.d(TAG, "Time zone offset = " + msOffset + ", " + msOffset/1000/3600 + " in hours.");
    }


    /**
     * The typical constructor.
     * @param buf a string buffer with one line of Arduino output,
     * which contains days, minutes, and 8 temperatures, all comma-separated.
     */
    public TempPoint(String buf) {
        String [] parts = buf.split(",");
        if (parts.length != 9) {
            throw new InputMismatchException("Buffer must contain 1 time part and 8 temperatures. >" + buf + "<");
        }
        //Log.d(TAG, "Point string has " + parts.length + " items.");
        seconds2k = Long.parseLong(parts[0]);
        setMeasured(Arrays.copyOfRange(parts, 1, 5));  // Note that range is inclusive, exclusive
        setTarget(Arrays.copyOfRange(parts, 5, 9));
        //setTimeZone();
    }

    /* not used
    public TempPoint(long s) {
        // Just set the time, expecting temperatures in later BLE chunks.
        seconds2k = s;
        //setTimeZone();
    }
     */

    public TempPoint(long s, float m0, float m1, float m2, float m3,
                             float t0, float t1, float t2, float t3) {
        seconds2k = s;
        measured[0] = m0;
        measured[1] = m1;
        measured[2] = m2;
        measured[3] = m3;
        target[0] = t0;
        target[1] = t1;
        target[2] = t2;
        target[3] = t3;
        //setTimeZone();
    }

    /*  Not used
    public TempPoint(long s, float[] meas, float[] targ) {
        seconds2k = s;
        for (byte i=0; i<nTemps; i++) {
            measured[i] = meas[i];
            target[i] = targ[i];
        }
        //setTimeZone();
    }
     */

    public TempPoint(Parcel in) {
        seconds2k = in.readLong();
        measured = in.createFloatArray();
        target = in.createFloatArray();
        //setTimeZone();
    }

    public static final Creator<TempPoint> CREATOR = new Creator<TempPoint>() {
        @Override
        public TempPoint createFromParcel(Parcel in) {
            return new TempPoint(in);
        }

        @Override
        public TempPoint[] newArray(int size) {
            return new TempPoint[size];
        }
    };

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeLong(seconds2k);
        dest.writeFloatArray(measured);
        dest.writeFloatArray(target);
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public int compareTo(TempPoint other) {
        return Long.compare(this.seconds2k, other.seconds2k);
    }

    public void setMeasured(String [] parts) {
        for (int i = 0; i< nTemps; i++) {
            measured[i] = Float.parseFloat(parts[i]);
        }
    }
    public void setTarget(String [] parts) {
        for (int i = 0; i< nTemps; i++) {
            target[i] = Float.parseFloat(parts[i]);
        }
    }


    public long seconds2000() {
        return seconds2k;
    }

    public long seconds70() {
        return seconds2k + SECONDS_FROM_1970_TO_2000 - msOffset/1000;
    }

    public long msec70() {
        return (long)1000 * (seconds2k + SECONDS_FROM_1970_TO_2000) - msOffset;

    }

    @NonNull
    public String toString() {
        Date d = new Date(msec70());
        return "Point at " + d + " first temp = " + measured[0];
    }
}
