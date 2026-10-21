package com.salatv.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest
import java.util.concurrent.Executors

/** The few HTTP calls SalaTV makes: TMDB, Crunchyroll and app downloads. */
object Http {
    class Error(val code: Int, val body: String) : IOException("HTTP $code")

    fun form(vararg pairs: Pair<String, String>) = pairs.joinToString("&") { (k, v) -> enc(k) + "=" + enc(v) }
    fun enc(s: String): String = URLEncoder.encode(s, "UTF-8")

    fun get(url: String, headers: Map<String, String> = emptyMap()) = call("GET", url, null, null, headers)

    fun post(url: String, body: String, type: String, headers: Map<String, String> = emptyMap()) = call("POST", url, body, type, headers)

    private fun call(method: String, url: String, body: String?, type: String?, headers: Map<String, String>): String {
        val con = URL(url).openConnection() as HttpURLConnection
        con.requestMethod = method
        con.connectTimeout = 10_000
        con.readTimeout = 20_000
        con.setRequestProperty("User-Agent", "SalaTV/0.3")
        con.setRequestProperty("Accept", "application/json")
        headers.forEach { (k, v) -> con.setRequestProperty(k, v) }
        if (body != null) {
            con.doOutput = true
            con.setRequestProperty("Content-Type", type ?: "application/json")
            con.outputStream.use { it.write(body.toByteArray()) }
        }
        val code = con.responseCode
        val stream = if (code >= 400) con.errorStream else con.inputStream
        val text = stream?.bufferedReader()?.use { it.readText() } ?: ""
        if (code >= 400) throw Error(code, text.take(300))
        return text
    }
}

/**
 * Pictures from the web or from SalaTV's files, shrunk to the size they are drawn at, kept in memory
 * (about 24 MB) and on disk, so the carousel and the rows come back at once.
 */
object Img {
    private val pool = Executors.newFixedThreadPool(3)
    private val mem = object : LruCache<String, Bitmap>(24 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }
    private val loading = HashSet<String>()

    private fun hash(s: String) = MessageDigest.getInstance("SHA-1").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }

    /** The picture if it is ready; otherwise null, and [ready] runs on the main thread once it is. */
    fun get(c: Context, src: String?, width: Int, ready: () -> Unit): Bitmap? {
        if (src.isNullOrEmpty()) return null
        val key = "$src@$width"
        mem.get(key)?.let { return it }
        synchronized(loading) { if (!loading.add(key)) return null }
        val app = c.applicationContext
        pool.execute {
            val b = try { load(app, src, width) } catch (e: Exception) { null }
            synchronized(loading) { loading.remove(key) }
            if (b != null) {
                mem.put(key, b)
                Bg.main.post(ready)
            }
        }
        return null
    }

    private fun load(c: Context, src: String, width: Int): Bitmap? {
        if (src.startsWith("/")) return decode(File(src), width)
        val dir = File(c.cacheDir, "img").apply { mkdirs() }
        val f = File(dir, hash(src))
        if (!f.exists()) {
            val con = URL(src).openConnection() as HttpURLConnection
            con.connectTimeout = 10_000; con.readTimeout = 20_000
            con.setRequestProperty("User-Agent", "SalaTV/0.3")
            val tmp = File(dir, f.name + ".part")
            con.inputStream.use { i -> tmp.outputStream().use { i.copyTo(it) } }
            tmp.renameTo(f)
            trim(dir)
        }
        return decode(f, width)
    }

    private fun decode(f: File, width: Int): Bitmap? {
        if (!f.exists()) return null
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(f.path, o)
        var sample = 1
        while (o.outWidth / (sample * 2) >= width) sample *= 2
        return BitmapFactory.decodeFile(f.path, BitmapFactory.Options().apply { inSampleSize = sample; inPreferredConfig = Bitmap.Config.RGB_565 })
    }

    /** Keeps the disk cache under 60 MB, oldest out first. */
    private fun trim(dir: File) {
        val files = dir.listFiles()?.sortedBy { it.lastModified() } ?: return
        var total = files.sumOf { it.length() }
        for (f in files) {
            if (total < 60L * 1024 * 1024) break
            total -= f.length(); f.delete()
        }
    }
}
