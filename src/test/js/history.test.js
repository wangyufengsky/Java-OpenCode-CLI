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
  const document = { querySelector: (selector) => selector === '#history-filter-form' ? form : null };
  const window = {};
  const context = vm.createContext({ window, document, HTMLInputElement: FakeInput });
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
