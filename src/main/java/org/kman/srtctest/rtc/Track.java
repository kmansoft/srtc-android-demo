package org.kman.srtctest.rtc;

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

    private final int mTrackId;
    private final int mPayloadType;
    private final int mCodec;
    private final int mProfileId;
    private final int mLevel;
}
