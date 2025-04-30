package org.kman.srtctest

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.media.MediaRecorder
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
import android.util.Base64
import android.util.Range
import android.util.Size
import android.view.KeyEvent
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.preference.PreferenceManager
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONObject
import org.kman.srtctest.rtc.PeerConnection
import org.kman.srtctest.rtc.SimulcastLayer
import org.kman.srtctest.rtc.Track
import org.kman.srtctest.util.MyLog
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.ShortBuffer
import java.nio.charset.StandardCharsets
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.sqrt

class MainActivity : Activity(), SurfaceHolder.Callback {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContentView(R.layout.main_activity)

        mSharedPrefs = PreferenceManager.getDefaultSharedPreferences(this)

        mEditWhipServer = findViewById(R.id.whip_server)
        mEditWhipToken = findViewById(R.id.whip_token)
        mCheckIsSimulcast = findViewById(R.id.whip_simulcast)
        mButtonConnect = findViewById(R.id.whip_connect)
        mStatusTextView = findViewById(R.id.status)
        mSurfaceViewPreview = findViewById(R.id.preview)
        mViewGroupBottomBar = findViewById(R.id.bottom_bar)
        mViewGroupInputBar = findViewById(R.id.input_bar)
        mTextRms = findViewById(R.id.audio_rms)

        mButtonConnect.setOnClickListener {
            onClickConnect()
        }
        mSurfaceViewPreview.holder.addCallback(this)

        mRenderThread = RenderThread(this, object : RenderThread.ErrorCallback {
            override fun onError(error: String) {
                Util.toast(this@MainActivity, R.string.error_opengl, error)
            }
        })

        setFieldsFromPrefs()
        setFieldsFromIntent(intent)
    }

    override fun onDestroy() {
        super.onDestroy()

        mSurfaceViewPreview.holder.removeCallback(this)
        mPreviewTarget?.release()

        // The camera needs to be released on the camera thread
        val camera = mCamera
        mCamera = null

        if (camera != null) {
            mCameraHandler.blockingCall {
                camera.close()
            }
        }

        mCameraTexture?.release()

        disconnect()

        mCameraThread.quitSafely()
        mEncoderThread.quitSafely()

        mRenderThread.release()
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setFieldsFromIntent(intent)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK && event != null && event.repeatCount ==  0) {
            if (!mIsConnectUIVisible) {
                disconnect()
                showConnectUI(true)
            } else {
                finish()
            }
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onPostResume() {
        super.onPostResume()

        if (!hasPermissions()) {
            requestPermissions(arrayOf(PERM_CAMERA, PERM_RECORD_AUDIO), 1)
        } else {
            initCameraCapture()
        }
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        mPreviewTarget?.release()

        val frame = holder.surfaceFrame
        mPreviewTarget = mRenderThread.createTarget(holder.surface, "preview", frame.width(), frame.height())
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        mPreviewTarget?.setSize(width, height)
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        mPreviewTarget?.release()
        mPreviewTarget = null
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        for (result in grantResults) {
            if (result != PackageManager.PERMISSION_GRANTED) {
                Util.toast(this, R.string.error_no_permissions)
                return
            }
        }

        initCameraCapture()
    }

    private fun disconnect() {
        releasePeerConnection()
        releaseEncoders()

        if (!isDestroyed) {
            updateCameraSession()
        }
    }

    private fun releasePeerConnection() {
        mPeerConnection?.release()
        mPeerConnection = null
    }

    private fun releaseEncoders() {
        mAudioRecord?.stop()
        mAudioRecord?.release()
        mAudioRecord = null

        mIsAudioRecordQuit.set(true)
        mAudioThread?.join()
        mAudioThread = null

        mVideoEncoderSingle?.release()
        mVideoEncoderSingle = null
        for (encoder in mVideoEncoderSimulcastList) {
            encoder.release()
        }
        mVideoEncoderSimulcastList.clear()
    }

    private fun hasPermissions(): Boolean {
        return ContextCompat.checkSelfPermission(
            this,
            android.Manifest.permission.CAMERA
        ) == PackageManager.PERMISSION_GRANTED &&
                ContextCompat.checkSelfPermission(
                    this,
                    android.Manifest.permission.RECORD_AUDIO
                ) == PackageManager.PERMISSION_GRANTED
    }

    private fun setFieldsFromPrefs() {
        val server = mSharedPrefs.getString(PREF_KEY_SERVER, null)
        val token = mSharedPrefs.getString(PREF_KEY_TOKEN, null)

        if (!server.isNullOrEmpty() && !token.isNullOrEmpty()) {
            mEditWhipServer.setText(server)
            mEditWhipToken.setText(token)
        }
    }

    @SuppressLint("SetTextI18n")
    private fun setFieldsFromIntent(intent: Intent?) {
         val data = intent?.data ?: return

        MyLog.i(TAG, "New intent: %s", data)

        val scheme = data.scheme ?: return
        if (scheme != "srtc") return

        val host = data.host ?: return

        if (host == "ivs") {
            val header = data.getQueryParameter("header") ?: return
            val claims = data.getQueryParameter("claims") ?: return
            val signature = data.getQueryParameter("signature") ?: return

            try {
                val decoded = Base64.decode(claims, Base64.NO_PADDING)
                val json = JSONObject(String(decoded, StandardCharsets.UTF_8))

                var server = json.getString("whip_url")
                val participantId = json.getString("jti")
                val token = "${header}.${claims}.${signature}"

                if (server.isNotEmpty() && participantId.isNotEmpty()) {
                    server = "${server}/publish/${participantId}"
                }
                mEditWhipServer.setText(server)
                mEditWhipToken.setText(token)

                saveFieldsToPrefs(server, token)
            } catch (x: Exception) {
                Util.toast(this, R.string.error_parsing_claims, x.toString())
            }
        } else if (host == "pion") {
            val server = data.getQueryParameter("server") ?: return
            val token = data.getQueryParameter("token") ?: return

            mEditWhipServer.setText(server)
            mEditWhipToken.setText(token)

            saveFieldsToPrefs(server, token)
        }
    }

    private fun saveFieldsToPrefs(server: String, token: String) {
        mSharedPrefs.edit().apply {
            putString(PREF_KEY_SERVER, server)
            putString(PREF_KEY_TOKEN, token)
        }.apply()
    }

    private fun showConnectUI(show: Boolean) {
        if (mIsConnectUIVisible != show) {
            mIsConnectUIVisible = show

            mViewGroupInputBar.visibility = if (show) View.VISIBLE else View.GONE
            mButtonConnect.text = getString(if (show) R.string.whip_connect else R.string.whip_disconnect)
        }
    }

    private fun onClickConnect() {
        if (!mIsConnectUIVisible) {
            releasePeerConnection()
            releaseEncoders()

            showConnectUI(true)
        } else {
            val server = mEditWhipServer.text.toString().trim()
            if (server.isEmpty()) {
                mEditWhipServer.error = getString(R.string.error_server_missing)
                mEditWhipServer.requestFocus()
                return
            }
            if (!server.contains("://")) {
                mEditWhipServer.error = getString(R.string.error_server_invalid)
                mEditWhipServer.requestFocus()
                return
            }
            mEditWhipServer.error = null

            val token = mEditWhipToken.text.toString().trim()
            if (token.isEmpty()) {
                mEditWhipToken.error = getString(R.string.error_token_missing)
                mEditWhipToken.requestFocus()
                return
            }
            mEditWhipToken.error = null

            // Save just in case
            saveFieldsToPrefs(server, token)

            // Release peer connection
            releasePeerConnection()

            // Release encoders
            releaseEncoders()

            // And create a new one
            mPeerConnection = PeerConnection().apply {
                setConnectionStateListener { state ->
                    onPeerConnectionConnectState(state)
                }
            }

            // Create the SDP offer
            val peerConnection = requireNotNull(mPeerConnection)

            val offerConfig = PeerConnection.OfferConfig()

            // Video options
            val videoConfig = PeerConnection.PubVideoConfig()

            val codecList = MediaCodecList(MediaCodecList.REGULAR_CODECS)
            val codecH264 = findEncoder(codecList, MIME_VIDEO_H264, false) ?: findEncoder(
                codecList,
                MIME_VIDEO_H264,
                true
            )
            if (codecH264 == null) {
                Util.toast(this, R.string.error_no_encoder)
                return
            }

            videoConfig.codecList.add(
                // Baseline
                PeerConnection.PubVideoCodec(PeerConnection.VIDEO_CODEC_H264, 0x42001f),
            )

            val capsH264 = codecH264.getCapabilitiesForType(MIME_VIDEO_H264)
            if (isProfileSupported(
                    capsH264,
                    MediaCodecInfo.CodecProfileLevel.AVCProfileConstrainedBaseline
                )
            ) {
                // Baseline constrained
                videoConfig.codecList.add(
                    PeerConnection.PubVideoCodec(PeerConnection.VIDEO_CODEC_H264, 0x42e01f)
                )
            }
            if (isProfileSupported(capsH264, MediaCodecInfo.CodecProfileLevel.AVCProfileMain)) {
                // Main
                videoConfig.codecList.add(
                    PeerConnection.PubVideoCodec(PeerConnection.VIDEO_CODEC_H264, 0x4d001f)
                )
            }

            // Simulcast
            if (mCheckIsSimulcast.isChecked) {
                var size = Size(PUBLISH_VIDEO_WIDTH, PUBLISH_VIDEO_HEIGHT)
                if (mCameraOrientation == 90 || mCameraOrientation == 270) {
                    size = Size(size.height, size.width)
                }

                val sizeLow = Size(size.width / 4, size.height / 4)
                val sizeMid = Size(size.width / 2, size.height / 2)
                val sizeHigh = Size(size.width, size.height)

                videoConfig.simulcastLayerList.add(
                    SimulcastLayer(
                        "low", sizeLow.width, sizeLow.height,
                        ENCODE_FRAMES_PER_SECOND, BITRATE_LOW
                    )
                )
                videoConfig.simulcastLayerList.add(
                    SimulcastLayer(
                        "mid", sizeMid.width, sizeMid.height,
                        ENCODE_FRAMES_PER_SECOND, BITRATE_MID
                    )
                )
                videoConfig.simulcastLayerList.add(
                    SimulcastLayer(
                        "hi", sizeHigh.width, sizeHigh.height,
                        ENCODE_FRAMES_PER_SECOND, BITRATE_HIGH
                    )
                )
            }

            // Audio config
            val audioConfig = PeerConnection.PubAudioConfig()
            audioConfig.codecList.add(
                PeerConnection.PubAudioCodec(
                    PeerConnection.AUDIO_CODEC_OPUS,
                    RECORDER_CHUNK_MS,
                    RECORDER_CHANNELS == 2
                )
            )

            val offer = try {
                peerConnection.initPublishOffer(
                    offerConfig,
                    videoConfig,
                    audioConfig
                )
            } catch (x: Exception) {
                Util.toast(this, R.string.sdp_offer_error, x.message)
                return
            }

            showConnectUI(false)

            // offer = Util.loadRawResource(this, R.raw.pub_offer_chrome_v_only)

            val request = Request.Builder().apply {
                url(server)
                method("POST", offer.toRequestBody("application/sdp".toMediaType()))
                header("Authorization", "Bearer $token")
            }.build()

            val ms0 = SystemClock.elapsedRealtime()
            HttpClient.execute(request, object : HttpClient.Callback {
                override fun onCompleted(response: Response?, data: ByteArray?, error: Exception?) {
                    if (error != null) {
                        Util.toast(this@MainActivity, R.string.sdp_offer_error, error.message)
                        showConnectUI(true)
                        return
                    }

                    if (data != null) {
                        val ms1 = SystemClock.elapsedRealtime()
                        Util.toast(
                            this@MainActivity,
                            R.string.sdp_offer_received_sdp_answer,
                            ms1 - ms0
                        )

                        val answer = String(data, StandardCharsets.UTF_8)
                        MyLog.i(TAG, "SDP answer:\n%s", answer)

                        mSetAnswerTimeMillis = SystemClock.elapsedRealtime()

                        try {
                            mPeerConnection?.setPublishAnswer(answer)
                        } catch (x: Exception) {
                            Util.toast(
                                this@MainActivity,
                                R.string.error_remote_description,
                                x.message
                            )
                            showConnectUI(true)
                            return
                        }

                        onPublishSdpCompleted()
                    }
                }
            })
        }
    }

    private fun onPublishSdpCompleted() {
        val videoSingleTrack = mPeerConnection?.videoSingleTrack
        val videoSimulcastTrackList = mPeerConnection?.videoSimulcastTrackList
        val audioTrack = mPeerConnection?.audioTrack

        MyLog.i(TAG, "Video single track: %s", videoSingleTrack)
        MyLog.i(TAG, "Video simulcast track list: %s", videoSimulcastTrackList)
        MyLog.i(TAG, "Audio track: %s", audioTrack)

        if (videoSingleTrack != null) {
            var size = Size(PUBLISH_VIDEO_WIDTH, PUBLISH_VIDEO_HEIGHT)
            if (mCameraOrientation == 90 || mCameraOrientation == 270) {
                size = Size(size.height, size.width)
            }

            if (mVideoEncoderSingle == null) {
                mVideoEncoderSingle = EncoderWrapper(this, videoSingleTrack, size,
                    mRenderThread, mEncoderHandler)
                mVideoEncoderSingle?.start()
            }
        } else if (!videoSimulcastTrackList.isNullOrEmpty()) {
            for (track in videoSimulcastTrackList) {
                val layer = requireNotNull(track.simulcastLayer)
                val size = Size(layer.width, layer.height)
                val encoder = EncoderWrapper(this, track, size,
                    mRenderThread, mEncoderHandler)
                if (encoder.start()) {
                    mVideoEncoderSimulcastList.add(encoder)
                }
            }
        } else {
            MyLog.i(TAG, "Error: no video tracks")
            Util.toast(this, R.string.error_no_video_tracks)
        }

        if (audioTrack != null) {
            initAudioRecording(audioTrack.codecOptions)
        }
    }

    private fun onPeerConnectionConnectState(state: Int) {
        if (state == PeerConnection.CONNECTION_STATE_FAILED) {
            releasePeerConnection()
            releaseEncoders()

            showConnectUI(true)
        }

        val stateId = when (state) {
            PeerConnection.CONNECTION_STATE_CONNECTING ->
                R.string.pc_state_connecting
            PeerConnection.CONNECTION_STATE_CONNECTED ->
                R.string.pc_state_connected
            PeerConnection.CONNECTION_STATE_FAILED ->
                R.string.pc_state_failed
            PeerConnection.CONNECTION_STATE_CLOSED ->
                R.string.pc_state_closed
            else -> return
        }

        var message = getString(R.string.pc_connection_state, getString(stateId))
        if (state == PeerConnection.CONNECTION_STATE_CONNECTED) {
            val elapsed = SystemClock.elapsedRealtime() - mSetAnswerTimeMillis
            message += " "
            message += getString(R.string.pc_time_to_connect, elapsed)
        }

        mStatusTextView.text = message
    }

    private fun initCameraCapture() {
        if (!mIsInitCameraDone) {
            mIsInitCameraDone = true

            // Find the camera
            // Find the camera
            val cm = getSystemService(CameraManager::class.java)
            val frontCameraId = cm.cameraIdList.find {
                cm.getCameraCharacteristics(it).get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_FRONT
            }

            if (frontCameraId == null) {
                Util.toast(this, R.string.error_no_front_camera)
                return
            }

            initCameraCapture(frontCameraId)
        }
    }

    private fun initAudioRecording(codecOptions: Track.CodecOptions?) {
        if (mAudioRecord == null) {
            if (ContextCompat.checkSelfPermission(
                    this,
                    PERM_RECORD_AUDIO
                ) == PackageManager.PERMISSION_GRANTED
            ) {
                val minptime = codecOptions?.minptime ?: RECORDER_CHUNK_MS
                val stereo = codecOptions?.stereo ?: (RECORDER_CHANNELS == 2)

                val bufferSize = AudioRecord.getMinBufferSize(
                    RECORDER_SAMPLE_RATE,
                    if (stereo) 2 else 1,
                    RECORDER_AUDIO_ENCODING
                )

                val format = AudioFormat.Builder().apply {
                    setSampleRate(RECORDER_SAMPLE_RATE)
                    setChannelMask(
                        if (stereo) AudioFormat.CHANNEL_IN_STEREO
                        else AudioFormat.CHANNEL_IN_MONO)
                    setEncoding(RECORDER_AUDIO_ENCODING)
                }.build()

                val audioRecord = AudioRecord.Builder().apply {
                    setAudioSource(MediaRecorder.AudioSource.DEFAULT)
                    setBufferSizeInBytes(bufferSize)
                    setAudioFormat(format)
                }.build()

                if (audioRecord == null) {
                    Util.toast(this, R.string.error_cannot_create_audio_record)
                    return
                }

                mAudioRecord = audioRecord
                mAudioRecord?.startRecording()

                mIsAudioRecordQuit.set(false)

                mAudioThread = Thread {
                    audioThreadFunc(audioRecord, minptime, stereo)
                }.apply {
                    name = "AudioRecord"
                    start()
                }
            }
        }
    }

    private fun initCameraCapture(cameraId: String) {
        if (ContextCompat.checkSelfPermission(this, PERM_CAMERA) == PackageManager.PERMISSION_GRANTED) {
            val cm = getSystemService(CameraManager::class.java)
            val chars = cm.getCameraCharacteristics(cameraId)
            val streamConfigMap = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            val outputSizes = streamConfigMap?.getOutputSizes(SurfaceTexture::class.java)
            if (outputSizes == null) {
                Util.toast(this, R.string.error_no_camera_output_sizes)
                return
            }

            val chosenSize =
                outputSizes.find { it.width == 1920 && it.height == 1080 } ?:
                outputSizes.find { it.width == 1280 && it.height == 720}
            if (chosenSize == null) {
                Util.toast(this, R.string.error_no_camera_output_sizes)
                return
            }

            mCameraOrientation = chars.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0

            mCameraTexture?.release()
            mCameraTexture = mRenderThread.createCameraTexture(chosenSize.width, chosenSize.height, mCameraOrientation)

            mCameraTexture?.also {
                it.texture.setOnFrameAvailableListener({ surfaceTexture ->
                    val cameraTexture = mCameraTexture
                    if (cameraTexture != null && cameraTexture.texture == surfaceTexture) {
                        mRenderThread.onCameraTextureUpdated(cameraTexture)
                    }
                }, mCameraHandler)
            }

            cm.openCamera(cameraId, mCameraStateCallback, mMainHandler)
        }
    }

    private fun onCameraOpened(camera: CameraDevice) {
        if (isDestroyed) {
            camera.close()
            return
        }

        mCamera = camera
        updateCameraSession()
    }

    private fun onCameraClosed(camera: CameraDevice) {
        mCamera = null
    }

    private fun onCameraError(camera: CameraDevice, error: Int) {
         Util.toast(this, R.string.error_camera_open, error)

        mCamera?.close()
        mCamera = null
    }

    private fun updateCameraSession() {
        if (isDestroyed) {
            return
        }

        val camera = mCamera ?: return

        mCameraSession?.close()
        mCameraSession = null

        val surfaceList = getCaptureSurfaceList()
        if (surfaceList.isNotEmpty()) {
            @Suppress("DEPRECATION")
            camera.createCaptureSession(surfaceList, mCameraSessionCallback, mCameraHandler)
        }
    }

    private fun onCameraSessionConfigured(session: CameraCaptureSession) {
        if (isDestroyed || mCamera == null) {
            session.close()
            return
        }

        val surfaceList = getCaptureSurfaceList()
        if (surfaceList.isEmpty()) {
            session.close()
            return
        }

        mCameraSession = session

        val camera = requireNotNull(mCamera)
        val request = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
            for (surface in surfaceList) {
                addTarget(surface)
            }
            set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, Range.create(15, 15))
        }.build()

        session.setRepeatingRequest(request, null, mCameraHandler)
    }

    private fun onCameraSessionConfigureFailed(session: CameraCaptureSession) {
        Util.toast(this, R.string.error_camera_session_failed)
    }

    private fun getCaptureSurfaceList(): List<Surface> {
        val surfaceList = ArrayList<Surface>()
        mCameraTexture?.also {
            surfaceList.add(it.surface)
        }
        return surfaceList
    }

    private fun findEncoder(codecList: MediaCodecList,
                            mimeType: String,
                            allowSoftware: Boolean) : MediaCodecInfo? {
        for (info in codecList.codecInfos) {
            if (info.isEncoder) {
                if (allowSoftware || info.isHardwareAccelerated) {
                    for (type in info.supportedTypes) {
                        if (type == mimeType) {
                            return info
                        }
                    }
                }
            }
        }

        return null
    }

    private fun isProfileSupported(caps: MediaCodecInfo.CodecCapabilities, profileId: Int): Boolean {
        for (profile in caps.profileLevels) {
            if (profile.profile == profileId) {
                return true
            }
        }
        return false
    }

    private fun findEncoderProfile(profileId: Int): Int {
        return when(profileId) {
            H264_PROFILE_BASELINE -> MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline
            H264_PROFILE_CONSTRAINED_BASELINE -> MediaCodecInfo.CodecProfileLevel.AVCProfileConstrainedBaseline
            H264_PROFILE_MAIN -> MediaCodecInfo.CodecProfileLevel.AVCProfileMain
            else -> MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline
        }
    }

    private fun findEncoderLevel(levelId: Int): Int {
        return when(levelId) {
            30 -> MediaCodecInfo.CodecProfileLevel.AVCLevel3
            31 -> MediaCodecInfo.CodecProfileLevel.AVCLevel31
            40 -> MediaCodecInfo.CodecProfileLevel.AVCLevel4
            41 -> MediaCodecInfo.CodecProfileLevel.AVCLevel41
            42 -> MediaCodecInfo.CodecProfileLevel.AVCLevel42
            else -> MediaCodecInfo.CodecProfileLevel.AVCLevel31
        }
    }

    private fun audioThreadFunc(record: AudioRecord, minptime: Int, stereo: Boolean) {
        val samplesPerChannel = RECORDER_SAMPLE_RATE * minptime / 1000
        var chunkSize = samplesPerChannel
        if (RECORDER_AUDIO_ENCODING == AudioFormat.ENCODING_PCM_16BIT) {
            chunkSize *= 2
        }
        if (stereo) {
            chunkSize *= 2
        }

        val byteBuffer = ByteBuffer.allocateDirect(chunkSize).apply {
            order(ByteOrder.nativeOrder())
        }
        val shortBuffer = byteBuffer.asShortBuffer()

        var lastTime = SystemClock.elapsedRealtime()
        var lastFrameCount = 0

        while (!mIsAudioRecordQuit.get()) {
            val r = record.read(byteBuffer, byteBuffer.capacity(), AudioRecord.READ_BLOCKING)
            if (r > 0) {
                val rms = calculateRms(shortBuffer, r / 2)
                mMainHandler.post {
                    showRms(rms)
                }

                try {
                    mPeerConnection?.publishAudioFrame(
                        byteBuffer, r,
                        RECORDER_SAMPLE_RATE,
                        if (stereo) 2 else 1
                    )
                } catch (x: Exception) {
                    mMainHandler.post {
                        Util.toast(
                            this@MainActivity,
                            R.string.error_publishing_audio_frame,
                            x.message
                        )
                    }
                }

                lastFrameCount += 1
                val now = SystemClock.elapsedRealtime()
                if (now - lastTime >= 1000L) {
                    val fps = Math.round(lastFrameCount * 1000.0 / (now - lastTime))
                    MyLog.i(TAG, "Audio fps=%d, chunkSize=%d", fps, chunkSize)
                    lastTime = now
                    lastFrameCount = 0
                }
            }
        }
    }

    private fun calculateRms(buffer: ShortBuffer, sampleCount: Int): Float {
        var sum = 0.0f
        for (i in 0 until sampleCount) {
            val value = buffer[i].toFloat() / 32767.0f
            sum += value * value
        }
        return sqrt(sum / sampleCount)
    }

    private fun showRms(rms: Float) {
        mTextRms.text = String.format(Locale.US, "rms = %.2f", rms)
    }

    private fun setVideoCodecSpecificData(track: Track, csdList: Array<ByteBuffer>) {
        val layer = track.simulcastLayer
        if (layer == null) {
            mPeerConnection?.setVideoSingleCodecSpecificData(csdList)
        } else {
            mPeerConnection?.setVideoSimulcastCodecSpecificData(layer, csdList)
        }
    }

    private fun publishVideoFrame(track: Track, frame: ByteBuffer) {
        val layer = track.simulcastLayer
        if (layer == null) {
            mPeerConnection?.publishVideoSingleFrame(frame)
        } else {
            mPeerConnection?.publishVideoSimulcastFrame(layer, frame)
        }
    }

    private val mMainHandler = Handler(Looper.getMainLooper())

    private val mCameraThread = HandlerThread("Camera").apply { start() }
    private val mCameraHandler = Handler(mCameraThread.looper)

    private val mEncoderThread = HandlerThread("Encoder").apply { start() }
    private val mEncoderHandler = Handler(mEncoderThread.looper)

    private lateinit var mSharedPrefs: SharedPreferences

    private lateinit var mEditWhipServer: EditText
    private lateinit var mEditWhipToken: EditText
    private lateinit var mCheckIsSimulcast: CheckBox
    private lateinit var mButtonConnect: Button
    private lateinit var mStatusTextView: TextView
    private lateinit var mSurfaceViewPreview: SurfaceView
    private lateinit var mViewGroupBottomBar: ViewGroup
    private lateinit var mViewGroupInputBar: ViewGroup
    private lateinit var mTextRms: TextView

    private lateinit var mRenderThread: RenderThread

    private var mIsConnectUIVisible = true

    private var mSetAnswerTimeMillis = 0L
    private var mPeerConnection: PeerConnection? = null

    private var mIsInitCameraDone = false
    private var mCamera: CameraDevice? = null
    private var mCameraOrientation = 0
    private var mCameraSession: CameraCaptureSession? = null

    private var mVideoEncoderSingle: EncoderWrapper? = null
    private val mVideoEncoderSimulcastList = ArrayList<EncoderWrapper>()

    private var mCameraTexture: RenderThread.CameraTexture? = null
    private var mPreviewTarget: RenderThread.RenderTarget? = null

    private val mIsAudioRecordQuit = AtomicBoolean(false)
    private var mAudioRecord: AudioRecord? = null
    private var mAudioThread: Thread? = null

    private class EncoderWrapper(
        val activity: MainActivity,
        val track: Track,
        val size: Size,
        val renderThread: RenderThread,
        val handler: Handler,
        ) {

        var encoder: MediaCodec? = null
        var renderTarget: RenderThread.RenderTarget? = null

        fun start(): Boolean {
            val codec = track.codec
            val mime = when(codec) {
                PeerConnection.VIDEO_CODEC_H264 -> MIME_VIDEO_H264
                else -> {
                    Util.toast(activity, R.string.error_unsupported_video_codec, codec)
                    return false
                }
            }

            val codecList = MediaCodecList(MediaCodecList.REGULAR_CODECS)
            val codecInfo = activity.findEncoder(codecList, mime, false) ?:
                    activity.findEncoder(codecList, mime, true)
            if (codecInfo == null) {
                Util.toast(activity, R.string.error_no_encoder)
            } else {
                MyLog.i(TAG, "Encoder for %s: %s", mime, codecInfo.name)

                val format = MediaFormat.createVideoFormat(mime, size.width, size.height).apply {
                    setInteger(MediaFormat.KEY_BIT_RATE, BITRATE_HIGH * 1000)
                    setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 2)
                    setInteger(MediaFormat.KEY_FRAME_RATE, ENCODE_FRAMES_PER_SECOND)
                    setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)

                    if (codec == PeerConnection.VIDEO_CODEC_H264) {
                        val codecOptions = track.codecOptions
                        val profileLevelId = codecOptions?.profileLevelId ?: H264_PROFILE_BASELINE
                        setInteger(
                            MediaFormat.KEY_PROFILE,
                            activity.findEncoderProfile(profileLevelId shr 8)
                        )
                        setInteger(MediaFormat.KEY_LEVEL, activity.findEncoderLevel(profileLevelId and 0xff))
                        if (profileLevelId shr 8 == H264_PROFILE_MAIN) {
                            setInteger(MediaFormat.KEY_MAX_B_FRAMES, 0)
                        }
                    }
                }

                encoder = try {
                    MediaCodec.createByCodecName(codecInfo.name)
                } catch (x: Exception) {
                    MyLog.i(TAG, "Error creating the encoder: %s", x.message)
                    Util.toast(activity, R.string.error_creating_encoder)
                    return false
                }

                try {
                    encoder?.setCallback(callback, handler)
                    encoder?.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)

                    val inputSurface = requireNotNull(encoder?.createInputSurface())

                    encoder?.start()

                    val name = "encoder-" + (track.simulcastLayer?.name ?: "default");
                    renderTarget = renderThread.createTarget(inputSurface, name, size.width, size.height)
                } catch (x: Exception) {
                    MyLog.i(TAG, "Error configuring the encoder: %s", x.message)
                    Util.toast(activity, R.string.error_starting_encoder)

                    encoder?.release()
                    encoder = null
                    renderTarget?.release()
                    renderTarget = null

                    return false
                }
            }

            return true
        }

        fun release() {
            val e = encoder
            encoder = null

            if (e != null) {
                handler.blockingCall {
                    e.stop()
                    e.release()
                }
            }

            renderTarget?.release()
            renderTarget = null
        }

        private val callback = object : MediaCodec.Callback() {
            override fun onInputBufferAvailable(codec: MediaCodec, index: Int) {
            }

            override fun onOutputBufferAvailable(
                codec: MediaCodec,
                index: Int,
                info: MediaCodec.BufferInfo
            ) {
                val buffer = codec.getOutputBuffer(index) ?: return

                try {
                    activity.publishVideoFrame(track, buffer)
                } catch (x: Exception) {
                    reportErrorToast(R.string.error_publishing_video_frame, x.message)
                } finally {
                    codec.releaseOutputBuffer(index, false)
                }
            }

            override fun onError(codec: MediaCodec, e: MediaCodec.CodecException) {
            }

            override fun onOutputFormatChanged(codec: MediaCodec, format: MediaFormat) {
                val csd0 = format.getByteBuffer("csd-0")
                val csd1 = format.getByteBuffer("csd-1")

                val csdList = ArrayList<ByteBuffer>()

                if (csd0 != null) {
                    MyLog.i(TAG,"Encoder format csd0: %d bytes", csd0.limit())
                    csdList.add(csd0)
                }
                if (csd1 != null) {
                    MyLog.i(TAG,"Encoder format csd1: %d bytes", csd1.limit())
                    csdList.add(csd1)
                }

                if (csdList.isNotEmpty()) {
                    try {
                        activity.setVideoCodecSpecificData(track, csdList.toTypedArray())
                    } catch (x: Exception) {
                        reportErrorToast(R.string.error_setting_video_frame_csd, x.message)
                    }
                }
            }

            private fun reportErrorToast(resourceId: Int, message: String?) {
                activity.mMainHandler.post {
                    Util.toast(activity, resourceId, message)
                }
            }
        }

    }

    private val mCameraStateCallback = object : CameraDevice.StateCallback() {
        override fun onOpened(camera: CameraDevice) {
            onCameraOpened(camera)
        }

        override fun onClosed(camera: CameraDevice) {
            onCameraClosed(camera)
        }

        override fun onDisconnected(camera: CameraDevice) {
            onCameraClosed(camera)
        }

        override fun onError(camera: CameraDevice, error: Int) {
            onCameraError(camera, error)
        }
    }

    private val mCameraSessionCallback = object : CameraCaptureSession.StateCallback() {
        override fun onConfigured(session: CameraCaptureSession) {
            onCameraSessionConfigured(session)
        }

        override fun onConfigureFailed(session: CameraCaptureSession) {
            onCameraSessionConfigureFailed(session)
        }
    }

    companion object {
        private const val TAG = "MainActivity"

        private const val PREF_KEY_SERVER = "whip_server"
        private const val PREF_KEY_TOKEN = "whip_token"

        private const val PERM_CAMERA = android.Manifest.permission.CAMERA
        private const val PERM_RECORD_AUDIO =  android.Manifest.permission.RECORD_AUDIO

        private const val MIME_VIDEO_H264 = "video/avc"

        private const val H264_PROFILE_BASELINE = 0x4200
        private const val H264_PROFILE_CONSTRAINED_BASELINE = 0x42e0
        private const val H264_PROFILE_MAIN = 0x4d00

        private const val RECORDER_SAMPLE_RATE = 48000
        private const val RECORDER_CHUNK_MS = 20
        private const val RECORDER_CHANNELS = 1
        private const val RECORDER_AUDIO_ENCODING = AudioFormat.ENCODING_PCM_16BIT

        private const val PUBLISH_VIDEO_WIDTH = 1280
        private const val PUBLISH_VIDEO_HEIGHT = 720

        private const val ENCODE_FRAMES_PER_SECOND = 15

        private const val BITRATE_LOW = 500
        private const val BITRATE_MID = 1500
        private const val BITRATE_HIGH = 2500
    }
}
