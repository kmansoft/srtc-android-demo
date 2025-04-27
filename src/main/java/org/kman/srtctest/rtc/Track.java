package org.kman.srtctest.rtc;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.Locale;

public class Track {

    public static class CodecOptions {
        public CodecOptions(int profileLevelId,
                            int minptime,
                            boolean stereo) {
            this.profileLevelId = profileLevelId;
            this.minptime = minptime;
            this.stereo =  stereo;
        }

        @NonNull
        @Override
        public String toString() {
            return String.format(Locale.US,
                    "CodecOptions(profileLevelid=%x, minptime=%d, stereo=%b)",
                    profileLevelId, minptime, stereo);
        }

        public int profileLevelId;
        public int minptime;
        public boolean stereo;
    }

    Track(int trackId,
          int payloadId,
          int codec,
          @Nullable CodecOptions codecOptions,
          @Nullable SimulcastLayer simulcastLayer) {
        mTrackId = trackId;
        mPayloadId = payloadId;
        mCodec = codec;
        mCodecOptions = codecOptions;
        mSimulcastLayer = simulcastLayer;
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

    public @Nullable CodecOptions getCodecOptions() {
        return mCodecOptions;
    }

    public @Nullable SimulcastLayer getSimulcastLayer() {
        return mSimulcastLayer;
    }

    @NonNull
    @Override
    public String toString() {
        return String.format(Locale.US,
                "Track(trackId=%d, payloadId=%d, codec=%d, codecOptions=%s, layer=%s)",
                mTrackId, mPayloadId, mCodec, mCodecOptions, mSimulcastLayer);
    }

    private final int mTrackId;
    private final int mPayloadId;
    private final int mCodec;
    private final CodecOptions mCodecOptions;
    private final SimulcastLayer mSimulcastLayer;
}
