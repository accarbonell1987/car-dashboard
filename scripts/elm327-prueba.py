#!/usr/bin/env python3
"""
Prueba del ELM327 desde la Mac por Bluetooth clásico (puerto serie /dev/cu.OBDII).
Sin dependencias: solo Python 3 de macOS.

Uso:
  python3 scripts/elm327-prueba.py                 # prueba automática
  python3 scripts/elm327-prueba.py -i              # prueba y luego modo interactivo
  python3 scripts/elm327-prueba.py --puerto /dev/cu.OBDII

Usa la misma secuencia de arranque que screen/.../Elm327Bluetooth.kt.
Cada comando tiene tiempo límite, así que nunca se queda colgado como screen.
"""
import argparse, os, select, sys, termios, time

INICIO = ["ATZ", "ATE0", "ATL0", "ATS0", "ATH0", "ATAT1", "ATSP0"]
PRUEBAS = [
    ("ATI", "Versión del chip"),
    ("ATRV", "Voltaje de la batería"),
    ("0100", "PIDs soportados 01-20 (el auto responde?)"),
    ("ATDP", "Protocolo detectado"),
    ("010C", "RPM"),
    ("0105", "Temperatura del refrigerante"),
    ("010D", "Velocidad"),
    ("0902", "VIN"),
]


def abrir(puerto, baudios):
    fd = os.open(puerto, os.O_RDWR | os.O_NOCTTY | os.O_NONBLOCK)
    attrs = termios.tcgetattr(fd)
    attrs[0] = 0                                    # iflag
    attrs[1] = 0                                    # oflag
    attrs[2] = termios.CS8 | termios.CREAD | termios.CLOCAL
    attrs[3] = 0                                    # lflag: sin eco ni modo canónico
    vel = getattr(termios, f"B{baudios}", termios.B38400)
    attrs[4] = attrs[5] = vel
    termios.tcsetattr(fd, termios.TCSANOW, attrs)
    termios.tcflush(fd, termios.TCIOFLUSH)
    return fd


def comando(fd, cmd, limite=5.0):
    os.write(fd, (cmd + "\r").encode())
    datos = b""
    fin = time.time() + limite
    while time.time() < fin:
        r, _, _ = select.select([fd], [], [], 0.2)
        if r:
            try:
                trozo = os.read(fd, 256)
            except BlockingIOError:
                continue
            if not trozo:
                break
            datos += trozo
            if b">" in trozo:
                break
    texto = datos.decode(errors="replace").replace(">", "").replace("\r", "\n")
    lineas = [l.strip() for l in texto.split("\n") if l.strip() and l.strip() != cmd]
    completo = b">" in datos
    return " | ".join(lineas), completo


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--puerto", default="/dev/cu.OBDII")
    ap.add_argument("--baudios", type=int, default=38400)
    ap.add_argument("-i", "--interactivo", action="store_true")
    a = ap.parse_args()

    print(f"Abriendo {a.puerto} (la Mac conecta el Bluetooth ahora; puede tardar unos segundos)...")
    try:
        fd = abrir(a.puerto, a.baudios)
    except OSError as e:
        print(f"No pude abrir el puerto: {e}")
        print("Revisa: otro programa usando el puerto (pkill screen), el teléfono conectado al adaptador,")
        print("o el adaptador sin corriente. Prueba también: Bluetooth > OBDII > Forget y emparejar de nuevo.")
        sys.exit(1)

    time.sleep(1.5)  # dar tiempo a que se establezca el enlace RFCOMM
    os.write(fd, b"\r")  # descarta cualquier comando a medias
    time.sleep(0.3)
    termios.tcflush(fd, termios.TCIFLUSH)

    print("\n== Arranque ==")
    for cmd in INICIO:
        resp, ok = comando(fd, cmd, 8.0 if cmd == "ATZ" else 3.0)
        print(f"{cmd:6} -> {resp or '(sin respuesta)'}{'' if ok else '  [sin prompt >]'}")
        if cmd == "ATZ" and not resp:
            print("\nEl adaptador no responde. Sal, desenchúfalo 10 s del auto y vuelve a probar.")
            sys.exit(2)

    print("\n== Pruebas ==")
    for cmd, desc in PRUEBAS:
        resp, ok = comando(fd, cmd, 10.0 if cmd == "0100" else 4.0)
        print(f"{cmd:6} {desc:42} -> {resp or '(sin respuesta)'}{'' if ok else '  [sin prompt >]'}")

    if a.interactivo:
        print("\nModo interactivo: escribe comandos (ATxx o PIDs como 010C). 'salir' para terminar.")
        while True:
            try:
                cmd = input("> ").strip().upper()
            except EOFError:
                break
            if cmd in ("SALIR", "EXIT", "Q"):
                break
            if cmd:
                resp, ok = comando(fd, cmd, 10.0)
                print(resp or "(sin respuesta)", "" if ok else "[sin prompt >]")
    os.close(fd)


if __name__ == "__main__":
    main()
