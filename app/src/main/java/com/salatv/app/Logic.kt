package com.salatv.app

/** Decisions with no Android in them, so the tests in src/test can check them on the PC. */
object Logic {
    /**
     * The dock: pinned apps first. With [auto] they are sorted by what gets opened at this time of day and
     * free places go to the most used apps of the moment. [visible] is in alphabetical order.
     */
    fun dock(pinned: List<String>, visible: List<String>, ranks: Map<String, Float>, auto: Boolean, max: Int = Prefs.DOCK_MAX): List<String> {
        val shown = pinned.filter { it in visible }
        if (!auto) return shown.take(max)
        fun r(p: String) = ranks[p] ?: 0f
        val out = shown.sortedByDescending { r(it) }.toMutableList()
        if (out.size < max) out += visible.filter { it !in out && r(it) > 0.5f }.sortedByDescending { r(it) }.take(max - out.size)
        if (out.isEmpty()) out += visible.take(5)
        return out.take(max)
    }

    val SORTS = listOf("az" to "A–Z", "used" to "Más usadas", "recent" to "Recientes", "type" to "Por tipo")

    fun sortName(mode: String) = SORTS.firstOrNull { it.first == mode }?.second ?: "A–Z"

    /** Orders «Todas tus apps». [alpha] is already A–Z; ties keep that order. */
    fun sort(alpha: List<String>, mode: String, totals: Map<String, Int>, last: Map<String, Long>, type: (String) -> String): List<String> = when (mode) {
        "used" -> alpha.sortedByDescending { totals[it] ?: 0 }
        "recent" -> alpha.sortedByDescending { last[it] ?: 0L }
        "type" -> alpha.sortedBy { t -> TYPE_ORDER.indexOf(type(t)).let { if (it < 0) TYPE_ORDER.size else it } }
        else -> alpha
    }

    private val TYPE_ORDER = listOf("Vídeo", "Música", "Juegos", "Fotos", "Noticias", "Social", "Herramientas", "App")

    /**
     * A second Home press 0.3–3.5 s after the vigía brought SalaTV forward counts as «Inicio twice».
     * On the TV, Amazon's home takes a few hundred ms to show after each press, so 2.5 s was too tight.
     */
    fun isDoubleHome(sinceJumpMs: Long, salaShown: Boolean) = salaShown && sinceJumpMs in 300..3500
}
