# Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
# SPDX-License-Identifier: GPL-3.0-or-later

"""Grow an nl6 CRS-X fleet over its REST API (nl6 v0.32.0).

Devices export flows from the moment they exist, so no scenario is used. A
fleet only grows here: for a smaller fleet the ladder restarts nl6 and riptide
(ladder.py) and grows again from zero, since nl6 cannot remove devices cleanly.
"""

import ipaddress
import json
import time

import web

MIX = (("netflow5", 1), ("netflow9", 1), ("ipfix", 2))
RESOURCE_FILE = "cisco_crs_x.json"


class FleetError(Exception):
    pass


def split(devices):
    if devices % 4:
        raise ValueError(f"{devices} devices is not a multiple of 4")
    return [(protocol, devices * share // 4) for protocol, share in MIX]


class Fleet:

    def __init__(self, base_url, collector, exporters_start="172.27.0.1", poll_seconds=2, patience_seconds=1800):
        self.base = base_url.rstrip("/")
        self.collector = collector
        self.start = ipaddress.IPv4Address(exporters_start)
        self.poll_seconds = poll_seconds
        self.patience = patience_seconds

    def _status(self):
        status, raw = web.request("GET", f"{self.base}/api/v1/status")
        if status != 200:
            raise FleetError(f"nl6 status answered {status}: {raw[:200]!r}")
        return json.loads(raw)["data"]

    def _wait_idle(self):
        deadline = time.monotonic() + self.patience
        while self._status()["create_batch_in_progress"]:
            if time.monotonic() > deadline:
                raise FleetError("nl6 is still creating a batch after the patience window")
            time.sleep(self.poll_seconds)

    def size(self):
        return self._status()["total_devices"]

    def grow_to(self, devices):
        have = self.size()
        if devices < have:
            raise FleetError(f"the fleet has {have} devices and cannot shrink to {devices}; restart nl6 first")
        if devices == have:
            return
        next_ip = self.start + have
        for protocol, count in split(devices - have):
            batch = {"start_ip": str(next_ip), "device_count": count, "netmask": "16",
                     "resource_file": RESOURCE_FILE,
                     "flow": {"collector": self.collector, "protocol": protocol}}
            deadline = time.monotonic() + self.patience
            while True:
                self._wait_idle()
                status, raw = web.request("POST", f"{self.base}/api/v1/devices", batch)
                if status != 409:
                    break
                # 409 is nl6's batch gate; one that outlasts the patience is a
                # real conflict, such as overlapping addresses.
                if time.monotonic() > deadline:
                    raise FleetError(f"nl6 kept answering 409 to a {protocol} batch: {raw[:300]!r}")
                time.sleep(self.poll_seconds)
            if status not in (200, 201):
                raise FleetError(f"nl6 refused a {protocol} batch ({status}): {raw[:300]!r}")
            data = json.loads(raw)["data"]
            if data["failed"] or data["created"] != count:
                raise FleetError(f"nl6 created {data['created']} of {count} {protocol} devices, {data['failed']} failed")
            next_ip += count
        self._wait_idle()
        if self.size() != devices:
            raise FleetError(f"nl6 reports {self.size()} devices, expected {devices}")
