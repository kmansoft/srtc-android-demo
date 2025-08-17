#include "jni_error.h"
#include "jni_util.h"
#include "jni_class_map.h"

namespace {

srtc::android::ClassMap gClassOfferException;

}

namespace srtc::android {

void JavaError::initializeJNI(JNIEnv* env)
{

    gClassOfferException.findClass(env, SRTC_PACKAGE_NAME "/SRtcException")
        .findMethod(env, "<init>", "(ILjava/lang/String;)V");

}

void JavaError::throwSRtcException(JNIEnv* env, const srtc::Error& error)
{
    const auto message = env->NewStringUTF(error.message.c_str());
    const auto exc = gClassOfferException.newObject(env,
                                                    static_cast<jint>(error.code),
                                                    message);

    env->Throw(reinterpret_cast<jthrowable>(exc));
}


}
