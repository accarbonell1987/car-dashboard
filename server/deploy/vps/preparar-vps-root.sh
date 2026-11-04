#!/usr/bin/env bash
# Preparación de CAROBD en el VPS compartido. La corre Alberto como root, UNA vez (ver INSTALAR.md).
# No toca nada de los clientes, de Corehub ni de CarStatus: crea el usuario carobd, /opt/carobd,
# la llave restringida y un vhost propio (zz-carobd), con respaldo y nginx -t antes del reload.
# Se corre desde una carpeta con este script, deploy-vps.sh y nginx-zz-carobd.conf:
#   DOMINIO=carobd.guay.pro PUERTO=4201 LLAVE_PUB="ssh-ed25519 AAAA... carobd-deploy" \
#   GHCR_USUARIO=accarbonell1987 GHCR_TOKEN=<token con solo read:packages> bash preparar-vps-root.sh
set -euo pipefail

: "${DOMINIO:?falta DOMINIO}"
: "${PUERTO:?falta PUERTO}"
: "${LLAVE_PUB:?falta LLAVE_PUB}"
: "${GHCR_USUARIO:?falta GHCR_USUARIO}"
: "${GHCR_TOKEN:?falta GHCR_TOKEN}"
AQUI="$(cd "$(dirname "$0")" && pwd)"
[[ -f "$AQUI/deploy-vps.sh" && -f "$AQUI/nginx-zz-carobd.conf" ]] || { echo "Faltan deploy-vps.sh o nginx-zz-carobd.conf junto a este script." >&2; exit 1; }

[[ "$(id -u)" == 0 ]] || { echo "Se corre como root." >&2; exit 1; }
[[ "$DOMINIO" =~ ^[a-z0-9.-]+$ ]] || { echo "Dominio inválido." >&2; exit 1; }
[[ "$PUERTO" =~ ^[0-9]{4}$ ]] || { echo "Puerto inválido." >&2; exit 1; }
[[ "$LLAVE_PUB" =~ ^ssh-ed25519\ [A-Za-z0-9+/=]+( .*)?$ ]] || { echo "La llave tiene que ser ssh-ed25519." >&2; exit 1; }
case "$PUERTO" in 30*|41*) echo "El rango 30xx es de los clientes y el 41xx de Corehub." >&2; exit 1 ;; esac

SELLO="$(date +%Y%m%d-%H%M%S)"
RESPALDO="/root/respaldos-carobd/$SELLO"
mkdir -p "$RESPALDO"
echo "Respaldo en $RESPALDO"

# 0) Comprobaciones: nada de esto debe existir ya.
if ss -ltnH | awk '{print $4}' | grep -Eq "[:.]$PUERTO\$"; then
  echo "El puerto $PUERTO ya está en uso. Elige otro del rango 42xx." >&2; exit 1
fi
if [[ -e /etc/nginx/sites-available/zz-carobd || -e /etc/nginx/sites-enabled/zz-carobd ]]; then
  echo "Ya existe un vhost zz-carobd; no lo piso." >&2; exit 1
fi
if grep -Rqs "server_name[^;]*\b$DOMINIO\b" /etc/nginx/sites-enabled/; then
  echo "Otro vhost ya atiende $DOMINIO; no sigo." >&2; exit 1
fi

# 1) Usuario propio. Su propio ~/.docker/config.json: el login a GHCR no pisa el de Corehub (usuario deploy).
if ! id carobd >/dev/null 2>&1; then
  useradd --create-home --shell /bin/bash carobd
fi
usermod -aG docker carobd
passwd -l carobd >/dev/null   # sin contraseña: solo entra por la llave restringida

# 2) Carpetas. Los secretos y los datos viven solo aquí.
install -d -o carobd -g carobd -m 750 /opt/carobd /opt/carobd/prod /opt/carobd/prod/env /opt/carobd/prod/data
install -d -m 700 /opt/carobd/prod/data/postgres
install -d -o carobd -g carobd -m 750 /opt/carobd/prod/data/respaldos

# 3) Script de deploy: de root y no editable por carobd, para que la llave no pueda cambiarlo.
install -m 755 "$AQUI/deploy-vps.sh" /opt/carobd/deploy-vps.sh
chown root:root /opt/carobd/deploy-vps.sh
chmod 755 /opt/carobd/deploy-vps.sh
chown root:carobd /opt/carobd
chmod 750 /opt/carobd

# 4) Secretos: clave de la base y un token por dispositivo. Se generan aquí y no salen del VPS salvo para ti.
if [[ ! -f /opt/carobd/prod/.env ]]; then
  DB_PASSWORD="$(openssl rand -hex 24)"
  printf 'API_PORT=%s\nDB_PASSWORD=%s\nCAROBD_TAG=\n' "$PUERTO" "$DB_PASSWORD" > /opt/carobd/prod/.env
  chown carobd:carobd /opt/carobd/prod/.env
  chmod 600 /opt/carobd/prod/.env
fi
if [[ ! -f /opt/carobd/prod/env/api.env ]]; then
  T_PANTALLA="$(openssl rand -hex 24)"
  T_MOVIL="$(openssl rand -hex 24)"
  printf 'CAROBD_TOKENS=%s:alberto,%s:alberto\n' "$T_PANTALLA" "$T_MOVIL" > /opt/carobd/prod/env/api.env
  chown carobd:carobd /opt/carobd/prod/env/api.env
  chmod 600 /opt/carobd/prod/env/api.env
  echo
  echo "Tokens (guárdalos; van en Ajustes > Cuenta y sync de cada app):"
  echo "  pantalla: $T_PANTALLA"
  echo "  móvil:    $T_MOVIL"
  echo
fi

# 5) Llave restringida: solo puede correr el script de deploy.
install -d -o carobd -g carobd -m 700 /home/carobd/.ssh
touch /home/carobd/.ssh/authorized_keys
cp -p /home/carobd/.ssh/authorized_keys "$RESPALDO/authorized_keys.carobd"
LINEA="command=\"/opt/carobd/deploy-vps.sh\",no-port-forwarding,no-agent-forwarding,no-X11-forwarding,no-pty $LLAVE_PUB"
grep -qxF "$LINEA" /home/carobd/.ssh/authorized_keys || printf '%s\n' "$LINEA" >> /home/carobd/.ssh/authorized_keys
chown carobd:carobd /home/carobd/.ssh/authorized_keys
chmod 600 /home/carobd/.ssh/authorized_keys

# 6) Login a GHCR como carobd, con un token de solo lectura de paquetes.
printf '%s' "$GHCR_TOKEN" | su - carobd -c "docker login ghcr.io -u '$GHCR_USUARIO' --password-stdin" >/dev/null
echo "GHCR: carobd quedó con sesión en ghcr.io"

# 7) nginx: vhost propio, respaldo de la carpeta, nginx -t antes del reload y vuelta atrás si falla.
cp -a /etc/nginx/sites-available "$RESPALDO/sites-available"
cp -a /etc/nginx/sites-enabled "$RESPALDO/sites-enabled"
sed "s/DOMINIO_CAROBD/$DOMINIO/; s/PUERTO_CAROBD/$PUERTO/" "$AQUI/nginx-zz-carobd.conf" > /etc/nginx/sites-available/zz-carobd
ln -s /etc/nginx/sites-available/zz-carobd /etc/nginx/sites-enabled/zz-carobd
if nginx -t; then
  systemctl reload nginx
  echo "nginx recargado con zz-carobd"
else
  echo "nginx -t falló: quito zz-carobd y no recargo." >&2
  rm -f /etc/nginx/sites-enabled/zz-carobd /etc/nginx/sites-available/zz-carobd
  nginx -t
  exit 1
fi

echo
echo "Listo. Falta: el primer deploy desde la Mac y luego certbot --nginx -d $DOMINIO"
