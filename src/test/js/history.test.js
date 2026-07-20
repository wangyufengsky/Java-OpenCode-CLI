'use strict';

const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');

class FakeInput {
  constructor(value = '') {
    this.value = value;
    this.min = '';
    this.max = '';
    this.listeners = new Map();
  }
  addEventListener(type, listener) {
    if (!this.listeners.has(type)) this.listeners.set(type, []);
    this.listeners.get(type).push(listener);
  }
  dispatch(type) {
    for (const listener of this.listeners.get(type) || []) listener({ target: this });
  }
}

function harness() {
  const from = new FakeInput('2026-07-01');
  const until = new FakeInput('2026-07-31');
  const query = new FakeInput('weekly');
  const page = new FakeInput('3');
  const form = {
    elements: {
      namedItem(name) { return { from, until, q: query, page }[name] || null; },
      length: 4,
      0: query,
      1: from,
      2: until,
      3: page
    }
  };
  const actionButtons = [];
  const document = {
    querySelector: (selector) => selector === '#history-filter-form' ? form : null,
    querySelectorAll: () => actionButtons
  };
  const window = { location: { href: 'http://localhost/history', assign() {}, reload() {} } };
  const context = vm.createContext({ window, document, HTMLInputElement: FakeInput, fetch: async () => ({ ok: true }) });
  const script = fs.readFileSync(path.join(__dirname, '../../main/resources/static/js/history.js'), 'utf8');
  vm.runInContext(script, context, { filename: 'history.js' });
  return { from, until, query, page };
}

test('date range constraints stay synchronized and changed filters restart at page one', () => {
  const { from, until, query, page } = harness();

  assert.equal(until.min, '2026-07-01');
  assert.equal(from.max, '2026-07-31');
  query.value = 'different-weekly';
  query.dispatch('input');
  assert.equal(page.value, '1');
  from.value = '2026-07-12';
  from.dispatch('change');
  assert.equal(until.min, '2026-07-12');
});

test('one-click rerun posts the source run and opens the new run detail', async () => {
  const button = new FakeInput();
  button.dataset = { historyAction: 'rerun', runId: '42' };
  const assigned = [];
  const requests = [];
  const form = {
    elements: {
      namedItem() { return new FakeInput(); }
    }
  };
  const document = {
    querySelector: (selector) => selector === '#history-filter-form' ? form : null,
    querySelectorAll: () => [button]
  };
  const window = {
    location: {
      href: 'http://localhost/history',
      assign(value) { assigned.push(value); },
      reload() {}
    },
    confirm: () => true
  };
  const context = vm.createContext({
    window,
    document,
    HTMLInputElement: FakeInput,
    fetch: async (url, options) => {
      requests.push([url, options]);
      return { ok: true, json: async () => ({ id: 77 }) };
    }
  });
  const script = fs.readFileSync(path.join(__dirname, '../../main/resources/static/js/history.js'), 'utf8');
  vm.runInContext(script, context, { filename: 'history.js' });

  await button.listeners.get('click')[0]({ preventDefault() {} });

  assert.equal(requests.length, 1);
  assert.equal(requests[0][0], '/api/runs/42/rerun');
  assert.equal(requests[0][1].method, 'POST');
  assert.deepEqual(assigned, ['/runs/77']);
});

test('single and bulk cleanup use delete endpoints after confirmation', async () => {
  const single = new FakeInput();
  single.textContent = '清理';
  single.dataset = { historyAction: 'delete', runId: '42' };
  const all = new FakeInput();
  all.textContent = '清理所有历史';
  all.dataset = { historyAction: 'clear-all' };
  const requests = [];
  const assigned = [];
  let reloads = 0;
  let confirmations = 0;
  const document = {
    querySelector: () => null,
    querySelectorAll: () => [single, all]
  };
  const window = {
    location: {
      assign(value) { assigned.push(value); },
      reload() { reloads += 1; }
    },
    confirm() {
      confirmations += 1;
      return true;
    }
  };
  const context = vm.createContext({
    window,
    document,
    HTMLInputElement: FakeInput,
    fetch: async (url, options) => {
      requests.push([url, options.method]);
      return { ok: true, json: async () => ({ deleted: 1 }) };
    }
  });
  const script = fs.readFileSync(path.join(__dirname, '../../main/resources/static/js/history.js'), 'utf8');
  vm.runInContext(script, context, { filename: 'history.js' });

  await single.listeners.get('click')[0]({ preventDefault() {} });
  await all.listeners.get('click')[0]({ preventDefault() {} });

  assert.deepEqual(requests, [
    ['/api/runs/42', 'DELETE'],
    ['/api/runs', 'DELETE']
  ]);
  assert.equal(confirmations, 2);
  assert.equal(reloads, 1);
  assert.deepEqual(assigned, ['/history']);
});
