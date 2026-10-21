package com.salatv.app

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Handler
import android.os.Looper
import java.util.concurrent.Executors

class App : Application() {
    companion object {
        /** Set when the screen turns on, so the profile picker shows on the next visit to the home. */
        @Volatile var needPicker = true
    }

    override fun onCreate() {
        super.onCreate()
        SafetyNet.install(this)
        Fonts.init(this)
        registerReceiver(object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) { needPicker = true }
        }, IntentFilter(Intent.ACTION_SCREEN_ON))
    }
}

/** Two background threads and a way back to the main one; enough for a launcher. */
object Bg {
    private val pool = Executors.newFixedThreadPool(2)
    val main = Handler(Looper.getMainLooper())

    fun <T> run(work: () -> T, done: (Result<T>) -> Unit) {
        pool.execute {
            val r = runCatching(work)
            main.post { done(r) }
        }
    }
}

/** Counts crashes; three in five minutes puts SalaTV in safe mode and the vigía stops taking over Home. */
object SafetyNet {
    private const val WINDOW = 5 * 60_000L

    fun install(c: Context) {
        val app = c.applicationContext
        val prev = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            try {
                record(app)
                Registro.add(app, "Cierre", "${e.javaClass.simpleName}: ${e.message ?: ""} en ${e.stackTrace.firstOrNull { it.className.startsWith("com.salatv") } ?: "?"}")
            } catch (_: Throwable) {}
            prev?.uncaughtException(t, e)
        }
    }

    private fun times(c: Context) = Prefs.sp(c).getString("crashes", "")!!.split(',').mapNotNull { it.toLongOrNull() }

    private fun record(c: Context) {
        val now = System.currentTimeMillis()
        val recent = (times(c) + now).filter { now - it < WINDOW }
        val ed = Prefs.sp(c).edit().putString("crashes", recent.joinToString(","))
        if (recent.size >= 3) ed.putBoolean("safe", true)
        ed.commit()
    }

    fun safeMode(c: Context) = Prefs.bool(c, "safe", false)

    fun recentCrashes(c: Context): Int {
        val now = System.currentTimeMillis()
        return times(c).count { now - it < 24 * 3600_000L }
    }

    fun clear(c: Context) = Prefs.sp(c).edit().putBoolean("safe", false).putString("crashes", "").apply()
}
