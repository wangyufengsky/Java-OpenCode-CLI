'use strict';

const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');

class FakeElement {
  constructor(tagName = 'div', id = '') {
    this.tagName = tagName.toUpperCase();
    this.id = id;
    this.parent = null;
    this.children = [];
    this.listeners = new Map();
    this.attributes = new Map();
    this.dataset = {};
    this.value = '';
    this.textContent = '';
    this.className = '';
    this.disabled = false;
    this.required = false;
    this.checked = false;
    this.validationMessage = '';
  }

  append(...children) {
    children.forEach((child) => this.appendChild(child));
  }

  appendChild(child) {
    if (child && typeof child === 'object') child.parent = this;
    this.children.push(child);
    return child;
  }

  replaceChildren(...children) {
    this.children.forEach((child) => {
      if (child && typeof child === 'object') child.parent = null;
    });
    this.children = [];
    this.append(...children);
  }

  addEventListener(type, listener) {
    if (!this.listeners.has(type)) this.listeners.set(type, []);
    this.listeners.get(type).push(listener);
  }

  dispatch(type, init = {}) {
    const event = {
      type,
      target: this,
      currentTarget: this,
      defaultPrevented: false,
      preventDefault() { this.defaultPrevented = true; },
      ...init
    };
    return (this.listeners.get(type) || []).map((listener) => listener(event));
  }

  setAttribute(name, value) {
    this.attributes.set(name, String(value));
  }

  removeAttribute(name) {
    this.attributes.delete(name);
  }

  querySelector(selector) {
    return this.querySelectorAll(selector)[0] || null;
  }

  querySelectorAll(selector) {
    const matches = [];
    const visit = (node) => {
      node.children.forEach((child) => {
        if (!child || typeof child !== 'object') return;
        if (matchesSelector(child, selector)) matches.push(child);
        visit(child);
      });
    };
    visit(this);
    return matches;
  }

  setCustomValidity(message) {
    this.validationMessage = message;
  }

  checkValidity() {
    return this.querySelectorAll('[data-config-key]').every((control) => (
      control.disabled || (!control.validationMessage && (!control.required || String(control.value).trim() !== ''))
    ));
  }

  reportValidity() {}

  focus() {}
}

function matchesSelector(element, selector) {
  if (selector.startsWith('#')) return element.id === selector.slice(1);
  if (selector === ':invalid') {
    return !element.disabled && Boolean(element.validationMessage || (element.required && !String(element.value).trim()));
  }
  const dataMatch = selector.match(/^\[data-([\w-]+)(?:="([^"]*)")?\]$/);
  if (!dataMatch) return false;
  const key = dataMatch[1].replace(/-([a-z])/g, (_all, letter) => letter.toUpperCase());
  if (!Object.prototype.hasOwnProperty.call(element.dataset, key)) return false;
  return dataMatch[2] === undefined || element.dataset[key] === dataMatch[2];
}

class FakeDocument extends FakeElement {
  constructor() {
    super('document');
  }

  createElement(tagName) {
    return new FakeElement(tagName);
  }

  createTextNode(text) {
    const node = new FakeElement('#text');
    node.textContent = text;
    return node;
  }
}

class FakeEvent {
  constructor(type) {
    this.type = type;
  }
}

function deferred() {
  let resolve;
  let reject;
  const promise = new Promise((resolvePromise, rejectPromise) => {
    resolve = resolvePromise;
    reject = rejectPromise;
  });
  return { promise, resolve, reject };
}

function response(body, ok = true) {
  return { ok, json: async () => body };
}

function add(document, parent, tagName, id, value = '') {
  const element = new FakeElement(tagName, id);
  element.value = value;
  parent.appendChild(element);
  return element;
}

function createHarness(fetchImplementation) {
  const document = new FakeDocument();
  const form = add(document, document, 'form', 'run-form');
  form.dataset.selectedChain = 'alpha';
  form.dataset.selectedMode = 'full';
  form.dataset.selectedRunDate = '';
  form.dataset.selectedRerunType = '';
  form.dataset.selectedRerunId = '';
  form.dataset.copyFrom = '';

  const nodes = {
    form,
    chain: add(document, form, 'select', 'chainId', 'alpha'),
    mode: add(document, form, 'select', 'mode', 'full'),
    rerunType: add(document, form, 'select', 'rerunType'),
    rerunId: add(document, form, 'input', 'rerunId'),
    runDate: add(document, form, 'input', 'runDate'),
    configFields: add(document, form, 'div', 'chain-config-fields'),
    configDescription: add(document, form, 'p', 'chain-config-description'),
    submit: add(document, form, 'button', 'submit-run'),
    submitResult: add(document, form, 'p', 'submit-result'),
    copyStatus: add(document, form, 'p', 'copy-load-status'),
    summaryChain: add(document, form, 'span', 'summary-chain'),
    summaryMode: add(document, form, 'span', 'summary-mode'),
    summaryDate: add(document, form, 'span', 'summary-date'),
    summaryProject: add(document, form, 'span', 'summary-project')
  };

  const field = {
    key: 'project.name',
    label: '项目名称',
    description: '当前项目名称。',
    type: 'text',
    group: 'project',
    required: true,
    summary: true
  };
  const definitions = {
    alpha: { label: 'Alpha', description: 'Alpha workflow', fields: [field] },
    beta: { label: 'Beta', description: 'Beta workflow', fields: [field] }
  };
  const location = { href: 'http://localhost/runs/new' };
  const history = { replaceState() {} };
  const window = {
    ConsoleCommon: {
      chainConfigDefinitions: definitions,
      groupDefinitions: [{ group: 'project', label: '项目', description: '项目字段' }],
      rerunTypeDefinitions: { alpha: [], beta: [] }
    },
    location,
    history,
    setTimeout,
    clearTimeout
  };
  const context = vm.createContext({
    window,
    document,
    fetch: fetchImplementation,
    history,
    URL,
    Event: FakeEvent,
    AbortController,
    encodeURIComponent,
    setTimeout,
    clearTimeout
  });
  const script = fs.readFileSync(path.join(__dirname, '../../main/resources/static/js/run-form.js'), 'utf8');
  vm.runInContext(script, context, { filename: 'run-form.js' });
  return { nodes, location };
}

async function flush() {
  await new Promise((resolve) => setImmediate(resolve));
  await new Promise((resolve) => setImmediate(resolve));
}

function requestHarness(options = {}) {
  const submitReply = deferred();
  const betaReply = options.betaReply || null;
  let postCalls = 0;
  const fetchImplementation = (url, requestOptions = {}) => {
    if (url === '/api/runs' && requestOptions.method === 'POST') {
      postCalls += 1;
      return submitReply.promise;
    }
    if (url.includes('/api/chains/beta/defaults') && betaReply) return betaReply.promise;
    if (url.includes('/api/chains/')) return Promise.resolve(response({ defaults: { 'project.name': 'Demo' } }));
    throw new Error(`Unexpected request: ${url}`);
  };
  const harness = createHarness(fetchImplementation);
  return { ...harness, submitReply, betaReply, getPostCalls: () => postCalls };
}

async function initialize(harness) {
  await flush();
  assert.equal(harness.nodes.submit.disabled, false);
}

async function changeToBeta(harness) {
  harness.nodes.chain.value = 'beta';
  const [change] = harness.nodes.chain.dispatch('change');
  await change;
}

test('submit remains disabled when a newer chain configuration finishes loading', async () => {
  const harness = requestHarness();
  await initialize(harness);

  const [submission] = harness.nodes.form.dispatch('submit');
  assert.equal(harness.nodes.submit.disabled, true);
  await changeToBeta(harness);

  assert.equal(harness.nodes.submit.disabled, true);
  harness.submitReply.resolve(response({ error: 'failed' }, false));
  await submission;
  assert.equal(harness.nodes.submit.disabled, false);
});

test('a failed submit clears the busy state and re-enables the run button', async () => {
  const harness = requestHarness();
  await initialize(harness);

  const [submission] = harness.nodes.form.dispatch('submit');
  assert.equal(harness.nodes.form.attributes.get('aria-busy'), 'true');
  assert.equal(harness.nodes.submit.disabled, true);

  harness.submitReply.resolve(response({ error: '配置校验失败' }, false));
  await submission;

  assert.equal(harness.nodes.form.attributes.get('aria-busy'), 'false');
  assert.equal(harness.nodes.submit.disabled, false);
  assert.equal(harness.nodes.submitResult.textContent, '配置校验失败');
});

test('a second submit event cannot start another request while the first is pending', async () => {
  const harness = requestHarness();
  await initialize(harness);

  const [first] = harness.nodes.form.dispatch('submit');
  await changeToBeta(harness);
  const [second] = harness.nodes.form.dispatch('submit');
  const callsWhilePending = harness.getPostCalls();
  harness.submitReply.resolve(response({ error: 'failed' }, false));
  await Promise.all([first, second]);

  assert.equal(callsWhilePending, 1);
});

test('a successful submit restores state from a still-loading chain before redirect', async () => {
  const betaReply = deferred();
  const harness = requestHarness({ betaReply });
  await initialize(harness);

  const [submission] = harness.nodes.form.dispatch('submit');
  harness.nodes.chain.value = 'beta';
  const [change] = harness.nodes.chain.dispatch('change');
  harness.submitReply.resolve(response({ id: 99 }));
  await submission;

  assert.equal(harness.location.href, '/runs/99');
  assert.equal(harness.nodes.submit.disabled, true);
  betaReply.resolve(response({ defaults: { 'project.name': 'Beta Demo' } }));
  await change;
  assert.equal(harness.nodes.submit.disabled, false);
});
