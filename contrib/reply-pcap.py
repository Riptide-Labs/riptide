#!/usr/bin/env python3
# Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
# SPDX-License-Identifier: GPL-3.0-or-later

"""Replay the UDP payloads of a pcap into a local collector (issue #834).

Reads a classic libpcap file with the standard library only: no pyshark, no
tshark, no port heuristics. Every IPv4 UDP datagram's payload is sent to the
target in capture order with a fixed pacing delay. Which port the exporter
used in the capture is irrelevant unless --match-port asks for a filter.

Handled: microsecond and nanosecond magics in both byte orders; Ethernet
(with one 802.1Q tag), Linux cooked v1 and v2 framing; IPv4; UDP.
Skipped and counted: anything else at the link, network or transport layer,
IP fragments, and datagrams cut short by the capture's snapshot length.
Refused: pcapng, an unknown magic, an unknown link type.

Limits (see docs/docs/develop/run-and-debug.md): the collector sees the
exporter as the replay host's address, and flow timestamps stay the capture's.
"""

import argparse
import socket
import struct
import sys
import time
from collections import Counter

PCAPNG_SECTION_HEADER = b"\x0a\x0d\x0d\x0a"
MAGICS = {
    b"\xa1\xb2\xc3\xd4": ">",
    b"\xd4\xc3\xb2\xa1": "<",
    b"\xa1\xb2\x3c\x4d": ">",  # nanosecond variants; the timestamp unit is not used here
    b"\x4d\x3c\xb2\xa1": "<",
}

DLT_EN10MB = 1
DLT_LINUX_SLL = 113
DLT_LINUX_SLL2 = 276

ETHERTYPE_IPV4 = 0x0800
ETHERTYPE_IPV6 = 0x86DD
ETHERTYPE_VLAN = 0x8100
IPPROTO_UDP = 17


class Refused(Exception):
    """The file cannot be read at all; nothing was sent."""


def link_layer(linktype: int, frame: bytes):
    """Return (ethertype, network-layer bytes); (None, b"") for a frame too short to carry either."""
    if linktype == DLT_EN10MB:
        if len(frame) < 14:
            return None, b""
        ethertype = struct.unpack_from("!H", frame, 12)[0]
        offset = 14
        if ethertype == ETHERTYPE_VLAN and len(frame) >= 18:
            ethertype = struct.unpack_from("!H", frame, 16)[0]
            offset = 18
        return ethertype, frame[offset:]
    if linktype == DLT_LINUX_SLL:
        if len(frame) < 16:
            return None, b""
        return struct.unpack_from("!H", frame, 14)[0], frame[16:]
    # DLT_LINUX_SLL2; records() has already refused every other link type
    if len(frame) < 20:
        return None, b""
    return struct.unpack_from("!H", frame, 0)[0], frame[20:]


def udp_payload(ethertype, packet: bytes):
    """Return (skip reason, destination port, payload); reason is None when the payload is usable."""
    if ethertype == ETHERTYPE_IPV6:
        return "IPv6", None, None
    if ethertype != ETHERTYPE_IPV4 or len(packet) < 20:
        return "not IPv4", None, None
    ihl = (packet[0] & 0x0F) * 4
    flags_fragment = struct.unpack_from("!H", packet, 6)[0]
    protocol = packet[9]
    if flags_fragment & 0x3FFF:  # more-fragments flag or a non-zero offset
        return "fragmented", None, None
    if protocol != IPPROTO_UDP:
        return "not UDP", None, None
    if len(packet) < ihl + 8:
        return "truncated by snaplen", None, None
    dport, udp_length = struct.unpack_from("!HH", packet, ihl + 2)
    payload = packet[ihl + 8:ihl + udp_length]
    if len(payload) < udp_length - 8:
        return "truncated by snaplen", None, None
    return None, dport, payload


def records(data: bytes):
    """Yield (link type, frame bytes) per record of a classic libpcap file, refusing what it cannot read."""
    if data[:4] == PCAPNG_SECTION_HEADER:
        raise Refused("this is a pcapng file; convert it first: tcpdump -r in.pcapng -w out.pcap")
    order = MAGICS.get(data[:4])
    if order is None:
        raise Refused(f"unknown magic {data[:4].hex()}; not a libpcap file")
    if len(data) < 24:
        raise Refused("file is shorter than a libpcap header")
    linktype = struct.unpack_from(order + "I", data, 20)[0]
    if linktype not in (DLT_EN10MB, DLT_LINUX_SLL, DLT_LINUX_SLL2):
        raise Refused(f"link type {linktype} is not handled (Ethernet, Linux cooked v1 and v2 are)")
    offset = 24
    while offset + 16 <= len(data):
        incl_len = struct.unpack_from(order + "I", data, offset + 8)[0]
        offset += 16
        yield linktype, data[offset:offset + incl_len]
        offset += incl_len


def main(argv=None) -> int:
    parser = argparse.ArgumentParser(description=__doc__.split("\n\n")[0],
                                     formatter_class=argparse.ArgumentDefaultsHelpFormatter)
    parser.add_argument("capture", help="a classic libpcap file (not pcapng)")
    parser.add_argument("--host", default="127.0.0.1", help="where the collector listens")
    parser.add_argument("--port", type=int, default=9999, help="the collector's UDP port")
    parser.add_argument("--delay", type=float, default=0.01, help="seconds between sends; 0 disables pacing")
    parser.add_argument("--match-port", type=int, default=None,
                        help="send only datagrams whose destination port in the capture is this")
    args = parser.parse_args(argv)

    with open(args.capture, "rb") as f:
        data = f.read()

    sent = 0
    skipped = Counter()
    try:
        with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as sock:
            for linktype, frame in records(data):
                ethertype, packet = link_layer(linktype, frame)
                reason, dport, payload = udp_payload(ethertype, packet)
                if reason is None and args.match_port is not None and dport != args.match_port:
                    reason = "port mismatch"
                if reason is not None:
                    skipped[reason] += 1
                    continue
                sock.sendto(payload, (args.host, args.port))
                sent += 1
                if args.delay > 0:
                    time.sleep(args.delay)
    except Refused as e:
        print(f"refused: {e}", file=sys.stderr)
        return 2

    detail = ", ".join(f"{reason} {count}" for reason, count in skipped.items())
    summary = f"sent {sent} UDP payloads to {args.host}:{args.port}; skipped {sum(skipped.values())}"
    if detail:
        summary += f" ({detail})"
    print(summary, file=sys.stderr)
    return 0


if __name__ == "__main__":
    sys.exit(main())
