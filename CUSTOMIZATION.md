# PTC customization alignment and CPS maintenance

## Deployment boundary

Use additive custom resources, installed PTC maintenance tools, module-owned
declarative XCONF and explicit shared-file merges. Never change OOTB artifacts.
Standard-Utilities JSP replacement, legacy Log4j overlays, destructive schema
reinstall scripts, local configuration and historical logs are not release inputs.
The only approved compiled distribution is the reviewed custom JAR and seven
matching ClassInfo files, with the prebuilt manifest described in
[PUBLICATION.md](PUBLICATION.md). Other build output remains private.

This aligns **deployment/registration** with the documented mechanisms. It does
not claim that PTC supports every custom Java/JavaScript API call, or that this
module is vendor-certified.

DB Ninja is for **development and test environments only**. The general PTC
SafeArea mechanism is also used for other production customizations; that is
not permission to deploy this diagnostic module in production.

## PTC references

1. [Safe Area directory structure (13.0)](https://support.ptc.com/help/windchill/r13.0.0.0/en/Windchill_Help_Center/customization/WCCG_Oview_ManageCust_SafeAreaandTextTailorDirectoryStructure.html)
2. [PTC script for customized files (13.0)](https://support.ptc.com/help/windchill/r13.0.0.0/en/Windchill_Help_Center/customization/WCCG_Oview_ManageCust_PTCScriptCustomizedFiles.html)
3. [Safe Area when installing a service pack (13.0)](https://support.ptc.com/help/windchill/r13.0.0.0/en/Windchill_Help_Center/customization/WCCG_Oview_ManageCust_UsingSafeAreaforInstallingServicePack.html)
4. [Custom JavaScript/CSS (13.0)](https://support.ptc.com/help/windchill/r13.0.0.0/en/Windchill_Help_Center/customization/WCCG_UICust_UITechOview_AddCustomCode_JavascriptCSSFiles.html)
5. [Requested JavaScript/CSS reference (12.0.2 cloud help)](https://support.ptc.com/help/windchill/cloud/r12.0.2.0/en/Windchill_Help_Center/WCCG_UICust_UITechOview_AddCustomCode_JavascriptCSSFiles.html)
6. [Defining actions and their resource bundles (13.0)](https://support.ptc.com/help/windchill/r13.0.0.0/en/Windchill_Help_Center/customization/WCCG_UICust_AddActionsHook_WCClientArchAction_Defining_a_new_action.html)

Prior baseline inspection covered the installed **13.0** `bin/swmaint.xml`,
`bin/jsfrag_combine.xml`, CCD scripts and `xconfmanager -h/-d`.
A 13.1 or cloud help example alone does not prove target compatibility.

## Source-to-runtime mapping

Paths in the runtime column are relative to the reviewed Windchill home.

| Source / artifact | Runtime destination / mechanism |
|---|---|
| Validated target-build or exact-baseline prebuilt JAR | `custom/lib/DbCapture.jar` |
| Seven ClassInfo files matching the selected JAR | `codebase/com/custom/dbcapture/*.ClassInfo.ser` |
| Module `src_web/config/actions/DbCapture-*.xml` | `codebase/config/actions/DbCapture-*.xml`, registered through ordered-set XCONF entries |
| Module `src_web/config/mvc/DbCapture-configs.xml` | `codebase/config/mvc/DbCapture-configs.xml`, custom Spring/MVC configuration |
| Module service declarations | `custom/DbCapture/xconf/DbCapture.service.properties.xconf` |
| [Module XCONF](deployment/DbNinja.xconf) | `custom/xconf/DbNinja.xconf`, installed by `xconfmanager -i` |
| Module `custom/DbCapture` JavaScript/CSS | `codebase/custom/DbCapture/`, HTTP-readable only for intended runtime resources |
| Two declared `custom/DbCapture/icons` PNGs | `codebase/netmarkets/images/dbcapture/`, referenced by the action bundle's `.icon` keys and retained in SafeArea |
| Generated combination of the canonical Header, CSV and menu-icon sources | `codebase/netmarkets/javascript/util/jsfrags/dbNinja.jsfrag` |
| Five custom JSP templates | `codebase/netmarkets/jsp/dbcapture/`, a module-specific route, no PTC JSP replacement |
| Custom URL-validator XML | `codebase/config/urlValidators/DbCapture-validators.xml` |
| UI-component registration template | Merged into `codebase/customroleaccessprefs.xml`; unrelated components preserved |

The existing `com.custom.dbcapture` names are historical persistent identities.
They are retained for recorded objects, associations, action resources and
serialized evidence compatibility. They do not imply PTC authorship. A namespace
change requires an explicit data-migration/compatibility project; this packaging
work does not rename stored types or ship PTC original implementation files.

### Start and Stop action icons

Ninja Trick uses the ninja (U+1F977); Ninja Stealth uses dashing away (U+1F4A8).
They are versioned **16 x 16 transparent PNGs**, not browser-font-dependent
emoji labels. The existing action resource bundle resolves their
`dbcapture/<filename>.png` paths relative to `netmarkets/images`. Standard
`start.gif`/`stop.gif`, action IDs and permission filters are not changed.
See [artwork provenance, exact paths and regeneration](deployment/icons/README.md).
The bindings are limited to `dbcapture.startDbCapture.icon` and
`dbcapture.stopDbCapture.icon` in the module's own resource bundle. No OOTB
action/resource bundle, shared image file or common CSS/JavaScript is replaced.
The installed Quick Links controller omits image fields from its dynamic menu
response. The module's own menu-icon controller therefore listens to PTC's
`dynamicMenuLoad` and `dynamicMenuShow` extension events, requires the exact
Quick Links menu instance, and updates only the `startDbCapture`/`stopDbCapture`
items through their Ext icon elements. It does not match translated labels,
replace menu items, change permissions/handlers or modify PTC source.

The Java resource must be in the selected validated JAR: use a target rebuild or
the matching qualified prebuilt candidate and a full reviewed plan.
JavaScript-only deployment or reuse of an old JAR does not apply a Java bundle
change. Prior verified icon deployment is recorded separately from pending
source/binary release acceptance in [LOCAL-INSTALL.md](LOCAL-INSTALL.md).
SVG render sources and font software are not copied to the web root.

### SafeArea

- `wtSafeArea/siteMod`: canonical site-customized files for the approved target
  (development/test only for DB Ninja).
- `wtSafeArea/ptcCurrent`: newer PTC versions supplied by a maintenance installer
  when corresponding site-modified files exist.
- `wtSafeArea/ptcOrig`: the genuine PTC original saved **before its first edit**.
  Do not label an already-customized live file as a PTC original.

This package adds custom files and merges customer-owned extension files; it
does not modify a stock PTC JSP or base stylesheet. Therefore an empty `ptcOrig`
is legitimate. Private deployment backups are distinct from `ptcOrig`.

The installer calls `swmaint.xml createSafeArea`, preserves the reviewed files in
the canonical `siteMod`, and invokes `listSiteChanges`, `listSiteChangesIgnored`
and `installSiteChanges` using a reviewed subset as `wtSafeArea.siteMod.dir`.
That directory property is present in the inspected PTC script. Scoping avoids
reinstalling unrelated or stale customizations already in the site's SafeArea.
The PTC script itself is never edited or redistributed.

The JavaScript-only plan further limits deployment to Header, CSV, menu icons and the custom
fragment, while checking that other runtime files/configuration have not changed.
It is appropriate for a verified client-only change, not a replacement for full
CPS qualification. Plans reject extra/unreviewed files in the staging tree.

**Do not stage** `site.xconf`, `declarations.xconf`, generated properties,
generated `main.js`/`windchill-all*.js`, logs, vaults or private evidence in SafeArea.
Some are expressly excluded by `swmaint`; others would pin an obsolete PTC base
after an update. Back them up privately, use their native tools and regenerate them.

### JavaScript

The PTC guidance explicitly prescribes a new, uniquely named fragment under
`netmarkets/javascript/util/jsfrags`, followed by `jsfrag_combine.xml`.
The installer generates `dbNinja.jsfrag` from the canonical sources named in
[assets.json](deployment/assets.json), then invokes the installed combine and
compress targets. The product bundles are **outputs**, never edited inputs.

The fragment initializes once after shell loading, including when the header
rendered before the load event. It avoids duplicate controllers during migration
from legacy `netmarkets.presentation.jsFiles` registrations. Those legacy DB Ninja
registrations are removed via xconfmanager; unrelated plugins are retained.

CSV is a shell controller, not an inert external script tag in an Ajax-loaded JSP.
The diagnostics controller remains a versioned custom asset for the full diagnostic
page. No standard PTC JavaScript file is copied into the source package or patched.

### CSS, HTML and JSP

Custom CSS lives under `custom/DbCapture` and is registered with
`xconfmanager --add netmarkets.presentation.cssFiles=...` and propagation.
The property is an ordered set; preserve the site's other values and load order.
The current generated target is `codebase/presentation.properties`.
Rules use DB Ninja-specific classes/IDs rather than changing global PTC theme rules.

There is no stock static HTML replacement. Module HTML is rendered by the five
custom JSPs at their existing module-specific routes. The optional standard
Utilities JSP overlay was removed from active distribution; navigation is
registered with **incremental** custom action models instead.

Do not place backups, `.java`, scripts, configuration secrets or SQL evidence in
the web root. The deployment manifest copies only named runtime assets/templates.

### XCONF and shared registrations

The public CCD `custom.site.xconf` is only an empty compilation skeleton. Do not
deploy it over a site's shared file. The standalone installer registers the
module-owned declaration in `declarations.xconf` using **xconfmanager**.
Declarative scalar properties use `default`, not site-only `value` attributes.

For an existing direct-copy installation, an XML-aware merge removes only the
recognized DB Ninja declarations/references from shared custom files. Site limits
and exclusions are preserved as site overrides. Conflicting service slots,
scalar shared-list overrides or unexpected role structure stop the operation.
No shared file is replaced with a sample from another environment.

`wt.properties`, `presentation.properties` and `service.properties` are generated
targets. They are backed up, but normal configuration changes are recorded through
XCONF and require successful propagation and validation.

## After a CPS/service pack/major upgrade

1. Read that release's supported customization and maintenance procedure.
   Preserve private backups and record the current JAR/source version.
2. Use the applicable maintenance-installer SafeArea/site-modified-files workflow.
   Where offered, load updated site-modified PTC files into `ptcCurrent` for review.
3. Compare the genuine original, new PTC version and site version when a stock
   file is modified. For shared custom XML, merge current contributions from all
   modules; never replay an old aggregate file blindly.
4. Rebuild with target CCD and the updated target SDK/JDK. A changed model or CPS
   requires a reviewed new `deployment/generated-model-baseline.json`; do not
   reuse locked generated entries merely because runtime Java recompiles.
   Recheck persisted model and DTO compatibility, JSP compilation and native
   profiler API signatures.
5. Generate a **new** plan. Old plans include PTC-tool and target fingerprints
   and are not intended to cross a CPS change.
6. Reapply the reviewed custom fragment/resources with PTC tools and rebuild
   JavaScript from the **new PTC** fragments. Do not restore an old combined bundle.
7. Restart the approved nodes, hard-reload clients and repeat both disk and
   runtime acceptance. Test ordinary-user authorization as well as administrator UX.

SafeArea helps preserve/reconcile custom files. It is **not a guarantee against
all overwrite mechanisms, incompatible APIs, future CCD deploys or administrator
mistakes**. Reconciliation and regression testing remain mandatory.
