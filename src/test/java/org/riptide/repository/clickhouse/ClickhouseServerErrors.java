/*
 * Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.riptide.repository.clickhouse;

/**
 * ClickHouse server error codes the ITs in this package assert against, in one place.
 *
 * <p>These are literals because client-v2 0.10.0 names no enum member for them, and a literal
 * repeated per test is a fact remembered in as many places as there are tests. It was remembered in
 * three ({@link TenantWriteBarrierIT}, {@link TenantOnboardingIT}, {@link PoisonBatchProbeIT})
 * before this class existed; the value now lives here and every site reads it from here.</p>
 *
 * <p>Read off a real server rather than predicted: each constant's javadoc names the message the
 * pinned image actually produced.</p>
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

    /** The same code as text, for the assertions that match a stack trace rather than a code. */
    static final String VIOLATED_CONSTRAINT_TEXT = String.valueOf(VIOLATED_CONSTRAINT);
}
