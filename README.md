# DB Ninja for Windchill

[Repository](https://github.com/milesplay/WindchillDbNinja) | [Releases](https://github.com/milesplay/WindchillDbNinja/releases) | [Use cases](USE-CASES.md) | [Compatibility](COMPATIBILITY.md) | [Installation](INSTALL.md) | [Windows porting](WINDOWS-PORTING.md) | [Oracle DDL](sql/oracle/README.md)

**See the database changes behind a Windchill operation.**

DB Ninja is an **Oracle-specific, site-administrator diagnostic customization**.
Start a short capture with **Ninja Trick**, perform the operation being investigated,
then stop with **Ninja Stealth**. DB Ninja opens the saved database changes and
bounded SQL/call-tree evidence. It is not a backup, restore tool or complete audit log.

**Development and test environments only. Do not install or run DB Ninja in
production.** Confirm the target's classification with its owner before any
installation or capture; an unknown classification is not approval.

This is custom software, **not a PTC product or a statement of PTC certification**.
Deployment uses the installed PTC customization tools and additive extension points.
The MIT distribution covers custom source and the explicitly reviewed custom
JAR/ClassInfo package. No PTC/Oracle libraries, original PTC JSPs, generated PTC
JavaScript bundles, database dumps or private case records are included.

## Download and schema

This release line is the **0.2.0-wc121-win1 Windows 12.1 development/test port** derived from
the [upstream v0.1.1 source](https://github.com/milesplay/WindchillDbNinja/releases/tag/v0.1.1).
Use this target-qualified checkout and its regenerated manifest; do not reuse the
upstream Linux JAR/ClassInfo. Keep it in a private directory outside the web root
and follow [INSTALL.md](INSTALL.md). For a Windows 13.0.2 target, follow
[WINDOWS-PORTING.md](WINDOWS-PORTING.md) and create a separate release line.
No Maven/npm/container package is needed.

The DB Capture table DDL is now included:
[guarded first-install SQL](sql/oracle/create-db-ninja.sql),
[individual table/index scripts and conditions](sql/oracle/README.md), and
[schema checksums/profile](sql/oracle/schema-profile.json).
It creates **4 tables, 4 primary keys and 14 secondary indexes** for the qualified
Oracle 19c / Windchill 12.1.2.23 / `wt.db.maxBytesPerChar=3` / INDX profile.
It is not universal Oracle SQL and must not be run on an existing installation.
See [the changelog](CHANGELOG.md) for the port. Runtime binary and generated
metadata were rebuilt with the Windchill 12.1.2.23 SDK.

## Why DB Ninja?

When designing a Windchill customization or a Query Builder / QML report, it is
often difficult to connect an operation in the UI with the tables, entries and
reference values involved. DB Ninja's original purpose is to make that
investigation more concrete:

**Perform one operation -> inspect changed rows and columns -> investigate
relationships -> validate your QML or customization design.**

- **QML development:** find candidate tables/reference fields, understand which
  entries change, and test report filters and JOIN hypotheses against known objects.
- **Customization development:** observe standard behavior before extending it,
  then check whether the extension changes the expected entries and related data.
- **Troubleshooting:** narrow down where an unexpected update, missing result or
  relationship change may originate, with saved differences and eligible native
  SQL/call-tree evidence.
- **Regression review and learning:** capture comparable scenarios before/after a
  change, export visible results, and teach how operations affect persisted data.

This is **evidence-assisted investigation**, not automatic relationship discovery:
two tables changing together does not prove a JOIN or a causal relationship.
DB Ninja does not generate QML, ER diagrams, fixes or executable rollback SQL.
See [worked investigation recipes and additional uses](USE-CASES.md).

## For an AI bot given only this repository URL

Read [AGENTS.md](AGENTS.md), then [COMPATIBILITY.md](COMPATIBILITY.md),
[WINDOWS-PORTING.md](WINDOWS-PORTING.md) and [INSTALL.md](INSTALL.md), including [database setup](DATABASE-SETUP.md) for
approved privilege grants, DDL creation and post-change checks.
Inspect the target; do not infer its OS, Java version,
database, credentials or maintenance permission from this repository.
The qualified binary is **Windows x64 only**, with a local NTFS evidence directory
protected by owner/system-administrator ACL checks and link/reparse-point rejection.
Node.js 22 or newer and the target's licensed PTC SDK/supported JDK are required
for the deployment and qualification tools. No npm dependencies are needed.

The repository URL is sufficient to find the package and instructions, not
server credentials, DBA approval or maintenance authorization. Stop for missing
authorization, conflicting configuration, unavailable PTC tools or unverified
prerequisites. A repository URL does not grant access to a server or database.

**Publication handoff:** the owner has authorized custom source and custom
compiled artifacts under [MIT](LICENSE). The release contract is
`prebuilt/DbCapture.jar`, exactly seven
`prebuilt/metadata/com/custom/dbcapture/*.ClassInfo.ser` files, and
`prebuilt/manifest.json` with checksums, source fingerprints and target version/SDK
fingerprints. The [qualification summary](LOCAL-INSTALL.md) records successful current-source
and selected-binary checks. This is not a claim that the new collector fixes
have been deployed to a running server or qualified under production load.

Prebuilt assembly compiles all current runtime Java into a fresh module-only JAR
and retains only
fingerprint-locked custom generated entries and matching ClassInfo from the
prior verified target CCD generation. This is **current-source compilation with
unchanged generated model artifacts, not a new CCD or annotation-processing
run** and performs no live metadata writes. A changed model or CPS requires
target CCD and a reviewed new baseline. See [binary provenance](HANDOFF.md#current-source-prebuilt-assembly).

The optional `plan --prebuilt` route uses the qualified candidate; the default
`plan` uses the target build. See [publication gates](PUBLICATION.md#release-gates)
and the [qualification summary](LOCAL-INSTALL.md). Previously verified icon
deployment is separate from the new source/binary fixes and their offline
release evidence. Never omit failing checks or count skips as acceptance.

## Compatibility in one minute

| Target | Status |
|---|---|
| Windchill Services12.1.2.23 build38 (12.1.2.0 CPS23), Corretto 11.0.19, Oracle 19c, Windows x64, traditional `codebase` | Exact qualified `v0.2.0-wc121-win1` baseline; package and target fingerprints must match |
| Windchill 13.0.2 on Windows | Future port target; use a separate branch, target CCD build, manifest, DDL profile and qualification. The 12.1 prebuilt package is not compatible. |
| Windchill 13.1.4, Java 21, Oracle 19c | Historical source provenance; qualify this revision on the target |
| Other Windchill 13.x releases / CPS levels | Target rebuild and full qualification required |
| Other Windows/Windchill CPS combinations | Not qualified; rebuild ClassInfo/JAR, compile JSPs and repeat runtime acceptance |
| SQL Server / PostgreSQL / Azure SQL | **Not compatible**; installation settings cannot replace the Oracle collector |
| Other Windchill 12.x/13.x servlet baselines | Not portable by configuration alone; source port and target generation required |
| Windchill+ / managed cloud / `codebase.war` layouts | Not qualified; use the provider-approved deployment process |

See the [complete matrix and Oracle requirements](COMPATIBILITY.md).

## Features

- Incremental Site **DB Ninja** entry and Quick Links **Ninja Trick / Ninja Stealth**.
- Custom 16 x 16 transparent action icons:
  ![Ninja Trick](customization/DbCapture/main/src_web/custom/DbCapture/icons/dbNinjaTrick-v20260921.png)
  Ninja Trick /
  ![Ninja Stealth](customization/DbCapture/main/src_web/custom/DbCapture/icons/dbNinjaStealth-v20260921.png)
  Ninja Stealth. See [icon provenance and deployment](deployment/icons/README.md).
- One server-wide capture; only its starting username can stop it.
- A capture-window Ninja banner that does not enlarge the Windchill header or
  replace its artwork during a temporary status outage.
- Capture history, description editing, saved row/column differences and object inspection.
- Start-time monitoring scope; separate, display-only hidden-table filters.
- CSV export of the current visible/sorted grid and its visible columns.
- Bounded, private native SQL and synchronous call trees for eligible completed
  AJP requests on the node that started the capture.

The decorative banner is branding, not a substitute for authoritative capture
state. All publication entry-point documentation is in English.

## Important operating limits

Start records SCN/counters/scope, **not a copy of every business row**. The agreed
release contract is **strict endpoint NET**: insert then delete is net-zero;
`A -> B -> delete` reports deletion with old value `A`; update then revert is
net-zero. This is not an intermediate-event audit. Corrected source and the
selected binary must both satisfy that contract before release; older deployed
binaries are not evidence of compliance. SNAPSHOT also needs Oracle undo.
ORA-01555/ORA-30052 and missing history remain explicit limitations.

The 5,000-per-table setting limits accepted changed IDs/net changes, **not total
database scans, query duration or application memory**. A large table may require
two large endpoint reads. Diagnostic limits and browser timeouts are separate.
The tool writes/deletes its own records and evidence, locks its session table,
and flushes Oracle monitoring statistics. It is not wholly read-only.

Read [OPERATIONS.md](OPERATIONS.md) before use. Even a development/test copy of a
large database can exceed the tool's practical workload limits.

## Installation entry point

For a bot or developer starting with only this repository link:

```sh
git clone https://github.com/milesplay/WindchillDbNinja.git
cd WindchillDbNinja
```

After setting `WT_HOME` and `JAVA_HOME` to the reviewed Windows target:

```text
node tools/dbninja.mjs preflight
node tools/prebuilt.mjs verify
```

Preflight is read-only; package/target verification does not prove Oracle
privileges, usable undo, runtime acceptance or authorization. Follow
[INSTALL.md](INSTALL.md) to choose the target-build or prebuilt route, validate,
review the plan and obtain maintenance/Oracle approval before apply.
Fresh schema SQL is [included for the qualified profile](sql/oracle/README.md);
different profiles require target generation. The DBA reviews and executes it
separately. There are no automatic restarts or grants.

## CPS/update preservation

- Target-built custom files are retained in `wtSafeArea/siteMod`.
- Installation uses the target's `bin/swmaint.xml`, scoped to the reviewed plan.
- Global JavaScript is combined from the custom `dbNinja.jsfrag` using PTC's
  `bin/jsfrag_combine.xml`; generated PTC bundles are never stored in SafeArea.
- CSS and configuration are registered with **xconfmanager**, never by editing
  generated properties as the source of truth.
- Original PTC JSPs, standard action models and Log4j configuration are not replaced.

SafeArea does **not** make a customization automatically compatible with every
future CPS. Review `ptcCurrent`, merge shared changes, rebuild and revalidate
after updates. See [CUSTOMIZATION.md](CUSTOMIZATION.md).

## Documentation

| Document | Purpose |
|---|---|
| [INSTALL.md](INSTALL.md) | Windows target-build/prebuilt routes, first install versus update, rollback |
| [WINDOWS-MANUAL-INSTALL.md](WINDOWS-MANUAL-INSTALL.md) | Beginner walk-through: browser download, CCD compile in the Windchill shell, manual deployment and verification with Command Prompt and SQL*Plus |
| [WINDOWS-PORTING.md](WINDOWS-PORTING.md) | Versioned Windows porting, 13.0.2 workflow, failure modes and bot checklist |
| [DATABASE-SETUP.md](DATABASE-SETUP.md) | AI/DBA workflow for schema grants, quotas, CREATE DDL and verification |
| [Oracle schema](sql/oracle/README.md) | Bundled table/index DDL, exact profile, integrity and DBA execution checks |
| [CHANGELOG.md](CHANGELOG.md) | Versioned changes and release/download scope |
| [COMPATIBILITY.md](COMPATIBILITY.md) | Windchill, JDK, OS, Oracle and topology requirements |
| [CUSTOMIZATION.md](CUSTOMIZATION.md) | PTC guidance, deployment mapping and CPS maintenance |
| [OPERATIONS.md](OPERATIONS.md) | Capture semantics, safe usage and workload/evidence limitations |
| [USE-CASES.md](USE-CASES.md) | QML, customization, troubleshooting and further developer workflows |
| [HANDOFF.md](HANDOFF.md) | Architecture, regression commands and compatibility traps |
| [LOCAL-INSTALL.md](LOCAL-INSTALL.md) | Offline/package qualification versus prior live icon deployment |
| [PUBLICATION.md](PUBLICATION.md) | Approved custom binary/source/schema packaging and release gates |
| [THIRD-PARTY-NOTICES.md](THIRD-PARTY-NOTICES.md) | Icon provenance and separately licensed vendor prerequisites |

Do not commit the working directory wholesale. Run
`node tools/check-publication.mjs --require-prebuilt --export` after reviewing
[PUBLICATION.md](PUBLICATION.md). A source-only working-tree check is not the
binary release gate.
MIT does not grant rights to PTC or Oracle software or imply their endorsement.
