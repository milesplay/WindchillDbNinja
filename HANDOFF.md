# Maintainer handoff

Repository: <https://github.com/milesplay/WindchillDbNinja>.
Custom source and custom compiled JAR/ClassInfo distribution is owner-authorized
under [MIT](LICENSE). Publication and live deployment remain separate from that
approval. Start with [installation](INSTALL.md),
[compatibility](COMPATIBILITY.md) and [operating semantics](OPERATIONS.md).
The last qualified Windows line is `v0.2.0-wc121-win1`. The separate
`port/windows-wc1302` / `0.3.0-wc1302-win1` line is an unqualified source
candidate for Windchill 13.0.2.6 build 33 / Information Modeler 13.0.2.0
build 396. Target CCD JAR, seven ClassInfo files and a target sql3-generated
explicit-BYTE create-only DDL candidate now exist privately. The approved
`SELECT ON SYS.V_$DATABASE` and `ANALYZE ANY` grants, target DDL and initial
28-file deployment plan were applied on the authorized development/test
target; schema postchecks passed. A later Wex XML-only menu repair was applied,
and the user reports the menus fixed. An owner restart and post-restart
acceptance after that repair are not recorded. The candidate remains unreleased
and unqualified pending end-to-end, monitoring-flush and multi-node acceptance.
Keep the 12.1/Linux artifacts historical; do not reuse them or replay target
changes.

## Architecture

- `DbCaptureService` / `StandardDbCaptureService`: administrator-gated capture
  lifecycle, persisted ownership, concurrency, result search and object lookup.
- `engine`: table scope/catalog, baseline, Oracle Flashback and endpoint collection,
  net-effect reduction and saved changes.
- `diagnostics`: bounded native SQL/stack observation and persisted report DTOs.
- MVC builders/data utilities and action resources: capture/change grids,
  diagnostics, object references and the dedicated CSV toolbar action.
- Five module JSPs: administration, state/settings API, description editing,
  diagnostics and object inspection.
- Header, CSV and Ninja-only menu-icon controllers: combined into the custom
  jsfrag; diagnostics JS remains a page-specific custom resource. The module's
  Java icon resource and two 16 x 16 PNGs are complemented by PTC
  `dynamicMenuLoad`/`dynamicMenuShow` events, without changing OOTB artifacts.
- [Deployment tool](tools/dbninja.mjs): target-native CCD build, private plans,
  SafeArea installation, XCONF propagation, JS combination, verification and rollback.
- [XML helper](tools/ConfigurationFiles.java): local JDK XML-aware shared-file
   merges and target-model planning; not installed in Windchill.

Existing action IDs, URLs, persistent Java package names, capture IDs, model
associations, serialized evidence types and saved setting keys are deliberately
unchanged. Renaming them can break old records or serialization.

## Important implementation contracts

1. The required capture scope freezes the monitoring filter and eligible
   physical catalog at Start; collection and diagnostics must agree.
   Hidden-table controls affect only saved-result display. After baseline loss,
   cross-node/restart recovery must warn; do not invent historical scope.
   Current source/binary tests must demonstrate closure of the earlier scope
   and unreliable-activity-selection defects.
2. `includedTables` supersedes legacy group switches, including an explicitly empty
   selection. Site/extra exclusions cannot be bypassed by opt-in.
3. PREFERENCEINSTANCE is a movable default exclusion. Do not restore an old local
   extra-exclusion token during generic deployment. The migration utility remains
   only because its compatibility tests exercise it; installation never invokes it.
4. Start is globally serialized; Stop is checked against the persisted starting
   username. Client enablement is not the authorization boundary.
5. Native confirmations are mandatory. If a controller is unavailable, actions
   fail visibly; they must not fall through into an unconfirmed server command.
6. Header placement never changes height/min-height or adds a row. Account for
   sibling server markers, user/search controls, narrow screens and late layout.
   Keep capture-window artwork stable across pending/unknown states. The visual
   window is not an authorization/state substitute; only validated server state
   enables mutations. Do not show capture artwork for initial/idle outages.
7. CSV exports the visible grid range, sort order and visible columns, not a hidden
   backing store. Preserve UTF-8 BOM, CRLF, escaping, formula protection and the
   180-byte filename cap.
8. Diagnostics records are keyed by immutable session OID, not a reusable CAP label.
   Test old serialized reports; do not infer missing historical scope/evidence from today.
9. JSP Ajax content does not execute ordinary external script tags. Keep global
   controllers in the shell fragment. Do not fix loading with standard JSP patches.
10. MVC column IDs are global DataUtility selectors. Use module-specific selectors;
    generic IDs may resolve to an unrelated PTC utility.
11. The custom MVC XML must retain its needed Spring namespaces/schema locations.
    A broken MVC configuration can affect more than the DB Ninja page.
12. The disabled heuristic LogCorrelator path and legacy DTO/settings fields are
    retained as compile/compatibility dependencies, not declared dead code. Do not
    enable its archived Log4j/schema setup or remove types without reference analysis.
13. Strict endpoint NET is agreed: insert then delete is net-zero;
    `A -> B -> delete` retains old value `A`, not B. Update then revert is
    net-zero. Enforce this in both collection paths without weakening tests.

## Package contract

The historical 0.2.0 prebuilt candidate is `prebuilt/DbCapture.jar`, seven
`prebuilt/metadata/com/custom/dbcapture/*.ClassInfo.ser` files and
`prebuilt/manifest.json` with checksums, source fingerprints and target
version/SDK fingerprints. Its exact baseline is **Windchill Services12.1.2.23
build38 (12.1.2.0 CPS23), Corretto 11.0.19, Oracle 19c, Windows x64, traditional
`codebase`**. Other CPS/SDK combinations need rebuild and qualification.
The evidence store is qualified for a local NTFS volume with explicit ACL and
link/reparse-point checks. Other Windows service identities/filesystems require
separate qualification.

The historical 0.2.0 `plan --prebuilt` route selects only its exact qualified
package. For 0.3.0 use only the private 13.0.2.6 target-CCD candidate and its
reviewed, unapplied plan. Do not use historical 12.1/Linux artifacts or silently
fall back to an old installed JAR. Oracle execution and runtime acceptance
remain separately gated.
[Fresh-install DDL](sql/oracle/README.md) is bundled for the exact Oracle 19c /
unchanged Windchill 12.1.2.23 model / `wt.db.maxBytesPerChar=3` / BYTE / INDX
profile; all other profiles require target generation and qualification.
DBA review/execution is separate. No PTC/Oracle libraries or generated PTC
JavaScript bundles belong in the package.

`tools/schema-package.mjs verify` binds the eight CREATE inputs and deterministic
combined output to the unchanged model baseline. Its optional `--target` checks
the prebuilt target and declared/propagated width without an Oracle connection.
The combined SQL guards reserved table/index/constraint names and explicit
tablespaces before the first CREATE; it is neither an upgrade nor a reset.
The publication gate rejects missing schema resources and any reviewed file
omitted from the actual Git index. Package and prebuilt manifest versions must
agree. See [CHANGELOG.md](CHANGELOG.md) for the 0.1.1 packaging-only patch.

### Current-source prebuilt assembly

The historical 12.1 prebuilt flow used `--release 11 -proc:none`. The 0.3
source/validation tools now target `--release 17`; do not run prebuilt assembly
until a new target CCD baseline is generated and reviewed. The old metadata and
model baseline are invalid for WC 13.0.2.

`tools/build-prebuilt.mjs` compiles **all current runtime Java** against the
target SDK with `--release 17 -proc:none` into private output. It assembles a
fresh module-only JAR, **not an overlay retaining stale implementation classes**.
Only these 15 fingerprint-locked custom generated JAR entries are retained from
the previous verified target CCD generation:

| Retained generated entry | Count |
|---|---:|
| Model base classes | 7 |
| Association classes | 3 |
| English `RB.ser` resources | 4 |
| Listener list | 1 |

The seven matching ClassInfo files come from that same verified CCD generation.
The four model Java definitions and `.rbInfo` source are byte-identical to the
baseline; this assembly introduces no schema or annotation changes.
`deployment/generated-model-baseline.json` records source, generated-entry,
metadata and SDK fingerprints.

This is **current-source compilation with unchanged generated model artifacts,
not a new CCD or annotation-processing run**. It performs no live metadata
writes. A genuinely changed model or CPS requires target CCD, a reviewed new
baseline and qualification. Do not edit fingerprints merely to accept drift.
The default target-build route remains a separate CCD workflow with its own
maintenance approval and metadata safeguards.

## Repeatable verification

Set the reviewed `WT_HOME` and `JAVA_HOME`. For the default target-build route:

```text
node tools/dbninja.mjs test
node tools/validate.mjs scope
node tools/validate.mjs presentation
node tools/validate.mjs profiler
node tools/validate.mjs compatibility
node tools/validate.mjs smoke
node tools/validate.mjs web
node tools/validate.mjs icons
node tools/validate.mjs contracts
node tools/validate.mjs all
```

Use the smallest relevant group while editing; run `all` before a release.
`all` is offline with respect to business-data writes. Negative-path tests
intentionally log exceptions; examine the final test verdict rather than
counting every ERROR word as a failed test.

For the prebuilt route, verify and validate that selected candidate:

```text
node tools/prebuilt.mjs verify
node tools/validate.mjs all --prebuilt
node tools/dbninja.mjs plan --prebuilt
```

For English publication documentation only:

```text
node --test tools/documentation-audit-regression.cjs
```

For action-icon-only edits, `icons` compiles the current Java resource bundle
and checks real PTC icon rendering plus JDK PNG decoding. It does not require a
prebuilt module JAR or run CCD/annotation processing. Pair it with the
`action-icon-regression.cjs`, `deployment-regression.cjs` and
`header-ux-regression.cjs` Node selectors. The normal full-release gates still
apply. See [the icon contract](deployment/icons/README.md).

The `contracts` group compiles current Java sources with `-proc:none` against
the reviewed JAR/SDK and uses mocked JDBC. It neither connects to Oracle nor
generates ClassInfo; generated model bases are still required. Source-only
success is not proof that the shipped JAR contains the same fixes.

Known source, binary and installer defects must be closed with recorded
evidence. [LOCAL-INSTALL.md](LOCAL-INSTALL.md) separates historical icon
deployment from the pending new qualification. Do not freeze old failure counts
into release assertions, skip contracts or treat TODO failures as passes.
Run focused deployment/publication fixtures with:

```text
node --test tools/deployment-regression.cjs tools/schema-regression.cjs tools/release-audit-regression.cjs
```

`all` is fail-fast: if its Node phase fails, later Java/JSP phases are not run
by that invocation. The individual groups above remain available for complete
diagnosis; do not mistake a short failed `all` for execution of every group.
Git-dependent checks skipped because Git is unavailable must be run again with
Git before committing. Review staged bytes and history, not only working files.

The old Oracle fixture was tied to a private database identity and is archived,
not silently generalized by weakening its safety guard. For live qualification,
use a separately approved disposable-object scenario from [USE-CASES.md](USE-CASES.md)
and verify the actual rows/values. No database-writing test, general-purpose
schema reset or cleanup utility is run by the published offline suite.

The tests require the selected target SDK for Java/JSP checks. Native Node
regressions are dependency-free; JDK XML integration tests explicitly skip if
`JAVA_HOME` is absent. A skipped check is not a passing platform qualification.

## Release discipline

- Canonical client names live in [assets.json](deployment/assets.json). Change a
  versioned filename when changing a directly served asset, and keep page references
  and tests consistent. Recombine the shell fragment with the installed PTC tool.
- Never copy generated PTC bundles into the source tree/SafeArea.
- Rebuild after a Windchill SDK/CPS/JDK change; retain the target build privately.
- Recheck the actual deployed browser after restart/cache refresh. Offline DOM
  fixtures alone do not prove script ordering in the PTC shell.
- Preserve saved records/evidence/settings and unrelated customizations.
- Keep historical hostnames, account details, stack dumps and captures out of
  public documentation. Use [the publication procedure](PUBLICATION.md).
- Preflight is read-only. Apply requires prior maintenance and Oracle approval.
  No automatic restart, grant, production deployment or database reset.
- Private evidence has LOB, cap and undo limits; native SQL/call trees are
  single-node. This is not a full audit, backup or restore product.
- The repository URL supplies package/instructions, not server credentials,
  DBA approval or maintenance authorization. Do not report newly deployed fixes,
  successful public-checkout installation or full-suite acceptance without evidence.
