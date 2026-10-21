package com.salatv.app

import android.app.Activity
import android.app.SearchManager
import android.content.Context
import android.content.Intent
import android.graphics.drawable.GradientDrawable
import android.media.tv.TvContract
import android.media.tv.TvInputInfo
import android.media.tv.TvInputManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.KeyEvent
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView

/** Sleep timer, Wi‑Fi, headphones, theme and the way to Amazon's own home. Opens on the right. */
class QuickPanelActivity : Screen() {
    override fun onResume() {
        super.onResume()
        val col = Ui.page(this, "Panel rápido", side = true)
        Ui.row(col, "Red", Net.describe(this).text) { Sys.open(this, Settings.ACTION_WIFI_SETTINGS) }
        Ui.row(col, "Auriculares y altavoces", "Bluetooth") { Sys.open(this, Settings.ACTION_BLUETOOTH_SETTINGS) }

        Ui.section(col, "Apagar la tele en")
        val status = Ui.note(col, SleepTimer.describe(this))
        Ui.chips(col, listOf("No", "15", "30", "60", "90"), SleepTimer.choice(this)) { i ->
            SleepTimer.set(this, SleepTimer.CHOICES[i])
            status.text = SleepTimer.describe(this)
        }

        Ui.section(col, "Tema")
        val modes = listOf("auto", "day", "night")
        Ui.chips(col, listOf("Atardecer", "Día", "Noche"), modes.indexOf(Look.mode(this)).coerceAtLeast(0)) { i ->
            Prefs.put(this, "theme", modes[i])
        }

        Ui.section(col, "Tele")
        Ui.row(col, "Imagen", "Abre el ajuste de imagen de la tele") { Sys.open(this, Settings.ACTION_DISPLAY_SETTINGS) }
        Ui.row(col, "Salud de la tele", "Memoria, espacio y liberar memoria") { startActivity(Intent(this, HealthActivity::class.java)) }
        Ui.row(col, "Limpieza y vigía", "Apps de anuncios, DNS y botón Inicio") { startActivity(Intent(this, CleanupActivity::class.java)) }
        Ui.row(col, Platform.stockName(this), "Para la tienda y los ajustes propios de la tele") { Sys.stockHome(this); finish() }
        Ui.row(col, "Ajustes de SalaTV") { startActivity(Intent(this, SettingsActivity::class.java)) }
    }
}

/** HDMI ports and the tuner, with the names the user gives them. */
class InputsActivity : Screen() {
    override fun onResume() {
        super.onResume()
        val pick = intent.getBooleanExtra("pickWake", false)
        val col = Ui.page(this, if (pick) "¿Qué entrada al encender?" else "Entradas", if (pick) null else "OK para cambiar. Mantén OK para ponerle nombre.")
        val names = Prefs.inputNames(this)
        val list = Inputs.list(this)
        if (list.all { it.id.startsWith("hdmi") }) {
            Ui.note(col, "Fire OS no deja a otras apps ver sus entradas. SalaTV cambia de entrada pulsando por ADB la tecla HDMI de cada puerto.", Ui.MUTED)
        }
        list.forEach { inp ->
            val name = names[inp.id]
            val r = Ui.row(col, name ?: inp.label, if (name != null) "${inp.type} · ${inp.label}" else inp.type) {
                if (pick) { Prefs.put(this, "wake", "input:" + inp.id); finish() } else Inputs.open(this, inp.id)
            }
            if (!pick) {
                r.view.setOnLongClickListener { rename(inp.id, name ?: inp.label); true }
                r.view.setOnKeyListener { _, code, ev ->
                    if (code == KeyEvent.KEYCODE_MENU && ev.action == KeyEvent.ACTION_UP) { rename(inp.id, name ?: inp.label); true } else false
                }
            }
        }
    }

    private fun rename(id: String, current: String) = Ui.prompt(this, "Nombre de la entrada", current) { n ->
        val m = Prefs.inputNames(this); m[id] = n; Prefs.saveInputNames(this, m); onResume()
    }
}

/** «¿Quién ve la tele?» on screen-on, and the place to add, rename and delete profiles. */
class ProfilesActivity : Screen() {
    override fun onResume() {
        super.onResume()
        if (intent.getBooleanExtra("pick", false)) picker() else manage()
    }

    private fun picker() {
        Fonts.init(this)
        val root = FrameLayout(this).apply { background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(0xFF1A2030.toInt(), 0xFF05060A.toInt())) }
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL }
        col.addView(Ui.text(this, "¿Quién ve la tele?", 34f, Ui.FG, Fonts.medium))
        val avs = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, Ui.px(this@ProfilesActivity, 40), 0, Ui.px(this@ProfilesActivity, 36)) }
        val cur = Prefs.current(this).id
        var focus: android.view.View? = null
        Prefs.profiles(this).forEach { p ->
            val v = avatar(p) { Prefs.setCurrent(this, p.id); finish() }
            avs.addView(v, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { leftMargin = Ui.px(this@ProfilesActivity, 18); rightMargin = Ui.px(this@ProfilesActivity, 18) })
            if (p.id == cur) focus = v
        }
        col.addView(avs)
        val manageRow = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        Ui.row(manageRow, "Gestionar perfiles") { startActivity(Intent(this, ProfilesActivity::class.java)) }
        col.addView(manageRow, LinearLayout.LayoutParams(Ui.px(this, 300), ViewGroup.LayoutParams.WRAP_CONTENT))
        root.addView(col, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
        setContentView(root)
        focus?.requestFocus()
    }

    private fun avatar(p: Profile, onClick: () -> Unit): LinearLayout {
        val c = this
        val box = LinearLayout(c).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL
            isFocusable = true; isClickable = true; setOnClickListener { onClick() }
        }
        val circle = TextView(c).apply {
            text = p.name.take(1).uppercase(); textSize = 44f; typeface = Fonts.bold; setTextColor(Ui.INK); gravity = Gravity.CENTER
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(p.color) }
        }
        val name = Ui.text(c, p.name, 18f, Ui.MUTED, Fonts.medium)
        box.addView(circle, LinearLayout.LayoutParams(Ui.px(c, 112), Ui.px(c, 112)))
        box.addView(name, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = Ui.px(c, 14) })
        box.setOnFocusChangeListener { v, f ->
            v.animate().scaleX(if (f) 1.1f else 1f).scaleY(if (f) 1.1f else 1f).setDuration(160).start()
            (circle.background as GradientDrawable).setStroke(if (f) Ui.px(c, 5) else 0, Ui.FG)
            name.setTextColor(if (f) Ui.FG else Ui.MUTED)
        }
        return box
    }

    private fun manage() {
        val col = Ui.page(this, "Perfiles de la casa", "Cada perfil tiene su dock. Al encender la tele eliges quién ve.")
        val cur = Prefs.current(this).id
        Prefs.profiles(this).forEach { p ->
            Ui.row(col, p.name, if (p.autoDock) "Dock que cambia con la hora" else "Dock fijo", if (p.id == cur) "En uso" else null) { options(p) }
        }
        Ui.row(col, "Añadir perfil") {
            Ui.prompt(this, "Nombre del perfil", "") { n ->
                val l = Prefs.profiles(this)
                l += Profile(Prefs.newId("p"), n, Prefs.COLORS[l.size % Prefs.COLORS.size], mutableListOf(), true)
                Prefs.saveProfiles(this, l)
                manage()
            }
        }
    }

    private fun options(p: Profile) = Ui.menu(this, p.name, listOf(
        "Usar este perfil" to { Prefs.setCurrent(this, p.id); finish() },
        "Cambiar nombre" to { Ui.prompt(this, "Nombre del perfil", p.name) { n -> p.name = n; Prefs.saveProfile(this, p); manage() } },
        "Cambiar color" to {
            p.color = Prefs.COLORS[(Prefs.COLORS.indexOf(p.color) + 1).mod(Prefs.COLORS.size)]
            Prefs.saveProfile(this, p); manage()
        },
        "Borrar" to {
            val l = Prefs.profiles(this)
            if (l.size == 1) Ui.toast(this, "Tiene que quedar al menos un perfil")
            else { Prefs.saveProfiles(this, l.filter { it.id != p.id }); manage() }
        },
    ))
}

class FolderActivity : Screen() {
    override fun onResume() {
        super.onResume()
        val id = intent.getStringExtra("id")
        val f = Prefs.folders(this).firstOrNull { it.id == id } ?: return finish()
        val col = Ui.page(this, f.name, if (f.pkgs.size == 1) "1 app" else "${f.pkgs.size} apps")
        f.pkgs.mapNotNull { AppRepo.byPkg(it) }.forEach { e ->
            Ui.row(col, e.label, null, icon = e.banner ?: e.icon) { Sys.launch(this, e.pkg) }
        }
        Ui.section(col, "Carpeta")
        Ui.row(col, "Elegir apps") { startActivity(Intent(this, AppPickerActivity::class.java).putExtra("purpose", "folder:${f.id}")) }
        Ui.row(col, "Cambiar nombre") {
            Ui.prompt(this, "Nombre de la carpeta", f.name) { n ->
                val l = Prefs.folders(this); l.firstOrNull { it.id == f.id }?.name = n; Prefs.saveFolders(this, l); onResume()
            }
        }
        Ui.row(col, "Borrar carpeta", "Las apps vuelven a «Todas tus apps»") {
            Prefs.saveFolders(this, Prefs.folders(this).filter { it.id != f.id }); finish()
        }
    }
}

class HiddenActivity : Screen() {
    override fun onResume() {
        super.onResume()
        val hidden = Prefs.hidden(this)
        val col = Ui.page(this, "Apps ocultas", "Siguen instaladas; solo no salen en el inicio")
        AppRepo.apps.filter { it.pkg in hidden }.forEach { e ->
            Ui.row(col, e.label, null, icon = e.banner ?: e.icon) {
                Ui.menu(this, e.label, listOf(
                    "Abrir" to { Sys.launch(this, e.pkg) },
                    "Mostrar en el inicio" to { Prefs.saveHidden(this, Prefs.hidden(this) - e.pkg); onResume() },
                ))
            }
        }
        Ui.row(col, "Elegir apps ocultas") { startActivity(Intent(this, AppPickerActivity::class.java).putExtra("purpose", "hidden")) }
    }
}

/** One list of apps for several jobs: «pin», «hidden», «folder:<id>» (tick several) or «key:<code>:<scan>» (pick one). */
class AppPickerActivity : Screen() {
    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        if (AppRepo.apps.isEmpty()) Bg.run({ AppRepo.load(this) }) { build() } else build()
    }

    private fun build() {
        val purpose = intent.getStringExtra("purpose") ?: return finish()
        val apps = AppRepo.apps
        when {
            purpose == "wake" -> {
                val col = Ui.page(this, "¿Qué app al encender?")
                apps.forEach { e -> Ui.row(col, e.label, null, icon = e.banner ?: e.icon) { Prefs.put(this, "wake", "app:" + e.pkg); finish() } }
            }
            purpose.startsWith("key:") -> {
                val (_, code, scan) = purpose.split(':')
                val col = Ui.page(this, "¿Qué abre este botón?")
                fun save(pkg: String) {
                    val l = Prefs.keyMaps(this).filter { it.keyCode != code.toInt() }.toMutableList()
                    l += KeyMap(code.toInt(), scan.toInt(), pkg)
                    Prefs.saveKeyMaps(this, l)
                    Ui.toast(this, "Botón guardado")
                    finish()
                }
                Ui.row(col, "SalaTV", "Vuelve al inicio") { save(packageName) }
                apps.forEach { e -> Ui.row(col, e.label, null, icon = e.banner ?: e.icon) { save(e.pkg) } }
            }
            else -> {
                val p = Prefs.current(this)
                val folderId = purpose.removePrefix("folder:").takeIf { purpose.startsWith("folder:") }
                val folder = folderId?.let { id -> Prefs.folders(this).firstOrNull { it.id == id } }
                val title = when {
                    purpose == "pin" -> "Apps del dock de ${p.name}"
                    purpose == "hidden" -> "Apps ocultas"
                    purpose == "pinapps" -> "Apps que piden el código"
                    else -> "Apps de «${folder?.name ?: "carpeta"}»"
                }
                val sub = when (purpose) {
                    "pin" -> "Marca hasta ${Prefs.DOCK_MAX}. Con «cambia con la hora», SalaTV las ordena según el momento del día."
                    "hidden" -> "Las apps marcadas no salen en el inicio"
                    "pinapps" -> "Piden el código al abrirlas desde SalaTV o desde cualquier sitio, si el vigía está activo"
                    else -> "Las apps marcadas viven en esta carpeta"
                }
                val col = Ui.page(this, title, sub)
                apps.forEach { e ->
                    var on = when {
                        purpose == "pin" -> e.pkg in p.pinned
                        purpose == "hidden" -> e.pkg in Prefs.hidden(this)
                        purpose == "pinapps" -> e.pkg in Pin.apps(this)
                        else -> folder?.pkgs?.contains(e.pkg) == true
                    }
                    lateinit var row: Ui.Row
                    row = Ui.row(col, e.label, null, if (on) "✓" else "", e.banner ?: e.icon) {
                        val want = !on
                        when {
                            purpose == "pin" -> {
                                val cur = Prefs.current(this)
                                if (want && cur.pinned.size >= Prefs.DOCK_MAX) { Ui.toast(this, "El dock tiene sitio para ${Prefs.DOCK_MAX} apps"); return@row }
                                if (want) cur.pinned += e.pkg else cur.pinned.remove(e.pkg)
                                Prefs.saveProfile(this, cur)
                            }
                            purpose == "hidden" -> Prefs.saveHidden(this, if (want) Prefs.hidden(this) + e.pkg else Prefs.hidden(this) - e.pkg)
                            purpose == "pinapps" -> Prefs.put(this, "pinApps", if (want) Pin.apps(this) + e.pkg else Pin.apps(this) - e.pkg)
                            folderId != null -> {
                                if (want) Prefs.moveToFolder(this, e.pkg, folderId)
                                else { val l = Prefs.folders(this); l.firstOrNull { it.id == folderId }?.pkgs?.remove(e.pkg); Prefs.saveFolders(this, l) }
                            }
                        }
                        on = want
                        row.value.text = if (on) "✓" else ""
                    }
                    row.valueColor(Ui.OK)
                }
            }
        }
    }
}

class CityActivity : Screen() {
    override fun onResume() {
        super.onResume()
        val col = Ui.page(this, "Ciudad", "Para saber a qué hora se pone el sol y cambiar al tema de noche")
        val cur = Cities.current(this)
        var focus: android.view.View? = null
        Cities.ALL.forEach { city ->
            val t = Sun.times(city)
            val r = Ui.row(col, city.name, t?.let { "Atardecer hoy a las ${Sun.fmt(it.second)}" }, if (city.id == cur.id) "✓" else null) {
                Prefs.put(this, "city", city.id); finish()
            }
            r.valueColor(Ui.OK)
            if (city.id == cur.id) focus = r.view
        }
        focus?.requestFocus()
    }
}

class SettingsActivity : Screen() {
    override fun onResume() {
        super.onResume()
        val col = Ui.page(this, "Ajustes de SalaTV")
        val p = Prefs.current(this)

        Ui.section(col, "Inicio")
        val modes = listOf("auto" to "Según el atardecer", "day" to "Siempre de día", "night" to "Siempre de noche")
        Ui.row(col, "Tema", null, modes.firstOrNull { it.first == Look.mode(this) }?.second) {
            Ui.menu(this, "Tema", modes.map { (k, label) -> label to { Prefs.put(this, "theme", k); onResume() } })
        }
        val city = Cities.current(this)
        val t = Sun.times(city)
        Ui.row(col, "Ciudad", t?.let { "Amanece a las ${Sun.fmt(it.first)} · atardece a las ${Sun.fmt(it.second)}" }, city.name) {
            startActivity(Intent(this, CityActivity::class.java))
        }
        Ui.row(col, "Dock de ${p.name}", if (p.autoDock) "Las apps se ordenan según lo que abres a cada hora" else "Siempre en el mismo orden",
            if (p.autoDock) "Cambia con la hora" else "Fijo") {
            Ui.menu(this, "Dock", listOf(
                "Cambia con la hora" to { p.autoDock = true; Prefs.saveProfile(this, p); onResume() },
                "Fijo" to { p.autoDock = false; Prefs.saveProfile(this, p); onResume() },
            ))
        }
        if (p.autoDock && !UsageRank.has(this)) Ui.note(col, "Para que el dock cambie con la hora hace falta el permiso de uso de apps. Dalo en «Limpieza y vigía» › «Configurar todo».", Ui.WARN)
        Ui.row(col, "Apps del dock") { startActivity(Intent(this, AppPickerActivity::class.java).putExtra("purpose", "pin")) }
        Ui.row(col, "Apps ocultas", null, Prefs.hidden(this).size.toString()) { startActivity(Intent(this, HiddenActivity::class.java)) }
        Ui.row(col, "Perfiles", null, Prefs.profiles(this).size.toString()) { startActivity(Intent(this, ProfilesActivity::class.java)) }

        Ui.row(col, "Ordenar «Todas tus apps»", null, Logic.sortName(p.sort)) {
            Ui.menu(this, "Ordenar", Logic.SORTS.map { (k, name) -> name to { p.sort = k; Prefs.saveProfile(this, p); onResume() } })
        }
        toggle(col, "Fila «Seguir viendo»", "Capítulos y pelis a medias de Crunchyroll y de lo que veas en esta tele", "contRow", true)
        if (!MediaWatcher.enabled(this)) Ui.note(col, "Para guardar lo que ves en esta tele hace falta un permiso. Dalo en «Limpieza y vigía» › «Configurar todo».", Ui.WARN)

        val dens = listOf("Compacto", "Normal", "Grande")
        Ui.row(col, "Tamaño del inicio", "Tarjetas, textos y separaciones", dens[Prefs.int(this, "density", 1)]) {
            Ui.menu(this, "Tamaño del inicio", dens.mapIndexed { i, n -> n to { Prefs.put(this, "density", i); onResume() } })
        }
        toggle(col, "Carrusel de tendencias", "Lo más visto esta semana en tus apps, arriba del todo", "carousel", true)
        toggle(col, "Fondo que se mueve", "La imagen de la tendencia se acerca muy despacio", "kenBurns", true)
        toggle(col, "Anime de la temporada", "Fila con los estrenos de Crunchyroll", "seasonRow", true)

        Ui.section(col, "Servicios")
        val region = Tmdb.region(this)
        Ui.row(col, "País de las tendencias", "Para saber en qué app está cada título", Tmdb.REGIONS.firstOrNull { it.first == region }?.second ?: region) {
            Ui.menu(this, "País", Tmdb.REGIONS.map { (code, name) -> name to { Prefs.put(this, "region", code); Prefs.put(this, "trendRegion", ""); onResume() } })
        }
        if (Crunchy.linked(this)) {
            val crName = Prefs.str(this, "crProfileName:" + p.id) ?: "el principal"
            Ui.row(col, "Crunchyroll", "Conectado como ${Prefs.str(this, "crEmail") ?: "tu cuenta"} · perfil de ${p.name}: $crName", "Cambiar") {
                Ui.menu(this, "Crunchyroll", listOf(
                    "Perfil de Crunchyroll para ${p.name}" to { pickCrProfile(p) },
                    "Desconectar" to { Crunchy.logout(this); onResume() },
                ))
            }
        } else {
            Ui.row(col, "Conectar Crunchyroll", "Para Seguir viendo, episodios nuevos y la temporada. No oficial: puede dejar de funcionar.") {
                Ui.prompt(this, "Correo de Crunchyroll", Prefs.str(this, "crEmail") ?: "") { email ->
                    Ui.prompt(this, "Contraseña", "", password = true) { pass ->
                        Ui.toast(this, "Conectando con Crunchyroll…")
                        Bg.run({ Crunchy.login(this, email, pass) }) { r ->
                            r.onSuccess { Ui.toast(this, it); Prefs.put(this, "feedAt", 0L); onResume() }
                                .onFailure { Ui.toast(this, "Crunchyroll no aceptó el inicio de sesión"); Registro.add(this, "Crunchyroll", "Inicio de sesión: ${it.message}") }
                        }
                    }
                }
            }
            Ui.note(col, "También desde el PC, sin escribir con el mando: tools\\claves.ps1")
        }

        Ui.section(col, "Al encender")
        val wake = Prefs.str(this, "wake", "home")!!
        val wakeName = when {
            wake == "usual" -> "Lo que más uso a esa hora"
            wake == "last" -> "La última app"
            wake.startsWith("app:") -> AppRepo.byPkg(wake.removePrefix("app:"))?.label ?: "Una app"
            wake.startsWith("input:") -> Prefs.inputNames(this)[wake.removePrefix("input:")] ?: "Una entrada"
            wake == "resume" -> "Seguir donde lo dejé"
            else -> "El inicio de SalaTV"
        }
        Ui.row(col, "Al encender la tele", "Qué se abre nada más encender", wakeName) {
            Ui.menu(this, "Al encender la tele", listOf(
                "El inicio de SalaTV" to { Prefs.put(this, "wake", "home"); onResume() },
                "Seguir donde lo dejé" to { Prefs.put(this, "wake", "resume"); onResume() },
                "Lo que más uso a esa hora" to { Prefs.put(this, "wake", "usual"); onResume() },
                "La última app" to { Prefs.put(this, "wake", "last"); onResume() },
                "Una app concreta…" to { startActivity(Intent(this, AppPickerActivity::class.java).putExtra("purpose", "wake")) },
                "Una entrada…" to { startActivity(Intent(this, InputsActivity::class.java).putExtra("pickWake", true)) },
            ))
        }

        Ui.section(col, "Mando")
        Ui.row(col, "Botones del mando", "Que un botón abra la app que quieras") { startActivity(Intent(this, KeysActivity::class.java)) }
        toggle(col, "Doble Inicio", "Pulsar Inicio dos veces seguidas vuelve a la app donde estabas", "doubleHome", true) { Vigia.reload() }
        toggle(col, "Sonidos suaves", "Un clic muy bajo al moverse y al abrir", "sounds", false)

        Ui.section(col, "Noche")
        val hours = listOf(21, 22, 23, 0)
        Ui.row(col, "Horario de noche", "Para el volumen nocturno y «¿Sigues ahí?»", "de ${Night.from(this)}:00 a ${Night.to(this)}:00") {
            Ui.menu(this, "Empieza a las…", hours.map { h -> "$h:00" to {
                Prefs.put(this, "nightFrom", h)
                Ui.menu(this, "Termina a las…", listOf(6, 7, 8, 9).map { e -> "$e:00" to { Prefs.put(this, "nightTo", e); onResume() } })
            } })
        }
        val caps = listOf(0, 20, 30, 40, 50)
        Ui.row(col, "Volumen nocturno", "Tope de volumen por la noche. No funciona si el sonido va por HDMI-CEC a una barra.",
            if (Night.cap(this) == 0) "Apagado" else "Máximo ${Night.cap(this)} %") {
            Ui.menu(this, "Volumen nocturno", caps.map { v -> (if (v == 0) "Apagado" else "Máximo $v %") to { Prefs.put(this, "nightVolume", v); onResume() } })
        }
        val idles = listOf(0, 60, 120, 180)
        Ui.row(col, "¿Sigues ahí?", "Si de noche nadie toca el mando, pregunta y apaga la tele",
            if (Night.idleMinutes(this) == 0) "Apagado" else "Tras ${Night.idleMinutes(this) / 60} h") {
            Ui.menu(this, "¿Sigues ahí?", idles.map { v -> (if (v == 0) "Apagado" else "Tras ${v / 60} h sin tocar el mando") to { Prefs.put(this, "stillThere", v); onResume() } })
        }

        Ui.section(col, "Apps con PIN")
        Ui.row(col, "Código", "4 cifras. Se pide al abrir las apps elegidas", if (Pin.hasCode(this)) "Cambiar" else "Elegir") {
            startActivity(Intent(this, PinActivity::class.java).putExtra("set", true))
        }
        Ui.row(col, "Apps que piden el código", null, Pin.apps(this).size.toString()) {
            if (!Pin.hasCode(this)) startActivity(Intent(this, PinActivity::class.java).putExtra("set", true))
            else startActivity(Intent(this, AppPickerActivity::class.java).putExtra("purpose", "pinapps"))
        }

        Ui.section(col, "Tele")
        Ui.row(col, "Entradas") { startActivity(Intent(this, InputsActivity::class.java)) }
        Ui.row(col, "Salud de la tele") { startActivity(Intent(this, HealthActivity::class.java)) }
        Ui.row(col, "Limpieza y vigía", "Apps de anuncios, DNS, red de seguridad y volver a fábrica") { startActivity(Intent(this, CleanupActivity::class.java)) }
        Ui.row(col, "Apps recomendadas", "SmartTube, Stremio, TV Bro, Kodi y VLC", if (Updates.count(this) > 0) "${Updates.count(this)} nuevas" else null) {
            startActivity(Intent(this, StoreActivity::class.java))
        }
        toggle(col, "Usar internet", "Solo para las apps recomendadas y los avisos de versiones nuevas", "internet", true)
        Ui.row(col, "Registro de problemas", null, Registro.read(this).size.toString()) { startActivity(Intent(this, RegistroActivity::class.java)) }

        Ui.section(col, "Acerca de")
        val v = packageManager.getPackageInfo(packageName, 0).versionName
        Ui.note(col, "SalaTV $v. Sin anuncios y sin cuentas. Solo usa internet para las apps recomendadas y sus versiones; nada de lo que ves sale de la tele.")
    }

    private fun pickCrProfile(p: Profile) {
        Ui.toast(this, "Leyendo los perfiles de Crunchyroll…")
        Bg.run({ Crunchy.profiles(this) }) { r ->
            val list = r.getOrNull()
            if (list.isNullOrEmpty()) { Ui.toast(this, "No se pudieron leer los perfiles"); return@run }
            Ui.menu(this, "Perfil para ${p.name}", list.map { (id, name) -> name to {
                Prefs.put(this, "crProfile:" + p.id, id); Prefs.put(this, "crProfileName:" + p.id, name); onResume()
            } })
        }
    }

    private fun toggle(col: android.widget.LinearLayout, title: String, sub: String, key: String, def: Boolean, after: () -> Unit = {}) {
        val on = Prefs.bool(this, key, def)
        Ui.row(col, title, sub, if (on) "Sí" else "No") { Prefs.put(this, key, !on); after(); onResume() }.valueColor(if (on) Ui.OK else Ui.MUTED)
    }
}
