---
title: riptide onboard, offboard and revoke-legacy
sidebar_position: 11
description: Flags, defaults, exit codes, admin privileges, the DDL each subcommand emits and every message it can refuse with.
---

# Provisioning CLI reference

The three subcommands run with no Spring context against an admin credential passed on the command line, never the collector's scoped credential.
Secret references resolve through `plain`, `env://VAR` and `file:///path[#key]` only.

## Command line

```text
riptide onboard  --admin-url URL [--admin-user U] [--admin-password REF] \
                 --tenant T --org O --writer-secret REF --reader-secret REF \
                 [--database DB] [--quota-bytes N] \
                 [--create-schema [--ttl-days N]]
riptide offboard --admin-url URL [--admin-user U] [--admin-password REF] \
                 --tenant T [--database DB] --yes
riptide revoke-legacy --admin-url URL [--admin-user U] [--admin-password REF] \
                 [--database DB] (--yes | --dry-run)
```

`riptide` is `java -jar riptide.jar` for the plain jar, `/usr/share/riptide/riptide.jar` for the deb and rpm.
Every option is `--key value`; the flags `--yes`, `--create-schema` and `--dry-run` take no value.
An unknown positional argument is an error.

### Shared options

| Option | Type | Default | Description |
| --- | --- | --- | --- |
| **`--admin-url`** | URL | required | ClickHouse HTTP endpoint the admin credential connects to |
| **`--admin-user`** | string | `default` | admin user |
| **`--admin-password`** | secret ref | empty | admin password |
| **`--database`** | string | `riptide` | database holding `flows`; also qualifies the generated account and role names (`writer_<tenant>@<database>`, `bi_<tenant>@<database>`, `flow_writer@<database>`, `flow_reader@<database>`, `flow_ingest@<database>`), because ClickHouse users, roles and quotas are instance-wide |

### `riptide onboard`

Provisions one `(tenant, org)` idempotently and prints the collector's config stanza on stdout; every other line goes to stderr.

| Option | Type | Default | Description |
| --- | --- | --- | --- |
| **`--tenant`** | string | required | tenant id; names `writer_<tenant>@<db>` and `bi_<tenant>@<db>`. Must match `[A-Za-z0-9_-]+` |
| **`--org`** | string | required | organisation pinned as `SQL_org` on the tenant's users. Same charset |
| **`--writer-secret`** | secret ref | required | password of the ingest writer; the printed stanza references the same secret |
| **`--reader-secret`** | secret ref | required | password of the BI reader |
| **`--quota-bytes`** | integer | `50000000000` | `written_bytes` per hour allowed to each writer user, one bucket per user (`written_rows` is not a ClickHouse quota metric) |
| **`--create-schema`** | flag | off | bootstrap the database, `flows`, the dead-letter table and the four 1-minute rollup tables and views when absent; also the way to add the rollups or the dead-letter table to a deployment provisioned before they existed |
| **`--ttl-days`** | integer | `30` | retention of a `flows` table this run creates; requires `--create-schema`; 1 to 10950 (ClickHouse `DateTime` ends in 2106, a larger interval wraps and expires data immediately). Ignored with a warning when the table already exists |

Re-running is safe: users are created `IF NOT EXISTS` and then have their password reconciled with `ALTER USER`, so a rotated secret is applied; `CONST` settings are preserved; row policies are re-asserted with `OR REPLACE`.
A re-run against an existing schema emits no `CREATE` statement, so an admin without `CREATE` privileges keeps working.

Example, first run on a fresh single-node server:

```bash
export CH_ADMIN_PW=admin ACME_WRITER_PW=w-secret ACME_READER_PW=r-secret
java -jar riptide.jar onboard \
  --admin-url http://127.0.0.1:8123 --admin-user admin --admin-password env://CH_ADMIN_PW \
  --tenant acme --org acme-eu \
  --writer-secret env://ACME_WRITER_PW --reader-secret env://ACME_READER_PW \
  --create-schema
```

Expected output (stderr first, then the stanza on stdout):

```text
Onboarded tenant 'acme' (org 'acme-eu') into database 'riptide'. Add this to the tenant's riptide config (the collector authenticates as 'writer_acme@riptide'):
riptide.clickhouse.username=writer_acme@riptide
riptide.clickhouse.password=env://ACME_WRITER_PW
riptide.identity.tenant=acme
riptide.identity.organisation=acme-eu
```

### `riptide offboard`

Drops the tenant's users, in both the qualified and the pre-rename spelling, and its row policies on `flows`, `flows_dead_letter` and every rollup.
The database's roles, constraints and quota stay; they are shared by every tenant in the database.

| Option | Type | Default | Description |
| --- | --- | --- | --- |
| **`--tenant`** | string | required | tenant to remove |
| **`--yes`** | flag | off | required; without it the command refuses with exit code 2 |

```bash
java -jar riptide.jar offboard \
  --admin-url http://127.0.0.1:8123 --admin-user admin --admin-password env://CH_ADMIN_PW \
  --tenant acme --yes
```

Expected output:

```text
Offboarded tenant 'acme' from database 'riptide': dropped writer_acme@riptide and bi_acme@riptide, and the tenant's row policies on flows, the dead-letter table and every rollup. The database's roles, constraints and quota are left in place: they are shared by every tenant in this database, so offboard never removes them. If this was the last tenant here, drop them by hand.
```

When a pre-rename `writer_<tenant>` or `bi_<tenant>` existed, a `note:` line names what was dropped and that the drop reached every database on the server; when the admin lacks `SHOW USERS`, the note says the check could not be made.

### `riptide revoke-legacy`

Takes back `INSERT`, `SELECT` and `SHOW TABLES` from the pre-rename `flow_writer` and `flow_reader` roles on one database: its `flows`, `flows_dead_letter`, every rollup target and every rollup `_mv` view.
The roles are never dropped.

| Option | Type | Default | Description |
| --- | --- | --- | --- |
| **`--dry-run`** | flag | off | run every check, print the statements, execute none; needs no `--yes` |
| **`--yes`** | flag | off | apply; required without `--dry-run` |

```bash
java -jar riptide.jar revoke-legacy \
  --admin-url http://127.0.0.1:8123 --admin-user admin --admin-password env://CH_ADMIN_PW \
  --database db_a --dry-run
```

Expected output (two roles, ten tables, twenty statements):

```text
Would revoke INSERT, SELECT, SHOW TABLES from the pre-rename roles flow_writer and flow_reader on `db_a`.flows, `db_a`.flows_dead_letter, `db_a`.flows_by_application_1m, `db_a`.flows_by_application_1m_mv, `db_a`.flows_by_conversation_1m, `db_a`.flows_by_conversation_1m_mv, `db_a`.flows_by_exporter_iface_1m, `db_a`.flows_by_exporter_iface_1m_mv, `db_a`.flows_by_geo_asn_1m, `db_a`.flows_by_geo_asn_1m_mv. Nothing has been changed — re-run with --yes to apply:
REVOKE INSERT, SELECT, SHOW TABLES ON `db_a`.flows FROM `flow_writer`
REVOKE INSERT, SELECT, SHOW TABLES ON `db_a`.flows_dead_letter FROM `flow_writer`
…
REVOKE INSERT, SELECT, SHOW TABLES ON `db_a`.flows_by_geo_asn_1m_mv FROM `flow_reader`
```

On a database the roles no longer reach:

```text
Nothing to revoke: the pre-rename roles hold no INSERT, SELECT or SHOW TABLES reaching database 'db_a'. Either this was already run here, or the server was provisioned after the rename. (The database and its flows table do exist — that is checked first, so this is not a mistyped --database.)
```

### Exit codes

| Code | Meaning |
| --- | --- |
| `0` | success, including "Nothing to revoke" |
| `1` | a provisioning refusal or a failed statement; `error:` on stderr names it, and nothing was changed unless the message says half-revoked |
| `2` | bad arguments, a missing `--yes`, or an unresolvable secret |

## Admin privileges

| Mode | Minimum privileges for the admin credential |
| --- | --- |
| default (schema exists) | `CREATE USER`, `CREATE ROLE`, `CREATE QUOTA`, `CREATE ROW POLICY`, `ALTER USER`, `ALTER ROLE`, `DROP USER`, `DROP ROW POLICY` (offboard), `ALTER TABLE` on `<db>.flows`, `INSERT` and `SELECT` on `<db>.flows`, `SHOW TABLES` on the rollup views, `SELECT` on `system.databases`, `system.tables` and `system.columns` with grant option (they are granted onward to the roles), and `SHOW USERS ON *.*` (see [Messages](#messages)) |
| re-running against a database another admin provisioned | the above, plus `ROLE ADMIN` `(unverified)`: granting a role it did not itself create requires it, and the per-database roles were created by whichever admin ran the first `onboard` there |
| `--create-schema` | the above, plus `CREATE DATABASE ON <db>.*`, `CREATE TABLE ON <db>.*` (the `flows` table, the dead-letter table and the rollup targets) and `CREATE VIEW ON <db>.*` (the rollups' materialized views) |
| `revoke-legacy` (standalone) | `INSERT`, `SELECT` on `<db>.*` with grant option (revoking a role's privilege needs it; `ROLE ADMIN` does not), plus `SELECT` on `system.grants` and `system.row_policies`. It uses none of `CREATE USER`, `CREATE ROLE`, `CREATE ROW POLICY` or `SHOW USERS` |

`GRANT OPTION` is the one requirement neither catalog read can detect, so omitting it fails on the first `REVOKE` rather than up front.
`SHOW USERS` covers neither catalog read, and neither `SHOW ROLES` nor `SHOW ROW POLICIES` covers `system.grants`.

## What onboard emits

Statements in execution order.
A default run emits no `CREATE DATABASE`, `CREATE TABLE` or `CREATE VIEW`; the schema blocks appear only with **`--create-schema`** and only when the object is absent.
`IF NOT EXISTS` never replaces a table.

```sql
-- --create-schema only, when flows is absent: single-node MergeTree, TTL from --ttl-days (default 30)
CREATE DATABASE IF NOT EXISTS riptide;
CREATE TABLE IF NOT EXISTS riptide.flows (…);
-- --create-schema only, when a rollup target or view is absent. Additive columns first, because the
-- views select srcCountry, dstCountry and exporterName, which a pre-0.5 table lacks.
ALTER TABLE riptide.flows ADD COLUMN IF NOT EXISTS … ;
CREATE TABLE IF NOT EXISTS riptide.flows_by_application_1m (…);          -- and three more, TTL 365 days
-- Existing rollups are repaired in place before the views are created: one ALTER per target that
-- gained a dimension, then MODIFY QUERY on its view. A shrink of the sorting key is refused.
CREATE MATERIALIZED VIEW IF NOT EXISTS riptide.flows_by_application_1m_mv
  TO riptide.flows_by_application_1m AS SELECT … FROM riptide.flows AS f GROUP BY …;
-- --create-schema only, when flows_dead_letter is absent; same TTL as the raw table, no CHECK.
CREATE TABLE IF NOT EXISTS riptide.flows_dead_letter (tenant, failedAt, error, payload);

-- Once per database, on every run. Additive column upgrades come first, so a re-run brings a
-- pre-existing table up to date in place.
ALTER TABLE riptide.flows ADD COLUMN IF NOT EXISTS … ;   -- one per column added since the table was created
CREATE ROLE IF NOT EXISTS `flow_writer@riptide`;
GRANT INSERT ON riptide.flows TO `flow_writer@riptide`;
-- The writer also reads flows: a materialized view runs as the inserting user.
GRANT SELECT ON riptide.flows TO `flow_writer@riptide`;
CREATE ROLE IF NOT EXISTS `flow_reader@riptide`;
GRANT SELECT ON riptide.flows TO `flow_reader@riptide`;
GRANT SELECT ON system.databases TO `flow_reader@riptide`;
GRANT SELECT ON system.tables    TO `flow_reader@riptide`;
GRANT SELECT ON system.columns   TO `flow_reader@riptide`;
ALTER ROLE `flow_reader@riptide` SETTINGS readonly = 2, allow_ddl = 0;
ALTER TABLE riptide.flows ADD CONSTRAINT IF NOT EXISTS tenant_pinned CHECK tenant = getSetting('SQL_tenant');
ALTER TABLE riptide.flows ADD CONSTRAINT IF NOT EXISTS org_pinned    CHECK organisation = getSetting('SQL_org');
CREATE QUOTA IF NOT EXISTS `flow_ingest@riptide` FOR INTERVAL 1 hour MAX written_bytes = 50000000000
  KEYED BY user_name TO `flow_writer@riptide`;
GRANT INSERT ON riptide.flows_dead_letter TO `flow_writer@riptide`;      -- INSERT only; the SELECT is per user, below
GRANT INSERT ON riptide.flows_by_application_1m TO `flow_writer@riptide`;   -- and the other three
GRANT SELECT ON riptide.flows_by_application_1m TO `flow_reader@riptide`;
GRANT SHOW TABLES ON riptide.flows_by_application_1m_mv TO `flow_writer@riptide`;   -- and the other three

-- Per tenant: two scoped users, two role grants, one row policy per table.
CREATE USER IF NOT EXISTS `writer_acme@riptide` IDENTIFIED WITH sha256_password BY '…'
  SETTINGS SQL_tenant = 'acme' CONST, SQL_org = 'acme-eu' CONST;
ALTER USER `writer_acme@riptide` IDENTIFIED WITH sha256_password BY '…';
GRANT `flow_writer@riptide` TO `writer_acme@riptide`;
CREATE USER IF NOT EXISTS `bi_acme@riptide` IDENTIFIED WITH sha256_password BY '…'
  SETTINGS SQL_tenant = 'acme' CONST, SQL_org = 'acme-eu' CONST;
ALTER USER `bi_acme@riptide` IDENTIFIED WITH sha256_password BY '…';
GRANT `flow_reader@riptide` TO `bi_acme@riptide`;
-- The policy name is not qualified: a policy's identity is `name ON db.table`.
-- A live pre-rename writer_acme / bi_acme is kept in every TO list below for as long as it exists.
CREATE ROW POLICY OR REPLACE acme_iso ON riptide.flows
  FOR SELECT USING tenant = 'acme' TO `bi_acme@riptide`, `writer_acme@riptide`;
CREATE ROW POLICY OR REPLACE acme_iso ON riptide.flows_dead_letter
  FOR SELECT USING tenant = 'acme' TO `bi_acme@riptide`;
GRANT SELECT ON riptide.flows_dead_letter TO `bi_acme@riptide`;           -- per user, right after its policy
CREATE ROW POLICY OR REPLACE acme_iso ON riptide.flows_by_application_1m
  FOR SELECT USING tenant = 'acme' TO `bi_acme@riptide`;                    -- and the other three
```

What the run leaves behind on ClickHouse 26.7.13.12, read back as the admin:

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

```sql
SHOW GRANTS FOR `flow_writer@riptide`;
```

Expected output:

```text
GRANT SELECT, INSERT ON riptide.flows TO `flow_writer@riptide`
GRANT INSERT ON riptide.flows_by_application_1m TO `flow_writer@riptide`
GRANT SHOW TABLES ON riptide.flows_by_application_1m_mv TO `flow_writer@riptide`
GRANT INSERT ON riptide.flows_by_conversation_1m TO `flow_writer@riptide`
GRANT SHOW TABLES ON riptide.flows_by_conversation_1m_mv TO `flow_writer@riptide`
GRANT INSERT ON riptide.flows_by_exporter_iface_1m TO `flow_writer@riptide`
GRANT SHOW TABLES ON riptide.flows_by_exporter_iface_1m_mv TO `flow_writer@riptide`
GRANT INSERT ON riptide.flows_by_geo_asn_1m TO `flow_writer@riptide`
GRANT SHOW TABLES ON riptide.flows_by_geo_asn_1m_mv TO `flow_writer@riptide`
GRANT INSERT ON riptide.flows_dead_letter TO `flow_writer@riptide`
```

## What offboard emits

```sql
DROP ROW POLICY IF EXISTS acme_iso ON riptide.flows;
DROP ROW POLICY IF EXISTS acme_iso ON riptide.flows_dead_letter;
DROP ROW POLICY IF EXISTS acme_iso ON riptide.flows_by_application_1m;   -- and the other three
DROP USER IF EXISTS `bi_acme@riptide`;
DROP USER IF EXISTS `writer_acme@riptide`;
DROP USER IF EXISTS writer_acme;   -- the pre-rename pair, keyed on the tenant alone
DROP USER IF EXISTS bi_acme;
```

## What revoke-legacy emits

For each of `flow_writer` and `flow_reader` that holds a grant on the database, one statement per table in this order: `flows`, `flows_dead_letter`, then each rollup target followed by its `_mv` view.

```sql
REVOKE INSERT, SELECT, SHOW TABLES ON `db_a`.flows FROM `flow_writer`;
```

`REVOKE` of a privilege the role does not hold, or on a table that does not exist, is a silent no-op on 26.7, so the full mirror is emitted even for a role that only ever held part of it.

## Messages

Every refusal below leaves the server unchanged unless the message says otherwise.

| Message | Probable cause | Recovery |
| --- | --- | --- |
| **`database 'riptide' has no flows table — re-run with --create-schema to bootstrap it, or check the --database value for typos (an admin-provisioned table is also accepted)`** | first `onboard` on a fresh server, or a mistyped `--database` | add `--create-schema` on a single node; pre-create `flows` admin-side on a replicated cluster |
| **`database 'riptide' is missing the 1-minute rollup tables or their materialized views — re-run with --create-schema to add them. …`** | a database provisioned before the rollups existed, or an interrupted bootstrap that left a target without its view | re-run with `--create-schema`; only tables and views are created, `flows` and its data are untouched |
| **`database 'riptide' is missing the dead-letter table (flows_dead_letter) — re-run with --create-schema to add it. …`** | a database provisioned before `flows_dead_letter` existed | re-run with `--create-schema`; one table is created and nothing else |
| **`warning: --ttl-days ignored — the flows table already exists, its retention is unchanged (use ALTER TABLE ... MODIFY TTL to change it)`** | `--ttl-days` on a re-run | `ALTER TABLE riptide.flows MODIFY TTL timestamp + INTERVAL <n> DAY` |
| **`error: --ttl-days requires --create-schema`** | `--ttl-days` without the flag | add `--create-schema`, or drop `--ttl-days` |
| **`error: --ttl-days must be between 1 and 10950 …`** | out of range | pick a value in range |
| **`warning: the pre-rename account 'writer_acme' still exists on this server, and it is instance-wide … Then: DROP USER `writer_acme`, and re-run onboard here so the policies stop naming it.`** | the tenant was onboarded before names carried the database | follow [Migrate a deployment onboarded before the rename](../guides/migrate-tenant-accounts.md) |
| **`error: could not check whether tenant 'acme' still has pre-rename (database-unqualified) accounts on this server: Code: 497. DB::Exception: … it's necessary to have the grant SELECT ON system.users. (ACCESS_DENIED) … GRANT SHOW USERS ON *.* TO <your --admin-user>`** | the admin lacks `SHOW USERS`; `CREATE USER` and `DROP USER` do not imply it | `GRANT SHOW USERS ON *.* TO <admin>`. The run aborts before executing anything, because re-issuing `acme_iso` without a live pre-rename account in its `TO` list would leave that account reading every tenant's rows |
| **`refusing to offboard 'acme' from database 'riptide' without --yes …`** | `--yes` missing | add `--yes` |
| **`note: could not check whether tenant 'acme' also had pre-rename (database-unqualified) accounts — reading system.users needs GRANT SHOW USERS ON *.*. Any that existed have now been dropped …`** | `offboard` by an admin without `SHOW USERS` | verify no other database relied on `writer_acme` or `bi_acme` |
| **`refusing to revoke the pre-rename roles' grants on database 'riptide' without --yes … Run with --dry-run first to see the exact statements.`** | neither `--yes` nor `--dry-run` | add one |
| **`error: database 'nope' has no flows table, so there is nothing here to revoke and no way to tell a migrated database from a mistyped one. …`** | mistyped `--database` | fix the name; it defaults to `riptide` |
| **`error: refusing to revoke the pre-rename roles' grants on database 'db_a': its row policies still name the database-unqualified grantees bi_bravo, writer_bravo, so that database is not migrated yet …`** | a pre-rename grantee still serves this database; it may be a role rather than a user | finish steps 1 to 4 of the migration for that tenant, then re-run |
| **`error: refusing to revoke on database 'db_a': it has NO row policies on its flows or rollup tables, so there is nothing to reason from. …`** | a hand-provisioned database, or one whose policies were dropped | run `onboard` for each tenant first; if the database genuinely has no tenants, revoke by hand |
| **`error: refusing to revoke on database 'db_a': a row policy on its flows or rollup tables applies to ALL principals (or to all except a listed few) …`** | a policy `TO ALL` stores an empty grantee list | re-issue the policy naming its grantees explicitly, as `onboard` does |
| **`error: refusing to revoke on database 'db_a': a pre-rename role holds a grant WIDER than this command can take back — `flow_reader` on db_a.*. … Revoke the wider grant by hand, for example: REVOKE INSERT, SELECT, SHOW TABLES ON db_a.* FROM `flow_reader` — then re-run this command.`** | a role holds `ON db_a.*` or `ON *.*` | revoke the wider grant by hand, then re-run |
| **`error: could not read which grants the pre-rename roles hold on database 'riptide': Code: 497. … it's necessary to have the grant SELECT ON system.grants. (ACCESS_DENIED) …`** | ClickHouse refuses `system.grants` rather than filtering it | `GRANT SELECT ON system.grants TO <admin>` |
| **`error: could not check whether a pre-rename grantee still serves database 'riptide': … GRANT SELECT ON system.row_policies TO <your --admin-user>`** | same, for `system.row_policies` | `GRANT SELECT ON system.row_policies TO <admin>` |
| **`error: the revoke failed part-way through on database 'db_a' …`** | a `REVOKE` failed after earlier ones ran, usually a missing `GRANT OPTION` | grant it and re-run; the statements are idempotent and the re-run finishes the job |

## Open questions

- The `ROLE ADMIN` row in the privileges table is stated from ClickHouse's grant rules, not measured. `(unverified)`
- The half-revoked message was not reproduced on a live server; its wording is taken from the source.
- Output blocks on this page were captured against a throwaway `clickhouse/clickhouse-server:26.7` container (26.7.13.12), not the compose stack.
