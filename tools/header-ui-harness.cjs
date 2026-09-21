const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');

const root = path.resolve(__dirname, '..');
const headerName = require('../deployment/assets.json').header;
const header = fs.readFileSync(path.join(root,
  'customization/DbCapture/main/src_web/custom/DbCapture', headerName), 'utf8');

function captureState(running, extra = {}) {
  return {
    ok: true, stateKnown: true, running,
    captureId: running ? 'CAP-000123' : null,
    startedBy: running ? 'alice' : null, startedAtMillis: running ? 1000 : 0,
    administrator: true, ownedByCurrentUser: running,
    canStart: !running, canStop: running, ...extra
  };
}

function headerPage(running = true, owner = true, administrator = true, options = {}) {
  const buttons = new Map(), dom = new Map();
  const requests = [], alerts = [], navigations = [], timers = [], prompts = [], confirmations = [];
  const listeners = {};
  const resizeObservers = [], headerStyleWrites = [], heightCalls = [];
  const geometry = {width: options.width || 1280};
  let ready, hides = 0;
  const promptInput = {maxLength: -1, attributes: {}, setAttribute(k, v) {this.attributes[k] = v;}};
  const quickMenu = {id: 'quickLinksMenu', loaded: true, loadOnce: true,
    isVisible: () => false, hide() {}};
  function rectangle(left, top, right, bottom) {
    return {left, top, right, bottom, x: left, y: top, width: right - left, height: bottom - top};
  }
  function matches(element, selector) {
    return selector.split(',').some(part => {
      const value = part.trim();
      if (value[0] === '.') return element.className.split(/\s+/).includes(value.slice(1));
      if (value === '[role=button]') return element.getAttribute('role') === 'button';
      return element.tagName === value.toUpperCase();
    });
  }
  function descendants(element, selector) {
    return element.children.flatMap(child =>
      (matches(child, selector) ? [child] : []).concat(descendants(child, selector)));
  }
  function node(tag = 'div', id = '') {
    let text = '';
    return {
      id, tagName: tag.toUpperCase(), className: '', style: {}, children: [], height: 43,
      attributes: {}, parentElement: null,
      get textContent() {return text + this.children.map(child => child.textContent).join('');},
      set textContent(value) {text = String(value); this.children = [];},
      appendChild(element) {
        this.children.push(element);
        element.parentElement = this;
        if (element.id) dom.set(element.id, element);
        return element;
      },
      setAttribute(name, value) {this.attributes[name] = String(value); this[name] = String(value);},
      getAttribute(name) {return Object.hasOwn(this.attributes, name) ? this.attributes[name] : null;},
      removeAttribute(name) {delete this.attributes[name]; delete this[name];},
      contains(element) {
        for (let current = element; current; current = current.parentElement) {
          if (current === this) return true;
        }
        return false;
      },
      querySelectorAll(selector) {return descendants(this, selector);},
      querySelector(selector) {return descendants(this, selector)[0] || null;},
      get offsetWidth() {return this.className === 'dbcBannerArt' ? options.artWidth || 272
        : this.getBoundingClientRect().width;},
      get offsetHeight() {return this.className === 'dbcBannerArt' ? 32 : this.getBoundingClientRect().height;},
      getBoundingClientRect() {
        if (this.rect) return this.rect();
        const left = parseFloat(this.style.left) || 0, top = parseFloat(this.style.top) || 0;
        return rectangle(left, top, left + (parseFloat(this.style.width) || 0),
          top + (parseFloat(this.style.height) || 0));
      }
    };
  }
  const body = node('body'), headerElement = node('div', 'header');
  body.appendChild(headerElement);
  headerElement.style = new Proxy({minHeight: options.minimumHeight || ''}, {
    set(target, key, value) {headerStyleWrites.push({key, value}); target[key] = value; return true;}
  });
  headerElement.rect = () => rectangle(0, 0, geometry.width,
    Math.max(headerElement.height, parseFloat(headerElement.style.minHeight) || 0));
  dom.set('header', headerElement);
  function obstacle(tag, id, rect, parent = headerElement, className = '') {
    const element = node(tag, id);
    element.rect = rect;
    element.className = className;
    parent.appendChild(element);
    return element;
  }
  const logo = obstacle('div', 'logoNav', () => rectangle(2, 1, 159, 44));
  const user = obstacle('span', 'globalUser', () => rectangle(161, 13, 208.5, 29));
  const marker = obstacle('h1', 'activeMSMarker',
    () => rectangle(200, options.markerTop ?? 15, options.markerRight || 354.2,
      (options.markerTop ?? 15) + 14), body);
  marker.textContent = '123456@windchill.example.test';
  if (options.hiddenMarker) marker.style.visibility = 'hidden';
  const typeGroup = obstacle('div', 'typeChooserTrigger',
    () => rectangle((options.controlLeft ?? geometry.width - 483) - 5, 0,
      (options.controlLeft ?? geometry.width - 483) + 181, 40));
  const typeControl = obstacle('div', 'typeControl',
    () => rectangle(options.controlLeft ?? geometry.width - 483, 10,
      (options.controlLeft ?? geometry.width - 483) + 171, 29), typeGroup, 'x-form-field-wrap');
  const searchGroup = obstacle('div', 'globalSearch',
    () => rectangle(geometry.width - 302, 0, geometry.width - 92, 40));
  const searchControl = obstacle('div', 'searchControl',
    () => rectangle(geometry.width - 302, 10, geometry.width - 109, 29), searchGroup, 'x-form-field-wrap');
  const quickLinks = obstacle('table', 'quickLinksButton',
    () => rectangle(geometry.width - 92, 10, geometry.width - 10, 30), headerElement, 'x-btn');
  if (options.quickLinksTitle) quickLinks.setAttribute('title', options.quickLinksTitle);
  const obstacles = [logo, user, marker, typeControl, searchControl, quickLinks];
  if (options.fullHeaderBlocker) {
    obstacles.push(obstacle('button', 'customFullWidthControl',
      () => rectangle(0, 0, geometry.width, 43)));
  }
  let layouts = 0;
  const headerPanel = {
    setHeight(value) {heightCalls.push(value); if (!options.fixedHeader) headerElement.height = value;},
    ownerCt: {doLayout() {layouts++;}}
  };
  const document = {
    body, hidden: false,
    getElementById: id => dom.get(id) || null,
    querySelectorAll: selector => descendants(body, selector),
    createElement: tag => node(tag),
    addEventListener(type, fn) {listeners[type] = fn;}
  };
  const location = {assign: url => navigations.push(url)};
  const context = {
    window: {top: {location}, location, addEventListener(type, fn) {listeners[type] = fn;},
      getComputedStyle: element => ({display: element.style.display || 'block',
        visibility: element.style.visibility || 'visible'}),
      ResizeObserver: options.noResizeObserver ? undefined : class {
        constructor(callback) {this.callback = callback; this.elements = []; resizeObservers.push(this);}
        observe(element) {this.elements.push(element);}
        unobserve(element) {this.elements = this.elements.filter(value => value !== element);}
      },
      confirm(text) {confirmations.push(text); return options.confirmResult !== false;}},
    document,
    setInterval(fn) {timers.push(fn); return timers.length;},
    PTC: {navigation: {on(event, callback) {ready = callback;}}},
    Ext: {
      get: () => ({}),
      getCmp: id => id === 'header' ? headerPanel
        : id === 'quickLinksButton' ? {menu: quickMenu} : buttons.get(id),
      decode: JSON.parse,
      util: {Format: {htmlEncode: text => String(text).replace(/&/g, '&amp;')
        .replace(/</g, '&lt;').replace(/>/g, '&gt;').replace(/"/g, '&quot;')}},
      Button: function (config) {
        Object.assign(this, config);
        this.nativeButton = {attributes: {}, setAttribute(k, v) {this.attributes[k] = v;}};
        this.dom = {querySelector: () => this.nativeButton};
        this.setDisabled = value => {this.disabled = value;};
        this.setVisible = value => {this.visible = value;};
        this.setText = value => {this.text = value;};
        this.getEl = () => ({dom: this.dom});
        buttons.set(this.id, this);
      },
      Ajax: {request(config) {requests.push(config); return config;}},
      MessageBox: {
        alert: (title, text) => alerts.push({title, text}),
        hide: () => {hides++;},
        getDialog: () => ({getEl: () => ({dom: {querySelector: () => promptInput}})}),
        prompt(title, message, reply) {
          prompts.push({title, message, reply});
          if (!options.manualPrompt) reply('ok', 'test capture');
        }
      }
    }
  };
  vm.runInNewContext(header, context, {filename: headerName});
  ready();
  if (options.initialize !== false) {
    requests.shift().success({responseText: JSON.stringify(captureState(running, {
      administrator, ownedByCurrentUser: running && owner,
      canStart: administrator && !running, canStop: administrator && running && owner
    }))});
  }
  return {
    buttons, requests, alerts, navigations, context, timers, prompts, promptInput, quickMenu, listeners, confirmations,
    stop: () => context.window.DbCaptureHeader.stopFromAction(),
    start: () => context.window.DbCaptureHeader.startFromAction(),
    state: () => context.window.DbCaptureHeader.getState(),
    poll: () => timers[0](),
    get hides() {return hides;},
    banner: () => dom.get('dbCaptureGlobalBanner'), headerElement, marker, obstacles, quickLinks,
    headerStyleWrites, heightCalls, resizeObservers,
    setHeaderWidth(width) {geometry.width = width;},
    flushResize() {resizeObservers.forEach(observer => observer.callback());},
    get layouts() {return layouts;}
  };
}

module.exports = {headerName, headerPage, captureState};
