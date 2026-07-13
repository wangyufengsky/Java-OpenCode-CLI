'use strict';

const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');

class FakeClassList {
  constructor() { this.values = new Set(); }
  toggle(name, active) { active ? this.values.add(name) : this.values.delete(name); }
  contains(name) { return this.values.has(name); }
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

test('search only changes current row visibility and does not call the server', () => {
  const { api, fetchCalls } = loadApi();
  const rows = [
    { dataset: { search: '代码贡献报告 full 7' }, hidden: false },
    { dataset: { search: '研发周报 rerun 8' }, hidden: false }
  ];

  const visible = api.filterRows(rows, '周报');

  assert.equal(visible, 1);
  assert.equal(rows[0].hidden, true);
  assert.equal(rows[1].hidden, false);
  assert.equal(fetchCalls.length, 0);
});
