#!/usr/bin/env python3
"""
Offline emulator of the ELM327 v1.5 clone plugged into the Hyundai Accent 2016 1.6 CRDi,
fed by the real captures in ../capturas. Python 3 stdlib only. See README.md.

  python3 obd/emulador/elm327_emulador.py                      # TCP 127.0.0.1:35000
  python3 obd/emulador/elm327_emulador.py --pty                # pseudo-terminal for the serial scripts
  python3 obd/emulador/elm327_emulador.py --escenario carroceria --velocidad 2

Console on stdin: puerta|cinturon|ac|freno|embrague|neutro on|off|auto, rpm N|auto, vel N|auto, cuelgue, reset,
corte, estado.
"""
import argparse, bisect, csv, heapq, os, re, select, socket, sys, threading, time, tty

CAPTURES = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "capturas")
VIN = b"KMHCU41UAGU000000"  # redacted VIN; the repository is public, never put the real one here
ELM_ID = b"ELM327 v1.5"
ECU_RESPONSE_ID = 0x7E8
OBD_REQUEST_IDS = {0x7DF, 0x7E0}
DEFAULT_RATE_HZ = 10.0
# console name -> (CAN ID, byte index, bit mask)
BIT_OVERRIDES = {"puerta": (0x690, 0, 0x40), "cinturon": (0x690, 2, 0x01), "ac": (0x350, 1, 0x40)}
# Engine block 22ED01 (bytes counted after 62 ED 01): console name -> (byte index, bit mask). See obd/codigos-can.md.
ED01_OVERRIDES = {"freno": (12, 0x01), "embrague": (13, 0x01), "neutro": (13, 0x08)}
# 22ED01 at rest, ignition on (capturas/elm327-motor22-pedales-2.csv): no pedal pressed, gearbox in neutral.
ED01_REPOSO = bytes.fromhex("FFFF7EF8000112007F48656380082513CB0F009900FF000000469B64FF0001000019")
# --tiempos-ble: timings of the BLE clone measured in a car log (0.1.80, 2026-11-21), p50 of ~1 700 commands.
BLE_IDA_Y_VUELTA = 0.047  # any command that returns at once: AT commands and single-frame PIDs with a reply count
BLE_ESPERA_SIN_CUENTA = 0.055  # without a reply count the adapter waits for more ECUs (ATAT1 adaptive timeout)
BLE_POR_TRAMA = 0.002  # each extra ISO-TP frame of a multi-frame reply


class BufferFull(Exception):
    pass


def read_csv(name):
    path = os.path.join(CAPTURES, name)
    if not os.path.exists(path):
        return []
    with open(path, newline="") as file:
        return list(csv.DictReader(file))


def read_text(name):
    path = os.path.join(CAPTURES, name)
    if not os.path.exists(path):
        return ""
    with open(path, encoding="utf-8", errors="replace") as file:
        return file.read()


class Series:
    """Sample-and-hold time series: value_at(t) is the last value captured at or before t."""

    def __init__(self, points):
        points.sort(key=lambda point: point[0])
        self.times = [point[0] for point in points]
        self.values = [point[1] for point in points]

    def value_at(self, t):
        return self.values[max(bisect.bisect_right(self.times, t) - 1, 0)]


def build_series(rows):
    """rows: [(time, key, bytes)] -> ({key: Series}, loop duration in seconds)."""
    if not rows:
        return {}, 1.0
    start = min(row[0] for row in rows)
    grouped = {}
    for t, key, data in rows:
        grouped.setdefault(key, []).append((t - start, data))
    return {key: Series(points) for key, points in grouped.items()}, max(max(row[0] for row in rows) - start, 1.0)


def load_exploration():
    """Mode 01 values (data bytes only) and mode 09 payloads (from 0x49 on) from elm327-exploracion.txt."""
    text = read_text("elm327-exploracion.txt")
    mode01 = {int(m[1], 16): bytes.fromhex(m[2]) for m in re.finditer(r"^01([0-9A-F]{2}) .*?\[7E8\] ([0-9A-F]+)", text, re.M)}
    for m in re.finditer(r"^01([0-9A-F]{2}) -> \['7E8([0-9A-F]+)'\]", text, re.M):  # bitmaps: raw 7E8 frame
        frame = bytes.fromhex(m[2])
        mode01[int(m[1], 16)] = frame[3:1 + frame[0]]
    mode09 = {int(m[1], 16): bytes.fromhex(m[2]) for m in re.finditer(r"^09([0-9A-F]{2}) \[7E8\] ([0-9A-F]+)", text, re.M)}
    mode09[0x02] = b"\x49\x02\x01" + VIN
    return mode01, mode09


def supported_pids(mode01):
    supported = set()
    for base in (0x00, 0x20, 0x40, 0x60, 0x80):
        bits = int.from_bytes(mode01.get(base, bytes(4)), "big")
        supported |= {base + i + 1 for i in range(32) if bits >> (31 - i) & 1}
    return supported


def load_listen_dumps():
    """Frames and measured rates from the per-ID listening dumps (elm327-exploracion2-ids*.txt)."""
    rows, rates, current = [], {}, None
    for name in ("elm327-exploracion2-ids.txt", "elm327-exploracion2-ids-b.txt"):
        for line in read_text(name).splitlines():
            header = re.match(r"([0-9A-F]{3}): \d+ tramas en [\d.]+s \(~([\d.]+) Hz\)", line)
            frame = re.match(r"\s+(\d+\.\d+) ([0-9A-F]{2,16})\s*$", line)
            if header:
                current = int(header[1], 16)
                rates[current] = float(header[2])
            elif line.startswith("="):
                current = None
            elif frame and current is not None:
                rows.append((float(frame[1]), current, bytes.fromhex(frame[2])))
    return rows, rates


def load_pid_rows(scenario):
    if scenario == "guiada":
        return [(float(r["epoch"]), int(r["id"], 16), bytes.fromhex(r["datos"])) for r in read_csv("elm327-guiada.csv") if r["tipo"] == "pid"]
    # ponytail: carroceria has no PID rows; it reuses the idle series (the engine was running, 14.2 V).
    return [(float(r["t"]), int(r["pid"], 16), bytes.fromhex(r["datos"])) for r in read_csv("elm327-ralenti.csv")]


def load_can_rows(scenario, dump_rows):
    # epoch, not t: the CSVs append several sessions and t restarts at each one.
    for name in (["elm327-carroceria.csv"] if scenario == "carroceria" else []) + ["elm327-guiada.csv"]:
        rows = [(float(r["epoch"]), int(r["id"], 16), bytes.fromhex(r["datos"])) for r in read_csv(name) if r["tipo"] == "can"]
        if rows:
            return rows
    return dump_rows


class Vehicle:
    """Scenario data plus console overrides, shared by the client session and the console."""

    def __init__(self, scenario, speed, ble_clone=False, ble_times=False):
        self.scenario, self.speed, self.ble_clone, self.ble_times = scenario, speed, ble_clone, ble_times
        self.mode01, self.mode09 = load_exploration()
        self.supported = supported_pids(self.mode01)
        self.pid_series, self.pid_duration = build_series(load_pid_rows(scenario))
        dump_rows, self.rates = load_listen_dumps()
        self.can_series, self.can_duration = build_series(load_can_rows(scenario, dump_rows))
        self.overrides = {}
        self.hung = self.cut = False
        self.started = time.monotonic()

    def clock(self, duration):
        return (time.monotonic() - self.started) * self.speed % duration

    def pid_value(self, pid):
        if pid == 0x0C and "rpm" in self.overrides:
            return (self.overrides["rpm"] * 4).to_bytes(2, "big")
        if pid == 0x0D and "vel" in self.overrides:
            return bytes([self.overrides["vel"]])
        series = self.pid_series.get(pid)
        return series.value_at(self.clock(self.pid_duration)) if series else self.mode01.get(pid)

    def answer(self, request):
        """OBD payload the engine ECU would send back, or None for NO DATA."""
        mode, pid = request[0], request[1] if len(request) > 1 else None
        if mode == 0x01 and len(request) > 2:
            # Several PIDs in one request, as the real ECU accepts: 41 + (pid, value) for each one it knows.
            parts = [bytes([pid]) + self.pid_value(pid) for pid in request[1:7] if pid in self.supported and self.pid_value(pid)]
            return b"\x41" + b"".join(parts) if parts else None
        if mode == 0x01 and pid is not None and (pid in self.supported or pid % 0x20 == 0):
            value = self.pid_value(pid)
            return bytes([0x41, pid]) + value if value else None
        if mode == 0x22:
            if request[1:3] != b"\xed\x01":
                return b"\x7f\x22\x31"  # request out of range, as the ECU answers unknown identifiers
            data = bytearray(ED01_REPOSO)
            for name, (index, mask) in ED01_OVERRIDES.items():
                if name in self.overrides:
                    data[index] = data[index] | mask if self.overrides[name] else data[index] & ~mask
            if self.overrides.get("freno"):
                data[13] |= 0x40  # the second brake switch closes with the first one
            return b"\x62\xed\x01" + bytes(data)
        if mode == 0x09 and pid in self.mode09:
            return self.mode09[pid]
        if len(request) == 1:
            return {0x03: b"\x43\x00", 0x07: b"\x47\x00"}.get(mode)
        return None

    def frame(self, can_id):
        data = bytearray(self.can_series[can_id].value_at(self.clock(self.can_duration)))
        for name, (target, index, mask) in BIT_OVERRIDES.items():
            if can_id == target and name in self.overrides and len(data) > index:
                data[index] = data[index] | mask if self.overrides[name] else data[index] & ~mask
        if can_id == 0x316 and len(data) >= 7:
            if "rpm" in self.overrides:
                data[2:4] = (self.overrides["rpm"] * 4).to_bytes(2, "little")
            if "vel" in self.overrides:
                data[6] = self.overrides["vel"]
        return bytes(data)

    def battery(self):
        rpm = int.from_bytes(self.pid_value(0x0C) or bytes(2), "big") / 4
        return b"14.2V" if rpm > 0 else b"12.6V"

    def command(self, words):
        name, value = words[0].lower(), words[1].lower() if len(words) > 1 else ""
        if name in BIT_OVERRIDES or name in ED01_OVERRIDES or name in ("rpm", "vel"):
            if value == "auto":
                self.overrides.pop(name, None)
            elif name in BIT_OVERRIDES or name in ED01_OVERRIDES:
                self.overrides[name] = {"on": True, "off": False}[value]
            else:
                number = int(value)
                if not 0 <= number <= (16383 if name == "rpm" else 255):
                    raise ValueError(value)
                self.overrides[name] = number
        elif name == "cuelgue":
            self.hung = True
        elif name == "reset":
            self.hung = False
        elif name == "corte":
            self.cut = True
        elif name != "estado":
            raise ValueError(name)
        return (f"escenario={self.scenario} t_pid={self.clock(self.pid_duration):.1f}/{self.pid_duration:.0f}s "
                f"t_can={self.clock(self.can_duration):.1f}/{self.can_duration:.0f}s overrides={self.overrides} "
                f"colgado={self.hung}")


class Session:
    """One client talking to the adapter over a non-blocking file descriptor (socket or pty master)."""

    def __init__(self, fd, vehicle, latency):
        self.fd, self.vehicle, self.latency = fd, vehicle, latency
        self.pending, self.last = b"", ""
        self.automatic, self.protocol, self.detected = True, "0", False
        self.reset_settings()

    def reset_settings(self):
        self.echo, self.linefeeds, self.spaces, self.headers, self.auto_format = True, False, True, False, True
        self.header = 0x7DF
        self.receive_address, self.filter, self.mask = None, 0, 0
        if self.automatic:
            self.detected = False

    # ---- transport ----
    def receive(self, timeout):
        """Bytes read; b'' on timeout; None when the client left or 'corte' was issued."""
        deadline = None if timeout is None else time.monotonic() + timeout
        while not self.vehicle.cut:
            wait = 0.2 if deadline is None else max(0.0, min(0.2, deadline - time.monotonic()))
            if select.select([self.fd], [], [], wait)[0]:
                try:
                    data = os.read(self.fd, 1024)
                except BlockingIOError:
                    continue
                except OSError:
                    return None
                if not data:
                    return None
                if not self.vehicle.hung:  # a hung clone swallows everything
                    return data
            elif deadline is not None and time.monotonic() >= deadline:
                return b""
        return None

    def send(self, data, monitoring=False):
        if monitoring and not select.select([], [self.fd], [], 0)[1]:
            raise BufferFull
        while data:
            if not select.select([], [self.fd], [], 5)[1]:
                raise ConnectionError("el cliente dejó de leer")
            try:
                data = data[os.write(self.fd, data):]
            except BlockingIOError:
                pass

    def end(self):
        return b"\r\n" if self.linefeeds else b"\r"

    def reply(self, lines):
        self.send(b"".join(line + self.end() for line in lines) + self.end() + b">")

    def run(self):
        try:
            while True:
                data = self.receive(None)
                if data is None:
                    return
                self.pending += data
                while b"\r" in self.pending and not self.vehicle.hung:
                    line, self.pending = self.pending.split(b"\r", 1)
                    self.execute(line)
                if self.vehicle.hung:
                    self.pending = b""
        except OSError:
            return

    # ---- commands ----
    def execute(self, raw):
        if self.echo:
            self.send(raw + self.end())
        command = "".join(raw.decode("ascii", "replace").split()).upper() or self.last  # bare CR repeats
        if not command:
            return self.send(b">")
        self.last = command
        if command.startswith("AT"):
            if self.vehicle.ble_times:
                time.sleep(BLE_IDA_Y_VUELTA)
            lines = self.at_command(command[2:])
        elif re.fullmatch(r"[0-9A-F]+", command):
            lines = self.obd_request(command)
        else:
            lines = [b"?"]
        self.reply(lines)
        if self.vehicle.ble_clone and command == "ATMA":
            # The real clone over BLE sends late leftovers and an extra prompt after ATMA stops:
            # a client that does not resync pairs every later reply with the previous command.
            time.sleep(0.15)
            self.send(self.end() + b">")

    def at_command(self, argument):
        settings = {"E0": ("echo", False), "E1": ("echo", True), "L0": ("linefeeds", False), "L1": ("linefeeds", True),
                    "S0": ("spaces", False), "S1": ("spaces", True), "H0": ("headers", False), "H1": ("headers", True),
                    "CAF0": ("auto_format", False), "CAF1": ("auto_format", True)}
        if argument in settings:
            setattr(self, *settings[argument])
            return [b"OK"]
        if argument == "Z":
            self.reset_settings()
            return [b"\xfc", b"", ELM_ID]  # the clone sends a garbage byte before the banner
        if argument == "WS":
            self.reset_settings()
            return [b"", ELM_ID]
        if argument == "D":
            self.reset_settings()
        if argument == "I":
            return [ELM_ID]
        if argument == "RV":
            return [self.vehicle.battery()]
        if argument == "DP":
            name = b"ISO 15765-4 (CAN 11/500)" if self.protocol == "6" else f"PROTOCOL {self.protocol}".encode()
            if self.automatic:
                return [b"AUTO, " + name if self.detected else b"AUTO"]
            return [name]
        if argument == "DPN":
            return [(("A" if self.automatic else "") + (self.protocol if self.detected or not self.automatic else "0")).encode()]
        if argument == "MA":
            return self.monitor()
        if argument == "PC" and self.automatic:
            self.detected = False
        protocol = re.fullmatch(r"SP(A?)([0-9A-C])", argument)
        if protocol:
            self.automatic = bool(protocol[1]) or protocol[2] == "0"
            self.protocol, self.detected = protocol[2], False
        header = re.fullmatch(r"SH([0-9A-F]{3}|[0-9A-F]{6}|[0-9A-F]{8})", argument)
        if header:
            self.header = int(header[1], 16) if len(header[1]) == 3 else None
        address = re.fullmatch(r"CRA([0-9A-FX]{3})?", argument)
        if address or argument == "AR":
            if address and address[1] and "X" in address[1]:
                return [b"?"]  # this clone rejects X wildcards
            self.receive_address = int(address[1], 16) if address and address[1] else None
            self.filter = self.mask = 0
        value = re.fullmatch(r"C([FM])([0-9A-F]{3})", argument)
        if value:
            setattr(self, "filter" if value[1] == "F" else "mask", int(value[2], 16))
        return [b"OK"]

    def accepts(self, can_id):
        if self.receive_address is not None:
            return can_id == self.receive_address
        return can_id & self.mask == self.filter & self.mask

    def hex_line(self, data):
        return (" " if self.spaces else "").join(f"{byte:02X}" for byte in data)

    def obd_request(self, command):
        count = None
        if len(command) % 2:
            command, count = command[:-1], int(command[-1], 16)  # trailing response-count digit, e.g. "010C1"
        request = bytes.fromhex(command)
        if not self.auto_format and request:  # CAF0: the client sends the PCI byte itself
            request = request[1:1 + request[0]]
        if not request:
            return [b"?"]
        lines = []
        if self.automatic and not self.detected:
            lines.append(b"SEARCHING...")
            self.protocol, self.detected = "6", True
        elif self.protocol != "6":
            return [b"UNABLE TO CONNECT"]
        payload = self.vehicle.answer(request) if self.header in OBD_REQUEST_IDS and self.accepts(ECU_RESPONSE_ID) else None
        frames = self.format_response(payload) if payload else [b"NO DATA"]
        data_lines = [line for line in frames if not re.fullmatch(rb"[0-9A-F]{3}", line)]  # the length line is no frame
        if self.vehicle.ble_times:
            # With a count the clone returns after that many CAN frames; otherwise it waits out the timeout.
            waits = count is None or payload is None or count > len(data_lines)
            received = len(data_lines) if count is None else min(count, len(data_lines))
            time.sleep(BLE_IDA_Y_VUELTA + (BLE_ESPERA_SIN_CUENTA if waits else 0) + BLE_POR_TRAMA * (received - 1))
        else:
            time.sleep(self.latency)
        if count is not None and payload is not None and count < len(data_lines):
            # The clone stops after `count` frames: a multi-frame reply comes back cut (CLAUDE.md, "The BLE clone").
            frames = frames[:len(frames) - len(data_lines) + count]
        return lines + frames

    def format_response(self, payload):
        """ISO-TP framing as the ELM prints it, honouring H, S and CAF."""
        if len(payload) <= 7:
            frames = [bytes([len(payload)]) + payload]
        else:
            frames = [bytes([0x10 | len(payload) >> 8, len(payload) & 0xFF]) + payload[:6]]
            frames += [bytes([0x20 | (i + 1) & 0x0F]) + payload[start:start + 7] for i, start in enumerate(range(6, len(payload), 7))]
        header = f"{ECU_RESPONSE_ID:03X}{' ' if self.spaces else ''}" if self.headers else ""
        if not self.auto_format:
            return [(header + self.hex_line(frame.ljust(8, b"\x00"))).encode() for frame in frames]
        if self.headers:
            return [(header + self.hex_line(frame)).encode() for frame in frames]
        if len(frames) == 1:
            return [self.hex_line(payload).encode()]
        separator = " " if self.spaces else ""
        return [f"{len(payload):03X}".encode()] + [f"{i & 0xF:X}:{separator}{self.hex_line(frame[2 if i == 0 else 1:])}".encode()
                                                   for i, frame in enumerate(frames)]

    def monitor(self):
        """ATMA: replays the scenario frames that pass the filters until any byte arrives."""
        if self.pending:
            self.pending = self.pending[1:]
            return [b"STOPPED"]
        vehicle = self.vehicle
        queue = [(time.monotonic(), can_id) for can_id in vehicle.can_series if self.accepts(can_id)]
        heapq.heapify(queue)
        while True:
            wait = max(0.0, queue[0][0] - time.monotonic()) if queue else 0.2
            data = self.receive(min(wait, 0.2))
            if data is None:
                raise ConnectionError("cliente desconectado")
            if data:
                self.pending += data[1:]
                return [b"STOPPED"]
            now = time.monotonic()
            while queue and queue[0][0] <= now and not vehicle.hung:
                due, can_id = heapq.heappop(queue)
                prefix = f"{can_id:03X}{' ' if self.spaces else ''}" if self.headers else ""
                try:
                    self.send((prefix + self.hex_line(vehicle.frame(can_id))).encode() + self.end(), monitoring=True)
                except BufferFull:
                    return [b"BUFFER FULL"]
                # ponytail: frames keep their real rate; --velocidad only speeds up the scenario clock.
                heapq.heappush(queue, (max(due + 1 / vehicle.rates.get(can_id, DEFAULT_RATE_HZ), now), can_id))


def console(vehicle):
    for line in sys.stdin:
        words = line.split()
        if not words:
            continue
        try:
            print(vehicle.command(words), flush=True)
        except (ValueError, KeyError):
            print("? comandos: puerta|cinturon|ac|freno|embrague|neutro on|off|auto, rpm N|auto, vel N|auto, cuelgue, reset, corte, estado", flush=True)


def serve_tcp(vehicle, host, port, latency):
    server = socket.create_server((host, port))
    print(f"ELM327 v1.5 emulado ({vehicle.scenario}) en {host}:{server.getsockname()[1]}", flush=True)
    while True:
        connection, address = server.accept()
        connection.setsockopt(socket.SOL_SOCKET, socket.SO_SNDBUF, 8192)  # small queue, so a slow reader overflows like the clone
        connection.setblocking(False)
        print(f"cliente conectado {address[0]}:{address[1]}", flush=True)
        with connection:
            Session(connection.fileno(), vehicle, latency).run()
        vehicle.cut = False
        print("cliente desconectado", flush=True)


def serve_pty(vehicle, latency):
    master, slave = os.openpty()
    tty.setraw(slave)  # we keep the slave open: no EOF between clients and no echo of our own replies
    os.set_blocking(master, False)
    print(f"ELM327 v1.5 emulado ({vehicle.scenario}) en {os.ttyname(slave)}", flush=True)
    while True:
        Session(master, vehicle, latency).run()  # ponytail: on a pty 'corte' only restarts the session
        vehicle.cut = False


def main():
    parser = argparse.ArgumentParser(description="Emulador offline del ELM327 del Accent a partir de las capturas reales")
    parser.add_argument("--host", default="127.0.0.1")
    parser.add_argument("--puerto", type=int, default=35000)
    parser.add_argument("--escenario", choices=["ralenti", "guiada", "carroceria"], default="ralenti")
    parser.add_argument("--velocidad", type=float, default=1.0, help="factor de tiempo de la reproducción")
    parser.add_argument("--latencia", type=float, default=60, help="ms por petición OBD (60 ≈ 16 lecturas/s)")
    parser.add_argument("--pty", action="store_true", help="pseudo-terminal serie en vez de TCP")
    parser.add_argument("--clon-ble", action="store_true", help="imita al clon por BLE: prompt tardío tras detener ATMA")
    parser.add_argument("--tiempos-ble", action="store_true",
                        help="tiempos medidos del clon por BLE en el auto (reemplaza --latencia): ~47 ms por comando, "
                             "~55 ms más si el pedido no lleva cuenta de respuestas")
    arguments = parser.parse_args()
    vehicle = Vehicle(arguments.escenario, arguments.velocidad, arguments.clon_ble, arguments.tiempos_ble)
    threading.Thread(target=console, args=(vehicle,), daemon=True).start()
    try:
        if arguments.pty:
            serve_pty(vehicle, arguments.latencia / 1000)
        else:
            serve_tcp(vehicle, arguments.host, arguments.puerto, arguments.latencia / 1000)
    except KeyboardInterrupt:
        pass


if __name__ == "__main__":
    main()
