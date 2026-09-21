import fs from 'node:fs';
import path from 'node:path';
import crypto from 'node:crypto';

export function confined(root, relative) {
  if (!relative || path.isAbsolute(relative) || relative.includes('\\')
      || /^[A-Za-z]:/.test(relative)
      || relative.split('/').some(part => part === '..' || part === '' || part === '.')) {
    throw new Error(`Invalid relative path: ${relative}`);
  }
  root = path.resolve(root);
  if (root === path.parse(root).root) throw new Error('A filesystem root is not a deployment root.');
  const result = path.join(root, relative);
  for (let current = result; ; current = path.dirname(current)) {
    try {
      if (fs.lstatSync(current).isSymbolicLink()) {
        const error = new Error(`Review symbolic links before deployment: ${current}`);
        error.code = 'DBNINJA_SYMLINK';
        throw error;
      }
    } catch (error) {
      if (error.code !== 'ENOENT') throw error;
    }
    if (current === path.dirname(current)) break;
  }
  return result;
}

export function fingerprint(file) {
  let stat;
  try {
    stat = fs.lstatSync(file);
  } catch (error) {
    if (error.code === 'ENOENT') return null;
    throw error;
  }
  if (!stat.isFile() || stat.isSymbolicLink()) throw new Error(`Expected a regular file: ${file}`);
  return crypto.createHash('sha256').update(fs.readFileSync(file)).digest('hex');
}
