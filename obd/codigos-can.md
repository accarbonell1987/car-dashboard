# Códigos CAN y OBD del Accent 2016 (RB, 1.6 CRDi)

Investigación del 2026-11-21: qué documenta la comunidad para este auto y qué se
confirma con las capturas de `capturas/` (2026-11-20, ELM327 v1.5 por BLE, CAN 11 bit a 500 kbps).

## Resumen

- **El bus del puerto OBD es el bus de tren motriz (C-CAN).** Casi todos sus IDs están en el DBC
  `hyundai_i30_2014.dbc` de opendbc (plataforma GD, la misma generación que el RB). Las escalas
  coinciden con valores reales: cilindrada 1,6 L, voltaje 14,1 V, ralentí 780 rpm.
- **El odómetro está en `0x4F0` (CLU1), no en `0x5B0` (CLU12).** Este tablero usa el formato viejo
  (CLU1/CLU3). La captura da **91 268,7 km**. Hay que compararlo con el tablero para confirmarlo.
- **Nivel de combustible: no hay en el bus.** Ni `0x50C` (CLU13) ni el PID `012F` existen. CLU3
  (`0x4F1`) solo trae el aviso de reserva (bits 16-17), que siempre estuvo en 0 (tanque no bajo).
- **Varios datos nuevos salen gratis, solo escuchando:** temperatura exterior del A/C, temperatura
  del combustible, presión atmosférica, contador de consumo y testigo de dirección asistida.
- **Modo 22 en el motor:** no hay lista pública para el EDC17C57 de Hyundai. El barrido que se hizo
  cubrió pocos rangos. Seguir barriendo **el motor** (7E0) es compatible con la regla de seguridad.
- **ABS, airbag, puertas de acompañante y freno de mano:** no aparecen en este bus. ABS
  (`0x153`, `0x386`, `0x220`) no aparece nunca. El airbag manda `0x5A0`/`0x5A1`, pero sin datos útiles.

## Regla de seguridad

Todo lo marcado **Pasivo** se lee con `ATMA`/`ATCRA`, sin enviar nada al bus. Lo marcado
**Motor** es una consulta a 7E0 (permitida). Lo marcado **⚠ Otro módulo** requiere consultar
otro módulo y **está prohibido** por la regla del proyecto (ver `README.md`). Se lista solo
como referencia.

## IDs del bus decodificados

Bits en formato Intel (little-endian), contando desde el bit 0 del byte 0, como en el DBC.
"Validado" significa que el valor decodificado de la captura coincide con un dato real conocido.

| ID | Nombre DBC | Hz | Señal útil | Bits / escala | Valor en la captura | Estado |
| --- | --- | --- | --- | --- | --- | --- |
| `0x4F0` | CLU1 | 48 | **Odómetro** | 40-63, ×0,1 km | 91 268,7 km (fijo) | **Validado**: el tablero marcaba 91 294 km al día siguiente (2026-11-21) |
| `0x4F0` | CLU1 | 48 | Velocidad del tablero | 8-16, ×0,5 km/h | 0 (detenido) | Probar andando |
| `0x4F0` | CLU1 | 48 | Freno de mano | bit 7 | siempre 0, aunque se accionó | No cableado en este auto |
| `0x4F1` | CLU3 | 10 | Aviso de reserva | 16-17 | 0 | Probar con reserva |
| `0x4F1` | CLU3 | 10 | Luneta térmica | bit 5 | cambió a 1 | Validado |
| `0x4F1` | CLU3 | 10 | Autonomía (DTE) | 25-34, km | 0 | Este tablero no la manda |
| `0x690` | CLU2 | 10 | Puerta conductor | 6-7 | cambia | Validado (2026-11-20) |
| `0x690` | CLU2 | 10 | Cinturón conductor | 16-17 | cambia | Validado (2026-11-20) |
| `0x690` | CLU2 | 10 | Intermitentes, balizas, baúl, acompañante, luces | 24, 25, 49, 18-19, 54-55, 37-38 | sin cambios | No los manda este auto |
| `0x316` | EMS11 | 97 | RPM | 16-31, ×0,25 | 773-2579 | Validado (igual a `010C`) |
| `0x316` | EMS11 | 97 | Velocidad | 48-55, km/h | 0 | Probar andando |
| `0x329` | EMS12 | 97 | Refrigerante | 8-15, ×0,75 −48 °C | 88-96 °C | Validado |
| `0x329` | EMS12 | 97 | Pedal acelerador | 48-55, ×0,3906 % | 0 | Probar |
| `0x329` | EMS12 | 97 | Cilindrada | 56-63, ×0,1 L | 1,6 L | Validado |
| `0x545` | EMS14 | 97 | **Voltaje batería** | 24-31, ×0,101563 V | 13,7-14,3 V | Validado (igual a `ATRV`) |
| `0x545` | EMS14 | 97 | **Temp. combustible** | 48-55, ×0,75 −48 °C | 59-62 °C | Nuevo, plausible (retorno diésel) |
| `0x545` | EMS14 | 97 | **Presión atmosférica** | 3-7, ×10,73 +459 mmHg | 738 mmHg (984 hPa) | Nuevo, plausible |
| `0x545` | EMS14 | 97 | Contador de consumo (FCO) | 8-23, ×0,128 µl | 0-22, se mueve | Nuevo: derivar para L/h, validar contra `015E` |
| `0x545` | EMS14 | 97 | Testigo MIL / inmovilizador | bit 1 / bit 0 | 0 | Validado (sin fallas) |
| `0x350` | FATC | 48 | **Temp. exterior** | 24-31, ×0,5 −40 °C | 33 °C | Nuevo, comparar con un termómetro |
| `0x350` | FATC | 48 | Par del compresor A/C | 0-7, ×0,2 Nm | 11 Nm | Indica A/C encendido |
| `0x2A0` | EMS5 | 48 | Temp. admisión | 16-23, ×0,75 −48 °C | 43-45 °C | Plausible |
| `0x260` | EMS16 | 48 | Bujías de precalentamiento | bit 24 | 0 | Probar en arranque en frío |
| `0x260` | EMS16 | 48 | Estado del motor | 28-30 | 3 | Sin tabla de valores |
| `0x18F` | EMS_H12 | 48 | Ralentí objetivo | 40-47, ×10 rpm | 780 | Validado |
| `0x080` | EMS_DCT11 | 48 | RPM, par | 16-31 ×0,25 | igual a `0x316` | Duplicado |
| `0x0A0` | EngFrzFrm1 | 1 | Carga, refrigerante, RPM, velocidad | PIDs 04, 05, 0C, 0D | 26 %, 90 °C, 780 rpm | Validado (copia del modo 01) |
| `0x0A1` | EngFrzFrm2 | 1 | MAP, presión de rail | 32-39 kPa; 40-55 ×10 kPa | 101 kPa; 230-281 bar | Validado (igual a `0123`) |
| `0x382` | EMS9 | 48 | Temp. aceite, presión turbo | 32-39; 20-31 | 0 | El EDC17 no los llena |
| `0x5E4` | MDPS1 | 10 | Testigo de dirección asistida | 1-2 | 0 | Nuevo (0 = sin falla) |
| `0x5A0` | ACU11 | 1 | Airbag: falla, cinturones | 28-31, 34-35 | todo 0 | Ver nota |
| `0x5A1` | ACU12 | 1 | Número de serie del airbag | 0-63 | constante | Sin uso (no publicar) |
| `0x400` | CLU_CFG11 | — | Tipo de vehículo | 0-15 | — | Visto solo en una captura |
| `0x081` | EMS_DCT2 | 48 | Temp. de aire (`CR_Ems_IndAirTemp`) | 16-23, ×0,75 −48 °C | 44 °C | Validado (igual a `0x2A0`) |
| `0x081` | EMS_DCT2 | 48 | Freno (`BRAKE_ACT`) | 6-7 | 1 | Probar pisando el freno |
| `0x2C0`, `0x5A2` | — | 10 / 1 | — | — | constantes | Sin definición; en un Kia Rio 2013 siguen con el motor desconectado: no son del motor |
| `0x543` | — | 4 | — | — | siempre 0 | Sin definición en ninguna fuente; quizá propio del diésel (hipótesis) |

Nota sobre `0x5A0`: en el DBC moderno los cinturones y la falla del airbag van en bits 28-35. Aquí
siempre vale 0, incluso con el cinturón del conductor desabrochado. El cinturón sale de `0x690`.

## Modo 01 del motor: datos que ya responden y la app puede usar

Todos son **Motor** (7E0). Lectura del 2026-11-20 en ralentí:

| PID | Dato | Lectura |
| --- | --- | --- |
| `015E` | Consumo instantáneo | 0,25 L/h |
| `016D` | Presión de rail pedida / real | 273 / 238 bar |
| `0178` | Temp. de gases de escape | 110 °C |
| `017C` | Temp. del DPF | 110 °C |
| `017F` | Horas de motor totales / en ralentí | 4796 h / 1428 h (a confirmar el formato) |
| `0131` | Km desde el último borrado de fallas | 11 928 km |

`015C` (aceite), `016F`-`0174` (turbo, VGT), `017A` (presión DPF) y `0183` (NOx) responden, pero
siempre en 0: el ECU los anuncia y no los llena.

## Modo 22 (UDS) en el motor 7E0

ECU: Bosch EDC17C57 (respuesta de `22F180`). El servicio `21` no existe (NRC 11). Ya barridos
con NRC 31 en todo el rango: `0000-03FF`, `1000-11FF`, `2000-21FF`, `F000-F0FF`. Responden
`00FA` (identificación Bosch) y `F180-F199` (identificación).

No se encontró ninguna lista pública de DIDs de datos para el EDC17 de Hyundai/Kia (D4FB).
Las de otras marcas con EDC17 (Renault `22242C` hollín, Mazda `2228F4`) no sirven acá.

**Siguiente paso posible (Motor, permitido):** barrer `22 0400-0FFF`, `1200-1FFF`, `2200-2FFF`,
`3000-FFFF` en 7E0, por bloques de 256 y con pausas. Es lectura pura, pero lleva horas por BLE.

## Lo que el Autel lee del motor (2026-11-21)

Con el Autel (app en el teléfono), contacto en ON y motor apagado, el módulo **Engine** muestra
más de 150 datos en vivo. Todos salen del ECU del motor, así que **existe un protocolo extendido
en 7E0** que nuestro barrido no encontró (probablemente servicio `21` o `22` dentro de una sesión
de diagnóstico extendida, o DIDs `22` en rangos sin barrer).

Datos vistos, con su número en la lista del Autel. El usuario probó pedales y palanca: cambian.

| N.º | Dato | Valor visto | Uso en la app |
| --- | --- | --- | --- |
| 001 | Pedal del acelerador | 0 % | Gauge |
| 002 | Válvula de control de presión (rail) | 7 % | Diagnóstico |
| 003 | Masa de aire por cilindro | 0 mg/carrera | Diagnóstico (EGR, turbo) |
| 004 | Presión barométrica | 988 hPa | Coincide con `0x545` (984 hPa): valida esa escala |
| 005 | Interruptor de embrague | OFF / ON al pisar | Marcha, 3D |
| 007, 101 | Caja en punto muerto | ON / OFF | **Indicador de punto muerto** |
| 008, 009 | Interruptores de freno 1 y 2 | OFF / ON al pisar | **Luz de stop del 3D** |
| 006, 010 | A/C pedido, relé del compresor | OFF | Testigo de A/C |
| 097 | Sensor de presión de rail, crudo | 0,51 V | Diagnóstico |
| 104 | Embrague pisado más del 40 % | NO | — |
| 106, 107 | Cinturón y puerta del conductor | Sin abrochar, abierta | Ya los tenemos por `0x690` |
| 108 | Interruptor de capó | OPEN | A verificar: quizá no está instalado |
| 109-114 | Sensor de batería (AMS) | 6,00 V, 87,5 °C, 0 % | **No instalado**: valores por defecto, no usar |
| 098-099, 143-153 | ISG (start-stop), cambio D/N | NO / OFF | No aplica a este auto |
| 146 | Velocidad | 0 km/h | Ya la tenemos |
| 148 | Presión del cilindro maestro de freno | 0,0 bar | A verificar andando |

Los datos 011 a 096 y 115 a 142 no se capturaron: ahí deberían estar hollín y presión del DPF,
temperaturas de escape, turbo, EGR y correcciones de inyectores.

**Cómo obtener los comandos (pasivo):** con un cable en Y para el puerto OBD, conectar el Autel
y el ELM327 a la vez. El ELM327 escucha con `ATCRA` en `7E0` y `7E8` mientras el Autel muestra
Engine > Live data. Así se registran la petición y la respuesta de cada dato, sin que nuestro
adaptador envíe nada. Con el Autel, entrar **solo a Engine** para respetar la regla.

El menú del Autel lista además A/T, ABS, Airbag, TPMS, A/C, EPS, IMMO, SMK, BCM y CODE. Es la
lista genérica del modelo: no prueba que este auto tenga todos esos módulos.

## Modo 22 en el motor: bloque `ED xx` (encontrado el 2026-11-21)

El barrido de 7E0 dio NRC 31 en `0400-0FFF`, `1200-1FFF` y `2200-65FF`. **`22ED00-EDFF` responde
con 39 bloques** (`capturas/elm327-motor22-ED.txt`). Es el bloque diésel que la comunidad (OBDb)
documenta para Kia Ceed/Rio e i20/i30 CRDi. Falta barrer `6600-EBFF` y `EF00-FFFF`.

Byte n = posición después del eco `62 ED xx`, desde 0. Valores con contacto en ON, motor apagado.

| Comando | Dato | Bytes / fórmula | Valor | Estado |
| --- | --- | --- | --- | --- |
| `22ED03` / `22ED0A` | Odómetro del ECU | b49-52 / b0-3, ÷1000 km | 90 761,0 km | Contador propio del motor: 0,6 % menos que el tablero |
| `22ED09` | Tiempo de motor | b0-3, segundos | 4 799 h | Validado (coincide con `017F`) |
| `22ED01` | Temp. de combustible | b27 × 0,7843 − 50 °C | 28 °C | Plausible |
| `22ED01` | Presión de admisión (turbo) | b25 × 13,725 hPa | 961 hPa | Plausible (atmosférica 988); validar andando |
| `22ED01` | Par del motor | b30 × 2,3529 Nm | 2 Nm | Validar andando |
| `22ED03` | Temp. de escape 1 y 2 | b37, b38 × 4,7098 + 99 °C | 108 °C | Piso del sensor; validar andando |
| `22ED03` | Presión diferencial, regeneraciones, km desde la última | b36, b40-48, b53-55 | todo 0 | **Probablemente sin DPF** |
| `22ED1D`, `22ED94` | Hollín | b0-1 / b7 | 0 | Ídem |
| `22ED02`, `22ED07` | Temp. de aceite | b23 − 4; b19 × 0,7843 − 50 | sin dato | Sin sensor |

**Pedales y palanca (prueba guiada del 2026-11-21, contacto en ON, motor apagado).** Capturas en
`capturas/elm327-motor22-pedales-1.csv` y `-2.csv`, con los pasos en `-pedales-marcas.txt`.

| Comando | Dato | Byte y bit | Estado |
| --- | --- | --- | --- |
| `22ED01` | Interruptor de freno 1 | b12, bit 0 (`0x01`) | Validado: 1 al pisar |
| `22ED01` | Interruptor de freno 2 | b13, bit 6 (`0x40`) | Validado: 1 al pisar |
| `22ED01` | Embrague pisado | b13, bit 0 (`0x01`) | Validado en dos pisadas |
| `22ED01` | **Punto muerto** | b13, bit 3 (`0x08`) | Validado: 0 con primera puesta, 1 en punto muerto |
| `22ED27` | Embrague y punto muerto (copia) | b4, bit 6 y bit 3 | Validado, mismo comportamiento |
| `22ED04` | Interruptores de freno | b18 y b19 (0/1) | Validado |
| `22ED01` | Voltaje de batería | b19 × ~0,0816 V | Estimado: `0x99` = 12,5 V (`ATRV` daba 12,4-12,5); bajó al encender las luces de stop |
| `22ED01` | Pedal del acelerador | b4 (`0x00` suelto, `0xFF` a fondo) | **Candidato**: una sola muestra, de una respuesta truncada |

Sin ubicar todavía: presión de rail y masa de aire (hay que medir con el motor en marcha).

Con `22ED01` solo (un pedido, 34 bytes) la app obtiene freno, embrague, punto muerto, presión de
admisión, temperatura de combustible y voltaje.

**Fiabilidad del adaptador:** en las dos grabaciones el enlace BLE del clon se degradó cerca de
los 60-70 s de sondeo continuo (respuestas truncadas y luego mudo); hubo que reenchufarlo. La app
debe sondear más despacio que una vez por segundo y tolerar respuestas cortas.

## Lo que requiere hablar con otros módulos (⚠ prohibido por la regla)

| Dato | Cómo lo hace la comunidad | Módulo |
| --- | --- | --- |
| Odómetro | `22B002` en 7C6 (EV nuevos) | ⚠ Tablero 7C6 |
| Nivel de combustible | lectura del tablero (`21xx` / `22xx`) | ⚠ Tablero 7C6 |
| Velocidad de cada rueda, fallas ABS | `21xx` en el ABS | ⚠ ABS (no apareció en el bus) |
| Puertas, baúl, luces | BCM / body CAN | ⚠ BCM, otro bus |

En 7C6 (tablero) el barrido del 2026-11-20 encontró bloques `210C`, `210E`, `211A`, `2147`
con bytes que varían, pero fue el barrido que dejó los relojes sin funcionar. No repetir.

## Comunidad rusa (Solaris) y otras fuentes

- **Alarma StarLine A93 en Solaris 2014-16:** lee puertas, baúl, cierre centralizado, freno de mano
  y pedal de freno por CAN, pero la toma en el **conector 4 del tablero** (CAN-H amarillo, CAN-L
  negro), no en el OBD. El nivel de combustible no figura. Es probable que ese sea el bus de
  carrocería (B-CAN), que no llega al OBD: explicaría por qué el tablero enciende el testigo de la
  puerta del acompañante y `0x690` no cambia. Leerlo sería **pasivo**, pero requiere un segundo
  adaptador cableado al tablero.
- **Kia Ceed JD (misma generación):** C-CAN 500 kbps en OBD 6/14; B-CAN 100 kbps solo en un
  conector interno. Refuerza lo anterior.
- **Nivel de combustible en el Solaris:** los paneles digitales (SmartGauges) lo leen del
  aforador por entrada analógica, no por CAN. Pandora lo muestra en %, sin ID publicado.
- **Solaris AT 2013 (drive2):** confirma `0x316` RPM, `0x0A0` refrigerante, `0x0A1` MAP.
- **Car Scanner tiene un perfil "Hyundai: Accent IV RB 1.6 CRDI".** Es la vía más corta para
  descubrir los DIDs del motor: capturar las peticiones con cabecera 7E0. **⚠ Cuidado:** el
  perfil puede consultar ABS, airbag o tablero. No correrlo completo sin filtrar por módulo.
- **DPF Monitor PRO** (comercial) lee hollín, presión diferencial y última regeneración en el
  i30 GD / Ceed JD 1.6 CRDi por OBD: los datos existen en el motor, pero no publica los comandos.

## Fuentes

- opendbc, `hyundai_i30_2014.dbc` y `generator/hyundai/hyundai_can.dbc`:
  https://github.com/commaai/opendbc/tree/master/opendbc/dbc
- JejuSoul, PIDs para Hyundai/Kia eléctricos (no aplica al diésel):
  https://github.com/JejuSoul/OBD-PIDs-for-HKMC-EVs
- Lista de bases de IDs CAN por marca (incluye Solaris 2013 en ruso):
  https://github.com/iDoka/awesome-automotive-can-id
- Hilo de Torque sobre DPF en Kia Cee'd 1.6 2016 (sin PIDs confirmados):
  https://torque-bhp.com/community/main-forum/2016-kia-ceed-1-6-dpf-readings/

- Guía de instalación StarLine A93, Solaris 2014-16:
  https://support.starline.ru/uploaded/file/13469/9/35988/bbcbcfd5422ec7e5fbaec5029c22e93b.pdf
- Solaris AT 2013, IDs CAN: https://www.drive2.ru/b/522073135831319435/
- Combustible por CAN con Pandora: https://www.drive2.ru/l/497060620688949933/
- Panel SmartGauges (entradas analógicas): https://www.drive2.ru/b/482631454719737905/
- Buses del Kia Ceed JD: https://www.drive2.ru/l/632370919649581943/
- Trazas CAN de Kia Rio 2013 con y sin ECU (rusEFI):
  https://github.com/rusefi/rusefi_documentation/tree/master/OEM-Docs/Kia/2013-CAN-logs
- Perfiles de Car Scanner: https://www.carscanner.info/profile-database-changelog/
- DPF Monitor PRO: https://matway-electronics.pl/dpf-monitor-pro-dpf-fap-regeneration-monitor-obd2

## Pruebas pendientes (todas pasivas)

1. Odómetro: escuchar `0x4F0` y comparar con el tablero (anotar el número que muestra).
2. Velocidad: escuchar `0x4F0` y `0x316` andando, comparar con `010D`.
3. Temp. exterior: escuchar `0x350` con un termómetro al lado.
4. Reserva: escuchar `0x4F1` con el testigo de combustible encendido.
5. Consumo: comparar la derivada de FCO (`0x545`) con `015E`.
6. Bujías: escuchar `0x260` en un arranque en frío.
7. Freno de pie: escuchar `0x081` (bits 6-7) pisando y soltando el pedal.
