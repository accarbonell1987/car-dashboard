#!/usr/bin/env python3
"""Polls engine-ECU (7E0) mode 22 data blocks in a loop and logs them to CSV.

Read-only and engine-only. Used to find which bits change while the driver
presses a pedal or moves the gear lever: run it, perform the steps, diff the rows.

  ../capturas/.venv/bin/python scripts/elm327-motor22.py --ble OBDII --segundos 90 \
      --csv obd/capturas/elm327-motor22-pedales.csv
"""
import argparse, importlib.util, os, sys, termios, time

spec = importlib.util.spec_from_file_location("explorar", os.path.join(os.path.dirname(__file__), "elm327-explorar.py"))
ex = importlib.util.module_from_spec(spec)
spec.loader.exec_module(ex)

ap = argparse.ArgumentParser()
ap.add_argument("--ble", default="OBDII")
ap.add_argument("--dids", default="ED01,ED02,ED03,ED04,ED05,ED06,ED07,ED0C,ED0E,ED1F,ED27")
ap.add_argument("--segundos", type=float, default=90)
ap.add_argument("--csv", required=True)
a = ap.parse_args()

fd = ex.abrir(ex.puente_ble(a.ble), 38400)
time.sleep(1.5); os.write(fd, b"\r"); time.sleep(0.3); termios.tcflush(fd, termios.TCIFLUSH)
for _ in range(6):
    if any("ELM" in l for l in ex.cmd(fd, "ATZ", 8.0)):
        break
    time.sleep(2)
else:
    sys.exit("El adaptador no responde.")
for c in ["ATE0", "ATL0", "ATS0", "ATH1", "ATAT1", "ATSP6", "ATSH7E0", "ATCF7E8", "ATCM7FF", "ATST32"]:
    ex.cmd(fd, c, 3.0)

dids = a.dids.split(",")
t0 = time.time()
with open(a.csv, "w") as f:
    f.write("epoch,did,datos\n")
    ciclo = 0
    while time.time() - t0 < a.segundos:
        for did in dids:
            r, _ = ex.pedir(fd, "22" + did, 4.0)
            ps = [p for v in r.values() for p in v]
            if ps and ps[-1][:1] == b"\x62":
                f.write(f"{time.time():.1f},{did},{ex.hx(ps[-1][3:])}\n")
        f.flush()
        ciclo += 1
        print(f"t={time.time() - t0:5.0f}s ciclo {ciclo}", flush=True)
ex.cmd(fd, "ATZ", 5.0)
print("FIN")
