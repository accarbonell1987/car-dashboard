#!/usr/bin/env bash
# Verifica CAROBD en tu máquina con Docker, sin gastar minutos de GitHub Actions.
#
#   scripts/verificar-local.sh            pruebas de core y servidor + APKs de pantalla y móvil
#   scripts/verificar-local.sh rapido     solo pruebas de core y servidor (sin Android)
#   scripts/verificar-local.sh servidor   levanta Postgres + API en http://localhost:8080
#   scripts/verificar-local.sh parar      baja el servidor local
#
# La primera vez baja la imagen del Android SDK (unos GB) y las dependencias de Gradle;
# después quedan en caché en el volumen carobd-gradle y todo va mucho más rápido.
set -euo pipefail
cd "$(dirname "$0")/.."

IMAGEN_ANDROID="ghcr.io/cirruslabs/android-sdk:35"
# aapt2 de Android solo existe para Linux x86_64; en Mac con Apple Silicon Docker lo emula.
PLATAFORMA="linux/amd64"

gradle_en_docker() {
  docker run --rm --platform "$PLATAFORMA" \
    -v "$PWD":/proyecto -v carobd-gradle:/root/.gradle -w /proyecto \
    "$IMAGEN_ANDROID" ./gradlew --no-daemon "$@"
}

case "${1:-todo}" in
  todo)
    gradle_en_docker :core:jvmTest :server:test :screen:assembleDebug :mobile:assembleDebug
    echo
    echo "APKs listos:"
    ls -1 screen/build/outputs/apk/debug/*.apk mobile/build/outputs/apk/debug/*.apk
    ;;
  rapido)
    gradle_en_docker -Pcarobd.android=false :core:jvmTest :server:test
    ;;
  servidor)
    gradle_en_docker -Pcarobd.android=false :server:fatJar
    DB_PASSWORD=local CAROBD_TOKENS="local:alberto" \
      docker compose -f server/deploy/docker-compose.local.yml up -d --build
    echo "Esperando la API..."
    for _ in $(seq 1 30); do
      if curl -fs http://localhost:8080/health >/dev/null; then echo "API arriba: http://localhost:8080/health (token: local)"; exit 0; fi
      sleep 2
    done
    echo "La API no respondió; mira: docker compose -f server/deploy/docker-compose.local.yml logs api" >&2
    exit 1
    ;;
  parar)
    docker compose -f server/deploy/docker-compose.local.yml down
    ;;
  *)
    sed -n '2,9p' "$0"; exit 1 ;;
esac
