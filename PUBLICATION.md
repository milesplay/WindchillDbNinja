# Publication handoff

**Canonical repository:** <https://github.com/milesplay/WindchillDbNinja>.
**Maintainer:** the GitHub repository owner, [milesplay](https://github.com/milesplay).

The owner has authorized publication of custom source plus the custom compiled
JAR/ClassInfo package after known defects are fixed, selected the
[MIT License](LICENSE) (copyright 2026 milesplay), restricted the first binary
to Linux, and selected strict endpoint NET semantics. These decisions are
settled; they are not evidence of a commit, push, released binary, passing test
suite or newly deployed runtime. No Git/network publication is performed by
this documentation handoff.

The maintainer has verified authenticated repository access and permission to
push `main`. The checked starting revision contained only a blank README and
no license; it was not a published DB Ninja package. Include the local MIT
license in the reviewed publication. Repository access does not replace the
remaining source/binary qualification or target authorization.

## Public package

Use an explicit allowlist, not a wholesale copy of the working directory:

- English root documentation, MIT license and [third-party notices](THIRD-PARTY-NOTICES.md).
- Module-authored source, custom resources and deployment declarations.
- The two declared 16 x 16 PNG icons and their SVG render sources, with
  [provenance](deployment/icons/README.md); no font files.
- Deployment/validation tools and regression tests, read-only Oracle checks and
  review-only privilege guidance.
- The exact approved compiled candidate:

  ```text
  prebuilt/DbCapture.jar
  prebuilt/metadata/com/ptc/dbcapture/*.ClassInfo.ser   (exactly seven)
  prebuilt/manifest.json
  ```

The manifest must contain checksums, source fingerprints and target version/SDK
fingerprints using package-relative identifiers, not private build paths.
Check the actual JAR entries and all metadata, not just filenames. Only custom
classes and custom persistent metadata are authorized; the binary exception is
not permission to publish arbitrary JARs or serialized files.

Prebuilt assembly uses `tools/build-prebuilt.mjs`: all current runtime Java is
compiled against the target SDK with `--release 17 -proc:none` into private
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

The exact first-binary baseline is **Windchill Services13.0.2.11 build32
(13.0.2.0 CPS11), Corretto 17.0.12, Oracle 19c, Linux x64, traditional
`codebase`**. Other CPS/SDK combinations require rebuild and qualification.
Windows is unsupported as shipped: POSIX permissions and `unix:nlink` are
required, there is no NTFS ACL fallback, and no Windows certification is claimed.

Do not include PTC/Oracle libraries, original PTC JSPs, generated PTC JavaScript
bundles, portable pre-generated SQL, copied site configuration, credentials,
private build/backups, logs, captures, SQL/bind evidence or customer data.
Non-English local documents remain local and are excluded from publication;
shipped English documents must not link to excluded documents.
Windchill and Oracle remain separately licensed prerequisites.

## Release gates

| Gate | Evidence required before calling the candidate qualified |
|---|---|
| Collector fixes | Current-source and selected-binary regressions for reliable activity selection, frozen/consistent table scope and strict endpoint NET. Insert then delete is net-zero; `A -> B -> delete` retains old value A. Do not weaken expectations to hide defects. |
| Deployment/publication guards | The original path, DDL, rollback, Windows-command and staged-index fixtures now have maintainer-confirmed targeted passing evidence; see [the qualification record](LOCAL-INSTALL.md#deployment-and-publication-audit-findings). Final binary allowlisting and candidate index/history review remain release gates. |
| Binary identity | A reviewed custom-only JAR, exactly seven matching ClassInfo files, manifest checksums/source fingerprints and matching target version/SDK fingerprints; no silent old-JAR fallback. |
| Offline qualification | Run the complete suite against the selected candidate. Record revision/artifact identity, commands, exit status, counts and skips in [LOCAL-INSTALL.md](LOCAL-INSTALL.md). Source-only or icon-only success is insufficient. |
| Clean package and Git review | Exercise an allowlist export/fresh checkout without private build/backup dependencies. Review actual staged bytes and history, including attachments; a working-tree scan alone is insufficient. |
| Target acceptance | Separately authorized non-production installation, ordinary-user denial, actual browser behavior and disposable Oracle FLASHBACK/SNAPSHOT scenarios. Keep unexecuted live checks explicitly unverified. |
| Published-link handoff | After publication, verify public README/package links and a clean checkout. An export or historical local installation does not prove public-link-only end-to-end deployment. |

Final validation numbers are pending maintainer completion before commit.
[Prior verified icon deployment](LOCAL-INSTALL.md#runtime-icon-deployment-2026-09-21)
does not establish that new collector/helper fixes are installed. Do not call a
failed, skipped, mocked or historical scenario a newly passed live test.
Windows qualification is outside this Linux-only release, not an implied pass.

**Development and test environments only. Do not install or run DB Ninja in
production.** An unknown environment classification is not approval.
Preflight remains read-only; apply requires prior maintenance and Oracle
approval. Fresh schema DDL is generated on the target and reviewed/executed by
the DBA. There are no automatic grants or restarts.

## Candidate checks and export

With the reviewed `WT_HOME` and `JAVA_HOME`:

```text
node tools/prebuilt.mjs verify
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
Never upload private evidence to a public issue, release attachment or online
scanner. No command here instructs an automatic push.

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
> customization and troubleshooting. Linux-only diagnostic customization for
> qualified development and test environments.

Suggested topics: `windchill`, `ptc`, `plm`, `oracle`, `windchill-qml`,
`query-builder`, `customization`, `database-diagnostics`, `troubleshooting`.
