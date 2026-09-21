# Sanitized qualification summary

This is a publication handoff, not a chronological case log. Private receipts
retain detailed execution evidence; public documentation omits hostnames,
accounts, process identities, capture/data identifiers and private build paths.

The owner has authorized custom source and custom JAR/ClassInfo publication at
<https://github.com/milesplay/WindchillDbNinja> under [MIT](LICENSE), with a
Linux-only first binary and strict endpoint NET semantics. This authorization
does not attest to a commit/push, completed release tests or new live deployment.

## Exact binary baseline

| Component | First-binary target |
|---|---|
| Windchill | Services13.0.2.11 build32 (13.0.2.0 CPS11) |
| JDK | Amazon Corretto 17.0.12 |
| Database | Oracle 19c; target RU, schema/PDB privileges and undo still require DBA qualification |
| OS/filesystem | Linux x64 with POSIX permissions and `unix:nlink` |
| Layout | Traditional `codebase`, with the installed PTC SDK/maintenance tools |
| Distribution | Custom `prebuilt/DbCapture.jar`, seven matching `prebuilt/metadata/com/ptc/dbcapture/*.ClassInfo.ser` files and `prebuilt/manifest.json` |

The manifest requires checksums, source fingerprints and target version/SDK
fingerprints. Other CPS/SDK combinations need rebuild and qualification.
Windows is unsupported as shipped; no NTFS ACL fallback or Windows
certification is claimed.

### Prebuilt provenance

The candidate assembly uses `tools/build-prebuilt.mjs` to compile all current
runtime Java against the target SDK with `--release 17 -proc:none` into private
output. It produces a fresh module-only JAR, not an overlay retaining stale
implementation classes. Only 15 fingerprint-locked custom generated entries
(7 model bases, 3 association classes, 4 English `RB.ser` resources and a listener
list) and seven matching ClassInfo files are retained from the prior verified
target CCD generation.

The four model Java definitions and `.rbInfo` source are byte-identical to that
baseline. `deployment/generated-model-baseline.json` records source,
generated-entry, metadata and SDK fingerprints. This is current-source
compilation with unchanged generated model artifacts, **not a new CCD or
annotation-processing run**; there are no schema/annotation changes or live
metadata writes. A changed model or CPS requires target CCD and a reviewed new
baseline. Final candidate verification remains distinct from this assembly
description.

## Runtime icon deployment: 2026-09-21

**Prior verified scope, not acceptance of the new source/binary fixes.**
The earlier explicitly authorized local deployment established:

- The module-owned Java action resource resolves the two versioned 16 x 16 PNGs.
  The reviewed icon JAR change was limited to the action-resource class; model
  ClassInfo was not regenerated for that icon-only deployment.
- Scoped PTC SafeArea/swmaint deployment preserved private backups and protected
  unrelated files. The approved foreground application service was restarted;
  this is a historical event, not permission to restart another target.
- Real Quick Links responses lacked image fields. The module-owned Ninja-only
  controller was therefore included in the custom jsfrag, using PTC
  `dynamicMenuLoad` and `dynamicMenuShow`, with PTC combine/compress. No OOTB
  resource, menu handler, permission rule or framework source was changed.
- After a cache-bypassing reload, the actual Quick Links menu displayed both
  images at 16 x 16. Idle action enablement and the original header height were
  preserved; other actions did not receive Ninja images.

Earlier read-only browser checks and focused offline icon checks belong to that
historical scope. They are not proof of a new successful capture, a clean-target
installation, full release acceptance or deployed collector/helper fixes.
Unrelated pre-existing application warnings were not represented as repaired.

## Java contract audit findings

The earlier audit identified unreliable monitoring/activity fallback, physical
scope drift, inconsistent quoted/mixed-case table eligibility and divergent NET
reductions. These are closure requirements, not permanent expected failure
counts. The selected semantics are now settled:

- INSERT then DELETE, absent at both endpoints, is net-zero.
- `A -> B -> delete` reports deletion with old value A, not B.
- UPDATE then revert is net-zero for reportable columns.
- FLASHBACK and SNAPSHOT must agree given equivalent endpoint information.

Corrected source and the selected binary must both demonstrate these contracts.
Mocked JDBC tests do not establish real Oracle transaction, locking, ordering or
undo behavior. No claim is made here that new source fixes have been deployed.

## Deployment and publication audit findings

The earlier audit required stronger destination/ancestor confinement,
create-only DDL shape validation, rollback prerequisite checks and staged-content
review. The maintainer has fixed the original path, DDL, rollback,
Windows-command and staged-index failures and confirmed **22 targeted tests
passed with actual Git and no skips**. This is targeted tool evidence, not a
full source/binary verdict or a deployed-runtime result. Simulated
Windows-command checks do not qualify a Windows runtime or expand the approved
Linux-only scope.

The approved prebuilt packaging is new release work, not a relabeling of an old
private build. Publication must check module-only contents, seven matching
ClassInfo files, source-to-binary identity and target fingerprints. Never include
PTC/Oracle libraries, generated PTC JavaScript bundles or portable pre-generated SQL.

## Release 0.1.0 qualification: 2026-09-21

The fixed-source and selected-prebuilt checks below completed successfully.
These are offline/package results, not a new deployment or live Oracle
acceptance test. The prebuilt JAR is **254,435 bytes**, SHA-256:

```text
69859afca0ed384566e90db8a8cb177da7976f6d7efcded9b7adba83c3fada72
```

[The manifest](prebuilt/manifest.json) records every binary checksum, current
runtime-source fingerprint and exact target SDK profile. It is the authoritative
artifact identity; the eventual Git commit identifies the complete publication.
The record separates revision/artifact identity, commands, exit statuses, counts
and skips. If a scenario is not executed, keep that fact and its release impact
explicit.

| Qualification | Verified result |
|---|---|
| Repository access and `main` push permission | Maintainer-confirmed; the checked starting revision had only a blank README and no license, not a DB Ninja release |
| Targeted deployment/publication regressions | Original confinement, DDL, rollback, command-quoting and staged-blob failures now pass; Git-backed cases executed, not skipped |
| Complete Node suite | **279/279 passed; zero failures, skips or TODOs** |
| Current-source compilation | **48 runtime Java files** compiled against the qualified SDK with `-proc:none`; generated model inputs and live installation unchanged |
| Source-first strict contracts/helper/lifecycle | **147 + 18 + 29 checks passed**, including all five original contract failures and the named coupled fixes |
| Actual packaged-JAR strict contracts/helper/lifecycle | The same **147 + 18 + 29 checks passed** with only tests compiled ahead of the prebuilt JAR, not fresh implementation classes |
| Other selected-candidate groups | Scope/search 22,878; catalog 22; shipped-DDL 208; preference compatibility 8; presentation 92; object comparison 19; profiler 266; SQL/tree 77; DTO compatibility 11; smoke 32; icons 35 assertions passed, plus profiler API linkage |
| JSP/JavaScript/XML | All **five JSPs** translated and compiled; script/XML checks passed. Existing deprecated/unchecked compiler notes remain visible |
| Current-source JAR and matching ClassInfo | Fresh module-only JAR assembled; 15 fingerprint-locked generated entries and seven matching metadata files validated; no new CCD generation |
| `node tools/prebuilt.mjs verify` | **Exit 0** against the exact Linux/SDK/JDK target |
| `node tools/validate.mjs all --prebuilt` | **Exit 0**, including the Node suite and both source/candidate contract variants |
| `node tools/dbninja.mjs plan --prebuilt` | **Exit 0**; 30 files staged for the existing target, with no live files changed |
| Publication content/binary scan | English-only candidate and all eight custom binary artifacts passed the allowlist, archive-content, checksums and private-pattern checks |
| Git index/history and public checkout | Checked separately at publication time; Git-backed fixtures alone are not a review of the actual commit |
| Public checkout and clean-target end-to-end installation | Not established by the prior local deployment |
| Newly fixed runtime deployment and live Oracle acceptance | Not established by this handoff; requires separate authorization |

Keep the tests' real expectations. Do not suppress failures or convert them to
TODO passes. `all` is fail-fast; a stopped run does not execute later groups.
Source-first compilation, a smaller passing subset and a package hash match are
not substitutes for complete selected-binary qualification.
The Java counts above total **24,036 assertions**, including duplicated
source/candidate checks and exhaustive small-alphabet scope combinations; they
are not 24,036 independent end-to-end scenarios.

Confirmed corrections include reliable activity fallback, a shared frozen
physical catalog, consistent identifier eligibility, strict NET reduction,
fail-closed legacy logging, settings-related Stop cleanup, numeric capture-ID
rollover and explicit identifier-width rejection. Destination/publication path
confinement, every supported DDL statement/target, rollback prerequisites,
Windows command construction and actual staged blobs are now guarded.
Windows runtime support has not been added.

The initial release remains single-process/node-qualified. Restart/cross-node
baseline recovery warns that the current eligible scope is used rather than
reloading the original catalog. A broader Start/transaction failure matrix and
real Oracle boundary/undo/authorization behavior remain qualification work;
they are not claimed as newly exercised live results.

## Live checks not established for the new release

- Ordinary-user denial after authentication, not only anonymous rejection.
- Separately authorized disposable FLASHBACK/SNAPSHOT captures proving actual
  committed values, strict NET cases, ownership/concurrency and failure handling.
- New persistent saves/deletion against authorized test records.
- Actual downloaded CSV bytes and OS clipboard behavior, not just simulated
  browser handoff.
- Multi-node/failover behavior, another CPS/SDK, Windows or workload-scale limits.

These checks require an authorized test target; neither the repository URL nor
package verification supplies credentials, DBA approval or a maintenance window.
**Development and test environments only. Do not install or run DB Ninja in
production.** Stop if the environment classification is unknown.

Preflight is read-only. Apply requires prior maintenance and Oracle approval.
Fresh schema SQL must be generated on the target, reviewed/executed by the DBA,
and kept private. There are no automatic grants or restarts.

## Limits remain

Private native SQL/call-tree evidence is local to the starting node and is not
proof of commit or complete request coverage. Caps are not query-work budgets;
LOB representations can miss content changes. Both historical collection paths
depend on usable Oracle undo. ORA-01555/ORA-30052 and incomplete history remain
explicit limitations. This is not a full audit, backup or restore tool.

See [INSTALL.md](INSTALL.md), [OPERATIONS.md](OPERATIONS.md),
[HANDOFF.md](HANDOFF.md) and [PUBLICATION.md](PUBLICATION.md) for the release and
target-acceptance procedures. Keep detailed logs and rollback receipts private.
