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
            val r = dp(a, 26f)
            root.setBackgroundColor(0xA6000000.toInt())
            scroll.background = GradientDrawable().apply { setColor(PANEL); cornerRadii = floatArrayOf(r, r, 0f, 0f, 0f, 0f, r, r) }
            col.setPadding(px(a, 28), px(a, 30), px(a, 28), px(a, 30))
            scroll.addView(col, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            root.addView(scroll, FrameLayout.LayoutParams(px(a, 440), ViewGroup.LayoutParams.MATCH_PARENT, Gravity.END))
            root.setOnClickListener { a.finish() }
        } else {
            root.background = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(0xFF171C29.toInt(), BG, BG))
            col.setPadding(px(a, 64), px(a, 40), px(a, 64), px(a, 56))
            scroll.addView(col, FrameLayout.LayoutParams(px(a, 780), ViewGroup.LayoutParams.WRAP_CONTENT))
            root.addView(scroll, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        }
        col.addView(text(a, title, if (side) 26f else 34f, FG, Fonts.bold))
        if (sub != null) col.addView(text(a, sub, 15f, MUTED, Fonts.light), lp(a, 8))
        a.setContentView(root)
        return col
    }

    class Row(val view: LinearLayout, val title: TextView, val sub: TextView?, val value: TextView) {
        fun valueColor(color: Int) = value.setTextColor(textStates(INK, color, color))
    }

    fun row(col: LinearLayout, title: String, sub: String? = null, value: String? = null, icon: Bitmap? = null, onClick: (() -> Unit)? = null): Row {
        val c = col.context
        val r = LinearLayout(c).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(px(c, 18), px(c, 13), px(c, 18), px(c, 13))
            background = states(round(Color.WHITE, dp(c, 14f)), null, round(PANEL2, dp(c, 14f)))
        }
        if (icon != null) {
            val wide = icon.width > icon.height * 1.3f
            val iv = ImageView(c).apply {
                setImageBitmap(icon); scaleType = ImageView.ScaleType.CENTER_CROP
                clipToOutline = true; background = round(Color.TRANSPARENT, dp(c, 6f))
            }
            r.addView(iv, LinearLayout.LayoutParams(px(c, if (wide) 64 else 36), px(c, 36)).apply { rightMargin = px(c, 14) })
        }
        val texts = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL }
        val t = text(c, title, 17f, FG, Fonts.medium).apply {
            setTextColor(textStates(INK, FG, FG)); isDuplicateParentStateEnabled = true
        }
        texts.addView(t)
        var s: TextView? = null
        if (sub != null) {
            s = text(c, sub, 13.5f, MUTED, Fonts.light).apply {
                setTextColor(textStates(0xFF4A4F5C.toInt(), MUTED, MUTED)); isDuplicateParentStateEnabled = true
            }
            texts.addView(s, lp(c, 4))
        }
        r.addView(texts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val v = text(c, value ?: "", 15f, MUTED, Fonts.medium).apply {
            setTextColor(textStates(INK, MUTED, MUTED)); isDuplicateParentStateEnabled = true; gravity = Gravity.END
        }
        r.addView(v, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { leftMargin = px(c, 16) })
        if (onClick != null) {
            r.isFocusable = true; r.isClickable = true
            r.setOnClickListener { onClick() }
            r.setOnFocusChangeListener { view, f ->
                view.animate().scaleX(if (f) 1.02f else 1f).scaleY(if (f) 1.02f else 1f).setDuration(140).start()
            }
        }
        col.addView(r, lp(c, 8))
        return Row(r, t, s, v)
    }

    fun section(col: LinearLayout, s: String) {
        val c = col.context
        col.addView(text(c, s.uppercase(), 12.5f, MUTED, Fonts.medium).apply { letterSpacing = 0.14f }, lp(c, 28))
    }

    fun note(col: LinearLayout, s: String, color: Int = MUTED): TextView {
        val t = text(col.context, s, 14.5f, color, Fonts.light)
        col.addView(t, lp(col.context, 10))
        return t
    }

    fun chips(col: LinearLayout, labels: List<String>, selected: Int, onPick: (Int) -> Unit): LinearLayout {
        val c = col.context
        val wrap = LinearLayout(c).apply { orientation = LinearLayout.HORIZONTAL }
        val views = mutableListOf<TextView>()
        labels.forEachIndexed { i, l ->
            val t = text(c, l, 15f, FG, Fonts.medium).apply {
                isFocusable = true; isClickable = true
                setPadding(px(c, 16), px(c, 10), px(c, 16), px(c, 10))
                background = states(round(Color.WHITE, dp(c, 22f)), round(alpha(LAMP, 0.2f), dp(c, 22f), LAMP, px(c, 2)), round(PANEL2, dp(c, 22f)))
                setTextColor(textStates(INK, LAMP, FG))
                isSelected = i == selected
                setOnClickListener {
                    views.forEachIndexed { j, v -> v.isSelected = j == i }
                    onPick(i)
                }
            }
            views += t
            wrap.addView(t, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { rightMargin = px(c, 10) })
        }
        col.addView(wrap, lp(c, 10))
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

    private fun dialog(a: Activity, title: String, body: (LinearLayout, Dialog) -> Unit) {
        val d = Dialog(a)
        d.requestWindowFeature(Window.FEATURE_NO_TITLE)
        val col = LinearLayout(a).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(px(a, 22), px(a, 20), px(a, 22), px(a, 22))
            background = round(PANEL, dp(a, 22f), 0x26FFFFFF, px(a, 1))
        }
        col.addView(text(a, title, 21f, FG, Fonts.bold))
        body(col, d)
        d.setContentView(col, ViewGroup.LayoutParams(px(a, 400), ViewGroup.LayoutParams.WRAP_CONTENT))
        d.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        d.window?.setDimAmount(0.6f)
        d.show()
    }

    fun menu(a: Activity, title: String, items: List<Pair<String, () -> Unit>>) = dialog(a, title) { col, d ->
        items.forEach { (label, fn) -> row(col, label) { d.dismiss(); fn() } }
    }

    fun confirm(a: Activity, title: String, yes: String, fn: () -> Unit) = menu(a, title, listOf(yes to fn, "Cancelar" to {}))

    fun prompt(a: Activity, title: String, initial: String, onOk: (String) -> Unit) = dialog(a, title) { col, d ->
        val field = EditText(a).apply {
            setText(initial); setSelection(initial.length)
            setTextColor(FG); setHintTextColor(MUTED); textSize = 18f; typeface = Fonts.regular
            isSingleLine = true; imeOptions = EditorInfo.IME_ACTION_DONE
            background = round(PANEL2, dp(a, 12f)); setPadding(px(a, 16), px(a, 12), px(a, 16), px(a, 12))
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
