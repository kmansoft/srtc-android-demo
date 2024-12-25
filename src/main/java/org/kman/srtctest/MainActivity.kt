package org.kman.srtctest

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.content.SharedPreferences
import android.os.Bundle
import android.os.SystemClock
import android.util.Base64
import android.util.Log
import android.view.KeyEvent
import android.widget.Button
import android.widget.EditText
import androidx.preference.PreferenceManager
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
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

        // test code
        val offer = Util.loadRawResource(this, R.raw.pub_offer_chrome_v_only)
        val request = Request.Builder().apply {
            url(server)
            method("POST", offer.toRequestBody("application/sdp".toMediaType()))
            header("Authorization", "Bearer $token")
        }.build()

        val ms0 = SystemClock.elapsedRealtime()
        HttpClient.execute(request, object : HttpClient.Callback {
            override fun onCompleted(response: Response?, data: ByteArray?, error: Exception?) {
                if (error != null) {
                    Util.toast(this@MainActivity, R.string.sdp_offer_error, error.toString())
                    return
                }

                if (data != null) {
                    val ms1 = SystemClock.elapsedRealtime()
                    Util.toast(this@MainActivity, R.string.sdp_offer_received_sdp_answer, ms1 - ms0)

                    val answer = String(data, StandardCharsets.UTF_8)
                    MyLog.i(TAG, "SDP answer:\n%s", answer)
                }
            }
        })
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
