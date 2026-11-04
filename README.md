# CAROBD

Sistema para dueños de vehículos: un tablero en vivo en la pantalla del auto, el registro de mantenimientos en el móvil y una nube propia que los mantiene sincronizados, aunque no siempre haya Internet.

Por ahora el vehículo es fijo: Hyundai Accent 2016 1.6 CRDi diésel manual, con un adaptador Vgate vLinker BM (ELM327 por Bluetooth clásico).

## Estructura

| Carpeta | Qué es |
|---|---|
| `core/` | Kotlin Multiplatform compartido por todo (servidor, Android e iPhone): modelo de datos, catálogo de PIDs OBD-II, decodificador, cálculo de marcha, plan de mantenimiento, reglas del garaje y de sincronización, y un simulador del Accent. |
| `screen/` | App Android para la pantalla del auto (horizontal, 1280x720). Grilla de 6x3 gauges editable, modo demo con el simulador y conexión al ELM327 por Bluetooth. |
| `mobile/` | App del teléfono en Compose Multiplatform: la misma interfaz para **iPhone y Android** (y una vista previa de escritorio). Vehículo, plan de mantenimiento con lo que falta por km y por fecha, y registro de servicios. |
| `mobile/iosApp/` | Proyecto de Xcode que envuelve la app para iPhone. |
| `server/` | API en Ktor con Postgres para el VPS. `POST /sync` recibe y entrega cambios; `GET /salud` responde `ok`. |
| `server/deploy/` | Compose local para pruebas y `vps/`: despliegue en el VPS compartido (Postgres, API, respaldo diario; nginx del VPS hace de proxy). |

## Compilar

Requiere JDK 17. Las apps (pantalla y móvil) requieren además el Android SDK; lo más fácil es instalar Android Studio.

```bash
# Todo
./gradlew build

# Solo core y servidor, sin Android SDK
./gradlew -Pcarobd.android=false :core:jvmTest :server:test

# Vista previa de la app móvil en una ventana del Mac
./gradlew :mobile:run

# APKs de depuración
./gradlew :screen:assembleDebug :mobile:assembleDebug
```

### App del móvil en el iPhone

Necesitas Xcode (App Store) y haber abierto antes el proyecto en Android Studio una vez, para que exista `local.properties` con la ruta del Android SDK.

1. Abre `mobile/iosApp/iosApp.xcodeproj` en Xcode.
2. Elige un simulador de iPhone arriba y pulsa ▶. La primera compilación tarda varios minutos porque Gradle compila Kotlin para iOS.
3. Para tu iPhone real: pon tu Team ID en `mobile/iosApp/Configuration/Config.xcconfig` (basta una cuenta gratuita de Apple), conecta el teléfono y elígelo como destino.

### App del móvil en Android

En Android Studio elige la configuración `mobile` y un emulador, y pulsa ▶.

### Verificar en local con Docker (lo normal)

Todo se prueba primero en tu máquina. Solo necesitas Docker; el script usa una imagen con JDK 17 y el Android SDK.

```bash
scripts/verificar-local.sh            # pruebas + APKs de pantalla y móvil
scripts/verificar-local.sh rapido     # solo pruebas de core y servidor
scripts/verificar-local.sh servidor   # Postgres + API en http://localhost:8080
scripts/verificar-local.sh parar      # baja el servidor local
```

La CI de GitHub Actions es **solo manual** (Actions > CI > Run workflow), para no gastar minutos.

## Sincronización

Cada registro tiene un id UUID creado en el dispositivo, `updatedAt` y `deletedAt`. Los dispositivos guardan todo localmente y, cuando hay red, hacen `POST /sync` con sus cambios y la fecha de su última sincronización. Gana la edición más reciente, los borrados son lógicos, y para el odómetro gana la lectura más alta. Reenviar el mismo cambio no duplica nada.

- **Pantalla:** guarda en `datos.json` (carpeta privada de la app, escritura atómica). Graba un viaje cada vez que el motor pasa de 400 rpm y lo cierra tras 60 s apagado o al desconectarse el vLinker; el modo demo no graba. Sincroniza al abrir, cada 15 minutos y al terminar cada viaje. El botón ☁ de la barra muestra cuántos cambios faltan subir y abre la configuración (URL del servidor y token).
- **Móvil:** el mantenimiento, los servicios y el odómetro van a la misma base local (la versión anterior se importa sola al actualizar). Muestra los últimos viajes que subió la pantalla. Sincroniza al abrir, cada 5 minutos y después de cada cambio.
- **Odómetro:** el último dato firme (inicial, corrección a mano o servicio) más los km de los viajes posteriores. Nunca baja.
- **Probar contra el servidor local:** `scripts/verificar-local.sh servidor` y en la pantalla (emulador, build debug) poner `http://10.0.2.2:8080` con el token `local`.

## Desplegar el servidor en el VPS

El VPS es compartido con sistemas de clientes: CAROBD escucha solo en `127.0.0.1`, usa su propia red,
usuario y vhost (`zz-carobd`), y se despliega con una llave que solo puede correr su script.
La imagen se construye en la Mac para `linux/amd64` y se sube a GHCR; nunca se construye en el VPS.
Los pasos están en [`server/deploy/vps/INSTALAR.md`](server/deploy/vps/INSTALAR.md).

```bash
VPS=<ip> DOMINIO=carobd.guay.pro server/deploy/vps/desplegar.sh
```

## Fases

0. Base del monorepo.
1. Probar el enlace con el vLinker: Car Scanner en la pantalla, y Bluetooth con el teléfono emparejado a la vez.
2. Tablero: mover y redimensionar gauges, varios tableros, modo noche.
3. Guardado local y sincronización entre pantalla, móvil y VPS.
3.5. Interfaz de la pantalla tipo launcher: barra de estado, pestañas Inicio, Tablero, Diagnóstico, Vehículo y Ajustes.
4. Diagnóstico con el vLinker: leer y borrar códigos de falla (modos 03, 07 y 04).
5. PIDs de Hyundai (modo 22) y prueba del BCM para puertas y luces.
6. Varios vehículos: selector de marca, modelo, año y color, con su perfil de señales.
