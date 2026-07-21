/*
 * Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.riptide.provisioning;

import com.clickhouse.client.api.Client;
import org.riptide.schema.FlowsSchema;
import org.riptide.secrets.SecretRef;
import org.riptide.secrets.SecretResolvers;

import java.io.PrintStream;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * The {@code onboard}/{@code offboard} subcommands. Runs with no Spring context: it builds an admin
 * ClickHouse {@link Client} from explicit arguments and resolves secrets through
 * {@link SecretResolvers#defaults()} ({@code plain}/{@code env}/{@code file}), so the running
 * collector never instantiates any provisioning code. Admin credentials come from the invocation,
 * never from {@code riptide.clickhouse.*}.
 */
public final class ProvisioningCommand {

    private static final long DEFAULT_QUOTA_BYTES = 50_000_000_000L;

    /** 30 years — far beyond any NetFlow retention, comfortably below the 2106 DateTime wrap. */
    private static final int MAX_TTL_DAYS = 10_950;

    private ProvisioningCommand() {
    }

    /** True if {@code arg} names a provisioning subcommand. */
    public static boolean matches(final String arg) {
        return "onboard".equals(arg) || "offboard".equals(arg);
    }

    /** Run the subcommand named by {@code args[0]}. Returns a process exit code. */
    public static int run(final String[] args) {
        return run(args, System.out, System.err);
    }

    /** As {@link #run(String[])} but with explicit streams — the config stanza goes to {@code out}. */
    public static int run(final String[] args, final PrintStream out, final PrintStream err) {
        final Args parsed;
        try {
            parsed = Args.parse(args);
        } catch (final IllegalArgumentException e) {
            err.println("error: " + e.getMessage());
            usage(err);
            return 2;
        }

        final var resolvers = SecretResolvers.defaults();
        try {
            // Resolve the admin password inside the try so a missing env var / unknown scheme takes
            // the clean error path, not an uncaught throw before System.exit.
            final String adminPassword = parsed.get("admin-password") == null
                    ? "" : resolvers.resolve(SecretRef.of(parsed.get("admin-password")));

            try (var admin = new Client.Builder()
                    .addEndpoint(parsed.require("admin-url"))
                    .setUsername(parsed.getOrDefault("admin-user", "default"))
                    .setPassword(adminPassword)
                    .build()) {

                final var provisioner = new TenantProvisioner(admin, resolvers);
                final String database = parsed.getOrDefault("database", "riptide");

                return switch (parsed.subcommand) {
                    case "onboard" -> onboard(parsed, database, provisioner, out, err);
                    case "offboard" -> offboard(parsed, database, provisioner, err);
                    default -> {
                        usage(err);
                        yield 2;
                    }
                };
            }
        } catch (final IllegalArgumentException e) {
            err.println("error: " + e.getMessage());
            return 2;
        } catch (final TenantProvisioner.ProvisioningException e) {
            err.println("error: " + e.getMessage());
            return 1;
        }
    }

    private static int onboard(final Args parsed, final String database, final TenantProvisioner provisioner,
                               final PrintStream out, final PrintStream err) {
        final long quotaBytes = parseQuotaBytes(parsed.get("quota-bytes"));
        final boolean createSchema = parsed.flags.contains("create-schema");
        final boolean ttlRequested = parsed.get("ttl-days") != null;
        if (ttlRequested && !createSchema) {
            // Enforce the nesting the usage text promises: the TTL only applies to a table this
            // run creates — accepting it standalone would silently do nothing.
            throw new IllegalArgumentException("--ttl-days requires --create-schema");
        }
        final int ttlDays = parseTtlDays(parsed.get("ttl-days"));
        final var spec = new TenantSpec(
                parsed.require("tenant"),
                parsed.require("org"),
                database,
                parsed.require("writer-secret"),
                parsed.require("reader-secret"),
                quotaBytes);

        final var result = provisioner.onboard(spec, createSchema, ttlDays);
        if (ttlRequested && !result.schemaBootstrapped()) {
            err.println("warning: --ttl-days ignored — the flows table already exists, its retention"
                    + " is unchanged (use ALTER TABLE ... MODIFY TTL to change it)");
        }
        err.println("Onboarded tenant '" + spec.tenant() + "' (org '" + spec.organisation()
                + "'). Add this to the tenant's riptide config:");
        out.println(result.configStanza());
        return 0;
    }

    private static int offboard(final Args parsed, final String database, final TenantProvisioner provisioner,
                                final PrintStream err) {
        final var ref = new TenantProvisioner.TenantRef(database, parsed.require("tenant"));
        if (!parsed.flags.contains("yes")) {
            err.println("refusing to offboard '" + ref.tenant()
                    + "' without --yes (this drops the tenant's writer/reader users and row policy)");
            return 2;
        }
        provisioner.offboard(ref);
        err.println("Offboarded tenant '" + ref.tenant() + "' (dropped its users and row policy).");
        return 0;
    }

    static long parseQuotaBytes(final String value) {
        if (value == null) {
            return DEFAULT_QUOTA_BYTES;
        }
        try {
            return Long.parseLong(value);
        } catch (final NumberFormatException e) {
            throw new IllegalArgumentException("--quota-bytes must be a number, was: " + value);
        }
    }

    /**
     * Retention for a {@code --create-schema}-created table; defaults to the collector's 30 days.
     * Capped at 30 years: ClickHouse {@code DateTime} ends 2106-02-07 and TTL arithmetic wraps
     * modulo 2^32 seconds — an oversized interval (verified: {@code INTERVAL 49710 DAY}) wraps to a
     * TTL in the past and silently discards every inserted row.
     */
    static int parseTtlDays(final String value) {
        if (value == null) {
            return FlowsSchema.DEFAULT_TTL_DAYS;
        }
        final int days;
        try {
            days = Integer.parseInt(value);
        } catch (final NumberFormatException e) {
            throw new IllegalArgumentException("--ttl-days must be a number of days, was: " + value);
        }
        if (days <= 0 || days > MAX_TTL_DAYS) {
            throw new IllegalArgumentException("--ttl-days must be between 1 and " + MAX_TTL_DAYS
                    + " (ClickHouse DateTime ends 2106; larger intervals wrap and expire data immediately),"
                    + " was: " + value);
        }
        return days;
    }

    private static void usage(final PrintStream err) {
        err.println("""
                usage:
                  riptide onboard  --admin-url URL [--admin-user U] [--admin-password REF] \\
                                   --tenant T --org O --writer-secret REF --reader-secret REF \\
                                   [--database DB] [--quota-bytes N] \\
                                   [--create-schema [--ttl-days N]]
                  riptide offboard --admin-url URL [--admin-user U] [--admin-password REF] \\
                                   --tenant T [--database DB] --yes
                secret REF: plain literal, env://VAR, or file:///path[#key]
                --create-schema: bootstrap the database, flows table, and 1-minute rollup
                                 tables/views if absent (needs CREATE privileges) — also the way
                                 to add the rollups to a pre-rollup deployment; without it, a
                                 missing schema fails before provisioning""");
    }

    /** Minimal {@code --key value} / {@code --flag} parser. {@code args[0]} is the subcommand. */
    private record Args(String subcommand, Map<String, String> options, Set<String> flags) {

        private static final Set<String> KNOWN_FLAGS = Set.of("yes", "create-schema");

        static Args parse(final String[] args) {
            if (args.length == 0 || !matches(args[0])) {
                throw new IllegalArgumentException("expected 'onboard' or 'offboard'");
            }
            final var options = new HashMap<String, String>();
            final var flags = new HashSet<String>();
            int i = 1;
            while (i < args.length) {
                final String token = args[i];
                if (!token.startsWith("--")) {
                    throw new IllegalArgumentException("unexpected argument: " + token);
                }
                final String key = token.substring(2);
                if (KNOWN_FLAGS.contains(key)) {
                    flags.add(key);
                    i += 1;
                } else {
                    if (i + 1 >= args.length) {
                        throw new IllegalArgumentException("missing value for --" + key);
                    }
                    options.put(key, args[i + 1]);
                    i += 2;
                }
            }
            return new Args(args[0], options, flags);
        }

        String get(final String key) {
            return this.options.get(key);
        }

        String getOrDefault(final String key, final String fallback) {
            return this.options.getOrDefault(key, fallback);
        }

        String require(final String key) {
            final String value = this.options.get(key);
            if (value == null) {
                throw new IllegalArgumentException("missing required --" + key);
            }
            return value;
        }
    }
}
