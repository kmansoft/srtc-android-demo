package org.kman.srtctest.rtc;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.kman.srtctest.util.MyLog;

import java.util.ArrayList;
import java.util.List;

public class PeerConnection {

    public PeerConnection() {
        MyLog.i(TAG, "Create %s", getClass().getName());
        mHandle = createImpl();
    }

    public void release() {
        MyLog.i(TAG, "Release");
        synchronized (mLock) {
            if (mHandle != 0L) {
                releaseImpl(mHandle);
                mHandle = 0L;
            }
        }
    }

    /*
     * Definitions for createPublishOffer
     */

    public static final int VIDEO_CODEC_H264 = 0;

    public static final int AUDIO_CODEC_OPUS = 0;

    public static class OfferConfig {
    }

    public static class VideoLayer {
        public int codec;
        public int profileId;
        public int level;
        // Below are for Simulcast only
        @Nullable
        public String name;
        public int width;
        public int height;
        public int bitsPerSecond;
    }

    public static class VideoConfig {
        @NonNull
        public String cname = "";
        @NonNull
        public VideoLayer[] layerList = new VideoLayer[0];
    }

    public static class AudioConfig {
        int codec;
    }

    @NonNull
    public String initPublishOffer(@NonNull OfferConfig config,
                                   @NonNull VideoConfig video,
                                   @Nullable AudioConfig audio) throws RtcException {
        synchronized (mLock) {
            return initPublishOfferImpl(mHandle, config, video, audio);
        }
    }

    static {
        System.loadLibrary("srtctest");
    }

    private static final String TAG = "PeerConnection";

    private native long createImpl();

    private native void releaseImpl(long handle);

    private native String initPublishOfferImpl(long handle,
                                               @NonNull OfferConfig config,
                                               @NonNull VideoConfig video,
                                               @Nullable AudioConfig audio) throws RtcException;

    private final Object mLock = new Object();
    private long mHandle;
}
