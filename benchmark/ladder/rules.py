# Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
# SPDX-License-Identifier: GPL-3.0-or-later

"""Judge one ladder step (flow knee spec, section 3).

Rules run in a fixed order and the first that matches is the verdict. A cause
outside riptide (a busy uplink, a busy ClickHouse) is checked before riptide's
own loss, so it is never mistaken for riptide's knee.
"""

from dataclasses import dataclass

PASS = "pass"
KNEE_VERDICT = "riptide-bound"
STOP_VERDICTS = frozenset({"inconclusive", "network-bound", "clickhouse-bound", "unexplained-loss", "nl6-bound"})

LOSS_TOLERANCE = 0.01
QUEUE_GROWTH = 2.0
QUEUE_FLOOR = 0.10
NL6_RATE_DROP = 0.05
NL6_CPU_MAX = 0.85
CLICKHOUSE_CPU_MAX = 0.90
UPLINK_MAX = 0.70
SCRAPE_GAP_MAX_SECONDS = 30


@dataclass(frozen=True)
class Step:
    """Everything measured over one hold. Counts are increases over the hold."""
    devices: int
    hold_seconds: float
    wire_tx_packets: float       # loadgen ingest NIC, transmitted
    wire_rx_packets: float       # SUT ingest NIC, received
    kernel_drops: float          # SUT Udp_RcvbufErrors + Udp_InErrors
    listener_drops: float        # riptide:lost_total{stage="listener"}
    pipeline_drops: float        # riptide:lost_total, every other stage
    dispatched_records: float    # sum of parsers_*_recordsDispatched
    stored_rows: float           # ClickHouse count() over the hold's receivedAt window
    queue_start: float           # fullest queue, depth / capacity, at the hold's start
    queue_end: float             # the same at its end
    loadgen_cpu: float           # busy share of the loadgen VM, 0..1
    clickhouse_cpu: float        # busy share of the ClickHouse VM, 0..1
    flush_p99_rising: bool       # flush p99 in the last 2 min > 1.5x the first 2 min
    uplink_utilisation: float    # of 1 Gbit/s on the libvirt host, 0..1
    scrape_gap_seconds: float    # longest scrape gap in the hold
    foreign_queries: int         # ClickHouse SELECTs in the hold not issued by the driver

    @property
    def flows_per_device(self):
        return self.dispatched_records / self.hold_seconds / self.devices


def _mismatch(a, b):
    return abs(a - b) > LOSS_TOLERANCE * max(a, b)


def _queue_growing(s):
    return not (s.queue_end <= QUEUE_GROWTH * s.queue_start or s.queue_end < QUEUE_FLOOR)


def verdict(s, reference_fpd):
    """(verdict, reason) for step s; reference_fpd is the first step's flows per device, None on the first."""
    if s.scrape_gap_seconds > SCRAPE_GAP_MAX_SECONDS:
        return "inconclusive", f"scrape gap of {s.scrape_gap_seconds:.0f} s"
    if s.foreign_queries > 0:
        return "inconclusive", f"foreign_queries={s.foreign_queries}: a dashboard or client read ClickHouse during the hold"
    if s.dispatched_records == 0:
        return "inconclusive", "no flows reached riptide's parsers"
    if s.uplink_utilisation > UPLINK_MAX:
        return "network-bound", f"uplink at {s.uplink_utilisation:.0%}"
    growing = _queue_growing(s)
    if growing and (s.clickhouse_cpu > CLICKHOUSE_CPU_MAX or s.flush_p99_rising):
        return "clickhouse-bound", f"clickhouse_cpu={s.clickhouse_cpu:.0%} flush_p99_rising={s.flush_p99_rising} with a growing queue"
    causes = [f"{name}={value:g}" for name, value in
              (("kernel_drops", s.kernel_drops), ("listener_drops", s.listener_drops), ("pipeline_drops", s.pipeline_drops))
              if value > 0]
    if growing:
        causes.append(f"queue {s.queue_start:.0%} -> {s.queue_end:.0%}")
    if causes:
        return KNEE_VERDICT, ", ".join(causes)
    if _mismatch(s.wire_tx_packets, s.wire_rx_packets):
        return "unexplained-loss", f"wire: {s.wire_tx_packets:g} sent, {s.wire_rx_packets:g} received"
    if _mismatch(s.dispatched_records, s.stored_rows):
        return "unexplained-loss", f"store: {s.dispatched_records:g} dispatched, {s.stored_rows:g} stored"
    if reference_fpd is not None and s.flows_per_device < (1 - NL6_RATE_DROP) * reference_fpd:
        return "nl6-bound", f"{s.flows_per_device:.2f} flows/s per device, reference {reference_fpd:.2f}"
    if s.loadgen_cpu > NL6_CPU_MAX:
        return "nl6-bound", f"loadgen_cpu={s.loadgen_cpu:.0%}"
    return PASS, "clean"
