import fs from 'node:fs';
import path from 'node:path';
import {fileURLToPath} from 'node:url';

export const tables = ['DbCaptureAttrDelta', 'DbCaptureChange', 'DbCaptureSession', 'DbCaptureTableChange'];

function statements(source, file) {
  const result = [];
  let text = '', quoted = false, blockComment = false, lineStart = true;
  const finish = () => {
    if (text.trim()) result.push(text.trim());
    text = '';
  };
  for (let i = 0; i < source.length; i++) {
    const c = source[i], next = source[i + 1];
    if (blockComment) {
      if (c === '*' && next === '/') { blockComment = false; text += ' '; i++; }
      continue;
    }
    if (quoted) {
      text += c;
      if (c === "'" && next === "'") { text += next; i++; }
      else if (c === "'") quoted = false;
      continue;
    }
    if (lineStart) {
      const end = source.indexOf('\n', i);
      const line = source.slice(i, end < 0 ? source.length : end).trim();
      if (/^(?:REM(?:\s|$)|SET\s+ECHO\s+(?:ON|OFF)\s*$)/i.test(line)) {
        i = end < 0 ? source.length : end;
        text += '\n';
        continue;
      }
      if (line === '/') {
        finish();
        i = end < 0 ? source.length : end;
        continue;
      }
    }
    if (c === '-' && next === '-') {
      const end = source.indexOf('\n', i);
      i = end < 0 ? source.length : end;
      text += '\n';
      lineStart = true;
      continue;
    }
    if (c === '/' && next === '*') { blockComment = true; i++; continue; }
    if (c === "'") quoted = true;
    if (c === ';') finish();
    else text += c;
    lineStart = c === '\n' || (lineStart && /\s/.test(c));
  }
  if (quoted || blockComment || text.trim()) {
    throw new Error(`Unexpected/destructive or unterminated SQL in ${file}; review manually.`);
  }
  return result;
}

const identifier = '[A-Za-z][A-Za-z0-9_$#]*';
const literal = "'(?:''|[^'])*'";
const columnType = '(?:NUMBER(?:\\(\\s*\\d+(?:\\s*,\\s*-?\\d+)?\\s*\\))?'
  + '|(?:N?VARCHAR2|N?CHAR|RAW)\\(\\s*\\d+(?:\\s+(?:BYTE|CHAR))?\\s*\\)'
  + '|DATE|TIMESTAMP(?:\\(\\s*\\d+\\s*\\))?|CLOB|NCLOB|BLOB)';
const columnDefinition = new RegExp(`^(${identifier})\\s+${columnType}`
  + `(?:\\s+DEFAULT\\s+(?:NULL|[+-]?\\d+(?:\\.\\d+)?|${literal}))?(?:\\s+NOT\\s+NULL)?$`, 'i');

function splitColumns(body) {
  const masked = body.replace(/'(?:''|[^'])*'/g, match => ' '.repeat(match.length));
  let depth = 0, start = 0;
  const result = [];
  for (let i = 0; i < masked.length; i++) {
    if (masked[i] === '(') depth++;
    else if (masked[i] === ')') {
      if (--depth < 0) throw new Error('Unexpected/destructive SQL: unbalanced definition.');
    } else if (masked[i] === ',' && depth === 0) {
      result.push(body.slice(start, i).trim());
      start = i + 1;
    }
  }
  if (depth !== 0) throw new Error('Unexpected/destructive SQL: unbalanced definition.');
  result.push(body.slice(start).trim());
  return result;
}

function parenthesized(sql, opening) {
  const masked = sql.replace(/'(?:''|[^'])*'/g, match => ' '.repeat(match.length));
  let depth = 0;
  for (let i = opening; i < masked.length; i++) {
    if (masked[i] === '(') depth++;
    else if (masked[i] === ')' && --depth === 0) {
      return {body: sql.slice(opening + 1, i), suffix: sql.slice(i + 1).trim()};
    }
  }
  throw new Error('Unexpected/destructive SQL: unbalanced statement.');
}

function validateStorage(suffix, tableStatement) {
  const storagePair = '(?:(?:INITIAL|NEXT)\\s+\\d+[KMG]?'
    + '|(?:MINEXTENTS|PCTINCREASE|FREELISTS|FREELIST\\s+GROUPS)\\s+\\d+'
    + '|MAXEXTENTS\\s+(?:\\d+|UNLIMITED)|BUFFER_POOL\\s+(?:DEFAULT|KEEP|RECYCLE))';
  const option = new RegExp(`^(?:TABLESPACE\\s+${identifier}\\b`
    + `|STORAGE\\s*\\(\\s*${storagePair}(?:\\s+${storagePair})*\\s*\\)`
    + '|(?:PCTFREE|PCTUSED|INITRANS|MAXTRANS)\\s+\\d+\\b'
    + '|(?:LOGGING|NOLOGGING|NOPARALLEL)\\b'
    + (tableStatement ? '|ENABLE\\s+PRIMARY\\s+KEY\\s+USING\\s+INDEX\\b' : '')
    + ')', 'i');
  while (suffix) {
    const match = suffix.match(option);
    if (!match) throw new Error('Unexpected/destructive or unsupported storage clause.');
    suffix = suffix.slice(match[0].length).trim();
  }
}

function validateTable(sql, table) {
  const prefix = sql.match(new RegExp(`^CREATE\\s+TABLE\\s+${table}\\s*\\(`, 'i'));
  if (!prefix) throw new Error('Unexpected/destructive or unrelated table statement.');
  const {body, suffix} = parenthesized(sql, prefix[0].length - 1);
  const names = new Set();
  let primaryKeys = 0;
  for (const definition of splitColumns(body)) {
    const column = definition.match(columnDefinition);
    if (column) {
      const name = column[1].toUpperCase();
      if (names.has(name)) throw new Error('Unexpected/destructive SQL: duplicate column.');
      names.add(name);
    } else if (new RegExp(`^CONSTRAINT\\s+PK_${table}\\s+PRIMARY\\s+KEY\\s*\\(\\s*IDA2A2\\s*\\)$`, 'i').test(definition)) {
      if (++primaryKeys > 1) throw new Error('Unexpected/destructive SQL: duplicate primary key.');
    } else {
      throw new Error('Unexpected/destructive or unsupported column/constraint definition.');
    }
  }
  if (!names.has('IDA2A2')) throw new Error('Unexpected/destructive SQL: missing persistent identity.');
  validateStorage(suffix, true);
  return names;
}

function validateIndex(sql, table, columns, indexes) {
  const prefix = sql.match(new RegExp(`^CREATE\\s+(?:UNIQUE\\s+)?INDEX\\s+(${table}[A-Za-z0-9_$#]*)`
    + `\\s+ON\\s+${table}\\s*\\(`, 'i'));
  if (!prefix) throw new Error('Unexpected/destructive or unrelated index statement.');
  const name = prefix[1].toUpperCase();
  if (indexes.has(name)) throw new Error('Unexpected/destructive SQL: duplicate index.');
  indexes.add(name);
  const {body, suffix} = parenthesized(sql, prefix[0].length - 1);
  for (const value of splitColumns(body)) {
    const column = value.match(new RegExp(`^(${identifier})(?:\\s+(?:ASC|DESC))?$`, 'i'));
    if (!column || !columns.has(column[1].toUpperCase())) {
      throw new Error('Unexpected/destructive or unsupported index expression.');
    }
  }
  validateStorage(suffix, false);
}

export function createOnlySql(directory) {
  const fragments = [];
  const columns = new Map(), indexes = new Set();
  for (const kind of ['Table', 'Index']) {
    for (const table of tables) {
      const file = path.join(directory, `create_${table}_${kind}.sql`);
      const original = fs.readFileSync(file, 'utf8');
      const parts = statements(original, file);
      let creates = 0;
      for (const sql of parts) {
        if (kind === 'Table') {
          if (new RegExp(`^COMMENT\\s+ON\\s+TABLE\\s+${table}\\s+IS\\s+${literal}$`, 'i').test(sql)) {
            if (creates !== 1) throw new Error(`Unexpected/destructive comment before table creation in ${file}`);
          } else {
            if (++creates !== 1) throw new Error(`Unexpected/destructive additional table statement in ${file}`);
            columns.set(table, validateTable(sql, table));
          }
        } else {
          validateIndex(sql, table, columns.get(table), indexes);
          creates++;
        }
      }
      if (creates === 0) throw new Error(`Expected target-generated ${kind} DDL in ${file}`);
      // SQL*Plus slash terminators are normalized to avoid executing a CREATE twice.
      fragments.push(`-- Source: ${path.basename(file)}\n${parts.map(sql => `${sql};`).join('\n')}\n`);
    }
  }
  const names = tables.map(table => `'${table.toUpperCase()}'`).join(', ');
  return `-- First installation only. Generated on the TARGET Windchill.
-- Review tablespaces, byte widths and all statements with the DBA.
-- Run as the Windchill schema. Oracle DDL commits; partial failure is not rollback-safe.
WHENEVER OSERROR EXIT FAILURE
WHENEVER SQLERROR EXIT SQL.SQLCODE
SET DEFINE OFF
SET ECHO ON
DECLARE
   existing_count NUMBER;
BEGIN
   SELECT COUNT(*) INTO existing_count FROM user_tables WHERE table_name IN (${names});
   IF existing_count <> 0 THEN
      RAISE_APPLICATION_ERROR(-20001, 'DB Ninja tables already exist. Do not reinstall/reset; review a migration instead.');
   END IF;
END;
/
${fragments.join('\n')}
SELECT table_name FROM user_tables WHERE table_name IN (${names}) ORDER BY table_name;
`;
}

if (process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  try {
    if (process.argv.length !== 4) throw new Error('Usage: node tools/create-schema.mjs TARGET_GENERATED_DDL_DIRECTORY OUTPUT.sql');
    const output = path.resolve(process.argv[3]);
    const sql = createOnlySql(fs.realpathSync(process.argv[2]));
    fs.mkdirSync(path.dirname(output), {recursive: true, mode: 0o700});
    fs.writeFileSync(output, sql, {flag: 'wx', mode: 0o600});
    console.log(`Created ${output}. No SQL was executed. Do not publish target-generated DDL.`);
  } catch (error) {
    console.error(`ERROR: ${error.message}`);
    process.exitCode = 1;
  }
}
