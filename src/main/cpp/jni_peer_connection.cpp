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

#define LOG(level, ...) srtc::log(level, "JavaPeerConnection", __VA_ARGS__)

namespace {

srtc::android::ClassMap gClassJavaIoByteBuffer;
srtc::android::ClassMap gClassJavaUtilArrayList;
srtc::android::ClassMap gClassSimulcastLayer;
srtc::android::ClassMap gClassTrack;
srtc::android::ClassMap gClassPeerConnection;
srtc::android::ClassMap gClassOfferConfig;
srtc::android::ClassMap gClassVideoCodec;
srtc::android::ClassMap gClassVideoConfig;
srtc::android::ClassMap gClassAudioCodec;
srtc::android::ClassMap gClassAudioConfig;

class HighestProfileSelector : public srtc::SdpAnswer::TrackSelector {
public:
    ~HighestProfileSelector() override = default;

    [[nodiscard]] std::shared_ptr<srtc::Track> selectTrack(srtc::MediaType type,
                                                           const std::vector<std::shared_ptr<srtc::Track>>& list) const override;

};

bool isBetter(const std::shared_ptr<srtc::Track>& best,
              const std::shared_ptr<srtc::Track>& curr)
{
    if (!best) {
        return true;
    }

    if (best->getCodec() != curr->getCodec()) {
        return best->getCodec() < curr->getCodec();
    }

    return best->getProfileLevelId() < curr->getProfileLevelId();
}

std::shared_ptr<srtc::Track> HighestProfileSelector::selectTrack(srtc::MediaType type,
                                                                 const std::vector<std::shared_ptr<srtc::Track>>& list) const
{
    if (list.empty()) {
        return nullptr;
    }

    if (type == srtc::MediaType::Audio) {
        return list[0];
    } else if (type == srtc::MediaType::Video) {
        std::shared_ptr<srtc::Track> best;
        for (const auto& curr : list) {
            if (isBetter(best, curr)) {
                best = curr;
            }
        }
        return best;
    } else {
        return nullptr;
    }
}

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
    std::vector<srtc::PubVideoCodec> videoCodecList;
    std::vector<srtc::SimulcastLayer> videoSimulcastLayerList;

    if (video) {
        const auto codecListJni = gClassVideoConfig.getFieldObject(env, video, "codecList");
        for (jsize i = 0;
             i < gClassJavaUtilArrayList.callIntMethod(env, codecListJni, "size"); i += 1) {
            const auto itemJni = gClassJavaUtilArrayList.callObjectMethod(env, codecListJni, "get", i);
            videoCodecList.push_back(
                srtc::PubVideoCodec {
                    .codec = static_cast<srtc::Codec>(gClassVideoCodec.getFieldInt(env, itemJni, "codec")),
                    .profileLevelId = static_cast<uint32_t>(gClassVideoCodec.getFieldInt(env, itemJni,
                                                                                         "profileLevelId"))
            });
        }

        const auto simulcastListJni = gClassVideoConfig.getFieldObject(env, video, "simulcastLayerList");
        for (jsize i = 0;
             i < gClassJavaUtilArrayList.callIntMethod(env, simulcastListJni, "size"); i += 1) {
            const auto itemJni = gClassJavaUtilArrayList.callObjectMethod(env, simulcastListJni, "get", i);
            videoSimulcastLayerList.push_back(
                srtc::SimulcastLayer {
                    .name = gClassSimulcastLayer.getFieldString(env, itemJni, "name"),
                    .width = static_cast<uint16_t>(gClassSimulcastLayer.getFieldInt(env, itemJni, "width")),
                    .height = static_cast<uint16_t>(gClassSimulcastLayer.getFieldInt(env, itemJni, "height")),
                    .framesPerSecond = static_cast<uint16_t>(gClassSimulcastLayer.getFieldInt(env, itemJni, "framesPerSecond")),
                    .kilobitPerSecond = static_cast<uint32_t>(gClassSimulcastLayer.getFieldInt(env, itemJni, "kilobitPerSecond"))
            });
        }
    }

    const srtc::PubVideoConfig videoConfig {
        .codecList = videoCodecList,
        .simulcastLayerList = videoSimulcastLayerList
    };

    // Audio
    std::vector<srtc::PubAudioCodec> audioCodecList;

    if (audio) {
        const auto itemListJni = gClassAudioConfig.getFieldObject(env, audio, "codecList");
        for (jsize i = 0;
            i < gClassJavaUtilArrayList.callIntMethod(env, itemListJni, "size"); i += 1) {
            const auto itemJni = gClassJavaUtilArrayList.callObjectMethod(env, itemListJni, "get", i);
            audioCodecList.push_back(srtc::PubAudioCodec{
                    .codec = static_cast<srtc::Codec>(gClassAudioCodec.getFieldInt(env, itemJni, "codec")),
                    .minPacketTimeMs = static_cast<uint32_t>(gClassAudioCodec.getFieldInt(env, itemJni, "minPacketTimeMs"))
            });
        }
    }

    const srtc::PubAudioConfig audioConfig { .codecList = audioCodecList };

    // Create the offer
    std::string outSdpOffer;

    const auto offer = std::make_shared<srtc::SdpOffer>(offerConfig,
                                                        video ? srtc::optional(videoConfig) : srtc::nullopt,
                                                        audio ? srtc::optional(audioConfig) : srtc::nullopt);
    const auto [ offerStr, error1 ] = offer->generate();

    if (error1.isError()) {
        // Throw an exception
        srtc::android::JavaError::throwSRtcException(env, error1);
        return nullptr;
    }

    const auto error2 = ptr->mConn->setSdpOffer(offer);
    if (error2.isError()) {
        // Throw an exception
        srtc::android::JavaError::throwSRtcException(env, error2);
        return nullptr;
    }

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

    const auto offer = ptr->mConn->getSdpOffer();
    const auto answerStr = srtc::android::fromJavaString(env, answer);
    const auto selector = std::make_shared<HighestProfileSelector>();

    std::shared_ptr<srtc::SdpAnswer> outAnswer;

    if (const auto error = srtc::SdpAnswer::parse(offer, answerStr, selector, outAnswer); error.isError()) {
        srtc::android::JavaError::throwSRtcException(env, error);
        return;
    }

    if (const auto error = ptr->mConn->setSdpAnswer(outAnswer); error.isError()) {
        srtc::android::JavaError::throwSRtcException(env, error);
        return;
    }

    const auto videoSingleTrack = ptr->mConn->getVideoSingleTrack();
    const auto videoSimulcastTrackList = ptr->mConn->getVideoSimulcastTrackList();
    const auto audioTrack = ptr->mConn->getAudioTrack();

    if (videoSingleTrack) {
        jobject videoTrackJ = gClassTrack.newObject(env,
                                            static_cast<jint>(videoSingleTrack->getTrackId()),
                                            static_cast<jint>(videoSingleTrack->getPayloadId()),
                                            static_cast<jint>(videoSingleTrack->getCodec()),
                                            static_cast<jint>(videoSingleTrack->getProfileLevelId()),
                                            nullptr);
        gClassPeerConnection.setFieldObject(env, thiz, "mVideoSingleTrack", videoTrackJ);
    } else if (!videoSimulcastTrackList.empty()) {
        jobject listJ = gClassJavaUtilArrayList.newObject(env);

        for (const auto& track : videoSimulcastTrackList) {
            const auto& layer = track->getSimulcastLayer();

            jstring nameJ = env->NewStringUTF(layer.name.c_str());
            jobject layerJ = gClassSimulcastLayer.newObject(env,
                                                           nameJ,
                                                           static_cast<jint>(layer.width),
                                                           static_cast<jint>(layer.height),
                                                           static_cast<jint>(layer.framesPerSecond),
                                                           static_cast<jint>(layer.kilobitPerSecond));
            jobject videoTrackJ = gClassTrack.newObject(env,
                                                        static_cast<jint>(track->getTrackId()),
                                                        static_cast<jint>(track->getPayloadId()),
                                                        static_cast<jint>(track->getCodec()),
                                                        static_cast<jint>(track->getProfileLevelId()),
                                                        layerJ);

            gClassJavaUtilArrayList.callBooleanMethod(env, listJ, "add", videoTrackJ);
        }

        gClassPeerConnection.setFieldObject(env, thiz, "mVideoSimulcastTrackList", listJ);
    }

    jobject audioTrackJ = nullptr;
    if (audioTrack) {
        audioTrackJ = gClassTrack.newObject(env,
                                            static_cast<jint>(audioTrack->getTrackId()),
                                            static_cast<jint>(audioTrack->getPayloadId()),
                                            static_cast<jint>(audioTrack->getCodec()),
                                            static_cast<jint>(audioTrack->getProfileLevelId()));
    }
    gClassPeerConnection.setFieldObject(env, thiz, "mAudioTrack", audioTrackJ);
}

extern "C"
JNIEXPORT void JNICALL
Java_org_kman_srtctest_rtc_PeerConnection_setVideoSingleCodecSpecificDataImpl(JNIEnv *env, jobject thiz,
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
        const auto error = ptr->mConn->setVideoSingleCodecSpecificData(list);
        if (error.isError()) {
            srtc::android::JavaError::throwSRtcException(env, error);
        }
    }
}

extern "C"
JNIEXPORT void JNICALL
Java_org_kman_srtctest_rtc_PeerConnection_publishVideoSingleFrameImpl(JNIEnv *env, jobject thiz,
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

    const auto error = ptr->publishVideoSingleFrame(std::move(bb));
    if (error.isError()) {
        srtc::android::JavaError::throwSRtcException(env, error);
        return;
    }
}

extern "C"
JNIEXPORT void JNICALL
Java_org_kman_srtctest_rtc_PeerConnection_setVideoSimulcastCodecSpecificDataImpl(JNIEnv *env, jobject thiz,
                                                                                 jlong handle, jobject layer, jobjectArray array)
{
    const auto ptr = reinterpret_cast<srtc::android::JavaPeerConnection *>(handle);
    if (!ptr) {
        return;
    }

    const auto layerName = gClassSimulcastLayer.getFieldString(env, layer, "name");
    if (layerName.empty()) {
        const srtc::Error error = { srtc::Error::Code::InvalidData, "The layer name is empty" };
        srtc::android::JavaError::throwSRtcException(env, error);
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
        const auto error = ptr->mConn->setVideoSimulcastCodecSpecificData(layerName, list);
        if (error.isError()) {
            srtc::android::JavaError::throwSRtcException(env, error);
        }
    }
}

extern "C"
JNIEXPORT void JNICALL
Java_org_kman_srtctest_rtc_PeerConnection_publishVideoSimulcastFrameImpl(JNIEnv *env, jobject thiz,
                                                                         jlong handle, jobject layer, jobject buf)
{
    const auto ptr = reinterpret_cast<srtc::android::JavaPeerConnection*>(handle);
    if (!ptr) {
        return;
    }

    const auto layerName = gClassSimulcastLayer.getFieldString(env, layer, "name");
    if (layerName.empty()) {
        const srtc::Error error = { srtc::Error::Code::InvalidData, "The layer name is empty" };
        srtc::android::JavaError::throwSRtcException(env, error);
    }

    const auto bufPtr = env->GetDirectBufferAddress(buf);
    const auto bufSize = gClassJavaIoByteBuffer.callIntMethod(env, buf, "limit");

    srtc::ByteBuffer bb{static_cast<uint8_t *>(bufPtr),
                        static_cast<size_t>(bufSize)};

    const auto error = ptr->publishVideoSimulcastFrame(layerName, std::move(bb));
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
            .findMethod(env, "<init>", "()V")
            .findMethod(env, "size", "()I")
            .findMethod(env, "get", "(I)Ljava/lang/Object;")
            .findMethod(env, "add", "(Ljava/lang/Object;)Z");

    // SimulcastLayer

    gClassSimulcastLayer.findClass(env, SRTC_PACKAGE_NAME "/SimulcastLayer")
            .findMethod(env, "<init>", "(Ljava/lang/String;IIII)V")
            .findField(env, "name", "Ljava/lang/String;")
            .findField(env, "width", "I")
            .findField(env, "height", "I")
            .findField(env, "framesPerSecond", "I")
            .findField(env, "kilobitPerSecond", "I");

    // Track

    gClassTrack.findClass(env, SRTC_PACKAGE_NAME "/Track")
            .findMethod(env, "<init>", "(IIIIL" SRTC_PACKAGE_NAME "/SimulcastLayer;)V");

    // PeerConnection

    gClassPeerConnection.findClass(env, SRTC_PACKAGE_NAME "/PeerConnection")
            .findField(env, "mVideoSingleTrack", "L" SRTC_PACKAGE_NAME "/Track;")
            .findField(env, "mVideoSimulcastTrackList", "Ljava/util/List;")
            .findField(env, "mAudioTrack", "L" SRTC_PACKAGE_NAME "/Track;")
            .findMethod(env, "fromNativeOnConnectionState", "(I)V");

    // OfferConfig

    gClassOfferConfig.findClass(env, SRTC_PACKAGE_NAME "/PeerConnection$OfferConfig")
            .findField(env, "cname", "Ljava/lang/String;");

    // PubVideoCodecConfig

    gClassVideoCodec.findClass(env, SRTC_PACKAGE_NAME "/PeerConnection$PubVideoCodec")
            .findField(env, "codec", "I")
            .findField(env, "profileLevelId", "I");

    // PubVideoConfig

    gClassVideoConfig.findClass(env, SRTC_PACKAGE_NAME "/PeerConnection$PubVideoConfig")
            .findField(env, "codecList", "Ljava/util/ArrayList;")
            .findField(env, "simulcastLayerList", "Ljava/util/ArrayList;");

    // PubAudioCodecConfig

    gClassAudioCodec.findClass(env, SRTC_PACKAGE_NAME "/PeerConnection$PubAudioCodec")
            .findField(env, "codec", "I")
            .findField(env, "minPacketTimeMs", "I");

    // PubAudioConfig

    gClassAudioConfig.findClass(env, SRTC_PACKAGE_NAME "/PeerConnection$PubAudioConfig")
            .findField(env, "codecList", "Ljava/util/ArrayList;");
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
    LOG(SRTC_LOG_V, "destructor %p", this);

    mConn.reset();
    free(mOpusEncoder);

    const auto env = getJNIEnv();
    env->DeleteGlobalRef(mThiz);
}

Error JavaPeerConnection::publishVideoSingleFrame(ByteBuffer&& frame)
{
    return mConn->publishVideoSingleFrame(std::move(frame));
}

Error JavaPeerConnection::publishVideoSimulcastFrame(const std::string& layerName,
                                                     ByteBuffer&& frame)
{
    return mConn->publishVideoSimulcastFrame(layerName, std::move(frame));
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
            return mConn->publishAudioFrame(std::move(output));
        }
    }

    return Error::OK;
}

}
