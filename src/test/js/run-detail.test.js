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

function flushPromises() {
  return new Promise((resolve) => setImmediate(resolve));
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

test('SSE reconnecting state is exposed without changing the event snapshot order', () => {
  const harness = createHarness();
  harness.source.emit('STARTED', { id: 11, eventType: 'STARTED', message: 'first' });
  harness.source.onerror();

  const streamState = harness.document.querySelector('#stream-state');
  assert.equal(streamState.textContent, '正在重连');
  assert.equal(streamState.dataset.connectionState, 'reconnecting');
  assert.deepEqual(
    harness.document.querySelector('#events').querySelectorAll('li[data-event-id]').map((item) => item.dataset.eventId),
    ['11']
  );
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

test('healthy task events refresh the snapshot without leaving realtime mode', async () => {
  const requests = [];
  const failedSnapshot = snapshot('FAILED', 'author-a', {
    visible: true,
    available: true,
    rerunType: 'author',
    rerunId: 'author-a',
    reason: null
  });
  failedSnapshot.summary.totalTasks = 2;
  failedSnapshot.summary.succeededTasks = 1;
  failedSnapshot.tasks = [
    { taskKey: 'author-a', taskName: '作者 A', state: 'FAILED', phase: 'failed' },
    { taskKey: 'author-b', taskName: '作者 B', state: 'SUCCEEDED', phase: 'complete' }
  ];
  const harness = createHarness(async (url) => {
    requests.push(url);
    return { ok: true, json: async () => failedSnapshot };
  });

  harness.source.onopen();
  harness.source.emit('TASK_FAILED', {
    id: 21,
    eventType: 'TASK_FAILED',
    message: 'author-a failed'
  });
  await flushPromises();

  assert.deepEqual(requests, ['/api/runs/42/snapshot?afterEventId=21']);
  assert.equal(harness.document.querySelector('#stream-state').textContent, '实时');
  assert.equal(harness.document.querySelector('#run-task-progress').textContent, '1/2');
  assert.equal(harness.document.querySelector('#run-failed-tasks').textContent, '1');
  assert.equal(harness.document.querySelector('#failure-summary').hidden, false);
  assert.equal(harness.document.querySelector('#failure-message').textContent, 'failed');
  assert.equal(harness.document.querySelector('#task-rows').querySelectorAll('tr[data-task-key]').length, 2);
  assert.equal(harness.document.querySelector('#rerun-failed-task').hidden, false);
  assert.match(harness.document.querySelector('#rerun-failed-task').href, /rerunId=author-a/);
});

test('healthy task refreshes coalesce bursts and replayed snapshot events do not loop', async () => {
  const first = deferred();
  const second = deferred();
  const requests = [];
  const harness = createHarness((url) => {
    requests.push(url);
    return requests.length === 1 ? first.promise : second.promise;
  });

  harness.source.onopen();
  harness.source.emit('TASK_RUNNING', { id: 30, eventType: 'TASK_RUNNING', message: 'running' });
  harness.source.emit('TASK_FAILED', { id: 31, eventType: 'TASK_FAILED', message: 'failed' });
  harness.source.emit('TASK_SUCCEEDED', { id: 32, eventType: 'TASK_SUCCEEDED', message: 'succeeded' });
  assert.equal(requests.length, 1);

  const firstSnapshot = snapshot('RUNNING');
  firstSnapshot.events = [{ id: 33, eventType: 'TASK_RUNNING', message: 'snapshot replay' }];
  first.resolve({ ok: true, json: async () => firstSnapshot });
  await flushPromises();
  assert.equal(requests.length, 2);
  assert.equal(requests[1], '/api/runs/42/snapshot?afterEventId=33');

  const secondSnapshot = snapshot('RUNNING');
  secondSnapshot.events = [
    { id: 34, eventType: 'TASK_RUNNING', message: 'snapshot replay 2' },
    { id: 35, eventType: 'TASK_FAILED', message: 'snapshot replay 3' }
  ];
  second.resolve({ ok: true, json: async () => secondSnapshot });
  await flushPromises();
  await flushPromises();

  assert.equal(requests.length, 2);
  assert.equal(harness.document.querySelector('#stream-state').textContent, '实时');
  assert.equal(harness.controller.getState().lastEventId, 35);
});

test('a late healthy refresh cannot overwrite a later recovery generation or reopened stream', async () => {
  const healthy = deferred();
  let requestCount = 0;
  const harness = createHarness(() => {
    requestCount += 1;
    if (requestCount === 1) return healthy.promise;
    return Promise.resolve({ ok: true, json: async () => snapshot('RUNNING') });
  });

  harness.source.onopen();
  harness.source.emit('TASK_RUNNING', { id: 40, eventType: 'TASK_RUNNING', message: 'running' });
  assert.equal(requestCount, 1);
  harness.source.onerror();
  harness.source.onerror();
  await flushPromises();
  harness.source.onopen();
  harness.source.emit('STARTED', { id: 41, eventType: 'STARTED', message: 'reopened' });

  healthy.resolve({ ok: true, json: async () => snapshot('QUEUED') });
  await flushPromises();
  await flushPromises();

  assert.equal(harness.document.querySelector('#stream-state').textContent, '实时');
  assert.equal(harness.document.querySelector('#run-state').textContent, '运行中');
  assert.equal(harness.controller.getState().polling, false);
});
