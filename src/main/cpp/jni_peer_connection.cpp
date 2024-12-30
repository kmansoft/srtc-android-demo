#include "srtc/peer_connection.h"
#include "srtc/sdp_answer.h"
#include "srtc/sdp_offer.h"
#include "srtc/track.h"

#include "jni_class_map.h"
#include "jni_util.h"
#include "jni_peer_connection.h"
#include "jni_error.h"

#include <jni.h>

namespace {

srtc::android::ClassMap gClassPeerConnection;
srtc::android::ClassMap gClassTrack;
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
    const srtc::OfferConfig offerConfig {
        .cname = gClassOfferConfig.getFieldString(env, config, "cname")
    };

    std::vector<srtc::VideoLayer> layerList;

    const auto layerListJni = gClassVideoConfig.getFieldObjectArray(env, video, "layerList");
    for (jsize i = 0; i < env->GetArrayLength(layerListJni); i += 1) {
        const auto layerJni = env->GetObjectArrayElement(layerListJni, i);
        layerList.push_back(srtc::VideoLayer {
            .codec = static_cast<srtc::Codec>(gClassVideoLayer.getFieldInt(env, layerJni, "codec")),
            .profileId = static_cast<uint32_t>(gClassVideoLayer.getFieldInt(env, layerJni, "profileId")),
            .level = static_cast<uint32_t>(gClassVideoLayer.getFieldInt(env, layerJni, "level"))
        });
    }

    const srtc::VideoConfig videoConfig {
        .layerList = layerList
    };

    std::string outSdpOffer;

    const auto offer = std::make_shared<srtc::SdpOffer>(offerConfig, videoConfig, std::nullopt);
    const auto [ offerStr, error ] = offer->generate();

    if (error.isError()) {
        // Throw an exception
        srtc::android::JavaError::throwException(env, error);
        return nullptr;
    }

    const auto ptr = reinterpret_cast<srtc::PeerConnection*>(handle);
    ptr->setSdpOffer(offer);

    return env->NewStringUTF(offerStr.c_str());
}

extern "C"
JNIEXPORT void JNICALL
Java_org_kman_srtctest_rtc_PeerConnection_setPublishAnswerImpl(JNIEnv *env, jobject thiz, jlong handle,
                                                               jstring answer)
{
    std::shared_ptr<srtc::SdpAnswer> outAnswer;
    const auto answerStr = srtc::android::fromJavaString(env, answer);
    const auto error = srtc::SdpAnswer::parse(answerStr, outAnswer);
    if (error.isError()) {
        srtc::android::JavaError::throwException(env, error);
        return;
    }

    const auto ptr = reinterpret_cast<srtc::PeerConnection*>(handle);
    ptr->setSdpAnswer(outAnswer);

    const auto videoTrack = ptr->getVideoTrack();
    const auto audioTrack = ptr->getAudioTrack();

    jobject videoTrackJ = nullptr;
    if (videoTrack) {
        videoTrackJ = gClassTrack.newObject(env, videoTrack->getTrackId(), videoTrack->getPayloadType(),
                                            static_cast<jint>(videoTrack->getCodec()),
                                            videoTrack->getProfileId(), videoTrack->getLevel());
    }
    gClassPeerConnection.setFieldObject(env, thiz, "mVideoTrack", videoTrackJ);

    jobject audioTrackJ = nullptr;
    if (audioTrack) {
        audioTrackJ = gClassTrack.newObject(env, audioTrack->getTrackId(), audioTrack->getPayloadType(),
                                            static_cast<jint>(audioTrack->getCodec()),
                                            audioTrack->getProfileId(), audioTrack->getLevel());
    }
    gClassPeerConnection.setFieldObject(env, thiz, "mAudioTrack", audioTrackJ);
}

namespace srtc::android {

void PeerConnection::initializeJNI(JNIEnv *env)
{
    gClassPeerConnection.findClass(env, SRTC_PACKAGE_NAME "/PeerConnection")
            .findField(env, "mVideoTrack", "L" SRTC_PACKAGE_NAME "/Track;")
            .findField(env, "mAudioTrack", "L" SRTC_PACKAGE_NAME "/Track;");

    gClassTrack.findClass(env, SRTC_PACKAGE_NAME "/Track")
            .findMethod(env, "<init>", "(IIIII)V");

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
