package com.salatv.app

import android.app.SearchManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** One card in «Seguir viendo» or «Anime de la temporada»: what, where, how far and how to get back. */
class Show(
    val key: String,
    val pkg: String,
    val title: String,
    val sub: String,
    /** A web address, or an absolute path for pictures saved by SalaTV. */
    val image: String?,
    val progress: Float,
    val minutesLeft: Int,
    val at: Long,
    val isNew: Boolean,
    val url: String?,
    val playheadSec: Long,
    val seriesUrl: String? = null,
) {
    fun toJson(): JSONObject = JSONObject().put("key", key).put("pkg", pkg).put("title", title).put("sub", sub).put("image", image ?: "")
        .put("progress", progress.toDouble()).put("left", minutesLeft).put("at", at).put("new", isNew).put("url", url ?: "")
        .put("ph", playheadSec).put("series", seriesUrl ?: "")

    companion object {
        fun fromJson(o: JSONObject) = Show(o.getString("key"), o.getString("pkg"), o.getString("title"), o.optString("sub"),
            o.optString("image").ifEmpty { null }, o.optDouble("progress").toFloat(), o.optInt("left", -1), o.optLong("at"),
            o.optBoolean("new"), o.optString("url").ifEmpty { null }, o.optLong("ph"), o.optString("series").ifEmpty { null })
    }
}

/** The service badge on cards: short name and brand colour. */
object Services {
    private val KNOWN = mapOf(
        "com.netflix.ninja" to Triple("NETFLIX", 0xFFE50914.toInt(), 0xFFFFFFFF.toInt()),
        "com.crunchyroll.crunchyroid" to Triple("Crunchyroll", 0xFFF47521.toInt(), 0xFFFFFFFF.toInt()),
        "com.amazon.firebat" to Triple("prime video", 0xFF1AA7EC.toInt(), 0xFF06121A.toInt()),
        "com.amazon.avod.thirdpartyclient" to Triple("prime video", 0xFF1AA7EC.toInt(), 0xFF06121A.toInt()),
        "com.disney.disneyplus" to Triple("Disney+", 0xFF1B4FD8.toInt(), 0xFFFFFFFF.toInt()),
        "com.wbd.stream" to Triple("max", 0xFF002BE7.toInt(), 0xFFFFFFFF.toInt()),
        "org.smarttube.stable" to Triple("SmartTube", 0xFFFF3B3B.toInt(), 0xFFFFFFFF.toInt()),
        "com.stremio.one" to Triple("Stremio", 0xFF7B5BD6.toInt(), 0xFFFFFFFF.toInt()),
        "com.apple.atve.amazon.appletv" to Triple("Apple TV", 0xFF1C1C1E.toInt(), 0xFFFFFFFF.toInt()),
    )

    fun badge(pkg: String): Triple<String, Int, Int> = KNOWN[pkg] ?: AppRepo.byPkg(pkg)?.let { Triple(it.label, Ui.shade(it.color, 0.7f), 0xFFFFFFFF.toInt()) }
        ?: Triple(pkg.substringAfterLast('.'), 0xFF3A3F4C.toInt(), 0xFFFFFFFF.toInt())

    fun name(pkg: String) = when (pkg) {
        "com.netflix.ninja" -> "Netflix"
        "com.amazon.firebat", "com.amazon.avod.thirdpartyclient" -> "Prime Video"
        else -> badge(pkg).first
    }
}

/** «Seguir viendo» for the current profile: Crunchyroll's own list plus what was played on this TV. */
object Feed {
    private fun file(c: Context) = File(c.filesDir, "feed-" + Prefs.current(c).id + ".json")

    fun cached(c: Context): List<Show> = try {
        val a = JSONArray(file(c).readText())
        (0 until a.length()).map { Show.fromJson(a.getJSONObject(it)) }
    } catch (e: Exception) { local(c) }

    /** Titles played on this TV, from the media sessions, as cards. */
    private fun local(c: Context): List<Show> = Watching.all(c).map { w ->
        Show("m:" + w.key, w.pkg, w.title, w.sub, w.art?.let { File(File(c.filesDir, "art"), it).path }, w.progress, w.minutesLeft,
            w.at, false, LinkRules.url(w.pkg, w.mediaId, w.uri, w.pos / 1000), w.pos / 1000)
    }

    /** Merges both sources, newest first. Network; off the main thread. */
    fun refresh(c: Context): List<Show> {
        val hidden = Prefs.set(c, "hiddenShows")
        val out = mutableListOf<Show>()
        var crOk = false
        if (Crunchy.linked(c) && Prefs.bool(c, "internet", true)) {
            try { out += Crunchy.continueWatching(c); crOk = true } catch (e: Exception) {
                Registro.add(c, "Crunchyroll", "No se pudo leer Seguir viendo: ${e.message ?: e.javaClass.simpleName}")
            }
        }
        // With the account connected, its list is the better source for Crunchyroll.
        out += local(c).filter { !(crOk && it.pkg == Crunchy.PKG) }
        val list = out.filter { it.key !in hidden }.sortedByDescending { it.at }.take(14)
        file(c).writeText(JSONArray(list.map { it.toJson() }).toString())
        return list
    }

    fun hide(c: Context, s: Show) {
        Prefs.put(c, "hiddenShows", Prefs.set(c, "hiddenShows") + s.key)
        if (s.key.startsWith("m:")) Watching.remove(c, s.key.removePrefix("m:"))
    }

    /** New episodes not announced yet; marks them announced. */
    fun fresh(c: Context, list: List<Show>): List<Show> {
        val told = Prefs.set(c, "announced")
        val now = list.filter { it.isNew && (it.key + it.sub) !in told }
        if (now.isNotEmpty()) Prefs.put(c, "announced", told + now.map { it.key + it.sub })
        return now
    }

    private fun seasonFile(c: Context) = File(c.filesDir, "season.json")

    fun cachedSeason(c: Context): Pair<String, List<Show>> = try {
        val o = JSONObject(seasonFile(c).readText())
        val a = o.getJSONArray("items")
        o.getString("name") to (0 until a.length()).map { Show.fromJson(a.getJSONObject(it)) }
    } catch (e: Exception) { "" to emptyList() }

    fun refreshSeason(c: Context): Pair<String, List<Show>> {
        if (!Crunchy.linked(c)) return "" to emptyList()
        val f = seasonFile(c)
        if (f.exists() && System.currentTimeMillis() - f.lastModified() < 12 * 3600_000L) return cachedSeason(c)
        val (name, items) = Crunchy.seasonal(c)
        f.writeText(JSONObject().put("name", name).put("items", JSONArray(items.map { it.toJson() })).toString())
        return name to items
    }

    /** Resumes a card: the exact episode when the app accepts the link, else the app and «play». */
    fun open(c: Context, s: Show) {
        val link = s.url
        if (link != null && Sys.start(c, s.pkg, Intent(Intent.ACTION_VIEW, Uri.parse(link)).setPackage(s.pkg))) return
        val series = s.seriesUrl
        if (series != null && Sys.start(c, s.pkg, Intent(Intent.ACTION_VIEW, Uri.parse(series)).setPackage(s.pkg))) return
        if (Sys.start(c, s.pkg, null)) {
            val mw = MediaWatcher.instance
            Bg.main.postDelayed({ if (mw?.play(s.pkg) != true) Ui.toast(c, "Sigue «${s.title}» en ${Services.name(s.pkg)}") }, 2500)
            Registro.add(c, "Seguir viendo", "${s.pkg} no aceptó el enlace al capítulo; se abrió la app")
        }
    }
}

/** «Mi lista»: series and films to watch, per profile, from the carousel, the title page or the search. */
object MyList {
    private fun key(c: Context) = "mylist:" + Prefs.current(c).id

    fun all(c: Context): List<Title> = try {
        val a = JSONArray(Prefs.str(c, key(c)) ?: "[]")
        (0 until a.length()).map { Title.fromJson(a.getJSONObject(it)) }
    } catch (e: Exception) { emptyList() }

    fun has(c: Context, t: Title) = all(c).any { it.key == t.key }

    fun toggle(c: Context, t: Title): Boolean {
        val l = all(c).toMutableList()
        val had = l.removeAll { it.key == t.key }
        if (!had) l.add(0, t)
        Prefs.put(c, key(c), JSONArray(l.map { it.toJson() }).toString())
        return !had
    }

    /** Re-checks where each title streams (it changes), at most once a day. */
    fun refresh(c: Context) {
        if (!Tmdb.enabled(c) || System.currentTimeMillis() - Prefs.long(c, key(c) + "At") < 86_400_000L) return
        val l = all(c).map { t -> try { Title(t.type, t.id, t.name, t.overview, t.backdrop, t.poster, t.rating, t.year, Tmdb.apps(c, t.type, t.id)) } catch (e: Exception) { t } }
        Prefs.put(c, key(c), JSONArray(l.map { it.toJson() }).toString())
        Prefs.put(c, key(c) + "At", System.currentTimeMillis())
    }
}

/** How to open a search for a title inside each app, and how to open a TMDB title in an app. */
object AppSearch {
    private fun yt(q: String) = Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com/results?search_query=" + Uri.encode(q)))

    val KNOWN: Map<String, (String) -> Intent> = mapOf(
        "com.netflix.ninja" to { q -> Intent(Intent.ACTION_VIEW, Uri.parse("https://www.netflix.com/search?q=" + Uri.encode(q))) },
        "org.smarttube.stable" to ::yt,
        "org.smarttube.beta" to ::yt,
        "com.amazon.firetv.youtube" to ::yt,
        "com.stremio.one" to { q -> Intent(Intent.ACTION_VIEW, Uri.parse("stremio:///search?search=" + Uri.encode(q))) },
        "com.phlox.tvwebbrowser" to { q -> Intent(Intent.ACTION_VIEW, Uri.parse("https://duckduckgo.com/?q=" + Uri.encode(q))) },
        "com.amazon.cloud9" to { q -> Intent(Intent.ACTION_VIEW, Uri.parse("https://duckduckgo.com/?q=" + Uri.encode(q))) },
        "com.crunchyroll.crunchyroid" to { q -> Intent(Intent.ACTION_VIEW, Uri.parse("https://www.crunchyroll.com/search?q=" + Uri.encode(q))) },
    )

    fun intent(pkg: String, q: String): Intent = (KNOWN[pkg]?.invoke(q) ?: Intent(Intent.ACTION_SEARCH).putExtra(SearchManager.QUERY, q)).setPackage(pkg)

    /** Opens [q] inside [pkg]; falls back to opening the app. */
    fun go(c: Context, pkg: String, q: String) {
        if (Sys.start(c, pkg, intent(pkg, q))) return
        if (Sys.start(c, pkg, null)) Ui.toast(c, "Busca «$q» en ${Services.name(pkg)}")
        else Ui.toast(c, "No se pudo abrir ${Services.name(pkg)}")
    }

    /** A TMDB title in an app: Crunchyroll straight to the series when found, the rest through their search. */
    fun openTitle(c: Context, pkg: String, t: Title) {
        if (pkg == Crunchy.PKG && Crunchy.linked(c)) {
            Bg.run({ Crunchy.findSeries(c, t.name) }) { r ->
                val id = r.getOrNull()
                if (id == null || !Sys.start(c, pkg, Intent(Intent.ACTION_VIEW, Uri.parse("https://www.crunchyroll.com/series/$id")).setPackage(pkg))) go(c, pkg, t.name)
            }
            return
        }
        go(c, pkg, t.name)
    }
}

/**
 * Keys from the PC: tools/claves.ps1 pushes a small text file into SalaTV's folder; it is read once and
 * deleted at once, so the Crunchyroll password never stays on the TV.
 */
object Claves {
    fun import(c: Context) {
        val f = File(c.getExternalFilesDir(null) ?: return, "claves.txt")
        if (!f.exists()) return
        val kv = f.readLines().mapNotNull { l -> l.split('=', limit = 2).takeIf { it.size == 2 }?.let { it[0].trim() to it[1].trim() } }.toMap()
        f.delete()
        kv["region"]?.takeIf { it.length == 2 }?.let { Prefs.put(c, "region", it.uppercase()) }
        val email = kv["crunchyroll_email"]; val pass = kv["crunchyroll_password"]
        if (!email.isNullOrEmpty() && !pass.isNullOrEmpty()) {
            Bg.run({ Crunchy.login(c, email, pass) }) { r ->
                r.onSuccess { Ui.toast(c, it) }.onFailure {
                    Ui.toast(c, "Crunchyroll no aceptó el inicio de sesión")
                    Registro.add(c, "Crunchyroll", "Inicio de sesión: ${it.message}")
                }
            }
        }
    }
}
