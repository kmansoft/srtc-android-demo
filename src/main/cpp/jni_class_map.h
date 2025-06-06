#pragma once

#include <unordered_map>
#include <string>

#include <jni.h>

namespace srtc::android {

class ClassMap {
public:
    ClassMap();

    ClassMap& findClass(JNIEnv* env, const char* className);
    ClassMap& findMethod(JNIEnv* env, const char* name, const char* signature);
    ClassMap& findField(JNIEnv* env, const char* name, const char* type);

    [[nodiscard]] jobject getFieldObject(JNIEnv* env, jobject obj, const char* name) const;
    [[nodiscard]] jint getFieldInt(JNIEnv* env, jobject obj, const char* name) const;
    [[nodiscard]] jboolean getFieldBoolean(JNIEnv* env, jobject obj, const char* name) const;
    [[nodiscard]] std::string getFieldString(JNIEnv* env, jobject obj, const char* name) const;
    [[nodiscard]] jobjectArray getFieldObjectArray(JNIEnv* env, jobject obj, const char* name) const;

    void setFieldObject(JNIEnv* env, jobject obj, const char* name, jobject value);

    void callVoidMethod(JNIEnv* env, jobject obj, const char* name...);
    jint callIntMethod(JNIEnv* env, jobject obj, const char* name...);
    jobject callObjectMethod(JNIEnv* env, jobject obj, const char* name...);
    jboolean callBooleanMethod(JNIEnv* env, jobject obj, const char* name...);

    jobject newObject(JNIEnv* env, ...);

private:
    jclass mClass;
    std::unordered_map<std::string, jmethodID> mMethodMap;
    std::unordered_map<std::string, jfieldID> mFieldMap;
};

}
