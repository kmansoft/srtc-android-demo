package org.kman.srtctest.rtc;

import android.os.Handler;
import android.os.Looper;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.kman.srtctest.util.MyLog;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

public class PeerConnection {

    public PeerConnection() {
        MyLog.i(TAG, "Create %s", getClass().getName());
        mHandle = createImpl();
    }

    public void release() {
        MyLog.i(TAG, "Release");
        synchronized (mHandleLock) {
            if (mHandle != 0L) {
                releaseImpl(mHandle);
                mHandle = 0L;
            }
        }
    }

    /*
     * Definitions for publish offer and answer
     */

    public static final int VIDEO_CODEC_H264 = 1;

    public static final int AUDIO_CODEC_OPUS = 100;

    public static class OfferConfig {
        @NonNull
        public String cname = "";
    }

    public static class PubVideoCodecConfig {
        public PubVideoCodecConfig(int codec, int profileLevelId) {
            this.codec = codec;
            this.profileLevelId = profileLevelId;
        }

        public int codec;
        public int profileLevelId;
    }

    public static class PubVideoConfig {
        public final ArrayList<PubVideoCodecConfig> list = new ArrayList<>();
    }

    public static class PubAudioCodecConfig {
        PubAudioCodecConfig(int codec) {
            this.codec = codec;
        }

        public int codec;
    }

    public static class PubAudioConfig {
        @NonNull
        public ArrayList<PubAudioCodecConfig> list = new ArrayList<>();
    }

    @NonNull
    public String initPublishOffer(@NonNull OfferConfig config,
                                   @Nullable PubVideoConfig video,
                                   @Nullable PubAudioConfig audio) throws SRtcException {
        synchronized (mHandleLock) {
            return initPublishOfferImpl(mHandle, config, video, audio);
        }
    }

    public void setPublishAnswer(@NonNull String answer) throws SRtcException {
        synchronized (mHandleLock) {
            setPublishAnswerImpl(mHandle, answer);
        }
    }

    public Track getVideoTrack() {
        synchronized (mHandleLock) {
            return mVideoTrack;
        }
    }

    public Track getAudioTrack() {
        synchronized (mHandleLock) {
            return mAudioTrack;
        }
    }

    // Connection state

    public static final int CONNECTION_STATE_NONE = 0;
    public static final int CONNECTION_STATE_CONNECTING = 1;
    public static final int CONNECTION_STATE_CONNECTED = 2;
    public static final int CONNECTION_STATE_FAILED = 100;
    public static final int CONNECTION_STATE_CLOSED = 200;

    public interface ConnectionStateListener {
        void onConnectionState(int state);
    }

    public void setConnectionStateListener(ConnectionStateListener listener) {
        synchronized (mListenerLock) {
            mConnectionStateListener = listener;
        }
    }

    // Publishing frames

    public void setVideoCodecSpecificData(@NonNull ByteBuffer[] array) {
        for (ByteBuffer buf : array) {
            buf.position(0);
        }

        synchronized (mHandleLock) {
            setVideoCodecSpecificDataImpl(mHandle, array);
        }
    }

    public void publishVideoFrame(@NonNull ByteBuffer buf) throws SRtcException {
        assert buf.isDirect();

        synchronized (mHandleLock) {
            publishVideoFrameImpl(mHandle, buf);
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
                                               @Nullable PubVideoConfig video,
                                               @Nullable PubAudioConfig audio) throws SRtcException;

    private native void setPublishAnswerImpl(long handle,
                                             @NonNull String answer) throws SRtcException;

    private native void setVideoCodecSpecificDataImpl(long handle,
                                                     @NonNull ByteBuffer[] array);

    private native void publishVideoFrameImpl(long handle,
                                              @NonNull ByteBuffer buf) throws SRtcException;

    void fromNativeOnConnectionState(int state) {
        mMainHandler.post(() -> {
           synchronized (mListenerLock) {
               if (mConnectionStateListener != null) {
                   mConnectionStateListener.onConnectionState(state);
               }
           }
        });
    }

    private final Handler mMainHandler = new Handler(Looper.getMainLooper());
    private final Object mHandleLock = new Object();

    private long mHandle;
    private Track mVideoTrack;
    private Track mAudioTrack;

    private final Object mListenerLock = new Object();
    private ConnectionStateListener mConnectionStateListener;
}
