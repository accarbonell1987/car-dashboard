#!/usr/bin/env python3
"""
Continuous capture for a guided session (steps marked separately in ../capturas/elm327-guiada-marcas.txt).
STRICT: only mode-01 PIDs to the engine (ATSH7DF) plus passive listening (ATMA filtered by ATCRA).
Nothing else is ever sent to the bus.

  ../capturas/.venv/bin/python scripts/elm327-guiada.py --ble OBDII
  stop: touch ../capturas/elm327-guiada.stop   (or Ctrl+C, or --max-segundos)
"""
import argparse, importlib.util, os, sys, time

_dir = os.path.dirname(os.path.abspath(__file__))
_spec = importlib.util.spec_from_file_location("ex2", os.path.join(_dir, "elm327-explorar2.py"))
ex2 = importlib.util.module_from_spec(_spec); _spec.loader.exec_module(ex2)
base, cmd, hx, log = ex2.base, ex2.cmd, ex2.hx, ex2.base.log

PIDS_RAPIDOS = [0x0C, 0x0D, 0x11, 0x49, 0x04]
PIDS_LENTOS = [0x0B, 0x10, 0x23, 0x5E, 0x61, 0x62, 0x42]
PRIORITARIOS = ["316", "329", "545", "260", "2A0", "2C0", "18F", "080", "350", "382", "4F0"]
OTROS = ["081", "0A0", "0A1", "4F1", "543", "5A0", "5A1", "5A2", "5E4", "690"]
LENTOS_ID = {"543", "5A0", "5A1", "5A2"}  # <= 5 Hz: need a longer window to catch a frame
# ponytail: priority IDs twice per round; fine for 15 s steps, add per-step weighting if a step needs a specific ID.
RONDA = [i for par in zip(PRIORITARIOS, PRIORITARIOS[1:] + PRIORITARIOS[:1]) for i in par] + OTROS
IDS_POR_CICLO = 3


def preparar(fd):
    os.write(fd, b"\r"); time.sleep(0.3)
    for _ in range(5):  # a stale reply can arrive first over BLE
        base.leer_hasta_prompt(fd, 0.5)
        r = cmd(fd, "ATZ", 8.0)
        if any("ELM" in l for l in r):
            break
    else:
        raise OSError(f"el adaptador no responde a ATZ: {r}")
    # ATCAF0 for the whole run: PIDs go with their PCI byte by hand (0201xx) and listening gets raw frames.
    for c in ["ATE0", "ATL0", "ATS0", "ATH1", "ATSP6", "ATAT2", "ATCSM1", "ATSH7DF", "ATCAF0", "ATCRA7E8"]:
        cmd(fd, c)
    return r


def conectar(a):
    puerto = base.puente_ble(a.ble) if a.ble else a.puerto
    fd = base.abrir(puerto, a.baudios)
    time.sleep(1.0)
    return fd, preparar(fd)


def leer_pid(fd, p, csv, t0):
    r, lin = ex2.pedir(fd, f"0201{p:02X}", 1.0)
    for v in r.values():
        for d in v:
            if len(d) > 2 and d[0] == 0x41 and d[1] == p:
                csv.write(f"{time.time():.3f},{time.time() - t0:.3f},pid,{p:02X},{hx(d[2:])}\n")
                return True
    return False


def escuchar_id(fd, cid, segundos, csv, t0):
    cmd(fd, f"ATCRA{cid}")
    tramas, lleno = ex2.escuchar(fd, segundos)  # raises OSError if the link died
    cmd(fd, "ATCRA7E8")
    for t, c, d in tramas:
        csv.write(f"{t:.3f},{t - t0:.3f},can,{c},{hx(d)}\n")
    return len(tramas), lleno


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--puerto", default="/dev/cu.OBDII")
    ap.add_argument("--baudios", type=int, default=38400)
    ap.add_argument("--ble", metavar="NOMBRE", default=None)
    ap.add_argument("--csv", default=os.path.join(_dir, "../../capturas/elm327-guiada.csv"))
    ap.add_argument("--salida", default=os.path.join(_dir, "../../capturas/elm327-guiada.txt"))
    ap.add_argument("--parar", default=os.path.join(_dir, "../../capturas/elm327-guiada.stop"))
    ap.add_argument("--max-segundos", type=float, default=600)
    ap.add_argument("--ventana", type=float, default=0.35, help="seconds of listening per fast ID")
    ap.add_argument("--ventana-lenta", type=float, default=1.1, help="seconds of listening per slow ID (<=5 Hz)")
    a = ap.parse_args()
    if os.path.exists(a.parar):
        os.remove(a.parar)
    base.salida_f = open(a.salida, "w")
    log(f"Captura guiada {time.strftime('%Y-%m-%d %H:%M:%S')}  " + (f"BLE '{a.ble}'" if a.ble else f"puerto {a.puerto}"))
    fd, r = conectar(a)
    log("ATZ ->", r, "| ATRV ->", cmd(fd, "ATRV"))
    csv = open(a.csv, "w", buffering=1)
    csv.write("epoch,t,tipo,id,datos\n")
    t0 = time.time()
    ok = sum(leer_pid(fd, 0x0C, csv, t0) for _ in range(2))
    log(f"010C -> {'OK' if ok else 'SIN RESPUESTA'}")
    log(f"REGISTRANDO desde {time.strftime('%H:%M:%S')} (epoch {t0:.3f})")
    ciclo, i_lento, i_id, fallos = 0, 0, 0, 0
    try:
        while time.time() - t0 < a.max_segundos and not os.path.exists(a.parar):
            try:
                pids = PIDS_RAPIDOS + [PIDS_LENTOS[i_lento % len(PIDS_LENTOS)]]
                i_lento += 1
                n_pid = sum(leer_pid(fd, p, csv, t0) for p in pids)
                resumen = []
                for _ in range(IDS_POR_CICLO):
                    cid = RONDA[i_id % len(RONDA)]; i_id += 1
                    n, lleno = escuchar_id(fd, cid, a.ventana_lenta if cid in LENTOS_ID else a.ventana, csv, t0)
                    resumen.append(f"{cid}:{n}" + ("!" if lleno else ""))
                if n_pid == 0:
                    ex2.sincronizar(fd)
                ciclo += 1; fallos = 0
                if ciclo % 10 == 0:
                    log(f"{time.strftime('%H:%M:%S')} t={time.time() - t0:5.0f}s ciclo {ciclo} pids {n_pid}/{len(pids)} {' '.join(resumen)}")
            except OSError as e:
                fallos += 1
                log(f"{time.strftime('%H:%M:%S')} !! ENLACE CAÍDO ({e}); reintento {fallos}")
                if fallos > 6:
                    raise
                try:
                    os.close(fd)
                except OSError:
                    pass
                time.sleep(min(2 * fallos, 8))
                try:
                    fd, _ = conectar(a)
                    log(f"{time.strftime('%H:%M:%S')} RECONECTADO")
                except OSError as e2:
                    log(f"   reconexión falló: {e2}")
    except KeyboardInterrupt:
        pass
    finally:
        log(f"FIN {time.strftime('%H:%M:%S')}  {time.time() - t0:.0f}s, {ciclo} ciclos")
        try:
            cmd(fd, "ATCRA"); cmd(fd, "ATCAF1"); os.close(fd)
        except OSError:
            pass
        csv.close()


if __name__ == "__main__":
    main()
