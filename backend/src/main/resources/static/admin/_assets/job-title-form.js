(function () {
  const form = document.querySelector('[data-org-jt-form]');
  if (!form) {
    return;
  }

  const companySelect = form.querySelector('[data-jt-company]');
  const deptSelect = form.querySelector('[data-jt-department]');
  if (!deptSelect) {
    return;
  }

  let departmentsByCompany = {};
  try {
    departmentsByCompany = JSON.parse(form.getAttribute('data-departments') || '{}');
  } catch {
    departmentsByCompany = {};
  }

  let selectedDept = form.getAttribute('data-selected-department') || '';
  const selectCompanyMsg = form.getAttribute('data-select-company-msg') || '';
  const fixedCompany = form.getAttribute('data-fixed-company') || '';

  function renderDepartments(companyId) {
    const keepValue = deptSelect.value && deptSelect.value !== '0' ? deptSelect.value : '';
    deptSelect.innerHTML = '';

    if (!companyId) {
      const opt = document.createElement('option');
      opt.value = '0';
      opt.textContent = selectCompanyMsg;
      deptSelect.appendChild(opt);
      deptSelect.disabled = true;
      return;
    }

    deptSelect.disabled = false;

    const none = document.createElement('option');
    none.value = '0';
    none.textContent = '—';
    deptSelect.appendChild(none);

    const list = departmentsByCompany[String(companyId)] || departmentsByCompany[companyId] || [];
    list.forEach(function (dept) {
      const opt = document.createElement('option');
      opt.value = String(dept.id);
      opt.textContent = dept.name;
      deptSelect.appendChild(opt);
    });

    const pick = keepValue || selectedDept;
    if (pick && list.some(function (d) {
      return String(d.id) === String(pick);
    })) {
      deptSelect.value = String(pick);
    }

    selectedDept = '';
  }

  function syncDepartments() {
    const companyId = companySelect ? companySelect.value : fixedCompany;
    renderDepartments(companyId);
  }

  if (companySelect) {
    companySelect.addEventListener('change', function () {
      selectedDept = '';
      syncDepartments();
    });
  }

  if (!companySelect && fixedCompany) {
    syncDepartments();
  }
})();
