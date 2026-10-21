package com.salatv.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Locale
import java.util.TimeZone
import kotlin.math.min

/** A film or series from TMDB, with the installed apps where it can be watched. */
class Title(
    val type: String,
    val id: Int,
    val name: String,
    val overview: String,
    val backdrop: String?,
    val poster: String?,
    val rating: Double,
    val year: String,
    val pkgs: List<String>,
    /** Title logo (transparent PNG path on TMDB), when there is one. */
    val logo: String? = null,
) {
    val key get() = "t:$type:$id"
    val ratingText get() = if (rating > 0) "★ " + "%.1f".format(rating).replace('.', ',') else ""
    val kind get() = if (type == "tv") "Serie" else "Película"

    fun toJson(): JSONObject = JSONObject().put("type", type).put("id", id).put("name", name).put("overview", overview)
        .put("backdrop", backdrop ?: "").put("poster", poster ?: "").put("rating", rating).put("year", year).put("pkgs", JSONArray(pkgs)).put("logo", logo ?: "")

    companion object {
        fun fromJson(o: JSONObject) = Title(
            o.getString("type"), o.getInt("id"), o.getString("name"), o.optString("overview"),
            o.optString("backdrop").ifEmpty { null }, o.optString("poster").ifEmpty { null },
            o.optDouble("rating", 0.0), o.optString("year"),
            o.optJSONArray("pkgs")?.let { a -> (0 until a.length()).map { a.getString(it) } } ?: emptyList(),
            o.optString("logo").ifEmpty { null },
        )
    }
}

class TitleDetails(val title: Title, val genres: String, val extra: String, val trailer: String?,
                   val seasons: List<Pair<Int, String>>, val cast: List<Pair<String, String?>>, val similar: List<Title>)

class Episode(val number: Int, val name: String, val overview: String, val still: String?, val date: String)

class Shelf(val id: String, val label: String, val items: List<Title>)

/**
 * TMDB (themoviedb.org): what is trending this week, where each title streams in the TV's country
 * (JustWatch data), details and search. Uses the read token from local.properties.
 */
object Tmdb {
    private const val API = "https://api.themoviedb.org/3"
    const val IMG = "https://image.tmdb.org/t/p/"
    private const val LANG = "es-MX"

    /** TMDB provider ids to the Android TV apps that play them. Amazon channels play inside Prime Video. */
    private val PROVIDERS: Map<Int, List<String>> = mapOf(
        8 to listOf("com.netflix.ninja"),
        1796 to listOf("com.netflix.ninja"),
        9 to listOf("com.amazon.firebat", "com.amazon.avod.thirdpartyclient"),
        119 to listOf("com.amazon.firebat", "com.amazon.avod.thirdpartyclient"),
        2100 to listOf("com.amazon.firebat", "com.amazon.avod.thirdpartyclient"),
        1968 to listOf("com.crunchyroll.crunchyroid", "com.amazon.firebat"),
        1889 to listOf("com.amazon.firebat"),
        2243 to listOf("com.amazon.firebat"),
        283 to listOf("com.crunchyroll.crunchyroid"),
        337 to listOf("com.disney.disneyplus"),
        1899 to listOf("com.wbd.stream", "com.hbo.hbonow"),
        384 to listOf("com.wbd.stream", "com.hbo.hbonow"),
        350 to listOf("com.apple.atve.amazon.appletv", "com.apple.atve.androidtv.appletv"),
        531 to listOf("com.cbs.ott", "com.cbs.ca"),
    )

    val REGIONS = listOf("DO" to "República Dominicana", "MX" to "México", "US" to "Estados Unidos", "CO" to "Colombia",
        "ES" to "España", "AR" to "Argentina", "CL" to "Chile", "PE" to "Perú", "PR" to "Puerto Rico")

    fun token(c: Context) = c.getString(R.string.tmdb_token).takeIf { it.isNotBlank() }
    fun enabled(c: Context) = token(c) != null && Prefs.bool(c, "internet", true)

    fun region(c: Context): String = Prefs.str(c, "region") ?: when (TimeZone.getDefault().id) {
        "America/Santo_Domingo" -> "DO"
        "America/Mexico_City", "America/Monterrey", "America/Cancun", "America/Tijuana" -> "MX"
        "America/Bogota" -> "CO"
        "Europe/Madrid", "Atlantic/Canary" -> "ES"
        "America/Argentina/Buenos_Aires" -> "AR"
        "America/Santiago" -> "CL"
        "America/Lima" -> "PE"
        "America/Puerto_Rico" -> "PR"
        else -> Locale.getDefault().country.ifEmpty { "US" }
    }

    private fun get(c: Context, path: String): JSONObject {
        val t = token(c) ?: throw IllegalStateException("Falta la clave de TMDB")
        val sep = if (path.contains('?')) "&" else "?"
        return JSONObject(Http.get("$API$path${sep}language=$LANG", mapOf("Authorization" to "Bearer $t")))
    }

    fun installed(c: Context, pkg: String) = try { c.packageManager.getPackageInfo(pkg, 0); true } catch (e: Exception) { false }

    /** The streaming apps on this TV that TMDB knows, one per service. */
    fun streamingApps(c: Context): List<String> = PROVIDERS.values.flatten().distinct().filter { installed(c, it) }.distinctBy { Services.name(it) }

    /** A cheap call to check that the key works. Network. */
    fun ping(c: Context) { get(c, "/configuration") }

    /** Installed apps where [type]/[id] streams in the TV's country, in TMDB's order. */
    fun apps(c: Context, type: String, id: Int): List<String> {
        val res = get(c, "/$type/$id/watch/providers").optJSONObject("results") ?: return emptyList()
        val reg = res.optJSONObject(region(c)) ?: return emptyList()
        val out = LinkedHashSet<String>()
        for (kind in listOf("flatrate", "free", "ads")) {
            val a = reg.optJSONArray(kind) ?: continue
            for (i in 0 until a.length()) {
                PROVIDERS[a.getJSONObject(i).optInt("provider_id")]?.firstOrNull { installed(c, it) }?.let { out += it }
            }
        }
        return out.toList()
    }

    private fun parse(o: JSONObject, typeHint: String? = null, pkgs: List<String> = emptyList()): Title? {
        val type = o.optString("media_type").ifEmpty { typeHint ?: return null }
        if (type != "tv" && type != "movie") return null
        val date = o.optString(if (type == "tv") "first_air_date" else "release_date")
        return Title(type, o.getInt("id"), o.optString(if (type == "tv") "name" else "title"), o.optString("overview"),
            o.optString("backdrop_path").ifEmpty { null }, o.optString("poster_path").ifEmpty { null },
            o.optDouble("vote_average", 0.0), date.take(4), pkgs)
    }

    private fun cacheFile(c: Context) = File(c.filesDir, "trending.json")

    /** Last saved trending list, for an instant start. */
    fun cachedTrending(c: Context): List<Title> = try {
        val a = JSONArray(cacheFile(c).readText())
        (0 until a.length()).map { Title.fromJson(a.getJSONObject(it)) }
    } catch (e: Exception) { emptyList() }

    /**
     * This week's trending series and films that stream in one of the installed apps (up to 8).
     * Network: about 25 calls, so it is refreshed every 6 hours. Off the main thread.
     */
    fun refreshTrending(c: Context, force: Boolean = false): List<Title> {
        if (!enabled(c)) return emptyList()
        val f = cacheFile(c)
        if (!force && f.exists() && System.currentTimeMillis() - f.lastModified() < 6 * 3600_000L && Prefs.str(c, "trendRegion") == region(c)) return cachedTrending(c)
        val out = mutableListOf<Title>()
        for (page in 1..3) {
            val a = get(c, "/trending/all/week?page=$page").optJSONArray("results") ?: break
            for (i in 0 until a.length()) {
                val t = parse(a.getJSONObject(i)) ?: continue
                if (t.backdrop == null) continue
                val apps = try { apps(c, t.type, t.id) } catch (e: Exception) { emptyList() }
                if (apps.isNotEmpty()) out += Title(t.type, t.id, t.name, t.overview, t.backdrop, t.poster, t.rating, t.year, apps, try { logo(c, t.type, t.id) } catch (e: Exception) { null })
                if (out.size >= 12) break
            }
            if (out.size >= 12) break
        }
        f.writeText(JSONArray(out.map { it.toJson() }).toString())
        Prefs.put(c, "trendRegion", region(c))
        return out
    }

    fun details(c: Context, type: String, id: Int): TitleDetails {
        val o = get(c, "/$type/$id?append_to_response=videos")
        val t = parse(o, type, apps(c, type, id))!!
        val genres = o.optJSONArray("genres")?.let { a -> (0 until a.length()).joinToString(", ") { a.getJSONObject(it).optString("name") } } ?: ""
        val extra = if (type == "tv") {
            val seasons = o.optInt("number_of_seasons")
            val last = o.optJSONObject("last_episode_to_air")
            listOfNotNull(
                if (seasons > 0) (if (seasons == 1) "1 temporada" else "$seasons temporadas") else null,
                last?.let { "último episodio: T${it.optInt("season_number")} E${it.optInt("episode_number")} (${it.optString("air_date")})" },
            ).joinToString(" · ")
        } else {
            val min = o.optInt("runtime")
            if (min > 0) "${min / 60} h ${min % 60} min" else ""
        }
        val vids = o.optJSONObject("videos")?.optJSONArray("results")
        var trailer: String? = null
        if (vids != null) for (i in 0 until vids.length()) {
            val v = vids.getJSONObject(i)
            if (v.optString("site") == "YouTube" && v.optString("type") in setOf("Trailer", "Teaser")) { trailer = v.optString("key"); break }
        }
        if (trailer == null) trailer = try {
            val en = JSONObject(Http.get("$API/$type/$id/videos", mapOf("Authorization" to "Bearer ${token(c)}"))).optJSONArray("results")
            (0 until (en?.length() ?: 0)).map { en!!.getJSONObject(it) }.firstOrNull { it.optString("site") == "YouTube" && it.optString("type") == "Trailer" }?.optString("key")
        } catch (e: Exception) { null }
        val seasons = (o.optJSONArray("seasons") ?: JSONArray()).let { arr -> (0 until arr.length()).map { arr.getJSONObject(it) } }
            .filter { it.optInt("season_number") > 0 }.map { it.optInt("season_number") to it.optString("name") }
        val credits = try { get(c, "/$type/$id/credits").optJSONArray("cast") } catch (e: Exception) { null }
        val cast = (0 until min(10, credits?.length() ?: 0)).map { credits!!.getJSONObject(it) }.map { it.optString("name") to it.optString("profile_path").ifEmpty { null } }
        val similar = try { list(c, "/$type/$id/recommendations", type) } catch (e: Exception) { emptyList() }
        val withLogo = Title(t.type, t.id, t.name, t.overview, t.backdrop, t.poster, t.rating, t.year, t.pkgs, try { logo(c, type, id) } catch (e: Exception) { null })
        return TitleDetails(withLogo, genres, extra, trailer, seasons, cast, similar)
    }

    /** Episodes of one season, with their pictures. */
    fun season(c: Context, id: Int, n: Int): List<Episode> {
        val a = get(c, "/tv/$id/season/$n").optJSONArray("episodes") ?: return emptyList()
        return (0 until a.length()).map { a.getJSONObject(it) }.map {
            Episode(it.optInt("episode_number"), it.optString("name"), it.optString("overview"), it.optString("still_path").ifEmpty { null }, it.optString("air_date"))
        }
    }

    private val logos = HashMap<String, String>()
    private val noLogo = HashSet<String>()

    /** The title's logo: Spanish first, then English, then any. Cached for the session. */
    fun logo(c: Context, type: String, id: Int): String? {
        val k = "$type$id"
        synchronized(logos) { logos[k]?.let { return it }; if (k in noLogo) return null }
        val t = token(c) ?: return null
        val a = JSONObject(Http.get("$API/$type/$id/images?include_image_language=es,en,null", mapOf("Authorization" to "Bearer $t"))).optJSONArray("logos") ?: JSONArray()
        val all = (0 until a.length()).map { a.getJSONObject(it) }
        val pick = all.firstOrNull { it.optString("iso_639_1") == "es" } ?: all.firstOrNull { it.optString("iso_639_1") == "en" } ?: all.firstOrNull()
        val path = pick?.optString("file_path")?.ifEmpty { null }
        synchronized(logos) { if (path != null) logos[k] = path else noLogo += k }
        return path
    }

    private fun list(c: Context, path: String, type: String): List<Title> {
        val a = get(c, path).optJSONArray("results") ?: return emptyList()
        return (0 until a.length()).mapNotNull { parse(a.getJSONObject(it), type) }.filter { it.poster != null }
    }

    /** TMDB provider ids of the installed streaming apps. */
    fun myProviders(c: Context): List<Int> = listOf(8, 119, 283, 337, 1899, 350, 531).filter { id -> PROVIDERS[id]?.any { installed(c, it) } == true }

    private fun discover(c: Context, type: String, extra: String, providers: List<Int> = myProviders(c)): List<Title> {
        val prov = if (providers.isEmpty()) "" else "&with_watch_providers=${providers.joinToString("|")}&watch_region=${region(c)}&with_watch_monetization_types=flatrate|free|ads"
        return list(c, "/discover/$type?include_adult=false$prov&$extra", type)
    }

    /** The shelves of one «Explorar» tab: movie, tv or anime. Cached 6 hours. */
    fun shelves(c: Context, tab: String, force: Boolean = false): List<Shelf> {
        val f = File(c.filesDir, "explore-$tab-${region(c)}.json")
        if (!force && f.exists() && System.currentTimeMillis() - f.lastModified() < 6 * 3600_000L) return cachedShelves(f)
        val today = java.text.SimpleDateFormat("yyyy-MM-dd", Locale.US).format(java.util.Date())
        val svc = mapOf(8 to "Netflix", 119 to "Prime Video", 337 to "Disney+", 283 to "Crunchyroll", 1899 to "Max")
        val mine = myProviders(c)
        val defs = mutableListOf<Triple<String, String, () -> List<Title>>>()
        when (tab) {
            "movie" -> {
                defs += Triple("pop", "Lo más popular en tus apps") { discover(c, "movie", "sort_by=popularity.desc") }
                defs += Triple("new", "Estrenos en tus apps") { discover(c, "movie", "sort_by=primary_release_date.desc&primary_release_date.lte=$today&vote_count.gte=15") }
                defs += Triple("top", "Las mejor valoradas") { discover(c, "movie", "sort_by=vote_average.desc&vote_count.gte=800") }
                mine.filter { it in svc && it != 283 }.forEach { p -> defs += Triple("p$p", "En ${svc[p]}") { discover(c, "movie", "sort_by=popularity.desc", listOf(p)) } }
                defs += Triple("short", "Para una noche corta: menos de 100 min") { discover(c, "movie", "sort_by=popularity.desc&with_runtime.lte=100&with_runtime.gte=60") }
                listOf(28 to "Acción", 35 to "Comedia", 27 to "Terror", 878 to "Ciencia ficción", 53 to "Suspense", 10749 to "Romance", 10751 to "En familia", 80 to "Crimen", 99 to "Documentales")
                    .forEach { (g, n) -> defs += Triple("g$g", n) { discover(c, "movie", "sort_by=popularity.desc&with_genres=$g") } }
            }
            "tv" -> {
                defs += Triple("pop", "Las series más populares en tus apps") { discover(c, "tv", "sort_by=popularity.desc&without_genres=16") }
                defs += Triple("new", "Series nuevas") { discover(c, "tv", "sort_by=first_air_date.desc&first_air_date.lte=$today&vote_count.gte=10&without_genres=16") }
                defs += Triple("top", "Las mejor valoradas") { discover(c, "tv", "sort_by=vote_average.desc&vote_count.gte=500&without_genres=16") }
                mine.filter { it in svc && it != 283 }.forEach { p -> defs += Triple("p$p", "En ${svc[p]}") { discover(c, "tv", "sort_by=popularity.desc&without_genres=16", listOf(p)) } }
                listOf(18 to "Drama", 35 to "Comedia", 80 to "Crimen", 10765 to "Ciencia ficción y fantasía", 10759 to "Acción y aventura", 9648 to "Misterio", 10764 to "Reality", 99 to "Documentales", 10762 to "Para niños")
                    .forEach { (g, n) -> defs += Triple("g$g", n) { discover(c, "tv", "sort_by=popularity.desc&with_genres=$g&without_genres=16") } }
            }
            else -> {
                val ja = "with_genres=16&with_original_language=ja"
                defs += Triple("pop", "El anime más popular") { discover(c, "tv", "sort_by=popularity.desc&$ja") }
                defs += Triple("new", "Anime nuevo") { discover(c, "tv", "sort_by=first_air_date.desc&first_air_date.lte=$today&vote_count.gte=5&$ja") }
                defs += Triple("top", "El mejor valorado") { discover(c, "tv", "sort_by=vote_average.desc&vote_count.gte=300&$ja") }
                if (283 in mine) defs += Triple("cr", "En Crunchyroll") { discover(c, "tv", "sort_by=popularity.desc&$ja", listOf(283)) }
                if (8 in mine) defs += Triple("nf", "Anime en Netflix") { discover(c, "tv", "sort_by=popularity.desc&$ja", listOf(8)) }
                defs += Triple("mov", "Películas de anime") { discover(c, "movie", "sort_by=popularity.desc&$ja") }
                listOf("10759" to "Acción y aventura", "35" to "Comedia", "10765" to "Fantasía", "18" to "Drama", "9648" to "Misterio")
                    .forEach { (g, n) -> defs += Triple("g$g", n) { discover(c, "tv", "sort_by=popularity.desc&with_genres=16,$g&with_original_language=ja") } }
            }
        }
        val out = defs.mapNotNull { (id, label, fetch) -> try { Shelf(id, label, fetch().take(20)) } catch (e: Exception) { null } }.filter { it.items.isNotEmpty() }
        f.writeText(JSONArray(out.map { s -> JSONObject().put("id", s.id).put("label", s.label).put("items", JSONArray(s.items.map { it.toJson() })) }).toString())
        return out
    }

    fun cachedShelves(c: Context, tab: String) = cachedShelves(File(c.filesDir, "explore-$tab-${region(c)}.json"))

    private fun cachedShelves(f: File): List<Shelf> = try {
        val a = JSONArray(f.readText())
        (0 until a.length()).map { a.getJSONObject(it) }.map { o ->
            val items = o.getJSONArray("items")
            Shelf(o.getString("id"), o.getString("label"), (0 until items.length()).map { Title.fromJson(items.getJSONObject(it)) })
        }
    } catch (e: Exception) { emptyList() }

    /** «Porque viste…»: TMDB recommendations for a series watched, found by its name. */
    fun because(c: Context, name: String): List<Title> {
        val a = get(c, "/search/tv?query=${Http.enc(name)}").optJSONArray("results") ?: return emptyList()
        val id = (0 until a.length()).map { a.getJSONObject(it) }.firstOrNull()?.optInt("id") ?: return emptyList()
        return discoverIn(c, list(c, "/tv/$id/recommendations", "tv")).take(12)
    }

    /** Keeps the titles that stream in an installed app, with those apps filled in. */
    private fun discoverIn(c: Context, l: List<Title>): List<Title> = l.take(16).mapNotNull { t ->
        val apps = try { apps(c, t.type, t.id) } catch (e: Exception) { emptyList() }
        if (apps.isEmpty()) null else Title(t.type, t.id, t.name, t.overview, t.backdrop, t.poster, t.rating, t.year, apps)
    }


    /** Series and films matching [q], each with the installed apps that have it. */
    fun search(c: Context, q: String): List<Title> {
        val a = get(c, "/search/multi?query=${Http.enc(q)}").optJSONArray("results") ?: return emptyList()
        val out = mutableListOf<Title>()
        for (i in 0 until a.length()) {
            val t = parse(a.getJSONObject(i)) ?: continue
            val apps = try { apps(c, t.type, t.id) } catch (e: Exception) { emptyList() }
            out += Title(t.type, t.id, t.name, t.overview, t.backdrop, t.poster, t.rating, t.year, apps)
            if (out.size >= 12) break
        }
        return out
    }
}
