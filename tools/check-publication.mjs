import fs from 'node:fs';
import path from 'node:path';
import crypto from 'node:crypto';
import {spawnSync} from 'node:child_process';
import {actionIcons, bundle, confined} from './dbninja.mjs';
import {validateActionIcon} from './icon-assets.mjs';
import {readPrebuilt, prebuiltFiles} from './prebuilt.mjs';
import {moduleJarEntries} from './module-archive.mjs';

const documents = ['README.md', 'INSTALL.md', 'HANDOFF.md', 'AGENTS.md',
  'COMPATIBILITY.md', 'CUSTOMIZATION.md', 'OPERATIONS.md', 'PUBLICATION.md',
  'LOCAL-INSTALL.md', 'USE-CASES.md'];
const optionalDocuments = ['THIRD-PARTY-NOTICES.md', 'CONTRIBUTING.md', 'SECURITY.md'];
const rootFiles = new Set(['.gitignore', '.gitattributes', 'package.json', 'LICENSE', ...documents]);
const publicDirectories = ['customization/DbCapture', 'customization/configurations', 'deployment', 'tools', 'sql'];
const excludedRoots = new Set(['backups', 'build', '.git']);
const localDocuments = new Set(['README-ja.md', 'LOCAL-INSTALL-ja.md', 'USE-CASES-ja.md']);
const textExtensions = new Set(['.md', '.json', '.java', '.rbInfo', '.jsp', '.xml', '.xconf', '.js', '.css', '.mjs', '.cjs']);
const approvedIcons = new Map(actionIcons.map(icon => [icon.png, icon]));
const approvedIconSources = new Set(actionIcons.map(icon => icon.svg));
const approvedArtifacts = new Map();
const sitePatterns = [
  /\/(?:ptc|opt\/ptc)\/Windchill_[0-9]/,
  /-----BEGIN (?:RSA |EC |OPENSSH )?PRIVATE KEY-----/,
  /\b(?:ghp_[A-Za-z0-9]{30,}|github_pat_[A-Za-z0-9_]{50,}|AKIA[A-Z0-9]{16})\b/
];

function publicationPath(relative) {
  try {
    return confined(bundle, relative);
  } catch (error) {
    if (error.code === 'DBNINJA_SYMLINK') throw new Error(`Do not publish symlinks: ${relative}`);
    throw error;
  }
}

function validateContent(relative, bytes, staged = false) {
  if (approvedIcons.has(relative)) {
    validateActionIcon(bytes, approvedIcons.get(relative));
    return;
  }
  if (approvedArtifacts.has(relative)) {
    const approved = approvedArtifacts.get(relative);
    if (bytes.length !== approved.bytes
        || crypto.createHash('sha256').update(bytes).digest('hex') !== approved.sha256) {
      throw new Error(`Unreviewed prebuilt artifact: ${relative}`);
    }
    const contents = relative.endsWith('.jar') ? [...moduleJarEntries(bytes).values()] : [bytes];
    for (const content of contents) {
      if (sitePatterns.some(pattern => pattern.test(content.toString('utf8')))) {
        throw new Error(`Possible local identifier/secret in ${staged ? 'staged ' : ''}${relative}; value suppressed.`);
      }
    }
    return;
  }
  if (!textExtensions.has(path.extname(relative)) && !rootFiles.has(relative)
      && !relative.endsWith('.sql') && !approvedIconSources.has(relative)) {
    throw new Error(`Unapproved file type: ${relative}`);
  }
  if (/\.(jar|class|ser|log|bak|zip|gz|trc|dmp)$/i.test(relative)) throw new Error(`Private/generated binary: ${relative}`);
  if (bytes.includes(0)) throw new Error(`Binary content: ${relative}`);
  const content = bytes.toString('utf8');
  if (!Buffer.from(content, 'utf8').equals(bytes)) throw new Error(`Non-UTF-8 source: ${relative}`);
  if (relative.endsWith('.md') && /[\u3040-\u30ff\u3400-\u9fff\uac00-\ud7af]/.test(content)) {
    throw new Error(`Publication documentation must be English: ${relative}`);
  }
  for (const pattern of sitePatterns) {
    if (pattern.test(content)) {
      throw new Error(`Possible local identifier/secret in ${staged ? 'staged ' : ''}${relative}; inspect locally (value suppressed).`);
    }
  }
}

function main() {
  const options = process.argv.slice(2);
  if (options.some(option => !['--export', '--require-prebuilt'].includes(option))
      || new Set(options).size !== options.length) {
    throw new Error('Usage: node tools/check-publication.mjs [--export] [--require-prebuilt]');
  }
  if (process.env.DBNINJA_PRIVATE_PATTERNS) {
    const patterns = JSON.parse(fs.readFileSync(process.env.DBNINJA_PRIVATE_PATTERNS, 'utf8'));
    if (!Array.isArray(patterns) || patterns.some(value => typeof value !== 'string')) {
      throw new Error('DBNINJA_PRIVATE_PATTERNS must name a private JSON array of regex strings.');
    }
    sitePatterns.push(...patterns.map(value => new RegExp(value, 'i')));
  }
  for (const name of optionalDocuments) {
    if (fs.existsSync(publicationPath(name))) {
      documents.push(name);
      rootFiles.add(name);
    }
  }
  if (fs.existsSync(publicationPath('prebuilt'))) {
    const candidate = readPrebuilt(bundle);
    for (const [relative, metadata] of Object.entries(candidate.manifest.artifacts)) {
      approvedArtifacts.set(relative, metadata);
    }
    publicDirectories.push('prebuilt');
  } else if (options.includes('--require-prebuilt')) {
    throw new Error('The required reviewed prebuilt package is missing.');
  }
  const files = [];
  const walk = relative => {
    const absolute = publicationPath(relative);
    const stat = fs.lstatSync(absolute);
    if (stat.isSymbolicLink()) throw new Error(`Do not publish symlinks: ${relative}`);
    if (stat.isDirectory()) {
      for (const name of fs.readdirSync(absolute)) walk(`${relative}/${name}`);
      return;
    }
    if (!stat.isFile()) throw new Error(`Do not publish special files: ${relative}`);
    validateContent(relative, fs.readFileSync(absolute));
    files.push(relative);
  };
  for (const name of rootFiles) {
    if (!fs.existsSync(path.join(bundle, name))) throw new Error(`Missing publication file: ${name}`);
    walk(name);
  }
  for (const relative of publicDirectories) walk(relative);
  for (const entry of fs.readdirSync(bundle)) {
    if (!rootFiles.has(entry) && !excludedRoots.has(entry) && !localDocuments.has(entry)
        && !publicDirectories.some(relative => relative === entry || relative.startsWith(`${entry}/`))) {
      throw new Error(`Unclassified root entry: ${entry}`);
    }
  }
  for (const entry of fs.readdirSync(path.join(bundle, 'customization'))) {
    if (!['DbCapture', 'configurations', 'generated', 'temp', 'moduleOrder.properties'].includes(entry)) {
      throw new Error(`Unclassified customization entry: ${entry}`);
    }
  }
  const publicSet = new Set(files);
  if (approvedArtifacts.size) {
    const actual = files.filter(relative => relative.startsWith('prebuilt/')).sort();
    const expected = ['prebuilt/manifest.json', ...prebuiltFiles].sort();
    if (JSON.stringify(actual) !== JSON.stringify(expected)) throw new Error('Unapproved or missing prebuilt package files.');
  }
  for (const icon of actionIcons) {
    for (const relative of [icon.png, icon.svg]) {
      if (!publicSet.has(relative)) throw new Error(`Missing published action icon asset: ${relative}`);
    }
  }
  if (fs.existsSync(path.join(bundle, '.git'))) {
    const git = spawnSync('git', ['-C', bundle, 'ls-files', '--stage', '-z'], {encoding: 'utf8'});
    if (git.error) throw new Error(`Cannot check this repository's Git index: ${git.error.message}`);
    if (git.status !== 0) throw new Error('Git index check failed; review permissions and repository state.');
    for (const entry of git.stdout.split('\0').filter(Boolean)) {
      const parsed = entry.match(/^(\d{6}) ([a-f0-9]{40,64}) ([0-3])\t([\s\S]+)$/);
      if (!parsed) throw new Error('Unexpected Git index entry; review the index manually.');
      const [, mode, objectId, stage, relative] = parsed;
      if (!publicSet.has(relative)) throw new Error(`Git already tracks an excluded/unclassified file: ${relative}`);
      if (stage !== '0' || !['100644', '100755'].includes(mode)) {
        throw new Error(`Unmerged, symlink or unsupported Git index entry: ${relative}`);
      }
      const blob = spawnSync('git', ['-C', bundle, 'cat-file', 'blob', objectId], {maxBuffer: 32 * 1024 * 1024});
      if (blob.error) throw new Error(`Cannot read staged content for ${relative}: ${blob.error.message}`);
      if (blob.status !== 0) throw new Error(`Cannot read staged content: ${relative}`);
      validateContent(relative, blob.stdout, true);
      if (!blob.stdout.equals(fs.readFileSync(publicationPath(relative)))) {
        throw new Error(`Git index differs from the reviewed working tree: ${relative}; review and stage the intended content.`);
      }
    }
    console.log('PASS: Git index paths and staged bytes match the reviewed publication files.');
  } else {
    console.log('NOTE: No .git entry at the publication root; no Git index was checked. Initialize a standalone clean export and re-run before committing.');
  }
  for (const document of files.filter(relative => relative.endsWith('.md'))) {
    const text = fs.readFileSync(path.join(bundle, document), 'utf8');
    for (const match of text.matchAll(/\[[^\]]*\]\(([^)\s]+)\)/g)) {
      const target = match[1].split('#')[0];
      if (!target || /^[a-z]+:/i.test(target)) continue;
      const relative = path.posix.normalize(path.posix.join(path.posix.dirname(document), target.replace(/^\.\//, '')));
      if (target.startsWith('/') || !publicSet.has(relative)) {
        throw new Error(`Unpublished/broken document link: ${document} -> ${target}`);
      }
    }
  }
  files.sort();
  const build = publicationPath('build');
  fs.mkdirSync(build, {recursive: true, mode: 0o700});
  fs.writeFileSync(confined(build, 'public-files.txt'), `${files.join('\n')}\n`, {mode: 0o600});
  const hashes = files.map(file => `${crypto.createHash('sha256').update(fs.readFileSync(path.join(bundle, file))).digest('hex')}  ${file}`);
  fs.writeFileSync(confined(build, 'public-sha256.txt'), `${hashes.join('\n')}\n`, {mode: 0o600});
  if (options.includes('--export')) {
    const output = fs.mkdtempSync(path.join(build, 'public-source-'));
    for (const relative of files) {
      const target = path.join(output, relative);
      fs.mkdirSync(path.dirname(target), {recursive: true});
      fs.copyFileSync(path.join(bundle, relative), target);
    }
    console.log(`PUBLICATION_EXPORT=${output}`);
  }
  console.log(`PASS: ${files.length} reviewed English-documentation/source/resource files; ${approvedArtifacts.size} approved custom binary artifacts and two approved action PNGs.`);
  console.log('No private backups, PTC SDK libraries or known site identifiers are approved. Human rights/confidentiality review remains required.');
}

try {
  main();
} catch (error) {
  console.error(`ERROR: ${error.message}`);
  process.exitCode = 1;
}
