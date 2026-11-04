#!/usr/bin/env bash
# Despliegue de CAROBD desde la Mac (sin CI de GitHub, sin construir en el VPS):
#   1. pruebas y jar del servidor
#   2. imagen linux/amd64 a GHCR con tag prod-<sha>
#   3. compose por stdin a la llave restringida (solo puede correr /opt/carobd/deploy-vps.sh)
#   4. pruebas de humo: CAROBD en su dominio y los siete sitios de clientes
#
# Uso:  VPS=<ip o host> DOMINIO=carobd.guay.pro server/deploy/vps/desplegar.sh
# Vuelta atrás:  SHA=<sha anterior> VPS=... DOMINIO=... server/deploy/vps/desplegar.sh --sin-construir
set -euo pipefail

: "${VPS:?falta VPS (ip o host del servidor)}"
: "${DOMINIO:?falta DOMINIO}"
LLAVE="${LLAVE:-$HOME/.ssh/carobd_deploy}"
IMAGEN='ghcr.io/accarbonell1987/car-dashboard/server'
RAIZ="$(cd "$(dirname "$0")/../../.." && pwd)"
cd "$RAIZ"

CONSTRUIR=1
[[ "${1:-}" == "--sin-construir" ]] && CONSTRUIR=0

if [[ "$CONSTRUIR" == 1 ]]; then
  # Solo se despliega lo que está en un commit: el tag tiene que decir exactamente qué corre.
  if [[ -n "$(git status --porcelain)" ]]; then
    echo "Hay cambios sin commit. Haz commit antes de desplegar." >&2
    exit 1
  fi
  SHA="$(git rev-parse HEAD)"
else
  : "${SHA:?con --sin-construir hace falta SHA=<sha a desplegar>}"
fi
[[ "$SHA" =~ ^[0-9a-f]{40}$ ]] || { echo "SHA inválido." >&2; exit 1; }
TAG="prod-$SHA"

if [[ "$CONSTRUIR" == 1 ]]; then
  echo "== Pruebas y jar del servidor"
  ./gradlew -Pcarobd.android=false --no-parallel :server:test :server:fatJar

  echo "== Imagen $IMAGEN:$TAG (linux/amd64)"
  docker buildx build --platform linux/amd64 \
    --label "org.opencontainers.image.source=https://github.com/accarbonell1987/car-dashboard" \
    --label "org.opencontainers.image.revision=$SHA" \
    -t "$IMAGEN:$TAG" --push server
fi

echo "== Deploy de $TAG en $VPS"
ssh -i "$LLAVE" -o IdentitiesOnly=yes -o BatchMode=yes "carobd@$VPS" "$TAG" < server/deploy/vps/compose.vps.yml

echo "== Pruebas de humo"
FALLO=0
codigo() { curl -s -o /dev/null -w '%{http_code}' --max-time 10 "$1" || true; }

c="$(codigo "https://$DOMINIO/health")"
[[ "$c" == "000" ]] && c="$(codigo "http://$DOMINIO/health")"   # antes de certbot solo hay http
printf '%-32s %s\n' "$DOMINIO (CAROBD)" "$c"
[[ "$c" =~ ^[23] ]] || { echo "  CAROBD no responde 2xx/3xx." >&2; FALLO=1; }

# Vecinos: solo silencio o 502/503/504 es culpa nuestra (rompimos el nginx compartido). Un 500 propio no cuenta.
for d in gestion panel recuerdos dashboard-usil dashboard-taxit dashboard-tiomono dashboard-kitadol; do
  c="$(codigo "https://$d.guay.pro")"
  printf '%-32s %s\n' "$d.guay.pro" "$c"
  case "$c" in 000|502|503|504) echo "  $d.guay.pro no responde bien: revisar nginx y volver atrás." >&2; FALLO=1 ;; esac
done

if [[ "$FALLO" == 1 ]]; then
  echo "Algo falló. Para volver atrás: SHA=<sha anterior> VPS=$VPS DOMINIO=$DOMINIO $0 --sin-construir" >&2
  exit 1
fi
echo "Todo bien: CAROBD $TAG en https://$DOMINIO"
