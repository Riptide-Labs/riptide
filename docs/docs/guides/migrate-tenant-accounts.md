---
title: Migrate a deployment onboarded before the rename
description: Move a tenant from the instance-wide writer_<tenant> and bi_<tenant> accounts to the database-qualified ones without downtime, close each database to the old roles with revoke-legacy, then drop the roles.
---

# Migrate a deployment onboarded before the rename

Nothing breaks on upgrade, and nothing is removed for you.
An instance provisioned under the old naming keeps its unqualified `writer_<tenant>` and `bi_<tenant>` users and its `flow_writer` and `flow_reader` roles, and its collector keeps working.
Those accounts carry no database in their name, so they are one object shared by every database on the server; this procedure retires them one tenant and one database at a time.
Why the names changed is under [Why object names carry their database](../architecture/multi-tenancy.md#object-names-carry-their-database).

## Prerequisites

- The admin credential holds `SHOW USERS ON *.*` for `onboard`, and `SELECT` on `system.grants` and `system.row_policies` plus `INSERT, SELECT ON <db>.* WITH GRANT OPTION` for `revoke-legacy`; see the [admin privileges table](../reference/provisioning-cli.md#admin-privileges).
- You know every database on the server where each tenant is provisioned.

## Steps {/* #upgrading-a-deployment-onboarded-before-the-rename */}

Per tenant, in this order.

1. Re-run `onboard` for the tenant in every database on this server where it is provisioned.
   Each database gets its own qualified account, created with the same secret.

   ```bash
   java -jar riptide.jar onboard \
     --admin-url http://127.0.0.1:8123 --admin-user admin --admin-password env://CH_ADMIN_PW \
     --database db_a --tenant bravo --org bravo-eu \
     --writer-secret env://BRAVO_WRITER_PW --reader-secret env://BRAVO_READER_PW
   ```

   Expected output:

   ```text
   warning: the pre-rename account 'writer_bravo' still exists on this server, and it is instance-wide — it is not specific to database 'db_a'. It holds the old instance-wide write role, so it can still INSERT into every database provisioned before the rename. This run kept it on this tenant's row policies, so it stays filtered to its own rows meanwhile. Retire it only once EVERY database's collector and datasource for tenant 'bravo' has moved to the qualified account — dropping it sooner takes those other databases offline. Then: DROP USER `writer_bravo`, and re-run onboard here so the policies stop naming it.
   warning: the pre-rename account 'bi_bravo' still exists on this server, and it is instance-wide — it is not specific to database 'db_a'. It holds the old instance-wide read role wherever that role was granted SELECT, so it can still read those databases. This run kept it on this tenant's row policies, so it stays filtered to its own rows meanwhile. Retire it only once EVERY database's collector and datasource for tenant 'bravo' has moved to the qualified account — dropping it sooner takes those other databases offline. Then: DROP USER `bi_bravo`, and re-run onboard here so the policies stop naming it.
   Onboarded tenant 'bravo' (org 'bravo-eu') into database 'db_a'. Add this to the tenant's riptide config (the collector authenticates as 'writer_bravo@db_a'):
   riptide.clickhouse.username=writer_bravo@db_a
   riptide.clickhouse.password=env://BRAVO_WRITER_PW
   riptide.identity.tenant=bravo
   riptide.identity.organisation=bravo-eu
   ```

   The run adds the qualified account alongside the old one; it neither renames nor drops it, because the tenant's collector is still authenticating as the old one until you paste the new stanza.
   It also keeps the old account named on the tenant's row policies for as long as that account exists.
   The policy name is unchanged by the rename, so the run rewrites the existing policy's `TO` list, and an account dropped from it would be named by no policy and start reading every tenant's rows; see [Row policies are not deny-by-default](../architecture/multi-tenancy.md#dropping-the-roles).

2. Update every collector config to the username from that database's new stanza, and every Grafana or MCP datasource to the matching `bi_<tenant>@<database>`.
   Restart them.

3. Drop the old accounts, only now.

   ```sql
   DROP USER `writer_bravo`;
   DROP USER `bi_bravo`;
   ```

   Step 1 must cover every database before this step, because the old accounts are one object shared by all of them.
   Until this step the old account still holds the old instance-wide role, so it can still write to every database provisioned before the rename.

4. Re-run `onboard` once more in each database, so the policies stop naming the accounts you dropped.

   ```text
   Onboarded tenant 'bravo' (org 'bravo-eu') into database 'db_a'. Add this to the tenant's riptide config (the collector authenticates as 'writer_bravo@db_a'):
   ```

   No `warning:` line means no pre-rename account is left for this tenant.

5. Once a database has no pre-rename account left serving it, [close it to the old roles](#revoking-the-pre-rename-roles-on-a-migrated-database).
   Steps 1 to 4 migrate a tenant; only this closes the database, which other tenants may still be using elsewhere on the server.

## Verify

```sql
SELECT name, apply_to_list FROM system.row_policies WHERE database = 'db_a' AND name LIKE 'bravo%' ORDER BY name;
```

Expected output after step 4:

```text
   ┌─name─────────────────────────────────────────┬─apply_to_list─────────────────────────┐
1. │ bravo_iso ON db_a.flows                      │ ['bi_bravo@db_a','writer_bravo@db_a'] │
2. │ bravo_iso ON db_a.flows_by_application_1m    │ ['bi_bravo@db_a']                     │
3. │ bravo_iso ON db_a.flows_by_conversation_1m   │ ['bi_bravo@db_a']                     │
4. │ bravo_iso ON db_a.flows_by_exporter_iface_1m │ ['bi_bravo@db_a']                     │
5. │ bravo_iso ON db_a.flows_by_geo_asn_1m        │ ['bi_bravo@db_a']                     │
6. │ bravo_iso ON db_a.flows_dead_letter          │ ['bi_bravo@db_a']                     │
   └──────────────────────────────────────────────┴───────────────────────────────────────┘
```

Between steps 1 and 3 the `flows` list reads `['bi_bravo','bi_bravo@db_a','writer_bravo','writer_bravo@db_a']`: both spellings, filtered to the same rows.

## Close a migrated database to the old roles {/* #revoking-the-pre-rename-roles-on-a-migrated-database */}

Step 4 finishes the migration for a tenant.
It does not close the database: the pre-rename `flow_writer` and `flow_reader` roles are instance-wide, and re-onboarding never took away the `INSERT` and `SELECT` they already hold on the databases they covered.
So `db_a` can be fully migrated while a legacy account belonging to some other, unmigrated tenant still reaches it.
Measured on the pinned image: that account inserts into the migrated database's `flows`.

`revoke-legacy` takes those grants back, one database at a time.
Per-database is what makes it safe: `REVOKE … ON db_a.flows FROM flow_writer` leaves the same account still writing to an unmigrated `db_b` through the same role, so closing one database never takes another's ingest down.

1. Run it with **`--dry-run`**, which runs every check and prints the exact statements without executing one.

   ```bash
   java -jar riptide.jar revoke-legacy \
     --admin-url http://127.0.0.1:8123 --admin-user admin --admin-password env://CH_ADMIN_PW \
     --database db_a --dry-run
   ```

   Expected output:

   ```text
   Would revoke INSERT, SELECT, SHOW TABLES from the pre-rename roles flow_writer and flow_reader on `db_a`.flows, `db_a`.flows_dead_letter, `db_a`.flows_by_application_1m, `db_a`.flows_by_application_1m_mv, `db_a`.flows_by_conversation_1m, `db_a`.flows_by_conversation_1m_mv, `db_a`.flows_by_exporter_iface_1m, `db_a`.flows_by_exporter_iface_1m_mv, `db_a`.flows_by_geo_asn_1m, `db_a`.flows_by_geo_asn_1m_mv. Nothing has been changed — re-run with --yes to apply:
   REVOKE INSERT, SELECT, SHOW TABLES ON `db_a`.flows FROM `flow_writer`
   REVOKE INSERT, SELECT, SHOW TABLES ON `db_a`.flows_dead_letter FROM `flow_writer`
   REVOKE INSERT, SELECT, SHOW TABLES ON `db_a`.flows_by_application_1m FROM `flow_writer`
   REVOKE INSERT, SELECT, SHOW TABLES ON `db_a`.flows_by_application_1m_mv FROM `flow_writer`
   REVOKE INSERT, SELECT, SHOW TABLES ON `db_a`.flows_by_conversation_1m FROM `flow_writer`
   REVOKE INSERT, SELECT, SHOW TABLES ON `db_a`.flows_by_conversation_1m_mv FROM `flow_writer`
   REVOKE INSERT, SELECT, SHOW TABLES ON `db_a`.flows_by_exporter_iface_1m FROM `flow_writer`
   REVOKE INSERT, SELECT, SHOW TABLES ON `db_a`.flows_by_exporter_iface_1m_mv FROM `flow_writer`
   REVOKE INSERT, SELECT, SHOW TABLES ON `db_a`.flows_by_geo_asn_1m FROM `flow_writer`
   REVOKE INSERT, SELECT, SHOW TABLES ON `db_a`.flows_by_geo_asn_1m_mv FROM `flow_writer`
   REVOKE INSERT, SELECT, SHOW TABLES ON `db_a`.flows FROM `flow_reader`
   REVOKE INSERT, SELECT, SHOW TABLES ON `db_a`.flows_dead_letter FROM `flow_reader`
   REVOKE INSERT, SELECT, SHOW TABLES ON `db_a`.flows_by_application_1m FROM `flow_reader`
   REVOKE INSERT, SELECT, SHOW TABLES ON `db_a`.flows_by_application_1m_mv FROM `flow_reader`
   REVOKE INSERT, SELECT, SHOW TABLES ON `db_a`.flows_by_conversation_1m FROM `flow_reader`
   REVOKE INSERT, SELECT, SHOW TABLES ON `db_a`.flows_by_conversation_1m_mv FROM `flow_reader`
   REVOKE INSERT, SELECT, SHOW TABLES ON `db_a`.flows_by_exporter_iface_1m FROM `flow_reader`
   REVOKE INSERT, SELECT, SHOW TABLES ON `db_a`.flows_by_exporter_iface_1m_mv FROM `flow_reader`
   REVOKE INSERT, SELECT, SHOW TABLES ON `db_a`.flows_by_geo_asn_1m FROM `flow_reader`
   REVOKE INSERT, SELECT, SHOW TABLES ON `db_a`.flows_by_geo_asn_1m_mv FROM `flow_reader`
   ```

   The revoke names that database's `flows`, its `flows_dead_letter`, every rollup target and every rollup materialized view, the mirror of what `onboard` grants there, and nothing else.
   The dead-letter table is in the list even though the pre-rename roles predate it: a `SELECT` hand-granted on it to the instance-wide `flow_reader` would reopen exactly the cross-database read this command closes, and a `REVOKE` naming an absent table is a measured no-op on the pinned image.
   It takes back `SHOW TABLES` too, because a `SELECT` on a rollup view reads around the row policy attached to its target.

2. Replace `--dry-run` with **`--yes`** to apply.
   `--yes` is required for the reason `offboard` requires it: this takes privileges away from a live server, and `--database` defaults to `riptide`.

   Expected output:

   ```text
   Revoked INSERT, SELECT, SHOW TABLES from the pre-rename roles flow_writer and flow_reader on `db_a`.flows, … . A pre-rename account can no longer reach database 'db_a'. The roles themselves are left in place: they carry no database in their name, so dropping one would revoke every database still on the old naming. Run this in each database as you finish migrating it; once no database needs them and no user holds them, drop them by hand. The statements that ran:
   REVOKE INSERT, SELECT, SHOW TABLES ON `db_a`.flows FROM `flow_writer`
   …
   ```

3. Run it again to confirm it is a no-op.

   ```text
   Nothing to revoke: the pre-rename roles hold no INSERT, SELECT or SHOW TABLES reaching database 'db_a'. Either this was already run here, or the server was provisioned after the rename. (The database and its flows table do exist — that is checked first, so this is not a mistyped --database.)
   ```

Run it in each database as you finish migrating it.

### When it refuses

It changes nothing in any of these cases; each is a state where the checks would otherwise have found nothing and read that as a clean answer.
The exact messages are in the [message table](../reference/provisioning-cli.md#messages).

| Refusal | Why | Do this |
| --- | --- | --- |
| the database or its `flows` table does not exist | a mistyped `--database` matches no grant and no policy, so it would report an all-clear over an exposure it never looked at | fix the name |
| a pre-rename grantee is still named by a row policy there | `onboard` keeps every live pre-rename account named and stops naming one you retired, so a name carrying no `@db_a` still depends on this database; it may be a role rather than a user | finish steps 1 to 4 for its tenant, then re-run |
| the database has no row policies at all | zero policies is "nothing to reason from", and it is equally what a hand-provisioned database looks like, whose collector may still be authenticating as a pre-rename account | run `onboard` for each of its tenants first; that creates the policies this check reads |
| a policy applies to `ALL` | such a policy stores an empty grantee list, so the check would see no names on a database where everyone is served | re-issue it naming its grantees explicitly, as `onboard` does |
| a pre-rename role holds a grant wider than these tables (`ON db_a.*`, `ON *.*`) | running would leave the role holding everything else while reporting the database closed | revoke the wider grant by hand, then re-run |
| it cannot read `system.grants` or `system.row_policies` | ClickHouse refuses them to an admin without the privilege rather than filtering, so "nothing found" and "nothing to find" are the same answer | grant the privilege the message names, then re-run |

One failure it cannot pre-empt: if a `REVOKE` fails part-way through, the database is left half-revoked and the message says so.
Re-running finishes the job, because the statements are idempotent.
The usual cause is the one privilege no catalog read can check, `GRANT OPTION`.

## Drop the roles {/* #dropping-the-roles */}

Once no database needs the roles any more and no user holds them, drop them and the orphaned quota by hand.
Checking the second condition needs its own grant, because `system.role_grants` is refused, not filtered, to an admin without it:

```sql
GRANT SELECT ON system.role_grants TO <your admin user>;   -- otherwise the query below is refused
SELECT * FROM system.role_grants WHERE granted_role_name IN ('flow_writer', 'flow_reader');
-- only when that returns nothing:
DROP ROLE IF EXISTS flow_writer, flow_reader;
DROP QUOTA IF EXISTS flow_ingest;   -- (unverified) pre-rename quota name; no command composes it
```

Without the grant an under-privileged operator sees an empty result and concludes it is safe to drop roles another tenant still holds.
That blindness is why the per-database `revoke-legacy` exists: it closes each database as you migrate it, so the instance-wide drop is a tidy-up rather than the only defence.

## Offboard reaches every database

`offboard` drops both namings, so it revokes a tenant whether or not that tenant has been migrated.
The unqualified `writer_<tenant>` and `bi_<tenant>` it drops are keyed on the tenant alone, so `offboard --database db_b --tenant acme` removes the same credential that an unmigrated `db_a` is still ingesting with.
This is inherent to the old naming, and dropping them is still correct: leaving them would report a revocation that did not happen.
If any other database on this server still has that tenant on the old naming, it has just lost its credential; re-onboard it to give it a `@<database>` account of its own.
Migrating every database first (step 1) avoids the situation entirely.

## Related

- [Provisioning CLI reference](../reference/provisioning-cli.md) for the flags, the privileges and every refusal message.
- [How multi-tenancy works](../architecture/multi-tenancy.md) for why names carry the database and why an unnamed user reads every row.

## Open questions

- The pre-rename quota name `flow_ingest` in the drop block is inferred; the only quota name the code composes is the qualified `flow_ingest@<database>`, and `revoke-legacy` touches no quota. `(unverified)`
- Outputs were captured against a throwaway `clickhouse/clickhouse-server:26.7` container (26.7.13.12) seeded with hand-created pre-rename roles and users, not against a server that was genuinely onboarded by an older riptide.
