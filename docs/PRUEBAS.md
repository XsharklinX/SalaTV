# Pruebas en la tele

Tele de referencia: Hisense con Fire OS 8.1.8 (AFTHY7ABBA, «ginza»).

## Probado (21/10/2026)

- Instalación con `instalar.ps1`, copia y vuelta atrás automática.
- Los cuatro permisos de «Configurar todo» se aceptan en Fire OS 8.1.8 (dados desde el PC con los mismos comandos).
- Vigía: desde una app, Inicio lleva a SalaTV en ~1 s. Doble Inicio vuelve a la app anterior.
- En Fire OS la tecla Inicio no llega al vigía; tras actualizar SalaTV, el vigía la pone delante solo al arrancar.
- El DNS cifrado de AdGuard no funciona en algunas redes («Private DNS is broken»): con él puesto la tele se quedaba sin internet y sin hora. SalaTV comprueba la red al activarlo y lo quita si falla.
- Rendimiento del inicio (`adb shell dumpsys gfxinfo com.salatv.app`): del 40–62 % de fotogramas lentos al 8 % tras componer el fondo una vez por cambio de foco y quitar sombras de las tarjetas sin foco.
- Enlaces que acepta cada app (filtros de intents con `dumpsys package`):
  - Netflix: `https://www.netflix.com/title/<id>` y `/watch/<id>`.
  - Prime Video (`com.amazon.firebat`): `https://app.primevideo.com/detail?gti=…`.
  - Crunchyroll: solo `crunchyroll://`; con `https://` abre su menú.
  - Disney+: `disneyplus://`, o `https://www.disneyplus.com/`.

## Por probar

- Que Crunchyroll abra `crunchyroll://series/<id>` y `crunchyroll://watch/<id>` en la serie y el capítulo.
- Que Disney+ abra `disneyplus://www.disneyplus.com/browse/entity-…` en el título.
- Qué apps publican su sesión de reproducción (Netflix, Crunchyroll, Prime Video, SmartTube, Xuper Hydra) y con qué datos.
- Entradas: en Fire OS cambiar de entrada es solo de Amazon (`TUNE_INPUT`), así que SalaTV pulsa por ADB la tecla HDMI 1–4. Hay que ver si la tele responde.
- Que `input keyevent 223` apague la tele y no solo la imagen («¿Sigues ahí?»).
- Qué botones del mando llegan al vigía.
