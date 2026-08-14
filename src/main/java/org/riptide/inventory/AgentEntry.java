/*
 * Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.riptide.inventory;

/**
 * One built agent-range entry: the range's key plus its references resolved to
 * objects at build time, so the walk path performs zero name lookups (AD-5).
 * Either reference may be absent at this stage; the semantics of omission
 * (not polled, default profile) land with stories 2.4/2.5.
 *
 * @param range the configured range key, for logs and error messages
 * @param credentials the resolved credential set, or {@code null} when omitted
 * @param polling the resolved polling profile, or {@code null} when omitted
 */
public record AgentEntry(String range, CredentialSet credentials, PollingProfile polling) {
}
