#pragma once

#include <jni.h>

namespace srtc::android {

class PeerConnection {
public:
    static void initializeJNI(JNIEnv* env);
};

}
