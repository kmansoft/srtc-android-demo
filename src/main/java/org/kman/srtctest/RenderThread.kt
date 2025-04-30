package org.kman.srtctest

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.SurfaceTexture
import android.opengl.EGL14
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.Matrix
import android.os.Handler
import android.os.Looper
import android.view.Surface
import org.kman.srtctest.util.MyLog
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.ShortBuffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.atomic.AtomicBoolean
import javax.microedition.khronos.egl.EGL10
import javax.microedition.khronos.egl.EGL11
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.egl.EGLContext
import javax.microedition.khronos.egl.EGLDisplay
import javax.microedition.khronos.egl.EGLSurface
import kotlin.random.Random

class RenderThread(context: Context,
                   errorCallback: ErrorCallback) {

    class CameraTexture(
        val thread: RenderThread,
        val id: Int,
        val texture: SurfaceTexture,
        val surface: Surface,
        val orientation: Int
    ) {
        fun release() {
            thread.release(this)
        }
    }

    class RenderTarget (
        val thread: RenderThread,
        val surface: EGLSurface,
        val name: String,
        var width: Int,
        var height: Int
    ) {
        fun setSize(width: Int, height: Int) {
            thread.setRenderTargetSize(this, width, height)
        }

        fun release() {
            thread.release(this)
        }
    }

    interface ErrorCallback {
        fun onError(error: String)
    }

    fun createCameraTexture(width: Int, height: Int, orientation: Int): CameraTexture? {
        var res: CameraTexture? = null

        if (!blocking {
                if (mIsInitialized) {
                    val egl = requireNotNull(mEgl)
                    egl.eglMakeCurrent(mEglDisplay, mEglPBuffer, mEglPBuffer, mEglContext)

                    val handles = IntArray(1)
                    GLES20.glGenTextures(1, handles, 0)

                    val textureId = handles[0]
                    if (textureId == 0) {
                        sendError("Cannot create an OES texture")
                    } else {
                        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId)
                        GLES20.glTexParameteri(
                            GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
                            GLES20.GL_TEXTURE_WRAP_S,
                            GLES20.GL_CLAMP_TO_EDGE
                        )
                        GLES20.glTexParameteri(
                            GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
                            GLES20.GL_TEXTURE_WRAP_T,
                            GLES20.GL_CLAMP_TO_EDGE
                        )
                        GLES20.glTexParameteri(
                            GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
                            GLES20.GL_TEXTURE_MIN_FILTER,
                            GLES20.GL_LINEAR
                        )
                        GLES20.glTexParameteri(
                            GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
                            GLES20.GL_TEXTURE_MAG_FILTER,
                            GLES20.GL_LINEAR
                        )

                        @SuppressLint("Recycle")
                        val surfaceTexture = SurfaceTexture(textureId)
                        surfaceTexture.setDefaultBufferSize(width, height)
                        @SuppressLint("Recycle")
                        val surface = Surface(surfaceTexture)

                        res = CameraTexture(this@RenderThread, textureId, surfaceTexture, surface, orientation)
                    }
                }
            }) {
            return null
        }

        return res
    }

    fun createTarget(surface: Surface, name: String, width: Int, height: Int): RenderTarget? {
        var res: RenderTarget? = null

        if (!blocking {
                if (mIsInitialized) {
                    val egl = requireNotNull(mEgl)
                    egl.eglMakeCurrent(mEglDisplay, mEglPBuffer, mEglPBuffer, mEglContext)

                    val glSurface = egl.eglCreateWindowSurface(mEglDisplay, mEglConfig, surface, null)
                    if (glSurface == null || glSurface == EGL10.EGL_NO_SURFACE) {
                        sendError("Cannot create a window surface")
                    } else {
                        val target =  RenderTarget(this, glSurface, name, width, height)
                        mRenderTargetList.add(target)

                        res = target
                    }
                }
            }) {
            return null
        }

        return res
    }

    fun onCameraTextureUpdated(texture: CameraTexture) {
        submit {
            val egl = requireNotNull(mEgl)
            egl.eglMakeCurrent(mEglDisplay, mEglPBuffer, mEglPBuffer, mEglContext)

            texture.texture.updateTexImage()

            for (target in mRenderTargetList) {
                renderToTarget(target, texture)
            }
        }
    }

    fun release(texture: CameraTexture?) {
        if (texture != null) {
            blocking {
                val egl = requireNotNull(mEgl)
                egl.eglMakeCurrent(mEglDisplay, mEglPBuffer, mEglPBuffer, mEglContext)

                val handles = IntArray(1) { texture.id }
                GLES20.glDeleteTextures(1, handles, 0)
            }
        }
    }

    fun release(target: RenderTarget?) {
        if (target != null) {
            blocking {
                val egl = requireNotNull(mEgl)
                egl.eglMakeCurrent(mEglDisplay, mEglPBuffer, mEglPBuffer, mEglContext)

                egl.eglDestroySurface(mEglDisplay, target.surface)

                mRenderTargetList.remove(target)
            }
        }
    }

    fun setRenderTargetSize(target: RenderTarget, width: Int, height: Int) {
        submit {
            target.width = width
            target.height = height
        }
    }

    fun release() {
        mIsQuit.set(true)

        mQueue.offer {
            // For the thread to notice the quit flag
        }

        mThread.join()
    }

    private fun submit(r: Runnable): Boolean {
        if (mIsQuit.get()) {
            return false
        }

        if (mQueue.size >= 10) {
            MyLog.i(TAG, "The render queue is full")
        }

        mQueue.offer(r)

        return true
    }

    private fun blocking(r: Runnable): Boolean {
        val latch = CountDownLatch(1)
        val wrapped = {
            r.run()
            latch.countDown()
        }

        if (!submit(wrapped)) {
            return false
        }

        latch.await()
        return true
    }

    private fun run() {
        // Initialize EGL
        val egl = EGLContext.getEGL() as EGL10
        initialize(egl)

        // Execute runnable commands
        while (!mIsQuit.get()) {
            val r = mQueue.take()
            r.run()
        }

        // Cleanup
        cleanup(egl)
    }

    private fun initialize(egl: EGL10) {
        val eglDisplay = egl.eglGetDisplay(EGL10.EGL_DEFAULT_DISPLAY)
        if (eglDisplay == EGL10.EGL_NO_DISPLAY) {
            sendError("Cannot get default display")
            return
        }

        val eglMajorMinor = IntArray(2)
        if (!egl.eglInitialize(eglDisplay, eglMajorMinor)) {
            sendError("Cannot initialize OpenGL")
            return
        }

        val eglNumConfigs = IntArray(1)
        egl.eglGetConfigs(eglDisplay, null, 0, eglNumConfigs)
        if (eglNumConfigs[0] == 0) {
            sendError("No configs")
            return
        }

        val eglConfigs = arrayOfNulls<EGLConfig>(eglNumConfigs[0])
        if (!egl.eglChooseConfig(
                eglDisplay,
                EGL_CONFIG,
                eglConfigs,
                eglConfigs.size,
                eglNumConfigs
            )
        ) {
            sendError("Failed to choose config")
            return
        }

        val eglContext =
            egl.eglCreateContext(eglDisplay, eglConfigs[0], EGL10.EGL_NO_CONTEXT, EGL_CONTEXT_ATTRS)
        if (eglContext == EGL10.EGL_NO_CONTEXT) {
            sendError("Failed to create context")
            return
        }

        val eglPBuffer = egl.eglCreatePbufferSurface(eglDisplay, eglConfigs[0], EGL_PBUFFER_ATTRS)
        if (eglPBuffer == null) {
            sendError("Failed to create PBuffer")
            return
        }

        // Current context
        egl.eglMakeCurrent(eglDisplay, eglPBuffer, eglPBuffer, eglContext)

        // Shaders
        val vshader = loadShader(R.raw.shader_v, GLES20.GL_VERTEX_SHADER)
        val fshader = loadShader(R.raw.shader_f, GLES20.GL_FRAGMENT_SHADER)
        if (vshader == 0 || fshader == 0) {
            return
        }

        // Program
        val program = GLES20.glCreateProgram()
        GLES20.glAttachShader(program, vshader)
        GLES20.glAttachShader(program, fshader)

        GLES20.glBindAttribLocation(program, 0, "a_Position")
        GLES20.glBindAttribLocation(program, 1, "a_TexCoord")

        GLES20.glLinkProgram(program)

        val status = IntArray(1)
        GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, status, 0)

        if (status[0] == 0) {
            val log = GLES20.glGetProgramInfoLog(program)
            sendError("Cannot link program: $log")
            return
        }

        // Buffers
        val vertices = floatArrayOf(
            -0.75f, 0.75f,
            -0.75f, -0.75f,
            0.75f, -0.75f,
            0.75f, 0.75f
        )
        mVerticesBuffer = ByteBuffer.allocateDirect(vertices.size * 4).apply {
            order(ByteOrder.nativeOrder())
        }.asFloatBuffer()
        mVerticesBuffer.put(vertices)
        mVerticesBuffer.position(0)

        val st = floatArrayOf(
            0.0f, 1.0f,
            0.0f, 0.0f,
            1.0f, 0.0f,
            1.0f, 1.0f
        )
        mStBuffer = ByteBuffer.allocateDirect(st.size * 4).apply {
            order(ByteOrder.nativeOrder())
        }.asFloatBuffer()
        mStBuffer.put(st)
        mStBuffer.position(0)

        val order = shortArrayOf(0, 1, 2, 0, 2, 3)
        mOrderBuffer  = ByteBuffer.allocateDirect(order.size * 2).apply {
            order(ByteOrder.nativeOrder())
        }.asShortBuffer()
        mOrderBuffer.put(order)
        mOrderBuffer.position(0)

        // Save for use by thread
        mEgl = egl
        mEglDisplay = eglDisplay
        mEglConfig = eglConfigs[0]
        mEglContext = eglContext
        mEglPBuffer = eglPBuffer
        mEglProgram = program

        mIsInitialized = true
    }

    private fun cleanup(egl: EGL10) {
        egl.eglMakeCurrent(
            EGL10.EGL_NO_DISPLAY,
            EGL10.EGL_NO_SURFACE,
            EGL10.EGL_NO_SURFACE,
            EGL10.EGL_NO_CONTEXT
        )

        if (mEglProgram > 0) {
            GLES20.glDeleteProgram(mEglProgram)
            mEglProgram = 0
        }
        if (mEglPBuffer != null) {
            egl.eglDestroySurface(mEglDisplay, mEglPBuffer)
            mEglPBuffer = null
        }
        if (mEglContext != null && mEglContext != EGL10.EGL_NO_CONTEXT) {
            egl.eglDestroyContext(mEglDisplay, mEglContext)
            mEglContext = null
        }
        if (mEglDisplay != null && mEglDisplay != EGL10.EGL_NO_DISPLAY) {
            egl.eglTerminate(mEglDisplay)
            mEglDisplay = null
        }

        mEglConfig = null
        mEgl = null
    }

    private fun loadShader(resId: Int, type: Int): Int {
        val shader = GLES20.glCreateShader(type)
        val source = Util.loadRawResource(mContext, resId)

        GLES20.glShaderSource(shader, source)
        GLES20.glCompileShader(shader)

        val status = IntArray(1)
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, status, 0)

        if (status[0] == 0) {
            val log = GLES20.glGetShaderInfoLog(shader)
            sendError("Cannot compile shader: $log")
            GLES20.glDeleteShader(shader)
            return 0
        }

        return shader
    }

    private fun sendError(error: String) {
        mMainHandler.post {
            mErrorCallback.onError(error)
        }
    }

    private fun renderToTarget(target: RenderTarget, texture: CameraTexture) {
        val egl = requireNotNull(mEgl)
        egl.eglMakeCurrent(mEglDisplay, target.surface, target.surface, mEglContext)

        GLES20.glViewport(0, 0, target.width, target.height)
        GLES20.glDisable(GLES20.GL_DEPTH_TEST)

        val r = mRandom.nextFloat()
        val g = mRandom.nextFloat()
        val b = mRandom.nextFloat()
        GLES20.glClearColor(r, g, b, 0f)

        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)

        // Draw our textured quad
        GLES20.glUseProgram(mEglProgram)

        GLES20.glVertexAttribPointer(0, 2, GLES20.GL_FLOAT, false, 0, mVerticesBuffer)
        GLES20.glEnableVertexAttribArray(0)
        GLES20.glVertexAttribPointer(1, 2, GLES20.GL_FLOAT, false, 0, mStBuffer)
        GLES20.glEnableVertexAttribArray(1)

        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, texture.id)

        val textureLocation = GLES20.glGetUniformLocation(mEglProgram, "u_Texture")
        GLES20.glUniform1i(textureLocation, 0)

        val matrix = FloatArray(16)
        Matrix.setIdentityM(matrix, 0)
        Matrix.rotateM(matrix, 0, 360.0f - texture.orientation, 0.0f, 0.0f, 1.0f)

        val matrixLocation = GLES20.glGetUniformLocation(mEglProgram, "u_TexMatrix")
        GLES20.glUniformMatrix4fv(matrixLocation, 1, true, matrix, 0)

        GLES20.glDrawElements(GLES20.GL_TRIANGLES, 6, GLES20.GL_UNSIGNED_SHORT, mOrderBuffer)

        egl.eglSwapBuffers(mEglDisplay, target.surface)
    }

    private val mMainHandler = Handler(Looper.getMainLooper())
    private val mErrorCallback = errorCallback
    private val mContext = context.applicationContext

    private val mThread = Thread(this::run, "Render").apply {
        start()
    }

    private val mIsQuit = AtomicBoolean(false)
    private val mQueue = LinkedBlockingQueue<Runnable>()

    // Set and used by worker thread
    private var mEgl: EGL10? = null
    private var mEglDisplay: EGLDisplay? = null
    private var mEglConfig: EGLConfig? = null
    private var mEglContext: EGLContext? = null
    private var mEglPBuffer: EGLSurface? = null
    private var mEglProgram: Int = 0
    private var mIsInitialized = false

    private lateinit var mVerticesBuffer: FloatBuffer
    private lateinit var mStBuffer: FloatBuffer
    private lateinit var mOrderBuffer: ShortBuffer

    private val mRandom = Random(System.currentTimeMillis())

    // Render targets
    private val mRenderTargetList = ArrayList<RenderTarget>()

    companion object {
        private const val TAG = "RenderThread"

        private const val EGL_OPENGL_ES2_BIT = 4
        private const val EGL_RECORDABLE_ANDROID = 0x3142

        private val EGL_CONFIG = intArrayOf(
            EGL_RECORDABLE_ANDROID, 1,
            EGL11.EGL_RENDERABLE_TYPE, EGL_OPENGL_ES2_BIT,
            EGL11.EGL_SURFACE_TYPE, EGL11.EGL_WINDOW_BIT or EGL11.EGL_PBUFFER_BIT,
            EGL11.EGL_RED_SIZE, 8,
            EGL11.EGL_GREEN_SIZE, 8,
            EGL11.EGL_BLUE_SIZE, 8,
            EGL11.EGL_ALPHA_SIZE, EGL11.EGL_DONT_CARE,
            EGL11.EGL_DEPTH_SIZE, EGL11.EGL_DONT_CARE,
            EGL11.EGL_NONE
        )
        private val EGL_CONTEXT_ATTRS = intArrayOf(
            EGL14.EGL_CONTEXT_CLIENT_VERSION, 2,
            EGL10.EGL_NONE
        )
        private val EGL_PBUFFER_ATTRS = intArrayOf(
            EGL10.EGL_WIDTH, 64, EGL10.EGL_HEIGHT, 64,
            EGL10.EGL_NONE
        )
    }
}
