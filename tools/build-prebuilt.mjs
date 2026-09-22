import fs from 'node:fs';
import path from 'node:path';
import crypto from 'node:crypto';
import {spawnSync} from 'node:child_process';
import {fileURLToPath} from 'node:url';
import {confined, fingerprint} from './filesystem.mjs';
import {moduleJarEntries} from './module-archive.mjs';
import {metadataNames, runtimeSources, readPrebuilt, verifyPrebuiltTarget} from './prebuilt.mjs';

function main() {
  if (process.argv.length !== 2) throw new Error('Usage: node tools/build-prebuilt.mjs (set WT_HOME and JAVA_HOME)');
  if (!process.env.WT_HOME || !process.env.JAVA_HOME) throw new Error('Set the reviewed WT_HOME and JAVA_HOME.');
  const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
  const home = fs.realpathSync(process.env.WT_HOME), javaHome = fs.realpathSync(process.env.JAVA_HOME);
  const java = path.join(javaHome, process.platform === 'win32' ? 'bin/java.exe' : 'bin/java');
  const profile = JSON.parse(fs.readFileSync(confined(root, 'deployment/generated-model-baseline.json'), 'utf8'));
  verifyPrebuiltTarget({home, java}, {manifest: profile});
  for (const [relative, expected] of Object.entries(profile.generatedInputSources)) {
    if (fingerprint(confined(root, relative)) !== expected) {
      throw new Error(`Generated-model input changed: ${relative}. Regenerate with the licensed target CCD before reviewing a new baseline.`);
    }
  }
  const baseJar = confined(root, 'build/DbCapture.jar');
  const baseHash = fingerprint(baseJar);
  const baseEntries = moduleJarEntries(fs.readFileSync(baseJar));
  const digest = bytes => crypto.createHash('sha256').update(bytes).digest('hex');
  for (const [name, expected] of Object.entries(profile.generatedJarEntries)) {
    if (!baseEntries.has(name) || digest(baseEntries.get(name)) !== expected) {
      throw new Error(`Generated custom model/resource baseline mismatch: ${name}`);
    }
  }
  for (const name of metadataNames) {
    if (fingerprint(confined(root, `build/metadata/com/custom/dbcapture/${name}`)) !== profile.metadata[name]) {
      throw new Error(`Target ClassInfo baseline mismatch: ${name}`);
    }
  }
  const sourceNames = runtimeSources(root);
  const sources = Object.fromEntries(sourceNames.map(relative => [relative, fingerprint(confined(root, relative))]));
  const build = confined(root, 'build');
  fs.mkdirSync(build, {recursive: true, mode: 0o700});
  const work = fs.mkdtempSync(path.join(build, 'prebuilt-'));
  const classes = path.join(work, 'classes');
  fs.mkdirSync(classes, {mode: 0o700});
  for (const name of Object.keys(profile.generatedJarEntries)) {
    const file = confined(classes, name);
    fs.mkdirSync(path.dirname(file), {recursive: true});
    fs.writeFileSync(file, baseEntries.get(name));
  }
  const classpath = [classes, path.join(home, 'codebase'), path.join(home, 'codebase/WEB-INF/lib/*'),
    path.join(home, 'lib/*'), path.join(home, 'srclib/tool/Annotations.jar')].join(path.delimiter);
  const execute = (command, args, label) => {
    const result = spawnSync(command, args, {cwd: work, encoding: 'utf8', timeout: 180000, maxBuffer: 16 * 1024 * 1024});
    fs.writeFileSync(path.join(work, `${label}.log`), (result.stdout || '') + (result.stderr || ''), {mode: 0o600});
    if (result.error) throw result.error;
    if (result.status !== 0) throw new Error(`${label} failed; inspect its private build log.`);
    return `${result.stdout || ''}${result.stderr || ''}`.trim();
  };
  const extension = process.platform === 'win32' ? '.exe' : '';
  const compiler = path.join(javaHome, `bin/javac${extension}`);
  const compilerVersion = execute(compiler, ['-version'], 'compiler-version');
  execute(compiler, ['-J-Xmx512m', `-J-Djava.io.tmpdir=${work}`, '--release', '11',
    '-encoding', 'UTF-8', '-proc:none', '-cp', classpath, '-d', classes,
    ...sourceNames.filter(name => name.endsWith('.java')).map(name => confined(root, name))], 'compile');
  const jar = path.join(work, 'DbCapture.jar');
  execute(path.join(javaHome, `bin/jar${extension}`), ['--create', '--file', jar, '-C', classes, '.'], 'jar');
  const entries = moduleJarEntries(fs.readFileSync(jar));
  if (JSON.stringify(runtimeSources(root)) !== JSON.stringify(sourceNames)
      || sourceNames.some(name => fingerprint(confined(root, name)) !== sources[name])) {
    throw new Error('Runtime sources changed during compilation; do not publish this build.');
  }
  if (fingerprint(baseJar) !== baseHash || metadataNames.some(name =>
    fingerprint(confined(root, `build/metadata/com/custom/dbcapture/${name}`)) !== profile.metadata[name])) {
    throw new Error('Target baseline changed during the read-only build.');
  }
  const outputs = new Map([['prebuilt/DbCapture.jar', fs.readFileSync(jar)]]);
  for (const name of metadataNames) {
    outputs.set(`prebuilt/metadata/com/custom/dbcapture/${name}`,
      fs.readFileSync(confined(root, `build/metadata/com/custom/dbcapture/${name}`)));
  }
  const manifest = {
    schemaVersion: 1, package: 'windchill-db-ninja',
    version: JSON.parse(fs.readFileSync(path.join(root, 'package.json'), 'utf8')).version,
    target: profile.target,
    build: {method: 'target-sdk-javac-with-matching-generated-models', compiler: compilerVersion,
      javaRelease: 11, builtAt: new Date().toISOString(), annotationProcessing: false,
      generatedModelBaselineSha256: fingerprint(confined(root, 'deployment/generated-model-baseline.json')),
      note: 'All current runtime Java sources compiled. Only unchanged, fingerprint-locked custom generated model/resource entries and ClassInfo were retained from the qualified target. No live files, PTC SDK libraries or generated PTC JavaScript bundles are copied into this package.'},
    sdk: profile.sdk, sources,
    artifacts: Object.fromEntries([...outputs].map(([relative, bytes]) =>
      [relative, {bytes: bytes.length, sha256: digest(bytes)}]))
  };
  outputs.set('prebuilt/manifest.json', Buffer.from(`${JSON.stringify(manifest, null, 2)}\n`));
  for (const [relative, bytes] of outputs) {
    const target = confined(root, relative);
    fs.mkdirSync(path.dirname(target), {recursive: true});
    if (fs.existsSync(target)) {
      const backup = confined(work, `previous/${relative}`);
      fs.mkdirSync(path.dirname(backup), {recursive: true});
      fs.copyFileSync(target, backup);
    }
    const pending = confined(root, `${relative}.pending`);
    fs.writeFileSync(pending, bytes, {flag: 'wx', mode: 0o644});
    fs.renameSync(pending, target);
  }
  fs.rmSync(confined(root, 'prebuilt/metadata/com/ptc'), {recursive: true, force: true});
  readPrebuilt(root);
  console.log(`PASS: ${sourceNames.filter(name => name.endsWith('.java')).length} current sources compiled; ${entries.size} module-only JAR entries and seven matching ClassInfo files packaged.`);
  console.log('No annotation processing, live installation writes, deployment or database commands occurred.');
  console.log(`Private build evidence: ${work}`);
}

try {
  main();
} catch (error) {
  console.error(`ERROR: ${error.message}`);
  process.exitCode = 1;
}
