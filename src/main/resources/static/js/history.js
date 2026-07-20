(() => {
  const form = document.querySelector('#history-filter-form');
  if (form) {
    const createdFrom = form.elements.namedItem('from');
    const createdUntil = form.elements.namedItem('until');
    const page = form.elements.namedItem('page');
    if (createdFrom instanceof HTMLInputElement && createdUntil instanceof HTMLInputElement) {
      function syncDateRange() {
        createdUntil.min = createdFrom.value;
        createdFrom.max = createdUntil.value;
      }

      createdFrom.addEventListener('change', syncDateRange);
      createdUntil.addEventListener('change', syncDateRange);
      ['q', 'state', 'chainId', 'from', 'until'].forEach((name) => {
        const field = form.elements.namedItem(name);
        if (!field || !page) return;
        field.addEventListener('input', () => { page.value = '1'; });
        field.addEventListener('change', () => { page.value = '1'; });
      });
      syncDateRange();
    }
  }

  const status = document.querySelector('#history-action-status');
  document.querySelectorAll('[data-history-action]').forEach((button) => {
    button.addEventListener('click', (event) => handleAction(event, button));
  });

  async function handleAction(event, button) {
    event.preventDefault();
    const action = button.dataset.historyAction;
    const runId = button.dataset.runId;
    if (action === 'delete' && !window.confirm(`确认清理运行 #${runId} 的历史记录？`)) return;
    if (action === 'clear-all' && !window.confirm('确认清理全部已完成和已失败的历史记录？运行中与排队中的记录会保留。')) return;

    const originalText = button.textContent;
    button.disabled = true;
    if (status) status.textContent = action === 'rerun' ? '正在创建重跑任务…' : '正在清理历史记录…';
    try {
      if (action === 'rerun') {
        const result = await request(`/api/runs/${encodeURIComponent(runId)}/rerun`, 'POST');
        window.location.assign(`/runs/${result.id}`);
        return;
      }
      if (action === 'delete') {
        await request(`/api/runs/${encodeURIComponent(runId)}`, 'DELETE');
        window.location.reload();
        return;
      }
      if (action === 'clear-all') {
        await request('/api/runs', 'DELETE');
        window.location.assign('/history');
      }
    } catch (error) {
      button.disabled = false;
      button.textContent = originalText;
      if (status) status.textContent = error.message || '操作失败，请稍后重试。';
    }
  }

  async function request(url, method) {
    const response = await fetch(url, { method });
    if (response.ok) return response.json();
    let message = '操作失败，请稍后重试。';
    try {
      const body = await response.json();
      if (body.error) message = body.error;
    } catch (_) {
      // Keep the stable fallback when the server did not return JSON.
    }
    throw new Error(message);
  }
})();
