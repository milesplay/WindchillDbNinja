const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const test = require('node:test');
const vm = require('node:vm');
const root = path.resolve(__dirname, '..');
const jsp = fs.readFileSync(path.join(root,
  'customization/DbCapture/main/src_web/custom/DbCapture/overlay/netmarkets/jsp/dbcapture/dbCaptureAdmin.jsp'), 'utf8');
const start = jsp.indexOf('function dbcSetScopeStatus');
const end = jsp.indexOf('   dbCaptureLoadScope();', start);
const scopeScript = jsp.slice(start, end);
const helpers = ['dbcMessage', 'dbcPostJson'].map(name =>
  jsp.match(new RegExp('function ' + name + '\\([^]*?\\n   \\}'))[0]).join('\n');
const saved = {ok: true, scopeExcluded: ['QUEUEENTRY', 'RECENTUPDATE'], scopeIncluded: [],
  scopeLocked: ['METHODCONTEXTS'], scopeReasons: {QUEUEENTRY: 'Queue', RECENTUPDATE: 'Index',
    METHODCONTEXTS: 'No IDA2A2'}, scopeNotice: 'Site exclusions are retained.'};

function page() {
  function element() {
    return {value: '', style: {}, children: [], disabled: true, textContent: '',
      get options() {return this.children;},
      set innerHTML(value) {this.children = []; this.textContent = value;},
      appendChild(node) {this.children.push(node); if (typeof node === 'string') this.textContent += node;}};
  }
  const ids = ['dbcScopeStatus', 'dbcSaveScopeButton', 'dbcScopeExcluded', 'dbcScopeIncluded',
    'dbcScopeFind', 'dbcIncludeScopeButton', 'dbcExcludeScopeButton', 'dbcEditScopeButton',
    'dbcCancelScopeButton', 'dbcReloadScopeButton', 'dbcScopeExcludedCount', 'dbcScopeIncludedCount',
    'dbcScopeLocked', 'dbcScopeLockedCount', 'dbcScopeNotice', 'dbcSearchButton'];
  const elements = Object.fromEntries(ids.map(id => [id, element()]));
  const requests = [];
  class Request {
    constructor() {requests.push(this);}
    open(method, url) {this.method = method; this.url = url;}
    setRequestHeader() {}
    send(body) {this.body = body;}
    respond(status, value) {
      this.status = status; this.responseText = typeof value === 'string' ? value : JSON.stringify(value);
      this.readyState = 4; this.onreadystatechange();
    }
  }
  const context = {DBC_ENDPOINT: '/state', XMLHttpRequest: Request, window: {confirm: () => true},
    document: {getElementById: id => elements[id], createTextNode: value => value, createElement: element}};
  vm.runInNewContext(helpers + '\n' + scopeScript, context);
  function ready() {
    context.dbCaptureLoadScope();
    requests[0].respond(200, saved);
    requests.length = 0;
  }
  function draft() {
    context.dbcEditScope();
    elements.dbcScopeExcluded.options[0].selected = true;
    context.dbcMoveScope(true);
  }
  return {context, elements, requests, ready, draft};
}

test('scope load accepts only an authorized successful complete HTTP/JSON response', () => {
  for (const [http, body] of [[403, '{"ok":false,"message":"Denied"}'], [200, 'null'],
    [500, '<error>'], [200, '{"ok":true}']]) {
    const p = page();
    p.context.dbCaptureLoadScope();
    p.requests[0].respond(http, body);
    assert.equal(p.context.dbcScopeLoaded, false);
    assert.equal(p.elements.dbcScopeExcluded.disabled, true);
    assert.match(p.elements.dbcScopeStatus.textContent, /Denied|Could not read/);
  }
});

test('scope read uses its dedicated catalog operation, not the global banner poll', () => {
  const p = page();
  p.context.dbCaptureLoadScope();
  assert.equal(p.requests[0].body, 'op=scope');
  p.requests[0].respond(200, saved);
  assert.deepEqual(p.elements.dbcScopeExcluded.options.map(o => o.value), ['QUEUEENTRY', 'RECENTUPDATE']);
  assert.equal(p.elements.dbcScopeExcluded.disabled, true);
  assert.equal(p.elements.dbcEditScopeButton.disabled, false);
  assert.equal(p.elements.dbcScopeNotice.textContent, saved.scopeNotice);
});

test('scope save sends the entire exact right-hand selection atomically', () => {
  const p = page();
  p.ready(); p.draft();
  p.context.dbCaptureSaveScope();
  const values = new URLSearchParams(p.requests[0].body);
  assert.deepEqual([...values], [['op', 'settings'], ['includedTables', 'QUEUEENTRY']]);
  p.requests[0].respond(200, {ok: false, message: 'Not an eligible table'});
  assert.match(p.elements.dbcScopeStatus.textContent, /Not an eligible table.*draft has been retained/);
  assert.deepEqual(Array.from(p.context.dbcScopeModel.included), ['QUEUEENTRY']);
});

test('scope save never reports success for HTTP errors, null JSON, or incomplete readback', () => {
  for (const [http, body] of [[403, '{"ok":true}'], [200, 'null'], [200, '{"ok":true}']]) {
    const p = page();
    p.ready(); p.draft();
    p.context.dbCaptureSaveScope();
    p.requests[0].respond(http, body);
    assert.doesNotMatch(p.elements.dbcScopeStatus.textContent, /Applies to/);
  }
});

test('successful save relocks both lists and explicitly applies to the next capture', () => {
  const p = page();
  p.ready(); p.draft();
  p.context.dbCaptureSaveScope();
  p.requests[0].respond(200, {...saved, scopeExcluded: ['RECENTUPDATE'], scopeIncluded: ['QUEUEENTRY']});
  assert.match(p.elements.dbcScopeStatus.textContent, /Applies to the next capture/);
  assert.equal(p.elements.dbcScopeIncluded.disabled, true);
  assert.match(jsp, /No changes are visible for/);
  assert.doesNotMatch(jsp, /recorded no column changes/);
});

test('moving several rows left after saving sends an empty include list explicitly', () => {
  const p = page();
  p.context.dbcAcceptScope({...saved, scopeExcluded: [], scopeIncluded: ['QUEUEENTRY', 'RECENTUPDATE']});
  p.context.dbcEditScope();
  for (const option of p.elements.dbcScopeIncluded.options) option.selected = true;
  p.context.dbcMoveScope(false);
  p.context.dbCaptureSaveScope();
  assert.equal(p.requests[0].body, 'op=settings&includedTables=');
});

test('reload requires confirmation before discarding an unsaved draft', () => {
  const p = page();
  p.ready(); p.draft();
  p.context.window.confirm = () => false;
  p.context.dbCaptureLoadScope();
  assert.equal(p.requests.length, 0);
  assert.deepEqual(Array.from(p.context.dbcScopeModel.included), ['QUEUEENTRY']);
});

test('scope changes cannot call Start, change Hidden state, or mutate an active capture', () => {
  assert.doesNotMatch(scopeScript, /op=start|dbcHideTables|dbcRunSearch|stopCapture|startCapture/);
  assert.match(jsp, /never to a capture already running/);
});

test('authorization and bounded description validation remain on the server', () => {
  const state = fs.readFileSync(path.join(root,
    'customization/DbCapture/main/src_web/custom/DbCapture/overlay/netmarkets/jsp/dbcapture/dbCaptureState.jsp'), 'utf8');
  assert.match(state, /requireAdministrator\(\)/);
  assert.match(state, /getBannerState\(\)/);
  assert.match(state, /Unsupported DB Capture operation/);
  assert.doesNotMatch(state, /"abort"\.equals\(op\)/);
  assert.match(state, /value\.length\(\) > 400/);
});
