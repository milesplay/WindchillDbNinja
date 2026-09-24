const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const test = require('node:test');

test('create-only DDL uses all target files and rejects destructive statements before producing output', async () => {
  const {createOnlySql, tables} = await import('./create-schema.mjs');
  const parent = path.resolve(__dirname, '../build');
  fs.mkdirSync(parent, {recursive: true});
  const directory = fs.mkdtempSync(path.join(parent, 'schema-test-'));
  try {
    for (const table of tables) {
      fs.writeFileSync(path.join(directory, `create_${table}_Table.sql`),
        `REM generated fixture\nCREATE TABLE ${table} (description VARCHAR2(1200), idA2A2 NUMBER, `
        + `CONSTRAINT PK_${table} PRIMARY KEY (idA2A2))\n/\n`);
      fs.writeFileSync(path.join(directory, `create_${table}_Index.sql`),
        `CREATE INDEX ${table}$COMPOSITE0 ON ${table}(idA2A2)\n/\n`);
    }
    const sql = createOnlySql(directory);
    assert.equal((sql.match(/CREATE TABLE/g) || []).length, 4);
    assert.equal((sql.match(/CREATE INDEX/g) || []).length, 4);
    assert.match(sql, /VARCHAR2\(1200\)/);
    assert.ok(sql.indexOf('existing_count <> 0') < sql.indexOf('CREATE TABLE'));
    assert.doesNotMatch(sql, /DBCAPTURESQLEVENTS|DROP TABLE|TRUNCATE TABLE|GRANT /);
    fs.appendFileSync(path.join(directory, 'create_DbCaptureSession_Table.sql'), '\nDROP TABLE business_table;\n');
    assert.throws(() => createOnlySql(directory), /Unexpected\/destructive/);
    fs.unlinkSync(path.join(directory, 'create_DbCaptureChange_Table.sql'));
    assert.throws(() => createOnlySql(directory), /ENOENT/);
  } finally {
    fs.rmSync(directory, {recursive: true});
  }
});

test('target Oracle slash scripts retain only module CREATE and exact table comments', async () => {
  const {createOnlySql, tables} = await import('./create-schema.mjs');
  const directory = fs.mkdtempSync(path.join(path.resolve(__dirname, '../build'), 'schema-test-'));
  try {
    for (const table of tables) {
      fs.writeFileSync(path.join(directory, `create_${table}_Table.sql`),
        `set echo on\nREM target fixture\nset echo off\nCREATE TABLE ${table} (\n`
        + `idA2A2 NUMBER NOT NULL, description VARCHAR2(1200) DEFAULT 'A; B -- not SQL',\n`
        + `charDescription VARCHAR2(8 CHAR),\n`
        + `ncharDescription NVARCHAR2(8),\n`
        + `CONSTRAINT PK_${table} PRIMARY KEY (idA2A2))\n`
        + `STORAGE (INITIAL 20k NEXT 20k PCTINCREASE 0)\nENABLE PRIMARY KEY USING INDEX\n`
        + `TABLESPACE INDX STORAGE (INITIAL 20k NEXT 20k PCTINCREASE 0)\n/\n`
        + `COMMENT ON TABLE ${table} IS 'Target metadata; not an executable statement'\n/\n`);
      fs.writeFileSync(path.join(directory, `create_${table}_Index.sql`),
        `CREATE INDEX ${table}$COMPOSITE0 ON ${table}(idA2A2 DESC)\nTABLESPACE INDX\n/\n`
        + `CREATE INDEX ${table}$COMPOSITE1 ON ${table}(description)\n/\n`);
    }
    const sql = createOnlySql(directory);
    assert.equal((sql.match(/CREATE TABLE/g) || []).length, 4);
    assert.equal((sql.match(/CREATE INDEX/g) || []).length, 8);
    assert.equal((sql.match(/COMMENT ON TABLE/g) || []).length, 4);
    assert.match(sql, /DEFAULT 'A; B -- not SQL'/);
    assert.equal((sql.match(/^\/$/gm) || []).length, 1, 'Only the owned PL/SQL precondition uses slash execution');
    const byteSql = createOnlySql(directory, {explicitByteSemantics: true});
    assert.equal((byteSql.match(/VARCHAR2\(1200 BYTE\)/g) || []).length, 4,
      'unqualified PTC target widths gain explicit BYTE semantics without changing numeric widths');
    assert.equal((byteSql.match(/VARCHAR2\(8 CHAR\)/g) || []).length, 4,
      'explicit CHAR semantics are not rewritten');
    assert.equal((byteSql.match(/NVARCHAR2\(8\)/g) || []).length, 4,
      'NVARCHAR2 national-character widths are not rewritten');
    assert.match(byteSql, /Explicit BYTE added only to unqualified target sql3 VARCHAR2 widths; numeric lengths unchanged/);
    const tableFile = path.join(directory, `create_${tables[0]}_Table.sql`);
    const original = fs.readFileSync(tableFile, 'utf8');
    for (const extra of [
      'COMMENT ON TABLE unrelated IS \'not this module\';',
      'CREATE TABLE unrelated (idA2A2 NUMBER);',
      'ALTER TABLE unrelated ADD x NUMBER;',
      'BEGIN dangerous_operation(); END;\n/',
      'HOST echo unsafe\n',
      'CREATE TABLE DbCaptureAttrDelta AS SELECT * FROM unrelated;',
      '/* unterminated comment'
    ]) {
      fs.writeFileSync(tableFile, original + '\n' + extra);
      assert.throws(() => createOnlySql(directory), /Unexpected\/destructive|unsupported/);
    }
    fs.writeFileSync(tableFile, original);
    fs.appendFileSync(path.join(directory, `create_${tables[0]}_Index.sql`),
      `CREATE INDEX ${tables[0]}$BAD ON unrelated(idA2A2);`);
    assert.throws(() => createOnlySql(directory), /unrelated/);
  } finally {
    fs.rmSync(directory, {recursive: true});
  }
});
