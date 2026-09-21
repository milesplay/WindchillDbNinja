const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const test = require('node:test');
const vm = require('node:vm');
const file = path.resolve(__dirname,
  '../customization/DbCapture/main/src_web/custom/DbCapture/overlay/netmarkets/jsp/dbcapture/objectDetails.jsp');
const jsp = fs.readFileSync(file, 'utf8');

function list(values) {
  return {size: () => values.length, isEmpty: () => values.length === 0,
    get: index => values[index], [Symbol.iterator]: () => values[Symbol.iterator]()};
}

function render({rows = [['current']], columns = ['NAME'], changes = [], problem = null} = {}) {
  const saved = changes.map(change => ({
    column: () => change.column || 'NAME', before: () => change.before,
    after: () => change.after, truncated: () => Boolean(change.truncated)
  }));
  const snapshot = {
    rows: () => list(rows.map(list)), columns: () => list(columns), changes: () => list(saved),
    changeFor: name => saved.find(change => change.column().toLowerCase() === name.toLowerCase()) || null,
    operationClass: () => 'object-updated', operation: () => 'UPDATE', reference: () => 'wt.part.WTPart:42',
    rowId: () => 42, readAt: () => '2026-09-19 12:00:00', truncated: () => false
  };
  // Execute the actual JSP body with record/list doubles; request authorization and JDBC stay outside this test.
  const body = jsp.slice(jsp.indexOf('<!DOCTYPE html>'));
  let code = 'let output = "";';
  let end = 0;
  for (const match of body.matchAll(/<%(=?)([\s\S]*?)%>/g)) {
    code += 'output += ' + JSON.stringify(body.slice(end, match.index)) + ';\n';
    if (match[1]) {
      code += 'output += (' + match[2] + ');\n';
    } else {
      code += match[2]
        .replace(/\b(?:int|String|DbCaptureObjectReader\.ChangedValue)\s+(\w+)\s*=/g, 'let $1 =')
        .replace(/for \(DbCaptureObjectReader\.ChangedValue (\w+) : snapshot\.changes\(\)\)/g,
          'for (const $1 of snapshot.changes())') + '\n';
    }
    end = match.index + match[0].length;
  }
  code += 'output += ' + JSON.stringify(body.slice(end)) + '; output;';
  const encodeForHTMLContent = text => String(text).replace(/[&<>"']/g,
    char => ({'&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;'}[char]));
  return vm.runInNewContext(code, {
    snapshot, problem, HTMLEncoder: {encodeForHTMLContent},
    DbCaptureObjectReader: {displayValue: value => value == null ? '(null)' : value === '' ? '(empty)' : value},
    wt: {util: {WTStandardDateFormat: {format: value => value}}}
  });
}

test('object page no longer shows the routine maximum-row explanation', () => {
  assert.doesNotMatch(jsp, /Maximum .*matching rows/);
  assert.doesNotMatch(jsp, /unique ID normally returns one/);
  assert.match(jsp, /snapshot\.truncated\(\)/, 'exceptional safety-cap notice is retained');
});
test('both headings use operation color, with strikethrough for deletion', () => {
  assert.match(jsp, /<header id="objectHeading" class="<%=snapshot\.operationClass\(\)%>">/);
  assert.match(jsp, /\.object-created\s*\{\s*color:\s*#187a30/);
  assert.match(jsp, /\.object-updated\s*\{\s*color:\s*#b00020/);
  assert.match(jsp, /\.object-deleted\s*\{\s*color:\s*#000/);
  assert.match(jsp, /\.object-deleted h1, \.object-deleted h2\s*\{\s*text-decoration:\s*line-through/);
});
test('comparison shares the Current value cell without introducing another column', () => {
  assert.match(jsp, /<thead><tr><th scope="col">Column<\/th><th scope="col">Current value<\/th><\/tr><\/thead>/);
  const cell = jsp.match(/<td><div class="current-value">[\s\S]*?<\/td>/)[0];
  assert.match(cell, /Changed value \(captured\)/);
  assert.match(cell, /class="changed-value"/);
  assert.match(cell, /change != null/);
  assert.match(cell, /encodeForHTMLContent\(DbCaptureObjectReader\.displayValue\(change\.before\(\)\)\)/);
  assert.match(cell, /encodeForHTMLContent\(DbCaptureObjectReader\.displayValue\(change\.after\(\)\)\)/);
  assert.match(jsp, /\.changed-value\s*\{\s*color:\s*#b00020/);
});
test('value whitespace is preserved without rendering JSP indentation as empty lines', () => {
  assert.match(jsp, /\.current-value, \.changed-value\s*\{\s*white-space:\s*pre-wrap/);
  const cellStyles = jsp.match(/\btd\s*\{[^}]*\}/g).join('\n');
  assert.doesNotMatch(cellStyles, /white-space:\s*pre/);
});

test('saved UPDATE values remain visible when the current row has since disappeared', () => {
  const output = render({rows: [], changes: [{before: 'original name', after: 'captured name'}]});
  assert.match(output, /No current row matches/);
  assert.match(output, /original name/);
  assert.match(output, /captured name/);
  assert.match(output, /Captured changes without a current row/);
  assert.doesNotMatch(output, /class="current-value"/, 'saved values must not masquerade as current data');
});

test('captured before and after boundaries cannot be confused with arrows inside values', () => {
  const first = render({changes: [{before: 'a -> b', after: 'c'}]});
  const second = render({changes: [{before: 'a', after: 'b -> c'}]});
  assert.notEqual(first, second, 'different before/after pairs currently render identical text');
  assert.match(first, /Before \(captured\):/);
  assert.match(first, /After \(captured\):/);
});

test('current values remain independent of captured after values from an earlier update', () => {
  const output = render({rows: [['later live value', 'unchanged column']], columns: ['NAME', 'OTHER'],
    changes: [{before: 'before capture', after: 'after capture'}]});
  assert.match(output, /class="current-value">later live value<\/div>/);
  assert.match(output, /class="changed-value">after capture<\/dd>/);
  assert.equal((output.match(/class="captured-change"/g) || []).length, 1);
  assert.doesNotMatch(output, /Captured changes without a current row/);
});

test('rendered current and captured values remain escaped, preserve lines and distinguish empty from null', () => {
  const output = render({rows: [['<current>&\nline 2']], changes: [{before: null, after: '', truncated: true}]});
  assert.match(output, /&lt;current&gt;&amp;\nline 2/);
  assert.match(output, /\(null\)/);
  assert.match(output, /\(empty\)/);
  assert.match(output, /Captured value preview is truncated/);
  assert.doesNotMatch(output, /<current>/);
});

test('missing-row comparisons escape every column and value and retain truncation notices', () => {
  const output = render({rows: [], changes: [
    {column: '<COLUMN>', before: '<img src=x onerror=alert(1)>', after: 'first\nsecond', truncated: true},
    {column: 'EMPTY', before: '', after: null}
  ]});
  assert.match(output, /&lt;COLUMN&gt;/);
  assert.match(output, /&lt;img src=x onerror=alert\(1\)&gt;/);
  assert.match(output, /first\nsecond/);
  assert.match(output, /Captured value preview is truncated/);
  assert.match(output, /\(empty\)/);
  assert.match(output, /\(null\)/);
  assert.doesNotMatch(output, /<img|class="current-value"/);
});

test('a missing row with no saved changed values does not fabricate a comparison', () => {
  const output = render({rows: []});
  assert.match(output, /No current row matches/);
  assert.doesNotMatch(output, /<table>|Before \(captured\):|After \(captured\):/);
});

test('the problem branch renders no object data or saved comparison', () => {
  const output = render({problem: '<denied>', changes: [{before: 'private old', after: 'private new'}]});
  assert.match(output, /role="alert">&lt;denied&gt;/);
  assert.doesNotMatch(output, /private old|private new|class="current-value"/);
});
