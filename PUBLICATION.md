# Publication handoff

**Canonical repository:** <https://github.com/milesplay/WindchillDbNinja>.
**Maintainer:** the GitHub repository owner, [milesplay](https://github.com/milesplay).

The owner has authorized publication of custom source plus the custom compiled
JAR/ClassInfo package after known defects are fixed, selected the
[MIT License](LICENSE) (copyright 2026 milesplay), restricted the first binary
to the reviewed Windows 12.1 target, and selected strict endpoint NET semantics. These decisions are
settled; approval alone is not evidence of a passing test suite or newly deployed
runtime. The initial 0.1.0 package was published in commit
`a6c6dbb189515512db295a08636100a33d7760da`; see [CHANGELOG.md](CHANGELOG.md)
and [the qualification record](LOCAL-INSTALL.md) for each revision's scope.

The current release line is `v0.2.0-wc121-win1`, based on upstream `v0.1.1`.
It contains the Windows 12.1 source port, target-qualified custom binary and
the [Windows porting guide](WINDOWS-PORTING.md). A future Windows 13.0.2 port
must use a separate branch, target-generated metadata and release line; it is
not a re-labeling of this package.

The maintainer verified authenticated repository access and permission to
push `main`. Preserve remote history and any subsequent contributor changes;
do not force-push a release. Repository access does not replace qualification
or target authorization.

## Public package

Use an explicit allowlist, not a wholesale copy of the working directory:

- English root documentation, MIT license and [third-party notices](THIRD-PARTY-NOTICES.md).
- Module-authored source, custom resources and deployment declarations.
- The two declared 16 x 16 PNG icons and their SVG render sources, with
  [provenance](deployment/icons/README.md); no font files.
- Deployment/validation tools and regression tests, read-only Oracle checks and
  review-only privilege guidance.
- [The AI/DBA runbook](DATABASE-SETUP.md) covering explicit grant/DDL approval,
  authorized application and post-change verification without embedded credentials.
- The [qualified Oracle first-install schema](sql/oracle/README.md), with eight
  custom CREATE inputs, the guarded combined script and model/profile checksums.
  The exact profile is Oracle 19c, unchanged Windchill 12.1.2.23 models,
  `wt.db.maxBytesPerChar=3`, explicit BYTE widths and INDX.
- The exact approved compiled candidate:

  ```text
  prebuilt/DbCapture.jar
  prebuilt/metadata/com/custom/dbcapture/*.ClassInfo.ser   (exactly seven)
  prebuilt/manifest.json
  ```

The manifest must contain checksums, source fingerprints and target version/SDK
fingerprints using package-relative identifiers, not private build paths.
Check the actual JAR entries and all metadata, not just filenames. Only custom
classes and custom persistent metadata are authorized; the binary exception is
not permission to publish arbitrary JARs or serialized files.

Prebuilt assembly uses `tools/build-prebuilt.mjs`: all current runtime Java is
compiled against the target SDK with `--release 11 -proc:none` into private
output, then assembled as a fresh module-only JAR, not a stale implementation
overlay. It retains only 15 fingerprint-locked custom generated entries
(7 model bases, 3 association classes, 4 English `RB.ser` resources and a listener
list) plus seven matching ClassInfo files from the previous verified target CCD
generation. The four model Java definitions and `.rbInfo` source are
byte-identical to that baseline.

`deployment/generated-model-baseline.json` records source, generated-entry,
metadata and SDK fingerprints. This is **current-source compilation with
unchanged generated model artifacts, not a new CCD or annotation-processing
run**. There are no schema/annotation changes or live metadata writes in this
assembly. A changed model or CPS requires target CCD and a reviewed new baseline.

The exact first-binary baseline is **Windchill Services12.1.2.23 build38
(12.1.2.0 CPS23), Corretto 11.0.19, Oracle 19c, Windows x64, traditional
`codebase`**. Other CPS/SDK combinations require rebuild and qualification.
Windows evidence storage is restricted to the reviewed local-NTFS ACL policy;
other service identities and filesystems are not qualified.

Do not include PTC/Oracle libraries, original PTC JSPs, generated PTC JavaScript
bundles, site-specific generated SQL, copied site configuration, credentials,
private build/backups, logs, captures, SQL/bind evidence or customer data.
Non-English local documents remain local and are excluded from publication;
shipped English documents must not link to excluded documents.
Windchill and Oracle remain separately licensed prerequisites.
The bundled custom DDL is profile-specific, not portable pre-generated SQL for
arbitrary sites. It contains no PTC vendor schema, automatic grants or resets.

## Release gates

| Gate | Evidence required before calling the candidate qualified |
|---|---|
| Collector fixes | Current-source and selected-binary regressions for reliable activity selection, frozen/consistent table scope and strict endpoint NET. Insert then delete is net-zero; `A -> B -> delete` retains old value A. Do not weaken expectations to hide defects. |
| Deployment/publication guards | The original path, DDL, rollback, Windows-command and staged-index fixtures now have maintainer-confirmed targeted passing evidence; see [the qualification record](LOCAL-INSTALL.md#deployment-and-publication-audit-findings). Final binary allowlisting and candidate index/history review remain release gates. |
| Binary identity | A reviewed custom-only JAR, exactly seven matching ClassInfo files, manifest checksums/source fingerprints and matching target version/SDK fingerprints; no silent old-JAR fallback. |
| Schema completeness | Eight CREATE input scripts plus deterministic guarded output, four tables/four PKs/fourteen secondary indexes, unchanged model/profile hashes and explicit Oracle/byte-width/tablespace conditions; no missing or additional schema inputs. |
| Offline qualification | Run the complete suite against the selected candidate. Record revision/artifact identity, commands, exit status, counts and skips in [LOCAL-INSTALL.md](LOCAL-INSTALL.md). Source-only or icon-only success is insufficient. |
| Clean package and Git review | Exercise an allowlist export/fresh checkout without private build/backup dependencies. Review actual staged bytes and history, including attachments; a working-tree scan alone is insufficient. |
| Target acceptance | Separately authorized non-production installation, ordinary-user denial, actual browser behavior and disposable Oracle FLASHBACK/SNAPSHOT scenarios. Keep unexecuted live checks explicitly unverified. |
| Published-link handoff | After publication, verify public README/package links and a clean checkout. An export or historical local installation does not prove public-link-only end-to-end deployment. |

Record final validation numbers for each revision before commit in the
[qualification summary](LOCAL-INSTALL.md), not only in a private terminal log.
[Prior verified icon deployment](LOCAL-INSTALL.md#runtime-icon-deployment-2026-09-21)
does not establish that new collector/helper fixes are installed. Do not call a
failed, skipped, mocked or historical scenario a newly passed live test.
Qualification applies only to the recorded Windows 12.1 target, not arbitrary Windows systems.

**Development and test environments only. Do not install or run DB Ninja in
production.** An unknown environment classification is not approval.
Preflight remains read-only; apply requires prior maintenance and Oracle
approval. Fresh schema DDL uses the exact qualified bundle or target generation
and is separately reviewed/executed by the DBA. There are no automatic grants
or restarts.

## Candidate checks and export

With the reviewed `WT_HOME` and `JAVA_HOME`:

```text
node tools/prebuilt.mjs verify
node tools/schema-package.mjs verify --target
node tools/validate.mjs all --prebuilt
node tools/check-publication.mjs --require-prebuilt --export
```

`node tools/check-publication.mjs --require-prebuilt` is the binary release
gate; `--export` may be combined with it to export the checked candidate.
`node tools/check-publication.mjs` remains available for source-only working-tree
checks. Such a check is not binary release acceptance.
An export reports `PUBLICATION_EXPORT=...`; use that reported export, not a
private build or backup directory, for candidate review.

The default `node tools/dbninja.mjs plan` uses the target build. Optional
`node tools/dbninja.mjs plan --prebuilt` selects the verified compiled candidate.
Neither package checks nor a plan authorize apply or prove Oracle access/undo.
See [INSTALL.md](INSTALL.md) for the full sequence.

Review every exported file, JAR entry, metadata file and manifest. The expected
guards cover UTF-8 text, allowed binary formats/hashes, path/symlink confinement
and private-content patterns; a scanner is not a complete secret scanner,
copyright determination or operational approval. Keep site-specific private
patterns in an unpublished file; never embed private identifiers in the scanner.

Before the maintainer commits, review `git status --short`, `git diff --cached`,
`git ls-files` and relevant history in the clean publication workspace.
`.gitignore` does not remove previously tracked files or sanitize historical
blobs. A staged version can differ from the working copy. Run Git-dependent
regressions with Git available rather than treating their skips as acceptance.
The publication gate requires **every** reviewed file in the Git index and
requires the indexed bytes to match the reviewed working tree, including DDL.
Never upload private evidence to a public issue, release attachment or online
scanner. No command here instructs an automatic push.

## Releases and downloadable distribution

Use a versioned GitHub **pre-release** for the Windows development/test preview.
Build `WindchillDbNinja-<version>-windows-x64.zip` with `git archive` from the exact
reviewed tag, using `WindchillDbNinja-<version>/` as its single root directory.
Include all tracked source, prebuilt artifacts, DDL, tools, licenses and English
documentation. Never archive the whole working directory or attach validation
logs, backups, credentials or licensed SDK libraries.

Upload the ZIP and a `SHA256SUMS` file containing its SHA-256 and filename.
State the exact commit, profile, test scope and live-test limitations in English
release notes. Verify the tag/commit, anonymously download both assets, compare
the archive bytes/checksum and verify an extracted copy before reporting release
completion. A source-hosting auto-generated archive is not a separate compiled
build; the custom compiled resources are already tracked in this project.

**GitHub Packages is intentionally not required.** This customization is not
consumed as a Maven library, npm package or container image. A full Releases ZIP
is the appropriate distribution; do not create an empty/unnecessary registry
package or redistribute a licensed Windchill image just to fill the Packages UI.
Keep `package.json` private; Node is used for local tools, not npm publication.

## Community and private disclosure

- Use the GitHub repository owner as the maintainer contact; no email address is
  asserted. Report non-sensitive reproducible issues with synthetic examples.
- Use **private GitHub Security Advisories, if enabled**, for sensitive reports.
  Otherwise ask the owner to establish an approved private channel without
  attaching sensitive details publicly. Never post credentials, SQL/bind values,
  schemas, captures, customer details or unredacted screenshots in public issues.
- Review repository protections, history/attachment scanning and CI permissions.
  Do not expose a licensed SDK, internal target or credentials to public CI or
  untrusted pull requests.
- Require relevant regressions, a sanitized compatibility statement and clear
  release notes for contributions. Preserve persistent identities and serialized
  compatibility; incompatible changes require a migration plan.

The repository URL is sufficient for the package and instructions, not server
credentials, DBA approval or maintenance authorization. It is not PTC
endorsement, Windows certification, production approval or a full audit,
backup/restore guarantee. Native SQL/call trees are single-node and private;
LOB representation, caps and Oracle undo constrain results.

## Suggested GitHub description

> Explore how Windchill operations change Oracle data to guide QML reports,
> customization and troubleshooting. Windows 12.1 diagnostic customization for
> qualified development and test environments.

Suggested topics: `windchill`, `ptc`, `plm`, `oracle`, `windchill-qml`,
`query-builder`, `customization`, `database-diagnostics`, `troubleshooting`.
