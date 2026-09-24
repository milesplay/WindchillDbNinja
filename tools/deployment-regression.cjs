const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const {spawnSync} = require('node:child_process');
const test = require('node:test');
const api = import('./dbninja.mjs');
const root = path.resolve(__dirname, '..');
const hasJava = Boolean(process.env.JAVA_HOME);
const exe = process.platform === 'win32' ? '.exe' : '';
const javaEnvironment = () => ({
  javaHome: process.env.JAVA_HOME,
  java: path.join(process.env.JAVA_HOME, `bin/java${exe}`),
  javac: path.join(process.env.JAVA_HOME, `bin/javac${exe}`)
});

function temp() {
  fs.mkdirSync(path.join(root, 'build'), {recursive: true});
  return fs.mkdtempSync(path.join(root, 'build', 'deployment-test-'));
}
function put(directory, relative, text) {
  const file = path.join(directory, relative);
  fs.mkdirSync(path.dirname(file), {recursive: true});
  fs.writeFileSync(file, text);
  return file;
}

function privatePlanDirectory(label, publicRead = false) {
  const backups = path.join(root, 'backups');
  fs.mkdirSync(backups, {recursive: true});
  const directory = fs.mkdtempSync(path.join(backups, `deployment-test-${label}-`));
  const runAcl = args => {
    const result = spawnSync('icacls.exe', [directory, ...args], {encoding: 'utf8'});
    assert.equal(result.status, 0, result.stderr || result.stdout || 'icacls failed');
  };
  const whoami = path.join(process.env.SystemRoot || 'C:/Windows', 'System32/whoami.exe');
  const identity = spawnSync(whoami, ['/user', '/fo', 'csv', '/nh'], {encoding: 'utf8'});
  assert.equal(identity.status, 0, identity.stderr || identity.error?.message || 'whoami failed');
  const userSid = identity.stdout.match(/S-1-5-[0-9-]+/)?.[0];
  assert.ok(userSid, 'whoami did not return the current user SID');
  runAcl(['/inheritance:r']);
  for (const principal of [`*${userSid}`, '*S-1-5-18', '*S-1-5-32-544']) {
    runAcl(['/grant:r', `${principal}:(OI)(CI)F`]);
  }
  if (publicRead) runAcl(['/grant:r', '*S-1-1-0:(OI)(CI)R']);
  return directory;
}

test('jsfrag initializes after load, survives late loading and never installs a second controller', async () => {
  const {createJsfrag} = await api;
  const code = createJsfrag('window.headerLoads++; window.DbCaptureHeader = {initialize:function(){window.starts++;}};',
    'window.csvLoads++; window.DbCaptureCsv = {};');
  for (const readyState of ['loading', 'complete']) {
    let onLoad;
    const context = {document: {readyState}, PTC: {navigation: {}}, Ext: {},
      window: {PTC: {navigation: {}}, Ext: {}, headerLoads: 0, csvLoads: 0, starts: 0,
        addEventListener(name, fn, options) {
          assert.equal(name, 'load');
          assert.equal(options.once, true);
          onLoad = fn;
        }}};
    vm.runInNewContext(code, context);
    if (readyState === 'loading') {
      assert.equal(context.window.headerLoads, 0);
      onLoad();
    }
    assert.equal(context.window.headerLoads, 1);
    assert.equal(context.window.csvLoads, 1);
    assert.equal(context.window.starts, 1);
    context.document.readyState = 'complete';
    vm.runInNewContext(code, context);
    assert.equal(context.window.headerLoads, 1);
    assert.equal(context.window.csvLoads, 1);
  }
});

test('missing shell APIs fail explicitly without running the controllers', async () => {
  const {createJsfrag} = await api;
  const errors = [];
  const console = {error: message => errors.push(message)};
  vm.runInNewContext(createJsfrag('throw Error("must not run");', 'throw Error("must not run");'),
    {window: {console}, console, document: {readyState: 'complete'}});
  assert.equal(errors.length, 1);
  assert.match(errors[0], /unavailable/);
});

test('paths reject traversal, absolute paths and symlink escapes', async () => {
  const {confined} = await api;
  const directory = temp();
  try {
    for (const relative of ['../outside', '/etc/passwd', 'a/../b', 'a\\b', 'a//b', './a', 'C:/outside', 'C:outside']) {
      assert.throws(() => confined(directory, relative), /Invalid relative/);
    }
    assert.equal(confined(directory, 'codebase/custom/DbCapture/test.js'),
      path.join(directory, 'codebase/custom/DbCapture/test.js'));
    if (process.platform !== 'win32') {
      fs.symlinkSync(path.dirname(directory), path.join(directory, 'outside'));
      assert.throws(() => confined(directory, 'outside/file'), /symbolic/);
      fs.symlinkSync(path.join(directory, 'not-created'), path.join(directory, 'dangling'));
      assert.throws(() => confined(directory, 'dangling'), /symbolic/);
      assert.throws(() => confined(path.join(directory, 'outside'), 'file'), /symbolic/);
    }
  } finally {
    fs.rmSync(directory, {recursive: true});
  }
});

test('CCD backup snapshots generated metadata and configuration before a target build', async () => {
  const {fingerprint, snapshotBuildFiles} = await api;
  const directory = temp();
  const home = path.join(directory, 'target');
  const backup = path.join(directory, 'private-backup');
  try {
    const metadata = 'codebase/com/custom/dbcapture/DbCaptureSession.ClassInfo.ser';
    const configuration = 'codebase/wt.properties';
    put(home, metadata, 'original model');
    put(home, configuration, 'original configuration');
    const before = snapshotBuildFiles({home}, backup, [metadata, configuration, 'site.xconf']);
    for (const relative of [metadata, configuration]) {
      assert.equal(before[relative].hash, fingerprint(path.join(home, relative)));
      assert.equal(fingerprint(path.join(backup, 'before', relative)), before[relative].hash);
    }
    assert.deepEqual(before['site.xconf'], {hash: null, mode: null});
    put(home, metadata, 'CCD generated a new model');
    assert.equal(fingerprint(path.join(backup, 'before', metadata)), before[metadata].hash);
  } finally {
    fs.rmSync(directory, {recursive: true});
  }
});

test('clean declarative defaults contain no site-specific example/security configuration', () => {
  const xconf = fs.readFileSync(path.join(root, 'deployment/DbNinja.xconf'), 'utf8');
  assert.doesNotMatch(xconf, /ConfigurableLink|SecurityLabel|netmarkets.presentation.jsFiles/);
  assert.match(xconf, /default="5000"/);
  assert.match(xconf, /name="wt.services.service.905000"\s+default=/);
  assert.doesNotMatch(xconf, /<Property\b[^>]*\bvalue=/);
  assert.match(xconf, /DbCapture.service.properties.xconf/);
  const ccd = fs.readFileSync(path.join(root, 'customization/configurations/xconf/custom.site.xconf'), 'utf8');
  assert.doesNotMatch(ccd, /<Property|<AddToProperty|<ConfigurationRef/);
});

test('header and Site action models explicitly merge with OOTB navigation', () => {
  const models = fs.readFileSync(path.join(root,
    'customization/DbCapture/main/src_web/config/actions/DbCapture-actionModels.xml'), 'utf8');
  for (const name of ['header actions', 'site navigation']) {
    assert.match(models, new RegExp(`<model name="${name}" incremental="true">`));
  }
  assert.doesNotMatch(models, /<model name="(?:header actions|site navigation)" incremental=""/);
});

test('Wex profile removes only owned menu shadows and refuses destructive output',
  {skip: !hasJava && 'Set JAVA_HOME to run Wex profile checks.'}, async () => {
    const {configuration, fingerprint} = await api;
    const directory = temp();
    const env = javaEnvironment();
    try {
      const sourceFile = path.join(root,
        'customization/DbCapture/main/src_web/config/actions/DbCapture-actionModels.xml');
      const source = fs.readFileSync(sourceFile, 'utf8');
      const before = fingerprint(sourceFile);
      const fixture = source.replace('</actionmodels>',
        '<model name="other tools"><action name="otherAction" type="other"/></model></actionmodels>');
      const input = put(directory, 'models.xml', fixture);
      const output = path.join(directory, 'wex-models.xml');
      configuration(env, 'wex-models', input, output);
      const result = fs.readFileSync(output, 'utf8');
      assert.doesNotMatch(result, /<model\b[^>]*name="(?:header actions|site navigation)"/);
      assert.match(result, /name="dbcapture session table toolbar"/);
      assert.match(result, /name="dbcapture change table toolbar"/);
      assert.match(result, /name="other tools"/);
      for (const action of ['editDbCaptureDescription', 'deleteDbCaptureSession',
        'exportDbCaptureChangesCsv', 'otherAction']) assert.match(result, new RegExp(`name="${action}"`));
      assert.equal((result.match(/<model\b/g) || []).length, 3);
      assert.equal(fingerprint(sourceFile), before);
      assert.throws(() => configuration(env, 'wex-models', input, output), /failed/);
      assert.equal(fs.readFileSync(output, 'utf8'), result);
      for (const [name, invalid] of [
        ['foreign-action', fixture.replace('name="startDbCapture"', 'name="siteSpecific"')],
        ['model-filter', fixture.replace('name="header actions"', 'name="header actions" resourceBundle="other"')],
        ['action-attribute', fixture.replace('name="startDbCapture"', 'name="startDbCapture" shortcut="true"')],
        ['inline-action', fixture.replace(/(<action name="startDbCapture"[\s\S]*?)\/>/,
          '$1><includeFilter name="siteFilter"/></action>')],
        ['missing-model', fixture.replace('name="site navigation"', 'name="other navigation"')],
        ['duplicate-model', fixture.replace('</actionmodels>',
          '<model name="site navigation" incremental="true"><action name="dbCaptureAdmin" type="dbcapture"/></model></actionmodels>')]
      ]) {
        const invalidInput = put(directory, `${name}.xml`, invalid);
        const invalidOutput = path.join(directory, `${name}-output.xml`);
        assert.throws(() => configuration(env, 'wex-models', invalidInput, invalidOutput), /failed/);
        assert.equal(fs.existsSync(invalidOutput), false);
      }
    } finally {
      fs.rmSync(directory, {recursive: true});
    }
  });

test('resolved Wex models preserve current standard and third-party actions without incremental loading',
  {skip: !hasJava && 'Set JAVA_HOME to run Wex model resolution checks.'}, async () => {
    const {configuration, fingerprint} = await api;
    const directory = temp();
    const env = javaEnvironment();
    try {
      const source = path.join(root, 'customization/DbCapture/main/src_web/config/actions/DbCapture-actionModels.xml');
      const baseline = put(directory, 'navigation.xml', '<actionmodels resourceBundle="fixtureBundle">'
        + '<model name="header actions"><submodel name="help"/><action name="clipboard" type="netmarkets"/>'
        + '<action name="separator" type="separator"/><action name="settings" type="user"/>'
        + '<action name="otherHeader" type="thirdparty"/></model>'
        + '<model name="site navigation"><action name="listFiles" type="site"/>'
        + '<action name="listUtilities" type="site"/><action name="otherSite" type="thirdparty"/></model></actionmodels>');
      const before = fingerprint(baseline);
      const output = path.join(directory, 'resolved.xml');
      configuration(env, 'wex-resolve', source, output, baseline);
      const result = fs.readFileSync(output, 'utf8');
      for (const name of ['help', 'clipboard', 'settings', 'otherHeader', 'listFiles', 'listUtilities', 'otherSite',
        'startDbCapture', 'stopDbCapture', 'dbCaptureAdmin', 'editDbCaptureDescription',
        'deleteDbCaptureSession', 'exportDbCaptureChangesCsv']) {
        assert.equal((result.match(new RegExp(`name="${name}"`, 'g')) || []).length, 1, name);
      }
      assert.doesNotMatch(result, /<model\b[^>]*\bincremental=/);
      assert.match(result, /DOCTYPE actionmodels SYSTEM "actionmodels.dtd"/);
      assert.doesNotMatch(result, /<action\b[^>]*\binsertAfter/);
      assert.ok(result.indexOf('name="clipboard"') < result.indexOf('name="startDbCapture"'));
      assert.ok(result.indexOf('name="startDbCapture"') < result.indexOf('name="stopDbCapture"'));
      assert.ok(result.indexOf('name="stopDbCapture"') < result.indexOf('name="settings"'));
      assert.ok(result.indexOf('name="listUtilities"') < result.indexOf('name="dbCaptureAdmin"'));
      assert.equal((result.match(/resourceBundle="fixtureBundle"/g) || []).length, 2);
      assert.equal(fingerprint(baseline), before);
      const missingAnchor = put(directory, 'missing-anchor.xml', fs.readFileSync(baseline, 'utf8')
        .replace('name="clipboard"', 'name="renamedClipboard"'));
      assert.throws(() => configuration(env, 'wex-resolve', source,
        path.join(directory, 'invalid.xml'), missingAnchor), /failed/);
      assert.equal(fs.existsSync(path.join(directory, 'invalid.xml')), false);
      const updated = put(directory, 'updated.xml', fs.readFileSync(baseline, 'utf8')
        .replace('name="listFiles"', 'name="newReleaseAction"'));
      const regenerated = path.join(directory, 'regenerated.xml');
      configuration(env, 'wex-resolve', source, regenerated, updated);
      assert.match(fs.readFileSync(regenerated, 'utf8'), /name="newReleaseAction"/);
      assert.doesNotMatch(fs.readFileSync(regenerated, 'utf8'), /name="listFiles"/);
    } finally {
      fs.rmSync(directory, {recursive: true});
    }
  });

test('XML merges preserve unrelated nodes, comments, Unicode and exact site values',
  {skip: !hasJava && 'Set JAVA_HOME to run JDK XML integration checks.'}, async () => {
    const {configuration} = await api;
    const directory = temp();
    const env = javaEnvironment();
    try {
      const input = put(directory, 'shared.xconf', '<?xml version="1.0" encoding="UTF-8"?>\n'
        + '<!DOCTYPE Configuration SYSTEM "xconf.dtd">\n'
        + '<Configuration xmlns:xlink="http://www.w3.org/1999/xlink"><!-- preserve me -->'
        + '<Property name="site.example" value="A &amp; B \u65e5\u672c" targetFile="codebase/wt.properties"/>'
        + '<Property name="wt.services.service.905000" value="com.custom.dbcapture.DbCaptureService/com.custom.dbcapture.StandardDbCaptureService"/>'
        + '<Property name="com.custom.dbcapture.maxRowsPerTable" value="321"/>'
        + '<AddToProperty name="netmarkets.presentation.jsFiles" value="custom/Unrelated/main.js"/>'
        + '<AddToProperty name="netmarkets.presentation.jsFiles" value="custom/DbCapture/dbCaptureHeader-v1.js"/>'
        + '<AddToProperty name="netmarkets.presentation.cssFiles" value="custom/DbCapture/dbCapture-v1.css"/>'
        + '</Configuration>');
      const output = path.join(directory, 'merged.xconf');
      configuration(env, 'migrate', input, output);
      const text = fs.readFileSync(output, 'utf8');
      assert.match(text, /preserve me/);
      assert.match(text, /A &amp; B \u65e5\u672c/);
      assert.match(text, /custom\/Unrelated\/main.js/);
      assert.doesNotMatch(text, /com.custom.dbcapture|custom\/DbCapture|905000/);
      assert.match(text, /DOCTYPE Configuration SYSTEM "xconf.dtd"/);
      configuration(env, 'migrate', output, path.join(directory, 'twice.xconf'));
      assert.equal(fs.readFileSync(path.join(directory, 'twice.xconf'), 'utf8'), text);
      const role = put(directory, 'roles.xml', '<uics><global labelId="globalLabel" incremental="true">'
        + '<uic name="OTHER" defaultAll="false"/></global></uics>');
      configuration(env, 'role', role, output);
      configuration(env, 'role', output, path.join(directory, 'twice.xconf'));
      const merged = fs.readFileSync(path.join(directory, 'twice.xconf'), 'utf8');
      assert.equal((merged.match(/DB_CAPTURE_ADMIN/g) || []).length, 1);
      assert.match(merged, /name="OTHER"/);
      assert.match(merged, /defaultAll="false" name="DB_CAPTURE_ADMIN"/);
      const duplicate = put(directory, 'duplicate.xml', '<uics><global labelId="globalLabel" incremental="true">'
        + '<uic name="DB_CAPTURE_ADMIN" defaultAll="true"/></global></uics>');
      assert.throws(() => configuration(env, 'role', duplicate, output), /failed/);
      const scalar = put(directory, 'scalar.xconf',
        '<Configuration><Property name="netmarkets.presentation.jsFiles" value="custom/DbCapture/dbCaptureHeader.js;other.js"/></Configuration>');
      assert.throws(() => configuration(env, 'migrate', scalar, output), /failed/);
    } finally {
      fs.rmSync(directory, {recursive: true});
    }
  });

test('planning stages the complete additive package without changing the target; drift blocks apply',
  {skip: !hasJava && 'Set JAVA_HOME to run JDK deployment integration checks.'}, async () => {
    const {actionIcons, createPlan, applyPlan, fingerprint} = await api;
    const directory = temp();
    let planFile;
    const env = {...javaEnvironment(), home: directory};
    const originalApproval = process.env.DBNINJA_MAINTENANCE_APPROVED;
    const originalOracle = process.env.DBNINJA_ORACLE_CONFIRMED;
    const originalPlanRoot = process.env.DBNINJA_PRIVATE_PLAN_ROOT;
    let privatePlanRoot;
    let publicPlanRoot;
    try {
      if (process.platform === 'win32') {
        privatePlanRoot = privatePlanDirectory('private');
        publicPlanRoot = privatePlanDirectory('public', true);
      }
      put(directory, 'codebase/wt.properties',
        'wt.services.service.905000=com.custom.dbcapture.DbCaptureService/com.custom.dbcapture.StandardDbCaptureService\n'
        + 'com.custom.dbcapture.maxRowsPerTable=321\ncom.custom.dbcapture.correlateLogs=false\n');
      put(directory, 'codebase/presentation.properties',
        'netmarkets.presentation.jsFiles=custom/Unrelated/main.js;custom/DbCapture/dbCaptureHeader-v1.js\n'
        + 'netmarkets.presentation.cssFiles=custom/Unrelated/site.css\n');
      put(directory, 'codebase/customroleaccessprefs.xml',
        '<uics><global labelId="globalLabel" incremental="true"><uic name="OTHER" defaultAll="false"/></global></uics>');
      fs.chmodSync(path.join(directory, 'codebase/customroleaccessprefs.xml'), 0o600);
      for (const relative of ['bin/swmaint.xml', 'bin/jsfrag_combine.xml',
        'ant/lib/ant-launcher.jar', 'bin/customizationTools/build.xml', 'custom/lib/DbCapture.jar',
        process.platform === 'win32' ? 'bin/xconfmanager.bat' : 'bin/xconfmanager']) {
        put(directory, relative, 'fixture');
      }
      put(directory, 'codebase/netmarkets/javascript/util/main.js', 'original main');
      const names = ['DbCaptureAttrDelta', 'DbCaptureChange', 'DbCaptureSession', 'DbCaptureTableChange',
        'DbCaptureChangeDeltaLink', 'DbCaptureSessionChangeLink', 'DbCaptureSessionTableLink'];
      for (const name of names) put(directory, `codebase/com/custom/dbcapture/${name}.ClassInfo.ser`, 'fixture');
      put(directory, 'servlet-fixture/jakarta/servlet/http/HttpServletRequest.class', 'fixture');
      fs.mkdirSync(path.join(directory, 'tomcat/lib'), {recursive: true});
      const jar = spawnSync(path.join(env.javaHome, `bin/jar${exe}`),
        ['cf', path.join(directory, 'tomcat/lib/servlet-api.jar'), '-C', path.join(directory, 'servlet-fixture'), '.']);
      assert.equal(jar.status, 0);
      const original = fingerprint(path.join(directory, 'codebase/wt.properties'));
      if (process.platform === 'win32') {
        delete process.env.DBNINJA_PRIVATE_PLAN_ROOT;
        assert.throws(() => createPlan(env, true), /DBNINJA_PRIVATE_PLAN_ROOT/);
        process.env.DBNINJA_PRIVATE_PLAN_ROOT = publicPlanRoot;
        assert.throws(() => createPlan(env, true), /ACL is not verified/);
      }
      process.env.DBNINJA_PRIVATE_PLAN_ROOT = path.join(root, 'build', 'unrestricted-plan-output');
      assert.throws(() => createPlan(env, true), /below the private backups root/);
      if (process.platform === 'win32') process.env.DBNINJA_PRIVATE_PLAN_ROOT = privatePlanRoot;
      else if (originalPlanRoot === undefined) delete process.env.DBNINJA_PRIVATE_PLAN_ROOT;
      else process.env.DBNINJA_PRIVATE_PLAN_ROOT = originalPlanRoot;
      planFile = createPlan(env, true);
      assert.equal(fingerprint(path.join(directory, 'codebase/wt.properties')), original);
      assert.equal(fs.existsSync(path.join(directory, 'wtSafeArea')), false);
      const plan = JSON.parse(fs.readFileSync(planFile, 'utf8'));
      assert.equal(plan.settings['com.custom.dbcapture.maxRowsPerTable'], '321');
      if (process.platform !== 'win32') {
        assert.equal(fs.statSync(path.join(path.dirname(planFile), 'siteMod/codebase/customroleaccessprefs.xml')).mode & 0o777, 0o600);
      }
      assert.ok(Object.hasOwn(plan.files, 'codebase/netmarkets/javascript/util/jsfrags/dbNinja.jsfrag'));
      assert.ok(Object.hasOwn(plan.files, 'custom/xconf/DbNinja.xconf'));
      assert.equal(Object.keys(plan.files).filter(name => name.endsWith('.ClassInfo.ser')).length, 7);
      assert.equal(Object.keys(plan.files).filter(name => name.endsWith('.jsp')).length, 5);
      assert.equal(Object.keys(plan.files).filter(name => name.endsWith('.png')).length, 2);
      for (const icon of actionIcons) {
        assert.equal(plan.files[icon.runtime], icon.sha256);
        assert.equal(fingerprint(path.join(path.dirname(planFile), 'siteMod', icon.runtime)), icon.sha256);
        if (process.platform !== 'win32') {
          assert.equal(fs.statSync(path.join(path.dirname(planFile), 'siteMod', icon.runtime)).mode & 0o777, 0o644);
        }
      }
      assert.ok(!Object.keys(plan.files).some(name => /\.svg$|\/(?:start|stop)\.gif$/.test(name)));
      assert.ok(!Object.keys(plan.files).some(name => /main\.js$|log4j|listBusinessAdminUtilities|site\.xconf$/.test(name)));
      process.env.DBNINJA_MAINTENANCE_APPROVED = 'yes';
      process.env.DBNINJA_ORACLE_CONFIRMED = 'yes';
      const unexpected = put(path.join(path.dirname(planFile), 'siteMod'), 'codebase/unreviewed.properties', 'not approved');
      assert.throws(() => applyPlan(env, planFile), /Unexpected stage contents/);
      fs.unlinkSync(unexpected);
      for (const relative of Object.keys(plan.files)) {
        const target = path.join(directory, relative);
        fs.mkdirSync(path.dirname(target), {recursive: true});
        fs.copyFileSync(path.join(path.dirname(planFile), 'siteMod', relative), target);
      }
      const staticFile = createPlan(env, true, true);
      try {
        const staticPlan = JSON.parse(fs.readFileSync(staticFile, 'utf8'));
        assert.equal(staticPlan.javascriptOnly, true);
        assert.equal(Object.keys(staticPlan.files).length, 4);
        assert.ok(Object.keys(staticPlan.files).every(relative => /\.(js|jsfrag)$/.test(relative)));
        assert.ok(!Object.keys(staticPlan.before).some(relative => /(?:^|\/)(site\.xconf|wt\.properties|service\.properties|presentation\.properties)$/.test(relative)));
        assert.ok(Object.keys(staticPlan.prerequisites).some(relative => relative.endsWith('.jsp')));
        put(directory, 'codebase/config/actions/DbCapture-actions.xml', 'concurrent edit');
        assert.throws(() => applyPlan(env, staticFile), /Non-JavaScript prerequisite changed/);
      } finally {
        fs.rmSync(path.dirname(staticFile), {recursive: true});
      }
      put(directory, 'codebase/wt.properties', 'someone changed this');
      assert.throws(() => applyPlan(env, planFile), /Concurrent change/);
      assert.equal(fs.existsSync(path.join(directory, 'wtSafeArea')), false);
    } finally {
      if (originalApproval === undefined) delete process.env.DBNINJA_MAINTENANCE_APPROVED;
      else process.env.DBNINJA_MAINTENANCE_APPROVED = originalApproval;
      if (originalOracle === undefined) delete process.env.DBNINJA_ORACLE_CONFIRMED;
      else process.env.DBNINJA_ORACLE_CONFIRMED = originalOracle;
      if (originalPlanRoot === undefined) delete process.env.DBNINJA_PRIVATE_PLAN_ROOT;
      else process.env.DBNINJA_PRIVATE_PLAN_ROOT = originalPlanRoot;
      if (planFile) fs.rmSync(path.dirname(planFile), {recursive: true});
      if (privatePlanRoot) fs.rmSync(privatePlanRoot, {recursive: true, force: true});
      if (publicPlanRoot) fs.rmSync(publicPlanRoot, {recursive: true, force: true});
      fs.rmSync(directory, {recursive: true});
    }
  });
