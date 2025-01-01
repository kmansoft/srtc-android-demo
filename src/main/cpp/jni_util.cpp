#include <mutex>
#include <pthread.h>

#include "jni_util.h"

namespace  {

std::once_flag gJniEnvInit;
pthread_key_t gJniPThreadKey;
JavaVM* gJavaVM;

void onPThreadDestroy(void* ) {
    gJavaVM->DetachCurrentThread();
}

}

namespace srtc::android {

std::string fromJavaString(JNIEnv* env, jstring str)
{
    const auto ptr = env->GetStringUTFChars(str, nullptr);
    std::string res = ptr;
    env->ReleaseStringUTFChars(str, ptr);

    return res;
}

void initJNIEnv(JavaVM* vm) {
    gJavaVM = vm;
}

JNIEnv* getJNIEnv() {
    std::call_once(gJniEnvInit, [] {
        pthread_key_create(&gJniPThreadKey, onPThreadDestroy);
    });

    // Try the thread local
    auto env = static_cast<JNIEnv *>(pthread_getspecific(gJniPThreadKey));
    if (env) {
        return env;
    }

    // Nope, attach to the VM
    JavaVMAttachArgs args {
        .version = JNI_VERSION_1_6,
        .name = nullptr,
        .group = nullptr
    };
    gJavaVM->AttachCurrentThread(&env, &args);

    pthread_setspecific(gJniPThreadKey, env);

    return env;
}

}
