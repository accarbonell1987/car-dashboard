#!/usr/bin/env python3
"""
Guided body/dashboard capture (belt, handbrake, doors, indicators). Steps are marked separately in
../capturas/elm327-carroceria-marcas.txt.
STRICT: passive listening only (ATMA filtered by ATCRA). No PIDs, nothing is ever sent to the bus.

  ../capturas/.venv/bin/python scripts/elm327-carroceria.py --ble OBDII
  stop: touch ../capturas/elm327-carroceria.stop   (or Ctrl+C, or --max-segundos)
"""
import argparse, importlib.util, os, time

_dir = os.path.dirname(os.path.abspath(__file__))
_spec = importlib.util.spec_from_file_location("guiada", os.path.join(_dir, "elm327-guiada.py"))
g = importlib.util.module_from_spec(_spec); _spec.loader.exec_module(g)
ex2, cmd, hx, log = g.ex2, g.cmd, g.hx, g.log

# Listen groups: (ATCF filter, ATCM mask or None for an exact ATCRA ID, seconds). The adapter rejects X wildcards.
# Rates seen on 2026-11-20: 4F0 ~40 Hz, 545/18F ~75 Hz, 350 ~40 Hz, 4F1/0A0/0A1/5E4/690 ~9 Hz, 543 ~5 Hz, 5A0-5A2 1 Hz.
# 400/600 passes 4xx+5xx: 4F0 4F1 543 545 5A0 5A1 5A2 5E4 in one 1.2 s window (~130 frames/s).
CADA_CICLO = [("400", "600", 1.2), ("0A0", "7FE", 0.3), ("690", None, 0.35)]
# ponytail: 350/18F rotate one per cycle to keep the cycle short.
ROTATIVOS = [("350", None, 0.2), ("18F", None, 0.2)]


def escuchar(fd, filtro, mascara, segundos, csv, t0):
    comandos = ["ATCRA", f"ATCF{filtro}", f"ATCM{mascara}"] if mascara else [f"ATCRA{filtro}"]
    for c in comandos:
        if not cmd(fd, c, 2.0):  # the BLE link can go mute without an error: force a reconnect
            raise OSError(f"sin respuesta a {c}")
    tramas, lleno = ex2.escuchar(fd, segundos)  # raises OSError if the link died
    for t, c, d in tramas:
        csv.write(f"{t:.3f},{t - t0:.3f},can,{c},{hx(d)}\n")
    return f"{filtro}:{len(tramas)}" + ("!" if lleno else "")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--puerto", default="/dev/cu.OBDII")
    ap.add_argument("--baudios", type=int, default=38400)
    ap.add_argument("--ble", metavar="NOMBRE", default=None)
    ap.add_argument("--csv", default=os.path.join(_dir, "../../capturas/elm327-carroceria.csv"))
    ap.add_argument("--salida", default=os.path.join(_dir, "../../capturas/elm327-carroceria.txt"))
    ap.add_argument("--parar", default=os.path.join(_dir, "../../capturas/elm327-carroceria.stop"))
    ap.add_argument("--max-segundos", type=float, default=900)
    ap.add_argument("--solo", metavar="ID", help="listen only to this ID, almost continuously (2 s windows)")
    a = ap.parse_args()
    ciclo_fijo, rotativos = ([(a.solo, None, 2.0)], [None]) if a.solo else (CADA_CICLO, ROTATIVOS)
    if os.path.exists(a.parar):
        os.remove(a.parar)
    g.base.salida_f = open(a.salida, "a")  # append: a restart keeps earlier steps
    log(f"Captura carroceria {time.strftime('%Y-%m-%d %H:%M:%S')}  " + (f"BLE '{a.ble}'" if a.ble else f"puerto {a.puerto}"))
    fd, r = g.conectar(a)
    log("ATZ ->", r, "| ATRV ->", cmd(fd, "ATRV"))
    nuevo = not os.path.exists(a.csv)
    csv = open(a.csv, "a", buffering=1)
    if nuevo:
        csv.write("epoch,t,tipo,id,datos\n")
    t0 = time.time()
    log(f"REGISTRANDO desde {time.strftime('%H:%M:%S')} (epoch {t0:.3f})")
    n, fallos = 0, 0
    try:
        while time.time() - t0 < a.max_segundos and not os.path.exists(a.parar):
            try:
                tc = time.time()
                resumen = [escuchar(fd, *gr, csv, t0) for gr in ciclo_fijo + [rotativos[n % len(rotativos)]] if gr]
                n += 1; fallos = 0
                if n <= 3 or n % 5 == 0:
                    log(f"{time.strftime('%H:%M:%S')} t={time.time() - t0:5.0f}s ciclo {n} ({time.time() - tc:.1f}s) {' '.join(resumen)}")
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
                    fd, _ = g.conectar(a)
                    log(f"{time.strftime('%H:%M:%S')} RECONECTADO")
                except OSError as e2:
                    log(f"   reconexión falló: {e2}")
    except KeyboardInterrupt:
        pass
    finally:
        log(f"FIN {time.strftime('%H:%M:%S')}  {time.time() - t0:.0f}s, {n} ciclos")
        try:
            cmd(fd, "ATCRA"); cmd(fd, "ATCAF1"); os.close(fd)
        except OSError:
            pass
        csv.close()


if __name__ == "__main__":
    main()
