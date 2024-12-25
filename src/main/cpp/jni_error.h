#pragma once

#include "srtc/error.h"

#include <jni.h>

namespace srtc::android {

class Error {
public:
    static void initializeJNI(JNIEnv* env);

    static void throwException(JNIEnv* env, const srtc::Error& error);
};

}
