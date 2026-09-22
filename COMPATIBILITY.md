# Compatibility and prerequisites

This is a **qualification matrix**, not a vendor support matrix. PTC's support
policy for the underlying Windchill/OS/JDK/Oracle combination still applies.
Do not install a newer JDK or database release merely because a row below mentions it.

The qualified Windows 12.1 release is `v0.2.0-wc121-win1`. A Windows 13.0.2
port is a separate target exercise; follow [WINDOWS-PORTING.md](WINDOWS-PORTING.md)
and do not reuse the 12.1 JAR, ClassInfo files, manifest or generated DDL.

**Development and test environments only. Do not install or run DB Ninja in
production**, including a technically compatible stack. An unknown environment
classification must be resolved with the owner before installation or capture.

## Windchill, Java and deployment layout

| Environment | Evidence / requirement |
|---|---|
| Windchill Services12.1.2.23 build38 (12.1.2.0 CPS23), Corretto 11.0.19, javax Servlet, Oracle 19c, Windows x64 | Exact first-binary baseline. See [qualification evidence](LOCAL-INSTALL.md); historical icon acceptance is not new source/binary acceptance. |
| Windchill 13.1.4, Java 21, Oracle 19.3 | Original implementation provenance. This publication revision has not been requalified there. Rebuild with that installation's supported JDK and SDK. |
| Windchill 13.0.2 on Windows | Not qualified by this release. Use a separate target-build branch and release; verify the target's JDK, Servlet namespace, CCD metadata, XCONF DTD, Oracle profile and runtime behavior. |
| Other 13.x versions/CPS levels | Conditional candidates, not verified. Rebuild JAR/ClassInfo, compile all JSPs, test profiler integration and run browser acceptance. |
| Other Windchill 12.x or 13.x/CPS levels | Not qualified by this port. Rebuild JAR/ClassInfo, compile all JSPs, test profiler integration and run browser acceptance. |
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
`prebuilt/metadata/com/custom/dbcapture/*.ClassInfo.ser` files and
`prebuilt/manifest.json` recording checksums, source fingerprints and target
version/SDK fingerprints. It contains no PTC/Oracle libraries or generated PTC
JavaScript bundles. [Qualified custom schema DDL](sql/oracle/README.md) is included
separately; it is not portable pre-generated SQL for arbitrary sites. A different
schema profile requires target generation and DBA review, even with a prebuilt JAR.

The publication build compiles all current runtime Java with the target SDK
and `--release 11 -proc:none`, retaining only fingerprint-locked custom generated
entries and ClassInfo from the previous verified target CCD generation.
`deployment/generated-model-baseline.json` records the source, generated-entry,
metadata and SDK fingerprints. This is current-source compilation with unchanged
generated model artifacts, **not a new CCD or annotation-processing run**, and
does not write live metadata. The four model Java definitions and `.rbInfo`
source are unchanged. A changed model or CPS requires target CCD and a reviewed
new baseline; ordinary implementation recompilation cannot qualify stale
generated metadata. See [the assembly contract](HANDOFF.md#current-source-prebuilt-assembly).

Preserve the existing
`com.custom.dbcapture` persistent identities and serialized DTO compatibility;
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
Use [the AI/DBA database runbook](DATABASE-SETUP.md) for the explicit approval,
grant application, schema-owner reconnection and post-DDL verification sequence.

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

The [bundled first-install DDL](sql/oracle/README.md) is restricted to the
unchanged Windchill 12.1.2.23 generated-model baseline, Oracle 19c,
`wt.db.maxBytesPerChar=3`, explicit `VARCHAR2(n BYTE)`, the approved schema-default
data tablespace and **INDX** for all primary-key/secondary indexes.
Run `node tools/schema-package.mjs verify --target` for the prebuilt target's
SDK/datecode and declared/propagated width. It does not establish Oracle
connectivity, version, schema/PDB, tablespace capacity or quotas; the DBA does.

For a different model/release, byte-width or tablespace profile, generate DDL
**on the target** using its actual configuration. The usual Oracle directories
are `db/sql` (1), `db/sql2` (2) and `db/sql3` (3). Inspect the generator output;
do not infer the profile from directory existence or scale widths by a ratio.
PTC-generated widths may be capped at 4000 bytes.

Fresh-create SQL is only for a Windchill schema with no existing DB Ninja objects.
It supplies four primary keys and fourteen secondary indexes (18 indexes
including the PK backing indexes). Existing or partially
created objects require DBA review/migration. Never drop existing capture history
to make an installation script succeed.

## Windows filesystem

**Windows x64 on a local NTFS volume is the qualified platform for this port.**
The private evidence store removes inherited write access, restricts writes to
the Windchill process owner and approved Windows system/administrator identities,
rejects symbolic links and reparse-point path changes, and checks stable file
identity around opens. Network shares and non-NTFS filesystems are rejected.

Node.js **22 or newer** is a build/deployment/test prerequisite, not a Windchill
runtime dependency. No external npm packages are required. Use the PTC-supported
JDK already selected for the target. The DBA procedure needs the site's approved Oracle client. Git is required for
index/history publication review, not for running Windchill.

The [evidence store](customization/DbCapture/main/src/com/custom/dbcapture/diagnostics/SessionEvidenceStore.java)
is opened even when tracing is unavailable. A filesystem failure is not safely
limited to loss of optional SQL evidence. Do not bypass the privacy checks.
The NTFS policy and locked-file deletion path have native Java 11 regression
coverage. This does not qualify another service account, filesystem or cluster.

## Topology and authorization

Only site administrators can inspect captures or mutate them. All authenticated
users can receive a minimal running-state banner. Verify a non-administrator
account as well as an administrator after installing.

The persistent lock enforces one active capture, but SQL/call-tree collection is
**local to the starting MethodServer** and is not a cluster-wide recorder.
Deploy consistent files/configuration to all application nodes using the site's
normal process. Multiple-node, failover and cross-node Stop behavior require
separate qualification. Do not describe a single-node acceptance test as cluster certification.
