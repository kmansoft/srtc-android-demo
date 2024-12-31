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

        mPeerConnection?.release()
        mPeerConnection = null

        mCamera?.close()
        mCamera = null

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
        mPeerConnection = PeerConnection()

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
                profileId = 0x42
                level = 31
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

                    val videoTrack = mPeerConnection?.videoTrack
                    val audioTrack = mPeerConnection?.audioTrack;

                    MyLog.i(TAG, "Video track: %s", videoTrack)
                    MyLog.i(TAG, "Audio track: %s", audioTrack)
                }
            }
        })
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
         Util.toast(this, R.string.error_camera_open)

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
        return surfaceList
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

    companion object {
        private const val TAG = "MainActivity"

        private const val PREF_KEY_SERVER = "whip_server"
        private const val PREF_KEY_TOKEN = "whip_token"

        private const val PERM_CAMERA = android.Manifest.permission.CAMERA
        private const val PERM_RECORD_AUDIO =  android.Manifest.permission.RECORD_AUDIO
    }
}
