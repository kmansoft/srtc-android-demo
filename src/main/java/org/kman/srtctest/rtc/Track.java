package org.kman.srtctest.rtc;

import androidx.annotation.NonNull;

import java.util.Locale;

public class Track {

    Track(int trackId,
          int payloadType,
          int codec,
          int profileId,
          int level) {
        mTrackId = trackId;
        mPayloadType = payloadType;
        mCodec = codec;
        mProfileId = profileId;
        mLevel = level;
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

    public int getProfileId() {
        return mProfileId;
    }

    public int getLevel() {
        return mLevel;
    }

    @NonNull
    @Override
    public String toString() {
        return String.format(Locale.US,
                "Track(trackId=%d, payloadType=%d, codec=%d, profileId=%d, level=%d)",
                mTrackId, mPayloadType, mCodec, mProfileId, mLevel);
    }

    private final int mTrackId;
    private final int mPayloadType;
    private final int mCodec;
    private final int mProfileId;
    private final int mLevel;
}
