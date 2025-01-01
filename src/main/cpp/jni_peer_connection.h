#pragma once

#include <memory>
#include "srtc/peer_connection.h"

#include <jni.h>

namespace srtc::android {

class JavaPeerConnection {
public:
    static void initializeJNI(JNIEnv* env);

    JavaPeerConnection(jobject thiz);
    ~JavaPeerConnection();

    jobject mThiz;
    std::unique_ptr<PeerConnection> mConn;
};

}
