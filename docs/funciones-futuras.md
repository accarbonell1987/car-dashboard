# Funciones futuras: exploración de viabilidad

Exploración del 2026-11-21. No es un plan: para cada función dice cómo se puede hacer en la
pantalla Android del Accent, qué opción conviene y qué hay que probar antes de planificar.

## Lo primero: conocer la pantalla

Casi todo depende del hardware de la pantalla, y el repo no lo registra. Antes de planificar:

```bash
adb shell getprop ro.build.version.release   # versión de Android
adb shell getprop ro.board.platform          # chip (UIS7862, PX6, MT8227L...)
adb shell dumpsys SurfaceFlinger | rg GLES   # GPU y versión de OpenGL ES
adb shell cat /proc/meminfo | head -1        # RAM
adb shell pm list packages | rg -i "syu|bt|launcher|microntek"   # apps del fabricante
```

**Dato del 2026-11-21 (foto de "About Car Device"):** plataforma `CYA`, sistema
`UJC201-V1.1.22R8`, MCU `JCST_Q8_8T7`, dice Android 15, 4 GB de RAM y 64 GB. Sin canbox conectado
("Protocol Disconnected"). Según hilos de XDA, las UJC201 son un **MediaTek AC8257 de 4 núcleos**
y la versión de Android y la RAM que muestran suelen ser falsas (el Android real suele ser 10).
Falta verificar con CPU-Z o Device Info HW: nivel de API real, RAM real y GPU.
Fuente: https://xdaforums.com/t/ujc201-ac8257sv-latest-firmware.4792700/

**Confirmado con CPU-Z (2026-11-21):** MediaTek **AC8257**, 4 núcleos Cortex-A53 a 2,0 GHz, GPU
**PowerVR GE8300**, **OpenGL ES 3.2**, 1280x720, 64 GB. CPU-Z informa 4096 MB de RAM (1756 MB
libres). La versión de Android es incoherente: Ajustes dice 15, CPU-Z dice 12 con **nivel de API
35**, kernel 4.9 y parche de seguridad de 2021-10. El firmware falsea el nivel de API, así que el
Android real es más viejo de lo que declara (probablemente 10 u 11).

Consecuencias:

- **3D:** cumple el requisito de Filament (OpenGL ES 3.0), pero la GE8300 es una GPU de gama de
  entrada. Viable con un modelo liviano; sigue haciendo falta la prueba de cuadros por segundo.
- **API falseada:** el código que pregunta `SDK_INT >= N` puede elegir un camino que el sistema
  real no tiene. Cualquier API de Android 12 o posterior debe ir envuelta para no cerrar la app.

Con eso se decide: si el 3D es viable (hace falta OpenGL ES 3.0), cómo se hacen las llamadas
(depende de la app Bluetooth del fabricante) y cómo se comporta el launcher.

## Resumen

| # | Función | Viabilidad | Riesgo principal |
| --- | --- | --- | --- |
| 1 | Launcher que se abre siempre | Alta | El firmware puede volver al launcher de fábrica |
| 2 | Asistente de IA por voz | Alta | Micrófono de la pantalla e Internet en el auto |
| 3 | Modelo 3D con animaciones | Media | GPU de la pantalla; **las luces no están en el bus OBD** |
| 4 | Asistente: datos, llamadas, viajes | Datos y viajes: alta. Llamadas: media | Las llamadas dependen de la app Bluetooth del fabricante |
| 5 | Datos en la nube | Alta | Ya existe la base (`server/`, `POST /sync`) |

## 1. Launcher que se abra siempre

**Cómo:** agregar a `MainActivity` las categorías `HOME` y `DEFAULT` en el manifiesto. Android
ofrece entonces elegir CAROBD como pantalla de inicio; el botón Home y el arranque llevan a la app.

Lo que implica ser launcher:

- Hay que dar acceso a las demás apps (Maps, Waze, música, ajustes): una grilla de apps
  instaladas con `PackageManager.queryIntentActivities`. Inicio ya abre Maps y Waze.
- El botón Atrás no debe cerrar la app.
- Si la app falla al arrancar, la pantalla queda sin inicio: hay que dejar el launcher de fábrica
  instalado como alternativa y un acceso a los ajustes de Android.

**Alternativa más liviana:** no ser launcher y solo arrancar sola. Muchas pantallas chinas traen
en sus ajustes una opción de "app al encender". Un receptor de `BOOT_COMPLETED` propio no sirve
bien: desde Android 10 no puede abrir una pantalla sin permiso de superposición, y estas
unidades suelen dormir y despertar con el contacto en vez de reiniciar.

**Recomendación:** launcher real (`HOME`), con grilla de apps y salida a ajustes.

**A probar:** que el firmware respete la elección tras cortar el contacto (algunos FYT vuelven
al launcher de fábrica) y cuánto tarda la app en estar visible al dar contacto.

## 2. Asistente de IA por voz (modelo de OpenAI)

Dos formas de armarlo:

| Opción | Cómo funciona | A favor | En contra |
| --- | --- | --- | --- |
| **A. Realtime API** (voz a voz) | Un solo modelo (`gpt-realtime` o `gpt-realtime-mini`) escucha, razona, llama funciones y habla. Conexión WebRTC. | Latencia baja, se lo puede interrumpir, suena natural | Más caro por minuto; exige Internet estable |
| **B. Cadena de 3 pasos** | Transcripción → modelo de texto con funciones → voz sintetizada | Más barato, cada paso se cambia por separado, se puede leer el texto | 2-4 s de demora por turno |

**Recomendación:** empezar por A con el modelo mini. Para un conductor, la demora es lo que más
molesta. La app y el servidor se diseñan igual para ambas (las funciones son las mismas).

Piezas necesarias, cualquiera sea la opción:

- **La clave de OpenAI no va en el APK.** El servidor (`server/`, Ktor) pide a OpenAI un token
  efímero y se lo da a la pantalla. Así la clave queda en el VPS y se puede poner un tope de gasto.
- **Activación:** un botón grande en pantalla para empezar. La palabra de activación ("Hey...")
  es una segunda etapa: corre en el dispositivo (Porcupine u openWakeWord) y gasta CPU siempre.
- **Funciones (tools):** el modelo no toca el auto. Llama funciones que la app define:
  `leerDatosAuto()`, `navegarA(destino)`, `llamarA(contacto)`, `consultarMantenimiento()`.
- **Sin Internet:** el asistente no funciona. El tablero y las alertas siguen locales.

**Riesgos a probar:**

- **Micrófono:** el de estas pantallas suele ser malo y con ruido de ruta. Probar la
  transcripción andando; puede hacer falta un micrófono externo (muchas traen entrada).
- **Internet:** ¿la pantalla tiene SIM propia o usa el punto de acceso del teléfono?
- **Costo:** los precios publicados que encontré son contradictorios entre fuentes. Hay que
  leer la página oficial de precios y medir un uso real (por ejemplo 10 min de voz por día).

## 3. Modelo 3D del auto con animaciones

**Motor:** SceneView (usa Filament, de Google) se integra directo en Compose y carga modelos
glTF/GLB. Es la opción natural para `screen/`. Requiere OpenGL ES 3.0.

**Modelo:** hace falta un Accent RB (sedán) en GLB. Se compra (CGTrader, Sketchfab; revisar la
licencia) y se simplifica en Blender a menos de ~100 000 triángulos, con texturas PNG/JPEG
(Filament en Android no acepta WebP).

**Animaciones:**

- **Luces** (intermitentes, posición, baja, alta, stop, balizas): no son animaciones del modelo.
  Cada faro es un material aparte y la app le sube o baja el brillo (emisivo). El parpadeo es un
  temporizador en la app.
- **Lluvia y viento:** Filament no trae sistema de partículas. Lo más simple es dibujarlas en 2D
  con Canvas de Compose **encima** de la vista 3D: gotas según el clima (Inicio ya consulta
  Open-Meteo) y líneas de viento cuya velocidad sigue la del auto (PID `0D`).

**El problema de fondo: de dónde salen las luces.** Según `obd/codigos-can.md`, el bus del
puerto OBD **no trae** intermitentes, balizas, luces de posición ni altas/bajas (se probó el
2026-11-20 y no cambian). Opciones:

| Dato | Fuente posible | Estado |
| --- | --- | --- |
| Stop (pedal de freno) | Bus `0x081` bits 6-7, pasivo | A probar |
| Luces de posición | Cable ILL de la pantalla (atenúa el brillo de noche); si el firmware lo expone | A investigar en la pantalla |
| Marcha atrás | Cable de cámara de retroceso de la pantalla | A investigar |
| Intermitentes, balizas, altas, bajas | Bus de carrocería: segundo adaptador CAN en el conector del tablero, solo escucha | Requiere hardware y medir |

Sin el bus de carrocería, el 3D solo puede mostrar con datos reales: stop, velocidad (viento),
clima (lluvia) y puerta del conductor.

**Alternativa barata (2.5D):** imágenes del auto ya renderizadas desde un ángulo fijo, con las
luces como capas que se encienden. Funciona en cualquier GPU y se ve casi igual si el auto no rota.

**Recomendación:** antes de comprar un modelo, hacer una prueba de 1 día con SceneView y un GLB
cualquiera en la pantalla real, y medir los cuadros por segundo. Si va a menos de 30, usar 2.5D.

## 4. Asistente que interpreta datos, llama y planifica viajes

Son funciones (tools) del asistente del punto 2.

- **Interpretar datos del auto (alta):** `leerDatosAuto()` devuelve los valores actuales, las
  alertas activas (`Alertas.evaluar`) y los códigos de falla. El modelo los explica con la tabla
  de `obd/testigos-y-alertas.md` como contexto. Importante: las **alertas críticas no pasan por
  la IA**; siguen siendo reglas locales, y el asistente solo las explica.
- **Planificar viajes (alta):** `navegarA(destino)` abre Maps o Waze con un intent
  (`google.navigation:q=...`, `waze://?q=...&navigate=yes`); la app ya los abre desde Inicio.
  Con los viajes guardados y el consumo (`015E`) puede estimar combustible para una ruta. Para
  calcular rutas dentro de la app haría falta una API de mapas (Google Routes u OSRM, gratuita).
- **Llamadas (media):** la pantalla no tiene línea; la llamada sale por el teléfono vía
  Bluetooth manos libres, que maneja la app del fabricante. Caminos, del más simple al más complejo:
  1. Intent estándar `tel:` (`ACTION_DIAL` / `ACTION_CALL`): probar si el marcador del
     fabricante lo atiende.
  2. Intents propios de la app Bluetooth del fabricante (en FYT es `com.syu.bt`); no están
     documentados, se descubren con `adb`.
  3. Que la llamada la inicie la app del teléfono (`mobile/`) al recibir la orden. En iPhone el
     usuario tiene que confirmar con un toque.

  Los contactos también son un riesgo: la agenda que la pantalla baja del teléfono puede no
  estar disponible para otras apps.

**A probar primero:** `adb shell am start -a android.intent.action.DIAL -d tel:123` en la pantalla.

## 5. Almacenamiento en la nube

Ya existe: `server/` (Ktor + Postgres en el VPS) con `POST /sync`, y la pantalla sube viajes.
Lo que falta depende de qué se quiera guardar:

| Qué guardar | Cómo | Nota |
| --- | --- | --- |
| Viajes, mantenimiento, odómetro | Lo actual | Hecho |
| Telemetría detallada (una muestra por segundo de cada señal) | Tabla de muestras por viaje en Postgres, subida en lote y comprimida al terminar el viaje | ~1 MB por hora de manejo; TimescaleDB solo si crece mucho |
| Alertas y códigos de falla con fecha | Tabla nueva, sincronizada como el resto | Es el historial que el asistente necesita |
| Historial del asistente | Tabla nueva; guardar texto, no audio | Decidir cuánto tiempo se conserva |

**Recomendación:** seguir en el VPS propio. Firebase o Supabase no aportan nada que justifique
migrar, y el servidor además hace falta para el token del asistente (punto 2).

**A cuidar:** la telemetría con ubicación es un dato personal sensible; el respaldo diario del
VPS ya existe, pero conviene definir cuánto historial se guarda.

## 6. Navegación embebida (como Android Auto o CarPlay)

Pedido del 2026-11-21: Maps abre como app aparte y Waze no se instala en la pantalla.

**Por qué no se puede "meter" Google Maps dentro de la app:** Android no deja que una app
normal dibuje la pantalla de otra app dentro de un componente propio. Eso solo lo puede hacer
el sistema (es lo que usa Android Automotive). Android Auto y CarPlay funcionan distinto: el
teléfono dibuja el mapa y lo proyecta.

Opciones reales:

| Opción | Qué se ve | Costo | Esfuerzo |
| --- | --- | --- | --- |
| **A. Navigation SDK de Google** | El mapa y la guía giro a giro de Google Maps, con tráfico, **dentro** de un componente de la app | Gratis hasta 1 000 destinos por mes; requiere cuenta de Google Cloud con facturación | Medio |
| **B. Mapa abierto** (MapLibre + rutas OSRM/Valhalla) | Mapa propio con ruta; la guía por voz hay que armarla | Gratis | Alto, y sin tráfico |
| **C. Pantalla dividida** | Google Maps real en media pantalla y el tablero en la otra | Gratis | Bajo |
| **D. Proyección de Android Auto** (app Headunit Reloaded) | Android Auto del teléfono, pero en una app aparte | Pago único de la app | Nulo, pero no queda embebido |

**Recomendación:** A. Es lo único que da la experiencia pedida (mapa de Google embebido, con el
tablero alrededor) y para un solo auto entra en la franja gratuita. El asistente de voz puede
fijar el destino con una función `navegarA(destino)`.

**A probar antes:** que el SDK funcione en esta pantalla. Depende de Google Play Services (la
unidad tiene la versión 26.45) y el firmware falsea el nivel de API. Prueba de medio día con el
proyecto de ejemplo de Google.

**Waze:** falta saber qué mensaje da al instalar. Lo más probable es que Play Store la marque
"no compatible" porque la pantalla declara datos raros (densidad de 25 dpi, API 35 falso).
Waze no ofrece SDK para embeber, así que de todos modos no serviría para esto.

## Orden sugerido para planificar

1. **Relevar la pantalla** (comandos de arriba): desbloquea las decisiones de 1, 3 y 4.
2. **Launcher** (1): chico y sin dependencias.
3. **Nube: alertas y telemetría** (5): base para el asistente.
4. **Asistente por voz con datos del auto y navegación** (2 y 4): el de más valor.
5. **Llamadas** (4): después de probar los intents en la pantalla.
6. **3D** (3): al final; depende de la GPU y de conseguir las luces por el bus de carrocería.

## Fuentes

- OpenAI, modelo `gpt-realtime`: https://developers.openai.com/api/docs/models/gpt-realtime
- OpenAI, modelo `gpt-realtime-mini`: https://developers.openai.com/api/docs/models/gpt-realtime-mini
- OpenAI, latencia y costo de voz: https://developers.openai.com/api/docs/guides/voice-latency-cost?voice-api=realtime
- Navigation SDK de Google, precios: https://developers.google.com/maps/documentation/navigation/android-sdk/pricing
- SceneView (3D en Compose con Filament): https://github.com/sceneview/sceneview
- SceneView, problemas conocidos (WebP, hilo principal): https://sceneview.github.io/docs/troubleshooting/
- Launchers en pantallas Android de auto: https://android-headunits.com/android-head-unit-launcher/
- Llamadas por la app Bluetooth nativa en FYT (`com.syu.bt`): https://github.com/andreknieriem/open-headunit/issues/1170
- Intents de la app Bluetooth en unidades FYT/Joying: https://xdaforums.com/t/fyt-units-fythwonekey-non-rooted-app-to-assign-other-apps-to-the-unit-hardware-buttons.3912075/page-7
