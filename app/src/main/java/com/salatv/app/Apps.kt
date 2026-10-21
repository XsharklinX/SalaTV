package com.salatv.app

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.graphics.drawable.Drawable
import java.text.Collator
import java.util.Locale

class AppEntry(
    val pkg: String,
    val label: String,
    val intent: Intent,
    val banner: Bitmap?,
    val icon: Bitmap?,
    /** Bright accent taken from the artwork, for the glow and the focus halo. */
    val color: Int,
    val category: String,
    val updated: Long,
    /** False when SalaTV painted the banner because the app has none for TV. */
    val realBanner: Boolean = true,
) {
    /** Tile fill when the app has no banner. */
    val tile: Int = Ui.shade(color, 0.42f)
    @Volatile var blur: Bitmap? = null
    var shader: BitmapShader? = null
}

object AppRepo {
    @Volatile var apps: List<AppEntry> = emptyList()
        private set
    @Volatile private var dirty = true
    private val plain = HashMap<Int, Bitmap>()

    fun invalidate() { dirty = true }

    /** Other launchers (CleanYourTV's, Projectivy, Wolf…) and Amazon's home pieces. */
    @Volatile var launchers: Set<String> = emptySet()
        private set
    private val NOT_TO_WATCH = Regex("protector de pantalla|salvapantallas|screensaver|daydream", RegexOption.IGNORE_CASE)

    /** Launchers and screensavers: they stay in «Todas tus apps» but never fill the dock or «Seguir con…». */
    fun isTool(e: AppEntry) = e.pkg in launchers || NOT_TO_WATCH.containsMatchIn(e.label)

    /** Drops one app so its painted banner is drawn again (after a colour change). */
    fun forget(pkg: String) {
        apps = apps.filter { it.pkg != pkg }
        dirty = true
    }

    /** Colours offered for painted banners; 0 means the one taken from the icon. */
    val TINTS = listOf(
        "Automático" to 0, "Ámbar" to 0xFFFFB766.toInt(), "Azul" to 0xFF3B8CFF.toInt(), "Verde" to 0xFF2FB872.toInt(),
        "Coral" to 0xFFFF6F61.toInt(), "Violeta" to 0xFF8A6CF0.toInt(), "Rojo" to 0xFFE53935.toInt(), "Gris" to 0xFF7D8799.toInt(),
    )

    fun byPkg(pkg: String) = apps.firstOrNull { it.pkg == pkg }

    /** Everything that shows up in a TV or phone launcher, sorted the Spanish way. Reuses unchanged entries. */
    fun load(c: Context): List<AppEntry> {
        if (!dirty && apps.isNotEmpty()) return apps
        val pm = c.packageManager
        val found = LinkedHashMap<String, Pair<ResolveInfo, String>>()
        for (cat in arrayOf(Intent.CATEGORY_LEANBACK_LAUNCHER, Intent.CATEGORY_LAUNCHER)) {
            for (ri in pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(cat), 0)) {
                val p = ri.activityInfo.packageName
                if (p == c.packageName || p in found) continue
                found[p] = ri to cat
            }
        }
        launchers = pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), 0)
            .map { it.activityInfo.packageName }.toSet()
        val old = apps.associateBy { it.pkg }
        val collator = Collator.getInstance(Locale("es")).apply { strength = Collator.PRIMARY }
        val list = found.map { (p, v) ->
            val upd = try { pm.getPackageInfo(p, 0).lastUpdateTime } catch (e: Exception) { 0L }
            old[p]?.takeIf { it.updated == upd } ?: make(c, pm, v.first, v.second, upd)
        }.sortedWith { a, b -> collator.compare(a.label, b.label) }
        apps = list
        dirty = false
        return list
    }

    private fun make(c: Context, pm: PackageManager, ri: ResolveInfo, cat: String, upd: Long): AppEntry {
        val ai = ri.activityInfo
        val label = ri.loadLabel(pm).toString().trim().ifEmpty { ai.packageName }
        val intent = Intent(Intent.ACTION_MAIN).addCategory(cat).setClassName(ai.packageName, ai.name)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        val real = (ai.loadBanner(pm) ?: ai.applicationInfo.loadBanner(pm))?.let { draw(it, 320, 180) }
        val icon = try { draw(ri.loadIcon(pm), 128, 128) } catch (e: Exception) { null }
        val tint = Prefs.int(c, "tint:" + ai.packageName, 0)
        val color = if (real == null && tint != 0) tint else dominant(real ?: icon)
        val banner = real ?: paintBanner(icon, label, color)
        return AppEntry(ai.packageName, label, intent, banner, icon, color, category(ai.applicationInfo), upd, real != null)
    }

    /**
     * A banner in the same 16:9 shape as Netflix's or Disney+'s, for apps that only ship a phone icon:
     * the icon on the left over a gradient of its own colour, the name on the right.
     */
    private fun paintBanner(icon: Bitmap?, label: String, color: Int): Bitmap {
        val b = Bitmap.createBitmap(320, 180, Bitmap.Config.ARGB_8888)
        val cv = Canvas(b)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.shader = LinearGradient(0f, 0f, 320f, 180f, Ui.shade(color, 0.28f), Ui.shade(color, 0.78f), Shader.TileMode.CLAMP)
        cv.drawRect(0f, 0f, 320f, 180f, p)
        p.shader = android.graphics.RadialGradient(70f, 40f, 220f, 0x33FFFFFF, 0, Shader.TileMode.CLAMP)
        cv.drawRect(0f, 0f, 320f, 180f, p)
        p.shader = null
        if (icon != null) {
            p.setShadowLayer(14f, 0f, 6f, 0x66000000)
            p.color = Ui.shade(color, 0.2f)
            val r = android.graphics.RectF(22f, 44f, 114f, 136f)
            cv.drawRoundRect(r, 22f, 22f, p)
            p.clearShadowLayer()
            cv.drawBitmap(icon, null, r, Paint(Paint.FILTER_BITMAP_FLAG))
        }
        p.color = Color.WHITE
        p.typeface = Fonts.bold
        val x = if (icon != null) 132f else 24f
        val room = 320f - x - 18f
        var size = 34f
        p.textSize = size
        val words = label.split(' ')
        // One line if it fits, else two lines split near the middle.
        if (p.measureText(label) <= room || words.size == 1) {
            while (p.measureText(label) > room && size > 16f) { size -= 1f; p.textSize = size }
            cv.drawText(label, x, 90f + size * 0.35f, p)
        } else {
            val mid = (1 until words.size).minByOrNull { k -> kotlin.math.abs(words.take(k).joinToString(" ").length - words.drop(k).joinToString(" ").length) } ?: 1
            val l1 = words.take(mid).joinToString(" "); val l2 = words.drop(mid).joinToString(" ")
            size = 30f; p.textSize = size
            while (maxOf(p.measureText(l1), p.measureText(l2)) > room && size > 14f) { size -= 1f; p.textSize = size }
            cv.drawText(l1, x, 90f - size * 0.15f, p)
            cv.drawText(l2, x, 90f + size * 0.95f, p)
        }
        return b
    }

    /** Blurred backdrop for any picture (a «Continuar viendo» still), cached by key. */
    private val blurs = object : LinkedHashMap<String, Bitmap>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Bitmap>?) = size > 16
    }

    fun blurredArt(key: String, src: Bitmap): Bitmap = synchronized(blurs) {
        blurs.getOrPut(key) {
            val tiny = Bitmap.createScaledBitmap(src, 24, 14, true)
            Bitmap.createScaledBitmap(Bitmap.createScaledBitmap(tiny, 96, 54, true), 192, 108, true)
        }
    }

    private fun category(ai: ApplicationInfo) = when (ai.category) {
        ApplicationInfo.CATEGORY_GAME -> "Juegos"
        ApplicationInfo.CATEGORY_AUDIO -> "Música"
        ApplicationInfo.CATEGORY_VIDEO -> "Vídeo"
        ApplicationInfo.CATEGORY_IMAGE -> "Fotos"
        ApplicationInfo.CATEGORY_SOCIAL -> "Social"
        ApplicationInfo.CATEGORY_NEWS -> "Noticias"
        ApplicationInfo.CATEGORY_PRODUCTIVITY -> "Herramientas"
        else -> "App"
    }

    private fun draw(d: Drawable, w: Int, h: Int): Bitmap {
        val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        d.setBounds(0, 0, w, h)
        d.draw(Canvas(b))
        return b
    }

    /** Saturated pixels weigh more, so a red logo on black gives red, not dark grey. */
    private fun dominant(b: Bitmap?): Int {
        if (b == null) return Ui.LAMP
        val s = Bitmap.createScaledBitmap(b, 16, 9, true)
        val hsv = FloatArray(3)
        var r = 0.0; var g = 0.0; var bl = 0.0; var wsum = 0.0
        for (y in 0 until s.height) for (x in 0 until s.width) {
            val p = s.getPixel(x, y)
            if (Color.alpha(p) < 128) continue
            Color.colorToHSV(p, hsv)
            val w = hsv[1] * hsv[2] + 0.03
            r += Color.red(p) * w; g += Color.green(p) * w; bl += Color.blue(p) * w; wsum += w
        }
        if (wsum == 0.0) return Ui.LAMP
        Color.colorToHSV(Color.rgb((r / wsum).toInt(), (g / wsum).toInt(), (bl / wsum).toInt()), hsv)
        if (hsv[1] < 0.12f) return 0xFF8FA2C8.toInt()
        hsv[1] = hsv[1].coerceAtLeast(0.5f)
        hsv[2] = hsv[2].coerceAtLeast(0.85f)
        return Color.HSVToColor(hsv)
    }

    /**
     * A soft full-screen background from the app's artwork. Shrinking to 24×14 and growing back in two
     * bilinear steps blurs it for almost nothing, which matters on Fire OS where live blur (Android 12) is missing.
     */
    fun blurred(e: AppEntry): Bitmap {
        e.blur?.let { return it }
        val src = e.banner ?: iconCard(e)
        val tiny = Bitmap.createScaledBitmap(src, 24, 14, true)
        val mid = Bitmap.createScaledBitmap(tiny, 96, 54, true)
        val out = Bitmap.createScaledBitmap(mid, 192, 108, true)
        e.blur = out
        return out
    }

    private fun iconCard(e: AppEntry): Bitmap {
        val b = Bitmap.createBitmap(160, 90, Bitmap.Config.ARGB_8888)
        val c = Canvas(b)
        c.drawColor(e.tile)
        e.icon?.let { c.drawBitmap(Bitmap.createScaledBitmap(it, 72, 72, true), 44f, 9f, null) }
        return b
    }

    /** A plain lit backdrop for chips and folders. */
    fun plain(color: Int): Bitmap = synchronized(plain) {
        plain.getOrPut(color) {
            val b = Bitmap.createBitmap(96, 54, Bitmap.Config.ARGB_8888)
            val p = Paint()
            p.shader = LinearGradient(96f, 0f, 0f, 54f, Ui.shade(color, 0.6f), Ui.shade(color, 0.12f), Shader.TileMode.CLAMP)
            Canvas(b).drawRect(0f, 0f, 96f, 54f, p)
            b
        }
    }
}
