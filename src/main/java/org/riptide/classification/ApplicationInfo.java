/*
 * Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.riptide.classification;

/**
 * One row of an exporter's application table: RFC 6759 applicationName and applicationDescription.
 *
 * @param name what the exporter calls the application; what reaches the {@code application} column
 * @param description the exporter's prose for it. Stored for a future {@code applicationDescription}
 *     column and read by nothing today, so it costs memory and nothing else.
 */
public record ApplicationInfo(String name, String description) {

    /** The fresh record pins the fields it carries; the existing entry fills the rest. */
    public static ApplicationInfo merge(final ApplicationInfo fresh, final ApplicationInfo existing) {
        if (existing == null) {
            return fresh;
        }
        return new ApplicationInfo(
                fresh.name != null ? fresh.name : existing.name,
                fresh.description != null ? fresh.description : existing.description);
    }
}
