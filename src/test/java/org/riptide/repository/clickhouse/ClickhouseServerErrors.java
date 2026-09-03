/*
 * Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.riptide.repository.clickhouse;

/**
 * ClickHouse server error codes the ITs in this package assert against, in one place.
 *
 * <p>These are literals because client-v2 0.10.0 names no enum member for them, and a literal
 * repeated per test is a fact remembered in as many places as there are tests. {@code 469} was
 * remembered in three places before this class existed: {@link TenantWriteBarrierIT},
 * {@link TenantOnboardingIT}, and {@code ClickhouseRepositoryIT}, whose own {@code REJECTED_ROW_CODE}
 * the change that introduced this class deletes. {@link PoisonBatchProbeIT} arrives with this class
 * and reads from it rather than becoming a fourth; {@link MultiBlockPoisonProbeIT} (#700) is the
 * fifth reader and reads from here too. This enumeration exists to stop the next one becoming a
 * copy, so it is worth keeping current.</p>
 *
 * <p>Every code this package <em>asserts</em> lives here. One code it merely mentions does not:
 * {@code 181} ({@code ILLEGAL_FINAL}) appears in {@link PoisonBatchProbeIT}'s javadoc as prose about
 * why {@code FINAL} is refused on a plain {@code MergeTree}, with no assertion behind it, so
 * extracting it would relocate a sentence rather than centralise a fact.</p>
 *
 * <p>Read off a real server rather than predicted: the javadoc of each code below quotes the message
 * the pinned image actually produced.</p>
 */
final class ClickhouseServerErrors {

    private ClickhouseServerErrors() {
    }

    /**
     * A row a {@code CHECK} constraint refused.
     *
     * <p>Observed on the pinned image: {@code Code: 469. DB::Exception: Constraint `tenant_pinned`
     * for table barrier.flows is violated at row 1. Expression: (tenant = getSetting('SQL_tenant')).
     * Column values: tenant = 'evil'. (VIOLATED_CONSTRAINT)}</p>
     */
    static final int VIOLATED_CONSTRAINT = 469;

    /**
     * An attempt to change a setting the credential pins {@code CONST}.
     *
     * <p>Observed on the pinned image: {@code Code: 452. DB::Exception: Setting SQL_tenant should
     * not be changed. (SETTING_CONSTRAINT_VIOLATION)}</p>
     */
    static final int SETTING_CONSTRAINT_VIOLATION = 452;

    /**
     * The leading sentence of a {@link #VIOLATED_CONSTRAINT} message, for the assertions that match
     * a stack trace rather than read the code off a {@code ServerException}.
     *
     * <p>Anchored as {@code "Code: 469."} rather than the bare number, because {@code "469"} alone
     * matches a line number, a port, a duration in millis, or a different code such as {@code 4690},
     * anywhere in a full stack trace.</p>
     */
    static final String VIOLATED_CONSTRAINT_MESSAGE_PREFIX = "Code: " + VIOLATED_CONSTRAINT + ".";

    /** The same anchoring for {@link #SETTING_CONSTRAINT_VIOLATION}, and for the same reason. */
    static final String SETTING_CONSTRAINT_VIOLATION_MESSAGE_PREFIX =
            "Code: " + SETTING_CONSTRAINT_VIOLATION + ".";

    /**
     * A statement the credential holds no grant for.
     *
     * <p>Observed on the pinned image: {@code Code: 497. DB::Exception: writer_dual@iso_b: Not
     * enough privileges. To execute this query, it's necessary to have the grant INSERT ON
     * iso_a.flows. (ACCESS_DENIED)}</p>
     *
     * <p>This is the code the per-database roles buy (#649): a cross-database write is refused as a
     * missing privilege, before any row is evaluated — not by a {@link #VIOLATED_CONSTRAINT} that
     * happens to match, which it would not when both databases carry the same tenant id.</p>
     */
    static final int ACCESS_DENIED = 497;

    /** The same anchoring for {@link #ACCESS_DENIED}, and for the same reason. */
    static final String ACCESS_DENIED_MESSAGE_PREFIX = "Code: " + ACCESS_DENIED + ".";

    /**
     * A credential that does not exist, or whose password does not match.
     *
     * <p>Observed on the pinned image: {@code Code: 516. DB::Exception: writer_old: Authentication
     * failed: password is incorrect, or there is no user with such name. (AUTHENTICATION_FAILED)}
     * — the two causes are deliberately not distinguished by the server, so an assertion on this
     * code says the credential does not work, not why.</p>
     */
    static final int AUTHENTICATION_FAILED = 516;

    /** The same anchoring for {@link #AUTHENTICATION_FAILED}, and for the same reason. */
    static final String AUTHENTICATION_FAILED_MESSAGE_PREFIX = "Code: " + AUTHENTICATION_FAILED + ".";
}
