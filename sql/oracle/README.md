# Oracle first-install schema

**Development and test environments only. Do not install or run DB Ninja in
production.** These are custom DB Ninja definitions, not the Windchill vendor
schema. They are not an upgrade, reset or database restore.
Use [DATABASE-SETUP.md](../../DATABASE-SETUP.md) for AI-assisted privilege
review/application, schema-owner verification and the authorized CREATE sequence.

## Where is the DDL?

Use [create-db-ninja.sql](create-db-ninja.sql), the combined, guarded first-install
script. The eight source files remain available for DBA inspection:

| Table | CREATE TABLE / primary key | Secondary indexes |
|---|---|---|
| DBCAPTURESESSION | [Table](ddl/create_DbCaptureSession_Table.sql) | [Indexes](ddl/create_DbCaptureSession_Index.sql) |
| DBCAPTURECHANGE | [Table](ddl/create_DbCaptureChange_Table.sql) | [Indexes](ddl/create_DbCaptureChange_Index.sql) |
| DBCAPTUREATTRDELTA | [Table](ddl/create_DbCaptureAttrDelta_Table.sql) | [Indexes](ddl/create_DbCaptureAttrDelta_Index.sql) |
| DBCAPTURETABLECHANGE | [Table](ddl/create_DbCaptureTableChange_Table.sql) | [Indexes](ddl/create_DbCaptureTableChange_Index.sql) |

The package contains **4 tables, 4 primary keys, 14 secondary indexes and 4 table
comments**. The primary keys create four additional backing indexes: expect
**18 indexes in total** after a successful new installation. Index identifiers
are preserved exactly, including `DbCaptureTableChange$COMPOSI0`.

The three association classes in the seven ClassInfo files use columns on these
tables; they do not require three more tables. `DBCAPTURESQLEVENTS` is a legacy
table and is deliberately absent. Current native diagnostics use private
filesystem evidence. No module-specific sequence, trigger, grant, seed data,
DROP or reset script is required by this model. Windchill's existing persistence
infrastructure remains a prerequisite; do not create a substitute schema.

## Exact bundled profile

| Setting | Required profile |
|---|---|
| Windchill | Services 13.0.2.11 build32 / 13.0.2.0 CPS11, unchanged generated-model baseline |
| Database | Oracle 19c; actual RU, PDB and Windchill schema confirmed by the DBA |
| `wt.db.maxBytesPerChar` | **3**, in both the XCONF declaration and propagated properties |
| Strings | Explicit `VARCHAR2(n BYTE)`; widths from PTC's `sql3` generation, including 4000-byte caps |
| Data tables | The Windchill schema's approved default permanent tablespace |
| Primary-key and secondary indexes | Approved, online permanent tablespace **INDX**, with adequate quota/space |
| Application binary | Qualified Linux x64 / Java 17 prebuilt package; see [compatibility](../../COMPATIBILITY.md) |

The original custom-generated scripts were normalized only from CRLF to LF and
from implicit `VARCHAR2(n)` to explicit `VARCHAR2(n BYTE)`. Explicit BYTE avoids
silently inheriting a different session `NLS_LENGTH_SEMANTICS`.
[schema-profile.json](schema-profile.json) records input/output checksums and the
unchanged generated-model baseline. The combined script is reproducible with
the [strict assembler](../../tools/create-schema.mjs).

**Do not divide or multiply the widths, change INDX, edit checksums to bypass
verification, or change the site's encoding setting to fit this package.**
A different release/model, byte-width setting or tablespace policy requires
target PTC generation and DBA review using
[the alternative installation route](../../INSTALL.md#5-fresh-installation-only-initialize-the-schema).
Do not assume `sql3` is correct merely because that directory exists.

## Verify before DBA execution

From the repository root, without a database connection:

```text
node tools/schema-package.mjs verify
```

This requires all eight inputs and the combined script, validates model and
DDL checksums, parses every allowed statement, checks the exact object inventory
and compares the combined bytes with deterministic assembler output.

For the exact prebuilt target, set the reviewed `WT_HOME` and `JAVA_HOME`, then:

```text
node tools/schema-package.mjs verify --target
```

The target check verifies the prebuilt SDK/datecode/JDK and reads
`wt.db.maxBytesPerChar` with the installed xconfmanager wrapper and a Java
properties reader. It creates only private local tooling output, not live files.
It **does not connect to Oracle**, inspect quotas, authorize execution or execute
SQL. An unrecognized XCONF response or unpropagated/different width is a failure.
Inspect the property read-only; do not propagate unrelated pending site changes
just to make this check pass.

## Execution is a separate, authorized DBA step

1. Follow [INSTALL.md](../../INSTALL.md) through candidate validation, reviewed
   file deployment and the fresh-install maintenance procedure. Keep affected
   application services stopped; do not run concurrent installers.
2. Confirm Oracle 19c, the correct schema/PDB, a fresh schema-owner session with
   no pending transactions, default data tablespace and INDX capacity/quotas.
   Review [oracle-check.sql](../oracle-check.sql) and the
   [privilege template](../oracle-prerequisites.template.sql). Schema creation
   and FLASHBACK/SNAPSHOT privileges are separate approvals.
3. Review the complete SQL. Authenticate interactively through the site's
   approved Oracle client; never use a password in a command line or log.
   Do not execute as SYS/SYSTEM or through an altered `CURRENT_SCHEMA`.
4. In SQL*Plus, or a compatible script-mode client supporting `WHENEVER`,
   `SET` and PL/SQL slash terminators, run the **combined script once**, using
   the path in the extracted repository:

   ```text
   @"/path/to/db-ninja/sql/oracle/create-db-ninja.sql"
   ```

   Do not execute the eight input files separately: they lack the combined
   first-install guard. Do not send this script as one JDBC SQL statement.
5. Require a successful client result. Review the final table, primary-key and
   index queries: 4 tables, 4 enabled/validated primary keys and 18 valid indexes.
   Verify column definitions/widths against the source DDL. Proceed to the
   separately approved restart and runtime acceptance only after DBA sign-off.

Before the first CREATE, the script refuses existing reserved object names
(including index/backing-index names), conflicting primary-key constraint
names, SYS/SYSTEM or altered-current-schema sessions, and missing/offline
explicit tablespaces. Tablespace visibility is **not** proof of quota, free
space, all privileges or an approved default data tablespace.

Oracle DDL commits implicitly. `WHENEVER ... ROLLBACK` stops on errors and
rolls back pending transactional work, **not successful earlier CREATEs**.
A partial failure requires DBA diagnosis/migration; do not rerun, drop records
or weaken the precondition. Existing installations skip this script entirely.

This publication update statically validates the DDL and its provenance.
It is not evidence that this exact combined script was executed against a
new Oracle schema. Preserve that distinction in installation reports.
