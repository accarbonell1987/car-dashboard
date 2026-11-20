# OBD: exploración con ELM327

Capturas reales del 2026-11-20 en un Hyundai Accent 2016 1.6 CRDi, con un adaptador
ELM327 v1.5 por BLE (servicio FFF0, característica FFF1). Los scripts están en `scripts/`.

El VIN está redactado: su número de serie se reemplazó por ceros (`KMHCU41UAGU000000`),
tanto en texto como en hexadecimal (respuestas a `0902` y `22F190`).

## Regla de seguridad

Solo hablar con el motor (`7DF`/`7E0`, modos `01`, `03`, `07`, `09`) y escuchar el bus
de forma pasiva (`ATMA` filtrado). **No usar la fase `modulos` de `elm327-explorar2.py`**:
después de ese barrido a otros módulos, los relojes del tablero dejaron de funcionar hasta
reiniciar el auto.

## Scripts

| Script | Qué hace |
| --- | --- |
| `elm327-prueba.py` | Prueba básica del adaptador y modo interactivo. |
| `elm327-explorar.py` | Primera exploración de solo lectura (servicios de diagnóstico y escucha pasiva). |
| `elm327-explorar2.py` | Segunda tanda: inventario, IDs del bus, PIDs dudosos, registro y módulos (**no usar `modulos`**). |
| `elm327-guiada.py` | Captura continua guiada: PIDs modo 01 al motor + escucha pasiva de IDs. |
| `elm327-carroceria.py` | Captura guiada de carrocería/tablero (cinturón, freno de mano, puertas, luces), solo escucha. |

## Capturas (`capturas/`)

| Archivo | Contenido |
| --- | --- |
| `elm327-exploracion.txt` | Resultado de `elm327-explorar.py`. |
| `elm327-exploracion2.txt` | Resumen de `elm327-explorar2.py`. |
| `elm327-exploracion2-inventario.txt` | PIDs soportados por el motor. |
| `elm327-exploracion2-ids.txt`, `-ids-b.txt` | IDs CAN escuchados y sus tramas. |
| `elm327-exploracion2-dudosos.txt` | PIDs con respuestas a revisar. |
| `elm327-exploracion2-registro.txt` | Registro de la sesión. |
| `elm327-exploracion2-modulo-*.txt` | Respuestas de cada módulo (fase `modulos`, la que afectó al tablero). |
| `elm327-ralenti.csv` | PIDs en ralentí. |
| `elm327-guiada.csv`, `.txt`, `-marcas.txt` | Captura guiada (aceleraciones) y marcas de cada paso. |
| `elm327-carroceria.csv`, `.txt`, `-marcas.txt` | Captura de carrocería y marcas de cada paso. |
