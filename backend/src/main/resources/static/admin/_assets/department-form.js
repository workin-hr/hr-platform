(function () {
  const form = document.querySelector('[data-org-dept-form]');
  if (!form) {
    return;
  }

  const companySelect = form.querySelector('[data-dept-company]');
  const panel = form.querySelector('[data-dept-branches-panel]');
  const picker = form.querySelector('[data-dept-branches-picker]');
  const toolbar = form.querySelector('[data-dept-toolbar]');
  const searchInput = form.querySelector('[data-dept-search]');
  const selectAllBtn = form.querySelector('[data-dept-select-all]');
  const clearAllBtn = form.querySelector('[data-dept-clear-all]');
  const countBadge = form.querySelector('[data-dept-count-badge]');

  if (!picker) {
    return;
  }

  let branchesByCompany = {};
  try {
    branchesByCompany = JSON.parse(form.getAttribute('data-branches') || '{}');
  } catch {
    branchesByCompany = {};
  }

  let selectedIds = [];
  try {
    selectedIds = JSON.parse(form.getAttribute('data-selected-branches') || '[]');
  } catch {
    selectedIds = [];
  }
  selectedIds = selectedIds.map(String);

  const selectCompanyMsg = form.getAttribute('data-select-company-msg') || '';
  const fixedCompany = form.getAttribute('data-fixed-company') || '';
  const labelSelectAll = form.getAttribute('data-label-select-all') || 'Select all';
  const labelClearAll = form.getAttribute('data-label-clear-all') || 'Clear';
  const labelSearch = form.getAttribute('data-label-search') || '';
  const labelSelectedOne = form.getAttribute('data-label-selected-one') || '';
  const labelSelectedMany = form.getAttribute('data-label-selected-many') || '';

  function getGrid() {
    return picker.querySelector('[data-dept-branches-grid]');
  }

  function getCards() {
    return Array.from(picker.querySelectorAll('.dept-branch-card'));
  }

  function getCheckedIds() {
    return getCards()
      .filter(function (card) {
        return card.querySelector('input')?.checked;
      })
      .map(function (card) {
        return card.querySelector('input').value;
      });
  }

  function updateCount() {
    if (!countBadge) {
      return;
    }
    const n = getCheckedIds().length;
    if (n === 0) {
      countBadge.hidden = true;
      return;
    }
    countBadge.hidden = false;
    const label = n === 1 ? labelSelectedOne : labelSelectedMany;
    countBadge.textContent = n + ' ' + label;
  }

  function bindCard(card) {
    const input = card.querySelector('input');
    if (!input) {
      return;
    }
    function syncSelected() {
      card.classList.toggle('is-selected', input.checked);
      updateCount();
    }
    input.addEventListener('change', syncSelected);
    card.addEventListener('click', function (e) {
      if (e.target === input) {
        return;
      }
      e.preventDefault();
      input.checked = !input.checked;
      syncSelected();
    });
    syncSelected();
  }

  function filterCards(query) {
    const q = query.trim().toLowerCase();
    getCards().forEach(function (card) {
      const name = (card.querySelector('.dept-branch-card__name')?.textContent || '').toLowerCase();
      card.classList.toggle('is-hidden', q !== '' && !name.includes(q));
    });
  }

  function renderEmpty(message, withIcon) {
    picker.innerHTML = '';
    const wrap = document.createElement('div');
    wrap.className = 'dept-branches-picker__empty';
    if (withIcon) {
      const icon = document.createElement('span');
      icon.className = 'dept-branches-picker__empty-icon';
      icon.setAttribute('aria-hidden', 'true');
      icon.textContent = '◎';
      wrap.appendChild(icon);
    }
    const p = document.createElement('p');
    p.textContent = message;
    wrap.appendChild(p);
    picker.appendChild(wrap);
  }

  function renderBranches(companyId) {
    const keepChecked = getCheckedIds();
    if (searchInput) {
      searchInput.value = '';
    }

    if (!companyId) {
      panel?.classList.add('is-disabled');
      picker.setAttribute('data-disabled', '1');
      if (toolbar) {
        toolbar.hidden = true;
      }
      renderEmpty(selectCompanyMsg, true);
      updateCount();
      return;
    }

    panel?.classList.remove('is-disabled');
    picker.removeAttribute('data-disabled');
    if (toolbar) {
      toolbar.hidden = false;
    }

    const list = branchesByCompany[String(companyId)] || branchesByCompany[companyId] || [];

    if (!list.length) {
      renderEmpty('—', false);
      updateCount();
      return;
    }

    const idsToCheck = keepChecked.length ? keepChecked : selectedIds;

    picker.innerHTML = '';
    const grid = document.createElement('div');
    grid.className = 'dept-branches-grid';
    grid.setAttribute('data-dept-branches-grid', '');

    list.forEach(function (branch) {
      const label = document.createElement('label');
      label.className = 'dept-branch-card';
      if (idsToCheck.indexOf(String(branch.id)) !== -1) {
        label.classList.add('is-selected');
      }

      const input = document.createElement('input');
      input.type = 'checkbox';
      input.className = 'dept-branch-card__input';
      input.name = 'branch_ids[]';
      input.value = String(branch.id);
      input.checked = idsToCheck.indexOf(String(branch.id)) !== -1;

      const box = document.createElement('span');
      box.className = 'dept-branch-card__box';
      box.setAttribute('aria-hidden', 'true');
      box.innerHTML =
        '<svg class="dept-branch-card__icon" width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="3"><path d="M5 12l5 5L20 7"/></svg>';

      const name = document.createElement('span');
      name.className = 'dept-branch-card__name';
      name.textContent = branch.name;

      label.appendChild(input);
      label.appendChild(box);
      label.appendChild(name);
      grid.appendChild(label);
      bindCard(label);
    });

    picker.appendChild(grid);
    selectedIds = [];
    updateCount();
  }

  if (searchInput) {
    searchInput.placeholder = labelSearch || searchInput.placeholder;
    searchInput.addEventListener('input', function () {
      filterCards(searchInput.value);
    });
  }

  if (selectAllBtn) {
    selectAllBtn.textContent = labelSelectAll;
    selectAllBtn.addEventListener('click', function () {
      getCards().forEach(function (card) {
        if (card.classList.contains('is-hidden')) {
          return;
        }
        const input = card.querySelector('input');
        if (input) {
          input.checked = true;
          card.classList.add('is-selected');
        }
      });
      updateCount();
    });
  }

  if (clearAllBtn) {
    clearAllBtn.textContent = labelClearAll;
    clearAllBtn.addEventListener('click', function () {
      getCards().forEach(function (card) {
        const input = card.querySelector('input');
        if (input) {
          input.checked = false;
          card.classList.remove('is-selected');
        }
      });
      updateCount();
    });
  }

  getCards().forEach(bindCard);
  updateCount();

  function syncBranches() {
    const companyId = companySelect ? companySelect.value : fixedCompany;
    renderBranches(companyId);
  }

  if (companySelect) {
    companySelect.addEventListener('change', function () {
      selectedIds = [];
      syncBranches();
    });
  }

  if (!companySelect && fixedCompany) {
    syncBranches();
  }
})();
