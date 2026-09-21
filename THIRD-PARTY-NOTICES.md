# Third-party notices

DB Ninja's custom source and approved custom compiled artifacts are distributed
under the [MIT License](LICENSE), copyright 2026 milesplay. The license does not
grant rights to separately licensed dependencies or imply vendor endorsement.

## PTC and Oracle prerequisites

Windchill, its SDK/tools and Oracle remain separately licensed prerequisites.
No PTC/Oracle libraries, original PTC JSPs, generated PTC JavaScript bundles or
vendor font files are included in the public package. Build and qualify only
with an authorized target SDK. Review JAR contents and serialized metadata
before publication; compilation against an SDK is not permission to redistribute
that SDK.

The historical `com.ptc.dbcapture` package names identify custom persistent
types and are preserved for compatibility. They do not imply PTC authorship,
support or certification. Product names identify compatibility prerequisites;
no endorsement by their owners is claimed.

## Action-icon graphics

The two custom 16 x 16 PNGs represent Unicode U+1F977 (ninja) and U+1F4A8
(dashing away). They were rendered locally from Noto Color Emoji using librsvg;
they are not copied PTC artwork. Exact source filenames, font/renderer versions,
rendering commands and the font fingerprint are in the
[icon provenance record](deployment/icons/README.md).

Noto Color Emoji is supplied under the SIL Open Font License 1.1. No font
software is redistributed. The OFL does not require graphic output to adopt
the font software's license; see the [OFL FAQ](https://openfontlicense.org/ofl-faq/).
The SVG render sources contain character references, not embedded font data.
The renderer and font are not installation or browser prerequisites.

Only these two reviewed PNGs and the explicitly approved custom JAR/ClassInfo
are binary publication exceptions. This notice does not authorize additional
images, fonts, vendor classes or private operational data.
