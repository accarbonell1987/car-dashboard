#!/usr/bin/env bash
# /opt/carobd/deploy-vps.sh: lo único que puede correr la llave de deploy de CAROBD.
# authorized_keys: command="/opt/carobd/deploy-vps.sh",no-port-forwarding,no-agent-forwarding,no-pty ssh-ed25519 ...
# Entrada: el compose por stdin y el tag (prod-<sha de 40>) en SSH_ORIGINAL_COMMAND.
set -euo pipefail

DIR=/opt/carobd/prod
IMAGEN='ghcr.io/accarbonell1987/car-dashboard/server'

TAG="${SSH_ORIGINAL_COMMAND:-}"
if [[ ! "$TAG" =~ ^prod-[0-9a-f]{40}$ ]]; then
  echo "Tag inválido. Se espera prod-<sha de 40 caracteres>." >&2
  exit 2
fi

cd "$DIR"
NUEVO="$DIR/compose.yml.nuevo"
RENDER="$DIR/compose.yml.render"
limpiar() { rm -f -- "$DIR/compose.yml.nuevo" "$DIR/compose.yml.render"; }
trap limpiar EXIT

# Como mucho 256 KB: un compose real pesa unos pocos KB.
head -c 262144 > "$NUEVO"
[[ -s "$NUEVO" ]] || { echo "No llegó ningún compose por stdin." >&2; exit 3; }

# 1) Validar el compose nuevo con el .env del servidor, antes de tocar el que corre.
if ! CAROBD_TAG="$TAG" docker compose -f "$NUEVO" --env-file .env config > "$RENDER" 2>/dev/null; then
  echo "El compose nuevo no es válido; el que está corriendo no se tocó." >&2
  exit 4
fi

# 2) Que no pida más de lo que esta app necesita: si la llave se filtra, no sirve para montar el host.
if grep -Eq 'privileged: true|docker\.sock|network_mode: host|pid: host|cap_add|devices:' "$RENDER"; then
  echo "El compose pide permisos que CAROBD no usa." >&2
  exit 5
fi
if grep -E '^\s+source: ' "$RENDER" | grep -Ev 'source: /opt/carobd/prod/' | grep -q . || grep -q '\.\./' "$RENDER"; then
  echo "El compose monta carpetas fuera de /opt/carobd/prod." >&2
  exit 5
fi
PUBLICADOS="$(grep -cE '^\s+published:' "$RENDER" || true)"
LOCALES="$(grep -cE '^\s+host_ip: 127\.0\.0\.1$' "$RENDER" || true)"
if [[ "$PUBLICADOS" != "$LOCALES" ]]; then
  echo "El compose publica un puerto fuera de 127.0.0.1." >&2
  exit 5
fi
if ! grep -q "image: $IMAGEN:$TAG" "$RENDER"; then
  echo "El compose no usa $IMAGEN:$TAG." >&2
  exit 5
fi

# 3) Bajar la imagen antes de cambiar nada: si no existe, lo que corre sigue igual.
docker pull -q "$IMAGEN:$TAG" > /dev/null

# 4) Reemplazar, guardando lo anterior para volver atrás.
[[ -f compose.yml ]] && cp -p compose.yml compose.yml.prev
cp -p .env .env.prev
cp -p "$NUEVO" compose.yml
if grep -q '^CAROBD_TAG=' .env; then
  sed -i "s/^CAROBD_TAG=.*/CAROBD_TAG=$TAG/" .env
else
  echo "CAROBD_TAG=$TAG" >> .env
fi

volver_atras() {
  echo "Falló el arranque; vuelvo a la versión anterior." >&2
  if [[ -f compose.yml.prev ]]; then
    cp -p compose.yml.prev compose.yml
    cp -p .env.prev .env
    docker compose up -d --remove-orphans || true
  fi
  exit 6
}

docker compose up -d --remove-orphans || volver_atras

# 5) Prueba de humo local: el API tiene que contestar.
PUERTO="$(grep '^API_PORT=' .env | cut -d= -f2)"
for _ in $(seq 1 30); do
  if curl -fsS --max-time 3 "http://127.0.0.1:$PUERTO/salud" > /dev/null 2>&1; then
    echo "OK: CAROBD $TAG respondiendo en 127.0.0.1:$PUERTO"
    exit 0
  fi
  sleep 2
done
volver_atras
