#include <cassert>
#include <cstdarg>

#include "jni_class_map.h"

namespace srtc::android {

ClassMap::ClassMap()
    : mClass(nullptr)
{
}

ClassMap& ClassMap::findClass(JNIEnv* env, const char* className)
{
    assert(mClass == nullptr);

    const auto c = env->FindClass(className);
    assert(c != nullptr);

    mClass = reinterpret_cast<jclass>(env->NewGlobalRef(reinterpret_cast<jobject>(c)));
    assert(mClass != nullptr);

    return *this;
}

ClassMap& ClassMap::findMethod(JNIEnv* env, const char* name, const char* signature)
{
    const auto m = env->GetMethodID(mClass, name, signature);
    assert(m != nullptr);

    mMethodMap.emplace(name, m);

    return *this;
}

ClassMap& ClassMap::findField(JNIEnv* env, const char* name, const char* type)
{
    const auto f = env->GetFieldID(mClass, name, type);
    assert(f != nullptr);

    mFieldMap.emplace(name, f);

    return *this;
}

std::string ClassMap::getFieldString(JNIEnv *env, jobject obj, const char* name) const
{
    const auto iter = mFieldMap.find(name);
    assert(iter != mFieldMap.end());

    std::string res;

    const auto value = env->GetObjectField(obj, iter->second);
    if (value != nullptr) {
        const auto jstr = static_cast<jstring>(value);
        const auto ptr  = env->GetStringUTFChars(jstr, nullptr);
        res = ptr;
        env->ReleaseStringUTFChars(jstr, ptr);
    }

    return res;
}

jobjectArray ClassMap::getFieldObjectArray(JNIEnv* env, jobject obj, const char* name) const
{
    const auto iter = mFieldMap.find(name);
    assert(iter != mFieldMap.end());

    return reinterpret_cast<jobjectArray>(env->GetObjectField(obj, iter->second));
}

jint ClassMap::getFieldInt(JNIEnv *env, jobject obj, const char* name) const
{
    const auto iter = mFieldMap.find(name);
    assert(iter != mFieldMap.end());

    return env->GetIntField(obj, iter->second);
}

void ClassMap::setFieldObject(JNIEnv* env, jobject obj, const char* name, jobject value)
{
    const auto iter = mFieldMap.find(name);
    assert(iter != mFieldMap.end());

    env->SetObjectField(obj, iter->second, value);
}

void ClassMap::callVoidMethod(JNIEnv* env, jobject obj, const char* name...)
{
    const auto iter = mMethodMap.find(name);
    assert(iter != mMethodMap.end());

    va_list ap;
    va_start(ap, name);
    va_end(ap);

    env->CallVoidMethodV(obj, iter->second, ap);
}

jint ClassMap::callIntMethod(JNIEnv* env, jobject obj, const char* name...)
{
    const auto iter = mMethodMap.find(name);
    assert(iter != mMethodMap.end());

    va_list ap;
    va_start(ap, name);
    va_end(ap);

    return env->CallIntMethodV(obj, iter->second, ap);
}

jobject ClassMap::callObjectMethod(JNIEnv* env, jobject obj, const char* name...)
{
    const auto iter = mMethodMap.find(name);
    assert(iter != mMethodMap.end());

    va_list ap;
    va_start(ap, name);
    va_end(ap);

    return env->CallObjectMethodV(obj, iter->second, ap);
}

jobject ClassMap::newObject(JNIEnv* env, ...)
{
    const auto iter = mMethodMap.find("<init>");
    assert(iter != mMethodMap.end());

    va_list args;
    va_start(args, env);

    const auto obj = env->NewObjectV(mClass, iter->second, args);
    assert(obj != nullptr);

    va_end(args);

    return obj;

}

}
