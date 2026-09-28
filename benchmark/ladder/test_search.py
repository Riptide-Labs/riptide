# Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
# SPDX-License-Identifier: GPL-3.0-or-later

import unittest

from search import NonMonotonic, START, baseline, knee, next_fleet

P, F = "pass", "riptide-bound"


class Search(unittest.TestCase):

    def test_starts_at_1000(self):
        self.assertEqual(next_fleet([]), 1000)
        self.assertEqual(START, 1000)

    def test_doubles_while_passing(self):
        self.assertEqual(next_fleet([(1000, P), (2000, P)]), 4000)

    def test_bisects_after_the_first_failure(self):
        self.assertEqual(next_fleet([(1000, P), (2000, P), (4000, F)]), 3000)

    def test_bisection_lands_on_multiples_of_4(self):
        self.assertEqual(next_fleet([(1000, P), (2000, P), (4000, F), (3000, P), (3500, F), (3248, P)]) % 4, 0)

    def test_stops_when_the_gap_is_within_5_percent(self):
        history = [(1000, P), (2000, P), (4000, F), (3000, P), (3500, F), (3248, P), (3372, F)]
        self.assertIsNone(next_fleet(history))
        self.assertEqual(knee(history), 3248)

    def test_halves_when_the_first_step_fails(self):
        self.assertEqual(next_fleet([(1000, F)]), 500)
        self.assertEqual(next_fleet([(1000, F), (500, F)]), 248)

    def test_bisects_upward_after_halving(self):
        self.assertEqual(next_fleet([(1000, F), (500, P)]), 748)

    def test_a_pass_above_a_failure_stops_the_search(self):
        with self.assertRaises(NonMonotonic):
            next_fleet([(1000, P), (2000, F), (1500, P), (1800, F), (1900, P)])

    def test_no_knee_before_a_failure(self):
        self.assertIsNone(knee([(1000, P), (2000, P)]))

    def test_baseline_is_80_percent_rounded_down_to_4(self):
        self.assertEqual(baseline(3248), 2596)
        self.assertEqual(baseline(3248) % 4, 0)

    def test_the_fleet_never_drops_below_4(self):
        self.assertEqual(next_fleet([(1000, F), (500, F), (248, F), (124, F), (60, F), (28, F), (12, F), (4, F)]), None)


if __name__ == "__main__":
    unittest.main()
