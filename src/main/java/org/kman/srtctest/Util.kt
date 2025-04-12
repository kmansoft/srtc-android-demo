package org.kman.srtctest

import android.content.Context
import android.os.Handler
import android.widget.Toast
import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets
import java.util.concurrent.CountDownLatch

object Util {

    fun toast(context: Context, resId: Int) {
        gCurrToast?.cancel()

        gCurrToast = Toast.makeText(context, context.getString(resId), Toast.LENGTH_SHORT)
        gCurrToast?.show()
    }

    fun toast(context: Context, resId: Int, arg0: Any?) {
        gCurrToast?.cancel()

        gCurrToast = Toast.makeText(context, context.getString(resId, arg0), Toast.LENGTH_SHORT)
        gCurrToast?.show()
    }

    fun loadRawResource(context: Context, resId: Int): String {
        val bos = ByteArrayOutputStream()
        val buf = ByteArray(4096)

        context.resources.openRawResource(resId).use { stream ->
            while (true) {
                val r = stream.read(buf)
                if (r > 0) {
                    bos.write(buf, 0, r)
                } else {
                    break
                }
            }

            return String(bos.toByteArray(), StandardCharsets.UTF_8)
        }
    }

    private var gCurrToast: Toast? = null
}

fun Handler.blockingCall(r: Runnable) {
    val latch = CountDownLatch(1)
    post {
        r.run()
        latch.countDown()
    }
    latch.await()
}
