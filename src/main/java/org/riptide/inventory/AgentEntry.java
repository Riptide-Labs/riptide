/*
 * Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.riptide.inventory;

/**
 * One built agent-range entry: the range's key plus its references resolved to
 * objects at build time, so the walk path performs zero name lookups (AD-5).
 * A range with no credential set matches but is never polled, and a range built
 * by the loader always carries a polling profile (the implicit {@code default}).
 *
 * <p><b>Deliberately carries no observation domain, and should not gain one (#615).</b> An
 * exporter entry pins a domain because naming is per flow-source; an agent range does not, because
 * SNMP is per device. One address has one SNMP agent with one configured community, whatever domains
 * the device exports, so a domain cannot coherently select a credential set.</p>
 *
 * <p>It is also unimplementable without a larger change than it looks. {@code
 * InterfaceSnapshotPoller} keys registrations on {@code InetSocketAddress} — one per address, and
 * that is true back to 0.8. Honouring a domain-scoped range means re-keying it to (address, domain):
 * one device would then hold N registrations, N walk schedules and N snapshots of the same ifTable,
 * at N times the SNMP cost and N times its weight against {@code max-exporters}.</p>
 *
 * <p>And it would restore a defect rather than a capability. 0.8's {@code register()} returned the
 * existing registration on collision and discarded the newly resolved endpoint, so a device covered
 * by both a pinned node and a wider one was polled with whichever credentials the first flow after
 * boot selected — re-decided on every restart. The 0.9 rule (most specific range wins) replaced that
 * race. Naming and option-data enrichment still honour the pin; only credential selection does not.</p>
 *
 * @param range the configured range key, for logs and error messages
 * @param credentials the resolved credential set, or {@code null} when omitted
 * @param polling the resolved polling profile; never {@code null} from the loader,
 *     which resolves the implicit {@code default}, so a null reaches consumers only
 *     through direct construction
 * @param enabled {@code false} when the range is an explicit carve-out, which
 *     shadows wider ranges without ever being polled; {@code true} when absent
 * @param port the UDP port the agent answers on, 161 when absent
 */
public record AgentEntry(String range, CredentialSet credentials, PollingProfile polling, boolean enabled,
                         int port) {
}
