package com.juwon.yarn

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch

object MediaCache {
    private val cache = object : LruCache<String, Bitmap>(8 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }
    private val main = Handler(Looper.getMainLooper())

    fun load(url: String, done: (Bitmap?) -> Unit) {
        cache.get(url)?.let { done(it); return }
        GlobalScope.launch(Dispatchers.IO) {
            val bytes = Storage.fetch(url)
            val bmp = bytes?.let { BitmapFactory.decodeByteArray(it, 0, it.size) }
            if (bmp != null) cache.put(url, bmp)
            main.post { done(bmp) }
        }
    }
}
