const assert = require('node:assert/strict');
const crypto = require('node:crypto');
const fs = require('node:fs');
const path = require('node:path');
const test = require('node:test');

const root = path.resolve(__dirname, '..');
const api = import('./icon-assets.mjs');
const assets = JSON.parse(fs.readFileSync(path.join(root, 'deployment/assets.json'), 'utf8'));
const bytesFor = icon => fs.readFileSync(path.join(root, icon.png));
const digest = bytes => crypto.createHash('sha256').update(bytes).digest('hex');

test('the two action icons have explicit Unicode identities, custom paths and reviewed checksums', async () => {
  const {actionIconDefinitions, validateActionIcon} = await api;
  const icons = actionIconDefinitions(assets);
  assert.deepEqual(icons.map(icon => [icon.action, icon.codePoint]),
    [['startDbCapture', '1F977'], ['stopDbCapture', '1F4A8']]);
  assert.notEqual(icons[0].sha256, icons[1].sha256);
  for (const icon of icons) {
    const image = validateActionIcon(bytesFor(icon), icon);
    assert.equal(image.width, 16);
    assert.equal(image.height, 16);
    assert.ok(image.visiblePixels > 20 && image.transparentPixels > 0);
    assert.equal(icon.runtime, `codebase/netmarkets/images/${icon.resource}`);
    const svg = fs.readFileSync(path.join(root, icon.svg), 'utf8');
    assert.ok(svg.includes(`&#x${icon.codePoint};`));
    assert.match(svg, /font-family="Noto Color Emoji"/);
    assert.doesNotMatch(svg, /<script|<image|\bon\w+=|\bhref=/i);
  }
});

test('only Start and Stop action-resource icon entries change, not labels or action identity', async () => {
  const {actionIconDefinitions} = await api;
  const source = fs.readFileSync(path.join(root,
    'customization/DbCapture/main/src/com/ptc/dbcapture/dbCaptureActionResource.java'), 'utf8');
  for (const icon of actionIconDefinitions(assets)) {
    const symbol = icon.action === 'startDbCapture' ? 'START_ICON' : 'STOP_ICON';
    assert.ok(source.includes(`@RBEntry("${icon.resource}")\n   public static final String ${symbol} = "dbcapture.${icon.action}.icon";`));
  }
  for (const value of ['Ninja Trick', 'Ninja Stealth', 'edit.gif', 'delete.gif', 'export_list_to_csv.png']) {
    assert.ok(source.includes(`@RBEntry("${value}")`));
  }
  assert.doesNotMatch(source, /@RBEntry\("(?:start|stop)\.gif"\)/);
});

test('custom artwork is bound only to the dbcapture action namespace, never OOTB actions', async () => {
  const {actionIconDefinitions} = await api;
  const resource = fs.readFileSync(path.join(root,
    'customization/DbCapture/main/src/com/ptc/dbcapture/dbCaptureActionResource.java'), 'utf8');
  const entries = [...resource.matchAll(/@RBEntry\("((?:\\.|[^"\\])*)"\)\s*public static final String \w+\s*=\s*"([^"]+)"/g)];
  assert.match(resource, /@RBUUID\("com\.ptc\.dbcapture\.dbCaptureActionResource"\)/);
  for (const icon of actionIconDefinitions(assets)) {
    assert.deepEqual(entries.filter(entry => entry[1] === icon.resource).map(entry => entry[2]),
      [`dbcapture.${icon.action}.icon`]);
    assert.match(icon.runtime, /^codebase\/netmarkets\/images\/dbcapture\/dbNinja/);
  }
  const actions = fs.readFileSync(path.join(root,
    'customization/DbCapture/main/src_web/config/actions/DbCapture-actions.xml'), 'utf8');
  assert.deepEqual([...actions.matchAll(/<objecttype\b[^>]*\bname="([^"]+)"/g)].map(match => match[1]), ['dbcapture']);
  assert.match(actions, /resourceBundle="com\.ptc\.dbcapture\.dbCaptureActionResource"/);
  const models = fs.readFileSync(path.join(root,
    'customization/DbCapture/main/src_web/config/actions/DbCapture-actionModels.xml'), 'utf8');
  for (const action of ['startDbCapture', 'stopDbCapture']) {
    assert.match(models, new RegExp(`name="${action}"\\s+type="dbcapture"`));
  }
});

test('icon declarations cannot add arbitrary binary files or escape module paths', async () => {
  const {actionIconDefinitions} = await api;
  for (const file of ['../outside.png', '/outside.png', 'arbitrary.png', 'DbCapture.jar', 'a\\b.png']) {
    const invalid = structuredClone(assets);
    invalid.actionIcons.startDbCapture.file = file;
    assert.throws(() => actionIconDefinitions(invalid), /Invalid action icon declaration/);
  }
  const extra = structuredClone(assets);
  extra.actionIcons.unreviewed = extra.actionIcons.startDbCapture;
  assert.throws(() => actionIconDefinitions(extra), /Exactly the two/);
});

test('modified or truncated approved PNGs do not silently pass the publication gate', async () => {
  const {actionIconDefinitions, validateActionIcon} = await api;
  const icon = actionIconDefinitions(assets)[0], original = bytesFor(icon);
  assert.throws(() => validateActionIcon(original, {...icon, sha256: '0'.repeat(64)}), /checksum/);
  for (const length of [0, 8, 44, original.length - 1]) {
    assert.throws(() => validateActionIcon(original.subarray(0, length), icon), /PNG|image/);
  }
  const corrupt = Buffer.from(original);
  corrupt[corrupt.length - 1] ^= 1;
  assert.throws(() => validateActionIcon(corrupt, icon), /CRC/);
});

test('the icon gate rejects extra metadata even if someone updates its checksum', async () => {
  const {actionIconDefinitions, validateActionIcon, pngCrc32} = await api;
  const icon = actionIconDefinitions(assets)[0], original = bytesFor(icon);
  const text = Buffer.from('Comment\0synthetic fixture metadata');
  const chunk = Buffer.alloc(text.length + 12);
  chunk.writeUInt32BE(text.length);
  chunk.write('tEXt', 4, 'ascii');
  text.copy(chunk, 8);
  chunk.writeUInt32BE(pngCrc32(chunk.subarray(4, chunk.length - 4)), chunk.length - 4);
  const injected = Buffer.concat([original.subarray(0, 33), chunk, original.subarray(33)]);
  assert.throws(() => validateActionIcon(injected, {...icon, sha256: digest(injected)}), /unapproved PNG chunk/);
});

test('the icon gate rejects an incorrect size even with a valid CRC and updated checksum', async () => {
  const {actionIconDefinitions, validateActionIcon, pngCrc32} = await api;
  const icon = actionIconDefinitions(assets)[0], bytes = Buffer.from(bytesFor(icon));
  bytes.writeUInt32BE(32, 16);
  bytes.writeUInt32BE(pngCrc32(bytes.subarray(12, 29)), 29);
  assert.throws(() => validateActionIcon(bytes, {...icon, sha256: digest(bytes)}), /16x16/);
});
