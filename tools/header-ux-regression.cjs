const assert = require('node:assert/strict');
const test = require('node:test');
const fs = require('node:fs');
const path = require('node:path');
const {headerPage, captureState} = require('./header-ui-harness.cjs');

test('Quick Links operations remain unavailable until an authoritative state response', () => {
  const p = headerPage(false, true, true, {initialize: false});
  assert.equal(p.state().canStart, false);
  assert.equal(p.state().canStop, false);
});

test('Start is single-flight and guards both actions while its request is pending', () => {
  const p = headerPage(false);
  p.start();
  p.start();
  assert.equal(p.requests.filter(r => r.params.op === 'start').length, 1);
  assert.equal(p.state().canStart, false);
  assert.equal(p.state().canStop, false);
});

test('opening Start again does not replace an existing unsent prompt', () => {
  const p = headerPage(false, true, true, {manualPrompt: true});
  p.start();
  p.start();
  assert.equal(p.prompts.length, 1);
  assert.equal(p.promptInput.maxLength, 400);
});

test('Start rejects an overlong value instead of relying on server truncation', () => {
  const p = headerPage(false, true, true, {manualPrompt: true});
  p.start();
  p.prompts[0].reply('ok', 'x'.repeat(401));
  assert.equal(p.requests.length, 0);
  assert.ok(p.alerts.some(x => /400/.test(x.text)) || /400/.test(p.banner().textContent));
});

test('an older state poll cannot overwrite a completed Start result', () => {
  const p = headerPage(false);
  p.poll();
  const oldPoll = p.requests[0];
  p.start();
  const start = p.requests.find(r => r.params.op === 'start');
  start.success({responseText: JSON.stringify(captureState(true, {message: 'Capture started.'}))});
  oldPoll.success({responseText: JSON.stringify(captureState(false))});
  assert.equal(p.state().canStart, false);
  assert.equal(p.state().canStop, true);
  assert.match(p.banner().textContent, /CAP-000123.*running/);
});

test('a failed status poll cannot leave apparently authoritative enabled controls', () => {
  const p = headerPage(false);
  p.poll();
  p.requests[0].failure();
  assert.equal(p.state().canStart, false);
  assert.equal(p.state().canStop, false);
  assert.match(p.banner().textContent, /unavailable|cannot.*confirm|not.*confirm/i);
});

test('unknown status keeps the Ninja artwork, last owner styling and disabled mutation guards', () => {
  const p = headerPage();
  const artwork = p.banner().dbcHeadline.textContent;
  const style = p.banner().className;
  p.poll();
  p.requests.at(-1).failure();
  assert.equal(p.banner().dbcHeadline.textContent, artwork);
  assert.equal(p.banner().className, style);
  assert.equal(p.banner().style.display, 'block');
  assert.match(p.banner().title, /state unavailable/);
  assert.match(p.banner().title, /cannot be confirmed/);
  assert.match(p.banner().title, /Last confirmed running capture: CAP-000123/);
  assert.equal(p.banner().getAttribute('aria-label'), p.banner().title);
  assert.equal(p.state().known, false);
  assert.equal(p.state().canStart, false);
  assert.equal(p.state().canStop, false);
  const requestCount = p.requests.length;
  p.start(); p.stop();
  assert.equal(p.requests.length, requestCount);
  assert.equal(p.confirmations.length, 0);
  assert.doesNotMatch(p.banner().dbcHeadline.textContent, /UNKNOWN|PREPARING|COLLECTING/);
  assert.equal(p.headerElement.getBoundingClientRect().height, 43);
});

test('an idle or initial status outage does not display capture art and reports the error once', () => {
  for (const initialize of [true, false]) {
    const p = headerPage(false, true, true, {initialize});
    if (initialize) p.poll();
    p.requests.at(-1).failure();
    assert.equal(p.banner().dbcHeadline.textContent, '\u{1f977}DB IS BEING H\u2694JACKED\uff01');
    assert.equal(p.banner().style.display, 'none');
    assert.match(p.banner().title, /cannot be confirmed/);
    assert.doesNotMatch(p.banner().title, /Last confirmed running capture/);
    assert.equal(p.state().running, false);
    assert.equal(p.state().canStart, false);
    assert.equal(p.alerts.length, 1);
    assert.match(p.alerts[0].text, /cannot be confirmed/);
    assert.match(p.quickLinks.getAttribute('title'), /state unavailable/);
    p.poll();
    p.requests.at(-1).failure();
    assert.equal(p.alerts.length, 1);
    p.listeners.resize();
    assert.match(p.quickLinks.getAttribute('title'), /state unavailable/);
    p.poll();
    p.requests.at(-1).success({responseText: JSON.stringify(captureState(false))});
    assert.equal(p.banner().style.display, 'none');
    assert.equal(p.state().canStart, true);
    assert.equal(p.quickLinks.getAttribute('title'), null);
  }
});

test('an ambiguous Ninja Trick submission retains artwork until authoritative state ends the window', () => {
  const p = headerPage(false);
  p.start();
  const artwork = p.banner().dbcHeadline.textContent;
  assert.equal(p.banner().style.display, 'block');
  p.requests.find(request => request.params.op === 'start').failure();
  assert.equal(p.banner().dbcHeadline.textContent, artwork);
  assert.equal(p.banner().style.display, 'block');
  assert.equal(p.state().known, false);
  assert.equal(p.state().canStart, false);
  assert.equal(p.state().canStop, false);
  assert.match(p.banner().title, /operation outcome is not confirmed/);
  p.requests.at(-1).success({responseText: JSON.stringify(captureState(false))});
  assert.equal(p.banner().style.display, 'none');
  assert.equal(p.state().canStart, true);
});

test('an ambiguous Stop failure disables repeat mutations until fresh status is read', () => {
  const p = headerPage();
  p.stop();
  p.requests[0].failure();
  assert.equal(p.state().canStop, false);
  const status = p.requests.find(r => !r.params.op);
  assert.ok(status, 'refresh the authoritative state after an uncertain mutation');
  status.success({responseText: JSON.stringify(captureState(true))});
  assert.equal(p.state().canStop, true);
});

test('running-state transitions invalidate cached Quick Links permissions', () => {
  const p = headerPage(false);
  p.quickMenu.loaded = true;
  p.start();
  p.requests.find(r => r.params.op === 'start')
    .success({responseText: JSON.stringify(captureState(true))});
  assert.equal(p.quickMenu.loaded, false);
});

test('Quick Links use guarded Start/Stop entry points without duplicate header buttons', () => {
  const p = headerPage(false);
  assert.equal(typeof p.context.window.DbCaptureHeader, 'object');
  assert.equal(p.context.window.DbCaptureHeader.startFromAction(), false);
  assert.equal(p.context.window.DbCaptureHeader.startFromAction(), false);
  assert.equal(p.requests.filter(r => r.params.op === 'start').length, 1);
  assert.equal(p.buttons.size, 0);
  assert.equal(p.confirmations.length, 1);
});

test('cancelling native Start confirmation opens no description prompt and sends no mutation', () => {
  const p = headerPage(false, true, true, {confirmResult: false});
  p.start();
  assert.equal(p.confirmations.length, 1);
  assert.match(p.confirmations[0], /Ninja Trick.*start recording.*\n.*database/s);
  assert.equal(p.prompts.length, 0);
  assert.equal(p.requests.length, 0);
  assert.equal(p.state().canStart, true);
});

test('cancelling native Stop confirmation retains the running capture without a request', () => {
  const p = headerPage(true, true, true, {confirmResult: false});
  p.stop();
  assert.equal(p.confirmations.length, 1);
  assert.match(p.confirmations[0], /Ninja Stealth.*stop recording/);
  assert.equal(p.requests.length, 0);
  assert.equal(p.state().running, true);
  assert.equal(p.state().canStop, true);
});

test('unavailable or unauthorized actions do not even offer a confirmation', () => {
  const p = headerPage(true, false, false);
  p.start(); p.stop();
  assert.equal(p.confirmations.length, 0);
  assert.equal(p.requests.length, 0);
  assert.equal(p.buttons.size, 0);
});

test('DB Ninja labels rename the public actions without changing their stable internal keys', () => {
  const labels = fs.readFileSync(path.join(__dirname,
    '../customization/DbCapture/main/src/com/ptc/dbcapture/dbCaptureActionResource.java'), 'utf8');
  for (const [text, key] of [
    ['DB Ninja', 'dbcapture.dbCaptureAdmin.description'],
    ['Ninja Trick', 'dbcapture.startDbCapture.description'],
    ['Ninja Stealth', 'dbcapture.stopDbCapture.description']
  ]) {
    assert.ok(labels.includes('@RBEntry("' + text + '")'));
    assert.ok(labels.includes('"' + key + '"'));
  }
  assert.ok(!labels.includes('@RBEntry("DB Capture Administration")'));
  assert.ok(!labels.includes('@RBEntry("Start DB Capture")'));
  assert.ok(!labels.includes('@RBEntry("Stop DB Capture")'));
  const start = headerPage(false);
  start.start();
  assert.equal(start.prompts[0].title, 'Ninja Trick');
  assert.match(start.confirmations[0], /^Ninja Trick: start recording database changes/);
  const stop = headerPage(true);
  stop.stop();
  assert.match(stop.confirmations[0], /^Ninja Stealth: stop recording and collect results/);
});
