# CAROBD

Sistema para dueños de vehículos: un tablero en vivo en la pantalla del auto, el registro de mantenimientos en el móvil y una nube propia que los mantiene sincronizados, aunque no siempre haya Internet.

Por ahora el vehículo es fijo: Hyundai Accent 2016 1.6 CRDi diésel manual, con un adaptador Vgate vLinker BM (ELM327 por Bluetooth clásico).

## Estructura

| Carpeta | Qué es |
|---|---|
| `core/` | Kotlin puro compartido: modelo de datos, catálogo de PIDs OBD-II, decodificador, cálculo de marcha, plan de mantenimiento, reglas de sincronización y un simulador del Accent. |
| `screen/` | App Android para la pantalla del auto (horizontal, 1280x720). Grilla de 6x3 gauges editable, modo demo con el simulador y conexión al ELM327 por Bluetooth. |
| `mobile/` | App Android para el teléfono. Vehículo, plan de mantenimiento con lo que falta por km y por fecha, y registro de servicios. |
| `server/` | API en Ktor con Postgres para el VPS. `POST /sync` recibe y entrega cambios; `GET /salud` responde `ok`. |
| `server/deploy/` | Docker Compose con Postgres, la API, Caddy con TLS y un respaldo diario. |

## Compilar

Requiere JDK 17. Las apps Android requieren además el Android SDK.

```bash
# Todo
./gradlew build

# Solo core y servidor, sin Android SDK
./gradlew -Pcarobd.android=false :core:test :server:test

# APKs de depuración
./gradlew :screen:assembleDebug :mobile:assembleDebug
```

GitHub Actions corre las pruebas y deja los APK como artefactos de cada ejecución.

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
