#!/usr/bin/env python3
"""End-to-end tests: start the emulator on a free port and talk to it over TCP like a client would.

  python3 -m unittest obd/emulador/test_emulador.py -v
"""
import os, re, socket, subprocess, sys, time, unittest

EMULATOR = os.path.join(os.path.dirname(os.path.abspath(__file__)), "elm327_emulador.py")
REDACTED_VIN = b"KMHCU41UAGU000000"


class EmulatorTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.process = subprocess.Popen([sys.executable, EMULATOR, "--puerto", "0", "--latencia", "0", "--escenario", "carroceria"],
                                       stdin=subprocess.PIPE, stdout=subprocess.PIPE, text=True)
        cls.port = int(re.search(r":(\d+)$", cls.process.stdout.readline().strip())[1])

    @classmethod
    def tearDownClass(cls):
        cls.process.kill()
        cls.process.wait()

    def setUp(self):
        self.connection = socket.create_connection(("127.0.0.1", self.port), timeout=5)
        self.startup = self.command("ATZ")
        for command in ["ATE0", "ATL0", "ATS0", "ATH0", "ATSP6"]:
            self.assertEqual(self.command(command), ["OK"], command)

    def tearDown(self):
        self.connection.close()

    def read_until_prompt(self):
        data = b""
        while not data.endswith(b">"):
            chunk = self.connection.recv(4096)
            self.assertTrue(chunk, "connection closed")
            data += chunk
        return [line for line in data[:-1].decode("latin-1").split("\r") if line]

    def command(self, text):
        self.connection.sendall(text.encode() + b"\r")
        return [line for line in self.read_until_prompt() if line != text]

    def console(self, text):
        self.process.stdin.write(text + "\n")
        self.process.stdin.flush()
        time.sleep(0.2)

    def monitor(self, seconds):
        self.connection.sendall(b"ATMA\r")
        time.sleep(seconds)
        self.connection.sendall(b" ")
        return self.read_until_prompt()

    def test_startup_banner_has_garbage_byte(self):
        self.assertEqual(self.startup[-1], "ELM327 v1.5")
        self.assertEqual(len(self.startup[0]), 1)
        self.assertGreaterEqual(ord(self.startup[0]), 0x80)

    def test_pid_bitmap(self):
        self.assertEqual(self.command("0100"), ["4100983BA013"])

    def test_bitmap_with_headers_matches_capture(self):
        self.command("ATH1")
        self.assertEqual(self.command("0100"), ["7E8064100983BA013"])

    def test_rpm_is_plausible(self):
        line = self.command("010C")[0]
        self.assertTrue(line.startswith("410C"), line)
        self.assertTrue(500 <= int(line[4:8], 16) / 4 <= 5000, line)

    def test_manual_pci_with_caf0(self):
        for command in ["ATH1", "ATCAF0"]:
            self.command(command)
        line = self.command("02010C")[0]
        self.assertTrue(line.startswith("7E804410C"), line)
        self.assertEqual(len(line), 3 + 16)

    def test_unsupported_pid(self):
        self.assertEqual(self.command("0102"), ["NO DATA"])

    def test_other_module_header(self):
        self.command("ATSH7D4")
        self.assertEqual(self.command("0100"), ["NO DATA"])

    def test_redacted_vin(self):
        lines = self.command("0902")
        self.assertEqual(lines[0], "014")
        payload = bytes.fromhex("".join(line.split(":", 1)[1] for line in lines[1:]))
        self.assertEqual(payload, b"\x49\x02\x01" + REDACTED_VIN)

    def test_stored_dtcs(self):
        self.assertEqual(self.command("03"), ["4300"])

    def test_monitor_filters_and_stops(self):
        self.command("ATH1")
        self.command("ATCRA690")
        lines = self.monitor(1.0)
        self.assertEqual(lines[-1], "STOPPED")
        frames = lines[:-1]
        self.assertGreaterEqual(len(frames), 3)
        self.assertTrue(all(frame.startswith("690") for frame in frames), frames)
        self.assertEqual(self.command("ATI"), ["ELM327 v1.5"])  # back at the prompt

    def test_door_override(self):
        self.command("ATH1")
        self.command("ATCRA690")
        self.console("puerta on")
        try:
            frames = self.monitor(0.6)[:-1]
            self.assertTrue(frames)
            self.assertTrue(all(int(frame[3:5], 16) & 0x40 for frame in frames), frames)
            self.console("puerta off")
            frames = self.monitor(0.6)[:-1]
            self.assertTrue(all(not int(frame[3:5], 16) & 0x40 for frame in frames), frames)
        finally:
            self.console("puerta auto")


if __name__ == "__main__":
    unittest.main()
