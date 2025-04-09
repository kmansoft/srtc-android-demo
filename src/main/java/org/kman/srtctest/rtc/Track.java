package org.kman.srtctest.rtc;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.Locale;

public class Track {

    Track(int trackId,
          int payloadId,
          int codec,
          int profileLevelId,
          @Nullable SimulcastLayer layer) {
        mTrackId = trackId;
        mPayloadId = payloadId;
        mCodec = codec;
        mProfileLevelId = profileLevelId;
        mLayer = layer;
    }

    public int getTrackId() {
        return mTrackId;
    }

    public int getPayloadId() {
        return mPayloadId;
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
                "Track(trackId=%d, payloadId=%d, codec=%d, profileLevelId=%x, layer=%s)",
                mTrackId, mPayloadId, mCodec, mProfileLevelId, mLayer);
    }

    private final int mTrackId;
    private final int mPayloadId;
    private final int mCodec;
    private final int mProfileLevelId;
    private final SimulcastLayer mLayer;
}
