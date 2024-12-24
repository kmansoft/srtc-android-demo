package org.kman.srtctest

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.content.SharedPreferences
import android.os.Bundle
import android.util.Base64
import android.view.KeyEvent
import android.widget.Button
import android.widget.EditText
import androidx.preference.PreferenceManager
import org.json.JSONObject
import java.nio.charset.StandardCharsets

class MainActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContentView(R.layout.main_activity)

        mSharedPrefs = PreferenceManager.getDefaultSharedPreferences(this)

        mEditWhipServer = findViewById(R.id.whip_server)
        mEditWhipToken = findViewById(R.id.whip_token)
        mButtonConnect = findViewById(R.id.whip_connect)

        mButtonConnect.setOnClickListener {
            onClickConnect()
        }

        setFieldsFromPrefs()
        setFieldsFromIntent(intent)
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

        val header = data.getQueryParameter("header") ?: return
        val claims = data.getQueryParameter("claims") ?: return
        val signature = data.getQueryParameter("signature") ?: return

        try {
            val decoded = Base64.decode(claims, Base64.NO_PADDING)
            val json = JSONObject(String(decoded, StandardCharsets.UTF_8))

            val server = json.getString("whip_url")
            val participantId = json.getString("jti")
            val token = "${header}.${claims}.${signature}"

            if (server.isNotEmpty() && participantId.isNotEmpty()) {
                mEditWhipServer.setText("${server}/publish/${participantId}")
            }
            mEditWhipToken.setText(token)

            mSharedPrefs.edit().apply {
                putString(PREF_KEY_SERVER, server)
                putString(PREF_KEY_TOKEN, token)
            }.apply()

        } catch (x: Exception) {
            Util.toast(this, R.string.error_parsing_claims, x.toString())
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
    }

    private lateinit var mEditWhipServer: EditText
    private lateinit var mEditWhipToken: EditText
    private lateinit var mButtonConnect: Button

    private lateinit var mSharedPrefs: SharedPreferences

    companion object {
        private const val TAG = "MainActivity"
        private const val PREF_KEY_SERVER = "whip_server"
        private const val PREF_KEY_TOKEN = "whip_token"
    }
}
