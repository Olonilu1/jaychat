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
import org.json.JSONArray
import org.json.JSONObject

class CreateGroupActivity : AppCompatActivity() {
    private val scope = CoroutineScope(Dispatchers.Main)
    private lateinit var status: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Api.ctx = applicationContext
        setContentView(R.layout.activity_group)
        status = findViewById(R.id.status)
        val name = findViewById<TextInputEditText>(R.id.group_name)
        val members = findViewById<TextInputEditText>(R.id.members)

        findViewById<Button>(R.id.btn_create).setOnClickListener {
            val g = name.text.toString().trim()
            val raw = members.text.toString().trim()
            if (g.isEmpty()) { status.text = "Give the group a name"; return@setOnClickListener }
            val handles = raw.split(",").map { it.trim().lowercase().removePrefix("@") }.filter { it.isNotEmpty() }
            status.text = "Creating..."
            scope.launch {
                // resolve member usernames to ids
                val q = if (handles.isEmpty()) "" else "?username=in.(${handles.joinToString(",") { "\"$it\"" }})&select=id,username"
                val res = Api.rest("GET", "/rest/v1/profiles$q")
                var memberIds = mutableListOf(Api.userId ?: "")
                var missing = handles.toMutableList()
                if (res.second != null) {
                    try {
                        val arr = JSONArray(res.second)
                        for (k in 0 until arr.length()) {
                            val u = arr.getJSONObject(k).optString("username")
                            val id = arr.getJSONObject(k).optString("id")
                            memberIds.add(id); missing.remove(u)
                        }
                    } catch (_: Exception) {}
                }
                if (missing.isNotEmpty()) {
                    status.text = "No @${missing.joinToString(", @")} found. Check the spellings."
                    return@launch
                }
                val chatBody = JSONObject().put("kind", "group").put("name", g).put("created_by", Api.userId)
                val cres = Api.rest("POST", "/rest/v1/chats", chatBody)
                if (cres.second == null || !cres.second!!.startsWith("[")) {
                    status.text = "Could not create the group (code ${cres.first})"; return@launch
                }
                val cid = JSONArray(cres.second).getJSONObject(0).optString("id")
                val rows = JSONArray()
                for (id in memberIds) rows.put(JSONObject().put("chat_id", cid).put("user_id", id))
                Api.restArray("POST", "/rest/v1/chat_members", rows)
                val i = Intent(this@CreateGroupActivity, ChatActivity::class.java)
                i.putExtra("chat_id", cid); i.putExtra("title", "#$g"); i.putExtra("kind", "group")
                startActivity(i); finish()
            }
        }
    }
}
