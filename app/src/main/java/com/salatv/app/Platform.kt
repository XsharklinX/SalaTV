package com.salatv.app

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.SoundPool
import android.os.Build
import android.os.SystemClock
import java.io.File
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/** Fire TV or Google TV / Android TV, and the home each one ships with. */
object Platform {
    val STOCK_HOMES = listOf(
        "com.amazon.tv.launcher", "com.google.android.apps.tv.launcherx",
        "com.google.android.tvlauncher", "com.google.android.leanbacklauncher",
    )

    fun isFire(c: Context) = c.packageManager.hasSystemFeature("amazon.hardware.fire_tv") ||
        Build.MANUFACTURER.equals("Amazon", true) || Build.MODEL.startsWith("AFT")

    private fun installed(c: Context, p: String) = try { c.packageManager.getPackageInfo(p, 0); true } catch (e: Exception) { false }

    fun stockHome(c: Context): String? = STOCK_HOMES.firstOrNull { installed(c, it) }

    fun stockName(c: Context) = if (isFire(c)) "Inicio de Amazon" else "Inicio de Google TV"

    /** On Google TV and Android TV, SalaTV can be the real Home; Fire OS refuses. */
    fun isDefaultHome(c: Context): Boolean {
        val ri = c.packageManager.resolveActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), PackageManager.MATCH_DEFAULT_ONLY)
        return ri?.activityInfo?.packageName == c.packageName
    }

    fun makeDefaultHome(c: Context, adb: Adb): String {
        val out = adb.shell("cmd package set-home-activity ${c.packageName}/.MainActivity").trim()
        return if (isDefaultHome(c)) "SalaTV es ahora el inicio de la tele" else "La tele no aceptó el cambio: $out"
    }
}

/** A short Spanish log of what went wrong, readable in Ajustes and copyable to the PC. */
object Registro {
    const val PULL = "adb pull /sdcard/Android/data/com.salatv.app/files/registro.txt"
    private const val MAX = 300

    class Entry(val time: String, val area: String, val text: String)

    @Synchronized
    fun add(c: Context, area: String, text: String) {
        try {
            val f = File(c.filesDir, "registro.txt")
            val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US)
            val cutoff = fmt.format(Date(System.currentTimeMillis() - 7 * 86_400_000L))
            val line = fmt.format(Date()) + "\t" + area + "\t" + text.replace('\n', ' ').replace('\t', ' ').take(300)
            val kept = ((if (f.exists()) f.readLines() else emptyList()) + line).filter { it >= cutoff }.takeLast(MAX)
            val body = kept.joinToString("\n")
            f.writeText(body)
            c.getExternalFilesDir(null)?.let { File(it, "registro.txt").writeText(body) }
        } catch (_: Exception) {}
    }

    fun read(c: Context): List<Entry> {
        val f = File(c.filesDir, "registro.txt")
        if (!f.exists()) return emptyList()
        return f.readLines().mapNotNull { l ->
            val p = l.split('\t')
            if (p.size < 3) null else Entry(p[0], p[1], p[2])
        }.reversed()
    }

    fun clear(c: Context) {
        File(c.filesDir, "registro.txt").delete()
        c.getExternalFilesDir(null)?.let { File(it, "registro.txt").delete() }
    }
}

/** The night window shared by «Volumen nocturno» and «¿Sigues ahí?». */
object Night {
    fun from(c: Context) = Prefs.int(c, "nightFrom", 23)
    fun to(c: Context) = Prefs.int(c, "nightTo", 7)

    fun active(c: Context): Boolean {
        val h = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        val f = from(c); val t = to(c)
        return if (f <= t) h in f until t else h >= f || h < t
    }

    /** Volume cap in percent, 0 when off. */
    fun cap(c: Context) = Prefs.int(c, "nightVolume", 0)

    fun capSteps(c: Context): Int {
        val am = c.getSystemService(AudioManager::class.java)
        val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        return ((max * cap(c) + 99) / 100).coerceIn(1, max)
    }

    /** Lowers the volume to the cap if it is above it. True when it had to. */
    fun enforce(c: Context): Boolean {
        if (cap(c) == 0 || !active(c)) return false
        val am = c.getSystemService(AudioManager::class.java)
        val limit = capSteps(c)
        if (am.getStreamVolume(AudioManager.STREAM_MUSIC) <= limit) return false
        am.setStreamVolume(AudioManager.STREAM_MUSIC, limit, AudioManager.FLAG_SHOW_UI)
        return true
    }

    /** Idle minutes before «¿Sigues ahí?», 0 when off. */
    fun idleMinutes(c: Context) = Prefs.int(c, "stillThere", 0)
}

/** Apps that ask for a four-digit code. Unlocks last 30 minutes. */
object Pin {
    private val unlocked = HashMap<String, Long>()
    @Volatile var asking = false

    fun hash(code: String): String = MessageDigest.getInstance("SHA-256").digest(("salatv:" + code).toByteArray()).joinToString("") { "%02x".format(it) }

    fun hasCode(c: Context) = Prefs.str(c, "pinHash") != null
    fun check(c: Context, code: String) = Prefs.str(c, "pinHash") == hash(code)
    fun setCode(c: Context, code: String) = Prefs.put(c, "pinHash", hash(code))

    fun apps(c: Context) = Prefs.set(c, "pinApps")

    fun needs(c: Context, pkg: String): Boolean {
        if (!hasCode(c) || pkg !in apps(c)) return false
        val until = unlocked[pkg] ?: 0L
        return SystemClock.elapsedRealtime() > until
    }

    fun unlock(pkg: String) { unlocked[pkg] = SystemClock.elapsedRealtime() + 30 * 60_000L }
}

/** Two very quiet clicks: one when the focus moves, one when something opens. Off unless chosen. */
object Sounds {
    private var pool: SoundPool? = null
    private var tick = 0
    private var open = 0

    private fun ready(c: Context): SoundPool? {
        if (!Prefs.bool(c, "sounds", false)) return null
        pool?.let { return it }
        val p = SoundPool.Builder().setMaxStreams(2).setAudioAttributes(
            AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build(),
        ).build()
        tick = p.load(c, R.raw.tick, 1)
        open = p.load(c, R.raw.open, 1)
        pool = p
        return p
    }

    private fun vol(c: Context) = if (Night.active(c)) 0.08f else 0.2f

    fun tick(c: Context) { ready(c)?.play(tick, vol(c), vol(c), 1, 0, 1f) }
    fun open(c: Context) { ready(c)?.play(open, vol(c), vol(c), 1, 0, 1f) }
}
