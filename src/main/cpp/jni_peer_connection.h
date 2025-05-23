#pragma once

#include <memory>
#include "srtc/peer_connection.h"

#include <jni.h>

struct OpusEncoder;

namespace srtc::android {

class JavaPeerConnection {
public:
    static void initializeJNI(JNIEnv* env);

    explicit JavaPeerConnection(jobject thiz);
    ~JavaPeerConnection();

    [[nodiscard]] Error publishVideoSingleFrame(ByteBuffer&& frame);
    [[nodiscard]] Error publishVideoSimulcastFrame(const std::string& layerName, ByteBuffer&& frame);
    [[nodiscard]] Error publishAudioFrame(const void* frame,
                                          size_t size,
                                          int sampleRate,
                                          int channels);

    std::unique_ptr<PeerConnection> mConn;

private:
    jobject mThiz;
    OpusEncoder* mOpusEncoder;
};

}
