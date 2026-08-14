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
 * @param range the configured range key, for logs and error messages
 * @param credentials the resolved credential set, or {@code null} when omitted
 * @param polling the resolved polling profile; never {@code null} from the loader,
 *     which resolves the implicit {@code default}, so a null reaches consumers only
 *     through direct construction
 * @param enabled {@code false} when the range is an explicit carve-out, which
 *     shadows wider ranges without ever being polled; {@code true} when absent
 */
public record AgentEntry(String range, CredentialSet credentials, PollingProfile polling, boolean enabled) {
}
