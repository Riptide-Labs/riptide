# Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
# SPDX-License-Identifier: GPL-3.0-or-later

"""Each verdict rule of spec section 3, from a clean step changed in one field."""

import dataclasses
import unittest

from rules import PASS, Step, verdict

CLEAN = Step(
    devices=1000, hold_seconds=600,
    wire_tx_packets=100_000, wire_rx_packets=100_000,
    kernel_drops=0, listener_drops=0, pipeline_drops=0,
    dispatched_records=2_400_000, stored_rows=2_400_000,
    queue_start=0.01, queue_end=0.01,
    loadgen_cpu=0.40, clickhouse_cpu=0.30, flush_p99_rising=False,
    uplink_utilisation=0.10, scrape_gap_seconds=0, foreign_queries=0,
)
REF = 4.0  # flows per device per second at the 1,000-device step


def judged(**changes):
    return verdict(dataclasses.replace(CLEAN, **changes), REF)[0]


class Verdicts(unittest.TestCase):

    def test_a_clean_step_passes(self):
        self.assertEqual(verdict(CLEAN, REF), (PASS, "clean"))

    def test_the_first_step_has_no_reference_and_can_pass(self):
        self.assertEqual(verdict(CLEAN, None)[0], PASS)

    def test_socket_drops_are_riptide_bound(self):
        self.assertEqual(judged(listener_drops=1), "riptide-bound")

    def test_kernel_receive_errors_are_riptide_bound(self):
        self.assertEqual(judged(kernel_drops=1), "riptide-bound")

    def test_pipeline_drops_are_riptide_bound(self):
        self.assertEqual(judged(pipeline_drops=1), "riptide-bound")

    def test_a_queue_that_doubles_above_the_floor_is_riptide_bound(self):
        self.assertEqual(judged(queue_start=0.10, queue_end=0.21), "riptide-bound")

    def test_a_queue_that_doubles_below_the_floor_passes(self):
        self.assertEqual(judged(queue_start=0.02, queue_end=0.09), PASS)

    def test_a_queue_that_grows_less_than_double_passes(self):
        self.assertEqual(judged(queue_start=0.30, queue_end=0.59), PASS)

    def test_wire_loss_over_one_percent_is_unexplained(self):
        self.assertEqual(judged(wire_rx_packets=98_900), "unexplained-loss")

    def test_wire_loss_within_one_percent_passes(self):
        self.assertEqual(judged(wire_rx_packets=99_100), PASS)

    def test_a_store_mismatch_over_one_percent_is_unexplained(self):
        self.assertEqual(judged(stored_rows=2_370_000), "unexplained-loss")

    def test_fewer_flows_per_device_is_nl6_bound(self):
        # 3.7 flows/s per device is 7.5% under the reference of 4.0
        self.assertEqual(judged(dispatched_records=3.7 * 600 * 1000, stored_rows=3.7 * 600 * 1000), "nl6-bound")

    def test_a_busy_loadgen_is_nl6_bound(self):
        self.assertEqual(judged(loadgen_cpu=0.86), "nl6-bound")

    def test_riptide_loss_wins_over_a_busy_loadgen(self):
        self.assertEqual(judged(loadgen_cpu=0.95, listener_drops=5), "riptide-bound")

    def test_a_busy_clickhouse_with_a_growing_queue_is_clickhouse_bound(self):
        self.assertEqual(judged(clickhouse_cpu=0.91, queue_start=0.1, queue_end=0.5), "clickhouse-bound")

    def test_a_rising_flush_p99_with_a_growing_queue_is_clickhouse_bound(self):
        self.assertEqual(judged(flush_p99_rising=True, queue_start=0.1, queue_end=0.5), "clickhouse-bound")

    def test_a_busy_clickhouse_with_a_steady_queue_passes(self):
        self.assertEqual(judged(clickhouse_cpu=0.95), PASS)

    def test_a_busy_uplink_is_network_bound_even_with_riptide_loss(self):
        self.assertEqual(judged(uplink_utilisation=0.71, listener_drops=5), "network-bound")

    def test_a_scrape_gap_is_inconclusive(self):
        self.assertEqual(judged(scrape_gap_seconds=31), "inconclusive")

    def test_a_foreign_clickhouse_query_is_inconclusive(self):
        self.assertEqual(judged(foreign_queries=1), "inconclusive")

    def test_no_flows_is_inconclusive(self):
        self.assertEqual(judged(dispatched_records=0, stored_rows=0), "inconclusive")

    def test_every_non_pass_reason_names_its_cause(self):
        verdict_name, reason = verdict(dataclasses.replace(CLEAN, listener_drops=3), REF)
        self.assertIn("listener_drops=3", reason)


if __name__ == "__main__":
    unittest.main()
