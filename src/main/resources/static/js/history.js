(() => {
  const form = document.querySelector('#history-filter-form');
  if (!form) return;

  const createdFrom = form.elements.namedItem('from');
  const createdUntil = form.elements.namedItem('until');
  if (!(createdFrom instanceof HTMLInputElement) || !(createdUntil instanceof HTMLInputElement)) return;

  function syncDateRange() {
    createdUntil.min = createdFrom.value;
    createdFrom.max = createdUntil.value;
  }

  createdFrom.addEventListener('change', syncDateRange);
  createdUntil.addEventListener('change', syncDateRange);
  syncDateRange();
})();
