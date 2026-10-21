package com.salatv.app

import android.animation.ValueAnimator
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.os.Bundle
import android.os.StatFs
import android.os.SystemClock
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.animation.OvershootInterpolator
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.io.File
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.URL
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/**
 * «Ajustes en dos columnas»: categories on the left, their options on the right. Moving through the
 * categories shows their options at once; → goes in, ← or Atrás comes back. «Buscar en ajustes»
 * looks through every option of every category.
 */
class SettingsActivity : Screen() {
    private class Cat(val id: String, val name: String, val sub: String, val glyph: Int)

    private val cats = listOf(
        Cat("home", "Inicio", "Cómo se ve y qué filas salen", Glyph.HOME),
        Cat("accounts", "Cuentas y servicios", "De dónde sale cada cosa y si funciona", Glyph.USER),
        Cat("wake", "Al encender", "Qué se abre nada más encender la tele", Glyph.POWER),
        Cat("remote", "Mando", "Botones, doble Inicio y sonidos", Glyph.REMOTE),
        Cat("night", "Noche", "Volumen nocturno y «¿Sigues ahí?»", Glyph.MOON),
        Cat("pin", "Apps con PIN", "Un código para las apps que elijas", Glyph.LOCK),
        Cat("tv", "Tele", "Entradas, salud, limpieza y apps", Glyph.TV),
        Cat("check", "Comprobar todo", "Que todo funcione, de un vistazo", Glyph.CHECK),
        Cat("about", "Acerca de", "Versión, privacidad y créditos", Glyph.INFO),
    )
    private var cat = "home"
    private val catRows = HashMap<String, View>()
    private lateinit var pane: LinearLayout
    private lateinit var paneScroll: ScrollView
    private lateinit var search: EditText
    private var preview: HomePreview? = null
    /** Rows that use ← and → themselves (sliders and choices). */
    private val sideways = HashSet<View>()
    private var checks: List<Check>? = null
    private var checking = false
    private var renderQueued = false

    private class Check(val name: String, val detail: String, val ok: Boolean?, val fix: (() -> Unit)? = null)

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        Fonts.init(this)
        cat = intent.getStringExtra("cat") ?: Prefs.str(this, "settingsCat", "home")!!
        if (cats.none { it.id == cat }) cat = "home"
        shell()
        render()
        catRows[cat]?.requestFocus()
    }

    override fun onRestart() { super.onRestart(); render() }

    // ---------------------------------------------------------------- the two columns

    private fun shell() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            background = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(0xFF171C29.toInt(), Ui.BG, Ui.BG))
        }
        val left = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(px(30), px(30), px(14), px(20))
            background = GradientDrawable().apply { setColor(0x33000000) }
        }
        val head = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        head.addView(GlyphView(this, Glyph.GEAR, Ui.LAMP), LinearLayout.LayoutParams(px(26), px(26)).apply { rightMargin = px(10) })
        head.addView(Ui.text(this, "Ajustes", 26f, Ui.FG, Fonts.bold))
        left.addView(head, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { leftMargin = px(8) })

        // «Buscar en ajustes».
        val box = FrameLayout(this)
        search = EditText(this).apply {
            hint = "Buscar en ajustes"
            setTextColor(Ui.FG); setHintTextColor(Ui.MUTED); textSize = 14.5f; typeface = Fonts.regular
            isSingleLine = true
            imeOptions = EditorInfo.IME_ACTION_SEARCH or EditorInfo.IME_FLAG_NO_EXTRACT_UI
            setPadding(px(40), px(9), px(14), px(9))
            background = StateListDrawable().apply {
                addState(intArrayOf(android.R.attr.state_focused), Ui.halo(context, Ui.LAMP, Ui.dp(context, 20f)))
                addState(intArrayOf(), GradientDrawable().apply { setColor(0x14FFFFFF); cornerRadius = Ui.dp(context, 20f) })
            }
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun afterTextChanged(s: Editable?) {
                    if (renderQueued) return
                    renderQueued = true
                    postDelayed({ renderQueued = false; render() }, 250)
                }
            })
            setOnEditorActionListener { _, _, _ -> firstInPane()?.requestFocus(); true }
        }
        box.addView(search, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        box.addView(GlyphView(this, Glyph.SEARCH, Ui.MUTED), FrameLayout.LayoutParams(px(18), px(18), Gravity.CENTER_VERTICAL).apply { leftMargin = px(14) })
        left.addView(box, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = px(18); bottomMargin = px(10) })

        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        for (c in cats) list.addView(catRow(c), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = px(2) })
        left.addView(ScrollView(this).apply { isVerticalScrollBarEnabled = false; addView(list) },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        root.addView(left, LinearLayout.LayoutParams(px(268), ViewGroup.LayoutParams.MATCH_PARENT))
        root.addView(View(this).apply { setBackgroundColor(0x14FFFFFF) }, LinearLayout.LayoutParams(px(1), ViewGroup.LayoutParams.MATCH_PARENT))

        pane = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(px(30), px(30), px(44), px(40)) }
        paneScroll = ScrollView(this).apply { isVerticalScrollBarEnabled = false; isSmoothScrollingEnabled = true; addView(pane) }
        root.addView(paneScroll, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
        setContentView(root)
    }

    private fun catRow(c: Cat): View {
        val r = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            minimumHeight = px(42)
            setPadding(px(12), px(6), px(12), px(6))
            isFocusable = true; isClickable = true
            background = StateListDrawable().apply {
                addState(intArrayOf(android.R.attr.state_focused), Ui.halo(context, Ui.LAMP, Ui.dp(context, 12f)))
                addState(intArrayOf(android.R.attr.state_selected), GradientDrawable().apply { setColor(Ui.alpha(Ui.LAMP, 0.13f)); cornerRadius = Ui.dp(context, 12f) })
                addState(intArrayOf(), GradientDrawable().apply { setColor(Color.TRANSPARENT); cornerRadius = Ui.dp(context, 12f) })
            }
        }
        val g = GlyphView(this, c.glyph, Ui.MUTED)
        r.addView(g, LinearLayout.LayoutParams(px(20), px(20)).apply { rightMargin = px(12) })
        val t = Ui.text(this, c.name, 15f, Ui.FG, Fonts.medium)
        r.addView(t, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        r.setOnFocusChangeListener { _, has ->
            g.color = if (has || r.isSelected) Ui.LAMP else Ui.MUTED
            if (has && (cat != c.id || search.text.isNotEmpty())) {
                cat = c.id
                Prefs.put(this, "settingsCat", c.id)
                if (search.text.isNotEmpty()) search.setText("") else render()
            }
        }
        r.setOnClickListener { firstInPane()?.requestFocus() }
        r.tag = g
        catRows[c.id] = r
        return r
    }

    private fun firstInPane(): View? = pane.getFocusables(View.FOCUS_DOWN).firstOrNull { it.isShown }

    private fun inPane(v: View?): Boolean {
        var p: Any? = v
        while (p is View) { if (p === pane) return true; p = p.parent }
        return false
    }

    override fun dispatchKeyEvent(ev: KeyEvent): Boolean {
        val f = currentFocus
        if (ev.action == KeyEvent.ACTION_DOWN && ev.keyCode == KeyEvent.KEYCODE_DPAD_LEFT && f != null && inPane(f) && f !in sideways) {
            val next = f.focusSearch(View.FOCUS_LEFT)
            if (next == null || !inPane(next)) { back(); return true }
        }
        return super.dispatchKeyEvent(ev)
    }

    private fun back() {
        if (search.text.isNotEmpty()) search.requestFocus() else catRows[cat]?.requestFocus()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        val f = currentFocus
        when {
            inPane(f) -> back()
            search.text.isNotEmpty() -> { search.setText(""); catRows[cat]?.requestFocus() }
            else -> @Suppress("DEPRECATION") super.onBackPressed()
        }
    }

    // ---------------------------------------------------------------- the right column

    private fun render() {
        val f = currentFocus
        val keep = if (inPane(f)) f?.tag as? String else null
        val q = search.text.toString().trim()
        pane.removeAllViews(); sideways.clear(); preview = null
        val p = P(pane, q.takeIf { it.length >= 2 }?.lowercase())
        if (p.filter != null) {
            header("Resultados de «$q»", null)
            for (c in cats) { p.category(c.name); build(c.id, p) }
            if (p.matches == 0) Ui.note(pane, "Nada coincide con «$q». Prueba con «volumen», «Crunchyroll», «dock» o «carrusel».")
        } else {
            val c = cats.first { it.id == cat }
            header(c.name, c.sub)
            build(c.id, p)
        }
        for ((id, v) in catRows) {
            v.isSelected = p.filter == null && id == cat
            (v.tag as? GlyphView)?.color = if (v.isSelected || v.isFocused) Ui.LAMP else Ui.MUTED
        }
        if (keep != null) pane.findViewWithTag<View>(keep)?.requestFocus()
        if (keep == null && !inPane(currentFocus)) paneScroll.scrollTo(0, 0)
    }

    private fun refresh() = render()

    private fun header(title: String, sub: String?) {
        pane.addView(Ui.text(this, title, 24f, Ui.FG, Fonts.bold))
        if (sub != null) pane.addView(Ui.text(this, sub, 13.5f, Ui.MUTED, Fonts.light), lp(4))
    }

    private fun lp(top: Int) = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = px(top) }
    private fun px(v: Int) = Ui.px(this, v)

    /** Writes options into the pane; with a [filter] only the ones that match, under their category's name. */
    private inner class P(val col: LinearLayout, val filter: String?) {
        var matches = 0
        private var pending: String? = null

        fun category(name: String) { pending = name }

        private fun ok(title: String, sub: String?): Boolean {
            if (filter == null) return true
            val hay = Search.fold("$title ${sub ?: ""}")
            if (!filter.split(' ').filter { it.isNotBlank() }.all { hay.contains(Search.fold(it)) }) return false
            pending?.let { Ui.section(col, it); pending = null }
            matches++
            return true
        }

        fun section(s: String) { if (filter == null) Ui.section(col, s) }
        fun note(s: String, color: Int = Ui.MUTED) { if (filter == null) Ui.note(col, s, color) }

        fun row(title: String, sub: String? = null, value: String? = null, valueColor: Int = 0, onClick: (() -> Unit)? = null): Ui.Row? {
            if (!ok(title, sub)) return null
            val r = Ui.row(col, title, sub, value, onClick = onClick)
            r.view.tag = title
            if (valueColor != 0) r.valueColor(valueColor)
            return r
        }

        /** «Interruptores»: OK flips it, the knob slides over with a little bounce. */
        fun switch(title: String, sub: String?, get: () -> Boolean, set: (Boolean) -> Unit) {
            if (!ok(title, sub)) return
            var sw: SwitchView? = null
            val r = Ui.row(col, title, sub, null) {
                val v = !get(); set(v); sw?.set(v, true); Sounds.tick(this@SettingsActivity); preview?.refresh()
            }
            r.view.tag = title
            sw = SwitchView(this@SettingsActivity, get())
            r.view.addView(sw, LinearLayout.LayoutParams(px(42), px(24)).apply { leftMargin = px(12) })
        }

        fun pref(title: String, sub: String?, key: String, def: Boolean, after: () -> Unit = {}) =
            switch(title, sub, { Prefs.bool(this@SettingsActivity, key, def) }) { Prefs.put(this@SettingsActivity, key, it); after() }

        /** «Deslizadores»: ← and → move it a step; the value is written next to it. */
        fun slider(title: String, sub: String?, steps: List<Int>, label: (Int) -> String, get: () -> Int, set: (Int) -> Unit) {
            if (!ok(title, sub)) return
            val r = Ui.row(col, title, sub, label(get()), onClick = {})
            r.view.tag = title
            var i = steps.indexOf(get()).let { if (it < 0) steps.indexOfFirst { s -> s >= get() }.coerceAtLeast(0) else it }
            val bar = SliderView(this@SettingsActivity, steps.size, i, r.view)
            r.view.addView(bar, LinearLayout.LayoutParams(px(150), px(26)).apply { leftMargin = px(14) })
            r.value.minWidth = px(84)
            sideways += r.view
            r.view.setOnFocusChangeListener { _, _ -> bar.invalidate() }
            r.view.setOnKeyListener { _, code, ev ->
                if (ev.action != KeyEvent.ACTION_DOWN) return@setOnKeyListener code == KeyEvent.KEYCODE_DPAD_LEFT || code == KeyEvent.KEYCODE_DPAD_RIGHT
                val d = when (code) { KeyEvent.KEYCODE_DPAD_LEFT -> -1; KeyEvent.KEYCODE_DPAD_RIGHT -> 1; else -> return@setOnKeyListener false }
                val n = (i + d).coerceIn(0, steps.size - 1)
                if (n == i) { if (d < 0 && i == 0 && ev.repeatCount == 0) back(); return@setOnKeyListener true }
                i = n; set(steps[i]); r.value.text = label(steps[i]); bar.moveTo(i)
                Sounds.tick(this@SettingsActivity); preview?.refresh()
                true
            }
        }

        /** A choice among a few: ‹ value › with ← and →, or OK for the list. */
        fun choice(title: String, sub: String?, options: List<Pair<String, String>>, get: () -> String, set: (String) -> Unit) {
            if (!ok(title, sub)) return
            fun name() = options.firstOrNull { it.first == get() }?.second ?: options.first().second
            lateinit var r: Ui.Row
            r = Ui.row(col, title, sub, "‹  ${name()}  ›") {
                Ui.menu(this@SettingsActivity, title, options.map { (k, n) -> n to { set(k); r.value.text = "‹  $n  ›"; preview?.refresh() } })
            }
            r.view.tag = title
            r.valueColor(Ui.LAMP)
            sideways += r.view
            r.view.setOnKeyListener { _, code, ev ->
                val d = when (code) { KeyEvent.KEYCODE_DPAD_LEFT -> -1; KeyEvent.KEYCODE_DPAD_RIGHT -> 1; else -> return@setOnKeyListener false }
                if (ev.action != KeyEvent.ACTION_DOWN) return@setOnKeyListener true
                val i = options.indexOfFirst { it.first == get() }.coerceAtLeast(0)
                val n = i + d
                if (n !in options.indices) { if (d < 0 && ev.repeatCount == 0) back(); return@setOnKeyListener true }
                set(options[n].first); r.value.text = "‹  ${options[n].second}  ›"
                Sounds.tick(this@SettingsActivity); preview?.refresh()
                true
            }
        }

        fun view(v: View, top: Int = 10) { if (filter == null) col.addView(v, lp(top)) }
    }

    private fun build(id: String, p: P) {
        when (id) {
            "home" -> home(p)
            "accounts" -> accounts(p)
            "wake" -> wake(p)
            "remote" -> remote(p)
            "night" -> night(p)
            "pin" -> pin(p)
            "tv" -> tv(p)
            "check" -> check(p)
            "about" -> about(p)
        }
    }

    // ---------------------------------------------------------------- categories

    private fun home(p: P) {
        val prof = Prefs.current(this)
        if (p.filter == null) {
            // «Vista previa»: a small home that changes as you touch the options below.
            val card = FrameLayout(this).apply {
                background = GradientDrawable().apply { setColor(0xFF05060A.toInt()); cornerRadius = Ui.dp(context, 14f); setStroke(px(1), 0x24FFFFFF) }
                setPadding(px(1), px(1), px(1), px(1))
                clipToOutline = true
            }
            val pv = HomePreview(this)
            card.addView(pv, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, px(230)))
            card.addView(Ui.text(this, "VISTA PREVIA", 10f, Color.WHITE, Fonts.medium).apply {
                letterSpacing = 0.16f; setPadding(px(8), px(4), px(8), px(4))
                background = GradientDrawable().apply { setColor(0x99000000.toInt()); cornerRadius = Ui.dp(context, 8f) }
            }, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM or Gravity.END).apply { setMargins(0, 0, px(10), px(10)) })
            p.view(card, 16)
            preview = pv
        }

        p.section("Aspecto")
        p.choice("Tema", "De día, de noche o según el atardecer de tu ciudad",
            listOf("auto" to "Atardecer", "day" to "Día", "night" to "Noche"), { Look.mode(this) }) { Prefs.put(this, "theme", it) }
        val city = Cities.current(this)
        val t = Sun.times(city)
        p.row("Ciudad", t?.let { "Amanece a las ${Sun.fmt(it.first)} · atardece a las ${Sun.fmt(it.second)}" }, city.name) {
            startActivity(Intent(this, CityActivity::class.java))
        }
        val dens = listOf("Compacto", "Normal", "Grande")
        p.slider("Tamaño del inicio", "Tarjetas, textos y separaciones", listOf(0, 1, 2), { dens[it] },
            { Prefs.int(this, "density", 1) }) { Prefs.put(this, "density", it) }
        p.pref("Fondo que se mueve", "La imagen de arriba se acerca muy despacio", "kenBurns", true)

        p.section("Carrusel")
        p.pref("Carrusel de tendencias", "Tus episodios nuevos y lo más visto de la semana, arriba del todo", "carousel", true)
        p.slider("Velocidad del carrusel", "Cada cuánto pasa a la siguiente", listOf(5, 8, 12, 16, 20), { "cada $it s" },
            { Prefs.int(this, "carouselSpeed", 8) }) { Prefs.put(this, "carouselSpeed", it) }
        val apps = Tmdb.streamingApps(this)
        val chosen = Prefs.set(this, "carouselSvc").filter { it in apps }
        p.row("Servicios del carrusel", "De qué apps salen las tendencias", if (chosen.isEmpty()) "Todas" else "${chosen.size} de ${apps.size}") {
            servicesMenu(apps)
        }

        p.section("Filas")
        p.pref("Seguir viendo", "Capítulos y pelis a medias de Crunchyroll y de lo que veas en esta tele", "contRow", true)
        if (!MediaWatcher.enabled(this)) p.note("Para guardar lo que ves en esta tele hace falta un permiso: Comprobar todo › Permisos.", Ui.WARN)
        p.pref("Mi lista · Crunchyroll", "Lo que guardaste para ver después", "listRow", true)
        p.pref("Porque viste…", "Parecidas a lo último que viste", "becauseRow", true)
        p.pref("Anime de la temporada", "Los estrenos de Crunchyroll", "seasonRow", true)

        p.section("Apps")
        p.switch("El dock cambia con la hora", "Las apps se ordenan según lo que abres a cada hora", { prof.autoDock }) {
            prof.autoDock = it; Prefs.saveProfile(this, prof)
        }
        if (prof.autoDock && !UsageRank.has(this)) p.note("Hace falta el permiso de uso de apps: Comprobar todo › Permisos.", Ui.WARN)
        p.row("Apps del dock", "Las que están siempre a mano", prof.pinned.size.toString()) { startActivity(Intent(this, AppPickerActivity::class.java).putExtra("purpose", "pin")) }
        p.choice("Ordenar «Todas tus apps»", null, Logic.SORTS, { prof.sort }) { prof.sort = it; Prefs.saveProfile(this, prof) }
        p.row("Apps ocultas", null, Prefs.hidden(this).size.toString()) { startActivity(Intent(this, HiddenActivity::class.java)) }
        p.row("Perfiles", "Cada uno con su dock y su Seguir viendo", Prefs.profiles(this).size.toString()) { startActivity(Intent(this, ProfilesActivity::class.java)) }
    }

    private fun servicesMenu(apps: List<String>) {
        val on = Prefs.set(this, "carouselSvc").filter { it in apps }.toMutableSet()
        val items = mutableListOf<Pair<String, () -> Unit>>()
        items += (if (on.isEmpty()) "✓  Todas" else "    Todas") to { Prefs.put(this, "carouselSvc", emptySet<String>()); refresh() }
        for (pkg in apps) {
            val has = pkg in on
            items += ((if (has) "✓  " else "    ") + Services.name(pkg)) to {
                if (has) on -= pkg else on += pkg
                Prefs.put(this, "carouselSvc", if (on.size == apps.size) emptySet() else on.toSet())
                refresh(); servicesMenu(apps)
            }
        }
        Ui.menu(this, "Servicios del carrusel", items)
    }

    private fun accounts(p: P) {
        val prof = Prefs.current(this)
        p.section("Crunchyroll")
        if (Crunchy.linked(this)) {
            val crName = Prefs.str(this, "crProfileName:" + prof.id) ?: "el principal"
            val feed = File(filesDir, "feed-${prof.id}.json")
            p.row("Crunchyroll", "${Prefs.str(this, "crEmail") ?: "Tu cuenta"} · perfil de ${prof.name}: $crName" +
                (if (feed.exists()) " · leído ${ago(feed.lastModified())}" else ""), "Conectado", Ui.OK) {
                Ui.menu(this, "Crunchyroll", listOf(
                    "Perfil de Crunchyroll para ${prof.name}" to { pickCrProfile(prof) },
                    "Desconectar" to { Ui.confirm(this, "¿Desconectar Crunchyroll?", "Sí, desconectar") { Crunchy.logout(this); refresh() } },
                ))
            }
            p.note("Lee tu Seguir viendo, los episodios nuevos, la temporada y tu lista. No guarda la contraseña.")
        } else {
            p.row("Conectar Crunchyroll", "Seguir viendo, episodios nuevos, la temporada y Mi lista", "Sin conectar", Ui.WARN) { crLogin() }
            p.note("También desde el PC, sin escribir con el mando: tools\\claves.ps1. No oficial: puede dejar de funcionar.")
        }

        p.section("Recomendaciones")
        val tmdb = Tmdb.token(this) != null
        p.row("TMDB", "Tendencias, Explorar, fichas y en qué app está cada título",
            if (!tmdb) "Falta la clave" else if (!Tmdb.enabled(this)) "Sin internet" else "Activo", if (tmdb && Tmdb.enabled(this)) Ui.OK else Ui.WARN)
        p.choice("País", "Qué hay en cada app cambia según el país", Tmdb.REGIONS, { Tmdb.region(this) }) {
            Prefs.put(this, "region", it); Prefs.put(this, "trendRegion", "")
        }
        val jw = File(filesDir, "jw").listFiles()?.size ?: 0
        p.row("Enlaces directos", "De JustWatch: «Ver» entra directo en la serie o la peli. No oficial.", if (jw == 0) "Activo" else "$jw guardados", Ui.OK) {
            if (jw > 0) Ui.confirm(this, "¿Olvidar los $jw enlaces guardados?", "Sí, buscarlos otra vez") {
                File(filesDir, "jw").listFiles()?.forEach { it.delete() }; refresh()
            }
        }

        p.section("Esta tele")
        p.row("Lo que veas en esta tele", "Para Seguir viendo sin cuentas, con lo que se reproduce aquí",
            if (MediaWatcher.enabled(this)) "Concedido" else "Falta el permiso", if (MediaWatcher.enabled(this)) Ui.OK else Ui.WARN) {
            startActivity(Intent(this, CleanupActivity::class.java))
        }
        p.pref("Usar internet", "Sin internet no hay tendencias, Crunchyroll ni versiones nuevas", "internet", true)
        val at = Prefs.long(this, "netAt", 0L)
        p.row("Actualizar todo ahora", if (at > 0) "La última vez fue ${ago(at)}" else "Tendencias, Seguir viendo, Mi lista y la temporada") {
            Ui.toast(this, "Actualizando…")
            val app = applicationContext
            Bg.run({
                Tmdb.refreshTrending(app, true)
                val feed = Feed.refresh(app)
                try { Feed.refreshSeason(app) } catch (_: Exception) {}
                try { MyList.refresh(app) } catch (_: Exception) {}
                try { Because.refresh(app, feed) } catch (_: Exception) {}
                Feed.fresh(app, feed)
                Prefs.put(app, "netAt", System.currentTimeMillis())
            }) { r ->
                Ui.toast(this, if (r.isSuccess) "Todo al día" else "No se pudo: ${r.exceptionOrNull()?.message}")
                refresh()
            }
        }
    }

    private fun crLogin() {
        Ui.prompt(this, "Correo de Crunchyroll", Prefs.str(this, "crEmail") ?: "") { email ->
            Ui.prompt(this, "Contraseña", "", password = true) { pass ->
                Ui.toast(this, "Conectando con Crunchyroll…")
                Bg.run({ Crunchy.login(this, email, pass) }) { r ->
                    r.onSuccess { Ui.toast(this, it); Prefs.put(this, "feedAt", 0L); refresh() }
                        .onFailure { Ui.toast(this, "Crunchyroll no aceptó el inicio de sesión"); Registro.add(this, "Crunchyroll", "Inicio de sesión: ${it.message}") }
                }
            }
        }
    }

    private fun pickCrProfile(prof: Profile) {
        Ui.toast(this, "Leyendo los perfiles de Crunchyroll…")
        Bg.run({ Crunchy.profiles(this) }) { r ->
            val list = r.getOrNull()
            if (list.isNullOrEmpty()) { Ui.toast(this, "No se pudieron leer los perfiles"); return@run }
            Ui.menu(this, "Perfil para ${prof.name}", list.map { (id, name) -> name to {
                Prefs.put(this, "crProfile:" + prof.id, id); Prefs.put(this, "crProfileName:" + prof.id, name); refresh()
            } })
        }
    }

    private fun wake(p: P) {
        val wake = Prefs.str(this, "wake", "home")!!
        val wakeName = when {
            wake == "usual" -> "Lo que más uso a esa hora"
            wake == "last" -> "La última app"
            wake.startsWith("app:") -> AppRepo.byPkg(wake.removePrefix("app:"))?.label ?: "Una app"
            wake.startsWith("input:") -> Prefs.inputNames(this)[wake.removePrefix("input:")] ?: "Una entrada"
            wake == "resume" -> "Seguir donde lo dejé"
            else -> "El inicio de SalaTV"
        }
        p.row("Al encender la tele", "Qué se abre nada más encender", wakeName) {
            Ui.menu(this, "Al encender la tele", listOf(
                "El inicio de SalaTV" to { Prefs.put(this, "wake", "home"); refresh() },
                "Seguir donde lo dejé" to { Prefs.put(this, "wake", "resume"); refresh() },
                "Lo que más uso a esa hora" to { Prefs.put(this, "wake", "usual"); refresh() },
                "La última app" to { Prefs.put(this, "wake", "last"); refresh() },
                "Una app concreta…" to { startActivity(Intent(this, AppPickerActivity::class.java).putExtra("purpose", "wake")) },
                "Una entrada…" to { startActivity(Intent(this, InputsActivity::class.java).putExtra("pickWake", true)) },
            ))
        }
        p.note("«Seguir donde lo dejé» pregunta si quieres seguir con el último capítulo que veías.")
    }

    private fun remote(p: P) {
        p.row("Botones del mando", "Que un botón abra la app que quieras") { startActivity(Intent(this, KeysActivity::class.java)) }
        p.pref("Doble Inicio", "Pulsar Inicio dos veces seguidas vuelve a la app donde estabas", "doubleHome", true) { Vigia.reload() }
        p.pref("Sonidos suaves", "Un clic muy bajo al moverse y al abrir", "sounds", false)
    }

    private fun night(p: P) {
        val hours = listOf(21, 22, 23, 0)
        p.row("Horario de noche", "Para el volumen nocturno y «¿Sigues ahí?»", "de ${Night.from(this)}:00 a ${Night.to(this)}:00") {
            Ui.menu(this, "Empieza a las…", hours.map { h -> "$h:00" to {
                Prefs.put(this, "nightFrom", h)
                Ui.menu(this, "Termina a las…", listOf(6, 7, 8, 9).map { e -> "$e:00" to { Prefs.put(this, "nightTo", e); refresh() } })
            } })
        }
        p.slider("Volumen nocturno", "Tope de volumen por la noche", listOf(0, 20, 30, 40, 50), { if (it == 0) "Apagado" else "máx. $it %" },
            { Night.cap(this) }) { Prefs.put(this, "nightVolume", it) }
        p.note("No funciona si el sonido va por HDMI-CEC a una barra de sonido.")
        p.slider("¿Sigues ahí?", "Si de noche nadie toca el mando, pregunta y apaga la tele", listOf(0, 60, 120, 180),
            { if (it == 0) "Apagado" else "tras ${it / 60} h" }, { Night.idleMinutes(this) }) { Prefs.put(this, "stillThere", it) }
    }

    private fun pin(p: P) {
        p.row("Código", "4 cifras. Se pide al abrir las apps elegidas", if (Pin.hasCode(this)) "Cambiar" else "Elegir") {
            startActivity(Intent(this, PinActivity::class.java).putExtra("set", true))
        }
        p.row("Apps que piden el código", null, Pin.apps(this).size.toString()) {
            if (!Pin.hasCode(this)) startActivity(Intent(this, PinActivity::class.java).putExtra("set", true))
            else startActivity(Intent(this, AppPickerActivity::class.java).putExtra("purpose", "pinapps"))
        }
    }

    private fun tv(p: P) {
        p.row("Entradas", "Consola, decodificador y TV") { startActivity(Intent(this, InputsActivity::class.java)) }
        p.row("Salud de la tele", "Memoria, espacio y liberar RAM") { startActivity(Intent(this, HealthActivity::class.java)) }
        p.row("Limpieza y vigía", "Apps de anuncios, DNS, red de seguridad y volver a fábrica") { startActivity(Intent(this, CleanupActivity::class.java)) }
        p.row("Apps recomendadas", "SmartTube, Stremio, TV Bro, Kodi y VLC", if (Updates.count(this) > 0) "${Updates.count(this)} nuevas" else null) {
            startActivity(Intent(this, StoreActivity::class.java))
        }
        p.row("Registro de problemas", "Lo que falló y cuándo", Registro.read(this).size.toString()) { startActivity(Intent(this, RegistroActivity::class.java)) }
    }

    // ---------------------------------------------------------------- «Comprobar todo»

    private fun check(p: P) {
        if (p.filter != null) { p.row("Comprobar todo", "Vigía, permisos, hora, internet, DNS, Crunchyroll y TMDB") { cat = "check"; search.setText("") }; return }
        val list = checks
        if (list == null && !checking) runChecks()
        if (list == null) { p.note("Comprobando…", Ui.LAMP); return }
        val bad = list.count { it.ok == false }
        pane.addView(summary(bad, list.size), lp(16))
        p.section("Resultado")
        for (c in list) {
            val (v, col) = when (c.ok) { true -> "Bien" to Ui.OK; false -> (if (c.fix != null) "Arreglar" else "Revisar") to Ui.WARN; null -> "—" to Ui.MUTED }
            p.row(c.name, c.detail, v, col, onClick = c.fix ?: {})
        }
        p.row("Comprobar otra vez") { checks = null; runChecks(); refresh() }
    }

    private fun summary(bad: Int, total: Int): View {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(px(16), px(14), px(16), px(14))
            val col = if (bad == 0) Ui.OK else Ui.WARN
            background = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(Ui.alpha(col, 0.22f), Ui.alpha(col, 0.05f))).apply {
                cornerRadius = Ui.dp(context, 14f); setStroke(px(1), Ui.alpha(col, 0.5f))
            }
        }
        box.addView(GlyphView(this, if (bad == 0) Glyph.CHECK else Glyph.WARN, if (bad == 0) Ui.OK else Ui.WARN), LinearLayout.LayoutParams(px(30), px(30)).apply { rightMargin = px(14) })
        val t = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        t.addView(Ui.text(this, if (bad == 0) "Todo funciona" else if (bad == 1) "Hay 1 cosa por revisar" else "Hay $bad cosas por revisar", 18f, Ui.FG, Fonts.bold))
        t.addView(Ui.text(this, "$total comprobaciones · ahora mismo", 12.5f, Ui.MUTED, Fonts.light), lp(3))
        box.addView(t)
        return box
    }

    private fun runChecks() {
        checking = true
        val app = applicationContext
        Bg.run({
            val out = mutableListOf<Check>()
            val vigia = Cleaner.vigiaOn(app)
            out += Check("Vigía del botón Inicio", if (vigia) "Inicio te trae a SalaTV" else "Inicio lleva al inicio de Amazon", vigia) { startActivity(Intent(this, CleanupActivity::class.java)) }
            val missing = mutableListOf<String>()
            if (!UsageRank.has(app)) missing += "uso de apps"
            if (!MediaWatcher.enabled(app)) missing += "Seguir viendo"
            if (!Cleaner.canWriteSecure(app)) missing += "ajustes"
            if (!Installer.allowed(app)) missing += "instalar apps"
            out += Check("Permisos", if (missing.isEmpty()) "Todos concedidos" else "Faltan: " + missing.joinToString(", "), missing.isEmpty()) {
                startActivity(Intent(this, CleanupActivity::class.java))
            }
            val net = Net.describe(app)
            out += Check("Internet", net.text, net.online) { Sys.open(this, android.provider.Settings.ACTION_WIFI_SETTINGS) }
            val dns = try { InetAddress.getByName("api.themoviedb.org"); true } catch (e: Exception) { false }
            out += Check("Nombres de internet (DNS)", if (dns) (Cleaner.dnsHost(app)?.let { "Con $it" } ?: "DNS normal") else "No se resuelven: el DNS privado puede estar caído",
                if (net.online) dns else null) { startActivity(Intent(this, CleanupActivity::class.java)) }
            val skew = try {
                val con = URL("https://clients3.google.com/generate_204").openConnection() as HttpURLConnection
                con.requestMethod = "HEAD"; con.connectTimeout = 6000; con.readTimeout = 6000
                con.connect(); val d = con.date; con.disconnect()
                if (d > 0) abs(d - System.currentTimeMillis()) else null
            } catch (e: Exception) { null }
            out += Check("Hora de la tele", when {
                skew == null -> "No se pudo comparar sin internet"
                skew < 5 * 60_000L -> "En hora"
                else -> "Va ${skew / 60_000} min desfasada: las apps pueden fallar al conectar"
            }, skew?.let { it < 5 * 60_000L }) { Sys.open(this, android.provider.Settings.ACTION_DATE_SETTINGS) }
            out += when {
                Tmdb.token(app) == null -> Check("TMDB", "Falta la clave en local.properties al compilar", false)
                !net.online -> Check("TMDB", "Sin internet", null)
                else -> try { Tmdb.ping(app); Check("TMDB", "Responde", true) } catch (e: Exception) { Check("TMDB", "No responde: ${e.message}", false) }
            }
            out += when {
                !Crunchy.linked(app) -> Check("Crunchyroll", "Sin conectar", null) { cat = "accounts"; refresh() }
                !net.online -> Check("Crunchyroll", "Sin internet", null)
                else -> try { val n = Crunchy.profiles(app).size; Check("Crunchyroll", "Sesión activa · $n perfiles", true) }
                    catch (e: Exception) { Check("Crunchyroll", "La sesión caducó: vuelve a conectar", false) { crLogin() } }
            }
            val st = StatFs(app.filesDir.path)
            val free = st.availableBytes / (1024 * 1024)
            out += Check("Espacio libre", "$free MB libres", free > 500) { startActivity(Intent(this, HealthActivity::class.java)) }
            val crashes = SafetyNet.recentCrashes(app)
            out += Check("SalaTV", if (SafetyNet.safeMode(app)) "En modo seguro tras varios cierres" else if (crashes > 0) "$crashes cierres en 24 h" else "Sin cierres en 24 h",
                !SafetyNet.safeMode(app) && crashes == 0) { startActivity(Intent(this, RegistroActivity::class.java)) }
            out
        }) { r ->
            checking = false
            checks = r.getOrNull() ?: listOf(Check("Comprobar todo", "No se pudo terminar: ${r.exceptionOrNull()?.message}", false))
            if (cat == "check" && search.text.isEmpty()) render()
        }
    }

    private fun about(p: P) {
        val v = packageManager.getPackageInfo(packageName, 0).versionName
        p.row("SalaTV", "Inicio libre para Fire TV y Android TV", v)
        p.note("Sin anuncios y sin cuentas propias. Lo que ves no sale de la tele: SalaTV solo pide a TMDB, JustWatch y Crunchyroll lo que necesita mostrar.")
        p.section("Créditos")
        p.note("Listas de limpieza de CleanYourTV (MIT). Tipografía Outfit (OFL). Datos de películas y series de TMDB: este producto usa la API de TMDB, pero TMDB no lo respalda ni lo certifica. Enlaces directos gracias a JustWatch.")
    }

    private fun ago(t: Long): String {
        val m = (System.currentTimeMillis() - t) / 60_000
        return when {
            m < 1 -> "hace un momento"
            m < 60 -> "hace $m min"
            m < 24 * 60 -> "hace ${m / 60} h"
            else -> "hace ${m / (24 * 60)} días"
        }
    }
}

/** Accent-insensitive search text. */
object Search {
    fun fold(s: String): String = java.text.Normalizer.normalize(s.lowercase(), java.text.Normalizer.Form.NFD).replace(Regex("\\p{Mn}+"), "")
}

/** «Interruptores»: a pill with a knob that slides with a small overshoot. */
class SwitchView(c: Context, private var on: Boolean) : View(c) {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val r = RectF()
    private var pos = if (on) 1f else 0f

    fun set(v: Boolean, animate: Boolean) {
        on = v
        if (!animate) { pos = if (v) 1f else 0f; invalidate(); return }
        ValueAnimator.ofFloat(pos, if (v) 1f else 0f).apply {
            duration = 260; interpolator = OvershootInterpolator(2.2f)
            addUpdateListener { pos = it.animatedValue as Float; invalidate() }
            start()
        }
    }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        r.set(0f, 0f, w, h)
        p.color = Ui.lerp(0x33FFFFFF, Ui.OK, pos.coerceIn(0f, 1f))
        c.drawRoundRect(r, h / 2, h / 2, p)
        val k = h / 2 - Ui.dp(context, 3f)
        val x = h / 2 + (w - h) * pos
        p.color = 0x55000000; c.drawCircle(x, h / 2 + Ui.dp(context, 1f), k, p)
        p.color = Color.WHITE; c.drawCircle(x, h / 2, k, p)
    }
}

/** «Deslizadores»: a track with one dot per step; the knob glows while its row has the focus. */
class SliderView(c: Context, private val n: Int, i: Int, private val row: View) : View(c) {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val r = RectF()
    private var pos = i.toFloat()

    fun moveTo(i: Int) {
        ValueAnimator.ofFloat(pos, i.toFloat()).apply {
            duration = 220; interpolator = OvershootInterpolator(1.6f)
            addUpdateListener { pos = it.animatedValue as Float; invalidate() }
            start()
        }
    }

    override fun onDraw(c: Canvas) {
        val h = height.toFloat(); val k = Ui.dp(context, 8f)
        val x0 = k; val x1 = width - k
        val cy = h / 2
        val t = Ui.dp(context, 2f)
        fun xAt(f: Float) = x0 + (x1 - x0) * (if (n <= 1) 0f else f / (n - 1))
        r.set(x0, cy - t, x1, cy + t); p.color = 0x33FFFFFF; c.drawRoundRect(r, t, t, p)
        r.right = xAt(pos); p.color = Ui.LAMP; c.drawRoundRect(r, t, t, p)
        for (s in 0 until n) { p.color = if (s <= pos + 0.01f) Ui.LAMP else 0x55FFFFFF; c.drawCircle(xAt(s.toFloat()), cy, Ui.dp(context, 2.6f), p) }
        val x = xAt(pos)
        if (row.isFocused) { p.color = Ui.alpha(Ui.LAMP, 0.35f); c.drawCircle(x, cy, k * 1.25f, p) }
        p.color = Color.WHITE; c.drawCircle(x, cy, if (row.isFocused) k * 0.85f else k * 0.7f, p)
    }
}

/** Small line icons for the settings, drawn in a 24-unit box. */
object Glyph {
    const val HOME = 1; const val USER = 2; const val POWER = 3; const val REMOTE = 4; const val MOON = 5
    const val LOCK = 6; const val TV = 7; const val CHECK = 8; const val INFO = 9; const val GEAR = 10
    const val SEARCH = 11; const val WARN = 12
}

class GlyphView(c: Context, private val type: Int, color: Int) : View(c) {
    var color = color
        set(v) { field = v; invalidate() }
    private val s = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
    private val f = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()
    private val r = RectF()

    override fun onDraw(c: Canvas) {
        val k = width / 24f
        fun x(v: Float) = v * k
        s.color = color; s.strokeWidth = 1.9f * k; f.color = color
        path.reset()
        when (type) {
            Glyph.HOME -> { path.moveTo(x(3f), x(11f)); path.lineTo(x(12f), x(3.5f)); path.lineTo(x(21f), x(11f)); c.drawPath(path, s)
                path.reset(); path.moveTo(x(5.5f), x(9.5f)); path.lineTo(x(5.5f), x(20f)); path.lineTo(x(18.5f), x(20f)); path.lineTo(x(18.5f), x(9.5f)); c.drawPath(path, s) }
            Glyph.USER -> { c.drawCircle(x(12f), x(8f), x(4f), s); r.set(x(4f), x(14f), x(20f), x(28f)); c.drawArc(r, 180f, 180f, false, s) }
            Glyph.POWER -> { r.set(x(4f), x(4.5f), x(20f), x(20.5f)); c.drawArc(r, -60f, 300f, false, s); c.drawLine(x(12f), x(2.5f), x(12f), x(11f), s) }
            Glyph.REMOTE -> { r.set(x(7.5f), x(2.5f), x(16.5f), x(21.5f)); c.drawRoundRect(r, x(3f), x(3f), s); c.drawCircle(x(12f), x(8f), x(2.2f), s); c.drawCircle(x(12f), x(14f), x(0.9f), f); c.drawCircle(x(12f), x(17.5f), x(0.9f), f) }
            Glyph.MOON -> { path.addCircle(x(11f), x(12f), x(8.5f), Path.Direction.CW); val cut = Path().apply { addCircle(x(16f), x(8f), x(7f), Path.Direction.CW) }
                path.op(cut, Path.Op.DIFFERENCE); c.drawPath(path, s) }
            Glyph.LOCK -> { r.set(x(5f), x(10.5f), x(19f), x(21f)); c.drawRoundRect(r, x(2.5f), x(2.5f), s); r.set(x(8f), x(3f), x(16f), x(14f)); c.drawArc(r, 180f, 180f, false, s)
                c.drawLine(x(8f), x(8.5f), x(8f), x(10.5f), s); c.drawLine(x(16f), x(8.5f), x(16f), x(10.5f), s) }
            Glyph.TV -> { r.set(x(2.5f), x(4.5f), x(21.5f), x(17f)); c.drawRoundRect(r, x(2f), x(2f), s); c.drawLine(x(8f), x(20.5f), x(16f), x(20.5f), s) }
            Glyph.CHECK -> { c.drawCircle(x(12f), x(12f), x(9.5f), s); path.moveTo(x(7.5f), x(12.5f)); path.lineTo(x(10.5f), x(15.5f)); path.lineTo(x(16.5f), x(9f)); c.drawPath(path, s) }
            Glyph.INFO -> { c.drawCircle(x(12f), x(12f), x(9.5f), s); c.drawLine(x(12f), x(11f), x(12f), x(17f), s); c.drawCircle(x(12f), x(7.5f), x(1.2f), f) }
            Glyph.SEARCH -> { c.drawCircle(x(11f), x(11f), x(6.5f), s); c.drawLine(x(16f), x(16f), x(21f), x(21f), s) }
            Glyph.WARN -> { path.moveTo(x(12f), x(3.5f)); path.lineTo(x(21.5f), x(20f)); path.lineTo(x(2.5f), x(20f)); path.close(); c.drawPath(path, s)
                c.drawLine(x(12f), x(9.5f), x(12f), x(14f), s); c.drawCircle(x(12f), x(17f), x(1.2f), f) }
            Glyph.GEAR -> {
                val teeth = 8
                for (i in 0 until teeth * 2) {
                    val a0 = i * Math.PI / teeth - Math.PI / (teeth * 2)
                    val a1 = a0 + Math.PI / teeth
                    val rr = if (i % 2 == 0) 9.8f else 7.4f
                    val px = x(12f + cos(a0).toFloat() * rr); val py = x(12f + sin(a0).toFloat() * rr)
                    if (i == 0) path.moveTo(px, py) else path.lineTo(px, py)
                    path.lineTo(x(12f + cos(a1).toFloat() * rr), x(12f + sin(a1).toFloat() * rr))
                }
                path.close(); c.drawPath(path, s); c.drawCircle(x(12f), x(12f), x(3.2f), s)
            }
        }
    }
}

/**
 * «Vista previa»: a miniature of the home drawn from the current settings — theme, size, carousel,
 * moving background and rows — so every change shows up at once.
 */
class HomePreview(c: Context) : View(c) {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val bmp = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val r = RectF()
    private val src = Rect()
    private var dens = 1f
    private var dens0 = 1f
    private var densAt = 0L
    private val born = SystemClock.uptimeMillis()

    fun refresh() {
        val d = when (Prefs.int(context, "density", 1)) { 0 -> 0.84f; 2 -> 1.12f; else -> 1f }
        if (d != dens) { dens0 = cur(); dens = d; densAt = SystemClock.uptimeMillis() }
        invalidate()
    }

    init { dens = when (Prefs.int(c, "density", 1)) { 0 -> 0.84f; 2 -> 1.12f; else -> 1f }; dens0 = dens }

    private fun cur(): Float {
        val t = ((SystemClock.uptimeMillis() - densAt) / 260f).coerceIn(0f, 1f)
        val e = 1f - (1f - t) * (1f - t) * (1f - t)
        return dens0 + (dens - dens0) * e
    }

    private fun crop(c: Canvas, b: Bitmap, dst: RectF, zoom: Float) {
        val want = dst.width() / dst.height(); val have = b.width.toFloat() / b.height
        var sw = b.width.toFloat(); var sh = b.height.toFloat()
        if (have > want) sw = sh * want else sh = sw / want
        sw /= zoom; sh /= zoom
        val sx = (b.width - sw) / 2; val sy = (b.height - sh) / 2
        src.set(sx.toInt(), sy.toInt(), (sx + sw).toInt(), (sy + sh).toInt())
        c.drawBitmap(b, src, dst, bmp)
    }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        val u = w / 960f
        val day = Look.isDay(context)
        val bg = if (day) 0xFFEDE6DA.toInt() else 0xFF0B0D12.toInt()
        val fg = if (day) 0xFF201B15.toInt() else Color.WHITE
        val dn = cur()
        var busy = dn != dens
        c.drawColor(bg)
        val carousel = Prefs.bool(context, "carousel", true)
        val trend = if (carousel) Tmdb.cachedTrending(context).firstOrNull() else null
        // Hero.
        val heroH = 300f * u
        if (trend != null) {
            val art = Img.get(context, trend.backdrop?.let { Tmdb.IMG + "w780" + it }, 780) { invalidate() }
            if (art != null) {
                val kb = Prefs.bool(context, "kenBurns", true)
                val z = if (kb) 1.02f + 0.05f * ((SystemClock.uptimeMillis() - born) % 9000L / 9000f) else 1f
                if (kb) busy = true
                r.set(w * 0.36f, 0f, w, heroH)
                crop(c, art, r, z)
                p.shader = LinearGradient(w * 0.36f, 0f, w * 0.62f, 0f, bg, Ui.alpha(bg, 0f), Shader.TileMode.CLAMP); c.drawRect(r, p)
                p.shader = LinearGradient(0f, heroH, 0f, heroH * 0.55f, bg, Ui.alpha(bg, 0f), Shader.TileMode.CLAMP); c.drawRect(r, p)
                p.shader = null
            }
            p.color = fg; p.textSize = 34f * u * dn; p.typeface = Fonts.bold
            c.drawText(trend.name.take(26), 48f * u, 150f * u, p)
            p.textSize = 14f * u * dn; p.typeface = Fonts.regular; p.alpha = 170
            c.drawText(listOf(trend.kind, trend.year).filter { it.isNotEmpty() }.joinToString(" · "), 48f * u, 176f * u, p)
            p.alpha = 255
            r.set(48f * u, 196f * u, 150f * u, 226f * u); p.color = fg; c.drawRoundRect(r, r.height() / 2, r.height() / 2, p)
            r.set(160f * u, 196f * u, 270f * u, 226f * u); p.color = Ui.alpha(fg, 0.18f); c.drawRoundRect(r, r.height() / 2, r.height() / 2, p)
        } else if (carousel) {
            p.color = Ui.alpha(fg, 0.08f); r.set(48f * u, 90f * u, 560f * u, 230f * u); c.drawRoundRect(r, 12f * u, 12f * u, p)
        }
        // Chips.
        var x = 48f * u
        for (wd in floatArrayOf(70f, 76f, 64f, 26f, 26f, 26f)) {
            r.set(x, 24f * u, x + wd * u, 50f * u); p.color = Ui.alpha(fg, 0.12f); c.drawRoundRect(r, r.height() / 2, r.height() / 2, p)
            x += (wd + 8f) * u
        }
        // Dock.
        var y = (if (carousel) heroH + 6f * u else 90f * u)
        val tw = 120f * u * dn; val th = 68f * u * dn
        x = 48f * u
        val dock = Prefs.current(context).pinned.mapNotNull { AppRepo.byPkg(it) }
        for (i in 0 until 7) {
            if (x > w) break
            r.set(x, y, x + tw, y + th)
            val e = dock.getOrNull(i)
            val b = e?.banner
            if (b != null) {
                c.save(); val clip = Path().apply { addRoundRect(r, 8f * u, 8f * u, Path.Direction.CW) }; c.clipPath(clip); crop(c, b, r, 1f); c.restore()
            } else { p.color = e?.color ?: Ui.alpha(fg, 0.14f); c.drawRoundRect(r, 8f * u, 8f * u, p) }
            if (i == 0) { p.style = Paint.Style.STROKE; p.strokeWidth = 2.5f * u; p.color = fg; c.drawRoundRect(r, 8f * u, 8f * u, p); p.style = Paint.Style.FILL }
            x += tw + 14f * u * dn
        }
        y += th + 26f * u * dn
        // Rows.
        val rows = mutableListOf<String>()
        if (Prefs.bool(context, "contRow", true)) rows += "Seguir viendo"
        if (Prefs.bool(context, "listRow", true) && Crunchy.linked(context)) rows += "Mi lista · Crunchyroll"
        if (Prefs.bool(context, "becauseRow", true)) rows += "Porque viste…"
        if (Prefs.bool(context, "seasonRow", true)) rows += "Anime de la temporada"
        val feed = Feed.cached(context)
        for ((ri, name) in rows.withIndex()) {
            if (y > h) break
            p.color = fg; p.alpha = 200; p.textSize = 14f * u * dn; p.typeface = Fonts.medium
            c.drawText(name, 48f * u, y, p); p.alpha = 255
            y += 10f * u
            val cw = 168f * u * dn; val ch = 94f * u * dn
            x = 48f * u
            for (k in 0 until 6) {
                if (x > w) break
                r.set(x, y, x + cw, y + ch)
                val img = if (ri == 0) feed.getOrNull(k)?.image else null
                val b = img?.let { Img.get(context, it, 320) { invalidate() } }
                if (b != null) { c.save(); c.clipPath(Path().apply { addRoundRect(r, 8f * u, 8f * u, Path.Direction.CW) }); crop(c, b, r, 1f); c.restore() }
                else { p.color = Ui.alpha(fg, 0.10f + 0.03f * (k % 2)); c.drawRoundRect(r, 8f * u, 8f * u, p) }
                if (ri == 0) { r.set(x + 8f * u, y + ch - 9f * u, x + cw - 8f * u, y + ch - 6f * u); p.color = 0x55FFFFFF; c.drawRect(r, p)
                    r.right = r.left + (r.width()) * (0.3f + 0.12f * k % 0.6f); p.color = Ui.LAMP; c.drawRect(r, p) }
                x += cw + 12f * u * dn
            }
            y += ch + 28f * u * dn
        }
        if (busy) postInvalidateOnAnimation()
    }
}
