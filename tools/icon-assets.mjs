import crypto from 'node:crypto';
import {inflateSync} from 'node:zlib';

export function actionIconDefinitions(assets) {
  const expected = {
    startDbCapture: ['Trick', '1F977'],
    stopDbCapture: ['Stealth', '1F4A8']
  };
  if (!assets || !assets.actionIcons || typeof assets.actionIcons !== 'object'
      || Array.isArray(assets.actionIcons) || Object.keys(assets.actionIcons).length !== 2) {
    throw new Error('Exactly the two DB Ninja action icons must be declared.');
  }
  return Object.entries(expected).map(([action, [name, codePoint]]) => {
    const icon = assets.actionIcons[action];
    if (!icon || typeof icon.file !== 'string'
        || !new RegExp(`^dbNinja${name}-v[0-9]{8,10}\\.png$`).test(icon.file)
        || icon.codePoint !== codePoint || typeof icon.sha256 !== 'string'
        || !/^[a-f0-9]{64}$/.test(icon.sha256)) {
      throw new Error(`Invalid action icon declaration: ${action}`);
    }
    return {...icon, action,
      png: `customization/DbCapture/main/src_web/custom/DbCapture/icons/${icon.file}`,
      svg: `deployment/icons/${icon.file.replace(/\.png$/, '.svg')}`,
      resource: `dbcapture/${icon.file}`,
      runtime: `codebase/netmarkets/images/dbcapture/${icon.file}`};
  });
}

export function pngCrc32(bytes) {
  let crc = 0xffffffff;
  for (const byte of bytes) {
    crc ^= byte;
    for (let bit = 0; bit < 8; bit++) crc = (crc >>> 1) ^ ((crc & 1) ? 0xedb88320 : 0);
  }
  return (crc ^ 0xffffffff) >>> 0;
}

function paeth(left, up, corner) {
  const prediction = left + up - corner;
  const a = Math.abs(prediction - left), b = Math.abs(prediction - up), c = Math.abs(prediction - corner);
  return a <= b && a <= c ? left : b <= c ? up : corner;
}

export function validateActionIcon(bytes, icon) {
  const fail = message => { throw new Error(`Invalid action icon ${icon.file}: ${message}`); };
  if (!Buffer.isBuffer(bytes) || bytes.length < 45 || bytes.length > 8192
      || !bytes.subarray(0, 8).equals(Buffer.from([137, 80, 78, 71, 13, 10, 26, 10]))) {
    fail('expected a small PNG file');
  }
  let offset = 8, header = false, background = false, ended = false;
  const imageData = [];
  while (offset < bytes.length) {
    if (ended || bytes.length - offset < 12) fail('trailing or incomplete PNG data');
    const size = bytes.readUInt32BE(offset);
    const end = offset + 12 + size;
    if (end > bytes.length) fail('truncated PNG chunk');
    const type = bytes.toString('ascii', offset + 4, offset + 8);
    const data = bytes.subarray(offset + 8, offset + 8 + size);
    if (pngCrc32(bytes.subarray(offset + 4, end - 4)) !== bytes.readUInt32BE(end - 4)) fail('invalid PNG CRC');
    if (!header && type !== 'IHDR') fail('IHDR must be first');
    if (type === 'IHDR') {
      if (header || size !== 13 || data.readUInt32BE(0) !== 16 || data.readUInt32BE(4) !== 16
          || data[8] !== 8 || data[9] !== 6 || data[10] !== 0 || data[11] !== 0 || data[12] !== 0) {
        fail('expected one non-interlaced 16x16, 8-bit RGBA image');
      }
      header = true;
    } else if (type === 'bKGD') {
      if (background || imageData.length || size !== 6) fail('invalid background chunk');
      background = true;
    } else if (type === 'IDAT') {
      imageData.push(data);
    } else if (type === 'IEND') {
      if (size !== 0 || !imageData.length) fail('invalid image end');
      ended = true;
    } else {
      fail(`unapproved PNG chunk ${type}`);
    }
    offset = end;
  }
  if (!ended) fail('missing image end');
  const filtered = inflateSync(Buffer.concat(imageData), {maxOutputLength: 1040});
  if (filtered.length !== 1040) fail('invalid pixel payload length');
  const pixels = Buffer.alloc(1024);
  for (let row = 0; row < 16; row++) {
    const filter = filtered[row * 65];
    if (filter > 4) fail('invalid pixel filter');
    for (let column = 0; column < 64; column++) {
      const index = row * 64 + column;
      const left = column >= 4 ? pixels[index - 4] : 0;
      const up = row > 0 ? pixels[index - 64] : 0;
      const corner = row > 0 && column >= 4 ? pixels[index - 68] : 0;
      const prediction = [0, left, up, Math.floor((left + up) / 2), paeth(left, up, corner)][filter];
      pixels[index] = (filtered[row * 65 + column + 1] + prediction) & 255;
    }
  }
  let visiblePixels = 0;
  for (let index = 3; index < pixels.length; index += 4) if (pixels[index] !== 0) visiblePixels++;
  if (!visiblePixels || [0, 15, 240, 255].some(index => pixels[index * 4 + 3] !== 0)) {
    fail('expected visible artwork and transparent corners');
  }
  if (crypto.createHash('sha256').update(bytes).digest('hex') !== icon.sha256) fail('unreviewed image checksum');
  return {width: 16, height: 16, visiblePixels, transparentPixels: 256 - visiblePixels};
}
