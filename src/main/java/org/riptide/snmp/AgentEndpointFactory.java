/*
 * Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.riptide.snmp;

import inet.ipaddr.IPAddressString;
import org.riptide.inventory.AgentEntry;
import org.riptide.inventory.CredentialSet;
import org.riptide.inventory.CredentialVersion;

import java.util.Optional;

/**
 * Turns a built agent-range entry into a pollable SNMP endpoint. Bridges the
 * inventory's {@link CredentialSet} and polling values onto the existing
 * {@link SnmpDefinition} machinery, so secret laziness (values resolve inside
 * {@code SnmpVersion.getTarget} per walk, never here) and the degrade-on-failure
 * behaviour hold by construction rather than by reimplementation.
 *
 * <p>The UDP port comes from the agent range, defaulting to 161. It was briefly fixed
 * at 161 on the argument that no configuration surface carried one; that was wrong,
 * since the legacy tree exposes a per-node port and real deployments use it.</p>
 */
public final class AgentEndpointFactory {

    private AgentEndpointFactory() {
    }

    /**
     * Builds the endpoint for a matched entry, or empty when the entry carries no
     * credentials (an uncredentialed range is collected but never polled) or is an
     * explicit carve-out ({@code enabled: false}), which shadows wider ranges
     * precisely so the addresses under it are never walked.
     */
    public static Optional<SnmpEndpoint> endpointFor(final AgentEntry entry, final IPAddressString address) {
        if (!entry.enabled()) {
            return Optional.empty();
        }
        final CredentialSet credentials = entry.credentials();
        if (credentials == null) {
            return Optional.empty();
        }
        if (credentials.version() == null) {
            // bind-time validation guarantees a version; this guard names the range
            // instead of surfacing a bare NPE if a mutated set ever slips through
            throw new IllegalStateException(
                    "Agent range '%s' has a credential set with no version.".formatted(entry.range()));
        }
        final SnmpDefinition definition = new SnmpDefinition();
        definition.setSnmpVersion(version(credentials.version()));
        definition.setPort(entry.port());
        definition.setCommunity(credentials.community());
        definition.setSecurityName(credentials.securityName());
        definition.setAuthProtocol(credentials.authProtocol());
        definition.setAuthPassphrase(credentials.authPassphrase());
        definition.setPrivProtocol(credentials.privProtocol());
        definition.setPrivPassphrase(credentials.privPassphrase());
        if (entry.polling() != null) {
            definition.setTimeout(entry.polling().timeout());
            definition.setRetries(entry.polling().retries());
        }
        return Optional.of(definition.createEndpoint(address));
    }

    private static SnmpVersion version(final CredentialVersion version) {
        return switch (version) {
            case V1 -> SnmpVersion.v1;
            case V2C -> SnmpVersion.v2c;
            case V3 -> SnmpVersion.v3;
        };
    }
}
