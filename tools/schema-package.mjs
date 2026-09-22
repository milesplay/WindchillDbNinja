import fs from 'node:fs';
import path from 'node:path';
import {spawnSync} from 'node:child_process';
import {fileURLToPath} from 'node:url';
import {confined, fingerprint} from './filesystem.mjs';
import {createOnlySql, readCreateOnly, tables} from './create-schema.mjs';
import {generatedSourceFiles, readPrebuilt, verifyPrebuiltTarget} from './prebuilt.mjs';
import {configuration, describeProperty} from './dbninja.mjs';

export const schemaInputs = ['Table', 'Index'].flatMap(kind =>
  tables.map(table => `sql/oracle/ddl/create_${table}_${kind}.sql`)).sort();
export const schemaFiles = ['sql/oracle/README.md', 'sql/oracle/schema-profile.json',
  'sql/oracle/create-db-ninja.sql', ...schemaInputs];
const targetProfile = {windchill: '12.1.2.23', oracleMajor: 19, maxBytesPerChar: 3,
  lengthSemantics: 'BYTE', indexTablespace: 'INDX', tableTablespace: 'schema-default'};
const sameNames = (actual, expected) => Array.isArray(actual)
  && JSON.stringify([...actual].sort()) === JSON.stringify([...expected].sort());

function checkFile(root, relative, metadata) {
  const file = confined(root, relative), stat = fs.lstatSync(file);
  if (!stat.isFile() || !metadata || !Number.isSafeInteger(metadata.bytes) || metadata.bytes < 1
      || !/^[a-f0-9]{64}$/.test(metadata.sha256 || '') || stat.size !== metadata.bytes
      || fingerprint(file) !== metadata.sha256) {
    throw new Error(`Schema file checksum/size mismatch: ${relative}`);
  }
}

export function readSchemaPackage(root) {
  for (const relative of schemaFiles) {
    if (!fs.lstatSync(confined(root, relative)).isFile()) throw new Error(`Missing regular schema resource: ${relative}`);
  }
  if (!sameNames(fs.readdirSync(confined(root, 'sql/oracle')), ['README.md', 'schema-profile.json', 'create-db-ninja.sql', 'ddl'])
      || !sameNames(fs.readdirSync(confined(root, 'sql/oracle/ddl')), schemaInputs.map(file => path.posix.basename(file)))) {
    throw new Error('Schema resource inventory differs from the reviewed first-install package.');
  }
  const profile = JSON.parse(fs.readFileSync(confined(root, 'sql/oracle/schema-profile.json'), 'utf8'));
  if (profile.schemaVersion !== 1 || profile.id !== 'windchill-12.1.2.23-oracle19c-sql3'
      || !profile.target || !sameNames(Object.keys(profile.target), Object.keys(targetProfile))
      || Object.entries(targetProfile).some(([key, value]) => profile.target[key] !== value)
      || profile.provenance?.method !== 'ptc-generated-custom-model-sql3'
      || !sameNames(profile.provenance.transformations, ['CRLF to LF', 'Explicit VARCHAR2 BYTE semantics'])
      || !profile.inputs || !sameNames(Object.keys(profile.inputs), schemaInputs)) {
    throw new Error('Unsupported or incomplete Oracle schema profile.');
  }
  const baselineFile = confined(root, 'deployment/generated-model-baseline.json');
  if (profile.generatedModelBaselineSha256 !== fingerprint(baselineFile)) {
    throw new Error('Schema generated-model baseline changed; regenerate and qualify the DDL.');
  }
  const baseline = JSON.parse(fs.readFileSync(baselineFile, 'utf8'));
  if (baseline.schemaVersion !== 1 || baseline.target?.windchill !== profile.target.windchill
      || baseline.target?.oracleMajor !== profile.target.oracleMajor
      || !sameNames(Object.keys(baseline.generatedInputSources || {}), generatedSourceFiles)) {
    throw new Error('Schema model input inventory does not match the qualified baseline.');
  }
  for (const [relative, hash] of Object.entries(baseline.generatedInputSources)) {
    if (fingerprint(confined(root, relative)) !== hash) {
      throw new Error(`Schema model/resource source changed: ${relative}; regenerate and qualify the DDL.`);
    }
  }
  for (const relative of schemaInputs) {
    checkFile(root, relative, profile.inputs[relative]);
    const text = fs.readFileSync(confined(root, relative), 'utf8');
    if ([...text.matchAll(/\bVARCHAR2\s*\([^)]*\)/gi)].some(match => !/^VARCHAR2\(\d+ BYTE\)$/i.test(match[0]))) {
      throw new Error(`Bundled schema requires explicit VARCHAR2 BYTE semantics: ${relative}`);
    }
  }
  const directory = confined(root, 'sql/oracle/ddl'), definition = readCreateOnly(directory);
  if (!sameNames(profile.tables, definition.tables) || !sameNames(profile.primaryKeys, definition.primaryKeys)
      || !sameNames(profile.secondaryIndexes, definition.indexes)
      || definition.tables.length !== 4 || definition.primaryKeys.length !== 4
      || definition.indexes.length !== 14 || definition.comments !== 4
      || !sameNames(definition.tablespaces, ['INDX'])) {
    throw new Error('Bundled schema must contain exactly four tables, four primary keys, fourteen secondary indexes and four comments in the qualified profile.');
  }
  checkFile(root, 'sql/oracle/create-db-ninja.sql', profile.combined);
  const script = fs.readFileSync(confined(root, 'sql/oracle/create-db-ninja.sql'), 'utf8');
  if (script !== createOnlySql(directory)) {
    throw new Error('Combined schema differs from the guarded deterministic assembler output.');
  }
  return {profile, definition, script};
}

export function verifySchemaWidth(declaration, propagated) {
  const values = [...declaration.matchAll(/^Property information for 'wt\.db\.maxBytesPerChar':\r?\n[ \t]*Values:\r?\n([\s\S]*?)^[ \t]*Locations:/gm)];
  if (values.length !== 1 || values[0][1].trim() !== '- 3'
      || propagated.trim() !== `wt.db.maxBytesPerChar=${Buffer.from('3').toString('base64')}`) {
    throw new Error('Bundled DDL requires declared and propagated wt.db.maxBytesPerChar=3. Inspect with xconfmanager; regenerate on the target for a different profile.');
  }
}

export function verifySchemaTarget(root, env) {
  const schema = readSchemaPackage(root);
  verifyPrebuiltTarget(env, readPrebuilt(root));
  const declaration = describeProperty(env, 'wt.db.maxBytesPerChar');
  const propagated = configuration(env, 'properties', confined(env.home, 'codebase/wt.properties'), 'wt.db.maxBytesPerChar');
  verifySchemaWidth(declaration, propagated);
  return schema;
}

if (process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  try {
    if (process.argv[2] !== 'verify' || process.argv.length > 4
        || (process.argv[3] && process.argv[3] !== '--target')) {
      throw new Error('Usage: node tools/schema-package.mjs verify [--target]');
    }
    const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
    if (process.argv[3]) {
      if (!process.env.WT_HOME || !process.env.JAVA_HOME) throw new Error('Set WT_HOME and JAVA_HOME explicitly.');
      const home = fs.realpathSync(process.env.WT_HOME), javaHome = fs.realpathSync(process.env.JAVA_HOME);
      const extension = process.platform === 'win32' ? '.exe' : '';
      verifySchemaTarget(root, {home, javaHome, java: path.join(javaHome, `bin/java${extension}`),
        javac: path.join(javaHome, `bin/javac${extension}`)});
      console.log('PASS: matching Windows prebuilt target and declared/propagated wt.db.maxBytesPerChar=3.');
    } else {
      readSchemaPackage(root);
    }
    console.log('PASS: four module tables, four primary keys, fourteen secondary indexes and deterministic guarded SQL.');
    console.log('No database connection or SQL execution. The DBA must still confirm Oracle 19c, schema/PDB, tablespaces, quotas and first-install approval.');
  } catch (error) {
    console.error(`ERROR: ${error.message}`);
    process.exitCode = 1;
  }
}
