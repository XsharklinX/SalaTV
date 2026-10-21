package com.salatv.app

import android.content.Context
import java.util.Calendar
import java.util.TimeZone
import kotlin.math.acos
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.tan

class City(val id: String, val name: String, val lat: Double, val lon: Double, val tz: String)

object Cities {
    val ALL = listOf(
        City("mad", "Madrid", 40.4168, -3.7038, "Europe/Madrid"),
        City("bcn", "Barcelona", 41.3874, 2.1686, "Europe/Madrid"),
        City("vlc", "Valencia", 39.4699, -0.3763, "Europe/Madrid"),
        City("svq", "Sevilla", 37.3891, -5.9845, "Europe/Madrid"),
        City("bio", "Bilbao", 43.2630, -2.9350, "Europe/Madrid"),
        City("lpa", "Las Palmas", 28.1235, -15.4363, "Atlantic/Canary"),
        City("cdmx", "Ciudad de México", 19.4326, -99.1332, "America/Mexico_City"),
        City("gdl", "Guadalajara", 20.6597, -103.3496, "America/Mexico_City"),
        City("mty", "Monterrey", 25.6866, -100.3161, "America/Monterrey"),
        City("cun", "Cancún", 21.1619, -86.8515, "America/Cancun"),
        City("tij", "Tijuana", 32.5149, -117.0382, "America/Tijuana"),
        City("bog", "Bogotá", 4.7110, -74.0721, "America/Bogota"),
        City("mde", "Medellín", 6.2442, -75.5812, "America/Bogota"),
        City("clo", "Cali", 3.4516, -76.5320, "America/Bogota"),
        City("baq", "Barranquilla", 10.9685, -74.7813, "America/Bogota"),
        City("lim", "Lima", -12.0464, -77.0428, "America/Lima"),
        City("uio", "Quito", -0.1807, -78.4678, "America/Guayaquil"),
        City("gye", "Guayaquil", -2.1709, -79.9224, "America/Guayaquil"),
        City("ccs", "Caracas", 10.4806, -66.9036, "America/Caracas"),
        City("scl", "Santiago de Chile", -33.4489, -70.6693, "America/Santiago"),
        City("bue", "Buenos Aires", -34.6037, -58.3816, "America/Argentina/Buenos_Aires"),
        City("cor", "Córdoba (Argentina)", -31.4201, -64.1888, "America/Argentina/Cordoba"),
        City("mvd", "Montevideo", -34.9011, -56.1645, "America/Montevideo"),
        City("asu", "Asunción", -25.2637, -57.5759, "America/Asuncion"),
        City("lpb", "La Paz", -16.4897, -68.1193, "America/La_Paz"),
        City("vvi", "Santa Cruz de la Sierra", -17.7833, -63.1821, "America/La_Paz"),
        City("pty", "Panamá", 8.9824, -79.5199, "America/Panama"),
        City("sjo", "San José (Costa Rica)", 9.9281, -84.0907, "America/Costa_Rica"),
        City("mga", "Managua", 12.1149, -86.2362, "America/Managua"),
        City("tgu", "Tegucigalpa", 14.0723, -87.1921, "America/Tegucigalpa"),
        City("sal", "San Salvador", 13.6929, -89.2182, "America/El_Salvador"),
        City("gua", "Ciudad de Guatemala", 14.6349, -90.5069, "America/Guatemala"),
        City("sdq", "Santo Domingo", 18.4861, -69.9312, "America/Santo_Domingo"),
        City("sju", "San Juan", 18.4655, -66.1057, "America/Puerto_Rico"),
        City("hav", "La Habana", 23.1136, -82.3666, "America/Havana"),
        City("mia", "Miami", 25.7617, -80.1918, "America/New_York"),
        City("nyc", "Nueva York", 40.7128, -74.0060, "America/New_York"),
        City("hou", "Houston", 29.7604, -95.3698, "America/Chicago"),
        City("lax", "Los Ángeles", 34.0522, -118.2437, "America/Los_Angeles"),
    )

    /** The chosen city, or the first one in the TV's time zone. */
    fun current(c: Context): City {
        Prefs.str(c, "city")?.let { id -> ALL.firstOrNull { it.id == id }?.let { return it } }
        val tz = TimeZone.getDefault()
        return ALL.firstOrNull { it.tz == tz.id }
            ?: ALL.firstOrNull { TimeZone.getTimeZone(it.tz).rawOffset == tz.rawOffset }
            ?: ALL[0]
    }
}

/** Sunrise and sunset with the NOAA approximation; a minute or two off is fine for a theme switch. */
object Sun {
    /** Minutes after local midnight for (sunrise, sunset), or null in polar day or night. */
    fun times(city: City, cal: Calendar = Calendar.getInstance()): Pair<Int, Int>? {
        val n = cal.get(Calendar.DAY_OF_YEAR)
        val g = 2 * Math.PI / 365.0 * (n - 1)
        val eq = 229.18 * (0.000075 + 0.001868 * cos(g) - 0.032077 * sin(g) - 0.014615 * cos(2 * g) - 0.040849 * sin(2 * g))
        val decl = 0.006918 - 0.399912 * cos(g) + 0.070257 * sin(g) - 0.006758 * cos(2 * g) +
            0.000907 * sin(2 * g) - 0.002697 * cos(3 * g) + 0.00148 * sin(3 * g)
        val lat = Math.toRadians(city.lat)
        val x = cos(Math.toRadians(90.833)) / (cos(lat) * cos(decl)) - tan(lat) * tan(decl)
        if (x < -1 || x > 1) return null
        val ha = Math.toDegrees(acos(x))
        val offset = TimeZone.getTimeZone(city.tz).getOffset(cal.timeInMillis) / 60_000
        val rise = 720 - 4 * (city.lon + ha) - eq + offset
        val set = 720 - 4 * (city.lon - ha) - eq + offset
        return rise.toInt() to set.toInt()
    }

    fun fmt(min: Int) = "%02d:%02d".format(((min / 60) % 24 + 24) % 24, (min % 60 + 60) % 60)

    fun isDayNow(city: City): Boolean {
        val cal = Calendar.getInstance()
        val now = cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE)
        val t = times(city, cal) ?: return cal.get(Calendar.HOUR_OF_DAY) in 8..19
        return now in t.first until t.second
    }
}

/** Day or night for the home: the user's choice, or the real sunset of their city. */
object Look {
    fun mode(c: Context) = Prefs.str(c, "theme", "auto")!!
    fun isDay(c: Context) = when (mode(c)) {
        "day" -> true
        "night" -> false
        else -> Sun.isDayNow(Cities.current(c))
    }
}
