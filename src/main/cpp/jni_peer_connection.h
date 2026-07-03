#pragma once

#include "srtc/peer_connection.h"
#include <memory>

#include <jni.h>

struct OpusEncoder;

namespace srtc
{
class Track;
class SdpAnswer;
} // namespace srtc

namespace srtc::android
{

class JavaPeerConnection
{
public:
    static void initializeJNI(JNIEnv* env);

    explicit JavaPeerConnection(jobject thiz);
    ~JavaPeerConnection();

    [[nodiscard]] Error setVideoSingleCodecSpecificData(std::vector<srtc::ByteBuffer>&& list);
    [[nodiscard]] Error publishVideoSingleFrame(ByteBuffer&& frame);
    [[nodiscard]] Error setVideoSimulcastCodecSpecificData(const std::string& layerName,
                                                           std::vector<srtc::ByteBuffer>&& list);
    [[nodiscard]] Error publishVideoSimulcastFrame(const std::string& layerName, ByteBuffer&& frame);
    [[nodiscard]] Error publishAudioFrame(const void* frame, size_t size, int sampleRate, int channels);

    std::unique_ptr<PeerConnection> mConn;

    void initTracks(const std::shared_ptr<srtc::SdpAnswer>& answer);

    [[nodiscard]] std::shared_ptr<srtc::Track> getVideoSingleTrack() const;
    [[nodiscard]] std::vector<std::shared_ptr<srtc::Track>> getVideoSimulcastTrackList() const;
    [[nodiscard]] std::shared_ptr<srtc::Track> getAudioTrack() const;

private:
    jobject mThiz;
    OpusEncoder* mOpusEncoder;
    int64_t mOpusPts;

    std::shared_ptr<srtc::Track> mVideoSingleTrack;
    std::vector<std::shared_ptr<srtc::Track>> mVideoSimulcastTrackList;
    std::shared_ptr<srtc::Track> mAudioTrack;
};

} // namespace srtc::android
