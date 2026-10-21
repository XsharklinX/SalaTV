package com.salatv.app

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Rect
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.net.Uri
import android.os.SystemClock
import android.provider.Settings
import android.service.notification.NotificationListenerService
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream

/** Something watched in another app: what, where it stopped, and a picture of it. */
class Watched(
    val pkg: String,
    val title: String,
    val sub: String,
    val mediaId: String?,
    val uri: String?,
    val pos: Long,
    val dur: Long,
    val at: Long,
    val art: String?,
    /** SalaTV profile that was watching; null in entries from 0.2. */
    val profile: String? = null,
) {
    val key get() = Watching.key(pkg, title)
    val progress get() = if (dur > 0) (pos.toFloat() / dur).coerceIn(0f, 1f) else 0f
    val minutesLeft get() = if (dur > 0) ((dur - pos) / 60_000).toInt().coerceAtLeast(0) else -1
}

/** The «Continuar viendo» history, newest first, kept in SalaTV's private files. */
object Watching {
    private const val MAX = 12

    fun key(pkg: String, title: String) = "$pkg|$title"

    private fun file(c: Context) = File(c.filesDir, "watching.json")
    private fun artDir(c: Context) = File(c.filesDir, "art").apply { mkdirs() }

    /** This profile's entries, newest first. */
    fun all(c: Context): List<Watched> {
        val p = Prefs.current(c).id
        return raw(c).filter { it.profile == null || it.profile == p }
    }

    @Synchronized
    private fun raw(c: Context): List<Watched> {
        val f = file(c)
        if (!f.exists()) return emptyList()
        return try {
            val a = JSONArray(f.readText())
            (0 until a.length()).map { i ->
                val o = a.getJSONObject(i)
                Watched(o.getString("pkg"), o.getString("title"), o.optString("sub"), o.optString("id").ifEmpty { null },
                    o.optString("uri").ifEmpty { null }, o.optLong("pos"), o.optLong("dur"), o.optLong("at"), o.optString("art").ifEmpty { null },
                    o.optString("profile").ifEmpty { null })
            }.sortedByDescending { it.at }
        } catch (e: Exception) { emptyList() }
    }

    @Synchronized
    private fun save(c: Context, l: List<Watched>) {
        val a = JSONArray()
        l.forEach {
            a.put(JSONObject().put("pkg", it.pkg).put("title", it.title).put("sub", it.sub).put("id", it.mediaId ?: "")
                .put("uri", it.uri ?: "").put("pos", it.pos).put("dur", it.dur).put("at", it.at).put("art", it.art ?: "").put("profile", it.profile ?: ""))
        }
        file(c).writeText(a.toString())
        val keep = l.mapNotNull { it.art }.toSet()
        artDir(c).listFiles()?.forEach { if (it.name !in keep) it.delete() }
    }

    /** Adds or updates by app and title. Finished items (under 2 minutes left) leave the row. */
    @Synchronized
    fun upsert(c: Context, w: Watched, art: Bitmap?) {
        val l = raw(c).filter { it.key != w.key || (it.profile != null && it.profile != w.profile) }.toMutableList()
        val nearEnd = w.dur > 0 && w.dur - w.pos < 120_000 && w.pos > 60_000
        if (!nearEnd) {
            val old = raw(c).firstOrNull { it.key == w.key }?.art
            var name = old
            if (art != null) {
                name = "a" + w.key.hashCode().toUInt().toString(16) + ".jpg"
                try {
                    FileOutputStream(File(artDir(c), name)).use { crop(art).compress(Bitmap.CompressFormat.JPEG, 85, it) }
                } catch (e: Exception) { name = old }
            }
            l.add(0, Watched(w.pkg, w.title, w.sub, w.mediaId, w.uri, w.pos, w.dur, w.at, name, w.profile))
        }
        save(c, l.take(MAX * 3))
    }

    fun remove(c: Context, key: String) = save(c, raw(c).filter { it.key != key })

    fun art(c: Context, w: Watched): Bitmap? = w.art?.let { BitmapFactory.decodeFile(File(artDir(c), it).path) }

    private fun crop(b: Bitmap): Bitmap {
        val out = Bitmap.createBitmap(320, 180, Bitmap.Config.RGB_565)
        val want = 16f / 9f
        val have = b.width.toFloat() / b.height
        val src = if (have > want) {
            val w = (b.height * want).toInt(); Rect((b.width - w) / 2, 0, (b.width + w) / 2, b.height)
        } else {
            val h = (b.width / want).toInt(); Rect(0, (b.height - h) / 2, b.width, (b.height + h) / 2)
        }
        Canvas(out).drawBitmap(b, src, Rect(0, 0, 320, 180), null)
        return out
    }
}

/**
 * Reads what other apps are playing through their media sessions (the same ones Alexa and the
 * remote's play button use). Android only shares them with a notification listener, which
 * «Configurar todo» allows through ADB. SalaTV ignores the notifications themselves.
 */
class MediaWatcher : NotificationListenerService() {
    companion object {
        @Volatile var instance: MediaWatcher? = null

        fun enabled(c: Context) = Settings.Secure.getString(c.contentResolver, "enabled_notification_listeners")
            ?.contains(c.packageName) == true

        /** Music and short clips are not «series and films». */
        private val SKIP = setOf("com.spotify.tv.android", "com.spotify.music", "com.amazon.mp3", "com.amazon.bueller.music")
    }

    private var msm: MediaSessionManager? = null
    private val watched = HashMap<SessionKey, Pair<MediaController, MediaController.Callback>>()
    private val lastSave = HashMap<String, Long>()

    private class SessionKey(val token: android.media.session.MediaSession.Token) {
        override fun equals(other: Any?) = other is SessionKey && other.token == token
        override fun hashCode() = token.hashCode()
    }

    private val sessions = MediaSessionManager.OnActiveSessionsChangedListener { list -> sync(list ?: emptyList()) }

    override fun onListenerConnected() {
        instance = this
        val m = getSystemService(MediaSessionManager::class.java) ?: return
        msm = m
        val me = ComponentName(this, MediaWatcher::class.java)
        try {
            m.addOnActiveSessionsChangedListener(sessions, me)
            sync(m.getActiveSessions(me))
        } catch (e: SecurityException) {
            Registro.add(this, "Continuar viendo", "Sin permiso para ver lo que se reproduce")
        }
    }

    override fun onListenerDisconnected() {
        msm?.removeOnActiveSessionsChangedListener(sessions)
        watched.values.forEach { (ctl, cb) -> ctl.unregisterCallback(cb) }
        watched.clear()
        instance = null
    }

    private fun sync(list: List<MediaController>) {
        val now = list.associateBy { SessionKey(it.sessionToken) }
        watched.keys.filter { it !in now }.forEach { k ->
            watched.remove(k)?.let { (ctl, cb) -> record(ctl, force = true); ctl.unregisterCallback(cb) }
        }
        now.forEach { (k, ctl) ->
            if (k in watched || ctl.packageName == packageName || ctl.packageName in SKIP) return@forEach
            val cb = object : MediaController.Callback() {
                override fun onMetadataChanged(metadata: MediaMetadata?) = record(ctl, force = true)
                override fun onPlaybackStateChanged(state: PlaybackState?) = record(ctl, force = state?.state != PlaybackState.STATE_PLAYING)
            }
            ctl.registerCallback(cb, Bg.main)
            watched[k] = ctl to cb
            record(ctl, force = true)
        }
    }

    /** Saves at most every 15 s while playing, and always on pause, stop or a new title. */
    private fun record(ctl: MediaController, force: Boolean) {
        val md = ctl.metadata ?: return
        val title = (md.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE) ?: md.getString(MediaMetadata.METADATA_KEY_TITLE))?.trim()
        if (title.isNullOrEmpty()) return
        val dur = md.getLong(MediaMetadata.METADATA_KEY_DURATION)
        if (dur in 1..90_000) return
        val k = Watching.key(ctl.packageName, title)
        val now = SystemClock.elapsedRealtime()
        if (!force && now - (lastSave[k] ?: 0L) < 15_000) return
        lastSave[k] = now
        val ps = ctl.playbackState
        var pos = ps?.position ?: 0L
        if (ps != null && ps.state == PlaybackState.STATE_PLAYING && ps.lastPositionUpdateTime > 0) {
            pos += ((now - ps.lastPositionUpdateTime) * ps.playbackSpeed).toLong()
        }
        val sub = listOfNotNull(
            md.getString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE),
            md.getString(MediaMetadata.METADATA_KEY_ARTIST),
            md.getString(MediaMetadata.METADATA_KEY_ALBUM),
        ).firstOrNull { it.isNotBlank() && it != title } ?: ""
        val art = md.getBitmap(MediaMetadata.METADATA_KEY_ART) ?: md.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
            ?: md.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON)
        val w = Watched(ctl.packageName, title, sub, md.getString(MediaMetadata.METADATA_KEY_MEDIA_ID),
            md.getString(MediaMetadata.METADATA_KEY_MEDIA_URI), pos.coerceAtLeast(0), dur, System.currentTimeMillis(), null,
            Prefs.current(applicationContext).id)
        val app = applicationContext
        Bg.run({ Watching.upsert(app, w, art) }) {}
    }

    /** If the app still has its session (it is in the background), press play on it. */
    fun play(pkg: String): Boolean {
        val ctl = watched.values.map { it.first }.firstOrNull { it.packageName == pkg } ?: return false
        ctl.transportControls.play()
        return true
    }
}

/** Web addresses that take an app straight to a title. Pure, so it is tested on the PC. */
object LinkRules {
    val YOUTUBE = setOf(
        "org.smarttube.stable", "org.smarttube.beta", "com.liskovsoft.smarttubetv", "com.liskovsoft.smarttubetv.beta",
        "com.amazon.firetv.youtube", "com.google.android.youtube.tv",
    )
    private val YT_ID = Regex("^[A-Za-z0-9_-]{11}$")
    private val YT_IN_URL = Regex("(?:v=|youtu\\.be/|/embed/|/shorts/)([A-Za-z0-9_-]{11})")

    fun url(pkg: String, mediaId: String?, uri: String?, posSec: Long): String? {
        if (pkg in YOUTUBE) {
            val id = mediaId?.takeIf { YT_ID.matches(it) } ?: uri?.let { YT_IN_URL.find(it)?.groupValues?.get(1) } ?: return null
            return "https://www.youtube.com/watch?v=$id" + if (posSec > 5) "&t=${posSec}s" else ""
        }
        if (pkg == "com.netflix.ninja") {
            val id = mediaId?.takeIf { it.matches(Regex("^\\d{6,10}$")) } ?: return null
            return "https://www.netflix.com/watch/$id"
        }
        if (uri != null && !uri.startsWith("content:") && !uri.startsWith("file:") && uri.contains(':')) return uri
        return null
    }
}

object Resume {
    /** Opens the title where it was left; falls back to opening the app and pressing play. */
    fun open(c: Context, w: Watched) {
        val url = LinkRules.url(w.pkg, w.mediaId, w.uri, w.pos / 1000)
        if (url != null) {
            val i = Intent(Intent.ACTION_VIEW, Uri.parse(url)).setPackage(w.pkg).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (Sys.start(c, w.pkg, i)) return
        }
        if (Sys.start(c, w.pkg, null)) {
            val mw = MediaWatcher.instance
            Bg.main.postDelayed({
                if (mw?.play(w.pkg) != true) Ui.toast(c, "Sigue «${w.title}» desde ${AppRepo.byPkg(w.pkg)?.label ?: "la app"}")
            }, 2500)
            Registro.add(c, "Continuar viendo", "${w.pkg} no deja abrir el título directamente; se abrió la app")
        } else {
            Ui.toast(c, "No se pudo abrir la app")
        }
    }
}
