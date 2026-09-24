const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const {test} = require('node:test');

const root = path.resolve(__dirname, '..');
const read = name => fs.readFileSync(path.join(root, name), 'utf8');
const prose = name => read(name).replace(/\*\*/g, '').replace(/\s+/g, ' ');
const englishDocs = [
  'README.md', 'INSTALL.md', 'AGENTS.md', 'COMPATIBILITY.md', 'CUSTOMIZATION.md',
  'OPERATIONS.md', 'HANDOFF.md', 'PUBLICATION.md', 'LOCAL-INSTALL.md',
  'USE-CASES.md', 'THIRD-PARTY-NOTICES.md', 'CHANGELOG.md', 'sql/oracle/README.md', 'DATABASE-SETUP.md',
  'WINDOWS-PORTING.md',
];
const packageDocs = ['README.md', 'INSTALL.md', 'COMPATIBILITY.md', 'AGENTS.md', 'HANDOFF.md', 'PUBLICATION.md'];

for (const name of englishDocs) {
  test(`${name}: public entry is English and does not depend on excluded translations`, () => {
    const text = read(name);
    assert.match(text, /^# /);
    assert.doesNotMatch(text, /[\p{Script=Han}\p{Script=Hiragana}\p{Script=Katakana}\p{Script=Hangul}]/u);
    assert.doesNotMatch(text, /(?:-ja|\.ja|_ja)\.md\b/i);
    assert.doesNotMatch(text, /```powershell/i);
    assert.doesNotMatch(text, /(?:^|[\s`(])\/(?:ptc|home|root|opt|var)\/[^\s`)]*/m);
  });

  test(`${name}: relative publication links resolve without local-only documents`, () => {
    for (const [, href] of read(name).matchAll(/!?\[[^\]]*\]\(([^)\s]+)(?:\s+"[^"]*")?\)/g)) {
      if (/^(?:[a-z][a-z0-9+.-]*:|\/\/)/i.test(href)) continue;
      const file = decodeURIComponent(href.split(/[?#]/, 1)[0]);
      if (!file) continue;
      const destination = path.resolve(root, path.dirname(name), file);
      const relative = path.relative(root, destination);
      assert.ok(!path.isAbsolute(relative) && relative !== '..' && !relative.startsWith(`..${path.sep}`), href);
      assert.ok(fs.existsSync(destination), `${name}: missing link ${href}`);
      assert.doesNotMatch(relative, /^(?:backups|build)(?:\/|\\)/);
      assert.doesNotMatch(relative, /(?:-ja|\.ja|_ja)\.md$/i);
    }
  });
}

for (const name of ['README.md', 'INSTALL.md', 'AGENTS.md', 'COMPATIBILITY.md', 'OPERATIONS.md', 'LOCAL-INSTALL.md', 'PUBLICATION.md', 'DATABASE-SETUP.md']) {
  test(`${name}: deployment guidance explicitly prohibits production use`, () => {
    const text = prose(name);
    assert.match(text, /development and test environments only/i);
    assert.match(text, /do not install or run DB Ninja in production/i);
    assert.match(text, /unknown (?:environment )?classification|classification is unknown/i);
  });
}

for (const name of packageDocs) {
  test(`${name}: records the exact Windows binary baseline and explicit candidate selection`, () => {
    const text = prose(name);
    assert.match(text, /Windchill Services12\.1\.2\.23 build38 \(12\.1\.2\.0 CPS23\)/);
    assert.match(text, /Corretto 11\.0\.19/);
    assert.match(text, /Oracle 19c/);
    assert.match(text, /Windows x64/);
    assert.match(text, /traditional.{0,30}`codebase`/i);
    assert.match(text, /(?:local )?NTFS|NTFS volume/i);
    assert.match(text, /(?:other|different).{0,150}(?:CPS|SDK).{0,150}rebuild/i);
    assert.match(text, /plan --prebuilt/);
    assert.match(text, /default.{0,100}(?:plan|target[- ]build|target-built)/i);
    assert.ok(text.includes('prebuilt/DbCapture.jar'));
    assert.ok(text.includes('prebuilt/metadata/com/custom/dbcapture/*.ClassInfo.ser'));
    assert.ok(text.includes('prebuilt/manifest.json'));
    assert.match(text, /seven/);
    assert.match(text, /checksums/);
    assert.match(text, /source fingerprints/);
    assert.match(text, /target version\/SDK fingerprints/);
  });
}

test('SafeArea guidance does not turn a standard PTC mechanism into production approval', () => {
  const text = prose('CUSTOMIZATION.md');
  assert.match(text, /not permission to deploy this diagnostic module in production/i);
  assert.match(text, /Never change OOTB artifacts/);
  assert.match(text, /reviewed subset/);
  assert.match(text, /xconfmanager/);
  assert.match(text, /combine and compress/);
});

test('icons use the module Java resource, two PNGs and Ninja-only dynamic-menu binding', () => {
  const text = prose('CUSTOMIZATION.md');
  assert.match(text, /16 x 16 transparent PNGs/);
  assert.match(text, /module's own resource bundle/);
  assert.match(text, /dynamicMenuLoad/);
  assert.match(text, /dynamicMenuShow/);
  assert.match(text, /only the `startDbCapture`\/`stopDbCapture` items/);
  assert.match(text, /Java resource must be in the selected validated JAR/);
  assert.match(text, /JavaScript-only deployment or reuse of an old JAR does not apply a Java bundle change/);
});

test('Oracle guidance distinguishes both historical query paths and the monitoring-flush privilege', () => {
  const text = prose('COMPATIBILITY.md');
  assert.match(text, /both collector paths.*Oracle 19c/i);
  assert.match(text, /SNAPSHOT.*same Flashback Query object access and usable undo/i);
  assert.match(text, /FLASHBACK.*READ.*SELECT/);
  assert.match(text, /FLUSH_DATABASE_MONITORING_INFO.*requires `ANALYZE ANY`/);
  assert.match(text, /Per-table Flashback grants do not replace that requirement/);
  assert.match(text, /oracle-database\/19\/adfns\/flashback\.html#/);
  assert.match(text, /oracle-database\/19\/arpls\/DBMS_STATS\.html#/);
});

test('preflight and apply separate read-only inspection from prior authorization', () => {
  const text = prose('INSTALL.md');
  assert.match(text, /Preflight is read-only/);
  assert.match(text, /prior maintenance and Oracle approval/);
  assert.match(text, /DBNINJA_MAINTENANCE_APPROVED=yes/);
  assert.match(text, /DBNINJA_ORACLE_CONFIRMED=yes/);
  assert.match(text, /scripts never restart Windchill or execute SQL/);
  assert.match(text, /flags record a decision already made/);
  assert.match(text, /bundled SQL is profile-specific, not portable pre-generated SQL/);
  assert.match(text, /Fresh installation only: initialize the schema/);
  assert.match(text, /node tools\/schema-package\.mjs verify --target/);
  assert.match(text, /Have the DBA review/);
});

test('schema and release handoff exposes the missing DDL without implying automatic Oracle execution', () => {
  const schema = prose('sql/oracle/README.md');
  assert.match(schema, /4 tables, 4 primary keys, 14 secondary indexes and 4 table comments/);
  assert.match(schema, /18 indexes in total/);
  assert.match(schema, /wt\.db\.maxBytesPerChar/);
  assert.match(schema, /VARCHAR2\(n BYTE\)/);
  assert.match(schema, /INDX/);
  assert.match(schema, /does not connect to Oracle/);
  assert.match(schema, /not evidence that this exact combined script was executed/);
  assert.match(schema, /Do not execute the eight input files separately/);
  assert.match(prose('README.md'), /sql\/oracle\/create-db-ninja\.sql/);
  assert.match(prose('README.md'), /releases\/tag\/v0\.1\.1/);
  assert.match(prose('PUBLICATION.md'), /GitHub Packages is intentionally not required/);
  assert.match(prose('PUBLICATION.md'), /git archive/);
  assert.match(prose('PUBLICATION.md'), /SHA256SUMS/);
  assert.match(prose('CHANGELOG.md'), /runtime redeployment or restart/);
});

test('AI database setup explicitly covers approved grants, schema-owner verification and CREATE acceptance', () => {
  for (const name of ['README.md', 'INSTALL.md', 'AGENTS.md', 'COMPATIBILITY.md', 'sql/oracle/README.md']) {
    assert.match(read(name), /DATABASE-SETUP\.md/);
  }
  const text = prose('DATABASE-SETUP.md');
  assert.match(text, /Windchill-schema privilege grants, DDL table creation and post-change verification/);
  assert.match(text, /explicit approval for those exact statements and a secure authorized grantor connection/);
  assert.match(text, /GRANT CREATE TABLE TO <WINDCHILL_SCHEMA>/);
  assert.match(text, /GRANT EXECUTE ON SYS\.DBMS_FLASHBACK/);
  assert.match(text, /GRANT ANALYZE ANY/);
  assert.match(text, /Do not grant `FLASHBACK ANY TABLE` by default/);
  assert.match(text, /new Windchill schema-owner session/);
  assert.match(text, /AS OF SCN <START_SCN>/);
  assert.match(text, /VERSIONS BETWEEN SCN <START_SCN> AND <END_SCN>/);
  assert.match(text, /monitoring flush/);
  assert.match(text, /After the DBA explicitly approves table creation, the AI may execute/);
  assert.match(text, /4 module tables.*4 enabled\/validated primary keys.*18 valid indexes/);
  assert.match(text, /Do not request that passwords be pasted into chat/);
  assert.match(text, /Existing or partially installed DB Ninja schemas do not run the CREATE script/);
});

test('public Oracle privilege template is non-executing and read-only probes do not flush or mutate', () => {
  const template = read('sql/oracle-prerequisites.template.sql');
  assert.match(template, /-- GRANT CREATE TABLE TO <WINDCHILL_SCHEMA>/);
  assert.match(template, /-- GRANT EXECUTE ON SYS\.DBMS_STATS/);
  assert.match(template, /-- ALTER USER <WINDCHILL_SCHEMA> QUOTA/);
  assert.doesNotMatch(template.replace(/^--.*$/gm, ''), /^\s*(GRANT|REVOKE|ALTER|CREATE|BEGIN|EXECUTE)\b/im);
  const checks = read('sql/oracle-check.sql').replace(/^--.*$/gm, '');
  assert.match(checks, /CURRENT_SCHEMA/);
  assert.match(checks, /FROM session_privs/);
  assert.match(checks, /FROM user_ts_quotas/);
  assert.doesNotMatch(checks, /^\s*(GRANT|REVOKE|ALTER|CREATE|BEGIN|INSERT|UPDATE|DELETE|TRUNCATE|DROP)\b/im);
  assert.doesNotMatch(checks, /FLUSH_DATABASE_MONITORING_INFO/);
});

test('prebuilt verification and validation do not silently replace the target-build default', () => {
  const text = prose('INSTALL.md');
  assert.match(text, /node tools\/prebuilt\.mjs verify/);
  assert.match(text, /node tools\/validate\.mjs all --prebuilt/);
  assert.match(text, /node tools\/dbninja\.mjs plan --prebuilt/);
  assert.match(text, /default uses the target-built candidate/i);
  assert.match(text, /Stop on missing artifacts, checksum\/source drift or a target fingerprint mismatch/);
  assert.match(text, /not a manifest edit to suppress a mismatch/);
});

for (const name of [...packageDocs, 'LOCAL-INSTALL.md']) {
  test(`${name}: distinguishes current-source prebuilt assembly from new CCD generation`, () => {
    const text = prose(name);
    assert.match(text, /compiles? all current runtime Java|all current runtime Java is compiled/i);
    assert.match(text, /current-source compilation with unchanged generated model artifacts, not a new CCD or annotation-processing run/i);
    assert.match(text, /no (?:live metadata writes|schema\/annotation changes or live metadata writes)|does not.{0,160}write live metadata/i);
    assert.match(text, /changed model or CPS requires target CCD(?:,| and) a reviewed new baseline/i);
  });
}

test('maintainer provenance identifies only the locked custom generated entries and unchanged model sources', () => {
  const text = prose('HANDOFF.md');
  assert.match(text, /tools\/build-prebuilt\.mjs/);
  assert.match(text, /--release 17 -proc:none/);
  assert.match(text, /fresh module-only JAR, not an overlay retaining stale implementation classes/);
  assert.match(text, /15 fingerprint-locked custom generated JAR entries/);
  assert.match(text, /Model base classes \| 7/);
  assert.match(text, /Association classes \| 3/);
  assert.match(text, /English `RB\.ser` resources \| 4/);
  assert.match(text, /Listener list \| 1/);
  assert.match(text, /seven matching ClassInfo files come from that same verified CCD generation/i);
  assert.match(text, /four model Java definitions and `\.rbInfo` source are byte-identical/i);
  assert.match(text, /deployment\/generated-model-baseline\.json/);
  assert.match(text, /source, generated-entry, metadata and SDK fingerprints/);
});

test('AI handoff rejects unclassified targets and does not equate a URL with authorization', () => {
  assert.match(prose('AGENTS.md'), /Stop for production or unknown environment classification/);
  assert.match(prose('AGENTS.md'), /Do not report skipped, simulated or historical scenarios as newly passed live tests/);
  for (const name of ['README.md', 'AGENTS.md', 'PUBLICATION.md']) {
    const text = prose(name);
    assert.ok(text.includes('https://github.com/milesplay/WindchillDbNinja'));
    assert.match(text, /(?:URL|repository)[^.]*package and instructions[^.]*not server credentials, DBA approval or maintenance authorization/i);
  }
});

test('publication authorizes MIT custom artifacts but excludes vendor and operational data', () => {
  const text = prose('PUBLICATION.md');
  assert.match(text, /owner has authorized publication of custom source plus the custom compiled JAR\/ClassInfo package/i);
  assert.match(text, /MIT License/);
  assert.match(text, /Copyright 2026 milesplay/i);
  assert.match(text, /Do not include PTC\/Oracle libraries/);
  assert.match(text, /generated PTC JavaScript bundles/);
  assert.match(text, /portable pre-generated SQL/);
  assert.match(text, /staged bytes and history/);
  assert.match(text, /Non-English local documents remain local and are excluded/);
  assert.match(text, /does not prove public-link-only end-to-end deployment/);
  assert.match(text, /node tools\/check-publication\.mjs --require-prebuilt --export/);
  assert.match(text, /source-only working-tree checks\. Such a check is not binary release acceptance/);
  assert.match(text, /PUBLICATION_EXPORT=/);
  assert.doesNotMatch(text, /SOURCE_ONLY_EXPORT/);
  assert.doesNotMatch(text, /current public export is source-only|binary distribution approval.*required before changing that policy/i);
  const license = read('LICENSE');
  assert.match(license, /^MIT License\r?\n/);
  assert.match(license, /Copyright \(c\) 2026 milesplay/);
  assert.match(license, /Permission is hereby granted, free of charge/);
  assert.match(license, /THE SOFTWARE IS PROVIDED "AS IS"/);
});

test('Windows qualification requires the reviewed NTFS evidence policy', () => {
  const text = prose('COMPATIBILITY.md');
  assert.match(text, /Windows x64 on a local NTFS volume is the qualified platform/);
  assert.match(text, /owner.*system\/administrator identities/i);
  assert.match(text, /symbolic links and reparse-point/);
  assert.match(text, /Network shares and non-NTFS filesystems are rejected/);
  assert.match(text, /locked-file deletion path.*native Java 11 regression/i);
  assert.match(prose('AGENTS.md'), /Stop for.*non-Windows\/non-NTFS evidence storage/);
});

for (const name of ['README.md', 'AGENTS.md', 'OPERATIONS.md', 'HANDOFF.md', 'PUBLICATION.md', 'USE-CASES.md']) {
  test(`${name}: preserves the agreed strict endpoint NET contract`, () => {
    const text = prose(name);
    assert.match(text, /strict endpoint NET/i);
    assert.match(text, /insert(?:[- ]then[- ])delete.{0,100}net-zero/i);
    assert.match(text, /A -> B -> delete/);
    assert.match(text, /old value (?:`)?A/);
  });
}

test('qualification record separates prior icon deployment from new source and binary evidence', () => {
  const text = prose('LOCAL-INSTALL.md');
  assert.match(text, /Runtime icon deployment: 2026-09-21/);
  assert.match(text, /Prior verified scope, not acceptance of the new source\/binary fixes/);
  assert.match(text, /revision\/artifact identity, commands, exit statuses, counts and skips/);
  assert.match(text, /If a scenario is not executed, keep that fact and its release impact explicit/);
  assert.match(text, /Mocked JDBC tests do not establish real Oracle/);
});

test('operating guidance keeps undo, LOB, workload, privacy and single-node limits explicit', () => {
  const text = prose('OPERATIONS.md');
  assert.match(text, /LOB auditing/);
  assert.match(text, /not database-work budgets/);
  assert.match(text, /SNAPSHOT.*Oracle undo/);
  assert.match(text, /starting MethodServer/);
  assert.match(text, /Private runtime evidence/);
  assert.match(text, /must never become a web asset or a public source artifact/);
  assert.match(text, /pre-execution messages alone do not prove successful execution, commit/);
  assert.match(prose('README.md'), /not a backup, restore tool or complete audit log/);
});

test('community guidance names the repository owner and an optional private disclosure route', () => {
  const text = prose('PUBLICATION.md');
  assert.match(text, /GitHub repository owner, \[milesplay\]/);
  assert.match(text, /private GitHub Security Advisories, if enabled/);
  assert.match(text, /Never post credentials, SQL\/bind values/);
  assert.match(text, /no email address is asserted/);
  for (const name of englishDocs) assert.doesNotMatch(read(name), /mailto:/i);
  assert.match(prose('THIRD-PARTY-NOTICES.md'), /SIL Open Font License 1\.1/);
  assert.match(prose('THIRD-PARTY-NOTICES.md'), /No font software is redistributed/);
});
