const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const crypto = require('node:crypto');
const {spawnSync} = require('node:child_process');
const {pathToFileURL} = require('node:url');
const test = require('node:test');
const root = path.resolve(__dirname, '..');
const sha = bytes => crypto.createHash('sha256').update(bytes).digest('hex');

function put(root, relative, contents, mode = 0o600) {
  const file = path.join(root, relative);
  fs.mkdirSync(path.dirname(file), {recursive: true});
  fs.writeFileSync(file, contents, {mode});
  return file;
}

function inventory(root) {
  const files = {};
  const walk = relative => {
    for (const entry of fs.readdirSync(path.join(root, relative), {withFileTypes: true})) {
      const name = path.posix.join(relative, entry.name);
      assert.ok(!entry.isSymbolicLink());
      if (entry.isDirectory()) walk(name);
      else files[name] = sha(fs.readFileSync(path.join(root, name)));
    }
  };
  walk('');
  return files;
}

test('fresh prebuilt installation plans every runtime resource from the checkout without live writes or old artifacts', {
  skip: process.platform !== 'win32' || process.arch !== 'x64' || !process.env.JAVA_HOME
    ? 'Requires the qualified Windows x64 platform and JAVA_HOME for the real JDK XML/property helper.' : false
}, async t => {
  fs.mkdirSync(path.join(root, 'build'), {recursive: true});
  const directory = fs.mkdtempSync(path.join(root, 'build/first-install-test-'));
  t.after(() => fs.rmSync(directory, {recursive: true}));
  const source = path.join(directory, 'checkout with spaces'), home = path.join(directory, 'fresh target with spaces');
  for (const relative of ['tools', 'prebuilt', 'deployment', 'customization/DbCapture',
    'customization/configurations', 'package.json']) {
    fs.cpSync(path.join(root, relative), path.join(source, relative), {recursive: true});
  }
  const {metadataNames, sdkFiles} = await import('./prebuilt.mjs');
  for (const relative of [...sdkFiles, 'bin/swmaint.xml', 'bin/jsfrag_combine.xml',
    'ant/lib/ant-launcher.jar', 'bin/customizationTools/build.xml', 'bin/xconfmanager.bat']) {
    put(home, relative, 'Synthetic target fixture; not a vendor file.\n');
  }
  put(home, 'codebase/wt.properties', 'wt.db.maxBytesPerChar=3\n');
  put(home, 'codebase/presentation.properties',
    'netmarkets.presentation.jsFiles=custom/Unrelated/site.js\nnetmarkets.presentation.cssFiles=custom/Unrelated/site.css\n');
  put(home, 'codebase/netmarkets/javascript/util/main.js', '// Synthetic existing Windchill bundle.\n');
  put(home, 'servlet-fixture/javax/servlet/http/HttpServletRequest.class', 'Synthetic ZIP entry; never loaded.\n');
  const javaHome = fs.realpathSync(process.env.JAVA_HOME);
  const jar = spawnSync(path.join(javaHome, 'bin/jar.exe'),
    ['cf', path.join(home, 'tomcat/lib/servlet-api.jar'), '-C', path.join(home, 'servlet-fixture'), '.'], {encoding: 'utf8'});
  assert.equal(jar.status, 0, jar.stderr);
  const sdk = Object.fromEntries(sdkFiles.map(relative => [relative, sha(fs.readFileSync(path.join(home, relative)))]));
  const baselineFile = path.join(source, 'deployment/generated-model-baseline.json');
  const baseline = JSON.parse(fs.readFileSync(baselineFile, 'utf8'));
  baseline.sdk = sdk;
  fs.writeFileSync(baselineFile, JSON.stringify(baseline));
  const manifestFile = path.join(source, 'prebuilt/manifest.json');
  const manifest = JSON.parse(fs.readFileSync(manifestFile, 'utf8'));
  manifest.sdk = sdk;
  manifest.build.generatedModelBaselineSha256 = sha(fs.readFileSync(baselineFile));
  fs.writeFileSync(manifestFile, JSON.stringify(manifest));
  // Only the unavailable vendor version probe is simulated; XML/properties use the real JDK.
  const java = put(directory, 'java-probe.cmd', '@echo off\r\n'
    + 'echo %* | %SystemRoot%\\System32\\findstr.exe /c:"wt.util.version.WindchillVersion" >nul\r\n'
    + 'if not errorlevel 1 (echo 12.1.2.23 12.1 wnc.12.1.2.23 38& exit /b 0)\r\n'
    + `"${path.join(javaHome, 'bin/java.exe')}" %*\r\nexit /b %ERRORLEVEL%\r\n`, 0o700);
  const env = {home, javaHome, java, javac: path.join(javaHome, 'bin/javac.exe')};
  const {createPlan, assets, actionIcons, fingerprint} = await import(pathToFileURL(path.join(source, 'tools/dbninja.mjs')).href);
  const before = inventory(home);
  const planFile = createPlan(env, false, false, true);
  const plan = JSON.parse(fs.readFileSync(planFile, 'utf8'));
  const expected = [
    ...[assets.header, assets.csv, assets.diagnostics, assets.menuIcons, assets.css]
      .map(name => `codebase/custom/DbCapture/${name}`),
    ...actionIcons.map(icon => icon.runtime),
    'codebase/config/actions/DbCapture-actions.xml',
    'codebase/config/actions/DbCapture-actionModels.xml',
    'codebase/config/mvc/DbCapture-configs.xml',
    ...['dbCaptureAdmin.jsp', 'dbCaptureState.jsp', 'editDescription.jsp', 'captureDiagnostics.jsp', 'objectDetails.jsp']
      .map(name => `codebase/netmarkets/jsp/dbcapture/${name}`),
    'codebase/config/urlValidators/DbCapture-validators.xml',
    'custom/DbCapture/xconf/DbCapture.service.properties.xconf',
    'custom/xconf/DbNinja.xconf', 'custom/lib/DbCapture.jar',
    ...metadataNames.map(name => `codebase/com/custom/dbcapture/${name}`),
    `codebase/netmarkets/javascript/util/jsfrags/${assets.jsfrag}`,
    'codebase/customroleaccessprefs.xml'
  ].sort();
  assert.equal(expected.length, 28);
  assert.deepEqual(Object.keys(plan.files).sort(), expected);
  assert.equal(plan.artifactSource, 'prebuilt');
  assert.equal(plan.prebuiltVersion, JSON.parse(fs.readFileSync(path.join(source, 'package.json'))).version);
  assert.deepEqual(plan.settings, {});
  assert.deepEqual(plan.prerequisites, sdk);
  for (const [relative, hash] of Object.entries(plan.files)) {
    assert.equal(fingerprint(path.join(path.dirname(planFile), 'siteMod', relative)), hash, relative);
    assert.deepEqual(plan.before[relative], {hash: null, mode: null},
      `No installed DB Ninja resource should be needed: ${relative}`);
  }
  assert.equal(plan.files['custom/lib/DbCapture.jar'], manifest.artifacts['prebuilt/DbCapture.jar'].sha256);
  assert.doesNotMatch(expected.join('\n'), /\.sql$|schema-profile|overlay\/|\.svg$|main\.js$|site\.xconf$/m);
  assert.deepEqual(inventory(home), before);
  assert.equal(fs.existsSync(path.join(home, 'wtSafeArea')), false);
  const jsp = path.join(source, 'customization/DbCapture/main/src_web/custom/DbCapture/overlay/netmarkets/jsp/dbcapture/objectDetails.jsp');
  const jspBytes = fs.readFileSync(jsp);
  fs.unlinkSync(jsp);
  assert.throws(() => createPlan(env, false, false, true), /Missing source\/build artifact/);
  fs.writeFileSync(jsp, jspBytes);
  fs.unlinkSync(path.join(source, 'prebuilt/metadata/com/custom/dbcapture', metadataNames[0]));
  assert.throws(() => createPlan(env, false, false, true), /artifact mismatch/);
  assert.deepEqual(inventory(home), before, 'Even failed plans must leave the fresh target unchanged.');
});
