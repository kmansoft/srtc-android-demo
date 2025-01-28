#include "srtc/peer_connection.h"
#include "srtc/sdp_answer.h"
#include "srtc/sdp_offer.h"
#include "srtc/track.h"
#include "srtc/logging.h"

#include "opus.h"
#include "opus_defines.h"

#include "jni_class_map.h"
#include "jni_util.h"
#include "jni_peer_connection.h"
#include "jni_error.h"

#include <jni.h>

#define LOG(...) srtc::log("JavaPeerConnection", __VA_ARGS__)

namespace {

srtc::android::ClassMap gClassJavaIoByteBuffer;
srtc::android::ClassMap gClassJavaUtilArrayList;
srtc::android::ClassMap gClassPeerConnection;
srtc::android::ClassMap gClassTrack;
srtc::android::ClassMap gClassOfferConfig;
srtc::android::ClassMap gClassVideoCodecConfig;
srtc::android::ClassMap gClassVideoConfig;
srtc::android::ClassMap gClassAudioCodecConfig;
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
    const auto ptr = reinterpret_cast<srtc::android::JavaPeerConnection*>(handle);
    if (!ptr) {
        const srtc::Error error = { srtc::Error::Code::InvalidData, "The connection has been released" };
        srtc::android::JavaError::throwSRtcException(env, error);
        return nullptr;
    }

    const srtc::OfferConfig offerConfig{
        .cname = gClassOfferConfig.getFieldString(env, config, "cname")
    };

    // Video
    std::vector<srtc::PubVideoCodecConfig> videoList;

    if (video) {
        const auto itemListJni = gClassVideoConfig.getFieldObject(env, video, "list");
        for (jsize i = 0;
            i < gClassJavaUtilArrayList.callIntMethod(env, itemListJni, "size"); i += 1) {
            const auto itemJni = gClassJavaUtilArrayList.callObjectMethod(env, itemListJni, "get", i);
            videoList.push_back(srtc::PubVideoCodecConfig{
                .codec = static_cast<srtc::Codec>(gClassVideoCodecConfig.getFieldInt(env, itemJni, "codec")),
                .profileLevelId = static_cast<uint32_t>(gClassVideoCodecConfig.getFieldInt(env, itemJni, "profileLevelId"))
            });
        }
    }

    const srtc::PubVideoConfig videoConfig { videoList };

    // Audio
    std::vector<srtc::PubAudioCodecConfig> audioList;

    if (audio) {
        const auto itemListJni = gClassAudioConfig.getFieldObject(env, audio, "list");
        for (jsize i = 0;
            i < gClassJavaUtilArrayList.callIntMethod(env, itemListJni, "size"); i += 1) {
            const auto itemJni = gClassJavaUtilArrayList.callObjectMethod(env, itemListJni, "get", i);
            audioList.push_back(srtc::PubAudioCodecConfig{
                    .codec = static_cast<srtc::Codec>(gClassAudioCodecConfig.getFieldInt(env, itemJni, "codec")),
                    .minPacketTimeMs = static_cast<uint32_t>(gClassAudioCodecConfig.getFieldInt(env, itemJni, "minPacketTimeMs"))
            });
        }
    }

    const srtc::PubAudioConfig audioConfig { audioList };

    // Create the offer
    std::string outSdpOffer;

    const auto offer = std::make_shared<srtc::SdpOffer>(offerConfig,
                                                        video ? std::optional(videoConfig) : std::nullopt,
                                                        audio ? std::optional(audioConfig) : std::nullopt);
    const auto [ offerStr, error ] = offer->generate();

    if (error.isError()) {
        // Throw an exception
        srtc::android::JavaError::throwSRtcException(env, error);
        return nullptr;
    }

    ptr->mConn->setSdpOffer(offer);

    return env->NewStringUTF(offerStr.c_str());
}

extern "C"
JNIEXPORT void JNICALL
Java_org_kman_srtctest_rtc_PeerConnection_setPublishAnswerImpl(JNIEnv *env, jobject thiz, jlong handle,
                                                               jstring answer)
{
    const auto ptr = reinterpret_cast<srtc::android::JavaPeerConnection*>(handle);
    if (!ptr) {
        return;
    }

    std::shared_ptr<srtc::SdpAnswer> outAnswer;
    const auto answerStr = srtc::android::fromJavaString(env, answer);

    const auto error1 = srtc::SdpAnswer::parse(answerStr, outAnswer);
    if (error1.isError()) {
        srtc::android::JavaError::throwSRtcException(env, error1);
        return;
    }

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
                                            videoTrack->getProfileLevelId());
    }
    gClassPeerConnection.setFieldObject(env, thiz, "mVideoTrack", videoTrackJ);

    jobject audioTrackJ = nullptr;
    if (audioTrack) {
        audioTrackJ = gClassTrack.newObject(env, audioTrack->getTrackId(), audioTrack->getPayloadType(),
                                            static_cast<jint>(audioTrack->getCodec()),
                                            audioTrack->getProfileLevelId());
    }
    gClassPeerConnection.setFieldObject(env, thiz, "mAudioTrack", audioTrackJ);
}

extern "C"
JNIEXPORT void JNICALL
Java_org_kman_srtctest_rtc_PeerConnection_setVideoCodecSpecificDataImpl(JNIEnv *env, jobject thiz,
                                                                        jlong handle, jobjectArray array)
{
    const auto ptr = reinterpret_cast<srtc::android::JavaPeerConnection *>(handle);
    if (!ptr) {
        return;
    }

    std::vector<srtc::ByteBuffer> list;

    for (jsize i = 0; i < env->GetArrayLength(array); i += 1) {
        const auto item = env->GetObjectArrayElement(array, i);

        const auto itemSize = gClassJavaIoByteBuffer.callIntMethod(env, item, "limit");
        const auto itemArray = env->NewByteArray(itemSize);

        gClassJavaIoByteBuffer.callObjectMethod(env, item, "get", itemArray, 0, itemSize);

        jboolean isCopy = { false };
        const auto itemArrayPtr = env->GetByteArrayElements(itemArray, &isCopy);

        list.emplace_back(reinterpret_cast<const uint8_t*>(itemArrayPtr), static_cast<size_t>(itemSize));

        env->ReleaseByteArrayElements(itemArray, itemArrayPtr, JNI_ABORT);
        env->DeleteLocalRef(itemArray);
    }

    if (!list.empty()) {
        const auto error = ptr->mConn->setVideoCodecSpecificData(list);
        if (error.isError()) {
            srtc::android::JavaError::throwSRtcException(env, error);
        }
    }
}

extern "C"
JNIEXPORT void JNICALL
Java_org_kman_srtctest_rtc_PeerConnection_publishVideoFrameImpl(JNIEnv *env, jobject thiz,
                                                                jlong handle, jobject buf)
{
    const auto ptr = reinterpret_cast<srtc::android::JavaPeerConnection*>(handle);
    if (!ptr) {
        return;
    }

    const auto bufPtr = env->GetDirectBufferAddress(buf);
    const auto bufSize = gClassJavaIoByteBuffer.callIntMethod(env, buf, "limit");

    srtc::ByteBuffer bb{static_cast<uint8_t *>(bufPtr),
                        static_cast<size_t>(bufSize)};

    const auto error = ptr->publishVideoFrame(bb);
    if (error.isError()) {
        srtc::android::JavaError::throwSRtcException(env, error);
        return;
    }
}

extern "C"
JNIEXPORT void JNICALL
Java_org_kman_srtctest_rtc_PeerConnection_publishAudioFrameImpl(JNIEnv *env, jobject thiz,
                                                                jlong handle, jobject buf,
                                                                jint size,
                                                                jint sampleRate,
                                                                jint channels)
{
    const auto ptr = reinterpret_cast<srtc::android::JavaPeerConnection*>(handle);
    if (!ptr) {
        return;
    }

    const auto bufPtr = env->GetDirectBufferAddress(buf);
    const auto error = ptr->publishAudioFrame(bufPtr, static_cast<size_t>(size), sampleRate, channels);
    if (error.isError()) {
        srtc::android::JavaError::throwSRtcException(env, error);
        return;
    }
}

namespace srtc::android {

void JavaPeerConnection::initializeJNI(JNIEnv *env)
{
    gClassJavaIoByteBuffer.findClass(env, "java/nio/ByteBuffer")
            .findMethod(env, "limit", "()I")
            .findMethod(env, "get", "([BII)Ljava/nio/ByteBuffer;");

    gClassJavaUtilArrayList.findClass(env, "java/util/ArrayList")
            .findMethod(env, "size", "()I")
            .findMethod(env, "get", "(I)Ljava/lang/Object;");

    gClassPeerConnection.findClass(env, SRTC_PACKAGE_NAME "/PeerConnection")
            .findField(env, "mVideoTrack", "L" SRTC_PACKAGE_NAME "/Track;")
            .findField(env, "mAudioTrack", "L" SRTC_PACKAGE_NAME "/Track;")
            .findMethod(env, "fromNativeOnConnectionState", "(I)V");

    gClassTrack.findClass(env, SRTC_PACKAGE_NAME "/Track")
            .findMethod(env, "<init>", "(IIII)V");

    gClassOfferConfig.findClass(env, SRTC_PACKAGE_NAME "/PeerConnection$OfferConfig")
            .findField(env, "cname", "Ljava/lang/String;");

    gClassVideoCodecConfig.findClass(env, SRTC_PACKAGE_NAME "/PeerConnection$PubVideoCodecConfig")
            .findField(env, "codec", "I")
            .findField(env, "profileLevelId", "I");

    gClassVideoConfig.findClass(env, SRTC_PACKAGE_NAME "/PeerConnection$PubVideoConfig")
            .findField(env, "list", "Ljava/util/ArrayList;");

    gClassAudioCodecConfig.findClass(env, SRTC_PACKAGE_NAME "/PeerConnection$PubAudioCodecConfig")
            .findField(env, "codec", "I")
            .findField(env, "minPacketTimeMs", "I");

    gClassAudioConfig.findClass(env, SRTC_PACKAGE_NAME "/PeerConnection$PubAudioConfig")
            .findField(env, "list", "Ljava/util/ArrayList;");
}

JavaPeerConnection::JavaPeerConnection(jobject thiz)
    : mThiz(thiz)
    , mConn(std::make_unique<PeerConnection>())
    , mOpusEncoder(nullptr)
{
    mConn->setConnectionStateListener([this](PeerConnection::ConnectionState state){
        const auto env = getJNIEnv();
        gClassPeerConnection.callVoidMethod(env, mThiz, "fromNativeOnConnectionState", static_cast<jint>(state));
    });
}

JavaPeerConnection::~JavaPeerConnection()
{
    LOG("destructor %p", this);

    mConn.reset();
    free(mOpusEncoder);

    const auto env = getJNIEnv();
    env->DeleteGlobalRef(mThiz);
}

Error JavaPeerConnection::publishVideoFrame(ByteBuffer& frame)
{
    return mConn->publishVideoFrame(frame);
}

Error JavaPeerConnection::publishAudioFrame(const void* frame,
                                            size_t size,
                                            int sampleRate,
                                            int channels)
{
    // This is thread safe because we have "synchronized" on the Java side
    if (mOpusEncoder == nullptr) {
        const auto encoderSize = opus_encoder_get_size(channels);
        mOpusEncoder = static_cast<OpusEncoder*>(malloc(encoderSize));
        std::memset(mOpusEncoder, 0, encoderSize);

        if (opus_encoder_init(mOpusEncoder, sampleRate, channels, OPUS_APPLICATION_VOIP) != 0) {
            free(mOpusEncoder);
            mOpusEncoder = nullptr;
        } else {
            opus_encoder_ctl(mOpusEncoder, OPUS_SET_BITRATE(96 * 1024));
            opus_encoder_ctl(mOpusEncoder, OPUS_SET_INBAND_FEC(1));
            opus_encoder_ctl(mOpusEncoder, OPUS_SET_PACKET_LOSS_PERC(20));
        }
    }

    if (mOpusEncoder != nullptr) {
        ByteBuffer output { 4000 };

        const auto encodedSize = opus_encode(mOpusEncoder,
            static_cast<const opus_int16*>(frame),
            static_cast<int>(size / sizeof(opus_int16) / channels),
            output.data(),
            static_cast<opus_int32>(output.capacity()));

        if (encodedSize > 0) {
            output.resize(static_cast<size_t>(encodedSize));
            return mConn->publishAudioFrame(output);
        }
    }

    return Error::OK;
}

}
