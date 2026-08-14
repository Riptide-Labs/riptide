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
 * <p>Not wired to the poller yet: consumers cut over in story 2.8. The UDP port
 * stays the existing 161 default; where a per-range port belongs is undecided in
 * the PRD and tracked with the agent-ranges story.</p>
 */
public final class AgentEndpointFactory {

    private AgentEndpointFactory() {
    }

    /**
     * Builds the endpoint for a matched entry, or empty when the entry carries no
     * credentials (an uncredentialed range is collected but never polled).
     */
    public static Optional<SnmpEndpoint> endpointFor(final AgentEntry entry, final IPAddressString address) {
        final CredentialSet credentials = entry.credentials();
        if (credentials == null) {
            return Optional.empty();
        }
        if (credentials.getVersion() == null) {
            // bind-time validation guarantees a version; this guard names the range
            // instead of surfacing a bare NPE if a mutated set ever slips through
            throw new IllegalStateException(
                    "Agent range '%s' has a credential set with no version.".formatted(entry.range()));
        }
        final SnmpDefinition definition = new SnmpDefinition();
        definition.setSnmpVersion(version(credentials.getVersion()));
        definition.setCommunity(credentials.getCommunity());
        definition.setSecurityName(credentials.getSecurityName());
        definition.setAuthProtocol(credentials.getAuthProtocol());
        definition.setAuthPassphrase(credentials.getAuthPassphrase());
        definition.setPrivProtocol(credentials.getPrivProtocol());
        definition.setPrivPassphrase(credentials.getPrivPassphrase());
        if (entry.polling() != null) {
            definition.setTimeout(entry.polling().getTimeout());
            definition.setRetries(entry.polling().getRetries());
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
