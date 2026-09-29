#!/usr/bin/env python3
# Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
# SPDX-License-Identifier: GPL-3.0-or-later

"""Find riptide's flow knee, or hold a baseline (flow knee spec, sections 3 and 4).

Runs on the lab's observability VM (benchmark/bin/ladder-run starts it). Every
judged step is one JSON line in --records; a restarted driver resumes after
the last complete line. Any verdict other than pass or riptide-bound stops the
ladder and waits for a human.
"""

import argparse
import dataclasses
import json
import subprocess
import sys
import time
from pathlib import Path

import measure
import nl6
import search
from rules import KNEE_VERDICT, PASS, STOP_VERDICTS, verdict

WARMUP_SECONDS = 120
HOLD_SECONDS = 600
# Rows for the hold's last seconds are still in riptide's batch queue when it
# ends (batch latency, flush, nl6's 5 s burst): count ClickHouse after this.
SETTLE_SECONDS = 30


def load(path):
    """(records, torn): every complete line; torn is True when the last line was cut short."""
    p = Path(path)
    if not p.exists():
        return [], False
    records, torn = [], False
    for line in p.read_text().splitlines():
        try:
            records.append(json.loads(line))
        except ValueError:
            torn = True
    return records, torn


def _append(path, record):
    with open(path, "a") as f:
        f.write(json.dumps(record, sort_keys=True) + "\n")
        f.flush()


def _step(path, fleet, devices, measure_step, restart_loadgen, now, sleep, reference, warmup, hold):
    if devices < fleet.size():
        restart_loadgen()
    fleet.grow_to(devices)
    sleep(warmup)
    start = now()
    sleep(hold)
    end = now()
    sleep(SETTLE_SECONDS)
    try:
        s = measure_step(devices, start, end)
        name, reason = verdict(s, reference)
        fields = dataclasses.asdict(s) | {"flows_per_device": s.flows_per_device}
    except measure.Missing as missing:
        name, reason, fields = "inconclusive", str(missing), {"devices": devices}
    record = fields | {"devices": devices, "start": start, "end": end, "verdict": name, "reason": reason}
    _append(path, record)
    return record


def run(path, fleet, measure_step, restart_loadgen, now, sleep, warmup=WARMUP_SECONDS, hold=HOLD_SECONDS):
    """The knee search; returns why it stopped."""
    records, torn = load(path)
    if torn:
        print(f"ladder: ignored a torn last line in {path}", file=sys.stderr)
    for r in records:
        if r["verdict"] in STOP_VERDICTS:
            return f"stopped earlier at {r['devices']} devices: {r['verdict']} ({r['reason']})"
    while True:
        history = [(r["devices"], r["verdict"]) for r in records]
        try:
            devices = search.next_fleet(history)
        except search.NonMonotonic as noise:
            return f"stopped: {noise}"
        if devices is None:
            k = search.knee(history)
            return f"knee at {k} devices; baseline {search.baseline(k)} devices" if k else "no fleet passes"
        reference = records[0].get("flows_per_device") if records and records[0]["verdict"] == PASS else None
        record = _step(path, fleet, devices, measure_step, restart_loadgen, now, sleep, reference, warmup, hold)
        records.append(record)
        if record["verdict"] in STOP_VERDICTS:
            return f"stopped at {devices} devices: {record['verdict']} ({record['reason']})"


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--inventory", required=True)
    parser.add_argument("--clickhouse-password-file", required=True)
    parser.add_argument("--records", required=True)
    parser.add_argument("--baseline", type=int, help="hold this fleet once instead of searching")
    parser.add_argument("--hold", type=float, default=HOLD_SECONDS)
    parser.add_argument("--loadgen-ssh", default="", help="ssh target that restarts nl6: 'bench@<loadgen mgmt>'")
    args = parser.parse_args()

    inv = json.loads(Path(args.inventory).read_text())
    s = inv["services"]
    lab = measure.Lab(prometheus="http://localhost:9090",
                      clickhouse=f"http://{s['clickhouse']['addresses']['observe']}:8123",
                      clickhouse_password=Path(args.clickhouse_password_file).read_text().strip(),
                      database="riptide_knee",
                      macs={name: svc["macs"] for name, svc in s.items()})
    fleet = nl6.Fleet(f"http://{s['loadgen']['addresses']['mgmt']}:8080",
                      collector=f"{s['sut']['addresses']['ingest']}:9999")

    def restart_loadgen():
        subprocess.run(["ssh", "-o", "StrictHostKeyChecking=accept-new", args.loadgen_ssh, "sudo systemctl restart nl6"], check=True)
        deadline = time.monotonic() + 600
        while True:
            try:
                if fleet.size() == 0:
                    return
            except Exception:
                pass
            if time.monotonic() > deadline:
                raise nl6.FleetError("nl6 did not come back empty within 600 s")
            time.sleep(5)

    measure_step = lambda devices, start, end: measure.measure(lab, devices, start, end)
    if args.baseline:
        records, _ = load(args.records)
        reference = next((r.get("flows_per_device") for r in records if r["devices"] == search.START and r["verdict"] == PASS), None)
        record = _step(args.records, fleet, args.baseline, measure_step, restart_loadgen, time.time, time.sleep,
                       reference, WARMUP_SECONDS, args.hold)
        print(f"baseline {args.baseline} devices over {args.hold:.0f} s: {record['verdict']} ({record['reason']})")
        return 0 if record["verdict"] == PASS else 1
    print(run(args.records, fleet, measure_step, restart_loadgen, time.time, time.sleep, hold=args.hold))
    return 0


if __name__ == "__main__":
    sys.exit(main())
