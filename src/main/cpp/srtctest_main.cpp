#include <jni.h>

#include "jni_peer_connection.h"
#include "jni_error.h"

extern "C"
jint JNI_OnLoad(JavaVM *vm, void *reserved)
{
    using namespace srtc::android;

    JNIEnv* env = nullptr;
    vm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6);

    PeerConnection::initializeJNI(env);
    JavaError::initializeJNI(env);

    return JNI_VERSION_1_6;
}
