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

srtc::android::ClassMap gClassJavaIoByteBuffer;
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
#pragma clang diagnostic push
#pragma ide diagnostic ignored "MemoryLeak"
    const auto ptr = new srtc::android::JavaPeerConnection(env->NewGlobalRef(thiz));
#pragma clang diagnostic pop
    return reinterpret_cast<jlong>(ptr);
}

extern "C"
JNIEXPORT void JNICALL
Java_org_kman_srtctest_rtc_PeerConnection_releaseImpl(JNIEnv* env, jobject thiz, jlong handle)
{
    const auto ptr = reinterpret_cast<srtc::android::JavaPeerConnection*>(handle);
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
        srtc::android::JavaError::throwSRtcException(env, error);
        return nullptr;
    }

    const auto ptr = reinterpret_cast<srtc::android::JavaPeerConnection*>(handle);
    ptr->mConn->setSdpOffer(offer);

    return env->NewStringUTF(offerStr.c_str());
}

extern "C"
JNIEXPORT void JNICALL
Java_org_kman_srtctest_rtc_PeerConnection_setPublishAnswerImpl(JNIEnv *env, jobject thiz, jlong handle,
                                                               jstring answer)
{
    std::shared_ptr<srtc::SdpAnswer> outAnswer;
    const auto answerStr = srtc::android::fromJavaString(env, answer);

    const auto error1 = srtc::SdpAnswer::parse(answerStr, outAnswer);
    if (error1.isError()) {
        srtc::android::JavaError::throwSRtcException(env, error1);
        return;
    }

    const auto ptr = reinterpret_cast<srtc::android::JavaPeerConnection*>(handle);
    const auto error2 = ptr->mConn->setSdpAnswer(outAnswer);
    if (error2.isError()) {
        srtc::android::JavaError::throwSRtcException(env, error2);
        return;
    }

    const auto videoTrack = ptr->mConn->getVideoTrack();
    const auto audioTrack = ptr->mConn->getAudioTrack();

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

extern "C"
JNIEXPORT void JNICALL
Java_org_kman_srtctest_rtc_PeerConnection_publishVideoFrameImpl(JNIEnv *env, jobject thiz,
                                                                jlong handle, jobject buf)
{
    const auto bufPtr = env->GetDirectBufferAddress(buf);
    const auto bufSize = gClassJavaIoByteBuffer.callIntMethod(env, buf, "limit");

    srtc::ByteBuffer bb { static_cast<uint8_t*>(bufPtr),
                          static_cast<size_t>(bufSize) };

    const auto ptr = reinterpret_cast<srtc::android::JavaPeerConnection*>(handle);
    const auto error = ptr->mConn->publishVideoFrame(std::move(bb));
    if (error.isError()) {
        srtc::android::JavaError::throwSRtcException(env, error);
        return;
    }
}

namespace srtc::android {

void JavaPeerConnection::initializeJNI(JNIEnv *env)
{
    gClassJavaIoByteBuffer.findClass(env, "java/nio/Buffer")
            .findMethod(env, "limit", "()I");

    gClassPeerConnection.findClass(env, SRTC_PACKAGE_NAME "/PeerConnection")
            .findField(env, "mVideoTrack", "L" SRTC_PACKAGE_NAME "/Track;")
            .findField(env, "mAudioTrack", "L" SRTC_PACKAGE_NAME "/Track;")
            .findMethod(env, "fromNativeOnConnectionState", "(I)V");

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

JavaPeerConnection::JavaPeerConnection(jobject thiz)
    : mThiz(thiz)
    , mConn(std::make_unique<PeerConnection>())
{
    mConn->setConnectionStateListener([this](PeerConnection::ConnectionState state){
        const auto env = getJNIEnv();
        gClassPeerConnection.callVoidMethod(env, mThiz, "fromNativeOnConnectionState", static_cast<jint>(state));
    });
}

JavaPeerConnection::~JavaPeerConnection()
{
    mConn.reset();

    const auto env = getJNIEnv();
    env->DeleteGlobalRef(mThiz);
}

}
