#!/usr/bin/env python3
# Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
# SPDX-License-Identifier: GPL-3.0-or-later

"""Fixture tests for reply-pcap.py (issue #834). Run with:
    python3 -m unittest discover -s contrib

Each test builds a libpcap file in memory with struct, binds a UDP socket on
127.0.0.1, runs the script as a subprocess against that port, and asserts
which payloads arrived and what the summary line says. The fixtures are built
here rather than checked in as binaries so the expected payloads sit next to
the assertions that read them.
"""

import re
import socket
import struct
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

SCRIPT = Path(__file__).parent / "reply-pcap.py"

DLT_EN10MB = 1
DLT_LINUX_SLL = 113
DLT_LINUX_SLL2 = 276

MAGIC_LE_US = b"\xd4\xc3\xb2\xa1"
MAGIC_BE_US = b"\xa1\xb2\xc3\xd4"
MAGIC_LE_NS = b"\x4d\x3c\xb2\xa1"
MAGIC_BE_NS = b"\xa1\xb2\x3c\x4d"

SUMMARY = re.compile(r"^sent (\d+) UDP payloads to ([\d.]+):(\d+); skipped (\d+)(?: \((.*)\))?$")


# --- packet builders -------------------------------------------------------

def udp(payload: bytes, sport: int = 40000, dport: int = 9999) -> bytes:
    return struct.pack("!HHHH", sport, dport, 8 + len(payload), 0) + payload


def ipv4(l4: bytes, proto: int = 17, frag: int = 0) -> bytes:
    header = struct.pack("!BBHHHBBH4s4s", 0x45, 0, 20 + len(l4), 1, frag, 64, proto, 0,
                         bytes([10, 0, 0, 1]), bytes([127, 0, 0, 1]))
    return header + l4


def ipv6(l4: bytes, next_header: int = 17) -> bytes:
    header = struct.pack("!IHBB16s16s", 0x60000000, len(l4), next_header, 64,
                         b"\xfd" + b"\x00" * 15, b"\xfd" + b"\x00" * 14 + b"\x01")
    return header + l4


def ethernet(l3: bytes, ethertype: int = 0x0800) -> bytes:
    return b"\x02" * 6 + b"\x04" * 6 + struct.pack("!H", ethertype) + l3


def vlan_ethernet(l3: bytes, ethertype: int = 0x0800) -> bytes:
    return b"\x02" * 6 + b"\x04" * 6 + struct.pack("!HHH", 0x8100, 0x0064, ethertype) + l3


def sll(l3: bytes, ethertype: int = 0x0800) -> bytes:
    return struct.pack("!HHH8sH", 0, 1, 6, b"\x04" * 6 + b"\x00\x00", ethertype) + l3


def sll2(l3: bytes, ethertype: int = 0x0800) -> bytes:
    return struct.pack("!HHiHBB8s", ethertype, 0, 2, 1, 0, 6, b"\x04" * 6 + b"\x00\x00") + l3


def pcap(frames, linktype: int = DLT_EN10MB, magic: bytes = MAGIC_LE_US, snaplen: int = 65535) -> bytes:
    order = "<" if magic in (MAGIC_LE_US, MAGIC_LE_NS) else ">"
    out = magic + struct.pack(order + "HHiIII", 2, 4, 0, 0, snaplen, linktype)
    for i, frame in enumerate(frames):
        if isinstance(frame, tuple):  # (frame, incl_len) to simulate snaplen truncation
            frame, incl_len = frame
            frame = frame[:incl_len]
        else:
            incl_len = len(frame)
        out += struct.pack(order + "IIII", 1700000000 + i, 0, len(frame), incl_len) + frame
    return out


def flow_frames(payloads, framing=ethernet, dport: int = 9999):
    return [framing(ipv4(udp(p, dport=dport))) for p in payloads]


# --- runner ----------------------------------------------------------------

def replay(pcap_bytes: bytes, *args: str):
    """Run the script against a fresh UDP socket; return (received payloads, completed process)."""
    with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as sock:
        sock.bind(("127.0.0.1", 0))
        port = sock.getsockname()[1]
        with tempfile.NamedTemporaryFile(suffix=".pcap", delete=False) as f:
            f.write(pcap_bytes)
            path = f.name
        try:
            proc = subprocess.run([sys.executable, str(SCRIPT), path, "--delay", "0", "--port", str(port), *args],
                                  capture_output=True, text=True, timeout=30)
        finally:
            Path(path).unlink()
        received = []
        sock.settimeout(0.2)
        while True:
            try:
                received.append(sock.recv(65535))
            except socket.timeout:
                break
        return received, proc


def summary_of(proc):
    lines = [line for line in proc.stderr.splitlines() if line.startswith("sent ")]
    assert len(lines) == 1, f"expected one summary line, got stderr:\n{proc.stderr}"
    m = SUMMARY.match(lines[0])
    assert m, f"summary line does not match the documented form: {lines[0]!r}"
    reasons = {}
    if m.group(5):
        for item in m.group(5).split(", "):
            reason, count = item.rsplit(" ", 1)
            reasons[reason] = int(count)
    return int(m.group(1)), int(m.group(3)), int(m.group(4)), reasons


PAYLOADS = [b"\x00\x09first", b"\x00\x0asecond", b"\x00\x0athird"]


class SendsEveryUdpPayload(unittest.TestCase):

    def test_ethernet_in_capture_order(self):
        received, proc = replay(pcap(flow_frames(PAYLOADS)))
        self.assertEqual(proc.returncode, 0, proc.stderr)
        self.assertEqual(received, PAYLOADS)
        sent, _port, skipped, reasons = summary_of(proc)
        self.assertEqual((sent, skipped, reasons), (3, 0, {}))

    def test_vlan_tagged_ethernet(self):
        received, proc = replay(pcap(flow_frames(PAYLOADS, framing=vlan_ethernet)))
        self.assertEqual(proc.returncode, 0, proc.stderr)
        self.assertEqual(received, PAYLOADS)

    def test_linux_cooked_v1(self):
        received, proc = replay(pcap(flow_frames(PAYLOADS, framing=sll), linktype=DLT_LINUX_SLL))
        self.assertEqual(proc.returncode, 0, proc.stderr)
        self.assertEqual(received, PAYLOADS)

    def test_linux_cooked_v2(self):
        received, proc = replay(pcap(flow_frames(PAYLOADS, framing=sll2), linktype=DLT_LINUX_SLL2))
        self.assertEqual(proc.returncode, 0, proc.stderr)
        self.assertEqual(received, PAYLOADS)

    def test_port_in_capture_is_irrelevant(self):
        # The SRX case: an export port no dissector knows must replay regardless.
        received, proc = replay(pcap(flow_frames(PAYLOADS, dport=31337)))
        self.assertEqual(proc.returncode, 0, proc.stderr)
        self.assertEqual(received, PAYLOADS)


class ReadsEveryPcapMagic(unittest.TestCase):

    def test_big_endian_microseconds(self):
        received, proc = replay(pcap(flow_frames(PAYLOADS), magic=MAGIC_BE_US))
        self.assertEqual(proc.returncode, 0, proc.stderr)
        self.assertEqual(received, PAYLOADS)

    def test_little_endian_nanoseconds(self):
        received, proc = replay(pcap(flow_frames(PAYLOADS), magic=MAGIC_LE_NS))
        self.assertEqual(proc.returncode, 0, proc.stderr)
        self.assertEqual(received, PAYLOADS)

    def test_big_endian_nanoseconds(self):
        received, proc = replay(pcap(flow_frames(PAYLOADS), magic=MAGIC_BE_NS))
        self.assertEqual(proc.returncode, 0, proc.stderr)
        self.assertEqual(received, PAYLOADS)


class SkipsAndCountsWhatItCannotSend(unittest.TestCase):

    def test_mixed_capture_reports_each_reason(self):
        frames = [
            ethernet(ipv4(udp(PAYLOADS[0]))),
            ethernet(ipv4(b"\x00" * 20, proto=6)),                  # TCP
            ethernet(ipv6(udp(b"over-v6")), ethertype=0x86dd),      # IPv6
            ethernet(b"\x00" * 28, ethertype=0x0806),               # ARP
            ethernet(ipv4(udp(PAYLOADS[1]), frag=0x2000)),          # more-fragments set
            ethernet(ipv4(udp(PAYLOADS[2]))),
        ]
        received, proc = replay(pcap(frames))
        self.assertEqual(proc.returncode, 0, proc.stderr)
        self.assertEqual(received, [PAYLOADS[0], PAYLOADS[2]])
        sent, _port, skipped, reasons = summary_of(proc)
        self.assertEqual(sent, 2)
        self.assertEqual(skipped, 4)
        self.assertEqual(reasons, {"not UDP": 1, "IPv6": 1, "not IPv4": 1, "fragmented": 1})

    def test_snaplen_truncation_is_counted(self):
        whole = ethernet(ipv4(udp(b"x" * 200)))
        frames = [(whole, 100), ethernet(ipv4(udp(PAYLOADS[0])))]
        received, proc = replay(pcap(frames, snaplen=100))
        self.assertEqual(proc.returncode, 0, proc.stderr)
        self.assertEqual(received, [PAYLOADS[0]])
        sent, _port, skipped, reasons = summary_of(proc)
        self.assertEqual((sent, skipped, reasons), (1, 1, {"truncated by snaplen": 1}))

    def test_udp_length_below_the_header_is_malformed_not_sent(self):
        # A length field under 8 would slice an empty payload and count it as sent.
        bogus = struct.pack("!HHHH", 40000, 9999, 0, 0) + b"payload"
        frames = [ethernet(ipv4(bogus)), ethernet(ipv4(udp(PAYLOADS[0])))]
        received, proc = replay(pcap(frames))
        self.assertEqual(proc.returncode, 0, proc.stderr)
        self.assertEqual(received, [PAYLOADS[0]])
        sent, _port, skipped, reasons = summary_of(proc)
        self.assertEqual((sent, skipped, reasons), (1, 1, {"malformed UDP length": 1}))

    def test_readable_capture_with_nothing_to_send_is_not_an_error(self):
        received, proc = replay(pcap([ethernet(ipv4(b"\x00" * 20, proto=6))]))
        self.assertEqual(proc.returncode, 0, proc.stderr)
        self.assertEqual(received, [])
        sent, _port, skipped, _reasons = summary_of(proc)
        self.assertEqual((sent, skipped), (0, 1))


class FiltersByDestinationPortOnRequest(unittest.TestCase):

    def test_match_port_keeps_only_that_port(self):
        frames = [
            ethernet(ipv4(udp(PAYLOADS[0], dport=9999))),
            ethernet(ipv4(udp(b"dns-answer", dport=53))),
            ethernet(ipv4(udp(PAYLOADS[1], dport=9999))),
        ]
        received, proc = replay(pcap(frames), "--match-port", "9999")
        self.assertEqual(proc.returncode, 0, proc.stderr)
        self.assertEqual(received, [PAYLOADS[0], PAYLOADS[1]])
        sent, _port, skipped, reasons = summary_of(proc)
        self.assertEqual((sent, skipped, reasons), (2, 1, {"port mismatch": 1}))

    def test_without_match_port_every_port_is_sent(self):
        frames = [
            ethernet(ipv4(udp(PAYLOADS[0], dport=9999))),
            ethernet(ipv4(udp(b"dns-answer", dport=53))),
        ]
        received, proc = replay(pcap(frames))
        self.assertEqual(proc.returncode, 0, proc.stderr)
        self.assertEqual(received, [PAYLOADS[0], b"dns-answer"])


class TargetIsSettable(unittest.TestCase):

    def test_summary_names_the_target_it_used(self):
        # replay() always passes --port; the summary must echo that port, not the default.
        _received, proc = replay(pcap(flow_frames(PAYLOADS[:1])))
        _sent, port, _skipped, _reasons = summary_of(proc)
        self.assertNotEqual(port, 9999)
        self.assertIn(f"127.0.0.1:{port}", proc.stderr)

    def test_default_target_is_the_documented_one(self):
        out = subprocess.run([sys.executable, str(SCRIPT), "--help"], capture_output=True, text=True)
        self.assertEqual(out.returncode, 0, out.stderr)
        self.assertIn("127.0.0.1", out.stdout)
        self.assertIn("9999", out.stdout)


class RefusesWhatItCannotRead(unittest.TestCase):

    def test_pcapng_is_refused_with_the_conversion(self):
        section_header = b"\x0a\x0d\x0d\x0a" + struct.pack("<I", 28) + b"\x4d\x3c\x2b\x1a" + b"\x00" * 16
        received, proc = replay(section_header)
        self.assertNotEqual(proc.returncode, 0)
        self.assertEqual(received, [])
        self.assertIn("pcapng", proc.stderr)
        self.assertIn("tcpdump -r", proc.stderr)

    def test_unknown_magic_is_refused(self):
        received, proc = replay(b"\xde\xad\xbe\xef" + b"\x00" * 20)
        self.assertNotEqual(proc.returncode, 0)
        self.assertEqual(received, [])
        self.assertIn("magic", proc.stderr)

    def test_unknown_link_type_is_refused_by_number(self):
        received, proc = replay(pcap(flow_frames(PAYLOADS), linktype=105))  # DLT_IEEE802_11
        self.assertNotEqual(proc.returncode, 0)
        self.assertEqual(received, [])
        self.assertIn("105", proc.stderr)

    def test_truncated_file_header_is_refused(self):
        received, proc = replay(MAGIC_LE_US + b"\x00" * 10)
        self.assertNotEqual(proc.returncode, 0)
        self.assertEqual(received, [])


class NeedsOnlyTheStandardLibrary(unittest.TestCase):

    def test_no_third_party_imports(self):
        source = SCRIPT.read_text()
        imported = set(re.findall(r"^(?:import|from)\s+([\w.]+)", source, re.MULTILINE))
        third_party = {m for m in imported if m.split(".")[0] not in sys.stdlib_module_names}
        self.assertEqual(third_party, set(), f"third-party imports found: {third_party}")


if __name__ == "__main__":
    unittest.main()
