# Database setup for an AI-assisted installation

This runbook is part of [the agent contract](AGENTS.md) and
[installation procedure](INSTALL.md). It explicitly covers **Windchill-schema
privilege grants, DDL table creation and post-change verification**.

**Development and test environments only. Do not install or run DB Ninja in
production.** Stop if the environment classification is unknown. A repository
URL, local administrator account or `DBNINJA_ORACLE_CONFIRMED=yes` flag is not
permission to grant database privileges or execute DDL.

## 1. Discover the actual target and obtain separate approvals

The AI bot must record privately, and confirm with the owner/DBA:

- The actual Windchill database connection, Oracle release/RU, PDB, schema owner
  and whether this is a fresh DB Ninja installation or an existing one.
- Authorization for each proposed privilege/quota change, the four-table
  CREATE script, the maintenance window and any later service restart.
- The least-privilege grantor connection and a separate Windchill schema-owner
  connection, both through the site's approved secure authentication mechanism.
- The current grants, default data tablespace, INDX availability/space/quotas,
  and the rollback/repair owner. Preserve a private pre-change record.

Do not guess the schema from a saved connection label, use SYS as the application
schema, create a replacement Windchill user, or pass passwords in shell commands.
Do not request that passwords be pasted into chat. An AI without an explicitly
authorized connection must hand the reviewed SQL to the DBA and wait for results.

As the **actual Windchill schema**, use a new session to inspect identity:

```sql
SELECT SYS_CONTEXT('USERENV','SESSION_USER') AS session_user,
       SYS_CONTEXT('USERENV','CURRENT_SCHEMA') AS current_schema,
       SYS_CONTEXT('USERENV','DB_NAME') AS db_name,
       SYS_CONTEXT('USERENV','CON_NAME') AS container_name
FROM dual;
```

The session user and current schema must be the intended owner. Container
identity must match Windchill's PDB; a grant in a different container is not
evidence of access here. Keep actual identities out of public reports.

## 2. Review missing privileges and capacity

Run [oracle-check.sql](sql/oracle-check.sql) with the site's approved client.
It reads effective system privileges, tablespace/quota information, SCN,
diagnostic views, monitoring rows and existing module tables. It makes no
database changes and exits on the first error. A permission error means the
subsequent probes did not run; record it instead of reporting a complete pass.
It is not proof of every historical-query privilege or sufficient undo.

| Operation | Requirement and grant decision |
|---|---|
| Login | The existing Windchill schema must already be usable. Do not create another user or grant DBA/RESOURCE roles as a shortcut. |
| First-install tables | `CREATE TABLE` in its own schema plus adequate quota on the approved default data tablespace. Existing rights may already suffice. |
| Primary-key/secondary indexes | Indexes are on the schema's own tables; review INDX quota/space. Do not invent a `CREATE INDEX` system privilege or grant `CREATE ANY INDEX`/`CREATE ANY TABLE` for this owner-schema install. |
| SCN acquisition | Runtime tries `DBMS_FLASHBACK.GET_SYSTEM_CHANGE_NUMBER`, with `V$DATABASE.CURRENT_SCN` as fallback. Review `EXECUTE ON SYS.DBMS_FLASHBACK` and/or the separately approved `SELECT ON SYS.V_$DATABASE`. |
| FLASHBACK and SNAPSHOT | Both need usable Oracle Flashback Query access and undo. Qualify owned-table access first. Do not grant `FLASHBACK ANY TABLE` by default; current collection is based on the schema's own eligible tables. |
| Monitoring flush | The runtime calls `DBMS_STATS.FLUSH_DATABASE_MONITORING_INFO`. Oracle 19c documents **ANALYZE ANY** for this call. Execution access to `SYS.DBMS_STATS` must also exist (often through PUBLIC). This broad system privilege requires explicit DBA approval; Flashback object grants do not replace it. |
| Read-only diagnostic script | Its full baseline probes use `SYS.V_$DATABASE`, `SYS.V_$PARAMETER` and `SYS.V_$UNDOSTAT`. Review individual SELECT grants; do not grant `SELECT ANY DICTIONARY` or a broad catalog role merely for these views. |
| Persistence after install | The Windchill schema owns and maintains the four module tables. No public table grants or separate DB Ninja runtime account are required. |

`SESSION_PRIVS` includes enabled-role privileges, while object/package access
can also come from ownership, roles or PUBLIC. A missing row in a direct-grant
view alone is not proof that a new grant is needed. Test the actual operations
using the real application schema/session rights.

Oracle references:
[CREATE TABLE prerequisites](https://docs.oracle.com/en/database/oracle/oracle-database/19/sqlrf/CREATE-TABLE.html),
[CREATE INDEX prerequisites](https://docs.oracle.com/en/database/oracle/oracle-database/19/sqlrf/CREATE-INDEX.html)
and [the FLASHBACK/monitoring references](COMPATIBILITY.md#flashback-and-snapshot-privilege-checklist).

## 3. Apply only explicitly approved grants

The checked-in [grant/quota review template](sql/oracle-prerequisites.template.sql)
contains **commented examples only**. Running that file does not grant anything.
The AI bot must prepare a separate private script containing only missing,
DBA-approved statements, with the exact reviewed schema identifier substituted.
Angle-bracket placeholders are not executable Oracle identifiers.

Examples of statements a DBA may select, **not a blanket installation command**:

```sql
GRANT CREATE TABLE TO <WINDCHILL_SCHEMA>;
GRANT EXECUTE ON SYS.DBMS_FLASHBACK TO <WINDCHILL_SCHEMA>;
GRANT EXECUTE ON SYS.DBMS_STATS TO <WINDCHILL_SCHEMA>;
GRANT SELECT ON SYS.V_$DATABASE TO <WINDCHILL_SCHEMA>;
GRANT SELECT ON SYS.V_$PARAMETER TO <WINDCHILL_SCHEMA>;
GRANT SELECT ON SYS.V_$UNDOSTAT TO <WINDCHILL_SCHEMA>;
GRANT ANALYZE ANY TO <WINDCHILL_SCHEMA>;
```

Omit privileges already available or not selected by the approved access plan.
If quotas need adjustment, the DBA chooses specific capacities and tablespaces;
do not automatically use `UNLIMITED TABLESPACE`, `QUOTA UNLIMITED`, or reduce
an existing allocation. Do not change UNDO_RETENTION, retention guarantee,
ARCHIVELOG, supplemental logging or unrelated site privileges.

With explicit approval for those exact statements and a secure authorized
grantor connection, the AI may execute the reviewed private script and capture
each result. Otherwise the DBA executes it and supplies the outcome.
Neither the Node installer nor the public template auto-grants privileges.
Stop on a failed/denied grant; do not escalate to an ANY/DBA privilege to bypass
it. If ANALYZE ANY is declined, obtain a separately reviewed qualification of
the explicit full-scan fallback and its workload impact rather than claiming
the normal monitoring-flush path is qualified.

GRANT and quota DDL commit implicitly. Use a fresh administrative session
without pending business work. Never record credentials or actual schema
identifiers in the repository, release notes, public issues or screenshots.
Do not automatically revoke pre-existing rights during rollback.

## 4. Reconnect as Windchill and verify the grants

Use a **new Windchill schema-owner session**, not the grantor/SYS session:

1. Recheck user/current-schema/PDB identity and rerun
   [oracle-check.sql](sql/oracle-check.sql). Require all selected baseline probes
   to complete. If a diagnostic view was intentionally not approved, document
   that limitation and run a DBA-reviewed private probe for the chosen SCN path;
   do not label the unexecuted full script successful.
2. Verify both historical read forms on a small, explicitly approved disposable
   test table owned by Windchill, using SCNs obtained from the approved SCN path:

   ```sql
   SELECT COUNT(*) FROM <APPROVED_OWNED_TEST_TABLE> AS OF SCN <START_SCN>;
   SELECT COUNT(*) FROM <APPROVED_OWNED_TEST_TABLE> AS OF SCN <END_SCN>;
   SELECT COUNT(*) FROM <APPROVED_OWNED_TEST_TABLE>
     VERSIONS BETWEEN SCN <START_SCN> AND <END_SCN>;
   ```

   Do not use an arbitrary large/business table or create business data without
   separate authorization. Privilege success alone does not guarantee undo
   retention, correct net changes or compatibility with every table type.
3. Separately obtain approval to exercise the monitoring flush:

   ```sql
   BEGIN
     DBMS_STATS.FLUSH_DATABASE_MONITORING_INFO;
   END;
   /
   ```

   This changes monitoring metadata and is intentionally absent from the
   read-only check script. Record failure or a deliberately untested operation;
   never silently convert it to a passing privilege test.

An expired SCN/undo error and a privilege error are different failures. Resolve
the actual cause with the DBA; do not add broad grants to fix missing undo.

## 5. Create the DB Ninja tables on a fresh installation

Follow [INSTALL.md](INSTALL.md#5-fresh-installation-only-initialize-the-schema):
validate the candidate, apply only the approved file plan, and keep the affected
application services stopped until database initialization is accepted.
**Existing or partially installed DB Ninja schemas do not run the CREATE script.**

For the exact bundled profile, from the extracted repository:

```text
node tools/schema-package.mjs verify
node tools/schema-package.mjs verify --target
```

These commands do not connect to Oracle. They require unchanged models and the
qualified Windchill 12.1.2.23 / Oracle 19c / `wt.db.maxBytesPerChar=3` / BYTE /
INDX profile; the DBA confirms the database-side conditions. Other profiles
require target PTC generation and a new reviewed script, not altered checksums.

After the DBA explicitly approves table creation, the AI may execute the
[combined first-install DDL](sql/oracle/create-db-ninja.sql) through the approved
Windchill-owner script client, or hand it to the DBA:

```text
@"/path/to/db-ninja/sql/oracle/create-db-ninja.sql"
```

Use a SQL*Plus-compatible **script-mode** client supporting `WHENEVER`, `SET`
and PL/SQL slash terminators. Do not send the whole file as one SQL statement,
run individual inputs, execute as SYS/SYSTEM or use altered CURRENT_SCHEMA.
Oracle DDL commits implicitly; successful earlier CREATEs cannot be rolled back
after a later failure. Stop for DBA repair/migration, never reset/drop and retry.

## 6. Verify creation and hand off runtime acceptance

The combined script prints tables, constraints and indexes. Require:

- Exactly **4 module tables**, **4 enabled/validated primary keys** and
  **18 valid indexes** (4 PK backing indexes plus 14 secondary indexes).
- Column names, NUMBER/DATE types and explicit VARCHAR2 BYTE widths matching
  [the source DDL](sql/oracle/README.md), and approved data/index tablespaces.
- Successful schema-owner access and agreed FLASHBACK/SNAPSHOT/monitoring
  checks, with no privilege tests falsely attributed to the grantor.
- A private record of the release/commit, SQL checksum, approved statements,
  results and any skipped checks; no credentials, capture rows or internal
  identities in published evidence.

Only then proceed to the separately authorized restart and disposable capture
acceptance in [INSTALL.md](INSTALL.md#6-restart-and-prove-runtime-behavior).
Test ordinary-user denial, capture ownership, both collector paths and strict
endpoint NET semantics. Report DDL, grants, package checks and runtime checks
as separate outcomes; do not report installation complete while a required
approval or verification remains outstanding.
