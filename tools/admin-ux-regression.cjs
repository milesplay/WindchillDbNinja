const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const test = require('node:test');
const vm = require('node:vm');

const root = path.resolve(__dirname, '..');
const admin = fs.readFileSync(path.join(root,
  'customization/DbCapture/main/src_web/custom/DbCapture/overlay/netmarkets/jsp/dbcapture/dbCaptureAdmin.jsp'), 'utf8');
const script = admin.match(/<script type="text\/javascript">([\s\S]*?)<\/script>/)[1]
  .replace(/<%=[\s\S]*?%>/g, '');
const wiring = script.indexOf('   (function () {\n      function onClick');
assert.ok(wiring > 0);
const functions = script.slice(0, wiring).replace(/^\s*dbCaptureLoadScope\(\);/m, '');
const ids = ['dbcapture.sessionTable', 'dbcapture.changeTable'];
const scopeIds = ['dbcScopeExcluded', 'dbcScopeIncluded', 'dbcScopeFind',
  'dbcIncludeScopeButton', 'dbcExcludeScopeButton'];
const scopeData = {
  ok: true, scopeExcluded: ['QUEUEENTRY', 'RECENTUPDATE'], scopeIncluded: [],
  scopeLocked: ['METHODCONTEXTS'], scopeNotice: 'Business tables remain included.',
  scopeReasons: {QUEUEENTRY: 'Queue work', RECENTUPDATE: 'Recent-object index', METHODCONTEXTS: 'No IDA2A2'}
};

function element(value = '') {
  let text = '';
  return {
    value, checked: false, disabled: false, className: '', style: {},
    children: [], attributes: {}, listeners: {},
    get textContent() {return text + this.children.map(c => typeof c === 'string' ? c : c.textContent).join('');},
    set textContent(value) {text = String(value); this.children = [];},
    get options() {return this.children;},
    set innerHTML(value) { this.textContent = value; this.children = []; },
    get innerHTML() { return this.textContent; },
    appendChild(node) { this.children.push(node); },
    setAttribute(name, value) { this.attributes[name] = value; },
    addEventListener(type, handler) {this.listeners[type] = handler;}, focus() {}
  };
}

function page() {
  const elements = Object.fromEntries([
    'dbcKeyword', 'dbcSearchButton', 'dbcStopButton', 'dbcSearchStatus', 'dbcActionStatus',
    'dbcFilterRow', 'dbcFilterCapture', 'dbcHideTables', 'dbcKnownTables',
    'dbcHiddenReadback', 'dbcFilterStatus', 'dbcScopeStatus', 'dbcSaveScopeButton',
    'dbcShowUnfinished', 'dbcEditScopeButton', 'dbcCancelScopeButton', 'dbcReloadScopeButton',
    'dbcScopeExcludedCount', 'dbcScopeIncludedCount', 'dbcScopeLocked', 'dbcScopeLockedCount',
    'dbcScopeNotice', 'dbcCsvStatus', ...scopeIds
  ].map(id => [id, element()]));
  const requests = [], reloads = [], cancelled = [], alerts = [];
  const timers = new Map();
  let clock = 1000, timerId = 0;
  class FakeDate extends Date {
    constructor(...args) { super(...(args.length ? args : [clock])); }
    static now() { return clock; }
  }
  class Store {
    constructor(id) {
      this.jscaTableID = id;
      this.chunkEnabled = true;
      this.chunk = {id: id + '-source'};
      this.load_complete = true;
      this.proxy = {dataSourceLoading: false};
      this.totalLength = 0;
      this.records = [];
      this.listeners = new Map();
      this.data = {
        each: fn => this.records.forEach(fn),
        get length() { return this.items.length; },
        items: this.records
      };
    }
    on(event, handler, scope) {
      const key = event.toLowerCase();
      if (!this.listeners.has(key)) this.listeners.set(key, []);
      this.listeners.get(key).push({handler, scope});
    }
    un(event, handler) {
      const key = event.toLowerCase();
      this.listeners.set(key, (this.listeners.get(key) || []).filter(x => x.handler !== handler));
    }
    fireEvent(event, ...args) {
      for (const {handler, scope} of [...(this.listeners.get(event.toLowerCase()) || [])]) {
        handler.apply(scope || this, args);
      }
    }
    getCount() { return this.records.length; }
    getTotalCount() { return Math.max(this.totalLength, this.records.length); }
    getAt(index) { return this.records[index]; }
    getRange() { return this.records; }
    each(fn) { this.records.forEach(fn); }
    isLoading() { return this.proxy.dataSourceLoading; }
    setBaseParam() {}
    removeAll() {
      this.records = [];
      this.data.items = this.records;
      this.fireEvent('clear', this, []);
    }
    setRows(rows) {
      this.records = rows.map(data => ({data, get: key => data[key]}));
      this.data.items = this.records;
      this.totalLength = rows.length;
    }
  }
  const tables = Object.fromEntries(ids.map(id => {
    const store = new Store(id);
    const selection = {clearSelections() {}, selectRow() {}};
    const grid = {id, titleCount: 0, jcaTableConfig: {chunk: store.chunk},
      getStore: () => store, getSelectionModel: () => selection};
    store.on('datachanged', () => {grid.titleCount = store.getCount();});
    return [id, grid];
  }));
  function cancel(store) {
    cancelled.push(store.jscaTableID);
    for (const load of reloads) {
      if (load.id === store.jscaTableID && !load.completed) load.cancelled = true;
    }
    store.proxy.dataSourceLoading = false;
    store.load_complete = true;
    store.fireEvent('DSLoadingInterupted', 'Cancelled');
  }
  class Request {
    constructor() { requests.push(this); }
    open(method, url) { this.method = method; this.url = url; }
    setRequestHeader() {}
    send(body) { this.body = body; }
    abort() { this.aborted = true; if (this.onabort) this.onabort(); }
    respond(status, data) {
      this.status = status;
      this.responseText = typeof data === 'string' ? data : JSON.stringify(data);
      this.readyState = 4;
      this.onreadystatechange();
    }
  }
  const context = {
    document: {
      getElementById: id => elements[id] || null,
      getElementsByTagName: () => Object.values(elements),
      createTextNode: text => text,
      createElement: () => element(),
      querySelectorAll: () => elements.dbcHiddenReadback.children
        .filter(c => typeof c !== 'string').flatMap(chip => chip.children.filter(c => typeof c !== 'string')),
      oncontextmenu: null
    },
    window: {addEventListener() {}, removeEventListener() {}, confirm: () => true},
    XMLHttpRequest: Request, Date: FakeDate, alert: text => alerts.push(text),
    setTimeout(fn, delay) { timers.set(++timerId, {fn, at: clock + delay}); return timerId; },
    clearTimeout(id) { timers.delete(id); },
    setInterval(fn, delay) { timers.set(++timerId, {fn, at: clock + delay, interval: delay}); return timerId; },
    clearInterval(id) { timers.delete(id); },
    Ext: {
      getCmp(id) {
        const grid = Object.values(tables).find(g => id === 'header_' + g.id + '_cancelDataSourceButton');
        return grid ? {cancelButtonHandler: () => cancel(grid.getStore())} : null;
      }
    },
    PTC: {
      util: {
        unescapeHTML: value => value.replace(/&(lt|gt|amp|quot|#39);/g,
          (_, name) => ({lt: '<', gt: '>', amp: '&', quot: '"', '#39': "'"})[name])
      },
      jca: {
        DataSourceRegistry: {killDataSource: store => cancel(store)},
        table: {Utils: {
          getTable: id => typeof id === 'string' ? tables[id] : id,
          getTables: () => Object.values(tables),
          reload(grid, params) {
            const store = grid.getStore();
            store.load_complete = false;
            store.proxy.dataSourceLoading = true;
            store.totalLength = 0;
            store.removeAll();
            reloads.push({id: grid.id, params: JSON.parse(JSON.stringify(params))});
          }
        }}
      }
    }
  };
  vm.runInNewContext(functions, context, {filename: 'dbCaptureAdmin.jsp'});
  const csvAction = {disabled: false, setDisabled(value) {this.disabled = value;}};
  context.dbcCsvAction = csvAction;
  function advance(ms) {
    const end = clock + ms;
    let steps = 0;
    while (true) {
      const next = [...timers].filter(([, t]) => t.at <= end).sort((a, b) => a[1].at - b[1].at)[0];
      if (!next) break;
      assert.ok(++steps < 10000, 'timers must be bounded');
      const [id, timer] = next;
      clock = timer.at;
      if (timer.interval) timer.at += timer.interval;
      else timers.delete(id);
      timer.fn();
    }
    clock = end;
  }
  function approveFilter() {
    const request = requests.find(r => !r.aborted && !r.readyState && /^op=tables/.test(r.body || ''));
    if (request) request.respond(200, {ok: true, known: ['WTPART', 'WTPARTMASTER'],
      available: ['WTPART', 'WTPARTMASTER'], hidden: [], unknown: []});
  }
  function complete(load, rows, failed = false) {
    load.completed = true;
    if (load.cancelled) return;
    const store = tables[load.id].getStore();
    store.setRows(rows);
    store.load_complete = true;
    store.proxy.dataSourceLoading = false;
    store.fireEvent(failed ? 'exception' : 'datasourcecomplete', store);
  }
  function loadScope() {
    context.dbCaptureLoadScope();
    requests.at(-1).respond(200, scopeData);
  }
  function editScope() {
    context.dbcEditScope();
    elements.dbcScopeExcluded.options[0].selected = true;
    context.dbcMoveScope(true);
  }
  return {context, elements, requests, reloads, cancelled, alerts, tables,
    advance, approveFilter, complete, loadScope, editScope, csvAction,
    status: () => elements.dbcSearchStatus.textContent};
}

test('Clear updates standard Windchill title counters as well as store data', () => {
  const p = page();
  for (const grid of Object.values(p.tables)) {
    grid.getStore().setRows([{captureId: 'CAP-OLD'}]);
    grid.titleCount = 1;
  }
  p.context.dbCaptureClear();
  for (const grid of Object.values(p.tables)) {
    assert.equal(grid.getStore().getCount(), 0);
    assert.equal(grid.titleCount, 0);
  }
});

test('context description decodes framework escaping once and preserves literal text/whitespace', () => {
  const p = page(), grid = p.tables[ids[0]];
  grid.getStore().setRows([{description: '  &lt;keep&gt; &amp; &quot;\u65e5\u672c\u8a9e&quot; &#39;x&#39; &amp;lt;  '}]);
  assert.equal(p.context.dbcRecordValue(grid, 0, 'description'), '  <keep> & "\u65e5\u672c\u8a9e" \'x\' &lt;  ');
});

test('failed Hidden metadata does not replace the actually-applied readback', () => {
  const p = page();
  p.context.dbcFillReadback(['WTPART']);
  p.context.dbcReportFilter();
  p.requests.at(-1).respond(503, {ok: false, message: 'Unavailable'});
  assert.equal(p.elements.dbcHiddenReadback.textContent, 'WTPART\u00d7');
  assert.match(p.elements.dbcFilterStatus.textContent, /Unavailable/);
});

test('hidden selections whose last capture was deleted do not block subsequent searches', () => {
  const p = page();
  p.elements.dbcHideTables.value = 'WTPART, NO_SUCH_TABLE';
  p.context.dbCaptureSearch();
  assert.equal(p.reloads.length, 0);
  assert.equal(p.requests.length, 1);
  p.requests[0].respond(200, {ok: true, known: ['WTPART'], hidden: ['WTPART'], unknown: ['NO_SUCH_TABLE']});
  assert.equal(p.reloads.length, 2);
  assert.equal(p.reloads[1].params.dbcHideTables, 'WTPART, NO_SUCH_TABLE');
});

test('context Hidden addition refreshes only Database Changes, never Capture Sessions', () => {
  const p = page();
  p.context.dbcAddHiddenTable('WTPART');
  p.approveFilter();
  assert.deepEqual(p.reloads.map(r => r.id), [ids[1]]);
});

test('new keyword results reconcile a capture filter absent from the matching sessions', () => {
  const p = page();
  p.context.dbcCaptureFilter = 'CAP-OLD';
  p.elements.dbcKeyword.value = 'target';
  p.context.dbCaptureSearch();
  p.approveFilter();
  p.complete(p.reloads[0], [{captureId: 'CAP-NEW'}]);
  p.complete(p.reloads[1], []);
  p.advance(100);
  assert.equal(p.context.dbcCaptureFilter, null);
  assert.equal(p.reloads.length, 3);
  assert.equal(p.reloads[2].params.dbcCaptureId, undefined);
  assert.equal(p.reloads[2].params.dbcKeyword, 'target');
});

test('search timeout is bounded even when a store never reports completion', () => {
  const p = page();
  p.context.dbCaptureSearch();
  p.approveFilter();
  p.advance(120001);
  assert.equal(p.elements.dbcSearchButton.disabled, false);
  assert.match(p.status(), /timed out/i);
});

test('Clear cancels only its active Windchill data sources before a late reply', () => {
  const p = page();
  p.context.dbCaptureSearch();
  p.approveFilter();
  const loads = [...p.reloads];
  p.context.dbCaptureClear();
  for (const load of loads) p.complete(load, [{captureId: 'CAP-OLD'}]);
  for (const grid of Object.values(p.tables)) assert.equal(grid.getStore().getCount(), 0);
  assert.deepEqual(p.cancelled.sort(), [...ids].sort());
  assert.match(p.status(), /Cleared/);
});

test('Stop search cancels active loading instead of leaving a misleading pending result', () => {
  const p = page();
  p.context.dbCaptureSearch();
  p.approveFilter();
  p.context.dbCaptureStopSearch();
  assert.deepEqual(p.cancelled.sort(), [...ids].sort());
  assert.equal(p.elements.dbcSearchButton.disabled, false);
  assert.match(p.status(), /stopped/i);
});

test('a normal empty search is an inline result, not a blocking alert', () => {
  const p = page();
  p.elements.dbcKeyword.value = 'no-match';
  p.context.dbCaptureSearch();
  p.approveFilter();
  p.complete(p.reloads[0], []);
  p.complete(p.reloads[1], []);
  p.advance(2000);
  assert.equal(p.alerts.length, 0);
  assert.match(p.status(), /No matches/i);
});

test('scope inputs cannot save false defaults before the initial load completes', () => {
  const p = page();
  p.context.dbCaptureLoadScope();
  for (const id of [...scopeIds, 'dbcSaveScopeButton']) assert.equal(p.elements[id].disabled, true);
});

test('scope save is single-flight and has a bounded timeout', () => {
  const p = page();
  p.loadScope();
  p.editScope();
  const before = p.requests.length;
  p.context.dbCaptureSaveScope();
  p.context.dbCaptureSaveScope();
  assert.equal(p.requests.length - before, 1);
  assert.equal(p.requests.at(-1).timeout, 30000);
  p.requests.at(-1).ontimeout();
  assert.match(p.elements.dbcScopeStatus.textContent, /timed out/i);
  assert.equal(p.elements.dbcSaveScopeButton.disabled, false);
});

test('unavailable initial scope retries reading instead of writing unchecked defaults', () => {
  const p = page();
  p.context.dbCaptureLoadScope();
  p.requests.at(-1).respond(503, {ok: false, message: 'Unavailable'});
  p.context.dbCaptureSaveScope();
  assert.equal(p.requests.length, 1);
  p.context.dbCaptureLoadScope();
  assert.equal(p.requests.at(-1).body, 'op=scope');
  assert.equal(p.elements.dbcScopeExcluded.disabled, true);
});

test('editing a previously saved scope marks the current draft unsaved', () => {
  const p = page();
  p.loadScope();
  assert.equal(typeof p.context.dbCaptureScopeChanged, 'function');
  p.editScope();
  assert.match(p.elements.dbcScopeStatus.textContent, /unsaved|not saved/i);
});

test('row checkboxes and modifier selection are not overwritten by custom row filtering', () => {
  const p = page(), grid = p.tables[ids[0]];
  grid.getStore().setRows([{captureId: 'CAP-TEST'}]);
  let selected = 0, filtered = 0;
  p.context.dbcSelectRow = () => {selected++;};
  p.context.dbcSetCaptureFilter = () => {filtered++;};
  for (const event of [
    {getTarget: () => ({})}, {ctrlKey: true}, {metaKey: true}, {shiftKey: true}
  ]) p.context.dbcSessionRowClick(grid, 0, event);
  assert.equal(selected, 0);
  assert.equal(filtered, 0);
  p.context.dbcSessionRowClick(grid, 0, {getTarget: () => null});
  assert.equal(selected, 1);
  assert.equal(filtered, 1);
});

test('manual lookup of a deleted completed capture is not described as pending Stop results', () => {
  const p = page();
  p.context.DBC_COMPLETED_CAPTURE = 'CAP-OLD';
  p.elements.dbcKeyword.value = 'CAP-OLD';
  p.context.dbCaptureSearch();
  p.approveFilter();
  p.complete(p.reloads[0], []);
  p.complete(p.reloads[1], []);
  p.advance(2000);
  assert.match(p.status(), /No matches/);
  assert.doesNotMatch(p.status(), /still being applied|did not appear/);
});

test('incomplete filter metadata is rejected without clearing or reloading results', () => {
  const p = page();
  p.context.dbCaptureSearch();
  p.requests[0].respond(200, {ok: true});
  assert.equal(p.reloads.length, 0);
  assert.equal(p.elements.dbcSearchButton.disabled, false);
  assert.match(p.elements.dbcFilterStatus.textContent, /incomplete table metadata/);
});

test('out-of-order filter readbacks cannot overwrite the newer response', () => {
  const p = page();
  p.elements.dbcHideTables.value = 'WTPART';
  p.context.dbcReportFilter();
  p.context.dbcReportFilter();
  p.requests[1].respond(200, {ok: true, known: ['WTPART'], hidden: ['WTPART'], unknown: []});
  const message = p.elements.dbcFilterStatus.textContent;
  p.requests[0].respond(200, {ok: true, known: ['OLD'], hidden: ['OLD'], unknown: ['OLD']});
  assert.equal(p.elements.dbcHiddenReadback.textContent, 'WTPART\u00d7');
  assert.equal(p.elements.dbcFilterStatus.textContent, message);
});

test('one failed result table cannot be presented as a successful partial pair', () => {
  const p = page();
  p.context.dbCaptureSearch();
  p.approveFilter();
  p.complete(p.reloads[0], [{captureId: 'CAP-TEST'}]);
  p.complete(p.reloads[1], [], true);
  assert.equal(p.elements.dbcSearchButton.disabled, false);
  assert.match(p.status(), /could not finish loading Database Changes/);
  assert.equal(p.tables[ids[0]].getStore().getCount(), 0);
  assert.equal(p.tables[ids[1]].getStore().getCount(), 0);
});

test('table-filter validation cannot repaint a newly navigated page', () => {
  const p = page();
  p.context.dbCaptureSearch();
  p.elements.dbcSearchButton = element();
  p.requests[0].respond(200, {ok: true, known: ['WTPART'], hidden: [], unknown: []});
  assert.equal(p.reloads.length, 0);
});

test('description endpoint rejects overlong UI values before persistence can clip them', () => {
  const state = fs.readFileSync(path.join(root,
    'customization/DbCapture/main/src_web/custom/DbCapture/overlay/netmarkets/jsp/dbcapture/dbCaptureState.jsp'), 'utf8');
  assert.match(state, /startCapture\(boundedDescription\(request\.getParameter\("label"\)\)\)/);
  assert.match(state, /String description = boundedDescription\(request\.getParameter\("description"\)\)/);
  assert.match(state, /value\.length\(\) > 400/);
  assert.match(state, /availableTables\.contains\(name\)/);
});

test('Hidden controls have no duplicate group or free-text editing interface', () => {
  const p = page();
  assert.deepEqual(Array.from(p.context.dbcHiddenGroups()), []);
  assert.doesNotMatch(admin, /class="dbcHideGroup"|dbcApplyFilterButton|Also hide|DISPLAY_GROUPS/);
  assert.match(admin, /type="hidden" id="dbcHideTables"/);
});

test('Enter on keyword and scope list filter cannot submit the outer form', () => {
  const p = page(), calls = [];
  p.context.dbCaptureSearch = () => calls.push('search');
  p.context.dbcRenderScope = () => calls.push('scope-filter');
  p.context.dbcReportFilter = () => null;
  vm.runInNewContext(script.slice(wiring), p.context);
  for (const id of ['dbcKeyword', 'dbcScopeFind']) {
    let prevented = false;
    const result = p.elements[id].onkeydown({keyCode: 13, preventDefault() {prevented = true;}});
    assert.equal(prevented, true);
    assert.equal(result, false);
  }
  assert.deepEqual(calls, ['search', 'scope-filter']);
});

test('scope is locked until Edit and Cancel discards the entire dual-list draft', () => {
  const p = page();
  p.loadScope();
  assert.equal(p.elements.dbcScopeExcluded.disabled, true);
  p.context.dbcMoveScope(true);
  assert.equal(p.context.dbcScopeModel.included.length, 0);
  p.editScope();
  assert.deepEqual(Array.from(p.context.dbcScopeModel.included), ['QUEUEENTRY']);
  assert.equal(p.elements.dbcSaveScopeButton.disabled, false);
  p.context.dbcCancelScope();
  assert.equal(p.context.dbcScopeModel.included.length, 0);
  assert.equal(p.elements.dbcScopeExcluded.disabled, true);
  assert.equal(p.requests.length, 1);
});

test('scope Save persists an exact complete right-hand list and relocks after readback', () => {
  const p = page();
  p.loadScope();
  p.editScope();
  p.context.dbCaptureSaveScope();
  assert.equal(p.requests.at(-1).body, 'op=settings&includedTables=QUEUEENTRY');
  p.requests.at(-1).respond(200, {...scopeData, scopeExcluded: ['RECENTUPDATE'], scopeIncluded: ['QUEUEENTRY']});
  assert.equal(p.elements.dbcScopeIncluded.disabled, true);
  assert.match(p.elements.dbcScopeStatus.textContent, /Applies to the next capture/);
});

test('scope filtering does not lose offscreen selections in the draft', () => {
  const p = page();
  p.loadScope();
  p.editScope();
  p.elements.dbcScopeFind.value = 'RECENT';
  p.context.dbcRenderScope();
  assert.equal(p.elements.dbcScopeIncluded.options.length, 0);
  assert.deepEqual(Array.from(p.context.dbcScopeModel.included), ['QUEUEENTRY']);
  p.context.dbCaptureSaveScope();
  assert.equal(new URLSearchParams(p.requests.at(-1).body).get('includedTables'), 'QUEUEENTRY');
});

test('scope requires valid disjoint server lists and explains locked tables', () => {
  const p = page();
  assert.equal(p.context.dbcAcceptScope({...scopeData, scopeIncluded: ['QUEUEENTRY']}), false);
  assert.equal(p.context.dbcAcceptScope({...scopeData, scopeExcluded: ['<script>']}), false);
  p.loadScope();
  assert.match(p.elements.dbcScopeLocked.textContent, /METHODCONTEXTS - No IDA2A2/);
  assert.equal(p.context.dbcAcceptScope({...scopeData, scopeLocked: ['BIN$old/==$0'],
    scopeReasons: {...scopeData.scopeReasons, 'BIN$old/==$0': 'Recycle-bin table; never capturable'}}), true);
  assert.match(p.elements.dbcScopeLocked.textContent, /Recycle-bin table/);
});

test('hidden chips remove one table and preserve applied keyword, capture and other hidden selections', () => {
  const p = page();
  p.context.dbcAppliedCriteria = {keyword: 'saved', groups: '', tables: 'WTPART, WTPARTMASTER',
    unfinished: false, captureId: 'CAP-000001'};
  p.context.dbcCaptureFilter = 'CAP-000001';
  p.elements.dbcKeyword.value = 'unsubmitted draft';
  p.elements.dbcHideTables.value = 'WTPART, WTPARTMASTER';
  p.context.dbcFillReadback(['WTPART', 'WTPARTMASTER']);
  p.elements.dbcHiddenReadback.children[0].children[1].listeners.click();
  assert.equal(p.reloads.length, 1);
  assert.equal(p.reloads[0].params.dbcHideTables, 'WTPARTMASTER');
  assert.equal(p.reloads[0].params.dbcKeyword, 'saved');
  assert.equal(p.reloads[0].params.dbcCaptureId, 'CAP-000001');
});

test('a failed hide restores the applied chips and input, not a success-shaped pending filter', () => {
  const p = page();
  p.context.dbcAppliedCriteria = {keyword: '', groups: '', tables: 'WTPART', unfinished: false};
  p.elements.dbcHideTables.value = 'WTPART';
  p.context.dbcAddHiddenTable('WTPARTMASTER');
  p.complete(p.reloads[0], [], true);
  assert.equal(p.elements.dbcHideTables.value, 'WTPART');
  assert.equal(p.elements.dbcHiddenReadback.textContent, 'WTPART\u00d7');
  assert.match(p.elements.dbcFilterStatus.textContent, /failed.*previous/);
});

test('duplicate and invalid right-click table names cannot change the Hidden selection', () => {
  const p = page();
  p.elements.dbcHideTables.value = 'WTPART';
  p.context.dbcAddHiddenTable('wtpart');
  p.context.dbcAddHiddenTable('BAD*');
  assert.equal(p.reloads.length, 0);
  assert.equal(p.elements.dbcHideTables.value, 'WTPART');
});

test('both independent scope and display filter fieldsets remain collapsed by default', () => {
  assert.match(admin, /legend="Monitoring scope" id="dbCaptureScopePanel" collapsed="true"/);
  assert.match(admin, /legend="Hide these tables in Database Changes"\s+id="dbcTableFilterPanel" collapsed="true"/);
});

test('CSV captures the visible snapshot once and downloads it after authorized filename lookup', () => {
  const p = page(), downloads = [];
  let reads = 0;
  const snapshot = {headers: ['Table'], rows: [['WTPART']], captureIds: ['CAP-000001']};
  p.context.window.DbCaptureCsv = {
    snapshotGrid(grid) {assert.equal(grid, p.tables[ids[1]]); reads++; return snapshot;},
    download(data, names) {downloads.push({data, names}); return 'Rename.csv';}
  };
  p.context.dbcDownloadCsv();
  p.context.dbcDownloadCsv();
  assert.equal(p.requests.length, 1);
  assert.equal(reads, 1);
  assert.equal(p.csvAction.disabled, true);
  assert.equal(p.requests[0].body, 'op=exportNames&captureIds=CAP-000001');
  p.tables[ids[1]].getStore().setRows([{captureId: 'CAP-000002'}]);
  p.requests[0].respond(200, {ok: true, exportNames: {'CAP-000001': 'Rename'}});
  assert.equal(downloads.length, 1);
  assert.equal(downloads[0].data, snapshot);
  assert.equal(p.csvAction.disabled, false);
  assert.match(p.elements.dbcCsvStatus.textContent, /Rename\.csv.*1 row/);
});

test('CSV lookup and download failures are explicit and cannot report success', () => {
  for (const failure of ['http', 'download']) {
    const p = page();
    let downloaded = false;
    p.context.window.DbCaptureCsv = {
      snapshotGrid: () => ({headers: ['Table'], rows: [['WTPART']], captureIds: ['CAP-000001']}),
      download() {downloaded = true; throw new Error('Missing metadata');}
    };
    p.context.dbcDownloadCsv();
    p.requests[0].respond(failure === 'http' ? 403 : 200,
      failure === 'http' ? {ok: false, message: 'Denied'} : {ok: true});
    assert.equal(downloaded, failure === 'download');
    assert.match(p.elements.dbcCsvStatus.textContent, /CSV was not downloaded/);
    assert.equal(p.csvAction.disabled, false);
  }
});

test('CSV cannot export while a search is pending or when the module is unavailable', () => {
  const p = page();
  p.context.dbcActiveSearch = {};
  p.context.dbcDownloadCsv();
  assert.equal(p.requests.length, 0);
  assert.match(p.elements.dbcCsvStatus.textContent, /Wait/);
  p.context.dbcActiveSearch = null;
  p.context.dbcDownloadCsv();
  assert.equal(p.requests.length, 0);
  assert.match(p.elements.dbcCsvStatus.textContent, /did not load/);
});

test('a new search clears stale CSV feedback while preserving an in-progress download status', () => {
  const p = page();
  p.elements.dbcCsvStatus.textContent = 'No Database Changes rows are currently displayed.';
  p.context.dbcSetBusy(true);
  assert.equal(p.elements.dbcCsvStatus.textContent, '');
  p.context.dbcCsvSaving = true;
  p.elements.dbcCsvStatus.textContent = 'Preparing CSV...';
  p.context.dbcSetBusy(true);
  assert.equal(p.elements.dbcCsvStatus.textContent, 'Preparing CSV...');
});
