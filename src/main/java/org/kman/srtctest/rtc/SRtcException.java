package org.kman.srtctest.rtc;

import androidx.annotation.NonNull;

public class SRtcException extends Exception {

    public int getCode() {
        return mCode;
    }

    private SRtcException(int code, @NonNull String message) {
        super(message);
        mCode = code;
    }

    private final int mCode;
}
