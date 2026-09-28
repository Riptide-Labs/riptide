# Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
# SPDX-License-Identifier: GPL-3.0-or-later

"""Where the ladder goes next (flow knee spec, section 3).

history is every judged step in run order as (devices, verdict), holding only
"pass" and "riptide-bound": any other verdict stops the ladder before the
search is asked again.
"""

from rules import KNEE_VERDICT, PASS

START = 1000
RESOLUTION = 0.05
BASELINE_SHARE = 0.80


class NonMonotonic(Exception):
    """A fleet passed above one that failed: the result is noise, not a knee."""


def _down4(x):
    return int(x) // 4 * 4


def _bounds(history):
    passes = [d for d, v in history if v == PASS]
    fails = [d for d, v in history if v == KNEE_VERDICT]
    if passes and fails and max(passes) > min(fails):
        raise NonMonotonic(f"{max(passes)} devices passed above {min(fails)}, which failed")
    return (max(passes) if passes else None), (min(fails) if fails else None)


def next_fleet(history):
    """The next fleet size, or None when the knee is found or no smaller fleet exists."""
    if not history:
        return START
    lo, hi = _bounds(history)
    if hi is None:
        return 2 * lo
    if lo is None:
        smaller = _down4(hi / 2)
        return smaller if smaller >= 4 and smaller < hi else None
    if hi - lo <= RESOLUTION * hi:
        return None
    mid = _down4((lo + hi) / 2)
    return mid if lo < mid < hi else None


def knee(history):
    """The largest passing fleet below the first failure, or None before any failure."""
    lo, hi = _bounds(history)
    return lo if hi is not None else None


def baseline(knee_devices):
    return max(4, _down4(BASELINE_SHARE * knee_devices))
