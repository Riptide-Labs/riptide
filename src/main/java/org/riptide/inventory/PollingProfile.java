/*
 * Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.riptide.inventory;

import lombok.Data;

import java.time.Duration;

/**
 * A named set of poll-behaviour parameters, configured under
 * {@code riptide.snmp.polling.<name>} and referenced by agent ranges.
 *
 * <p>Skeletal for the inventory gate story: fields exist so references resolve.
 * Defaulting rules ({@code default} applies where no profile is named), the
 * expiry-outlives-refresh warning, and the retirement of the old global spellings
 * land with the polling-profiles story (2.4).</p>
 */
@Data
public class PollingProfile {

    private Duration refreshInterval;

    private Duration snapshotExpiry;

    private int timeout = 500;

    private int retries = 1;
}
