package org.kman.srtctest.util

import android.util.Log
import java.util.Locale

object MyLog {

    @JvmStatic
    fun i(tag: String, message: String) {
        Log.i(tag, message)
    }

    @JvmStatic
    fun i(tag: String, format: String, arg0: Any?) {
        Log.i(tag, String.format(Locale.US, format, arg0))
    }

    @JvmStatic
    fun i(tag: String, format: String, arg0: Any?, arg1: Any?) {
        Log.i(tag, String.format(Locale.US, format, arg0, arg1))
    }

    @JvmStatic
    fun i(tag: String, format: String, arg0: Any?, arg1: Any?, arg2: Any?) {
        Log.i(tag, String.format(Locale.US, format, arg0, arg1, arg2))
    }

    @JvmStatic
    fun i(tag: String, format: String, vararg args: Any?) {
        Log.i(tag, String.format(Locale.US, format, *args))
    }
}