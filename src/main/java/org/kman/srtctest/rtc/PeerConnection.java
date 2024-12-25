package org.kman.srtctest.rtc;

import org.kman.srtctest.Util;
import org.kman.srtctest.util.MyLog;

public class PeerConnection {

    public PeerConnection() {
        MyLog.i(TAG, "Create %s", getClass().getName());
    }

    public void release() {
        MyLog.i(TAG, "Release");
    }

    static {
        System.loadLibrary("srtctest");
    }

    private static final String TAG = "PeerConnection";
}
