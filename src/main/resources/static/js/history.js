(() => {
  const form = document.querySelector('#history-filter-form');
  if (!form) return;

  const createdFrom = form.elements.namedItem('from');
  const createdUntil = form.elements.namedItem('until');
  const page = form.elements.namedItem('page');
  if (!(createdFrom instanceof HTMLInputElement) || !(createdUntil instanceof HTMLInputElement)) return;

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
})();
