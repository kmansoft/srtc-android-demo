package org.kman.srtctest

import android.os.Handler
import android.os.Looper
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.time.Duration
import java.util.concurrent.Executors

object HttpClient {

    interface Callback {
        fun onCompleted(response: Response?, data: ByteArray?, error: Exception?)
    }

    class StatusCodeException(val code: Int) : Exception("HTTP status code $code") {
    }

    fun execute(request: Request, callback: Callback) {
        mExecutor.submit {
            var req = request

            try {
                while (true) {
                    mClient.newCall(req).execute().use { response ->
                        if (response.isRedirect) {
                            val newUrl = response.headers["Location"]
                            if (newUrl.isNullOrEmpty()) {
                                mMainHandler.post {
                                    callback.onCompleted(
                                        null, null, Exception("Location is empty on redirect")
                                    )
                                }
                                return@submit
                            }
                            req = Request.Builder().apply {
                                url(newUrl)
                                method(request.method, request.body)
                                headers(request.headers)
                            }.build()
                        } else if (!response.isSuccessful) {
                            val data = response.body?.string()

                            mMainHandler.post {
                                callback.onCompleted(
                                    response, null, StatusCodeException(response.code)
                                )
                            }
                            return@submit
                        } else {
                            val data = response.body?.bytes()
                            mMainHandler.post {
                                callback.onCompleted(response, data, null)
                            }
                            return@submit
                        }
                    }
                }
            } catch (x: Exception) {
                mMainHandler.post {
                    callback.onCompleted(null, null, x)
                }
                return@submit
            }
        }
    }

    private val mMainHandler = Handler(Looper.getMainLooper())
    private val mClient = OkHttpClient.Builder().apply {
        followRedirects(false)
        followSslRedirects(false)
        callTimeout(Duration.ofSeconds(10))
    }.build()
    private val mExecutor = Executors.newSingleThreadExecutor()
}
