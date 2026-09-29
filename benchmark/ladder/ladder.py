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
import web
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


def _rewrite(path, records):
    """Drop a torn last line, so the next append starts on a line of its own."""
    Path(path).write_text("".join(json.dumps(r, sort_keys=True) + "\n" for r in records))


def _append(path, record):
    with open(path, "a") as f:
        f.write(json.dumps(record, sort_keys=True) + "\n")
        f.flush()


def _step(path, fleet, devices, measure_step, restart_loadgen, now, sleep, reference, warmup, hold, expected):
    # expected is the fleet the records account for. Anything else (a smaller
    # step, nl6 restarted without riptide, a grow that died half way) starts
    # from an empty nl6 and an empty riptide session table.
    if devices < expected or fleet.size() != expected:
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
        print(f"ladder: dropped a torn last line from {path}", file=sys.stderr)
        _rewrite(path, records)
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
        expected = records[-1]["devices"] if records else 0
        record = _step(path, fleet, devices, measure_step, restart_loadgen, now, sleep, reference, warmup, hold, expected)
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
    parser.add_argument("--sut-ssh", default="", help="ssh target that restarts riptide: 'bench@<sut mgmt>'")
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

    readyz = f"http://{s['sut']['addresses']['observe']}:8080/readyz"

    def ssh(target, command):
        subprocess.run(["ssh", "-o", "StrictHostKeyChecking=accept-new", "-o", "ConnectTimeout=10", target, command],
                       check=True, timeout=180)

    def wait_for(what, ok):
        deadline = time.monotonic() + 600
        while True:
            try:
                if ok():
                    return
            except Exception:
                pass
            if time.monotonic() > deadline:
                raise nl6.FleetError(f"{what} within 600 s")
            time.sleep(5)

    def restart_loadgen():
        # A smaller fleet needs an empty nl6 and an empty riptide session table:
        # restarted exporters come back on new source ports, and the old
        # sessions would hold riptide's source bound (4,096) for their 30 min
        # idle timeout, refusing the new exporters' templates.
        ssh(args.loadgen_ssh, "sudo systemctl restart nl6")
        wait_for("nl6 did not come back empty", lambda: fleet.size() == 0)
        ssh(args.sut_ssh, "sudo systemctl restart riptide")
        wait_for("riptide did not answer /readyz", lambda: web.request("GET", readyz, timeout=5)[0] == 200)

    measure_step = lambda devices, start, end: measure.measure(lab, devices, start, end)
    if args.baseline:
        records, torn = load(args.records)
        if torn:
            _rewrite(args.records, records)
        reference = next((r.get("flows_per_device") for r in records if r["devices"] == search.START and r["verdict"] == PASS), None)
        # A baseline always starts from an empty nl6 and riptide.
        record = _step(args.records, fleet, args.baseline, measure_step, restart_loadgen, time.time, time.sleep,
                       reference, WARMUP_SECONDS, args.hold, expected=0)
        print(f"baseline {args.baseline} devices over {args.hold:.0f} s: {record['verdict']} ({record['reason']})")
        return 0 if record["verdict"] == PASS else 1
    print(run(args.records, fleet, measure_step, restart_loadgen, time.time, time.sleep, hold=args.hold))
    return 0


if __name__ == "__main__":
    sys.exit(main())
