import {inflateRawSync} from 'node:zlib';
import {pngCrc32 as crc32} from './icon-assets.mjs';

export function moduleJarEntries(bytes) {
  if (!Buffer.isBuffer(bytes) || bytes.length < 22 || bytes.length > 32 * 1024 * 1024
      || bytes.readUInt32LE(0) !== 0x04034b50) {
    throw new Error('Invalid or oversized module JAR.');
  }
  let end = -1;
  for (let offset = bytes.length - 22; offset >= Math.max(0, bytes.length - 65557); offset--) {
    if (bytes.readUInt32LE(offset) === 0x06054b50 && offset + 22 + bytes.readUInt16LE(offset + 20) === bytes.length) {
      end = offset;
      break;
    }
  }
  if (end < 0 || bytes.readUInt16LE(end + 4) || bytes.readUInt16LE(end + 6)
      || bytes.readUInt16LE(end + 20)) throw new Error('Unsupported module JAR layout or archive comment.');
  const count = bytes.readUInt16LE(end + 10), length = bytes.readUInt32LE(end + 12);
  const start = bytes.readUInt32LE(end + 16);
  if (!count || count > 1000 || bytes.readUInt16LE(end + 8) !== count || start + length !== end) {
    throw new Error('Invalid module JAR directory.');
  }
  const entries = new Map(), ranges = [];
  let offset = start, total = 0;
  for (let index = 0; index < count; index++) {
    if (offset + 46 > end || bytes.readUInt32LE(offset) !== 0x02014b50) throw new Error('Invalid JAR entry header.');
    const flags = bytes.readUInt16LE(offset + 8), method = bytes.readUInt16LE(offset + 10);
    const checksum = bytes.readUInt32LE(offset + 16), compressed = bytes.readUInt32LE(offset + 20);
    const size = bytes.readUInt32LE(offset + 24), nameLength = bytes.readUInt16LE(offset + 28);
    const extraLength = bytes.readUInt16LE(offset + 30), commentLength = bytes.readUInt16LE(offset + 32);
    const local = bytes.readUInt32LE(offset + 42), next = offset + 46 + nameLength + extraLength + commentLength;
    if (next > end || (flags & 1) || ![0, 8].includes(method) || commentLength
        || bytes.readUInt16LE(offset + 34) || size > 8 * 1024 * 1024 || local + 30 > start) {
      throw new Error('Unsupported or oversized module JAR entry.');
    }
    const nameBytes = bytes.subarray(offset + 46, offset + 46 + nameLength);
    const name = nameBytes.toString('utf8');
    if (!name || !Buffer.from(name, 'utf8').equals(nameBytes) || name.includes('\\')
        || name.startsWith('/') || name.split('/').some(part => part === '.' || part === '..')
        || entries.has(name)) throw new Error('Unsafe or duplicate module JAR path.');
    if (bytes.readUInt32LE(local) !== 0x04034b50 || bytes.readUInt16LE(local + 6) !== flags
        || bytes.readUInt16LE(local + 8) !== method) throw new Error('JAR local header mismatch.');
    const localNameLength = bytes.readUInt16LE(local + 26), localExtra = bytes.readUInt16LE(local + 28);
    const dataStart = local + 30 + localNameLength + localExtra, dataEnd = dataStart + compressed;
    if (dataEnd > start || !bytes.subarray(local + 30, local + 30 + localNameLength).equals(nameBytes)) {
      throw new Error('JAR entry data bounds or name mismatch.');
    }
    if (ranges.some(([a, b]) => local < b && dataEnd > a)) throw new Error('Overlapping module JAR entries.');
    ranges.push([local, dataEnd]);
    const data = method === 0 ? bytes.subarray(dataStart, dataEnd)
      : inflateRawSync(bytes.subarray(dataStart, dataEnd), {maxOutputLength: Math.max(1, size)});
    if (data.length !== size || crc32(data) !== checksum || (total += size) > 64 * 1024 * 1024) {
      throw new Error('Module JAR content checksum/size mismatch.');
    }
    if (name.endsWith('/')) {
      if (size !== 0 || !(name === 'META-INF/' || /^(?:com\/|com\/custom\/|com\/custom\/dbcapture\/(?:[A-Za-z0-9_$]+\/)*)$/.test(name))) {
        throw new Error('Unexpected module JAR directory.');
      }
    } else if (name !== 'META-INF/MANIFEST.MF' && name !== 'META-INF/ptc.listeners.lst'
        && !/^com\/custom\/dbcapture\/(?:[A-Za-z0-9_$]+\/)*[A-Za-z0-9_$]+\.(?:class|RB\.ser)$/.test(name)) {
      throw new Error(`Non-module or unapproved JAR entry: ${name}`);
    }
    if (name.endsWith('.class')
        && (data.length < 8 || data.readUInt32BE(0) !== 0xcafebabe || data.readUInt16BE(4) === 65535
          || data.readUInt16BE(6) !== 61)) throw new Error('Unsupported module class-file version.');
    entries.set(name, data);
    offset = next;
  }
  if (offset !== end || !entries.has('com/custom/dbcapture/StandardDbCaptureService.class')
      || !entries.has('com/custom/dbcapture/dbCaptureActionResource.class')) {
    throw new Error('Incomplete module JAR.');
  }
  return entries;
}
