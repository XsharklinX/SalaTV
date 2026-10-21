package com.salatv.app

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings

/** The clean-up CleanYourTV did from the PC, done from the TV: the list, the DNS and the vigía. */
object Cleaner {
    const val VIGIA = "com.salatv.app/com.salatv.app.Vigia"
    const val DNS_HOST = "dns.adguard-dns.com"

    /** CleanYourTV's own Home-key service on this TV. Two vigías would fight over Home, so ours replaces it. */
    private const val OTHER_VIGIA = "com.gathin.tvhome/"

    /** Never touched, even if one slips into the list: home, settings, inputs, updates, store, Android itself. */
    private val PROTECTED = listOf(
        "com.amazon.tv.launcher", "com.amazon.tv.settings", "com.amazon.tv.settings.v2",
        "com.amazon.tv.inputpreference.service", "com.amazon.device.software.ota",
        "com.amazon.tv.oobe", "com.amazon.venezia", "com.salatv.app", "android",
    )

    fun isProtected(p: String) = p in PROTECTED || p.startsWith("com.android.") || p.startsWith("com.amazon.tv.settings")

    /** The list chosen in «Lista de limpieza»: this TV's own, or one of CleanYourTV's per-model lists. */
    fun list(c: Context): List<String> = read(c, ListsActivity.current(c))

    private fun read(c: Context, id: String): List<String> = try {
        c.assets.open(if (id == "custom") "debloat.txt" else "listas/$id.txt").bufferedReader().readLines()
            .map { it.substringBefore('#').trim() }
            .filter { it.isNotEmpty() && !it.contains(' ') && !isProtected(it) }
    } catch (e: Exception) { emptyList() }

    fun count(c: Context, id: String) = read(c, id).size

    /** Installed and enabled. A package that is gone does not count. */
    private fun active(pm: PackageManager, p: String): Boolean = try {
        val s = pm.getApplicationEnabledSetting(p)
        s == PackageManager.COMPONENT_ENABLED_STATE_DEFAULT || s == PackageManager.COMPONENT_ENABLED_STATE_ENABLED
    } catch (e: Exception) { false }

    fun reactivated(c: Context): List<String> = list(c).filter { active(c.packageManager, it) }

    private fun services(c: Context) = Settings.Secure.getString(c.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: ""

    fun vigiaOn(c: Context) = services(c).split(':').any { it.equals(VIGIA, true) || it.equals("com.salatv.app/.Vigia", true) } &&
        Settings.Secure.getInt(c.contentResolver, Settings.Secure.ACCESSIBILITY_ENABLED, 0) == 1

    fun dnsHost(c: Context): String? = try {
        val cr = c.contentResolver
        if (Settings.Global.getString(cr, "private_dns_mode") == "hostname") Settings.Global.getString(cr, "private_dns_specifier") else null
    } catch (e: Exception) { null }

    fun canWriteSecure(c: Context) = c.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) == PackageManager.PERMISSION_GRANTED

    private fun servicesWith(c: Context, on: Boolean): String {
        val keep = services(c).split(':').filter {
            it.isNotBlank() && !it.startsWith("com.salatv.app/") && !it.startsWith(OTHER_VIGIA)
        }
        return (if (on) keep + VIGIA else keep).joinToString(":")
    }

    // ---- actions; they run on a background thread ----

    /**
     * One tap: secure settings, app usage, what is playing (for «Continuar viendo»), installing apps,
     * and the vigía. On Google TV and Android TV it also makes SalaTV the real Home.
     */
    fun setupAll(c: Context, adb: Adb): String {
        val pkg = c.packageName
        adb.shell("pm grant $pkg android.permission.WRITE_SECURE_SETTINGS")
        adb.shell("appops set $pkg GET_USAGE_STATS allow")
        adb.shell("appops set $pkg REQUEST_INSTALL_PACKAGES allow")
        adb.shell("cmd notification allow_listener $pkg/$pkg.MediaWatcher")
        val hadOther = services(c).contains(OTHER_VIGIA)
        setVigia(c, true, adb)
        val home = if (!Platform.isFire(c)) " " + Platform.makeDefaultHome(c, adb) + "." else ""
        Prefs.put(c, "setupV", Prefs.SETUP_VERSION)
        return "Listo: vigía activo y permisos concedidos." + home + if (hadOther) " Se apagó el vigía de CleanYourTV para que no compitan." else ""
    }

    fun setVigia(c: Context, on: Boolean, adb: Adb?) {
        val v = servicesWith(c, on)
        if (adb != null) {
            if (v.isEmpty()) adb.shell("settings delete secure enabled_accessibility_services; settings put secure accessibility_enabled 0")
            else adb.shell("settings put secure enabled_accessibility_services '$v'; settings put secure accessibility_enabled 1")
        } else {
            val cr = c.contentResolver
            Settings.Secure.putString(cr, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, v)
            Settings.Secure.putInt(cr, Settings.Secure.ACCESSIBILITY_ENABLED, if (v.isEmpty()) 0 else 1)
        }
    }

    fun setDns(c: Context, on: Boolean, adb: Adb?): String {
        if (adb != null) {
            adb.shell(if (on) "settings put global private_dns_mode hostname; settings put global private_dns_specifier $DNS_HOST"
                else "settings put global private_dns_mode opportunistic")
        } else {
            val cr = c.contentResolver
            Settings.Global.putString(cr, "private_dns_mode", if (on) "hostname" else "opportunistic")
            if (on) Settings.Global.putString(cr, "private_dns_specifier", DNS_HOST)
        }
        Prefs.put(c, "dnsWanted", on)
        if (!on) return "DNS normal"
        // Some networks block encrypted DNS (port 853); then strict mode leaves the TV with no names at all.
        // Seen on this TV's Wi-Fi: «Private DNS is broken». Check, and step back if so.
        Thread.sleep(8000)
        if (dnsWorks()) return "DNS privado activo ($DNS_HOST)"
        setDns(c, false, adb)
        Registro.add(c, "DNS", "Esta red no deja usar el DNS cifrado de AdGuard; se volvió al DNS normal")
        return "Tu red no deja usar el DNS cifrado: la tele se quedaba sin internet, así que se volvió al DNS normal"
    }

    private fun dnsWorks(): Boolean = try {
        java.net.InetAddress.getByName("connectivitycheck.gstatic.com"); true
    } catch (e: Exception) { false }

    fun reapply(c: Context, adb: Adb): String {
        val pkgs = reactivated(c)
        if (pkgs.isEmpty()) return "Todo seguía limpio"
        val out = adb.shell(pkgs.joinToString("; ") { "pm disable-user --user 0 $it" })
        Prefs.addDisabled(c, pkgs)
        Watch.acknowledge(c)
        val left = reactivated(c).size
        return (if (left == 0) "Limpio: ${pkgs.size} apps desactivadas otra vez" else "Se desactivaron ${pkgs.size - left} de ${pkgs.size}; Fire OS no dejó tocar el resto") + "\n" + out.trim()
    }

    /** Everything back as it came: the list, the DNS and the vigía. CleanYourTV's own launcher stays installed. */
    fun factory(c: Context, adb: Adb): String {
        val pkgs = (list(c) + Prefs.disabledBySala(c)).distinct()
        adb.shell(pkgs.joinToString("; ") { "pm enable $it" })
        setDns(c, false, adb)
        setVigia(c, false, adb)
        Prefs.clearDisabled(c)
        Prefs.put(c, "dnsWanted", false)
        return "Tele como de fábrica: ${pkgs.size} apps reactivadas, DNS normal y vigía apagado"
    }
}

/** Notices when a Fire OS update brought back what was cleaned, or switched the vigía off. */
object Watch {
    class Report(val reactivated: List<String>, val firmwareChanged: Boolean, val vigiaOff: Boolean, val dnsLost: Boolean) {
        val attention get() = reactivated.isNotEmpty() || dnsLost || (vigiaOff && firmwareChanged)

        fun title() = when {
            reactivated.isNotEmpty() && firmwareChanged -> "Una actualización reactivó ${reactivated.size} apps"
            reactivated.isNotEmpty() -> "${reactivated.size} apps de anuncios activas otra vez"
            dnsLost -> "El DNS privado se apagó"
            else -> "La actualización apagó el vigía"
        }

        fun detail() = when {
            reactivated.isNotEmpty() -> "OK para volver a limpiar desde la tele"
            dnsLost -> "OK para volver a activarlo"
            else -> "OK para volver a activarlo"
        }

        fun signature() = reactivated.sorted().joinToString(",") + "|$vigiaOff|$dnsLost"
    }

    fun check(c: Context): Report {
        val fp = Build.FINGERPRINT
        val seen = Prefs.str(c, "fp")
        if (seen == null) Prefs.put(c, "fp", fp)
        val dnsLost = Prefs.bool(c, "dnsWanted", false) && Cleaner.dnsHost(c) == null
        val setup = Prefs.setupDone(c)
        return Report(Cleaner.reactivated(c), seen != null && seen != fp, setup && !Cleaner.vigiaOn(c), dnsLost)
    }

    fun acknowledge(c: Context) = Prefs.put(c, "fp", Build.FINGERPRINT)
}
