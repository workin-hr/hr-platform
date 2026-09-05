(function () {
  function parseJson(el, attr, fallback) {
    try {
      return JSON.parse(el.getAttribute(attr) || fallback);
    } catch {
      return JSON.parse(fallback);
    }
  }

  function initOrgFilters(form) {
    const companySelect = form.querySelector('[data-filter-company]');
    const branchSelect = form.querySelector('[data-filter-branch]');
    const deptSelect = form.querySelector('[data-filter-department]');
    const jobSelect = form.querySelector('[data-filter-job-title]');

    const branchesByCompany = parseJson(form, 'data-branches-by-company', '{}');
    const deptsByCompany = parseJson(form, 'data-departments-by-company', '{}');
    const deptsByBranch = parseJson(form, 'data-departments-by-branch', '{}');
    const jobsByDept = parseJson(form, 'data-job-titles-by-dept', '{}');
    const filterAll = form.getAttribute('data-filter-all') || '—';
    const pickBranchMsg = form.getAttribute('data-pick-branch-first-msg') || filterAll;
    const pickDeptMsg = form.getAttribute('data-pick-dept-first-msg') || filterAll;

    let selectedBranch = form.getAttribute('data-selected-branch') || '';
    let selectedDept = form.getAttribute('data-selected-department') || '';
    let selectedJob = form.getAttribute('data-selected-job-title') || '';

    function currentCompanyId() {
      return companySelect ? companySelect.value : '';
    }

    function listForCompany(map, companyId) {
      if (companyId && companyId !== '0') {
        return map[String(companyId)] || [];
      }
      const all = [];
      Object.keys(map).forEach(function (key) {
        (map[key] || []).forEach(function (item) {
          all.push(item);
        });
      });
      return all;
    }

    function departmentsForBranch(branchId, companyId) {
      if (branchId && branchId !== '0') {
        return deptsByBranch[String(branchId)] || [];
      }
      if (companyId && companyId !== '0') {
        return listForCompany(deptsByCompany, companyId);
      }
      return [];
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
      const jobs = [];
      const seen = {};
      listForCompany(deptsByCompany, companyId).forEach(function (d) {
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

    function renderJobTitles(deptId, branchId, companyId, keepJob) {
      if (!jobSelect) {
        return;
      }

      jobSelect.innerHTML = '';
      const allOpt = document.createElement('option');
      allOpt.value = '0';
      allOpt.textContent = filterAll;
      jobSelect.appendChild(allOpt);

      let list = jobsForDepartment(deptId);
      if ((!deptId || deptId === '0') && branchId && branchId !== '0') {
        list = jobsForBranch(branchId);
      } else if ((!deptId || deptId === '0') && companyId && companyId !== '0') {
        list = jobsForCompany(companyId);
      }

      if (list.length === 0 && (!deptId || deptId === '0')) {
        jobSelect.disabled = false;
        return;
      }

      jobSelect.disabled = false;
      list.forEach(function (j) {
        const opt = document.createElement('option');
        opt.value = String(j.id);
        opt.textContent = j.name;
        jobSelect.appendChild(opt);
      });

      const pick = keepJob || selectedJob;
      if (pick && pick !== '0') {
        jobSelect.value = String(pick);
      }
      selectedJob = '';
    }

    function renderDepartmentsFromBranch(branchId, keepDept, companyId) {
      if (!deptSelect) {
        renderJobTitles('0', branchId, companyId, '');
        return;
      }

      deptSelect.innerHTML = '';
      const allOpt = document.createElement('option');
      allOpt.value = '0';
      allOpt.textContent = filterAll;
      deptSelect.appendChild(allOpt);

      const list = departmentsForBranch(branchId, companyId);

      if (list.length === 0) {
        deptSelect.disabled = false;
        if (branchId && branchId !== '0') {
          const hint = document.createElement('option');
          hint.value = '0';
          hint.disabled = true;
          hint.textContent = pickDeptMsg;
          deptSelect.appendChild(hint);
        }
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

      const pick = keepDept || selectedDept;
      if (pick && pick !== '0') {
        deptSelect.value = String(pick);
      }
      selectedDept = '';
      renderJobTitles(deptSelect.value, branchId, companyId, selectedJob);
    }

    function renderDepartmentsFromCompany(companyId, keepDept) {
      if (!deptSelect || branchSelect) {
        return;
      }

      deptSelect.innerHTML = '';
      const allOpt = document.createElement('option');
      allOpt.value = '0';
      allOpt.textContent = filterAll;
      deptSelect.appendChild(allOpt);

      if (!companyId || companyId === '0') {
        deptSelect.disabled = false;
        renderJobTitles('0', '0', companyId, '');
        return;
      }

      deptSelect.disabled = false;
      listForCompany(deptsByCompany, companyId).forEach(function (d) {
        const opt = document.createElement('option');
        opt.value = String(d.id);
        opt.textContent = d.name;
        deptSelect.appendChild(opt);
      });

      const pick = keepDept || selectedDept;
      if (pick && pick !== '0') {
        deptSelect.value = String(pick);
      }
      selectedDept = '';
      renderJobTitles(deptSelect.value, '0', companyId, selectedJob);
    }

    function renderBranches(companyId, keepBranch) {
      if (!branchSelect) {
        renderDepartmentsFromCompany(companyId, '');
        return;
      }

      branchSelect.innerHTML = '';
      const allOpt = document.createElement('option');
      allOpt.value = '0';
      allOpt.textContent = filterAll;
      branchSelect.appendChild(allOpt);

      if (!companyId || companyId === '0') {
        branchSelect.disabled = false;
        selectedBranch = '';
        selectedDept = '';
        selectedJob = '';
        renderDepartmentsFromBranch('0', '', '');
        return;
      }

      branchSelect.disabled = false;
      listForCompany(branchesByCompany, companyId).forEach(function (b) {
        const opt = document.createElement('option');
        opt.value = String(b.id);
        opt.textContent = b.name;
        branchSelect.appendChild(opt);
      });

      const pick = keepBranch || selectedBranch;
      if (pick && pick !== '0') {
        branchSelect.value = String(pick);
      }
      selectedBranch = '';
      renderDepartmentsFromBranch(branchSelect.value, selectedDept, companyId);
    }

    if (companySelect) {
      companySelect.addEventListener('change', function () {
        selectedBranch = '';
        selectedDept = '';
        selectedJob = '';
        if (branchSelect) {
          branchSelect.value = '0';
        }
        if (deptSelect) {
          deptSelect.value = '0';
        }
        if (jobSelect) {
          jobSelect.value = '0';
        }
        renderBranches(companySelect.value, '');
        if (!branchSelect) {
          renderDepartmentsFromCompany(companySelect.value, '');
        }
      });
    }

    if (branchSelect) {
      branchSelect.addEventListener('change', function () {
        selectedDept = '';
        selectedJob = '';
        renderDepartmentsFromBranch(branchSelect.value, '', currentCompanyId());
      });
    }

    if (deptSelect) {
      deptSelect.addEventListener('change', function () {
        const branchId = branchSelect ? branchSelect.value : '0';
        renderJobTitles(deptSelect.value, branchId, currentCompanyId(), '');
      });
    }

    const initialCompany = companySelect ? companySelect.value : '';
    if (branchSelect) {
      renderBranches(initialCompany, selectedBranch);
    } else if (deptSelect) {
      renderDepartmentsFromCompany(initialCompany, selectedDept);
    }
  }

  document.querySelectorAll('[data-org-filters]').forEach(initOrgFilters);
})();
