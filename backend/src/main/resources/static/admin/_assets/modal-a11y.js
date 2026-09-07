// The keyboard, for the dashboard's copied modal.
//
// `crud.js` opens `.modal-bg` by adding a class and closes it on a click, which
// is all legacy does. That leaves a dialog you cannot dismiss with Escape, that
// does not take focus, that lets Tab walk out into the page behind it, and that
// drops focus on the floor when it closes. None of that is worth changing in
// the copy -- so it is added here instead, over the same markup.
(function () {
  const FOCUSABLE = 'a[href], button:not([disabled]), input:not([disabled]):not([type="hidden"]), '
    + 'select:not([disabled]), textarea:not([disabled]), [tabindex]:not([tabindex="-1"])';

  let opener = null;

  function open(modal) {
    opener = document.activeElement;
    modal.setAttribute('aria-hidden', 'false');
    const first = modal.querySelector('.modal ' + FOCUSABLE + ':not(.modal-close)')
      || modal.querySelector('.modal-close');
    if (first) {
      first.focus();
    }
  }

  function close(modal) {
    modal.setAttribute('aria-hidden', 'true');
    // Back where it came from, so a keyboard user is not returned to the top
    // of the document every time they cancel.
    if (opener && document.contains(opener)) {
      opener.focus();
    }
    opener = null;
  }

  // crud.js toggles the class rather than firing an event, so that is what
  // there is to watch.
  new MutationObserver(function (records) {
    records.forEach(function (record) {
      const modal = record.target;
      if (!modal.classList || !modal.classList.contains('modal-bg')) {
        return;
      }
      if (modal.classList.contains('open')) {
        open(modal);
      } else if (record.oldValue && record.oldValue.includes('open')) {
        close(modal);
      }
    });
  }).observe(document.body, {
    subtree: true, attributes: true, attributeFilter: ['class'], attributeOldValue: true,
  });

  document.addEventListener('keydown', function (event) {
    const modal = document.querySelector('.modal-bg.open');
    if (!modal) {
      return;
    }
    if (event.key === 'Escape') {
      event.preventDefault();
      modal.classList.remove('open');
      return;
    }
    if (event.key !== 'Tab') {
      return;
    }
    const items = Array.from(modal.querySelectorAll(FOCUSABLE))
      .filter(function (node) { return node.offsetParent !== null; });
    if (!items.length) {
      return;
    }
    const first = items[0];
    const last = items[items.length - 1];
    if (event.shiftKey && document.activeElement === first) {
      event.preventDefault();
      last.focus();
    } else if (!event.shiftKey && document.activeElement === last) {
      event.preventDefault();
      first.focus();
    }
  });
})();
