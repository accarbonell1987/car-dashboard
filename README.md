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
| `server/deploy/` | Docker Compose con Postgres, la API, Caddy con TLS y un respaldo diario. |

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

## Desplegar el servidor en el VPS

```bash
./gradlew -Pcarobd.android=false :server:fatJar
cd server/deploy
cp .env.ejemplo .env   # completar DOMINIO, DB_PASSWORD y CAROBD_TOKENS
docker compose up -d --build
curl https://TU_DOMINIO/salud
```

`CAROBD_TOKENS` tiene la forma `token1:usuario1,token2:usuario2`. Cada dispositivo envía su token como `Authorization: Bearer ...`.

## Fases

0. Base del monorepo (esta).
1. Probar el enlace con el vLinker: Car Scanner en la pantalla, y Bluetooth con el teléfono emparejado a la vez.
2. Tablero: mover y redimensionar gauges, varios tableros, modo noche.
3. Room y sincronización real entre pantalla, móvil y VPS.
4. PIDs de Hyundai (modo 22) y prueba del BCM para puertas y luces.
