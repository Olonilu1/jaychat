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

class ProfileSetupActivity : AppCompatActivity() {
    private val scope = CoroutineScope(Dispatchers.Main)
    private lateinit var status: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Api.ctx = applicationContext
        setContentView(R.layout.activity_profile)
        status = findViewById(R.id.status)
        val username = findViewById<TextInputEditText>(R.id.username)
        val display = findViewById<TextInputEditText>(R.id.display_name)

        findViewById<Button>(R.id.btn_save).setOnClickListener {
            val u = username.text.toString().trim().lowercase().removePrefix("@")
            val d = display.text.toString().trim()
            if (u.isEmpty() || !u.matches(Regex("^[a-z0-9_]{3,20}$"))) {
                status.text = "Usernames are 3-20 characters: letters, numbers or _"; return@setOnClickListener
            }
            if (d.isEmpty()) { status.text = "Add a display name"; return@setOnClickListener }
            status.text = "Saving..."
            scope.launch {
                val body = JSONObject().put("id", Api.userId ?: "").put("username", u).put("display_name", d)
                val res = Api.rest("POST", "/rest/v1/profiles", body)
                if (res.first == 201 || res.first == 200) {
                    Api.prefs().edit().putString("username", u).apply()
                    Api.prefs().edit().putString("display_name", d).apply()
                    startActivity(Intent(this@ProfileSetupActivity, MainActivity::class.java)); finish()
                } else if (res.first == 409 || (res.second ?: "").contains("duplicate")) {
                    status.text = "@$u is taken. Pick another."
                } else {
                    status.text = "Could not save (code ${res.first}). Try again."
                }
            }
        }
    }
}
