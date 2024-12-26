#include "jni_error.h"
#include "jni_util.h"
#include "jni_class_map.h"

namespace {

srtc::android::ClassMap gClassOfferException;

}

namespace srtc::android {

void JavaError::initializeJNI(JNIEnv* env)
{

    gClassOfferException.findClass(env, SRTC_PACKAGE_NAME "/RtcException")
        .findMethod(env, "<init>", "(ILjava/lang/String;)V");

}

void JavaError::throwException(JNIEnv* env, const srtc::Error& error)
{
    const auto message = env->NewStringUTF(error.mMessage.c_str());
    const auto exc = gClassOfferException.newObject(env,
                                                    static_cast<jint>(error.mCode),
                                                    message);

    env->Throw(reinterpret_cast<jthrowable>(exc));
}


}
