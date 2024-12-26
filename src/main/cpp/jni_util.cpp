#include "jni_util.h"

namespace srtc::android {

std::string fromJavaString(JNIEnv* env, jstring str)
{
    const auto ptr = env->GetStringUTFChars(str, nullptr);
    std::string res = ptr;
    env->ReleaseStringUTFChars(str, ptr);

    return res;
}

}
