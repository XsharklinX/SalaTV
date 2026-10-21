package com.salatv.app

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

/** [sort] orders «Todas tus apps»: az, used, recent or type. */
class Profile(val id: String, var name: String, var color: Int, val pinned: MutableList<String>, var autoDock: Boolean, var sort: String = "az")

class Folder(val id: String, var name: String, val pkgs: MutableList<String>)

/** A remote button that opens an app. [scanCode] is 0 when only the key code is known. */
class KeyMap(val keyCode: Int, val scanCode: Int, val pkg: String)

object Prefs {
    val COLORS = intArrayOf(
        0xFFFFB766.toInt(), 0xFF7CB8FF.toInt(), 0xFF5FD39A.toInt(),
        0xFFFF8A80.toInt(), 0xFFB99BFF.toInt(), 0xFFFFD36B.toInt(),
    )
    const val DOCK_MAX = 6

    /** The dock CleanYourTV set up on this TV; only seeds the first profile. */
    private val FIRST_DOCK = listOf("com.xuper.netxxus", "org.smarttube.stable", "com.crunchyroll.crunchyroid", "com.netflix.ninja")

    fun sp(c: Context): SharedPreferences = c.getSharedPreferences("sala", Context.MODE_PRIVATE)

    fun str(c: Context, k: String, def: String? = null): String? = sp(c).getString(k, def)
    fun bool(c: Context, k: String, def: Boolean) = sp(c).getBoolean(k, def)
    fun long(c: Context, k: String, def: Long = 0L) = sp(c).getLong(k, def)
    fun int(c: Context, k: String, def: Int) = sp(c).getInt(k, def)
    fun put(c: Context, k: String, v: Int) = sp(c).edit().putInt(k, v).apply()
    fun set(c: Context, k: String): MutableSet<String> = sp(c).getStringSet(k, emptySet())!!.toMutableSet()
    fun put(c: Context, k: String, v: Set<String>) = sp(c).edit().putStringSet(k, v).apply()

    /** «Configurar todo» bumps this; a higher number in a new version asks for it again. */
    const val SETUP_VERSION = 2L
    /** Done by the button, or already true when every permission is in place (for example, granted from the PC). */
    fun setupDone(c: Context): Boolean {
        if (long(c, "setupV") >= SETUP_VERSION) return true
        val all = Cleaner.vigiaOn(c) && Cleaner.canWriteSecure(c) && UsageRank.has(c) && MediaWatcher.enabled(c)
        if (all) put(c, "setupV", SETUP_VERSION)
        return all
    }
    fun put(c: Context, k: String, v: String?) = sp(c).edit().putString(k, v).apply()
    fun put(c: Context, k: String, v: Boolean) = sp(c).edit().putBoolean(k, v).apply()
    fun put(c: Context, k: String, v: Long) = sp(c).edit().putLong(k, v).apply()

    fun newId(prefix: String) = prefix + System.currentTimeMillis().toString(36)

    private fun strings(a: JSONArray?): MutableList<String> {
        val l = mutableListOf<String>()
        if (a != null) for (i in 0 until a.length()) l += a.optString(i)
        return l
    }

    // ---- profiles ----

    fun profiles(c: Context): MutableList<Profile> {
        val raw = str(c, "profiles")
            ?: return mutableListOf(Profile("p1", "Casa", COLORS[0], FIRST_DOCK.toMutableList(), true))
        val a = JSONArray(raw)
        val l = mutableListOf<Profile>()
        for (i in 0 until a.length()) {
            val o = a.getJSONObject(i)
            l += Profile(o.getString("id"), o.getString("name"), o.getInt("color"), strings(o.optJSONArray("pinned")), o.optBoolean("auto", true), o.optString("sort", "az"))
        }
        if (l.isEmpty()) l += Profile("p1", "Casa", COLORS[0], mutableListOf(), true)
        return l
    }

    fun saveProfiles(c: Context, l: List<Profile>) {
        val a = JSONArray()
        l.forEach {
            a.put(JSONObject().put("id", it.id).put("name", it.name).put("color", it.color)
                .put("pinned", JSONArray(it.pinned)).put("auto", it.autoDock).put("sort", it.sort))
        }
        put(c, "profiles", a.toString())
    }

    fun current(c: Context): Profile {
        val l = profiles(c)
        val id = str(c, "profile")
        return l.firstOrNull { it.id == id } ?: l[0]
    }

    fun setCurrent(c: Context, id: String) = put(c, "profile", id)

    fun saveProfile(c: Context, p: Profile) {
        val l = profiles(c)
        val i = l.indexOfFirst { it.id == p.id }
        if (i >= 0) l[i] = p else l += p
        saveProfiles(c, l)
    }

    // ---- folders and hidden apps (shared by every profile) ----

    fun folders(c: Context): MutableList<Folder> {
        val a = JSONArray(str(c, "folders") ?: "[]")
        val l = mutableListOf<Folder>()
        for (i in 0 until a.length()) {
            val o = a.getJSONObject(i)
            l += Folder(o.getString("id"), o.getString("name"), strings(o.optJSONArray("pkgs")))
        }
        return l
    }

    fun saveFolders(c: Context, l: List<Folder>) {
        val a = JSONArray()
        l.forEach { a.put(JSONObject().put("id", it.id).put("name", it.name).put("pkgs", JSONArray(it.pkgs))) }
        put(c, "folders", a.toString())
    }

    /** Puts [pkg] in folder [id], taking it out of any other folder. */
    fun moveToFolder(c: Context, pkg: String, id: String) {
        val l = folders(c)
        l.forEach { it.pkgs.remove(pkg) }
        l.firstOrNull { it.id == id }?.pkgs?.add(pkg)
        saveFolders(c, l)
    }

    fun hidden(c: Context): MutableSet<String> = sp(c).getStringSet("hidden", emptySet())!!.toMutableSet()
    fun saveHidden(c: Context, s: Set<String>) = sp(c).edit().putStringSet("hidden", s).apply()

    // ---- inputs, remote buttons, searches ----

    fun inputNames(c: Context): MutableMap<String, String> {
        val o = JSONObject(str(c, "inputs") ?: "{}")
        val m = mutableMapOf<String, String>()
        o.keys().forEach { m[it] = o.getString(it) }
        return m
    }

    fun saveInputNames(c: Context, m: Map<String, String>) = put(c, "inputs", JSONObject(m).toString())

    fun keyMaps(c: Context): MutableList<KeyMap> {
        val a = JSONArray(str(c, "keys") ?: "[]")
        val l = mutableListOf<KeyMap>()
        for (i in 0 until a.length()) {
            val o = a.getJSONObject(i)
            l += KeyMap(o.getInt("code"), o.optInt("scan"), o.getString("pkg"))
        }
        return l
    }

    fun saveKeyMaps(c: Context, l: List<KeyMap>) {
        val a = JSONArray()
        l.forEach { a.put(JSONObject().put("code", it.keyCode).put("scan", it.scanCode).put("pkg", it.pkg)) }
        put(c, "keys", a.toString())
        Vigia.reload()
    }

    fun searches(c: Context): MutableList<String> = strings(JSONArray(str(c, "searches") ?: "[]"))

    fun addSearch(c: Context, q: String) {
        val l = searches(c)
        l.removeAll { it.equals(q, true) }
        l.add(0, q)
        put(c, "searches", JSONArray(l.take(6)).toString())
    }

    // ---- what SalaTV disabled itself, so «Todo como de fábrica» can undo it ----

    fun disabledBySala(c: Context): Set<String> = sp(c).getStringSet("disabled", emptySet())!!

    fun addDisabled(c: Context, pkgs: Collection<String>) =
        sp(c).edit().putStringSet("disabled", disabledBySala(c) + pkgs).apply()

    fun clearDisabled(c: Context) = sp(c).edit().remove("disabled").apply()
}
