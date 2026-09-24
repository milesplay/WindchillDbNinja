import fs from 'node:fs';
import path from 'node:path';
import {fileURLToPath} from 'node:url';
import {confined} from './filesystem.mjs';

export const tables = ['DbCaptureAttrDelta', 'DbCaptureChange', 'DbCaptureSession', 'DbCaptureTableChange'];

function addExplicitByteSemantics(sql) {
  return sql.replace(/\b(VARCHAR2\s*\(\s*\d+)(\s*\))/gi, '$1 BYTE$2');
}

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
  const option = new RegExp(`^(?:TABLESPACE\\s+(${identifier})(?=\\s|$)`
    + `|STORAGE\\s*\\(\\s*${storagePair}(?:\\s+${storagePair})*\\s*\\)`
    + '|(?:PCTFREE|PCTUSED|INITRANS|MAXTRANS)\\s+\\d+\\b'
    + '|(?:LOGGING|NOLOGGING|NOPARALLEL)\\b'
    + (tableStatement ? '|ENABLE\\s+PRIMARY\\s+KEY\\s+USING\\s+INDEX\\b' : '')
    + ')', 'i');
  const tablespaces = new Set();
  while (suffix) {
    const match = suffix.match(option);
    if (!match) throw new Error('Unexpected/destructive or unsupported storage clause.');
    if (match[1]) tablespaces.add(match[1].toUpperCase());
    suffix = suffix.slice(match[0].length).trim();
  }
  return tablespaces;
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
  if (primaryKeys !== 1) throw new Error('Unexpected/destructive SQL: missing primary key.');
  return {columns: names, primaryKey: `PK_${table.toUpperCase()}`, tablespaces: validateStorage(suffix, true)};
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
  return validateStorage(suffix, false);
}

export function readCreateOnly(directory, {explicitByteSemantics = false} = {}) {
  const fragments = [];
  const columns = new Map(), indexes = new Set(), tablespaces = new Set(), primaryKeys = [];
  let comments = 0, byteWidthsMadeExplicit = 0;
  for (const kind of ['Table', 'Index']) {
    for (const table of tables) {
      const file = confined(directory, `create_${table}_${kind}.sql`);
      if (!fs.lstatSync(file).isFile()) throw new Error(`Expected a regular DDL file: ${file}`);
      const original = fs.readFileSync(file, 'utf8');
      const parts = statements(original, file).map(sql => {
        if (!explicitByteSemantics || kind !== 'Table' || !/^CREATE\s+TABLE\b/i.test(sql)) return sql;
        const normalized = addExplicitByteSemantics(sql);
        const explicitWidths = /\bVARCHAR2\s*\(\s*\d+\s+BYTE\s*\)/gi;
        byteWidthsMadeExplicit += (normalized.match(explicitWidths) || []).length
          - (sql.match(explicitWidths) || []).length;
        return normalized;
      });
      let creates = 0, tableComments = 0;
      for (const sql of parts) {
        if (kind === 'Table') {
          if (new RegExp(`^COMMENT\\s+ON\\s+TABLE\\s+${table}\\s+IS\\s+${literal}$`, 'i').test(sql)) {
            if (creates !== 1 || ++tableComments !== 1) {
              throw new Error(`Unexpected/destructive comment before table creation or duplicate comment in ${file}`);
            }
            comments++;
          } else {
            if (++creates !== 1) throw new Error(`Unexpected/destructive additional table statement in ${file}`);
            const definition = validateTable(sql, table);
            columns.set(table, definition.columns);
            primaryKeys.push(definition.primaryKey);
            for (const name of definition.tablespaces) tablespaces.add(name);
          }
        } else {
          for (const name of validateIndex(sql, table, columns.get(table), indexes)) tablespaces.add(name);
          creates++;
        }
      }
      if (creates === 0) throw new Error(`Expected module ${kind} DDL in ${file}`);
      // SQL*Plus slash terminators are normalized to avoid executing a CREATE twice.
      fragments.push(`-- Source: ${path.basename(file)}\n${parts.map(sql => `${sql};`).join('\n')}\n`);
    }
  }
  return {fragments, tables: tables.map(table => table.toUpperCase()), primaryKeys: primaryKeys.sort(),
    indexes: [...indexes].sort(), tablespaces: [...tablespaces].sort(), comments, byteWidthsMadeExplicit};
}

export function createOnlySql(directory, options = {}) {
  const definition = readCreateOnly(directory, options);
  const quoted = names => names.map(name => `'${name}'`).join(', ');
  const names = quoted(definition.tables);
  const objects = quoted([...definition.tables, ...definition.primaryKeys, ...definition.indexes].sort());
  const constraints = quoted(definition.primaryKeys);
  const tablespaceCheck = definition.tablespaces.length ? `
   SELECT COUNT(*) INTO tablespace_count FROM user_tablespaces
      WHERE tablespace_name IN (${quoted(definition.tablespaces)}) AND status = 'ONLINE' AND contents = 'PERMANENT';
   IF tablespace_count <> ${definition.tablespaces.length} THEN
      RAISE_APPLICATION_ERROR(-20003, 'Required index/data tablespace is unavailable. Ask the DBA to review the DDL profile.');
   END IF;` : '';
  const widthNote = options.explicitByteSemantics
    ? '-- Explicit BYTE added only to unqualified target sql3 VARCHAR2 widths; numeric lengths unchanged.\n'
    : '';
  return `-- First installation only. Assembled from reviewed module CREATE scripts.
-- Use only the matching Windchill/Oracle/byte-width profile. Development and test only.
-- Review tablespaces, byte widths and all statements with the DBA.
-- Run as the Windchill schema. Oracle DDL commits; partial failure is not rollback-safe.
-- Use a fresh schema-owner session with no pending work; never run as SYS or SYSTEM.
${widthNote}WHENEVER OSERROR EXIT FAILURE ROLLBACK
WHENEVER SQLERROR EXIT SQL.SQLCODE ROLLBACK
SET DEFINE OFF
SET ECHO ON
DECLARE
   existing_count NUMBER;
   constraint_count NUMBER;
   tablespace_count NUMBER;
BEGIN
   IF SYS_CONTEXT('USERENV', 'CURRENT_SCHEMA') <> SYS_CONTEXT('USERENV', 'SESSION_USER')
      OR SYS_CONTEXT('USERENV', 'SESSION_USER') IN ('SYS', 'SYSTEM') THEN
      RAISE_APPLICATION_ERROR(-20002, 'Use a fresh connection as the actual Windchill schema owner without CURRENT_SCHEMA changes.');
   END IF;
   SELECT COUNT(*) INTO existing_count FROM user_objects WHERE object_name IN (${objects});
   SELECT COUNT(*) INTO constraint_count FROM user_constraints WHERE constraint_name IN (${constraints});
   IF existing_count <> 0 OR constraint_count <> 0 THEN
      RAISE_APPLICATION_ERROR(-20001, 'DB Ninja object or constraint names already exist. Do not reinstall/reset; review a migration instead.');
   END IF;${tablespaceCheck}
END;
/
${definition.fragments.join('\n')}
SELECT table_name FROM user_tables WHERE table_name IN (${names}) ORDER BY table_name;
SELECT constraint_name, table_name, status, validated FROM user_constraints
   WHERE constraint_name IN (${constraints}) ORDER BY constraint_name;
SELECT index_name, table_name, status FROM user_indexes
   WHERE table_name IN (${names}) ORDER BY table_name, index_name;
`;
}

if (process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  try {
    const explicitByteSemantics = process.argv[4] === '--explicit-byte';
    if ((process.argv.length !== 4 && process.argv.length !== 5) || (process.argv[4] && !explicitByteSemantics)) {
      throw new Error('Usage: node tools/create-schema.mjs TARGET_GENERATED_DDL_DIRECTORY OUTPUT.sql [--explicit-byte]');
    }
    const output = path.resolve(process.argv[3]);
    const sql = createOnlySql(fs.realpathSync(process.argv[2]), {explicitByteSemantics});
    fs.mkdirSync(path.dirname(output), {recursive: true, mode: 0o700});
    fs.writeFileSync(output, sql, {flag: 'wx', mode: 0o600});
    console.log(`Created ${output}. No SQL was executed. Keep site-specific regenerated DDL private; review the target profile with the DBA.`);
  } catch (error) {
    console.error(`ERROR: ${error.message}`);
    process.exitCode = 1;
  }
}
