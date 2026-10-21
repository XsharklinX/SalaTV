package com.salatv.app

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Rect
import android.os.Bundle
import android.os.SystemClock
import android.view.KeyEvent
import android.view.View
import java.io.File

class MainActivity : Activity(), HomeView.Host {
    private lateinit var home: HomeView
    private var longPress = false
    private var loading = false
    private var again = false
    private var lastSnapshot = 0L
    private var lastNet = 0L

    private val pkgReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) { AppRepo.invalidate(); reload() }
    }

    private fun snapFile() = File(filesDir, "home.jpg")

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        Fonts.init(this)
        home = HomeView(this)
        home.host = this
        // «Arranque al instante»: the last picture of the home is up before the apps finish loading.
        if (AppRepo.apps.isEmpty()) home.placeholder = try { BitmapFactory.decodeFile(snapFile().path) } catch (e: Exception) { null }
        setContentView(home)
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
        registerReceiver(pkgReceiver, IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED); addAction(Intent.ACTION_PACKAGE_REMOVED); addAction(Intent.ACTION_PACKAGE_CHANGED)
            addDataScheme("package")
        })
    }

    override fun onDestroy() {
        unregisterReceiver(pkgReceiver)
        super.onDestroy()
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        home.goHome()
    }

    override fun onResume() {
        super.onResume()
        home.clearZoom()
        Claves.import(this)
        if (App.needPicker) {
            App.needPicker = false
            if (Prefs.profiles(this).size > 1) {
                startActivity(Intent(this, ProfilesActivity::class.java).putExtra("pick", true))
                return
            }
        }
        reload()
        refreshNet(false)
    }

    /** Local data: apps, usage, the saved feed. Fast; runs on every visit. */
    fun reload() {
        if (loading) { again = true; return }
        loading = true
        Bg.run({
            val apps = AppRepo.load(this)
            UsageRank.ranks(this)
            Triple(apps, Watch.check(this), Feed.cached(this))
        }) { r ->
            loading = false
            r.onSuccess { (apps, report, feed) -> build(apps, report, feed) }
                .onFailure { Registro.add(this, "Inicio", "No se pudo cargar: ${it.javaClass.simpleName}") }
            if (again) { again = false; reload() }
        }
    }

    /** Internet data: trends, Crunchyroll, «Mi lista», new versions. At most every 5 minutes unless [force]. */
    private fun refreshNet(force: Boolean) {
        if (!Prefs.bool(this, "internet", true)) return
        if (!force && SystemClock.elapsedRealtime() - lastNet < 5 * 60_000L && lastNet > 0) return
        lastNet = SystemClock.elapsedRealtime()
        val app = applicationContext
        Bg.run({
            val errors = mutableListOf<String>()
            try { Tmdb.refreshTrending(app) } catch (e: Exception) { errors += "Tendencias: ${e.message}" }
            val feed = try { Feed.refresh(app) } catch (e: Exception) { errors += "Seguir viendo: ${e.message}"; emptyList() }
            try { Feed.refreshSeason(app) } catch (e: Exception) { errors += "Temporada: ${e.message}" }
            try { MyList.refresh(app) } catch (e: Exception) { errors += "Mi lista: ${e.message}" }
            try { Updates.checkIfDue(app) } catch (_: Exception) {}
            errors.forEach { Registro.add(app, "Internet", it) }
            Feed.fresh(app, feed)
        }) { r ->
            r.getOrNull()?.firstOrNull()?.let { s -> home.notice("Nuevo: ${s.title} · ${s.sub.removePrefix("Siguiente: ")} ya está en ${Services.name(s.pkg)}") }
            reload()
        }
    }

    private fun build(apps: List<AppEntry>, report: Watch.Report, feed: List<Show>) {
        val p = Prefs.current(this)
        val hidden = Prefs.hidden(this)
        val folders = Prefs.folders(this)
        val byPkg = apps.associateBy { it.pkg }
        val visible = apps.filter { it.pkg !in hidden }
        val ranks = UsageRank.ranks(this)
        val slot = UsageRank.slotName()

        val fillable = visible.filter { it.pkg in p.pinned || !AppRepo.isTool(it) }
        val dockPkgs = Logic.dock(p.pinned, fillable.map { it.pkg }, ranks, p.autoDock)
        val dockItems = dockPkgs.mapNotNull { byPkg[it] }.map { e ->
            val note = when {
                p.autoDock && (ranks[e.pkg] ?: 0f) > 0.5f -> "Sueles abrirla por la $slot"
                e.pkg in p.pinned -> "Fijada en tu dock"
                else -> null
            }
            HomeView.Item.App(e, note)
        }

        // Card rows: only series and films, never apps.
        val rows = mutableListOf<HomeView.CardRow>()
        if (Prefs.bool(this, "contRow", true)) {
            val shows = feed.filter { it.pkg in byPkg && it.pkg !in hidden }
            rows += HomeView.CardRow("seguir", "Seguir viendo", shows.map { HomeView.Item.Card(it, "seguir") })
        }
        val mine = MyList.all(this)
        rows += HomeView.CardRow("milista", "Mi lista", mine.map { HomeView.Item.Pick(it) })
        val (seasonName, season) = Feed.cachedSeason(this)
        if (Prefs.bool(this, "seasonRow", true) && byPkg.containsKey(Crunchy.PKG)) {
            rows += HomeView.CardRow("season", seasonName.ifEmpty { "Anime de la temporada" }, season.map { HomeView.Item.Card(it, "season") })
        }

        val inFolder = folders.flatMap { it.pkgs }.toSet()
        val inDock = dockPkgs.toSet()
        val grid = mutableListOf<HomeView.Item>()
        folders.forEach { f -> grid += HomeView.Item.Fold(f, f.pkgs.mapNotNull { byPkg[it] }.filter { it.pkg !in hidden }) }
        val rest = visible.filter { it.pkg !in inDock && it.pkg !in inFolder }.map { it.pkg }
        Logic.sort(rest, p.sort, UsageRank.totals(this), UsageRank.lastUsed(this)) { byPkg[it]?.category ?: "App" }
            .forEach { grid += HomeView.Item.App(byPkg.getValue(it), null) }
        val hiddenCount = apps.count { it.pkg in hidden }
        if (hiddenCount > 0) grid += HomeView.Item.Hidden(hiddenCount)

        val chips = mutableListOf<HomeView.Chip>()
        when {
            SafetyNet.safeMode(this) -> chips += HomeView.Chip("seguro", "Modo seguro", HomeView.WARN, "Modo seguro",
                "SalaTV se cerró varias veces y el vigía está en pausa. OK para ver qué hacer", warn = true)
            !Prefs.setupDone(this) -> chips += HomeView.Chip("setup", "Configurar", HomeView.WARN,
                "Configura SalaTV", "Activa el vigía, «Seguir viendo» y los permisos en un paso", warn = true)
            report.attention && report.signature() != Prefs.str(this, "dismissed") -> chips += HomeView.Chip("aviso", "Aviso", HomeView.WARN,
                report.title(), report.detail(), warn = true)
        }
        val upd = Updates.count(this)
        if (upd > 0) chips += HomeView.Chip("updates", if (upd == 1) "1 actualización" else "$upd actualizaciones", 0,
            "Actualizaciones", "Hay versiones nuevas de apps que instalaste por fuera", warn = true)
        chips += HomeView.Chip("buscar", "Buscar", HomeView.SEARCH, "Buscar", "Un título y en qué app está")
        chips += HomeView.Chip("entradas", "Entradas", HomeView.INPUT, "Entradas", "Consola, decodificador y TV")
        val net = Net.describe(this)
        chips += HomeView.Chip("red", "", if (net.online) HomeView.WIFI else HomeView.NOWIFI, "Red", net.text)
        chips += HomeView.Chip("panel", "", HomeView.GEAR, "Panel rápido", "Apagado programado, tema, salud de la tele y más")
        val profiles = Prefs.profiles(this)
        chips += HomeView.Chip("perfil", p.name.take(1).uppercase(), HomeView.AVATAR, p.name,
            if (profiles.size > 1) "OK para cambiar de perfil" else "Perfiles de la casa", color = p.color)

        val trends = if (Prefs.bool(this, "carousel", true)) Tmdb.cachedTrending(this).filter { t -> t.pkgs.any { it in byPkg } } else emptyList()
        home.setData(chips, trends, dockItems, rows, grid, if (profiles.size > 1) p.name else null,
            "Todas tus apps · " + Logic.sortName(p.sort), mine.map { it.key }.toSet())
        saveSnapshotLater()
    }

    private fun saveSnapshotLater() {
        if (SystemClock.elapsedRealtime() - lastSnapshot < 10 * 60_000L && snapFile().exists()) return
        home.postDelayed({
            val b = home.snapshot() ?: return@postDelayed
            lastSnapshot = SystemClock.elapsedRealtime()
            val f = snapFile()
            Bg.run({ f.outputStream().use { b.compress(Bitmap.CompressFormat.JPEG, 80, it) } }) {}
        }, 2500)
    }

    // ---------------------------------------------------------------- HomeView.Host

    override fun onOpen(item: HomeView.Item) {
        Sounds.open(this)
        when (item) {
            is HomeView.Item.App -> launch(item.e)
            is HomeView.Item.Card -> Feed.open(this, item.s)
            is HomeView.Item.Pick -> {
                val pkg = item.t.pkgs.firstOrNull()
                if (pkg != null) AppSearch.openTitle(this, pkg, item.t) else openTitlePage(item.t)
            }
            is HomeView.Item.Fold -> startActivity(Intent(this, FolderActivity::class.java).putExtra("id", item.f.id))
            is HomeView.Item.Hidden -> startActivity(Intent(this, HiddenActivity::class.java))
        }
    }

    override fun onMove() = Sounds.tick(this)

    override fun onTrend(t: Title, action: Int) {
        when (action) {
            0 -> {
                Sounds.open(this)
                val pkg = t.pkgs.firstOrNull()
                if (pkg != null) AppSearch.openTitle(this, pkg, t) else openTitlePage(t)
            }
            1 -> { val added = MyList.toggle(this, t); Ui.toast(this, if (added) "«${t.name}» está en Mi lista" else "Quitada de Mi lista"); reload() }
            else -> openTitlePage(t)
        }
    }

    private fun openTitlePage(t: Title) = startActivity(Intent(this, TitleActivity::class.java).putExtra("type", t.type).putExtra("id", t.id).putExtra("json", t.toJson().toString()))

    private fun launch(e: AppEntry) {
        if (!Sys.start(this, e.pkg, e.intent)) {
            home.clearZoom()
            Ui.toast(this, "No se pudo abrir ${e.label}")
        }
    }

    override fun onChip(chip: HomeView.Chip) {
        when (chip.id) {
            "buscar" -> startActivity(Intent(this, SearchActivity::class.java))
            "entradas" -> startActivity(Intent(this, InputsActivity::class.java))
            "red" -> Sys.open(this, android.provider.Settings.ACTION_WIFI_SETTINGS)
            "panel" -> startActivity(Intent(this, QuickPanelActivity::class.java))
            "perfil" -> startActivity(Intent(this, ProfilesActivity::class.java).putExtra("pick", Prefs.profiles(this).size > 1))
            "updates" -> startActivity(Intent(this, StoreActivity::class.java))
            else -> startActivity(Intent(this, CleanupActivity::class.java))
        }
    }

    override fun onMenu(item: HomeView.Item, inDock: Boolean, anchor: Rect) {
        when (item) {
            is HomeView.Item.App -> appMenu(item.e, inDock, anchor)
            is HomeView.Item.Card -> {
                val s = item.s
                val acc = Services.badge(s.pkg).second
                val main = mutableListOf<Pair<String, () -> Unit>>()
                main += (if (item.row == "season") "Ver la serie" else "Seguir viendo") to { Feed.open(this, s) }
                if (s.seriesUrl != null && item.row != "season") main += "Ver la serie" to { Feed.open(this, Show(s.key, s.pkg, s.title, "", null, 0f, -1, 0, false, s.seriesUrl, 0)) }
                if (item.row != "season") main += "Quitar de Seguir viendo" to { Feed.hide(this, s); reload() }
                Ui.bubble(this, s.title, main, emptyList(), anchor, acc)
            }
            is HomeView.Item.Pick -> {
                val t = item.t
                val acc = t.pkgs.firstOrNull()?.let { Services.badge(it).second } ?: Ui.LAMP
                val main = t.pkgs.map { pkg -> "Ver en ${Services.name(pkg)}" to { AppSearch.openTitle(this, pkg, t) } }.toMutableList()
                main += "Ficha" to { openTitlePage(t) }
                main += "Quitar de Mi lista" to { MyList.toggle(this, t); reload() }
                Ui.bubble(this, t.name, main, emptyList(), anchor, acc)
            }
            is HomeView.Item.Fold -> Ui.bubble(this, item.f.name, listOf(
                "Abrir" to { onOpen(item) },
                "Elegir apps" to { startActivity(Intent(this, AppPickerActivity::class.java).putExtra("purpose", "folder:" + item.f.id)) },
                "Cambiar nombre" to { Ui.prompt(this, "Nombre de la carpeta", item.f.name) { n -> renameFolder(item.f.id, n) } },
            ), listOf("Borrar carpeta" to { deleteFolder(item.f.id) }), anchor, Ui.LAMP)
            is HomeView.Item.Hidden -> onOpen(item)
        }
    }

    private fun appMenu(e: AppEntry, inDock: Boolean, anchor: Rect) {
        val p = Prefs.current(this)
        val main = mutableListOf<Pair<String, () -> Unit>>()
        val more = mutableListOf<Pair<String, () -> Unit>>()
        main += "Abrir" to { launch(e) }
        if (inDock) main += "Mover" to { home.startMove() }
        if (e.pkg in p.pinned) main += "Quitar del dock" to { p.pinned.remove(e.pkg); Prefs.saveProfile(this, p); reload() }
        else main += "Fijar en el dock" to {
            if (p.pinned.size >= Prefs.DOCK_MAX) Ui.toast(this, "El dock tiene sitio para ${Prefs.DOCK_MAX} apps. Quita una antes.")
            else { p.pinned += e.pkg; Prefs.saveProfile(this, p); reload() }
        }
        more += "Añadir a una carpeta" to { chooseFolder(e.pkg, anchor) }
        if (!inDock) more += "Ordenar todas las apps" to { sortMenu(anchor) }
        if (!e.realBanner) more += "Color del banner" to {
            Ui.menu(this, "Color del banner", AppRepo.TINTS.map { (name, color) ->
                name to { Prefs.put(this, "tint:" + e.pkg, color); AppRepo.forget(e.pkg); reload() }
            }, anchor, e.color)
        }
        val pinned = e.pkg in Pin.apps(this)
        more += (if (pinned) "Quitar el PIN" else "Pedir PIN para abrirla") to {
            if (!Pin.hasCode(this)) startActivity(Intent(this, PinActivity::class.java).putExtra("set", true))
            Prefs.put(this, "pinApps", if (pinned) Pin.apps(this) - e.pkg else Pin.apps(this) + e.pkg)
            Ui.toast(this, if (pinned) "${e.label} ya no pide código" else "${e.label} pedirá el código")
        }
        more += "Ocultar" to {
            Prefs.saveHidden(this, Prefs.hidden(this) + e.pkg)
            p.pinned.remove(e.pkg); Prefs.saveProfile(this, p)
            Ui.toast(this, "${e.label} está oculta. La tienes al final, en «Apps ocultas».")
            reload()
        }
        more += "Información" to { Sys.appInfo(this, e.pkg) }
        more += "Desinstalar" to { Sys.uninstall(this, e.pkg) }
        Ui.bubble(this, e.label, main, more, anchor, e.color)
    }

    private fun sortMenu(anchor: Rect) {
        val p = Prefs.current(this)
        Ui.menu(this, "Ordenar todas las apps", Logic.SORTS.map { (k, name) ->
            (if (k == p.sort) "$name ✓" else name) to { p.sort = k; Prefs.saveProfile(this, p); reload() }
        }, anchor)
    }

    private fun chooseFolder(pkg: String, anchor: Rect) {
        val opts = Prefs.folders(this).map { f -> f.name to { Prefs.moveToFolder(this, pkg, f.id); reload() } }.toMutableList()
        opts += "Nueva carpeta…" to {
            Ui.prompt(this, "Nombre de la carpeta", "") { name ->
                val l = Prefs.folders(this)
                l.forEach { it.pkgs.remove(pkg) }
                l += Folder(Prefs.newId("f"), name, mutableListOf(pkg))
                Prefs.saveFolders(this, l)
                reload()
            }
        }
        Ui.menu(this, "Añadir a una carpeta", opts, anchor)
    }

    private fun renameFolder(id: String, name: String) {
        val l = Prefs.folders(this)
        l.firstOrNull { it.id == id }?.name = name
        Prefs.saveFolders(this, l)
        reload()
    }

    private fun deleteFolder(id: String) {
        Prefs.saveFolders(this, Prefs.folders(this).filter { it.id != id })
        reload()
    }

    override fun onDockMoved(pkgs: List<String>) {
        val p = Prefs.current(this)
        val wasAuto = p.autoDock
        p.pinned.clear(); p.pinned.addAll(pkgs); p.autoDock = false
        Prefs.saveProfile(this, p)
        if (wasAuto) Ui.toast(this, "Orden guardado. El dock queda fijo; puedes volver a «cambia con la hora» en Ajustes.")
        reload()
    }

    // ---------------------------------------------------------------- remote

    override fun onKeyDown(code: Int, ev: KeyEvent): Boolean {
        if (Sys.isSelect(code)) {
            if (ev.repeatCount == 0) { ev.startTracking(); longPress = false }
            return true
        }
        when (code) {
            KeyEvent.KEYCODE_MENU -> { home.menu(); return true }
            KeyEvent.KEYCODE_DPAD_LEFT -> return home.key(0, ev.repeatCount)
            KeyEvent.KEYCODE_DPAD_RIGHT -> return home.key(1, ev.repeatCount)
            KeyEvent.KEYCODE_DPAD_UP -> return home.key(2, ev.repeatCount)
            KeyEvent.KEYCODE_DPAD_DOWN -> return home.key(3, ev.repeatCount)
            KeyEvent.KEYCODE_MEDIA_REWIND -> { home.jumpEdge(false); return true }
            KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> { home.jumpEdge(true); return true }
        }
        return super.onKeyDown(code, ev)
    }

    override fun onKeyLongPress(code: Int, ev: KeyEvent): Boolean {
        if (Sys.isSelect(code)) { longPress = true; home.menu(); return true }
        return super.onKeyLongPress(code, ev)
    }

    override fun onKeyUp(code: Int, ev: KeyEvent): Boolean {
        if (Sys.isSelect(code)) {
            if (!longPress && !ev.isCanceled) home.activate()
            longPress = false
            return true
        }
        if (code in KeyEvent.KEYCODE_DPAD_UP..KeyEvent.KEYCODE_DPAD_RIGHT) home.keyUp()
        return super.onKeyUp(code, ev)
    }

    @Deprecated("Still the way to catch Back on Android 11")
    override fun onBackPressed() {
        home.back()
    }
}
