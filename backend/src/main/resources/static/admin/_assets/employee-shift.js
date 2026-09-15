// Fills the employee form's shift select for the company chosen in the form.
//
// Legacy's _employee_form.php lists the shifts of the company the page was
// opened for (page.php's $empFormShifts), so an administrator with no company
// filter, who names the company in the form's #emp_company select, is offered
// no shift at all -- and the shift is required, so legacy's add cannot be
// finished that way. employee-form.js, copied from legacy unchanged, fills the
// branch, department and job title selects; this fills the shift select the
// same way, from the form's data-shifts-by-company map (D-250).
//
// Only a form with #emp_company is touched. A filtered add and an edit keep the
// shift options the server rendered for their one company.
(function () {
  function shiftsByCompany(form) {
    try {
      return JSON.parse(form.getAttribute('data-shifts-by-company') || '{}');
    } catch (e) {
      return {};
    }
  }

  document.querySelectorAll('[data-employee-form]').forEach(function (form) {
    const companySelect = form.querySelector('#emp_company');
    const shiftSelect = form.querySelector('[data-emp-shift]');
    if (!companySelect || !shiftSelect) {
      return;
    }
    const shifts = shiftsByCompany(form);
    const none = shiftSelect.options.length ? shiftSelect.options[0].cloneNode(true) : null;

    function render() {
      shiftSelect.replaceChildren();
      if (none) {
        shiftSelect.appendChild(none.cloneNode(true));
      }
      (shifts[companySelect.value] || []).forEach(function (shift) {
        const option = document.createElement('option');
        option.value = String(shift.id);
        option.textContent = shift.name;
        shiftSelect.appendChild(option);
      });
      shiftSelect.selectedIndex = 0;
    }

    companySelect.addEventListener('change', render);
    render();
  });
})();
