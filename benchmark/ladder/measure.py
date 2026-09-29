# Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
# SPDX-License-Identifier: GPL-3.0-or-later

"""Measure one hold from Prometheus and ClickHouse (flow knee spec, section 3).

Every Prometheus query is evaluated once, at the hold's end, over a range of
the hold's length. An empty result raises Missing rather than reading as 0:
a renamed metric must never look like "no loss".
"""

import json
from dataclasses import dataclass
from urllib.parse import urlencode

import web
from rules import Step

UPLINK_BITS_PER_SECOND = 1e9
SCRAPE_INTERVAL_SECONDS = 10
PHASE_SECONDS = 120  # flush p99 compared between the first and last 2 minutes

# NIC counters are joined to the interface that carries a MAC from
# inventory.json. The range sits on the counter; the join comes after
# increase()/rate(), since a range cannot apply to a binary expression.
QUERIES = {
    "wire_tx_packets": 'sum(increase(node_network_transmit_packets_total{{service="loadgen"}}[{range}s]) * on(instance, device) group_left node_network_info{{service="loadgen", address="{loadgen_ingest}"}})',
    "wire_rx_packets": 'sum(increase(node_network_receive_packets_total{{service="sut"}}[{range}s]) * on(instance, device) group_left node_network_info{{service="sut", address="{sut_ingest}"}})',
    "kernel_drops": 'sum(increase(node_netstat_Udp_RcvbufErrors{{service="sut"}}[{range}s])) + sum(increase(node_netstat_Udp_InErrors{{service="sut"}}[{range}s]))',
    "listener_drops": 'sum(increase(riptide:lost_total{{stage="listener"}}[{range}s]))',
    "pipeline_drops": 'sum(increase(riptide:lost_total{{stage!="listener"}}[{range}s]))',
    # increase() drops __name__, so the parser series would share one labelset;
    # the name is kept as a label and the increase taken over a subquery.
    "dispatched_records": 'sum(increase(label_replace({{job="riptide", __name__=~"parsers_.+_recordsDispatched"}}, "parser", "$1", "__name__", "(.+)")[{range}s:10s]))',
    # A set riptide has no template for is dropped before any loss counter,
    # for example when a full session table refuses the exporter's session.
    "undecodable_sets": 'sum(increase(label_replace({{job="riptide", __name__=~"parsers_.+_undecodableSets"}}, "parser", "$1", "__name__", "(.+)")[{range}s:10s]))',
    "queue": "max(riptide:queue_depth / on(stage, component) riptide:queue_capacity)",
    "loadgen_cpu": '1 - avg(rate(node_cpu_seconds_total{{service="loadgen", mode="idle"}}[{range}s]))',
    "clickhouse_cpu": '1 - avg(rate(node_cpu_seconds_total{{service="clickhouse", mode="idle"}}[{range}s]))',
    "flush_p99_first": 'max(max_over_time(persister_batch_flush_seconds{{quantile="0.99"}}[{phase}s] offset {late}s))',
    "flush_p99_last": 'max(max_over_time(persister_batch_flush_seconds{{quantile="0.99"}}[{phase}s]))',
    "uplink_bytes_per_second": 'sum(rate(node_network_receive_bytes_total{{service="clickhouse"}}[{range}s]) * on(instance, device) group_left node_network_info{{service="clickhouse", address="{clickhouse_store}"}})'
                               ' + sum(rate(node_network_receive_bytes_total{{service="observe"}}[{range}s]) * on(instance, device) group_left node_network_info{{service="observe", address="{observe_observe}"}})',
    # Successful scrapes per target: up=0 is still a sample, so sum, not count.
    "min_scrapes": "min(sum_over_time(up[{range}s]))",
}


class Missing(Exception):
    pass


@dataclass(frozen=True)
class Lab:
    prometheus: str
    clickhouse: str
    clickhouse_password: str
    database: str
    macs: dict


def query_args(macs, range_seconds):
    return {"range": int(range_seconds), "phase": PHASE_SECONDS, "late": int(range_seconds) - PHASE_SECONDS,
            "loadgen_ingest": macs["loadgen"]["ingest"], "sut_ingest": macs["sut"]["ingest"],
            "clickhouse_store": macs["clickhouse"]["store"], "observe_observe": macs["observe"]["observe"]}


def _prom(lab, name, promql, at):
    status, raw = web.request("GET", f"{lab.prometheus}/api/v1/query?" + urlencode({"query": promql, "time": at}))
    if status != 200:
        raise Missing(f"{name}: Prometheus answered {status}: {raw[:200]!r}")
    result = json.loads(raw)["data"]["result"]
    if not result:
        raise Missing(f"{name}: empty result for {promql}")
    return float(result[0]["value"][1])


def _clickhouse(lab, sql):
    status, raw = web.request("POST", f"{lab.clickhouse}/", sql.encode(), auth=("default", lab.clickhouse_password))
    if status != 200:
        raise Missing(f"ClickHouse answered {status}: {raw[:200]!r}")
    return int(raw.decode().strip())


def _gap(hold, successes):
    """Longest hole the missed scrapes can leave: k missed in a row span (k + 1) intervals."""
    missed = int(hold // SCRAPE_INTERVAL_SECONDS) - int(successes)
    return (missed + 1) * SCRAPE_INTERVAL_SECONDS if missed > 0 else 0


def measure(lab, devices, start, end):
    hold = end - start
    args = query_args(lab.macs, hold)
    v = {name: _prom(lab, name, q.format(**args), end) for name, q in QUERIES.items() if name != "queue"}
    queue_start = _prom(lab, "queue_start", QUERIES["queue"], start)
    queue_end = _prom(lab, "queue_end", QUERIES["queue"], end)
    ms = lambda t: int(t * 1000)
    stored = _clickhouse(lab, f"SELECT count() FROM {lab.database}.flows "
                              f"WHERE receivedAt >= fromUnixTimestamp64Milli({ms(start)}) "
                              f"AND receivedAt < fromUnixTimestamp64Milli({ms(end)}) "
                              f"SETTINGS log_comment = 'bench-ladder'")
    foreign = _clickhouse(lab, "SELECT count() FROM system.query_log "
                               f"WHERE event_time >= toDateTime({int(start)}) AND event_time < toDateTime({int(end)}) "
                               "AND type = 'QueryFinish' AND query_kind = 'Select' AND is_initial_query "
                               "AND log_comment != 'bench-ladder' "
                               "SETTINGS log_comment = 'bench-ladder'")
    return Step(
        devices=devices, hold_seconds=hold,
        wire_tx_packets=v["wire_tx_packets"], wire_rx_packets=v["wire_rx_packets"],
        kernel_drops=v["kernel_drops"], listener_drops=v["listener_drops"], pipeline_drops=v["pipeline_drops"],
        undecodable_sets=v["undecodable_sets"],
        dispatched_records=v["dispatched_records"], stored_rows=stored,
        queue_start=queue_start, queue_end=queue_end,
        loadgen_cpu=v["loadgen_cpu"], clickhouse_cpu=v["clickhouse_cpu"],
        flush_p99_rising=v["flush_p99_last"] > 1.5 * v["flush_p99_first"],
        uplink_utilisation=v["uplink_bytes_per_second"] * 8 / UPLINK_BITS_PER_SECOND,
        scrape_gap_seconds=_gap(hold, v["min_scrapes"]),
        foreign_queries=foreign,
    )
