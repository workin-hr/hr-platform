(function () {
  function parseJson(el, attr, fallback) {
    try {
      return JSON.parse(el.getAttribute(attr) || fallback);
    } catch {
      return JSON.parse(fallback);
    }
  }

  function initEmployeeForm(form) {
    const branchSelect = form.querySelector('[data-emp-branch]');
    const deptSelect = form.querySelector('[data-emp-department]');
    const jobSelect = form.querySelector('[data-emp-job]');
    if (!branchSelect || !deptSelect || !jobSelect) {
      return;
    }

    const companySelect = form.querySelector('#emp_company');
    const branchesByCompany = parseJson(form, 'data-branches-by-company', '{}');
    const deptsByCompany = parseJson(form, 'data-departments-by-company', '{}');
    const deptsByBranch = parseJson(form, 'data-departments-by-branch', '{}');
    const jobsByDept = parseJson(form, 'data-job-titles-by-dept', '{}');
    const jobsByCompany = parseJson(form, 'data-job-titles-by-company', '{}');
    const jobsById = {};
    Object.keys(jobsByCompany).forEach(function (cid) {
      (jobsByCompany[cid] || []).forEach(function (j) {
        jobsById[String(j.id)] = j.name;
      });
    });
    const selectCompanyMsg = form.getAttribute('data-select-company-msg') || '';
    const selectBranchMsg = form.getAttribute('data-select-branch-msg') || '';
    const selectDeptMsg = form.getAttribute('data-select-dept-msg') || '';

    let selectedBranch = '';
    let selectedDept = '';
    let selectedJob = '';

    function readSelectedFromForm() {
      selectedBranch = form.getAttribute('data-selected-branch') || '';
      selectedDept = form.getAttribute('data-selected-department') || '';
      selectedJob = form.getAttribute('data-selected-job') || '';
    }

    function jobName(jobId) {
      return jobsById[String(jobId)] || form.getAttribute('data-selected-job-label') || '';
    }

    function ensureJobInList(list, jobId) {
      if (!jobId || jobId === '0' || jobId === 0) {
        return list;
      }
      const id = String(jobId);
      if (list.some(function (j) {
        return String(j.id) === id;
      })) {
        return list;
      }
      const name = jobName(jobId);
      if (!name) {
        return list;
      }
      return list.concat([{ id: parseInt(id, 10), name: name }]);
    }

    function applySelectValue(selectEl, value, label) {
      if (!selectEl || value === '' || value === null || value === undefined) {
        return;
      }
      const strVal = String(value);
      selectEl.value = strVal;
      if (selectEl.value !== strVal) {
        const opt = document.createElement('option');
        opt.value = strVal;
        const text = label && String(label).trim() ? String(label).trim() : '';
        opt.textContent = text || ('#' + strVal);
        selectEl.appendChild(opt);
        selectEl.value = strVal;
      }
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
      if (!companyId) {
        return [];
      }
      return jobsByCompany[String(companyId)] || [];
    }

    function resolveCompanyId() {
      if (companySelect && companySelect.value) {
        return companySelect.value;
      }
      const fixed = form.querySelector('input[name="company_id"]');
      if (fixed?.value) {
        return fixed.value;
      }
      return form.getAttribute('data-selected-company') || '';
    }

    function renderJobTitles(deptId, branchId, companyId, keepJob) {
      jobSelect.innerHTML = '';
      const none = document.createElement('option');
      none.value = '0';
      none.textContent = '—';
      jobSelect.appendChild(none);

      let list = jobsForDepartment(deptId);
      if ((!deptId || deptId === '0') && branchId && branchId !== '0') {
        list = jobsForBranch(branchId);
      }
      if (!list.length && companyId) {
        list = jobsForCompany(companyId);
      }

      const pick = keepJob !== undefined && keepJob !== '' ? keepJob : selectedJob;
      list = ensureJobInList(list, pick);

      if (!list.length && (!deptId || deptId === '0')) {
        jobSelect.disabled = true;
        const hint = document.createElement('option');
        hint.value = '0';
        hint.disabled = true;
        hint.textContent = selectDeptMsg;
        jobSelect.appendChild(hint);
        return;
      }

      jobSelect.disabled = false;
      list.forEach(function (j) {
        const opt = document.createElement('option');
        opt.value = String(j.id);
        opt.textContent = j.name;
        jobSelect.appendChild(opt);
      });

      applySelectValue(jobSelect, pick, jobName(pick));
      selectedJob = '';
    }

    function departmentsForContext(branchId, companyId) {
      if (branchId && branchId !== '0') {
        return deptsByBranch[String(branchId)] || [];
      }
      if (companyId) {
        return deptsByCompany[String(companyId)] || [];
      }
      return [];
    }

    function renderDepartments(branchId, companyId, keepDept) {
      deptSelect.innerHTML = '';
      const none = document.createElement('option');
      none.value = '0';
      none.textContent = '—';
      deptSelect.appendChild(none);

      const list = departmentsForContext(branchId, companyId);
      if (!list.length) {
        deptSelect.disabled = true;
        renderJobTitles('0', branchId, companyId, selectedJob);
        return;
      }

      deptSelect.disabled = false;

      list.forEach(function (d) {
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
      if (!companySelect) {
        return;
      }

      branchSelect.innerHTML = '';
      const pickFirst = document.createElement('option');
      pickFirst.value = '0';

      if (!companyId || companyId === '') {
        pickFirst.textContent = selectCompanyMsg;
        branchSelect.appendChild(pickFirst);
        branchSelect.value = '0';
        branchSelect.disabled = true;
        renderDepartments('0', '', '');
        return;
      }

      pickFirst.textContent = selectBranchMsg;
      branchSelect.appendChild(pickFirst);
      branchSelect.disabled = false;

      (branchesByCompany[String(companyId)] || []).forEach(function (b) {
        const opt = document.createElement('option');
        opt.value = String(b.id);
        opt.textContent = b.name;
        branchSelect.appendChild(opt);
      });

      const pick = keepBranch !== undefined && keepBranch !== '' ? keepBranch : selectedBranch;
      applySelectValue(branchSelect, pick);
      const branchVal = branchSelect.value || '0';
      selectedBranch = '';
      renderDepartments(branchVal, companyId, selectedDept);
    }

    function refreshCascade() {
      readSelectedFromForm();
      const companyId = resolveCompanyId();
      if (companySelect) {
        renderBranches(companyId, selectedBranch);
      } else {
        const branchVal = selectedBranch || branchSelect.value || '0';
        renderDepartments(branchVal, companyId, selectedDept);
      }
    }

    if (companySelect) {
      companySelect.addEventListener('change', function () {
        selectedBranch = '';
        selectedDept = '';
        selectedJob = '';
        renderBranches(companySelect.value, '');
      });
    }

    branchSelect.addEventListener('change', function () {
      selectedDept = '';
      selectedJob = '';
      renderDepartments(branchSelect.value, resolveCompanyId(), '');
    });

    deptSelect.addEventListener('change', function () {
      selectedJob = '';
      renderJobTitles(deptSelect.value, branchSelect.value, resolveCompanyId(), '');
    });

    refreshCascade();
  }

  document.querySelectorAll('[data-employee-form]').forEach(initEmployeeForm);
})();
