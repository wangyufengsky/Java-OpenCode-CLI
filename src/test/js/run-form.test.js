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

function createHarness(fetchImplementation, options = {}) {
  const selectedChain = options.selectedChain || 'alpha';
  const document = new FakeDocument();
  const form = add(document, document, 'form', 'run-form');
  form.dataset.selectedChain = selectedChain;
  form.dataset.selectedMode = options.selectedMode || 'full';
  form.dataset.selectedRunDate = '';
  form.dataset.selectedRerunType = options.selectedRerunType || '';
  form.dataset.selectedRerunId = options.selectedRerunId || '';
  form.dataset.copyFrom = '';

  const nodes = {
    form,
    chain: add(document, form, 'select', 'chainId', selectedChain),
    mode: add(document, form, 'select', 'mode', options.selectedMode || 'full'),
    rerunType: add(document, form, 'select', 'rerunType'),
    rerunId: add(document, form, 'input', 'rerunId'),
    runDate: add(document, form, 'input', 'runDate'),
    configFields: add(document, form, 'div', 'chain-config-fields'),
    configDescription: add(document, form, 'p', 'chain-config-description'),
    submit: add(document, form, 'button', 'submit-run'),
    submitResult: add(document, form, 'p', 'submit-result'),
    copyStatus: add(document, form, 'p', 'copy-load-status'),
    preflightAlert: add(document, form, 'p', 'preflight-alert'),
    summaryChain: add(document, form, 'span', 'summary-chain'),
    summaryProject: add(document, form, 'span', 'summary-project'),
    summaryScope: add(document, form, 'span', 'summary-scope'),
    summaryValidation: add(document, form, 'span', 'summary-validation'),
    summaryAgentBridge: add(document, form, 'span', 'summary-agentbridge'),
    summaryOutput: add(document, form, 'span', 'summary-output'),
    summaryReady: add(document, form, 'span', 'summary-ready')
  };

  const field = options.field || {
    key: 'project.name',
    label: '项目名称',
    description: '当前项目名称。',
    type: 'text',
    group: 'project',
    required: true,
    summary: true
  };
  const fields = options.fields || [field];
  const definitions = {
    alpha: { label: 'Alpha', description: 'Alpha workflow', fields },
    beta: { label: 'Beta', description: 'Beta workflow', fields }
  };
  const location = { href: 'http://localhost/runs/new' };
  const history = { replaceState() {} };
  const window = {
    ConsoleCommon: {
      chainConfigDefinitions: definitions,
      groupDefinitions: options.groupDefinitions
        || [{ group: 'project', label: '项目', description: '项目字段' }],
      rerunTypeDefinitions: { alpha: [], beta: [] }
    },
    location,
    history,
    setTimeout: options.setTimeout || setTimeout,
    clearTimeout: options.clearTimeout || clearTimeout
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
  if (options.loadConsoleCommon) {
    const commonScript = fs.readFileSync(
      path.join(__dirname, '../../main/resources/static/js/console-common.js'), 'utf8'
    );
    vm.runInContext(commonScript, context, { filename: 'console-common.js' });
  }
  const script = fs.readFileSync(path.join(__dirname, '../../main/resources/static/js/run-form.js'), 'utf8');
  vm.runInContext(script, context, { filename: 'run-form.js' });
  return { nodes, location, consoleCommon: window.ConsoleCommon };
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
  const harness = createHarness(fetchImplementation, options);
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

function createFakeTimers() {
  let nextId = 1;
  const callbacks = new Map();
  return {
    setTimeout(callback) {
      const id = nextId++;
      callbacks.set(id, callback);
      return id;
    },
    clearTimeout(id) {
      callbacks.delete(id);
    },
    fireNext() {
      const next = callbacks.entries().next().value;
      if (!next) throw new Error('No timer is pending');
      callbacks.delete(next[0]);
      next[1]();
    }
  };
}

test('run summary renders the six Figma summary fields independently', async () => {
  const fields = [
    { key: 'project.repo', label: '项目仓库', description: '', type: 'text', group: 'project', required: true },
    { key: 'paths.out', label: '输出目录', description: '', type: 'path', group: 'project', required: true },
    { key: 'source.package-paths', label: '包路径', description: '', type: 'list', group: 'scope' },
    { key: 'test.require-coverage', label: '要求覆盖率', description: '', type: 'checkbox', group: 'validation' },
    { key: 'test.coverage-threshold-percent', label: '覆盖率阈值', description: '', type: 'number', group: 'validation' },
    { key: 'agentbridge.timeout-minutes', label: '超时', description: '', type: 'number', group: 'agentbridge' },
    { key: 'agentbridge.max-attempts', label: '尝试轮次', description: '', type: 'number', group: 'agentbridge' }
  ];
  const defaults = {
    'project.repo': '/workspace/demo',
    'paths.out': 'src/test/java',
    'source.package-paths': ['src/main/java'],
    'test.require-coverage': true,
    'test.coverage-threshold-percent': 80,
    'agentbridge.timeout-minutes': 30,
    'agentbridge.max-attempts': 3
  };
  const harness = createHarness(
    () => Promise.resolve(response({ defaults })),
    {
      fields,
      groupDefinitions: [
        { group: 'project', label: '项目', description: '' },
        { group: 'scope', label: '范围', description: '' },
        { group: 'validation', label: '验证', description: '' },
        { group: 'agentbridge', label: 'AgentBridge', description: '' }
      ]
    }
  );
  await initialize(harness);

  assert.equal(harness.nodes.summaryChain.textContent, 'Alpha');
  assert.equal(harness.nodes.summaryProject.textContent, '/workspace/demo');
  assert.equal(harness.nodes.summaryScope.textContent, 'src/main/java');
  assert.equal(harness.nodes.summaryValidation.textContent, '失败即停止 · 覆盖率 80%');
  assert.equal(harness.nodes.summaryAgentBridge.textContent, '最多 3 轮 · 30 分钟超时');
  assert.equal(harness.nodes.summaryOutput.textContent, 'src/test/java');
  assert.equal(harness.nodes.summaryReady.textContent, '已就绪');
});

test('MyBatis metadata renders every field, all summaries, and the three rerun modes', async () => {
  const timers = createFakeTimers();
  const defaults = {
    'project.id': 'demo',
    'project.name': 'Demo',
    'project.repo': '/workspace/demo',
    'paths.out': '/reports/mybatis',
    'source.paths': ['src/main/resources/mapper'],
    'source.include': ['**/*Mapper.xml'],
    'database.connection-name': 'Gauss Review',
    'database.database-name': 'orders',
    'database.schema-name': 'audit',
    'database.scope': 'PROJECT',
    'database.environment': 'test',
    'database.non-owner-non-admin-read-only-account': true,
    'database.row-level-security-disabled-for-safe-base-tables': true,
    'database.user-defined-and-security-definer-function-execution-revoked-including-public': true,
    'database.statement-timeout-seconds': 30,
    'database.statement-timeout-scope': 'role',
    'database.max-rows': 20,
    'database.max-scenarios-per-sql': 3,
    'database.max-evidence-bytes': 262144,
    'database.retain-raw-rows': true,
    'database.allow-agent-select': true,
    'agentbridge.web-base-url': 'http://agentbridge.test',
    'agentbridge.mcp-url': 'http://agentbridge.test/mcp',
    'agentbridge.concurrency': 1,
    'agentbridge.max-concurrency': 1,
    'agentbridge.timeout-minutes': 2,
    'agentbridge.poll-millis': 1000,
    'agentbridge.validation-settle-seconds': 0,
    'agentbridge.validation-max-corrections': 0,
    'agentbridge.task-message': 'Review SQL'
  };
  const harness = createHarness(
    (url) => {
      if (url.includes('/api/chains/mybatis-sql-review/defaults')) {
        return Promise.resolve(response({ defaults }));
      }
      throw new Error(`Unexpected request: ${url}`);
    },
    {
      loadConsoleCommon: true,
      selectedChain: 'mybatis-sql-review',
      selectedMode: 'rerun',
      selectedRerunType: 'sql',
      selectedRerunId: 'statement-key',
      setTimeout: timers.setTimeout,
      clearTimeout: timers.clearTimeout
    }
  );
  await initialize(harness);

  assert.equal(harness.nodes.form.querySelectorAll('[data-config-key]').length, 32);
  assert.ok(harness.nodes.form.querySelector('#config-database-connection-name'));
  assert.ok(harness.nodes.form.querySelector('#config-agentbridge-validation-max-corrections'));
  assert.equal(harness.nodes.summaryChain.textContent, 'MyBatis SQL 审查');
  assert.equal(harness.nodes.summaryProject.textContent, '/workspace/demo');
  assert.equal(harness.nodes.summaryScope.textContent, 'src/main/resources/mapper');
  assert.equal(harness.nodes.summaryValidation.textContent, '失败即停止');
  assert.equal(harness.nodes.summaryAgentBridge.textContent, '自动重连 · 2 分钟超时');
  assert.equal(harness.nodes.summaryOutput.textContent, '/reports/mybatis');
  assert.deepEqual(harness.nodes.rerunType.children.map((option) => option.value), ['sql', 'xml', 'index']);
  assert.equal(harness.nodes.rerunId.disabled, false);
  assert.equal(harness.nodes.rerunId.required, true);
  assert.equal(harness.nodes.rerunId.value, 'statement-key');

  harness.nodes.rerunType.value = 'xml';
  harness.nodes.rerunType.dispatch('change');
  assert.equal(harness.nodes.rerunId.disabled, false);
  assert.equal(harness.nodes.rerunId.required, true);

  harness.nodes.rerunType.value = 'index';
  harness.nodes.rerunType.dispatch('change');
  assert.equal(harness.nodes.rerunId.disabled, true);
  assert.equal(harness.nodes.rerunId.required, false);
  assert.equal(harness.nodes.rerunId.value, '');
});

test('MyBatis console metadata describes the native Database MCP boundary', () => {
  const harness = createHarness(() => Promise.resolve(response({ defaults: {} })), {
    loadConsoleCommon: true,
    selectedChain: 'mybatis-sql-review'
  });
  const definition = harness.consoleCommon.chainConfigDefinitions['mybatis-sql-review'];
  const connectionName = definition.fields.find((field) => field.key === 'database.connection-name');
  const databaseScope = definition.fields.find((field) => field.key === 'database.scope');
  const mcpUrl = definition.fields.find((field) => field.key === 'agentbridge.mcp-url');

  assert.match(definition.description, /AgentBridge Custom MCP/);
  assert.equal(connectionName.description, 'AgentBridge Custom MCP 注册的 Database MCP 数据源名称。');
  assert.doesNotMatch(connectionName.description, /AgentBridge Database Tools/);
  assert.match(databaseScope.description, /GLOBAL、PROJECT 或 ALL/);
  assert.doesNotMatch(mcpUrl.description, /GLOBAL、PROJECT 或 ALL/);
  assert.match(mcpUrl.description, /http:\/\/127\.0\.0\.1:8642\/mcp/);
});

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

test('a stale path preflight cannot update the alert after switching chains', async () => {
  const timers = createFakeTimers();
  const oldPreflight = deferred();
  const field = {
    key: 'source.repo',
    label: '仓库路径',
    description: '本机仓库路径。',
    type: 'path',
    group: 'project',
    required: false,
    summary: true
  };
  const harness = createHarness((url) => {
    if (url.includes('/api/chains/alpha/defaults')) return Promise.resolve(response({ defaults: { 'source.repo': '/alpha' } }));
    if (url.includes('/api/chains/beta/defaults')) return Promise.resolve(response({ defaults: { 'source.repo': '' } }));
    if (url.startsWith('/api/path-preflight')) return oldPreflight.promise;
    throw new Error(`Unexpected request: ${url}`);
  }, { field, setTimeout: timers.setTimeout, clearTimeout: timers.clearTimeout });
  await flush();

  timers.fireNext();
  await flush();
  await changeToBeta(harness);
  assert.equal(harness.nodes.preflightAlert.textContent, '');

  oldPreflight.resolve(response({ accessible: false, message: '旧路径不可访问' }));
  await flush();

  assert.equal(harness.nodes.preflightAlert.textContent, '');
});

test('a replaced path value prevents its earlier preflight response from writing status', async () => {
  const timers = createFakeTimers();
  const firstPreflight = deferred();
  const field = {
    key: 'source.repo',
    label: '仓库路径',
    description: '本机仓库路径。',
    type: 'path',
    group: 'project',
    required: false,
    summary: true
  };
  const harness = createHarness((url) => {
    if (url.includes('/api/chains/alpha/defaults')) return Promise.resolve(response({ defaults: { 'source.repo': '/first' } }));
    if (url.startsWith('/api/path-preflight')) return firstPreflight.promise;
    throw new Error(`Unexpected request: ${url}`);
  }, { field, setTimeout: timers.setTimeout, clearTimeout: timers.clearTimeout });
  await flush();

  timers.fireNext();
  await flush();
  const control = harness.nodes.form.querySelector('#config-source-repo');
  control.value = '/second';
  control.dispatch('input');
  assert.equal(harness.nodes.preflightAlert.textContent, '路径预检等待中…');

  firstPreflight.resolve(response({ accessible: false, message: '旧路径不可访问' }));
  await flush();

  assert.equal(harness.nodes.preflightAlert.textContent, '路径预检等待中…');
  assert.notEqual(harness.nodes.form.querySelector('#config-source-repo-validation').textContent, '旧路径不可访问');
});

test('clearing a path prevents its earlier preflight response from restoring the alert', async () => {
  const timers = createFakeTimers();
  const firstPreflight = deferred();
  const field = {
    key: 'source.repo',
    label: '仓库路径',
    description: '本机仓库路径。',
    type: 'path',
    group: 'project',
    required: false,
    summary: true
  };
  const harness = createHarness((url) => {
    if (url.includes('/api/chains/alpha/defaults')) return Promise.resolve(response({ defaults: { 'source.repo': '/first' } }));
    if (url.startsWith('/api/path-preflight')) return firstPreflight.promise;
    throw new Error(`Unexpected request: ${url}`);
  }, { field, setTimeout: timers.setTimeout, clearTimeout: timers.clearTimeout });
  await flush();

  timers.fireNext();
  await flush();
  const control = harness.nodes.form.querySelector('#config-source-repo');
  control.value = '';
  control.dispatch('input');
  assert.equal(harness.nodes.preflightAlert.textContent, '');

  firstPreflight.resolve(response({ accessible: false, message: '旧路径不可访问' }));
  await flush();

  assert.equal(harness.nodes.preflightAlert.textContent, '');
  assert.equal(harness.nodes.form.querySelector('#config-source-repo-validation').textContent, '');
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
