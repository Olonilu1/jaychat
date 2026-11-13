package com.juwon.yarn

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

object Api {
    const val PREFS = "yarn_prefs"
    lateinit var ctx: Context
    val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    val token: String? get() = prefs().getString("access_token", null)
    val userId: String? get() = prefs().getString("user_id", null)
    val myUsername: String get() = prefs().getString("username", "") ?: ""
    var openChatId: String? = null

    fun prefs() = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun nowIso(): String =
        java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", java.util.Locale.US).apply {
            timeZone = java.util.TimeZone.getTimeZone("UTC")
        }.format(java.util.Date())

    fun clear() {
        prefs().edit().clear().apply()
    }

    private fun baseUrl(path: String) = Constants.SUPABASE_URL.trimEnd('/') + path

    suspend fun auth(path: String, body: JSONObject): JSONObject? = withContext(Dispatchers.IO) {
        try {
            val req = Request.Builder()
                .url(baseUrl(path))
                .header("apikey", Constants.SUPABASE_ANON_KEY)
                .header("Content-Type", "application/json")
                .post(body.toString().toRequestBody("application/json".toMediaType()))
                .build()
            client.newCall(req).execute().use { resp ->
                val text = resp.body?.string() ?: return@use null
                runCatching { JSONObject(text) }.getOrNull()
            }
        } catch (e: Exception) { null }
    }

    private fun expiredBody(text: String?): Boolean {
        if (text == null) return false
        val t = text.lowercase()
        return t.contains("jwt expired") || t.contains("pgrst301") || t.contains("token expired")
    }

    suspend fun refreshToken(): Boolean = withContext(Dispatchers.IO) {
        val refresh = prefs().getString("refresh_token", null) ?: return@withContext false
        try {
            val body = JSONObject().put("refresh_token", refresh)
            val req = Request.Builder()
                .url(baseUrl("/auth/v1/token?grant_type=refresh_token"))
                .header("apikey", Constants.SUPABASE_ANON_KEY)
                .header("Content-Type", "application/json")
                .post(body.toString().toRequestBody("application/json".toMediaType()))
                .build()
            client.newCall(req).execute().use { resp ->
                val text = resp.body?.string() ?: return@use false
                val res = runCatching { JSONObject(text) }.getOrNull() ?: return@use false
                val access = res.optString("access_token")
                if (access.isNotEmpty()) {
                    val newRefresh = res.optString("refresh_token").ifEmpty { refresh }
                    prefs().edit()
                        .putString("access_token", access)
                        .putString("refresh_token", newRefresh)
                        .apply()
                    true
                } else {
                    // refresh token itself is dead; force a fresh login next launch
                    false
                }
            }
        } catch (e: Exception) { false }
    }

    private suspend fun rawRest(
        method: String, path: String, json: String?, upsert: Boolean, arrayBody: Boolean
    ): Pair<Int, String?> = withContext(Dispatchers.IO) {
        try {
            val b = Request.Builder().url(baseUrl(path))
                .header("apikey", Constants.SUPABASE_ANON_KEY)
                .header("Authorization", "Bearer ${token ?: ""}")
                .header("Content-Type", "application/json")
                .header("Prefer", if (upsert) "return=representation,resolution=merge-duplicates" else "return=representation")
            when (method) {
                "GET" -> b.get()
                "POST" -> b.post(json!!.toRequestBody("application/json".toMediaType()))
                "PATCH" -> b.patch(json!!.toRequestBody("application/json".toMediaType()))
                "DELETE" -> b.delete()
            }
            client.newCall(b.build()).execute().use { resp ->
                val text = resp.body?.string()
                Pair(resp.code, text)
            }
        } catch (e: Exception) { Pair(-1, null) }
    }

    private suspend fun restWithRefresh(
        method: String, path: String, json: String?, upsert: Boolean, arrayBody: Boolean
    ): Pair<Int, String?> {
        var res = rawRest(method, path, json, upsert, arrayBody)
        if (res.first == 401 && expiredBody(res.second) && refreshToken()) {
            res = rawRest(method, path, json, upsert, arrayBody)
        }
        return res
    }

    suspend fun rest(method: String, path: String, body: JSONObject? = null, upsert: Boolean = false): Pair<Int, String?> =
        restWithRefresh(method, path, body?.toString(), upsert, arrayBody = false)

    suspend fun restArray(method: String, path: String, body: JSONArray? = null): Pair<Int, String?> {
        val arr = body ?: JSONArray()
        val res = restWithRefresh(method, path, arr.toString(), upsert = false, arrayBody = true)
        if (res.first == -1) return Pair(-1, null)
        return res
    }
}
