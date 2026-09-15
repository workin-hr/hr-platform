(function () {
  function parseJson(el, attr, fallback) {
    try {
      return JSON.parse(el.getAttribute(attr) || fallback);
    } catch {
      return JSON.parse(fallback);
    }
  }

  function initWorkforceForm(form) {
    const companySelect = form.querySelector('[data-filter-company]');
    const fixedCompany = form.querySelector('[data-fixed-company]');
    const branchSelect = form.querySelector('[data-wp-branch]');
    const deptSelect = form.querySelector('[data-wp-department]');
    const jobSelect = form.querySelector('[data-wp-job-title]');
    const plannedInput = form.querySelector('[name="planned_count"]');
    const submitBtn = form.querySelector('[data-wp-submit]');

    const branchesByCompany = parseJson(form, 'data-branches-by-company', '{}');
    const deptsByBranch = parseJson(form, 'data-departments-by-branch', '{}');
    const jobsByDept = parseJson(form, 'data-job-titles-by-dept', '{}');
    const jobsByCompany = parseJson(form, 'data-job-titles-by-company', '{}');

    const phBranch = form.getAttribute('data-placeholder-branch') || '—';
    const phJob = form.getAttribute('data-placeholder-job') || '—';

    let selectedBranch = '';
    let selectedDept = '';
    let selectedJob = '';

    function readSelectedFromForm() {
      selectedBranch = form.getAttribute('data-selected-branch') || '';
      selectedDept = form.getAttribute('data-selected-department') || '';
      selectedJob = form.getAttribute('data-selected-job-title') || '';
    }

    function currentCompanyId() {
      if (fixedCompany) {
        return fixedCompany.getAttribute('data-fixed-company')
          || fixedCompany.value
          || '';
      }
      return companySelect ? companySelect.value : '';
    }

    function listForCompany(map, companyId) {
      return map[String(companyId)] || [];
    }

    function jobsForDepartment(deptId) {
      if (deptId && deptId !== '0') {
        return jobsByDept[String(deptId)] || [];
      }
      return [];
    }

    function jobsForBranch(branchId) {
      const jobs = [];
      const seen = {};
      (deptsByBranch[String(branchId)] || []).forEach(function (d) {
        (jobsByDept[String(d.id)] || []).forEach(function (j) {
          const id = String(j.id);
          if (!seen[id]) {
            seen[id] = true;
            jobs.push(j);
          }
        });
      });
      return jobs;
    }

    function jobsForCompany(companyId) {
      return jobsByCompany[String(companyId)] || [];
    }

    function applySelectValue(selectEl, value) {
      if (!selectEl || value === '' || value === null || value === undefined) {
        return;
      }
      selectEl.value = String(value);
      if (selectEl.value !== String(value)) {
        const opt = document.createElement('option');
        opt.value = String(value);
        opt.textContent = '#' + value;
        selectEl.appendChild(opt);
        selectEl.value = String(value);
      }
    }

    function renderJobTitles(deptId, branchId, companyId, keepJob) {
      if (!jobSelect) {
        return;
      }
      jobSelect.innerHTML = '';
      const empty = document.createElement('option');
      empty.value = '';
      empty.textContent = phJob;
      jobSelect.appendChild(empty);

      let list = jobsForDepartment(deptId);
      if ((!deptId || deptId === '0') && branchId) {
        list = jobsForBranch(branchId);
      }
      if (!list.length && companyId) {
        list = jobsForCompany(companyId);
      }

      list.forEach(function (j) {
        const opt = document.createElement('option');
        opt.value = String(j.id);
        opt.textContent = j.name;
        jobSelect.appendChild(opt);
      });

      const pick = keepJob !== undefined && keepJob !== '' ? keepJob : selectedJob;
      applySelectValue(jobSelect, pick);
      selectedJob = '';
      updateSubmitState();
    }

    function renderDepartments(branchId, companyId, keepDept) {
      if (!deptSelect) {
        renderJobTitles('0', branchId, companyId, selectedJob);
        return;
      }
      deptSelect.innerHTML = '';
      const none = document.createElement('option');
      none.value = '0';
      none.textContent = '—';
      deptSelect.appendChild(none);

      (deptsByBranch[String(branchId)] || []).forEach(function (d) {
        const opt = document.createElement('option');
        opt.value = String(d.id);
        opt.textContent = d.name;
        deptSelect.appendChild(opt);
      });

      const pick = keepDept !== undefined && keepDept !== '' ? keepDept : selectedDept;
      applySelectValue(deptSelect, pick);
      const deptVal = deptSelect.value || '0';
      selectedDept = '';
      renderJobTitles(deptVal, branchId, companyId, selectedJob);
    }

    function renderBranches(companyId, keepBranch) {
      if (!branchSelect) {
        return;
      }
      branchSelect.innerHTML = '';
      const empty = document.createElement('option');
      empty.value = '';
      empty.textContent = phBranch;
      branchSelect.appendChild(empty);

      if (!companyId) {
        updateSubmitState();
        return;
      }

      listForCompany(branchesByCompany, companyId).forEach(function (b) {
        const opt = document.createElement('option');
        opt.value = String(b.id);
        opt.textContent = b.name;
        branchSelect.appendChild(opt);
      });

      const pick = keepBranch !== undefined && keepBranch !== '' ? keepBranch : selectedBranch;
      applySelectValue(branchSelect, pick);
      const branchVal = branchSelect.value || '';
      selectedBranch = '';
      renderDepartments(branchVal, companyId, selectedDept);
    }

    function updateSubmitState() {
      if (!submitBtn) {
        return;
      }
      const companyOk = !!currentCompanyId();
      const branchOk = !!(branchSelect?.value && parseInt(branchSelect.value, 10) > 0);
      const jobOk = !!(jobSelect?.value && parseInt(jobSelect.value, 10) > 0);
      const plannedOk = plannedInput?.value !== '' && parseInt(plannedInput.value, 10) >= 0;
      submitBtn.disabled = !(companyOk && branchOk && jobOk && plannedOk);
    }

    if (companySelect) {
      companySelect.addEventListener('change', function () {
        selectedBranch = '';
        selectedDept = '';
        selectedJob = '';
        renderBranches(companySelect.value, '');
      });
    }

    branchSelect?.addEventListener('change', function () {
      selectedDept = '';
      selectedJob = '';
      renderDepartments(branchSelect.value, currentCompanyId(), '');
    });

    deptSelect?.addEventListener('change', function () {
      selectedJob = '';
      renderJobTitles(deptSelect.value, branchSelect?.value || '', currentCompanyId(), '');
    });

    jobSelect?.addEventListener('change', updateSubmitState);
    plannedInput?.addEventListener('input', updateSubmitState);

    form.addEventListener('submit', function (e) {
      updateSubmitState();
      if (submitBtn?.disabled) {
        e.preventDefault();
      }
    });

    form._wpRefresh = function () {
      readSelectedFromForm();
      renderBranches(currentCompanyId(), selectedBranch);
    };

    readSelectedFromForm();
    renderBranches(currentCompanyId(), selectedBranch);
    updateSubmitState();
  }

  const modal = document.getElementById('wpModal');
  const form = modal?.querySelector('form[data-org-wp-form]');
  if (form) {
    initWorkforceForm(form);
  }

  window.workforceOpenAdd = function () {
    if (!form) {
      return;
    }
    crudOpenAdd('wpModal');
    form.setAttribute('data-selected-branch', '');
    form.setAttribute('data-selected-department', '');
    form.setAttribute('data-selected-job-title', '');
    if (form.querySelector('[name="planned_count"]')) {
      form.querySelector('[name="planned_count"]').value = '1';
    }
    form._wpRefresh?.();
  };

  window.workforceOpenEdit = function (btn) {
    if (!form) {
      return;
    }
    const companyId = btn.getAttribute('data-company-id') || '';

    form.setAttribute('data-selected-branch', btn.getAttribute('data-branch-id') || '');
    form.setAttribute('data-selected-department', btn.getAttribute('data-department-id') || '');
    form.setAttribute('data-selected-job-title', btn.getAttribute('data-job-title-id') || '');

    const companySelect = form.querySelector('[data-filter-company]');
    const fixedCompany = form.querySelector('[data-fixed-company]');
    if (companySelect && companyId) {
      companySelect.value = String(companyId);
    }
    if (fixedCompany && companyId) {
      fixedCompany.value = String(companyId);
      fixedCompany.setAttribute('data-fixed-company', String(companyId));
    }

    form._wpRefresh?.();

    crudOpenEdit('wpModal', btn);
    form._wpRefresh?.();
  };
})();
