# Testigos, sensores y alertas del Accent 2016 1.6 CRDi

Investigación del 2026-11-21. Objetivo: que la app avise cuando falla un sistema crítico y diga
qué tiene que hacer el conductor. Complementa `codigos-can.md` (IDs y bits).

Fuente de los testigos: manuales de Hyundai de la misma generación (i20, i30, Verna/RB). No se
pudo bajar el manual del Accent RB (manualslib bloquea las descargas automáticas): conviene
cotejar la lista con el manual de la guantera, sobre todo DPF (no todos los mercados lo traen).

## Cómo se puede detectar cada cosa

| Vía | Qué es | Regla de seguridad |
| --- | --- | --- |
| **Bus** | Escucha pasiva (ATMA) de un ID de `codigos-can.md` | Permitido |
| **PID** | Modo 01 al motor (7E0/7DF) | Permitido |
| **DTC** | Modo 03 / 07 al motor: códigos de falla confirmados y pendientes | Permitido |
| **No** | El dato va cableado al tablero o está en otro módulo | No se puede sin romper la regla |

## Testigos del tablero

Prioridad: **Crítico** = detenerse; **Atención** = ir al taller pronto; **Info** = aviso.

| Testigo | Prioridad | ¿La app lo puede ver? | Acción del conductor |
| --- | --- | --- | --- |
| Presión de aceite | Crítico | **No** (interruptor cableado al tablero; `015C` siempre 0) | Detenerse en lugar seguro y apagar ya. Revisar nivel de aceite. No seguir manejando. |
| Temperatura del motor (aguja) | Crítico | **Sí**: PID `05`, bus `0x329` | Ver regla de refrigerante abajo. |
| Carga de batería | Crítico | **Sí, indirecto**: voltaje en bus `0x545` / `ATRV` | Apagar consumos (A/C, luces extra) y manejar directo al taller; si además sube la temperatura, la correa puede estar cortada: detenerse. |
| Falla de motor (MIL) | Atención (Crítico si parpadea) | **Sí**: bus `0x545` bit 1, PID `01` bit 7 y **DTC** | Leer el código. Si el motor pierde fuerza o tironea, bajar velocidad e ir al taller. |
| Freno de mano / líquido de frenos | Crítico | **No** (freno de mano no está en este bus; líquido va cableado) | Si se enciende con el freno de mano bajo: detenerse, revisar nivel de líquido. No manejar sin frenos firmes. |
| ABS / EBD | Atención (Crítico si se enciende junto al de frenos) | **No** (ABS no aparece en este bus) | Frena normal pero sin ABS: aumentar distancia. Con el de frenos a la vez: detenerse. |
| Airbag | Atención | **No** (`0x5A0` siempre en cero) | Ir al taller; el airbag puede no saltar. |
| Dirección asistida (EPS) | Atención | **Sí**: bus `0x5E4` bits 1-2 (sin probar con falla) | La dirección se pone dura. Manejar despacio al taller. |
| Agua en el filtro de combustible | Atención | **Probable por DTC** (P2269 en EDC17); sin probar | Purgar el agua del filtro cuanto antes; si no, se dañan bomba e inyectores. |
| DPF (filtro de partículas) | Atención | **Parcial**: PID `017C` temp. DPF, `0178` gases, **DTC** P2002/P2463/P244x | Si parpadea: manejar 15-20 min a más de 60 km/h y 2000 rpm para regenerar. Si sigue: taller. |
| Precalentamiento (bujías) | Info | **Sí, a probar**: bus `0x260` bit 24 | Esperar a que se apague antes de arrancar. Si parpadea con el motor andando: taller. |
| Inmovilizador | Info / Atención | **Sí**: bus `0x545` bit 0 | Si sigue encendido y no arranca: probar con la otra llave. |
| Reserva de combustible | Atención | **Sí**: bus `0x4F1` bits 16-17 (sin probar con reserva) | Cargar combustible pronto. En diésel no conviene quedarse seco: hay que purgar. |
| Puerta abierta | Info | **Solo la del conductor**: bus `0x690` | Cerrar la puerta. |
| Cinturón | Info | **Solo el del conductor**: bus `0x690` | Abrocharse. |
| Luneta térmica | Info | **Sí**: bus `0x4F1` bit 5 | — |
| Intermitentes, luces altas, antinieblas, baúl | Info | **No** en este bus | — |

## Alertas por sensores (lo que el tablero NO avisa)

La app puede adelantarse al tablero con estos datos del motor. Umbrales iniciales para el D4FB;
son una perilla de calibración, se ajustan con lecturas reales.

| Señal | Fuente | Atención | Crítico | Acción del conductor |
| --- | --- | --- | --- | --- |
| Refrigerante | PID `05` / bus `0x329` | > 105 °C | > 112 °C | Atención: bajar el ritmo, apagar el A/C. Crítico: detenerse, dejar el motor en ralentí 1 min y apagar. No abrir la tapa caliente. |
| Refrigerante que no sube | PID `05` | < 70 °C tras 15 min andando | — | Termostato abierto: más consumo y desgaste. Taller sin urgencia. |
| Voltaje motor en marcha | bus `0x545` / `ATRV` | < 13,2 V | < 12,4 V o > 15,2 V | Bajo: no carga, apagar consumos, ir al taller. Alto: regulador dañado, puede dañar la batería. |
| Voltaje antes de arrancar | `ATRV` con RPM 0 | < 12,2 V | < 11,8 V | Batería débil: puede no arrancar en el próximo intento. |
| Presión de rail: pedida vs real | PID `016D` | diferencia > 15 % sostenida 5 s | diferencia > 30 % | Problema de alimentación (filtro tapado, bomba): ir al taller; si el motor corta, detenerse. |
| Temperatura de combustible | bus `0x545` | > 75 °C | > 85 °C | El motor reduce potencia. Bajar el ritmo. |
| Gases de escape / DPF | PID `0178` / `017C` | > 650 °C sin regeneración | > 800 °C | Riesgo de daño en turbo o DPF: aflojar el acelerador. |
| RPM | PID `0C` / bus `0x316` | > 4200 rpm | > 4600 rpm | Cambiar de marcha (manual 6 vel.). |
| Códigos de falla | **DTC** modo 03 / 07 | Pendiente (07) | Confirmado (03) con MIL | Mostrar código y descripción; si es de agua, DPF o rail, usar la acción de arriba. |

## Lo que conviene cambiar en `core/.../tablero/Alertas.kt`

1. **Quitar las reglas que nunca pueden saltar:**
   - `temp_aceite`: este EDC17 responde `015C`, pero siempre en 0.
   - `combustible`: el nivel no está en ningún lado. Reemplazarla por un testigo de reserva leído de `0x4F1`.
2. **Agregar un campo `accion` a `ReglaAlerta`**, aparte de `mensaje`, para mostrar qué hacer, como en las tablas.
3. **Soportar reglas binarias** (testigo encendido = 1) para MIL, EPS, inmovilizador y reserva, además de las de umbral.
4. **Agregar un histéresis o una duración mínima.** Ejemplo: rail > 15 % durante 5 s. Hace falta para no dar falsas alarmas en transitorios.
5. **Leer DTC (modo 03 y 07) al arrancar y cada pocos minutos.** Es la única vía para enterarse de agua en el combustible, del DPF y de fallas de sensores.

## Lo que queda fuera (y cómo se podría tener)

Presión de aceite, freno y líquido de frenos, ABS, airbag y nivel de combustible van cableados al
tablero o están en módulos que la regla prohíbe consultar. La única forma de verlos sin consultar
nada sería **escuchar el bus de carrocería**: un segundo adaptador CAN en el conector del tablero,
ver la nota de StarLine en `codigos-can.md`. Hay que medir primero si ese bus es distinto.

## Pruebas pendientes

1. Arranque en frío: `0x260` bit 24 (precalentamiento).
2. Con reserva encendida: `0x4F1` bits 16-17.
3. Leer DTC (`03`, `07`) con el motor andando, para tener la línea base sin fallas.
4. Andando 20 min en ruta: refrigerante estable y `016D` pedida vs real, para calibrar umbrales.

## Fuentes

- Manual Hyundai i20 (misma generación), testigos de diésel:
  https://www.manualslib.com/manual/1229771/Hyundai-I20.html?page=128
- Manual Hyundai i30, testigos: https://www.manualslib.com/manual/1256505/Hyundai-I30.html?page=208
- Hyundai/Kia DPF, códigos P2002 y P246C: https://dpf-cleaner.uk/hyundai-kia-dpf-problems/
- Regeneración DPF Hyundai/Kia: https://www.diagwiki.com/hyundai-kia-diesel-particulate-filter-dpf-regeneration
