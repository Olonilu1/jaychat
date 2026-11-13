package com.juwon.yarn

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

object Storage {
    suspend fun upload(name: String, bytes: ByteArray, contentType: String): String? = withContext(Dispatchers.IO) {
        try {
            val url = Constants.SUPABASE_URL.trimEnd('/') + "/storage/v1/object/jaychat/" + name
            val req = Request.Builder().url(url)
                .header("apikey", Constants.SUPABASE_ANON_KEY)
                .header("Authorization", "Bearer ${Api.token ?: ""}")
                .header("x-upsert", "true")
                .post(bytes.toRequestBody(contentType.toMediaType()))
                .build()
            Api.client.newCall(req).execute().use { r -> if (r.isSuccessful) publicUrl(name) else null }
        } catch (e: Exception) { null }
    }

    fun publicUrl(name: String) =
        Constants.SUPABASE_URL.trimEnd('/') + "/storage/v1/object/public/jaychat/" + name

    suspend fun fetch(url: String): ByteArray? = withContext(Dispatchers.IO) {
        try {
            Api.client.newCall(Request.Builder().url(url).build()).execute().use { r ->
                if (r.isSuccessful) r.body?.bytes() else null
            }
        } catch (e: Exception) { null }
    }
}
