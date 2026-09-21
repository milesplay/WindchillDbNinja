# DB Ninja action icons

| Action | Unicode artwork | Runtime resource |
|---|---|---|
| Ninja Trick / `startDbCapture` | U+1F977, ninja | `dbcapture/dbNinjaTrick-v20260921.png` |
| Ninja Stealth / `stopDbCapture` | U+1F4A8, dashing away | `dbcapture/dbNinjaStealth-v20260921.png` |

The runtime images are **16 x 16, 8-bit RGBA, transparent, non-interlaced PNG**.
This matches the installed Windchill 13.0 start/stop action-icon dimensions and
uses its existing action-resource `.icon` mechanism. It is not an emoji string
in the label or a replacement for any standard PTC image or framework source.
Labels, tooltips, action IDs, confirmations and permission filters are unchanged.
The two image-resource entries use `@RBPseudo(false)` so pseudo-localization
does not alter their filenames.
Only `dbcapture.startDbCapture.icon` and `dbcapture.stopDbCapture.icon` use this
artwork. No OOTB resource bundle, shared image path, global CSS/JavaScript or
other product action is redirected to these PNGs.

The live 13.0 Quick Links response omits image fields even when an action bundle
defines them. The module-owned `dbNinjaActionIcons` controller is included in
the custom jsfrag and uses PTC's documented-in-source `dynamicMenuLoad` and
`dynamicMenuShow` events. It requires the actual `quickLinksMenu` instance and
the two exact Ninja `actionName` values. It sets only those items' image
configuration and existing Ext icon elements; it never replaces the menu,
matches labels, changes disabled state/handlers or alters OOTB menu items.
This binding is required for the icons to be visible in Quick Links; a resource
bundle/standalone image-renderer test alone is not sufficient acceptance.

The `.icon` values are relative to `codebase/netmarkets/images`. The reviewed
deployment plan maps the two PNGs from the module's permitted CCD web-source
folder into `codebase/netmarkets/images/dbcapture`, including canonical SafeArea
copies. Only the PNGs are deployed; these SVG render sources and this document
are not web runtime files.

## Provenance and reproduction

The SVGs contain the requested Unicode characters as numeric character
references, not embedded third-party font data or copied PTC artwork.
The PNGs were rendered locally using:

- Noto Color Emoji, installed font package version `20211102`.
- Font SHA-256:
  `bf2a8506b80614ba190a34c7b037af1269a7d614fe9f3b613cc15cdeec6f814b`.
- librsvg `rsvg-convert` 2.50.7, at 16 x 16 pixels.

Noto Color Emoji is supplied under SIL Open Font License 1.1. No font files are
distributed by this package. These are rendered graphics; the OFL's requirement
for font software to retain its license does not impose that license on graphic
output. See the [OFL FAQ](https://openfontlicense.org/ofl-faq/). This attribution
does not grant rights to the PTC SDK. The project's separate
[MIT License](../../LICENSE) covers its custom source and approved artifacts.

From the repository root, with the reviewed local font and renderer available:

```sh
rsvg-convert --width 16 --height 16 --format png \
  --output customization/DbCapture/main/src_web/custom/DbCapture/icons/dbNinjaTrick-v20260921.png \
  deployment/icons/dbNinjaTrick-v20260921.svg
rsvg-convert --width 16 --height 16 --format png \
  --output customization/DbCapture/main/src_web/custom/DbCapture/icons/dbNinjaStealth-v20260921.png \
  deployment/icons/dbNinjaStealth-v20260921.svg
```

Rendering tools/fonts are **not installation or browser prerequisites**: the
reviewed PNGs are already included. Different renderer/font versions may change
pixels or checksums; review the image before changing its version and hash in
[assets.json](../assets.json).

The publication gate accepts only these two declared PNG paths with their
reviewed hashes, expected dimensions/color format, PNG CRCs, bounded pixel
payload, transparent corners and permitted chunks. The separately qualified
module JAR and seven matching ClassInfo files are the only other binary
publication exceptions. Arbitrary images, font files, extra image metadata,
vendor libraries and private operational data remain excluded.

## Verification and deployment boundary

```text
node --test tools/menu-icon-regression.cjs tools/action-icon-regression.cjs tools/deployment-regression.cjs tools/header-ux-regression.cjs
node tools/validate.mjs icons
node tools/check-publication.mjs
```

The `icons` group needs the target SDK/JDK (`WT_HOME` and `JAVA_HOME`) but not a
prebuilt DB Ninja JAR. It compiles the current resource class with `-proc:none`,
decodes the PNGs using JDK ImageIO, and draws them through the installed PTC icon
renderer. It does not write the live installation or connect to a database.

The resource bundle is Java: a **target rebuild and full reviewed deployment**
are required for the live application. Do not use `--reuse-installed` or
`--javascript-only` to hide this Java-resource change, overwrite PTC
`start.gif`/`stop.gif`, or hot-copy a class into the server. Follow the approved
restart/cache procedure after the known release blockers are resolved.

Icon-only verification is not a complete source/binary release qualification.
Windows remains unsupported as shipped; the first binary profile is Linux-only.
The explicitly authorized prior local icon deployment is recorded in
[the installation record](../../LOCAL-INSTALL.md#runtime-icon-deployment-2026-09-21).
