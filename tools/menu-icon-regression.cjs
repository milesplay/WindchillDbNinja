const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const test = require('node:test');
const root = path.resolve(__dirname, '..');
const assets = require('../deployment/assets.json');
const source = fs.readFileSync(path.join(root,
  'customization/DbCapture/main/src_web/custom/DbCapture', assets.menuIcons), 'utf8');

function setup() {
  const listeners = {}, errors = [];
  const makeItem = (actionName, text, disabled = false, rendered = true) => {
    const updates = [];
    return {actionName, text, disabled, rendered, icon: undefined, updates,
      handler() {}, iconEl: {set(value) {updates.push(value.src);}}};
  };
  const start = makeItem('startDbCapture', 'Ninja Trick');
  const stop = makeItem('stopDbCapture', 'Ninja Stealth', true);
  const ordinary = makeItem('copy', 'Ninja Trick');
  ordinary.icon = 'netmarkets/images/copy.gif';
  const items = [start, stop, ordinary, makeItem('toString', 'Other')];
  const menu = {id: 'quickLinksMenu', items: {each(callback) {items.forEach(callback);}}};
  const button = {menu};
  const PTC = {menu: {on(event, handler) {(listeners[event] ||= []).push(handler);}}};
  const Ext = {getCmp(id) {return id === 'quickLinksButton' ? button : null;}};
  const console = {error(message) {errors.push(message);}};
  const window = {PTC, Ext, console};
  vm.runInNewContext(source, {window, PTC, Ext, console});
  return {window, PTC, Ext, button, menu, items, start, stop, ordinary, listeners, errors};
}

test('live rendered Ninja entries get real image sources without touching labels, handlers or disabled state', () => {
  const f = setup(), startHandler = f.start.handler, stopHandler = f.stop.handler;
  f.window.DbNinjaActionIcons.initialize();
  for (const item of [f.start, f.stop]) {
    const expected = `netmarkets/images/dbcapture/${assets.actionIcons[item.actionName].file}`;
    assert.equal(item.icon, expected);
    assert.deepEqual(item.updates, [expected]);
  }
  assert.equal(f.start.handler, startHandler);
  assert.equal(f.stop.handler, stopHandler);
  assert.equal(f.start.disabled, false);
  assert.equal(f.stop.disabled, true);
  assert.equal(f.start.text, 'Ninja Trick');
  assert.equal(f.ordinary.icon, 'netmarkets/images/copy.gif');
  assert.equal(f.ordinary.updates.length, 0);
  assert.equal(f.items[3].updates.length, 0);
  assert.equal(f.errors.length, 0);
});

test('PTC load/show hooks handle reloaded and cached menus without duplicate listener registration', () => {
  const f = setup();
  f.window.DbNinjaActionIcons.initialize();
  f.window.DbNinjaActionIcons.initialize();
  assert.equal(f.listeners.dynamicMenuLoad.length, 1);
  assert.equal(f.listeners.dynamicMenuShow.length, 1);
  f.start.updates.length = 0;
  f.listeners.dynamicMenuLoad[0](f.menu);
  f.listeners.dynamicMenuShow[0](f.button, f.menu);
  assert.equal(f.start.updates.length, 2);
  assert.equal(f.ordinary.updates.length, 0);
});

test('other menus and same-label OOTB actions are never decorated', () => {
  const f = setup();
  f.window.DbNinjaActionIcons.initialize();
  f.start.updates.length = 0;
  for (const menu of [{...f.menu, id: 'someOtherMenu'}, {...f.menu}]) {
    f.listeners.dynamicMenuLoad[0](menu);
    f.listeners.dynamicMenuShow[0]({}, menu);
  }
  assert.equal(f.start.updates.length, 0);
  assert.equal(f.ordinary.updates.length, 0);
});

test('an unrendered item retains the image configuration for the native renderer', () => {
  const f = setup();
  f.start.rendered = false;
  delete f.start.iconEl;
  f.window.DbNinjaActionIcons.initialize();
  assert.equal(f.start.icon, `netmarkets/images/dbcapture/${assets.actionIcons.startDbCapture.file}`);
  assert.equal(f.errors.length, 0);
});

test('missing menu APIs or image elements fail visibly without enabling an action', () => {
  const f = setup();
  delete f.stop.iconEl;
  f.window.DbNinjaActionIcons.initialize();
  assert.equal(f.errors.length, 1);
  assert.match(f.errors[0], /icon element/);
  assert.equal(f.stop.disabled, true);
  const missing = setup();
  delete missing.PTC.menu;
  missing.window.DbNinjaActionIcons.initialize();
  assert.match(missing.errors[0], /event API/);
});
