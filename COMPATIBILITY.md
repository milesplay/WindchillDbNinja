# Compatibility and prerequisites

This is a **qualification matrix**, not a vendor support matrix. PTC's support
policy for the underlying Windchill/OS/JDK/Oracle combination still applies.
Do not install a newer JDK or database release merely because a row below mentions it.

**Development and test environments only. Do not install or run DB Ninja in
production**, including a technically compatible stack. An unknown environment
classification must be resolved with the owner before installation or capture.

## Windchill, Java and deployment layout

| Environment | Evidence / requirement |
|---|---|
| Windchill Services13.0.2.11 build32 (13.0.2.0 CPS11), Corretto 17.0.12, Jakarta Servlet, Oracle 19c, Linux x64 | Exact first-binary baseline. See [qualification evidence](LOCAL-INSTALL.md); historical icon acceptance is not new source/binary acceptance. |
| Windchill 13.1.4, Java 21, Oracle 19.3 | Original implementation provenance. This publication revision has not been requalified there. Rebuild with that installation's supported JDK and SDK. |
| Other 13.x versions/CPS levels | Conditional candidates, not verified. Rebuild JAR/ClassInfo, compile all JSPs, test profiler integration and run browser acceptance. |
| Windchill 12.x or any `javax.servlet` container | Incompatible as shipped. The JSP/API/SDK port is more than a documentation or environment-variable change. |
| Traditional on-premises `codebase` directory | Deployment tooling's qualified layout. The owner needs access to PTC Ant, CCD and xconfmanager. |
| `codebase.war`, immutable containers or Windchill+ | Not qualified by this installer; it refuses `codebase.war`. Use a provider-approved package/CCD process, not filesystem workarounds. |

The code uses Windchill persistence/MVC APIs, native profiler integration and
Log4j Core interfaces. File placement through a standard extension point does
not certify those implementation APIs for all future versions.

**Default to a target build.** The optional prebuilt package is restricted to the
exact baseline and matching source/target fingerprints. With the reviewed
`WT_HOME` and `JAVA_HOME`, run `node tools/prebuilt.mjs verify`, then
`node tools/validate.mjs all --prebuilt`; select it with
`node tools/dbninja.mjs plan --prebuilt`. The default `plan` uses the target build.
Other CPS/SDK combinations need rebuild and qualification; a similar version
label or successful checksum alone is insufficient.

The package contract is `prebuilt/DbCapture.jar`, exactly seven
`prebuilt/metadata/com/ptc/dbcapture/*.ClassInfo.ser` files and
`prebuilt/manifest.json` recording checksums, source fingerprints and target
version/SDK fingerprints. It contains no PTC/Oracle libraries, generated PTC
JavaScript bundles or portable pre-generated SQL. Fresh DDL is generated on the
target and reviewed by the DBA, even when the JAR is prebuilt.

The publication build compiles all current runtime Java with the target SDK
and `--release 17 -proc:none`, retaining only fingerprint-locked custom generated
entries and ClassInfo from the previous verified target CCD generation.
`deployment/generated-model-baseline.json` records the source, generated-entry,
metadata and SDK fingerprints. This is current-source compilation with unchanged
generated model artifacts, **not a new CCD or annotation-processing run**, and
does not write live metadata. The four model Java definitions and `.rbInfo`
source are unchanged. A changed model or CPS requires target CCD and a reviewed
new baseline; ordinary implementation recompilation cannot qualify stale
generated metadata. See [the assembly contract](HANDOFF.md#current-source-prebuilt-assembly).

Preserve the existing
`com.ptc.dbcapture` persistent identities and serialized DTO compatibility;
renaming them is a data migration, not a cosmetic packaging change.

## Database compatibility

| Database | Can this implementation run? |
|---|---|
| Oracle 19c | Baseline database family. Actual Version Query/undo behavior must be checked on the target RU and workload. |
| Other Oracle releases, including 21c/23ai | Not tested here. Require PTC certification of the underlying stack and separate feature qualification. SQL similarity is not evidence of compatibility. |
| Oracle multitenant | Connect to the PDB containing Windchill's schema. Grants in the wrong container do not qualify it. |
| Oracle RAC | Not independently qualified. Validate global capture locking, SCN behavior and all application nodes. |
| Oracle standby/read-only database | Not suitable: DB Ninja persists its own capture records in the Windchill schema. |
| Microsoft SQL Server / Azure SQL | **Not supported.** Requires a new collector and persistence/diagnostic review, not different JDBC settings. |
| PostgreSQL or any other non-Oracle database | **Not supported.** Oracle SCNs, Flashback Versions/AS OF queries and monitoring interfaces are fundamental dependencies. |

The filesystem preflight cannot establish the active database vendor from
installed DDL directories. Confirm the **actual connection/database**, not a
directory name or a saved connection label.

### Oracle operations to qualify

- `DBMS_FLASHBACK.GET_SYSTEM_CHANGE_NUMBER`, current SCN and required diagnostic views.
- `SELECT ... VERSIONS BETWEEN SCN ... AS OF SCN ...` on eligible schema tables.
- `SELECT ... AS OF SCN ...` for endpoint fallback. Fallback still depends on undo.
- `USER_TAB_MODIFICATIONS` and `DBMS_STATS.FLUSH_DATABASE_MONITORING_INFO`.
- Writes to four DB Ninja tables and their generated indexes; locks on its own session table.

Run [oracle-check.sql](sql/oracle-check.sql) as the actual schema user in a new
session. It performs reads, does not flush monitoring statistics and does not
prove every Flashback privilege or workload scenario. Follow it with a
DBA-reviewed functional test on disposable test data.

The [privilege review template](sql/oracle-prerequisites.template.sql) lists what
the original Oracle 19c deployment used. **It does not grant anything.**
Broad privileges such as `FLASHBACK ANY TABLE` and `ANALYZE ANY` are not an
automatic least-privilege recommendation. Existing/owned-object/per-table
privileges may suffice for historical table reads; the monitoring flush has a
separate requirement below. A DBA must decide and test the actual runtime operations.
Do not expose SYSDBA credentials to an AI bot or put passwords in command arguments.

### FLASHBACK and SNAPSHOT privilege checklist

Both collector paths below require qualification against the **Oracle 19c** baseline.
SNAPSHOT is this module's endpoint-comparison name, not a separate database
snapshot product or a way to avoid Oracle Flashback privileges and undo.

| Operation | Privilege/access to review in the actual Windchill schema and PDB |
|---|---|
| FLASHBACK: Flashback Version Query | For objects not owned by the schema, Oracle documents `FLASHBACK` plus `READ` or `SELECT` on the relevant objects; owned-object access and any existing grants must be verified. Do not automatically grant `FLASHBACK ANY TABLE`. |
| SNAPSHOT: two `SELECT ... AS OF SCN` reads | The same Flashback Query object access and usable undo are required at both endpoints. Ordinary current-row SELECT access alone does not qualify historical reads. |
| SCN acquisition | Ability to execute `SYS.DBMS_FLASHBACK.GET_SYSTEM_CHANGE_NUMBER`; review `EXECUTE` on `SYS.DBMS_FLASHBACK`. |
| Monitoring flush | The implemented `DBMS_STATS.FLUSH_DATABASE_MONITORING_INFO` call requires `ANALYZE ANY` according to the Oracle 19c security model. Per-table Flashback grants do not replace that requirement. Obtain separate DBA approval; the installer must not grant it. |
| Monitoring and diagnostics | Read `USER_TAB_MODIFICATIONS` and the approved `V_$DATABASE`, `V_$PARAMETER`, `V_$UNDOSTAT` views used by qualification/diagnostics. A grant in the CDB root does not prove access in the application's PDB. |
| DB Ninja persistence | Reviewed creation of four module tables/indexes on first install, and the normal schema rights to maintain/lock those module records. No SYSDBA runtime login. |

Oracle references: [Flashback Query/Version Query privileges](https://docs.oracle.com/en/database/oracle/oracle-database/19/adfns/flashback.html#GUID-BA59D897-C98B-4418-8CAE-42C35AC5B08C)
and [monitoring flush security model](https://docs.oracle.com/en/database/oracle/oracle-database/19/arpls/DBMS_STATS.html#GUID-CA79C291-B7B4-4B35-8507-454366D83A03).
The read-only preflight SQL is **not** a successful test of either collector.
A DBA-approved disposable-data scenario must demonstrate both the Version Query
and the two AS OF reads at reviewed SCN bounds, their returned values, and
failure handling when history is unavailable.

There is no prescribed UNDO_RETENTION number. A setting is not a guarantee of
available history, and the observed ORA-01555 problem is not proven fixed.
Do not automatically enable retention guarantee, ARCHIVELOG, supplemental logging,
database-wide tracing or change Oracle parameters for this tool.

### Schema and encoding

The active path needs four model tables: `DBCAPTURESESSION`, `DBCAPTURECHANGE`,
`DBCAPTUREATTRDELTA`, `DBCAPTURETABLECHANGE`, plus target-generated indexes.
The seven persisted model/association ClassInfo files must match the JAR.
Legacy installations may also contain `DBCAPTURESQLEVENTS`; leave it and its
data alone. The disabled heuristic correlator is not part of the installation.

Generate DDL **on the target**, using its `wt.db.maxBytesPerChar` and tablespace
configuration. The usual Oracle directories are `db/sql` (1), `db/sql2` (2)
and `db/sql3` (3). Inspect the generator output; do not infer widths from this
repository. `VARCHAR2(1200)` from one environment is not a portable schema contract.

Fresh-create SQL is only for an empty DB Ninja schema. Existing or partially
created tables require DBA review/migration. Never drop existing capture history
to make an installation script succeed.

## Linux and Windows

**Linux x64 is the only shipped binary platform. Windows runtime is blocked as
shipped, not merely untested.** The private
evidence store requires POSIX permissions and the `unix:nlink` attribute and has
no NTFS ACL fallback. This conclusion follows from source and JDK filesystem
contracts; it is not a Windows execution result or Windows certification.
Linux also requires a suitable filesystem; do not assume an arbitrary network
share supplies the necessary privacy and hard-link checks.

Node.js **22 or newer** is a build/deployment/test prerequisite, not a Windchill
runtime dependency. No external npm packages are required. Use the PTC-supported
JDK already selected for the target. The DBA procedure needs the site's approved Oracle client. Git is required for
index/history publication review, not for running Windchill.

The [evidence store](customization/DbCapture/main/src/com/ptc/dbcapture/diagnostics/SessionEvidenceStore.java)
is opened even when tracing is unavailable. A filesystem failure is not safely
limited to loss of optional SQL evidence. Do not bypass the privacy checks.
A Windows port would require reviewed filesystem/ACL behavior and native
end-to-end qualification; portable-looking Node commands do not supply either.

## Topology and authorization

Only site administrators can inspect captures or mutate them. All authenticated
users can receive a minimal running-state banner. Verify a non-administrator
account as well as an administrator after installing.

The persistent lock enforces one active capture, but SQL/call-tree collection is
**local to the starting MethodServer** and is not a cluster-wide recorder.
Deploy consistent files/configuration to all application nodes using the site's
normal process. Multiple-node, failover and cross-node Stop behavior require
separate qualification. Do not describe a single-node acceptance test as cluster certification.
