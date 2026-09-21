const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const crypto = require('node:crypto');
const {spawnSync} = require('node:child_process');
const test = require('node:test');

const root = path.resolve(__dirname, '..');
const assets = JSON.parse(fs.readFileSync(path.join(root, 'deployment/assets.json'), 'utf8'));
const gitAvailable = spawnSync('git', ['--version']).status === 0;
const posixOnly = process.platform === 'win32' && 'POSIX fixture; real Windows qualification is separate.';
const documents = ['README.md', 'INSTALL.md', 'HANDOFF.md', 'AGENTS.md',
  'COMPATIBILITY.md', 'CUSTOMIZATION.md', 'OPERATIONS.md', 'PUBLICATION.md',
  'LOCAL-INSTALL.md', 'USE-CASES.md', 'CHANGELOG.md', 'DATABASE-SETUP.md'];

function fixture(t) {
  const parent = path.join(root, 'build');
  fs.mkdirSync(parent, {recursive: true});
  const directory = fs.mkdtempSync(path.join(parent, 'release-audit-fixture-'));
  t.after(() => fs.rmSync(directory, {recursive: true, force: true}));
  return directory;
}

function put(directory, relative, content) {
  const output = path.join(directory, relative);
  fs.mkdirSync(path.dirname(output), {recursive: true});
  fs.writeFileSync(output, content);
  return output;
}

function copyTool(directory, name) {
  put(directory, `tools/${name}`, fs.readFileSync(path.join(__dirname, name)));
  if (name === 'dbninja.mjs') {
    for (const dependency of ['icon-assets.mjs', 'filesystem.mjs', 'prebuilt.mjs', 'module-archive.mjs']) {
      put(directory, `tools/${dependency}`, fs.readFileSync(path.join(__dirname, dependency)));
    }
  }
}

function publicationFixture(t) {
  const directory = fixture(t);
  const source = path.join(directory, 'source');
  for (const name of ['.gitignore', '.gitattributes', 'LICENSE', ...documents]) put(source, name, '# Audit fixture\n');
  put(source, 'package.json', '{}\n');
  for (const relative of ['customization/DbCapture', 'customization/configurations', 'sql']) {
    fs.mkdirSync(path.join(source, relative), {recursive: true});
  }
  put(source, 'deployment/assets.json', fs.readFileSync(path.join(root, 'deployment/assets.json')));
  copyTool(source, 'dbninja.mjs');
  copyTool(source, 'check-publication.mjs');
  copyTool(source, 'create-schema.mjs');
  copyTool(source, 'schema-package.mjs');
  for (const relative of ['sql/oracle', 'sql/oracle-check.sql', 'sql/oracle-prerequisites.template.sql',
    'deployment/generated-model-baseline.json']) {
    fs.cpSync(path.join(root, relative), path.join(source, relative), {recursive: true});
  }
  const baseline = JSON.parse(fs.readFileSync(path.join(root, 'deployment/generated-model-baseline.json'), 'utf8'));
  for (const relative of Object.keys(baseline.generatedInputSources)) {
    put(source, relative, fs.readFileSync(path.join(root, relative)));
  }
  for (const icon of Object.values(assets.actionIcons)) {
    for (const relative of [
      `customization/DbCapture/main/src_web/custom/DbCapture/icons/${icon.file}`,
      `deployment/icons/${icon.file.replace(/\.png$/, '.svg')}`
    ]) put(source, relative, fs.readFileSync(path.join(root, relative)));
  }
  return {directory, source};
}

function publication(source, extraEnvironment = {}) {
  const env = {...process.env};
  delete env.DBNINJA_PRIVATE_PATTERNS;
  return spawnSync(process.execPath, [path.join(source, 'tools/check-publication.mjs')], {
    cwd: source, env: {...env, ...extraEnvironment}, encoding: 'utf8'
  });
}

function git(source, args) {
  const result = spawnSync('git', ['-C', source, ...args], {encoding: 'utf8'});
  assert.equal(result.status, 0, result.stderr);
}

test('publication guards reject known tokens, binary payloads, extra roots and private patterns', t => {
  for (const [relative, content, message] of [
    ['README.md', 'ghp_' + 'x'.repeat(40), /Possible local identifier\/secret/],
    ['deployment/module.jar', 'synthetic, not a compiled artifact', /Unapproved file type|binary/],
    ['deployment/unreviewed.png', 'synthetic unapproved image', /Unapproved file type/],
    [`customization/DbCapture/main/src_web/custom/DbCapture/icons/${assets.actionIcons.startDbCapture.file}`,
      Buffer.from('synthetic invalid PNG'), /Invalid action icon/],
    ['tools/fixture.java', Buffer.from([65, 0, 66]), /Binary content/],
    ['tools/fixture.java', Buffer.from([0xff]), /Non-UTF-8/],
    ['unclassified.md', '# Not allowed', /Unclassified root/]
  ]) {
    const {source} = publicationFixture(t);
    put(source, relative, content);
    const result = publication(source);
    assert.notEqual(result.status, 0);
    assert.match(result.stderr, message);
    assert.ok(!result.stderr.includes('ghp_' + 'x'.repeat(40)));
  }
  const {directory, source} = publicationFixture(t);
  put(source, 'README.md', 'customer-audit-fixture-only');
  const patterns = put(directory, 'private-patterns.json', JSON.stringify(['customer-audit-fixture-only']));
  const result = publication(source, {DBNINJA_PRIVATE_PATTERNS: patterns});
  assert.notEqual(result.status, 0);
  assert.match(result.stderr, /Possible local identifier\/secret/);
  assert.ok(!result.stderr.includes('customer-audit-fixture-only'));
});

test('publication source manifest excludes generated and archived fixture artifacts', t => {
  const {source} = publicationFixture(t);
  put(source, 'build/generated.jar', 'synthetic fixture');
  put(source, 'backups/private-fixture.txt', 'synthetic fixture');
  const result = publication(source);
  assert.equal(result.status, 0, result.stderr);
  const manifest = fs.readFileSync(path.join(source, 'build/public-files.txt'), 'utf8');
  assert.doesNotMatch(manifest, /(?:^|\n)(build|backups)\//);
  assert.doesNotMatch(manifest, /\.(jar|class|ser)(?:\n|$)/);
  assert.match(result.stdout, /No .git entry/);
});

test('publication rejects a symlink within an included tree', {skip: posixOnly}, t => {
  const {source} = publicationFixture(t);
  fs.symlinkSync(path.join(source, 'README.md'), path.join(source, 'tools/linked.md'));
  const result = publication(source);
  assert.notEqual(result.status, 0);
  assert.match(result.stderr, /Do not publish symlinks/);
});

test('publication rejects a symlinked customization ancestor', {skip: posixOnly}, t => {
  const {directory, source} = publicationFixture(t);
  const outside = path.join(directory, 'outside-customization');
  fs.renameSync(path.join(source, 'customization'), outside);
  fs.symlinkSync(outside, path.join(source, 'customization'));
  const result = publication(source);
  assert.notEqual(result.status, 0, 'The source-only gate accepted a symlinked ancestor.');
});

test('publication rejects excluded paths already in a Git index', {
  skip: !gitAvailable && 'Git is not installed; no real index check was executed.'
}, t => {
  const {source} = publicationFixture(t);
  git(source, ['init', '--quiet']);
  put(source, 'build/private-fixture.txt', 'synthetic fixture');
  git(source, ['add', '-f', 'build/private-fixture.txt']);
  const result = publication(source);
  assert.notEqual(result.status, 0);
  assert.match(result.stderr, /Git already tracks an excluded\/unclassified file/);
});

test('publication scans staged content, not only sanitized working-tree content', {
  skip: !gitAvailable && 'Git is not installed; the staged-blob scenario remains unexecuted.',
}, t => {
  const {source} = publicationFixture(t);
  git(source, ['init', '--quiet']);
  put(source, 'README.md', 'ghp_' + 'x'.repeat(40));
  git(source, ['add', 'README.md']);
  put(source, 'README.md', '# Sanitized working tree, unchanged staged blob\n');
  const result = publication(source);
  assert.notEqual(result.status, 0, 'The staged synthetic token was not scanned.');
});

test('publication refuses a complete working tree with DDL omitted from the actual Git index', {
  skip: !gitAvailable && 'Git is not installed; no actual index-completeness check was executed.'
}, t => {
  const {source} = publicationFixture(t);
  git(source, ['init', '--quiet']);
  git(source, ['add', '.']);
  assert.equal(publication(source).status, 0);
  git(source, ['rm', '--cached', '--', 'sql/oracle/create-db-ninja.sql']);
  const result = publication(source);
  assert.notEqual(result.status, 0);
  assert.match(result.stderr, /Git index omits publication files: sql\/oracle\/create-db-ninja.sql/);
});

test('publication requires the schema bundle even for a source-only checkout', t => {
  const {source} = publicationFixture(t);
  fs.unlinkSync(path.join(source, 'sql/oracle/ddl/create_DbCaptureSession_Table.sql'));
  const result = publication(source);
  assert.notEqual(result.status, 0);
  assert.match(result.stderr, /ENOENT|Missing regular schema resource/);
});

test('deployment confinement rejects a dangling symlink before any write', {skip: posixOnly}, async t => {
  const directory = fixture(t);
  const home = path.join(directory, 'mock-home');
  fs.mkdirSync(home);
  fs.symlinkSync(path.join(directory, 'not-yet-created.txt'), path.join(home, 'dangling.txt'));
  const {confined} = await import('./dbninja.mjs');
  assert.throws(() => confined(home, 'dangling.txt'), /symbolic/);
});

for (const additional of [
  'ALTER TABLE AuditUnrelated ADD audit_fixture NUMBER;\n',
  'CREATE TABLE AuditUnrelated (audit_fixture NUMBER);\n'
]) {
  test(`create-only DDL rejects an unrelated ${additional.split(' ')[0]} statement`, async t => {
    const directory = fixture(t);
    const {createOnlySql, tables} = await import('./create-schema.mjs');
    for (const table of tables) {
      put(directory, `create_${table}_Table.sql`, `CREATE TABLE ${table} (idA2A2 NUMBER, `
        + `CONSTRAINT PK_${table} PRIMARY KEY (idA2A2))\n/\n`);
      put(directory, `create_${table}_Index.sql`,
        `CREATE INDEX ${table}$COMPOSITE0 ON ${table}(idA2A2)\n/\n`);
    }
    fs.appendFileSync(path.join(directory, `create_${tables[0]}_Table.sql`), additional);
    assert.throws(() => createOnlySql(directory), /Unexpected|destructive|unrelated/i);
  });
}

function rollbackFixture(t) {
  const directory = fixture(t);
  copyTool(directory, 'dbninja.mjs');
  put(directory, 'deployment/assets.json', fs.readFileSync(path.join(root, 'deployment/assets.json')));
  const home = path.join(directory, 'mock-home');
  const javaHome = path.join(directory, 'mock-jdk');
  fs.mkdirSync(javaHome);
  const xconf = put(home, 'bin/xconfmanager', '#!/bin/sh\n'
    + '[ "$1" = "--validateassite" ] || exit 91\n'
    + 'printf "SIMULATED read-only xconf validation\\n"\n');
  fs.chmodSync(xconf, 0o755);
  const hash = value => crypto.createHash('sha256').update(value).digest('hex');
  const managed = 'codebase/custom/DbCapture/audit-fixture.js';
  const generated = 'codebase/netmarkets/javascript/util/main.js';
  const prerequisite = 'codebase/netmarkets/jsp/dbcapture/objectDetails.jsp';
  const backup = path.join(directory, 'owned-backup');
  const planDirectory = path.join(directory, 'reviewed-plan');
  const before = {}, after = {}, files = {};
  for (const relative of [managed, `wtSafeArea/siteMod/${managed}`, generated]) {
    put(home, relative, 'after-fixture\n');
    put(backup, `before/${relative}`, 'before-fixture\n');
    before[relative] = {hash: hash('before-fixture\n'), mode: 0o644};
    after[relative] = hash('after-fixture\n');
  }
  put(home, prerequisite, 'prerequisite-fixture\n');
  put(home, 'site.xconf', '<Configuration/>\n');
  put(planDirectory, `siteMod/${managed}`, 'after-fixture\n');
  files[managed] = hash('after-fixture\n');
  const plan = {format: 1, target: fs.realpathSync(home), javascriptOnly: true,
    files, before, settings: {}, presentation: {},
    prerequisites: {[prerequisite]: hash('prerequisite-fixture\n')},
    toolchain: {'bin/xconfmanager': hash(fs.readFileSync(xconf))}};
  const applied = {backup, status: 'static-verified-browser-reload-required', after};
  const planFile = put(planDirectory, 'plan.json', JSON.stringify(plan));
  put(planDirectory, 'applied.json', JSON.stringify(applied));
  const run = () => spawnSync(process.execPath, [
    path.join(directory, 'tools/dbninja.mjs'), 'rollback', planFile
  ], {
    cwd: directory, encoding: 'utf8',
    env: {...process.env, WT_HOME: home, JAVA_HOME: javaHome, DBNINJA_MAINTENANCE_APPROVED: 'yes'}
  });
  return {home, backup, planDirectory, managed, generated, prerequisite, applied, run};
}

test('mock rollback restores an unchanged snapshot using only a fake xconf validator', {
  skip: posixOnly
}, t => {
  const f = rollbackFixture(t);
  const result = f.run();
  assert.equal(result.status, 0, result.stderr);
  assert.match(result.stdout, /SIMULATED read-only xconf validation/);
  for (const relative of [f.managed, `wtSafeArea/siteMod/${f.managed}`, f.generated]) {
    assert.equal(fs.readFileSync(path.join(f.home, relative), 'utf8'), 'before-fixture\n');
  }
  assert.equal(JSON.parse(fs.readFileSync(path.join(f.planDirectory, 'applied.json'), 'utf8')).status,
    'rolled-back');
});

test('mock rollback rejects incomplete, changed-live and corrupted-backup snapshots before restoring', {
  skip: posixOnly
}, t => {
  for (const problem of ['incomplete', 'live-change', 'backup-change', 'toolchain-change']) {
    const f = rollbackFixture(t);
    if (problem === 'incomplete') {
      delete f.applied.after[f.managed];
      put(f.planDirectory, 'applied.json', JSON.stringify(f.applied));
    } else if (problem === 'live-change') put(f.home, f.managed, 'independent edit\n');
    else if (problem === 'backup-change') put(f.backup, `before/${f.managed}`, 'bad backup\n');
    else fs.appendFileSync(path.join(f.home, 'bin/xconfmanager'), '# changed toolchain\n');
    const result = f.run();
    assert.notEqual(result.status, 0, problem);
    assert.match(result.stderr, /Incomplete post-apply|changed after deployment|Backup checksum mismatch|PTC tool changed/);
    assert.doesNotMatch(result.stdout, /SIMULATED/);
    assert.equal(fs.readFileSync(path.join(f.home, f.generated), 'utf8'), 'after-fixture\n');
  }
});

test('mock JavaScript-only rollback refuses changed non-JavaScript prerequisites', {skip: posixOnly}, t => {
  const f = rollbackFixture(t);
  put(f.home, f.prerequisite, 'independent JSP upgrade\n');
  const result = f.run();
  assert.notEqual(result.status, 0, 'Rollback restored old assets after a non-JavaScript prerequisite changed.');
  assert.equal(fs.readFileSync(path.join(f.home, f.generated), 'utf8'), 'after-fixture\n');
});

function windowsAdapter() {
  const source = fs.readFileSync(path.join(__dirname, 'dbninja.mjs'), 'utf8');
  const start = source.indexOf('function run('), end = source.indexOf('function ant(');
  assert.ok(start >= 0 && end > start);
  const calls = [];
  const run = vm.runInNewContext(`${source.slice(start, end)}\nrun`, {
    path: path.win32,
    process: {platform: 'win32', env: {ComSpec: 'cmd.exe'}, stderr: {write() {}}},
    spawnSync(command, args, options) {
      calls.push({command, args, options});
      return {status: 0, stdout: ''};
    }
  });
  return {run, calls};
}

test('simulated Windows batch adapter quotes paths and refuses command metacharacters', () => {
  const {run, calls} = windowsAdapter();
  run('C:\\Fixture Space\\xconfmanager.bat', ['--validateassite', 'C:\\Fixture Space\\site.xconf'], '.');
  assert.equal(calls[0].command, 'cmd.exe');
  assert.deepEqual(Array.from(calls[0].args.slice(0, 3)), ['/d', '/s', '/c']);
  assert.match(calls[0].args[3], /^""C:\\Fixture Space\\xconfmanager\.bat"/);
  for (const value of ['a&b', '%PATH%', '!value!', 'a|b', 'a^b', 'a"b', 'a\nb']) {
    assert.throws(() => run('C:\\Fixture\\xconfmanager.bat', ['-s', value], '.'), /metacharacters/);
  }
  assert.equal(calls.length, 1);
  run('C:\\Fixture Space\\java.exe', ['-version'], '.');
  assert.equal(calls[1].command, 'C:\\Fixture Space\\java.exe');
});

test('Windows command-construction simulation uses verbatim arguments (not native qualification)', () => {
  const {run, calls} = windowsAdapter();
  run('C:\\Fixture Space\\xconfmanager.bat', ['--validateassite', 'C:\\Fixture Space\\site.xconf'], '.');
  assert.equal(calls[0].options.windowsVerbatimArguments, true);
});
