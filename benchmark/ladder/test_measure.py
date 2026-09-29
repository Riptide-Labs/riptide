# Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
# SPDX-License-Identifier: GPL-3.0-or-later

import json
import unittest
from urllib.parse import parse_qs, urlparse

import measure
from fakes import FakeServer

MACS = {"sut": {"ingest": "02:00:00:00:00:10"}, "loadgen": {"ingest": "02:00:00:00:00:11"},
        "clickhouse": {"store": "02:00:00:00:00:12", "observe": "02:00:00:00:00:13"},
        "observe": {"observe": "02:00:00:00:00:14"}}


def vector(value):
    return {"status": "success", "data": {"resultType": "vector", "result": [{"metric": {}, "value": [0, str(value)]}]}}


class Prometheus:
    """Answers every query with the value its name maps to; unknown queries get an empty vector."""

    def __init__(self, values):
        self.values = values
        self.seen = []

    def __call__(self, method, path, body):
        q = parse_qs(urlparse(path).query)["query"][0]
        self.seen.append(q)
        for name, promql in measure.QUERIES.items():
            if q == promql.format(**measure.query_args(MACS, 600)) and name in self.values:
                return 200, vector(self.values[name])
        return 200, {"status": "success", "data": {"resultType": "vector", "result": []}}


CLEAN = {"wire_tx_packets": 100000, "wire_rx_packets": 100000, "kernel_drops": 0, "listener_drops": 0,
         "pipeline_drops": 0, "dispatched_records": 2400000, "queue": 0.01, "loadgen_cpu": 0.4,
         "clickhouse_cpu": 0.3, "flush_p99_first": 0.05, "flush_p99_last": 0.05, "uplink_bytes_per_second": 12.5e6,
         "min_scrapes": 60}


class Measure(unittest.TestCase):

    def lab(self, values, rows=2400000, foreign=0):
        prom = FakeServer(Prometheus(values))
        self.addCleanup(prom.close)

        def clickhouse(method, path, body):
            sql = body.decode()
            if "system.query_log" in sql:
                return 200, f"{foreign}\n"
            return 200, f"{rows}\n"

        ch = FakeServer(clickhouse)
        self.addCleanup(ch.close)
        self.ch = ch
        return measure.Lab(prometheus=prom.url, clickhouse=ch.url, clickhouse_password="pw",
                           database="riptide_knee", macs=MACS)

    def test_a_clean_hold_becomes_a_step(self):
        step = measure.measure(self.lab(CLEAN), devices=1000, start=1000.0, end=1600.0)
        self.assertEqual(step.dispatched_records, 2400000)
        self.assertEqual(step.stored_rows, 2400000)
        self.assertAlmostEqual(step.uplink_utilisation, 0.1)
        self.assertEqual(step.scrape_gap_seconds, 0)
        self.assertFalse(step.flush_p99_rising)

    def test_the_store_count_uses_the_hold_window_and_tags_itself(self):
        measure.measure(self.lab(CLEAN), devices=1000, start=1000.0, end=1600.0)
        sql = [b.decode() for m, p, b in self.ch.requests if b"flows" in b][0]
        self.assertIn("riptide_knee.flows", sql)
        self.assertIn("receivedAt >= fromUnixTimestamp64Milli(1000000)", sql)
        self.assertIn("receivedAt < fromUnixTimestamp64Milli(1600000)", sql)
        self.assertIn("log_comment = 'bench-ladder'", sql)

    def test_an_empty_result_is_missing_not_zero(self):
        values = dict(CLEAN)
        del values["listener_drops"]
        with self.assertRaises(measure.Missing) as caught:
            measure.measure(self.lab(values), devices=1000, start=1000.0, end=1600.0)
        self.assertIn("listener_drops", str(caught.exception))

    def test_a_rising_flush_p99(self):
        values = dict(CLEAN, flush_p99_last=0.08)
        self.assertTrue(measure.measure(self.lab(values), 1000, 1000.0, 1600.0).flush_p99_rising)

    def test_missing_scrapes_become_a_gap(self):
        values = dict(CLEAN, min_scrapes=56)
        self.assertEqual(measure.measure(self.lab(values), 1000, 1000.0, 1600.0).scrape_gap_seconds, 40)

    def test_several_parsers_are_summed_without_a_label_clash(self):
        # increase() drops __name__, so the ipfix, netflow5 and netflow9
        # parser series collide unless the name is kept in a label first
        # (live on Prometheus v3.15.0: "vector cannot contain metrics with the
        # same labelset").
        q = measure.QUERIES["dispatched_records"]
        self.assertIn('label_replace({{job="riptide", __name__=~"parsers_.+_recordsDispatched"}}, "parser", "$1", "__name__", "(.+)")', q)
        self.assertIn("[{range}s:10s]", q)

    def test_flush_p99_reads_the_exported_timer(self):
        # riptide exports the flush timer as a summary in seconds (read from a
        # live /metrics of 0.17.0): persister_batch_flush_seconds{quantile="0.99"}.
        for name in ("flush_p99_first", "flush_p99_last"):
            self.assertIn('persister_batch_flush_seconds{{quantile="0.99"}}', measure.QUERIES[name])

    def test_foreign_clickhouse_queries_are_counted(self):
        self.assertEqual(measure.measure(self.lab(CLEAN, foreign=3), 1000, 1000.0, 1600.0).foreign_queries, 3)


if __name__ == "__main__":
    unittest.main()
