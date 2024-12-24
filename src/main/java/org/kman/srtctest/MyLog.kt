package org.kman.srtctest

import android.util.Log
import java.util.Locale

object MyLog {

    fun i(tag: String, message: String) {
        Log.i(tag, message)
    }

    fun i(tag: String, format: String, arg0: Any?) {
        Log.i(tag, String.format(Locale.US, format, arg0))
    }

    fun i(tag: String, format: String, arg0: Any?, arg1: Any?) {
        Log.i(tag, String.format(Locale.US, format, arg0, arg1))
    }

    fun i(tag: String, format: String, arg0: Any?, arg1: Any?, arg2: Any?) {
        Log.i(tag, String.format(Locale.US, format, arg0, arg1, arg2))
    }

    fun i(tag: String, format: String, vararg args: Any?) {
        Log.i(tag, String.format(Locale.US, format, *args))
    }
}