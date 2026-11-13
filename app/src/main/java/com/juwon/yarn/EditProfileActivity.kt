package com.juwon.yarn

import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.textfield.TextInputEditText
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

class EditProfileActivity : AppCompatActivity() {
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var picked: Uri? = null
    private val pickImage = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) { picked = uri; showPicked(uri) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Api.ctx = applicationContext
        setContentView(R.layout.activity_edit_profile)
        findViewById<Button>(R.id.btn_photo).setOnClickListener { pickImage.launch("image/*") }
        findViewById<Button>(R.id.btn_save).setOnClickListener { save() }
        findViewById<TextView>(R.id.status).text = "@${Api.myUsername}"
        load()
    }

    private fun load() {
        val uid = Api.userId ?: return
        scope.launch {
            val res = Api.rest("GET", "/rest/v1/profiles?id=eq.$uid&select=*")
            try {
                val o = JSONArray(res.second ?: "[]").optJSONObject(0) ?: return@launch
                findViewById<TextInputEditText>(R.id.display_name).setText(o.optString("display_name"))
                findViewById<TextInputEditText>(R.id.about).setText(o.optString("about"))
                val av = o.optString("avatar_url")
                if (av.isNotEmpty()) MediaCache.load(av) { b ->
                    b?.let { findViewById<ImageView>(R.id.avatar).setImageBitmap(it) }
                }
            } catch (_: Exception) {}
        }
    }

    private fun showPicked(uri: Uri) {
        scope.launch(Dispatchers.IO) {
            val b = contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it) }
            runOnUiThread { b?.let { findViewById<ImageView>(R.id.avatar).setImageBitmap(it) } }
        }
    }

    private fun save() {
        val uid = Api.userId ?: return
        val name = findViewById<TextInputEditText>(R.id.display_name).text.toString().trim()
        val about = findViewById<TextInputEditText>(R.id.about).text.toString().trim()
        findViewById<Button>(R.id.btn_save).isEnabled = false
        scope.launch {
            try {
                var avatarUrl: String? = null
                picked?.let { uri ->
                    val bytes = contentResolver.openInputStream(uri)?.use { it.readBytes() }
                    if (bytes != null) {
                        avatarUrl = Storage.upload("${uid}/profile_${UUID.randomUUID()}.jpg", bytes, "image/jpeg")
                    }
                }
                val body = JSONObject().put("display_name", name).put("about", about)
                avatarUrl?.let { body.put("avatar_url", it) }
                Api.rest("PATCH", "/rest/v1/profiles?id=eq.$uid", body)
                Api.prefs().edit().putString("display_name", name).apply()
                Toast.makeText(this@EditProfileActivity, "profile saved", Toast.LENGTH_SHORT).show()
                finish()
            } catch (e: Exception) {
                Toast.makeText(this@EditProfileActivity, "couldn't save", Toast.LENGTH_SHORT).show()
            }
            findViewById<Button>(R.id.btn_save).isEnabled = true
        }
    }
}
