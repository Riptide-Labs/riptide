# Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
# SPDX-License-Identifier: GPL-3.0-or-later

import json
import unittest

import nl6
from fakes import FakeServer


class FakeNl6:
    """nl6 v0.32.0's envelope and status fields; batches complete at once unless told otherwise."""

    def __init__(self, busy_polls=0, fail=0, conflict_once=False):
        self.total, self.busy_polls, self.fail, self.conflict_once = 0, busy_polls, fail, conflict_once

    def __call__(self, method, path, body):
        if method == "GET" and path == "/api/v1/status":
            busy = self.busy_polls > 0
            self.busy_polls -= 1
            return 200, {"success": True, "message": "Success",
                         "data": {"total_devices": self.total, "create_batch_in_progress": busy}}
        if method == "POST" and path == "/api/v1/devices":
            if self.conflict_once:
                self.conflict_once = False
                return 409, {"success": False, "message": "create batch in progress"}
            req = json.loads(body)
            created = req["device_count"] - self.fail
            self.total += created
            return 201, {"success": True, "message": "Success",
                         "data": {"created": created, "requested": req["device_count"], "failed": self.fail}}
        return 404, {"success": False, "message": "not found"}


class Split(unittest.TestCase):

    def test_one_to_one_to_two(self):
        self.assertEqual(nl6.split(1000), [("netflow5", 250), ("netflow9", 250), ("ipfix", 500)])

    def test_rejects_a_fleet_not_divisible_by_4(self):
        with self.assertRaises(ValueError):
            nl6.split(1002)


class Grow(unittest.TestCase):

    def fleet(self, fake):
        server = FakeServer(fake)
        self.addCleanup(server.close)
        self.server = server
        return nl6.Fleet(server.url, collector="172.24.0.10:9999", poll_seconds=0)

    def posts(self):
        return [json.loads(b) for m, p, b in self.server.requests if m == "POST"]

    def test_first_step_creates_three_crs_x_batches_in_the_mix(self):
        fleet = self.fleet(FakeNl6())
        fleet.grow_to(1000)
        posts = self.posts()
        self.assertEqual([(p["flow"]["protocol"], p["device_count"]) for p in posts],
                         [("netflow5", 250), ("netflow9", 250), ("ipfix", 500)])
        self.assertTrue(all(p["resource_file"] == "cisco_crs_x.json" and p["netmask"] == "16"
                            and p["flow"]["collector"] == "172.24.0.10:9999" for p in posts))
        self.assertEqual(fleet.size(), 1000)

    def test_growing_adds_only_the_difference_on_fresh_addresses(self):
        fleet = self.fleet(FakeNl6())
        fleet.grow_to(1000)
        fleet.grow_to(2000)
        posts = self.posts()
        self.assertEqual([p["device_count"] for p in posts[3:]], [250, 250, 500])
        self.assertEqual(posts[0]["start_ip"], "172.27.0.1")
        self.assertEqual(posts[3]["start_ip"], "172.27.3.233")  # 172.27.0.1 + 1000

    def test_waits_while_a_batch_is_in_progress(self):
        fleet = self.fleet(FakeNl6(busy_polls=2))
        fleet.grow_to(4)
        polls = [r for r in self.server.requests if r[1] == "/api/v1/status"]
        self.assertGreaterEqual(len(polls), 3)

    def test_retries_a_409_once_the_gate_clears(self):
        fleet = self.fleet(FakeNl6(conflict_once=True))
        fleet.grow_to(4)
        self.assertEqual(fleet.size(), 4)

    def test_a_failed_device_stops_the_ladder(self):
        fleet = self.fleet(FakeNl6(fail=1))
        with self.assertRaises(nl6.FleetError):
            fleet.grow_to(1000)

    def test_shrinking_is_refused(self):
        fleet = self.fleet(FakeNl6())
        fleet.grow_to(1000)
        with self.assertRaises(nl6.FleetError):
            fleet.grow_to(500)


if __name__ == "__main__":
    unittest.main()
