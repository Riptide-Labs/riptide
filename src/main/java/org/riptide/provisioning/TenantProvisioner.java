/*
 * Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.riptide.provisioning;

import com.clickhouse.client.api.Client;
import org.riptide.schema.FlowsSchema;
import org.riptide.secrets.SecretRef;
import org.riptide.secrets.SecretResolvers;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ExecutionException;

/**
 * Provisions and de-provisions a tenant against ClickHouse using an admin connection. This is the
 * whole admin-side of multi-tenancy: it ensures the one-time role/constraint/quota objects, then
 * creates (or drops) the per-tenant scoped users and row policy. It depends only on a ClickHouse
 * {@link Client} and {@link SecretResolvers}, so it carries no Spring coupling and lifts cleanly
 * into a future {@code riptide-admin} module.
 *
 * <p>The caller owns the admin {@link Client} (built from admin credentials supplied explicitly at
 * invocation — never the collector's scoped credential) and its lifecycle.
 */
public final class TenantProvisioner {

    private final Client admin;
    private final SecretResolvers secretResolvers;

    public TenantProvisioner(final Client admin, final SecretResolvers secretResolvers) {
        this.admin = Objects.requireNonNull(admin, "admin");
        this.secretResolvers = Objects.requireNonNull(secretResolvers, "secretResolvers");
    }

    /**
     * Ensure the shared objects and this tenant's users/policy exist (idempotent). Returns the
     * riptide configuration stanza for the tenant's collector, referencing the same writer secret.
     *
     * <p>A pre-flight check verifies the database and {@code flows} table exist <em>before any
     * statement runs</em>. If they are missing, the run fails unless {@code createSchema} is set —
     * a typo'd database name must fail loudly, not silently provision a phantom database with the
     * shared roles granted on it. The bootstrap statements are only <em>emitted</em> when actually
     * creating: ClickHouse checks the {@code CREATE} privileges even when {@code IF NOT EXISTS}
     * would no-op, and a least-privilege admin re-run (e.g. password rotation) must keep working.
     */
    public OnboardResult onboard(final TenantSpec spec, final boolean createSchema, final int ttlDays) {
        final String writerPassword = resolve(spec.writerSecret());
        final String readerPassword = resolve(spec.readerSecret());

        final var statements = new ArrayList<String>();
        final boolean bootstrap = !flowsTableExists(spec.database());
        if (bootstrap) {
            if (!createSchema) {
                throw new ProvisioningException(
                        "database '" + spec.database() + "' has no flows table — re-run with"
                                + " --create-schema to bootstrap it, or check the --database value"
                                + " for typos (an admin-provisioned table is also accepted)", null);
            }
            statements.addAll(ProvisioningDdl.bootstrapSchema(spec.database(), ttlDays));
        }
        statements.addAll(ProvisioningDdl.ensureShared(spec.database(), spec.quotaBytes()));
        statements.addAll(ProvisioningDdl.onboardTenant(
                spec.database(), spec.tenant(), spec.organisation(), writerPassword, readerPassword));
        execute(statements);

        return new OnboardResult(configStanza(spec), bootstrap);
    }

    /**
     * The config stanza plus whether this run created the schema — the caller needs the latter to
     * warn when an explicitly requested {@code --ttl-days} was not applied (table pre-existed).
     */
    public record OnboardResult(String configStanza, boolean schemaBootstrapped) {
    }

    /**
     * Pre-flight existence check. {@code EXISTS DATABASE} is queried first — {@code EXISTS TABLE}
     * against a nonexistent database can raise {@code UNKNOWN_DATABASE} rather than returning 0.
     */
    private boolean flowsTableExists(final String database) {
        return exists("EXISTS DATABASE " + ProvisioningDdl.ident(database))
                && exists("EXISTS TABLE " + FlowsSchema.qualifiedFlows(database));
    }

    private boolean exists(final String sql) {
        try {
            // EXISTS returns a single UInt8 column named "result".
            return this.admin.queryAll(sql).getFirst().getLong("result") == 1;
        } catch (final Exception e) {
            throw new ProvisioningException("Pre-flight schema check failed: " + sql, e);
        }
    }

    /** Drop the tenant's users and row policy; the shared roles/constraints/quota are left intact. */
    public void offboard(final TenantRef ref) {
        execute(ProvisioningDdl.offboardTenant(ref.database(), ref.tenant()));
    }

    private void execute(final List<String> statements) {
        for (final String sql : statements) {
            try {
                this.admin.execute(sql).get();
            } catch (final InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new ProvisioningException("Interrupted while provisioning", e);
            } catch (final ExecutionException e) {
                throw new ProvisioningException("Provisioning statement failed: " + redact(sql), e.getCause());
            }
        }
    }

    private String resolve(final String ref) {
        return this.secretResolvers.resolve(SecretRef.of(ref));
    }

    private static String configStanza(final TenantSpec spec) {
        // Built by concatenation (not String.format) so the line separators stay literal '\n' — a
        // config stanza the operator pastes, not platform-dependent output.
        return "riptide.clickhouse.username=writer_" + spec.tenant() + "\n"
                + "riptide.clickhouse.password=" + spec.writerSecret() + "\n"
                + "riptide.identity.tenant=" + spec.tenant() + "\n"
                + "riptide.identity.organisation=" + spec.organisation();
    }

    /**
     * Never surface a resolved password in an error. Matches the whole escaped string literal after
     * {@code IDENTIFIED WITH … BY}: {@code \\.} consumes an escaped char and {@code [^'\\]} any
     * other (including newlines). The two branches are disjoint (the "other" branch excludes the
     * backslash), so there is no ambiguity that could cause catastrophic backtracking, and a
     * password with a {@code '} or {@code \n} cannot leak past a naive {@code '.*?'}.
     */
    static String redact(final String sql) {
        return sql.replaceAll("(?is)(IDENTIFIED WITH \\w+ BY )'(?:\\\\.|[^'\\\\])*'", "$1'***'");
    }

    /** A validated reference to an existing tenant, for teardown. */
    public record TenantRef(String database, String tenant) {
        public TenantRef {
            TenantSpec.requireSafe("database", database);
            TenantSpec.requireSafe("tenant", tenant);
        }
    }

    /** Thrown when a provisioning statement fails; the message never contains a resolved secret. */
    public static final class ProvisioningException extends RuntimeException {
        public ProvisioningException(final String message, final Throwable cause) {
            super(message, cause);
        }
    }
}
