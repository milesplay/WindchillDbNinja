import fs from 'node:fs';
import path from 'node:path';
import {spawnSync} from 'node:child_process';
import {actionIcons, assets, bundle, configuration} from './dbninja.mjs';
import {readPrebuilt, verifyPrebuiltTarget} from './prebuilt.mjs';

const groups = ['scope', 'presentation', 'profiler', 'compatibility', 'smoke', 'web', 'icons', 'contracts'];
const requested = process.argv[2] || 'all';
const prebuilt = process.argv[3] === '--prebuilt';

function run(command, args, cwd = bundle) {
  const result = spawnSync(command, args, {cwd, stdio: 'inherit'});
  if (result.error) throw result.error;
  if (result.status !== 0) throw new Error(`${path.basename(command)} failed: exit ${result.status}, signal ${result.signal || 'none'}`);
}

function files(directory, suffix) {
  return fs.readdirSync(directory, {withFileTypes: true}).flatMap(entry => {
    const file = path.join(directory, entry.name);
    if (entry.isSymbolicLink()) throw new Error(`Unexpected source symlink: ${file}`);
    return entry.isDirectory() ? files(file, suffix) : entry.name.endsWith(suffix) ? [file] : [];
  });
}

function main() {
  if (!['all', 'unit', ...groups].includes(requested) || process.argv.length > 4
      || (process.argv[3] && !prebuilt)) {
    throw new Error(`Usage: node tools/validate.mjs all|unit|${groups.join('|')} [--prebuilt]`);
  }
  if (requested === 'all' || requested === 'unit') {
    run(process.execPath, ['tools/dbninja.mjs', 'test']);
    if (requested === 'unit') {
      if (prebuilt) readPrebuilt(bundle);
      return;
    }
  }
  if (!process.env.WT_HOME || !process.env.JAVA_HOME) throw new Error('Set WT_HOME and JAVA_HOME explicitly.');
  const home = fs.realpathSync(process.env.WT_HOME);
  const javaHome = fs.realpathSync(process.env.JAVA_HOME);
  const extension = process.platform === 'win32' ? '.exe' : '';
  const env = {home, javaHome, java: path.join(javaHome, `bin/java${extension}`),
    javac: path.join(javaHome, `bin/javac${extension}`)};
  if (prebuilt && process.env.DBC_JAR) throw new Error('Use --prebuilt or DBC_JAR, not both.');
  const candidate = prebuilt ? verifyPrebuiltTarget(env, readPrebuilt(bundle)) : null;
  const jar = candidate ? candidate.jar : process.env.DBC_JAR ? path.resolve(process.env.DBC_JAR) : path.join(bundle, 'build/DbCapture.jar');
  const jarExists = fs.existsSync(jar);
  if (!jarExists && requested !== 'icons') throw new Error('Build DB Ninja first, or set DBC_JAR explicitly to the reviewed candidate JAR.');
  const cp = [...(jarExists ? [jar] : []), path.join(home, 'codebase'), path.join(home, 'codebase/WEB-INF/lib/*'),
    path.join(home, 'lib/*'), path.join(home, 'srclib/tool/Annotations.jar')].join(path.delimiter);
  const outputRoot = path.join(bundle, 'build/validation');
  fs.mkdirSync(outputRoot, {recursive: true, mode: 0o700});
  const directory = fs.mkdtempSync(path.join(outputRoot, `${requested}-`));
  console.log(`Private validation output: ${directory}`);
  const compile = (name, sources, classpath = cp, options = []) => {
    const output = path.join(directory, name);
    fs.mkdirSync(output, {recursive: true});
    run(env.javac, ['-J-Xmx512m', '-encoding', 'UTF-8', '-proc:none', ...options,
      '-cp', classpath, '-d', output, ...sources.map(file => path.isAbsolute(file) ? file : path.join(bundle, 'tools', file))]);
    return [output, classpath].join(path.delimiter);
  };
  const execute = (classpath, name, args = [], options = []) => run(env.java,
    ['-Xmx512m', '-ea', `-Dwt.home=${home}`, ...options, '-cp', classpath, name, ...args], directory);
  for (const group of requested === 'all' ? groups : [requested]) {
    console.log(`=== ${group} ===`);
    if (group === 'scope') {
      const classpath = compile(group, ['ScopeFindTest.java', 'MonitoringScopeCatalogTest.java',
        'MonitoringScopeDdlTest.java', 'MigratePreferenceExclusion.java', 'PreferenceMigrationTest.java']);
      for (const name of ['ScopeFindTest', 'MonitoringScopeCatalogTest']) execute(classpath, `com.ptc.dbcapture.engine.${name}`);
      execute(classpath, 'com.ptc.dbcapture.engine.MonitoringScopeDdlTest', [home]);
      execute(classpath, 'com.ptc.dbcapture.PreferenceMigrationTest');
    } else if (group === 'presentation') {
      const classpath = compile(group, ['PresentationTest.java', 'ObjectPresentationTest.java']);
      execute(classpath, 'com.ptc.dbcapture.PresentationTest', [], ['-Duser.timezone=GMT', '-Ddbc.verifyInstalledRegistration=false']);
      execute(classpath, 'com.ptc.dbcapture.ObjectPresentationTest', [], ['-Duser.timezone=GMT']);
    } else if (group === 'profiler') {
      const logging = [path.join(home, 'srclib/log4j-api.jar'), path.join(home, 'srclib/log4j-core.jar')].join(path.delimiter);
      const sources = files(path.join(bundle, 'customization/DbCapture/main/src/com/ptc/dbcapture/diagnostics'), '.java');
      const classpath = compile(group, [...sources, 'ProfilerDiagnosticsTest.java', 'ProfilerApiCheck.java',
        'SqlEvidencePresentationTest.java'], logging, ['--release', '17', '-Xlint:all,-classfile', '-Werror']);
      execute(classpath, 'com.ptc.dbcapture.diagnostics.ProfilerDiagnosticsTest', [path.join(directory, 'evidence-fixtures')]);
      execute(classpath, 'com.ptc.dbcapture.diagnostics.SqlEvidencePresentationTest');
      execute([classpath, path.join(home, 'codebase'), path.join(home, 'srclib/*'),
        path.join(home, 'srclib/jmxcore/*'), path.join(home, 'tomcat/lib/*')].join(path.delimiter),
      'com.ptc.dbcapture.diagnostics.ProfilerApiCheck');
    } else if (group === 'compatibility') {
      const classpath = compile(group, ['DiagnosticsReportCompatibilityTest.java'], cp, ['--release', '17']);
      execute(classpath, 'com.ptc.dbcapture.DiagnosticsReportCompatibilityTest', [path.join(directory, 'dto-fixtures')]);
    } else if (group === 'smoke') {
      execute(compile(group, ['LocalSmokeTest.java']), 'com.ptc.dbcapture.engine.LocalSmokeTest');
    } else if (group === 'icons') {
      const resource = path.join(bundle, 'customization/DbCapture/main/src/com/ptc/dbcapture/dbCaptureActionResource.java');
      const classpath = compile(group, [resource, 'ActionIconTest.java'], cp, ['--release', '17']);
      execute(classpath, 'com.ptc.dbcapture.ActionIconTest',
        [path.join(bundle, 'customization/DbCapture/main/src_web/custom/DbCapture/icons'),
          ...actionIcons.map(icon => icon.file)], ['-Djava.awt.headless=true']);
    } else if (group === 'contracts') {
      const sources = files(path.join(bundle, 'customization/DbCapture/main/src/com/ptc/dbcapture'), '.java');
      const tests = ['CaptureContractAuditTest.java', 'CaptureHelperTest.java', 'CaptureLifecycleTest.java'];
      const variants = [
        ['source', compile(group, [...sources, ...tests], cp, ['--release', '17'])],
        ['candidate', compile('candidate-contracts', tests, cp, ['--release', '17'])]
      ];
      for (const [variant, classpath] of variants) {
        console.log(`--- contracts: ${variant} ---`);
        execute(classpath, 'com.ptc.dbcapture.engine.CaptureContractAuditTest',
          [path.join(directory, `${variant}-unused-fixture-home`)]);
        execute(classpath, 'com.ptc.dbcapture.CaptureHelperTest');
        execute(classpath, 'com.ptc.dbcapture.CaptureLifecycleTest',
          [path.join(directory, `${variant}-lifecycle-fixture`)]);
      }
    } else if (group === 'web') {
      const web = path.join(directory, 'web');
      const jsp = path.join(web, 'netmarkets/jsp/dbcapture');
      fs.mkdirSync(jsp, {recursive: true});
      const linkChildren = (source, target, skip) => {
        for (const entry of fs.readdirSync(source, {withFileTypes: true})) {
          if (entry.name === skip) continue;
          const from = path.join(source, entry.name), to = path.join(target, entry.name);
          if (fs.statSync(from).isDirectory()) fs.symlinkSync(from, to, 'junction');
          else fs.copyFileSync(from, to);
        }
      };
      linkChildren(path.join(home, 'codebase'), web, 'netmarkets');
      linkChildren(path.join(home, 'codebase/netmarkets'), path.join(web, 'netmarkets'), 'jsp');
      linkChildren(path.join(home, 'codebase/netmarkets/jsp'), path.join(web, 'netmarkets/jsp'), 'dbcapture');
      const client = path.join(bundle, 'customization/DbCapture/main/src_web/custom/DbCapture');
      const pages = files(path.join(client, 'overlay/netmarkets/jsp/dbcapture'), '.jsp');
      for (const page of pages) fs.copyFileSync(page, path.join(jsp, path.basename(page)));
      if (pages.length !== 5) throw new Error('Expected exactly five DB Ninja JSPs; review validation coverage.');
      const classpath = [cp, path.join(home, 'tomcat/lib/*'), path.join(home, 'tomcat/bin/tomcat-juli.jar'),
        path.join(home, 'ant/lib/ant.jar'), path.join(home, 'custom/lib/*')].join(path.delimiter);
      const generated = path.join(directory, 'jsp-generated');
      run(env.java, ['-Xmx512m', '-cp', classpath, 'org.apache.jasper.JspC',
        '-uriroot', web, '-d', generated, ...pages.map(page => path.join(jsp, path.basename(page)))]);
      compile('jsp-classes', files(generated, '.java'), classpath);
      for (const name of [assets.header, assets.csv, assets.diagnostics, assets.menuIcons]) run(process.execPath, ['--check', path.join(client, name)]);
      for (const folder of ['customization/DbCapture', 'customization/configurations', 'deployment']) {
        for (const suffix of ['.xml', '.xconf']) {
          for (const file of files(path.join(bundle, folder), suffix)) configuration(env, 'check', file, 'unused');
        }
      }
    }
  }
  console.log('PASS: requested validations completed. Offline tests do not replace runtime/browser or production-load acceptance.');
}

try {
  main();
} catch (error) {
  console.error(`ERROR: ${error.message}`);
  process.exitCode = 1;
}
