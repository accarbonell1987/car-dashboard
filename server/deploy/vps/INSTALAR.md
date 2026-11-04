# CAROBD en el VPS compartido

Sigue el mismo diseño que Corehub (`core-platform/.claude/context/DEPLOYMENT.md`):
la imagen se construye en la Mac para `linux/amd64`, se sube a GHCR y el VPS solo la baja.
Nada de CAROBD toca a los siete clientes, a Corehub ni a CarStatus.

| | Valor |
|---|---|
| Dominio | `carobd.guay.pro` (A record a la IP del VPS) |
| Puerto | `127.0.0.1:4201` (rango 42xx; 30xx es de clientes y 41xx de Corehub) |
| Red | `carobd-prod`, propia (CAROBD no habla con Corehub) |
| Usuario del host | `carobd`, sin contraseña, en el grupo `docker`, con su propio login a GHCR |
| Carpeta | `/opt/carobd/prod` (compose, `.env`, `env/api.env`, `data/`) |
| Script de deploy | `/opt/carobd/deploy-vps.sh`, de root, no editable por `carobd` |
| vhost | `zz-carobd` (empieza con `zz-` para no quitarle el servidor por defecto a `guay-gestion`) |
| Imagen | `ghcr.io/accarbonell1987/car-dashboard/server:prod-<sha de 40>`, nunca `latest` |

## 1. En la Mac (una vez)

```bash
# Llave propia de esta app, sin passphrase para que el deploy no pida nada.
ssh-keygen -t ed25519 -N '' -C carobd-deploy -f ~/.ssh/carobd_deploy

# Token de GitHub (classic) con write:packages para subir la imagen desde la Mac:
echo <TOKEN_ESCRITURA> | docker login ghcr.io -u accarbonell1987 --password-stdin

# Y otro token con SOLO read:packages: es el que queda guardado en el VPS.
```

## 2. En el VPS, como root (lo corres tú)

DNS primero: el A record de `carobd.guay.pro` apuntando a la IP del VPS.

Copia la carpeta `server/deploy/vps/` al VPS con tu acceso de root y corre el script ahí.
Antes de cada cambio hace respaldo en `/root/respaldos-carobd/<fecha>/`, corre con `set -euo pipefail`,
se niega si el puerto o el dominio ya están en uso, y solo recarga nginx si `nginx -t` pasa.

```bash
# Desde la Mac, en car-dashboard-source:
scp -r server/deploy/vps <TU_ACCESO_ROOT>:/root/carobd-instalar

# En el VPS, como root:
cd /root/carobd-instalar
DOMINIO=carobd.guay.pro PUERTO=4201 \
LLAVE_PUB="<contenido de ~/.ssh/carobd_deploy.pub>" \
GHCR_USUARIO=accarbonell1987 GHCR_TOKEN=<TOKEN_SOLO_LECTURA> \
bash preparar-vps-root.sh
```

Imprime dos tokens (pantalla y móvil). Guárdalos: van en **Ajustes › Cuenta y sync** de cada app.

Revisar a los vecinos después del reload:

```bash
for d in gestion panel recuerdos dashboard-usil dashboard-taxit dashboard-tiomono dashboard-kitadol; do
  printf '%-30s %s\n' "$d.guay.pro" "$(curl -s -o /dev/null -w '%{http_code}' https://$d.guay.pro --max-time 10)"
done
```

Lo normal: `gestion` responde 302 y los otros seis 200.

## 3. Primer deploy, desde la Mac

```bash
VPS=<IP_DEL_VPS> DOMINIO=carobd.guay.pro server/deploy/vps/desplegar.sh
```

Corre las pruebas del servidor, construye la imagen `linux/amd64`, la sube a GHCR, manda el compose
por la llave restringida y prueba CAROBD y los siete sitios. Solo silencio o 502/503/504 en un vecino
cuenta como culpa nuestra.

## 4. HTTPS (root, una vez, después del primer deploy)

```bash
certbot --nginx -d carobd.guay.pro
nginx -t && systemctl reload nginx
```

Luego vuelve a correr el paso 3 o solo las pruebas, y en las apps pon `https://carobd.guay.pro`.

## Vuelta atrás

```bash
SHA=<sha anterior> VPS=<IP_DEL_VPS> DOMINIO=carobd.guay.pro server/deploy/vps/desplegar.sh --sin-construir
```

El script del VPS además guarda `compose.yml.prev` y `.env.prev`, y vuelve solo a la versión anterior
si la nueva no arranca o no responde en `/salud` en 60 segundos.

Si el vhost causara problemas (root): `rm /etc/nginx/sites-enabled/zz-carobd && nginx -t && systemctl reload nginx`.

## Qué puede hacer la llave si se filtra

Solo correr `/opt/carobd/deploy-vps.sh`: sin shell, sin reenvío de puertos, sin pty. El script exige un tag
`prod-<sha de 40>`, una imagen de `ghcr.io/accarbonell1987/car-dashboard/server`, puertos solo en
127.0.0.1 y montajes solo dentro de `/opt/carobd/prod`, y rechaza `privileged`, `docker.sock`,
`network_mode: host`, `cap_add` y `devices`.
