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
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
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
import android.widget.EditText
import androidx.core.content.ContextCompat
import androidx.preference.PreferenceManager
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONObject
import org.kman.srtctest.rtc.PeerConnection
import org.kman.srtctest.util.MyLog
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.util.UUID

class MainActivity : Activity(), SurfaceHolder.Callback {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContentView(R.layout.main_activity)

        mSharedPrefs = PreferenceManager.getDefaultSharedPreferences(this)

        mEditWhipServer = findViewById(R.id.whip_server)
        mEditWhipToken = findViewById(R.id.whip_token)
        mButtonConnect = findViewById(R.id.whip_connect)
        mSurfaceViewPreview = findViewById(R.id.preview)
        mViewGroupBottomBar = findViewById(R.id.bottom_bar)

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

        mCamera?.close()
        mCamera = null

        mCameraTexture?.release()

        mCameraThread.quitSafely()
        mEncoderThread.quitSafely()

        mRenderThread.release()

        disconnect()
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
        mPreviewTarget = mRenderThread.createTarget(holder.surface, frame.width(), frame.height())
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
        mPeerConnection?.release()
        mPeerConnection = null

        mEncoder?.stop()
        mEncoder?.release()
        mEncoder = null

        mEncoderTarget?.release()
        mEncoderTarget = null

        if (!isDestroyed) {
            updateCameraSession()
        }
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

            mViewGroupBottomBar.visibility = if (show) View.VISIBLE else View.GONE
        }
    }

    private fun onClickConnect() {
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

        // Release current peer connection
        mPeerConnection?.release()
        mPeerConnection = null

        // And create a new one
        mPeerConnection = PeerConnection().apply {
            setConnectionStateListener { state ->
                onPeerConnectionConnectState(state)
            }
        }

        // Create the SDP offer
        val peerConnection = requireNotNull(mPeerConnection)

        val offerConfig = PeerConnection.OfferConfig()
        offerConfig.cname = UUID.randomUUID().toString()

        val videoConfig = PeerConnection.VideoConfig()
        videoConfig.layerList = listOf(PeerConnection.VideoLayer().apply {
                codec = PeerConnection.VIDEO_CODEC_H264
                profileId = H264_PROFILE_BASELINE
                level = H264_LEVEL
            }).toTypedArray()

        val offer = try {
            peerConnection.initPublishOffer(
                offerConfig,
                videoConfig,
                null
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
                    Util.toast(this@MainActivity, R.string.sdp_offer_received_sdp_answer, ms1 - ms0)

                    val answer = String(data, StandardCharsets.UTF_8)
                    MyLog.i(TAG, "SDP answer:\n%s", answer)

                    try {
                        mPeerConnection?.setPublishAnswer(answer)
                    } catch (x: Exception) {
                        Util.toast(this@MainActivity, R.string.error_remote_description, x.message)
                        showConnectUI(true)
                        return
                    }

                    onPublishSdpCompleted()
                }
            }
        })
    }

    private fun onPublishSdpCompleted() {
        val videoTrack = mPeerConnection?.videoTrack
        val audioTrack = mPeerConnection?.audioTrack

        MyLog.i(TAG, "Video track: %s", videoTrack)
        MyLog.i(TAG, "Audio track: %s", audioTrack)

        if (videoTrack != null && mEncoder == null) {
            val codecMime = when(videoTrack.codec) {
                PeerConnection.VIDEO_CODEC_H264 -> MIME_VIDEO_H264
                else -> {
                    Util.toast(this, R.string.error_unsupported_video_codec, videoTrack.codec)
                    return
                }
            }
            val codecList = MediaCodecList(MediaCodecList.REGULAR_CODECS)
            val codecInfo = findEncoder(codecList, codecMime, false) ?:
                    findEncoder(codecList, codecMime, true)
            if (codecInfo == null) {
                Util.toast(this, R.string.error_no_encoder)
            } else {
                MyLog.i(TAG, "Encoder for %s: %s", codecMime, codecInfo.name)

                var size = Size(1280, 720)

                if (mCameraOrientation == 90 || mCameraOrientation == 270) {
                    size = Size(size.height, size.width)
                }

                val format = MediaFormat.createVideoFormat(codecMime, size.width, size.height).apply {
                    setInteger(MediaFormat.KEY_BIT_RATE, 2000000)
                    setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 2)
                    setInteger(MediaFormat.KEY_FRAME_RATE, 15)
                    if (videoTrack.codec == PeerConnection.VIDEO_CODEC_H264) {
                        setInteger(
                            MediaFormat.KEY_PROFILE,
                            findEncoderProfile(videoTrack.profileId)
                        )
                        setInteger(MediaFormat.KEY_LEVEL, videoTrack.level)
                    }
                }

                mEncoder = MediaCodec.createByCodecName(codecInfo.name)

                try {
                    mEncoder?.setCallback(mEncoderCallback, mEncoderHandler)
                    mEncoder?.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)

                    val inputSurface = mEncoder?.createInputSurface()

                    mEncoder?.start()

                    if (inputSurface != null) {
                       mEncoderTarget = mRenderThread.createTarget(inputSurface, size.width, size.height)
                    }
                } catch (x: Exception) {
                    Util.toast(this, R.string.error_starting_encoder)

                    MyLog.i(TAG, "Error configuring the encoder: %s", x.message)

                    mEncoder?.release()
                    mEncoder = null
                    mEncoderTarget?.release()
                    mEncoderTarget = null
                }
            }
        }
    }

    private fun onPeerConnectionConnectState(state: Int) {
        if (state == PeerConnection.CONNECTION_STATE_FAILED) {
            mEncoder?.release()
            mEncoder = null
            mEncoderTarget?.release()
            mEncoderTarget = null
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

        Util.toast(this, R.string.pc_connection_state, getString(stateId))
    }

    private fun initCameraCapture() {
        if (!mIsInitCameraDone) {
            mIsInitCameraDone = true

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

    private fun findEncoderProfile(profileId: Int): Int {
        return when(profileId) {
            H264_PROFILE_BASELINE -> MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline
            H264_PROFILE_MAIN -> MediaCodecInfo.CodecProfileLevel.AVCProfileMain
            else -> MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline
        }
    }

    private val mMainHandler = Handler(Looper.getMainLooper())

    private val mCameraThread = HandlerThread("Camera").apply { start() }
    private val mCameraHandler = Handler(mCameraThread.looper)

    private val mEncoderThread = HandlerThread("Camera").apply { start() }
    private val mEncoderHandler = Handler(mEncoderThread.looper)

    private lateinit var mSharedPrefs: SharedPreferences

    private lateinit var mEditWhipServer: EditText
    private lateinit var mEditWhipToken: EditText
    private lateinit var mButtonConnect: Button
    private lateinit var mSurfaceViewPreview: SurfaceView
    private lateinit var mViewGroupBottomBar: ViewGroup

    private lateinit var mRenderThread: RenderThread

    private var mIsConnectUIVisible = true

    private var mPeerConnection: PeerConnection? = null

    private var mIsInitCameraDone = false
    private var mCamera: CameraDevice? = null
    private var mCameraOrientation = 0
    private var mCameraSession: CameraCaptureSession? = null

    private var mEncoder: MediaCodec? = null
    private var mEncoderTarget: RenderThread.RenderTarget? = null

    private var mCameraTexture: RenderThread.CameraTexture? = null
    private var mPreviewTarget: RenderThread.RenderTarget? = null

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

    private val mEncoderCallback = object : MediaCodec.Callback() {
        override fun onInputBufferAvailable(codec: MediaCodec, index: Int) {
        }

        override fun onOutputBufferAvailable(
            codec: MediaCodec,
            index: Int,
            info: MediaCodec.BufferInfo
        ) {
            val buffer = codec.getOutputBuffer(index) ?: return

            try {
                mPeerConnection?.publishVideoFrame(buffer)
            } catch (x: Exception) {
                Util.toast(this@MainActivity, R.string.error_publishing_video_frame, x.message)
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
                    mPeerConnection?.setVideoCodecSpecificData(csdList.toTypedArray())
                } catch (x: Exception) {
                    Util.toast(this@MainActivity, R.string.error_setting_video_frame_csd, x.message)
                }
            }
        }
    }

    companion object {
        private const val TAG = "MainActivity"

        private const val PREF_KEY_SERVER = "whip_server"
        private const val PREF_KEY_TOKEN = "whip_token"

        private const val PERM_CAMERA = android.Manifest.permission.CAMERA
        private const val PERM_RECORD_AUDIO =  android.Manifest.permission.RECORD_AUDIO

        private const val MIME_VIDEO_H264 = "video/avc"

        private const val H264_PROFILE_BASELINE = 0x42
        private const val H264_PROFILE_MAIN = 0x4D
        private const val H264_LEVEL = 31
    }
}
