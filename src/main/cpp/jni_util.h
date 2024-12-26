#pragma once

#include <string>

#include <jni.h>

#define SRTC_PACKAGE_NAME "org/kman/srtctest/rtc"

namespace srtc::android {

std::string fromJavaString(JNIEnv* env, jstring str);

}
