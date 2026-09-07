// Opens a row action's dialog and fills it from the row that asked.
//
// One dialog per page rather than one per row: the trigger carries the row's
// values as `data-dialog-*`, and each is copied into the element declaring the
// matching `data-dialog-field`. A form input takes it as its value; anything
// else takes it as text, which is how the subject line names the row.
(function () {
  function fill(dialog, trigger) {
    dialog.querySelectorAll('[data-dialog-field]').forEach(function (target) {
      const key = target.getAttribute('data-dialog-field');
      const value = trigger.getAttribute('data-dialog-' + key);
      if (value === null) {
        return;
      }
      if ('value' in target && target.tagName !== 'P' && target.tagName !== 'SPAN') {
        target.value = value;
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
    const dialog = document.getElementById(trigger.getAttribute('data-dialog'));
    if (!dialog || typeof dialog.showModal !== 'function') {
      // No <dialog> support: leave the click alone rather than swallow it, so
      // a link still navigates and a submit still submits.
      return;
    }
    event.preventDefault();

    // The ⋮ menu is portaled to the body while open and closes on outside
    // clicks; close it first so it does not sit above the backdrop.
    const menu = trigger.closest('.row-actions');
    if (menu) {
      menu.classList.remove('is-open');
      const opener = menu.querySelector('.row-actions__trigger');
      if (opener) {
        opener.setAttribute('aria-expanded', 'false');
      }
    }
    document.body.click();

    fill(dialog, trigger);
    dialog.showModal();

    const first = dialog.querySelector('.row-dialog__body input, .row-dialog__body textarea, .row-dialog__body select');
    if (first) {
      first.focus();
      if (typeof first.select === 'function') {
        first.select();
      }
    }
  });

  // Clicking the backdrop closes it, which is what every other modal on the
  // web does and what people try first.
  document.addEventListener('click', function (event) {
    if (event.target.tagName === 'DIALOG' && event.target.classList.contains('row-dialog')) {
      event.target.close();
    }
  });
})();
