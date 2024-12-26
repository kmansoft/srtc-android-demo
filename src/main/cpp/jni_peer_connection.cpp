#include "srtc/peer_connection.h"
#include "srtc/sdp_offer.h"

#include "jni_class_map.h"
#include "jni_package.h"
#include "jni_peer_connection.h"
#include "jni_error.h"

#include <jni.h>

namespace {

srtc::android::ClassMap gClassOfferConfig;
srtc::android::ClassMap gClassVideoLayer;
srtc::android::ClassMap gClassVideoConfig;
srtc::android::ClassMap gClassAudioConfig;

}

extern "C"
JNIEXPORT jlong JNICALL
Java_org_kman_srtctest_rtc_PeerConnection_createImpl(JNIEnv* env, jobject thiz)
{
    const auto ptr = new srtc::PeerConnection();
    return reinterpret_cast<jlong>(ptr);
}

extern "C"
JNIEXPORT void JNICALL
Java_org_kman_srtctest_rtc_PeerConnection_releaseImpl(JNIEnv* env, jobject thiz, jlong handle)
{
    const auto ptr = reinterpret_cast<srtc::PeerConnection*>(handle);
    delete ptr;
}

extern "C"
JNIEXPORT jstring JNICALL
Java_org_kman_srtctest_rtc_PeerConnection_initPublishOfferImpl(JNIEnv *env, jobject thiz,
                                                               jlong handle, jobject config,
                                                               jobject video, jobject audio)
{
    const auto ptr = reinterpret_cast<srtc::PeerConnection*>(handle);

    const srtc::OfferConfig offerConfig {
        .cname = gClassOfferConfig.getFieldString(env, config, "cname")
    };

    std::vector<srtc::VideoLayer> layerList;

    const auto layerListJni = gClassVideoConfig.getFieldObjectArray(env, video, "layerList");
    for (jsize i = 0; i < env->GetArrayLength(layerListJni); i += 1) {
        const auto layerJni = env->GetObjectArrayElement(layerListJni, i);
        layerList.push_back(srtc::VideoLayer {
            .codec = static_cast<srtc::VideoCodec>(gClassVideoLayer.getFieldInt(env, layerJni, "codec")),
            .profileId = static_cast<uint32_t>(gClassVideoLayer.getFieldInt(env, layerJni, "profileId")),
            .level = static_cast<uint32_t>(gClassVideoLayer.getFieldInt(env, layerJni, "level"))
        });
    }

    const srtc::VideoConfig videoConfig {
        .layerList = layerList
    };

    std::string outSdpOffer;

    const auto offer = std::make_shared<srtc::SdpOffer>(offerConfig, videoConfig, std::nullopt);
    const auto error = offer->generate(outSdpOffer);

    if (error.isError()) {
        // Throw an exception
        srtc::android::JavaError::throwException(env, error);
        return nullptr;
    }

    ptr->setSdpOffer(offer);

    return env->NewStringUTF(outSdpOffer.c_str());
}

namespace srtc::android {

void PeerConnection::initializeJNI(JNIEnv *env)
{
    gClassOfferConfig.findClass(env, SRTC_PACKAGE_NAME "/PeerConnection$OfferConfig")
            .findField(env, "cname", "Ljava/lang/String;");

    gClassVideoLayer.findClass(env, SRTC_PACKAGE_NAME "/PeerConnection$VideoLayer")
            .findField(env, "codec", "I")
            .findField(env, "profileId", "I")
            .findField(env, "level", "I");

    gClassVideoConfig.findClass(env, SRTC_PACKAGE_NAME "/PeerConnection$VideoConfig")
            .findField(env, "layerList", "[L" SRTC_PACKAGE_NAME "/PeerConnection$VideoLayer;");

    gClassAudioConfig.findClass(env, SRTC_PACKAGE_NAME "/PeerConnection$AudioConfig")
            .findField(env, "codec", "I");
}

}
