# Wex Menu Compatibility

The active approach generates complete custom menu models from the target's
current navigation XML. It does not install a Wex extension, modify standard
navigation files, or disable Wex signature checks. The XML-only repair was
applied on the development/test target, and the user reports the menus fixed.
An owner restart and post-restart acceptance after that repair are not
confirmed; this guide does not authorize further target changes.

## Why This Profile Exists

The inspected Wex Deploy 2.4-13.0 implementation collects action models with
its own merger. `NmXmlActionModels.merge()` replaces duplicate model names
instead of honoring PTC's incremental model contents. Its `updateSystem()`
then invokes the legacy PTC DOM parser. A partial custom definition of
`header actions` or `site navigation` can therefore replace the standard menu.

Changing `incremental=""` to `incremental="true"` does not fix this Wex path.
PTC's action report can reload a different intermediate model state; verify
the rendered menus and raw ActionsMenu response after Wex initialization.

## Target-Generated Models

The `wex-resolve` operation of [ConfigurationFiles.java](../../tools/ConfigurationFiles.java)
reads the installed `codebase/config/actions/navigation-actionModels.xml`,
copies its two complete menu models into the custom output, and inserts the
existing `dbcapture` actions at their declared positions. Standard actions,
third-party entries, model metadata, and both DB Capture table toolbars are
preserved. The generated models do not depend on incremental loading.

The base file is never modified. Generated target-specific XML stays in the
private plan and the installed custom/SafeArea destinations; do not publish
it as vendor source or commit it as a fixed baseline. The public non-Wex
source remains incremental. Full plans detect Wex and regenerate this profile
from the current target automatically, with the base XML and Wex archive hashes
recorded as prerequisites.

After a CPS or menu change, regenerate and review a new plan. Review other menu
contributors and repeat runtime acceptance; generation is not a blanket
future-version compatibility guarantee. Missing anchors, unexpected content
in the owned partial models, and duplicate target models stop generation.

## Menu-Only Deployment

With the target `WT_HOME` and supported Java 17 `JAVA_HOME` set, choose an
existing ACL-verified private plan directory below this repository's backups:

```powershell
$env:DBNINJA_PRIVATE_PLAN_ROOT = '<private-directory-under-backups>'
node tools/dbninja.mjs plan --wex-menus
```

Review the resulting plan and generated XML. The menu-only plan changes just
`codebase/config/actions/DbCapture-actionModels.xml` and its canonical SafeArea
copy. It preserves the existing table models from the live custom file and
fingerprints the standard navigation, Wex configuration, JAR and properties.
This initial-repair mode requires the owned partial models; use a normal full
plan for later regeneration once complete models are installed.

Only after maintenance approval and authoritative idle capture status:

```powershell
$env:DBNINJA_MAINTENANCE_APPROVED = 'yes'
$env:DBNINJA_CAPTURE_IDLE_CONFIRMED = 'yes'
node tools/dbninja.mjs apply '<reviewed-plan.json>'
node tools/dbninja.mjs verify '<reviewed-plan.json>'
```

The tool preserves a checksum-verified rollback backup and uses scoped PTC
SafeArea installation. It does not change XCONF, rebuild JavaScript, modify
database objects, or restart services. The environment flags record prior
approval and observation; they are not authorization or a capture-state probe.
Ask the owner to perform the approved restart and then verify the browser.

## Offline Checks

From the repository, with the target Java 17 and Windchill paths already set:

```powershell
node --test --test-name-pattern="Wex|XML merges preserve" tools/deployment-regression.cjs
```

Generate the preview into a new, private output path outside Windchill:

```powershell
& "$env:JAVA_HOME/bin/java.exe" tools/ConfigurationFiles.java wex-resolve customization/DbCapture/main/src_web/config/actions/DbCapture-actionModels.xml "<private-new-output.xml>" "$env:WT_HOME/codebase/config/actions/navigation-actionModels.xml"
```

The resolver checks preserve action identities and order, reject missing
anchors and unsafe changes, and exercise regeneration from a changed base.
These offline checks do not establish restarted runtime acceptance.

## Approval And Acceptance

Before installation, confirm development/test classification, the exact
reviewed deployment changes, approved maintenance/restart procedure and node
scope, and authoritative idle capture status. Do not start a capture as a
menu test or make database changes for this repair.

After the approved XML deployment and owner-performed restart, verify:

- Standard Site entries and DB Ninja coexist with Extension Manager/Center.
- Quick Links retains Help, Clipboard, My Settings and the available standard
  entries, with exactly one Ninja Trick and one Ninja Stealth action.
- The raw `servlet/ActionsMenu?actions=header%20actions` response retains the
  standard actions after Wex has initialized, not merely during PTC reload.
- DB Ninja opens; table actions and icons still work; cancelling Start does
  not mutate anything; ordinary-user restrictions remain intact.
- Live/SafeArea files, rollback receipts and future deployment configuration
  consistently use the reviewed profile.