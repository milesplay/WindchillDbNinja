const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const test = require('node:test');
const vm = require('node:vm');

const root = path.resolve(__dirname, '../customization/DbCapture/main/src_web/custom/DbCapture');
const jsp = fs.readFileSync(path.join(root, 'overlay/netmarkets/jsp/dbcapture/captureDiagnostics.jsp'), 'utf8');
const asset = jsp.match(/\/custom\/DbCapture\/(dbCaptureDiagnostics-[\w.-]+\.js)/)[1];
const client = fs.readFileSync(path.join(root, asset), 'utf8');
const css = fs.readFileSync(path.join(root, 'dbCapture-v2026092003.css'), 'utf8');
const javaRoot = path.resolve(__dirname, '../customization/DbCapture/main/src/com/ptc/dbcapture');
const settleClipboard = () => new Promise(resolve => setImmediate(resolve));

function fixture(hash = '', options = {}) {
  const listeners = {};
  const messages = {textContent: ''};
  const cardMessages = {textContent: ''};
  const treeMessages = {textContent: ''};
  const classes = new Set();
  const former = {classList: {remove: name => classes.delete('former-' + name)}};
  classes.add('former-selected');
  const sql = {textContent: 'UPDATE WTPart\nSET name=?\nWHERE idA2A2=?'};
  const tree = {textContent: 'Servlet request: req-1\nAJP thread: ajp-exec-1 [42]\n\nMethodContext mc-1\n  example.create(Part.java:1)\n    #9 UPDATE WTPART\n'};
  const summary = {focus: options => {summary.focusOptions = options;}};
  function detail(name, open = false, parentElement = null) {
    return {
      tagName: 'DETAILS', open, parentElement,
      matches: selector => selector === 'details' || selector === '.' + name || selector === 'details.' + name
    };
  }
  const group = detail('request', options.requestOpen !== false);
  const treeSection = detail('call-tree', true, group);
  const sqlSection = detail('sql-section', true, group);
  const identity = detail('request-identity', false, group);
  const card = Object.assign(detail('statement-card', false, sqlSection), {
    classList: {add: name => classes.add(name), remove: name => classes.delete(name)},
    scrollIntoView: value => {card.scroll = value;},
    querySelector: name => ({'.sql-text': sql, '.sql-feedback': cardMessages, summary}[name] || null)
  });
  const rawStack = detail('raw-stack', false, card);
  const nativeMessage = detail('native-message', false, card);
  const context = detail('method-context', false, treeSection);
  const nodes = [detail('call-node', false, context), detail('call-node', true, context)];
  const descendants = [identity, treeSection, context, ...nodes, sqlSection, card, rawStack, nativeMessage];
  group.querySelectorAll = selector => selector === 'details' ? descendants : [
    ...(selector.includes('.method-context') ? [context] : []),
    ...(selector.includes('.call-node') ? nodes : [])
  ];
  group.querySelector = name => ({'.call-tree-text': tree, '.tree-feedback': treeMessages}[name] || null);
  const peerClasses = new Set();
  const peerGroup = detail('request', true);
  const peerSection = detail('sql-section', false, peerGroup);
  const peer = Object.assign(detail('statement-card', false, peerSection), {
    classList: {add: name => peerClasses.add(name), remove: name => peerClasses.delete(name)},
    scrollIntoView: value => {peer.scroll = value;},
    querySelector: name => name === 'summary' ? {focus: options => {peer.focusOptions = options;}} : null
  });
  peerGroup.querySelectorAll = () => [peerSection, peer];
  const oldRange = {cloneRange() {return oldRange;}};
  const selection = {
    rangeCount: 1,
    getRangeAt: () => oldRange,
    removeAllRanges() {selection.cleared = true;},
    addRange(range) {selection.range = range;}
  };
  const fields = [];
  const attempts = [];
  const clipboardWrites = [];
  const active = {focus: value => {active.focusOptions = value; document.activeElement = active;}};
  const document = {
    activeElement: active,
    addEventListener(name, handler, capture) {listeners[name] = {handler, capture};},
    getElementById: id => ({'diagnostic-feedback': messages, 'statement-9': card, 'statement-19': peer}[id] || null),
    querySelectorAll: selector => selector === 'details.request' ? [group, peerGroup] :
      [former, ...(classes.has('selected') ? [card] : []),
      ...(peerClasses.has('selected') ? [peer] : [])],
    createElement: name => {
      assert.equal(name, 'textarea', 'HTTP copying must use a plain-text form field, not rich HTML');
      const field = {
        style: {},
        setAttribute: (name, value) => {field[name] = value;},
        focus: () => {document.activeElement = field;},
        select: () => {field.selected = true;},
        setSelectionRange: (start, end) => {field.range = [start, end];},
        remove: () => {field.removed = true;}
      };
      fields.push(field);
      return field;
    },
    body: {appendChild: field => {field.appended = true;}},
    execCommand: command => {
      assert.equal(command, 'copy');
      const field = fields.at(-1);
      assert.equal(field.selected, true);
      assert.deepEqual(field.range, [0, field.value.length]);
      attempts.push(field.value);
      if (options.copyThrows) throw new Error('copy denied');
      return options.copyResult === undefined ? true : options.copyResult;
    }
  };
  if (options.noExecCommand) delete document.execCommand;
  const window = {getSelection: () => selection, location: {hash},
    isSecureContext: options.secure === true, navigator: {}};
  if (options.writeText) {
    window.navigator.clipboard = {writeText: text => {
      clipboardWrites.push(text);
      return options.writeText(text);
    }};
  }
  vm.runInNewContext(client, {document, window});
  return {
    card, peer, peerClasses, nodes, context, messages, cardMessages, treeMessages, classes, sql, tree, summary,
    selection, window, document, group, peerGroup, treeSection, sqlSection, peerSection, identity, descendants,
    rawStack, nativeMessage, fields, attempts, clipboardWrites, active, oldRange,
    click(mapping, options = {}) {
      const event = {target: {closest: key => mapping[key] || null},
        button: 0, ...options, preventDefault() {event.prevented = true;}};
      listeners.click.handler(event);
      return event;
    },
    toggle(target, open) {
      assert.equal(listeners.toggle.capture, true, 'native details toggles do not bubble');
      target.open = open;
      listeners.toggle.handler({target});
    },
    leaf: {getAttribute: () => '#statement-9'},
    treeButton: action => ({
      getAttribute: () => action,
      closest: () => group
    }),
    sqlCopyButton: {closest: () => card},
    treeCopyButton: {closest: () => group}
  };
}

test('a tree leaf opens and highlights its exact sequence without mixing requests', () => {
  const page = fixture();
  page.click({'.sql-leaf': page.leaf});
  assert.equal(page.card.open, true);
  assert.equal(page.classes.has('selected'), true);
  assert.equal(page.classes.has('former-selected'), false);
  assert.equal(page.card.scroll.block, 'nearest');
});

test('a missing statement produces an explicit error rather than a success-shaped link', () => {
  const page = fixture();
  const event = page.click({'.sql-leaf': {getAttribute: () => '#statement-missing'}});
  assert.equal(event.prevented, true);
  assert.match(page.messages.textContent, /unavailable/);
});

test('tree controls change their request tree without expanding SQL statement details', () => {
  const page = fixture();
  page.click({'[data-tree-action]': page.treeButton('expand')});
  assert.ok(page.nodes.every(node => node.open));
  page.click({'[data-tree-action]': page.treeButton('collapse')});
  assert.ok(page.nodes.every(node => !node.open));
  assert.equal(page.card.open, false);
});

test('Expand tree reopens a manually closed MethodContext and Collapse tree closes it', () => {
  const page = fixture();
  assert.equal(page.context.open, false);
  page.click({'[data-tree-action]': page.treeButton('expand')});
  assert.equal(page.context.open, true, 'an open call node inside a closed context is still invisible');
  assert.ok(page.nodes.every(node => node.open));
  page.click({'[data-tree-action]': page.treeButton('collapse')});
  assert.equal(page.context.open, false);
  assert.ok(page.nodes.every(node => !node.open));
});

test('one request collapse/expand closes/opens ALL descendants without touching a reused peer thread', () => {
  const page = fixture();
  page.toggle(page.group, false);
  assert.ok(page.descendants.every(node => !node.open));
  assert.equal(page.peerGroup.open, true);
  assert.equal(page.peer.open, false);
  page.toggle(page.group, true);
  assert.ok(page.descendants.every(node => node.open),
    'request identity, both sections, contexts, frames, SQL, native message and full stack must all open');
  assert.equal(page.peer.open, false);
  assert.equal(page.peerSection.open, false);
  page.toggle(page.group, false);
  assert.ok(page.descendants.every(node => !node.open));
});

test('individual SQL and call-tree toggles remain independent of their open request and each other', () => {
  const page = fixture();
  page.toggle(page.group, false);
  page.toggle(page.group, true);
  for (const node of [page.card, page.sqlSection, page.treeSection, page.context, page.nodes[0], page.rawStack]) {
    page.toggle(node, false);
    assert.equal(page.group.open, true, 'a descendant toggle must not collapse its request');
    assert.equal(page.nativeMessage.open, true, 'independent descendants must retain their own state');
  }
  page.toggle(page.treeSection, true);
  assert.equal(page.context.open, false, 'opening the Call Tree section alone preserves manually closed frames');
  assert.equal(page.card.open, false);
  assert.equal(page.sqlSection.open, false);
  page.toggle(page.sqlSection, true);
  assert.equal(page.card.open, false);
  assert.equal(page.nodes[0].open, false);
});

test('initial or duplicate native toggle events do not undo independent child choices', () => {
  const page = fixture();
  page.toggle(page.group, true);
  assert.equal(page.rawStack.open, false, 'initial parsed open state must not open all raw evidence');
  page.toggle(page.group, false);
  page.toggle(page.group, true);
  page.toggle(page.card, false);
  page.toggle(page.group, true);
  assert.equal(page.card.open, false, 'a queued duplicate event must not undo an individual SQL collapse');
});

test('leaf navigation reveals a collapsed request and SQL section, without changing peer requests', () => {
  const page = fixture();
  page.toggle(page.group, false);
  page.click({'.sql-leaf': page.leaf});
  assert.equal(page.group.open, true);
  assert.equal(page.sqlSection.open, true);
  assert.ok(page.descendants.every(node => node.open));
  assert.equal(page.peer.open, false);
  assert.equal(page.card.scroll.block, 'nearest');
  page.toggle(page.sqlSection, false);
  page.toggle(page.card, false);
  page.click({'.sql-leaf': page.leaf});
  assert.equal(page.sqlSection.open, true);
  assert.equal(page.card.open, true);
});

test('leaf activation uses one nearest scroll and moves keyboard focus to the SQL summary', () => {
  const page = fixture();
  const event = page.click({'.sql-leaf': page.leaf});
  assert.equal(event.prevented, true, 'default fragment navigation must not override the nearest scroll');
  assert.equal(page.card.scroll.block, 'nearest');
  assert.equal(page.card.scroll.inline, 'nearest');
  assert.equal(page.summary.focusOptions?.preventScroll, true);
});

test('modified leaf clicks keep native navigation without changing the source page', () => {
  for (const options of [{ctrlKey: true}, {metaKey: true}, {shiftKey: true}, {altKey: true}, {button: 1}]) {
    const page = fixture();
    const event = page.click({'.sql-leaf': page.leaf}, options);
    assert.equal(event.prevented, undefined);
    assert.equal(page.card.open, false);
    assert.equal(page.card.scroll, undefined);
    assert.equal(page.classes.has('selected'), false);
  }
});

test('a valid leaf clears a previous missing-statement error', () => {
  const page = fixture();
  page.click({'.sql-leaf': {getAttribute: () => '#statement-missing'}});
  assert.match(page.messages.textContent, /unavailable/);
  page.click({'.sql-leaf': page.leaf});
  assert.equal(page.messages.textContent, '');
});

test('direct statement fragments still open and highlight their statement', () => {
  const page = fixture('#statement-9');
  assert.equal(page.card.open, true);
  assert.equal(page.classes.has('selected'), true);
});

test('direct statement fragments also reveal closed request ancestors', () => {
  const page = fixture('#statement-9', {requestOpen: false});
  assert.equal(page.group.open, true);
  assert.equal(page.sqlSection.open, true);
  assert.equal(page.card.open, true);
  assert.equal(page.classes.has('selected'), true);
  assert.equal(page.card.scroll, undefined, 'initial fragment handling must not add a second scripted scroll');
});

test('switching to another request leaves exactly one selected SQL card without changing the fragment', () => {
  const page = fixture('#statement-9');
  page.click({'.sql-leaf': {getAttribute: () => '#statement-19'}});
  assert.equal(page.classes.has('selected'), false);
  assert.equal(page.peerClasses.has('selected'), true);
  assert.equal(page.peer.open, true);
  assert.equal(page.peer.focusOptions.preventScroll, true);
  assert.equal(page.window.location.hash, '#statement-9');
  assert.doesNotMatch(jsp, /\.statement-card:target/, 'an old URL fragment must not retain a second highlight');
});

test('an unknown direct fragment leaves statement visibility and selection unchanged', () => {
  const page = fixture('#statement-999');
  assert.equal(page.card.open, false);
  assert.equal(page.classes.has('selected'), false);
  assert.equal(page.messages.textContent, '');
});

test('HTTP Copy SQL really copies plain text with placeholders and gives local feedback', () => {
  const page = fixture();
  page.click({'.copy-sql': page.sqlCopyButton});
  assert.deepEqual(page.attempts, [page.sql.textContent]);
  assert.equal(page.selection.cleared, true);
  assert.equal(page.selection.range, page.oldRange);
  assert.equal(page.active.focusOptions.preventScroll, true);
  assert.ok(page.fields.every(field => field.appended && field.removed));
  assert.equal(page.sqlCopyButton.disabled, false);
  assert.match(page.cardMessages.textContent, /Placeholders are unchanged/);
  assert.match(page.cardMessages.textContent, /copied to clipboard/);
  assert.equal(page.messages.textContent, '', 'copy feedback belongs beside the SQL, not above all requests');
  assert.equal(page.sql.textContent, 'UPDATE WTPart\nSET name=?\nWHERE idA2A2=?');
});

test('clipboard failure is explicit and unrelated clicks do not fabricate success', () => {
  const page = fixture('', {copyResult: false});
  page.click({});
  assert.equal(page.messages.textContent, '');
  page.click({'.copy-sql': page.sqlCopyButton});
  assert.match(page.cardMessages.textContent, /Could not copy SQL.*denied or failed/);
  assert.doesNotMatch(page.cardMessages.textContent, /copied to clipboard/);
  assert.equal(page.sqlCopyButton.disabled, false);
  assert.equal(page.fields[0].removed, true);
});

test('copy feedback still falls back to the page status if an older JSP has no local status', () => {
  const page = fixture();
  const query = page.card.querySelector;
  page.card.querySelector = name => name === '.sql-feedback' ? null : query(name);
  page.click({'.copy-sql': page.sqlCopyButton});
  assert.match(page.messages.textContent, /Placeholders are unchanged/);
  assert.deepEqual(page.attempts, [page.sql.textContent]);
});

test('secure clipboard Copy SQL waits for real success and suppresses duplicate pending copies', async () => {
  let resolve;
  const page = fixture('', {secure: true, writeText: () => new Promise(done => {resolve = done;})});
  page.click({'.copy-sql': page.sqlCopyButton});
  page.click({'.copy-sql': page.sqlCopyButton});
  assert.deepEqual(page.clipboardWrites, [page.sql.textContent]);
  assert.equal(page.sqlCopyButton.disabled, true);
  assert.match(page.cardMessages.textContent, /Copying SQL/);
  assert.doesNotMatch(page.cardMessages.textContent, /copied to clipboard/);
  assert.deepEqual(page.attempts, []);
  resolve();
  await settleClipboard();
  assert.match(page.cardMessages.textContent, /SQL copied to clipboard/);
  assert.equal(page.sqlCopyButton.disabled, false);
  assert.deepEqual(page.fields, []);
});

test('HTTP uses the synchronous fallback even when a clipboard-shaped API exists', () => {
  const page = fixture('', {writeText: () => {throw new Error('must not use insecure Clipboard API');}});
  page.click({'.copy-sql': page.sqlCopyButton});
  assert.deepEqual(page.clipboardWrites, []);
  assert.deepEqual(page.attempts, [page.sql.textContent]);
  assert.match(page.cardMessages.textContent, /SQL copied to clipboard/);
});

test('secure API denial can use a real successful text fallback, never a selection-only success', async () => {
  const page = fixture('', {secure: true, writeText: () => Promise.reject(new Error('denied'))});
  page.click({'.copy-sql': page.sqlCopyButton});
  await settleClipboard();
  assert.deepEqual(page.attempts, [page.sql.textContent]);
  assert.match(page.cardMessages.textContent, /SQL copied to clipboard/);
});

test('denied secure API and failed fallback report an error and restore the copy button', async () => {
  const page = fixture('', {secure: true, writeText: () => Promise.reject(new Error('denied')), copyResult: false});
  page.click({'.copy-sql': page.sqlCopyButton});
  await settleClipboard();
  assert.match(page.cardMessages.textContent, /Could not copy SQL.*denied or failed/);
  assert.doesNotMatch(page.cardMessages.textContent, /copied to clipboard/);
  assert.equal(page.sqlCopyButton.disabled, false);
  assert.equal(page.fields[0].removed, true);
});

test('thrown API/fallback errors, unavailable fallback and nonboolean results never show fake success', () => {
  for (const options of [
    {secure: true, writeText: () => {throw new Error('denied');}, copyThrows: true},
    {copyThrows: true}, {noExecCommand: true}, {copyResult: 'true'}
  ]) {
    const page = fixture('', options);
    page.click({'.copy-sql': page.sqlCopyButton});
    assert.match(page.cardMessages.textContent, /Could not copy SQL/);
    assert.doesNotMatch(page.cardMessages.textContent, /copied to clipboard/);
    assert.equal(page.sqlCopyButton.disabled, false);
    assert.ok(page.fields.every(field => field.removed));
  }
});

test('the HTTP text fallback does not need a document selection API', () => {
  const page = fixture();
  page.window.getSelection = () => null;
  page.click({'.copy-sql': page.sqlCopyButton});
  assert.deepEqual(page.attempts, [page.sql.textContent]);
  assert.match(page.cardMessages.textContent, /copied to clipboard/);
});

test('copy preserves the previous input selection and focus without scrolling', () => {
  const page = fixture();
  Object.assign(page.active, {selectionStart: 2, selectionEnd: 5, selectionDirection: 'backward',
    setSelectionRange: (...args) => {page.active.restoredSelection = args;}});
  page.click({'.copy-sql': page.sqlCopyButton});
  assert.deepEqual(page.active.restoredSelection, [2, 5, 'backward']);
  assert.equal(page.active.focusOptions.preventScroll, true);
  assert.equal(page.document.activeElement, page.active);
});

test('Copy Call Tree includes closed descendants and reports locally without altering independent toggles', () => {
  const page = fixture();
  page.toggle(page.context, false);
  page.toggle(page.nodes[0], false);
  page.cardMessages.textContent = 'Previous SQL status';
  page.click({'.copy-tree': page.treeCopyButton});
  assert.deepEqual(page.attempts, [page.tree.textContent]);
  assert.match(page.treeMessages.textContent, /Call Tree copied to clipboard/);
  assert.equal(page.context.open, false);
  assert.equal(page.nodes[0].open, false);
  assert.equal(page.card.open, false);
  assert.equal(page.cardMessages.textContent, 'Previous SQL status');
  assert.equal(page.messages.textContent, '');
  assert.equal(page.peer.open, false);
});

test('Call Tree clipboard denial is visible beside that tree and never overwrites SQL feedback', async () => {
  const page = fixture('', {secure: true, writeText: () => Promise.reject(new Error('denied')), noExecCommand: true});
  page.cardMessages.textContent = 'SQL status';
  page.click({'.copy-tree': page.treeCopyButton});
  await settleClipboard();
  assert.match(page.treeMessages.textContent, /Could not copy Call Tree.*denied or failed/);
  assert.equal(page.cardMessages.textContent, 'SQL status');
  assert.equal(page.treeCopyButton.disabled, false);
});

test('SQL and Call Tree copy literal plain text, never their escaped or rendered HTML', async () => {
  for (const secure of [true, false]) {
    const page = fixture('', {secure, writeText: () => Promise.resolve()});
    page.sql.textContent = "UPDATE WTPART SET name='<tag> & \"quoted\" \\n 日本語' WHERE idA2A2=?";
    page.sql.innerHTML = 'DO NOT COPY &lt;tag&gt;';
    page.tree.textContent += '  example.<literal&>(Source.java:1)\n';
    page.tree.innerHTML = '<details><summary>DO NOT COPY</summary></details>';
    page.click({'.copy-sql': page.sqlCopyButton});
    page.click({'.copy-tree': page.treeCopyButton});
    await settleClipboard();
    assert.deepEqual(secure ? page.clipboardWrites : page.attempts, [page.sql.textContent, page.tree.textContent]);
    assert.doesNotMatch(client, /innerHTML|ClipboardItem|text\/html/);
  }
});

test('missing or empty copy sources report unavailable text and never call the clipboard', () => {
  for (const label of ['sql', 'tree']) {
    const page = fixture();
    page[label].textContent = '';
    page.click({['.copy-' + label]: label === 'sql' ? page.sqlCopyButton : page.treeCopyButton});
    assert.match((label === 'sql' ? page.cardMessages : page.treeMessages).textContent, /unavailable; nothing was copied/);
    assert.deepEqual(page.attempts, []);
    assert.deepEqual(page.clipboardWrites, []);
  }
  const page = fixture();
  page.card.querySelector = () => null;
  page.click({'.copy-sql': page.sqlCopyButton});
  assert.match(page.messages.textContent, /unavailable; nothing was copied/);
  assert.deepEqual(page.attempts, []);
});

test('a successful retry replaces local clipboard failure and clears stale page feedback', () => {
  const options = {copyResult: false};
  const page = fixture('', options);
  page.click({'.copy-sql': page.sqlCopyButton});
  assert.match(page.cardMessages.textContent, /Could not copy/);
  page.click({'.sql-leaf': {getAttribute: () => '#statement-missing'}});
  options.copyResult = true;
  page.click({'.copy-sql': page.sqlCopyButton});
  assert.match(page.cardMessages.textContent, /SQL copied to clipboard/);
  assert.equal(page.messages.textContent, '');
});

test('one page supports both legacy URLs and pairs SQL cards with call-tree leaves', () => {
  assert.match(jsp, /kind != null && !"sql"\.equals\(kind\) && !"stack"\.equals\(kind\)/);
  assert.match(jsp, /tree\(out, context\.roots\(\)\)/);
  assert.match(jsp, /id="statement-<%=statement\.sequence\(\)%>"/);
  assert.match(jsp, /href=\\"#statement-/);
  assert.match(jsp, /html\(statement\.sql\(\)\)/);
  assert.match(jsp, /html\(statement\.rawStack\(\)\)/);
  assert.match(jsp, /html\(statement\.nativeMessage\(\)\)/);
  assert.match(jsp, /html\(node\.frame\(\)\)/);
  assert.match(jsp, /html\(report\.warnings\(\)/);
  assert.match(jsp, /class="sql-feedback" role="status" aria-live="polite"/);
  assert.match(jsp, /<details class="request" open>/);
  assert.match(jsp, /<details class="call-tree" open>/);
  assert.match(jsp, /<details class="sql-section" open>/);
  assert.match(jsp, /class="copy-tree">Copy Call Tree/);
  assert.match(jsp, /class="copy-sql">Copy SQL/);
  assert.doesNotMatch(jsp, /Select SQL text|onclick=/);
  assert.match(jsp, /class="call-tree-text" hidden><%=html\(SqlEvidencePresentation\.callTreeText\(group\)\)/);
  assert.doesNotMatch(jsp, /if \("sql"\.equals\(kind\)\)/);
});

test('recorded and not-recorded scopes sit together and legacy scope is explicitly unavailable', () => {
  assert.match(jsp, /class="scope-layout"/);
  assert.match(jsp, /Recorded table scope/);
  assert.match(jsp, /Not Recorded table scope/);
  assert.match(jsp, /report\.notRecordedScopeAvailable\(\)/);
  assert.match(jsp, /String\.join\(", ", report\.notRecordedTables\(\)\)/);
  assert.match(jsp, /Not Recorded scope is unavailable/);
  assert.match(jsp, /Legacy or unavailable evidence is never reconstructed/);
  assert.doesNotMatch(jsp, /DbCaptureSettings|DbCaptureScopeCatalog|MonitoringScope\.current/);
});

test('Start freezes one catalog with the baseline filter and historical reads never consult current scope', () => {
  const service = fs.readFileSync(path.join(javaRoot, 'StandardDbCaptureService.java'), 'utf8');
  const boundary = fs.readFileSync(path.join(javaRoot, 'DbCaptureDiagnostics.java'), 'utf8');
  const engine = fs.readFileSync(path.join(javaRoot, 'engine/CaptureEngine.java'), 'utf8');
  const begin = engine.slice(engine.indexOf('public CaptureBaseline begin('), engine.indexOf('public CaptureResult end('));
  assert.equal((begin.match(/MonitoringScope\.readCatalog\(connection\)/g) || []).length, 1);
  assert.match(begin, /new CaptureBaseline\(scn, activity, warning, filter, catalog\)/);
  assert.match(service, /MonitoringScope\.Catalog catalog = baseline\.getCatalog\(\)/);
  assert.doesNotMatch(service, /MonitoringScope\.readCatalog\(c\)/,
    'diagnostic scope must reuse the same frozen physical catalog as row collection');
  assert.match(service, /catalog\.includedTables\(baseline\.getScope\(\)\), catalog\.getTableNames\(\)/);
  assert.doesNotMatch(service, /engine\.allIncludedTables\(baseline\.getScope\(\)\)/,
    'two catalog reads could yield a recorded scope and complement from different instants');
  const read = boundary.slice(boundary.indexOf('public static Report read('), boundary.indexOf('public static String warning('));
  assert.match(read, /snapshot\.isNotRecordedScopeAvailable\(\) \? snapshot\.getNotRecordedTables\(\) : null/);
  assert.doesNotMatch(read, /readCatalog|DbCaptureSettings|TableFilter|allIncludedTables/);
});

test('empty historical evidence and partial stacks are explicitly distinguished from no database changes', () => {
  assert.match(jsp, /Historical SQL\/stacks cannot be reconstructed/);
  assert.match(jsp, /statement\.stackTruncated\(\)/);
  assert.match(jsp, /outer caller path is incomplete/);
  assert.match(jsp, /older captures can have a narrower scope/);
});

test('warning height overrides the existing eight-em recorded-text rule without changing it', () => {
  assert.match(css, /textarea\.dbcRecordedText\s*\{[^}]*height:\s*8em !important/);
  assert.match(css, /textarea\.dbcWarningSummary\s*\{[^}]*height:\s*5\.5em !important/);
  assert.ok(css.indexOf('textarea.dbcWarningSummary') > css.indexOf('textarea.dbcRecordedText'));
});

test('long call paths scroll inside panels instead of widening a narrow browser page', () => {
  assert.match(jsp, /\.request-layout > div\s*\{\s*min-width:\s*0/);
  assert.match(jsp, /@media \(max-width: 950px\)[^\n]*grid-template-columns:\s*minmax\(0, 1fr\)/);
  assert.match(jsp, /\.tree-panel, \.statements\s*\{[^}]*overflow:\s*auto/);
});
