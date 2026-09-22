# Agent deployment contract

Repository: <https://github.com/milesplay/WindchillDbNinja>.
The URL supplies the package and instructions, not server credentials, DBA
approval or maintenance authorization. Given access to an authorized target:

1. Read [README.md](README.md), [COMPATIBILITY.md](COMPATIBILITY.md),
   [INSTALL.md](INSTALL.md), [DATABASE-SETUP.md](DATABASE-SETUP.md),
   [CUSTOMIZATION.md](CUSTOMIZATION.md) and
   [OPERATIONS.md](OPERATIONS.md). Use [USE-CASES.md](USE-CASES.md) to understand
   the intended QML/customization/troubleshooting workflows. Treat [LOCAL-INSTALL.md](LOCAL-INSTALL.md)
   as evidence about one environment, not a target configuration.
  For Windows porting or a new Windchill release, read
  [WINDOWS-PORTING.md](WINDOWS-PORTING.md) before selecting or rebuilding a
  binary.
2. Discover and verify the actual Windchill home, release/CPS, deployment layout,
   owner account, OS, supported JDK, Node version, database vendor/container/schema,
   application nodes, environment classification and maintenance procedure.
   Do not guess them. Development and test environments only: do not install or
   run DB Ninja in production.
3. Confirm authorization. Stop for production or unknown environment
  classification, non-Windows/non-NTFS evidence storage, non-Oracle,
  non-`javax.servlet`, managed-cloud
   restrictions, `codebase.war`, service-slot conflicts, unreviewed DB privileges,
   active captures or unavailable maintenance approval. Never work around permissions.
4. Run the read-only filesystem preflight and the separately approved database
   checks. Follow [the database runbook](DATABASE-SETUP.md): inventory existing
   Windchill-schema privileges/quotas, prepare only missing approved GRANTs in a
   private script, and obtain explicit DBA approval for each change. Execute with
   an authorized grantor connection only if specifically permitted; otherwise
   hand it to the DBA. Reconnect as Windchill to verify both historical-query
   paths and the separately approved monitoring flush.
   Keep credentials in the site's existing credential mechanism.
   Do not send licensed vendor source, private configuration, logs, SQL evidence
   or credentials to third-party services.
5. Before a target CCD build, installation write or restart, obtain the necessary
   maintenance approval and preserve backups. CCD can generate live ClassInfo:
   the target-build command snapshots/restores those files. Prefer a staging
   clone for a new SDK/CPS. Prebuilt assembly is the separate private-output
   workflow described below, not another CCD run.
6. Use the target build by default, or explicitly select the prebuilt package
   only after package/target verification. Run the offline suite against the
   selected candidate. Generate a plan, inspect shared XML diffs and file hashes,
   and apply only that plan with the documented tools.
   A stale plan must be regenerated, never forced past a drift check.
   Stop on unresolved collector/deployment/publication failures; do not omit
   tests, treat TODO assertions or skips as passes, or force an apply/rollback.
7. Fresh installation: discover [the bundled CREATE DDL](sql/oracle/README.md),
   verify its model/byte-width/tablespace profile, and obtain DBA approval.
   Use the bundle only for the exact qualified profile; otherwise generate and
   review create-only DDL with the target's PTC tools. After specific DDL approval,
   execute the combined script as the actual Windchill owner through the approved
   script client, or hand it to the DBA. Verify four tables, four primary keys
   and eighteen total indexes before restart; report grant and DDL outcomes
   separately. The public privilege template itself makes no changes.
   Existing installation: preserve captures, scope settings, private evidence and
   schema. Do not run reset/reinstall SQL or revive archived log/Utilities overlays.
8. Restart only the approved services/nodes, after confirming no capture is
   running. The deployment tool never performs a restart or a database mutation.
9. Verify disk hashes **and** the restarted application: original header height,
   administrator/ordinary-user controls, Cancel without mutation, DB Ninja search,
   diagnostics, CSV and a separately authorized disposable capture. Do not report
   skipped, simulated or historical scenarios as newly passed live tests.
10. Record the version, tested commands, scope, results, skipped checks and rollback
    location privately. Publish only a sanitized summary. The owner has approved
    custom source plus custom JAR/ClassInfo under MIT; use the narrow publication
    allowlist and review the Git index/history and binary provenance. Approval
    does not make a candidate tested, installed or published.

## Commands

Use Windows x64 and a local NTFS filesystem with the module's verified ACL policy.
The exact first-binary baseline is Windchill Services12.1.2.23 build38
(12.1.2.0 CPS23), Corretto 11.0.19, Oracle 19c and traditional `codebase`.
Other CPS/SDK/filesystem combinations require rebuild and qualification.

After setting the reviewed target environment, read-only preflight comes first:

```text
node tools/dbninja.mjs preflight
```

Default target-build route, with prior maintenance approval for the build:

```text
node tools/dbninja.mjs build
node tools/validate.mjs all
node tools/dbninja.mjs plan
```

Optional prebuilt route:

```text
node tools/prebuilt.mjs verify
node tools/validate.mjs all --prebuilt
node tools/dbninja.mjs plan --prebuilt
```

The package is `prebuilt/DbCapture.jar`, exactly seven
`prebuilt/metadata/com/custom/dbcapture/*.ClassInfo.ser` files and
`prebuilt/manifest.json` with checksums, source fingerprints and target
version/SDK fingerprints. Do not override a mismatch or silently use an
installed JAR.

The prebuilt assembly compiles all current runtime Java using the target SDK
with `--release 11 -proc:none` into a fresh module-only JAR, not a stale class
overlay. It retains only fingerprint-locked custom generated entries and
matching ClassInfo from the prior verified CCD generation, recorded in
`deployment/generated-model-baseline.json`. This is current-source compilation
with unchanged generated model artifacts, **not a new CCD or
annotation-processing run**; there are no live metadata writes. A changed model
or CPS requires target CCD and a reviewed new baseline. See
[HANDOFF.md](HANDOFF.md#current-source-prebuilt-assembly) for the retained entries.

Then, only with maintenance and Oracle approval:

```text
node tools/dbninja.mjs apply <reviewed-plan.json>
node tools/dbninja.mjs verify <reviewed-plan.json>
```

Fresh schema DDL is [included](sql/oracle/create-db-ninja.sql) for Oracle 19c,
the unchanged Windchill 12.1.2.23 model, `wt.db.maxBytesPerChar=3`, explicit BYTE
widths and INDX. Run `node tools/schema-package.mjs verify` and, on the exact
prebuilt target, `node tools/schema-package.mjs verify --target`. Different
profiles require target generation, not an edited manifest to bypass checks.
The DBA separately reviews and executes the combined script; it is not an
upgrade and must not run against existing or partial DB Ninja objects.
There is no automatic grant or automatic restart.

Use a tagged clone or the full source/install ZIP plus SHA256SUMS from
[Releases](https://github.com/milesplay/WindchillDbNinja/releases).
Verify the archive checksum before extraction and keep all tracked source,
metadata, schema and tools: downloading only the JAR is not a valid handoff.

`plan --reuse-installed` is only a packaging migration for a previously verified,
unchanged JAR/ClassInfo on the same target. It is not a substitute for rebuilding
after an SDK/CPS change.

`plan --javascript-only` is a narrower Header/CSV/menu-icon update for an already migrated
installation. It verifies unchanged non-JavaScript prerequisites, applies three
controller sources plus their fragment via PTC tools and requires a browser hard reload rather than another
server restart. Never use it to conceal unapplied Java/JSP/configuration changes.

## Invariants

- No edits to generated `wt.properties`, `presentation.properties` or
  `service.properties` as the source of configuration.
- No standard JSP/HTML/JavaScript/CSS/Log4j replacement.
- No root `site.xconf` / root `declarations.xconf` or generated PTC bundles in `wtSafeArea/siteMod`.
- No broad CCD deploy of this standalone package over another customization root.
- No changes to unrelated shared XML nodes or presentation registrations.
- No Java package/serialized DTO/persistent model rename without a data-migration design.
- No automatic grants, schema reset, Oracle parameter change, sample business-data
  mutation, capture deletion or restart.
- Strict endpoint NET: insert then delete is net-zero; `A -> B -> delete`
  retains old value `A`. Test the chosen contract; never weaken expectations to
  hide a source/binary defect.
- No full audit, backup/restore, cluster-wide native-evidence or unrestricted
  LOB/cap/undo guarantee. Protect evidence; native collection is single-node.
- No PTC/Oracle libraries, generated PTC JavaScript bundles, private build output
  or operational evidence in the public package.
- No claim that SafeArea prevents all future compatibility problems or that this
  customization is PTC-certified.
