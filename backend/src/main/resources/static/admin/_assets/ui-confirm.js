// A form carrying data-confirm asks first, in the layout's #ui-confirm window
// rather than the browser's confirm() (D-288).
//
// confirm() was thirty inline `onsubmit="return confirm('...')"` handlers: a
// grey system box in the browser's language, not the page's, with nothing to
// tell "delete" from "approve". Its message was also a translation spliced
// into a JavaScript string inside an HTML attribute, so an apostrophe in any
// language broke the handler -- and inline handlers are what a strict
// Content-Security-Policy refuses.
//
//   data-confirm="question"          the window's message
//   data-confirm-detail="text"       an optional second line (what is affected)
//   data-confirm-tone="danger"       a destructive action: red confirm button
//
// Confirming re-submits the same form with the same submitter, so a form with
// two buttons still posts the one that was pressed. modal-a11y.js gives the
// window Escape, the Tab trap and focus back to the opener, as for every other.
(function () {
  const modal = document.getElementById('ui-confirm');
  if (!modal) {
    return;
  }
  const message = modal.querySelector('#ui-confirm-message');
  const detail = modal.querySelector('[data-confirm-slot="detail"]');
  const ok = modal.querySelector('[data-confirm-ok]');
  const cancel = modal.querySelector('[data-confirm-cancel]');

  let pending = null;
  const confirmed = new WeakSet();

  function open(form, submitter) {
    pending = { form: form, submitter: submitter };
    message.textContent = form.dataset.confirm;
    detail.textContent = form.dataset.confirmDetail || '';
    detail.hidden = !form.dataset.confirmDetail;
    const danger = form.dataset.confirmTone === 'danger';
    modal.classList.toggle('ui-confirm--danger', danger);
    ok.classList.toggle('btn-red', danger);
    ok.classList.toggle('btn-blue', !danger);
    modal.classList.add('open');
    // Cancel takes focus, not Confirm: Enter on a window that just opened
    // should never be the thing that deletes.
    requestAnimationFrame(function () { cancel.focus(); });
  }

  function close() {
    modal.classList.remove('open');
  }

  document.addEventListener('submit', function (event) {
    const form = event.target;
    if (!(form instanceof HTMLFormElement) || !form.dataset.confirm) {
      return;
    }
    if (confirmed.has(form)) {
      confirmed.delete(form);
      return;
    }
    event.preventDefault();
    event.stopImmediatePropagation();
    open(form, event.submitter || null);
  }, true);

  ok.addEventListener('click', function () {
    if (!pending) {
      return;
    }
    const target = pending;
    pending = null;
    close();
    confirmed.add(target.form);
    const submitter = target.submitter && target.submitter.form === target.form ? target.submitter : undefined;
    if (typeof target.form.requestSubmit === 'function') {
      target.form.requestSubmit(submitter);
      // requestSubmit fires no submit event when validation refuses the form,
      // and the mark would then let the next submit through unasked.
      confirmed.delete(target.form);
    } else {
      confirmed.delete(target.form);
      target.form.submit();
    }
  });

  cancel.addEventListener('click', close);
  modal.addEventListener('click', function (event) {
    if (event.target === modal || event.target.closest('.modal-close')) {
      close();
    }
  });

  // However the window closes -- Cancel, ×, the backdrop, or Escape through
  // modal-a11y.js -- a close without Confirm abandons the submit.
  new MutationObserver(function () {
    if (!modal.classList.contains('open')) {
      pending = null;
    }
  }).observe(modal, { attributes: true, attributeFilter: ['class'] });
})();
