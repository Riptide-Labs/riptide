/*
 * Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.riptide.repository.clickhouse;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Locale;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What a drifted rollup's line says about what this start did with it (#657).
 *
 * <p>#654 removed a false promise from these lines and left the gap deliberately. Two attempts to
 * close it by inferring permanence from the <em>drift shape</em> were reverted, the second telling
 * operators to drop a view and target where a lossless {@code ALTER} would have done. A third
 * draft, reviewed before merge, reintroduced the promise from the other direction: it offered
 * {@code riptide onboard} as the remedy, which runs the same planner that refuses a shrunk sorting
 * key, a corrected aggregate, a summed missing measure and a column outside the sorting key.</p>
 *
 * <p>So this says only what happened, never what would help, and every sentence it can produce is
 * pinned here — including that none of them promises anything.</p>
 */
class RepairOutlookTest {

    private static final String ROLLUP = "flows_by_application_1m";
    private static final Set<String> NONE = Set.of();
    private static final Set<String> THIS = Set.of(ROLLUP);

    /**
     * A set holding a <em>different</em> rollup, which is what makes these assertions per-rollup.
     *
     * <p>Without it every set is either empty or holds the rollup under test, and an implementation
     * reading {@code !set.isEmpty()} instead of {@code set.contains(rollup)} would satisfy every
     * assertion. The per-rollup property is the entire reason these sets are passed rather than a
     * start-wide flag.</p>
     */
    private static final Set<String> ANOTHER = Set.of("flows_by_exporter_iface_1m");

    /** A refused rollup was already told the reason and the remedy; saying it twice is the #654 error. */
    @Test
    void aRefusedRollupGetsNothingFurtherHere() {
        assertThat(ClickhouseRepository.repairOutlook(ROLLUP, NONE, NONE, THIS,
                ClickhouseRepository.RepairPosture.PLANNED))
                .as("planTargetRepair already logged its reason and remedy; a second line here is"
                        + " the third place that remembers a condition")
                .isEmpty();
    }

    /**
     * An unread catalog is reported as such, from the input the real call path builds.
     *
     * <p><b>`unrepaired` holds the rollup on purpose.</b> When the catalog cannot be read,
     * {@code planViewCreation} declines every rollup and {@code start()} adds them all to
     * {@code unrepaired} — so that set then means "not repaired", not "tried and failed". An earlier
     * draft tested {@code unrepaired} first and passed a test built with it empty: a combination
     * production cannot produce, pinning a branch that could never render while the sentence that
     * did render claimed a repair had been attempted.</p>
     */
    @Test
    void anUnreadCatalogSaysSoEvenThoughEveryRollupLooksUnrepaired() {
        assertThat(ClickhouseRepository.repairOutlook(ROLLUP, NONE, THIS, NONE,
                ClickhouseRepository.RepairPosture.CATALOG_UNREADABLE))
                .as("this is the combination start() actually assembles for an unread catalog")
                .contains("No repair was planned for any rollup")
                .as("and names no cause: planTargetRepair also returns empty on interrupt, where"
                        + " nothing was read and nothing was logged, so 'could not read their"
                        + " shapes' would be false on that path")
                .doesNotContain("could not read");
    }

    /**
     * Validate mode says no start repairs anything, and names no remedy.
     *
     * <p>The multi-tenant default, and the arm a draft got wrong: naming {@code riptide onboard}
     * here is a promise the planner refuses for several drift classes, and this line cannot know
     * which drift it is looking at.</p>
     */
    @Test
    void validateModeSaysNoStartRepairsAndPromisesNothing() {
        final String outlook = ClickhouseRepository.repairOutlook(ROLLUP, NONE, NONE, NONE,
                ClickhouseRepository.RepairPosture.NOT_MANAGED);

        assertThat(outlook)
                .as("waiting for a later start is exactly what this deployment must not be told to do")
                .contains("No repair is attempted on any start")
                .contains("does not manage the schema");
        assertThat(outlook)
                .as("and it must offer no remedy: onboard runs the same planner that refuses a"
                        + " shrunk sorting key, a corrected aggregate and a summed missing measure")
                .doesNotContain("onboard");
    }

    /** A planned repair that failed must not read as one that was never attempted. */
    @Test
    void aPlannedRepairThatFailedSaysSo() {
        assertThat(ClickhouseRepository.repairOutlook(ROLLUP, THIS, THIS, NONE,
                ClickhouseRepository.RepairPosture.PLANNED))
                .as("the operator is pointed at a failure that was logged, rather than told to look"
                        + " for one that never was")
                .contains("Work on it was attempted on this start")
                .contains("did not succeed")
                .contains("logged above");
    }

    /**
     * A planned repair that ran and left drift behind says that, rather than denying it was tried.
     *
     * <p>The case a start-wide flag could not express, and the reason the planned set is passed at
     * all: "this start planned no repair for it" is simply false here.</p>
     */
    @Test
    void aPlannedRepairThatRanAndLeftDriftSaysWhatThatMeans() {
        assertThat(ClickhouseRepository.repairOutlook(ROLLUP, THIS, NONE, NONE,
                ClickhouseRepository.RepairPosture.PLANNED))
                .as("claiming none was planned would be false for a rollup that was in the plan")
                .contains("was planned for it on this start and ran")
                .as("and says only that: what a FUTURE start will do is not something one start's"
                        + " outcome establishes, which is the #654 defect class")
                .contains("and it still differs")
                .doesNotContain("this version repairs");
    }

    /**
     * Manage mode that planned nothing for this rollup says exactly that, and stops.
     *
     * <p>It says what this start did and stops. A tail about every future start would be inferred from
     * one start's outcome, and false whenever {@code planViewRepair}'s catalog read failed
     * transiently — the next start then repairs the rollup with no action at all.</p>
     */
    @Test
    void aRollupNoRepairWasPlannedForSaysOnlyThat() {
        assertThat(ClickhouseRepository.repairOutlook(ROLLUP, NONE, NONE, NONE,
                ClickhouseRepository.RepairPosture.PLANNED))
                .as("says what this start did")
                .contains("No repair was planned for it on this start")
                .as("and makes no claim about later starts, which one start's outcome cannot settle")
                .doesNotContain("every start")
                .doesNotContain("acted on");
    }

    /**
     * Another rollup's state never decides this one's sentence.
     *
     * <p>Each case puts a different rollup in the set that would otherwise select the branch, so an
     * implementation reading {@code !isEmpty()} fails here.</p>
     */
    @Test
    void anotherRollupsStateDoesNotDecideThisOne() {
        assertThat(ClickhouseRepository.repairOutlook(ROLLUP, NONE, NONE, ANOTHER,
                ClickhouseRepository.RepairPosture.PLANNED))
                .as("another rollup being refused must not silence this one")
                .isNotEmpty();
        assertThat(ClickhouseRepository.repairOutlook(ROLLUP, ANOTHER, NONE, NONE,
                ClickhouseRepository.RepairPosture.PLANNED))
                .as("another rollup being planned must not claim a repair was planned for this one")
                .contains("No repair was planned for it on this start");
        assertThat(ClickhouseRepository.repairOutlook(ROLLUP, THIS, ANOTHER, NONE,
                ClickhouseRepository.RepairPosture.PLANNED))
                .as("another rollup's failure must not report this one's work as failed")
                .contains("and it still differs");
    }

    /**
     * Each sentence carries the space that joins it to the warning; the refused case appends none.
     *
     * <p>The format ends {@code "…and are slower.{}"}. Drop the leading spaces and an operator reads
     * {@code slower.No repair…}; the IT matches on the body, so nothing else would notice.</p>
     */
    @Test
    void everySentenceCarriesTheSpaceThatJoinsItToTheLine() {
        for (final var posture : ClickhouseRepository.RepairPosture.values()) {
            assertThat(ClickhouseRepository.repairOutlook(ROLLUP, NONE, NONE, NONE, posture))
                    .as("posture %s", posture).startsWith(" ");
        }
        // The two planned branches: the loop above passes an empty planned set, so PLANNED only ever
        // reaches the "none was planned" sentence.
        assertThat(ClickhouseRepository.repairOutlook(ROLLUP, THIS, THIS, NONE,
                ClickhouseRepository.RepairPosture.PLANNED)).startsWith(" ");
        assertThat(ClickhouseRepository.repairOutlook(ROLLUP, THIS, NONE, NONE,
                ClickhouseRepository.RepairPosture.PLANNED)).startsWith(" ");
        assertThat(ClickhouseRepository.repairOutlook(ROLLUP, NONE, NONE, THIS,
                ClickhouseRepository.RepairPosture.PLANNED))
                .as("the refused case appends nothing, leaving the line ending at 'slower.'")
                .isEmpty();
    }

    /**
     * The promise guard matches what it was widened for, and still spares a true remedy.
     *
     * <p>Every other use of this pattern is a negative assertion, so without this the alternatives
     * could be deleted and every one of them would still pass.</p>
     */
    @Test
    void thePromiseGuardMatchesThePhrasingItWasWidenedFor() {
        assertThat(RepairPromises.PROMISES_A_REPAIR.matcher(
                "Run 'riptide onboard' against this database to have it repaired.").find())
                .as("the exact draft wording that reached review unmatched")
                .isTrue();
        assertThat(RepairPromises.PROMISES_A_REPAIR.matcher(
                "drop and re-create the rollup to have it rebuilt").find())
                .as("FlowsSchema's remedy is true and must not be flagged: the guard matches a"
                        + " promise, not every mention of repair")
                .isFalse();
    }

    /**
     * No sentence names a drift class, a destructive remedy, or any promise of repair.
     *
     * <p>The guard on the whole approach, across every reachable combination. Both reverted attempts
     * failed here: one enumerated drift classes, the other told operators to drop the view and
     * target. The third draft slipped a repair promise past {@code PROMISES_A_REPAIR} because that
     * pattern predated its wording, so the pattern is asserted here too rather than trusted.</p>
     */
    @Test
    void noOutlookNamesADriftClassAPromiseOrADestructiveRemedy() {
        for (final var posture : ClickhouseRepository.RepairPosture.values()) {
            for (final Set<String> planned : List.of(NONE, THIS)) {
                for (final Set<String> unrepaired : List.of(NONE, THIS)) {
                    for (final Set<String> refused : List.of(NONE, THIS)) {
                        final String outlook = ClickhouseRepository.repairOutlook(
                                ROLLUP, planned, unrepaired, refused, posture);
                        final String lower = outlook.toLowerCase(Locale.ROOT);
                        final String where = "posture %s planned %s unrepaired %s refused %s"
                                .formatted(posture, planned, unrepaired, refused);

                        assertThat(lower).as("names a drift class: %s", where)
                                .doesNotContain("sorting key").doesNotContain("measure")
                                .doesNotContain("column");
                        assertThat(lower).as("claims something about a later start: %s", where)
                                .doesNotContain("every start").doesNotContain("next start")
                                .doesNotContain("acted on");
                        assertThat(lower).as("offers a command as the fix: %s", where)
                                .doesNotContain("onboard");
                        assertThat(lower).as("names a destructive remedy: %s", where)
                                .doesNotContain("drop").doesNotContain("rebuild")
                                .doesNotContain("re-create").doesNotContain("recreate")
                                .doesNotContain("delete").doesNotContain("truncate");
                        assertThat(RepairPromises.PROMISES_A_REPAIR.matcher(outlook).find())
                                .as("promises a repair: %s -> %s", where, outlook)
                                .isFalse();
                    }
                }
            }
        }
    }
    /**
     * The posture is downgraded only when a failed catalog read is the reason nothing is planned.
     *
     * <p>Both reads are passed because either can fail on its own: {@code planTargetRepair} and
     * {@code planViewRepair} read the catalog separately. The third argument is what keeps a failed
     * read from erasing work that already happened — a view read that fails after target repairs
     * were planned and ran must not produce {@code CATALOG_UNREADABLE}, which tells the operator no
     * repair was planned for any rollup while their outcomes sit in the same log.</p>
     */
    @Test
    void aFailedCatalogReadDowngradesThePostureOnlyWhenNothingWasPlanned() {
        assertThat(ClickhouseRepository.postureOf(true, true, true))
                .as("both reads succeeded: nothing to explain, whatever the plan came to")
                .isEqualTo(ClickhouseRepository.RepairPosture.PLANNED);
        assertThat(ClickhouseRepository.postureOf(true, true, false))
                .isEqualTo(ClickhouseRepository.RepairPosture.PLANNED);

        assertThat(ClickhouseRepository.postureOf(false, true, true))
                .as("the target read failed and nothing was planned: that IS why")
                .isEqualTo(ClickhouseRepository.RepairPosture.CATALOG_UNREADABLE);
        assertThat(ClickhouseRepository.postureOf(true, false, true))
                .as("and the view read is not a lesser read — it fails the same way")
                .isEqualTo(ClickhouseRepository.RepairPosture.CATALOG_UNREADABLE);

        assertThat(ClickhouseRepository.postureOf(true, false, false))
                .as("repairs were planned and ran; a later failed read must not deny them")
                .isEqualTo(ClickhouseRepository.RepairPosture.PLANNED);
        assertThat(ClickhouseRepository.postureOf(false, true, false))
                .isEqualTo(ClickhouseRepository.RepairPosture.PLANNED);
    }

}
