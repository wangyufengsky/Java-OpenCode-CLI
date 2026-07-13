(function () {
  'use strict';

  const stateLabels = {
    QUEUED: '排队中',
    RUNNING: '运行中',
    SUCCEEDED: '已成功',
    FAILED: '已失败'
  };

  const eventLabels = {
    QUEUED: '已排队',
    STARTED: '已开始',
    SUCCEEDED: '已成功',
    FAILED: '已失败',
    TASK_GROUP_STARTED: '任务组已开始',
    TASK_GROUP_SUCCEEDED: '任务组已完成',
    TASK_QUEUED: '任务已排队',
    TASK_RUNNING: '任务运行中',
    TASK_SUCCEEDED: '任务已成功',
    TASK_FAILED: '任务已失败'
  };

  const phaseLabels = {
    queued: '排队',
    started: '已开始',
    submitted: '已提交',
    running: '执行中',
    execution: '执行',
    complete: '已完成',
    failed: '已失败'
  };

  const eventTypes = [
    'QUEUED',
    'STARTED',
    'SUCCEEDED',
    'FAILED',
    'TASK_GROUP_STARTED',
    'TASK_GROUP_SUCCEEDED',
    'TASK_QUEUED',
    'TASK_RUNNING',
    'TASK_SUCCEEDED',
    'TASK_FAILED'
  ];

  function matchesEventFilter(event, filter) {
    const eventType = String(event.eventType || '').toUpperCase();
    if (filter === 'failed') return eventType.includes('FAILED');
    if (filter === 'task') return eventType.startsWith('TASK_');
    if (filter === 'stage') return !eventType.startsWith('TASK_') && !eventType.includes('FAILED');
    return true;
  }

  function createRunDetailController(root, dependencies) {
    const body = root.body || root.querySelector('body');
    const runId = body && body.dataset.runId;
    const events = root.querySelector('#events');
    if (!runId || !events) return null;

    const EventSourceConstructor = dependencies.EventSource;
    const fetchSnapshot = dependencies.fetch.bind(dependencies);
    const setIntervalFunction = dependencies.setInterval.bind(dependencies);
    const clearIntervalFunction = dependencies.clearInterval.bind(dependencies);
    const streamState = root.querySelector('#stream-state');
    const eventFilterControl = root.querySelector('#event-filter');
    const taskFilterControl = root.querySelector('#task-filter');
    const taskRows = root.querySelector('#task-rows');
    const seenIds = new Set(
      Array.from(events.querySelectorAll('li[data-event-id]')).map((item) => String(item.dataset.eventId))
    );
    let lastEventId = Array.from(seenIds).reduce((maximum, value) => {
      const eventId = Number(value);
      return Number.isFinite(eventId) ? Math.max(maximum, eventId) : maximum;
    }, 0);
    let eventFilter = events.dataset.eventFilter || 'all';
    let taskFilter = 'all';
    let consecutiveErrors = 0;
    let snapshotTimer = null;
    let recoveryGeneration = 0;
    let snapshotPendingGeneration = null;
    let snapshotAbortController = null;
    let healthyRefreshDirty = false;
    let sseOpen = false;
    let source = null;

    if (eventFilterControl) {
      eventFilterControl.value = eventFilter;
      eventFilterControl.addEventListener('change', () => {
        eventFilter = eventFilterControl.value;
        events.dataset.eventFilter = eventFilter;
        applyEventFilter();
        const url = new URL(dependencies.location.href);
        if (eventFilter === 'all') {
          url.searchParams.delete('eventFilter');
        } else {
          url.searchParams.set('eventFilter', eventFilter);
        }
        dependencies.history.replaceState(null, '', url);
      });
    }

    if (taskFilterControl) {
      taskFilterControl.addEventListener('change', () => {
        taskFilter = taskFilterControl.value;
        applyTaskFilter();
      });
    }

    function setStreamState(text) {
      if (streamState) streamState.textContent = text;
    }

    function appendEvent(messageOrEvent, refreshFromLiveEvent = false) {
      let event = messageOrEvent;
      if (messageOrEvent && typeof messageOrEvent.data === 'string') {
        try {
          event = JSON.parse(messageOrEvent.data);
        } catch (error) {
          setStreamState('消息格式异常，继续监听');
          return;
        }
      }
      if (!event || event.id === undefined || event.id === null) return;
      const eventId = String(event.id);
      if (seenIds.has(eventId)) return;
      seenIds.add(eventId);
      const numericId = Number(event.id);
      if (Number.isFinite(numericId)) lastEventId = Math.max(lastEventId, numericId);
      updateStateFromEvent(event);
      const item = createEventItem(event, eventId);
      events.appendChild(item);
      if (refreshFromLiveEvent && requiresSnapshotRefresh(event)) {
        queueHealthySnapshotRefresh();
      }
      if (!matchesEventFilter(event, eventFilter)) return;
      item.hidden = false;
      const emptyState = events.querySelector('[data-empty-state]');
      if (emptyState) emptyState.remove();
    }

    function requiresSnapshotRefresh(event) {
      const eventType = String(event.eventType || '').toUpperCase();
      return eventType.startsWith('TASK_') || eventType === 'SUCCEEDED' || eventType === 'FAILED';
    }

    function createEventItem(event, eventId) {
      const item = root.createElement('li');
      item.dataset.eventId = eventId;
      item.dataset.eventType = event.eventType || '';
      item.hidden = !matchesEventFilter(event, eventFilter);
      const time = root.createElement('time');
      time.textContent = event.createdAt || '';
      const type = root.createElement('strong');
      type.textContent = eventLabels[event.eventType] || event.eventType || '事件';
      const text = root.createElement('span');
      text.textContent = event.message || '';
      item.append(time, type, text);
      return item;
    }

    function updateStateFromEvent(event) {
      const stateByEventType = {
        QUEUED: 'QUEUED',
        STARTED: 'RUNNING',
        SUCCEEDED: 'SUCCEEDED',
        FAILED: 'FAILED'
      };
      const state = stateByEventType[event.eventType];
      if (!state) return;
      setText('#run-state', stateLabels[state] || state);
    }

    function applyEventFilter() {
      let visibleCount = 0;
      events.querySelectorAll('li[data-event-id]').forEach((item) => {
        const visible = matchesEventFilter({ eventType: item.dataset.eventType }, eventFilter);
        item.hidden = !visible;
        if (visible) visibleCount += 1;
      });
      renderEventEmptyState(visibleCount === 0);
    }

    function renderEventEmptyState(empty) {
      const current = events.querySelector('[data-empty-state]');
      if (!empty) {
        if (current) current.remove();
        return;
      }
      if (current) return;
      const item = root.createElement('li');
      item.dataset.emptyState = 'true';
      const message = root.createElement('span');
      message.className = 'empty';
      message.textContent = '当前筛选下暂无事件。';
      item.appendChild(message);
      events.appendChild(item);
    }

    function updateSnapshot(snapshot) {
      if (!snapshot || !snapshot.run || !snapshot.summary) return;
      const run = snapshot.run;
      const summary = snapshot.summary;
      setText('#run-state', stateLabels[run.state] || run.state || '状态未知');
      setText('#run-task-progress', `${summary.succeededTasks || 0}/${summary.totalTasks || 0}`);
      setText('#run-failed-tasks', String(summary.failedTasks || 0));
      setText('#run-duration', `${summary.durationSeconds || 0} 秒`);
      updateFailureSummary(run, summary);
      updateFailureActions(run, snapshot.rerunAction);
      renderTasks(Array.isArray(snapshot.tasks) ? snapshot.tasks : []);
      (Array.isArray(snapshot.events) ? snapshot.events : []).forEach((event) => appendEvent(event, false));
      applyEventFilter();
    }

    function updateFailureSummary(run, summary) {
      const panel = root.querySelector('#failure-summary');
      if (!panel) return;
      const visible = run.state === 'FAILED'
        || hasText(summary.failureMessage)
        || hasText(summary.failedTaskKey)
        || hasText(summary.lastErrorMessage);
      panel.hidden = !visible;
      setText('#failure-message', summary.failureMessage || '未记录');
      setText('#failed-task-key', summary.failedTaskKey || '未记录');
      setText('#last-error-message', summary.lastErrorMessage || '未记录');
    }

    function updateFailureActions(run, action) {
      const container = root.querySelector('#failure-actions');
      const rerunLink = root.querySelector('#rerun-failed-task');
      const unavailableReason = root.querySelector('#rerun-unavailable-reason');
      const configureLink = root.querySelector('#configure-new-run');
      const visible = Boolean(action && action.visible);
      const available = visible && Boolean(action.available);

      if (container) container.hidden = !visible;
      if (rerunLink) {
        rerunLink.hidden = !available;
        rerunLink.href = '';
        if (available) {
          const url = new URL('/runs/new', dependencies.location.href);
          url.searchParams.set('chainId', run.chainId || '');
          url.searchParams.set('mode', 'rerun');
          url.searchParams.set('rerunType', action.rerunType || '');
          url.searchParams.set('rerunId', action.rerunId || '');
          rerunLink.href = `${url.pathname}${url.search}`;
        }
      }
      if (unavailableReason) {
        unavailableReason.hidden = !visible || available;
        unavailableReason.textContent = visible && !available ? (action.reason || '无法安全确定重跑类型') : '';
      }
      if (configureLink) {
        configureLink.hidden = !visible || available;
        configureLink.href = '';
        if (visible && !available) {
          const url = new URL('/runs/new', dependencies.location.href);
          url.searchParams.set('chainId', run.chainId || '');
          configureLink.href = `${url.pathname}${url.search}`;
        }
      }
    }

    function renderTasks(tasks) {
      if (!taskRows) return;
      taskRows.replaceChildren();
      const sortedTasks = tasks.slice().sort((left, right) => {
        const leftFailed = String(left.state).toUpperCase() === 'FAILED' ? 0 : 1;
        const rightFailed = String(right.state).toUpperCase() === 'FAILED' ? 0 : 1;
        return leftFailed - rightFailed || String(left.taskKey || '').localeCompare(String(right.taskKey || ''));
      });
      sortedTasks.forEach((task) => taskRows.appendChild(createTaskRow(task)));
      applyTaskFilter();
    }

    function createTaskRow(task) {
      const row = root.createElement('tr');
      const normalizedState = String(task.state || '').toUpperCase();
      row.dataset.taskKey = task.taskKey || '';
      row.dataset.taskState = normalizedState;
      const name = root.createElement('td');
      name.dataset.taskName = '';
      name.textContent = task.taskName || task.taskKey || '未命名任务';
      const state = root.createElement('td');
      state.dataset.taskStateLabel = '';
      state.textContent = stateLabels[normalizedState] || task.state || '状态未知';
      const phase = root.createElement('td');
      phase.dataset.taskPhase = '';
      phase.textContent = phaseLabels[String(task.phase || '').toLowerCase()] || task.phase || '状态未知';
      row.append(name, state, phase);
      return row;
    }

    function applyTaskFilter() {
      if (!taskRows) return;
      let visibleCount = 0;
      taskRows.querySelectorAll('tr[data-task-key]').forEach((row) => {
        const visible = taskFilter === 'all' || row.dataset.taskState === taskFilter;
        row.hidden = !visible;
        if (visible) visibleCount += 1;
      });
      renderTaskEmptyState(visibleCount === 0);
    }

    function renderTaskEmptyState(empty) {
      if (!taskRows) return;
      const current = taskRows.querySelector('[data-task-empty]');
      if (!empty) {
        if (current) current.remove();
        return;
      }
      if (current) return;
      const row = root.createElement('tr');
      row.dataset.taskEmpty = 'true';
      const cell = root.createElement('td');
      cell.colSpan = 3;
      cell.className = 'empty';
      cell.textContent = taskFilter === 'all' ? '暂无任务事件。' : '当前筛选下暂无任务。';
      row.appendChild(cell);
      taskRows.appendChild(row);
    }

    async function pollSnapshot(generation = recoveryGeneration, updateStreamState = true) {
      if (generation !== recoveryGeneration || snapshotPendingGeneration === generation) return;
      snapshotPendingGeneration = generation;
      if (!updateStreamState) healthyRefreshDirty = false;
      const AbortControllerConstructor = dependencies.AbortController;
      const abortController = AbortControllerConstructor ? new AbortControllerConstructor() : null;
      snapshotAbortController = abortController;
      try {
        const response = await fetchSnapshot(`/api/runs/${encodeURIComponent(runId)}/snapshot?afterEventId=${lastEventId}`, {
          headers: { Accept: 'application/json' },
          ...(abortController ? { signal: abortController.signal } : {})
        });
        if (generation !== recoveryGeneration) return;
        if (!response.ok) throw new Error(`snapshot ${response.status}`);
        const snapshot = await response.json();
        if (generation !== recoveryGeneration) return;
        updateSnapshot(snapshot);
        if (updateStreamState) setStreamState('定时刷新');
      } catch (error) {
        if (generation !== recoveryGeneration) return;
        if (updateStreamState) setStreamState('刷新失败，继续重试');
      } finally {
        const refreshAgain = !updateStreamState
          && generation === recoveryGeneration
          && sseOpen
          && snapshotTimer === null
          && healthyRefreshDirty;
        if (generation === recoveryGeneration && snapshotPendingGeneration === generation) {
          snapshotPendingGeneration = null;
          snapshotAbortController = null;
        }
        if (refreshAgain) {
          healthyRefreshDirty = false;
          void pollSnapshot(generation, false);
        }
      }
    }

    function queueHealthySnapshotRefresh() {
      if (!sseOpen || snapshotTimer !== null) return;
      const generation = recoveryGeneration;
      if (snapshotPendingGeneration === generation) {
        healthyRefreshDirty = true;
        return;
      }
      void pollSnapshot(generation, false);
    }

    function invalidateSnapshotRequest() {
      recoveryGeneration += 1;
      if (snapshotAbortController) snapshotAbortController.abort();
      snapshotAbortController = null;
      snapshotPendingGeneration = null;
      healthyRefreshDirty = false;
    }

    function startSnapshotPolling() {
      if (snapshotTimer !== null) return;
      invalidateSnapshotRequest();
      const generation = recoveryGeneration;
      setStreamState('定时刷新');
      void pollSnapshot(generation);
      snapshotTimer = setIntervalFunction(() => pollSnapshot(generation), 5000);
    }

    function stopSnapshotPolling() {
      invalidateSnapshotRequest();
      if (snapshotTimer !== null) {
        clearIntervalFunction(snapshotTimer);
        snapshotTimer = null;
      }
    }

    function connect() {
      if (!EventSourceConstructor) {
        startSnapshotPolling();
        return;
      }
      source = new EventSourceConstructor(`/api/runs/${encodeURIComponent(runId)}/events`);
      source.onopen = () => {
        consecutiveErrors = 0;
        stopSnapshotPolling();
        sseOpen = true;
        setStreamState('实时');
      };
      source.onerror = () => {
        if (sseOpen) {
          sseOpen = false;
          invalidateSnapshotRequest();
        }
        consecutiveErrors += 1;
        setStreamState('正在重连');
        if (consecutiveErrors >= 2) startSnapshotPolling();
      };
      eventTypes.forEach((type) => source.addEventListener(type, (message) => appendEvent(message, true)));
    }

    function setText(selector, value) {
      const element = root.querySelector(selector);
      if (element) element.textContent = value;
    }

    applyEventFilter();
    applyTaskFilter();
    connect();

    return {
      appendEvent,
      applyEventFilter,
      applyTaskFilter,
      pollSnapshot,
      startSnapshotPolling,
      stopSnapshotPolling,
      updateSnapshot,
      getState: () => ({
        consecutiveErrors,
        lastEventId,
        polling: snapshotTimer !== null,
        recoveryGeneration,
        sseOpen,
        seenIds: new Set(seenIds)
      }),
      getSource: () => source
    };
  }

  function hasText(value) {
    return value !== null && value !== undefined && String(value).trim() !== '';
  }

  window.AgentBridgeRunDetail = { createRunDetailController, matchesEventFilter };
  window.AgentBridgeRunDetail.controller = createRunDetailController(document, window);
}());
