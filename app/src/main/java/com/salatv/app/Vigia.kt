package com.salatv.app

import android.accessibilityservice.AccessibilityService
import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager
import android.os.PowerManager
import android.os.SystemClock
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Fire OS will not hand Home to another app. When the stock home comes to the front, the vigía puts
 * SalaTV in front of it. Being an accessibility service, it also sees every remote button and which
 * app is in front, which is what the shortcuts, «Doble Inicio», «¿Sigues ahí?», the night volume,
 * the PIN guard and «Encendido directo» need.
 */
class Vigia : AccessibilityService() {
    companion object {
        @Volatile var instance: Vigia? = null
        /** Set when the user asks for the stock home on purpose; cleared when they open any other app. */
        @Volatile var bypass = false
        /** While set, the next non-navigation button is reported here instead of doing anything. */
        @Volatile var learner: ((Int, Int) -> Unit)? = null
        /** Last remote press, for «¿Sigues ahí?». */
        @Volatile var lastKey = SystemClock.elapsedRealtime()

        private val PASS = setOf(
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT,
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_HOME,
            KeyEvent.KEYCODE_VOLUME_UP, KeyEvent.KEYCODE_VOLUME_DOWN, KeyEvent.KEYCODE_VOLUME_MUTE, KeyEvent.KEYCODE_POWER,
        )
        private val IGNORE = setOf("com.android.systemui", "android", "com.amazon.tv.ime", "com.android.inputmethod.latin")

        fun reload() { instance?.load() }
    }

    private var maps: List<KeyMap> = emptyList()
    private var settingsPanel = true
    private var doubleHome = true
    private var lastJump = 0L
    private var salaShown = false
    private var swallow = -1
    /** The app that was in front before Home, for «Doble Inicio». */
    private var before: String? = null
    private var front: String? = null
    /** Whatever window came to the front last, stock home included. */
    private var lastWindow: String? = null
    private val launchable = HashMap<String, Boolean>()

    private val idle = object : Runnable {
        override fun run() {
            checkIdle()
            Night.enforce(this@Vigia)
            Bg.main.postDelayed(this, 60_000)
        }
    }

    private val screen = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            when (i.action) {
                Intent.ACTION_SCREEN_ON -> { lastKey = SystemClock.elapsedRealtime(); Bg.main.postDelayed({ onWake() }, 1800) }
                "android.media.VOLUME_CHANGED_ACTION" -> Night.enforce(c)
            }
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        load()
        lastWindow = frontFromUsage()
        // After an update the TV falls back to the stock home while the vigía is away, and on Fire OS
        // Home is not passed to accessibility services, so nothing else would bring SalaTV back.
        if (lastWindow in Platform.STOCK_HOMES) Bg.main.postDelayed({ if (lastWindow in Platform.STOCK_HOMES) onStockHome() }, 1500)
        registerReceiver(screen, IntentFilter().apply { addAction(Intent.ACTION_SCREEN_ON); addAction("android.media.VOLUME_CHANGED_ACTION") })
        Bg.main.postDelayed(idle, 60_000)
    }

    override fun onUnbind(intent: Intent?): Boolean {
        instance = null
        Bg.main.removeCallbacks(idle)
        try { unregisterReceiver(screen) } catch (_: Exception) {}
        return super.onUnbind(intent)
    }

    fun load() {
        maps = Prefs.keyMaps(this)
        settingsPanel = Prefs.bool(this, "settingsKeyPanel", true)
        doubleHome = Prefs.bool(this, "doubleHome", true)
    }

    /** What was in front when the vigía started, from the system's usage log. */
    private fun frontFromUsage(): String? = try {
        val usm = getSystemService(android.app.usage.UsageStatsManager::class.java)
        val now = System.currentTimeMillis()
        val ev = usm.queryEvents(now - 10 * 60_000L, now)
        val e = android.app.usage.UsageEvents.Event()
        var last: String? = null
        while (ev.hasNextEvent()) {
            ev.getNextEvent(e)
            @Suppress("DEPRECATION")
            if (e.eventType == android.app.usage.UsageEvents.Event.MOVE_TO_FOREGROUND && e.packageName !in IGNORE) last = e.packageName
        }
        last
    } catch (e: Exception) { null }

    private fun isApp(pkg: String) = launchable.getOrPut(pkg) { Sys.launchIntent(this, pkg) != null }

    override fun onAccessibilityEvent(e: AccessibilityEvent?) {
        if (e == null || e.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val pkg = e.packageName?.toString() ?: return
        if (pkg !in IGNORE) lastWindow = pkg
        when {
            pkg in Platform.STOCK_HOMES -> if (!bypass) onStockHome()
            pkg == packageName -> if (lastJump > 0) salaShown = true
            pkg in IGNORE -> {}
            else -> {
                bypass = false
                if (isApp(pkg) && pkg != front) {
                    front = pkg
                    Prefs.put(this, "lastApp", pkg)
                }
                if (Pin.needs(this, pkg) && !Pin.asking) {
                    Pin.asking = true
                    startActivity(Intent(this, PinActivity::class.java).putExtra("pkg", pkg).putExtra("guard", true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }
            }
        }
    }

    private fun onStockHome() {
        val now = SystemClock.uptimeMillis()
        if (SafetyNet.safeMode(this)) return
        val prev = before
        if (doubleHome && prev != null && Logic.isDoubleHome(now - lastJump, salaShown)) {
            lastJump = 0; salaShown = false
            Sys.start(this, prev, null)
            return
        }
        if (now - lastJump < 700) return
        before = front
        front = null
        lastJump = now
        salaShown = false
        try {
            startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED))
        } catch (_: Exception) {}
    }

    /** «Encendido directo»: what to open when the screen turns on. */
    private fun onWake() {
        val w = Prefs.str(this, "wake", "home")!!
        try {
            when {
                w == "home" -> {}
                w == "resume" -> if (Feed.cached(this).isNotEmpty()) startActivity(Intent(this, ResumePromptActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                w == "usual" -> UsageRank.ranks(this).filterKeys { it != packageName && isApp(it) }.maxByOrNull { it.value }?.key?.let { Sys.start(this, it, null) }
                w == "last" -> Prefs.str(this, "lastApp")?.let { Sys.start(this, it, null) }
                w.startsWith("app:") -> Sys.start(this, w.removePrefix("app:"), null)
                w.startsWith("input:") -> Inputs.open(this, w.removePrefix("input:"))
            }
        } catch (e: Exception) {
            Registro.add(this, "Encendido directo", e.message ?: e.javaClass.simpleName)
        }
    }

    private fun checkIdle() {
        val mins = Night.idleMinutes(this)
        if (mins <= 0 || !Night.active(this) || StillThereActivity.showing) return
        if (getSystemService(PowerManager::class.java)?.isInteractive != true) return
        if (SystemClock.elapsedRealtime() - lastKey < mins * 60_000L) return
        startActivity(Intent(this, StillThereActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    override fun onKeyEvent(ev: KeyEvent): Boolean {
        lastKey = SystemClock.elapsedRealtime()
        val code = ev.keyCode
        if (code == swallow) {
            if (ev.action == KeyEvent.ACTION_UP) swallow = -1
            return true
        }
        val l = learner
        if (l != null && code !in PASS && ev.action == KeyEvent.ACTION_DOWN) {
            learner = null
            swallow = code
            l(code, ev.scanCode)
            return true
        }
        // Home pressed while the stock home is already in front opens no new window, so the window
        // watcher never fires. Seen right after updating SalaTV: the TV falls back to Amazon's home.
        // A fresh vigía (after an update) may not know what is in front: if Home opens no new window,
        // the stock home was already there.
        if (code == KeyEvent.KEYCODE_HOME && ev.action == KeyEvent.ACTION_UP) {
            val was = lastWindow
            if (was == null || was in Platform.STOCK_HOMES) {
                bypass = false
                lastWindow = null
                Bg.main.postDelayed({
                    val now = lastWindow
                    if ((now == null || now in Platform.STOCK_HOMES) && SystemClock.uptimeMillis() - lastJump > 700) onStockHome()
                }, 400)
            }
            return false
        }
        if (code == KeyEvent.KEYCODE_VOLUME_UP && Night.cap(this) > 0 && Night.active(this)) {
            val am = getSystemService(AudioManager::class.java)
            if (am.getStreamVolume(AudioManager.STREAM_MUSIC) >= Night.capSteps(this)) {
                if (ev.action == KeyEvent.ACTION_DOWN && ev.repeatCount == 0) Ui.toast(this, "Volumen nocturno: máximo ${Night.cap(this)} %")
                return true
            }
        }
        val m = maps.firstOrNull { it.scanCode > 0 && it.scanCode == ev.scanCode && it.keyCode == code }
            ?: maps.firstOrNull { it.keyCode == code }
        if (m != null) {
            if (ev.action == KeyEvent.ACTION_UP && !ev.isCanceled) open(m.pkg)
            return true
        }
        if (code == KeyEvent.KEYCODE_SETTINGS && settingsPanel) {
            if (ev.action == KeyEvent.ACTION_UP) startActivity(Intent(this, QuickPanelActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            return true
        }
        return false
    }

    private fun open(pkg: String) {
        if (pkg == packageName) {
            startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } else if (!Sys.launch(this, pkg)) {
            Ui.toast(this, "Esa app ya no está instalada")
        }
    }

    /** Last resort for turning the TV off when ADB is not available. */
    fun sleep() = performGlobalAction(GLOBAL_ACTION_LOCK_SCREEN)

    override fun onInterrupt() {}
}

/** Puts the TV to sleep: through its own ADB if allowed, else the accessibility lock action. */
object Power {
    fun sleep(c: Context) {
        val app = c.applicationContext
        Bg.run({ Adb.connect(app, allowPrompt = false).use { it.shell("input keyevent 223") } }) { r ->
            if (r.isFailure) {
                Registro.add(app, "Apagar", "Sin ADB; se usó el bloqueo de pantalla")
                Vigia.instance?.sleep()
            }
        }
    }
}

object SleepTimer {
    val CHOICES = intArrayOf(0, 15, 30, 60, 90)

    private fun pending(c: Context) = PendingIntent.getBroadcast(c, 7, Intent(c, SleepReceiver::class.java),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

    fun set(c: Context, minutes: Int) {
        val am = c.getSystemService(AlarmManager::class.java)
        am.cancel(pending(c))
        if (minutes <= 0) {
            Prefs.put(c, "sleepAt", 0L)
            return
        }
        val at = System.currentTimeMillis() + minutes * 60_000L
        am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pending(c))
        Prefs.put(c, "sleepAt", at)
        Prefs.put(c, "sleepMin", minutes.toLong())
    }

    fun at(c: Context): Long = Prefs.long(c, "sleepAt").takeIf { it > System.currentTimeMillis() } ?: 0L

    fun choice(c: Context): Int = if (at(c) == 0L) 0 else CHOICES.indexOf(Prefs.long(c, "sleepMin").toInt()).coerceAtLeast(0)

    fun describe(c: Context): String {
        val t = at(c)
        return if (t == 0L) "Sin apagado programado" else "La tele se apagará a las " + SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(t))
    }
}

class SleepReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        Prefs.put(c, "sleepAt", 0L)
        Power.sleep(c)
    }
}
