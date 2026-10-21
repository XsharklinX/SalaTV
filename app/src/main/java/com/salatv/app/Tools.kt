package com.salatv.app

import android.app.Activity
import android.content.Intent
import android.os.Build
import android.provider.Settings
import android.view.KeyEvent
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView

class HealthActivity : Screen() {
    private var busy = false

    override fun onResume() {
        super.onResume()
        build(null)
    }

    private fun build(freed: String?) {
        val col = Ui.page(this, "Salud de la tele", "Memoria, espacio y apps abiertas")
        val s = Health.snap(this)
        val used = s.memTotal - s.memAvail
        Ui.bar(col, "Memoria", "${Health.gb(used)} de ${Health.gb(s.memTotal)} GB", used.toFloat() / s.memTotal, if (used > s.memTotal * 0.85) Ui.BAD else Ui.LAMP)
        val sUsed = s.storTotal - s.storFree
        Ui.bar(col, "Almacenamiento", "${Health.gb(sUsed)} de ${Health.gb(s.storTotal)} GB", sUsed.toFloat() / s.storTotal, if (s.storFree < 1_000_000_000L) Ui.BAD else Ui.OK)
        if (freed != null) Ui.note(col, freed, Ui.OK)

        Ui.section(col, "Ahora")
        Ui.row(col, "Apps usadas en la última hora", if (s.recent < 0) "Hace falta el permiso de uso de apps" else null, if (s.recent < 0) "—" else s.recent.toString())
        Ui.row(col, "Memoria que usa SalaTV", "La meta es menos de 100 MB", "${s.ownMb} MB").valueColor(if (s.ownMb < 100) Ui.OK else Ui.WARN)
        val free = Ui.row(col, "Liberar memoria", "Cierra las apps en segundo plano. Las del sistema no se tocan.") { free(s.memAvail) }
        Ui.row(col, "Espacio de cada app", "Abre la lista de apps de la tele") { Sys.open(this, Settings.ACTION_APPLICATION_SETTINGS) }
        free.view.requestFocus()
    }

    private fun free(before: Long) {
        if (busy) return
        busy = true
        Ui.toast(this, "Liberando memoria…")
        Bg.run({
            val adb = try { Adb.connect(this, allowPrompt = false) } catch (e: Exception) { null }
            try { Health.free(this, adb) } finally { adb?.close() }
            Thread.sleep(1500)
            Health.snap(this).memAvail
        }) { r ->
            busy = false
            val after = r.getOrNull() ?: before
            val mb = ((after - before) / 1_048_576).coerceAtLeast(0)
            build(if (mb > 0) "Liberados $mb MB" else "La memoria ya estaba libre")
        }
    }
}

/** What runs under the hood: the vigía, permissions, the clean-up list, the DNS, the safety net. */
class CleanupActivity : Screen() {
    private var busy = false
    private lateinit var status: TextView

    override fun onResume() {
        super.onResume()
        if (!busy) build()
    }

    private fun stateRow(col: LinearLayout, title: String, value: String, good: Boolean) {
        Ui.row(col, title, null, value).valueColor(if (good) Ui.OK else Ui.WARN)
    }

    private fun build() {
        val col = Ui.page(this, "Limpieza y vigía", "Lo que SalaTV hace por debajo para que la tele siga limpia")
        status = Ui.note(col, "", Ui.LAMP).apply { visibility = View.GONE }
        val report = Watch.check(this)
        val total = Cleaner.list(this).size
        val back = report.reactivated
        val dns = Cleaner.dnsHost(this)

        Ui.section(col, "Estado")
        stateRow(col, "Vigía del botón Inicio", if (Cleaner.vigiaOn(this)) "Activo" else "Apagado", Cleaner.vigiaOn(this))
        if (!Platform.isFire(this)) stateRow(col, "SalaTV como inicio de la tele", if (Platform.isDefaultHome(this)) "Sí" else "No", Platform.isDefaultHome(this))
        stateRow(col, "Permiso de uso de apps", if (UsageRank.has(this)) "Concedido" else "Falta", UsageRank.has(this))
        stateRow(col, "Ver lo que se reproduce (Continuar viendo)", if (MediaWatcher.enabled(this)) "Concedido" else "Falta", MediaWatcher.enabled(this))
        stateRow(col, "Permiso de ajustes del sistema", if (Cleaner.canWriteSecure(this)) "Concedido" else "Falta", Cleaner.canWriteSecure(this))
        stateRow(col, "Instalar apps recomendadas", if (Installer.allowed(this)) "Permitido" else "Falta", Installer.allowed(this))
        stateRow(col, "Apps de anuncios y rastreo", "${total - back.size} de $total desactivadas", back.isEmpty())
        stateRow(col, "DNS privado", dns ?: "Normal", dns != null)
        if (report.firmwareChanged) Ui.note(col, "Fire OS se actualizó desde la última vez (ahora ${Build.DISPLAY}).", Ui.WARN)

        Ui.section(col, "Acciones")
        val setup = Ui.row(col, "Configurar todo", "Activa el vigía y da los permisos (uso de apps, Continuar viendo, ajustes, instalar apps). La primera vez la tele pregunta «¿Permitir la depuración?»: marca «Permitir siempre» y acepta.") {
            act("Configurando…", true) { adb -> Cleaner.setupAll(this, adb!!) }
        }
        Ui.row(col, "Volver a limpiar", if (back.isEmpty()) "Todo sigue limpio" else "${back.size} apps se activaron otra vez", if (back.isEmpty()) "✓" else back.size.toString()) {
            if (back.isEmpty()) Ui.toast(this, "No hay nada que limpiar") else act("Limpiando…", true) { adb -> Cleaner.reapply(this, adb!!) }
        }
        if (back.isNotEmpty()) Ui.note(col, back.joinToString("\n"))
        Ui.row(col, "Lista de limpieza", "Qué apps desactiva «Volver a limpiar»", ListsActivity.label(this)) { startActivity(Intent(this, ListsActivity::class.java)) }
        if (report.attention) Ui.row(col, "Ocultar este aviso", "No volverá a salir hasta que cambie algo") {
            Prefs.put(this, "dismissed", report.signature()); Watch.acknowledge(this); finish()
        }

        Ui.section(col, "DNS privado")
        Ui.note(col, "Corta rastreadores y muchos anuncios en toda la tele con ${Cleaner.DNS_HOST}. No quita los anuncios de YouTube y algún servicio de Amazon puede fallar.")
        Ui.chips(col, listOf("AdGuard", "Normal"), if (dns != null) 0 else 1) { i ->
            act(if (i == 0) "Activando el DNS…" else "Quitando el DNS…", false) { adb -> Cleaner.setDns(this, i == 0, adb) }
        }

        Ui.section(col, "Red de seguridad")
        val crashes = SafetyNet.recentCrashes(this)
        Ui.note(col, if (SafetyNet.safeMode(this)) "SalaTV se cerró varias veces seguidas y el vigía está en pausa: el botón Inicio lleva al inicio de Amazon."
            else "Si SalaTV se cierra 3 veces en 5 minutos, el botón Inicio vuelve solo al inicio de Amazon." + if (crashes > 0) " Cierres en las últimas 24 h: $crashes." else "")
        if (SafetyNet.safeMode(this)) Ui.row(col, "Salir del modo seguro", "El vigía vuelve a funcionar") { SafetyNet.clear(this); build() }
        Ui.row(col, "Quitar SalaTV del inicio", "Apaga el vigía y abre el inicio original. SalaTV sigue instalada.") {
            Ui.confirm(this, "¿Quitar SalaTV del inicio?", "Sí, quitarla") {
                act("Apagando el vigía…", false) { adb -> Cleaner.setVigia(this, false, adb); Bg.main.post { Sys.stockHome(this) }; "Vigía apagado" }
            }
        }
        Ui.row(col, "Todo como de fábrica", "Reactiva las $total apps de la lista, quita el DNS y el vigía") {
            Ui.confirm(this, "¿Dejar la tele como de fábrica?", "Sí, todo de fábrica") {
                act("Volviendo a fábrica…", true) { adb -> Cleaner.factory(this, adb!!).also { Bg.main.post { Sys.stockHome(this) } } }
            }
        }

        Ui.section(col, "Depuración ADB")
        Ui.note(col, "SalaTV usa la depuración de la propia tele (127.0.0.1:5555) para lo que antes se hacía desde el PC.")
        Ui.row(col, "Probar la conexión") {
            act("Conectando…", true) { adb -> "Conectado con " + adb!!.shell("getprop ro.build.version.name").trim().ifEmpty { "la tele" } }
        }
        Ui.row(col, "Apagar la depuración ADB", "Más seguro cuando no la uses. Para volver a usarla, actívala en Opciones para desarrolladores.") {
            Ui.confirm(this, "¿Apagar la depuración ADB?", "Sí, apagarla") {
                act("Apagando…", false) { adb ->
                    if (adb != null) try { adb.shell("settings put global adb_enabled 0") } catch (_: Exception) {}
                    else Settings.Global.putInt(contentResolver, Settings.Global.ADB_ENABLED, 0)
                    "Depuración apagada"
                }
            }
        }
        Prefs.str(this, "lastLog")?.takeIf { it.isNotBlank() }?.let {
            Ui.section(col, "Último resultado")
            Ui.note(col, it)
        }
        if (!Prefs.setupDone(this)) setup.view.requestFocus()
    }

    /**
     * Runs [work] off the main thread. With [needsAdb], or when SalaTV cannot change secure settings
     * by itself yet, it connects to the TV's ADB first.
     */
    private fun act(label: String, needsAdb: Boolean, work: (Adb?) -> String) {
        if (busy) return
        busy = true
        status.text = label
        status.visibility = View.VISIBLE
        Bg.run({
            if (needsAdb || !Cleaner.canWriteSecure(this)) {
                Adb.connect(this) {
                    Bg.main.post { status.text = "Mira la tele: acepta el aviso «¿Permitir la depuración?» y marca «Permitir siempre»." }
                }.use { work(it) }
            } else work(null)
        }) { r ->
            busy = false
            r.onSuccess {
                Prefs.put(this, "lastLog", it)
                Ui.toast(this, it.lineSequence().first())
                build()
            }.onFailure {
                status.text = Adb.explain(it)
                status.setTextColor(Ui.BAD)
                Registro.add(this, "Limpieza", label.trimEnd('…') + ": " + Adb.explain(it))
            }
        }
    }
}

/** Learn a remote button through the vigía, then choose what it opens. */
class KeysActivity : Screen() {
    private lateinit var status: TextView
    private val giveUp = Runnable {
        if (Vigia.learner != null) {
            Vigia.learner = null
            status.text = "No llegó ningún botón. Ese botón lo gestiona Fire OS y no se puede cambiar."
        }
    }

    override fun onResume() {
        super.onResume()
        val col = Ui.page(this, "Botones del mando", "Que un botón del mando abra la app que quieras, o vuelva a SalaTV")
        Ui.note(col, "Fire OS solo deja cambiar algunos botones. Si al pulsar uno aquí no pasa nada, ese no se puede.")
        status = Ui.note(col, "", Ui.LAMP)
        Prefs.keyMaps(this).forEach { km ->
            val target = if (km.pkg == packageName) "SalaTV" else AppRepo.byPkg(km.pkg)?.label ?: km.pkg
            Ui.row(col, keyName(km.keyCode), "Abre $target") {
                Ui.menu(this, keyName(km.keyCode), listOf("Quitar este atajo" to {
                    Prefs.saveKeyMaps(this, Prefs.keyMaps(this).filter { it.keyCode != km.keyCode }); onResume()
                }))
            }
        }
        val add = Ui.row(col, "Añadir un botón") { learn() }
        Ui.section(col, "Botón de ajustes del mando")
        Ui.chips(col, listOf("Abre el panel rápido", "Abre los ajustes de Amazon"), if (Prefs.bool(this, "settingsKeyPanel", true)) 0 else 1) { i ->
            Prefs.put(this, "settingsKeyPanel", i == 0); Vigia.reload()
        }
        add.view.requestFocus()
    }

    private fun keyName(code: Int) = "Botón " + KeyEvent.keyCodeToString(code).removePrefix("KEYCODE_").replace('_', ' ').lowercase()

    private fun learn() {
        if (Vigia.instance == null) {
            status.text = "Primero activa el vigía en «Limpieza y vigía» › «Configurar todo»."
            return
        }
        status.text = "Pulsa ahora el botón del mando que quieres usar…"
        Vigia.learner = { code, scan ->
            Bg.main.post {
                Bg.main.removeCallbacks(giveUp)
                startActivity(Intent(this, AppPickerActivity::class.java).putExtra("purpose", "key:$code:$scan"))
            }
        }
        Bg.main.postDelayed(giveUp, 15_000)
    }

    override fun onPause() {
        Vigia.learner = null
        Bg.main.removeCallbacks(giveUp)
        super.onPause()
    }
}
