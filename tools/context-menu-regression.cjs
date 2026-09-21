const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const test = require('node:test');
const vm = require('node:vm');

const root = path.resolve(__dirname, '..');
const admin = fs.readFileSync(path.join(root,
  'customization/DbCapture/main/src_web/custom/DbCapture/overlay/netmarkets/jsp/dbcapture/dbCaptureAdmin.jsp'), 'utf8');
const endpoint = fs.readFileSync(path.join(root,
  'customization/DbCapture/main/src_web/custom/DbCapture/overlay/netmarkets/jsp/dbcapture/dbCaptureState.jsp'), 'utf8');
const editor = fs.readFileSync(path.join(root,
  'customization/DbCapture/main/src_web/custom/DbCapture/overlay/netmarkets/jsp/dbcapture/editDescription.jsp'), 'utf8');

const filterStart = admin.indexOf('function dbcExactTableNames');
const filterEnd = admin.indexOf('function dbcUnfinishedWanted', filterStart);
const filterScript = admin.slice(filterStart, filterEnd);
const contextStart = admin.indexOf('function dbcInstallCapturedContextMenu');
const contextEnd = admin.indexOf('function dbcShowChangeContextMenu', contextStart);
assert.ok(contextStart >= 0 && contextEnd > contextStart);
const contextScript = admin.slice(contextStart, contextEnd);

function contextPage() {
  const menus = [];
  const statuses = [];
  const previousCalls = [];
  const windowListeners = {};
  const grids = {};
  let activeEvent;
  const previous = function (event) {
    previousCalls.push({receiver: this, event});
    return true;
  };
  const context = {
    DBC_TABLES: ['sessions', 'changes'],
    document: {oncontextmenu: previous},
    window: {addEventListener(type, handler) {windowListeners[type] = handler;}},
    dbcTable: id => grids[id],
    dbcSetStatus(message) {statuses.push(message);},
    Date: class {getTime() {return 1000;}}
  };
  vm.runInNewContext(contextScript, context);
  for (const id of context.DBC_TABLES) {
    const listeners = {};
    const dom = {
      addEventListener(type, handler, capture) {
        assert.equal(capture, true);
        listeners[type] = handler;
      },
      contains(target) {return target.gridId === id;}
    };
    const grid = grids[id] = {
      id, dom, listeners,
      getEl() {return {dom};},
      getView() {return {findRowIndex: target => target.rowIndex};}
    };
    context.dbcInstallCapturedContextMenu(grid, (g, rowIndex, xy) => {
      menus.push({grid: g.id, rowIndex, xy: Array.from(xy), eventType: activeEvent.type,
        prevented: activeEvent.defaultPrevented, stopped: activeEvent.stopped});
    });
  }
  context.dbcInstallDocumentContextSuppression();
  function dispatch(type, target, values = {}) {
    const event = {
      type, target, button: 2, which: 3, pageX: 135, pageY: 246,
      clientX: 35, clientY: 46, defaultPrevented: false, returnValue: true,
      stopped: false, immediateStopped: false,
      preventDefault() {this.defaultPrevented = true; this.returnValue = false;},
      stopPropagation() {this.stopped = true;},
      stopImmediatePropagation() {this.immediateStopped = true; this.stopped = true;},
      ...values
    };
    activeEvent = event;
    const grid = grids[target.gridId];
    if (grid && grid.listeners[type]) {
      grid.listeners[type](event);
    }
    if (type === 'contextmenu' && !event.stopped
        && context.document.oncontextmenu(event) === false) {
      event.preventDefault();
    }
    return event;
  }
  function rightClick(target) {
    const count = menus.length;
    const down = dispatch('mousedown', target);
    // A menu opened on mousedown takes the subsequent native event's target.
    const hitTarget = menus.length > count ? {menu: true} : target;
    const up = dispatch('mouseup', hitTarget);
    const menu = dispatch('contextmenu', hitTarget);
    return {down, up, menu};
  }
  return {context, grids, menus, statuses, previous, previousCalls,
    windowListeners, dispatch, rightClick};
}

function hiddenPage(initial = '') {
  const input = {value: initial};
  let reloads = 0;
  let status = null;
  const context = {
    DBC_TABLES: ['sessions', 'changes'],
    DBC_CHANGE_TABLE: 'changes', dbcActiveSearch: null, dbcPendingFilterRequest: null,
    dbcAppliedCriteria: null, dbcCaptureFilter: null,
    document: {getElementById: id => id === 'dbcHideTables' ? input : null},
    dbcHiddenTables: () => input.value, dbcCriteria: () => ({}),
    dbcSetFilterStatus(message, problem) { status = {message, problem}; },
    dbcReportFilter(done) { done({unknown: [], hidden: [], known: ['WTPART', 'WTPARTMASTER']}); },
    dbcRunSearch(tableIds) {
      assert.deepEqual(Array.from(tableIds), ['changes']);
      reloads++;
    },
    Error
  };
  vm.runInNewContext(filterScript, context);
  return {context, input, get reloads() {return reloads;}, get status() {return status;}};
}

test('right-click hide adds one normalized exact name to the internal Hidden selection', () => {
  const page = hiddenPage('wtpartmaster, WTPARTMASTER');
  page.context.dbcAddHiddenTable('wtpart');
  assert.equal(page.input.value, 'WTPARTMASTER, WTPART');
  assert.equal(page.reloads, 1);
});

test('right-click hide rejects pattern/injection text and exposes per-table removal', () => {
  const page = hiddenPage('');
  page.context.dbcAddHiddenTable('WT*');
  assert.equal(page.input.value, '');
  assert.equal(page.reloads, 0);
  assert.equal(page.status.problem, true);
  assert.match(admin, /dbcRemoveHiddenTable\(name\)/);
  assert.doesNotMatch(admin, /dbcApplyFilterButton/);
});

test('both grids install scoped context listeners and keep existing row actions', () => {
  assert.match(admin, /addEventListener\("mousedown"/);
  assert.match(admin, /event\.button !== 2 && event\.which !== 3/);
  assert.match(admin, /addEventListener\("contextmenu"/);
  assert.match(admin, /stopImmediatePropagation/);
  assert.match(admin, /dom\.oncontextmenu = function \(\) \{ return false; \}/);
  assert.match(admin, /document\.oncontextmenu = suppressBrowserMenu/);
  assert.match(admin, /ev\.returnValue = false/);
  assert.match(admin, /document\.oncontextmenu = previous/);
  assert.match(admin, /dbcInstallDocumentContextSuppression\(\)/);
  assert.match(admin, /dbcInstallCapturedContextMenu\(sessionGrid, dbcShowSessionContextMenu\)/);
  assert.match(admin, /dbcInstallCapturedContextMenu\(changeGrid/);
  assert.match(admin, /selection\.selectRow\(rowIndex, false\)/);
  assert.match(admin, /dbcShowSessionContextMenu/);
});

for (const gridId of ['sessions', 'changes']) {
  test(`${gridId}: right mousedown blocks OOTB handling without opening or focusing a menu`, () => {
    const page = contextPage();
    const event = page.dispatch('mousedown', {gridId, rowIndex: 0});
    assert.equal(event.defaultPrevented, true);
    assert.equal(event.immediateStopped, true);
    assert.equal(page.menus.length, 0);
  });

  test(`${gridId}: native right-click sequence cancels contextmenu before opening one menu`, () => {
    const page = contextPage();
    const target = {gridId, rowIndex: 2};
    const events = page.rightClick(target);
    assert.equal(events.menu.target, target);
    assert.equal(events.menu.defaultPrevented, true);
    assert.equal(events.menu.returnValue, false);
    assert.equal(events.menu.immediateStopped, true);
    assert.deepEqual(page.menus, [{
      grid: gridId, rowIndex: 2, xy: [135, 246], eventType: 'contextmenu',
      prevented: true, stopped: true
    }]);
    assert.equal(page.previousCalls.length, 0);
  });

  test(`${gridId}: header and empty space suppress the native menu without row actions`, () => {
    const page = contextPage();
    for (const rowIndex of [false, -1]) {
      const event = page.dispatch('contextmenu', {gridId, rowIndex});
      assert.equal(event.defaultPrevented, true);
      assert.equal(event.immediateStopped, true);
    }
    assert.equal(page.menus.length, 0);
  });

  test(`${gridId}: left and middle mousedown retain normal selection and link behavior`, () => {
    const page = contextPage();
    for (const button of [0, 1]) {
      const event = page.dispatch('mousedown', {gridId, rowIndex: 0},
        {button, which: button + 1});
      assert.equal(event.defaultPrevented, false);
      assert.equal(event.stopped, false);
    }
    assert.equal(page.menus.length, 0);
  });

  test(`${gridId}: contextmenu without mousedown supports successive rows without a timing gate`, () => {
    const page = contextPage();
    for (const rowIndex of [0, 1]) {
      const event = page.dispatch('contextmenu', {gridId, rowIndex}, {button: 0, which: 0});
      assert.equal(event.defaultPrevented, true);
    }
    assert.deepEqual(page.menus.map(menu => menu.rowIndex), [0, 1]);
  });
}

test('document fallback delegates outside targets to the existing handler and restores it on unload', () => {
  const page = contextPage();
  const outside = page.dispatch('contextmenu', {outside: true});
  assert.equal(outside.defaultPrevented, false);
  assert.equal(page.menus.length, 0);
  assert.equal(page.previousCalls.length, 1);
  assert.equal(page.previousCalls[0].receiver, page.context.document);
  assert.equal(page.previousCalls[0].event, outside);
  page.windowListeners.unload();
  assert.equal(page.context.document.oncontextmenu, page.previous);
});

test('unload does not replace a document handler installed by another customization', () => {
  const page = contextPage();
  const replacement = () => true;
  page.context.document.oncontextmenu = replacement;
  page.windowListeners.unload();
  assert.equal(page.context.document.oncontextmenu, replacement);
});

test('unavailable grid event support reports an explicit status', () => {
  const page = contextPage();
  page.context.dbcInstallCapturedContextMenu({}, () => assert.fail('Unexpected menu'));
  assert.deepEqual(page.statuses, ['This browser cannot install DB Capture context menus.']);
});

test('Hide tables panel uses the same collapsed Windchill fieldset as Monitoring Scope and Find', () => {
  assert.match(admin, /<s:searchFieldSet legend="Hide these tables in Database Changes"[\s\S]*collapsed="true">/);
  assert.doesNotMatch(admin, /dbcToggleTableFilter|dbcPanelToggle|dbcTableFilterBody/);
});

test('Database Changes installs its dedicated Hidden context menu', () => {
  assert.match(admin, /dbcShowChangeContextMenu\(g, rowIndex/);
  assert.match(admin, /Hide " \+ tableName \+ " in Database Changes/);
});

test('context edit/delete use server authorization and immutable OID', () => {
  assert.match(admin, /op=delete&sessionOid=/);
  assert.match(admin, /Ext\.MessageBox\.prompt\("Edit Description"/);
  assert.match(admin, /op=describe&sessionOid=/);
  assert.match(admin, /description=" \+ encodeURIComponent\(value \|\| ""\)/);
  assert.doesNotMatch(admin, /window\.open\(DBC_EDIT_URL/);
  assert.match(endpoint, /deleteCaptureByOid\(request\.getParameter\("sessionOid"\)\)/);
  assert.match(endpoint, /setDescriptionByOid/);
  assert.match(editor, /DBC_SESSION_OID/);
  assert.match(editor, /op=describe&sessionOid=/);
});
