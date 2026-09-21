const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const test = require('node:test');
const vm = require('node:vm');

const root = path.resolve(__dirname, '..');
const clientRoot = path.join(root, 'customization/DbCapture/main/src_web/custom/DbCapture');
const {headerName, headerPage, captureState} = require('./header-ui-harness.cjs');
const admin = fs.readFileSync(path.join(clientRoot, 'overlay/netmarkets/jsp/dbcapture/dbCaptureAdmin.jsp'), 'utf8');
const autoSearch = admin.match(/function dbcAutoSearchCompleted\(captureId\) \{[\s\S]*?\n   \}/)[0];
const resultsUrl = 'https://example.invalid/Windchill/app/?dbcResults=CAP-000123'
  + '#ptc1/dbcapture/dbCaptureAdmin?dbcCompletedCapture=CAP-000123';

test('confirmed Stop opens the exact completed capture without a second completion confirmation', () => {
  const page = headerPage();
  page.stop();
  assert.equal(page.requests.length, 1);
  assert.equal(page.requests[0].params.op, 'stop');
  assert.equal(page.requests[0].timeout, 180000);
  page.requests[0].success({ responseText: JSON.stringify(captureState(false, {
    completedCaptureId: 'CAP-000123',
    resultsUrl, message: 'Completed with warnings.'
  })) });
  assert.deepEqual(page.navigations, [resultsUrl]);
  assert.equal(page.hides, 1);
  assert.equal(page.alerts.length, 0, 'progress is inline and completion moves directly to the page');
  assert.equal(page.confirmations.length, 1);
});

test('pending Stop guards both Quick Links actions and blocks repeated collection', () => {
  const page = headerPage();
  page.stop();
  page.stop();
  assert.equal(page.requests.length, 1);
  assert.equal(page.state().canStart, false);
  assert.equal(page.state().canStop, false);
  assert.equal(page.confirmations.length, 1);
});

test('a failed Stop stays on the page, surfaces the error, and permits retry', () => {
  const page = headerPage();
  page.stop();
  page.requests[0].success({ responseText: JSON.stringify(captureState(true, {
    ok: false, message: 'Collection failed.'
  })) });
  assert.equal(page.navigations.length, 0);
  assert.match(page.alerts.at(-1).text, /Collection failed/);
  assert.equal(page.state().canStop, true);
  page.stop();
  assert.equal(page.requests.length, 2);
});

test('network failure and malformed/null responses never navigate', () => {
  for (const body of ['<html>error</html>', 'null', '{"running":false}', null]) {
    const page = headerPage();
    page.stop();
    if (body === null) page.requests[0].failure();
    else page.requests[0].success({ responseText: body });
    assert.equal(page.navigations.length, 0);
    assert.equal(page.state().canStop, false);
    assert.match(page.alerts.at(-1).text, /unreadable|could not be reached/);
  }
});

test('a successful Stop without a valid result link is reported, not silently redirected', () => {
  for (const data of [
    { completedCaptureId: 'CAP-000123', resultsUrl: null },
    { completedCaptureId: 'invalid', resultsUrl }
  ]) {
    const page = headerPage();
    page.stop();
    page.requests[0].success({ responseText: JSON.stringify(captureState(false, data)) });
    assert.equal(page.navigations.length, 0);
    assert.match(page.alerts.at(-1).text, /capture finished.*no result link/i);
  }
});

test('navigation failure remains a visible completed-but-not-opened error', () => {
  const page = headerPage();
  page.context.window.top.location.assign = () => { throw new Error('blocked navigation'); };
  page.stop();
  page.requests[0].success({ responseText: JSON.stringify(captureState(false, {
    completedCaptureId: 'CAP-000123', resultsUrl
  })) });
  assert.match(page.alerts.at(-1).text, /capture finished.*could not be opened/i);
});

test('Start announces the capture inline without another modal or jumping to results', () => {
  const page = headerPage(false);
  page.start();
  page.requests[0].success({ responseText: JSON.stringify(captureState(true, {
    message: 'Capture started.'
  })) });
  assert.equal(page.navigations.length, 0);
  assert.match(page.banner().textContent, /Capture started/);
  assert.equal(page.alerts.length, 0);
  assert.equal(page.confirmations.length, 1);
});

test('same user in another browser may Stop, while another user cannot', () => {
  const owner = headerPage(true, true, true);
  assert.equal(owner.state().canStop, true);
  assert.match(owner.banner().textContent, /under your user account/);
  const other = headerPage(true, false, true);
  assert.equal(other.state().canStart, false);
  assert.equal(other.state().canStop, false);
  assert.match(other.banner().textContent, /started by alice.*Wait for completion/);
});

test('non-administrator sees the global warning banner but no capture buttons', () => {
  const page = headerPage(true, false, false);
  assert.equal(page.buttons.size, 0);
  assert.equal(page.state().canStart, false);
  assert.equal(page.state().canStop, false);
  assert.equal(page.banner().style.display, 'block');
});

function resultPage() {
  const elements = { dbcKeyword: { value: '' }, dbcSearchButton: {} };
  const tables = {};
  const timers = new Map();
  const searches = [];
  let status = '';
  let timerId = 0;
  const context = {
    document: { getElementById: id => elements[id] },
    DBC_TABLES: ['dbcapture.sessionTable', 'dbcapture.changeTable'],
    dbcSearchToken: 0,
    dbcCaptureFilter: null,
    dbcTable: id => tables[id],
    dbcSetStatus: value => { status = value; },
    dbcShowFilterRow() {},
    dbCaptureSearch() {
      searches.push({ keyword: elements.dbcKeyword.value, filter: context.dbcCaptureFilter });
      context.dbcSearchToken++;
    },
    setInterval(fn) { timers.set(++timerId, fn); return timerId; },
    clearInterval(id) { timers.delete(id); }
  };
  vm.runInNewContext(autoSearch, context);
  function ready(id, state = {}) {
    const store = { load_complete: true, isLoading: () => false, ...state };
    tables[id] = { getStore: () => store };
    return store;
  }
  return {
    context, elements, tables, timers, searches, ready,
    auto: id => context.dbcAutoSearchCompleted(id),
    tick: () => { for (const fn of [...timers.values()]) fn(); },
    get status() { return status; }
  };
}

test('automatic search waits for both chunked stores, then runs once for the completed ID', () => {
  const page = resultPage();
  page.auto('CAP-000123');
  page.tick();
  assert.equal(page.searches.length, 0);
  page.ready('dbcapture.sessionTable');
  const store = page.ready('dbcapture.changeTable', { load_complete: false, isLoading: () => true });
  page.tick();
  assert.equal(page.searches.length, 0);
  store.isLoading = () => false;
  page.tick();
  assert.equal(page.searches.length, 0, 'the initial chunk request must also be complete');
  store.load_complete = true;
  page.tick();
  page.tick();
  assert.deepEqual(page.searches, [{ keyword: 'CAP-000123', filter: 'CAP-000123' }]);
  assert.equal(page.timers.size, 0);
});

test('normal opening does not automatically search, and malformed IDs are refused', () => {
  const page = resultPage();
  page.auto('');
  assert.equal(page.timers.size, 0);
  page.auto('CAP-1&oid=other');
  assert.equal(page.timers.size, 0);
  assert.match(page.status, /Invalid completed capture ID/);
});

test('search reporting rechecks a transient empty store before declaring No matches', () => {
  assert.match(admin, /var emptyReportChecks = 0/);
  assert.match(admin, /criteria\.awaitingCapture \? 400 : 10/);
  assert.match(admin, /emptyReportChecks\+\+ < emptyReportLimit/);
  assert.match(admin, /setTimeout\(report, 100\)/);
  assert.ok(admin.indexOf('emptyReportChecks++ < emptyReportLimit')
    < admin.indexOf('No matches. Check the keyword'));
  assert.match(admin, /criteria\.awaitingCapture[\s\S]*still being applied/);
  assert.match(admin, /startedAt >= 120000/);
  assert.doesNotMatch(admin, /keyword === DBC_COMPLETED_CAPTURE \?/);
});

test('manual search or navigation away cancels a pending automatic search', () => {
  for (const reason of ['manual', 'navigate']) {
    const page = resultPage();
    page.auto('CAP-000123');
    if (reason === 'manual') page.context.dbcSearchToken++;
    else page.elements.dbcSearchButton = {};
    page.tick();
    assert.equal(page.timers.size, 0);
    assert.equal(page.searches.length, 0);
  }
});

test('table initialization timeout and missing controls are explicit and bounded', () => {
  const page = resultPage();
  page.auto('CAP-000123');
  for (let i = 0; i < 480; i++) page.tick();
  assert.equal(page.timers.size, 0);
  assert.match(page.status, /Press Search to retry/);
  delete page.elements.dbcKeyword;
  page.auto('CAP-000123');
  assert.match(page.status, /search controls are not ready/);
});

test('rendered admin JavaScript parses and the auto-search is wired to validated server state', () => {
  for (const match of admin.matchAll(/<script type="text\/javascript">([\s\S]*?)<\/script>/g)) {
    new vm.Script(match[1].replace(/<%=[\s\S]*?%>/g, 'CAP-000123'));
  }
  assert.match(admin, /dbcAutoSearchCompleted\(DBC_COMPLETED_CAPTURE\)/);
  assert.match(admin, /isVisible\(completed, false\)/);
  assert.match(admin, /encodeForHTMLContent\(dbcCompletionMessage\)/);
});

test('jsfrag input uses the canonical versioned header without query-string filenames', () => {
  const assets = fs.readFileSync(path.join(root, 'deployment/assets.json'), 'utf8');
  assert.ok(assets.includes(headerName));
  assert.match(headerName, /^dbCaptureHeader-v[0-9]+\.js$/);
  assert.ok(fs.existsSync(path.join(clientRoot, headerName)));
  assert.doesNotMatch(assets, /"dbCaptureHeader\.js"/);
  assert.doesNotMatch(assets, /dbCaptureHeader[^"]*\?/);
});
