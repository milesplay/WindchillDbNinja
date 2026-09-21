const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const crypto = require('node:crypto');
const test = require('node:test');
const api = import('./schema-package.mjs');
const root = path.resolve(__dirname, '..');
const sha = bytes => crypto.createHash('sha256').update(bytes).digest('hex');

async function fixture(t) {
  const {schemaFiles} = await api;
  const {generatedSourceFiles} = await import('./prebuilt.mjs');
  fs.mkdirSync(path.join(root, 'build'), {recursive: true});
  const directory = fs.mkdtempSync(path.join(root, 'build/schema-package-test-'));
  t.after(() => fs.rmSync(directory, {recursive: true}));
  for (const relative of [...schemaFiles, 'deployment/generated-model-baseline.json', ...generatedSourceFiles]) {
    const file = path.join(directory, relative);
    fs.mkdirSync(path.dirname(file), {recursive: true});
    fs.copyFileSync(path.join(root, relative), file);
  }
  const profileFile = path.join(directory, 'sql/oracle/schema-profile.json');
  const profile = JSON.parse(fs.readFileSync(profileFile, 'utf8'));
  const save = () => fs.writeFileSync(profileFile, JSON.stringify(profile));
  const changeInput = (relative, change) => {
    const file = path.join(directory, relative);
    fs.writeFileSync(file, change(fs.readFileSync(file, 'utf8')));
    const bytes = fs.readFileSync(file);
    profile.inputs[relative] = {bytes: bytes.length, sha256: sha(bytes)};
    save();
  };
  return {directory, profile, save, changeInput, generatedSourceFiles};
}

test('bundled DDL has the complete qualified four-table schema and deterministic output', async () => {
  const {readSchemaPackage} = await api;
  const {profile, definition, script} = readSchemaPackage(root);
  assert.equal(profile.target.maxBytesPerChar, 3);
  assert.equal(profile.target.oracleMajor, 19);
  assert.equal(definition.tables.length, 4);
  assert.equal(definition.primaryKeys.length, 4);
  assert.equal(definition.indexes.length, 14);
  assert.equal(definition.comments, 4);
  assert.deepEqual(definition.tablespaces, ['INDX']);
  assert.equal((script.match(/^CREATE TABLE /gm) || []).length, 4);
  assert.equal((script.match(/^CREATE INDEX /gm) || []).length, 14);
  assert.equal((script.match(/^COMMENT ON TABLE /gm) || []).length, 4);
  assert.equal((script.match(/^\/$/gm) || []).length, 1);
  assert.doesNotMatch(script, /\b(?:DROP|TRUNCATE|GRANT|ALTER)\s|\bDBCAPTURESQLEVENTS\b/i);
});

test('the first-install guard covers every reserved table, index and primary-key name before any CREATE', async () => {
  const {readSchemaPackage} = await api;
  const {definition, script} = readSchemaPackage(root);
  const guard = script.slice(0, script.indexOf('\nCREATE TABLE'));
  assert.match(guard, /FROM user_objects WHERE object_name IN/);
  assert.match(guard, /FROM user_constraints WHERE constraint_name IN/);
  assert.match(guard, /existing_count <> 0 OR constraint_count <> 0/);
  for (const name of [...definition.tables, ...definition.primaryKeys, ...definition.indexes]) {
    assert.ok(guard.includes(`'${name}'`), name);
  }
  assert.match(guard, /CURRENT_SCHEMA.*SESSION_USER/);
  assert.match(guard, /IN \('SYS', 'SYSTEM'\)/);
  assert.match(guard, /FROM user_tablespaces/);
  assert.match(guard, /'INDX'.*status = 'ONLINE'.*contents = 'PERMANENT'/);
  assert.match(guard, /WHENEVER SQLERROR EXIT SQL\.SQLCODE ROLLBACK/);
  assert.match(guard, /WHENEVER OSERROR EXIT FAILURE ROLLBACK/);
});

test('schema verification refuses missing, changed or extra SQL resources', async t => {
  const {readSchemaPackage, schemaInputs} = await api;
  for (const kind of ['missing-input', 'missing-combined', 'changed-input', 'extra']) {
    const f = await fixture(t);
    if (kind === 'missing-input') fs.unlinkSync(path.join(f.directory, schemaInputs[0]));
    if (kind === 'missing-combined') fs.unlinkSync(path.join(f.directory, 'sql/oracle/create-db-ninja.sql'));
    if (kind === 'changed-input') fs.appendFileSync(path.join(f.directory, schemaInputs[0]), '\n-- Unreviewed edit\n');
    if (kind === 'extra') fs.writeFileSync(path.join(f.directory, 'sql/oracle/ddl/unreviewed.sql'), '-- Not approved\n');
    assert.throws(() => readSchemaPackage(f.directory), /ENOENT|checksum|inventory/);
  }
});

test('schema verification rejects model drift and unsupported width, release or tablespace profiles', async t => {
  const {readSchemaPackage} = await api;
  for (const kind of ['model', 'baseline', 'width', 'oracle', 'windchill', 'tablespace', 'inventory']) {
    const f = await fixture(t);
    if (kind === 'model') fs.appendFileSync(path.join(f.directory, f.generatedSourceFiles[0]), '\n// Fixture change\n');
    if (kind === 'baseline') f.profile.generatedModelBaselineSha256 = '0'.repeat(64);
    if (kind === 'width') f.profile.target.maxBytesPerChar = 1;
    if (kind === 'oracle') f.profile.target.oracleMajor = 23;
    if (kind === 'windchill') f.profile.target.windchill = '13.0.2.12';
    if (kind === 'tablespace') f.profile.target.indexTablespace = 'SITE_SPECIFIC';
    if (kind === 'inventory') f.profile.secondaryIndexes.pop();
    f.save();
    assert.throws(() => readSchemaPackage(f.directory), /source changed|baseline changed|profile|exactly/);
  }
});

test('rewritten checksums do not bypass statement, index inventory or BYTE-semantics checks', async t => {
  const {readSchemaPackage} = await api;
  for (const kind of ['unsafe', 'index', 'width', 'primary-key']) {
    const f = await fixture(t);
    if (kind === 'unsafe') f.changeInput('sql/oracle/ddl/create_DbCaptureSession_Index.sql',
      text => text + '\nDROP TABLE UnrelatedFixture;\n');
    if (kind === 'index') f.changeInput('sql/oracle/ddl/create_DbCaptureSession_Index.sql',
      text => text.slice(text.indexOf('/\n') + 2));
    if (kind === 'width') f.changeInput('sql/oracle/ddl/create_DbCaptureSession_Table.sql',
      text => text.replace('VARCHAR2(120 BYTE)', 'VARCHAR2(120 CHAR)'));
    if (kind === 'primary-key') f.changeInput('sql/oracle/ddl/create_DbCaptureSession_Table.sql',
      text => text.replace(/,\n CONSTRAINT PK_DbCaptureSession PRIMARY KEY \(idA2A2\)/, ''));
    assert.throws(() => readSchemaPackage(f.directory), /Unexpected\/destructive|exactly|BYTE semantics|missing primary key/);
  }
});

test('the combined script cannot diverge from the assembler even after rehashing', async t => {
  const {readSchemaPackage} = await api;
  const f = await fixture(t), file = path.join(f.directory, 'sql/oracle/create-db-ninja.sql');
  fs.appendFileSync(file, '\n-- An unreviewed change\n');
  const bytes = fs.readFileSync(file);
  f.profile.combined = {bytes: bytes.length, sha256: sha(bytes)};
  f.save();
  assert.throws(() => readSchemaPackage(f.directory), /deterministic assembler/);
});

test('width qualification requires exactly one declared value and the same propagated value', async () => {
  const {verifySchemaWidth} = await api;
  const declaration = value => `Property information for 'wt.db.maxBytesPerChar':\n   Values:\n   - ${value}\n   Locations:\n   - fixture\n`;
  const propagated = value => `wt.db.maxBytesPerChar=${Buffer.from(value).toString('base64')}\n`;
  assert.doesNotThrow(() => verifySchemaWidth(declaration('3'), propagated('3')));
  assert.doesNotThrow(() => verifySchemaWidth(declaration('3').replaceAll('\n', '\r\n'), propagated('3')));
  for (const value of ['1', '2', '4', 'unknown', '3\n   - 3']) {
    assert.throws(() => verifySchemaWidth(declaration(value), propagated('3')), /requires declared and propagated/);
  }
  for (const value of ['1', '4', '']) {
    assert.throws(() => verifySchemaWidth(declaration('3'), propagated(value)), /requires declared and propagated/);
  }
  assert.throws(() => verifySchemaWidth(declaration('3') + declaration('3'), propagated('3')), /requires declared/);
  assert.throws(() => verifySchemaWidth('unrecognized output', propagated('3')), /requires declared/);
});

test('DDL source symlinks cannot substitute files outside the reviewed package', {skip: process.platform === 'win32'}, async t => {
  const {readSchemaPackage, schemaInputs} = await api;
  const f = await fixture(t), file = path.join(f.directory, schemaInputs[0]);
  fs.unlinkSync(file);
  fs.symlinkSync(path.join(root, schemaInputs[0]), file);
  assert.throws(() => readSchemaPackage(f.directory), /symbolic/);
});
