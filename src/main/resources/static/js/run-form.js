(function initializeRunForm() {
  const common = window.ConsoleCommon;
  const runForm = document.querySelector('#run-form');
  if (!common || !runForm) return;

  const { chainConfigDefinitions, groupDefinitions, rerunTypeDefinitions } = common;
  const chainSelect = document.querySelector('#chainId');
  const modeSelect = document.querySelector('#mode');
  const rerunTypeSelect = document.querySelector('#rerunType');
  const rerunIdInput = document.querySelector('#rerunId');
  const runDateInput = document.querySelector('#runDate');
  const configFields = document.querySelector('#chain-config-fields');
  const configDescription = document.querySelector('#chain-config-description');
  const submitButton = document.querySelector('#submit-run');
  const submitResult = document.querySelector('#submit-result');
  const copyLoadStatus = document.querySelector('#copy-load-status');
  const preflightAlert = document.querySelector('#preflight-alert');
  const preflightState = new Map();
  let defaults = {};
  let renderSequence = 0;
  let copiedConfigPending = Boolean(runForm.dataset.copyFrom);
  let configLoading = false;
  let configReady = false;
  let submitInFlight = false;
  let preflightAlertControlId = null;

  chainSelect.value = runForm.dataset.selectedChain || chainSelect.value;
  modeSelect.value = runForm.dataset.selectedMode || modeSelect.value;
  if (runForm.dataset.selectedRunDate) runDateInput.value = runForm.dataset.selectedRunDate;
  renderRerunTypeOptions(chainSelect.value, runForm.dataset.selectedRerunType);
  rerunIdInput.value = runForm.dataset.selectedRerunId || rerunIdInput.value;
  updateRerunFields();
  updateWorkflowCards();
  renderConfigFields(chainSelect.value);
  updateSummary();

  chainSelect.addEventListener('change', async () => {
    copiedConfigPending = false;
    renderRerunTypeOptions(chainSelect.value);
    updateWorkflowCards();
    const url = new URL(window.location.href);
    url.searchParams.set('chainId', chainSelect.value);
    url.searchParams.delete('copyFrom');
    history.replaceState(null, '', url);
    await renderConfigFields(chainSelect.value);
    updateSummary();
  });
  modeSelect.addEventListener('change', () => {
    updateRerunFields();
    updateSummary();
  });
  rerunTypeSelect.addEventListener('change', () => {
    updateRerunFields();
    updateSummary();
  });
  rerunIdInput.addEventListener('input', () => {
    validateControl(rerunIdInput, '重跑编号');
    updateSummary();
  });
  runDateInput.addEventListener('input', updateSummary);

  document.querySelectorAll('[data-chain-card]').forEach((card) => {
    card.addEventListener('click', (event) => {
      event.preventDefault();
      if (card.dataset.chainCard === chainSelect.value) return;
      chainSelect.value = card.dataset.chainCard;
      chainSelect.dispatchEvent(new Event('change'));
    });
  });

  async function renderConfigFields(chainId) {
    const sequence = ++renderSequence;
    clearPreflightStates();
    const copySnapshot = copiedConfigPending ? runForm.dataset.copyFrom : '';
    copiedConfigPending = false;
    const definition = chainConfigDefinitions[chainId];
    configReady = false;
    setConfigLoading(true);
    configFields.replaceChildren();
    configDescription.textContent = definition ? definition.description : '';
    copyLoadStatus.textContent = copySnapshot ? '正在载入复制配置…' : '';
    if (!definition) {
      setConfigLoading(false);
      return;
    }

    const loading = document.createElement('p');
    loading.className = 'muted';
    loading.textContent = '正在读取链路默认配置…';
    configFields.appendChild(loading);
    let nextDefaults = {};
    try {
      nextDefaults = await loadDefaults(chainId);
    } catch (error) {
      if (sequence !== renderSequence || chainSelect.value !== chainId) return;
      configDescription.textContent = `${definition.description}（默认配置读取失败，可手动填写后提交）`;
    }
    let values = { ...nextDefaults };
    if (copySnapshot) {
      try {
        const copied = await loadCopiedConfig(copySnapshot);
        if (copied.chainId === chainId) values = { ...values, ...(copied.config || {}) };
        if (sequence === renderSequence && chainSelect.value === chainId) {
          copyLoadStatus.textContent = '原运行配置已载入；提交时将创建新运行。';
        }
      } catch (error) {
        if (sequence === renderSequence && chainSelect.value === chainId) {
          copyLoadStatus.textContent = '复制配置读取失败，已保留链路默认值。';
        }
      }
    }
    if (sequence !== renderSequence || chainSelect.value !== chainId) return;

    defaults = nextDefaults;
    configFields.replaceChildren();
    groupDefinitions.forEach((groupDefinition) => {
      const fields = definition.fields.filter((item) => item.group === groupDefinition.group);
      if (fields.length === 0) return;
      const group = document.createElement('details');
      group.className = 'panel config-group';
      group.open = groupDefinition.group === 'project';

      const heading = document.createElement('summary');
      const title = document.createElement('strong');
      title.textContent = groupDefinition.label;
      const description = document.createElement('span');
      description.className = 'muted';
      description.textContent = ` · ${groupDefinition.description}`;
      heading.append(title, description);
      group.appendChild(heading);

      const grid = document.createElement('div');
      grid.className = 'config-fields';
      fields.forEach((definitionField) => {
        const value = Object.prototype.hasOwnProperty.call(values, definitionField.key)
          ? values[definitionField.key] : undefined;
        grid.appendChild(createConfigField(definitionField, value, sequence, chainId));
      });
      group.appendChild(grid);
      configFields.appendChild(group);
    });
    definition.fields.filter(shouldPreflight).forEach((definitionField) => {
      const control = document.querySelector(`#${fieldId(definitionField.key)}`);
      const validation = document.querySelector(`#${fieldId(definitionField.key)}-validation`);
      if (control && validation && readValue(control, definitionField.type)) {
        schedulePreflight(control, validation, sequence, chainId);
      }
    });
    configReady = Boolean(configFields.querySelector('[data-config-key]'));
    setConfigLoading(false);
    updateSummary();
  }

  function setConfigLoading(loading) {
    configLoading = loading;
    configFields.setAttribute('aria-busy', String(loading));
    syncSubmitState();
  }

  function syncSubmitState() {
    submitButton.disabled = submitInFlight || configLoading || !configReady;
  }

  async function loadDefaults(chainId) {
    const response = await fetch(`/api/chains/${encodeURIComponent(chainId)}/defaults`);
    if (!response.ok) throw new Error('无法读取链路默认配置');
    const body = await response.json();
    return body.defaults || {};
  }

  async function loadCopiedConfig(sourceRunId) {
    const response = await fetch(`/api/runs/${encodeURIComponent(sourceRunId)}/config`);
    if (!response.ok) throw new Error('无法读取复制运行配置');
    return response.json();
  }

  function createConfigField(definitionField, value, generation, chainId) {
    const wrapper = document.createElement('label');
    wrapper.className = `config-field config-field-${definitionField.type}`;
    wrapper.htmlFor = fieldId(definitionField.key);

    const title = document.createElement('span');
    title.className = 'config-field-title';
    title.textContent = definitionField.required ? `${definitionField.label} *` : definitionField.label;
    const control = createControl(definitionField, value);
    const description = document.createElement('small');
    description.id = `${control.id}-description`;
    description.textContent = definitionField.description;
    const validation = document.createElement('small');
    validation.id = `${control.id}-validation`;
    validation.className = 'form-message';
    validation.setAttribute('aria-live', 'polite');
    const actions = document.createElement('span');
    const badge = document.createElement('span');
    badge.className = 'status-badge queued';
    badge.textContent = `默认：${formatDefault(defaults[definitionField.key])}`;
    const restore = document.createElement('button');
    restore.type = 'button';
    restore.className = 'button';
    restore.textContent = '恢复默认值';
    restore.addEventListener('click', () => {
      setControlValue(control, definitionField.type, defaults[definitionField.key]);
      validateControl(control, definitionField.label);
      if (shouldPreflight(definitionField)) schedulePreflight(control, validation, generation, chainId);
      updateSummary();
    });
    actions.append(badge, document.createTextNode(' '), restore);
    control.setAttribute('aria-describedby', `${description.id} ${validation.id}`);
    control.addEventListener('input', () => {
      validateControl(control, definitionField.label);
      if (shouldPreflight(definitionField)) schedulePreflight(control, validation, generation, chainId);
      updateSummary();
    });
    control.addEventListener('change', updateSummary);
    wrapper.append(title, control, description, actions, validation);
    return wrapper;
  }

  function createControl(definitionField, value) {
    let control;
    if (definitionField.type === 'textarea' || definitionField.type === 'list') {
      control = document.createElement('textarea');
      control.rows = definitionField.type === 'list' ? 3 : 4;
    } else {
      control = document.createElement('input');
      control.type = definitionField.type === 'checkbox' ? 'checkbox'
        : definitionField.type === 'number' ? 'number' : 'text';
    }
    control.id = fieldId(definitionField.key);
    control.name = definitionField.key;
    control.required = definitionField.required;
    control.dataset.configKey = definitionField.key;
    control.dataset.configType = definitionField.type;
    setControlValue(control, definitionField.type, value);
    return control;
  }

  function setControlValue(control, type, value) {
    if (type === 'checkbox') {
      control.checked = Boolean(value);
    } else if (type === 'list') {
      control.value = Array.isArray(value) ? value.join('\n') : value ?? '';
    } else {
      control.value = value ?? '';
    }
  }

  function validateControl(control, label) {
    const message = control.required && !String(readValue(control, control.dataset.configType) ?? '').trim()
      ? `请填写${label}` : '';
    control.setCustomValidity(message);
    const validation = document.querySelector(`#${control.id}-validation`);
    if (validation) validation.textContent = message;
    return message === '';
  }

  function shouldPreflight(definitionField) {
    return definitionField.key.endsWith('.repo')
      || definitionField.key.endsWith('-root')
      || definitionField.type === 'path';
  }

  function schedulePreflight(control, status, generation = renderSequence, chainId = chainSelect.value) {
    const existing = preflightState.get(control.id);
    if (existing) {
      cancelPreflightState(existing);
    }
    const state = { timer: null, controller: null };
    preflightState.set(control.id, state);
    const value = control.value.trim();
    if (!value) {
      if (isCurrentPreflight(control, generation, chainId, state)) {
        if (!control.validationMessage) status.textContent = '';
        clearPreflightAlert(control.id);
      }
      releasePreflightState(control.id, state);
      return;
    }
    if (!isCurrentPreflight(control, generation, chainId, state)) return;
    status.textContent = '等待检查路径...';
    setPreflightAlert('路径预检等待中…', 'neutral', control.id);
    state.timer = window.setTimeout(async () => {
      if (!isCurrentPreflight(control, generation, chainId, state)) {
        releasePreflightState(control.id, state);
        return;
      }
      const controller = new AbortController();
      state.controller = controller;
      if (!isCurrentPreflight(control, generation, chainId, state)) {
        controller.abort();
        releasePreflightState(control.id, state);
        return;
      }
      status.textContent = '正在检查路径...';
      setPreflightAlert('正在检查本机路径…', 'neutral', control.id);
      try {
        const response = await fetch(`/api/path-preflight?path=${encodeURIComponent(value)}`, { signal: controller.signal });
        if (!isCurrentPreflight(control, generation, chainId, state)) return;
        if (!response.ok) throw new Error('路径预检服务不可用');
        const body = await response.json();
        if (!isCurrentPreflight(control, generation, chainId, state)) return;
        status.textContent = body.message || (body.accessible ? '路径可读' : '路径暂不可访问');
        setPreflightAlert(status.textContent, body.accessible ? 'success' : 'warning', control.id);
      } catch (error) {
        if (isCurrentPreflight(control, generation, chainId, state) && error.name !== 'AbortError') {
          status.textContent = '无法完成路径预检；仍可继续提交。';
          setPreflightAlert(status.textContent, 'warning', control.id);
        }
      } finally {
        releasePreflightState(control.id, state);
      }
    }, 400);
  }

  function clearPreflightStates() {
    preflightState.forEach((state) => cancelPreflightState(state));
    preflightState.clear();
    preflightAlertControlId = null;
    setPreflightAlert('');
  }

  function cancelPreflightState(state) {
    window.clearTimeout(state.timer);
    state.controller?.abort();
  }

  function releasePreflightState(controlId, state) {
    if (preflightState.get(controlId) === state) preflightState.delete(controlId);
  }

  function isCurrentPreflight(control, generation, chainId, state) {
    return generation === renderSequence
      && chainId === chainSelect.value
      && document.querySelector(`#${control.id}`) === control
      && preflightState.get(control.id) === state;
  }

  function clearPreflightAlert(controlId) {
    if (preflightAlertControlId === controlId) {
      preflightAlertControlId = null;
      setPreflightAlert('');
    }
  }

  function setPreflightAlert(message, tone = 'neutral', controlId = null) {
    if (!preflightAlert) return;
    preflightAlert.textContent = message;
    preflightAlert.dataset.tone = tone;
    if (message) preflightAlertControlId = controlId;
  }

  function renderRerunTypeOptions(chainId, selectedValue = null) {
    const options = rerunTypeDefinitions[chainId] || [];
    rerunTypeSelect.replaceChildren();
    options.forEach((option) => {
      const item = document.createElement('option');
      item.value = option.value;
      item.textContent = option.label;
      rerunTypeSelect.appendChild(item);
    });
    if (selectedValue && options.some((option) => option.value === selectedValue)) {
      rerunTypeSelect.value = selectedValue;
    }
    updateRerunFields();
  }

  function selectedRerunTypeDefinition() {
    return (rerunTypeDefinitions[chainSelect.value] || [])
      .find((option) => option.value === rerunTypeSelect.value) || null;
  }

  function updateRerunFields() {
    const rerunMode = modeSelect.value === 'rerun';
    const typeDefinition = selectedRerunTypeDefinition();
    rerunTypeSelect.required = rerunMode;
    const requiresId = rerunMode && typeDefinition && typeDefinition.requiresId;
    rerunIdInput.disabled = !requiresId;
    rerunIdInput.required = Boolean(requiresId);
    rerunIdInput.placeholder = typeDefinition ? typeDefinition.idPlaceholder : '多个编号用英文逗号分隔';
    if (!requiresId) rerunIdInput.value = '';
  }

  function updateWorkflowCards() {
    document.querySelectorAll('[data-chain-card]').forEach((card) => {
      if (card.dataset.chainCard === chainSelect.value) card.setAttribute('aria-current', 'true');
      else card.removeAttribute('aria-current');
    });
  }

  function collectConfig(chainId) {
    const definition = chainConfigDefinitions[chainId];
    if (!definition) return {};
    return Object.fromEntries(definition.fields.map((definitionField) => {
      const control = document.querySelector(`[data-config-key="${definitionField.key}"]`);
      return [definitionField.key, readValue(control, definitionField.type)];
    }));
  }

  function readValue(control, type) {
    if (!control) return null;
    if (type === 'checkbox') return control.checked;
    if (type === 'number') return control.value === '' ? null : Number(control.value);
    if (type === 'list') return control.value.split('\n').map((line) => line.trim()).filter(Boolean);
    return control.value;
  }

  function fieldId(key) {
    return `config-${key.replaceAll('.', '-').replaceAll('/', '-')}`;
  }

  function formatDefault(value) {
    if (Array.isArray(value)) return value.length === 0 ? '空列表' : value.join('，');
    if (value === undefined || value === null || value === '') return '空';
    if (value === true) return '是';
    if (value === false) return '否';
    return String(value);
  }

  function updateSummary() {
    const definition = chainConfigDefinitions[chainSelect.value];
    document.querySelector('#summary-chain').textContent = definition?.label || chainSelect.value;
    document.querySelector('#summary-project').textContent = firstSummaryValue([
      'project.repo', 'paths.repo', 'new-project', 'java-root', 'project.id', 'project.name'
    ]) || '等待填写';
    document.querySelector('#summary-scope').textContent = firstSummaryValue([
      'source.paths', 'source.package-paths', 'source.include', 'git.include', 'transaction-plan-dir',
      'service-identify', 'xml-root'
    ]) || defaultScopeSummary(chainSelect.value);
    document.querySelector('#summary-validation').textContent = validationSummary();
    document.querySelector('#summary-agentbridge').textContent = agentBridgeSummary();
    document.querySelector('#summary-output').textContent = firstSummaryValue([
      'paths.out', 'local-out', 'out'
    ]) || '等待填写';
    const readyBadge = document.querySelector('#summary-ready');
    readyBadge.textContent = configReady ? '已就绪' : '配置中';
    readyBadge.className = `status-badge ${configReady ? 'success' : 'queued'}`;
  }

  function firstSummaryValue(keys) {
    for (const key of keys) {
      const definitionField = chainConfigDefinitions[chainSelect.value]?.fields
        .find((item) => item.key === key);
      if (!definitionField) continue;
      const value = readValue(document.querySelector(`[data-config-key="${key}"]`), definitionField.type);
      if (value === null || value === '' || (Array.isArray(value) && value.length === 0)) continue;
      return Array.isArray(value) ? value.join('，') : String(value);
    }
    return '';
  }

  function defaultScopeSummary(chainId) {
    if (chainId === 'project-unit-test-generation') return '全量扫描';
    if (chainId === 'git-code-contribution-report' || chainId === 'weekly-engineering-report') return '全部仓库';
    return '按工作流默认范围';
  }

  function validationSummary() {
    const requireCoverage = document.querySelector('[data-config-key="test.require-coverage"]');
    if (!requireCoverage?.checked) return '失败即停止';
    const threshold = firstSummaryValue(['test.coverage-threshold-percent']);
    return threshold ? `失败即停止 · 覆盖率 ${threshold}%` : '失败即停止 · 要求覆盖率';
  }

  function agentBridgeSummary() {
    const attempts = firstSummaryValue(['agentbridge.max-attempts']);
    const timeout = firstSummaryValue(['agentbridge.timeout-minutes']);
    if (attempts && timeout) return `最多 ${attempts} 轮 · ${timeout} 分钟超时`;
    if (attempts) return `自动重试 · 最多 ${attempts} 轮`;
    if (timeout) return `自动重连 · ${timeout} 分钟超时`;
    return '自动重连 · 增量事件恢复';
  }

  runForm.addEventListener('submit', async (event) => {
    event.preventDefault();
    if (submitInFlight) return;
    if (configLoading) {
      submitResult.textContent = '配置仍在载入，请稍候。';
      return;
    }
    if (!configReady) {
      submitResult.textContent = '配置字段尚未就绪，请重新选择工作流后再试。';
      return;
    }
    runForm.querySelectorAll('[data-config-key]').forEach((control) => {
      const definition = chainConfigDefinitions[chainSelect.value].fields
        .find((item) => item.key === control.dataset.configKey);
      validateControl(control, definition?.label || control.name);
    });
    if (!runForm.checkValidity()) {
      const firstInvalid = runForm.querySelector(':invalid');
      if (firstInvalid) firstInvalid.focus();
      runForm.reportValidity();
      submitResult.textContent = '请先完成标记的必填项。';
      return;
    }

    submitInFlight = true;
    runForm.setAttribute('aria-busy', 'true');
    syncSubmitState();
    submitResult.textContent = '正在提交…';
    const payload = {
      chainId: chainSelect.value,
      mode: modeSelect.value,
      rerunType: modeSelect.value === 'rerun' ? rerunTypeSelect.value : '',
      rerunId: modeSelect.value === 'rerun' && selectedRerunTypeDefinition()?.requiresId ? rerunIdInput.value : '',
      runDate: runDateInput.value || null,
      config: collectConfig(chainSelect.value)
    };
    try {
      const response = await fetch('/api/runs', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(payload)
      });
      const body = await response.json().catch(() => ({}));
      if (response.ok && body.id) {
        window.location.href = `/runs/${body.id}`;
        return;
      }
      submitResult.textContent = body.error || '提交失败，请检查配置后重试。';
    } catch (error) {
      submitResult.textContent = '网络请求失败，已保留当前填写内容。';
    } finally {
      submitInFlight = false;
      runForm.setAttribute('aria-busy', 'false');
      syncSubmitState();
    }
  });
}());
