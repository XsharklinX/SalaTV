package com.salatv.app

import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import org.json.JSONObject

/** A pill button with the halo focus, for title pages and prompts. */
fun pill(col: LinearLayout, label: String, accent: Int, onClick: () -> Unit): TextView {
    val c = col.context
    val t = Ui.text(c, label, 15f, Ui.FG, Fonts.medium).apply {
        isFocusable = true; isClickable = true
        setPadding(Ui.px(c, 18), Ui.px(c, 10), Ui.px(c, 18), Ui.px(c, 10))
        background = android.graphics.drawable.StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_focused), Ui.halo(c, accent, Ui.dp(c, 22f)))
            addState(intArrayOf(), GradientDrawable().apply { setColor(0x24FFFFFF); cornerRadius = Ui.dp(c, 22f) })
        }
        setOnClickListener { onClick() }
    }
    col.addView(t, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { rightMargin = Ui.px(c, 10) })
    return t
}

/** «Ficha completa»: logo, synopsis, where it streams, trailer, seasons with episodes, cast and similar titles. */
class TitleActivity : Screen() {
    private lateinit var base: Title
    private var details: TitleDetails? = null
    private var season = 1
    private lateinit var episodes: LinearLayout

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        Fonts.init(this)
        base = Title.fromJson(JSONObject(intent.getStringExtra("json") ?: return finish()))
        render()
        Bg.run({ Tmdb.details(this, base.type, base.id) }) { r ->
            r.onSuccess { details = it; render() }.onFailure { Registro.add(this, "Ficha", "${base.name}: ${it.message}") }
        }
    }

    private fun dp(v: Int) = Ui.px(this, v)

    private fun image(view: ImageView, src: String?, w: Int) {
        fun load() { Img.get(this, src, w) { load() }?.let { view.setImageBitmap(it) } }
        load()
    }

    private fun focusBox(accent: Int, radius: Float) = android.graphics.drawable.StateListDrawable().apply {
        addState(intArrayOf(android.R.attr.state_focused), Ui.halo(this@TitleActivity, accent, Ui.dp(this@TitleActivity, radius)))
        addState(intArrayOf(), GradientDrawable().apply { setColor(Color.TRANSPARENT) })
    }

    private fun render() {
        val d = details
        val t = d?.title ?: base
        val accent = t.pkgs.firstOrNull()?.let { Services.badge(it).second } ?: Ui.LAMP
        val root = FrameLayout(this).apply { setBackgroundColor(Ui.BG) }
        val art = ImageView(this).apply { scaleType = ImageView.ScaleType.CENTER_CROP; alpha = 0.6f }
        root.addView(art, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        image(art, t.backdrop?.let { if (it.startsWith("http")) it else Tmdb.IMG + "w1280" + it }, 1280)
        root.addView(View(this).apply {
            background = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(0xF205060A.toInt(), 0xC005060A.toInt(), 0x3005060A))
        }, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        val scroll = android.widget.ScrollView(this).apply { isVerticalScrollBarEnabled = false }
        val page = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(52), dp(40), dp(40), dp(40)) }
        scroll.addView(page)
        root.addView(scroll, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        val top = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        page.addView(top)
        val left = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        top.addView(left, LinearLayout.LayoutParams(dp(470), ViewGroup.LayoutParams.WRAP_CONTENT))

        val meta = listOf(t.kind, t.year, t.ratingText, d?.genres ?: "").filter { it.isNotEmpty() }.joinToString(" · ")
        left.addView(Ui.text(this, meta.uppercase(), 11.5f, Ui.MUTED, Fonts.medium).apply { letterSpacing = 0.12f })
        if (t.logo != null) {
            val logo = ImageView(this).apply { scaleType = ImageView.ScaleType.FIT_START; adjustViewBounds = true }
            image(logo, Tmdb.IMG + "w500" + t.logo, 500)
            left.addView(logo, LinearLayout.LayoutParams(dp(400), dp(110)).apply { topMargin = dp(10) })
        } else {
            left.addView(Ui.text(this, t.name, 36f, Ui.FG, Fonts.bold).apply { maxLines = 2 },
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) })
        }
        if (!d?.extra.isNullOrEmpty()) left.addView(Ui.text(this, d!!.extra, 13f, Ui.MUTED, Fonts.regular),
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) })
        left.addView(Ui.text(this, t.overview.ifEmpty { "Sin sinopsis en español." }, 14.5f, Ui.FG, Fonts.light).apply { maxLines = 5; alpha = 0.9f },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(12) })

        // Buttons: «Seguir T2 E7» if it is in «Seguir viendo», else «Ver en …» for each app, straight into the title.
        val btns = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        var first: View? = null
        val resume = Feed.cached(this).firstOrNull { it.title.equals(t.name, true) }
        if (resume != null) first = pill(btns, "▶  Seguir · " + resume.sub.removePrefix("Siguiente: ").substringBefore(" «"), Services.badge(resume.pkg).second) { Feed.open(this, resume) }
        t.pkgs.forEach { pkg ->
            val v = pill(btns, (if (resume == null) "▶  " else "") + "Ver en ${Services.name(pkg)}", Services.badge(pkg).second) { Deep.title(this, pkg, t) }
            if (first == null) first = v
        }
        val trailer = d?.trailer
        if (trailer != null) {
            val v = pill(btns, "Tráiler", Ui.LAMP) {
                val yt = listOf("org.smarttube.stable", "org.smarttube.beta", "com.amazon.firetv.youtube").firstOrNull { p -> Sys.launchIntent(this, p) != null }
                val i = Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com/watch?v=$trailer"))
                if (yt != null) i.setPackage(yt)
                try { startActivity(i) } catch (e: Exception) { Ui.toast(this, "No hay app de YouTube para el tráiler") }
            }
            if (first == null) first = v
        }
        // «Mi lista» only when it really reaches your Crunchyroll list.
        if (MyList.canAdd(this, t)) {
            lateinit var listBtn: TextView
            listBtn = pill(btns, if (MyList.has(this, t)) "✓  En mi lista" else "+  Mi lista", Services.badge(Crunchy.PKG).second) {
                listBtn.text = "…"
                Bg.run({ MyList.toggle(this, t) }) { r ->
                    r.onSuccess { listBtn.text = if (it) "✓  En mi lista" else "+  Mi lista" }
                        .onFailure { listBtn.text = "+  Mi lista"; Ui.toast(this, "Crunchyroll no dejó cambiar la lista") }
                }
            }
            if (first == null) first = listBtn
        }
        left.addView(btns, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(18) })
        if (t.pkgs.isEmpty()) left.addView(Ui.text(this, "No está en ninguna de tus apps en ${Tmdb.REGIONS.firstOrNull { it.first == Tmdb.region(this) }?.second ?: Tmdb.region(this)}.", 13f, Ui.WARN, Fonts.light),
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(10) })

        // Seasons and episodes, on the right.
        if (t.type == "tv" && d != null && d.seasons.isNotEmpty()) {
            val right = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            top.addView(right, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { leftMargin = dp(36) })
            val chipsWrap = android.widget.HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false }
            val chipsCol = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            chipsWrap.addView(chipsCol)
            right.addView(chipsWrap)
            if (resume != null) Regex("T(\\d+)").find(resume.sub)?.groupValues?.get(1)?.toIntOrNull()?.let { n -> if (d.seasons.any { it.first == n }) season = n }
            Ui.chips(chipsCol, d.seasons.map { it.second.ifEmpty { "Temporada ${it.first}" } }, d.seasons.indexOfFirst { it.first == season }) { i ->
                season = d.seasons[i].first; loadEpisodes(t)
            }
            episodes = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            right.addView(episodes, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(10) })
            loadEpisodes(t)
        }

        if (d != null && d.cast.isNotEmpty()) {
            page.addView(Ui.text(this, "REPARTO", 11f, Ui.MUTED, Fonts.medium).apply { letterSpacing = 0.14f },
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(26) })
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            d.cast.forEach { (name, pic) ->
                val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL }
                val iv = ImageView(this).apply { scaleType = ImageView.ScaleType.CENTER_CROP; clipToOutline = true
                    background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Ui.PANEL2) } }
                image(iv, pic?.let { Tmdb.IMG + "w185" + it }, 160)
                box.addView(iv, LinearLayout.LayoutParams(dp(60), dp(60)))
                box.addView(Ui.text(this, name, 11.5f, Ui.MUTED, Fonts.regular).apply { maxLines = 2; gravity = Gravity.CENTER },
                    LinearLayout.LayoutParams(dp(78), ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(6) })
                row.addView(box, LinearLayout.LayoutParams(dp(82), ViewGroup.LayoutParams.WRAP_CONTENT))
            }
            page.addView(android.widget.HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(row) },
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(10) })
        }

        if (d != null && d.similar.isNotEmpty()) {
            page.addView(Ui.text(this, "SI TE GUSTA, TAMBIÉN", 11f, Ui.MUTED, Fonts.medium).apply { letterSpacing = 0.14f },
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(24) })
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(dp(4), dp(8), 0, dp(8)) }
            d.similar.take(14).forEach { s ->
                val box = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL; isFocusable = true; isClickable = true
                    setPadding(dp(3), dp(3), dp(3), dp(3)); background = focusBox(Ui.LAMP, 10f)
                    setOnClickListener {
                        startActivity(Intent(this@TitleActivity, TitleActivity::class.java).putExtra("json", s.toJson().toString()))
                        @Suppress("DEPRECATION") overridePendingTransition(android.R.anim.fade_in, 0)
                    }
                }
                val iv = ImageView(this).apply { scaleType = ImageView.ScaleType.CENTER_CROP; clipToOutline = true
                    background = GradientDrawable().apply { setColor(Ui.PANEL2); cornerRadius = Ui.dp(this@TitleActivity, 8f) } }
                image(iv, s.poster?.let { Tmdb.IMG + "w342" + it }, 220)
                box.addView(iv, LinearLayout.LayoutParams(dp(100), dp(150)))
                box.addView(Ui.text(this, s.name, 12f, Ui.FG, Fonts.medium).apply { maxLines = 2 }, LinearLayout.LayoutParams(dp(100), ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(5) })
                row.addView(box, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { rightMargin = dp(8) })
            }
            page.addView(android.widget.HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(row) })
        }

        setContentView(root)
        first?.requestFocus()
    }

    private fun loadEpisodes(t: Title) {
        val box = episodes
        box.removeAllViews()
        box.addView(Ui.text(this, "Cargando episodios…", 13f, Ui.MUTED, Fonts.light))
        val n = season
        Bg.run({ Tmdb.season(this, t.id, n) }) { r ->
            if (n != season) return@run
            box.removeAllViews()
            val list = r.getOrNull().orEmpty()
            if (list.isEmpty()) { box.addView(Ui.text(this, "Sin episodios en TMDB.", 13f, Ui.MUTED, Fonts.light)); return@run }
            val resume = Feed.cached(this).firstOrNull { it.title.equals(t.name, true) }
            val resumeEp = resume?.let { Regex("T(\\d+) · E(\\d+)").find(it.sub) }?.let { it.groupValues[1].toInt() to it.groupValues[2].toInt() }
            list.forEach { e ->
                val row = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; isFocusable = true; isClickable = true
                    setPadding(dp(6), dp(6), dp(10), dp(6)); background = focusBox(Ui.LAMP, 10f)
                    // Inside each app the exact episode is picked there; SalaTV opens the series straight away.
                    setOnClickListener {
                        if (resume != null && resumeEp == (n to e.number)) Feed.open(this@TitleActivity, resume)
                        else t.pkgs.firstOrNull()?.let { Deep.title(this@TitleActivity, it, t) }
                    }
                }
                val iv = ImageView(this).apply { scaleType = ImageView.ScaleType.CENTER_CROP; clipToOutline = true
                    background = GradientDrawable().apply { setColor(Ui.PANEL2); cornerRadius = Ui.dp(this@TitleActivity, 6f) } }
                image(iv, e.still?.let { Tmdb.IMG + "w300" + it }, 240)
                row.addView(iv, LinearLayout.LayoutParams(dp(112), dp(63)))
                val texts = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(12), 0, 0, 0) }
                val here = resumeEp == (n to e.number)
                texts.addView(Ui.text(this, "${e.number}. ${e.name}", 14f, if (here) Ui.LAMP else Ui.FG, Fonts.medium).apply { maxLines = 1 })
                texts.addView(Ui.text(this, if (here) "Aquí te quedaste" else e.date, 12f, Ui.MUTED, Fonts.light))
                row.addView(texts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                box.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(4) })
            }
        }
    }
}

/** «Al encender: seguir donde lo dejé»: a big offer to resume, gone after 10 seconds. */
class ResumePromptActivity : android.app.Activity() {
    private val close = Runnable { finish() }

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        Fonts.init(this)
        val s = Feed.cached(this).firstOrNull() ?: return finish()
        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        val art = ImageView(this).apply { scaleType = ImageView.ScaleType.CENTER_CROP; alpha = 0.5f }
        root.addView(art, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        Img.get(this, s.image, 1280) { recreate() }?.let { art.setImageBitmap(it) }
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(Ui.px(this@ResumePromptActivity, 64), 0, 0, 0); gravity = Gravity.CENTER_VERTICAL }
        col.addView(Ui.text(this, "¿Sigues con…?", 16f, Ui.MUTED, Fonts.medium))
        col.addView(Ui.text(this, s.title, 40f, Ui.FG, Fonts.bold))
        col.addView(Ui.text(this, listOfNotNull(s.sub, s.minutesLeft.takeIf { it > 0 }?.let { "quedan $it min" }).joinToString(" · "), 15f, Ui.FG, Fonts.light))
        val btns = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, Ui.px(this@ResumePromptActivity, 22), 0, 0) }
        val go = pill(btns, "▶  Seguir en ${Services.name(s.pkg)}", Services.badge(s.pkg).second) { Feed.open(this, s); finish() }
        pill(btns, "Ir al inicio", Ui.LAMP) { startActivity(Intent(this, MainActivity::class.java)); finish() }
        col.addView(btns)
        root.addView(col, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        setContentView(root)
        go.requestFocus()
        Bg.main.postDelayed(close, 10_000)
    }

    override fun onUserInteraction() {
        Bg.main.removeCallbacks(close)
        super.onUserInteraction()
    }

    override fun onDestroy() {
        Bg.main.removeCallbacks(close)
        super.onDestroy()
    }
}

/** Search: titles with the apps they are in (TMDB), then «search inside each app». */
class SearchActivity : Screen() {
    private lateinit var results: LinearLayout
    private lateinit var field: android.widget.EditText

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        val col = Ui.page(this, "Buscar", if (Tmdb.enabled(this)) "Escribe un título: verás en cuál de tus apps está" else "Escribe una vez y elige en qué app buscarlo")
        field = android.widget.EditText(this).apply {
            textSize = 20f; setTextColor(Ui.FG); setHintTextColor(Ui.MUTED); typeface = Fonts.regular
            hint = "Título, serie o canal"; isSingleLine = true; imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH
            background = GradientDrawable().apply { setColor(Ui.PANEL2); cornerRadius = Ui.dp(this@SearchActivity, 26f) }
            setPadding(Ui.px(this@SearchActivity, 22), Ui.px(this@SearchActivity, 12), Ui.px(this@SearchActivity, 22), Ui.px(this@SearchActivity, 12))
            setOnEditorActionListener { _, _, _ -> show(text.toString()); true }
        }
        col.addView(field, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = Ui.px(this@SearchActivity, 14) })
        val recent = Prefs.searches(this)
        if (recent.isNotEmpty()) {
            Ui.section(col, "Búsquedas recientes")
            Ui.chips(col, recent.take(4), -1) { i -> field.setText(recent[i]); show(recent[i]) }
        }
        results = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(results)
        // Fire OS's keyboard has «Anterior / Siguiente» and often closes without sending «search»,
        // so the search runs by itself a moment after you stop typing.
        field.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) {
                Bg.main.removeCallbacks(typed)
                if ((s?.trim()?.length ?: 0) >= 2) Bg.main.postDelayed(typed, 700)
            }
        })
        field.imeOptions = field.imeOptions or android.view.inputmethod.EditorInfo.IME_FLAG_NO_EXTRACT_UI
        field.requestFocus()
        field.postDelayed({ getSystemService(android.view.inputmethod.InputMethodManager::class.java)?.showSoftInput(field, 0) }, 250)
    }

    private val typed = Runnable { show(field.text.toString(), final = false) }
    private var shown = ""
    private var ticket = 0

    override fun onDestroy() {
        Bg.main.removeCallbacks(typed)
        super.onDestroy()
    }

    /** [final] when the search was asked for (enter, a recent chip): saves it and moves the focus to the results. */
    private fun show(raw: String, final: Boolean = true) {
        val q = raw.trim()
        if (q.isEmpty()) { results.removeAllViews(); shown = ""; return }
        if (final) {
            Prefs.addSearch(this, q)
            getSystemService(android.view.inputmethod.InputMethodManager::class.java)?.hideSoftInputFromWindow(field.windowToken, 0)
        }
        if (q == shown) { if (final) results.getChildAt(1)?.requestFocus(); return }
        shown = q
        val mine = ++ticket
        results.removeAllViews()
        if (Tmdb.enabled(this)) {
            Ui.section(results, "Títulos")
            val wait = Ui.note(results, "Buscando «$q»…")
            Bg.run({ Tmdb.search(this, q) }) { r ->
                if (mine != ticket || isFinishing) return@run
                results.removeView(wait)
                val list = r.getOrNull()
                when {
                    list == null -> Ui.note(results, "No se pudo buscar: comprueba la conexión.", Ui.WARN)
                    list.isEmpty() -> Ui.note(results, "Ningún título con ese nombre.")
                    else -> posters(list, focus = final)
                }
                appRows(q)
            }
        } else appRows(q)
    }

    private fun posters(list: List<Title>, focus: Boolean) {
        val scroll = HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; clipToPadding = false }
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(Ui.px(this@SearchActivity, 10), Ui.px(this@SearchActivity, 8), 0, Ui.px(this@SearchActivity, 8)) }
        var first: View? = null
        list.forEach { t ->
            val accent = t.pkgs.firstOrNull()?.let { Services.badge(it).second } ?: Ui.LAMP
            val box = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL; isFocusable = true; isClickable = true
                setPadding(Ui.px(this@SearchActivity, 4), Ui.px(this@SearchActivity, 4), Ui.px(this@SearchActivity, 4), Ui.px(this@SearchActivity, 4))
                background = android.graphics.drawable.StateListDrawable().apply {
                    addState(intArrayOf(android.R.attr.state_focused), Ui.halo(this@SearchActivity, accent, Ui.dp(this@SearchActivity, 12f)))
                    addState(intArrayOf(), GradientDrawable().apply { setColor(Color.TRANSPARENT) })
                }
                setOnClickListener {
                    val pkg = t.pkgs.firstOrNull()
                    if (t.pkgs.size == 1 && pkg != null) Deep.title(this@SearchActivity, pkg, t)
                    else startActivity(Intent(this@SearchActivity, TitleActivity::class.java).putExtra("json", t.toJson().toString()))
                }
            }
            val iv = ImageView(this).apply { scaleType = ImageView.ScaleType.CENTER_CROP; setBackgroundColor(Ui.PANEL2); clipToOutline = true
                background = GradientDrawable().apply { setColor(Ui.PANEL2); cornerRadius = Ui.dp(this@SearchActivity, 8f) } }
            fun load() { Img.get(this, t.poster?.let { Tmdb.IMG + "w342" + it }, 240) { load() }?.let { iv.setImageBitmap(it) } }
            load()
            box.addView(iv, LinearLayout.LayoutParams(Ui.px(this, 110), Ui.px(this, 165)))
            box.addView(Ui.text(this, t.name, 13f, Ui.FG, Fonts.medium).apply { maxLines = 2 }, LinearLayout.LayoutParams(Ui.px(this, 110), ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = Ui.px(this@SearchActivity, 6) })
            box.addView(Ui.text(this, if (t.pkgs.isEmpty()) "No está en tus apps" else t.pkgs.joinToString(" · ") { Services.name(it) }, 11.5f,
                if (t.pkgs.isEmpty()) Ui.MUTED else accent, Fonts.regular).apply { maxLines = 2 },
                LinearLayout.LayoutParams(Ui.px(this, 110), ViewGroup.LayoutParams.WRAP_CONTENT))
            row.addView(box, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { rightMargin = Ui.px(this@SearchActivity, 10) })
            if (first == null) first = box
        }
        scroll.addView(row)
        results.addView(scroll)
        // While typing the focus stays on the field; ↓ goes down to the posters.
        if (focus) first?.requestFocus()
    }

    private fun appRows(q: String) {
        Ui.section(results, "Buscar «$q» dentro de")
        val dock = Prefs.current(this).pinned
        val searchable = packageManager.queryIntentActivities(Intent(Intent.ACTION_SEARCH), 0).map { it.activityInfo.packageName }.toSet()
        AppRepo.apps.filter { it.pkg in AppSearch.KNOWN || it.pkg in searchable || it.pkg in dock }.forEach { e ->
            Ui.row(results, e.label, null, icon = e.banner ?: e.icon) { AppSearch.go(this, e.pkg, q) }
        }
    }
}
