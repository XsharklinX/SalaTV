package com.salatv.app

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.os.Bundle
import android.os.SystemClock
import android.view.KeyEvent
import android.view.View
import java.util.Locale
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min

/**
 * «Explorar»: three tabs (Películas, Series, Anime), each with many shelves of posters from the
 * streaming apps installed on this TV. The top of the screen describes the focused title.
 */
class ExploreActivity : Activity() {
    private lateinit var view: ExploreView
    private var tab = "movie"

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        Fonts.init(this)
        view = ExploreView(this)
        setContentView(view)
        tab = Prefs.str(this, "exploreTab", "movie")!!
        load(tab)
    }

    override fun onResume() { super.onResume(); view.clearZoom() }

    fun load(t: String) {
        tab = t
        Prefs.put(this, "exploreTab", t)
        view.setTab(t, withBecause(t, Tmdb.cachedShelves(this, t)))
        Bg.run({ Tmdb.shelves(this, t) }) { r ->
            if (t != tab) return@run
            r.onSuccess { view.setTab(t, withBecause(t, it)) }.onFailure { Ui.toast(this, "No se pudieron cargar las recomendaciones") }
        }
    }

    private fun withBecause(t: String, l: List<Shelf>): List<Shelf> {
        val (seed, items) = Because.cached(this)
        if (t == "movie" || items.isEmpty()) return l
        return listOf(Shelf("because", "Porque viste $seed", items)) + l
    }

    fun open(t: Title) {
        startActivity(Intent(this, TitleActivity::class.java).putExtra("json", t.toJson().toString()))
        @Suppress("DEPRECATION") overridePendingTransition(android.R.anim.fade_in, 0)
    }

    fun menu(t: Title, apps: List<String>, anchor: Rect) {
        val items = apps.map { pkg -> "Ver en ${Services.name(pkg)}" to { Deep.title(this, pkg, t) } }.toMutableList()
        items += "Ficha" to { open(t) }
        val full = Title(t.type, t.id, t.name, t.overview, t.backdrop, t.poster, t.rating, t.year, apps, t.logo)
        if (MyList.canAdd(this, full)) items += "Añadir a mi lista de Crunchyroll" to {
            Bg.run({ MyList.toggle(this, full) }) { r -> Ui.toast(this, if (r.getOrNull() == true) "Añadida a tu lista de Crunchyroll" else "Quitada de tu lista") }
        }
        Ui.bubble(this, t.name, items, emptyList(), anchor, apps.firstOrNull()?.let { Services.badge(it).second } ?: Ui.LAMP)
    }

    override fun onKeyDown(code: Int, ev: KeyEvent): Boolean = view.key(code, ev) || super.onKeyDown(code, ev)
    override fun onKeyUp(code: Int, ev: KeyEvent): Boolean = view.keyUp(code, ev) || super.onKeyUp(code, ev)
    override fun onKeyLongPress(code: Int, ev: KeyEvent): Boolean = view.longPress(code) || super.onKeyLongPress(code, ev)
}

class ExploreView(private val act: ExploreActivity) : View(act) {
    private val tabs = listOf("movie" to "Películas", "tv" to "Series", "anime" to "Anime")
    private var tab = "movie"
    private var shelves: List<Shelf> = emptyList()
    /** -1 = tabs, 0… = shelves. */
    private var row = 0
    private var col = 0
    private var longPressed = false

    private var u = 1f
    private var lastFrame = 0L
    private var frameK = 0.2f
    private var dt = 0.016f
    private val scroll = Spring1()
    private val offs = HashMap<String, Spring1>()
    private val scales = HashMap<String, HomeView.Spring>()
    private val zoom = Spring1(0f, soft = true)
    private val zoomFrom = RectF()
    private var zoomArt: Bitmap? = null
    private var pendingOpen: Title? = null

    private val apps = HashMap<String, List<String>>()
    private val asking = HashSet<String>()

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val glow = Paint(Paint.ANTI_ALIAS_FLAG)
    private val bmp = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val txt = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()
    private val rect2 = RectF()
    private val src = Rect()
    private val m = Matrix()

    /** Exponential follower for scrolling; [soft] for the zoom. */
    private class Spring1(var v: Float = 0f, val soft: Boolean = false) {
        var t = v
        fun step(k: Float): Boolean { val d = t - v; if (abs(d) < 0.01f) { v = t; return false }; v += d * k; return true }
        fun snap(x: Float) { v = x; t = x }
    }

    init { isFocusable = true }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) { u = w / 960f; faded = null }
    private fun s(v: Float) = v * u

    private val posterW = 112f
    private val posterH = 168f
    private val step = posterW + 16f
    private val pitch = 26f + posterH + 30f
    private val shelfTop = 318f

    fun setTab(t: String, l: List<Shelf>) {
        val same = t == tab && shelves.map { it.id } == l.map { it.id }
        tab = t; shelves = l
        if (!same && row >= 0) { row = 0; col = 0; offs.clear(); scroll.snap(0f) }
        row = row.coerceAtMost(shelves.size - 1)
        changed()
    }

    private fun cur(): Title? = shelves.getOrNull(row)?.items?.getOrNull(col)

    private fun changed() {
        scroll.t = max(0, row) * pitch
        shelves.getOrNull(row)?.let { sh ->
            val o = offs.getOrPut(sh.id) { Spring1() }
            val per = ((960f - 96f) / step).toInt()
            o.t = ((col - (per - 2)) * step).coerceIn(0f, max(0f, (sh.items.size - per) * step))
            // «Precarga»: the next posters and the neighbours' backdrops before you get there.
            sh.items.drop(col + 1).take(6).forEach { img(it.poster, "w342", 240) }
            sh.items.getOrNull(col + 1)?.let { img(it.backdrop, "w1280", 1280) }
            shelves.getOrNull(row + 1)?.items?.take(7)?.forEach { img(it.poster, "w342", 240) }
        }
        cur()?.let { fetchApps(it) }
        invalidate()
    }

    private fun fetchApps(t: Title) {
        if (t.key in apps || !asking.add(t.key)) return
        Bg.run({ Tmdb.apps(context, t.type, t.id) to (try { Tmdb.logo(context, t.type, t.id) } catch (e: Exception) { null }) }) { r ->
            asking.remove(t.key)
            apps[t.key] = r.getOrNull()?.first ?: emptyList()
            r.getOrNull()?.second?.let { logos[t.key] = it }
            invalidate()
        }
    }

    private val logos = HashMap<String, String>()

    private fun img(path: String?, size: String, w: Int): Bitmap? =
        Img.get(context, path?.let { if (it.startsWith("http")) it else Tmdb.IMG + size + it }, w) { invalidate() }

    // ---------------------------------------------------------------- keys

    fun key(code: Int, ev: KeyEvent): Boolean {
        if (pendingOpen != null) return true
        when (code) {
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> { if (ev.repeatCount == 0) { ev.startTracking(); longPressed = false }; return true }
            KeyEvent.KEYCODE_MENU -> { menu(); return true }
            KeyEvent.KEYCODE_MEDIA_REWIND -> { col = 0; changed(); return true }
            KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> { col = max(0, (shelves.getOrNull(row)?.items?.size ?: 1) - 1); changed(); return true }
        }
        val dir = when (code) { KeyEvent.KEYCODE_DPAD_LEFT -> 0; KeyEvent.KEYCODE_DPAD_RIGHT -> 1; KeyEvent.KEYCODE_DPAD_UP -> 2; KeyEvent.KEYCODE_DPAD_DOWN -> 3; else -> return false }
        val r0 = row; val c0 = col
        if (row == -1) {
            val i = tabs.indexOfFirst { it.first == tab }
            when (dir) {
                0 -> if (i > 0) { act.load(tabs[i - 1].first); Sounds.tick(context) }
                1 -> if (i < tabs.size - 1) { act.load(tabs[i + 1].first); Sounds.tick(context) }
                3 -> if (shelves.isNotEmpty()) row = 0
            }
        } else {
            val n = shelves.getOrNull(row)?.items?.size ?: 0
            when (dir) {
                0 -> if (col > 0) col--
                1 -> if (col < n - 1) col++
                2 -> if (row > 0) { row--; col = min(col, (shelves[row].items.size - 1)) } else row = -1
                3 -> if (row < shelves.size - 1) { row++; col = min(col, shelves[row].items.size - 1) }
            }
        }
        if (row != r0 || col != c0) { Sounds.tick(context); changed() }
        return true
    }

    fun keyUp(code: Int, ev: KeyEvent): Boolean {
        if (code != KeyEvent.KEYCODE_DPAD_CENTER && code != KeyEvent.KEYCODE_ENTER) return false
        if (!longPressed && !ev.isCanceled && row >= 0) cur()?.let { startZoom(it) }
        longPressed = false
        return true
    }

    fun longPress(code: Int): Boolean {
        if (code != KeyEvent.KEYCODE_DPAD_CENTER && code != KeyEvent.KEYCODE_ENTER) return false
        longPressed = true; menu(); return true
    }

    private fun menu() {
        val t = cur() ?: return
        focusRect(rect2)
        act.menu(t, apps[t.key] ?: emptyList(), Rect(s(rect2.left).toInt(), s(rect2.top).toInt(), s(rect2.right).toInt(), s(rect2.bottom).toInt()))
    }

    private fun focusRect(out: RectF) {
        val sh = shelves.getOrNull(row) ?: return out.setEmpty()
        val x = 48f + col * step - (offs[sh.id]?.v ?: 0f)
        val y = shelfTop + 26f
        out.set(x, y, x + posterW, y + posterH)
    }

    /** «Transición a la ficha»: the poster grows into the title's picture, then the page fades in on top. */
    private fun startZoom(t: Title) {
        Sounds.open(context)
        focusRect(zoomFrom)
        zoomArt = img(t.backdrop, "w1280", 1280) ?: img(t.poster, "w342", 240)
        zoom.v = 0f; zoom.t = 1f
        pendingOpen = t
        invalidate()
    }

    fun clearZoom() { zoom.snap(0f); pendingOpen = null; invalidate() }

    // ---------------------------------------------------------------- drawing

    override fun onDraw(c: Canvas) {
        val now = SystemClock.uptimeMillis()
        val ms = if (lastFrame == 0L) 16f else min(64f, (now - lastFrame).toFloat())
        lastFrame = now
        frameK = 1f - exp(-ms / 90f)
        dt = ms / 1000f
        var busy = scroll.step(frameK)
        offs.values.forEach { busy = it.step(frameK) or busy }
        val w = width.toFloat(); val h = height.toFloat()
        c.drawColor(0xFF07080C.toInt())
        val t = cur()
        drawHero(c, t, w)
        // Shelves scroll under the description.
        c.save()
        c.clipRect(0f, s(shelfTop - 6f), w, h)
        c.translate(0f, -s(scroll.v))
        for ((i, sh) in shelves.withIndex()) {
            val y = shelfTop + i * pitch
            if (y - scroll.v > 560f || y + pitch - scroll.v < shelfTop - 40f) continue
            txt.typeface = Fonts.medium; txt.textSize = s(15f); txt.color = Color.WHITE; txt.alpha = if (i == row) 235 else 150
            c.drawText(sh.label, s(48f), s(y + 16f), txt)
            txt.alpha = 255
            val off = offs.getOrPut(sh.id) { Spring1() }.v
            for ((k, item) in sh.items.withIndex()) {
                val x = 48f + k * step - off
                if (x + posterW < 0f || x > 960f) continue
                busy = poster(c, item, x, y + 26f, i == row && k == col) or busy
            }
        }
        c.restore()
        drawTabs(c, w)
        if (shelves.isEmpty()) {
            txt.typeface = Fonts.light; txt.textSize = s(16f); txt.color = Color.WHITE; txt.alpha = 180
            c.drawText("Cargando recomendaciones de tus apps…", s(48f), s(shelfTop + 30f), txt)
            txt.alpha = 255
        }
        if (zoom.t > 0f) {
            busy = zoom.step(min(1f, frameK * 1.3f)) or busy
            val e = 1f - (1f - zoom.v) * (1f - zoom.v)
            rect.set(s(zoomFrom.left) * (1 - e), s(zoomFrom.top) * (1 - e), s(zoomFrom.right) + (w - s(zoomFrom.right)) * e, s(zoomFrom.bottom) + (h - s(zoomFrom.bottom)) * e)
            fill.color = 0xFF05060A.toInt(); c.drawRect(rect, fill)
            zoomArt?.let { crop(c, it, rect, bmp) }
            if (pendingOpen != null && zoom.v > 0.95f) { val p = pendingOpen; pendingOpen = null; if (p != null) post { act.open(p) } }
        }
        if (busy) postInvalidateOnAnimation()
    }

    private fun crop(c: Canvas, b: Bitmap, r: RectF, p: Paint) {
        val want = r.width() / r.height(); val have = b.width.toFloat() / b.height
        if (have > want) { val cw = (b.height * want).toInt(); val x = (b.width - cw) / 2; src.set(x, 0, x + cw, b.height) }
        else { val ch = (b.width / want).toInt(); val y = (b.height - ch) / 2; src.set(0, y, b.width, y + ch) }
        c.drawBitmap(b, src, r, p)
    }

    private var fadedSrc: Bitmap? = null
    private var faded: Bitmap? = null

    private fun fadedArt(art: Bitmap, w: Int, h: Int): Bitmap {
        val f = faded
        if (f != null && fadedSrc === art) return f
        val out = Bitmap.createBitmap(max(1, w / 2), max(1, h / 2), Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        val r = RectF(0f, 0f, out.width.toFloat(), out.height.toFloat())
        crop(c, art, r, Paint(Paint.FILTER_BITMAP_FLAG))
        val mk = Paint().apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN) }
        mk.shader = LinearGradient(0f, 0f, r.width() * .5f, 0f, 0, Color.BLACK, Shader.TileMode.CLAMP); c.drawRect(r, mk)
        mk.shader = LinearGradient(0f, r.bottom, 0f, r.bottom - r.height() * .55f, 0, Color.BLACK, Shader.TileMode.CLAMP); c.drawRect(r, mk)
        fadedSrc = art; faded = out
        return out
    }

    private fun drawHero(c: Canvas, t: Title?, w: Float) {
        if (t == null) return
        val art = img(t.backdrop, "w1280", 1280)
        if (art != null) {
            rect.set(s(360f), 0f, w, s(shelfTop + 10f))
            c.drawBitmap(fadedArt(art, rect.width().toInt(), rect.height().toInt()), null, rect, bmp)
        }
        val x = 48f
        var y = 100f
        val pkgs = apps[t.key]
        var kx = x
        pkgs?.take(3)?.forEach { pkg ->
            val (name, bg, fg) = Services.badge(pkg)
            txt.typeface = Fonts.bold; txt.textSize = s(11f)
            val tw = txt.measureText(name)
            rect2.set(s(kx), s(y) - s(11.5f), s(kx) + tw + s(10f), s(y) + s(4f))
            fill.color = bg; c.drawRoundRect(rect2, s(4f), s(4f), fill)
            txt.color = fg; c.drawText(name, s(kx + 5f), s(y), txt)
            kx += tw / u + 18f
        }
        if (pkgs != null && pkgs.isEmpty()) { txt.typeface = Fonts.regular; txt.textSize = s(12f); txt.color = Ui.WARN; c.drawText("No está en tus apps en tu país", s(x), s(y), txt) }
        val logo = logos[t.key]?.let { img(it, "w500", 500) }
        if (logo != null) {
            val k = min(380f / logo.width * u, 96f / logo.height * u)
            rect2.set(s(x), s(y + 12f), s(x) + logo.width * k, s(y + 12f) + logo.height * k)
            glow.setShadowLayer(s(10f), 0f, s(2f), 0x88000000.toInt())
            c.drawBitmap(logo, null, rect2, glow); glow.clearShadowLayer()
            y += 12f + logo.height * k / u
        } else {
            txt.typeface = Fonts.bold; txt.textSize = s(40f); txt.color = Color.WHITE
            var size = 40f
            while (txt.measureText(t.name) > s(520f) && size > 24f) { size -= 2f; txt.textSize = s(size) }
            y += size + 8f
            c.drawText(t.name, s(x), s(y), txt)
        }
        y += 24f
        txt.typeface = Fonts.regular; txt.textSize = s(14f); txt.color = Color.WHITE; txt.alpha = 200
        c.drawText(listOf(t.kind, t.year, t.ratingText).filter { it.isNotEmpty() }.joinToString(" · "), s(x), s(y), txt)
        txt.typeface = Fonts.light; txt.alpha = 215
        var line = ""; var lines = 0
        for (word in t.overview.split(' ')) {
            val test = if (line.isEmpty()) word else "$line $word"
            if (txt.measureText(test) > s(480f)) { y += 20f; c.drawText(line, s(x), s(y), txt); line = word; lines++; if (lines == 3) break } else line = test
        }
        if (lines < 3 && line.isNotEmpty()) { y += 20f; c.drawText(line, s(x), s(y), txt) }
        txt.alpha = 255
    }

    private fun drawTabs(c: Canvas, w: Float) {
        rect.set(0f, 0f, w, s(74f))
        fill.color = Color.BLACK
        fill.shader = LinearGradient(0f, 0f, 0f, s(74f), 0xE607080C.toInt(), 0x0007080C, Shader.TileMode.CLAMP)
        c.drawRect(rect, fill); fill.shader = null
        txt.typeface = Fonts.bold; txt.textSize = s(22f); txt.color = Color.WHITE
        c.drawText("Explorar", s(48f), s(52f), txt)
        var x = 48f + txt.measureText("Explorar") / u + 24f
        for ((id, label) in tabs) {
            txt.typeface = Fonts.medium; txt.textSize = s(14f)
            val tw = txt.measureText(label) / u + 30f
            rect.set(s(x), s(30f), s(x + tw), s(62f))
            val on = id == tab
            val focused = on && row == -1
            if (focused) {
                glow.color = Ui.alpha(Ui.LAMP, 0.4f); glow.setShadowLayer(s(16f), 0f, 0f, Ui.alpha(Ui.LAMP, 0.85f))
                c.drawRoundRect(rect, rect.height() / 2, rect.height() / 2, glow); glow.clearShadowLayer()
                stroke.color = Color.WHITE; stroke.strokeWidth = s(1.5f); c.drawRoundRect(rect, rect.height() / 2, rect.height() / 2, stroke)
            } else {
                fill.color = if (on) Ui.alpha(Ui.LAMP, 0.22f) else 0x1FFFFFFF
                c.drawRoundRect(rect, rect.height() / 2, rect.height() / 2, fill)
                if (on) { stroke.color = Ui.LAMP; stroke.strokeWidth = s(1.2f); c.drawRoundRect(rect, rect.height() / 2, rect.height() / 2, stroke) }
            }
            txt.color = if (on && !focused) Ui.LAMP else Color.WHITE
            c.drawText(label, s(x + 15f), s(51f), txt)
            x += tw + 10f
        }
    }

    private fun poster(c: Canvas, t: Title, x: Float, y: Float, focused: Boolean): Boolean {
        val a = scales.getOrPut(tab + t.key) { HomeView.Spring(1f) }
        a.t = if (focused) 1.09f else 1f
        val busy = a.step(dt)
        val X = s(x); val Y = s(y); val W = s(posterW); val H = s(posterH)
        val r = s(9f)
        c.save()
        c.scale(a.v, a.v, X + W / 2, Y + H / 2)
        rect.set(X, Y, X + W, Y + H)
        if (focused) {
            val col = apps[t.key]?.firstOrNull()?.let { Services.badge(it).second } ?: Ui.LAMP
            glow.color = Ui.alpha(col, 0.9f); glow.setShadowLayer(s(24f), 0f, 0f, Ui.alpha(col, 0.85f))
            c.drawRoundRect(rect, r, r, glow); glow.clearShadowLayer()
        }
        fill.color = 0xFF1A1E28.toInt(); c.drawRoundRect(rect, r, r, fill)
        val b = img(t.poster, "w342", 240)
        if (b != null) {
            val sc = max(rect.width() / b.width, rect.height() / b.height)
            m.setScale(sc, sc); m.postTranslate(rect.left + (rect.width() - b.width * sc) / 2, rect.top + (rect.height() - b.height * sc) / 2)
            val sh = BitmapShader(b, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP); sh.setLocalMatrix(m)
            fill.color = Color.BLACK; fill.shader = sh; c.drawRoundRect(rect, r, r, fill); fill.shader = null
        } else {
            // «Carga con brillo».
            val ph = (SystemClock.uptimeMillis() % 1400L) / 1400f
            val x0 = rect.left - rect.width() + rect.width() * 3f * ph
            fill.color = Color.BLACK
            fill.shader = LinearGradient(x0, 0f, x0 + rect.width(), 0f, intArrayOf(0x00FFFFFF, 0x24FFFFFF, 0x00FFFFFF), null, Shader.TileMode.CLAMP)
            c.drawRoundRect(rect, r, r, fill); fill.shader = null
            txt.typeface = Fonts.medium; txt.textSize = s(12f); txt.color = Color.WHITE; txt.alpha = 160
            c.drawText(t.name.take(16), rect.left + s(8f), rect.bottom - s(10f), txt); txt.alpha = 255
            postInvalidateDelayed(40)
        }
        if (focused) { stroke.color = Color.WHITE; stroke.strokeWidth = s(2f) / a.v; c.drawRoundRect(rect, r, r, stroke) }
        c.restore()
        if (focused) {
            txt.typeface = Fonts.medium; txt.textSize = s(12.5f); txt.color = Color.WHITE
            val name = t.name
            val n = txt.breakText(name, true, W * 1.3f, null)
            c.drawText(if (n < name.length) name.substring(0, n).trimEnd() + "…" else name, X, Y + H + H * (a.v - 1f) / 2 + s(18f), txt)
        }
        return busy
    }
}
