/*
 * Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.riptide.inventory;

/**
 * Shared minimal-valid credential fixtures. When a validation rule tightens, this
 * is the one place test fixtures change ({@code ShapeBench} keeps a documented
 * local copy because the bench source set cannot see test roots).
 */
public final class TestCredentials {

    private TestCredentials() {
    }

    public static CredentialSet v3() {
        final CredentialSet set = new CredentialSet();
        set.setVersion(CredentialVersion.V3);
        set.setSecurityName("riptide");
        return set;
    }
}
