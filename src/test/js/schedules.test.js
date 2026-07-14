'use strict';

const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');

class FakeClassList {
  constructor() { this.values = new Set(); }
  add(name) { this.values.add(name); }
  remove(name) { this.values.delete(name); }
  toggle(name, active) { active ? this.values.add(name) : this.values.delete(name); }
  contains(name) { return this.values.has(name); }
}

class FakeElement {
  constructor(id = '') {
    this.id = id;
    this.dataset = {};
    this.attributes = new Map();
    this.listeners = new Map();
    this.children = [];
    this.classList = new FakeClassList();
    this.hidden = false;
    this.disabled = false;
    this.required = false;
    this.checked = false;
    this.value = '';
    this.textContent = '';
    this.ownerDocument = null;
  }
  addEventListener(type, listener) {
    if (!this.listeners.has(type)) this.listeners.set(type, []);
    this.listeners.get(type).push(listener);
  }
  dispatch(type, init = {}) {
    const event = { type, target: this, currentTarget: this, preventDefault() { this.defaultPrevented = true; }, ...init };
    for (const listener of this.listeners.get(type) || []) listener(event);
    return event;
  }
  setAttribute(name, value) { this.attributes.set(name, String(value)); }
  getAttribute(name) { return this.attributes.get(name) || null; }
  removeAttribute(name) { this.attributes.delete(name); }
  append(...children) { this.children.push(...children); }
  appendChild(child) { this.children.push(child); return child; }
  replaceChildren(...children) { this.children = children; }
  querySelector(selector) {
    if (selector === '.schedule-switch-label') return this.label || null;
    if (selector.includes('select') || selector.includes('[href]')) return this.focusables?.[0] || null;
    return null;
  }
  querySelectorAll(selector) {
    if (selector === 'tr[data-schedule-id]') return this.rows || [];
    if (selector === '[data-config-key]') return this.children.flatMap((child) => child.children || []).filter((child) => child.dataset?.configKey);
    if (selector.includes('[href]') || selector.includes('button')) return this.focusables || [];
    return [];
  }
  focus() { if (this.ownerDocument) this.ownerDocument.activeElement = this; }
  checkValidity() { return true; }
  reportValidity() {}
}

function deferred() {
  let resolve;
  const promise = new Promise((done) => { resolve = done; });
  return { promise, resolve };
}

function response(body, ok = true) {
  return { ok, json: async () => body };
}

function controllerHarness(fetchImplementation, options = {}) {
  const selectors = [
    '#schedule-page', '#schedule-list', '#schedule-search', '#schedule-empty', '#schedule-no-results',
    '#schedule-page-message', '#schedule-status-filter', '#schedule-drawer', '#schedule-drawer-backdrop', '#schedule-form',
    '#schedule-drawer-title', '#schedule-drawer-description', '#schedule-form-error', '#schedule-result',
    '#save-schedule', '#schedule-id', '#schedule-chain-id', '#schedule-mode', '#schedule-rerun-type',
    '#schedule-rerun-id', '#schedule-run-date', '#frequency', '#dayOfWeek', '#scheduleTime', '#runAt',
    '#enabled', '#schedule-config-fields', '#schedule-config-description', '#create-schedule',
    '#close-schedule-drawer', '#cancel-schedule'
  ];
  const nodes = Object.fromEntries(selectors.map((selector) => [selector, new FakeElement(selector.slice(1))]));
  const document = new FakeElement('document');
  document.body = new FakeElement('body');
  document.activeElement = options.previousFocus || new FakeElement('previous-focus');
  document.querySelector = (selector) => nodes[selector] || null;
  document.querySelectorAll = () => [];
  document.createElement = (tag) => {
    const element = new FakeElement(tag);
    element.ownerDocument = document;
    return element;
  };
  Object.values(nodes).forEach((node) => { node.ownerDocument = document; });
  document.body.ownerDocument = document;
  document.activeElement.ownerDocument = document;
  nodes['#schedule-drawer'].hidden = true;
  nodes['#schedule-drawer-backdrop'].hidden = true;
  nodes['#schedule-chain-id'].value = 'alpha';
  nodes['#schedule-mode'].value = 'full';
  nodes['#schedule-status-filter'].value = 'all';
  nodes['#frequency'].value = 'daily';
  nodes['#dayOfWeek'].value = '1';
  nodes['#scheduleTime'].value = '06:00';
  nodes['#enabled'].checked = true;
  const first = nodes['#schedule-chain-id'];
  const last = nodes['#save-schedule'];
  nodes['#schedule-drawer'].focusables = [first, last];
  const window = { fetch: fetchImplementation, location: { reload() {} }, ConsoleCommon: {
    chainConfigDefinitions: options.chainConfigDefinitions || {
      alpha: { description: 'Alpha', fields: [{ key: 'value', label: 'Value', description: '', type: 'text' }] },
      beta: { description: 'Beta', fields: [{ key: 'value', label: 'Value', description: '', type: 'text' }] }
    },
    rerunTypeDefinitions: {}
  } };
  const context = vm.createContext({ window, document, JSON, String, Array, Set, Map, Error, Promise, AbortController, encodeURIComponent });
  const script = fs.readFileSync(path.join(__dirname, '../../main/resources/static/js/schedules.js'), 'utf8');
  vm.runInContext(script, context, { filename: 'schedules.js' });
  return { api: window.AgentBridgeSchedules, controller: window.AgentBridgeSchedules.controller, nodes, document };
}

async function flush() {
  await new Promise((resolve) => setImmediate(resolve));
  await new Promise((resolve) => setImmediate(resolve));
}

class FakeButton {
  constructor(enabled, labelBase = '代码贡献报告 #7') {
    this.dataset = { enabled: String(enabled), labelBase };
    this.attributes = new Map([
      ['aria-checked', String(enabled)],
      ['aria-label', `${labelBase}，当前${enabled ? '已启用，点击停用' : '已停用，点击启用'}`]
    ]);
    this.label = { textContent: enabled ? '已启用' : '已停用' };
    this.classList = new FakeClassList();
    this.classList.toggle('is-enabled', enabled);
    this.disabled = false;
  }
  getAttribute(name) { return this.attributes.get(name) || null; }
  setAttribute(name, value) { this.attributes.set(name, String(value)); }
  querySelector(selector) { return selector === '.schedule-switch-label' ? this.label : null; }
  closest(selector) {
    if (selector === '.schedule-switch') return this;
    if (selector === 'tr[data-schedule-id]') return this.row || null;
    return null;
  }
}

function loadApi() {
  const fetchCalls = [];
  const window = {
    fetch: async (...args) => {
      fetchCalls.push(args);
      return { ok: true, json: async () => ({}) };
    }
  };
  const document = { querySelector: () => null };
  const context = vm.createContext({ window, document, JSON, String, Array, Set, Error, encodeURIComponent });
  const script = fs.readFileSync(path.join(__dirname, '../../main/resources/static/js/schedules.js'), 'utf8');
  vm.runInContext(script, context, { filename: 'schedules.js' });
  return { api: window.AgentBridgeSchedules, fetchCalls };
}

test('copy creates a new draft without carrying the schedule id', () => {
  const { api } = loadApi();
  const source = { id: 7, chainId: 'demo', config: { 'project.id': 'demo' } };
  const draft = api.copyDraft(source);

  assert.equal(draft.id, null);
  assert.deepEqual(draft.config, source.config);
  draft.config['project.id'] = 'copy';
  assert.equal(source.config['project.id'], 'demo');
});

test('edit saves to the id URL while a copied draft saves to create URL', async () => {
  const { api } = loadApi();
  const calls = [];
  const fetchImplementation = async (url, options) => {
    calls.push({ url, options });
    return { ok: true, json: async () => ({ id: 7 }) };
  };

  await api.requestSave(7, { chainId: 'demo' }, fetchImplementation);
  await api.requestSave(null, { chainId: 'demo-copy' }, fetchImplementation);

  assert.deepEqual(calls.map((call) => call.url), ['/api/schedules/7', '/api/schedules']);
  assert.equal(calls[0].options.method, 'POST');
});

test('toggle failure restores enabled state, label, visual state and aria attributes', async () => {
  const { api } = loadApi();
  const button = new FakeButton(true);
  const message = { textContent: '' };
  const failedFetch = async () => ({ ok: false, json: async () => ({ error: '数据库暂不可用' }) });

  await assert.rejects(api.toggleSchedule(button, 7, message, failedFetch), /数据库暂不可用/);

  assert.equal(button.disabled, false);
  assert.equal(button.dataset.enabled, 'true');
  assert.equal(button.getAttribute('aria-checked'), 'true');
  assert.equal(button.getAttribute('aria-label'), '代码贡献报告 #7，当前已启用，点击停用');
  assert.equal(button.label.textContent, '已启用');
  assert.equal(button.classList.contains('is-enabled'), true);
  assert.equal(message.textContent, '数据库暂不可用');
});

test('search and status filters only change current row visibility and do not call the server', () => {
  const { api, fetchCalls } = loadApi();
  const rows = [
    { dataset: { search: '代码贡献报告 full 7', enabled: 'true' }, hidden: false },
    { dataset: { search: '研发周报 rerun 8', enabled: 'false' }, hidden: false }
  ];

  const visible = api.filterRows(rows, '', 'enabled');

  assert.equal(visible, 1);
  assert.equal(rows[0].hidden, false);
  assert.equal(rows[1].hidden, true);
  assert.equal(fetchCalls.length, 0);
});

test('real controller disables saving while config loads and ignores an older chain response', async () => {
  const alpha = deferred();
  const beta = deferred();
  const fetchImplementation = (url) => url.includes('/alpha/') ? alpha.promise : beta.promise;
  const { controller, nodes } = controllerHarness(fetchImplementation);

  nodes['#create-schedule'].dispatch('click');
  assert.equal(nodes['#save-schedule'].disabled, true);
  assert.equal(nodes['#schedule-form'].getAttribute('aria-busy'), 'true');
  nodes['#schedule-chain-id'].value = 'beta';
  nodes['#schedule-chain-id'].dispatch('change');
  alpha.resolve(response({ defaults: { value: 'stale-alpha' } }));
  await flush();
  assert.equal(nodes['#save-schedule'].disabled, true);
  beta.resolve(response({ defaults: { value: 'fresh-beta' } }));
  await flush();

  assert.equal(controller.getState().configReady, true);
  assert.equal(nodes['#schedule-form'].getAttribute('aria-busy'), null);
  assert.equal(nodes['#save-schedule'].disabled, false);
  assert.equal(nodes['#schedule-config-fields'].querySelectorAll('[data-config-key]')[0].value, 'fresh-beta');
});

test('toggle success updates cached record, rejects duplicate clicks, and failure keeps cached state', async () => {
  const toggleReply = deferred();
  let toggleCalls = 0;
  const record = { id: 7, chainId: 'alpha', mode: 'full', frequency: 'DAILY', runTime: '06:00:00', enabled: true, config: {} };
  const fetchImplementation = async (url) => {
    if (url === '/api/schedules') return response([record]);
    toggleCalls += 1;
    return toggleReply.promise;
  };
  const { controller, nodes } = controllerHarness(fetchImplementation);
  await controller.loadRecords();
  const button = new FakeButton(true);

  const first = controller.toggle(button, '7');
  const second = controller.toggle(button, '7');
  assert.equal(toggleCalls, 1);
  toggleReply.resolve(response({ ...record, enabled: false, updatedAt: 'new' }));
  await Promise.all([first, second]);
  assert.equal(controller.getRecords()[0].enabled, false);
  controller.open('edit', controller.getRecords()[0], button);
  assert.equal(nodes['#enabled'].checked, false);

  const failed = async () => response({ error: '启用失败' }, false);
  controller.setFetch(failed);
  await assert.rejects(controller.toggle(button, '7'), /启用失败/);
  assert.equal(controller.getRecords()[0].enabled, false);
});

test('successful toggle synchronizes its row enabled dataset and reapplies the active status filter', async () => {
  const record = { id: 7, chainId: 'alpha', mode: 'full', frequency: 'DAILY', runTime: '06:00:00', enabled: true, config: {} };
  const fetchImplementation = async () => response({ ...record, enabled: false });
  const { controller, nodes } = controllerHarness(fetchImplementation);
  const button = new FakeButton(true);
  const row = { dataset: { scheduleId: '7', search: '日报计划', enabled: 'true' }, hidden: false, querySelector: () => button };
  button.row = row;
  nodes['#schedule-list'].rows = [row];
  nodes['#schedule-status-filter'].value = 'disabled';
  nodes['#schedule-status-filter'].dispatch('change');
  assert.equal(row.hidden, true);

  await controller.toggle(button, '7');

  assert.equal(row.dataset.enabled, 'false');
  assert.equal(row.hidden, false);
});

test('failed toggle preserves its row dataset and active status-filter visibility', async () => {
  const fetchImplementation = async () => response({ error: '停用失败' }, false);
  const { controller, nodes } = controllerHarness(fetchImplementation);
  const button = new FakeButton(true);
  const row = { dataset: { scheduleId: '7', search: '日报计划', enabled: 'true' }, hidden: false, querySelector: () => button };
  button.row = row;
  nodes['#schedule-list'].rows = [row];
  nodes['#schedule-status-filter'].value = 'enabled';
  nodes['#schedule-status-filter'].dispatch('change');
  assert.equal(row.hidden, false);

  await assert.rejects(controller.toggle(button, '7'), /停用失败/);

  assert.equal(row.dataset.enabled, 'true');
  assert.equal(row.hidden, false);
});

test('delegated schedule switch click calls the enabled endpoint and updates UI plus cache', async () => {
  const calls = [];
  const record = { id: 7, chainId: 'alpha', mode: 'full', frequency: 'DAILY', runTime: '06:00:00', enabled: true, config: {} };
  const fetchImplementation = async (url, options) => {
    if (url === '/api/schedules') return response([record]);
    calls.push({ url, options });
    return response({ ...record, enabled: false, updatedAt: 'new' });
  };
  const { controller, nodes } = controllerHarness(fetchImplementation);
  await controller.loadRecords();
  const button = new FakeButton(true);
  const row = { dataset: { scheduleId: '7' }, querySelector: () => button };
  button.row = row;
  nodes['#schedule-list'].rows = [row];

  nodes['#schedule-list'].dispatch('click', { target: button });
  await flush();

  assert.equal(calls.length, 1);
  assert.equal(calls[0].url, '/api/schedules/7/enabled');
  assert.equal(calls[0].options.method, 'POST');
  assert.deepEqual(JSON.parse(calls[0].options.body), { enabled: false });
  assert.equal(button.getAttribute('aria-checked'), 'false');
  assert.equal(button.label.textContent, '已停用');
  assert.equal(controller.getRecords()[0].enabled, false);
  assert.equal(nodes['#schedule-page-message'].textContent, '计划已停用。');
});

test('submit has a hard in-flight guard and preserves loading gate in finally', async () => {
  const defaults = response({ defaults: { value: 'ready' } });
  const betaDefaults = deferred();
  const save = deferred();
  let saves = 0;
  const fetchImplementation = (url) => {
    if (url.includes('/beta/')) return betaDefaults.promise;
    if (url.includes('/defaults')) return Promise.resolve(defaults);
    saves += 1;
    return save.promise;
  };
  const { nodes } = controllerHarness(fetchImplementation);
  nodes['#create-schedule'].dispatch('click');
  await flush();
  nodes['#schedule-form'].dispatch('submit');
  nodes['#schedule-form'].dispatch('submit');
  assert.equal(saves, 1);
  nodes['#schedule-chain-id'].value = 'beta';
  nodes['#schedule-chain-id'].dispatch('change');
  save.resolve(response({ id: 1 }));
  await flush();
  assert.equal(nodes['#save-schedule'].disabled, true);
  assert.equal(nodes['#schedule-form'].getAttribute('aria-busy'), 'true');
  betaDefaults.resolve(response({ defaults: { value: 'beta' } }));
  await flush();
  assert.equal(nodes['#save-schedule'].disabled, false);
});

test('copy clears id, edit keeps id, search does not fetch, and drawer traps Tab', async () => {
  let fetchCalls = 0;
  const record = { id: 7, chainId: 'alpha', mode: 'full', frequency: 'DAILY', runTime: '06:00:00', enabled: true, config: { value: 'x' } };
  const fetchImplementation = async (url) => {
    fetchCalls += 1;
    if (url === '/api/schedules') return response([record]);
    return response({ defaults: {} });
  };
  const { controller, nodes, document } = controllerHarness(fetchImplementation);
  await controller.loadRecords();
  controller.open('edit', record, nodes['#create-schedule']);
  assert.equal(nodes['#schedule-id'].value, '7');
  controller.open('copy', record, nodes['#create-schedule']);
  assert.equal(nodes['#schedule-id'].value, '');

  const beforeSearch = fetchCalls;
  nodes['#schedule-search'].value = 'alpha';
  nodes['#schedule-search'].dispatch('input');
  assert.equal(fetchCalls, beforeSearch);

  document.activeElement = nodes['#save-schedule'];
  const tab = document.dispatch('keydown', { key: 'Tab', shiftKey: false });
  assert.equal(tab.defaultPrevented, true);
  assert.equal(document.activeElement, nodes['#schedule-chain-id']);
  controller.close();
  assert.equal(document.activeElement, nodes['#create-schedule']);
});

test('drawer closes from Escape and its overlay and restores the trigger focus', async () => {
  const fetchImplementation = async () => response({ defaults: {} });
  const { controller, nodes, document } = controllerHarness(fetchImplementation);
  const trigger = nodes['#create-schedule'];

  controller.open('create', null, trigger);
  document.dispatch('keydown', { key: 'Escape' });
  assert.equal(nodes['#schedule-drawer'].hidden, true);
  assert.equal(nodes['#schedule-drawer-backdrop'].hidden, true);
  assert.equal(document.activeElement, trigger);

  controller.open('create', null, trigger);
  nodes['#schedule-drawer-backdrop'].dispatch('click');
  assert.equal(nodes['#schedule-drawer'].hidden, true);
  assert.equal(document.activeElement, trigger);
});

test('controller submits edits to the id URL and copied drafts to the create URL', async () => {
  const urls = [];
  const record = { id: 7, chainId: 'alpha', mode: 'full', frequency: 'DAILY', runTime: '06:00:00', enabled: true, config: { value: 'x' } };
  const fetchImplementation = async (url) => {
    urls.push(url);
    return response({ id: 7, ...record });
  };
  const { controller, nodes } = controllerHarness(fetchImplementation);

  controller.open('edit', record, nodes['#create-schedule']);
  nodes['#schedule-form'].dispatch('submit');
  await flush();
  controller.open('copy', record, nodes['#create-schedule']);
  nodes['#schedule-form'].dispatch('submit');
  await flush();

  assert.deepEqual(urls, ['/api/schedules/7', '/api/schedules']);
});
