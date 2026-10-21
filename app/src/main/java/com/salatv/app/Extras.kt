package com.salatv.app

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.media.tv.TvContract
import android.media.tv.TvInputInfo
import android.media.tv.TvInputManager
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView

/** Every list screen: ⏪ and ⏩ on the remote jump to the first and last option. */
open class Screen : Activity() {
    override fun onKeyDown(code: Int, ev: KeyEvent): Boolean {
        if (code == KeyEvent.KEYCODE_MEDIA_REWIND || code == KeyEvent.KEYCODE_MEDIA_FAST_FORWARD) {
            val all = window.decorView.getFocusables(View.FOCUS_DOWN).filter { it.isShown }
            (if (code == KeyEvent.KEYCODE_MEDIA_REWIND) all.firstOrNull() else all.lastOrNull())?.requestFocus()
            return true
        }
        return super.onKeyDown(code, ev)
    }
}

/** HDMI ports and the tuner. Fire OS keeps input switching to itself, so ADB key presses are the fallback. */
object Inputs {
    class In(val id: String, val label: String, val type: String, val hdmi: Int)

    fun list(c: Context): List<In> {
        val tim = c.getSystemService(Context.TV_INPUT_SERVICE) as? TvInputManager
        val real = try { tim?.tvInputList.orEmpty() } catch (e: Exception) { emptyList() }
        if (real.isNotEmpty()) return real.sortedBy { it.type }.map { info ->
            val own = info.loadLabel(c).toString()
            val type = when (info.type) {
                TvInputInfo.TYPE_HDMI -> "HDMI"
                TvInputInfo.TYPE_TUNER -> "Antena y TV"
                TvInputInfo.TYPE_COMPOSITE -> "AV"
                TvInputInfo.TYPE_COMPONENT -> "Componentes"
                else -> "Entrada"
            }
            In(info.id, own, type, Regex("HDMI\\s*(\\d)", RegexOption.IGNORE_CASE).find(own + " " + info.id)?.groupValues?.get(1)?.toInt() ?: 0)
        }
        // The list is empty on Fire OS without system rights: offer the four HDMI keys instead.
        return (1..4).map { In("hdmi$it", "HDMI $it", "HDMI", it) }
    }

    fun open(c: Context, id: String) {
        val inp = list(c).firstOrNull { it.id == id }
        if (!id.startsWith("hdmi")) {
            try {
                c.startActivity(Intent(Intent.ACTION_VIEW, TvContract.buildChannelUriForPassthroughInput(id)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                return
            } catch (e: Exception) {
                Registro.add(c, "Entradas", "Fire OS no dejó abrir ${inp?.label ?: id}; se prueba con la tecla HDMI")
            }
        }
        val n = inp?.hdmi ?: 0
        if (n !in 1..4) { Ui.toast(c, "Usa el botón de entradas del mando"); return }
        val app = c.applicationContext
        Bg.run({ Adb.connect(app, allowPrompt = false).use { it.shell("input keyevent ${242 + n}") } }) { r ->
            r.onFailure {
                Ui.toast(app, "No se pudo cambiar a HDMI $n: " + Adb.explain(it))
                Registro.add(app, "Entradas", "HDMI $n por ADB: ${it.javaClass.simpleName}")
            }
        }
    }
}

/**
 * Asks for the four-digit code. From SalaTV («then» set) it opens the app after; from the vigía
 * («guard») it sits on top of the locked app and sends the user home if they back out.
 * With «set» it chooses a new code.
 */
class PinActivity : Activity() {
    private val typed = StringBuilder()
    private var first: String? = null
    private lateinit var dots: LinearLayout
    private lateinit var msg: TextView

    private val setMode get() = intent.getBooleanExtra("set", false)

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        Fonts.init(this)
        val pkg = intent.getStringExtra("pkg")
        val name = pkg?.let { p ->
            AppRepo.byPkg(p)?.label ?: try { packageManager.getApplicationLabel(packageManager.getApplicationInfo(p, 0)).toString() } catch (e: Exception) { p }
        } ?: ""
        val root = FrameLayout(this).apply { setBackgroundColor(0xF20B0D12.toInt()) }
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL }
        col.addView(Ui.text(this, if (setMode) "Nuevo código" else name, 30f, Ui.FG, Fonts.bold))
        msg = Ui.text(this, if (setMode) "Elige 4 cifras para las apps con PIN" else "Escribe el código para abrirla", 16f, Ui.MUTED, Fonts.light)
        col.addView(msg, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = Ui.px(this@PinActivity, 10) })
        dots = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, Ui.px(this@PinActivity, 24), 0, Ui.px(this@PinActivity, 24)) }
        repeat(4) {
            dots.addView(View(this).apply { background = dot(false) }, LinearLayout.LayoutParams(Ui.px(this, 22), Ui.px(this, 22)).apply { leftMargin = Ui.px(this@PinActivity, 9); rightMargin = Ui.px(this@PinActivity, 9) })
        }
        col.addView(dots)
        val grid = GridLayout(this).apply { columnCount = 3 }
        val keys = listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "⌫", "0", "✕")
        var five: View? = null
        keys.forEach { k ->
            val t = Ui.text(this, k, 26f, Ui.FG, Fonts.medium).apply {
                gravity = Gravity.CENTER; isFocusable = true; isClickable = true
                background = android.graphics.drawable.StateListDrawable().apply {
                    addState(intArrayOf(android.R.attr.state_focused), GradientDrawable().apply { setColor(Color.WHITE); cornerRadius = Ui.dp(this@PinActivity, 40f) })
                    addState(intArrayOf(), GradientDrawable().apply { setColor(Ui.PANEL2); cornerRadius = Ui.dp(this@PinActivity, 40f) })
                }
                setTextColor(android.content.res.ColorStateList(arrayOf(intArrayOf(android.R.attr.state_focused), intArrayOf()), intArrayOf(Ui.INK, Ui.FG)))
                setOnClickListener { press(k) }
            }
            if (k == "5") five = t
            grid.addView(t, GridLayout.LayoutParams().apply {
                width = Ui.px(this@PinActivity, 76); height = Ui.px(this@PinActivity, 76)
                setMargins(Ui.px(this@PinActivity, 8), Ui.px(this@PinActivity, 8), Ui.px(this@PinActivity, 8), Ui.px(this@PinActivity, 8))
            })
        }
        col.addView(grid)
        root.addView(col, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
        setContentView(root)
        five?.requestFocus()
    }

    private fun dot(on: Boolean) = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setStroke(Ui.px(this@PinActivity, 3), Ui.FG)
        setColor(if (on) Ui.FG else Color.TRANSPARENT)
    }

    private fun refresh() {
        for (i in 0 until 4) dots.getChildAt(i).background = dot(i < typed.length)
    }

    private fun press(k: String) {
        when (k) {
            "⌫" -> if (typed.isNotEmpty()) typed.setLength(typed.length - 1)
            "✕" -> { cancel(); return }
            else -> if (typed.length < 4) typed.append(k)
        }
        refresh()
        if (typed.length == 4) Bg.main.postDelayed({ done() }, 150)
    }

    private fun done() {
        val code = typed.toString()
        typed.setLength(0)
        refresh()
        if (setMode) {
            if (first == null) { first = code; msg.text = "Escríbelo otra vez"; return }
            if (first == code) { Pin.setCode(this, code); Ui.toast(this, "Código guardado"); finish() }
            else { first = null; msg.text = "No coinciden. Elige 4 cifras otra vez"; msg.setTextColor(Ui.BAD) }
            return
        }
        if (!Pin.check(this, code)) {
            msg.text = "Código incorrecto"
            msg.setTextColor(Ui.BAD)
            dots.animate().translationX(18f).setDuration(60).withEndAction { dots.animate().translationX(0f).setDuration(120).start() }.start()
            return
        }
        intent.getStringExtra("pkg")?.let { Pin.unlock(it) }
        @Suppress("DEPRECATION")
        val then = intent.getParcelableExtra<Intent>("then")
        if (then != null) try { startActivity(then) } catch (_: Exception) {}
        finish()
    }

    private fun cancel() {
        if (intent.getBooleanExtra("guard", false)) {
            startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
        finish()
    }

    override fun onKeyDown(code: Int, ev: KeyEvent): Boolean {
        if (code in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9) { press((code - KeyEvent.KEYCODE_0).toString()); return true }
        return super.onKeyDown(code, ev)
    }

    @Deprecated("Still the way to catch Back on Android 11")
    override fun onBackPressed() = cancel()

    override fun onDestroy() {
        Pin.asking = false
        super.onDestroy()
    }
}

/** «¿Sigues ahí?»: a minute to answer, then the TV goes to sleep. Any button keeps it on. */
class StillThereActivity : Activity() {
    companion object { @Volatile var showing = false }

    private val start = SystemClock.elapsedRealtime()
    private lateinit var count: TextView
    private lateinit var bar: ProgressBar
    private val tick = object : Runnable {
        override fun run() {
            val left = 60 - ((SystemClock.elapsedRealtime() - start) / 1000).toInt()
            if (left <= 0) {
                Registro.add(this@StillThereActivity, "¿Sigues ahí?", "Nadie contestó; se apagó la tele")
                Power.sleep(this@StillThereActivity)
                finish()
                return
            }
            count.text = "La tele se apagará en $left s"
            bar.progress = left
            Bg.main.postDelayed(this, 1000)
        }
    }

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        showing = true
        val col = Ui.page(this, "¿Sigues ahí?", "Llevas un buen rato sin tocar el mando.", side = true)
        count = Ui.note(col, "", Ui.LAMP)
        bar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply { max = 60; progress = 60 }
        col.addView(bar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.px(this, 10)).apply { topMargin = Ui.px(this@StillThereActivity, 12) })
        val stay = Ui.row(col, "Sigo aquí") { finish() }
        Ui.row(col, "Apagar ya") { Power.sleep(this); finish() }
        stay.view.requestFocus()
        Bg.main.post(tick)
    }

    override fun onDestroy() {
        Bg.main.removeCallbacks(tick)
        showing = false
        Vigia.lastKey = SystemClock.elapsedRealtime()
        super.onDestroy()
    }
}

class RegistroActivity : Screen() {
    override fun onResume() {
        super.onResume()
        val col = Ui.page(this, "Registro de problemas", "Lo que ha fallado en los últimos 7 días")
        val l = Registro.read(this)
        if (l.isEmpty()) Ui.note(col, "Nada que contar: no ha fallado nada.", Ui.OK)
        l.take(80).forEach { Ui.row(col, it.text, it.area, it.time.substring(5).replace('-', '/')) }
        Ui.section(col, "Para arreglarlo en el PC")
        Ui.note(col, "Con la tele conectada por ADB, este comando copia el registro al PC:")
        Ui.note(col, Registro.PULL, Ui.FG).typeface = android.graphics.Typeface.MONOSPACE
        Ui.row(col, "Borrar el registro") { Registro.clear(this); onResume() }
    }
}

/** CleanYourTV's per-model lists (MIT), with the one applied on this TV first. */
class ListsActivity : Screen() {
    class ModelList(val folder: String, val title: String, val os: String)

    companion object {
        fun all(c: Context): List<ModelList> = c.assets.open("listas/index.tsv").bufferedReader().readLines().mapNotNull { l ->
            val p = l.split('\t')
            if (p.size < 3) null else ModelList(p[0], p[1], p[2])
        }

        fun current(c: Context) = Prefs.str(c, "list", "custom")!!

        fun label(c: Context): String {
            val cur = current(c)
            return if (cur == "custom") "La de esta tele (CleanYourTV)" else all(c).firstOrNull { it.folder == cur }?.title ?: cur
        }

        /** Lists whose folder or title mention this TV's model code or brand. */
        fun matches(c: Context): List<ModelList> {
            val model = Build.MODEL.lowercase()
            val brand = Build.MANUFACTURER.lowercase()
            val l = all(c)
            val exact = l.filter { model.length >= 4 && (it.folder.contains(model) || it.title.lowercase().contains(model)) }
            return exact.ifEmpty { l.filter { it.folder.startsWith(brand) || (Platform.isFire(c) && it.title.contains("Fire OS", true)) } }
        }
    }

    override fun onResume() {
        super.onResume()
        val col = Ui.page(this, "Lista de limpieza", "Qué apps de anuncios y rastreo desactiva «Volver a limpiar». Listas de CleanYourTV (licencia MIT).")
        val cur = current(this)
        fun pick(id: String) { Prefs.put(this, "list", id); Watch.acknowledge(this); finish() }
        Ui.section(col, "Tu tele: ${Build.MANUFACTURER} ${Build.MODEL}")
        Ui.row(col, "La de esta tele", "La que aplicaste con CleanYourTV (${Cleaner.count(this, "custom")} apps)", if (cur == "custom") "✓" else null) { pick("custom") }.valueColor(Ui.OK)
        matches(this).forEach { m ->
            Ui.row(col, m.title, "${m.os} · ${Cleaner.count(this, m.folder)} apps", if (cur == m.folder) "✓" else null) { pick(m.folder) }.valueColor(Ui.OK)
        }
        Ui.section(col, "Todas (${all(this).size} modelos)")
        all(this).forEach { m ->
            Ui.row(col, m.title, m.os, if (cur == m.folder) "✓" else null) { pick(m.folder) }.valueColor(Ui.OK)
        }
    }
}
