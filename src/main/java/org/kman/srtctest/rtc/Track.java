package org.kman.srtctest.rtc;

import androidx.annotation.NonNull;

import java.util.Locale;

public class Track {

    Track(int trackId,
          int payloadType,
          int codec,
          int profileLevelId) {
        mTrackId = trackId;
        mPayloadType = payloadType;
        mCodec = codec;
        mProfileLevelId = profileLevelId;
    }

    public int getTrackId() {
        return mTrackId;
    }

    public int getPayloadType() {
        return mPayloadType;
    }

    public int getCodec() {
        return mCodec;
    }

    public int getProfileLevelId() {
        return mProfileLevelId;
    }

    @NonNull
    @Override
    public String toString() {
        return String.format(Locale.US,
                "Track(trackId=%d, payloadType=%d, codec=%d, profileLevelId=%x)",
                mTrackId, mPayloadType, mCodec, mProfileLevelId);
    }

    private final int mTrackId;
    private final int mPayloadType;
    private final int mCodec;
    private final int mProfileLevelId;
}
