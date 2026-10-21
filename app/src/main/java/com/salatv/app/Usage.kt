package com.salatv.app

import android.app.ActivityManager
import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.os.Debug
import android.os.Environment
import android.os.Process
import android.os.StatFs
import java.util.Calendar

/** Which apps get opened at this time of day, from the system's own usage log (no tracking of our own). */
object UsageRank {
    private val SLOTS = arrayOf("mañana", "tarde", "noche")
    private var cache: Map<String, Float> = emptyMap()
    private var totalCache: Map<String, Int> = emptyMap()
    private var lastCache: Map<String, Long> = emptyMap()
    private var cachedAt = 0L
    private var cachedSlot = -1

    fun has(c: Context): Boolean {
        val ops = c.getSystemService(AppOpsManager::class.java) ?: return false
        @Suppress("DEPRECATION")
        return ops.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), c.packageName) == AppOpsManager.MODE_ALLOWED
    }

    private fun slot(hour: Int) = when (hour) {
        in 5..11 -> 0
        in 12..18 -> 1
        else -> 2
    }

    fun slotName() = SLOTS[slot(Calendar.getInstance().get(Calendar.HOUR_OF_DAY))]

    /** Score per package: launches in this slot over the last two weeks, recent days counting more. */
    @Synchronized
    fun ranks(c: Context): Map<String, Float> {
        if (!has(c)) return emptyMap()
        val now = System.currentTimeMillis()
        val nowSlot = slot(Calendar.getInstance().get(Calendar.HOUR_OF_DAY))
        if (now - cachedAt < 10 * 60_000L && nowSlot == cachedSlot) return cache
        val usm = c.getSystemService(UsageStatsManager::class.java) ?: return emptyMap()
        val events = usm.queryEvents(now - 14 * 86_400_000L, now)
        val e = UsageEvents.Event()
        val cal = Calendar.getInstance()
        val m = HashMap<String, Float>()
        val totals = HashMap<String, Int>()
        val last = HashMap<String, Long>()
        while (events.hasNextEvent()) {
            events.getNextEvent(e)
            @Suppress("DEPRECATION")
            if (e.eventType != UsageEvents.Event.MOVE_TO_FOREGROUND) continue
            val p = e.packageName
            cal.timeInMillis = e.timeStamp
            val sameSlot = slot(cal.get(Calendar.HOUR_OF_DAY)) == nowSlot
            val days = (now - e.timeStamp) / 86_400_000f
            m[p] = (m[p] ?: 0f) + (if (sameSlot) 1f else 0.15f) / (1f + days / 7f)
            totals[p] = (totals[p] ?: 0) + 1
            if (e.timeStamp > (last[p] ?: 0L)) last[p] = e.timeStamp
        }
        cache = m; totalCache = totals; lastCache = last; cachedAt = now; cachedSlot = nowSlot
        return m
    }

    /** Launches in the last two weeks, for «más usadas». */
    fun totals(c: Context): Map<String, Int> { ranks(c); return totalCache }

    /** When each app was last in front, for «recientes» and «Seguir con…». */
    fun lastUsed(c: Context): Map<String, Long> { ranks(c); return lastCache }

    /** «hace 20 min», «hace 2 h», «ayer», «el sábado», or a date. */
    fun ago(at: Long, now: Long = System.currentTimeMillis()): String {
        val min = (now - at) / 60_000
        if (min < 1) return "ahora"
        if (min < 60) return "hace $min min"
        val h = min / 60
        val then = Calendar.getInstance().apply { timeInMillis = at }
        val today = Calendar.getInstance().apply { timeInMillis = now }
        val dayDiff = today.get(Calendar.DAY_OF_YEAR) - then.get(Calendar.DAY_OF_YEAR) +
            365 * (today.get(Calendar.YEAR) - then.get(Calendar.YEAR))
        if (dayDiff == 0) return "hace $h h"
        if (dayDiff == 1) return "ayer"
        if (dayDiff < 7) return "el " + arrayOf("domingo", "lunes", "martes", "miércoles", "jueves", "viernes", "sábado")[then.get(Calendar.DAY_OF_WEEK) - 1]
        return "el ${then.get(Calendar.DAY_OF_MONTH)}/${then.get(Calendar.MONTH) + 1}"
    }

    /** Apps brought to the front in the last [minutes]. */
    fun recentApps(c: Context, minutes: Int): Int {
        if (!has(c)) return -1
        val usm = c.getSystemService(UsageStatsManager::class.java) ?: return -1
        val now = System.currentTimeMillis()
        val events = usm.queryEvents(now - minutes * 60_000L, now)
        val e = UsageEvents.Event()
        val seen = HashSet<String>()
        while (events.hasNextEvent()) {
            events.getNextEvent(e)
            @Suppress("DEPRECATION")
            if (e.eventType == UsageEvents.Event.MOVE_TO_FOREGROUND && e.packageName != c.packageName) seen += e.packageName
        }
        return seen.size
    }
}

object Health {
    class Snap(val memTotal: Long, val memAvail: Long, val storTotal: Long, val storFree: Long, val recent: Int, val ownMb: Int)

    fun snap(c: Context): Snap {
        val am = c.getSystemService(ActivityManager::class.java)
        val mi = ActivityManager.MemoryInfo()
        am.getMemoryInfo(mi)
        val st = StatFs(Environment.getDataDirectory().path)
        val own: Debug.MemoryInfo? = am.getProcessMemoryInfo(intArrayOf(Process.myPid())).firstOrNull()
        return Snap(mi.totalMem, mi.availMem, st.totalBytes, st.availableBytes, UsageRank.recentApps(c, 60), (own?.totalPss ?: 0) / 1024)
    }

    /** With ADB, «am kill-all» closes every background process; without it, each app we can see. */
    fun free(c: Context, adb: Adb?) {
        if (adb != null) {
            adb.shell("am kill-all")
            return
        }
        val am = c.getSystemService(ActivityManager::class.java)
        AppRepo.apps.forEach { am.killBackgroundProcesses(it.pkg) }
    }

    fun gb(bytes: Long) = "%.1f".format(bytes / 1_073_741_824.0).replace('.', ',')
}
