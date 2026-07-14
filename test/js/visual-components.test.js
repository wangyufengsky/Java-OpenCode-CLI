'use strict';

const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');

class Element {
  constructor(id, document) {
    this.id = id;
    this.ownerDocument = document;
    this.hidden = false;
    this.disabled = false;
    this.required = false;
    this.checked = false;
    this.value = '';
    this.dataset = {};
    this.listeners = new Map();
    this.attributes = new Map();
    this.classList = { add() {}, remove() {}, toggle() {} };
  }
  addEventListener(type, listener) { this.listeners.set(type, [...(this.listeners.get(type) || []), listener]); }
  dispatch(type, init = {}) {
    const event = { key: '', shiftKey: false, preventDefault() { this.defaultPrevented = true; }, ...init };
    (this.listeners.get(type) || []).forEach((listener) => listener(event));
    return event;
  }
  querySelector(selector) { return selector.includes('select') ? this.firstFocusable : null; }
  querySelectorAll() { return []; }
  replaceChildren() {}
  append() {}
  appendChild() {}
  setAttribute(name, value) { this.attributes.set(name, String(value)); }
  getAttribute(name) { return this.attributes.get(name) || null; }
  removeAttribute(name) { this.attributes.delete(name); }
  focus() { this.ownerDocument.activeElement = this; }
  checkValidity() { return true; }
  reportValidity() {}
}

function harness() {
  const document = new Element('document');
  document.body = new Element('body', document);
  document.activeElement = null;
  const ids = [
    'schedule-page', 'schedule-list', 'schedule-search', 'schedule-empty', 'schedule-no-results',
    'schedule-page-message', 'schedule-drawer', 'schedule-drawer-backdrop', 'schedule-form',
    'schedule-drawer-title', 'schedule-drawer-description', 'schedule-form-error', 'schedule-result',
    'save-schedule', 'schedule-id', 'schedule-chain-id', 'schedule-mode', 'schedule-rerun-type',
    'schedule-rerun-id', 'schedule-run-date', 'frequency', 'dayOfWeek', 'scheduleTime', 'runAt',
    'enabled', 'schedule-config-fields', 'schedule-config-description', 'create-schedule',
    'close-schedule-drawer', 'cancel-schedule'
  ];
  const nodes = Object.fromEntries(ids.map((id) => [id, new Element(id, document)]));
  document.querySelector = (selector) => nodes[selector.slice(1)] || null;
  document.querySelectorAll = () => [];
  document.createElement = (tag) => new Element(tag, document);
  nodes['schedule-drawer'].hidden = true;
  nodes['schedule-drawer-backdrop'].hidden = true;
  nodes['schedule-drawer'].firstFocusable = nodes['schedule-chain-id'];
  nodes['schedule-chain-id'].value = 'alpha';
  nodes['schedule-mode'].value = 'full';
  nodes.frequency.value = 'daily';
  nodes.dayOfWeek.value = '1';
  nodes.scheduleTime.value = '06:00';
  nodes.enabled.checked = true;
  const trigger = nodes['create-schedule'];
  document.activeElement = trigger;
  const window = {
    fetch: async () => ({ ok: true, json: async () => ({ defaults: {} }) }),
    location: { reload() {} },
    ConsoleCommon: { chainConfigDefinitions: { alpha: { description: '', fields: [] } }, rerunTypeDefinitions: {} }
  };
  const context = vm.createContext({ window, document, JSON, String, Array, Set, Map, Error, Promise, AbortController, encodeURIComponent });
  vm.runInContext(fs.readFileSync(path.join(__dirname, '../../src/main/resources/static/js/schedules.js'), 'utf8'), context);
  return { controller: window.AgentBridgeSchedules.controller, nodes, document, trigger };
}

test('semantic drawer closes on Escape and restores trigger focus', () => {
  const template = fs.readFileSync(path.join(__dirname, '../../src/main/resources/templates/schedules.html'), 'utf8');
  assert.match(template, /class="schedule-drawer c-drawer"/);

  const { controller, nodes, document, trigger } = harness();
  controller.open('create', null, trigger);
  assert.equal(nodes['schedule-drawer'].hidden, false);
  document.dispatch('keydown', { key: 'Escape' });
  assert.equal(nodes['schedule-drawer'].hidden, true);
  assert.equal(document.activeElement, trigger);
});
