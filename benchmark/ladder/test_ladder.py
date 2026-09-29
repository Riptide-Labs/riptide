# Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
# SPDX-License-Identifier: GPL-3.0-or-later

import dataclasses
import json
import tempfile
import unittest
from pathlib import Path

import ladder
from test_rules import CLEAN


class FakeFleet:
    def __init__(self):
        self.devices, self.grows = 0, []

    def size(self):
        return self.devices

    def grow_to(self, devices):
        if devices < self.devices:
            raise AssertionError("shrink without restart")
        self.grows.append(devices)
        self.devices = devices


class Clock:
    def __init__(self):
        self.t = 1000.0

    def now(self):
        return self.t

    def sleep(self, s):
        self.t += s


def by_capacity(capacity):
    """riptide keeps up to capacity devices, drops above it."""
    def measure_step(devices, start, end):
        drops = 0 if devices <= capacity else 10
        return dataclasses.replace(CLEAN, devices=devices, dispatched_records=4.0 * 600 * devices,
                                   stored_rows=4.0 * 600 * devices, listener_drops=drops)
    return measure_step


class Run(unittest.TestCase):

    def setUp(self):
        self.path = Path(tempfile.mkdtemp()) / "knee.jsonl"
        self.clock = Clock()
        self.fleet = FakeFleet()
        self.restarts = 0

    def restart(self):
        self.restarts += 1
        self.fleet.devices = 0

    def go(self, capacity):
        return ladder.run(str(self.path), self.fleet, by_capacity(capacity), self.restart,
                          self.clock.now, self.clock.sleep)

    def test_finds_the_knee_and_writes_one_record_per_step(self):
        reason = self.go(3300)
        records, torn = ladder.load(str(self.path))
        self.assertFalse(torn)
        self.assertIn("knee at 3248 devices", reason)
        self.assertEqual([r["devices"] for r in records][:3], [1000, 2000, 4000])
        self.assertTrue(all("verdict" in r and "reason" in r and "start" in r and "end" in r for r in records))

    def test_the_hold_starts_after_the_warmup(self):
        self.go(1500)
        first = ladder.load(str(self.path))[0][0]
        self.assertEqual(first["end"] - first["start"], 600)
        self.assertEqual(first["start"], 1000.0 + 120)

    def test_the_store_is_counted_after_the_pipeline_settles(self):
        # Counting at the hold's end missed the flows still in riptide's
        # batch queue: 3.4% short on the live lab at 1,000 devices.
        seen = []

        def measure_step(devices, start, end):
            seen.append((end, self.clock.now()))
            return dataclasses.replace(CLEAN, devices=devices)

        ladder.run(str(self.path), self.fleet, measure_step, self.restart, self.clock.now, self.clock.sleep)
        end, measured_at = seen[0]
        self.assertGreaterEqual(measured_at - end, ladder.SETTLE_SECONDS)
        self.assertGreaterEqual(ladder.SETTLE_SECONDS, 30)

    def test_a_smaller_fleet_restarts_the_loadgen_first(self):
        self.go(3300)
        self.assertGreaterEqual(self.restarts, 1)

    def test_a_stop_verdict_stops_the_ladder(self):
        def busy_uplink(devices, start, end):
            return dataclasses.replace(CLEAN, devices=devices, uplink_utilisation=0.9)
        reason = ladder.run(str(self.path), self.fleet, busy_uplink, self.restart, self.clock.now, self.clock.sleep)
        self.assertIn("network-bound", reason)
        self.assertEqual(len(ladder.load(str(self.path))[0]), 1)

    def test_resume_skips_finished_steps(self):
        self.go(3300)
        self.fleet = FakeFleet()
        reason = self.go(3300)
        self.assertIn("knee at 3248 devices", reason)
        self.assertEqual(self.fleet.grows, [], "a finished campaign re-ran a step")

    def test_resume_ignores_a_torn_last_line(self):
        self.path.write_text(json.dumps({"devices": 1000, "verdict": "pass", "reason": "clean",
                                         "start": 1, "end": 601, "flows_per_device": 4.0}) + "\n{\"devices\": 20")
        records, torn = ladder.load(str(self.path))
        self.assertTrue(torn)
        self.assertEqual(len(records), 1)

    def test_a_measurement_gap_is_an_inconclusive_record(self):
        def missing(devices, start, end):
            raise ladder.measure.Missing("listener_drops: empty result")
        reason = ladder.run(str(self.path), self.fleet, missing, self.restart, self.clock.now, self.clock.sleep)
        record = ladder.load(str(self.path))[0][0]
        self.assertEqual(record["verdict"], "inconclusive")
        self.assertIn("listener_drops", record["reason"])
        self.assertIn("inconclusive", reason)


if __name__ == "__main__":
    unittest.main()
