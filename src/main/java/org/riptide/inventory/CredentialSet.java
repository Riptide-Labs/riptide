/*
 * Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.riptide.inventory;

import lombok.Data;
import org.riptide.secrets.SecretRef;
import org.snmp4j.fluent.TargetBuilder;

/**
 * A named SNMP authentication definition, configured once under
 * {@code riptide.snmp.credentials.<name>} and referenced by agent ranges.
 *
 * <p>Skeletal for the inventory gate story: the field surface exists so agent-range
 * references resolve to an object at build time. Full validation, the version enum,
 * and the endpoint factory land with the credential-sets story (2.3); the version is
 * a plain string until then so this package does not reach into
 * {@code org.riptide.snmp}.</p>
 */
@Data
public class CredentialSet {

    private String version;

    private SecretRef community;

    private String securityName;

    private TargetBuilder.AuthProtocol authProtocol;

    private SecretRef authPassphrase;

    private TargetBuilder.PrivProtocol privProtocol;

    private SecretRef privPassphrase;
}
