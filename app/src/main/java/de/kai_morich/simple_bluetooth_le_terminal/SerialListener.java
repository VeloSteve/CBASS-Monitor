package de.kai_morich.simple_bluetooth_le_terminal;

// JSR changed privacy for use in a different package.
public interface SerialListener {
    void onSerialConnect      ();
    void onSerialConnectError (Exception e);
    void onSerialRead         (byte[] data);
    void onSerialIoError      (Exception e);
}
