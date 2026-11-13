package com.juwon.yarn

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.net.Uri
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

class ChatActivity : AppCompatActivity() {
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private lateinit var adapter: MsgAdapter
    private var chatId = ""
    private var chatKind = "direct"
    private var pollJob: Job? = null
    private var rt: ((String, JSONObject) -> Unit)? = null
    private var peerId = ""
    private var lastTypingSent = 0L
    private var typingClearJob: Job? = null
    private var recorder: MediaRecorder? = null
    private var recording = false
    private var player: MediaPlayer? = null
    private var currentMessages: List<Message> = emptyList()
    private val reactionsMap = mutableMapOf<String, MutableList<Pair<String, String>>>()
    private var replyTo: Message? = null
    private var ttl = 0L
    private var peerStatusText = ""
    private val walls = intArrayOf(0xFF1F2C34.toInt(), 0xFF0B141A.toInt(), 0xFF232A2F.toInt(), 0xFF2A2438.toInt(), 0xFF1E2A26.toInt())

    private val pickImage = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) sendAttachment(uri, "image", "image/jpeg", "jpg")
    }
    private val micPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) startRecording() else Toast.makeText(this, "microphone permission needed for voice notes", Toast.LENGTH_SHORT).show()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Api.ctx = applicationContext
        setContentView(R.layout.activity_chat)
        chatId = intent.getStringExtra("chat_id") ?: return
        chatKind = intent.getStringExtra("kind") ?: "direct"
        findViewById<TextView>(R.id.title).text = intent.getStringExtra("title") ?: "Chat"
        adapter = MsgAdapter(reactionsMap, onPlayAudio = { playAudio(it) }, onActions = { openActions(it) })
        val rv = findViewById<RecyclerView>(R.id.messages)
        rv.setBackgroundColor(walls[Api.prefs().getInt("wall_$chatId", 0)])
        val lm = LinearLayoutManager(this); lm.stackFromEnd = true
        rv.layoutManager = lm; rv.adapter = adapter
        findViewById<ImageButton>(R.id.back).setOnClickListener { finish() }
        findViewById<ImageButton>(R.id.send).setOnClickListener { send() }
        findViewById<ImageButton>(R.id.attach).setOnClickListener { pickImage.launch("image/*") }
        findViewById<ImageButton>(R.id.more).setOnClickListener { openMore() }
        val mic = findViewById<ImageButton>(R.id.mic)
        mic.setOnClickListener {
            if (recording) stopAndSendVoice()
            else if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED)
                startRecording()
            else micPermission.launch(Manifest.permission.RECORD_AUDIO)
        }
        val input = findViewById<EditText>(R.id.input)
        input.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) {
                if (s.isNullOrBlank()) { clearTyping(); return }
                val now = System.currentTimeMillis()
                if (now - lastTypingSent > 2000) { lastTypingSent = now; sendTyping() }
            }
        })
    }

    override fun onResume() {
        super.onResume()
        Api.openChatId = chatId
        poll()
        heartbeatSelf()
        loadPeerStatus()
        if (rt == null) rt = { table, rec -> handleLive(table, rec) }
        Realtime.add(rt!!)
    }

    override fun onPause() {
        super.onPause()
        Api.openChatId = null
        pollJob?.cancel(); pollJob = null
        rt?.let { Realtime.remove(it) }
        clearTyping()
        stopPlayer()
        if (recording) stopAndSendVoice()
    }

    override fun onDestroy() { super.onDestroy(); stopPlayer() }

    private fun handleLive(table: String, rec: JSONObject) {
        when (table) {
            "messages" -> if (rec.optString("chat_id") == chatId) scope.launch { load() }
            "typing" -> if (rec.optString("chat_id") == chatId && rec.optString("user_id") != Api.userId)
                showTyping(rec.optString("username"))
            "reactions" -> scope.launch { loadReactions() }
        }
    }

    private fun openActions(m: Message) {
        val mine = m.sender_id == Api.userId
        val base = arrayListOf("Reply", "\uD83D\uDC4D", "\u2764\uFE0F", "\uD83D\uDE02", "\uD83D\uDE2E", "\uD83D\uDE22", "\uD83D\uDD25")
        if (mine) base.add("Delete message")
        val opts = base.toTypedArray()
        AlertDialog.Builder(this).setItems(opts) { _, which ->
            when {
                which == 0 -> startReply(m)
                mine && which == opts.size - 1 -> deleteMessage(m)
                else -> toggleReaction(m, opts[which])
            }
        }.show()
    }

    private fun deleteMessage(m: Message) {
        scope.launch {
            Api.rest("DELETE", "/rest/v1/messages?id=eq.${m.id}")
            load()
            Toast.makeText(this@ChatActivity, "message deleted", Toast.LENGTH_SHORT).show()
        }
    }

    private fun toggleReaction(m: Message, emoji: String) {
        val uid = Api.userId ?: return
        scope.launch {
            val mine = reactionsMap[m.id]?.any { it.second == uid && it.first == emoji } ?: false
            if (mine) Api.rest("DELETE", "/rest/v1/reactions?message_id=eq.${m.id}&user_id=eq.$uid")
            else Api.rest("POST", "/rest/v1/reactions?on_conflict=message_id,user_id",
                JSONObject().put("message_id", m.id).put("user_id", uid)
                    .put("username", Api.myUsername).put("emoji", emoji), upsert = true)
            loadReactions()
        }
    }

    private suspend fun loadReactions() {
        if (currentMessages.isEmpty()) return
        val ids = currentMessages.joinToString(",") { "\"${it.id}\"" }
        val res = Api.rest("GET", "/rest/v1/reactions?message_id=in.($ids)&select=message_id,emoji,user_id")
        val map = mutableMapOf<String, MutableList<Pair<String, String>>>()
        try {
            val arr = JSONArray(res.second ?: "[]")
            for (k in 0 until arr.length()) {
                val o = arr.getJSONObject(k)
                map.getOrPut(o.optString("message_id")) { mutableListOf() }
                    .add(Pair(o.optString("emoji"), o.optString("user_id")))
            }
        } catch (_: Exception) {}
        reactionsMap.clear(); reactionsMap.putAll(map)
        adapter.notifyDataSetChanged()
    }

    private fun startReply(m: Message) {
        replyTo = m
        val bar = findViewById<TextView>(R.id.reply_bar)
        bar.visibility = View.VISIBLE
        bar.text = "replying to @${m.sender_username}: ${previewOf(m)} (tap to cancel)"
        bar.setOnClickListener { cancelReply() }
    }

    private fun cancelReply() { replyTo = null; findViewById<TextView>(R.id.reply_bar).visibility = View.GONE }

    private fun previewOf(m: Message) = when (m.kind) {
        "image" -> "[photo]"; "audio" -> "[voice note]"; else -> m.body.take(60)
    }

    private fun openMore() {
        val ttlLabel = if (ttl == 0L) "off" else if (ttl == 3600L) "1 hour" else "24 hours"
        val opts = arrayOf("Disappearing messages: $ttlLabel", "Change wallpaper", "Export chat")
        AlertDialog.Builder(this).setItems(opts) { _, which ->
            when (which) { 0 -> cycleTtl(); 1 -> cycleWall(); 2 -> exportChat() }
        }.show()
    }

    private fun cycleTtl() {
        ttl = when (ttl) { 0L -> 3600L; 3600L -> 86400L; else -> 0L }
        Toast.makeText(this, if (ttl == 0L) "disappearing messages off"
            else "new messages vanish after ${if (ttl == 3600L) "1 hour" else "24 hours"}", Toast.LENGTH_SHORT).show()
    }

    private fun cycleWall() {
        val p = Api.prefs()
        val next = (p.getInt("wall_$chatId", 0) + 1) % walls.size
        p.edit().putInt("wall_$chatId", next).apply()
        findViewById<RecyclerView>(R.id.messages).setBackgroundColor(walls[next])
    }

    private fun exportChat() {
        val sb = StringBuilder("JayChat - ${findViewById<TextView>(R.id.title).text}\n\n")
        for (m in currentMessages)
            sb.append("[${m.created_at.take(16).replace('T', ' ')}] @${m.sender_username}: ${previewOf(m)}${if (m.kind == "text") "" else " ${m.body}"}\n")
        val i = Intent(Intent.ACTION_SEND).apply { type = "text/plain"; putExtra(Intent.EXTRA_TEXT, sb.toString()) }
        startActivity(Intent.createChooser(i, "Export chat"))
    }

    private fun showTyping(username: String) {
        findViewById<TextView>(R.id.subtitle).text = "@$username is typing..."
        typingClearJob?.cancel()
        typingClearJob = scope.launch { delay(3000); findViewById<TextView>(R.id.subtitle).text = peerStatusText }
    }

    private fun heartbeatSelf() {
        val uid = Api.userId ?: return
        scope.launch { Api.rest("PATCH", "/rest/v1/profiles?id=eq.$uid", JSONObject().put("last_seen", Api.nowIso())) }
    }

    private fun loadPeerStatus() {
        if (chatKind == "group") return
        val peer = (intent.getStringExtra("title") ?: "").removePrefix("@")
        if (peer.isEmpty()) return
        scope.launch {
            val res = Api.rest("GET", "/rest/v1/profiles?username=eq.$peer&select=id,last_seen")
            try {
                val o = JSONArray(res.second ?: "[]").optJSONObject(0) ?: return@launch
                peerId = o.optString("id")
                val seen = o.optString("last_seen")
                peerStatusText = if (seen.isNotEmpty() && isoPlus(seen, 60) > Api.nowIso()) "online" else "last seen ${shortTime(seen)}"
                findViewById<TextView>(R.id.subtitle).text = peerStatusText
            } catch (_: Exception) {}
        }
    }

    private fun isoPlus(iso: String, seconds: Long): String {
        val t = runCatching {
            java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", java.util.Locale.US).apply {
                timeZone = java.util.TimeZone.getTimeZone("UTC")
            }.parse(iso.substring(0, 19))!!.time
        }.getOrDefault(0L)
        return isoFromEpoch(t + seconds * 1000)
    }

    private fun isoFromEpoch(ms: Long): String =
        java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", java.util.Locale.US).apply {
            timeZone = java.util.TimeZone.getTimeZone("UTC")
        }.format(java.util.Date(ms))

    private fun parseIso(iso: String): Long = runCatching {
        java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", java.util.Locale.US).apply {
            timeZone = java.util.TimeZone.getTimeZone("UTC")
        }.parse(iso.substring(0, 19))!!.time
    }.getOrDefault(Long.MAX_VALUE)

    private fun shortTime(iso: String): String = runCatching {
        val p = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", java.util.Locale.US).apply {
            timeZone = java.util.TimeZone.getTimeZone("UTC")
        }
        val d = p.parse(iso.substring(0, 19))
        java.text.SimpleDateFormat("h:mm a", java.util.Locale.getDefault()).format(d ?: return "recently")
    }.getOrDefault("recently")

    private fun sendTyping() {
        val uid = Api.userId ?: return
        scope.launch {
            Api.rest("POST", "/rest/v1/typing?on_conflict=chat_id,user_id",
                JSONObject().put("chat_id", chatId).put("user_id", uid)
                    .put("username", Api.myUsername).put("updated_at", Api.nowIso()), upsert = true)
        }
    }

    private fun clearTyping() {
        val uid = Api.userId ?: return
        scope.launch { Api.rest("DELETE", "/rest/v1/typing?chat_id=eq.$chatId&user_id=eq.$uid") }
    }

    private fun poll() {
        pollJob?.cancel()
        pollJob = scope.launch {
            while (isActive) { load(); delay(5000) }
        }
    }

    private suspend fun load() {
        val res = Api.rest("GET", "/rest/v1/messages?chat_id=eq.$chatId&order=created_at.asc&select=id,chat_id,sender_id,sender_username,sender_display,body,created_at,kind,attachment_url,read_at,reply_to,reply_preview,expires_at")
        if (res.second == null) return
        try {
            val arr = JSONArray(res.second)
            val list = mutableListOf<Message>()
            for (k in 0 until arr.length()) {
                val o = arr.getJSONObject(k)
                list += Message(
                    id = o.optString("id"), chat_id = o.optString("chat_id"),
                    sender_id = o.optString("sender_id"), sender_username = o.optString("sender_username"),
                    sender_display = o.optString("sender_display"), body = o.optString("body"),
                    created_at = o.optString("created_at"),
                    kind = o.optString("kind", "text"), attachment_url = o.optString("attachment_url"),
                    read_at = if (o.isNull("read_at")) "" else o.optString("read_at"),
                    reply_to = o.optString("reply_to"), reply_preview = o.optString("reply_preview"),
                    expires_at = if (o.isNull("expires_at")) "" else o.optString("expires_at")
                )
            }
            val now = System.currentTimeMillis()
            val fresh = list.filter { it.expires_at.isEmpty() || parseIso(it.expires_at) > now }
            currentMessages = fresh
            adapter.submit(fresh)
            markRead()
            loadReactions()
        } catch (_: Exception) {}
    }

    private suspend fun markRead() {
        val uid = Api.userId ?: return
        Api.rest("PATCH", "/rest/v1/messages?chat_id=eq.$chatId&sender_id=neq.$uid&read_at=is.null",
            JSONObject().put("read_at", Api.nowIso()))
    }

    private fun send() {
        val input = findViewById<EditText>(R.id.input)
        val text = input.text.toString().trim()
        if (text.isEmpty()) return
        input.setText("")
        clearTyping()
        val body = JSONObject()
            .put("chat_id", chatId).put("sender_id", Api.userId)
            .put("sender_username", Api.myUsername)
            .put("sender_display", Api.prefs().getString("display_name", "") ?: "")
            .put("body", text).put("kind", "text")
        replyTo?.let {
            body.put("reply_to", it.id)
            body.put("reply_preview", "@${it.sender_username}: ${previewOf(it)}")
        }
        cancelReply()
        if (ttl > 0) body.put("expires_at", isoFromEpoch(System.currentTimeMillis() + ttl * 1000))
        scope.launch { Api.rest("POST", "/rest/v1/messages", body); load() }
    }

    private fun postMessage(body: JSONObject) {
        if (ttl > 0) body.put("expires_at", isoFromEpoch(System.currentTimeMillis() + ttl * 1000))
        scope.launch { Api.rest("POST", "/rest/v1/messages", body); load() }
    }

    private fun sendAttachment(uri: Uri, kind: String, contentType: String, ext: String) {
        scope.launch {
            try {
                val bytes = contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: return@launch
                val name = "${Api.userId}/${UUID.randomUUID()}.$ext"
                val url = Storage.upload(name, bytes, contentType) ?: run {
                    Toast.makeText(this@ChatActivity, "upload failed", Toast.LENGTH_SHORT).show(); return@launch
                }
                val body = JSONObject()
                    .put("chat_id", chatId).put("sender_id", Api.userId)
                    .put("sender_username", Api.myUsername)
                    .put("sender_display", Api.prefs().getString("display_name", "") ?: "")
                    .put("body", "").put("kind", kind).put("attachment_url", url)
                postMessage(body)
            } catch (e: Exception) {
                Toast.makeText(this@ChatActivity, "couldn't send it", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun startRecording() {
        try {
            val out = File(cacheDir, "voice_${System.currentTimeMillis()}.m4a")
            val r = if (android.os.Build.VERSION.SDK_INT >= 31) MediaRecorder(this) else @Suppress("DEPRECATION") MediaRecorder()
            r.setAudioSource(MediaRecorder.AudioSource.MIC)
            r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            r.setOutputFile(out.absolutePath)
            r.prepare(); r.start()
            recorder = r; recording = true
            Toast.makeText(this, "recording... tap mic again to send", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            recording = false; recorder = null
            Toast.makeText(this, "couldn't start recording", Toast.LENGTH_SHORT).show()
        }
    }

    private fun stopAndSendVoice() {
        val r = recorder; recording = false; recorder = null
        if (r == null) return
        try { r.stop(); r.release() } catch (e: Exception) {}
        val newest = cacheDir.listFiles { f -> f.name.startsWith("voice_") }?.maxByOrNull { it.lastModified() }
        val file = newest ?: return
        scope.launch {
            try {
                val bytes = file.readBytes()
                val name = "${Api.userId}/${UUID.randomUUID()}.m4a"
                val url = Storage.upload(name, bytes, "audio/mp4")
                file.delete()
                if (url == null) { Toast.makeText(this@ChatActivity, "upload failed", Toast.LENGTH_SHORT).show(); return@launch }
                val body = JSONObject()
                    .put("chat_id", chatId).put("sender_id", Api.userId)
                    .put("sender_username", Api.myUsername)
                    .put("sender_display", Api.prefs().getString("display_name", "") ?: "")
                    .put("body", "").put("kind", "audio").put("attachment_url", url)
                postMessage(body)
            } catch (e: Exception) {
                Toast.makeText(this@ChatActivity, "couldn't send the voice note", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun playAudio(url: String) {
        try {
            stopPlayer()
            player = MediaPlayer()
            player!!.setDataSource(url)
            player!!.prepare()
            player!!.start()
        } catch (e: Exception) {
            Toast.makeText(this, "couldn't play it", Toast.LENGTH_SHORT).show()
        }
    }

    private fun stopPlayer() {
        try { player?.stop(); player?.release() } catch (_: Exception) {}
        player = null
    }
}

class MsgAdapter(
    val reactions: Map<String, List<Pair<String, String>>>,
    val onPlayAudio: (String) -> Unit,
    val onActions: (Message) -> Unit
) : RecyclerView.Adapter<MsgAdapter.VH>() {
    private val items = mutableListOf<Message>()
    fun submit(list: List<Message>) { items.clear(); items.addAll(list); notifyDataSetChanged() }
    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val body: TextView = v.findViewById(R.id.body)
        val img: ImageView = v.findViewById(R.id.img)
    }
    override fun onCreateViewHolder(p: ViewGroup, t: Int): VH =
        VH(LayoutInflater.from(p.context).inflate(if (t == 1) R.layout.item_msg_out else R.layout.item_msg_in, p, false))

    private fun fmtTime(iso: String): String {
        if (iso.isEmpty()) return ""
        return try {
            val f = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", java.util.Locale.US)
            f.timeZone = java.util.TimeZone.getTimeZone("UTC")
            val d = f.parse(iso.substringBefore('.')) ?: return ""
            val out = java.text.SimpleDateFormat("HH:mm", java.util.Locale.US)
            out.timeZone = java.util.TimeZone.getDefault()
            out.format(d)
        } catch (e: Exception) { "" }
    }
    override fun getItemViewType(i: Int) = if (items[i].sender_id == Api.userId) 1 else 0
    override fun getItemCount() = items.size
    override fun onBindViewHolder(h: VH, i: Int) {
        val m = items[i]
        val prefix = if (m.sender_id == Api.userId) "" else "@${m.sender_username}: "
        val reply = if (m.reply_preview.isNotEmpty()) "\u21A9 ${m.reply_preview}\n" else ""
        h.img.setImageDrawable(null); h.img.visibility = View.GONE
        var text: String
        when (m.kind) {
            "image" -> {
                text = prefix
                h.img.visibility = View.VISIBLE
                val url = m.attachment_url
                MediaCache.load(url) { bmp -> if (m.attachment_url == url) h.img.setImageBitmap(bmp) }
            }
            "audio" -> {
                text = prefix + "\uD83C\uDFA4 voice note - tap to play"
                h.body.setOnClickListener { onPlayAudio(m.attachment_url) }
            }
            else -> {
                text = prefix + m.body
                h.body.setOnClickListener(null)
            }
        }
        if (m.sender_id == Api.userId) text += if (m.read_at.isNotEmpty()) " \u2713\u2713" else " \u2713"
        val stamp = fmtTime(m.created_at)
        if (stamp.isNotEmpty()) text += "  $stamp"
        val rx = reactions[m.id]
        if (!rx.isNullOrEmpty()) {
            val counts = rx.groupingBy { it.first }.eachCount()
            text += "  " + counts.entries.joinToString(" ") { "${it.key}x${it.value}" }
        }
        h.body.text = reply + text
        h.body.setOnLongClickListener { onActions(m); true }
    }
}
