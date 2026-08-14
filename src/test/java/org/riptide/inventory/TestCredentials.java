/*
 * Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.riptide.inventory;

import org.riptide.secrets.SecretRef;

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

    /** Community only: validateCommunity rejects any USM field on v1/v2c. */
    public static CredentialSet v1() {
        return community(CredentialVersion.V1);
    }

    public static CredentialSet v2c() {
        return community(CredentialVersion.V2C);
    }

    private static CredentialSet community(final CredentialVersion version) {
        final CredentialSet set = new CredentialSet();
        set.setVersion(version);
        set.setCommunity(SecretRef.of("public"));
        return set;
    }
}
