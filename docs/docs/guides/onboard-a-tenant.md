---
title: Onboard a tenant
sidebar_position: 13
description: Prepare a ClickHouse cluster for multi-tenant writes, provision a tenant with riptide onboard, add the rollups or the dead-letter table to an older deployment, rotate a secret, and offboard.
---

# Onboard a tenant

Provision one `(tenant, org)` pair on a shared ClickHouse so its collector can only write, and its dashboards can only read, that tenant's rows.

## Prerequisites

| Requirement | Why |
| --- | --- |
| ClickHouse with replicated access storage on a cluster | users, roles and row policies must exist on every node; a credential provisioned on one node is otherwise unknown on another |
| the `SQL_` custom-settings prefix enabled in the server config | the write barrier reads `getSetting('SQL_tenant')`; see [Server requirement](../reference/clickhouse.md#server-requirement) |
| the collector in validate mode, `riptide.clickhouse.manage-schema=false` | the admin owns the schema; see [Schema ownership](../reference/clickhouse.md#schema-ownership) |
| an admin credential with the privileges in the [admin privileges table](../reference/provisioning-cli.md#admin-privileges) | `onboard` refuses outright without `SHOW USERS ON *.*` |

The default ClickHouse config stores SQL-created users in a node-local `local_directory`, so the snippet must replace the whole `user_directories` block, not merge into it.
Keep `users_xml` for the bootstrap admin, drop `local_directory`, and add `replicated` so new users land in Keeper:

```xml
<!-- /etc/clickhouse-server/config.d/access-storage.xml -->
<clickhouse>
    <user_directories replace="replace">
        <users_xml>
            <path>users.xml</path>
        </users_xml>
        <replicated>
            <zookeeper_path>/clickhouse/access/</zookeeper_path>
        </replicated>
    </user_directories>
</clickhouse>
```

Without `replace="replace"` the snippet appends to the default block, `local_directory` stays, and SQL-created users are written there, node-local.

On a fresh single-node server, `onboard --create-schema` bootstraps the database and the `flows` table itself.
On a replicated cluster, pre-create `flows` admin-side (`ReplicatedMergeTree`, `ON CLUSTER`) and run `onboard` without `--create-schema`: the bootstrap DDL is single-node (`MergeTree()`, no `ON CLUSTER`) and would create a node-local table on whichever replica the admin client hits, while the roles and grants replicate.

## Steps

1. Export the secrets the run resolves.
   The built-in resolvers are `plain`, `env://` and `file://`.

   ```bash
   export CH_ADMIN_PW=admin ACME_WRITER_PW=w-secret ACME_READER_PW=r-secret
   ```

2. Run `onboard`.
   Add **`--create-schema`** on a fresh single-node server; leave it off when the schema already exists.

   ```bash
   java -jar riptide.jar onboard \
     --admin-url http://127.0.0.1:8123 --admin-user admin --admin-password env://CH_ADMIN_PW \
     --tenant acme --org acme-eu \
     --writer-secret env://ACME_WRITER_PW --reader-secret env://ACME_READER_PW \
     --create-schema
   ```

   Expected output:

   ```text
   Onboarded tenant 'acme' (org 'acme-eu') into database 'riptide'. Add this to the tenant's riptide config (the collector authenticates as 'writer_acme@riptide'):
   riptide.clickhouse.username=writer_acme@riptide
   riptide.clickhouse.password=env://ACME_WRITER_PW
   riptide.identity.tenant=acme
   riptide.identity.organisation=acme-eu
   ```

   The stanza is on stdout, everything else on stderr, so `> stanza.properties` captures only what the collector needs.
   Without `--create-schema`, a missing database or table fails before any statement runs, and the run sends no `CREATE` statement at all.
   With it, `--ttl-days N` sets the retention of the `flows` table this run creates (default 30, maximum 10950).

3. Paste the stanza into the tenant's collector configuration and add the two keys the stanza does not carry.

   ```properties
   riptide.clickhouse.username=writer_acme@riptide
   riptide.clickhouse.password=env://ACME_WRITER_PW
   riptide.identity.tenant=acme
   riptide.identity.organisation=acme-eu
   riptide.clickhouse.manage-schema=false
   riptide.identity.zone=dmz
   ```

   `riptide.clickhouse.username` takes the `@` verbatim.
   A URL that embeds the same name needs `%40`; see [Why object names carry their database](../architecture/multi-tenancy.md#object-names-carry-their-database).

4. Point the tenant's Grafana datasource at `bi_acme@riptide` with the reader secret, in a Grafana org or instance of its own.
   Per-tenant datasources in a shared org and `$tenant` dashboard variables are not boundaries; see [Grafana topology](../architecture/multi-tenancy.md#grafana-topology).

5. Verify the barrier from the writer's side.

   ```bash
   curl -s -u 'writer_acme@riptide:w-secret' 'http://127.0.0.1:8123/' \
     --data-binary "INSERT INTO riptide.flows (tenant, organisation, timestamp) VALUES ('other', 'acme-eu', now())"
   ```

   Expected output:

   ```text
   Code: 469. DB::Exception: Constraint `tenant_pinned` for table riptide.flows (…) is violated at row 1. Expression: (tenant = getSetting('SQL_tenant')). Column values: tenant = 'other': While executing WaitForAsyncInsert. (VIOLATED_CONSTRAINT) (version 26.7.13.12 (official build))
   ```

   The same insert with `tenant = 'acme'` returns nothing and exit code 0.

6. Verify the policies from the admin's side.

   ```sql
   SELECT name, apply_to_list FROM system.row_policies WHERE database = 'riptide' ORDER BY name;
   ```

   Expected output:

   ```text
      ┌─name───────────────────────────────────────────┬─apply_to_list─────────────────────────────┐
   1. │ acme_iso ON riptide.flows                      │ ['bi_acme@riptide','writer_acme@riptide'] │
   2. │ acme_iso ON riptide.flows_by_application_1m    │ ['bi_acme@riptide']                       │
   3. │ acme_iso ON riptide.flows_by_conversation_1m   │ ['bi_acme@riptide']                       │
   4. │ acme_iso ON riptide.flows_by_exporter_iface_1m │ ['bi_acme@riptide']                       │
   5. │ acme_iso ON riptide.flows_by_geo_asn_1m        │ ['bi_acme@riptide']                       │
   6. │ acme_iso ON riptide.flows_dead_letter          │ ['bi_acme@riptide']                       │
      └────────────────────────────────────────────────┴───────────────────────────────────────────┘
   ```

## Add the rollups to an existing deployment {/* #adding-rollups-to-an-existing-deployment */}

A database provisioned before the rollups existed has a good `flows` table, so the schema check passes while the rollups are absent.
`onboard` checks for them separately and refuses without the flag:

```text
error: database 'riptide' is missing the 1-minute rollup tables or their materialized views — re-run with --create-schema to add them. This creates tables and materialized views only; the flows table and its data are untouched, and the rollups cover traffic from creation onward (a materialized view does not backfill)
```

1. Re-run the `onboard` command from step 2 with **`--create-schema`**.
   Only tables and materialized views are created; `flows` and its data are untouched.
   The check covers each rollup's target and its view, so an interrupted bootstrap that left targets without views is detected rather than reading as healthy.
2. Restart the collector.
   Which rollups are usable is decided once, at startup, so a collector that declined them keeps answering from raw `flows` until it restarts, however complete the repair was.

Rollups added this way cover traffic from creation onward; see [Backfill a rollup](backfill-a-rollup.md) for the history.

:::warning[Re-run onboard after every upgrade that adds a rollup dimension]

A validate-mode collector issues no DDL, so until `onboard` is re-run riptide declines all four rollups and answers every long-range query from raw `flows`, correct but truncated at the raw retention window.
Re-running brings existing rollups up to the running version's shape, appending any dimension a release has added; the statements no-op once a rollup is current, and no aggregation is interrupted.
Then restart the collector.

:::

`onboard` reads each rollup's live sorting key first and applies the same rule the collector does, so a change that would shrink a key is refused rather than applied.
ClickHouse itself accepts such a shrink on an upgraded table, because the primary key was frozen at the narrower shape.
If `onboard` reports a rollup as *left as it is*, that rollup gets no materialized view and stays out of the query path until the state its message describes is fixed; roles, users and password rotation are unaffected.
See [Recover from a rollup shape message](../operations/rollup-drift.md).

## Add the dead-letter table to an existing deployment {/* #adding-the-dead-letter-table-to-an-existing-deployment */}

The same shape, for the same reason.
A database provisioned before `flows_dead_letter` existed passes every other check while lacking it:

```text
error: database 'riptide' is missing the dead-letter table (flows_dead_letter) — re-run with --create-schema to add it. This creates one table and nothing else; the flows table, its data and the rollups are untouched. Without it a refused insert drops every row of its batch, which is what riptide did before this table existed
```

1. Re-run `onboard` with **`--create-schema`**.
   One table is added and nothing else; the refusal happens before any statement runs.
2. Restart the collector.
   Once the server has answered that the table is not there, riptide stops asking until the next start.

It is a refusal rather than a warning because ClickHouse will not tell you: measured on the pinned image, `GRANT`, `CREATE ROW POLICY` and `REVOKE` naming a table that does not exist all succeed silently.
An ungated run would grant on nothing, police nothing, print the stanza and exit 0, and you would find out from `persister.batch.deadLetterFailedRows` on some later refused batch.
Until the table is added, a refused batch costs what it always cost: every row counted in `persister.batch.failedRows` and nothing kept; see [Inspect and replay dead letters](../operations/dead-letters.md).

## Rotate a secret

1. Change the value behind the secret reference.
2. Re-run the `onboard` command from step 2 without `--create-schema`.
   The run reconciles the writer and reader passwords to the current secret with `ALTER USER`; the users' `CONST` settings are preserved.
3. Restart the collector so it reads the new value.

The row policies are re-asserted to match the recipe on every run.
A policy `TO` list widened by hand is reverted, and a grantee removed from a policy while it still holds `SELECT` is left governed by no policy and reads every tenant's rows.
Route extra grantees through provisioning, never through manual DDL.

## Offboard a tenant

```bash
java -jar riptide.jar offboard \
  --admin-url http://127.0.0.1:8123 --admin-user admin --admin-password env://CH_ADMIN_PW \
  --tenant acme --yes
```

Expected output:

```text
Offboarded tenant 'acme' from database 'riptide': dropped writer_acme@riptide and bi_acme@riptide, and the tenant's row policies on flows, the dead-letter table and every rollup. The database's roles, constraints and quota are left in place: they are shared by every tenant in this database, so offboard never removes them. If this was the last tenant here, drop them by hand.
```

`offboard` also drops the pre-rename `writer_acme` and `bi_acme` when they exist, and those are keyed on the tenant alone, so the drop reaches every database on the server; see [Migrate a deployment onboarded before the rename](migrate-tenant-accounts.md#offboard-reaches-every-database).

## Related

- [Provisioning CLI reference](../reference/provisioning-cli.md) for every flag, the DDL and every message.
- [How multi-tenancy works](../architecture/multi-tenancy.md) for the identity model and what the barrier guarantees.

## Open questions

- Outputs on this page were captured against a throwaway `clickhouse/clickhouse-server:26.7` container (26.7.13.12), not a replicated cluster; the access-storage snippet was not exercised.
