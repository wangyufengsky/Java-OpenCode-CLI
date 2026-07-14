(function exposeSchedules() {
  'use strict';

  function copyDraft(record) {
    return { ...JSON.parse(JSON.stringify(record)), id: null };
  }

  function saveUrl(id) {
    return id === null || id === undefined || id === '' ? '/api/schedules' : `/api/schedules/${encodeURIComponent(id)}`;
  }

  async function readBody(response) {
    try {
      return await response.json();
    } catch (_error) {
      return {};
    }
  }

  async function requestSave(id, payload, fetchImplementation = window.fetch.bind(window)) {
    const url = saveUrl(id);
    const response = await fetchImplementation(url, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(payload)
    });
    const body = await readBody(response);
    if (!response.ok) throw new Error(body.error || '保存失败');
    return { url, body };
  }

  function switchLabelBase(button) {
    return button.dataset.labelBase || (button.getAttribute('aria-label') || '').split('，')[0] || '定时任务';
  }

  function applySwitchState(button, enabled) {
    const label = button.querySelector('.schedule-switch-label');
    button.dataset.enabled = String(enabled);
    button.setAttribute('aria-checked', String(enabled));
    button.setAttribute('aria-label', `${switchLabelBase(button)}，当前${enabled ? '已启用，点击停用' : '已停用，点击启用'}`);
    button.classList.toggle('is-enabled', enabled);
    if (label) label.textContent = enabled ? '已启用' : '已停用';
  }

  async function toggleSchedule(button, scheduleId, message, fetchImplementation = window.fetch.bind(window)) {
    const original = button.getAttribute('aria-checked') === 'true';
    const requested = !original;
    button.disabled = true;
    applySwitchState(button, requested);
    if (message) message.textContent = requested ? '正在启用计划…' : '正在停用计划…';
    try {
      const response = await fetchImplementation(`/api/schedules/${encodeURIComponent(scheduleId)}/enabled`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ enabled: requested })
      });
      const body = await readBody(response);
      if (!response.ok) throw new Error(body.error || '启停操作失败');
      applySwitchState(button, Boolean(body.enabled));
      if (message) message.textContent = body.enabled ? '计划已启用。' : '计划已停用。';
      return body;
    } catch (error) {
      applySwitchState(button, original);
      if (message) message.textContent = error.message || '启停操作失败';
      throw error;
    } finally {
      button.disabled = false;
    }
  }

  function filterRows(rows, query, status = 'all') {
    const normalized = String(query || '').trim().toLocaleLowerCase('zh-CN');
    let visible = 0;
    rows.forEach((row) => {
      const matchesQuery = !normalized || String(row.dataset.search || row.textContent || '').toLocaleLowerCase('zh-CN').includes(normalized);
      const matchesStatus = status === 'all'
        || (status === 'enabled' && row.dataset.enabled === 'true')
        || (status === 'disabled' && row.dataset.enabled === 'false');
      const matches = matchesQuery && matchesStatus;
      row.hidden = !matches;
      if (matches) visible += 1;
    });
    return visible;
  }

  function createSchedulesController(document, dependencies = {}) {
  const window = dependencies.window || globalThis.window;
  let fetchImplementation = dependencies.fetch || window.fetch.bind(window);
  const page = document.querySelector('#schedule-page');
  if (!page) return null;

  const common = window.ConsoleCommon || { chainConfigDefinitions: {}, rerunTypeDefinitions: {} };
  const list = document.querySelector('#schedule-list');
  const search = document.querySelector('#schedule-search');
  const statusFilter = document.querySelector('#schedule-status-filter');
  const empty = document.querySelector('#schedule-empty');
  const noResults = document.querySelector('#schedule-no-results');
  const pageMessage = document.querySelector('#schedule-page-message');
  const drawer = document.querySelector('#schedule-drawer');
  const backdrop = document.querySelector('#schedule-drawer-backdrop');
  const form = document.querySelector('#schedule-form');
  const drawerTitle = document.querySelector('#schedule-drawer-title');
  const drawerDescription = document.querySelector('#schedule-drawer-description');
  const formError = document.querySelector('#schedule-form-error');
  const result = document.querySelector('#schedule-result');
  const saveButton = document.querySelector('#save-schedule');
  const idInput = document.querySelector('#schedule-id');
  const chain = document.querySelector('#schedule-chain-id');
  const mode = document.querySelector('#schedule-mode');
  const rerunType = document.querySelector('#schedule-rerun-type');
  const rerunId = document.querySelector('#schedule-rerun-id');
  const runDate = document.querySelector('#schedule-run-date');
  const frequency = document.querySelector('#frequency');
  const dayOfWeek = document.querySelector('#dayOfWeek');
  const runTime = document.querySelector('#scheduleTime');
  const runAt = document.querySelector('#runAt');
  const enabled = document.querySelector('#enabled');
  const configFields = document.querySelector('#schedule-config-fields');
  const configDescription = document.querySelector('#schedule-config-description');
  let records = null;
  let returnFocus = null;
  let configSequence = 0;
  let configAbortController = null;
  let configLoading = false;
  let configReady = false;
  let submitInFlight = false;
  const toggleInFlight = new Map();

  function rows() {
    return Array.from(list.querySelectorAll('tr[data-schedule-id]'));
  }

  async function loadRecords() {
    const response = await fetchImplementation('/api/schedules');
    const body = await readBody(response);
    if (!response.ok) throw new Error(body.error || '无法读取定时任务');
    records = body;
    return records;
  }

  function closeDrawer() {
    drawer.hidden = true;
    backdrop.hidden = true;
    document.body.classList.remove('drawer-open');
    const target = returnFocus;
    returnFocus = null;
    if (target && typeof target.focus === 'function') target.focus();
  }

  function syncSaveState() {
    saveButton.disabled = configLoading || !configReady || submitInFlight;
    if (configLoading) form.setAttribute('aria-busy', 'true');
    else form.removeAttribute('aria-busy');
  }

  function setConfigState(loading, ready) {
    configLoading = loading;
    configReady = ready;
    syncSaveState();
  }

  function showError(message) {
    formError.textContent = message;
    formError.hidden = false;
  }

  function clearError() {
    formError.textContent = '';
    formError.hidden = true;
    result.textContent = '';
  }

  function renderRerunTypes(selected) {
    const options = common.rerunTypeDefinitions[chain.value] || [];
    rerunType.replaceChildren();
    options.forEach((definition) => {
      const option = document.createElement('option');
      option.value = definition.value;
      option.textContent = definition.label;
      rerunType.appendChild(option);
    });
    if (selected && options.some((definition) => definition.value === selected)) rerunType.value = selected;
    updateRerunControls();
  }

  function updateRerunControls() {
    const rerunMode = mode.value === 'rerun';
    const definition = (common.rerunTypeDefinitions[chain.value] || []).find((item) => item.value === rerunType.value);
    rerunType.disabled = !rerunMode;
    rerunId.disabled = !rerunMode || !definition || !definition.requiresId;
    rerunId.required = rerunMode && Boolean(definition && definition.requiresId);
    if (rerunId.disabled) rerunId.value = '';
  }

  function createConfigControl(definition, value) {
    let control;
    if (definition.type === 'textarea' || definition.type === 'list') {
      control = document.createElement('textarea');
      control.rows = definition.type === 'list' ? 3 : 4;
      control.value = Array.isArray(value) ? value.join('\n') : (value ?? '');
    } else {
      control = document.createElement('input');
      control.type = definition.type === 'checkbox' ? 'checkbox' : (definition.type === 'path' ? 'text' : definition.type);
      if (definition.type === 'checkbox') control.checked = Boolean(value);
      else control.value = value ?? '';
    }
    control.id = `schedule-config-${definition.key.replaceAll('.', '-').replaceAll('/', '-')}`;
    control.dataset.configKey = definition.key;
    control.dataset.configType = definition.type;
    control.required = Boolean(definition.required);
    return control;
  }

  async function defaultsFor(chainId, signal) {
    const response = await fetchImplementation(`/api/chains/${encodeURIComponent(chainId)}/defaults`, { signal });
    const body = await readBody(response);
    if (!response.ok) throw new Error(body.error || '默认配置读取失败');
    return body.defaults || {};
  }

  async function renderConfig(values) {
    const sequence = ++configSequence;
    if (configAbortController) configAbortController.abort();
    configAbortController = new AbortController();
    const chainId = chain.value;
    const definition = common.chainConfigDefinitions[chainId];
    setConfigState(true, false);
    configFields.replaceChildren();
    configDescription.textContent = definition ? definition.description : '';
    if (!definition) {
      if (sequence === configSequence) setConfigState(false, true);
      return;
    }
    let resolved = values;
    if (resolved === null || resolved === undefined) {
      const loading = document.createElement('p');
      loading.className = 'muted';
      loading.textContent = '正在读取链路默认配置…';
      configFields.appendChild(loading);
      try {
        resolved = await defaultsFor(chainId, configAbortController.signal);
      } catch (error) {
        if (sequence !== configSequence || error.name === 'AbortError') return;
        resolved = {};
        if (sequence === configSequence) showError(`${error.message}，可继续手工填写。`);
      }
    }
    if (sequence !== configSequence) return;
    configFields.replaceChildren();
    definition.fields.forEach((field) => {
      const wrapper = document.createElement('label');
      const title = document.createElement('span');
      title.className = 'config-field-title';
      title.textContent = field.required ? `${field.label} *` : field.label;
      const control = createConfigControl(field, resolved[field.key]);
      const description = document.createElement('small');
      description.textContent = field.description;
      wrapper.htmlFor = control.id;
      wrapper.append(title, control, description);
      configFields.appendChild(wrapper);
    });
    setConfigState(false, true);
  }

  function setFrequencyFields() {
    const weekly = frequency.value === 'weekly';
    const once = frequency.value === 'once';
    document.querySelectorAll('.schedule-weekly-field').forEach((field) => { field.hidden = !weekly; });
    document.querySelectorAll('.schedule-time-field').forEach((field) => { field.hidden = once; });
    document.querySelectorAll('.schedule-once-field').forEach((field) => { field.hidden = !once; });
    dayOfWeek.disabled = !weekly;
    runTime.disabled = once;
    runAt.disabled = !once;
    runTime.required = !once;
    runAt.required = once;
  }

  function populate(record) {
    idInput.value = record && record.id != null ? String(record.id) : '';
    if (record) chain.value = record.chainId;
    mode.value = record?.mode || 'full';
    renderRerunTypes(record?.rerunType || '');
    rerunId.value = record?.rerunId || '';
    runDate.value = record?.runDate || '';
    frequency.value = record?.frequency ? record.frequency.toLowerCase() : 'daily';
    dayOfWeek.value = record?.dayOfWeek || '1';
    runTime.value = record?.runTime ? record.runTime.slice(0, 5) : '06:00';
    runAt.value = record?.runAt ? record.runAt.slice(0, 16) : '';
    enabled.checked = record ? Boolean(record.enabled) : true;
    setFrequencyFields();
    return renderConfig(record ? (record.config || {}) : null);
  }

  function openDrawer(kind, record, trigger) {
    returnFocus = trigger || document.activeElement;
    clearError();
    drawerTitle.textContent = kind === 'edit' ? '编辑计划' : kind === 'copy' ? '复制为新计划' : '新建计划';
    drawerDescription.textContent = kind === 'copy' ? '已复制原计划配置；保存后会创建一个新的计划。' : '配置工作流和执行时间。';
    populate(record);
    drawer.hidden = false;
    backdrop.hidden = false;
    document.body.classList.add('drawer-open');
    drawer.querySelector('select, input, button')?.focus();
  }

  function updateCachedRecord(scheduleId, updated) {
    if (!records || !updated) return;
    const index = records.findIndex((item) => String(item.id) === String(scheduleId));
    if (index >= 0) records[index] = { ...records[index], ...updated };
  }

  function toggle(button, scheduleId) {
    const key = String(scheduleId);
    if (toggleInFlight.has(key)) return toggleInFlight.get(key);
    const row = button.closest('tr[data-schedule-id]');
    const originalEnabled = row ? row.dataset.enabled : null;
    const operation = toggleSchedule(button, scheduleId, pageMessage, fetchImplementation)
      .then((updated) => {
        updateCachedRecord(scheduleId, updated);
        if (row && Object.prototype.hasOwnProperty.call(updated, 'enabled')) row.dataset.enabled = String(Boolean(updated.enabled));
        applyFilters();
        return updated;
      })
      .catch((error) => {
        if (row && originalEnabled !== null) row.dataset.enabled = originalEnabled;
        applyFilters();
        throw error;
      })
      .finally(() => toggleInFlight.delete(key));
    toggleInFlight.set(key, operation);
    return operation;
  }

  function readControl(control) {
    const type = control.dataset.configType;
    if (type === 'checkbox') return control.checked;
    if (type === 'number') return control.value === '' ? null : Number(control.value);
    if (type === 'list') return control.value.split('\n').map((line) => line.trim()).filter(Boolean);
    return control.value;
  }

  function payload() {
    const config = {};
    configFields.querySelectorAll('[data-config-key]').forEach((control) => {
      config[control.dataset.configKey] = readControl(control);
    });
    const rerunDefinition = (common.rerunTypeDefinitions[chain.value] || []).find((item) => item.value === rerunType.value);
    return {
      chainId: chain.value,
      mode: mode.value,
      rerunType: mode.value === 'rerun' ? rerunType.value : '',
      rerunId: mode.value === 'rerun' && rerunDefinition?.requiresId ? rerunId.value : '',
      runDate: runDate.value || null,
      config,
      frequency: frequency.value,
      dayOfWeek: frequency.value === 'weekly' ? Number(dayOfWeek.value) : null,
      runTime: frequency.value === 'once' ? null : runTime.value,
      runAt: frequency.value === 'once' ? runAt.value : null,
      enabled: enabled.checked
    };
  }

  function applyFilters() {
    const visible = filterRows(rows(), search.value, statusFilter ? statusFilter.value : 'all');
    noResults.hidden = visible > 0 || rows().length === 0;
    if (empty) empty.hidden = rows().length > 0;
  }

  search.addEventListener('input', applyFilters);
  if (statusFilter) statusFilter.addEventListener('change', applyFilters);

  list.addEventListener('click', async (event) => {
    const row = event.target.closest('tr[data-schedule-id]');
    if (!row) return;
    const toggleButton = event.target.closest('.schedule-switch');
    if (toggleButton) {
      try {
        await toggle(toggleButton, row.dataset.scheduleId);
      } catch (error) {
        pageMessage.textContent = error.message || '启停操作失败';
      }
      return;
    }
    const action = event.target.closest('[data-action]');
    if (!action) return;
    try {
      const current = (records || await loadRecords()).find((item) => String(item.id) === row.dataset.scheduleId);
      if (!current) throw new Error('未找到定时任务');
      openDrawer(action.dataset.action, action.dataset.action === 'copy' ? copyDraft(current) : current, action);
    } catch (error) {
      pageMessage.textContent = error.message || '无法读取定时任务';
    }
  });

  document.querySelector('#create-schedule').addEventListener('click', (event) => openDrawer('create', null, event.currentTarget));
  document.querySelector('#close-schedule-drawer').addEventListener('click', closeDrawer);
  document.querySelector('#cancel-schedule').addEventListener('click', closeDrawer);
  backdrop.addEventListener('click', closeDrawer);
  document.addEventListener('keydown', (event) => {
    if (drawer.hidden) return;
    if (event.key === 'Escape') {
      closeDrawer();
      return;
    }
    if (event.key !== 'Tab') return;
    const focusable = Array.from(drawer.querySelectorAll('a[href], button:not([disabled]), input:not([disabled]):not([type="hidden"]), select:not([disabled]), textarea:not([disabled]), [tabindex]:not([tabindex="-1"])'))
      .filter((element) => !element.hidden && element.getAttribute('aria-hidden') !== 'true');
    if (focusable.length === 0) return;
    const first = focusable[0];
    const last = focusable[focusable.length - 1];
    if ((!event.shiftKey && document.activeElement === last) || (event.shiftKey && document.activeElement === first)) {
      event.preventDefault();
      (event.shiftKey ? last : first).focus();
    }
  });
  chain.addEventListener('change', () => { renderRerunTypes(''); renderConfig(null); });
  mode.addEventListener('change', updateRerunControls);
  rerunType.addEventListener('change', updateRerunControls);
  frequency.addEventListener('change', setFrequencyFields);

  form.addEventListener('submit', async (event) => {
    event.preventDefault();
    if (submitInFlight || configLoading || !configReady) return;
    clearError();
    if (!form.checkValidity()) {
      form.reportValidity();
      showError('请检查必填项后再保存。');
      return;
    }
    submitInFlight = true;
    syncSaveState();
    result.textContent = '正在保存…';
    try {
      await requestSave(idInput.value || null, payload(), fetchImplementation);
      result.textContent = '保存成功，正在刷新列表…';
      window.location.reload();
    } catch (error) {
      showError(error.message || '保存失败');
      result.textContent = '草稿已保留，请修改后重试。';
    } finally {
      submitInFlight = false;
      syncSaveState();
    }
  });

  rows().forEach((row) => {
    const button = row.querySelector('.schedule-switch');
    if (button) {
      button.dataset.labelBase = (button.getAttribute('aria-label') || '').split('，')[0];
      applySwitchState(button, button.getAttribute('aria-checked') === 'true');
    }
  });

  syncSaveState();
  return {
    loadRecords,
    open: (kind, record, trigger) => openDrawer(kind, kind === 'copy' ? copyDraft(record) : record, trigger),
    close: closeDrawer,
    toggle,
    getRecords: () => records,
    getState: () => ({ configLoading, configReady, submitInFlight }),
    setFetch: (nextFetch) => { fetchImplementation = nextFetch; }
  };
  }

  const api = { copyDraft, saveUrl, requestSave, applySwitchState, toggleSchedule, filterRows, createSchedulesController };
  window.AgentBridgeSchedules = api;
  api.controller = createSchedulesController(document, { window });
}());
