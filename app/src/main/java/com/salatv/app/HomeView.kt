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
 * grid (the TV's size in dp) and scaled by [u]. Rows: -1 the chips at the top, 0 the dock on the glass
 * shelf, 1 «Continuar viendo» when there is something in it, then the grid of every other app.
 */
class HomeView(context: Context) : View(context) {

    interface Host {
        fun onOpen(item: Item)
        fun onChip(chip: Chip)
        fun onMenu(item: Item, inDock: Boolean)
        fun onDockMoved(pkgs: List<String>)
        fun onMove()
    }

    sealed class Item(val key: String) {
        class App(val e: AppEntry, val note: String?) : Item("a:" + e.pkg)
        class Fold(val f: Folder, val apps: List<AppEntry>) : Item("f:" + f.id)
        class Hidden(val count: Int) : Item("hidden")
        /** Something watched, from the media sessions. */
        class Media(val w: Watched, val app: AppEntry?, val art: Bitmap?) : Item("m:" + w.key)
        /** An app opened lately with nothing known about what was in it. */
        class Recent(val e: AppEntry, val at: Long) : Item("r:" + e.pkg)
    }

    class Chip(val id: String, val label: String, val icon: Int, val title: String, val desc: String, val warn: Boolean = false, val color: Int = 0)

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

    private class Hero(val label: String, val title: String, val desc: String, val hint: String?)

    companion object {
        const val SEARCH = 1; const val INPUT = 2; const val WIFI = 3; const val GEAR = 4
        const val AVATAR = 5; const val WARN = 6; const val NOWIFI = 7
        const val COLS = 7
        private const val DOCK_Y = 394f
        private const val CONT_Y = 590f
        private const val CONT_W = 196f
        private const val CONT_H = 110f
        private const val CONT_STEP = 214f
        private const val GRID_X = 54f
        private const val GRID_W = 108f
        private const val GRID_H = 61f
        private const val GRID_STEP_X = 124f
        private const val GRID_PITCH = 100f
    }

    var host: Host? = null
    /** Last frame of the previous session, shown until the apps are loaded («Arranque al instante»). */
    var placeholder: Bitmap? = null
    private var chips: List<Chip> = emptyList()
    private val dock = mutableListOf<Item>()
    private var cont: List<Item> = emptyList()
    private var grid: List<Item> = emptyList()
    private var gridLabel = "Todas tus apps"
    private var greetName: String? = null
    private var row = 0
    private var col = 0
    private var focusKey: String? = null
    var moveMode = false
        private set
    private var fast = false

    private var u = 1f
    private var pal = Pal(false)
    private var lastFrame = 0L
    private var frameK = 0.2f
    private val scroll = Anim(0f)
    private val contOff = Anim(0f)
    private val fade = Anim(1f)
    private val mix = Anim(1f)
    private val zoom = Anim(0f)
    private val scales = HashMap<String, Anim>()
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
    private val shadow = Paint(Paint.ANTI_ALIAS_FLAG)
    private val txt = Paint(Paint.ANTI_ALIAS_FLAG)
    private val mask = Paint(Paint.ANTI_ALIAS_FLAG).apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN) }
    private val shade = Paint()
    private val darken = LightingColorFilter(0xFF8C8C8C.toInt(), 0)
    private val rect = RectF()
    private val rect2 = RectF()
    private val src = Rect()
    private val m = Matrix()
    private val path = Path()
    private var leftShade: Shader? = null
    private var bottomShade: Shader? = null
    private var shadeFor = 0
    private val clockFmt = SimpleDateFormat("HH:mm", Locale.getDefault())
    private val dateFmt = SimpleDateFormat("EEEE d 'de' MMMM", Locale("es"))

    private val tick = object : Runnable {
        override fun run() {
            pal = Pal(Look.isDay(context))
            invalidate()
            postDelayed(this, 20_000)
        }
    }

    override fun onAttachedToWindow() { super.onAttachedToWindow(); post(tick) }
    override fun onDetachedFromWindow() { removeCallbacks(tick); super.onDetachedFromWindow() }
    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) { u = w / 960f; shadeFor = 0 }

    private fun s(v: Float) = v * u

    // ---------------------------------------------------------------- data and focus

    fun setData(newChips: List<Chip>, newDock: List<Item>, newCont: List<Item>, newGrid: List<Item>, name: String?, label: String) {
        chips = newChips
        dock.clear(); dock.addAll(newDock)
        cont = newCont
        grid = newGrid
        greetName = name
        gridLabel = label
        placeholder = null
        pal = Pal(Look.isDay(context))
        val keep = focusKey
        var found = false
        if (keep != null) {
            chips.indexOfFirst { "c:" + it.id == keep }.takeIf { it >= 0 }?.let { row = -1; col = it; found = true }
            if (!found) dock.indexOfFirst { it.key == keep }.takeIf { it >= 0 }?.let { row = 0; col = it; found = true }
            if (!found) cont.indexOfFirst { it.key == keep }.takeIf { it >= 0 }?.let { row = 1; col = it; found = true }
            if (!found) grid.indexOfFirst { it.key == keep }.takeIf { it >= 0 }?.let { row = gridStart() + it / COLS; col = it % COLS; found = true }
        }
        if (!found) clamp()
        focusChanged(bgCur != null)
    }

    private fun hasCont() = cont.isNotEmpty()
    private fun gridStart() = if (hasCont()) 2 else 1
    private fun isCont(r: Int) = hasCont() && r == 1
    private fun gridRows() = (grid.size + COLS - 1) / COLS
    private fun gridY0() = if (hasCont()) 812f else 604f

    private fun rowSize(r: Int) = when {
        r == -1 -> chips.size
        r == 0 -> dock.size
        isCont(r) -> cont.size
        r >= gridStart() -> min(COLS, grid.size - (r - gridStart()) * COLS).coerceAtLeast(0)
        else -> 0
    }

    private fun rowY(r: Int) = when {
        r <= 0 -> 0f
        isCont(r) -> CONT_Y
        else -> gridY0() + (r - gridStart()) * GRID_PITCH
    }

    private fun clamp() {
        if (row == -1 && chips.isEmpty()) row = 0
        if (row == 0 && dock.isEmpty()) row = if (hasCont() || grid.isNotEmpty()) 1 else -1
        if (row >= 1 && !isCont(row)) {
            row = if (gridRows() == 0) (if (hasCont()) 1 else if (dock.isNotEmpty()) 0 else -1)
            else row.coerceIn(gridStart(), gridStart() + gridRows() - 1)
        }
        col = col.coerceIn(0, max(0, rowSize(row) - 1))
    }

    private fun current(): Any? = when {
        row == -1 -> chips.getOrNull(col)
        row == 0 -> dock.getOrNull(col)
        isCont(row) -> cont.getOrNull(col)
        else -> grid.getOrNull((row - gridStart()) * COLS + col)
    }

    private fun mediaArt(item: Item): Bitmap? = when (item) {
        is Item.Media -> item.art ?: item.app?.banner
        is Item.Recent -> item.e.banner
        is Item.App -> item.e.banner ?: item.e.icon
        is Item.Fold -> item.apps.firstOrNull()?.let { it.banner ?: it.icon }
        else -> null
    }

    private fun itemColor(item: Item): Int = when (item) {
        is Item.App -> item.e.color
        is Item.Media -> item.app?.color ?: Ui.LAMP
        is Item.Recent -> item.e.color
        is Item.Fold -> item.apps.firstOrNull()?.color ?: Ui.LAMP
        else -> 0xFF8FA2C8.toInt()
    }

    private fun focusChanged(animate: Boolean) {
        val cur = current()
        focusKey = when (cur) { is Chip -> "c:" + cur.id; is Item -> cur.key; else -> null }
        val color: Int
        val bg: Bitmap = when (cur) {
            is Item.App -> { color = cur.e.color; AppRepo.blurred(cur.e) }
            is Item.Media -> {
                color = itemColor(cur)
                when {
                    cur.art != null -> AppRepo.blurredArt(cur.key, cur.art)
                    cur.app != null -> AppRepo.blurred(cur.app)
                    else -> AppRepo.plain(color)
                }
            }
            is Item.Recent -> { color = cur.e.color; AppRepo.blurred(cur.e) }
            is Item.Fold -> {
                val first = cur.apps.firstOrNull()
                color = first?.color ?: Ui.LAMP
                if (first != null) AppRepo.blurred(first) else AppRepo.plain(Ui.LAMP)
            }
            is Chip -> { color = if (cur.warn) Ui.WARN else if (cur.color != 0) cur.color else Ui.LAMP; AppRepo.plain(color) }
            else -> { color = 0xFF8FA2C8.toInt(); AppRepo.plain(color) }
        }
        if (bg !== bgCur) {
            bgPrev = bgCur; bgCur = bg
            if (animate) { fade.v = 0f; fade.t = 1f } else fade.snap(1f)
        }
        if (color != colorCur) {
            colorPrev = Ui.lerp(colorPrev, colorCur, mix.v); colorCur = color
            if (animate) { mix.v = 0f; mix.t = 1f } else mix.snap(1f)
        }
        scroll.t = if (row >= 1) rowY(row) - 300f else 0f
        if (isCont(row)) {
            val maxOff = max(0f, (cont.size - 4) * CONT_STEP)
            contOff.t = ((col - 3) * CONT_STEP).coerceIn(0f, maxOff)
        }
        if (!animate) { scroll.snap(scroll.t); contOff.snap(contOff.t) }
        invalidate()
    }

    private fun dockTileW(): Float {
        val n = dock.size
        return if (n == 0) 136f else min(136f, (864f - (n - 1) * 18f) / n)
    }

    private fun centerX(r: Int, c: Int): Float = when {
        r == -1 -> chipCx.getOrElse(c) { 800f }
        r == 0 -> 48f + c * (dockTileW() + 18f) + dockTileW() / 2
        isCont(r) -> 48f + c * CONT_STEP + CONT_W / 2 - contOff.t
        else -> GRID_X + c * GRID_STEP_X + GRID_W / 2
    }

    private fun nearest(r: Int, x: Float): Int {
        var best = 0; var d = Float.MAX_VALUE
        for (i in 0 until rowSize(r)) {
            val dd = abs(centerX(r, i) - x)
            if (dd < d) { d = dd; best = i }
        }
        return best
    }

    /** 0 left, 1 right, 2 up, 3 down. [repeat] is the key's repeat count: held arrows speed up. */
    fun key(dir: Int, repeat: Int = 0): Boolean {
        fast = repeat > 0
        if (moveMode) {
            if (dir == 0 || dir == 1) moveBy(if (dir == 0) -1 else 1)
            return true
        }
        val steps = if (repeat > 12 && dir < 2) 2 else 1
        val r0 = row; val c0 = col
        repeat(steps) { step(dir) }
        if (row != r0 || col != c0) { focusChanged(true); host?.onMove() }
        return true
    }

    private fun step(dir: Int) {
        val x = centerX(row, col)
        when (dir) {
            0 -> if (col > 0) col--
            1 -> if (col < rowSize(row) - 1) col++
            2 -> when {
                row == -1 -> {}
                row == 0 -> if (chips.isNotEmpty()) { row = -1; col = nearest(-1, x) }
                else -> {
                    var r = row - 1
                    while (r > 0 && rowSize(r) == 0) r--
                    if (r == 0 && dock.isEmpty()) r = -1
                    if (rowSize(r) > 0) { row = r; col = nearest(r, x) }
                }
            }
            3 -> {
                var r = row + 1
                while (r <= gridStart() + gridRows() && rowSize(r) == 0 && r < gridStart()) r++
                if (rowSize(r) > 0) { row = r; col = nearest(r, x) }
            }
        }
    }

    fun keyUp() { fast = false }

    /** ⏪ and ⏩: first or last item of the row. */
    fun jumpEdge(end: Boolean) {
        if (moveMode) return
        val c0 = col
        col = if (end) max(0, rowSize(row) - 1) else 0
        if (col != c0) { focusChanged(true); host?.onMove() }
    }

    private fun moveBy(d: Int) {
        val j = col + d
        if (j < 0 || j >= dock.size) return
        val a = dock[col]; dock[col] = dock[j]; dock[j] = a
        col = j
        host?.onMove()
        invalidate()
    }

    fun activate() {
        if (moveMode) { finishMove(); return }
        when (val cur = current()) {
            is Chip -> host?.onChip(cur)
            is Item.App, is Item.Media, is Item.Recent -> startZoom(cur as Item)
            is Item -> host?.onOpen(cur)
        }
    }

    private fun animationsOn() = Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) > 0f

    /** «Abrir con zoom»: the tile grows to fill the screen while the app starts. */
    private fun startZoom(item: Item) {
        if (!animationsOn() || pending != null) { host?.onOpen(item); return }
        val y = rowY(row) - scroll.v
        when {
            row == 0 -> { val tw = dockTileW(); zoomFrom.set(48f + col * (tw + 18f), DOCK_Y, 48f + col * (tw + 18f) + tw, DOCK_Y + tw * 76f / 136f) }
            isCont(row) -> { val x = 48f + col * CONT_STEP - contOff.v; zoomFrom.set(x, y, x + CONT_W, y + CONT_H) }
            else -> { val x = GRID_X + col * GRID_STEP_X; zoomFrom.set(x, y, x + GRID_W, y + GRID_H) }
        }
        zoomColor = when (item) { is Item.App -> item.e.tile; else -> Ui.shade(itemColor(item), 0.4f) }
        zoomArt = mediaArt(item)
        zoom.v = 0f; zoom.t = 1f
        pending = item
        invalidate()
    }

    /** Called when SalaTV comes back to the front, or when opening failed. */
    fun clearZoom() {
        zoom.snap(0f)
        pending = null
        invalidate()
    }

    fun menu() {
        if (moveMode || pending != null) return
        val cur = current()
        if (cur is Item) host?.onMenu(cur, row == 0)
    }

    fun startMove() {
        if (row == 0 && dock.isNotEmpty()) { moveMode = true; invalidate() }
    }

    private fun finishMove() {
        moveMode = false
        host?.onDockMoved(dock.filterIsInstance<Item.App>().map { it.e.pkg })
        invalidate()
    }

    /** Back: leave move mode, then go back to the start of the dock. False when there is nowhere to go. */
    fun back(): Boolean {
        if (moveMode) { finishMove(); return true }
        if (row != 0 || col != 0) {
            row = 0; col = 0; clamp(); focusChanged(true)
            return true
        }
        return false
    }

    fun goHome() {
        moveMode = false
        row = 0; col = 0; clamp(); focusChanged(true)
    }

    // ---------------------------------------------------------------- drawing

    override fun onDraw(c: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        val ph = placeholder
        if (ph != null && chips.isEmpty()) {
            rect.set(0f, 0f, w, h)
            c.drawBitmap(ph, null, rect, bmp)
            return
        }
        val now = SystemClock.uptimeMillis()
        val dt = if (lastFrame == 0L) 16f else min(64f, (now - lastFrame).toFloat())
        lastFrame = now
        frameK = min(1f, (1f - exp(-dt / 95f)) * if (fast) 2.2f else 1f)
        var busy = scroll.step(frameK)
        busy = contOff.step(frameK) or busy
        busy = fade.step(frameK * 0.75f) or busy
        busy = mix.step(frameK * 0.75f) or busy

        c.drawColor(pal.bg)
        drawBg(c, bgPrev, 1f - fade.v)
        drawBg(c, bgCur, fade.v)
        if (pal.day) { fill.color = Ui.alpha(pal.bg, 0.5f); c.drawRect(0f, 0f, w, h, fill) }

        val heroA = (1f - scroll.v / 200f).coerceIn(0f, 1f)
        if (heroA > 0.01f) drawHeroArt(c, heroA)

        if (shadeFor != pal.bg) {
            shadeFor = pal.bg
            leftShade = LinearGradient(0f, 0f, w * 0.66f, 0f, intArrayOf(Ui.alpha(pal.bg, .92f), Ui.alpha(pal.bg, .55f), Ui.alpha(pal.bg, 0f)), floatArrayOf(0f, .45f, 1f), Shader.TileMode.CLAMP)
            bottomShade = LinearGradient(0f, h, 0f, h * .38f, Ui.alpha(pal.bg, .9f), Ui.alpha(pal.bg, 0f), Shader.TileMode.CLAMP)
        }
        shade.shader = leftShade; c.drawRect(0f, 0f, w, h, shade)
        shade.shader = bottomShade; c.drawRect(0f, 0f, w, h, shade)
        val gc = Ui.lerp(colorPrev, colorCur, mix.v)
        shade.shader = RadialGradient(w * .25f, h * 1.05f, w * .55f, Ui.alpha(gc, if (pal.day) .28f else .42f), 0, Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, w, h, shade)
        shade.shader = null

        c.save()
        c.translate(0f, -s(scroll.v))
        drawHeroText(c, heroA)
        drawShelf(c)
        val tw = dockTileW(); val th = tw * 76f / 136f
        for (i in dock.indices) {
            val f = row == 0 && col == i
            busy = tile(c, dock[i], 48f + i * (tw + 18f), DOCK_Y + (76f - th) / 2, tw, th, f, 1.13f, false) or busy
        }
        busy = drawCont(c) or busy
        busy = drawGrid(c) or busy
        c.restore()

        if (scroll.v > 1f) {
            shade.shader = LinearGradient(0f, 0f, 0f, s(130f), Ui.alpha(pal.bg, min(1f, scroll.v / 150f) * .95f), Ui.alpha(pal.bg, 0f), Shader.TileMode.CLAMP)
            c.drawRect(0f, 0f, w, s(130f), shade)
            shade.shader = null
        }
        busy = drawTop(c) or busy
        drawHints(c, w)
        if (zoom.t > 0f) {
            busy = zoom.step(min(1f, frameK * 1.15f)) or busy
            drawZoom(c, w, h)
            if (pending != null && zoom.v > 0.96f) {
                val p = pending
                pending = null
                if (p != null) post { host?.onOpen(p) }
            }
        }
        if (busy) postInvalidateOnAnimation()
    }

    private fun drawZoom(c: Canvas, w: Float, h: Float) {
        val t = zoom.v
        val e = 1f - (1f - t) * (1f - t)
        rect.set(s(zoomFrom.left) * (1 - e), s(zoomFrom.top) * (1 - e), s(zoomFrom.right) + (w - s(zoomFrom.right)) * e, s(zoomFrom.bottom) + (h - s(zoomFrom.bottom)) * e)
        val r = s(12f) * (1 - e)
        fill.color = zoomColor
        c.drawRoundRect(rect, r, r, fill)
        val art = zoomArt ?: return
        c.save()
        path.reset(); path.addRoundRect(rect, r, r, Path.Direction.CW)
        c.clipPath(path)
        bmp.alpha = 255
        crop(c, art, rect, bmp)
        c.restore()
    }

    private fun drawBg(c: Canvas, b: Bitmap?, a: Float) {
        if (b == null || a <= 0.01f) return
        bmp.alpha = (a * 255).toInt()
        bmp.colorFilter = if (pal.day) null else darken
        rect.set(0f, 0f, width.toFloat(), height.toFloat())
        c.drawBitmap(b, null, rect, bmp)
        bmp.colorFilter = null
        bmp.alpha = 255
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

    /** The artwork, large on the right, fading into the background on the left and bottom. */
    private fun drawHeroArt(c: Canvas, a: Float) {
        val cur = current() as? Item ?: return
        val art = mediaArt(cur) ?: return
        val alpha = a * (0.35f + 0.65f * fade.v)
        rect.set(s(452f), s(70f), s(936f), s(70f + 484f * 9f / 16f))
        bmp.alpha = (alpha * 255).toInt()
        if (art.width < art.height * 1.3f) {
            val sz = s(150f)
            rect2.set(rect.centerX() - sz / 2, rect.centerY() - sz / 2, rect.centerX() + sz / 2, rect.centerY() + sz / 2)
            c.drawBitmap(art, null, rect2, bmp)
            bmp.alpha = 255
            return
        }
        val save = c.saveLayer(rect, null)
        crop(c, art, rect, bmp)
        mask.shader = LinearGradient(rect.left, 0f, rect.left + rect.width() * .5f, 0f, 0, Color.BLACK, Shader.TileMode.CLAMP)
        c.drawRect(rect, mask)
        mask.shader = LinearGradient(0f, rect.bottom, 0f, rect.bottom - rect.height() * .4f, 0, Color.BLACK, Shader.TileMode.CLAMP)
        c.drawRect(rect, mask)
        c.restoreToCount(save)
        bmp.alpha = 255
    }

    private fun heroFor(cur: Any?): Hero = when (cur) {
        is Item.App -> Hero(cur.e.category, cur.e.label, cur.note ?: "", if (moveMode) "← → para moverla · OK para terminar" else "OK para abrir · mantén OK para más opciones")
        is Item.Fold -> Hero("Carpeta", cur.f.name, if (cur.apps.size == 1) "1 app" else "${cur.apps.size} apps", "OK para abrir")
        is Item.Hidden -> Hero("Apps ocultas", "${cur.count} ocultas", "Siguen instaladas, solo no se ven aquí", "OK para verlas")
        is Item.Media -> Hero("Continuar viendo · " + (cur.app?.label ?: ""), cur.w.title,
            listOfNotNull(cur.w.sub.ifBlank { null }, cur.w.minutesLeft.takeIf { it > 0 }?.let { "quedan $it min" }).joinToString(" · "),
            "OK para seguir · mantén OK para quitarla")
        is Item.Recent -> Hero("Seguir con…", cur.e.label, "Abierta " + UsageRank.ago(cur.at), "OK para abrir")
        is Chip -> Hero(if (cur.warn) "Aviso" else "SalaTV", cur.title, cur.desc, null)
        else -> Hero("SalaTV", "Tu inicio", "Mantén OK sobre una app para fijarla en el dock", null)
    }

    private fun drawHeroText(c: Canvas, a: Float) {
        if (a <= 0.01f) return
        val hero = heroFor(current())
        txt.textAlign = Paint.Align.LEFT
        txt.color = pal.fg
        txt.typeface = Fonts.medium; txt.textSize = s(13f); txt.letterSpacing = 0.16f; txt.alpha = (a * 0.72f * 255).toInt()
        c.drawText(ellipsize(hero.label.uppercase(Locale("es")), s(560f)), s(48f), s(126f), txt)
        txt.typeface = Fonts.bold; txt.letterSpacing = -0.02f
        var size = 58f
        txt.textSize = s(size)
        while (txt.measureText(hero.title) > s(560f) && size > 30f) { size -= 2f; txt.textSize = s(size) }
        txt.alpha = (a * 255).toInt()
        val base = 134f + size * 0.82f
        c.drawText(ellipsize(hero.title, s(560f)), s(46f), s(base), txt)
        txt.typeface = Fonts.light; txt.textSize = s(18f); txt.letterSpacing = 0f; txt.alpha = (a * 0.82f * 255).toInt()
        if (hero.desc.isNotEmpty()) c.drawText(ellipsize(hero.desc, s(560f)), s(48f), s(base + 34f), txt)
        val hint = hero.hint ?: return
        txt.typeface = Fonts.regular; txt.textSize = s(14f); txt.alpha = (a * 255).toInt()
        val tw = txt.measureText(hint)
        val top = base + (if (hero.desc.isNotEmpty()) 56f else 24f)
        rect.set(s(48f), s(top), s(48f) + tw + s(28f), s(top + 32f))
        fill.color = pal.chip; fill.alpha = (Color.alpha(pal.chip) * a).toInt()
        c.drawRoundRect(rect, rect.height() / 2, rect.height() / 2, fill)
        c.drawText(hint, s(62f), s(top + 21f), txt)
        txt.alpha = 255
    }

    private fun drawShelf(c: Canvas) {
        rect.set(s(24f), s(368f), s(936f), s(518f))
        fill.color = pal.glass
        c.drawRoundRect(rect, s(28f), s(28f), fill)
        stroke.color = pal.glassLine; stroke.strokeWidth = s(1f)
        c.drawRoundRect(rect, s(28f), s(28f), stroke)
        stroke.color = Ui.alpha(Color.WHITE, if (pal.day) .8f else .35f)
        c.drawLine(s(56f), s(368.6f), s(904f), s(368.6f), stroke)
        if (dock.isEmpty()) {
            txt.typeface = Fonts.light; txt.textSize = s(16f); txt.color = pal.fg; txt.alpha = 180
            txt.textAlign = Paint.Align.CENTER
            c.drawText("Mantén OK sobre una app y elige «Fijar en el dock»", s(480f), s(448f), txt)
            txt.textAlign = Paint.Align.LEFT; txt.alpha = 255
        }
    }

    private fun drawCont(c: Canvas): Boolean {
        if (!hasCont()) return false
        var busy = false
        txt.typeface = Fonts.regular; txt.textSize = s(15f); txt.color = pal.fg; txt.alpha = 180; txt.letterSpacing = 0f
        c.drawText("Continuar viendo", s(48f), s(CONT_Y - 18f), txt)
        txt.alpha = 255
        if (scroll.v + 540f < CONT_Y) return false
        for (i in cont.indices) {
            val x = 48f + i * CONT_STEP - contOff.v
            if (x + CONT_W < 0f || x > 960f) continue
            busy = card(c, cont[i], x, CONT_Y, row == 1 && col == i) or busy
        }
        return busy
    }

    /** A «Continuar viendo» card: picture, app, title, episode and how far it got. */
    private fun card(c: Canvas, item: Item, x: Float, y: Float, focused: Boolean): Boolean {
        val a = scales.getOrPut(item.key) { Anim(1f) }
        a.t = if (focused) 1.08f else 1f
        val busy = a.step(frameK)
        val X = s(x); val Y = s(y); val W = s(CONT_W); val H = s(CONT_H)
        val r = s(12f)
        c.save()
        c.scale(a.v, a.v, X + W / 2, Y + H / 2)
        rect.set(X, Y, X + W, Y + H)
        shadow.color = pal.bg
        shadow.setShadowLayer(if (focused) s(20f) else s(7f), 0f, if (focused) s(10f) else s(4f), if (pal.day) 0x40000000 else 0x99000000.toInt())
        c.drawRoundRect(rect, r, r, shadow)
        c.save()
        path.reset(); path.addRoundRect(rect, r, r, Path.Direction.CW)
        c.clipPath(path)
        fill.color = Ui.shade(itemColor(item), 0.35f)
        c.drawRect(rect, fill)
        mediaArt(item)?.let { crop(c, it, rect, bmp) }
        if (item is Item.Media) {
            fill.color = Color.BLACK
            fill.shader = LinearGradient(0f, rect.bottom, 0f, rect.top + rect.height() * 0.3f, 0xE6000000.toInt(), 0, Shader.TileMode.CLAMP)
            c.drawRect(rect, fill); fill.shader = null
            val app = item.app?.label ?: ""
            if (app.isNotEmpty()) {
                txt.typeface = Fonts.medium; txt.textSize = s(10.5f)
                val bw = txt.measureText(app) + s(14f)
                rect2.set(rect.left + s(8f), rect.top + s(8f), rect.left + s(8f) + bw, rect.top + s(26f))
                fill.color = 0xB3000000.toInt(); c.drawRoundRect(rect2, s(9f), s(9f), fill)
                txt.color = Color.WHITE; c.drawText(app, rect2.left + s(7f), rect2.bottom - s(5.5f), txt)
            }
            txt.color = Color.WHITE
            val hasSub = item.w.sub.isNotBlank()
            txt.typeface = Fonts.bold; txt.textSize = s(14f)
            c.drawText(ellipsize(item.w.title, W - s(20f)), rect.left + s(10f), rect.bottom - s(if (hasSub) 26f else 13f), txt)
            if (hasSub) {
                txt.typeface = Fonts.regular; txt.textSize = s(11f); txt.alpha = 210
                c.drawText(ellipsize(item.w.sub, W - s(20f)), rect.left + s(10f), rect.bottom - s(11f), txt)
                txt.alpha = 255
            }
            if (item.w.dur > 0) {
                fill.color = 0x40FFFFFF
                c.drawRect(rect.left, rect.bottom - s(4f), rect.right, rect.bottom, fill)
                fill.color = Ui.LAMP
                c.drawRect(rect.left, rect.bottom - s(4f), rect.left + rect.width() * item.w.progress, rect.bottom, fill)
            }
        }
        c.restore()
        if (focused) {
            stroke.color = pal.ring; stroke.strokeWidth = s(3f) / a.v
            c.drawRoundRect(rect, r, r, stroke)
        }
        c.restore()
        val note = when (item) {
            is Item.Media -> UsageRank.ago(item.w.at) + (item.w.minutesLeft.takeIf { it > 0 }?.let { " · quedan $it min" } ?: "")
            is Item.Recent -> item.e.label + " · " + UsageRank.ago(item.at)
            else -> ""
        }
        txt.typeface = Fonts.regular; txt.textSize = s(12f); txt.color = pal.fg; txt.alpha = if (focused) 230 else 150
        c.drawText(ellipsize(note, W), X, Y + H + H * (a.v - 1f) / 2 + s(19f), txt)
        txt.alpha = 255
        return busy
    }

    private fun drawGrid(c: Canvas): Boolean {
        if (grid.isEmpty()) return false
        var busy = false
        val y0 = gridY0()
        txt.typeface = Fonts.regular; txt.textSize = s(15f); txt.color = pal.fg; txt.alpha = 180; txt.letterSpacing = 0f
        c.drawText(gridLabel, s(GRID_X), s(y0 - 22f), txt)
        txt.alpha = 255
        val top = scroll.v - 80f; val bottom = scroll.v + 560f
        for (i in grid.indices) {
            val r = i / COLS; val cc = i % COLS
            val y = y0 + r * GRID_PITCH
            if (y + GRID_H < top || y > bottom) continue
            busy = tile(c, grid[i], GRID_X + cc * GRID_STEP_X, y, GRID_W, GRID_H, row == r + gridStart() && col == cc, 1.1f, grid[i] !is Item.App) or busy
        }
        return busy
    }

    /** One tile; returns true while its focus animation is still running. */
    private fun tile(c: Canvas, item: Item, x: Float, y: Float, w: Float, h: Float, focused: Boolean, big: Float, alwaysName: Boolean): Boolean {
        val a = scales.getOrPut(item.key) { Anim(1f) }
        a.t = if (focused) big else 1f
        val busy = a.step(frameK)
        val sc = a.v
        val X = s(x); val Y = s(y); val W = s(w); val H = s(h)
        val cx = X + W / 2; val cy = Y + H / 2
        val r = H * 0.16f
        c.save()
        c.scale(sc, sc, cx, cy)
        rect.set(X, Y, X + W, Y + H)
        shadow.color = pal.bg
        shadow.setShadowLayer(if (focused) s(20f) else s(7f), 0f, if (focused) s(10f) else s(4f), if (pal.day) 0x40000000 else 0x99000000.toInt())
        c.drawRoundRect(rect, r, r, shadow)
        when (item) {
            is Item.App -> appFace(c, item.e, r)
            is Item.Fold -> folderFace(c, item, r)
            is Item.Hidden -> hiddenFace(c, item, r)
            else -> {}
        }
        if (focused) {
            fill.color = Color.BLACK
            fill.shader = LinearGradient(rect.left, rect.top, rect.left + rect.width() * .6f, rect.bottom, 0x40FFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP)
            c.drawRoundRect(rect, r, r, fill)
            fill.shader = null
            stroke.color = pal.ring; stroke.strokeWidth = s(3f) / sc
            c.drawRoundRect(rect, r, r, stroke)
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
            val name = when (item) {
                is Item.App -> item.e.label
                is Item.Fold -> item.f.name
                is Item.Hidden -> "Apps ocultas"
                else -> ""
            }
            txt.typeface = Fonts.medium; txt.textSize = s(13f); txt.color = pal.fg; txt.alpha = if (focused) 255 else 170
            txt.textAlign = Paint.Align.CENTER
            c.drawText(ellipsize(name, W * 1.25f), cx, Y + H + H * (sc - 1f) / 2 + s(20f), txt)
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
            // Opaque: other drawing (the hint pill) leaves the shared paint almost transparent.
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
            if (art != null) crop(c, art, rect, bmp) else {
                fill.color = e.tile; c.drawRoundRect(rect, s(3f), s(3f), fill)
            }
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

    private fun greeting(): String {
        val h = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        val g = when (h) { in 5..11 -> "Buenos días"; in 12..19 -> "Buenas tardes"; else -> "Buenas noches" }
        return if (greetName != null) "$g, $greetName" else g
    }

    private fun drawTop(c: Canvas): Boolean {
        var busy = false
        val now = Date()
        txt.textAlign = Paint.Align.LEFT; txt.color = pal.fg; txt.letterSpacing = 0f
        txt.typeface = Fonts.medium; txt.textSize = s(30f)
        val clock = clockFmt.format(now)
        c.drawText(clock, s(46f), s(57f), txt)
        val cw = txt.measureText(clock)
        txt.typeface = Fonts.light; txt.textSize = s(15f); txt.alpha = 185
        c.drawText(greeting() + " · " + dateFmt.format(now), s(46f) + cw + s(14f), s(55f), txt)
        txt.alpha = 255

        if (chipCx.size != chips.size) chipCx = FloatArray(chips.size)
        var right = s(914f)
        for (i in chips.indices.reversed()) {
            val ch = chips[i]
            val focused = row == -1 && col == i
            txt.typeface = Fonts.medium; txt.textSize = s(14f)
            val wide = ch.label.isNotEmpty() && ch.icon != AVATAR
            val w = if (wide) s(15f) + (if (ch.icon > 0) s(26f) else 0f) + txt.measureText(ch.label) + s(16f) else s(38f)
            rect.set(right - w, s(26f), right, s(64f))
            chipCx[i] = rect.centerX() / u
            val a = scales.getOrPut("c:" + ch.id) { Anim(1f) }
            a.t = if (focused) 1.08f else 1f
            busy = a.step(frameK) or busy
            c.save()
            c.scale(a.v, a.v, rect.centerX(), rect.centerY())
            val rad = rect.height() / 2
            if (ch.icon == AVATAR) {
                fill.color = ch.color
                c.drawCircle(rect.centerX(), rect.centerY(), rad, fill)
                if (focused) { stroke.color = pal.fg; stroke.strokeWidth = s(3f); c.drawCircle(rect.centerX(), rect.centerY(), rad + s(3f), stroke) }
                txt.textAlign = Paint.Align.CENTER; txt.typeface = Fonts.bold; txt.textSize = s(17f); txt.color = Ui.INK
                c.drawText(ch.label, rect.centerX(), rect.centerY() + s(6f), txt)
                txt.textAlign = Paint.Align.LEFT
            } else {
                fill.color = when {
                    focused -> pal.fg
                    ch.warn -> Ui.alpha(Ui.WARN, if (pal.day) .55f else .25f)
                    else -> pal.chip
                }
                c.drawRoundRect(rect, rad, rad, fill)
                val fg = when {
                    focused -> pal.bg
                    ch.warn && !pal.day -> Ui.WARN
                    else -> pal.fg
                }
                if (wide) {
                    var tx = rect.left + s(15f)
                    if (ch.icon > 0) { icon(c, ch.icon, tx + s(9f), rect.centerY(), s(18f), fg); tx += s(26f) }
                    txt.color = fg; txt.typeface = Fonts.medium; txt.textSize = s(14f)
                    c.drawText(ch.label, tx, rect.centerY() + s(5f), txt)
                } else icon(c, ch.icon, rect.centerX(), rect.centerY(), s(18f), fg)
            }
            c.restore()
            right -= w + s(10f)
        }
        return busy
    }

    private fun drawHints(c: Canvas, w: Float) {
        txt.textAlign = Paint.Align.CENTER; txt.typeface = Fonts.regular; txt.textSize = s(13f); txt.color = pal.fg
        if (moveMode) {
            val t = "← →  mover      OK  terminar"
            val tw = txt.measureText(t)
            rect.set(w / 2 - tw / 2 - s(18f), s(500f), w / 2 + tw / 2 + s(18f), s(530f))
            fill.color = Ui.LAMP
            c.drawRoundRect(rect, rect.height() / 2, rect.height() / 2, fill)
            txt.color = Ui.INK
            c.drawText(t, w / 2, s(520f), txt)
        } else if (scroll.v < 4f && (grid.isNotEmpty() || hasCont()) && row <= 0) {
            txt.alpha = 140
            c.drawText(if (hasCont()) "▾  Continuar viendo" else "▾  Todas tus apps", w / 2, s(533f), txt)
        }
        txt.alpha = 255; txt.textAlign = Paint.Align.LEFT
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
                c.drawCircle(x(12f), y(12f), 3.4f * k, stroke)
                for (i in 0 until 8) {
                    val a = i * Math.PI / 4
                    c.drawLine(x(12f + cos(a).toFloat() * 6.6f), y(12f + sin(a).toFloat() * 6.6f), x(12f + cos(a).toFloat() * 9.6f), y(12f + sin(a).toFloat() * 9.6f), stroke)
                }
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

    /** A half-size picture of the current home, for the next cold start. */
    fun snapshot(): Bitmap? {
        if (width == 0 || chips.isEmpty() || pending != null || zoom.v > 0f) return null
        val b = Bitmap.createBitmap(width / 2, height / 2, Bitmap.Config.RGB_565)
        val c = Canvas(b)
        c.scale(0.5f, 0.5f)
        draw(c)
        return b
    }
}
