package com.salatv.app

import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.net.wifi.WifiManager
import android.provider.Settings
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import kotlin.math.roundToInt

/** Outfit, the face CleanYourTV ships (SIL OFL), at the weights SalaTV uses. */
object Fonts {
    var light: Typeface = Typeface.DEFAULT
    var regular: Typeface = Typeface.DEFAULT
    var medium: Typeface = Typeface.DEFAULT
    var bold: Typeface = Typeface.DEFAULT_BOLD
    private var ready = false

    fun init(c: Context) {
        if (ready) return
        ready = true
        fun w(weight: Int): Typeface = try {
            Typeface.Builder(c.assets, "outfit.ttf").setFontVariationSettings("'wght' $weight").build()
        } catch (e: Exception) { Typeface.DEFAULT }
        light = w(300); regular = w(400); medium = w(500); bold = w(700)
    }
}

/** Colours of the home for day and night. */
class Pal(val day: Boolean) {
    val bg = if (day) 0xFFEDE7DB.toInt() else 0xFF05060A.toInt()
    val fg = if (day) 0xFF201B15.toInt() else Color.WHITE
    val glass = if (day) 0x80FFFFFF.toInt() else 0x21FFFFFF
    val glassLine = if (day) 0xB3FFFFFF.toInt() else 0x3DFFFFFF
    val chip = if (day) 0x17201B15 else 0x1FFFFFFF
    val ring = if (day) 0xFF201B15.toInt() else Color.WHITE
}

/** The building blocks of every screen that is not the home: rows, chips, sections, menus. */
object Ui {
    val BG = 0xFF0B0D12.toInt()
    val PANEL = 0xFF161A23.toInt()
    val PANEL2 = 0xFF1F2430.toInt()
    val FG = 0xFFECEEF3.toInt()
    val MUTED = 0xFF9AA2B4.toInt()
    val LAMP = 0xFFFFB766.toInt()
    val OK = 0xFF5FD39A.toInt()
    val BAD = 0xFFFF8A80.toInt()
    val WARN = 0xFFFFCF6B.toInt()
    val INK = 0xFF0A0B0F.toInt()

    fun dp(c: Context, v: Float) = v * c.resources.displayMetrics.density
    fun px(c: Context, v: Int) = dp(c, v.toFloat()).roundToInt()

    fun alpha(color: Int, a: Float) = (color and 0x00FFFFFF) or ((a.coerceIn(0f, 1f) * 255).roundToInt() shl 24)

    fun shade(color: Int, f: Float) = Color.rgb((Color.red(color) * f).toInt(), (Color.green(color) * f).toInt(), (Color.blue(color) * f).toInt())

    fun lerp(a: Int, b: Int, t: Float): Int {
        fun ch(x: Int, y: Int) = (x + (y - x) * t).roundToInt()
        return Color.argb(ch(Color.alpha(a), Color.alpha(b)), ch(Color.red(a), Color.red(b)), ch(Color.green(a), Color.green(b)), ch(Color.blue(a), Color.blue(b)))
    }

    private fun round(color: Int, r: Float, stroke: Int = 0, strokeW: Int = 0) = GradientDrawable().apply {
        setColor(color); cornerRadius = r
        if (strokeW > 0) setStroke(strokeW, stroke)
    }

    /**
     * «Halo del color de la app»: the focused item gets a soft tint of [accent] that fades to the right
     * and a thin border of it. The text keeps its colour, so nothing turns white on white.
     */
    fun halo(c: Context, accent: Int, radius: Float) = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
        intArrayOf(alpha(accent, 0.38f), alpha(accent, 0.10f))).apply {
        cornerRadius = radius
        setStroke(px(c, 2), alpha(accent, 0.85f))
    }

    private fun states(focused: Drawable, selected: Drawable?, normal: Drawable) = StateListDrawable().apply {
        addState(intArrayOf(android.R.attr.state_focused), focused)
        if (selected != null) addState(intArrayOf(android.R.attr.state_selected), selected)
        addState(intArrayOf(), normal)
    }

    private fun textStates(focused: Int, selected: Int, normal: Int) = ColorStateList(
        arrayOf(intArrayOf(android.R.attr.state_focused), intArrayOf(android.R.attr.state_selected), intArrayOf()),
        intArrayOf(focused, selected, normal),
    )

    fun text(c: Context, s: CharSequence, sp: Float, color: Int, tf: Typeface = Fonts.regular) = TextView(c).apply {
        text = s; textSize = sp; setTextColor(color); typeface = tf
        includeFontPadding = false; setLineSpacing(0f, 1.18f)
    }

    private fun lp(c: Context, top: Int) = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = px(c, top) }

    /** A full screen (or, with [side], a panel on the right) with a title. Returns the column to fill. */
    fun page(a: Activity, title: String, sub: String? = null, side: Boolean = false): LinearLayout {
        Fonts.init(a)
        val root = FrameLayout(a)
        val scroll = ScrollView(a).apply { isVerticalScrollBarEnabled = false; isFillViewport = true }
        val col = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL }
        if (side) {
            // «Columna estrecha»: a 300 dp panel floating on the right, the home still visible beside it.
            val r = dp(a, 22f)
            root.setBackgroundColor(0x66000000)
            scroll.background = GradientDrawable().apply { setColor(0xF2121520.toInt()); cornerRadius = r; setStroke(px(a, 1), 0x1FFFFFFF) }
            col.setPadding(px(a, 14), px(a, 18), px(a, 14), px(a, 18))
            scroll.addView(col, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            root.addView(scroll, FrameLayout.LayoutParams(px(a, 300), ViewGroup.LayoutParams.MATCH_PARENT, Gravity.END).apply {
                setMargins(0, px(a, 18), px(a, 18), px(a, 18))
            })
            root.setOnClickListener { a.finish() }
        } else {
            root.background = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(0xFF171C29.toInt(), BG, BG))
            col.setPadding(px(a, 56), px(a, 32), px(a, 56), px(a, 48))
            scroll.addView(col, FrameLayout.LayoutParams(px(a, 680), ViewGroup.LayoutParams.WRAP_CONTENT))
            root.addView(scroll, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        }
        col.addView(text(a, title, if (side) 19f else 28f, FG, Fonts.bold).apply { if (side) setPadding(px(a, 8), 0, 0, 0) })
        if (sub != null) col.addView(text(a, sub, 13.5f, MUTED, Fonts.light), lp(a, 6))
        a.setContentView(root)
        return col
    }

    class Row(val view: LinearLayout, val title: TextView, val sub: TextView?, val value: TextView) {
        fun valueColor(color: Int) = value.setTextColor(color)
    }

    /** Thin list row (44 dp with one line): halo on focus, value on the right. */
    fun row(col: LinearLayout, title: String, sub: String? = null, value: String? = null, icon: Bitmap? = null, accent: Int = LAMP, onClick: (() -> Unit)? = null): Row {
        val c = col.context
        val r = LinearLayout(c).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = px(c, 44)
            setPadding(px(c, 14), px(c, 8), px(c, 14), px(c, 8))
            background = states(halo(c, accent, dp(c, 12f)), null, round(Color.TRANSPARENT, dp(c, 12f)))
        }
        if (icon != null) {
            val wide = icon.width > icon.height * 1.3f
            val iv = ImageView(c).apply {
                setImageBitmap(icon); scaleType = ImageView.ScaleType.CENTER_CROP
                clipToOutline = true; background = round(Color.TRANSPARENT, dp(c, 5f))
            }
            r.addView(iv, LinearLayout.LayoutParams(px(c, if (wide) 50 else 28), px(c, 28)).apply { rightMargin = px(c, 12) })
        }
        val texts = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL }
        val t = text(c, title, 15.5f, FG, Fonts.medium)
        texts.addView(t)
        var s: TextView? = null
        if (sub != null) {
            s = text(c, sub, 12.5f, MUTED, Fonts.light)
            texts.addView(s, lp(c, 2))
        }
        r.addView(texts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val v = text(c, value ?: "", 13.5f, MUTED, Fonts.regular).apply { gravity = Gravity.END }
        r.addView(v, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { leftMargin = px(c, 14) })
        if (onClick != null) {
            r.isFocusable = true; r.isClickable = true
            r.setOnClickListener { onClick() }
        } else {
            t.setTextColor(MUTED)
        }
        col.addView(r, lp(c, 2))
        return Row(r, t, s, v)
    }

    fun section(col: LinearLayout, s: String) {
        val c = col.context
        col.addView(text(c, s.uppercase(), 11f, MUTED, Fonts.medium).apply { letterSpacing = 0.14f; setPadding(px(c, 14), 0, 0, 0) }, lp(c, 18))
    }

    fun note(col: LinearLayout, s: String, color: Int = MUTED): TextView {
        val t = text(col.context, s, 13f, color, Fonts.light).apply { setPadding(px(col.context, 14), 0, px(col.context, 8), 0) }
        col.addView(t, lp(col.context, 6))
        return t
    }

    fun chips(col: LinearLayout, labels: List<String>, selected: Int, onPick: (Int) -> Unit): LinearLayout {
        val c = col.context
        val wrap = LinearLayout(c).apply { orientation = LinearLayout.HORIZONTAL; setPadding(px(c, 10), 0, 0, 0) }
        val views = mutableListOf<TextView>()
        labels.forEachIndexed { i, l ->
            val t = text(c, l, 13f, FG, Fonts.medium).apply {
                isFocusable = true; isClickable = true
                setPadding(px(c, 12), px(c, 7), px(c, 12), px(c, 7))
                background = states(halo(c, LAMP, dp(c, 16f)), round(alpha(LAMP, 0.12f), dp(c, 16f), LAMP, px(c, 1)), round(0x12FFFFFF, dp(c, 16f)))
                setTextColor(textStates(FG, LAMP, FG))
                isSelected = i == selected
                setOnClickListener {
                    views.forEachIndexed { j, v -> v.isSelected = j == i }
                    onPick(i)
                }
            }
            views += t
            wrap.addView(t, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { rightMargin = px(c, 6) })
        }
        col.addView(wrap, lp(c, 6))
        return wrap
    }

    /** A labelled meter, like the memory bar in «Salud de la tele». */
    fun bar(col: LinearLayout, label: String, value: String, fraction: Float, color: Int) {
        val c = col.context
        val head = LinearLayout(c).apply { orientation = LinearLayout.HORIZONTAL }
        head.addView(text(c, label, 16f, FG, Fonts.regular), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        head.addView(text(c, value, 16f, FG, Fonts.medium))
        col.addView(head, lp(c, 18))
        col.addView(Meter(c, fraction, color), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, px(c, 14)).apply { topMargin = px(c, 8) })
    }

    private class Meter(c: Context, val f: Float, val color: Int) : View(c) {
        private val p = Paint(Paint.ANTI_ALIAS_FLAG)
        private val r = RectF()
        override fun onDraw(canvas: Canvas) {
            val h = height.toFloat()
            r.set(0f, 0f, width.toFloat(), h); p.color = 0x1FFFFFFF; canvas.drawRoundRect(r, h / 2, h / 2, p)
            r.right = width * f.coerceIn(0.02f, 1f); p.color = color; canvas.drawRoundRect(r, h / 2, h / 2, p)
        }
    }

    fun toast(c: Context, s: String) = Toast.makeText(c.applicationContext, s, Toast.LENGTH_LONG).show()

    /**
     * A small floating card: next to [anchor] (screen pixels) when given, else centred.
     * «Burbuja junto a la app»: about 250 dp wide, only as tall as its options.
     */
    private fun dialog(a: Activity, title: String, anchor: android.graphics.Rect? = null, width: Int = 260, body: (LinearLayout, Dialog) -> Unit) {
        val d = Dialog(a)
        d.requestWindowFeature(Window.FEATURE_NO_TITLE)
        val col = LinearLayout(a).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(px(a, 8), px(a, 10), px(a, 8), px(a, 8))
            background = round(0xF2121520.toInt(), dp(a, 16f), 0x1FFFFFFF, px(a, 1))
            elevation = dp(a, 12f)
        }
        col.addView(text(a, title.uppercase(), 11f, MUTED, Fonts.medium).apply {
            letterSpacing = 0.12f; setPadding(px(a, 12), px(a, 2), px(a, 12), px(a, 6)); maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        })
        body(col, d)
        d.setContentView(col, ViewGroup.LayoutParams(px(a, width), ViewGroup.LayoutParams.WRAP_CONTENT))
        d.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setDimAmount(0.35f)
            if (anchor != null) {
                col.measure(View.MeasureSpec.makeMeasureSpec(px(a, width), View.MeasureSpec.EXACTLY), View.MeasureSpec.UNSPECIFIED)
                val h = col.measuredHeight
                val screenH = a.resources.displayMetrics.heightPixels
                val screenW = a.resources.displayMetrics.widthPixels
                val lpw = attributes
                lpw.gravity = Gravity.TOP or Gravity.START
                lpw.x = anchor.left.coerceIn(px(a, 16), screenW - px(a, width) - px(a, 16))
                lpw.y = (if (anchor.top - h - px(a, 12) > px(a, 16)) anchor.top - h - px(a, 12) else anchor.bottom + px(a, 12)).coerceIn(px(a, 16), screenH - h - px(a, 16))
                attributes = lpw
            }
        }
        d.show()
    }

    fun menu(a: Activity, title: String, items: List<Pair<String, () -> Unit>>, anchor: android.graphics.Rect? = null, accent: Int = LAMP) =
        dialog(a, title, anchor) { col, d ->
            items.forEach { (label, fn) -> row(col, label, accent = accent) { d.dismiss(); fn() } }
        }

    /** Four options at hand and the rest behind «Más…», in the same spot. */
    fun bubble(a: Activity, title: String, main: List<Pair<String, () -> Unit>>, more: List<Pair<String, () -> Unit>>, anchor: android.graphics.Rect?, accent: Int) {
        val first = if (more.isEmpty()) main else main + ("Más…" to { menu(a, title, more, anchor, accent) })
        menu(a, title, first, anchor, accent)
    }

    fun confirm(a: Activity, title: String, yes: String, fn: () -> Unit) = menu(a, title, listOf(yes to fn, "Cancelar" to {}))

    fun prompt(a: Activity, title: String, initial: String, password: Boolean = false, onOk: (String) -> Unit) = dialog(a, title, width = 400) { col, d ->
        val field = EditText(a).apply {
            setText(initial); setSelection(initial.length)
            setTextColor(FG); setHintTextColor(MUTED); textSize = 17f; typeface = Fonts.regular
            isSingleLine = true; imeOptions = EditorInfo.IME_ACTION_DONE
            if (password) inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
            background = round(PANEL2, dp(a, 12f)); setPadding(px(a, 14), px(a, 10), px(a, 14), px(a, 10))
        }
        fun done() {
            val v = field.text.toString().trim()
            if (v.isNotEmpty()) { d.dismiss(); onOk(v) }
        }
        field.setOnEditorActionListener { _, _, _ -> done(); true }
        col.addView(field, lp(a, 14))
        row(col, "Guardar") { done() }
    }
}

object Sys {
    fun open(c: Context, action: String) {
        for (i in listOf(Intent(action), Intent(Settings.ACTION_SETTINGS))) {
            try { c.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); return } catch (_: Exception) {}
        }
        Ui.toast(c, "La tele no deja abrir ese ajuste")
    }

    fun launchIntent(c: Context, pkg: String): Intent? {
        val pm = c.packageManager
        return AppRepo.byPkg(pkg)?.intent ?: pm.getLeanbackLaunchIntentForPackage(pkg) ?: pm.getLaunchIntentForPackage(pkg)
    }

    /**
     * Every app SalaTV opens goes through here: [intent], or the app's launcher entry when null.
     * Apps with a PIN ask for the code first.
     */
    fun start(c: Context, pkg: String, intent: Intent?): Boolean {
        val i = Intent(intent ?: launchIntent(c, pkg) ?: return false).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (Pin.needs(c, pkg)) {
            c.startActivity(Intent(c, PinActivity::class.java).putExtra("pkg", pkg).putExtra("then", i).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            return true
        }
        return try {
            c.startActivity(i)
            true
        } catch (e: Exception) {
            Registro.add(c, "Abrir", "$pkg: ${e.javaClass.simpleName}")
            false
        }
    }

    fun launch(c: Context, pkg: String) = start(c, pkg, null)

    /** Opens the TV's own home and tells the vigía to leave it alone until the user opens an app. */
    fun stockHome(c: Context) {
        val home = Platform.stockHome(c)
        if (home == null) { Ui.toast(c, "No se encontró el inicio original"); return }
        Vigia.bypass = true
        try {
            c.startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).setPackage(home).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: Exception) {
            Vigia.bypass = false
            Ui.toast(c, "No se pudo abrir el inicio original")
        }
    }

    fun appInfo(c: Context, pkg: String) = try {
        c.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$pkg")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (e: Exception) { Ui.toast(c, "La tele no deja abrir la información de la app") }

    @Suppress("DEPRECATION")
    fun uninstall(c: Context, pkg: String) = try {
        c.startActivity(Intent(Intent.ACTION_UNINSTALL_PACKAGE, Uri.parse("package:$pkg")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (e: Exception) { appInfo(c, pkg) }

    fun isSelect(code: Int) = code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER ||
        code == KeyEvent.KEYCODE_NUMPAD_ENTER || code == KeyEvent.KEYCODE_BUTTON_A
}

object Net {
    class State(val online: Boolean, val text: String)

    @Suppress("DEPRECATION")
    fun describe(c: Context): State {
        val cm = c.getSystemService(ConnectivityManager::class.java)
        val caps = cm?.getNetworkCapabilities(cm.activeNetwork) ?: return State(false, "Sin conexión")
        return when {
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> State(true, "Conectada por cable")
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> {
                val wm = c.applicationContext.getSystemService(WifiManager::class.java)
                val level = WifiManager.calculateSignalLevel(wm?.connectionInfo?.rssi ?: -100, 4)
                State(true, "Wi‑Fi · señal " + arrayOf("débil", "regular", "buena", "excelente")[level])
            }
            else -> State(true, "Conectada")
        }
    }
}
