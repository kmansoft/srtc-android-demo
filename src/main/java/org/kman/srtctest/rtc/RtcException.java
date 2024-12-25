package org.kman.srtctest.rtc;

import androidx.annotation.NonNull;

public class RtcException extends Exception {

    public int getCode() {
        return mCode;
    }

    private RtcException(int code, @NonNull String message) {
        super(message);
        mCode = code;
    }

    private final int mCode;
}
