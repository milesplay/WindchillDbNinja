import fs from 'node:fs';
import path from 'node:path';
import {spawnSync} from 'node:child_process';
import {fileURLToPath} from 'node:url';
import {actionIconDefinitions, validateActionIcon} from './icon-assets.mjs';
import {confined, fingerprint} from './filesystem.mjs';
import {metadataNames, readPrebuilt, verifyPrebuiltTarget} from './prebuilt.mjs';
export {confined, fingerprint} from './filesystem.mjs';

export const bundle = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
export const assets = JSON.parse(fs.readFileSync(path.join(bundle, 'deployment/assets.json'), 'utf8'));
export const actionIcons = actionIconDefinitions(assets);
const moduleRoot = 'customization/DbCapture/main';
const clientRoot = `${moduleRoot}/src_web/custom/DbCapture`;
const service = 'com.custom.dbcapture.DbCaptureService/com.custom.dbcapture.StandardDbCaptureService';
const settingKeys = ['com.custom.dbcapture.excludeTables', 'com.custom.dbcapture.maxRowsPerTable',
  'com.custom.dbcapture.correlateLogs'];
const generatedJs = ['main.js', 'windchill-all-debug.js', 'windchill-all.js',
  'ext-and-extensions-debug.js', 'ext-and-extensions.js', 'windchill-libs-debug.js',
  'windchill-libs.js', 'jstable-all-debug.js', 'jstable-all.js',
  'windchill-typemanager-debug.js', 'windchill-typemanager.js'];
function mkdir(directory, mode = 0o700) {
  fs.mkdirSync(directory, {recursive: true, mode});
}

function copy(source, target, mode) {
  mkdir(path.dirname(target));
  fs.copyFileSync(source, target);
  fs.chmodSync(target, mode ?? (fs.statSync(source).mode & 0o777));
}

function save(file, value) {
  mkdir(path.dirname(file));
  fs.writeFileSync(file, `${JSON.stringify(value, null, 2)}\n`, {mode: 0o600});
}

function stamp() {
  return `${new Date().toISOString().replace(/[:.]/g, '-')}-${process.pid}`;
}

function environment() {
  if (!process.env.WT_HOME || !process.env.JAVA_HOME) {
    throw new Error('Set WT_HOME and JAVA_HOME explicitly to the reviewed target and its supported JDK.');
  }
  const home = fs.realpathSync(process.env.WT_HOME);
  const javaHome = fs.realpathSync(process.env.JAVA_HOME);
  const exe = process.platform === 'win32' ? '.exe' : '';
  return {home, javaHome, java: path.join(javaHome, `bin/java${exe}`),
    javac: path.join(javaHome, `bin/javac${exe}`)};
}

function run(command, args, cwd, capture = false) {
  let executable = command, parameters = args;
  let windowsVerbatimArguments = false;
  if (process.platform === 'win32' && /\.(bat|cmd)$/i.test(command)) {
    const values = [command, ...args];
    if (values.some(value => /["%!&|<>^\r\n]/.test(value))) {
      throw new Error('Windows batch arguments contain command metacharacters; use a reviewed simple installation path/value.');
    }
    executable = process.env.ComSpec || 'cmd.exe';
    parameters = ['/d', '/s', '/c', `"${values.map(value => `"${value}"`).join(' ')}"`];
    windowsVerbatimArguments = true;
  }
  const result = spawnSync(executable, parameters, {cwd, encoding: 'utf8',
    stdio: capture ? 'pipe' : 'inherit', maxBuffer: 16 * 1024 * 1024,
    ...(windowsVerbatimArguments ? {windowsVerbatimArguments: true} : {})});
  if (result.error) throw result.error;
  if (result.status !== 0) {
    if (capture) process.stderr.write(`${result.stdout || ''}${result.stderr || ''}`);
    throw new Error(`${path.basename(command)} failed (exit ${result.status}, signal ${result.signal || 'none'}).`);
  }
  return result.stdout || '';
}

function ant(env, script, targets, extra = []) {
  return run(env.java, ['-cp', path.join(env.home, 'ant/lib/ant-launcher.jar'),
    'org.apache.tools.ant.launch.Launcher', '-f', path.join(env.home, script), ...extra, ...targets],
  path.dirname(path.join(env.home, script)));
}

function xconf(env, args, capture = false) {
  const wrapper = path.join(env.home, process.platform === 'win32' ? 'bin/xconfmanager.bat' : 'bin/xconfmanager');
  return run(wrapper, args, env.home, capture);
}

export function describeProperty(env, name) {
  return xconf(env, ['-d', name], true);
}

export function configuration(env, operation, input, output) {
  const directory = path.join(bundle, 'build/tooling');
  const source = path.join(bundle, 'tools/ConfigurationFiles.java');
  const compiled = path.join(directory, 'ConfigurationFiles.class');
  mkdir(directory);
  if (!fs.existsSync(compiled) || fs.statSync(compiled).mtimeMs < fs.statSync(source).mtimeMs) {
    run(env.javac, ['--release', '11', '-encoding', 'UTF-8', '-Xlint:all', '-Werror', '-d', directory, source], bundle);
  }
  return run(env.java, ['-cp', directory, 'ConfigurationFiles', operation, input, output], bundle, true);
}

function properties(env, relative, keys) {
  const output = configuration(env, 'properties', confined(env.home, relative), keys.join(','));
  return Object.fromEntries(output.trim().split(/\r?\n/).filter(Boolean).map(line => {
    const separator = line.indexOf('=');
    return [line.slice(0, separator), Buffer.from(line.slice(separator + 1), 'base64').toString('utf8')];
  }));
}

export function createJsfrag(header, csv, menuIcons = '') {
  return `/* DBNINJA_JSFRAG_BEGIN: generated from deployment/assets.json; do not edit main.js. */
(function () {
   function installDbNinja() {
      if (!window.PTC || !PTC.navigation || !window.Ext) {
         if (window.console) { console.error("DB Ninja: Windchill shell APIs are unavailable; controls were not initialized."); }
         return;
      }
      if (!window.DbCaptureHeader) {
${header}
      }
      if (!window.DbCaptureCsv) {
${csv}
      }
      if (!window.DbNinjaActionIcons) {
${menuIcons}
      }
      if (typeof window.DbCaptureHeader.initialize === "function") {
         window.DbCaptureHeader.initialize();
      }
      if (window.DbNinjaActionIcons && typeof window.DbNinjaActionIcons.initialize === "function") {
         window.DbNinjaActionIcons.initialize();
      }
   }
   if (document.readyState === "complete") { installDbNinja(); }
   else { window.addEventListener("load", installDbNinja, {once: true}); }
})();
/* DBNINJA_JSFRAG_END */
`;
}

function maintenance() {
  if (process.env.DBNINJA_MAINTENANCE_APPROVED !== 'yes') {
    throw new Error('Maintenance approval required: set DBNINJA_MAINTENANCE_APPROVED=yes only after authorization.');
  }
}

function preflight(env) {
  if (Number(process.versions.node.split('.')[0]) < 22) throw new Error('Node.js 22 or newer is required.');
  for (const relative of ['codebase/wt.properties', 'codebase/presentation.properties',
    'bin/swmaint.xml', 'bin/jsfrag_combine.xml', 'ant/lib/ant-launcher.jar',
    'bin/customizationTools/build.xml', 'tomcat/lib/servlet-api.jar']) {
    if (!fs.existsSync(confined(env.home, relative))) throw new Error(`Missing prerequisite: ${relative}`);
  }
  if (fs.existsSync(path.join(env.home, 'codebase.war'))) {
    throw new Error('This package targets the traditional codebase layout; codebase.war requires separate qualification.');
  }
  run(env.java, ['-version'], bundle);
  run(env.javac, ['-version'], bundle);
  const jar = path.join(env.javaHome, process.platform === 'win32' ? 'bin/jar.exe' : 'bin/jar');
  const servletClasses = run(jar, ['tf', path.join(env.home, 'tomcat/lib/servlet-api.jar')], bundle, true);
  if (!servletClasses.includes('javax/servlet/http/HttpServletRequest.class')) {
    throw new Error('This port requires the Windchill 12.1 javax.servlet API.');
  }
  const current = properties(env, 'codebase/wt.properties', ['wt.services.service.905000', ...settingKeys]);
  if (current['wt.services.service.905000'] && current['wt.services.service.905000'] !== service) {
    throw new Error('Service slot 905000 is already owned by another customization. Do not overwrite it.');
  }
  if (current['com.custom.dbcapture.correlateLogs'] === 'true') {
    throw new Error('Legacy SQL correlation is enabled. Review and disable it explicitly before adopting this package.');
  }
  console.log('PASS: Windows filesystem/toolchain checks. This does NOT verify Oracle privileges, undo, schema or a maintenance window.');
  console.log('Oracle-only: perform the database checks in COMPATIBILITY.md before building/activating.');
  return current;
}

function inspectToolchain(env) {
  return Object.fromEntries(['bin/swmaint.xml', 'bin/jsfrag_combine.xml',
    process.platform === 'win32' ? 'bin/xconfmanager.bat' : 'bin/xconfmanager']
    .map(relative => [relative, fingerprint(confined(env.home, relative))]));
}

export function createPlan(env, reuseInstalled = false, javascriptOnly = false, prebuilt = false) {
  if (prebuilt && (reuseInstalled || javascriptOnly)) throw new Error('Choose one artifact source for the plan.');
  const current = preflight(env);
  const candidate = prebuilt ? verifyPrebuiltTarget(env, readPrebuilt(bundle)) : null;
  if (reuseInstalled && current['wt.services.service.905000'] !== service) {
    throw new Error('--reuse-installed is only for an existing DB Ninja installation.');
  }
  const directory = path.join(bundle, 'build', `plan-${stamp()}`);
  mkdir(directory);
  const siteMod = path.join(directory, 'siteMod');
  const plan = {format: 1, target: env.home, created: new Date().toISOString(),
    reuseInstalled, javascriptOnly, artifactSource: prebuilt ? 'prebuilt' : reuseInstalled ? 'installed' : 'target-build',
    toolchain: inspectToolchain(env), files: {}, before: {}, prerequisites: {}, settings: {},
    presentation: properties(env, 'codebase/presentation.properties',
      ['netmarkets.presentation.jsFiles', 'netmarkets.presentation.cssFiles'])};
  if (candidate) {
    plan.prebuiltManifestHash = fingerprint(candidate.manifestFile);
    plan.prebuiltVersion = candidate.manifest.version;
    Object.assign(plan.prerequisites, candidate.manifest.sdk);
  }
  const add = (source, relative) => {
    const target = confined(siteMod, relative);
    if (!fs.existsSync(source)) throw new Error(`Missing source/build artifact: ${source}`);
    copy(source, target, 0o644);
    plan.files[relative] = fingerprint(target);
  };
  const source = relative => confined(bundle, relative);
  for (const name of [assets.header, assets.csv, assets.diagnostics, assets.menuIcons, assets.css]) {
    add(source(`${clientRoot}/${name}`), `codebase/custom/DbCapture/${name}`);
  }
  for (const icon of actionIcons) {
    const file = source(icon.png);
    validateActionIcon(fs.readFileSync(file), icon);
    add(file, icon.runtime);
  }
  for (const [folder, names] of [
    ['actions', ['DbCapture-actions.xml', 'DbCapture-actionModels.xml']], ['mvc', ['DbCapture-configs.xml']]
  ]) {
    for (const name of names) add(source(`${moduleRoot}/src_web/config/${folder}/${name}`), `codebase/config/${folder}/${name}`);
  }
  const overlay = source(`${clientRoot}/overlay`);
  for (const name of ['dbCaptureAdmin.jsp', 'dbCaptureState.jsp', 'editDescription.jsp',
    'captureDiagnostics.jsp', 'objectDetails.jsp']) {
    add(path.join(overlay, 'netmarkets/jsp/dbcapture', name), `codebase/netmarkets/jsp/dbcapture/${name}`);
  }
  add(path.join(overlay, 'DbCapture-validators.xml'), 'codebase/config/urlValidators/DbCapture-validators.xml');
  add(source(`${moduleRoot}/xconf/DbCapture.service.properties.xconf`),
    'custom/DbCapture/xconf/DbCapture.service.properties.xconf');
  add(source('deployment/DbNinja.xconf'), 'custom/xconf/DbNinja.xconf');
  const jar = candidate ? candidate.jar
    : reuseInstalled ? confined(env.home, 'custom/lib/DbCapture.jar') : source('build/DbCapture.jar');
  add(jar, 'custom/lib/DbCapture.jar');
  for (const name of metadataNames) {
    add(candidate ? candidate.metadata[name] : reuseInstalled ? confined(env.home, `codebase/com/custom/dbcapture/${name}`)
      : source(`build/metadata/com/custom/dbcapture/${name}`), `codebase/com/custom/dbcapture/${name}`);
  }
  const jsfrag = `codebase/netmarkets/javascript/util/jsfrags/${assets.jsfrag}`;
  mkdir(path.dirname(confined(siteMod, jsfrag)));
  fs.writeFileSync(confined(siteMod, jsfrag),
    createJsfrag(fs.readFileSync(source(`${clientRoot}/${assets.header}`), 'utf8'),
      fs.readFileSync(source(`${clientRoot}/${assets.csv}`), 'utf8'),
      fs.readFileSync(source(`${clientRoot}/${assets.menuIcons}`), 'utf8')));
  plan.files[jsfrag] = fingerprint(confined(siteMod, jsfrag));
  const role = 'codebase/customroleaccessprefs.xml';
  const existingRole = confined(env.home, role);
  mkdir(path.dirname(confined(siteMod, role)));
  configuration(env, 'role', fs.existsSync(existingRole) ? existingRole : path.join(overlay, 'customroleaccessprefs.xml'),
    confined(siteMod, role));
  if (fs.existsSync(existingRole)) fs.chmodSync(confined(siteMod, role), fs.statSync(existingRole).mode & 0o777);
  plan.files[role] = fingerprint(confined(siteMod, role));
  for (const [relative, operation] of [['custom/xconf/custom.site.xconf', 'migrate'],
    ['custom/xconf/custom.declarations.xconf', 'references']]) {
    if (fs.existsSync(confined(env.home, relative))) {
      mkdir(path.dirname(confined(siteMod, relative)));
      configuration(env, operation, confined(env.home, relative), confined(siteMod, relative));
      fs.chmodSync(confined(siteMod, relative), fs.statSync(confined(env.home, relative)).mode & 0o777);
      plan.files[relative] = fingerprint(confined(siteMod, relative));
    }
  }
  for (const key of settingKeys) {
    if (Object.hasOwn(current, key)) plan.settings[key] = current[key];
  }
  if (javascriptOnly) {
    const selected = new Set([`codebase/custom/DbCapture/${assets.header}`,
      `codebase/custom/DbCapture/${assets.csv}`, `codebase/custom/DbCapture/${assets.menuIcons}`, jsfrag]);
    for (const [relative, hash] of Object.entries(plan.files)) {
      if (selected.has(relative)) continue;
      if (fingerprint(confined(env.home, relative)) !== hash) {
        throw new Error(`JavaScript-only update cannot change ${relative}; review a full plan instead.`);
      }
      plan.prerequisites[relative] = hash;
      fs.unlinkSync(confined(siteMod, relative));
      delete plan.files[relative];
    }
    for (const relative of ['site.xconf', 'declarations.xconf', 'codebase/wt.properties',
      'codebase/presentation.properties', 'codebase/service.properties']) {
      plan.prerequisites[relative] = fingerprint(confined(env.home, relative));
    }
  }
  const protectedFiles = new Set([...Object.keys(plan.files),
    ...Object.keys(plan.files).map(relative => `wtSafeArea/siteMod/${relative}`),
    ...(javascriptOnly ? [] : ['site.xconf', 'declarations.xconf', 'codebase/.xconf-target-file-hints',
      'codebase/wt.properties', 'codebase/presentation.properties', 'codebase/service.properties']),
    ...generatedJs.map(name => `codebase/netmarkets/javascript/util/${name}`)]);
  const util = path.join(env.home, 'codebase/netmarkets/javascript/util');
  for (const entry of fs.readdirSync(util, {withFileTypes: true})) {
    if (entry.isFile() && entry.name.endsWith('.js')) protectedFiles.add(`codebase/netmarkets/javascript/util/${entry.name}`);
  }
  for (const relative of protectedFiles) {
    const file = confined(env.home, relative);
    plan.before[relative] = {hash: fingerprint(file), mode: fs.existsSync(file) ? fs.statSync(file).mode & 0o777 : null};
  }
  save(path.join(directory, 'plan.json'), plan);
  console.log(`PLAN=${path.join(directory, 'plan.json')}`);
  console.log(`Prepared ${Object.keys(plan.files).length} files; no live files changed. Review the private plan and shared XML diffs.`);
  return path.join(directory, 'plan.json');
}

function loadPlan(env, file) {
  const absolute = fs.realpathSync(file);
  const plan = JSON.parse(fs.readFileSync(absolute, 'utf8'));
  if (plan.format !== 1 || plan.target !== env.home) throw new Error('Plan format/target mismatch.');
  for (const [relative, hash] of Object.entries(plan.toolchain)) {
    if (fingerprint(confined(env.home, relative)) !== hash) throw new Error(`PTC tool changed since planning: ${relative}`);
  }
  return {plan, directory: path.dirname(absolute)};
}

function checkStage(plan, directory) {
  const root = path.join(directory, 'siteMod');
  if (fs.lstatSync(root).isSymbolicLink()) throw new Error('Stage root must not be a symlink.');
  const found = [];
  const visit = relative => {
    for (const entry of fs.readdirSync(path.join(root, relative), {withFileTypes: true})) {
      const name = relative ? `${relative}/${entry.name}` : entry.name;
      if (entry.isDirectory()) visit(name);
      else if (entry.isFile()) found.push(name);
      else throw new Error(`Unexpected stage file type: ${name}`);
    }
  };
  visit('');
  if (JSON.stringify(found.sort()) !== JSON.stringify(Object.keys(plan.files).sort())) {
    throw new Error('Unexpected stage contents; only reviewed manifest files may be installed.');
  }
  for (const [relative, hash] of Object.entries(plan.files)) {
    if (fingerprint(confined(root, relative)) !== hash) throw new Error(`Stage changed: ${relative}`);
  }
}

function checkPrerequisites(env, plan) {
  for (const [relative, hash] of Object.entries(plan.prerequisites || {})) {
    if (fingerprint(confined(env.home, relative)) !== hash) throw new Error(`Non-JavaScript prerequisite changed: ${relative}`);
  }
}

function checkBefore(env, plan) {
  checkPrerequisites(env, plan);
  for (const [relative, before] of Object.entries(plan.before)) {
    if (fingerprint(confined(env.home, relative)) !== before.hash) {
      throw new Error(`Concurrent change since plan: ${relative}. Create/review a fresh plan; do not overwrite it.`);
    }
  }
}

export function verifyPlan(env, planFile) {
  const {plan, directory} = loadPlan(env, planFile);
  checkStage(plan, directory);
  checkPrerequisites(env, plan);
  for (const [relative, hash] of Object.entries(plan.files)) {
    if (fingerprint(confined(path.join(directory, 'siteMod'), relative)) !== hash) throw new Error(`Stage changed: ${relative}`);
    for (const location of [relative, `wtSafeArea/siteMod/${relative}`]) {
      if (fingerprint(confined(env.home, location)) !== hash) throw new Error(`Deployment differs: ${location}`);
    }
  }
  const presentation = properties(env, 'codebase/presentation.properties',
    ['netmarkets.presentation.jsFiles', 'netmarkets.presentation.cssFiles']);
  if ((presentation['netmarkets.presentation.jsFiles'] || '').split(';').some(value => value.startsWith('custom/DbCapture/'))) {
    throw new Error('Legacy global DB Capture jsFiles registration remains.');
  }
  if (!(presentation['netmarkets.presentation.cssFiles'] || '').split(';').includes(`custom/DbCapture/${assets.css}`)) {
    throw new Error('DB Ninja CSS registration is missing.');
  }
  for (const key of Object.keys(presentation)) {
    for (const item of (plan.presentation[key] || '').split(';').filter(Boolean)) {
      if (!item.startsWith('custom/DbCapture/') && !presentation[key].split(';').includes(item)) {
        throw new Error(`Unrelated presentation registration was lost: ${key}`);
      }
    }
  }
  const current = properties(env, 'codebase/wt.properties', ['wt.services.service.905000', ...settingKeys]);
  if (current['wt.services.service.905000'] !== service) throw new Error('Service registration mismatch.');
  for (const [key, value] of Object.entries(plan.settings)) {
    if (current[key] !== value) throw new Error(`Site setting was not preserved: ${key}`);
  }
  for (const name of ['main.js', 'windchill-all-debug.js', 'windchill-all.js']) {
    const text = fs.readFileSync(path.join(env.home, 'codebase/netmarkets/javascript/util', name), 'utf8');
    if (!text.includes('installDbNinja')) throw new Error(`DB Ninja was not combined into ${name}`);
  }
  xconf(env, ['--validateassite', path.join(env.home, 'site.xconf')]);
  console.log('PASS: source stage, live files and canonical SafeArea hashes, properties and combined JavaScript agree.');
  console.log(plan.javascriptOnly
    ? 'JavaScript-only update: hard-reload and verify the browser. No server configuration/JAR/JSP was changed.'
    : 'This is a disk/configuration check, NOT runtime acceptance. Restart approved MethodServers and verify the browser.');
}

export function applyPlan(env, planFile) {
  maintenance();
  if (process.env.DBNINJA_ORACLE_CONFIRMED !== 'yes') {
    throw new Error('Oracle prerequisites/schema must be approved: set DBNINJA_ORACLE_CONFIRMED=yes after checking them.');
  }
  const {plan, directory} = loadPlan(env, planFile);
  if (fs.existsSync(path.join(directory, 'applied.json'))) throw new Error('This plan was already attempted. Verify or roll it back; do not replay it.');
  checkBefore(env, plan);
  checkStage(plan, directory);
  const backup = path.join(bundle, 'backups', `deployment-${stamp()}`);
  mkdir(backup);
  for (const [relative, before] of Object.entries(plan.before)) {
    if (before.hash !== null) copy(confined(env.home, relative), confined(path.join(backup, 'before'), relative));
  }
  save(path.join(backup, 'plan.json'), plan);
  const result = {backup, status: 'started', after: {}};
  save(path.join(directory, 'applied.json'), result);
  console.log(`BACKUP=${backup}`);
  const newWebDirectories = new Set();
  try {
    for (const relative of Object.keys(plan.files).filter(name => name.startsWith('codebase/'))) {
      for (let parent = path.dirname(confined(env.home, relative)); parent !== env.home; parent = path.dirname(parent)) {
        if (!fs.existsSync(parent)) newWebDirectories.add(parent);
      }
    }
    ant(env, 'bin/swmaint.xml', ['createSafeArea']);
    for (const relative of Object.keys(plan.files)) {
      copy(confined(path.join(directory, 'siteMod'), relative), confined(env.home, `wtSafeArea/siteMod/${relative}`));
    }
    // The canonical SafeArea retains the files for CPS. Scope this copy operation
    // to the reviewed plan so unrelated, stale siteMod files cannot be reapplied.
    ant(env, 'bin/swmaint.xml', ['listSiteChanges', 'listSiteChangesIgnored', 'installSiteChanges'],
      [`-DwtSafeArea.siteMod.dir=${path.join(directory, 'siteMod')}`]);
    for (const relative of Object.keys(plan.files)) {
      const file = confined(env.home, relative);
      const browserAsset = (relative.startsWith('codebase/custom/DbCapture/') && /\.(js|css)$/.test(relative))
        || actionIcons.some(icon => icon.runtime === relative);
      fs.chmodSync(file, browserAsset ? 0o644 : plan.before[relative].mode ?? 0o644);
    }
    for (const directory of newWebDirectories) fs.chmodSync(directory, 0o755);
    if (!plan.javascriptOnly) {
      xconf(env, ['--validateasdecl', path.join(env.home, 'custom/xconf/DbNinja.xconf')]);
      xconf(env, ['-i', 'custom/xconf/DbNinja.xconf']);
      for (const [key, value] of Object.entries(plan.settings)) {
        xconf(env, ['-s', `${key}=${value}`, '-t', 'codebase/wt.properties']);
      }
      for (const key of ['netmarkets.presentation.jsFiles', 'netmarkets.presentation.cssFiles']) {
        for (const value of (plan.presentation[key] || '').split(';').filter(value => value.startsWith('custom/DbCapture/'))) {
          xconf(env, ['--remove', `${key}=${value}`]);
        }
      }
      xconf(env, ['--add', `netmarkets.presentation.cssFiles=custom/DbCapture/${assets.css}`, '-p']);
      xconf(env, ['--validateassite', path.join(env.home, 'site.xconf')]);
    }
    // These are PTC's public Ant targets, not copied/reimplemented PTC code.
    // CSS stays an external XCONF registration; docs and obsolete-file cleanup are unrelated.
    ant(env, 'bin/jsfrag_combine.xml', ['combine_jsfrag_files', 'compress']);
    verifyPlan(env, planFile);
    result.status = plan.javascriptOnly ? 'static-verified-browser-reload-required' : 'disk-verified-restart-required';
  } finally {
    for (const relative of Object.keys(plan.before)) result.after[relative] = fingerprint(confined(env.home, relative));
    save(path.join(directory, 'applied.json'), result);
    save(path.join(backup, 'applied.json'), result);
  }
}

function rollback(env, planFile) {
  maintenance();
  const {plan, directory} = loadPlan(env, planFile);
  checkPrerequisites(env, plan);
  const result = JSON.parse(fs.readFileSync(path.join(directory, 'applied.json'), 'utf8'));
  if (result.status === 'rolled-back') throw new Error('This plan was already rolled back.');
  if (JSON.stringify(Object.keys(result.after).sort()) !== JSON.stringify(Object.keys(plan.before).sort())) {
    throw new Error('Incomplete post-apply snapshot; review the target and backups manually before restoring anything.');
  }
  for (const [relative, hash] of Object.entries(result.after)) {
    if (fingerprint(confined(env.home, relative)) !== hash) {
      throw new Error(`File changed after deployment; review/merge instead of overwriting: ${relative}`);
    }
    const expected = plan.before[relative].hash;
    if (expected !== null && fingerprint(confined(path.join(result.backup, 'before'), relative)) !== expected) {
      throw new Error(`Backup checksum mismatch: ${relative}`);
    }
  }
  for (const [relative, before] of Object.entries(plan.before)) {
    const target = confined(env.home, relative);
    if (before.hash === null) {
      if (fs.existsSync(target)) fs.unlinkSync(target);
    } else {
      copy(confined(path.join(result.backup, 'before'), relative), target, before.mode);
    }
  }
  xconf(env, ['--validateassite', path.join(env.home, 'site.xconf')]);
  result.status = 'rolled-back';
  save(path.join(directory, 'applied.json'), result);
  save(path.join(result.backup, 'applied.json'), result);
  console.log('Restored the checksum-guarded file snapshot. No database records were changed. An approved restart/browser check is still required.');
}

function build(env) {
  maintenance();
  preflight(env);
  const backup = path.join(bundle, 'backups', `build-${stamp()}`);
  mkdir(backup);
  const before = {};
  for (const base of ['codebase/com/custom/dbcapture', 'custom/ser/com/custom/dbcapture']) {
    for (const name of metadataNames) {
      const relative = `${base}/${name}`, file = confined(env.home, relative);
      before[relative] = {hash: fingerprint(file), mode: fs.existsSync(file) ? fs.statSync(file).mode & 0o777 : null};
      if (before[relative].hash) copy(file, confined(path.join(backup, 'before'), relative));
    }
  }
  save(path.join(backup, 'metadata-before.json'), before);
  const started = Date.now() - 2000;
  try {
    const options = [`-Dwt.customizationSource.dir.path=${path.join(bundle, 'customization')}`];
    ant(env, 'bin/customizationTools/build.xml', ['clean', 'validate.folder.structure', 'compile'], options);
    copy(path.join(bundle, 'customization/temp/lib/DbCapture.jar'), path.join(bundle, 'build/DbCapture.jar'));
    for (const name of metadataNames) {
      const candidates = ['codebase/com/custom/dbcapture', 'custom/ser/com/custom/dbcapture']
        .map(base => confined(env.home, `${base}/${name}`))
        .filter(file => fs.existsSync(file) && fs.statSync(file).mtimeMs >= started);
      if (candidates.length !== 1) throw new Error(`Cannot identify newly generated ${name}; inspect CCD output, do not reuse stale metadata.`);
      copy(candidates[0], path.join(bundle, 'build/metadata/com/custom/dbcapture', name));
    }
    console.log('PASS: target-built JAR and seven ClassInfo files retained in build/. No CCD deploy was run.');
  } finally {
    for (const [relative, value] of Object.entries(before)) {
      const target = confined(env.home, relative);
      if (value.hash) copy(confined(path.join(backup, 'before'), relative), target, value.mode);
      else if (fs.existsSync(target)) fs.unlinkSync(target);
    }
    console.log(`Build-time live ClassInfo side effects restored; backup: ${backup}`);
  }
}

async function main() {
  const [command, argument, ...extra] = process.argv.slice(2);
  if (extra.length || !['preflight', 'build', 'ddl', 'plan', 'apply', 'verify', 'rollback', 'test'].includes(command)) {
    throw new Error('Usage: node tools/dbninja.mjs preflight|build|ddl|plan [--prebuilt|--reuse-installed|--javascript-only]|apply PLAN|verify PLAN|rollback PLAN|test');
  }
  if (command === 'test') {
    const tests = fs.readdirSync(path.join(bundle, 'tools')).filter(name => name.endsWith('-regression.cjs'));
    run(process.execPath, ['--test', '--test-reporter=tap', ...tests.map(name => path.join(bundle, 'tools', name))], bundle);
    return;
  }
  const env = environment();
  if (command === 'preflight') preflight(env);
  else if (command === 'build') build(env);
  else if (command === 'ddl') {
    maintenance();
    preflight(env);
    const generatedClasspath = [path.join(bundle, 'build/DbCapture.jar'),
      path.join(bundle, 'build/metadata')].join(path.delimiter);
    if (!fs.existsSync(path.join(bundle, 'build/DbCapture.jar'))
        || !fs.existsSync(path.join(bundle, 'build/metadata'))) {
      throw new Error('Build the target JAR and ClassInfo before generating DDL.');
    }
    ant(env, 'bin/tools.xml', ['sql_script'], ['-Dgen.input=com.custom.dbcapture.*',
      `-Dgen.classpath_add=${generatedClasspath}`]);
    console.log('Generated target DDL only; no SQL was executed. Inspect the generator output before assembling create-only SQL.');
  }
  else if (command === 'plan') {
    if (argument && !['--prebuilt', '--reuse-installed', '--javascript-only'].includes(argument)) throw new Error('Unknown plan option.');
    createPlan(env, argument === '--reuse-installed' || argument === '--javascript-only',
      argument === '--javascript-only', argument === '--prebuilt');
  } else if (!argument) throw new Error('An explicit reviewed plan.json is required.');
  else if (command === 'apply') applyPlan(env, argument);
  else if (command === 'verify') verifyPlan(env, argument);
  else rollback(env, argument);
}

if (process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  main().catch(error => {
    console.error(`ERROR: ${error.message}`);
    process.exitCode = 1;
  });
}
