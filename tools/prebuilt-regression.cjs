const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const crypto = require('node:crypto');
const test = require('node:test');
const api = import('./prebuilt.mjs');
const archive = import('./module-archive.mjs');
const checksums = import('./icon-assets.mjs');
const root = path.resolve(__dirname, '..');
const sha = bytes => crypto.createHash('sha256').update(bytes).digest('hex');

function put(root, relative, bytes) {
  const file = path.join(root, relative);
  fs.mkdirSync(path.dirname(file), {recursive: true});
  fs.writeFileSync(file, bytes);
  return file;
}

function storedZip(entries, crc32) {
  const locals = [], directory = [];
  let offset = 0;
  for (const [name, data] of entries) {
    const nameBytes = Buffer.from(name), crc = crc32(data);
    const local = Buffer.alloc(30 + nameBytes.length);
    local.writeUInt32LE(0x04034b50);
    local.writeUInt16LE(20, 4);
    local.writeUInt32LE(crc, 14);
    local.writeUInt32LE(data.length, 18);
    local.writeUInt32LE(data.length, 22);
    local.writeUInt16LE(nameBytes.length, 26);
    nameBytes.copy(local, 30);
    const central = Buffer.alloc(46 + nameBytes.length);
    central.writeUInt32LE(0x02014b50);
    central.writeUInt16LE(20, 4);
    central.writeUInt16LE(20, 6);
    central.writeUInt32LE(crc, 16);
    central.writeUInt32LE(data.length, 20);
    central.writeUInt32LE(data.length, 24);
    central.writeUInt16LE(nameBytes.length, 28);
    central.writeUInt32LE(offset, 42);
    nameBytes.copy(central, 46);
    locals.push(local, data);
    directory.push(central);
    offset += local.length + data.length;
  }
  const central = Buffer.concat(directory), end = Buffer.alloc(22);
  end.writeUInt32LE(0x06054b50);
  end.writeUInt16LE(entries.size, 8);
  end.writeUInt16LE(entries.size, 10);
  end.writeUInt32LE(central.length, 12);
  end.writeUInt32LE(offset, 16);
  return Buffer.concat([...locals, central, end]);
}

async function fixture(t) {
  fs.mkdirSync(path.join(root, 'build'), {recursive: true});
  const directory = fs.mkdtempSync(path.join(root, 'build/prebuilt-test-'));
  t.after(() => fs.rmSync(directory, {recursive: true}));
  const {metadataNames, sdkFiles, generatedEntries, generatedSourceFiles} = await api;
  const {pngCrc32} = await checksums;
  // These are parser fixtures only, not executable Java classes or model objects.
  const classBytes = Buffer.from('cafebabe0000003d0001', 'hex');
  const entries = new Map([
    ['META-INF/MANIFEST.MF', Buffer.from('Manifest-Version: 1.0\n')],
    ['com/ptc/dbcapture/StandardDbCaptureService.class', classBytes],
    ['com/ptc/dbcapture/dbCaptureActionResource.class', classBytes]
  ]);
  for (const name of generatedEntries) entries.set(name, name.endsWith('.class') ? classBytes : Buffer.from('generated fixture'));
  const sources = {};
  for (const relative of generatedSourceFiles) {
    const text = Buffer.from('English synthetic model/resource input\n');
    put(directory, relative, text);
    sources[relative] = sha(text);
  }
  const target = {os: 'linux', arch: 'x64', javaMajor: 17, windchill: '13.0.2.11',
    oracleMajor: 19, servlet: 'jakarta', layout: 'traditional-codebase'};
  const sdk = Object.fromEntries(sdkFiles.map(name => [name, sha(Buffer.from('SDK fixture'))]));
  const metadataBytes = Buffer.from('aced0005', 'hex');
  const baseline = {schemaVersion: 1, target, sdk, generatedInputSources: sources,
    generatedJarEntries: Object.fromEntries(generatedEntries.map(name => [name, sha(entries.get(name))])),
    metadata: Object.fromEntries(metadataNames.map(name => [name, sha(metadataBytes)]))};
  const baselineBytes = Buffer.from(JSON.stringify(baseline));
  put(directory, 'deployment/generated-model-baseline.json', baselineBytes);
  const payloads = new Map([['prebuilt/DbCapture.jar', storedZip(entries, pngCrc32)]]);
  for (const name of metadataNames) payloads.set(`prebuilt/metadata/com/ptc/dbcapture/${name}`, metadataBytes);
  const manifest = {schemaVersion: 1, package: 'windchill-db-ninja', version: '0.1.0',
    target, sdk, sources,
    build: {method: 'target-sdk-javac-with-matching-generated-models', javaRelease: 17,
      annotationProcessing: false, generatedModelBaselineSha256: sha(baselineBytes)},
    artifacts: Object.fromEntries([...payloads].map(([name, bytes]) => [name, {bytes: bytes.length, sha256: sha(bytes)}]))};
  for (const [name, bytes] of payloads) put(directory, name, bytes);
  const save = () => put(directory, 'prebuilt/manifest.json', JSON.stringify(manifest));
  save();
  return {directory, manifest, entries, payloads, save, crc32: pngCrc32};
}

test('prebuilt package requires exact module inventory, current sources and matching generated model artifacts', async t => {
  const f = await fixture(t), {readPrebuilt} = await api;
  const result = readPrebuilt(f.directory);
  assert.equal(Object.keys(result.metadata).length, 7);
  assert.equal(result.manifest.target.windchill, '13.0.2.11');
  assert.equal(result.jar, path.join(f.directory, 'prebuilt/DbCapture.jar'));
});

test('prebuilt refuses stale source, missing metadata and artifact tampering', async t => {
  const {readPrebuilt, generatedSourceFiles, metadataNames} = await api;
  for (const kind of ['source', 'missing', 'bytes']) {
    const f = await fixture(t);
    if (kind === 'source') fs.appendFileSync(path.join(f.directory, generatedSourceFiles[0]), 'changed');
    else if (kind === 'missing') fs.unlinkSync(path.join(f.directory, 'prebuilt/metadata/com/ptc/dbcapture', metadataNames[0]));
    else fs.appendFileSync(path.join(f.directory, 'prebuilt/DbCapture.jar'), 'unreviewed');
    assert.throws(() => readPrebuilt(f.directory), /source differs|artifact mismatch/);
  }
});

test('updating manifest hashes cannot substitute unrelated ClassInfo or generated base classes', async t => {
  const {readPrebuilt, metadataNames, generatedEntries} = await api;
  for (const kind of ['metadata', 'generated']) {
    const f = await fixture(t);
    const relative = kind === 'metadata'
      ? `prebuilt/metadata/com/ptc/dbcapture/${metadataNames[0]}` : 'prebuilt/DbCapture.jar';
    let bytes;
    if (kind === 'metadata') bytes = Buffer.concat([Buffer.from('aced0005', 'hex'), Buffer.from('different model')]);
    else {
      f.entries.set(generatedEntries[0], Buffer.from('cafebabe0000003d0002', 'hex'));
      bytes = storedZip(f.entries, f.crc32);
    }
    put(f.directory, relative, bytes);
    f.manifest.artifacts[relative] = {bytes: bytes.length, sha256: sha(bytes)};
    f.save();
    assert.throws(() => readPrebuilt(f.directory), /generated|Generated|metadata stream/);
  }
});

test('the first prebuilt profile cannot claim Windows, another Java major or non-Oracle support', async t => {
  const {readPrebuilt} = await api;
  for (const [key, value] of [['os', 'win32'], ['arch', 'arm64'], ['javaMajor', 21], ['oracleMajor', 23]]) {
    const f = await fixture(t);
    f.manifest.target[key] = value;
    f.save();
    assert.throws(() => readPrebuilt(f.directory), /Unsupported/);
  }
});

test('unapproved artifacts and symlinked prebuilt ancestry are rejected', async t => {
  const {readPrebuilt} = await api;
  const f = await fixture(t);
  f.manifest.artifacts['prebuilt/ptc-sdk.jar'] = f.manifest.artifacts['prebuilt/DbCapture.jar'];
  f.save();
  assert.throws(() => readPrebuilt(f.directory), /inventory/);
  if (process.platform !== 'win32') {
    const g = await fixture(t);
    fs.renameSync(path.join(g.directory, 'prebuilt'), path.join(g.directory, 'outside'));
    fs.symlinkSync(path.join(g.directory, 'outside'), path.join(g.directory, 'prebuilt'));
    assert.throws(() => readPrebuilt(g.directory), /symbolic/);
  }
});

test('module JAR inspection rejects vendor files, path traversal, corrupt data and unsupported class versions', async t => {
  const {moduleJarEntries} = await archive;
  for (const kind of ['vendor', 'traversal', 'checksum', 'version']) {
    const f = await fixture(t);
    if (kind === 'vendor') f.entries.set('wt/fc/Persistable.class', Buffer.from('cafebabe0000003d0001', 'hex'));
    if (kind === 'traversal') f.entries.set('../outside.class', Buffer.from('cafebabe0000003d0001', 'hex'));
    if (kind === 'version') f.entries.set('com/ptc/dbcapture/TooNew.class', Buffer.from('cafebabe000000410001', 'hex'));
    const bytes = storedZip(f.entries, f.crc32);
    if (kind === 'checksum') bytes[30 + Buffer.byteLength('META-INF/MANIFEST.MF')] ^= 1;
    assert.throws(() => moduleJarEntries(bytes), /unapproved|Unsafe|checksum|class-file/);
  }
});

test('target verification requires exact JDK, SDK hashes and Windchill support datecode', {
  skip: process.platform !== 'linux' || process.arch !== 'x64'
    ? 'The initial binary target is Linux x64 only.' : false
}, async t => {
  const f = await fixture(t);
  const {readPrebuilt, verifyPrebuiltTarget, sdkFiles} = await api;
  const home = path.join(f.directory, 'target with spaces');
  for (const relative of sdkFiles) put(home, relative, 'SDK fixture');
  const java = path.join(f.directory, 'java-probe-fixture');
  const probe = (major, release) => {
    fs.writeFileSync(java, '#!/bin/sh\n'
      + `if [ "$1" = "-version" ]; then printf 'openjdk version "${major}.0.12"\\n' >&2; exit 0; fi\n`
      + `printf '13.0.2.11 13.0 wnc.${release} 32\\n'\n`, {mode: 0o700});
  };
  probe(17, '13.0.2.11');
  const candidate = readPrebuilt(f.directory);
  assert.equal(verifyPrebuiltTarget({home, java}, candidate), candidate);
  probe(21, '13.0.2.11');
  assert.throws(() => verifyPrebuiltTarget({home, java}, candidate), /Java major version/);
  probe(17, '13.0.2.12');
  assert.throws(() => verifyPrebuiltTarget({home, java}, candidate), /datecode/);
  probe(17, '13.0.2.11');
  fs.appendFileSync(path.join(home, sdkFiles[0]), 'modified SDK');
  assert.throws(() => verifyPrebuiltTarget({home, java}, candidate), /SDK fingerprint/);
});
