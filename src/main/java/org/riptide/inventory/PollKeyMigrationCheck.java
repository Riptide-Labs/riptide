/*
 * Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.riptide.inventory;

import org.riptide.utils.PropertyNames;
import org.springframework.core.env.PropertySource;

import java.util.Optional;
import java.util.stream.Stream;
import java.util.Locale;
import java.util.Set;

/**
 * Per-agent poll cadence moved from the global {@code riptide.snmp.poll.*} keys into
 * named polling profiles ({@code riptide.snmp.polling.<name>}); the global spellings
 * would bind into fleet config and silently diverge from what profiles say, so
 * setting them fails startup loudly instead (the migration-check idiom). Fleet-level
 * keys under the same prefix (pool-width, max-exporters, deregister-after,
 * dead-endpoint backoff) keep binding exactly as before.
 *
 * <p>Deliberate interim state: until the consumer-cutover stories wire the poller
 * onto the inventory views, cadence runs on the built-in defaults and cannot be
 * tuned at all — the old keys are forbidden here and profiles are not consumed yet.
 * All of 0.9 ships as one release; this window exists only on development builds.</p>
 */
public final class PollKeyMigrationCheck {

    private PollKeyMigrationCheck() {
    }


    // relaxed binding accepts kebab, camelCase, underscore, and any letter case in files,
    // and TWO env-var mappings (canonical dashes-removed and legacy underscore-per-word);
    // a spelling denylist cannot enumerate all of those, so every property name is
    // normalized the way the binder does (lowercase, separators stripped) and compared
    // for equality. Equality, not prefix, keeps the new "polling." keys and the
    // fleet-level "poll." keys out of scope.
    private static final Set<String> RETIRED_KEYS = Set.of(
            "riptidesnmppollrefreshintervalms",
            "riptidesnmppollsnapshotexpiryms");



    /** Reusable against any source stack, matching the migration-check idiom. */
    public static void failOnRetiredPollKeys(final Iterable<PropertySource<?>> sources) {
        findRetiredPollKey(sources).ifPresent(name -> {
            throw new IllegalStateException(("Retired per-agent poll key found ('%s'): refresh and "
                    + "expiry moved into named polling profiles: configure "
                    + "riptide.snmp.polling.<name>.refresh-interval / .snapshot-expiry and reference "
                    + "the profile from agent ranges. Fleet-level riptide.snmp.poll.* keys are "
                    + "unaffected.").formatted(name));
        });
    }

    /**
     * The non-throwing probe the throwing path delegates to. Exists so the reloader can
     * scan profile-gated documents it deliberately does not fail on (#537) without this
     * class growing a second matching rule of its own.
     */
    public static Optional<String> findRetiredPollKey(final Iterable<PropertySource<?>> sources) {
        return matches(sources).map(PropertyNames.Located::name).findFirst();
    }

    /** Category label for the collected startup report. */
    public static final String LABEL = "retired per-agent poll keys";

    /**
     * Every match, not only the first, so startup can report them together.
     *
     * <p>{@link #findRetiredPollKey} is derived from this rather than walking separately: one walk,
     * two consumers, so the throwing path and the reloader's landmine probe cannot drift apart.</p>
     */
    public static Stream<PropertyNames.Located> matches(final Iterable<PropertySource<?>> sources) {
        return PropertyNames.located(sources)
                .filter(found -> RETIRED_KEYS.contains(normalize(found.name())));
    }

    /** This category's remediation, unchanged; the collected report adds structure, not prose. */
    public static String remediation() {
        return "refresh and expiry moved into named polling profiles: configure "
                + "riptide.snmp.polling.<name>.refresh-interval / .snapshot-expiry and reference "
                + "the profile from agent ranges. Fleet-level riptide.snmp.poll.* keys are "
                + "unaffected.";
    }

    private static String normalize(final String name) {
        return name.toLowerCase(Locale.ROOT).replace("-", "").replace("_", "").replace(".", "");
    }
}
