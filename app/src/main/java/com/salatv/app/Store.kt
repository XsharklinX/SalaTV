package com.salatv.app

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.LinearLayout
import android.widget.TextView
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/** Version strings to comparable numbers: «32.68s» → [32, 68], «v2.1.6» → [2, 1, 6]. Pure, tested on the PC. */
object Versions {
    fun parse(v: String?): List<Int> {
        val m = Regex("\\d+(?:\\.\\d+)*").find(v ?: "") ?: return emptyList()
        return m.value.split('.').map { it.toIntOrNull() ?: 0 }
    }

    fun newer(latest: String?, installed: String?): Boolean {
        val a = parse(latest); val b = parse(installed)
        if (a.isEmpty() || b.isEmpty()) return false
        for (i in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrElse(i) { 0 }; val y = b.getOrElse(i) { 0 }
            if (x != y) return x > y
        }
        return false
    }

    /** From a list of file names, the one for this processor: exact ABI, then «universal», then any. */
    fun pickAsset(names: List<String>, abi: String, prefer: String? = null): String? {
        val apks = names.filter { it.endsWith(".apk") }
        val pool = prefer?.let { p -> apks.filter { it.contains(p) }.ifEmpty { null } } ?: apks
        return pool.firstOrNull { it.contains(abi) } ?: pool.firstOrNull { it.contains("universal") }
            ?: pool.firstOrNull { !Regex("arm64|armeabi|x86").containsMatchIn(it) } ?: pool.firstOrNull()
    }
}

/** Apps worth having on a TV that the Amazon store does not carry, from their official sources only. */
object Catalog {
    sealed class Source {
        class Github(val repo: String, val prefer: String? = null) : Source()
        class Folder(val url: (String) -> String, val pattern: (String) -> Regex) : Source()
        class Web(val site: String) : Source()
    }

    class Pick(val pkgs: List<String>, val name: String, val what: String, val source: Source)

    val ALL = listOf(
        Pick(listOf("org.smarttube.stable", "org.smarttube.beta"), "SmartTube", "YouTube sin anuncios, hecho para el mando", Source.Github("yuliskov/SmartTube")),
        Pick(listOf("com.stremio.one"), "Stremio", "Películas y series con complementos", Source.Web("stremio.com/downloads")),
        Pick(listOf("com.phlox.tvwebbrowser"), "TV Bro", "Navegador para la tele, con bloqueo de anuncios", Source.Github("truefedex/tv-bro", prefer = "geckoExcluded")),
        Pick(listOf("org.xbmc.kodi"), "Kodi", "Tus películas y series del disco o de la red", Source.Folder(
            { abi -> "https://mirrors.kodi.tv/releases/android/" + (if (abi == "arm64-v8a") "arm64-v8a" else "arm") + "/" },
            { abi -> Regex("kodi-([0-9.]+)-[A-Za-z]+-${Regex.escape(abi)}\\.apk") })),
        Pick(listOf("org.videolan.vlc"), "VLC", "Reproduce cualquier vídeo, también por la red", Source.Folder(
            { _ -> "https://get.videolan.org/vlc-android/last/" },
            { abi -> Regex("VLC-Android-([0-9.]+)-${Regex.escape(abi)}\\.apk") })),
    )

    fun abi(): String = when (val a = Build.SUPPORTED_ABIS.firstOrNull() ?: "") {
        "arm64-v8a", "armeabi-v7a", "x86", "x86_64" -> a
        else -> "armeabi-v7a"
    }

    fun installedPkg(c: Context, p: Pick) = p.pkgs.firstOrNull { pkg -> try { c.packageManager.getPackageInfo(pkg, 0); true } catch (e: Exception) { false } }

    fun installedVersion(c: Context, pkg: String): String? = try { c.packageManager.getPackageInfo(pkg, 0).versionName } catch (e: Exception) { null }

    private fun get(url: String): String {
        val con = URL(url).openConnection() as HttpURLConnection
        con.connectTimeout = 10_000; con.readTimeout = 15_000
        con.setRequestProperty("User-Agent", "SalaTV")
        return con.inputStream.bufferedReader().use { it.readText() }
    }

    class Latest(val version: String, val url: String, val file: String)

    /** Looks up the newest download for this TV. Network; call off the main thread. */
    fun latest(p: Pick): Latest? = when (val s = p.source) {
        is Source.Github -> {
            val o = JSONObject(get("https://api.github.com/repos/${s.repo}/releases/latest"))
            val assets = o.getJSONArray("assets")
            val names = (0 until assets.length()).map { assets.getJSONObject(it).getString("name") }
            Versions.pickAsset(names, abi(), s.prefer)?.let { n ->
                val url = (0 until assets.length()).map { assets.getJSONObject(it) }.first { it.getString("name") == n }.getString("browser_download_url")
                Latest(o.getString("tag_name"), url, n)
            }
        }
        is Source.Folder -> {
            val base = s.url(abi())
            val html = get(base)
            s.pattern(abi()).findAll(html).map { it.value to it.groupValues[1] }.distinct()
                .maxWithOrNull { a, b -> if (Versions.newer(a.second, b.second)) 1 else if (Versions.newer(b.second, a.second)) -1 else 0 }
                ?.let { (file, v) -> Latest(v, base + file, file) }
        }
        is Source.Web -> null
    }
}

/** A daily look for new versions of the apps installed from outside the store. */
object Updates {
    fun count(c: Context) = Prefs.int(c, "updates", 0)
    fun enabled(c: Context) = Prefs.bool(c, "internet", true)

    /** Runs on a background thread. Returns the number of apps with a newer version. */
    fun checkIfDue(c: Context): Int {
        if (!enabled(c)) return 0
        if (System.currentTimeMillis() - Prefs.long(c, "updatesAt") < 20 * 3600_000L) return count(c)
        var n = 0
        val found = JSONObject()
        Catalog.ALL.forEach { p ->
            val pkg = Catalog.installedPkg(c, p) ?: return@forEach
            try {
                val l = Catalog.latest(p) ?: return@forEach
                if (Versions.newer(l.version, Catalog.installedVersion(c, pkg))) { n++; found.put(pkg, l.version) }
            } catch (e: Exception) {
                Registro.add(c, "Actualizaciones", "${p.name}: no se pudo mirar la versión (${e.javaClass.simpleName})")
            }
        }
        Prefs.put(c, "updates", n)
        Prefs.put(c, "updatesList", found.toString())
        Prefs.put(c, "updatesAt", System.currentTimeMillis())
        return n
    }

    fun pending(c: Context): Map<String, String> {
        val o = JSONObject(Prefs.str(c, "updatesList") ?: "{}")
        return o.keys().asSequence().associateWith { o.getString(it) }
    }
}

object Installer {
    /** Downloads [url] and hands it to Android's installer, which asks for confirmation. */
    fun install(c: Context, url: String, file: String, progress: (Int) -> Unit) {
        val dir = File(c.cacheDir, "apk").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }
        val f = File(dir, file)
        val con = URL(url).openConnection() as HttpURLConnection
        con.connectTimeout = 15_000; con.readTimeout = 30_000
        con.setRequestProperty("User-Agent", "SalaTV")
        con.instanceFollowRedirects = true
        val total = con.contentLengthLong
        con.inputStream.use { input ->
            f.outputStream().use { out ->
                val buf = ByteArray(64 * 1024)
                var done = 0L; var last = -1
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n); done += n
                    if (total > 0) {
                        val pct = (done * 100 / total).toInt()
                        if (pct != last) { last = pct; Bg.main.post { progress(pct) } }
                    }
                }
            }
        }
        val pi = c.packageManager.packageInstaller
        val id = pi.createSession(PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL))
        pi.openSession(id).use { s ->
            s.openWrite("app", 0, f.length()).use { out -> f.inputStream().use { it.copyTo(out) }; s.fsync(out) }
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
            val done = PendingIntent.getBroadcast(c, id, Intent(c, InstallReceiver::class.java).putExtra("name", file), flags)
            s.commit(done.intentSender)
        }
    }

    fun allowed(c: Context) = c.packageManager.canRequestPackageInstalls()
}

class InstallReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        when (i.getIntExtra(PackageInstaller.EXTRA_STATUS, -99)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                @Suppress("DEPRECATION")
                val confirm = i.getParcelableExtra<Intent>(Intent.EXTRA_INTENT) ?: return
                c.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
            PackageInstaller.STATUS_SUCCESS -> {
                Ui.toast(c, "Instalada")
                Prefs.put(c, "updatesAt", 0L)
                AppRepo.invalidate()
            }
            else -> {
                val msg = i.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: "error desconocido"
                Ui.toast(c, "No se pudo instalar: $msg")
                Registro.add(c, "Apps recomendadas", "${i.getStringExtra("name")}: $msg")
            }
        }
    }
}

class StoreActivity : Screen() {
    private var busy = false
    private lateinit var status: TextView

    override fun onResume() {
        super.onResume()
        if (!busy) build()
    }

    private fun build() {
        val col = Ui.page(this, "Apps recomendadas", "Apps buenas para la tele que no están en la tienda de Amazon. Se descargan de su web oficial.")
        status = Ui.note(col, "", Ui.LAMP)
        if (!Updates.enabled(this)) {
            Ui.note(col, "Están apagadas las funciones con internet. Actívalas en Ajustes de SalaTV.", Ui.WARN)
            return
        }
        if (!Installer.allowed(this)) {
            Ui.row(col, "Permitir que SalaTV instale apps", "Se hace una vez. «Configurar todo» en Limpieza y vigía también lo activa.") {
                Sys.open(this, Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES)
            }
        }
        val pending = Updates.pending(this)
        Catalog.ALL.forEach { p ->
            val pkg = Catalog.installedPkg(this, p)
            val ver = pkg?.let { Catalog.installedVersion(this, it) }
            val upd = pkg?.let { pending[it] }
            val e = pkg?.let { AppRepo.byPkg(it) }
            val sub = when {
                upd != null -> "Hay versión nueva: $upd (tienes ${ver ?: "?"})"
                pkg != null -> "Instalada · ${ver ?: ""}"
                else -> p.what
            }
            val action = when {
                p.source is Catalog.Source.Web -> if (pkg != null) "✓" else "Web"
                upd != null -> "Actualizar"
                pkg != null -> "✓"
                else -> "Instalar"
            }
            val r = Ui.row(col, p.name, sub, action, e?.banner ?: e?.icon) {
                when {
                    p.source is Catalog.Source.Web -> Ui.toast(this, "Descárgala desde ${(p.source as Catalog.Source.Web).site} con TV Bro")
                    pkg != null && upd == null -> Ui.menu(this, p.name, listOf("Abrir" to { Sys.start(this, pkg, null) }, "Buscar versión nueva" to { Prefs.put(this, "updatesAt", 0L); check() }))
                    else -> install(p)
                }
            }
            if (upd != null) r.valueColor(Ui.WARN) else if (pkg != null) r.valueColor(Ui.OK)
        }
        Ui.row(col, "Buscar versiones nuevas ahora") { Prefs.put(this, "updatesAt", 0L); check() }
        Ui.note(col, "SalaTV mira una vez al día si estas apps tienen versión nueva. Para eso, y para descargarlas, usa internet; nada más sale de la tele.")
    }

    private fun check() {
        if (busy) return
        busy = true
        status.text = "Buscando versiones nuevas…"
        Bg.run({ Updates.checkIfDue(this) }) { r ->
            busy = false
            r.onFailure { status.text = "No se pudo conectar. Comprueba la red." }
            build()
        }
    }

    private fun install(p: Catalog.Pick) {
        if (busy) return
        busy = true
        status.text = "Buscando la última versión de ${p.name}…"
        Bg.run({
            val l = Catalog.latest(p) ?: throw IllegalStateException("No hay descarga para este procesador")
            Bg.main.post { status.text = "Descargando ${p.name} ${l.version}…" }
            Installer.install(this, l.url, l.file) { pct -> status.text = "Descargando ${p.name} ${l.version}… $pct %" }
            l.version
        }) { r ->
            busy = false
            r.onSuccess { status.text = "Confirma la instalación de ${p.name} $it en la pantalla de Android." }
                .onFailure {
                    status.text = "No se pudo instalar ${p.name}: ${it.message ?: it.javaClass.simpleName}"
                    Registro.add(this, "Apps recomendadas", "${p.name}: ${it.message ?: it.javaClass.simpleName}")
                }
        }
    }
}
