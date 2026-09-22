# Sanitized qualification summary

This is a publication handoff, not a chronological case log. Private receipts
retain detailed execution evidence; public documentation omits hostnames,
accounts, process identities, capture/data identifiers and private build paths.

The owner has authorized custom source and custom JAR/ClassInfo publication at
<https://github.com/milesplay/WindchillDbNinja> under [MIT](LICENSE), with a
Windows 12.1 target binary and strict endpoint NET semantics. This authorization
does not attest to a commit/push, completed release tests or new live deployment.

## Exact binary baseline

| Component | First-binary target |
|---|---|
| Windchill | Services12.1.2.23 build38 (12.1.2.0 CPS23) |
| JDK | Amazon Corretto 11.0.19 |
| Database | Oracle 19c; target RU, schema/PDB privileges and undo still require DBA qualification |
| OS/filesystem | Windows x64 with local NTFS owner/system-administrator ACL enforcement |
| Layout | Traditional `codebase`, with the installed PTC SDK/maintenance tools |
| Distribution | Custom `prebuilt/DbCapture.jar`, seven matching `prebuilt/metadata/com/custom/dbcapture/*.ClassInfo.ser` files and `prebuilt/manifest.json` |

The manifest requires checksums, source fingerprints and target version/SDK
fingerprints. Other CPS/SDK combinations need rebuild and qualification.
This qualification is specific to the reviewed Windows service identity and
local NTFS volume; it is not a general Windows certification.

### Prebuilt provenance

The candidate assembly uses `tools/build-prebuilt.mjs` to compile all current
runtime Java against the target SDK with `--release 11 -proc:none` into private
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

## Release 0.2.0 Windows 12.1 qualification: 2026-09-22

This release records the completed Windows runtime fix and acceptance for the
exact baseline above. It does not broaden the supported matrix to arbitrary
Windows accounts, CPS levels, Java versions or clustered deployments.

| Qualification | Verified result |
|---|---|
| Versioned package identity | `0.2.0-wc121-win1`; package metadata, prebuilt manifest and release documentation use the same version line |
| Current-source build | All current runtime Java sources compiled with the target Java 11 SDK and `--release 11 -proc:none`; the candidate is a fresh module-only JAR |
| Evidence-store regression | **272 assertions passed**, including repeated large metadata checkpoints with a concurrent Windows reader |
| Documentation audit | **70/70 passed**, zero failures, skips or TODOs |
| Prebuilt candidate verification | Exit 0 against the exact Windows x64 / Windchill 12.1.2.23 / Java 11 target |
| Deployment | Reviewed 28-file SafeArea/xconf/JS plan applied; live and canonical SafeArea hashes matched after apply |
| Restart | ServerManager and MethodServer restarted on the approved target; the new MethodServer log contains no ClassInfo registration or evidence persistence error |
| Browser capture | A short empty administrator capture completed with zero changes and no warning/error text; the private metadata state was `COMPLETE` |
| Windows evidence | Local NTFS evidence directory retained the owner-only ACL policy; metadata, events and lease files passed `icacls` processing |
| Oracle row check | SELECT-only SQLcl query confirmed the corresponding session row was `COMPLETED`, `FLASHBACK`, zero table changes, with empty warning/error fields |
| Endpoint security | Direct GET mutation returned the POST-required response; missing and wrong nonce POSTs returned HTTP 403 without starting a capture |
| Ordinary-user authorization | Not executed because no separate ordinary-user test credential was supplied; do not treat administrator acceptance as this check |
| Multi-node/failover and production scale | Not qualified; native SQL/call-tree evidence remains single-starting-node and this package remains development/test only |

The Windows checkpoint fix catches an `AccessDeniedException` from atomic
metadata replacement, revalidates the staged and target files, then uses a
controlled Windows `REPLACE_EXISTING` fallback. The source and regression case
are documented in [WINDOWS-PORTING.md](WINDOWS-PORTING.md). The earlier failed
capture record remains historical evidence of the defect and is not counted as
the new acceptance.

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
full source/binary verdict or a deployed-runtime result. Simulated command checks
are supplemented by native Windows Java 11 NTFS, JSP, packaging and
deployment-plan tests on the reviewed target.

The approved prebuilt packaging is new release work, not a relabeling of an old
private build. Publication must check module-only contents, seven matching
ClassInfo files, source-to-binary identity and target fingerprints. Never include
PTC/Oracle libraries, generated PTC JavaScript bundles or site-specific generated
SQL. The 0.1.1 patch adds only the reviewed profile-specific custom CREATE DDL.

## Release 0.1.0 qualification: 2026-09-21

The fixed-source and selected-prebuilt checks below completed successfully.
These are offline/package results, not a new deployment or live Oracle
acceptance test. The Windows prebuilt JAR is **254,462 bytes**, SHA-256:

```text
0f07c6191fa5d7b0a514e2a6345f99680a9ae03e93cdecb954578b0f0c26a4de
```

[The manifest](prebuilt/manifest.json) records every binary checksum, current
runtime-source fingerprint and exact target SDK profile. It is the authoritative
artifact identity; Git commit `a6c6dbb189515512db295a08636100a33d7760da`
identifies the initial 0.1.0 publication.
The record separates revision/artifact identity, commands, exit statuses, counts
and skips. If a scenario is not executed, keep that fact and its release impact
explicit.

| Qualification | Verified result |
|---|---|
| Repository access and `main` push permission | Maintainer-confirmed; the checked starting revision had only a blank README and no license, not a DB Ninja release |
| Targeted deployment/publication regressions | Original confinement, DDL, rollback, command-quoting and staged-blob failures now pass; Git-backed cases executed, not skipped |
| Complete Node suite | **301/301 passed; zero failures, skips or TODOs** |
| Current-source compilation | **48 runtime Java files** compiled against the qualified SDK with `-proc:none`; generated model inputs and live installation unchanged |
| Source-first strict contracts/helper/lifecycle | **147 + 18 + 29 checks passed**, including all five original contract failures and the named coupled fixes |
| Actual packaged-JAR strict contracts/helper/lifecycle | The same **147 + 18 + 29 checks passed** with only tests compiled ahead of the prebuilt JAR, not fresh implementation classes |
| Other selected-candidate groups | Scope/search 22,878; catalog 22; shipped-DDL 204; preference compatibility 8; presentation 92; object comparison 19; profiler 267; SQL/tree 77; DTO compatibility 11; smoke 32; icons 35 assertions passed, plus profiler API linkage |
| JSP/JavaScript/XML | All **five JSPs** translated and compiled; script/XML checks passed. Existing deprecated/unchecked compiler notes remain visible |
| Current-source JAR and matching ClassInfo | Fresh module-only JAR assembled; 15 fingerprint-locked generated entries and seven matching metadata files validated; no new CCD generation |
| `node tools/prebuilt.mjs verify` | **Exit 0** against the exact Windows/SDK/JDK target |
| `node tools/validate.mjs all --prebuilt` | **Exit 0**, including the Node suite and both source/candidate contract variants |
| `node tools/dbninja.mjs plan --prebuilt` | **Exit 0**; 28 files staged for the fresh target, with no live files changed |
| Publication content/binary scan | English-only candidate and all eight custom binary artifacts passed the allowlist, archive-content, checksums and private-pattern checks |
| Git index/history and public checkout | Checked separately at publication time; Git-backed fixtures alone are not a review of the actual commit |
| Public checkout and clean-target end-to-end installation | Not established by the prior local deployment |
| Newly fixed runtime deployment and live Oracle acceptance | Not established by this handoff; requires separate authorization |

Keep the tests' real expectations. Do not suppress failures or convert them to
TODO passes. `all` is fail-fast; a stopped run does not execute later groups.
Source-first compilation, a smaller passing subset and a package hash match are
not substitutes for complete selected-binary qualification.
The Java counts above total **24,033 assertions**, including duplicated
source/candidate checks and exhaustive small-alphabet scope combinations; they
are not 24,036 independent end-to-end scenarios.

Confirmed corrections include reliable activity fallback, a shared frozen
physical catalog, consistent identifier eligibility, strict NET reduction,
fail-closed legacy logging, settings-related Stop cleanup, numeric capture-ID
rollover and explicit identifier-width rejection. Destination/publication path
confinement, every supported DDL statement/target, rollback prerequisites,
Windows command construction and actual staged blobs are now guarded.
The 0.1.0 record predates the Windows runtime port. Windows support is recorded
for the exact 0.2.0 target above; the earlier statement must not be read as a
current compatibility claim for other releases.

The initial release remains single-process/node-qualified. Restart/cross-node
baseline recovery warns that the current eligible scope is used rather than
reloading the original catalog. A broader Start/transaction failure matrix and
real Oracle boundary/undo/authorization behavior remain qualification work;
they are not claimed as newly exercised live results.

## Release 0.1.1 schema/distribution qualification: 2026-09-21

This patch corrects the missing CREATE DDL and makes privilege grants, table
creation and post-change verification explicit in the
[AI/DBA database runbook](DATABASE-SETUP.md). It does not deploy the new collector
implementation or perform any Oracle mutation.

| Qualification | Verified result |
|---|---|
| DDL provenance | All 8 source scripts matched the retained PTC custom `sql3` output after only CRLF-to-LF and explicit VARCHAR2 BYTE normalization |
| Schema inventory | 4 tables, 4 primary keys, 14 secondary indexes and 4 comments; 18 total indexes including PK backing indexes |
| Schema/profile integrity | Offline checks passed for input/output fingerprints, unchanged model inputs, exact profile and deterministic guarded combined SQL |
| Target profile inspection | Read-only prebuilt SDK/datecode/JDK check and installed xconfmanager plus propagated-properties check passed; `wt.db.maxBytesPerChar=3` |
| Focused schema/publication/prebuilt/docs/install tests | **105/105 passed; zero failures, skips or TODOs** |
| Complete Node suite | **301/301 passed; zero failures, skips or TODOs**, including actual Git staged-blob/completeness scenarios |
| Complete selected-binary validation | `node tools/validate.mjs all --prebuilt` **exit 0**; all **24,036 Java assertions**, source/candidate contract variants, profiler API linkage, five JSPs and JS/XML checks reran successfully |
| Fresh-install plan fixture | Exactly **28 runtime files** staged from the checkout without an installed DB Ninja JAR/ClassInfo; missing JSP/metadata rejected without target writes. Real JDK XML/property helpers; synthetic SDK/version probe, not a real fresh Windchill installation |
| Existing-target read-only plan | Exactly **30 files** staged, including the two existing shared XCONF migration files; no apply performed |
| Running-environment preservation | Hashes/modes of **88 protected live/SafeArea/configuration paths** and **9 SDK prerequisites** unchanged after validation |
| Runtime identity | All **58 runtime/model/artifact files** (49 Java/resource inputs, 8 custom binary artifacts and the generated-model baseline) byte-identical to 0.1.0; original build timestamp retained |
| Public package/index review | **160 files**, including all DDL and 8 approved custom binary artifacts; English documentation, known-private-pattern checks and actual staged-file/byte completeness passed |
| Grant/DDL instructions | Explicit discovery, least-privilege approval, authorized grant application, new schema-owner session, historical-read/monitoring checks, CREATE execution and 4-table/4-PK/18-index acceptance documented; public grant template remains non-executing |
| Actual Oracle grants / combined CREATE execution | **Not executed** in this publication task; requires separate environment/DBA authorization and target validation |
| Live deployment / restart / capture | **Not performed**; prior icon deployment remains separate historical evidence |

The JAR hash remains the 0.1.0 hash above. The combined CREATE script is
9,779 bytes, SHA-256:

```text
a2b8b3ba92678fb315d64f7e014a9dbe8d84a2bf77d25a2b8eb5dfa8bffd1934
```

The shipped [schema profile](sql/oracle/schema-profile.json) restricts use to
Oracle 19c, unchanged Windchill 12.1.2.23 models, width 3, explicit BYTE strings
and INDX. Offline guards and synthetic tests are not Oracle execution evidence.
Publication of a tag/ZIP also does not supply privileges or authorize a restart.
Verify the exact tag and downloaded ZIP/checksum separately when distributing.

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
Fresh schema SQL must match the reviewed target profile and be separately
reviewed/executed by the DBA. The 0.1.1 patch includes the qualified custom
CREATE script; other profiles require target generation, with site-specific
output kept private. There are no automatic grants or restarts.

## Limits remain

Private native SQL/call-tree evidence is local to the starting node and is not
proof of commit or complete request coverage. Caps are not query-work budgets;
LOB representations can miss content changes. Both historical collection paths
depend on usable Oracle undo. ORA-01555/ORA-30052 and incomplete history remain
explicit limitations. This is not a full audit, backup or restore tool.

See [INSTALL.md](INSTALL.md), [OPERATIONS.md](OPERATIONS.md),
[HANDOFF.md](HANDOFF.md) and [PUBLICATION.md](PUBLICATION.md) for the release and
target-acceptance procedures. Keep detailed logs and rollback receipts private.
