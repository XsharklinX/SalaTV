package com.salatv.app

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.BitmapFactory
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

    /** Home pressed while SalaTV is already in front: back to the start of the dock. */
    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        home.goHome()
    }

    override fun onResume() {
        super.onResume()
        home.clearZoom()
        if (App.needPicker) {
            App.needPicker = false
            if (Prefs.profiles(this).size > 1) {
                startActivity(Intent(this, ProfilesActivity::class.java).putExtra("pick", true))
                return
            }
        }
        reload()
    }

    fun reload() {
        if (loading) { again = true; return }
        loading = true
        Bg.run({
            val apps = AppRepo.load(this)
            UsageRank.ranks(this)
            Triple(apps, Watching.all(this).map { it to Watching.art(this, it) }, Watch.check(this))
        }) { r ->
            loading = false
            r.onSuccess { (apps, watched, report) -> build(apps, watched, report) }
                .onFailure { Registro.add(this, "Inicio", "No se pudo cargar: ${it.javaClass.simpleName}") }
            if (again) { again = false; reload() }
        }
        // Once a day, in the background: are there new versions of the apps installed from outside?
        if (Updates.enabled(this)) Bg.run({ Updates.checkIfDue(applicationContext) }) {}
    }

    private fun build(apps: List<AppEntry>, watched: List<Pair<Watched, Bitmap?>>, report: Watch.Report) {
        val p = Prefs.current(this)
        val hidden = Prefs.hidden(this)
        val folders = Prefs.folders(this)
        val byPkg = apps.associateBy { it.pkg }
        val visible = apps.filter { it.pkg !in hidden }
        val ranks = UsageRank.ranks(this)
        val slot = UsageRank.slotName()

        // Launchers and screensavers can be pinned by hand, but never fill the dock on their own.
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

        // «Continuar viendo»: titles from the media sessions, then apps opened lately («Seguir con…»).
        val cont = mutableListOf<HomeView.Item>()
        if (Prefs.bool(this, "contRow", true)) {
            watched.filter { it.first.pkg !in hidden && it.first.pkg in byPkg }.take(8).forEach { (w, art) ->
                cont += HomeView.Item.Media(w, byPkg[w.pkg], art)
            }
            val shownPkgs = watched.map { it.first.pkg }.toSet()
            val dismissed = Prefs.long(this, "recentCleared")
            UsageRank.lastUsed(this).entries
                .filter { (pkg, at) ->
                    val e = byPkg[pkg]
                    e != null && !AppRepo.isTool(e) && pkg !in hidden && pkg !in shownPkgs && at > dismissed && System.currentTimeMillis() - at < 7 * 86_400_000L
                }
                .sortedByDescending { it.value }.take(10 - cont.size.coerceAtMost(8))
                .forEach { (pkg, at) -> cont += HomeView.Item.Recent(byPkg.getValue(pkg), at) }
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
                "Configura SalaTV", "Activa el vigía, «Continuar viendo» y los permisos en un paso", warn = true)
            report.attention && report.signature() != Prefs.str(this, "dismissed") -> chips += HomeView.Chip("aviso", "Aviso", HomeView.WARN,
                report.title(), report.detail(), warn = true)
        }
        val upd = Updates.count(this)
        if (upd > 0) chips += HomeView.Chip("updates", if (upd == 1) "1 actualización" else "$upd actualizaciones", 0,
            "Actualizaciones", "Hay versiones nuevas de apps que instalaste por fuera", warn = true)
        chips += HomeView.Chip("buscar", "Buscar", HomeView.SEARCH, "Buscar", "Escribe una vez y abre la búsqueda en cada app")
        chips += HomeView.Chip("entradas", "Entradas", HomeView.INPUT, "Entradas", "Consola, decodificador y TV, con el nombre que les pongas")
        val net = Net.describe(this)
        chips += HomeView.Chip("red", "", if (net.online) HomeView.WIFI else HomeView.NOWIFI, "Red", net.text)
        chips += HomeView.Chip("panel", "", HomeView.GEAR, "Panel rápido", "Apagado programado, tema, salud de la tele y más")
        val profiles = Prefs.profiles(this)
        chips += HomeView.Chip("perfil", p.name.take(1).uppercase(), HomeView.AVATAR, p.name,
            if (profiles.size > 1) "OK para cambiar de perfil" else "Perfiles de la casa", color = p.color)

        home.setData(chips, dockItems, cont, grid, if (profiles.size > 1) p.name else null, "Todas tus apps · " + Logic.sortName(p.sort))
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
            is HomeView.Item.Media -> Resume.open(this, item.w)
            is HomeView.Item.Recent -> launch(item.e)
            is HomeView.Item.Fold -> startActivity(Intent(this, FolderActivity::class.java).putExtra("id", item.f.id))
            is HomeView.Item.Hidden -> startActivity(Intent(this, HiddenActivity::class.java))
        }
    }

    override fun onMove() = Sounds.tick(this)

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

    override fun onMenu(item: HomeView.Item, inDock: Boolean) {
        when (item) {
            is HomeView.Item.App -> appMenu(item.e, inDock)
            is HomeView.Item.Media -> Ui.menu(this, item.w.title, listOf(
                "Seguir viendo" to { Resume.open(this, item.w) },
                "Abrir ${item.app?.label ?: "la app"}" to { Sys.start(this, item.w.pkg, null) },
                "Quitar de Continuar viendo" to { Watching.remove(this, item.w.key); reload() },
            ))
            is HomeView.Item.Recent -> Ui.menu(this, item.e.label, listOf(
                "Abrir" to { launch(item.e) },
                "Vaciar «Seguir con…»" to { Prefs.put(this, "recentCleared", System.currentTimeMillis()); reload() },
            ))
            is HomeView.Item.Fold -> Ui.menu(this, item.f.name, listOf(
                "Abrir" to { onOpen(item) },
                "Elegir apps" to { startActivity(Intent(this, AppPickerActivity::class.java).putExtra("purpose", "folder:" + item.f.id)) },
                "Cambiar nombre" to { Ui.prompt(this, "Nombre de la carpeta", item.f.name) { n -> renameFolder(item.f.id, n) } },
                "Borrar carpeta" to { deleteFolder(item.f.id) },
            ))
            is HomeView.Item.Hidden -> onOpen(item)
        }
    }

    private fun appMenu(e: AppEntry, inDock: Boolean) {
        val p = Prefs.current(this)
        val opts = mutableListOf<Pair<String, () -> Unit>>()
        opts += "Abrir" to { launch(e) }
        if (inDock) opts += "Mover" to { home.startMove() }
        if (e.pkg in p.pinned) {
            opts += "Quitar del dock" to { p.pinned.remove(e.pkg); Prefs.saveProfile(this, p); reload() }
        } else {
            opts += "Fijar en el dock" to {
                if (p.pinned.size >= Prefs.DOCK_MAX) Ui.toast(this, "El dock tiene sitio para ${Prefs.DOCK_MAX} apps. Quita una antes.")
                else { p.pinned += e.pkg; Prefs.saveProfile(this, p); reload() }
            }
        }
        opts += "Añadir a una carpeta" to { chooseFolder(e.pkg) }
        if (!inDock) opts += "Ordenar todas las apps" to { sortMenu() }
        if (!e.realBanner) opts += "Color del banner" to {
            Ui.menu(this, "Color del banner", AppRepo.TINTS.map { (name, color) ->
                name to { Prefs.put(this, "tint:" + e.pkg, color); AppRepo.forget(e.pkg); reload() }
            })
        }
        val pinned = e.pkg in Pin.apps(this)
        opts += (if (pinned) "Quitar el PIN" else "Pedir PIN para abrirla") to {
            if (!Pin.hasCode(this)) startActivity(Intent(this, PinActivity::class.java).putExtra("set", true))
            Prefs.put(this, "pinApps", if (pinned) Pin.apps(this) - e.pkg else Pin.apps(this) + e.pkg)
            Ui.toast(this, if (pinned) "${e.label} ya no pide código" else "${e.label} pedirá el código")
        }
        opts += "Ocultar" to {
            Prefs.saveHidden(this, Prefs.hidden(this) + e.pkg)
            p.pinned.remove(e.pkg); Prefs.saveProfile(this, p)
            Ui.toast(this, "${e.label} está oculta. La tienes al final, en «Apps ocultas».")
            reload()
        }
        opts += "Información" to { Sys.appInfo(this, e.pkg) }
        opts += "Desinstalar" to { Sys.uninstall(this, e.pkg) }
        Ui.menu(this, e.label, opts)
    }

    private fun sortMenu() {
        val p = Prefs.current(this)
        Ui.menu(this, "Ordenar todas las apps", Logic.SORTS.map { (k, name) ->
            (if (k == p.sort) "$name ✓" else name) to { p.sort = k; Prefs.saveProfile(this, p); reload() }
        })
    }

    private fun chooseFolder(pkg: String) {
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
        Ui.menu(this, "Añadir a una carpeta", opts)
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
        if (Sys.isSelect(code)) {
            longPress = true
            home.menu()
            return true
        }
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
        // A home has nowhere to go back to.
        home.back()
    }
}
