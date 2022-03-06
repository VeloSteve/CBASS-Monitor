package info.pml.cbass_monitor;

/**
 * Temperatures at a single point in time during an experiment.  This stores the time in
 * milliseconds since start, measured temperatures, and target temperatures.
 */
public class TempPoint implements Comparable<TempPoint> {
    long millis = 0;
    final byte nTemps = 4;
    float[] measured = new float[nTemps];
    float[] target = new float[nTemps];

    public TempPoint(long m) {
        // Just set the time, expecting temperatures in later BLE chunks.
        millis = m;
    }
    public TempPoint(String m) {
        millis = new Long(m);
    }

    public TempPoint(long m, float m0, float m1, float m2, float m3,
                             float t0, float t1, float t2, float t3) {
        millis = m;
        measured[0] = m0;
        measured[1] = m1;
        measured[2] = m2;
        measured[3] = m3;
        target[0] = t0;
        target[1] = t1;
        target[2] = t2;
        target[3] = t3;
    }

    public TempPoint(long m, float[] meas, float[] targ) {
        millis = m;
        for (byte i=0; i<nTemps; i++) {
            measured[i] = meas[i];
            target[i] = targ[i];
        }
    }

    @Override
    public int compareTo(TempPoint other) {
        if (other.millis > this.millis) return -1;
        else if (other.millis < this.millis) return 1;
        else return 0;
    }

    public void setMeasured(String [] parts) {
        for (int i = 0; i< nTemps; i++) {
            measured[i] = new Float(parts[i]);
        }
    }
    public void setTarget(String [] parts) {
        for (int i = 0; i< nTemps; i++) {
            target[i] = new Float(parts[i]);
        }
    }

    public double getMinutes() {
        return (double)millis / 60000;
    }
}
