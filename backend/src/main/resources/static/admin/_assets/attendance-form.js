// dashboard/pages/attendance/assets/attendance-form.js's two behaviours that
// the shared emp-picker.js and crud.js do not cover: the add modal's
// check-in defaults to now, and its save is disabled until an employee and a
// check-in are both set. The picker itself (search/filter/select/reset) is
// emp-picker.js's, shared with every other page that embeds one.
(function () {
  const modal = document.getElementById('attModal');
  if (!modal) {
    return;
  }
  const form = modal.querySelector('form');
  const submitBtn = form ? form.querySelector('[data-att-submit]') : null;
  const employeeId = form ? form.querySelector('[name="employee_id"]') : null;
  const checkInInput = form ? form.querySelector('[name="check_in"]') : null;
  if (!form || !submitBtn || !employeeId || !checkInInput) {
    return;
  }

  function defaultDateTimeLocal() {
    const now = new Date();
    now.setMinutes(now.getMinutes() - now.getTimezoneOffset());
    return now.toISOString().slice(0, 16);
  }

  function updateSubmitState() {
    const hasEmployee = parseInt(employeeId.value, 10) > 0;
    const hasCheckIn = checkInInput.value !== '';
    submitBtn.disabled = !(hasEmployee && hasCheckIn);
  }

  // emp-picker.js's show() (a choice, or a reset's resync) dispatches
  // 'change' on employee_id -- a programmatic .value set fires nothing
  // native. 'input' on the form covers the search box being typed into,
  // which clears employee_id without going through show().
  form.addEventListener('change', updateSubmitState);
  form.addEventListener('input', updateSubmitState);

  const originalOpenAdd = window.crudOpenAdd;
  window.crudOpenAdd = function (modalId) {
    originalOpenAdd(modalId);
    if (modalId !== 'attModal') {
      return;
    }
    // form.reset() (inside crudOpenAdd) already cleared the picker via
    // emp-picker.js's own reset listener; this only sets the one legacy
    // default it does not (attendanceOpenAdd, attendance-form.js).
    checkInInput.value = defaultDateTimeLocal();
    updateSubmitState();
  };

  updateSubmitState();
})();
