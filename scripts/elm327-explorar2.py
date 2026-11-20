#!/usr/bin/env python3
"""
Segunda tanda de exploración, de SOLO LECTURA (servicios 01, 1A, 21, 22 y escucha pasiva con ATMA).
Reutiliza el transporte de elm327-explorar.py (puerto serie o --ble).

  ../capturas/.venv/bin/python scripts/elm327-explorar2.py --ble OBDII \
      --salida ../capturas/elm327-exploracion2.txt --csv ../capturas/elm327-ralenti.csv
  fases: inventario, ids, dudosos, registro, modulos
"""
import argparse, importlib.util, os, select, sys, time

_spec = importlib.util.spec_from_file_location("base", os.path.join(os.path.dirname(__file__), "elm327-explorar.py"))
base = importlib.util.module_from_spec(_spec); _spec.loader.exec_module(base)
pedir, hx, log, es_hex = base.pedir, base.hx, base.log, base.es_hex
_cmd_original = base.cmd


def cmd(fd, c, limite=4.0):
    """base.cmd that reports slow answers (a missing prompt usually means the ELM got out of sync)."""
    t0 = time.time()
    r = _cmd_original(fd, c, limite)
    if time.time() - t0 > 1.5:
        print(f"   [lento {time.time() - t0:.1f}s] {c} -> {r[:4]}", flush=True)
    return r


base.cmd = cmd  # pedir() goes through base.cmd too

# Observed in the first run (request -> response ID). 71A answers on 71B, not +8.
MODULOS = {0x7D4: 0x7DC, 0x7C6: 0x7CE, 0x7B3: 0x7BB, 0x7D2: 0x7DA, 0x71A: 0x71B}
PIDS_CLAVE = [0x0C, 0x0D, 0x04, 0x05, 0x0B, 0x0F, 0x10, 0x11, 0x23, 0x33, 0x42, 0x49, 0x5E, 0x61, 0x62]
PIDS_DUDOSOS = [0x24, 0x25, 0x4F, 0x50, 0x5C, 0x65, 0x67, 0x69, 0x6A, 0x6B, 0x6C, 0x6E, 0x6F, 0x70, 0x71, 0x72, 0x73,
                0x74, 0x76, 0x77, 0x79, 0x7A, 0x7B, 0x7C, 0x81, 0x82, 0x83, 0x85, 0x87, 0x89, 0x8A, 0x8F, 0x92, 0x93,
                0x94, 0x98, 0x99, 0x9C]


def escuchar(fd, segundos):
    """ATMA for `segundos`; returns ([(t, id, bytes)], buffer_full)."""
    os.write(fd, b"ATMA\r")
    resto, tramas, lleno = b"", [], False
    fin = time.time() + segundos
    while time.time() < fin:
        r, _, _ = select.select([fd], [], [], 0.05)
        if not r:
            continue
        try:
            resto += os.read(fd, 4096)
        except BlockingIOError:
            continue
        *lineas, resto = resto.replace(b"\n", b"\r").split(b"\r")
        ahora = time.time()
        for l in lineas:
            l = l.decode(errors="replace").strip().replace(" ", "")
            if "BUFFER" in l:
                lleno = True
            elif len(l) >= 5 and es_hex("0" + l[:3]) and es_hex(l[3:]):
                tramas.append((ahora, l[:3].upper(), bytes.fromhex(l[3:])))
    # A space stops ATMA; if ATMA already ended with NO DATA the ELM ignores it (a bare CR would repeat ATMA).
    os.write(fd, b" ")
    base.leer_hasta_prompt(fd, 3.0)
    sincronizar(fd)
    return tramas, lleno


def sincronizar(fd):
    """After ATMA over BLE late lines can swallow the next command; resync with ATI until it answers cleanly."""
    for _ in range(4):
        base.leer_hasta_prompt(fd, 0.3)
        if any("ELM" in l for l in cmd(fd, "ATI", 1.5)):
            return
    raise OSError("el adaptador dejó de responder (enlace BLE caído)")


def modo_monitor(fd, filtro, mascara):
    cmd(fd, "ATH1"); cmd(fd, "ATCAF0"); cmd(fd, f"ATCF{filtro:03X}"); cmd(fd, f"ATCM{mascara:03X}")


def fase_inventario(fd, segundos, desde=0, mascara=0x7C0):
    log("\n==================== FASE 1: inventario de IDs del bus ====================")
    ids = {}
    # ponytail: fixed-size groups; BUFFER FULL only loses frames, not IDs. Exact rates come from the per-ID phase.
    paso = 0x800 - mascara
    for filtro in range(desde & mascara, 0x800, paso):
        modo_monitor(fd, filtro, mascara)
        tramas, lleno = escuchar(fd, segundos)
        log(f"  grupo {filtro:03X}-{filtro + paso - 1:03X}: {len(tramas)} tramas, IDs {sorted({c for _, c, _ in tramas})}" + ("  BUFFER FULL" if lleno else ""))
        for t, cid, d in tramas:
            e = ids.setdefault(cid, {"n": 0, "largos": set(), "segundos": segundos})
            e["n"] += 1; e["largos"].add(len(d))
    cmd(fd, "ATCAF1")
    log(f"\nIDs distintos: {len(ids)}")
    for cid in sorted(ids):
        e = ids[cid]
        log(f"  {cid}  ~{e['n'] / e['segundos']:6.1f} Hz  largo {sorted(e['largos'])}")
    return sorted(ids)


def analizar(tramas):
    """Per byte position: constant, counter or changing (with min..max)."""
    datos = [d for _, _, d in tramas]
    out = []
    for i in range(max(len(d) for d in datos)):
        col = [d[i] for d in datos if len(d) > i]
        distintos = len(set(col))
        if distintos == 1:
            out.append(f"b{i}={col[0]:02X}")
            continue
        difs = {(b - a) & 0xFF for a, b in zip(col, col[1:])}
        bajos = {(b - a) & 0x0F for a, b in zip(col, col[1:])}
        if difs <= {1} or (len(bajos) == 1 and bajos != {0} and len({c >> 4 for c in col}) == 1):
            out.append(f"b{i}:contador")
        elif len({c & 0x0F for c in col}) == 1 or len({c >> 4 for c in col}) == 1:
            out.append(f"b{i}:{min(col):02X}..{max(col):02X}({distintos},medio byte fijo)")
        else:
            out.append(f"b{i}:{min(col):02X}..{max(col):02X}({distintos})")
    return " ".join(out)


def fase_ids(fd, ids, segundos):
    log("\n==================== FASE 2: escucha por ID ====================")
    cmd(fd, "ATH1"); cmd(fd, "ATCAF0")
    for cid in ids:
        cmd(fd, f"ATCRA{cid}")
        tramas, lleno = escuchar(fd, segundos)
        if not tramas:
            log(f"\n{cid}: sin tramas en {segundos}s"); continue
        t0 = tramas[0][0]
        log(f"\n{cid}: {len(tramas)} tramas en {segundos}s (~{len(tramas) / segundos:.1f} Hz)" + ("  BUFFER FULL" if lleno else ""))
        log(f"  bytes: {analizar(tramas)}")
        for t, _, d in tramas:
            log(f"  {t - t0:7.3f} {hx(d)}")
    cmd(fd, "ATCRA"); cmd(fd, "ATCAF1")


def fase_dudosos(fd):
    log("\n==================== FASE 3: PIDs dudosos, tramas crudas ====================")
    base.filtro_obd(fd); cmd(fd, "ATH1"); cmd(fd, "ATCAF1")
    for p in PIDS_DUDOSOS:
        r, lin = pedir(fd, f"01{p:02X}", 3.0)
        cmd(fd, "ATCAF0")
        crudo = cmd(fd, f"0201{p:02X}", 3.0)  # no auto-formatting: PCI byte by hand, frames exactly as on the bus
        cmd(fd, "ATCAF1")
        datos = [hx(d) for v in r.values() for d in v]
        log(f"01{p:02X} {base.NOMBRES.get(p, '?'):24} {lin} | sin formato {crudo}  ->  {datos}")


def fase_registro(fd, segundos, csv):
    log("\n==================== FASE 4: registro de PIDs clave ====================")
    base.filtro_obd(fd); cmd(fd, "ATH1"); cmd(fd, "ATCAF1"); cmd(fd, "ATAT2")
    n, t0 = 0, time.time()
    with open(csv, "w") as f:
        f.write("t,pid,datos\n")
        while time.time() - t0 < segundos:
            antes = n
            for p in PIDS_CLAVE:
                r, _ = pedir(fd, f"01{p:02X}1", 1.0)  # trailing 1: return after the first answer
                for v in r.values():
                    for d in v:
                        if len(d) > 2 and d[0] == 0x41 and d[1] == p:
                            f.write(f"{time.time() - t0:.3f},{p:02X},{hx(d[2:])}\n"); n += 1
            if n == antes:
                sincronizar(fd)
    cmd(fd, "ATAT1")
    dur = time.time() - t0
    log(f"{n} lecturas válidas en {dur:.0f}s = {n / dur:.1f} lecturas/s ({n / dur / len(PIDS_CLAVE):.2f} ciclos/s de {len(PIDS_CLAVE)} PIDs). CSV: {csv}")


def fase_modulos(fd):
    log("\n==================== FASE 5: barrido de lectura en otros módulos ====================")
    cmd(fd, "ATH1"); cmd(fd, "ATCAF1"); cmd(fd, "ATAT1"); cmd(fd, "ATST32"); cmd(fd, "ATCM7FF")
    rangos = [("21", range(0x00, 0x100), 2), ("1A", range(0x80, 0xA0), 2), ("22", range(0xF100, 0xF200), 4),
              ("22", range(0x0000, 0x0100), 4), ("22", range(0xB000, 0xB100), 4), ("22", range(0xC000, 0xC100), 4)]
    total = {}
    for pedido, respuesta in MODULOS.items():
        log(f"\nMódulo {pedido:03X} (responde {respuesta:03X}):")
        cmd(fd, f"ATSH{pedido:03X}"); cmd(fd, f"ATCF{respuesta:03X}")
        fc = [cmd(fd, c) for c in (f"ATFCSH{pedido:03X}", "ATFCSD300000", "ATFCSM1")]
        if any("?" in l for r in fc for l in r):
            log(f"  (el adaptador no acepta control de flujo manual: {fc})")
        total[pedido] = 0
        for prefijo, ids, ancho in rangos:
            ok, mudos, negs = 0, 0, {}
            for i in ids:
                c = f"{prefijo}{i:0{ancho}X}"
                r, _ = pedir(fd, c, 2.0)
                ps = [p for v in r.values() for p in v]
                if not ps:
                    mudos += 1
                    if mudos >= 8 and ok == 0 and not negs:
                        sincronizar(fd)  # raises if the link itself died
                        log(f"  {prefijo} {ids.start:0{ancho}X}..: sin respuesta, se omite el resto"); break
                    continue
                mudos = 0
                d = ps[-1]
                if d[0] == 0x7F:
                    nrc = d[2] if len(d) > 2 else 0
                    negs[nrc] = negs.get(nrc, 0) + 1
                    if nrc in (0x11, 0x7F) and negs[nrc] >= 4 and ok == 0:
                        log(f"  {prefijo}: servicio no soportado (NRC {nrc:02X})"); break
                else:
                    ok += 1
                    log(f"  {c} ({len(d)} bytes) {hx(d)}  '{base.ascii_de(d)}'")
            total[pedido] += ok
            log(f"  {prefijo} {ids.start:0{ancho}X}-{ids.stop - 1:0{ancho}X}: {ok} válidas; negativas: " + (", ".join(f"{k:02X}x{v}" for k, v in negs.items()) or "ninguna"))
        cmd(fd, "ATFCSM0")
    log("\nRespuestas válidas por módulo: " + ", ".join(f"{k:03X}={v}" for k, v in total.items()))


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--puerto", default="/dev/cu.OBDII")
    ap.add_argument("--baudios", type=int, default=38400)
    ap.add_argument("--ble", metavar="NOMBRE", default=None)
    ap.add_argument("--salida", default=None)
    ap.add_argument("--csv", default="../capturas/elm327-ralenti.csv")
    ap.add_argument("--solo", default="inventario,ids,dudosos,registro,modulos")
    ap.add_argument("--ids", default="", help="lista de IDs para la fase ids si no se corre el inventario (p. ej. 316,329)")
    ap.add_argument("--inventario-desde", type=lambda x: int(x, 16), default=0, help="primer ID (hex) del inventario")
    ap.add_argument("--inventario-mascara", type=lambda x: int(x, 16), default=0x7C0, help="máscara de grupo (hex): 7C0=64 IDs, 7E0=32, 7F0=16")
    ap.add_argument("--modulos", default="", help="subconjunto de módulos (hex, p. ej. 7D4,7C6)")
    ap.add_argument("--segundos-grupo", type=float, default=3.0)
    ap.add_argument("--segundos-id", type=float, default=3.0)
    ap.add_argument("--segundos-registro", type=float, default=60.0)
    a = ap.parse_args()
    if a.salida:
        base.salida_f = open(a.salida, "w")
    fases = a.solo.split(",")
    log(f"Exploración 2 ELM327 {time.strftime('%Y-%m-%d %H:%M:%S')}  " + (f"BLE '{a.ble}'" if a.ble else f"puerto {a.puerto}"))
    try:
        if a.ble:
            a.puerto = base.puente_ble(a.ble)
        fd = base.abrir(a.puerto, a.baudios)
    except OSError as e:
        log(f"No pude abrir el puerto: {e}"); sys.exit(1)
    time.sleep(1.0); os.write(fd, b"\r"); time.sleep(0.3)
    for _ in range(5):  # a stale reply can arrive first over BLE
        base.leer_hasta_prompt(fd, 0.5)
        r = cmd(fd, "ATZ", 8.0)
        if any("ELM" in l for l in r):
            break
    log("ATZ ->", r)
    if not any("ELM" in l for l in r):
        log("El adaptador no responde."); sys.exit(2)
    for c in ["ATE0", "ATL0", "ATS0", "ATH1", "ATAT1", "ATSP6"]:
        cmd(fd, c)
    log("ATRV ->", cmd(fd, "ATRV"))
    t0 = time.time()
    ids = [x.strip().upper() for x in a.ids.split(",") if x.strip()]
    if a.modulos:
        for k in [k for k in MODULOS if f"{k:03X}" not in a.modulos.upper().split(",")]:
            del MODULOS[k]
    pasos = [("inventario", lambda: ids.extend(i for i in fase_inventario(fd, a.segundos_grupo, a.inventario_desde, a.inventario_mascara) if i not in ids)),
             ("ids", lambda: fase_ids(fd, ids, a.segundos_id)),
             ("dudosos", lambda: fase_dudosos(fd)),
             ("registro", lambda: fase_registro(fd, a.segundos_registro, a.csv)),
             ("modulos", lambda: fase_modulos(fd))]
    try:
        for nombre, paso in pasos:
            if nombre in fases:
                try:
                    paso()
                except Exception as e:  # keep going with the remaining phases
                    log(f"\n!! fase {nombre} falló: {type(e).__name__}: {e}")
                    if "dejó de responder" in str(e):
                        sys.exit(3)
                    os.write(fd, b" "); base.leer_hasta_prompt(fd, 2.0); cmd(fd, "ATCAF1"); cmd(fd, "ATCRA")
    finally:
        log(f"\nFin. Duración {time.time() - t0:.0f} s. Voltaje final:", cmd(fd, "ATRV"))
        os.close(fd)


if __name__ == "__main__":
    main()
