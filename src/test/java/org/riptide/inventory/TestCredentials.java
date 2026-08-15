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
        return CredentialSet.usm("riptide");
    }

    /** Community only: validateCommunity rejects any USM field on v1/v2c. */
    public static CredentialSet v1() {
        return CredentialSet.community(CredentialVersion.V1, SecretRef.of("public"));
    }

    public static CredentialSet v2c() {
        return CredentialSet.community(CredentialVersion.V2C, SecretRef.of("public"));
    }
}
