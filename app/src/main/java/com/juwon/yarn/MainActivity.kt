package com.juwon.yarn

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.google.android.material.floatingactionbutton.FloatingActionButton
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject

class MainActivity : AppCompatActivity() {
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private lateinit var adapter: ChatAdapter
    private lateinit var statusAdapter: StatusAdapter
    private var pollJob: Job? = null
    private var activeTab = 0 // 0 chats, 1 status, 2 calls

    companion object { private var notifWired = false }

    private val notifPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Api.ctx = applicationContext
        if (Api.token == null) { startActivity(Intent(this, LoginActivity::class.java)); finish(); return }
        setContentView(R.layout.activity_main)
        findViewById<TextView>(R.id.header).text = "JayChat  (@${Api.myUsername})"
        adapter = ChatAdapter { chat ->
            val i = Intent(this, ChatActivity::class.java)
            i.putExtra("chat_id", chat.id); i.putExtra("title", chat.title())
            i.putExtra("kind", chat.kind)
            startActivity(i)
        }
        val rv = findViewById<RecyclerView>(R.id.chats)
        rv.layoutManager = LinearLayoutManager(this)
        rv.adapter = adapter
        statusAdapter = StatusAdapter(
            onMine = { showPostStatus() },
            onView = { u -> showStatusesOf(u) }
        )
        val sv = findViewById<RecyclerView>(R.id.status_list)
        sv.layoutManager = LinearLayoutManager(this)
        sv.adapter = statusAdapter
        wireTabs()
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
            notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        if (Build.VERSION.SDK_INT >= 26) {
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(NotificationChannel("jaychat", "Messages", NotificationManager.IMPORTANCE_DEFAULT))
        }
        wireNotifications()
    }

    private fun wireTabs() {
        val tabs = listOf(
            Triple(findViewById<View>(R.id.tab_chats), R.id.tab_chats_text, R.id.ind_chats),
            Triple(findViewById<View>(R.id.tab_status), R.id.tab_status_text, R.id.ind_status),
            Triple(findViewById<View>(R.id.tab_calls), R.id.tab_calls_text, R.id.ind_calls))
        tabs.forEachIndexed { idx, (view, textId, indId) ->
            view.setOnClickListener { selectTab(idx) }
            view.tag = arrayOf(textId, indId)
        }
        selectTab(0)
    }

    private fun selectTab(idx: Int) {
        activeTab = idx
        val mineOn = ContextCompat.getColor(this, android.R.color.white)
        val off = ContextCompat.getColor(this, R.color.dim_text)
        val textIds = intArrayOf(R.id.tab_chats_text, R.id.tab_status_text, R.id.tab_calls_text)
        val indIds = intArrayOf(R.id.ind_chats, R.id.ind_status, R.id.ind_calls)
        for (i in 0..2) {
            findViewById<TextView>(textIds[i]).setTextColor(if (i == idx) mineOn else off)
            findViewById<View>(indIds[i]).setBackgroundColor(if (i == idx) mineOn else 0x00000000)
        }
        findViewById<RecyclerView>(R.id.chats).visibility = if (idx == 0) View.VISIBLE else View.GONE
        findViewById<RecyclerView>(R.id.status_list).visibility = if (idx == 1) View.VISIBLE else View.GONE
        findViewById<TextView>(R.id.calls_placeholder).visibility = if (idx == 2) View.VISIBLE else View.GONE
        val fab = findViewById<FloatingActionButton>(R.id.fab)
        when (idx) {
            0 -> { fab.show(); fab.setOnClickListener { promptAddFriend() } }
            1 -> { fab.show(); fab.setOnClickListener { showPostStatus() } }
            else -> fab.hide()
        }
    }

    // ---------- status ----------
    private fun showPostStatus() {
        val input = EditText(this)
        input.hint = "What's on your mind?"
        input.setSingleLine(false)
        input.minLines = 2
        AlertDialog.Builder(this)
            .setTitle("Post status")
            .setView(input)
            .setPositiveButton("Post") { _, _ ->
                val body = input.text.toString().trim()
                if (body.isEmpty()) return@setPositiveButton
                scope.launch {
                    val o = JSONObject()
                        .put("user_id", Api.userId ?: return@launch)
                        .put("username", Api.myUsername)
                        .put("body", body)
                    val res = Api.rest("POST", "/rest/v1/statuses", o)
                    if (res.first in 200..299) {
                        Toast.makeText(this@MainActivity, "status posted", Toast.LENGTH_SHORT).show()
                        loadStatuses()
                    } else {
                        Toast.makeText(this@MainActivity, "couldn't post status", Toast.LENGTH_LONG).show()
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showStatusesOf(username: String) {
        val mine = statusRows.filter { it.username == username }
        if (mine.isEmpty()) return
        val text = mine.joinToString("\n\n") { s ->
            val t = runCatching {
                val p = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", java.util.Locale.US)
                p.timeZone = java.util.TimeZone.getTimeZone("UTC")
                val d = p.parse(s.createdAt!!.take(19))!!
                java.text.SimpleDateFormat("HH:mm", java.util.Locale.US).format(d)
            }.getOrDefault("")
            "${s.body}\n($t)"
        }
        AlertDialog.Builder(this).setTitle("@$username").setMessage(text)
            .setPositiveButton("OK", null).show()
    }

    private var statusRows: List<StatusRow> = emptyList()

    private suspend fun loadStatuses() {
        val cutoff = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", java.util.Locale.US).apply {
            timeZone = java.util.TimeZone.getTimeZone("UTC")
        }.format(java.util.Date(System.currentTimeMillis() - 24L * 3600 * 1000))
        val res = Api.rest("GET", "/rest/v1/statuses?select=*&created_at=gte.$cutoff&order=created_at.desc")
        if (res.second == null) return
        try {
            val arr = JSONArray(res.second)
            val list = mutableListOf<StatusRow>()
            for (k in 0 until arr.length()) {
                val o = arr.getJSONObject(k)
                list += StatusRow(
                    username = o.optString("username", "user"),
                    body = o.optString("body"),
                    createdAt = o.optString("created_at")
                )
            }
            statusRows = list
            // group: my status first, then one row per other user (latest)
            val mine = list.filter { it.username == Api.myUsername }
            val others = list.groupBy { it.username }.filterKeys { it != Api.myUsername }
                .map { it.value.first() }
            statusAdapter.submit(mine.firstOrNull(), others)
        } catch (_: Exception) {}
    }

    // ---------- chat (unchanged core) ----------
    private fun promptAddFriend() {
        val input = EditText(this)
        input.hint = "@username"
        input.setSingleLine()
        AlertDialog.Builder(this)
            .setTitle("Add friend")
            .setMessage("Type their @username to start a chat")
            .setView(input)
            .setPositiveButton("Open chat") { _, _ ->
                val name = input.text.toString().trim().replace("@", "").lowercase()
                if (name.isEmpty()) return@setPositiveButton
                scope.launch {
                    val res = Api.rest("POST", "/rest/v1/rpc/find_or_create_dm",
                        JSONObject().put("target_username", name))
                    val body = res.second ?: ""
                    if (res.first == 200 && body.startsWith("\"")) {
                        val cid = body.trim('"')
                        val i = Intent(this@MainActivity, ChatActivity::class.java)
                        i.putExtra("chat_id", cid)
                        i.putExtra("title", "@$name")
                        i.putExtra("kind", "dm")
                        startActivity(i)
                    } else {
                        val msg = runCatching { JSONObject(body).optString("message") }.getOrDefault("")
                        val why = when {
                            msg.contains("no user") -> "no user with that username"
                            msg.contains("that is you") -> "that's you"
                            msg.isNotEmpty() -> msg
                            else -> "network problem, try again"
                        }
                        Toast.makeText(this@MainActivity, why, Toast.LENGTH_LONG).show()
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun wireNotifications() {
        if (notifWired) return
        notifWired = true
        Realtime.add { table, rec ->
            if (table != "messages") return@add
            val cid = rec.optString("chat_id")
            if (cid == Api.openChatId) return@add
            try {
                val who = rec.optString("sender_display", rec.optString("sender_username"))
                val what = when (rec.optString("kind")) {
                    "image" -> "[photo]"; "audio" -> "[voice note]"; else -> rec.optString("body")
                }
                if (what.isEmpty()) return@add
                val b = NotificationCompat.Builder(this, "jaychat")
                    .setSmallIcon(R.mipmap.ic_launcher)
                    .setContentTitle(who).setContentText(what).setAutoCancel(true)
                androidx.core.app.NotificationManagerCompat.from(this)
                    .notify(cid.hashCode(), b.build())
            } catch (_: Exception) {}
        }
    }

    override fun onResume() {
        super.onResume()
        startPolling()
        Api.userId?.let { uid ->
            scope.launch { Api.rest("PATCH", "/rest/v1/profiles?id=eq.$uid", JSONObject().put("last_seen", Api.nowIso())) }
        }
    }
    override fun onPause() { super.onPause(); pollJob?.cancel(); pollJob = null }

    private fun startPolling() {
        pollJob?.cancel()
        pollJob = scope.launch {
            while (isActive) {
                loadChats()
                if (activeTab == 1) loadStatuses()
                delay(4000)
            }
        }
    }

    private suspend fun loadChats() {
        val uid = Api.userId ?: return
        val res = Api.rest("GET", "/rest/v1/rpc/my_chats")
        if (res.second == null) return
        try {
            val arr = JSONArray(res.second)
            val list = mutableListOf<Chat>()
            for (k in 0 until arr.length()) {
                val o = arr.getJSONObject(k)
                list += Chat(
                    id = o.optString("chat_id"),
                    kind = o.optString("kind"),
                    name = o.optString("name"),
                    other_username = o.optString("other_username"),
                    other_display = o.optString("other_display"),
                    last_message = if (o.isNull("last_message")) null else o.optString("last_message"),
                    last_at = if (o.isNull("last_at")) null else o.optString("last_at")
                )
            }
            adapter.submit(list.map { Row(it.id, it.kind, it.name, it.other_username, it.other_display, it.last_message) })
        } catch (_: Exception) {}
    }
}

data class Row(val id: String, val kind: String, val name: String, val other_username: String, val other_display: String, val last: String?) {
    fun title() = if (kind == "group") "#$name" else "@$other_username"
    fun sub() = if (kind == "group") "group chat" else other_display
}

data class StatusRow(val username: String, val body: String, val createdAt: String?)

class StatusAdapter(val onMine: () -> Unit, val onView: (String) -> Unit) : RecyclerView.Adapter<StatusAdapter.VH>() {
    private var mine: StatusRow? = null
    private val others = mutableListOf<StatusRow>()
    fun submit(m: StatusRow?, o: List<StatusRow>) {
        mine = m; others.clear(); others.addAll(o); notifyDataSetChanged()
    }
    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val avatar: TextView = v.findViewById(R.id.savatar)
        val title: TextView = v.findViewById(R.id.stitle)
        val sub: TextView = v.findViewById(R.id.ssub)
    }
    override fun onCreateViewHolder(p: ViewGroup, t: Int): VH =
        VH(LayoutInflater.from(p.context).inflate(R.layout.item_status, p, false))
    override fun getItemCount() = others.size + 1
    override fun onBindViewHolder(h: VH, i: Int) {
        if (i == 0) {
            val m = mine
            h.avatar.text = (Api.myUsername.take(1).ifEmpty { "Y" }).uppercase()
            h.title.text = "My status"
            h.sub.text = m?.body ?: "Tap to add a status update"
            h.itemView.setOnClickListener { onMine() }
        } else {
            val s = others[i - 1]
            h.avatar.text = s.username.take(1).uppercase()
            h.title.text = "@${s.username}"
            h.sub.text = s.body
            h.itemView.setOnClickListener { onView(s.username) }
        }
    }
}

class ChatAdapter(val onClick: (Row) -> Unit) : RecyclerView.Adapter<ChatAdapter.VH>() {
    private val items = mutableListOf<Row>()
    fun submit(list: List<Row>) { items.clear(); items.addAll(list); notifyDataSetChanged() }
    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val avatar: TextView = v.findViewById(R.id.avatar)
        val title: TextView = v.findViewById(R.id.title)
        val sub: TextView = v.findViewById(R.id.subtitle)
    }
    override fun onCreateViewHolder(p: ViewGroup, t: Int): VH =
        VH(LayoutInflater.from(p.context).inflate(R.layout.item_chat, p, false))
    override fun getItemCount() = items.size
    override fun onBindViewHolder(h: VH, i: Int) {
        val c = items[i]
        h.title.text = c.title()
        h.sub.text = c.last ?: c.sub()
        h.avatar.text = (if (c.kind == "group") c.name else c.other_username).take(1).uppercase()
        h.itemView.setOnClickListener { onClick(c) }
    }
}
