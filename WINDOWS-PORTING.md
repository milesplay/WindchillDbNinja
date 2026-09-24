# Windows Porting and Release Guide

This document explains how to maintain the Windows port without confusing it
with the upstream Linux release. It is written for a developer or AI bot that
has the repository but does not have access to the original target.

## Version lines

- `v0.1.1` is the upstream baseline. Do not add Windows artifacts to that tag.
- `v0.2.0-wc121-win1` is the qualified Windows 12.1 development/test port for
  Windchill Services 12.1.2.23 build38, Java 11, `javax.servlet`, Oracle 19c,
  Windows x64 and the traditional `codebase` layout.
- `port/windows-wc1302` is the active `0.3.0-wc1302-win1` source port candidate
  for Windchill 13.0.2.6 build 33, Information Modeler 13.0.2.0 build 396,
  Java 17 and Jakarta Servlet. A private target CCD JAR/ClassInfo and sql3 DDL
  candidate exist. Approved grants, target DDL and deployment were applied on
  one authorized dev/test target; final runtime qualification remains pending.

Keep the 12.1 prebuilt files, manifest and DDL as the historical 0.2.0 baseline.
The 13.0.2 line now has a separate private target-built JAR, ClassInfo files,
manifest/model baseline and schema profile. It still requires DBA/runtime
qualification before release. Never edit fingerprints or use old artifacts to
bypass a mismatch.

## Active Port Status

The target software profile is verified as Windows x64/local NTFS, Windchill
13.0.2.6 build 33 with no patches, Information Modeler 13.0.2.0 build 396,
Corretto 17.0.11.9.1 and Jakarta Servlet. Read-only preflight, target XCONF DTD
validation, profiler/API checks and the complete offline suite pass. A target
CCD JAR and seven matching ClassInfo files were generated privately. PTC
`sql_script` generated eight target `sql3` inputs; a create-only candidate uses
4 tables, 4 primary keys, 14 secondary indexes and explicit BYTE semantics.
The private model baseline/manifest passed SDK/datecode verification, and a
hash-bound 28-file deployment plan was applied from the restricted backup ACL.
The approved `SELECT ON SYS.V_$DATABASE` and `ANALYZE ANY` grants and target
CREATE DDL were applied; schema postchecks passed. A later Wex XML-only menu
repair was also applied and the user reports the menus fixed. An owner restart
and post-restart acceptance after that repair are not recorded. End-to-end
capture, monitoring flush and multi-node/failover acceptance remain pending.

The schema owner and PDB were cross-checked against installed DB settings;
`CREATE TABLE` and an existing `UNLIMITED TABLESPACE` privilege were observed.
The SCN package probe returned ORA-00904 before fallback/undo checks. A later
DBA transcript ran as SYSDBA, so it cannot prove MANAGER's effective grants or
schema inventory. The approved grants `SELECT ON SYS.V_$DATABASE` and
`ANALYZE ANY` have since been applied. MANAGER's SCN fallback works; V$PARAMETER
and V$UNDOSTAT remain unavailable, while `DBMS_STATS` execution is PUBLIC.
Owner-session NLS semantics are BYTE. The target DDL postchecks verified four
tables, four validated primary keys, 18 valid normal indexes plus three valid
LOB indexes, and 45 BYTE VARCHAR2 columns. No monitoring flush or real capture
has been tested. Active Oracle RU, workload undo, index capacity and
application-node topology remain incomplete.

The first approved CCD attempt failed before Information Modeler was registered;
the later target build succeeded. All 24 saved ClassInfo/configuration paths
matched after the prior failed attempt. After the successful build, the owner
confirmed that the observed `site.xconf` MethodServer-count change was
intentional; the corresponding propagated `wt.properties` and target-file hints
were retained, not restored over the site change. The CCD build itself did not
apply runtime files or restart services; the later deployment plan was applied
separately. The separate upstream main
DDL profile targets 13.0.2.11; its compiled artifact is Linux/`com.ptc`, so the
Windows `com.custom` candidate uses the newly generated target outputs instead.

An offline diff found identical CREATE table/index definitions in the 12.1
Windows and upstream 13.0.2.11 scripts, with model-package comments differing.
The actual Windows target DDL now comes from the installed 13.0.2.6 SDK. Its
45 unqualified `VARCHAR2(n)` widths were made explicit as `VARCHAR2(n BYTE)`;
all numeric widths stayed unchanged. The approved target DDL was applied and
the schema postchecks passed. The candidate remains unqualified pending final
runtime and multi-node acceptance.

The package version, `prebuilt/manifest.json`, release tag, ZIP name and
qualification record must agree. A source-only documentation change does not
justify changing the binary version; a changed runtime source requires a fresh
candidate build and focused validation.

## Safety boundary

DB Ninja is for authorized development and test environments only. Do not
install or run DB Ninja in production. The
repository does not provide credentials, DBA approval, maintenance approval or
PTC/Oracle libraries. Never publish hostnames, account names, logs, captures,
SQL bind values, database dumps, generated site configuration or private build
directories.

Read [AGENTS.md](AGENTS.md), [COMPATIBILITY.md](COMPATIBILITY.md),
[INSTALL.md](INSTALL.md), [DATABASE-SETUP.md](DATABASE-SETUP.md) and
[PUBLICATION.md](PUBLICATION.md) before changing a target.

## Porting a new Windows target

### 1. Create an isolated branch

Start from the nearest reviewed source tag and preserve the existing release:

```text
git clone https://github.com/milesplay/WindchillDbNinja.git
cd WindchillDbNinja
git switch -c port/windows-wc1302 v0.2.0-wc121-win1
```

Use a new package version such as `0.3.0-wc1302-win1` only after deciding the
target release/CPS and the compatibility impact. Do not commit a 13.0.2
artifact under the 12.1 manifest.

### 2. Inspect the actual target

Do not infer the Windows port from the operating system alone. Record, privately
first, the exact Windchill release/CPS, supported JDK, Servlet namespace, Oracle
version/PDB/schema, filesystem type, service account, deployment layout and
application-node topology. Confirm that the target is not production and that
the owner approved the maintenance window.

From the repository directory, use the target's supported tools:

```text
$env:WT_HOME = 'D:\ptc\Windchill'
$env:JAVA_HOME = 'C:\Program Files\Java\jdk-17'
node tools\dbninja.mjs preflight
node tools\schema-package.mjs verify
```

Preflight is read-only. It does not prove Oracle privileges, usable undo,
database compatibility or authorization. Run the read-only Oracle checks from
[DATABASE-SETUP.md](DATABASE-SETUP.md) through the site's approved client.

### 3. Choose the build route

The 12.1 prebuilt package is only for the exact 12.1/Java 11 target recorded in
its manifest. A Windows 13.0.2 target must use the target-build route unless a
new reviewed prebuilt baseline has already been created for that target.

For a new Windchill release:

1. Build with the installed target SDK and CCD/customization tools.
2. Before CCD writes, checksum-back up both known generated `ClassInfo` locations
  and site, declarations, generated properties and relevant customization XCONF
  files. The target build records the backup hashes and restores the known
  ClassInfo locations in its `finally` path. Unexpected configuration changes
  need site-owner review, not automatic replacement.
3. Compile all runtime Java against the target release and supported JDK.
4. Compile all five JSPs and run the complete validation groups.
5. Generate the target schema DDL from the target model and width/tablespace
   profile. Never reuse the bundled 12.1 DDL for 13.0.2.
6. Create a new `deployment/generated-model-baseline.json` only after comparing
   the generated model inputs, JAR entries and target metadata files. Never edit
   a fingerprint merely to silence a mismatch.

The target build writes to the Windchill installation. Treat compilation as a
deployment-side effect, use the target's maintenance procedure, and preserve
the pre-build metadata backup. The optional `build-prebuilt.mjs` route is a
private-output assembly and is not a substitute for a new CCD model build.

### 4. Resolve the platform seams

The upstream Linux/Java 17/Jakarta implementation is a source reference, not a
drop-in Windows 12.1 or Windows 13.0.2 binary. Resolve these seams from the
actual target SDK:

| Seam | Required decision |
|---|---|
| Java language level | Use the target JDK and `--release`; this 13.0.2 line requires `--release 17`. The historical 12.1 line uses Java 11. |
| Servlet namespace | Preserve the namespace provided by the target (`javax` or `jakarta`). |
| Windchill package identity | Keep the custom persistent identity stable after first installation; a rename is a data migration. |
| Generated model | Generate ClassInfo and model resources with the target CCD; never copy upstream metadata. |
| XCONF DTD | Validate declarations with the installed `xconfmanager`; examples from another release are not proof. |
| Oracle DDL | Generate and review create-only DDL for the actual width, tablespace and model profile. |
| Log4j and profiler APIs | Check installed classes and filters; do not assume the upstream filter ordering. |
| Windows filesystem | Require local NTFS, owner/system/administrator ACLs, no reparse points, and stable file identity checks. |

### 5. Build and validate

After the source and generated-model review:

```text
$env:WT_HOME = 'D:\ptc\Windchill'
$env:JAVA_HOME = 'C:\Program Files\Java\jdk-17'
$env:DBNINJA_MAINTENANCE_APPROVED = 'yes'

node tools\dbninja.mjs build
node tools\validate.mjs all
node tools\schema-package.mjs verify --target
node tools\check-publication.mjs --require-prebuilt --export
```

The target's actual Java version may change these commands only where the
installed toolchain requires it. Keep the full output and exit status private;
publish only sanitized counts and artifact hashes. A skipped group is not a
passing qualification.

### 6. Plan, apply and verify

Review the generated plan and all shared XML merges before applying it:

```text
node tools\dbninja.mjs plan
node tools\dbninja.mjs verify D:\private\plan.json

# Only after prior maintenance and Oracle approval:
$env:DBNINJA_ORACLE_CONFIRMED = 'yes'
node tools\dbninja.mjs apply D:\private\plan.json
node tools\dbninja.mjs verify D:\private\plan.json
```

The deployment tool uses the target's `swmaint.xml`, xconfmanager and
`jsfrag_combine.xml`. It does not restart Windchill or run database SQL. Stop
before apply if the plan detects drift, an active capture, an unexpected stage
file or a changed prerequisite. Restart only the approved services after the
disk verification succeeds.

### 7. Runtime acceptance

Record these checks for the new release, with sanitized evidence:

- ServerManager and MethodServer start without ClassInfo/service-registration
  errors.
- The administrator can start and stop a short empty capture.
- The capture row, private evidence metadata and Oracle row agree.
- The evidence directory has the expected Windows ACL and no reparse path.
- Direct mutation GET requests are rejected; missing and wrong CSRF nonces are
  rejected; ordinary users cannot mutate or inspect captures.
- The header, Quick Links actions, diagnostics, object view and CSV work after a
  hard browser reload.
- Oracle FLASHBACK/SNAPSHOT and strict endpoint NET scenarios are tested only
  with separately approved disposable data.

Do not turn an offline fixture, a prior release, or an administrator-only test
into a claim about ordinary-user authorization, multi-node failover or
production-scale performance.

## Windows port lessons and failure modes

These failures were useful signals during the 12.1 port:

- The upstream Linux JAR/ClassInfo and Java 17/Jakarta sources were not reusable
  as a Windows 12.1 binary. The target SDK and generated metadata had to be
  rebuilt and the runtime namespace moved to the target's custom package.
- Java 11 rejected records, switch expressions, `Stream.toList()`, pattern
  matching and text blocks. Compile with the target `--release` early.
- CRLF in `.rbInfo`/JSON changed model fingerprints. Keep repository text files
  LF-normalized through `.gitattributes`.
- A partial deployment caused `InfoNotFoundException` for a missing association
  ClassInfo file. Deploy the complete matching metadata set, not only the main
  model files.
- Windchill 12.1 rejected an upstream XCONF `AddToProperty targetFile`
  attribute. Validate every XCONF fragment against the installed DTD.
- The installed global Log4j `ReflectionFilter` required narrowly scoped
  coexistence handling. Never replace a customer's global filter blindly.
- Windows NTFS `fileKey()` can be null. The port uses owner/ACL checks, reparse
  rejection and a Windows identity fallback instead of assuming POSIX behavior.
- Replacing a large `metadata.properties` with Windows `ATOMIC_MOVE` could
  raise `AccessDeniedException` during concurrent reads. The writer now
  revalidates the staged/target files and uses a controlled Windows replacement
  fallback; the regression test repeatedly checkpoints large metadata while a
  reader runs.
- Offline filesystem tests did not reproduce the live checkpoint race. The
  final target acceptance therefore included a real empty capture and a
  SELECT-only database-row check.
- Oracle ORA-01555 is a bounded historical-data limitation, not a reason to
  claim complete audit coverage. Preserve warnings and qualify undo separately.

## Publication checklist

Before committing or opening a pull request:

```text
git status --short
git diff --check
node tools\check-publication.mjs --require-prebuilt --export
git diff --cached --stat
git ls-files
```

Review the actual staged bytes. Never stage `build/`, `backups/`, logs, private
evidence, site configuration, PTC/Oracle libraries or credentials. Commit the
source/docs/artifact change on its versioned branch, create the matching tag
only after the release gates pass, and publish a pre-release ZIP plus
`SHA256SUMS` from `git archive`.
