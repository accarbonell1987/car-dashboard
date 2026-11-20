#!/usr/bin/env python3
"""
Exploración completa y de SOLO LECTURA del auto con un ELM327 (Bluetooth clásico, /dev/cu.OBDII).
Sin dependencias. No escribe nada en el auto: solo usa servicios de lectura
(01, 03, 07, 09, 0A, 1A, 21, 22, 3E) y escucha pasiva del bus.

Uso (contacto puesto; mejor con el motor encendido):
  python3 scripts/elm327-explorar.py --salida ../capturas/elm327-exploracion.txt
  python3 scripts/elm327-explorar.py --solo estandar,ecus      # fases: estandar, ecus, motor, bus
  ../capturas/.venv/bin/python scripts/elm327-explorar.py --ble OBDII --salida ...   # por BLE (macOS)
"""
import argparse, os, select, sys, termios, time

NOMBRES = {
    0x01: "Estado MIL/DTC", 0x04: "Carga motor", 0x05: "Refrigerante", 0x0B: "MAP", 0x0C: "RPM",
    0x0D: "Velocidad", 0x0F: "Temp admisión", 0x10: "MAF", 0x11: "Acelerador", 0x13: "Sensores O2",
    0x1C: "Norma OBD", 0x1F: "Tiempo encendido", 0x21: "Dist. con MIL", 0x23: "Presión rail",
    0x2C: "EGR ordenada", 0x2D: "Error EGR", 0x2F: "Nivel combustible", 0x30: "Arranques desde borrado",
    0x31: "Dist. desde borrado", 0x33: "Barométrica", 0x41: "Monitores ciclo", 0x42: "Voltaje módulo",
    0x45: "Acelerador relativo", 0x46: "Temp ambiente", 0x49: "Pedal D", 0x4A: "Pedal E",
    0x4C: "Acelerador ordenado", 0x51: "Tipo combustible", 0x5C: "Temp aceite", 0x5E: "Consumo L/h",
    0x61: "Par pedido %", 0x62: "Par real %", 0x63: "Par referencia", 0x65: "Entradas aux",
    0x67: "Refrigerante (2)", 0x68: "Temp admisión (2)", 0x69: "EGR (2)", 0x6B: "Temp EGR",
    0x6F: "Presión entrada turbo", 0x70: "Control boost", 0x71: "VGT", 0x73: "Presión escape",
    0x74: "RPM turbo", 0x77: "Temp intercooler", 0x78: "Temp gases escape B1", 0x7A: "DPF B1",
    0x7C: "Temp DPF", 0x7F: "Tiempo motor total", 0x83: "NOx", 0x8D: "Pedal G", 0x9D: "Consumo g/s",
    0xA6: "Odómetro",
}

salida_f = None


def log(*a):
    s = " ".join(str(x) for x in a)
    print(s, flush=True)
    if salida_f:
        salida_f.write(s + "\n"); salida_f.flush()


def abrir(puerto, baudios):
    fd = os.open(puerto, os.O_RDWR | os.O_NOCTTY | os.O_NONBLOCK)
    at = termios.tcgetattr(fd)
    at[0] = 0; at[1] = 0; at[2] = termios.CS8 | termios.CREAD | termios.CLOCAL; at[3] = 0
    at[4] = at[5] = getattr(termios, f"B{baudios}", termios.B38400)
    termios.tcsetattr(fd, termios.TCSANOW, at)
    termios.tcflush(fd, termios.TCIOFLUSH)
    return fd


def puente_ble(nombre, caracteristica="0000fff1-0000-1000-8000-00805f9b34fb"):
    """Bridges the adapter's BLE characteristic (write + notify) to a pseudo-terminal; returns its path.
    Needs bleak (capturas/.venv). Same read-only commands, only the transport changes."""
    import asyncio, threading
    from bleak import BleakScanner, BleakClient
    maestro, esclavo = os.openpty()
    listo, fallo = threading.Event(), []

    async def correr():
        dispositivo = await BleakScanner.find_device_by_name(nombre, timeout=20)
        if not dispositivo:
            fallo.append(f"no encontré '{nombre}' por BLE"); listo.set(); return
        async with BleakClient(dispositivo, timeout=20) as cliente:
            await cliente.start_notify(caracteristica, lambda _, d: os.write(maestro, bytes(d)))
            cola = asyncio.Queue()
            asyncio.get_running_loop().add_reader(maestro, lambda: cola.put_nowait(os.read(maestro, 1024)))
            listo.set()
            while True:
                datos = await cola.get()
                for i in range(0, len(datos), 20):
                    await cliente.write_gatt_char(caracteristica, datos[i:i + 20], response=True)

    def hilo():
        try:
            asyncio.run(correr())
        except Exception as e:
            fallo.append(str(e)); listo.set()

    threading.Thread(target=hilo, daemon=True).start()
    if not listo.wait(45) or fallo:
        raise OSError(fallo[0] if fallo else "BLE no conectó a tiempo")
    puente_ble.esclavo = esclavo  # keep the slave end open for the whole run
    return os.ttyname(esclavo)


def leer_hasta_prompt(fd, limite):
    datos = b""
    fin = time.time() + limite
    while time.time() < fin:
        r, _, _ = select.select([fd], [], [], 0.1)
        if r:
            try:
                t = os.read(fd, 1024)
            except BlockingIOError:
                continue
            if not t:
                break
            datos += t
            if b">" in t:
                break
    return datos


def cmd(fd, c, limite=4.0):
    """Envía un comando y devuelve la lista de líneas de respuesta."""
    os.write(fd, (c + "\r").encode())
    d = leer_hasta_prompt(fd, limite)
    if b">" not in d:
        # sin prompt: espera un poco más para no desincronizar
        d += leer_hasta_prompt(fd, 2.0)
    t = d.decode(errors="replace").replace(">", "").replace("\r", "\n")
    return [l.strip().replace(" ", "") for l in t.split("\n") if l.strip() and l.strip() != c]


def es_hex(s):
    try:
        bytes.fromhex(s); return len(s) % 2 == 0
    except ValueError:
        return False


def tramas(lineas):
    """Con ATH1: agrupa por ID de CAN y reensambla ISO-TP. Devuelve {id: [payload, ...]}."""
    res, parcial = {}, {}
    for l in lineas:
        if len(l) < 5 or not es_hex(l[3:]) or not es_hex("0" + l[:3]):
            continue
        cid = l[:3].upper(); d = bytes.fromhex(l[3:])
        if not d:
            continue
        tipo = d[0] >> 4
        if tipo == 0:
            res.setdefault(cid, []).append(d[1:1 + (d[0] & 0x0F)])
        elif tipo == 1 and len(d) >= 2:
            parcial[cid] = [((d[0] & 0x0F) << 8) | d[1], bytearray(d[2:])]
        elif tipo == 2 and cid in parcial:
            parcial[cid][1] += d[1:]
    for cid, (total, buf) in parcial.items():
        res.setdefault(cid, []).append(bytes(buf[:total]))
    return res


def pedir(fd, c, limite=4.0):
    lin = cmd(fd, c, limite)
    return tramas(lin), lin


def hx(b):
    return b.hex().upper()


def ascii_de(b):
    return "".join(chr(x) if 32 <= x < 127 else "." for x in b)


def dtc(b):
    letras = "PCBU"
    out = []
    for i in range(0, len(b) - 1, 2):
        if b[i] == 0 and b[i + 1] == 0:
            continue
        out.append(f"{letras[b[i] >> 6]}{(b[i] >> 4) & 3}{b[i] & 0xF:X}{b[i + 1]:02X}")
    return out


def filtro_obd(fd):
    cmd(fd, "ATSH7DF"); cmd(fd, "ATCF7E8"); cmd(fd, "ATCM7F8")


def fase_estandar(fd):
    log("\n==================== FASE 1: OBD-II estándar ====================")
    filtro_obd(fd)
    soportados = {}
    for base in range(0x00, 0xE0, 0x20):
        r, lin = pedir(fd, f"01{base:02X}", 8.0 if base == 0 else 3.0)
        log(f"01{base:02X} -> {lin}")
        sigue = False
        for cid, ps in r.items():
            for p in ps:
                if len(p) >= 6 and p[0] == 0x41 and p[1] == base:
                    bits = int.from_bytes(p[2:6], "big")
                    for i in range(32):
                        if bits & (1 << (31 - i)):
                            soportados.setdefault(base + i + 1, set()).add(cid)
                    if bits & 1:
                        sigue = True
        if not sigue:
            break
    log("\nPIDs Modo 01 soportados:", " ".join(f"{p:02X}" for p in sorted(soportados)))
    log("\n-- Lectura de cada PID soportado --")
    for p in sorted(soportados):
        if p % 0x20 == 0:
            continue
        r, lin = pedir(fd, f"01{p:02X}")
        for cid, ps in r.items():
            for d in ps:
                log(f"01{p:02X} {NOMBRES.get(p, '?'):24} [{cid}] {hx(d[2:]) if len(d) > 2 else hx(d)}")
        if not r:
            log(f"01{p:02X} {NOMBRES.get(p, '?'):24} {lin}")

    log("\n-- Modo 09 (información del vehículo) --")
    for c in ["0900", "0902", "0904", "0906", "0908", "090A", "090B"]:
        r, lin = pedir(fd, c, 5.0)
        if r:
            for cid, ps in r.items():
                for d in ps:
                    log(f"{c} [{cid}] {hx(d)}  '{ascii_de(d[2:])}'")
        else:
            log(f"{c} -> {lin}")

    log("\n-- Códigos de falla --")
    for c, nombre in [("03", "Confirmados"), ("07", "Pendientes"), ("0A", "Permanentes")]:
        r, lin = pedir(fd, c, 5.0)
        if r:
            for cid, ps in r.items():
                for d in ps:
                    cuerpo = d[2:] if len(d) >= 2 else b""
                    log(f"{c} {nombre:12} [{cid}] {hx(d)} -> {dtc(cuerpo) or 'ninguno'}")
        else:
            log(f"{c} {nombre:12} -> {lin}")

    log("\n-- Modo 06 (resultados de monitores) --")
    r, lin = pedir(fd, "0600", 4.0)
    log(f"0600 -> {lin}")


def fase_ecus(fd):
    log("\n==================== FASE 2: módulos que responden en el bus ====================")
    cmd(fd, "ATCF700"); cmd(fd, "ATCM700")
    cmd(fd, "ATAT0"); cmd(fd, "ATST19")   # ~100 ms por dirección
    hallados = {}
    for addr in range(0x700, 0x800):
        if addr == 0x7DF:
            continue
        cmd(fd, f"ATSH{addr:03X}")
        for prueba in ("3E00", "1001"):
            r, lin = pedir(fd, prueba, 2.0)
            if r:
                hallados[addr] = r
                log(f"  {addr:03X} responde a {prueba}: " + ", ".join(f"{c}:{hx(p)}" for c, ps in r.items() for p in ps))
                break
            if any("ERROR" in l or "BUSBUSY" in l for l in lin):
                log(f"  {addr:03X} -> {lin}")
    log(f"\nMódulos encontrados: {' '.join(f'{a:03X}' for a in hallados) or 'ninguno'}")

    cmd(fd, "ATAT1"); cmd(fd, "ATST64")
    log("\n-- Identificación de cada módulo --")
    for addr in hallados:
        cmd(fd, f"ATSH{addr:03X}")
        log(f"\nMódulo {addr:03X}:")
        for c in ["22F190", "22F187", "22F18B", "22F189", "22F191", "22F193", "22F195", "22F197", "22F1A0", "22F100", "1A80", "1A90", "2101", "2180"]:
            r, lin = pedir(fd, c, 4.0)
            for cid, ps in r.items():
                for d in ps:
                    if d and d[0] != 0x7F:
                        log(f"  {c} [{cid}] {hx(d)}  '{ascii_de(d)}'")
    return hallados


def fase_motor(fd, rangos22):
    log("\n==================== FASE 3: datos propios de Hyundai en el motor (7E0) ====================")
    cmd(fd, "ATSH7E0"); cmd(fd, "ATCF7E8"); cmd(fd, "ATCM7FF"); cmd(fd, "ATAT1"); cmd(fd, "ATST32")

    def barrer(prefijo, ids, ancho):
        ok, mudos, negs = 0, 0, {}
        for i in ids:
            c = f"{prefijo}{i:0{ancho}X}"
            r, lin = pedir(fd, c, 4.0)
            ps = [p for v in r.values() for p in v]
            if not ps:
                mudos += 1
                if mudos >= 8 and ok == 0 and not negs:
                    log(f"  {prefijo}: el módulo no responde, se omite el resto del rango")
                    return
                continue
            mudos = 0
            d = ps[-1]
            if d[0] == 0x7F:
                nrc = d[2] if len(d) > 2 else 0
                negs[nrc] = negs.get(nrc, 0) + 1
                if nrc == 0x11 and negs[nrc] >= 4 and ok == 0:
                    log(f"  {prefijo}: servicio no soportado (NRC 11)")
                    return
            else:
                ok += 1
                log(f"  {c} ({len(d)} bytes) {hx(d)}")
        log(f"  {prefijo}: {ok} respuestas válidas; negativas por código: " + (", ".join(f"{k:02X}x{v}" for k, v in negs.items()) or "ninguna"))

    log("\n-- Servicio 21 (bloques de datos estilo Hyundai/Kia) --")
    barrer("21", range(0x00, 0x100), 2)
    for alto in rangos22:
        log(f"\n-- Servicio 22, identificadores {alto:02X}00 a {alto:02X}FF --")
        barrer("22", range(alto << 8, (alto << 8) + 0x100), 4)


def fase_bus(fd, segundos):
    log("\n==================== FASE 4: escucha pasiva del bus CAN ====================")
    cmd(fd, "ATAR"); cmd(fd, "ATCF000"); cmd(fd, "ATCM000"); cmd(fd, "ATH1"); cmd(fd, "ATCAF0")
    os.write(fd, b"ATMA\r")
    datos = b""
    fin = time.time() + segundos
    while time.time() < fin:
        r, _, _ = select.select([fd], [], [], 0.1)
        if r:
            try:
                datos += os.read(fd, 4096)
            except BlockingIOError:
                pass
    os.write(fd, b"\r")
    datos += leer_hasta_prompt(fd, 3.0)
    cmd(fd, "ATCAF1")
    ids = {}
    lineas = datos.decode(errors="replace").replace(">", "").replace("\r", "\n").split("\n")
    lleno = any("BUFFER" in l for l in lineas)
    for l in lineas:
        l = l.strip().replace(" ", "")
        if len(l) >= 5 and es_hex("0" + l[:3]) and es_hex(l[3:]):
            e = ids.setdefault(l[:3].upper(), [0, set()])
            e[0] += 1
            if len(e[1]) < 4:
                e[1].add(l[3:].upper())
    log(f"Tramas capturadas en {segundos}s: {sum(v[0] for v in ids.values())}, IDs distintos: {len(ids)}" + ("  (BUFFER FULL: el clon perdió tramas)" if lleno else ""))
    for cid in sorted(ids):
        n, ej = ids[cid]
        log(f"  {cid}  x{n:<5} ej: {'  '.join(sorted(ej))}")
    if not ids:
        log("  No se ve tráfico: probablemente hay una pasarela que aísla el puerto OBD. Crudo:", lineas[:6])


def main():
    global salida_f
    ap = argparse.ArgumentParser()
    ap.add_argument("--puerto", default="/dev/cu.OBDII")
    ap.add_argument("--baudios", type=int, default=38400)
    ap.add_argument("--salida", default=None)
    ap.add_argument("--solo", default="estandar,ecus,motor,bus")
    ap.add_argument("--rangos22", default="00,01,02,03,10,11,20,21,F0,F1")
    ap.add_argument("--segundos-bus", type=float, default=4.0)
    ap.add_argument("--ble", metavar="NOMBRE", default=None, help="usar BLE (servicio FFF0/FFF1) con el dispositivo de ese nombre, p. ej. OBDII; requiere bleak")
    ap.add_argument("--esperar-adaptador", type=int, default=60, help="segundos reintentando ATZ con el puerto abierto")
    a = ap.parse_args()
    if a.salida:
        salida_f = open(a.salida, "w")
    fases = a.solo.split(",")

    log(f"Exploración ELM327 {time.strftime('%Y-%m-%d %H:%M:%S')}  " + (f"BLE '{a.ble}'" if a.ble else f"puerto {a.puerto}"))
    try:
        if a.ble:
            a.puerto = puente_ble(a.ble)
        fd = abrir(a.puerto, a.baudios)
    except OSError as e:
        log(f"No pude abrir el puerto: {e}"); sys.exit(1)
    time.sleep(1.5); os.write(fd, b"\r"); time.sleep(0.3); termios.tcflush(fd, termios.TCIFLUSH)

    def arranque():
        # ponytail: keep the port open while the Bluetooth link comes up; closing it drops the link on macOS
        for _ in range(a.esperar_adaptador // 10 + 1):
            r = cmd(fd, "ATZ", 8.0)
            if any("ELM" in l for l in r):
                break
            time.sleep(2)
        log(f"ATZ    -> {r}")
        if not any("ELM" in l for l in r):
            log("El adaptador no responde."); sys.exit(2)
        for c in ["ATE0", "ATL0", "ATS0", "ATH1", "ATAT1", "ATSP6"]:
            log(f"{c:6} -> {cmd(fd, c, 3.0)}")
        log("ATI ->", cmd(fd, "ATI"), " ATRV ->", cmd(fd, "ATRV"))

    arranque()
    t0 = time.time()
    try:
        if "estandar" in fases: fase_estandar(fd)
        if "ecus" in fases: fase_ecus(fd)
        if "motor" in fases: fase_motor(fd, [int(x, 16) for x in a.rangos22.split(",") if x])
        if "bus" in fases: fase_bus(fd, a.segundos_bus)
    finally:
        try:
            os.write(fd, b"\r"); leer_hasta_prompt(fd, 1.0); cmd(fd, "ATZ", 5.0)
        except OSError:
            pass
        log(f"\nFin. Duración {time.time() - t0:.0f} s. Voltaje final:", cmd(fd, "ATRV"))
        os.close(fd)


if __name__ == "__main__":
    main()
