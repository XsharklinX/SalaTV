package com.salatv.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.util.Calendar
import java.util.UUID

/**
 * Crunchyroll through the API its own apps use. Not official: it signs in as the Android TV app
 * (credentials kept up to date by the crunchyroll-rs project) and may stop working when Crunchyroll
 * changes it. Only the refresh token is stored, never the password.
 */
object Crunchy {
    const val PKG = "com.crunchyroll.crunchyroid"
    private const val BASE = "https://www.crunchyroll.com"
    private const val BASIC = "d2Q0cXluNGVxYWJ0Z2Y3cW50cmQ6UFZkY3NwN3V0anM2dEQ3dkhiWHBrX1VVaVdEV18xcjI="
    private const val UA = "Crunchyroll/ANDROIDTV/3.72.1_22361 (Android 13.0; en-US; TCL-S5400AF Build/TP1A.220624.014)"
    private const val LOCALE = "es-419"

    @Volatile private var access: String? = null
    @Volatile private var accessUntil = 0L
    @Volatile private var accessProfile: String? = null

    class NotLinked : Exception("Crunchyroll no está conectado")

    fun linked(c: Context) = Prefs.str(c, "crRefresh") != null

    private fun deviceId(c: Context) = Prefs.str(c, "crDevice") ?: UUID.randomUUID().toString().also { Prefs.put(c, "crDevice", it) }

    private fun token(c: Context, fields: List<Pair<String, String>>): JSONObject {
        val all = fields + listOf("scope" to "offline_access", "device_id" to deviceId(c), "device_type" to "com.salatv.app", "device_name" to "SalaTV")
        val body = Http.form(*all.toTypedArray())
        val o = JSONObject(Http.post("$BASE/auth/v1/token", body, "application/x-www-form-urlencoded",
            mapOf("Authorization" to "Basic $BASIC", "User-Agent" to UA, "ETP-Anonymous-ID" to deviceId(c))))
        access = o.getString("access_token")
        accessUntil = System.currentTimeMillis() + (o.optLong("expires_in", 300) - 30) * 1000
        o.optString("refresh_token").takeIf { it.isNotEmpty() }?.let { Prefs.put(c, "crRefresh", it) }
        o.optString("account_id").takeIf { it.isNotEmpty() }?.let { Prefs.put(c, "crAccount", it) }
        return o
    }

    /** Signs in once with email and password; keeps only the refresh token. */
    fun login(c: Context, email: String, password: String): String {
        token(c, listOf("username" to email, "password" to password, "grant_type" to "password"))
        accessProfile = null
        Prefs.put(c, "crEmail", email)
        return "Crunchyroll conectado"
    }

    fun logout(c: Context) {
        Prefs.sp(c).edit().remove("crRefresh").remove("crAccount").remove("crEmail").apply()
        access = null
    }

    /** The Crunchyroll profile chosen for the current SalaTV profile, or null for the account's default. */
    fun profileFor(c: Context): String? = Prefs.str(c, "crProfile:" + Prefs.current(c).id)

    private fun bearer(c: Context): String {
        val prof = profileFor(c)
        val a = access
        if (a != null && System.currentTimeMillis() < accessUntil && accessProfile == prof) return a
        val rt = Prefs.str(c, "crRefresh") ?: throw NotLinked()
        if (prof != null) token(c, listOf("refresh_token" to rt, "grant_type" to "refresh_token_profile_id", "profile_id" to prof))
        else token(c, listOf("refresh_token" to rt, "grant_type" to "refresh_token"))
        accessProfile = prof
        return access!!
    }

    private fun api(c: Context, path: String): JSONObject {
        val sep = if (path.contains('?')) "&" else "?"
        return JSONObject(Http.get("$BASE$path${sep}locale=$LOCALE", mapOf("Authorization" to "Bearer ${bearer(c)}", "User-Agent" to UA)))
    }

    /** (profile id, name) of every profile in the account. */
    fun profiles(c: Context): List<Pair<String, String>> {
        val a = api(c, "/accounts/v1/me/multiprofile").optJSONArray("profiles") ?: return emptyList()
        return (0 until a.length()).map { a.getJSONObject(it) }.map { it.optString("profile_id") to it.optString("profile_name") }
    }

    /** The picture closest to [width] from Crunchyroll's nested image lists. */
    private fun image(images: JSONObject?, kind: String, width: Int = 480): String? {
        val outer = images?.optJSONArray(kind) ?: return null
        val inner = outer.optJSONArray(0) ?: return null
        var best: JSONObject? = null
        for (i in 0 until inner.length()) {
            val im = inner.getJSONObject(i)
            if (best == null || Math.abs(im.optInt("width") - width) < Math.abs(best.optInt("width") - width)) best = im
        }
        return best?.optString("source")
    }

    private fun time(s: String?): Long = try { Instant.parse(s).toEpochMilli() } catch (e: Exception) { 0L }

    private fun label(md: JSONObject?, title: String): String {
        if (md == null) return title
        val s = md.optInt("season_number"); val e = md.optString("episode").ifEmpty { md.optInt("episode_number").toString() }
        return "T$s · E$e" + if (title.isNotBlank()) " «$title»" else ""
    }

    private fun show(panel: JSONObject, playheadSec: Long, at: Long, next: Boolean): Show {
        val md = panel.optJSONObject("episode_metadata") ?: panel.optJSONObject("movie_metadata")
        val epId = panel.getString("id")
        val series = md?.optString("series_title")?.ifEmpty { null } ?: md?.optString("movie_listing_title")?.ifEmpty { null } ?: panel.optString("title")
        val durSec = (md?.optLong("duration_ms") ?: 0L) / 1000
        val avail = time(md?.optString("availability_starts")).takeIf { it > 0 } ?: time(md?.optString("episode_air_date"))
        val isNew = next && avail > System.currentTimeMillis() - 7 * 86_400_000L
        val seriesId = md?.optString("series_id")?.ifEmpty { null }
        return Show(
            key = "cr:" + (seriesId ?: epId), pkg = PKG, title = series,
            sub = (if (next) "Siguiente: " else "") + label(md, panel.optString("title")),
            image = image(panel.optJSONObject("images"), "thumbnail"),
            progress = if (durSec > 0) (playheadSec.toFloat() / durSec).coerceIn(0f, 1f) else 0f,
            minutesLeft = if (durSec > 0) ((durSec - playheadSec) / 60).toInt() else -1,
            at = if (isNew) maxOf(at, avail) else at, isNew = isNew,
            url = "crunchyroll://watch/$epId", playheadSec = playheadSec,
            seriesUrl = seriesId?.let { "crunchyroll://series/$it" },
        )
    }

    /**
     * «Seguir viendo»: the last series watched, one card each. Unfinished episodes resume where they
     * stopped; finished ones become the next episode, marked «Nuevo» if it came out this week.
     */
    fun continueWatching(c: Context): List<Show> {
        val acc = Prefs.str(c, "crAccount") ?: run { bearer(c); Prefs.str(c, "crAccount") } ?: throw NotLinked()
        val a = api(c, "/content/v2/$acc/watch-history?page_size=60").optJSONArray("data") ?: return emptyList()
        val seen = HashSet<String>()
        val out = mutableListOf<Show>()
        for (i in 0 until a.length()) {
            val h = a.getJSONObject(i)
            val parent = h.optString("parent_id").ifEmpty { h.optString("id") }
            if (!seen.add(parent)) continue
            val panel = h.optJSONObject("panel") ?: continue
            val at = time(h.optString("date_played"))
            if (!h.optBoolean("fully_watched")) {
                out += show(panel, h.optLong("playhead"), at, next = false)
            } else {
                val up = try { api(c, "/content/v2/discover/up_next/${panel.getString("id")}").optJSONArray("data")?.optJSONObject(0) } catch (e: Exception) { null }
                val np = up?.optJSONObject("panel")
                if (np != null && !up.optBoolean("fully_watched")) out += show(np, up.optLong("playhead"), at, next = true)
            }
            if (out.size >= 10) break
        }
        return out
    }

    /** This season's simulcasts (fall-2026 and so on), most popular first. */
    fun seasonal(c: Context): Pair<String, List<Show>> {
        val cal = Calendar.getInstance()
        val season = when (cal.get(Calendar.MONTH)) { in 0..2 -> "winter"; in 3..5 -> "spring"; in 6..8 -> "summer"; else -> "fall" }
        val want = "$season-${cal.get(Calendar.YEAR)}"
        val list = api(c, "/content/v1/season_list").optJSONArray("data") ?: JSONArray()
        var tag = want
        var name = "Anime de la temporada"
        for (i in 0 until list.length()) {
            val o = list.getJSONObject(i)
            if (o.optString("id") == want) {
                name = o.optJSONObject("localization")?.optString("title")?.ifEmpty { null } ?: name
            }
        }
        if ((0 until list.length()).none { list.getJSONObject(it).optString("id") == want } && list.length() > 0) tag = list.getJSONObject(0).optString("id")
        val a = api(c, "/content/v2/discover/browse?seasonal_tag=$tag&sort_by=popularity&n=20").optJSONArray("data") ?: return name to emptyList()
        val out = (0 until a.length()).map { a.getJSONObject(it) }.filter { it.optString("type") == "series" }.map { o ->
            val id = o.getString("id")
            Show(key = "crs:$id", pkg = PKG, title = o.optString("title"), sub = o.optString("description").take(140),
                image = image(o.optJSONObject("images"), "poster_wide"), progress = 0f, minutesLeft = -1, at = 0L,
                isNew = false, url = "crunchyroll://series/$id", playheadSec = 0, seriesUrl = "crunchyroll://series/$id")
        }
        return name to out
    }

    private fun account(c: Context): String = Prefs.str(c, "crAccount") ?: run { bearer(c); Prefs.str(c, "crAccount") } ?: throw NotLinked()

    private fun headers(c: Context) = mapOf("Authorization" to "Bearer ${bearer(c)}", "User-Agent" to UA)

    /** Your Crunchyroll list («Mi lista»), newest first. */
    fun watchlist(c: Context): List<Show> {
        val a = api(c, "/content/v2/discover/${account(c)}/watchlist?order=desc&n=40").optJSONArray("data") ?: return emptyList()
        return (0 until a.length()).map { a.getJSONObject(it) }.mapNotNull { o ->
            val p = o.optJSONObject("panel") ?: return@mapNotNull null
            val id = p.optString("id").ifEmpty { return@mapNotNull null }
            val isNew = o.optBoolean("new")
            Show(key = "crw:$id", pkg = PKG, title = p.optString("title"),
                sub = when { isNew -> "Episodio nuevo"; o.optBoolean("fully_watched") -> "Vista"; o.optBoolean("never_watched") -> "Sin empezar"; else -> "A medias" },
                image = image(p.optJSONObject("images"), "poster_wide"), progress = 0f, minutesLeft = -1, at = 0L, isNew = isNew,
                url = "crunchyroll://series/$id", playheadSec = 0, seriesUrl = "crunchyroll://series/$id")
        }
    }

    fun addToWatchlist(c: Context, seriesId: String) {
        Http.post("$BASE/content/v2/${account(c)}/watchlist?locale=$LOCALE", JSONObject().put("content_id", seriesId).toString(), "application/json", headers(c))
    }

    fun removeFromWatchlist(c: Context, seriesId: String) {
        Http.delete("$BASE/content/v2/${account(c)}/watchlist/$seriesId?locale=$LOCALE", headers(c))
    }

    /** The Crunchyroll series id for a title, to open it straight from the carousel. */
    fun findSeries(c: Context, q: String): String? {
        val a = api(c, "/content/v2/discover/search?q=${Http.enc(q)}&n=5&type=series").optJSONArray("data") ?: return null
        for (i in 0 until a.length()) {
            val items = a.getJSONObject(i).optJSONArray("items") ?: continue
            if (items.length() > 0) return items.getJSONObject(0).optString("id").ifEmpty { null }
        }
        return null
    }
}
