package org.kman.srtctest

import android.content.Context
import android.widget.Toast

object Util {

    fun toast(context: Context, resId: Int) {
        Toast.makeText(context, context.getString(resId), Toast.LENGTH_SHORT).show()
    }

    fun toast(context: Context, resId: Int, arg0: Any?) {
        Toast.makeText(context, context.getString(resId, arg0), Toast.LENGTH_SHORT).show()
    }

}