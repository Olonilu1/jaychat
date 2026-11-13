package com.juwon.yarn

import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.textfield.TextInputEditText
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.json.JSONObject

class LoginActivity : AppCompatActivity() {
    private val scope = CoroutineScope(Dispatchers.Main)
    private lateinit var status: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Api.ctx = applicationContext
        if (Api.token != null && Api.myUsername.isNotBlank()) {
            startActivity(Intent(this, MainActivity::class.java)); finish(); return
        }
        setContentView(R.layout.activity_login)
        status = findViewById(R.id.status)
        val email = findViewById<TextInputEditText>(R.id.email)
        val pass = findViewById<TextInputEditText>(R.id.password)

        findViewById<Button>(R.id.btn_login).setOnClickListener {
            doAuth(false, email.text.toString().trim(), pass.text.toString())
        }
        findViewById<Button>(R.id.btn_signup).setOnClickListener {
            doAuth(true, email.text.toString().trim(), pass.text.toString())
        }
    }

    private fun doAuth(signup: Boolean, email: String, pass: String) {
        if (email.isEmpty() || pass.length < 6) { status.text = "Enter your email and a password of 6+ characters"; return }
        status.text = if (signup) "Creating account..." else "Logging in..."
        scope.launch {
            val path = if (signup) "/auth/v1/signup" else "/auth/v1/token?grant_type=password"
            val body = JSONObject().put("email", email).put("password", pass)
            val res = Api.auth(path, body)
            if (res == null) { status.text = "Couldn't reach the server, check your connection and try again."; return@launch }
            val access = res.optString("access_token")
            val refresh = res.optString("refresh_token")
            val uid = res.optJSONObject("user")?.optString("id") ?: ""
            if (access.isNotEmpty()) {
                Api.prefs().edit().putString("access_token", access).apply()
                if (refresh.isNotEmpty()) Api.prefs().edit().putString("refresh_token", refresh).apply()
                Api.prefs().edit().putString("user_id", uid).apply()
                Api.prefs().edit().putString("email", email).apply()
                // already has a username? go straight in
                val prof = Api.rest("GET", "/rest/v1/profiles?id=eq.$uid&select=*")
                if (prof.second != null && prof.second!!.startsWith("[") && prof.second!!.length > 2) {
                    try {
                        val arr = org.json.JSONArray(prof.second)
                        if (arr.length() > 0) {
                            Api.prefs().edit().putString("username", arr.getJSONObject(0).optString("username")).apply()
                            startActivity(Intent(this@LoginActivity, MainActivity::class.java)); finish(); return@launch
                        }
                    } catch (_: Exception) {}
                }
                startActivity(Intent(this@LoginActivity, ProfileSetupActivity::class.java)); finish()
            } else {
                val code = res.optString("error_code", res.optString("code", ""))
                val msg = res.optString("msg", res.optString("error_description", res.optString("error", "")))
                status.text = when {
                    signup && code.contains("already", true) || msg.contains("already", true) ->
                        "That email already has an account. Tap Log in instead."
                    signup -> "Signup failed: $msg"
                    code.contains("invalid", true) || msg.contains("Invalid login", true) ->
                        "Wrong email or password. Try again."
                    else -> "Login failed: $msg"
                }
            }
        }
    }
}
