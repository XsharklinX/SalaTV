package com.salatv.app

import android.content.Context
import android.content.Intent
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Where a title lives inside each app, from JustWatch (the «where to watch» source behind TMDB).
 * Not official: its public GraphQL may change. Results are kept for a week per title.
 */
object JustWatch {
    private const val API = "https://apis.justwatch.com/graphql"
    private const val QUERY = "query S(\$country:Country!,\$language:Language!,\$first:Int!,\$filter:TitleFilter){popularTitles(country:\$country,first:\$first,filter:\$filter){edges{node{content(country:\$country,language:\$language){title externalIds{tmdbId}} offers(country:\$country,platform:WEB){monetizationType standardWebURL package{technicalName}}}}}}"

    /** JustWatch package names to the TV apps that open them. */
    private val PACKAGES = mapOf(
        "netflix" to listOf("com.netflix.ninja"),
        "netflixbasicwithads" to listOf("com.netflix.ninja"),
        "amazonprimevideo" to listOf("com.amazon.firebat", "com.amazon.avod.thirdpartyclient"),
        "amazonprime" to listOf("com.amazon.firebat", "com.amazon.avod.thirdpartyclient"),
        "amazonprimevideowithads" to listOf("com.amazon.firebat", "com.amazon.avod.thirdpartyclient"),
        "crunchyroll" to listOf(Crunchy.PKG),
        "disneyplus" to listOf("com.disney.disneyplus"),
        "hbomax" to listOf("com.wbd.stream", "com.hbo.hbonow"),
        "max" to listOf("com.wbd.stream", "com.hbo.hbonow"),
        "appletvplus" to listOf("com.apple.atve.amazon.appletv"),
        "paramountplus" to listOf("com.cbs.ott"),
    )

    private fun cache(c: Context) = File(c.filesDir, "jw").apply { mkdirs() }

    private fun installed(c: Context, pkg: String) = try { c.packageManager.getPackageInfo(pkg, 0); true } catch (e: Exception) { false }

    /** Installed app → web link of [t] in it. Network; off the main thread. */
    fun links(c: Context, t: Title): Map<String, String> {
        val f = File(cache(c), "${t.type}-${t.id}.json")
        if (f.exists() && System.currentTimeMillis() - f.lastModified() < 7 * 86_400_000L) {
            val o = JSONObject(f.readText())
            return o.keys().asSequence().associateWith { o.getString(it) }
        }
        val vars = JSONObject().put("country", Tmdb.region(c)).put("language", "es").put("first", 5)
            .put("filter", JSONObject().put("searchQuery", t.name).put("objectTypes", JSONArray().put(if (t.type == "tv") "SHOW" else "MOVIE")))
        val body = JSONObject().put("query", QUERY).put("variables", vars).toString()
        val edges = JSONObject(Http.post(API, body, "application/json", mapOf("User-Agent" to "Mozilla/5.0 SalaTV")))
            .getJSONObject("data").getJSONObject("popularTitles").getJSONArray("edges")
        // The same TMDB id, never just the same name.
        var node: JSONObject? = null
        for (i in 0 until edges.length()) {
            val n = edges.getJSONObject(i).getJSONObject("node")
            if (n.optJSONObject("content")?.optJSONObject("externalIds")?.optInt("tmdbId") == t.id) { node = n; break }
        }
        val out = LinkedHashMap<String, String>()
        val offers = node?.optJSONArray("offers") ?: JSONArray()
        for (i in 0 until offers.length()) {
            val o = offers.getJSONObject(i)
            if (o.optString("monetizationType") !in setOf("FLATRATE", "FREE", "ADS")) continue
            val pkg = PACKAGES[o.getJSONObject("package").optString("technicalName")]?.firstOrNull { installed(c, it) } ?: continue
            if (pkg !in out) out[pkg] = o.optString("standardWebURL")
        }
        f.writeText(JSONObject(out as Map<*, *>).toString())
        return out
    }
}

/**
 * Opens a title inside its app. Each app understands its own kind of link (checked on the TV):
 * Netflix and Prime Video take their web addresses, Crunchyroll only crunchyroll://, Disney+ only disneyplus://.
 */
object Deep {
    /** Links to try in order for [web] inside [pkg]. Pure, so it is tested on the PC. */
    fun candidates(pkg: String, web: String): List<String> = when (pkg) {
        Crunchy.PKG -> {
            val m = Regex("crunchyroll\\.com/(?:[a-z-]+/)?(watch|series)/([A-Z0-9]+)").find(web)
            if (m != null) listOf("crunchyroll://${m.groupValues[1]}/${m.groupValues[2]}") else listOf(web)
        }
        "com.disney.disneyplus" -> listOf(web.replaceFirst("https://", "disneyplus://"), web)
        else -> listOf(web)
    }

    /** True when an app accepted one of the links. */
    fun open(c: Context, pkg: String, web: String): Boolean {
        for (u in candidates(pkg, web)) {
            val i = Intent(Intent.ACTION_VIEW, Uri.parse(u)).setPackage(pkg)
            if (c.packageManager.resolveActivity(i, 0) == null) continue
            if (Sys.start(c, pkg, i)) return true
        }
        return false
    }

    /**
     * «Ver»: the exact title in [pkg] through JustWatch; for Crunchyroll, its own search as a second try;
     * the app's search as the last resort. Runs the lookup off the main thread.
     */
    fun title(c: Context, pkg: String, t: Title) {
        Bg.run({
            val jw = try { JustWatch.links(c, t)[pkg] } catch (e: Exception) { null }
            jw ?: if (pkg == Crunchy.PKG && Crunchy.linked(c)) Crunchy.findSeries(c, t.name)?.let { "https://www.crunchyroll.com/series/$it" } else null
        }) { r ->
            val web = r.getOrNull()
            if (web != null && open(c, pkg, web)) return@run
            Registro.add(c, "Ver", "${t.name}: sin enlace directo en ${Services.name(pkg)}; se abrió su búsqueda")
            AppSearch.go(c, pkg, t.name)
        }
    }
}
