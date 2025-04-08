package org.kman.srtctest.rtc;

import android.os.Handler;
import android.os.Looper;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.kman.srtctest.util.MyLog;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

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
        public String cname = UUID.randomUUID().toString();
    }

    public static class PubVideoCodec {
        public PubVideoCodec(int codec, int profileLevelId) {
            this.codec = codec;
            this.profileLevelId = profileLevelId;
        }

        public final int codec;
        public final int profileLevelId;
    }

    public static class PubVideoSimulcastLayer {
        PubVideoSimulcastLayer(@NonNull String name,
                               int width, int height,
                               int kilobitPerSecond) {
            this.name = name;
            this.width = width;
            this.height = height;
            this.kilobitPerSecond = kilobitPerSecond;
        }

        @NonNull
        public final String name;
        public final int width;
        public final int height;
        public final int kilobitPerSecond;
    }

    public static class PubVideoConfig {
        public final ArrayList<PubVideoCodec> codecList = new ArrayList<>();
        public final ArrayList<PubVideoSimulcastLayer> simulcastLayerList = new ArrayList<>();
    }

    public static class PubAudioCodec {
        public PubAudioCodec(int codec, int minPacketTimeMs) {
            this.codec = codec;
            this.minPacketTimeMs = minPacketTimeMs;
        }

        public int codec;
        public int minPacketTimeMs;
    }

    public static class PubAudioConfig {
        @NonNull
        public final ArrayList<PubAudioCodec> codecList = new ArrayList<>();
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

    public void publishAudioFrame(@NonNull ByteBuffer buf,
                                  int size,
                                  int sampleRate,
                                  int channels) throws SRtcException {
        assert buf.isDirect();

        synchronized (mHandleLock) {
            publishAudioFrameImpl(mHandle, buf, size, sampleRate, channels);
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

    private native void publishAudioFrameImpl(long handle,
                                              @NonNull ByteBuffer buf,
                                              int size,
                                              int sampleRate,
                                              int channels) throws SRtcException;

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
