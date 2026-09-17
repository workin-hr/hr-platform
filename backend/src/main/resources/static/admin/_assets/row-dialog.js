// Opens a row action's window and fills it from the row that asked.
//
// One window per page rather than one per row: the trigger carries the row's
// values as `data-dialog-*`, and each is copied into the element declaring the
// matching `data-dialog-field`. A checkbox is ticked when the value is "1" and
// unticked otherwise; any other form input takes it as its value; anything
// else takes it as text, which is how the subject line names the row. An
// option marked data-dialog-current-only is disabled unless it is the value the
// row carries: a retired choice stays with the rows that already have it.
//
// The window is legacy's `.modal-bg`, opened by its `open` class. crud.js
// closes it on the close button and the backdrop, and modal-a11y.js gives it
// Escape, the Tab trap and focus.
(function () {
  function fill(modal, trigger) {
    // Reset first. Closing the window does not clear its form, and fill() only
    // writes the fields that declare data-dialog-field -- so a rejection reason
    // typed for one row, then cancelled, was still in the box when the window
    // opened for the next one, and would have been submitted against that
    // employee.
    const form = modal.querySelector('form');
    if (form && typeof form.reset === 'function') {
      form.reset();
    }
    modal.querySelectorAll('[data-dialog-field]').forEach(function (target) {
      const key = target.getAttribute('data-dialog-field');
      const value = trigger.getAttribute('data-dialog-' + key);
      if (value === null) {
        return;
      }
      if (target.type === 'checkbox') {
        // Its value attribute is what it submits when ticked, so only the
        // tick comes from the row. Written as a value, it stayed ticked as
        // the form rendered it and saving re-activated an inactive row.
        target.checked = value === '1';
      } else if ('value' in target && target.tagName !== 'P' && target.tagName !== 'SPAN') {
        target.value = value;
        if (target.tagName === 'SELECT') {
          target.querySelectorAll('option[data-dialog-current-only]').forEach(function (option) {
            option.disabled = option.value !== value;
          });
        }
      } else {
        target.textContent = value;
      }
    });
  }

  document.addEventListener('click', function (event) {
    const trigger = event.target.closest('[data-dialog]');
    if (!trigger) {
      return;
    }
    const modal = document.getElementById(trigger.getAttribute('data-dialog'));
    if (!modal || !modal.classList.contains('modal-bg')) {
      // Nothing to open: leave the click alone rather than swallow it, so a
      // link still navigates and a submit still submits.
      return;
    }
    event.preventDefault();

    // The ⋮ menu is portaled to the body while open; close it first so it does
    // not sit above the backdrop. row-actions.js owns the unportaling, so it is
    // asked to do it rather than half-done here and finished with a synthetic
    // body click.
    document.dispatchEvent(new CustomEvent('row-actions:close'));

    // modal-a11y.js returns focus to whatever had it when the window opened.
    // A menu item has just been put back inside its closed menu, where focus
    // cannot land, so the row's ⋮ button takes it: closing the window then
    // leaves a keyboard user on the row they acted on, not at the top of the page.
    const menuButton = trigger.closest('[data-row-actions]')?.querySelector('.row-actions__trigger');
    (menuButton || trigger).focus();

    fill(modal, trigger);
    // A field with state of its own beyond its value -- emp-picker.js's label
    // and results -- redraws from what fill() just wrote.
    modal.dispatchEvent(new CustomEvent('row-dialog:filled', { bubbles: true }));
    modal.classList.add('open');
  });

  // A footer Cancel. It is not .modal-close, whose styles position the ×, so
  // crud.js does not see it; closing is the same class removal crud.js does.
  document.addEventListener('click', function (event) {
    const cancel = event.target.closest('[data-dialog-cancel]');
    const modal = cancel && cancel.closest('.modal-bg');
    if (modal) {
      modal.classList.remove('open');
    }
  });
})();
