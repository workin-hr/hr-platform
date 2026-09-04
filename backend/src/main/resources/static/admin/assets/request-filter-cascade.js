(function () {
  function parseJson(el, attr, fallback) {
    try {
      return JSON.parse(el.getAttribute(attr) || fallback);
    } catch {
      return JSON.parse(fallback);
    }
  }

  function initRequestFilters(form) {
    const companySelect = form.querySelector('[data-filter-company]');
    const typeSelect = form.querySelector('[data-filter-request-type]');
    if (!typeSelect) {
      return;
    }

    const typesByCompany = parseJson(form, 'data-request-types-by-company', '{}');
    const filterAll = form.getAttribute('data-filter-all') || '—';
    const selectCompanyMsg = form.getAttribute('data-select-company-msg') || filterAll;
    let selectedType = form.getAttribute('data-selected-request-type') || '0';

    function renderTypes(companyId) {
      typeSelect.innerHTML = '';
      const allOpt = document.createElement('option');
      allOpt.value = '0';
      allOpt.textContent = filterAll;
      typeSelect.appendChild(allOpt);

      if (!companySelect) {
        typeSelect.disabled = false;
        return;
      }

      if (!companyId || companyId === '') {
        typeSelect.disabled = true;
        const hint = document.createElement('option');
        hint.value = '0';
        hint.disabled = true;
        hint.textContent = selectCompanyMsg;
        typeSelect.appendChild(hint);
        return;
      }

      typeSelect.disabled = false;
      (typesByCompany[String(companyId)] || []).forEach(function (t) {
        const opt = document.createElement('option');
        opt.value = String(t.id);
        opt.textContent = t.name;
        typeSelect.appendChild(opt);
      });

      if (selectedType && selectedType !== '0') {
        typeSelect.value = String(selectedType);
      }
      selectedType = '';
    }

    companySelect.addEventListener('change', function () {
      selectedType = '';
      renderTypes(companySelect.value);
    });

    renderTypes(companySelect.value);
  }

  document.querySelectorAll('[data-request-filters]').forEach(initRequestFilters);
})();
