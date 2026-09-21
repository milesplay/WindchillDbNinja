const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const test = require('node:test');
const vm = require('node:vm');

const root = path.resolve(__dirname, '..');
const jsp = fs.readFileSync(path.join(root,
  'customization/DbCapture/main/src_web/custom/DbCapture/overlay/netmarkets/jsp/dbcapture/editDescription.jsp'), 'utf8');
const script = jsp.match(/<script type="text\/javascript">([\s\S]*?)<\/script>/)[1]
  .replace(/<%=[\s\S]*?%>/g, 'CAP-TEST');

function editor() {
  const elements = {};
  for (const id of ['dbcDescValue', 'dbcDescSave', 'dbcDescCancel', 'dbcDescStatus']) {
    elements[id] = { value: '', disabled: false, textContent: '', listeners: {},
      addEventListener(type, handler) {this.listeners[type] = handler;},
      focus() {}, select() {} };
  }
  const requests = [];
  const state = { closed: false, reloads: 0 };
  class Request {
    constructor() { requests.push(this); }
    open(method, url) { this.method = method; this.url = url; }
    setRequestHeader(name, value) { this[name] = value; }
    send(body) { this.body = body; }
    respond(status, body) {
      this.status = status;
      this.responseText = body;
      this.readyState = 4;
      this.onreadystatechange();
    }
  }
  const context = {
    document: { getElementById: id => elements[id] },
    window: {
      close() { state.closed = true; },
      opener: {
        document: {getElementById: id => id === 'dbcSearchButton' ? {} : null},
        dbCaptureSearch() { state.reloads++; }
      }
    },
    XMLHttpRequest: Request
  };
  vm.runInNewContext(script, context, { filename: 'editDescription.jsp' });
  return { context, elements, requests, state };
}

test('popup uses framework selection and no inactive OK/Apply form submit buttons', () => {
  assert.match(jsp, /commandBean\.getSelectedOidForPopup\(\)/);
  assert.match(jsp, /NmCommandBean\.getOidFromObject/);
  assert.match(jsp, /selected\.size\(\) != 1/);
  assert.ok(jsp.indexOf('getSelectedOidForPopup') < jsp.indexOf('request.getParameter("oid")'));
  assert.doesNotMatch(jsp, /include file="[^"]*endPopup\.jspf"/);
});

test('save encodes capture and description, blocks duplicate submits, and preserves search refresh', () => {
  const e = editor();
  e.elements.dbcDescValue.value = 'test & <tag> + Japanese: \u8aac\u660e';
  e.context.dbcSave();
  e.context.dbcSave();
  assert.equal(e.requests.length, 1);
  assert.equal(e.elements.dbcDescSave.disabled, true);
  const request = e.requests[0];
  assert.equal(request.method, 'POST');
  assert.equal(request.timeout, 30000);
  const params = new URLSearchParams(request.body);
  assert.equal(params.get('op'), 'describe');
  assert.equal(params.get('sessionOid'), 'CAP-TEST');
  assert.equal(params.get('captureId'), 'CAP-TEST');
  assert.equal(params.get('description'), e.elements.dbcDescValue.value);
  request.respond(200, '{"ok":true}');
  assert.equal(e.state.reloads, 1);
  assert.equal(e.state.closed, true);
});

test('server failure message is visible text and allows retry', () => {
  const e = editor();
  e.context.dbcSave();
  e.requests[0].respond(200, '{"ok":false,"message":"Invalid <capture>"}');
  assert.match(e.elements.dbcDescStatus.textContent, /Invalid <capture>/);
  assert.equal(e.elements.dbcDescSave.disabled, false);
  assert.equal(e.state.closed, false);
  e.context.dbcSave();
  assert.equal(e.requests.length, 2);
});

test('HTTP errors cannot masquerade as successful JSON saves', () => {
  const e = editor();
  e.context.dbcSave();
  e.requests[0].respond(403, '{"ok":true}');
  assert.match(e.elements.dbcDescStatus.textContent, /HTTP 403/);
  assert.equal(e.state.closed, false);
});

test('unreadable responses, network failures and timeouts are explicit', () => {
  for (const failure of ['html', 'network', 'timeout']) {
    const e = editor();
    e.context.dbcSave();
    if (failure === 'html') e.requests[0].respond(500, '<html>error</html>');
    if (failure === 'network') e.requests[0].onerror();
    if (failure === 'timeout') e.requests[0].ontimeout();
    assert.equal(e.state.closed, false);
    assert.equal(e.elements.dbcDescSave.disabled, false);
    assert.match(e.elements.dbcDescStatus.textContent, /unreadable|Could not reach|timed out/);
  }
});

test('successful persistence is not hidden if opener refresh fails', () => {
  const e = editor();
  e.context.window.opener.dbCaptureSearch = () => { throw new Error('refresh unavailable'); };
  e.context.dbcSave();
  e.requests[0].respond(200, '{"ok":true}');
  assert.match(e.elements.dbcDescStatus.textContent, /Description saved.*Could not refresh/);
  assert.equal(e.state.closed, false);
});

test('toolbar editor supports Enter and Escape without submitting its enclosing form', () => {
  const enter = editor();
  let prevented = false;
  enter.elements.dbcDescValue.listeners.keydown({keyCode: 13, preventDefault() {prevented = true;}});
  assert.equal(prevented, true);
  assert.equal(enter.requests.length, 1);
  const escape = editor();
  escape.elements.dbcDescValue.listeners.keydown({keyCode: 27, preventDefault() {}});
  assert.equal(escape.state.closed, true);
  assert.equal(escape.requests.length, 0);
});

test('toolbar editor does not silently clip an overlong programmatic value', () => {
  const e = editor();
  e.elements.dbcDescValue.value = 'x'.repeat(401);
  e.context.dbcSave();
  assert.equal(e.requests.length, 0);
  assert.match(e.elements.dbcDescStatus.textContent, /400.*Nothing was saved/);
});

test('Cancel cannot imply cancellation of a save already sent to the server', () => {
  const e = editor();
  e.context.dbcSave();
  assert.equal(e.elements.dbcDescCancel.disabled, true);
  e.context.dbcCancel();
  assert.equal(e.state.closed, false);
  assert.match(e.elements.dbcDescStatus.textContent, /in progress/);
});

test('a successful save without an active results opener remains visible until Close', () => {
  const e = editor();
  e.context.window.opener.document.getElementById = () => null;
  e.context.dbcSave();
  e.requests[0].respond(200, '{"ok":true}');
  assert.equal(e.state.closed, false);
  assert.equal(e.state.reloads, 0);
  assert.match(e.elements.dbcDescStatus.textContent, /Description saved.*Reload Capture Sessions/);
  assert.equal(e.elements.dbcDescCancel.value, 'Close');
  e.context.dbcSave();
  assert.equal(e.requests.length, 1);
});
