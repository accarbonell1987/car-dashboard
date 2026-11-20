# Emulador offline del ELM327

Emula el adaptador ELM327 v1.5 clon (MOCATEC) conectado al Hyundai Accent 2016 1.6 CRDi. Sirve para
desarrollar sin el auto: responde con los datos de las capturas reales de `../capturas/`.

Usa solo Python 3 (biblioteca estándar) y no habla con ningún adaptador real.

## Arrancar

Desde la raíz del repo:

```bash
python3 obd/emulador/elm327_emulador.py
```

| Opción | Por defecto | Qué hace |
| --- | --- | --- |
| `--host` | `127.0.0.1` | Interfaz TCP. Usa `0.0.0.0` para aceptar conexiones desde otra máquina. |
| `--puerto` | `35000` | Puerto TCP. Con `0` elige uno libre y lo muestra al arrancar. |
| `--escenario` | `ralenti` | Datos que reproduce: `ralenti`, `guiada` o `carroceria` (ver abajo). |
| `--velocidad` | `1` | Factor de tiempo de la reproducción: con `2` la captura avanza el doble de rápido. |
| `--latencia` | `60` | Milisegundos por petición OBD. 60 ms da unas 16 lecturas/s, como el clon. |
| `--pty` | — | Crea un pseudo-terminal serie en vez de escuchar por TCP. |

Atiende un cliente por vez. Cuando uno se desconecta, acepta al siguiente.

### Escenarios

| Escenario | PIDs de modo 01 | Tramas de `ATMA` |
| --- | --- | --- |
| `ralenti` | `elm327-ralenti.csv` | Las tramas `can` de `elm327-guiada.csv` |
| `guiada` | Filas `pid` de `elm327-guiada.csv` | Las tramas `can` de `elm327-guiada.csv` |
| `carroceria` | `elm327-ralenti.csv` (el motor estaba en marcha) | `elm327-carroceria.csv` |

Si falta el CSV de tramas, usa las de `elm327-exploracion2-ids*.txt`.

Cada serie se reproduce en bucle según su tiempo real. Entre muestras se repite el último valor capturado. Si un PID
soportado no aparece en la serie, devuelve su valor fijo de `elm327-exploracion.txt`.

## Conectarse

### Por TCP (`nc`)

```bash
nc localhost 35000
```

Escribe comandos como `ATZ`, `ATE0`, `0100` o `010C` y pulsa Enter. `nc` envía LF en vez de CR; para enviar CR,
usa `nc -c` en macOS o `nc -C` en Linux.

### Desde el emulador de Android

En el emulador de Android, la dirección `10.0.2.2` apunta al `localhost` del ordenador. La app se conecta a
`10.0.2.2:35000`.

### Desde los scripts de `scripts/` (puerto serie)

Arranca el emulador con `--pty`. Muestra la ruta del puerto, por ejemplo `/dev/ttys001`. Pásasela a los scripts con
`--puerto`:

```bash
python3 obd/emulador/elm327_emulador.py --pty --escenario carroceria
```

```bash
python3 scripts/elm327-explorar.py --puerto /dev/ttys001 --solo estandar,bus
```

Con `elm327-guiada.py` y `elm327-carroceria.py` pasa también `--csv` y `--salida` a un directorio temporal, para no
mezclar datos emulados con las capturas reales.

## Protocolo

- Comandos terminados en CR. Las mayúsculas y los espacios dan igual. Un CR solo repite el comando anterior.
- Por defecto: eco ON, linefeeds OFF, espacios ON y cabeceras OFF.
- **Comandos AT**:
  - `ATZ` devuelve un byte basura antes de `ELM327 v1.5`, como el clon. `ATI` devuelve `ELM327 v1.5`.
  - Implementados: `E0/E1`, `L0/L1`, `S0/S1`, `H0/H1`, `CAF0/CAF1`, `SP`/`SPA`, `DP`, `DPN`, `SH`, `CRA`, `CF`,
    `CM`, `AR`, `MA`, `PC`, `D` y `WS`.
  - `RV` devuelve `14.2V` con el motor en marcha y `12.6V` con 0 rpm.
  - Cualquier otro AT responde `OK`. Lo que no es AT ni hexadecimal responde `?`.
- **Protocolo**: con `SP0`, la primera petición responde `SEARCHING...` antes de los datos. Después, `DPN` devuelve
  `A6` (`6` si se fijó con `SP6`). Un protocolo fijo distinto de 6 responde `UNABLE TO CONNECT`.
- **Modo 01**: los bitmaps 00/20/40/60/80 son los reales. Los PIDs no soportados responden `NO DATA`.
- **Modo 09**: 00, 02, 04, 06, 0A y 0B, con tramas ISO-TP como el ELM real. Con `H0`, una línea de longitud
  (`014`) y luego líneas `0:`, `1:`... Con `H1`, tramas `7E8 10 …`, `7E8 21 …`. El VIN siempre es el redactado,
  `KMHCU41UAGU000000`.
- **Otros modos**: `03` → `43 00` y `07` → `47 00`. `0A`, `06` y el resto responden `NO DATA`.
- **Formato de las respuestas**:
  - Con `H1`, cada línea lleva `7E8` y el byte de longitud.
  - Con `CAF0`, el cliente pone el byte PCI (`02 01 0C`, como `elm327-guiada.py`) y recibe tramas de 8 bytes.
  - Los filtros `CRA`/`CF`/`CM` también se aplican a las respuestas.
  - Con `SH` a una cabecera distinta de `7DF`/`7E0`, responde `NO DATA`.
- **`ATMA`**:
  - Reproduce las tramas del escenario, cada ID a la frecuencia medida en `elm327-exploracion2-ids*.txt`.
  - Filtra por `CRA`, o por `CF`/`CM`.
  - Para con cualquier byte y responde `STOPPED` y `>`.
  - Si el cliente no lee a tiempo, responde `BUFFER FULL` y para.

## Consola

Mientras corre, el emulador lee comandos por su entrada estándar (la terminal donde lo arrancaste):

| Comando | Efecto |
| --- | --- |
| `puerta on\|off` | Bit 6 de b0 en la ID 690 (puerta del conductor). |
| `cinturon on\|off` | Bit 0 de b2 en la ID 690. |
| `ac on\|off` | Bit 0x40 de b1 en la ID 350. |
| `rpm N` | Fija `010C` y los bytes 2-3 de la ID 316 (little-endian, /4). |
| `vel N` | Fija `010D` y el byte 6 de la ID 316. |
| `<nombre> auto` | Quita ese override y vuelve a la captura, por ejemplo `rpm auto`. |
| `cuelgue` | Deja de responder, como el clon colgado, hasta `reset`. |
| `reset` | Sale del cuelgue. |
| `corte` | Cierra la conexión del cliente. Con `--pty`, solo reinicia la sesión. |
| `estado` | Muestra el escenario, la posición de la reproducción, los overrides y si está colgado. |

Los overrides afectan tanto a las respuestas de PID como a las tramas de `ATMA`.

## Tests

```bash
python3 -m unittest obd/emulador/test_emulador.py -v
```

## Lo que NO emula

- Otros módulos (71A, 7B3, 7C6, 7D2, 7D4) ni los servicios 21/22. Solo existe el motor en `7E8` con los modos 01,
  03, 07 y 09.
- Varios PIDs en una sola petición (`010C0D`). Solo responde al primero.
- El enlace BLE: no hay pérdidas ni BUFFER FULL espontáneos. `ATMA` sin filtro entrega todo el bus (~1000 tramas/s);
  el clon real se satura enseguida.
- Correlación entre IDs. Cada ID se capturó en ventanas separadas, así que cada una repite su propia serie.
- Contadores y checksums de las tramas: se repiten tal como se capturaron.
- `--velocidad` acelera la captura, pero no la frecuencia de envío de las tramas.
- Ajustes persistentes del adaptador entre clientes TCP: cada conexión empieza con los valores por defecto.
