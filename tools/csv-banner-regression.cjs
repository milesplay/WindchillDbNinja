const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const test = require('node:test');
const vm = require('node:vm');
const {headerName, headerPage, captureState} = require('./header-ui-harness.cjs');
const root = path.resolve(__dirname, '..');
const csvName = require('../deployment/assets.json').csv;
const csvSource = fs.readFileSync(path.join(root,
  'customization/DbCapture/main/src_web/custom/DbCapture', csvName), 'utf8');

test('CSV is registered as a shell asset, not an inert external script in an Ajax-loaded JSP', () => {
  const deployment = fs.readFileSync(path.join(root, 'tools/dbninja.mjs'), 'utf8');
  const admin = fs.readFileSync(path.join(root,
    'customization/DbCapture/main/src_web/custom/DbCapture/overlay/netmarkets/jsp/dbcapture/dbCaptureAdmin.jsp'), 'utf8');
  assert.match(deployment, /netmarkets\/javascript\/util\/jsfrags/);
  assert.match(deployment, /createJsfrag/);
  assert.ok(!/<script[^>]+src="[^"]*dbCaptureCsv/.test(admin),
    'the shell owns loading; the partial page must not suggest that an inert tag initializes CSV');
});

function csvModule(legacyArrayFrom = false) {
  const downloads = [], revoked = [], timers = [], alerts = [];
  const context = {window: {alert: text => alerts.push(text)}, Blob,
    URL: {createObjectURL(blob) {downloads.push({blob}); return 'blob:test';},
      revokeObjectURL(url) {revoked.push(url);}},
    setTimeout(fn) {timers.push(fn);},
    document: {
      body: {appendChild() {}},
      createElement(tag) {
        if (tag === 'template') {
          // Text-only DOM fixture: real HTML parsing is covered by browser acceptance.
          return {content: {nodeName: '#document-fragment', childNodes: []},
            set innerHTML(value) {this.content.childNodes = [{nodeType: 3, nodeValue: value}];}};
        }
        return {style: {}, click() {downloads.at(-1).name = this.download;}, remove() {}};
      }
    }
  };
  vm.runInNewContext((legacyArrayFrom ? 'Array.from = value => String(value).split("");\n' : '') + csvSource, context);
  return {api: context.window.DbCaptureCsv, downloads, revoked, timers, alerts, window: context.window};
}

test('CSV contains a BOM, CRLF records, quoted commas, doubled quotes and cell line breaks', () => {
  const {api} = csvModule();
  assert.equal(api.serialize(['Table', 'Value'], [['WTPART', 'A,"B"\nC']]),
    '\ufeff"Table","Value"\r\n"WTPART","A,""B""\nC"\r\n');
  assert.throws(() => api.serialize(['x'], [['x', 'y']]), /counts differ/);
});

test('spreadsheet formula prefixes are neutralized even after whitespace', () => {
  const {api} = csvModule();
  for (const value of ['=1+1', '+SUM(A1)', '-2+3', '@SUM(A1)', '  =1+1', '\t=1', '\rhello', '\nhello']) {
    assert.ok(api.csvCell(value).startsWith('"\''));
  }
  assert.equal(api.csvCell('123'), '"123"');
  assert.equal(api.csvCell('A=B'), '"A=B"');
  assert.equal(api.csvCell(null), '""');
});

test('filenames use saved Description and only empty descriptions fall back to Capture ID', () => {
  const {api} = csvModule();
  assert.equal(api.filename({'CAP-000001': 'Part rename'}, ['CAP-000001']), 'Part rename.csv');
  for (const description of ['', '  ', null]) {
    assert.equal(api.filename({'CAP-000001': description}, ['CAP-000001']), 'CAP-000001.csv');
  }
  assert.throws(() => api.filename({}, ['CAP-000001']), /missing/);
});

test('filenames remove forbidden characters, trailing dots, paths and Windows reserved names', () => {
  const {api} = csvModule();
  const make = value => api.filename({'CAP-000001': value}, ['CAP-000001']);
  const value = make('../a/b\\c:<d>"?*|\u0001..  ');
  assert.doesNotMatch(value, /[<>:"/\\|?*\u0000-\u001f]/);
  assert.ok(value.endsWith('.csv'));
  for (const reserved of ['CON', 'nul.txt', 'COM1', 'LPT9.csv', 'AUX']) {
    assert.ok(make(reserved).startsWith('_'));
  }
  assert.equal(make('...'), 'CAP-000001.csv');
});

test('filename maximum is 180 UTF-8 bytes including extension without split surrogate pairs', () => {
  const {api} = csvModule();
  for (const text of ['a'.repeat(400), '\u65e5\u672c\u8a9e'.repeat(140), '\u{1f600}'.repeat(300)]) {
    const name = api.filename({'CAP-000001': text}, ['CAP-000001']);
    assert.ok(Buffer.byteLength(name, 'utf8') <= 180);
    assert.ok(name.endsWith('.csv'));
    assert.doesNotMatch(name, /[\ud800-\udfff]$/u);
  }
});

test('filenames retain Unicode code points with Windchill Prototype overriding Array.from', () => {
  for (const legacy of [false, true]) {
    const {api} = csvModule(legacy);
    assert.equal(api.filename({'CAP-1': 'A\u{1f600}B'}, ['CAP-1']), 'A\u{1f600}B.csv');
    assert.equal(api.filename({'CAP-1': '\u{1f600}'.repeat(100)}, ['CAP-1']),
      '\u{1f600}'.repeat(44) + '.csv');
    assert.equal(api.filename({'CAP-1': '\ud800A\udc00'}, ['CAP-1']), '_A_.csv');
  }
});

test('multi-capture filenames combine only the visible captures in displayed order', () => {
  const {api} = csvModule();
  assert.equal(api.filename({'CAP-000001': 'First', 'CAP-000002': null, 'CAP-000003': 'Not displayed'},
    ['CAP-000002', 'CAP-000001']), 'CAP-000002__First.csv');
});

function gridFixture() {
  const record = data => ({get: key => data[key]});
  const rows = [record({captureId: 'CAP-000002', tableName: 'WTPARTMASTER', count: 2}),
    record({captureId: 'CAP-000001', tableName: 'WTPART', count: 1})];
  const columns = [
    {field: 'tableName', name: 'Table'},
    {field: 'count', name: 'Rows', renderer: value => String(value)},
    {field: 'captureId', name: 'Capture ID', hidden: true},
    {field: 'nmActions', name: 'Actions'}
  ];
  const store = {getRange: () => rows, load_complete: true,
    allData: [record({captureId: 'CAP-000099', tableName: 'NOT_VISIBLE'})],
    snapshot: [record({captureId: 'CAP-000098', tableName: 'ALSO_NOT_VISIBLE'})]};
  const model = {config: columns, getColumnCount: () => columns.length,
    getDataIndex: i => columns[i].field, isHidden: i => !!columns[i].hidden,
    getColumnHeader: i => columns[i].name, getRenderer: i => columns[i].renderer};
  return {store, model, grid: {getStore: () => store, getColumnModel: () => model}};
}

test('CSV exports current store range/order and visible column order, never allData or snapshot', () => {
  const {api} = csvModule(), {grid} = gridFixture();
  const snapshot = JSON.parse(JSON.stringify(api.snapshotGrid(grid)));
  assert.deepEqual(snapshot, {headers: ['Table', 'Rows'],
    rows: [['WTPARTMASTER', '2'], ['WTPART', '1']], captureIds: ['CAP-000002', 'CAP-000001']});
});

test('CSV refuses partial/loading stores, empty displays, missing columns and invalid capture IDs', () => {
  const {api} = csvModule();
  for (const change of [
    fixture => {fixture.store.load_complete = false;},
    fixture => {fixture.store.loading = true;},
    fixture => {fixture.store.isLoading = () => true;},
    fixture => {fixture.store.getRange = () => [];},
    fixture => {fixture.model.isHidden = () => true;},
    fixture => {fixture.store.getRange = () => [{get: () => 'not-a-capture'}];}
  ]) {
    const fixture = gridFixture();
    change(fixture);
    assert.throws(() => api.snapshotGrid(fixture.grid));
  }
});

test('CSV download uses the captured visible-row snapshot and releases its Blob URL', async () => {
  const {api, downloads, revoked, timers} = csvModule();
  const snapshot = {headers: ['Table'], rows: [['WTPART']], captureIds: ['CAP-000001']};
  assert.equal(api.download(snapshot, {'CAP-000001': 'Rename'}), 'Rename.csv');
  assert.equal(downloads[0].name, 'Rename.csv');
  assert.equal(downloads[0].blob.type, 'text/csv;charset=utf-8');
  assert.equal(await downloads[0].blob.text(), '"Table"\r\n"WTPART"\r\n');
  assert.equal(revoked.length, 0);
  timers[0]();
  assert.deepEqual(revoked, ['blob:test']);
});

test('pirate banner is an in-header red/white design with real running status retained accessibly', () => {
  const p = headerPage();
  assert.ok(p.headerElement.children.includes(p.banner()));
  assert.equal(p.banner().dbcHeadline.textContent, '\u{1f977}DB IS BEING H\u2694JACKED\uff01');
  assert.ok(p.banner().textContent.includes('T\u30fbH\u30fbE'));
  assert.match(p.banner().dbcDetail.textContent, /CAP-000123.*running/);
  assert.equal(p.banner().style.position, undefined);
  const css = fs.readFileSync(path.join(root,
    'customization/DbCapture/main/src_web/custom/DbCapture/dbCapture-v2026092003.css'), 'utf8');
  assert.match(css, /\.dbcCaptureGlobalBanner\s*\{[^}]*position: absolute/);
  assert.doesNotMatch(css, /position:\s*fixed/);
  assert.match(css, /max-width: 100%/);
  assert.equal(p.banner().dbcArt.children.length, 3);
  assert.deepEqual(p.banner().dbcArt.children.map(line => line.textContent),
    ['T・H・E', '🥷DB IS BEING H⚔JACKED！', 'ﾃﾞｰﾋﾞｰｷｬﾌﾟﾁｬｰ']);
});

function assertOriginalHeader(p, original = 43) {
  assert.equal(p.headerElement.getBoundingClientRect().height, original);
  assert.deepEqual(p.headerStyleWrites, [], 'the header style must never be mutated');
  assert.deepEqual(p.heightCalls, [], 'Panel.setHeight must never be called');
  assert.equal(p.layouts, 0, 'the header parent must not be relaid out to fit the banner');
}

function assertNoOverlap(p) {
  const banner = p.banner().getBoundingClientRect(), header = p.headerElement.getBoundingClientRect();
  assert.ok(banner.left >= header.left && banner.right <= header.right);
  assert.ok(banner.top >= header.top && banner.bottom <= header.bottom);
  for (const obstacle of p.obstacles) {
    const rect = obstacle.getBoundingClientRect();
    if (obstacle.style.visibility === 'hidden' || rect.top >= header.bottom || rect.bottom <= header.top) continue;
    assert.ok(!(rect.left < banner.right && rect.right > banner.left
      && rect.top < banner.bottom && rect.bottom > banner.top),
    `banner overlaps ${obstacle.id}, including controls outside the header subtree`);
  }
}

test('original header height is unchanged through idle, pending start, running, pending stop and completion', () => {
  const p = headerPage(false);
  const artwork = '\u{1f977}DB IS BEING H\u2694JACKED\uff01';
  assertOriginalHeader(p);
  p.start();
  assert.equal(p.banner().dbcHeadline.textContent, artwork);
  assert.match(p.banner().title, /starting database capture/);
  assertOriginalHeader(p);
  assertNoOverlap(p);
  p.requests.at(-1).success({responseText: JSON.stringify(captureState(true))});
  assert.equal(p.banner().dbcHeadline.textContent, artwork);
  assertOriginalHeader(p);
  assertNoOverlap(p);
  p.stop();
  assert.equal(p.banner().dbcHeadline.textContent, artwork);
  assert.match(p.banner().title, /collecting results/);
  assertOriginalHeader(p);
  assertNoOverlap(p);
  p.requests.at(-1).success({responseText: JSON.stringify({...captureState(false),
    completedCaptureId: 'CAP-000123', resultsUrl: 'app/#ptc1/dbcapture/dbCaptureAdmin?dbcCompletedCapture=CAP-000123'})});
  assertOriginalHeader(p);
  assert.equal(p.banner().style.display, 'none');
});

test('long owner text stays in the accessible detail and tooltip, not the bounded headline', () => {
  const p = headerPage(true, false);
  p.poll();
  p.requests.at(-1).success({responseText: JSON.stringify(captureState(true,
    {ownedByCurrentUser: false, startedBy: 'owner'.repeat(400)}))});
  assert.ok(p.banner().title.includes('owner'.repeat(400)));
  assert.ok(p.banner().dbcHeadline.textContent.length < 40);
});

test('an existing header minimum height is preserved without any panel or parent layout mutation', () => {
  const p = headerPage(true, true, true, {fixedHeader: true, minimumHeight: '50px'});
  assertOriginalHeader(p, 50);
  assertNoOverlap(p);
  assert.equal(p.headerElement.style.minHeight, '50px');
  p.poll();
  p.requests.at(-1).success({responseText: JSON.stringify(captureState(true))});
  assertOriginalHeader(p, 50);
  p.poll();
  p.requests.at(-1).success({responseText: JSON.stringify(captureState(false))});
  assertOriginalHeader(p, 50);
  assert.equal(p.headerElement.style.minHeight, '50px');
});

test('wide banner is centered in the original header and accounts for the ActiveMS sibling H1', () => {
  const p = headerPage(true, true, true, {width: 1920});
  assert.equal(p.headerElement.contains(p.marker), false, 'ActiveMS is not a header descendant');
  assert.equal(p.banner().getAttribute('data-dbc-layout'), 'full');
  assertOriginalHeader(p);
  assertNoOverlap(p);
  const rect = p.banner().getBoundingClientRect();
  assert.equal((rect.left + rect.right) / 2, 960);
  assert.equal(rect.top, 3.5);
  assert.equal(rect.bottom, 39.5);
});

test('normal widths preserve all three lines by shrinking only the banner art within the safe gap', () => {
  for (const width of [1112, 1024]) {
    const p = headerPage(true, true, true, {width});
    assertOriginalHeader(p);
    assertNoOverlap(p);
    assert.equal(p.banner().getAttribute('data-dbc-layout'), 'full');
    assert.match(p.banner().dbcArt.style.transform, /scale\(0\./);
    assert.ok(p.banner().getBoundingClientRect().left >= p.marker.getBoundingClientRect().right + 6);
    assert.match(p.banner().getAttribute('aria-label'), /^T・H・E\n🥷DB IS BEING H⚔JACKED！\nﾃﾞｰﾋﾞｰｷｬﾌﾟﾁｬｰ/);
  }
});

test('narrow headers use a compact free-gap indicator without changing height or covering controls', () => {
  for (const width of [768, 640, 480]) {
    const p = headerPage(true, true, true, {width});
    assertOriginalHeader(p);
    assertNoOverlap(p);
    assert.equal(p.banner().getAttribute('data-dbc-layout'), 'compact');
    assert.match(p.banner().className, /dbcCaptureGlobalBannerCompact/);
    assert.match(p.banner().title, /^T・H・E\n🥷DB IS BEING H⚔JACKED！\nﾃﾞｰﾋﾞｰｷｬﾌﾟﾁｬｰ/);
    assert.equal(p.banner().getAttribute('aria-label'), p.banner().title);
  }
});

test('no-space headers retain an accessible status and Quick Links tooltip rather than adding a row', () => {
  const p = headerPage(true, true, true, {width: 320, fullHeaderBlocker: true,
    quickLinksTitle: 'Existing Quick Links help'});
  assertOriginalHeader(p);
  assert.equal(p.banner().getAttribute('data-dbc-layout'), 'accessible');
  assert.match(p.banner().className, /dbcCaptureGlobalBannerNoSpace/);
  assert.match(p.quickLinks.getAttribute('title'), /^Existing Quick Links help\n\nT・H・E\n🥷DB IS BEING H⚔JACKED！/);
  p.poll();
  p.requests.at(-1).success({responseText: JSON.stringify(captureState(false))});
  assertOriginalHeader(p);
  assert.equal(p.quickLinks.getAttribute('title'), 'Existing Quick Links help');
});

test('ResizeObserver repositions after deferred Ext header geometry changes, without growing the header', () => {
  const p = headerPage(true, true, true, {width: 1920});
  assert.ok(p.resizeObservers[0].elements.includes(p.marker));
  p.listeners.resize();
  p.setHeaderWidth(1024);
  p.flushResize();
  assertOriginalHeader(p);
  assertNoOverlap(p);
  assert.equal(p.banner().getAttribute('data-dbc-layout'), 'full');
  p.setHeaderWidth(768);
  p.flushResize();
  assertOriginalHeader(p);
  assertNoOverlap(p);
  assert.equal(p.banner().getAttribute('data-dbc-layout'), 'compact');
});

test('window resize remains safe when ResizeObserver is unavailable', () => {
  const p = headerPage(true, true, true, {width: 1920, noResizeObserver: true});
  p.setHeaderWidth(1112);
  p.listeners.resize();
  assertOriginalHeader(p);
  assertNoOverlap(p);
});

test('hidden or below-header sibling headings do not consume the header gap', () => {
  for (const option of [{hiddenMarker: true}, {markerTop: 60}]) {
    const p = headerPage(true, true, true, {width: 1112, ...option});
    assertOriginalHeader(p);
    assertNoOverlap(p);
    assert.ok(p.banner().getBoundingClientRect().left < 354);
  }
});

test('the live header implementation has no height, minimum-height, extra-row or parent-layout mutation', () => {
  const source = fs.readFileSync(path.join(root,
    'customization/DbCapture/main/src_web/custom/DbCapture', headerName), 'utf8');
  assert.doesNotMatch(source, /\bminHeight\b|\bbannerExtraHeight\b|\.setHeight\s*\(|\.doLayout\s*\(/);
  assert.doesNotMatch(source, /header\.style\s*\./);
});

test('CSV uses a dedicated standard table action model and OOTB export icon, not a duplicate outside button', () => {
  const files = {
    models: 'customization/DbCapture/main/src_web/config/actions/DbCapture-actionModels.xml',
    actions: 'customization/DbCapture/main/src_web/config/actions/DbCapture-actions.xml',
    builder: 'customization/DbCapture/main/src/com/ptc/dbcapture/mvc/builders/DbCaptureChangeTableBuilder.java',
    labels: 'customization/DbCapture/main/src/com/ptc/dbcapture/dbCaptureActionResource.java',
    admin: 'customization/DbCapture/main/src_web/custom/DbCapture/overlay/netmarkets/jsp/dbcapture/dbCaptureAdmin.jsp'
  };
  const data = Object.fromEntries(Object.entries(files).map(([key, file]) =>
    [key, fs.readFileSync(path.join(root, file), 'utf8')]));
  assert.ok(data.models.includes('<model name="dbcapture change table toolbar"'));
  assert.ok(data.models.includes('<action name="exportDbCaptureChangesCsv" type="dbcapture" shortcut="true"/>'));
  assert.ok(data.builder.includes('table.setActionModel("dbcapture change table toolbar")'));
  assert.ok(data.labels.includes('@RBEntry("export_list_to_csv.png")'));
  assert.ok(data.actions.includes('window.DbCaptureCsv.exportFromAction(event, target, table)'));
  assert.ok(!data.admin.includes('id="dbcDownloadCsvButton"'));
  assert.ok(!data.models.match(/<model name="dbcapture session table toolbar"[\s\S]*?<\/model>/)[0]
    .includes('exportDbCaptureChangesCsv'));
});

test('standard export action delegates the clicked component and table without a fallback navigation', () => {
  const p = csvModule();
  const event = {}, target = {}, table = {};
  let called = 0;
  p.window.dbcDownloadCsv = (e, t, grid) => {
    assert.equal(e, event); assert.equal(t, target); assert.equal(grid, table); called++;
    return false;
  };
  assert.equal(p.api.exportFromAction(event, target, table), false);
  assert.equal(called, 1);
  delete p.window.dbcDownloadCsv;
  assert.equal(p.api.exportFromAction(event, target, table), false);
  assert.equal(p.alerts.length, 1);
});

test('table and Quick Links actions fail explicitly when their controllers are missing', () => {
  const xml = fs.readFileSync(path.join(root,
    'customization/DbCapture/main/src_web/config/actions/DbCapture-actions.xml'), 'utf8');
  for (const name of ['startDbCapture', 'stopDbCapture', 'exportDbCaptureChangesCsv']) {
    const action = xml.match(new RegExp('<action name="' + name + '"[\\s\\S]*?</action>'))[0];
    const expression = action.match(/onClick="([^"]+)"/)[1];
    const alerts = [];
    assert.equal(vm.runInNewContext(expression, {window: {alert: text => alerts.push(text)}}), false);
    assert.equal(alerts.length, 1);
  }
});
