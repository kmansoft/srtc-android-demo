package org.kman.srtctest

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.Camera
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
import android.view.KeyEvent
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.widget.Button
import android.widget.EditText
import androidx.core.content.ContextCompat
import androidx.preference.PreferenceManager
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.create
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONObject
import org.kman.srtctest.rtc.PeerConnection
import org.kman.srtctest.util.MyLog
import java.nio.charset.StandardCharsets

class MainActivity : Activity(), SurfaceHolder.Callback {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContentView(R.layout.main_activity)

        mSharedPrefs = PreferenceManager.getDefaultSharedPreferences(this)

        mEditWhipServer = findViewById(R.id.whip_server)
        mEditWhipToken = findViewById(R.id.whip_token)
        mButtonConnect = findViewById(R.id.whip_connect)
        mSurfaceViewPreview = findViewById(R.id.preview)

        mButtonConnect.setOnClickListener {
            onClickConnect()
        }
        mSurfaceViewPreview.holder.addCallback(this)

        setFieldsFromPrefs()
        setFieldsFromIntent(intent)
    }

    override fun onDestroy() {
        super.onDestroy()

        mSurfaceViewPreview.holder.removeCallback(this)
        mPreviewSurface = null

        mPeerConnection?.release()
        mPeerConnection = null

        mCamera?.close()
        mCamera = null

        mEncoder?.stop()
        mEncoder?.release()
        mEncoder = null
        mEncoderInputSurface = null

        mMediaThread.quitSafely()
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setFieldsFromIntent(intent)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK && event != null && event.repeatCount ==  0) {
            finish()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onPostResume() {
        super.onPostResume()

        if (!hasPermissions()) {
            requestPermissions(arrayOf(PERM_CAMERA, PERM_RECORD_AUDIO), 1)
        } else if (mPreviewSurface != null) {
            initCameraCapture()
        }
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        mPreviewSurface = holder.surface
        if (hasPermissions()) {
            if (mCamera == null) {
                initCameraCapture()
            } else {
                updateCameraSession()
            }
        }
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        mPreviewSurface = null
        updateCameraSession()
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

        if (mPreviewSurface != null) {
            initCameraCapture()
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

                mSharedPrefs.edit().apply {
                    putString(PREF_KEY_SERVER, server)
                    putString(PREF_KEY_TOKEN, token)
                }.apply()

            } catch (x: Exception) {
                Util.toast(this, R.string.error_parsing_claims, x.toString())
            }
        }
    }

    private fun onClickConnect() {
        val server = mEditWhipServer.text.toString().trim()
        if (server.isEmpty()) {
            mEditWhipServer.error = getString(R.string.error_server_missing)
            mEditWhipServer.requestFocus()
            return
        }
        if (!server.startsWith("https://")) {
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
        val claims = token.split('.')[1]
        val decoded = Base64.decode(claims, Base64.NO_PADDING)
        val json = JSONObject(String(decoded, StandardCharsets.UTF_8))

        val peerConnection = requireNotNull(mPeerConnection)

        val offerConfig = PeerConnection.OfferConfig()
        offerConfig.cname = json.getString("jti")

        val videoConfig = PeerConnection.VideoConfig()
        videoConfig.layerList = listOf(PeerConnection.VideoLayer().apply {
                codec = PeerConnection.VIDEO_CODEC_H264
                profileId = H264_PROFILE
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
                    return
                }

                if (data != null) {
                    val ms1 = SystemClock.elapsedRealtime()
                    Util.toast(this@MainActivity, R.string.sdp_offer_received_sdp_answer, ms1 - ms0)

                    val answer = String(data, StandardCharsets.UTF_8)
                    MyLog.i(TAG, "SDP answer:\n%s", answer)

                    mPeerConnection?.setPublishAnswer(answer)

                    onPublishSdpCompleted();
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

                val format = MediaFormat.createVideoFormat(codecMime, 1280, 720).apply {
                    setInteger(MediaFormat.KEY_BIT_RATE, 2500000)
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
                mEncoder?.setCallback(mEncoderCallback)

                try {
                    mEncoder?.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)

                    mEncoderInputSurface = mEncoder?.createInputSurface()

                    mEncoder?.start()
                } catch (x: Exception) {
                    Util.toast(this, R.string.error_starting_encoder)

                    MyLog.i(TAG, "Error configuring the encoder: %s", x.message)

                    mEncoder?.release()
                    mEncoder = null
                    mEncoderInputSurface = null
                }
            }
        }
    }

    private fun onPeerConnectionConnectState(state: Int) {
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
        val cm = getSystemService(CameraManager::class.java)
        if (ContextCompat.checkSelfPermission(this, PERM_CAMERA) == PackageManager.PERMISSION_GRANTED) {
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
        val camera = mCamera ?: return

        mCameraSession?.close()
        mCameraSession = null

        val surfaceList = createCameraSurfaceList()
        if (surfaceList.isNotEmpty()) {
            @Suppress("DEPRECATION")
            camera.createCaptureSession(surfaceList, mCameraSessionCallback, mMediaHandler)
        }
    }

    private fun onCameraSessionConfigured(session: CameraCaptureSession) {
        if (isDestroyed || mCamera == null) {
            session.close()
            return
        }

        val surfaceList = createCameraSurfaceList()
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

        session.setRepeatingRequest(request, null, mMediaHandler)
    }

    private fun onCameraSessionConfigureFailed(session: CameraCaptureSession) {
        Util.toast(this, R.string.error_camera_session_failed)
    }

    private fun createCameraSurfaceList(): List<Surface> {
        val surfaceList = ArrayList<Surface>()
        mPreviewSurface?.also {
            surfaceList.add(it)
        }
        mEncoderInputSurface?.also {
            surfaceList.add(it)
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
            0x42 -> MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline
            else -> MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline
        }
    }

    private val mMainHandler = Handler(Looper.getMainLooper())
    private val mMediaThread = HandlerThread("Media").apply { start() }
    private val mMediaHandler = Handler(mMediaThread.looper)

    private lateinit var mSharedPrefs: SharedPreferences

    private lateinit var mEditWhipServer: EditText
    private lateinit var mEditWhipToken: EditText
    private lateinit var mButtonConnect: Button
    private lateinit var mSurfaceViewPreview: SurfaceView

    private var mPeerConnection: PeerConnection? = null
    private var mPreviewSurface: Surface? = null

    private var mIsInitCameraDone = false
    private var mCamera: CameraDevice? = null
    private var mCameraSession: CameraCaptureSession? = null

    private var mEncoder: MediaCodec? = null
    private var mEncoderInputSurface: Surface? = null

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
            onCameraError(camera, error);
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

            val isKeyFrame = (info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0

            // MyLog.i(TAG, "Encoder frame: key = %b, %d bytes", isKeyFrame, buffer.limit())

            codec.releaseOutputBuffer(index, false)
        }

        override fun onError(codec: MediaCodec, e: MediaCodec.CodecException) {
        }

        override fun onOutputFormatChanged(codec: MediaCodec, format: MediaFormat) {
            val csd0 = format.getByteBuffer("csd-0")
            val csd1 = format.getByteBuffer("csd-1")

            if (csd0 != null) {
                MyLog.i(TAG,"Encoder format csd0: %d bytes", csd0.limit())
            }
            if (csd1 != null) {
                MyLog.i(TAG,"Encoder format csd1: %d bytes", csd1.limit())
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

        private const val H264_PROFILE = 0x42
        private const val H264_LEVEL = 31
    }
}
