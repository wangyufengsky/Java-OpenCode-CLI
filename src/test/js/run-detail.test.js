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
    this.children = [];
    this.parent = null;
    this.dataset = {};
    this.textContent = '';
    this.hidden = false;
    this.value = '';
    this.className = '';
    this.href = '';
    this.handlers = new Map();
  }

  append(...children) {
    children.forEach((child) => this.appendChild(child));
  }

  appendChild(child) {
    child.parent = this;
    this.children.push(child);
    return child;
  }

  replaceChildren(...children) {
    this.children.forEach((child) => { child.parent = null; });
    this.children = [];
    this.append(...children);
  }

  remove() {
    if (!this.parent) return;
    this.parent.children = this.parent.children.filter((child) => child !== this);
    this.parent = null;
  }

  addEventListener(type, handler) {
    this.handlers.set(type, handler);
  }

  querySelector(selector) {
    return this.querySelectorAll(selector)[0] || null;
  }

  querySelectorAll(selector) {
    const matches = [];
    const visit = (node) => {
      node.children.forEach((child) => {
        if (matchesSelector(child, selector)) matches.push(child);
        visit(child);
      });
    };
    visit(this);
    return matches;
  }
}

function matchesSelector(element, selector) {
  if (selector.startsWith('#')) return element.id === selector.slice(1);
  const match = selector.match(/^(\w+)?\[data-([\w-]+)\]$/);
  if (!match) return false;
  const tagMatches = !match[1] || element.tagName === match[1].toUpperCase();
  const key = match[2].replace(/-([a-z])/g, (_all, letter) => letter.toUpperCase());
  return tagMatches && Object.prototype.hasOwnProperty.call(element.dataset, key);
}

class FakeDocument extends FakeElement {
  constructor() {
    super('document');
    this.body = this.add('body', 'body');
    this.body.dataset.runId = '42';
    this.add('strong', 'run-state', this.body).textContent = '排队中';
    this.add('strong', 'run-task-progress', this.body);
    this.add('strong', 'run-failed-tasks', this.body);
    this.add('strong', 'run-duration', this.body);
    this.add('span', 'stream-state', this.body);
    this.add('select', 'event-filter', this.body).value = 'all';
    this.add('select', 'task-filter', this.body).value = 'all';
    const events = this.add('ol', 'events', this.body);
    events.dataset.eventFilter = 'all';
    this.add('tbody', 'task-rows', this.body);
    this.add('section', 'failure-summary', this.body).hidden = true;
    this.add('div', 'failure-actions', this.body).hidden = true;
    this.add('a', 'rerun-failed-task', this.body).hidden = true;
    this.add('span', 'rerun-unavailable-reason', this.body).hidden = true;
    this.add('a', 'configure-new-run', this.body).hidden = true;
    this.add('dd', 'failure-message', this.body);
    this.add('dd', 'failed-task-key', this.body);
    this.add('dd', 'last-error-message', this.body);
  }

  add(tagName, id, parent = this) {
    return parent.appendChild(new FakeElement(tagName, id));
  }

  createElement(tagName) {
    return new FakeElement(tagName);
  }
}

class FakeEventSource {
  constructor(url) {
    this.url = url;
    this.listeners = new Map();
    FakeEventSource.instance = this;
  }

  addEventListener(type, handler) {
    this.listeners.set(type, handler);
  }

  emit(type, data) {
    this.listeners.get(type)?.({ data: typeof data === 'string' ? data : JSON.stringify(data) });
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

function snapshot(state, failedTaskKey = null, rerunAction = { visible: false, available: false }) {
  return {
    run: { state, chainId: 'git-code-contribution-report' },
    summary: {
      totalTasks: failedTaskKey ? 1 : 0,
      succeededTasks: 0,
      failedTasks: failedTaskKey ? 1 : 0,
      durationSeconds: 1,
      failureMessage: failedTaskKey ? 'failed' : null,
      failedTaskKey,
      lastErrorMessage: failedTaskKey ? 'failed event' : null
    },
    tasks: [],
    events: [],
    rerunAction
  };
}

function createHarness(fetchImplementation = async () => ({ ok: true, json: async () => snapshot('RUNNING') })) {
  const document = new FakeDocument();
  const timers = [];
  const window = {
    EventSource: FakeEventSource,
    fetch: fetchImplementation,
    setInterval(handler, delay) {
      timers.push({ handler, delay, cleared: false });
      return timers.length;
    },
    clearInterval(id) {
      if (timers[id - 1]) timers[id - 1].cleared = true;
    },
    location: { href: 'http://localhost/runs/42' },
    history: { replaceState() {} },
    AbortController,
    URL
  };
  const context = vm.createContext({ window, document, URL, Set, Array, Number, String, Promise, AbortController });
  const script = fs.readFileSync(path.join(__dirname, '../../main/resources/static/js/run-detail.js'), 'utf8');
  vm.runInContext(script, context, { filename: 'run-detail.js' });
  return { controller: window.AgentBridgeRunDetail.controller, document, source: FakeEventSource.instance, timers };
}

test('late recovery snapshot cannot overwrite a reopened SSE stream', async () => {
  const pending = deferred();
  const harness = createHarness(() => pending.promise);

  harness.source.onerror();
  harness.source.onerror();
  assert.equal(harness.document.querySelector('#stream-state').textContent, '定时刷新');
  harness.source.onopen();
  harness.source.emit('STARTED', { id: 10, eventType: 'STARTED', message: 'started' });
  pending.resolve({ ok: true, json: async () => snapshot('QUEUED') });
  await pending.promise;
  await new Promise((resolve) => setImmediate(resolve));

  assert.equal(harness.document.querySelector('#stream-state').textContent, '实时');
  assert.equal(harness.document.querySelector('#run-state').textContent, '运行中');
  assert.equal(harness.controller.getState().polling, false);
});

test('STARTED event advances the run state to RUNNING', () => {
  const harness = createHarness();
  harness.source.emit('STARTED', { id: 11, eventType: 'STARTED', message: 'started' });
  assert.equal(harness.document.querySelector('#run-state').textContent, '运行中');
});

test('snapshots fully replace failed-task actions instead of retaining an old rerun id', () => {
  const harness = createHarness();
  const safe = (rerunId) => ({
    visible: true,
    available: true,
    rerunType: 'author',
    rerunId,
    reason: null
  });

  harness.controller.updateSnapshot(snapshot('FAILED', 'author-a', safe('author-a')));
  assert.match(harness.document.querySelector('#rerun-failed-task').href, /rerunId=author-a/);

  harness.controller.updateSnapshot(snapshot('RUNNING'));
  assert.equal(harness.document.querySelector('#rerun-failed-task').hidden, true);
  assert.equal(harness.document.querySelector('#rerun-failed-task').href, '');

  harness.controller.updateSnapshot(snapshot('FAILED', 'author-b', safe('author-b')));
  assert.match(harness.document.querySelector('#rerun-failed-task').href, /rerunId=author-b/);
  assert.doesNotMatch(harness.document.querySelector('#rerun-failed-task').href, /author-a/);

  harness.controller.updateSnapshot(snapshot('FAILED', 'unknown-task', {
    visible: true,
    available: false,
    rerunType: null,
    rerunId: 'unknown-task',
    reason: '无法安全确定重跑类型'
  }));
  assert.equal(harness.document.querySelector('#rerun-failed-task').hidden, true);
  assert.equal(harness.document.querySelector('#rerun-failed-task').href, '');
  assert.equal(harness.document.querySelector('#rerun-unavailable-reason').hidden, false);
  assert.equal(harness.document.querySelector('#configure-new-run').hidden, false);
});

test('malformed SSE data is observable and does not stop the next event', () => {
  const harness = createHarness();
  harness.source.emit('STARTED', '{not-json');
  assert.equal(harness.document.querySelector('#stream-state').textContent, '消息格式异常，继续监听');

  harness.source.emit('STARTED', { id: 12, eventType: 'STARTED', message: 'valid' });
  assert.equal(harness.document.querySelector('#run-state').textContent, '运行中');
  assert.equal(harness.controller.getState().seenIds.has('12'), true);
});
