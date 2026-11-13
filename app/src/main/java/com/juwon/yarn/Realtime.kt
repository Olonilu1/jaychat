package com.juwon.yarn

import android.os.Handler
import android.os.Looper
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.CopyOnWriteArrayList

object Realtime {
    private val handlers = CopyOnWriteArrayList<(String, JSONObject) -> Unit>()
    private var ws: WebSocket? = null
    private var ref = 1
    private val main = Handler(Looper.getMainLooper())

    fun add(h: (String, JSONObject) -> Unit) { handlers.add(h); ensureConnected() }
    fun remove(h: (String, JSONObject) -> Unit) { handlers.remove(h) }

    private fun ensureConnected() {
        if (ws != null || handlers.isEmpty()) return
        val url = Constants.SUPABASE_URL.replace("https://", "wss://").trimEnd('/') +
            "/realtime/v1/websocket?vsn=1.0.0&apikey=" + Constants.SUPABASE_ANON_KEY
        val req = Request.Builder().url(url).build()
        ws = Api.client.newWebSocket(req, object : WebSocketListener() {
            override fun onOpen(w: WebSocket, r: Response) {
                ref = 1
                val changes = JSONArray()
                    .put(JSONObject().put("event", "INSERT").put("schema", "public").put("table", "messages"))
                    .put(JSONObject().put("event", "UPDATE").put("schema", "public").put("table", "messages"))
                    .put(JSONObject().put("event", "INSERT").put("schema", "public").put("table", "typing"))
                    .put(JSONObject().put("event", "INSERT").put("schema", "public").put("table", "reactions"))
                    .put(JSONObject().put("event", "DELETE").put("schema", "public").put("table", "reactions"))
                val join = JSONObject()
                    .put("topic", "realtime:public:stream")
                    .put("event", "phx_join")
                    .put("ref", "1")
                    .put("payload", JSONObject().put("config", JSONObject().put("postgres_changes", changes)))
                w.send(join.toString())
                heartbeat(w)
            }
            override fun onMessage(w: WebSocket, text: String) {
                try {
                    val o = JSONObject(text)
                    if (o.optString("event") == "postgres_changes") {
                        val data = o.optJSONObject("payload")?.optJSONObject("data") ?: return
                        val table = data.optString("table")
                        val rec = data.optJSONObject("record") ?: data.optJSONObject("old_record") ?: return
                        main.post { for (h in handlers) h(table, rec) }
                    }
                } catch (_: Exception) {}
            }
            override fun onFailure(w: WebSocket, t: Throwable, r: Response?) { ws = null; retry() }
            override fun onClosed(w: WebSocket, code: Int, reason: String) { ws = null; retry() }
        })
    }

    private fun heartbeat(w: WebSocket) {
        main.postDelayed({
            if (ws === w) {
                ref++
                w.send(JSONObject().put("topic", "phoenix").put("event", "heartbeat")
                    .put("payload", JSONObject()).put("ref", ref.toString()).toString())
                heartbeat(w)
            }
        }, 25000)
    }

    private fun retry() {
        main.postDelayed({ if (handlers.isNotEmpty()) ensureConnected() }, 3000)
    }
}
