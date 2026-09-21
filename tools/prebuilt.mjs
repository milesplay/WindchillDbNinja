import fs from 'node:fs';
import path from 'node:path';
import crypto from 'node:crypto';
import {spawnSync} from 'node:child_process';
import {fileURLToPath} from 'node:url';
import {confined, fingerprint} from './filesystem.mjs';
import {moduleJarEntries} from './module-archive.mjs';

export const metadataNames = ['DbCaptureAttrDelta', 'DbCaptureChange', 'DbCaptureSession',
  'DbCaptureTableChange', 'DbCaptureChangeDeltaLink', 'DbCaptureSessionChangeLink',
  'DbCaptureSessionTableLink'].map(name => `${name}.ClassInfo.ser`);
export const sdkFiles = ['codebase/wt/fc/Persistable.class', 'codebase/wt/util/WTProperties.class',
  'codebase/wt/method/MethodContext.class', 'codebase/wt/pom/WTConnection.class',
  'codebase/wt/introspection/ClassInfo.class', 'tomcat/lib/servlet-api.jar',
  'srclib/log4j-api.jar', 'srclib/log4j-core.jar', 'srclib/tool/Annotations.jar'];
export const prebuiltFiles = ['prebuilt/DbCapture.jar',
  ...metadataNames.map(name => `prebuilt/metadata/com/ptc/dbcapture/${name}`)];
export const generatedEntries = [
  ...metadataNames.map(name => `com/ptc/dbcapture/_${name.replace('.ClassInfo.ser', '')}.class`),
  ...['DbCaptureChangeDeltaLink', 'DbCaptureSessionChangeLink', 'DbCaptureSessionTableLink']
    .map(name => `com/ptc/dbcapture/${name}.class`),
  ...['', '_en', '_en_GB', '_en_US'].map(locale => `com/ptc/dbcapture/dbcaptureResource${locale}.RB.ser`),
  'META-INF/ptc.listeners.lst'
];
export const generatedSourceFiles = ['DbCaptureAttrDelta.java', 'DbCaptureChange.java',
  'DbCaptureSession.java', 'DbCaptureTableChange.java', 'dbcaptureResource.rbInfo']
  .map(name => `customization/DbCapture/main/src/com/ptc/dbcapture/${name}`);
const digestPattern = /^[a-f0-9]{64}$/;

export function runtimeSources(root) {
  const result = [];
  const walk = relative => {
    const file = confined(root, relative), stat = fs.lstatSync(file);
    if (stat.isDirectory()) {
      for (const name of fs.readdirSync(file)) walk(`${relative}/${name}`);
    } else if (stat.isFile() && /\.(?:java|rbInfo)$/.test(relative)) {
      result.push(relative);
    } else {
      throw new Error(`Unexpected runtime source: ${relative}`);
    }
  };
  walk('customization/DbCapture/main/src');
  return result.sort();
}

export function readPrebuilt(root) {
  const manifestFile = confined(root, 'prebuilt/manifest.json');
  if (!fs.existsSync(manifestFile)) throw new Error('No prebuilt manifest. Use a reviewed target build instead.');
  const manifest = JSON.parse(fs.readFileSync(manifestFile, 'utf8'));
  const baseline = JSON.parse(fs.readFileSync(confined(root, 'deployment/generated-model-baseline.json'), 'utf8'));
  const target = manifest.target;
  if (manifest.schemaVersion !== 1 || manifest.package !== 'windchill-db-ninja'
      || typeof manifest.version !== 'string' || !/^\d+\.\d+\.\d+(?:-[A-Za-z0-9.-]+)?$/.test(manifest.version)
      || !target || target.os !== 'linux' || target.arch !== 'x64' || target.javaMajor !== 17
      || target.oracleMajor !== 19 || target.servlet !== 'jakarta'
      || target.layout !== 'traditional-codebase' || !/^\d+\.\d+\.\d+\.\d+$/.test(target.windchill || '')
      || !manifest.artifacts || !manifest.sources || !manifest.sdk
      || !manifest.build || manifest.build.javaRelease !== 17
      || manifest.build.method !== 'target-sdk-javac-with-matching-generated-models'
      || manifest.build.annotationProcessing !== false
      || manifest.build.generatedModelBaselineSha256 !== fingerprint(confined(root, 'deployment/generated-model-baseline.json'))) {
    throw new Error('Unsupported or incomplete prebuilt manifest.');
  }
  if (JSON.stringify(Object.keys(manifest.artifacts).sort()) !== JSON.stringify([...prebuiltFiles].sort())
      || JSON.stringify(Object.keys(manifest.sdk).sort()) !== JSON.stringify([...sdkFiles].sort())) {
    throw new Error('Prebuilt artifact/SDK inventory must match the reviewed profile.');
  }
  if (baseline.schemaVersion !== 1 || !baseline.generatedJarEntries || !baseline.generatedInputSources || !baseline.metadata
      || JSON.stringify(Object.keys(baseline.generatedJarEntries).sort()) !== JSON.stringify([...generatedEntries].sort())
      || JSON.stringify(Object.keys(baseline.generatedInputSources).sort()) !== JSON.stringify([...generatedSourceFiles].sort())
      || JSON.stringify(Object.keys(baseline.metadata).sort()) !== JSON.stringify([...metadataNames].sort())
      || JSON.stringify(Object.keys(baseline.sdk || {}).sort()) !== JSON.stringify([...sdkFiles].sort())
      || Object.entries(baseline.sdk || {}).some(([name, hash]) => manifest.sdk[name] !== hash)
      || Object.entries(baseline.target || {}).some(([name, value]) => target[name] !== value)) {
    throw new Error('Prebuilt generated-model baseline does not match this profile.');
  }
  const sources = runtimeSources(root);
  if (JSON.stringify(Object.keys(manifest.sources).sort()) !== JSON.stringify(sources)) {
    throw new Error('Prebuilt source inventory does not match this checkout; rebuild the module.');
  }
  for (const [relative, hash] of Object.entries(manifest.sources)) {
    if (typeof hash !== 'string' || !digestPattern.test(hash) || fingerprint(confined(root, relative)) !== hash) {
      throw new Error(`Prebuilt source differs from this checkout: ${relative}; rebuild instead of reusing stale binaries.`);
    }
  }
  for (const [relative, expected] of Object.entries(baseline.generatedInputSources)) {
    if (manifest.sources[relative] !== expected) {
      throw new Error('Generated model/resource inputs changed; regenerate and qualify the model baseline.');
    }
  }
  for (const hash of Object.values(manifest.sdk)) {
    if (typeof hash !== 'string' || !digestPattern.test(hash)) throw new Error('Invalid SDK fingerprint.');
  }
  for (const relative of prebuiltFiles) {
    const info = manifest.artifacts[relative], file = confined(root, relative);
    if (!info || typeof info.sha256 !== 'string' || !digestPattern.test(info.sha256)
        || !Number.isSafeInteger(info.bytes) || info.bytes < 1
        || info.bytes > (relative.endsWith('.jar') ? 32 : 8) * 1024 * 1024
        || !fs.existsSync(file) || fs.statSync(file).size !== info.bytes || fingerprint(file) !== info.sha256) {
      throw new Error(`Prebuilt artifact mismatch: ${relative}`);
    }
    const bytes = fs.readFileSync(file);
    if (relative.endsWith('.jar')) {
      const entries = moduleJarEntries(bytes);
      for (const [name, expected] of Object.entries(baseline.generatedJarEntries)) {
        if (!entries.has(name) || crypto.createHash('sha256').update(entries.get(name)).digest('hex') !== expected) {
          throw new Error(`Generated custom model/resource differs from the qualified baseline: ${name}`);
        }
      }
    } else if (bytes.length < 4 || bytes.readUInt32BE(0) !== 0xaced0005
        || info.sha256 !== baseline.metadata[path.posix.basename(relative)]) {
      throw new Error(`Invalid or mismatched model metadata stream: ${relative}`);
    }
  }
  return {manifest, manifestFile, jar: confined(root, 'prebuilt/DbCapture.jar'),
    metadata: Object.fromEntries(metadataNames.map(name => [name, confined(root, `prebuilt/metadata/com/ptc/dbcapture/${name}`)]))};
}

function execute(command, args, cwd) {
  const result = spawnSync(command, args, {cwd, encoding: 'utf8', timeout: 60000, maxBuffer: 8 * 1024 * 1024});
  if (result.error) throw result.error;
  if (result.status !== 0) throw new Error(`Target version probe failed: ${path.basename(command)} exit ${result.status}.`);
  return `${result.stdout || ''}\n${result.stderr || ''}`;
}

export function verifyPrebuiltTarget(env, candidate) {
  const {manifest} = candidate;
  if (process.platform !== manifest.target.os || process.arch !== manifest.target.arch) {
    throw new Error('This prebuilt package is restricted to Linux x64; do not deploy it on another platform.');
  }
  const version = execute(env.java, ['-version'], env.home);
  const major = version.match(/(?:openjdk|java) version "(\d+)[."]/);
  if (!major || Number(major[1]) !== manifest.target.javaMajor) throw new Error('Prebuilt Java major version mismatch.');
  for (const [relative, expected] of Object.entries(manifest.sdk)) {
    if (fingerprint(confined(env.home, relative)) !== expected) {
      throw new Error(`Prebuilt SDK fingerprint mismatch: ${relative}; use a target rebuild and qualification.`);
    }
  }
  const classpath = [path.join(env.home, 'codebase'), path.join(env.home, 'codebase/WEB-INF/lib/*'),
    path.join(env.home, 'lib/*'), path.join(env.home, 'srclib/*')].join(path.delimiter);
  const release = execute(env.java, [`-Dwt.home=${env.home}`, '-cp', classpath,
    'wt.util.version.WindchillVersion'], env.home);
  const releases = [...release.matchAll(/\bwnc\.([0-9]+(?:\.[0-9]+){3})\b/g)].map(match => match[1]);
  if (releases.length !== 1 || releases[0] !== manifest.target.windchill) {
    throw new Error('Prebuilt Windchill support datecode mismatch; rebuild for the actual release/CPS.');
  }
  return candidate;
}

if (process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  try {
    if (process.argv.length !== 3 || process.argv[2] !== 'verify') {
      throw new Error('Usage: node tools/prebuilt.mjs verify');
    }
    if (!process.env.WT_HOME || !process.env.JAVA_HOME) throw new Error('Set WT_HOME and JAVA_HOME explicitly.');
    const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
    const home = fs.realpathSync(process.env.WT_HOME), javaHome = fs.realpathSync(process.env.JAVA_HOME);
    const candidate = verifyPrebuiltTarget({home, java: path.join(javaHome, 'bin', process.platform === 'win32' ? 'java.exe' : 'java')},
      readPrebuilt(root));
    console.log(`PASS: module-only prebuilt ${candidate.manifest.version}; Linux x64, Windchill ${candidate.manifest.target.windchill}, Java 17.`);
    console.log('Oracle privileges/schema/undo, non-production classification and maintenance approval still require target review.');
  } catch (error) {
    console.error(`ERROR: ${error.message}`);
    process.exitCode = 1;
  }
}
