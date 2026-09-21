# Installation, upgrade and rollback

Read [compatibility](COMPATIBILITY.md), [operations](OPERATIONS.md) and
[PTC customization mapping](CUSTOMIZATION.md) first.
This procedure is for an authorized **Linux x64, on-premises, traditional-codebase,
Oracle-backed Windchill** installation. It is not a Windchill+ deployment recipe.

**Development and test environments only. Do not install or run DB Ninja in
production.** Obtain the environment owner's classification before proceeding;
stop if it is production or its classification is unknown. The tools do not
automatically identify a production system, and an approval flag cannot make
production deployment acceptable.

Use Node.js 22 or newer and the target's licensed SDK/supported JDK; no npm
dependencies or downloaded PTC/Oracle libraries are required. **Windows is
unsupported as shipped:** the evidence store requires POSIX permissions and
`unix:nlink`, with no NTFS ACL fallback. There is no Windows certification.
Read [the filesystem requirements](COMPATIBILITY.md#linux-and-windows).
Use the installation owner account, not an arbitrary root or
administrator account that leaves files owned by the wrong service identity.

## 1. Establish the target and approvals

Determine the exact release/CPS, supported JDK, Oracle container/schema, actual
database connection, deployment layout and node/service topology. Confirm:

- The target is an authorized development or test environment, not production.
- Filesystem access, a maintenance window and the restart scope.
- No active capture; use the authenticated state endpoint or the DB Ninja UI.
- Database prerequisites and schema state, approved by the DBA.
- A separate backup of existing captures, private evidence and site settings.
- Whether this is a **fresh install**, an **existing installation update** or a
  **packaging-only migration** of the same verified runtime.

The scripts never restart Windchill or execute SQL. Environment approval flags
record a decision already made; setting a flag is not authorization.
The [repository URL](https://github.com/milesplay/WindchillDbNinja) provides the
package and instructions, not server credentials, DBA approval or maintenance
authorization.

Use a local, access-controlled working directory outside the web root.
Never serve the repository, `build/` or `backups/` over HTTP.
Restrict plans, backups and runtime evidence to the installation owner and
approved administrators.

**Release gate:** known collector and deployment/publication defects require
fixed-source and selected-binary evidence before deployment. The agreed
semantics are strict endpoint NET, not transient-event auditing. The
[qualification summary](LOCAL-INSTALL.md) distinguishes prior icon deployment
from pending full-release validation; it does not certify the new fixes as
deployed. Do not bypass failures with a smaller passing group or an old JAR.

### Read-only preflight

```sh
cd /path/to/db-ninja
export WT_HOME=/path/to/target/Windchill
export JAVA_HOME=/path/to/the-PTC-supported-jdk
node tools/dbninja.mjs preflight
```

Paths are placeholders, not target detection rules. Preflight is read-only and
checks installed PTC tooling, Jakarta Servlet and service slot 905000.
It does not connect to Oracle or authorize an installation. Have a DBA run
[oracle-check.sql](sql/oracle-check.sql) as the actual Windchill schema using the
site's approved interactive authentication mechanism. Do not put passwords in a
command line. Review [the privilege template](sql/oracle-prerequisites.template.sql)
if operations are unavailable; do not automatically grant its broad privileges.

Also inspect the installed XCONF syntax and mappings before modifying settings:

```sh
# Linux; execute from WT_HOME.
./bin/xconfmanager -h
./bin/xconfmanager -d 'netmarkets.presentation.jsFiles,netmarkets.presentation.cssFiles,wt.services.service.905000'
```

Help exit conventions can differ; any nonzero write/propagation/validation
result is a failure. The verified
presentation target is `codebase/presentation.properties`, not `wt.properties`.
If the installed mappings/tools differ, stop and qualify them.
Return to the repository directory before running the remaining Node commands.

## 2. Select and validate the candidate

### Default: build with the target's PTC SDK and CCD

Prefer a staging clone, especially after a CPS/major-version update. On a live
target, coordinate the build with the maintenance window: PTC annotation
processing can write ClassInfo into the installation. The build command backs up
and restores the seven known model files in both known output locations, including
on failure, and retains the new JAR/ClassInfo in the private `build/` directory.
An unknown metadata output location is an error, not permission to use stale files.

```sh
export DBNINJA_MAINTENANCE_APPROVED=yes
node tools/dbninja.mjs build
node tools/validate.mjs all
```

The build runs installed CCD `clean`, `validate.folder.structure` and `compile`
with a per-command customization-root override. It **does not edit**
`customizationTools.properties` and **does not run broad CCD deploy**.

The validation suite includes UI tests, source-first collector contracts,
scope/catalog, presentation, native
profiler API compatibility, serialized DTO compatibility, local smoke, five JSP
translations/compilations and JS/XML syntax checks. It does not write business
data, run an Oracle capture or prove production-scale performance.
Check the exit code. `all` is fail-fast; later groups are not executed after an
earlier failure. A skipped test or a historical result is not new acceptance.

The source tree uses CCD's permitted web paths. Non-CCD deployment templates stay
under the module's `custom/DbCapture/overlay` source folder; only explicitly
selected runtime files are installed, never that folder wholesale.

### Optional: use the exact-baseline prebuilt package

The first binary targets **Windchill Services13.0.2.11 build32
(13.0.2.0 CPS11), Corretto 17.0.12, Oracle 19c, Linux x64 and traditional
`codebase`**. The package contains only:

- `prebuilt/DbCapture.jar`;
- seven matching `prebuilt/metadata/com/ptc/dbcapture/*.ClassInfo.ser` files;
- `prebuilt/manifest.json` with checksums, source fingerprints and target
  version/SDK fingerprints.

The publication candidate is a **current-source compilation with unchanged
generated model artifacts**, not a new CCD or annotation-processing run.
`tools/build-prebuilt.mjs` compiles all current runtime Java against the target
SDK with `--release 17 -proc:none` into private output and assembles a fresh
module-only JAR. It does not overlay stale implementation classes or write live
metadata. Only 15 fingerprint-locked custom generated JAR entries and the seven
matching ClassInfo files are retained from the prior verified target CCD
generation. See [the assembly contract](HANDOFF.md#current-source-prebuilt-assembly)
and `deployment/generated-model-baseline.json`.

The four model Java definitions and `.rbInfo` source are unchanged from that
baseline. A changed model or CPS requires target CCD and a reviewed new baseline,
not reuse of these generated artifacts or a fingerprint override.

With `WT_HOME` and `JAVA_HOME` pointing to the reviewed target:

```text
node tools/prebuilt.mjs verify
node tools/validate.mjs all --prebuilt
```

Verification checks the package/target contract, not Oracle privileges, usable
undo, service state or maintenance permission. Stop on missing artifacts,
checksum/source drift or a target fingerprint mismatch. Do not substitute
another JAR/ClassInfo or bypass the check. Other CPS/SDK combinations require
a target rebuild and full qualification, not a manifest edit to suppress a mismatch.
Prebuilt consumption does not remove the target's licensed tools, first-install
DDL generation, DBA review or restart/acceptance requirements.

## 3. Generate and review a non-mutating deployment plan

The default uses the target-built candidate:

```text
node tools/dbninja.mjs plan
```

Or, after the prebuilt verification and validation above:

```text
node tools/dbninja.mjs plan --prebuilt
```

Copy the exact `PLAN=.../plan.json` path it prints. The plan is private and contains
target-specific paths/settings. It stages files and checksums without modifying
Windchill. Review:

- Every planned destination and the source/build artifact version.
- The merged `customroleaccessprefs.xml`.
- Any migration of shared `custom/xconf/custom.site.xconf` and
  `custom.declarations.xconf`: unrelated elements, references, comments and
  security-label/configurable-link settings must remain.
- Current per-table limits/exclusions and unrelated JS/CSS registrations.
- Service slot ownership and PTC tool fingerprints.

For a **packaging-only migration** of a previously verified installation, use:

```text
node tools/dbninja.mjs plan --reuse-installed
```

That explicitly reuses the installed JAR and seven installed ClassInfo files.
Do not use this shortcut for fresh installs, changed Java/model source or a new SDK/CPS.
For offline tests of such a runtime, set `DBC_JAR` explicitly to its reviewed
installed JAR. There is no silent fallback to a historical binary.

For a **Header/CSV/menu-icon JavaScript-only** update on an already migrated installation:

```text
node tools/dbninja.mjs plan --javascript-only
```

This stages only the three global controller sources and their combined custom
fragment. All other runtime sources must already match, and the plan fingerprints
them plus the root/generated configuration as read-only prerequisites. Apply it
with the same approval/review procedure. It does not propagate/change XCONF,
replace Java/JSP/CSS or require another MethodServer restart; hard-reload and
verify the browser afterward. Do not use it for Java changes, new CSS/JSP
references, fresh installs or an unqualified SDK/CPS.

## 4. Apply only the reviewed plan

Immediately before applying, recheck that no capture is running and that the
approved maintenance window is active. For a fresh install, keep the affected
application services stopped until the new schema is initialized and checked.

```sh
export DBNINJA_MAINTENANCE_APPROVED=yes
export DBNINJA_ORACLE_CONFIRMED=yes
node tools/dbninja.mjs apply /absolute/path/printed-by-plan/plan.json
```

These flags must reflect **prior maintenance and Oracle approval**.
The qualified apply workflow must:

1. Reject target/PTC-tool/source-stage drift and previously attempted plans.
2. Copy the pre-change selected files and generated targets to a checksum-backed,
   private `backups/deployment-*` directory.
3. Use PTC `swmaint.xml createSafeArea`; preserve the canonical files in
   `wtSafeArea/siteMod`; call PTC `installSiteChanges` on the reviewed subset only.
4. Register `custom/xconf/DbNinja.xconf` with the installed xconfmanager wrapper.
5. Preserve existing capture settings, remove only legacy DB Ninja global JS/CSS
   registrations, and add the current CSS via the declared ordered-set property.
6. Propagate XCONF and validate it.
7. Run the installed `jsfrag_combine.xml` targets `combine_jsfrag_files` and
   `compress`. These rebuild PTC's bundles from the current PTC fragments plus the
   custom fragment. Unrelated documentation generation, old-file cleanup and
   base-CSS regeneration are deliberately not part of this focused deployment.
8. Verify live/SafeArea hashes, key settings, unrelated presentation
   registrations and the combined JavaScript.

Unexpected extra stage files are rejected before installation. Existing shared
file/directory permissions are not broadened: shared XML modes are retained;
new web directories and intended browser assets get explicit readable modes.
Confirm actual HTTP access and filesystem privacy during target acceptance.

**An apply success is disk verification, not runtime acceptance.** If any step
fails, inspect the status and backup. Do not rerun the same attempted plan or
claim that the feature is active.

### Remove a legacy deployment kit from the web root

Older CCD/direct-copy installations may still have
`codebase/custom/DbCapture/overlay` containing installer scripts, SQL, XML and
duplicate JSP templates. It is not a runtime endpoint directory in this package.
Inspect it, confirm no site-specific caller depends on it, and move the reviewed
deployment-only files into the private `backups/` tree, recording checksums.
Do not move the active JSPs under `codebase/netmarkets/jsp/dbcapture`.
Do not remove unknown files or blindly prune older versioned browser assets:
cached clients may still need those during a controlled cache-grace period.

The installer does not deploy the toolkit folder. Any reviewed archival step is
separate from the plan's file rollback; preserve its bytes privately and restore
them only if an approved rollback requires them.

## 5. Fresh installation only: generate and initialize the schema

Skip this section for a packaging migration or an existing populated installation.

With the validated target-build or matching prebuilt JAR/ClassInfo placed and
affected services still stopped:

```text
node tools/dbninja.mjs ddl
```

This invokes PTC `tools.xml sql_script` for `com.ptc.dbcapture.*` and only generates
files. Inspect the reported Oracle output directory and the actual
`wt.db.maxBytesPerChar`. Then assemble a create-only script; for example:

```sh
# Linux; sql3 is an example, not a universal choice.
node tools/create-schema.mjs "$WT_HOME/db/sql3/com/ptc/dbcapture" build/create-schema.sql
```

The assembler consumes all four target-generated table and index scripts, refuses
destructive statements and refuses to overwrite an existing output file. Its SQL
checks that **none of the four tables exists before the first CREATE**. It does
not create the obsolete SQL-event table.

Have the DBA review tablespaces, widths, constraints and statements, then execute
the reviewed script as the Windchill schema using the approved Oracle client.
No portable pre-generated SQL is distributed, including in the prebuilt package.
DDL commits implicitly. A mid-script failure requires DBA repair; a file rollback
cannot undo it. Do not reset/drop existing capture data to retry.

## 6. Restart and prove runtime behavior

Use the site's normal, approved service-control procedure for the affected
MethodServers/nodes. Do not guess a PID, kill processes by name or restart other
nodes without approval. Action/XCONF/JAR changes and server-side Jasper caching
can require restart. Clear only the identified DB Ninja JSP cache if required
by that target's normal procedure; never delete the entire Tomcat work tree.

After startup:

```text
node tools/dbninja.mjs verify <the-same-reviewed-plan.json>
```

Then verify the browser with a hard reload/empty cache so new combined bundles
are actually loaded:

1. Authentication is required; ordinary users cannot view/mutate capture data.
2. The authenticated state endpoint returns valid, authoritative state.
3. DB Ninja opens via Site, and the standard Site/Quick Links controls still work.
4. Only Quick Links supplies Ninja Trick/Ninja Stealth; Cancel sends no mutation.
5. Original header height and surrounding controls remain unchanged.
6. Existing captures/search/diagnostics/object details/CSV remain usable.
7. With separate permission, run a short capture around disposable test data and
   check actual changes and warnings in FLASHBACK and SNAPSHOT paths. Verify
   strict endpoint NET: insert then delete is net-zero, and `A -> B -> delete`
   reports old value `A`. Restore/delete only test records you own.
8. Compare preserved settings, original data/evidence and unrelated customization
   hashes. Inspect MethodServer logs for new errors.

Do not treat the browser's Stop timeout as a guaranteed cancellation of Oracle SQL.

### After an update: "DB Ninja controls are unavailable"

This alert means the browser has no initialized DB Ninja controller. After a
jsfrag deployment, an already-used browser may still have an older
`windchill-all.js` cached at the same URL, even when the server files are correct.
A normal reload or a successful cache-disabled automation test does not prove
that an existing user's cached bundle has been replaced.

Close the alert and perform **Ctrl+Shift+R** in Chrome/Edge on Windows or Linux.
Then open Ninja Trick and **cancel its normal start confirmation** to check
recovery without starting a capture. Notify existing users of this one-time
cache refresh after a JavaScript deployment, including a JavaScript-only update.

This client-cache symptom does not require a database reset, privilege change
or another MethodServer restart. Do not bypass the action's missing-controller
guard. If a cache-bypassing reload does not restore the controller, inspect
JavaScript loading/console errors and the deployed fragment before treating the
problem as resolved. A proxy cache may require a separate, approved review.

## 7. Rollback and uninstall

For a just-applied change in the same controlled maintenance window:

```text
node tools/dbninja.mjs rollback <reviewed-plan.json>
```

Qualified rollback must check **all post-apply hashes, backup hashes and
read-only prerequisite hashes before restoring anything**, including the
non-JavaScript dependencies of a JavaScript-only plan. It must refuse incomplete
snapshots, prerequisite/tool drift and subsequent edits. Stop for review/merge,
never force a stale restore. It restores the selected
file/configuration/bundle snapshot and removes only exact files that were absent
before. It never changes database data and never deletes directories recursively.
Restart and revalidate the approved nodes afterward.

This is not a general uninstall or a license to undo a later CPS. For a later
uninstall, first stop captures, back up data/evidence, review configuration
references and shared XML, remove only this module's entries, propagate XCONF and
rebuild JavaScript from the remaining fragments. Archive the data unless its owner
explicitly approves deletion. There is intentionally no automatic drop/reset script.
