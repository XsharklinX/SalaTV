package com.salatv.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Locale
import java.util.TimeZone

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
) {
    val key get() = "t:$type:$id"
    val ratingText get() = if (rating > 0) "★ " + "%.1f".format(rating).replace('.', ',') else ""
    val kind get() = if (type == "tv") "Serie" else "Película"

    fun toJson(): JSONObject = JSONObject().put("type", type).put("id", id).put("name", name).put("overview", overview)
        .put("backdrop", backdrop ?: "").put("poster", poster ?: "").put("rating", rating).put("year", year).put("pkgs", JSONArray(pkgs))

    companion object {
        fun fromJson(o: JSONObject) = Title(
            o.getString("type"), o.getInt("id"), o.getString("name"), o.optString("overview"),
            o.optString("backdrop").ifEmpty { null }, o.optString("poster").ifEmpty { null },
            o.optDouble("rating", 0.0), o.optString("year"),
            o.optJSONArray("pkgs")?.let { a -> (0 until a.length()).map { a.getString(it) } } ?: emptyList(),
        )
    }
}

class TitleDetails(val title: Title, val genres: String, val extra: String, val trailer: String?)

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

    private fun installed(c: Context, pkg: String) = try { c.packageManager.getPackageInfo(pkg, 0); true } catch (e: Exception) { false }

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
        for (page in 1..2) {
            val a = get(c, "/trending/all/week?page=$page").optJSONArray("results") ?: break
            for (i in 0 until a.length()) {
                val t = parse(a.getJSONObject(i)) ?: continue
                if (t.backdrop == null) continue
                val apps = try { apps(c, t.type, t.id) } catch (e: Exception) { emptyList() }
                if (apps.isNotEmpty()) out += Title(t.type, t.id, t.name, t.overview, t.backdrop, t.poster, t.rating, t.year, apps)
                if (out.size >= 8) break
            }
            if (out.size >= 8) break
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
        return TitleDetails(t, genres, extra, trailer)
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
