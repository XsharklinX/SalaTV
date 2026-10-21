package com.salatv.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LightingColorFilter
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RadialGradient
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.os.SystemClock
import android.provider.Settings
import android.view.View
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * The home, drawn by hand on one canvas: no layouts, no libraries. Everything is placed on a 960×540
 * grid (the TV's size in dp) and scaled by [u]; [dens] makes tiles and cards smaller or larger.
 *
 * Lines from top to bottom: the chips, the trends carousel (when TMDB is on), the dock on the glass
 * shelf, the card rows («Seguir viendo», «Mi lista», «Temporada») and the grid of every other app.
 * The top of the screen always describes what has the focus.
 */
class HomeView(context: Context) : View(context) {

    interface Host {
        fun onOpen(item: Item)
        fun onChip(chip: Chip)
        fun onMenu(item: Item, inDock: Boolean, anchor: Rect)
        fun onDockMoved(pkgs: List<String>)
        fun onMove()
        /** 0 = watch in its app, 1 = add to or remove from «Mi lista», 2 = title page. */
        fun onTrend(t: Title, action: Int)
        fun onChipMenu(chip: Chip)
    }

    sealed class Item(val key: String) {
        class App(val e: AppEntry, val note: String?) : Item("a:" + e.pkg)
        class Fold(val f: Folder, val apps: List<AppEntry>) : Item("f:" + f.id)
        class Hidden(val count: Int) : Item("hidden")
        /** «Seguir viendo» or «Temporada». */
        class Card(val s: Show, val row: String) : Item("s:" + s.key)
        /** «Mi lista». */
        class Pick(val t: Title) : Item(t.key)
    }

    class Chip(val id: String, val label: String, val icon: Int, val title: String, val desc: String, val warn: Boolean = false, val color: Int = 0)

    class CardRow(val id: String, val label: String, val items: List<Item>)

    private enum class Kind { CHIPS, HERO, DOCK, CARDS, GRID }

    private class Line(val kind: Kind, val items: List<Any>, val y: Float, val label: String? = null)

    private class Anim(var v: Float) {
        var t = v
        fun step(k: Float): Boolean {
            val d = t - v
            if (abs(d) < 0.002f) { v = t; return false }
            v += d * k
            return true
        }
        fun snap(x: Float) { v = x; t = x }
    }

    /** «Foco con efecto muelle»: a light spring that overshoots a little and settles in about 200 ms. */
    class Spring(var v: Float) {
        var t = v
        private var vel = 0f
        fun step(dt: Float): Boolean {
            if (abs(t - v) < 0.0015f && abs(vel) < 0.01f) { v = t; vel = 0f; return false }
            val h = dt.coerceAtMost(0.032f)
            vel += ((t - v) * 520f - vel * 26f) * h
            v += vel * h
            return true
        }
    }

    companion object {
        const val SEARCH = 1; const val INPUT = 2; const val WIFI = 3; const val GEAR = 4
        const val AVATAR = 5; const val WARN = 6; const val NOWIFI = 7; const val COMPASS = 8
        private const val MARGIN = 48f
    }

    var host: Host? = null
    /** Whether a title can go to «Mi lista» (it is on Crunchyroll and the account is connected). */
    var canList: (Title) -> Boolean = { false }
    var placeholder: Bitmap? = null

    private var chips: List<Chip> = emptyList()
    private var trends: List<Title> = emptyList()
    private val dock = mutableListOf<Item>()
    private var rows: List<CardRow> = emptyList()
    private var grid: List<Item> = emptyList()
    private var gridLabel = "Todas tus apps"
    private var greetName: String? = null
    private var mine: Set<String> = emptySet()
    private var lines: List<Line> = emptyList()
    private var li = 0
    private var col = 0
    private var focusKey: String? = null
    var moveMode = false
        private set
    private var fast = false

    private var trendIx = 0
    private var trendSince = SystemClock.uptimeMillis()
    private var lastInput = SystemClock.uptimeMillis()
    private var noticeText: String? = null
    private var noticeUntil = 0L

    private var u = 1f
    private var dens = 1f
    private var pal = Pal(false)
    private var kenBurns = true
    private var lastFrame = 0L
    private var frameK = 0.2f
    private val scroll = Anim(0f)
    private val rowOff = HashMap<String, Anim>()
    private val fade = Anim(1f)
    private val mix = Anim(1f)
    private val zoom = Anim(0f)
    private val scales = HashMap<String, Spring>()
    private var frameDt = 0.016f
    /** «Profundidad al moverte»: the background picture drifts a little against the focus. */
    private val parallax = Anim(0f)
    private var bgCur: Bitmap? = null
    private var bgPrev: Bitmap? = null
    private var colorPrev = Ui.LAMP
    private var colorCur = Ui.LAMP
    private var chipCx = FloatArray(0)
    private val zoomFrom = RectF()
    private var zoomColor = 0
    private var zoomArt: Bitmap? = null
    private var pending: Item? = null

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
    private val bmp = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val glow = Paint(Paint.ANTI_ALIAS_FLAG)
    private val txt = Paint(Paint.ANTI_ALIAS_FLAG)
    private val mask = Paint(Paint.ANTI_ALIAS_FLAG).apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN) }
    private val shade = Paint()
    private val darken = LightingColorFilter(0xFF8C8C8C.toInt(), 0)
    private val rect = RectF()
    private val rect2 = RectF()
    private val src = Rect()
    private val m = Matrix()
    private val path = Path()
    private val clockFmt = SimpleDateFormat("HH:mm", Locale.getDefault())
    private val dateFmt = SimpleDateFormat("EEEE d 'de' MMMM", Locale("es"))

    private val tick = object : Runnable {
        override fun run() {
            pal = Pal(Look.isDay(context))
            val now = SystemClock.uptimeMillis()
            val onHero = lines.getOrNull(li)?.kind == Kind.HERO
            if (trends.size > 1 && now - trendSince > Prefs.int(context, "carouselSpeed", 8) * 1000L && now - lastInput > (if (onHero) 15000 else 6000) && pending == null) {
                trendIx = (trendIx + 1) % trends.size
                trendSince = now
                trends.getOrNull((trendIx + 1) % trends.size)?.let { img(tmdb(it.backdrop, "w1280"), 1280) }
                if (heroShowsTrend()) focusChanged(true)
            }
            invalidate()
            postDelayed(this, 1000)
        }
    }

    override fun onAttachedToWindow() { super.onAttachedToWindow(); post(tick) }
    override fun onDetachedFromWindow() { removeCallbacks(tick); super.onDetachedFromWindow() }
    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) { u = w / 960f }

    private fun s(v: Float) = v * u

    // ---------------------------------------------------------------- layout

    private fun tileW() = min(136f * dens, (864f - (dock.size - 1) * 18f * dens) / max(1, dock.size))
    private fun tileH() = tileW() * 76f / 136f
    private fun heroBottom() = when { dens < 0.95f -> 330f; dens > 1.05f -> 372f; else -> 360f }
    private fun dockY() = heroBottom() + 24f * dens
    private fun shelfBottom() = dockY() + 76f * dens + 26f * dens
    private fun cardW() = 212f * dens
    private fun cardH() = cardW() * 9f / 16f
    private fun cardStep() = cardW() + 18f * dens
    private fun cardsPerScreen() = ((960f - MARGIN * 2 + 18f * dens) / cardStep()).toInt()
    private fun gW() = 108f * dens
    private fun gH() = gW() * 61f / 108f
    private fun gStep() = gW() + 16f * dens
    private fun cols() = max(4, ((960f - MARGIN * 2 + 16f * dens) / gStep()).toInt())

    private fun build() {
        val l = mutableListOf<Line>()
        l += Line(Kind.CHIPS, chips, 0f)
        if (trends.isNotEmpty()) l += Line(Kind.HERO, listOf("ver", "lista"), 0f)
        if (dock.isNotEmpty()) l += Line(Kind.DOCK, dock, dockY())
        var y = shelfBottom() + 30f * dens
        rows.filter { it.items.isNotEmpty() }.forEach { r ->
            l += Line(Kind.CARDS, r.items, y, r.label)
            y += 26f * dens + cardH() + 52f * dens
        }
        if (grid.isNotEmpty()) {
            y += 26f * dens
            val n = cols()
            grid.chunked(n).forEachIndexed { i, chunk -> l += Line(Kind.GRID, chunk, y, if (i == 0) gridLabel else null); y += gH() + 42f * dens }
        }
        lines = l
    }

    // ---------------------------------------------------------------- data and focus

    fun setData(newChips: List<Chip>, newTrends: List<Title>, newDock: List<Item>, newRows: List<CardRow>, newGrid: List<Item>,
                name: String?, label: String, myList: Set<String>) {
        chips = newChips
        if (newTrends.map { it.key } != trends.map { it.key }) { trendIx = 0; trendSince = SystemClock.uptimeMillis() }
        trends = newTrends
        dock.clear(); dock.addAll(newDock)
        rows = newRows
        grid = newGrid
        greetName = name
        gridLabel = label
        mine = myList
        placeholder = null
        pal = Pal(Look.isDay(context))
        dens = when (Prefs.int(context, "density", 1)) { 0 -> 0.84f; 2 -> 1.12f; else -> 1f }
        kenBurns = Prefs.bool(context, "kenBurns", true)
        build()
        val keep = focusKey
        var found = false
        if (keep != null) for ((i, line) in lines.withIndex()) {
            val c = line.items.indexOfFirst { keyOf(it) == keep }
            if (c >= 0) { li = i; col = c; found = true; break }
        }
        if (!found) {
            li = lines.indexOfFirst { it.kind == Kind.DOCK }.takeIf { it >= 0 } ?: lines.indexOfFirst { it.kind == Kind.HERO }.coerceAtLeast(0)
            col = 0
        }
        clamp()
        focusChanged(bgCur != null)
    }

    /** A short message under the clock for a few seconds («Nuevo episodio…»). */
    fun notice(text: String) {
        noticeText = text
        noticeUntil = SystemClock.uptimeMillis() + 7000
        invalidate()
    }

    private fun keyOf(o: Any?): String? = when (o) { is Chip -> "c:" + o.id; is Item -> o.key; is String -> "hero:$o"; else -> null }

    private fun clamp() {
        li = li.coerceIn(0, max(0, lines.size - 1))
        col = col.coerceIn(0, max(0, (lines.getOrNull(li)?.items?.size ?: 1) - 1))
    }

    private fun line() = lines.getOrNull(li)
    private fun current(): Any? = line()?.items?.getOrNull(col)
    private fun trend(): Title? = trends.getOrNull(trendIx)
    private fun heroShowsTrend() = trends.isNotEmpty() && line()?.kind.let { it == Kind.HERO || it == Kind.DOCK || (it == Kind.CHIPS && (current() as? Chip)?.warn != true) }

    private fun itemColor(o: Any?): Int = when (o) {
        is Item.App -> o.e.color
        is Item.Card -> Services.badge(o.s.pkg).second
        is Item.Pick -> o.t.pkgs.firstOrNull()?.let { Services.badge(it).second } ?: Ui.LAMP
        is Item.Fold -> o.apps.firstOrNull()?.color ?: Ui.LAMP
        is Chip -> if (o.warn) Ui.WARN else if (o.color != 0) o.color else Ui.LAMP
        else -> trend()?.pkgs?.firstOrNull()?.let { Services.badge(it).second } ?: Ui.LAMP
    }

    private fun img(src: String?, w: Int): Bitmap? = Img.get(context, src, w) { invalidate() }

    private fun tmdb(path: String?, size: String) = path?.let { if (it.startsWith("http")) it else Tmdb.IMG + size + it }

    /** The big picture behind the top of the screen for what has the focus. */
    private fun heroArt(): Bitmap? {
        val cur = current()
        return when {
            cur is Item.Card -> img(cur.s.image, 960)
            cur is Item.Pick -> img(tmdb(cur.t.backdrop, "w780"), 960)
            heroShowsTrend() -> img(tmdb(trend()?.backdrop, "w1280"), 1280)
            cur is Item.App -> cur.e.banner ?: cur.e.icon
            cur is Item.Fold -> cur.apps.firstOrNull()?.banner
            else -> null
        }
    }

    private fun focusChanged(animate: Boolean) {
        val cur = current()
        focusKey = keyOf(cur)
        val color = itemColor(cur)
        val art = heroArt()
        val bg: Bitmap = when {
            art != null -> AppRepo.blurredArt(focusKey + (if (heroShowsTrend()) trend()?.key else ""), art)
            cur is Item.App -> AppRepo.blurred(cur.e)
            else -> AppRepo.plain(color)
        }
        if (bg !== bgCur) {
            bgPrev = bgCur; bgCur = bg
            if (animate) { fade.v = 0f; fade.t = 1f } else fade.snap(1f)
        }
        if (color != colorCur) {
            colorPrev = Ui.lerp(colorPrev, colorCur, mix.v); colorCur = color
            if (animate) { mix.v = 0f; mix.t = 1f } else mix.snap(1f)
        }
        val ln = line()
        scroll.t = if (ln == null || ln.kind == Kind.CHIPS || ln.kind == Kind.HERO || ln.kind == Kind.DOCK) 0f else ln.y - 250f
        if (ln?.kind == Kind.CARDS) {
            val a = rowOff.getOrPut(ln.label ?: "") { Anim(0f) }
            val per = cardsPerScreen()
            a.t = ((col - (per - 2)) * cardStep()).coerceIn(0f, max(0f, (ln.items.size - per) * cardStep()))
            if (!animate) a.snap(a.t)
        }
        parallax.t = if (ln?.kind == Kind.DOCK || ln?.kind == Kind.CARDS) -min(60f, col * 7f) else 0f
        if (!animate) { scroll.snap(scroll.t); parallax.snap(parallax.t) }
        invalidate()
    }

    private fun centerX(i: Int, c: Int): Float {
        val ln = lines.getOrNull(i) ?: return 0f
        return when (ln.kind) {
            Kind.CHIPS -> chipCx.getOrElse(c) { 800f }
            Kind.HERO -> MARGIN + 80f + c * 190f
            Kind.DOCK -> MARGIN + c * (tileW() + 18f * dens) + tileW() / 2
            Kind.CARDS -> MARGIN + c * cardStep() + cardW() / 2 - (rowOff[ln.label ?: ""]?.t ?: 0f)
            Kind.GRID -> MARGIN + c * gStep() + gW() / 2
        }
    }

    private fun nearest(i: Int, x: Float): Int {
        val n = lines.getOrNull(i)?.items?.size ?: return 0
        var best = 0; var d = Float.MAX_VALUE
        for (c in 0 until n) { val dd = abs(centerX(i, c) - x); if (dd < d) { d = dd; best = c } }
        return best
    }

    /** 0 left, 1 right, 2 up, 3 down. [repeat] is the key's repeat count: held arrows speed up. */
    fun key(dir: Int, repeat: Int = 0): Boolean {
        lastInput = SystemClock.uptimeMillis()
        fast = repeat > 0
        if (moveMode) {
            if (dir == 0 || dir == 1) moveBy(if (dir == 0) -1 else 1)
            return true
        }
        val ln = line() ?: return true
        val i0 = li; val c0 = col; val t0 = trendIx
        val steps = if (repeat > 12 && dir < 2) 2 else 1
        repeat(steps) {
            val x = centerX(li, col)
            when (dir) {
                0 -> if (col > 0) col-- else if (ln.kind == Kind.HERO && trends.size > 1) { trendIx = (trendIx - 1 + trends.size) % trends.size; trendSince = lastInput }
                1 -> if (col < ln.items.size - 1) col++ else if (ln.kind == Kind.HERO && trends.size > 1) { trendIx = (trendIx + 1) % trends.size; trendSince = lastInput; col = 0 }
                2 -> if (li > 0) { li--; col = nearest(li, x) }
                3 -> if (li < lines.size - 1) { li++; col = nearest(li, x) }
            }
        }
        if (li != i0 || col != c0 || trendIx != t0) { focusChanged(true); host?.onMove() }
        return true
    }

    fun keyUp() { fast = false }

    fun jumpEdge(end: Boolean) {
        if (moveMode) return
        val n = line()?.items?.size ?: return
        val c0 = col
        col = if (end) n - 1 else 0
        if (col != c0) { focusChanged(true); host?.onMove() }
    }

    private fun moveBy(d: Int) {
        val c = col
        val j = c + d
        if (j < 0 || j >= dock.size) return
        val a = dock[c]; dock[c] = dock[j]; dock[j] = a
        col = j
        build()
        host?.onMove()
        invalidate()
    }

    fun activate() {
        lastInput = SystemClock.uptimeMillis()
        if (moveMode) { finishMove(); return }
        val ln = line() ?: return
        when (val cur = current()) {
            is Chip -> host?.onChip(cur)
            is String -> trend()?.let { host?.onTrend(it, if (cur == "ver") 0 else 1) }
            is Item.App, is Item.Card, is Item.Pick -> startZoom(cur as Item, ln)
            is Item -> host?.onOpen(cur)
        }
    }

    private fun animationsOn() = Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) > 0f

    /** Screen rectangle (design units) of what has the focus. */
    private fun focusRect(out: RectF) {
        val ln = line() ?: return out.setEmpty()
        val y = ln.y - scroll.v
        when (ln.kind) {
            Kind.DOCK -> { val x = MARGIN + col * (tileW() + 18f * dens); out.set(x, dockY(), x + tileW(), dockY() + tileH()) }
            Kind.CARDS -> { val x = MARGIN + col * cardStep() - (rowOff[ln.label ?: ""]?.v ?: 0f); val cy = y + 26f * dens; out.set(x, cy, x + cardW(), cy + cardH()) }
            Kind.GRID -> { val x = MARGIN + col * gStep(); out.set(x, y, x + gW(), y + gH()) }
            Kind.HERO -> out.set(MARGIN + col * 190f, 280f, MARGIN + col * 190f + 170f, 320f)
            Kind.CHIPS -> { val cx = chipCx.getOrElse(col) { 800f }; out.set(cx - 40f, 26f, cx + 40f, 64f) }
        }
    }

    /** Where the focused item is on screen, in pixels, for the bubble menu. */
    fun anchor(): Rect {
        focusRect(rect2)
        return Rect(s(rect2.left).toInt(), s(rect2.top).toInt(), s(rect2.right).toInt(), s(rect2.bottom).toInt())
    }

    /** «Abrir con zoom»: the tile grows to fill the screen while the app starts. */
    private fun startZoom(item: Item, ln: Line) {
        if (!animationsOn() || pending != null) { host?.onOpen(item); return }
        focusRect(zoomFrom)
        zoomColor = when (item) { is Item.App -> item.e.tile; else -> Ui.shade(itemColor(item), 0.4f) }
        zoomArt = when (item) { is Item.App -> item.e.banner ?: item.e.icon; is Item.Card -> img(item.s.image, 960); is Item.Pick -> img(tmdb(item.t.backdrop, "w780"), 960); else -> null }
        zoom.v = 0f; zoom.t = 1f
        pending = item
        invalidate()
    }

    fun clearZoom() { zoom.snap(0f); pending = null; invalidate() }

    fun menu() {
        lastInput = SystemClock.uptimeMillis()
        if (moveMode || pending != null) return
        when (val cur = current()) {
            is Item -> host?.onMenu(cur, line()?.kind == Kind.DOCK, anchor())
            is String -> trend()?.let { host?.onTrend(it, 2) }
            is Chip -> host?.onChipMenu(cur)
        }
    }

    fun startMove() {
        if (line()?.kind == Kind.DOCK) { moveMode = true; invalidate() }
    }

    private fun finishMove() {
        moveMode = false
        host?.onDockMoved(dock.filterIsInstance<Item.App>().map { it.e.pkg })
        invalidate()
    }

    private fun home() {
        li = lines.indexOfFirst { it.kind == Kind.DOCK }.takeIf { it >= 0 } ?: 0
        col = 0
        clamp()
        focusChanged(true)
    }

    fun back(): Boolean {
        if (moveMode) { finishMove(); return true }
        val dockLine = lines.indexOfFirst { it.kind == Kind.DOCK }
        if (li != dockLine || col != 0) { home(); return true }
        return false
    }

    fun goHome() { moveMode = false; home() }

    // ---------------------------------------------------------------- drawing

    override fun onDraw(c: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        val ph = placeholder
        if (ph != null && chips.isEmpty()) {
            rect.set(0f, 0f, w, h); c.drawBitmap(ph, null, rect, bmp); return
        }
        val now = SystemClock.uptimeMillis()
        val dt = if (lastFrame == 0L) 16f else min(64f, (now - lastFrame).toFloat())
        lastFrame = now
        frameK = min(1f, (1f - exp(-dt / 95f)) * if (fast) 2.2f else 1f)
        frameDt = dt / 1000f
        var busy = scroll.step(frameK)
        rowOff.values.forEach { busy = it.step(frameK) or busy }
        busy = fade.step(frameK * 0.75f) or busy
        busy = mix.step(frameK * 0.75f) or busy
        busy = parallax.step(frameK * 0.6f) or busy

        // The whole background (blurred picture, day veil, shades, colour glow) is one pre-composed
        // bitmap per focus: one full-screen draw per frame instead of seven.
        rect.set(0f, 0f, w, h)
        val curBack = backdrop(bgCur, colorCur)
        if (fade.v < 0.99f) {
            backdrop(bgPrev, colorPrev, prev = true)?.let { bmp.alpha = 255; c.drawBitmap(it, null, rect, bmp) }
            bmp.alpha = (fade.v * 255).toInt()
        } else bmp.alpha = 255
        if (curBack != null) c.drawBitmap(curBack, null, rect, bmp) else c.drawColor(pal.bg)
        bmp.alpha = 255

        val moving = kenBurns && heroShowsTrend() && now - lastInput < 120_000 && scroll.v < 5f
        drawHeroArt(c, now, moving)

        // Everything below the top description scrolls; above it, only the description stays.
        val clipTop = if (scroll.v > 2f) 250f - 24f else 0f
        c.save()
        c.clipRect(0f, s(clipTop), w, h)
        c.translate(0f, -s(scroll.v))
        for ((i, ln) in lines.withIndex()) {
            when (ln.kind) {
                Kind.DOCK -> busy = drawDock(c, i, ln) or busy
                Kind.CARDS -> busy = drawCards(c, i, ln) or busy
                Kind.GRID -> busy = drawGrid(c, i, ln) or busy
                else -> {}
            }
        }
        c.restore()
        if (scroll.v > 2f) {
            shade.shader = LinearGradient(0f, s(clipTop), 0f, s(clipTop + 40f), Ui.alpha(pal.bg, .9f), Ui.alpha(pal.bg, 0f), Shader.TileMode.CLAMP)
            c.drawRect(0f, s(clipTop), w, s(clipTop + 40f), shade)
            shade.shader = null
        }
        drawHeroText(c, compact = scroll.v > 2f)
        // A band behind the clock and the chips, so the picture never makes them hard to read.
        shade.shader = LinearGradient(0f, 0f, 0f, s(130f), Ui.alpha(pal.bg, .85f), Ui.alpha(pal.bg, 0f), Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, w, s(130f), shade)
        shade.shader = null
        busy = drawTop(c) or busy
        drawHints(c, w)
        drawNotice(c, w, now)
        if (zoom.t > 0f) {
            busy = zoom.step(min(1f, frameK * 1.15f)) or busy
            drawZoom(c, w, h)
            if (pending != null && zoom.v > 0.96f) {
                val p = pending; pending = null
                if (p != null) post { host?.onOpen(p) }
            }
        }
        // The slow zoom needs no more than 20 frames a second; the remote keeps priority.
        if (busy) postInvalidateOnAnimation() else if (moving) postInvalidateDelayed(50)
    }

    private class Back(val src: Bitmap, val color: Int, val day: Boolean, val out: Bitmap)
    private var backCur: Back? = null
    private var backPrev: Back? = null

    /**
     * Background for [b] and [color], composed once at a quarter of the screen (it is all blur and
     * gradients, so the size does not show) and reused until the focus changes.
     */
    private fun backdrop(b: Bitmap?, color: Int, prev: Boolean = false): Bitmap? {
        if (b == null || width == 0) return null
        val cached = if (prev) backPrev else backCur
        if (cached != null && cached.src === b && cached.color == color && cached.day == pal.day) return cached.out
        if (!prev) {
            val old = backCur
            if (old != null && old.src === bgPrev) backPrev = old
        }
        val w = width / 4f; val h = height / 4f
        val out = Bitmap.createBitmap(w.toInt(), h.toInt(), Bitmap.Config.RGB_565)
        val c = Canvas(out)
        val r = RectF(0f, 0f, w, h)
        c.drawColor(pal.bg)
        val p = Paint(Paint.FILTER_BITMAP_FLAG)
        if (!pal.day) p.colorFilter = darken
        c.drawBitmap(b, null, r, p)
        val q = Paint()
        if (pal.day) { q.color = Ui.alpha(pal.bg, 0.5f); c.drawRect(r, q) }
        q.shader = LinearGradient(0f, 0f, w * 0.66f, 0f, intArrayOf(Ui.alpha(pal.bg, .94f), Ui.alpha(pal.bg, .6f), Ui.alpha(pal.bg, 0f)), floatArrayOf(0f, .45f, 1f), Shader.TileMode.CLAMP)
        c.drawRect(r, q)
        q.shader = LinearGradient(0f, h, 0f, h * .3f, Ui.alpha(pal.bg, .95f), Ui.alpha(pal.bg, 0f), Shader.TileMode.CLAMP)
        c.drawRect(r, q)
        q.shader = RadialGradient(w * .25f, h * 1.05f, w * .55f, Ui.alpha(color, if (pal.day) .25f else .38f), 0, Shader.TileMode.CLAMP)
        c.drawRect(r, q)
        val back = Back(b, color, pal.day, out)
        if (prev) backPrev = back else backCur = back
        return out
    }

    private fun crop(c: Canvas, b: Bitmap, r: RectF, p: Paint) {
        val want = r.width() / r.height()
        val have = b.width.toFloat() / b.height
        if (have > want) {
            val cw = (b.height * want).toInt(); val x = (b.width - cw) / 2
            src.set(x, 0, x + cw, b.height)
        } else {
            val ch = (b.width / want).toInt(); val y = (b.height - ch) / 2
            src.set(0, y, b.width, y + ch)
        }
        c.drawBitmap(b, src, r, p)
    }

    /** The picture of what has the focus on the right, fading left and down; slowly zooming for trends. */
    private fun drawHeroArt(c: Canvas, now: Long, moving: Boolean) {
        val art = heroArt() ?: return
        val small = art.width < art.height * 1.3f
        val a = (if (scroll.v > 2f) 0.55f else 1f) * (0.35f + 0.65f * fade.v)
        val bottom = heroBottom() + 10f
        rect.set(s(400f), 0f, s(960f), s(bottom))
        bmp.alpha = (a * 255).toInt()
        if (small) {
            val sz = s(140f)
            rect2.set(s(700f) - sz / 2, s(200f) - sz / 2, s(700f) + sz / 2, s(200f) + sz / 2)
            c.drawBitmap(art, null, rect2, bmp); bmp.alpha = 255
            return
        }
        // Crop and fade once per picture; every frame after that is a single bitmap draw. Doing the
        // layer and the two masks per frame missed 40 % of frames on the TV.
        val faded = fadedArt(art, rect.width().toInt(), rect.height().toInt())
        c.save()
        c.translate(s(parallax.v), 0f)
        if (moving) {
            val k = ((now - trendSince) % 12_000L) / 12_000f
            c.scale(1f + 0.07f * k, 1f + 0.07f * k, rect.centerX() + s(40f), rect.centerY())
        }
        c.drawBitmap(faded, null, rect, bmp)
        c.restore()
        bmp.alpha = 255
    }

    private var fadedSrc: Bitmap? = null
    private var faded: Bitmap? = null

    private fun fadedArt(art: Bitmap, w: Int, h: Int): Bitmap {
        val f = faded
        if (f != null && fadedSrc === art && f.width == w / 2 && f.height == h / 2) return f
        // Half the screen size is plenty for a picture that fades into the background.
        val out = Bitmap.createBitmap(max(1, w / 2), max(1, h / 2), Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        val r = RectF(0f, 0f, out.width.toFloat(), out.height.toFloat())
        crop(c, art, r, Paint(Paint.FILTER_BITMAP_FLAG))
        val m = Paint().apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN) }
        m.shader = LinearGradient(0f, 0f, r.width() * .45f, 0f, 0, Color.BLACK, Shader.TileMode.CLAMP)
        c.drawRect(r, m)
        m.shader = LinearGradient(0f, r.bottom, 0f, r.bottom - r.height() * .55f, 0, Color.BLACK, Shader.TileMode.CLAMP)
        c.drawRect(r, m)
        fadedSrc = art; faded = out
        return out
    }

    private fun badge(c: Canvas, pkg: String, x: Float, base: Float, size: Float): Float {
        val (name, bg, fg) = Services.badge(pkg)
        txt.typeface = Fonts.bold; txt.textSize = s(size); txt.letterSpacing = 0f
        val tw = txt.measureText(name)
        rect2.set(s(x), s(base) - s(size) * 1.05f, s(x) + tw + s(size * 0.9f), s(base) + s(size) * 0.38f)
        fill.color = bg
        c.drawRoundRect(rect2, s(4f), s(4f), fill)
        txt.color = fg
        c.drawText(name, s(x) + s(size * 0.45f), s(base), txt)
        return x + (tw / u) + size * 0.9f
    }

    private fun wrap(text: String, maxW: Float, maxLines: Int): List<String> {
        val out = mutableListOf<String>()
        var cur = ""
        for (w in text.split(' ')) {
            val t = if (cur.isEmpty()) w else "$cur $w"
            if (txt.measureText(t) <= maxW) cur = t else {
                if (cur.isNotEmpty()) out += cur
                cur = w
                if (out.size == maxLines) break
            }
        }
        if (out.size < maxLines && cur.isNotEmpty()) out += cur
        if (out.size == maxLines && out.joinToString(" ").length < text.length) out[maxLines - 1] = ellipsize(out[maxLines - 1] + "…", maxW)
        return out.take(maxLines)
    }

    private class HeroInfo(val pkg: String?, val kicker: String, val title: String, val meta: String, val body: String, val button: String?)

    private fun heroInfo(): HeroInfo? {
        val cur = current()
        return when {
            cur is Item.Card -> HeroInfo(cur.s.pkg, if (cur.row == "season") "Temporada en Crunchyroll" else if (cur.s.isNew) "Episodio nuevo" else "Seguir viendo",
                cur.s.title, listOfNotNull(cur.s.sub.ifBlank { null }, cur.s.minutesLeft.takeIf { it > 0 }?.let { "quedan $it min" }).joinToString(" · "),
                if (cur.row == "season") cur.s.sub else "",
                if (cur.row == "season") "OK para ver la serie" else if (cur.s.playheadSec > 60) "OK para seguir · min ${cur.s.playheadSec / 60}:${"%02d".format(cur.s.playheadSec % 60)}" else "OK para ver")
            cur is Item.Pick -> HeroInfo(cur.t.pkgs.firstOrNull(), "Mi lista", cur.t.name,
                listOf(cur.t.kind, cur.t.year, cur.t.ratingText).filter { it.isNotEmpty() }.joinToString(" · "), cur.t.overview,
                if (cur.t.pkgs.isEmpty()) "Aún no está en tus apps" else "OK para ver en ${Services.name(cur.t.pkgs.first())}")
            heroShowsTrend() -> trend()?.let { t ->
                HeroInfo(t.pkgs.firstOrNull(), if (t.type == "show") "Episodio nuevo de tu serie" else "Tendencia nº ${trendIx + 1} esta semana", t.name,
                    listOf(t.kind, t.year, t.ratingText).filter { it.isNotEmpty() }.joinToString(" · "), t.overview, null)
            }
            cur is Chip -> HeroInfo(null, if (cur.warn) "Aviso" else "SalaTV", cur.title, "", cur.desc, null)
            cur is Item.App -> HeroInfo(null, cur.e.category, cur.e.label, cur.note ?: "", "",
                if (moveMode) "← → para moverla · OK para terminar" else "OK para abrir · mantén OK para más opciones")
            cur is Item.Fold -> HeroInfo(null, "Carpeta", cur.f.name, if (cur.apps.size == 1) "1 app" else "${cur.apps.size} apps", "", "OK para abrir")
            cur is Item.Hidden -> HeroInfo(null, "Apps ocultas", "${cur.count} ocultas", "Siguen instaladas, solo no se ven aquí", "", "OK para verlas")
            else -> null
        }
    }

    private fun drawHeroText(c: Canvas, compact: Boolean) {
        val info = heroInfo() ?: return
        val x = MARGIN
        var y = if (compact) 98f else 112f
        txt.textAlign = Paint.Align.LEFT; txt.color = pal.fg
        var kx = x
        if (info.pkg != null) kx = badge(c, info.pkg, x, y, 11.5f) + 10f
        txt.typeface = Fonts.medium; txt.textSize = s(12.5f); txt.letterSpacing = 0.14f; txt.color = pal.fg; txt.alpha = 190
        c.drawText(ellipsize(info.kicker.uppercase(Locale("es")), s(520f)), s(kx), s(y), txt)
        txt.alpha = 255; txt.letterSpacing = -0.02f; txt.typeface = Fonts.bold
        var size = if (compact) 34f else 50f * min(1f, dens + 0.08f)
        txt.textSize = s(size)
        while (txt.measureText(info.title) > s(540f) && size > 26f) { size -= 2f; txt.textSize = s(size) }
        // «Logos de los títulos»: the title's own logo when TMDB has one.
        val logoPath = if (!compact && heroShowsTrend()) trend()?.logo else null
        val logo = logoPath?.let { img(tmdb(it, "w500"), 500) }
        if (logo != null) {
            val maxW = 420f; val maxH = 110f
            val k = min(maxW / logo.width * u, maxH / logo.height * u)
            val lw = logo.width * k; val lh = logo.height * k
            rect2.set(s(x), s(y + 10f), s(x) + lw, s(y + 10f) + lh)
            glow.color = Color.WHITE; glow.setShadowLayer(s(10f), 0f, s(2f), if (pal.day) 0x99000000.toInt() else 0x66000000)
            c.drawBitmap(logo, null, rect2, glow)
            glow.clearShadowLayer()
            y += lh / u + 10f
        } else {
            y += size * 0.95f + 8f
            c.drawText(ellipsize(info.title, s(540f)), s(x - 2f), s(y), txt)
        }
        txt.letterSpacing = 0f
        if (info.meta.isNotEmpty()) {
            y += 26f
            txt.typeface = Fonts.regular; txt.textSize = s(15f); txt.alpha = 200
            c.drawText(ellipsize(info.meta, s(540f)), s(x), s(y), txt)
        }
        if (!compact && info.body.isNotEmpty()) {
            txt.typeface = Fonts.light; txt.textSize = s(15f); txt.alpha = 215
            for (l in wrap(info.body, s(500f), 2)) { y += 22f; c.drawText(l, s(x), s(y), txt) }
        }
        txt.alpha = 255
        if (compact) return
        y += 22f
        if (heroShowsTrend()) {
            val t = trend() ?: return
            val onHero = line()?.kind == Kind.HERO
            val acc = itemColor(null)
            val labels = listOf("▶  Ver en " + (t.pkgs.firstOrNull()?.let { Services.name(it) } ?: "su app"), when { t.type == "show" -> "ⓘ  Ver la serie"; !canList(t) -> "ⓘ  Ficha"; t.key in mine || mine.contains(t.name.lowercase()) -> "✓  En mi lista"; else -> "+  Mi lista" })
            var bx = x
            labels.forEachIndexed { i, l ->
                txt.typeface = Fonts.medium; txt.textSize = s(15f)
                val bw = txt.measureText(l) / u + 36f
                rect.set(s(bx), s(y), s(bx + bw), s(y + 38f))
                val f = onHero && col == i
                if (f) {
                    glow.color = Ui.alpha(acc, 0.35f); glow.setShadowLayer(s(18f), 0f, 0f, Ui.alpha(acc, 0.8f))
                    c.drawRoundRect(rect, rect.height() / 2, rect.height() / 2, glow)
                    stroke.color = Ui.alpha(Color.WHITE, 0.85f); stroke.strokeWidth = s(1.5f)
                    c.drawRoundRect(rect, rect.height() / 2, rect.height() / 2, stroke)
                } else {
                    fill.color = if (pal.day) 0x22000000 else 0x24FFFFFF
                    c.drawRoundRect(rect, rect.height() / 2, rect.height() / 2, fill)
                }
                txt.color = pal.fg
                c.drawText(l, s(bx + 18f), s(y + 25f), txt)
                bx += bw + 12f
            }
            // Dots: one per trend, the current one longer.
            var dx = x
            val dy = y + 56f
            for (i in trends.indices) {
                val wd = if (i == trendIx) 22f else 7f
                rect.set(s(dx), s(dy), s(dx + wd), s(dy + 7f))
                fill.color = Ui.alpha(pal.fg, if (i == trendIx) 0.95f else 0.35f)
                c.drawRoundRect(rect, s(3.5f), s(3.5f), fill)
                dx += wd + 6f
            }
        } else if (info.button != null) {
            txt.typeface = Fonts.regular; txt.textSize = s(14f)
            val tw = txt.measureText(info.button) / u
            rect.set(s(x), s(y), s(x + tw + 28f), s(y + 32f))
            fill.color = pal.chip
            c.drawRoundRect(rect, rect.height() / 2, rect.height() / 2, fill)
            txt.color = pal.fg
            c.drawText(info.button, s(x + 14f), s(y + 21f), txt)
        }
    }

    /** «Halo del color de la app»: a soft glow of [color] and a thin light ring, never a white fill. */
    private fun halo(c: Canvas, r: RectF, radius: Float, color: Int, sc: Float) {
        glow.color = Ui.alpha(color, 0.9f)
        glow.setShadowLayer(s(26f), 0f, 0f, Ui.alpha(color, 0.85f))
        c.drawRoundRect(r, radius, radius, glow)
        glow.clearShadowLayer()
    }

    private fun ring(c: Canvas, r: RectF, radius: Float, sc: Float) {
        stroke.color = Ui.alpha(if (pal.day) Color.BLACK else Color.WHITE, 0.9f); stroke.strokeWidth = s(2f) / sc
        c.drawRoundRect(r, radius, radius, stroke)
    }

    private fun drawDock(c: Canvas, i: Int, ln: Line): Boolean {
        rect.set(s(24f), s(heroBottom()), s(936f), s(shelfBottom()))
        fill.color = pal.glass
        c.drawRoundRect(rect, s(26f), s(26f), fill)
        stroke.color = pal.glassLine; stroke.strokeWidth = s(1f)
        c.drawRoundRect(rect, s(26f), s(26f), stroke)
        var busy = false
        val tw = tileW(); val th = tileH()
        for ((k, it) in ln.items.withIndex()) {
            busy = tile(c, it as Item, MARGIN + k * (tw + 18f * dens), dockY() + (76f * dens - th) / 2, tw, th, li == i && col == k, 1.1f, false) or busy
        }
        return busy
    }

    private fun drawCards(c: Canvas, i: Int, ln: Line): Boolean {
        txt.typeface = Fonts.medium; txt.textSize = s(15f * dens); txt.color = pal.fg; txt.alpha = 190; txt.letterSpacing = 0f
        c.drawText(ln.label ?: "", s(MARGIN), s(ln.y + 16f * dens), txt)
        txt.alpha = 255
        val off = rowOff.getOrPut(ln.label ?: "") { Anim(0f) }.v
        var busy = false
        for ((k, it) in ln.items.withIndex()) {
            val x = MARGIN + k * cardStep() - off
            if (x + cardW() < 0f || x > 960f) continue
            busy = card(c, it as Item, x, ln.y + 26f * dens, li == i && col == k) or busy
        }
        return busy
    }

    private fun cardImage(item: Item): Bitmap? = when (item) {
        is Item.Card -> img(item.s.image, 420)
        is Item.Pick -> img(tmdb(item.t.backdrop, "w300"), 300) ?: img(tmdb(item.t.poster, "w342"), 300)
        else -> null
    }

    /** A 16:9 card: picture, service, title, episode, progress and «Nuevo». */
    private fun card(c: Canvas, item: Item, x: Float, y: Float, focused: Boolean): Boolean {
        val a = scales.getOrPut(item.key) { Spring(1f) }
        a.t = if (focused) 1.07f else 1f
        val busy = a.step(frameDt)
        val X = s(x); val Y = s(y); val W = s(cardW()); val H = s(cardH())
        val r = s(11f)
        c.save()
        c.scale(a.v, a.v, X + W / 2, Y + H / 2)
        rect.set(X, Y, X + W, Y + H)
        val color = itemColor(item)
        if (focused) halo(c, rect, r, color, a.v)
        c.save()
        fill.color = Ui.shade(color, 0.3f); c.drawRoundRect(rect, r, r, fill)
        val pic = cardImage(item)
        if (pic == null) shimmer(c, rect, r)
        // Rounded picture through a shader: clipping to a path is slow on the TV's GPU.
        pic?.let { b ->
            val scale = max(rect.width() / b.width, rect.height() / b.height)
            m.setScale(scale, scale)
            m.postTranslate(rect.left + (rect.width() - b.width * scale) / 2, rect.top + (rect.height() - b.height * scale) / 2)
            val sh = BitmapShader(b, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
            sh.setLocalMatrix(m)
            fill.color = Color.BLACK; fill.shader = sh
            c.drawRoundRect(rect, r, r, fill); fill.shader = null
        }
        fill.color = Color.BLACK
        fill.shader = LinearGradient(0f, rect.bottom, 0f, rect.top + rect.height() * .25f, 0xE6000000.toInt(), 0, Shader.TileMode.CLAMP)
        c.drawRoundRect(rect, r, r, fill); fill.shader = null
        val (title, sub, prog, isNew, pkg) = when (item) {
            is Item.Card -> Quint(item.s.title, if (item.row == "season") "" else item.s.sub, item.s.progress, item.s.isNew, item.s.pkg)
            is Item.Pick -> Quint(item.t.name, listOf(item.t.kind, item.t.ratingText).filter { it.isNotEmpty() }.joinToString(" · "), 0f, false, item.t.pkgs.firstOrNull())
            else -> Quint("", "", 0f, false, null)
        }
        if (pkg != null) badge(c, pkg, x + 8f, y + 20f, 10f)
        if (isNew) {
            txt.typeface = Fonts.bold; txt.textSize = s(10.5f)
            val tw = txt.measureText("Nuevo")
            rect2.set(rect.right - tw - s(18f), rect.top + s(8f), rect.right - s(8f), rect.top + s(24f))
            fill.color = Ui.LAMP; c.drawRoundRect(rect2, s(4f), s(4f), fill)
            txt.color = Ui.INK; c.drawText("Nuevo", rect2.left + s(5f), rect2.bottom - s(4.5f), txt)
        }
        txt.color = Color.WHITE
        txt.typeface = Fonts.bold; txt.textSize = s(14f * dens)
        c.drawText(ellipsize(title, W - s(18f)), rect.left + s(9f), rect.bottom - s(if (sub.isNotEmpty()) 24f else 12f) * dens, txt)
        if (sub.isNotEmpty()) {
            txt.typeface = Fonts.regular; txt.textSize = s(11f * dens); txt.alpha = 215
            c.drawText(ellipsize(sub, W - s(18f)), rect.left + s(9f), rect.bottom - s(10f) * dens, txt)
            txt.alpha = 255
        }
        if (prog > 0f) {
            val bl = rect.left + s(8f); val br = rect.right - s(8f); val by = rect.bottom - s(6f)
            rect2.set(bl, by - s(3.5f), br, by)
            fill.color = 0x40FFFFFF; c.drawRoundRect(rect2, s(2f), s(2f), fill)
            rect2.right = bl + (br - bl) * prog
            fill.color = Ui.LAMP; c.drawRoundRect(rect2, s(2f), s(2f), fill)
        }
        c.restore()
        if (focused) ring(c, rect, r, a.v)
        c.restore()
        val meta = when (item) {
            is Item.Card -> if (item.row == "season") "" else listOfNotNull(if (item.s.at > 0) UsageRank.ago(item.s.at) else null,
                item.s.minutesLeft.takeIf { it > 0 }?.let { "quedan $it min" }).joinToString(" · ")
            is Item.Pick -> if (item.t.pkgs.isEmpty()) "Aún no está en tus apps" else "En " + item.t.pkgs.joinToString(", ") { Services.name(it) }
            else -> ""
        }
        if (meta.isNotEmpty()) {
            txt.typeface = Fonts.regular; txt.textSize = s(11.5f * dens); txt.color = pal.fg; txt.alpha = if (focused) 220 else 140
            c.drawText(ellipsize(meta, W), X, Y + H + H * (a.v - 1f) / 2 + s(17f * dens), txt)
            txt.alpha = 255
        }
        return busy
    }

    /** «Carga con brillo»: a light band sweeps across a card until its picture arrives. */
    private fun shimmer(c: Canvas, r: RectF, radius: Float) {
        val phase = (SystemClock.uptimeMillis() % 1400L) / 1400f
        val x0 = r.left - r.width() + r.width() * 3f * phase
        fill.color = Color.BLACK
        fill.shader = LinearGradient(x0, 0f, x0 + r.width(), 0f, intArrayOf(0x00FFFFFF, 0x26FFFFFF, 0x00FFFFFF), null, Shader.TileMode.CLAMP)
        c.drawRoundRect(r, radius, radius, fill)
        fill.shader = null
        postInvalidateDelayed(40)
    }

    private data class Quint(val a: String, val b: String, val c: Float, val d: Boolean, val e: String?)

    private fun drawGrid(c: Canvas, i: Int, ln: Line): Boolean {
        if (ln.label != null) {
            txt.typeface = Fonts.medium; txt.textSize = s(15f * dens); txt.color = pal.fg; txt.alpha = 190
            c.drawText(ln.label, s(MARGIN), s(ln.y - 14f * dens), txt)
            txt.alpha = 255
        }
        if (ln.y + gH() < scroll.v - 40f || ln.y > scroll.v + 560f) return false
        var busy = false
        for ((k, it) in ln.items.withIndex()) {
            busy = tile(c, it as Item, MARGIN + k * gStep(), ln.y, gW(), gH(), li == i && col == k, 1.1f, it !is Item.App) or busy
        }
        return busy
    }

    /** One tile; returns true while its focus animation is still running. */
    private fun tile(c: Canvas, item: Item, x: Float, y: Float, w: Float, h: Float, focused: Boolean, big: Float, alwaysName: Boolean): Boolean {
        val a = scales.getOrPut(item.key) { Spring(1f) }
        a.t = if (focused) big else 1f
        val busy = a.step(frameDt)
        val sc = a.v
        val X = s(x); val Y = s(y); val W = s(w); val H = s(h)
        val cx = X + W / 2; val cy = Y + H / 2
        val r = H * 0.16f
        c.save()
        c.scale(sc, sc, cx, cy)
        rect.set(X, Y, X + W, Y + H)
        // Only the focused tile gets a blurred halo; a blur per tile cost frames on the TV.
        if (focused) halo(c, rect, r, itemColor(item), sc)
        when (item) {
            is Item.App -> appFace(c, item.e, r)
            is Item.Fold -> folderFace(c, item, r)
            is Item.Hidden -> hiddenFace(c, item, r)
            else -> {}
        }
        if (focused) {
            ring(c, rect, r, sc)
            if (moveMode) {
                stroke.color = Ui.LAMP; stroke.strokeWidth = s(3f)
                val ay = rect.centerY(); val d = s(9f)
                c.drawLine(rect.left - s(10f), ay - d, rect.left - s(10f) - d, ay, stroke)
                c.drawLine(rect.left - s(10f) - d, ay, rect.left - s(10f), ay + d, stroke)
                c.drawLine(rect.right + s(10f), ay - d, rect.right + s(10f) + d, ay, stroke)
                c.drawLine(rect.right + s(10f) + d, ay, rect.right + s(10f), ay + d, stroke)
            }
        }
        c.restore()
        if (focused || alwaysName) {
            val name = when (item) { is Item.App -> item.e.label; is Item.Fold -> item.f.name; is Item.Hidden -> "Apps ocultas"; else -> "" }
            txt.typeface = Fonts.medium; txt.textSize = s(12.5f * dens); txt.color = pal.fg; txt.alpha = if (focused) 255 else 170
            txt.textAlign = Paint.Align.CENTER
            c.drawText(ellipsize(name, W * 1.25f), cx, Y + H + H * (sc - 1f) / 2 + s(18f * dens), txt)
            txt.textAlign = Paint.Align.LEFT; txt.alpha = 255
        }
        return busy
    }

    private fun appFace(c: Canvas, e: AppEntry, r: Float) {
        val b = e.banner
        if (b != null) {
            val sh = e.shader ?: BitmapShader(b, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP).also { e.shader = it }
            val scale = max(rect.width() / b.width, rect.height() / b.height)
            m.setScale(scale, scale)
            m.postTranslate(rect.left + (rect.width() - b.width * scale) / 2, rect.top + (rect.height() - b.height * scale) / 2)
            sh.setLocalMatrix(m)
            fill.color = Color.BLACK
            fill.shader = sh
            c.drawRoundRect(rect, r, r, fill)
            fill.shader = null
            return
        }
        fill.color = e.tile
        c.drawRoundRect(rect, r, r, fill)
        val ic = e.icon ?: return
        val isz = rect.height() * 0.6f
        rect2.set(rect.centerX() - isz / 2, rect.centerY() - isz / 2, rect.centerX() + isz / 2, rect.centerY() + isz / 2)
        c.drawBitmap(ic, null, rect2, bmp)
    }

    private fun folderFace(c: Canvas, f: Item.Fold, r: Float) {
        fill.color = if (pal.day) 0x1F000000 else 0x2EFFFFFF
        c.drawRoundRect(rect, r, r, fill)
        val pad = rect.height() * 0.1f
        val cw = (rect.width() - pad * 3) / 2; val ch = (rect.height() - pad * 3) / 2
        val box = RectF(rect)
        for (i in 0 until 4) {
            val e = f.apps.getOrNull(i) ?: break
            val x = box.left + pad + (i % 2) * (cw + pad); val y = box.top + pad + (i / 2) * (ch + pad)
            rect.set(x, y, x + cw, y + ch)
            val art = e.banner
            if (art != null) crop(c, art, rect, bmp) else { fill.color = e.tile; c.drawRoundRect(rect, s(3f), s(3f), fill) }
        }
        rect.set(box)
    }

    private fun hiddenFace(c: Canvas, item: Item.Hidden, r: Float) {
        fill.color = if (pal.day) 0x14000000 else 0x1AFFFFFF
        c.drawRoundRect(rect, r, r, fill)
        txt.textAlign = Paint.Align.CENTER; txt.color = pal.fg
        txt.typeface = Fonts.bold; txt.textSize = rect.height() * 0.36f
        c.drawText("+${item.count}", rect.centerX(), rect.centerY() + txt.textSize * 0.36f, txt)
        txt.textAlign = Paint.Align.LEFT
    }

    private fun drawZoom(c: Canvas, w: Float, h: Float) {
        val e = 1f - (1f - zoom.v) * (1f - zoom.v)
        rect.set(s(zoomFrom.left) * (1 - e), s(zoomFrom.top) * (1 - e), s(zoomFrom.right) + (w - s(zoomFrom.right)) * e, s(zoomFrom.bottom) + (h - s(zoomFrom.bottom)) * e)
        val r = s(12f) * (1 - e)
        fill.color = zoomColor
        c.drawRoundRect(rect, r, r, fill)
        val art = zoomArt ?: return
        c.save()
        path.reset(); path.addRoundRect(rect, r, r, Path.Direction.CW)
        c.clipPath(path)
        crop(c, art, rect, bmp)
        c.restore()
    }

    private fun greeting(): String {
        val h = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        val g = when (h) { in 5..11 -> "Buenos días"; in 12..19 -> "Buenas tardes"; else -> "Buenas noches" }
        return if (greetName != null) "$g, $greetName" else g
    }

    private fun drawTop(c: Canvas): Boolean {
        var busy = false
        val now = Date()
        txt.textAlign = Paint.Align.LEFT; txt.color = pal.fg; txt.letterSpacing = 0f
        txt.typeface = Fonts.medium; txt.textSize = s(26f)
        val clock = clockFmt.format(now)
        c.drawText(clock, s(46f), s(54f), txt)
        val cw = txt.measureText(clock)
        txt.typeface = Fonts.light; txt.textSize = s(14f); txt.alpha = 185
        c.drawText(greeting() + " · " + dateFmt.format(now), s(46f) + cw + s(12f), s(52f), txt)
        txt.alpha = 255
        if (chipCx.size != chips.size) chipCx = FloatArray(chips.size)
        var right = s(914f)
        val chipLine = lines.indexOfFirst { it.kind == Kind.CHIPS }
        for (i in chips.indices.reversed()) {
            val ch = chips[i]
            val focused = li == chipLine && col == i
            txt.typeface = Fonts.medium; txt.textSize = s(13f)
            val wide = ch.label.isNotEmpty() && ch.icon != AVATAR
            val w = if (wide) s(13f) + (if (ch.icon > 0) s(24f) else 0f) + txt.measureText(ch.label) + s(14f) else s(34f)
            rect.set(right - w, s(28f), right, s(62f))
            chipCx[i] = rect.centerX() / u
            val a = scales.getOrPut("c:" + ch.id) { Spring(1f) }
            a.t = if (focused) 1.06f else 1f
            busy = a.step(frameDt) or busy
            c.save()
            c.scale(a.v, a.v, rect.centerX(), rect.centerY())
            val rad = rect.height() / 2
            if (ch.icon == AVATAR) {
                if (focused) halo(c, rect, rad, ch.color, a.v)
                fill.color = ch.color
                c.drawCircle(rect.centerX(), rect.centerY(), rad, fill)
                if (focused) ring(c, rect, rad, a.v)
                txt.textAlign = Paint.Align.CENTER; txt.typeface = Fonts.bold; txt.textSize = s(15f); txt.color = Ui.INK
                c.drawText(ch.label, rect.centerX(), rect.centerY() + s(5.5f), txt)
                txt.textAlign = Paint.Align.LEFT
            } else {
                val acc = if (ch.warn) Ui.WARN else Ui.LAMP
                if (focused) {
                    halo(c, rect, rad, acc, a.v)
                    fill.color = Ui.alpha(acc, 0.35f); c.drawRoundRect(rect, rad, rad, fill)
                    ring(c, rect, rad, a.v)
                } else {
                    fill.color = if (ch.warn) Ui.alpha(Ui.WARN, if (pal.day) .55f else .25f) else pal.chip
                    c.drawRoundRect(rect, rad, rad, fill)
                }
                val fg = if (ch.warn && !pal.day && !focused) Ui.WARN else pal.fg
                if (wide) {
                    var tx = rect.left + s(13f)
                    if (ch.icon > 0) { icon(c, ch.icon, tx + s(8f), rect.centerY(), s(16f), fg); tx += s(24f) }
                    txt.color = fg; txt.typeface = Fonts.medium; txt.textSize = s(13f)
                    c.drawText(ch.label, tx, rect.centerY() + s(4.5f), txt)
                } else icon(c, ch.icon, rect.centerX(), rect.centerY(), s(16f), fg)
            }
            c.restore()
            right -= w + s(8f)
        }
        return busy
    }

    private fun drawHints(c: Canvas, w: Float) {
        if (!moveMode) return
        txt.textAlign = Paint.Align.CENTER; txt.typeface = Fonts.regular; txt.textSize = s(13f)
        val t = "← →  mover      OK  terminar"
        val tw = txt.measureText(t)
        rect.set(w / 2 - tw / 2 - s(18f), s(500f), w / 2 + tw / 2 + s(18f), s(530f))
        fill.color = Ui.LAMP
        c.drawRoundRect(rect, rect.height() / 2, rect.height() / 2, fill)
        txt.color = Ui.INK
        c.drawText(t, w / 2, s(520f), txt)
        txt.textAlign = Paint.Align.LEFT
    }

    private fun drawNotice(c: Canvas, w: Float, now: Long) {
        val t = noticeText ?: return
        if (now > noticeUntil) { noticeText = null; return }
        txt.typeface = Fonts.medium; txt.textSize = s(14f); txt.textAlign = Paint.Align.CENTER
        val tw = txt.measureText(t)
        rect.set(w / 2 - tw / 2 - s(18f), s(74f), w / 2 + tw / 2 + s(18f), s(104f))
        fill.color = if (pal.day) 0xF2FFFFFF.toInt() else 0xF21C212C.toInt()
        c.drawRoundRect(rect, rect.height() / 2, rect.height() / 2, fill)
        stroke.color = Ui.LAMP; stroke.strokeWidth = s(1.5f)
        c.drawRoundRect(rect, rect.height() / 2, rect.height() / 2, stroke)
        txt.color = pal.fg
        c.drawText(t, w / 2, s(94f), txt)
        txt.textAlign = Paint.Align.LEFT
        postInvalidateDelayed(500)
    }

    private fun icon(c: Canvas, type: Int, cx: Float, cy: Float, size: Float, color: Int) {
        val k = size / 24f
        val ox = cx - 12f * k; val oy = cy - 12f * k
        fun x(v: Float) = ox + v * k
        fun y(v: Float) = oy + v * k
        stroke.color = color; stroke.strokeWidth = 1.9f * k
        fill.color = color
        when (type) {
            SEARCH -> { c.drawCircle(x(11f), y(11f), 6.5f * k, stroke); c.drawLine(x(16f), y(16f), x(21f), y(21f), stroke) }
            INPUT -> {
                rect2.set(x(3f), y(5f), x(21f), y(17f)); c.drawRoundRect(rect2, 2 * k, 2 * k, stroke)
                c.drawLine(x(8f), y(21f), x(16f), y(21f), stroke); c.drawLine(x(12f), y(17f), x(12f), y(21f), stroke)
            }
            WIFI, NOWIFI -> {
                for (rr in floatArrayOf(4.5f, 9f, 13.5f)) {
                    rect2.set(x(12f - rr), y(20f - rr), x(12f + rr), y(20f + rr))
                    c.drawArc(rect2, -135f, 90f, false, stroke)
                }
                c.drawCircle(x(12f), y(19.6f), 1.4f * k, fill)
                if (type == NOWIFI) c.drawLine(x(4f), y(4f), x(20f), y(20f), stroke)
            }
            GEAR -> {
                // A real cog: eight square teeth around a ring, with the hole in the middle.
                path.reset()
                val teeth = 8
                for (i in 0 until teeth * 2) {
                    val a0 = (i * Math.PI / teeth) - Math.PI / (teeth * 2)
                    val a1 = a0 + Math.PI / teeth
                    val r = if (i % 2 == 0) 9.8f else 7.4f
                    val px0 = x(12f + cos(a0).toFloat() * r); val py0 = y(12f + sin(a0).toFloat() * r)
                    if (i == 0) path.moveTo(px0, py0) else path.lineTo(px0, py0)
                    path.lineTo(x(12f + cos(a1).toFloat() * r), y(12f + sin(a1).toFloat() * r))
                }
                path.close()
                c.drawPath(path, stroke)
                c.drawCircle(x(12f), y(12f), 3.2f * k, stroke)
            }
            COMPASS -> {
                c.drawCircle(x(12f), y(12f), 9.5f * k, stroke)
                path.reset(); path.moveTo(x(15.8f), y(8.2f)); path.lineTo(x(13.4f), y(13.4f)); path.lineTo(x(8.2f), y(15.8f)); path.lineTo(x(10.6f), y(10.6f)); path.close()
                c.drawPath(path, fill)
            }
            WARN -> {
                path.reset(); path.moveTo(x(12f), y(3.5f)); path.lineTo(x(21.5f), y(20f)); path.lineTo(x(2.5f), y(20f)); path.close()
                c.drawPath(path, stroke)
                c.drawLine(x(12f), y(9.5f), x(12f), y(14f), stroke)
                c.drawCircle(x(12f), y(17f), 1.2f * k, fill)
            }
        }
    }

    private fun ellipsize(s: String, max: Float): String {
        if (txt.measureText(s) <= max) return s
        val n = txt.breakText(s, true, max - txt.measureText("…"), null)
        return s.substring(0, n.coerceAtLeast(0)).trimEnd() + "…"
    }

    fun snapshot(): Bitmap? {
        if (width == 0 || chips.isEmpty() || pending != null || zoom.v > 0f) return null
        val b = Bitmap.createBitmap(width / 2, height / 2, Bitmap.Config.RGB_565)
        val c = Canvas(b)
        c.scale(0.5f, 0.5f)
        draw(c)
        return b
    }
}
