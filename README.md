# SalaTV

Un inicio propio para la Fire TV del salón: sin anuncios, rápido y pensado para el mando. Parte de lo que ya hizo [CleanYourTV](https://github.com/Gathin23/cleanyourtv) en esta tele y toma ideas de [Free My TV](https://github.com/simbasjungles/free-my-tv). También funciona en Google TV y Android TV, donde puede ser el inicio de verdad.

App Android en Kotlin sin librerías. El inicio se dibuja a mano en un solo Canvas (`HomeView`); el resto de pantallas son vistas normales que se manejan con el mando. Habla con la depuración ADB de la propia tele (`127.0.0.1:5555`) para lo que antes se hacía desde el PC. Solo usa internet para las apps recomendadas y sus versiones (se puede apagar en Ajustes).

## Instalar

Con la depuración ADB activada en la tele (Ajustes › Mi Fire TV › Opciones para desarrolladores):

```powershell
cd F:\Programacion\SalaTV
.\tools\instalar.ps1                # compila, instala en 10.0.0.100 y comprueba el vigía
.\tools\instalar.ps1 -Tele 10.0.0.112
```

`instalar.ps1` guarda la versión instalada en `copias\` antes de instalar. Si después el botón Inicio deja de llevar a SalaTV, vuelve sola a esa versión.

La primera vez, en la tele:

1. Abre **SalaTV** desde «Tus apps» (o «Alexa, abre SalaTV»).
2. Arriba sale **Configurar**. Entra y pulsa **Configurar todo**.
3. La tele pregunta «¿Permitir la depuración?». Marca **Permitir siempre** y acepta.

«Configurar todo» activa el vigía y da cuatro permisos: ajustes del sistema, uso de apps, ver lo que se reproduce (para «Continuar viendo») e instalar apps. También apaga el vigía de CleanYourTV (`com.gathin.tvhome/.HomeKeyService`) para que no compitan por el botón Inicio. En Google TV, además, deja SalaTV como inicio de la tele.

### Otras teles

```powershell
python tools\instalador.py
```

Busca en la red las teles con la depuración activa, dice qué modelo y sistema tiene cada una, instala SalaTV y aplica la lista de limpieza de su modelo. Antes de desactivar nada guarda en `copias\` la lista de paquetes de la tele.

## Lo que hace

### Versión 0.1

| Idea | Dónde |
|---|---|
| Vigía del botón Inicio | `Vigia.kt` |
| Abrir con la voz o un botón | Manifiesto, `KeysActivity` |
| ADB desde la propia tele | `Adb.kt` |
| Fondo que sigue al foco, estante de cristal | `HomeView` |
| Dock que cambia con la hora | `UsageRank`, `Logic.dock` |
| Día y noche según el atardecer | `Sun.kt` |
| Carpetas y apps ocultas | `Prefs`, `FolderActivity`, `HiddenActivity` |
| Entradas HDMI con nombre | `Inputs` en `Extras.kt` |
| Perfiles de la casa | `ProfilesActivity` |
| Buscar en cada app | `SearchActivity` |
| Panel rápido | `QuickPanelActivity` |
| Menú al mantener OK | `MainActivity.appMenu` |
| Botones del mando | `KeysActivity`, `Vigia.onKeyEvent` |
| Aviso tras las actualizaciones | `Watch` en `Cleaner.kt` |
| Salud de la tele | `HealthActivity` |
| DNS AdGuard | `Cleaner.setDns` |
| Red de seguridad y «Original» | `SafetyNet`, `CleanupActivity` |
| Ligera | Sin AndroidX ni librerías; APK de pruebas de 3,3 MB, casi todo las listas de limpieza |

### Versión 0.2

| Idea | Dónde | Nota |
|---|---|---|
| Continuar viendo | `Media.kt`, `HomeView.drawCont` | Lee las sesiones de reproducción de las apps (las mismas que usa Alexa): título, capítulo, imagen y por dónde ibas. Debajo, las apps abiertas hace poco («Seguir con…»). |
| Volver al punto exacto | `LinkRules`, `Resume` | SmartTube va al vídeo y al segundo; Netflix al título si da su número. Si la app no deja, se abre y SalaTV pulsa «play» en su sesión. |
| Doble Inicio | `Vigia.onStockHome` | Inicio dos veces en 2,5 s vuelve a la app anterior. |
| Encendido directo | `Vigia.onWake` | Inicio, lo más usado a esa hora, la última app, una app o una entrada. |
| Banners pintados | `AppRepo.paintBanner` | Para Xuper Hydra, SmartTube, TV Bro… Color elegible en el menú de la app. |
| Abrir con zoom | `HomeView.startZoom` | Se salta si las animaciones del sistema están apagadas. |
| ¿Sigues ahí? | `Vigia.checkIdle`, `StillThereActivity` | De noche, tras 1–3 h sin tocar el mando. |
| Volumen nocturno | `Night` en `Platform.kt` | No funciona si el sonido va por HDMI-CEC a una barra de sonido. |
| Ordenar «Todas tus apps» | `Logic.sort` | A–Z, más usadas, recientes, por tipo; por perfil. |
| Sonidos suaves | `Sounds`, `tools/make_sounds.py` | Apagados por defecto. |
| Apps con PIN | `Pin`, `PinActivity` | El vigía también lo pide si la app se abre desde fuera de SalaTV. |
| Arranque al instante | `MainActivity` (`home.jpg`) | La última imagen del inicio sale mientras cargan las apps. |
| Instalar con un clic | `tools/instalar.ps1` | Con copia y vuelta atrás. |
| Registro de problemas | `Registro`, `RegistroActivity` | Últimos 7 días; se copia al PC con `adb pull /sdcard/Android/data/com.salatv.app/files/registro.txt`. |
| Pruebas automáticas | `app/src/test` | `.\gradlew testDebugUnitTest`: sol, dock, orden, enlaces, versiones, clave ADB, PIN. |
| Navegación más rápida | `HomeView.key`, `Screen` | Mantener una flecha acelera; ⏪ ⏩ saltan al principio y al final. |
| Apps recomendadas | `Store.kt` | SmartTube y TV Bro desde GitHub; Kodi y VLC desde sus servidores; Stremio solo enlaza a su web. |
| Avisos de versiones nuevas | `Updates` | Una vez al día; chip arriba cuando hay alguna. |
| Instalador para otras teles | `tools/instalador.py` | |
| Google TV y Android TV | `Platform` | `cmd package set-home-activity`, como Free My TV. |
| Limpieza para cada modelo | `ListsActivity`, `assets/listas` | Las 158 listas de CleanYourTV (MIT); `tools/copy_lists.py` las actualiza. |

## Probado en la tele (21/10/2026)

- Instalación con `instalar.ps1`, copia y vuelta atrás automática.
- Los cuatro permisos de «Configurar todo» se aceptan en Fire OS 8.1.8 (dados desde el PC con los mismos comandos).
- Vigía: desde una app, Inicio lleva a SalaTV en ~1 s. Doble Inicio vuelve a la app anterior.
- En Fire OS la tecla Inicio no llega al vigía; tras actualizar SalaTV, el vigía la pone delante solo al arrancar.
- El DNS cifrado de AdGuard no funciona en la Wi‑Fi de casa («Private DNS is broken»): con él puesto la tele se quedaba sin internet y sin hora (estaba en 4 de julio). Se dejó en automático; SalaTV ahora comprueba la red al activarlo y lo quita si falla.

## Por probar en la tele

- Qué apps publican su sesión de reproducción, viendo algo de verdad en Netflix, Crunchyroll, Prime Video, SmartTube y Xuper Hydra.
- Qué apps publican su sesión de reproducción (Netflix, Crunchyroll, Prime Video, SmartTube, Xuper Hydra) y con qué datos.
- Entradas: en Fire OS cambiar de entrada es solo de Amazon (`TUNE_INPUT`), así que SalaTV pulsa por ADB la tecla HDMI 1–4. Hay que ver si la tele responde a esas teclas.
- Que `input keyevent 223` apague la tele y no solo la imagen (apagado programado y «¿Sigues ahí?»).
- Qué botones del mando llegan al vigía.

## Archivos

- `app/src/main/assets/debloat.txt`: la lista de CleanYourTV para esta tele (Fire OS 8.1.8).
- `app/src/main/assets/listas/`: las listas por modelo de CleanYourTV, con su licencia MIT.
- `app/src/main/assets/outfit.ttf`: fuente Outfit, licencia SIL OFL (`FONT-LICENSE-OFL.txt`).
- `tools/make_art.py`, `tools/make_sounds.py`: dibujan el banner y el icono, y generan los clics.
- `CHANGELOG.md`: notas de cada versión en español e inglés.
