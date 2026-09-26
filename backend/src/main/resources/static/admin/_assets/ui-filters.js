// On a phone, a list's filter bar folds behind one button (D-288).
//
// The lists carry up to eight filters. Stacked one per row at 390px they filled
// the first screen and a half before a single row of data, on every visit. Below
// 640px the fields fold away behind a button that says how many filters are in
// force, and open when any is -- a filtered list must never hide why it is
// filtered. From 640px up nothing changes.
(function () {
  const strings = (document.getElementById('ui-strings') || {}).dataset || {};
  const label = strings.strFilters || 'Filters';
  const narrow = window.matchMedia('(max-width: 639.98px)');

  // A select's first option is its "all" -- "", "all" or "0" depending on the
  // page -- so a select filters only when it holds something else.
  function filters(control) {
    if (!control || !control.value || control.value.trim() === '') {
      return false;
    }
    return control.tagName !== 'SELECT' || control.value !== control.options[0].value;
  }

  function active(form) {
    let count = 0;
    form.querySelectorAll('.filter-field').forEach(function (field) {
      if (filters(field.querySelector('select, input:not([type="hidden"])'))) {
        count += 1;
      }
    });
    return count;
  }

  document.querySelectorAll('.page-toolbar-filters').forEach(function (toolbar) {
    const form = toolbar.querySelector('form.toolbar-form--labeled');
    if (!form || form.querySelectorAll('.filter-field').length < 3) {
      return;
    }
    const button = document.createElement('button');
    button.type = 'button';
    button.className = 'btn btn-outline ui-filters-toggle';
    button.setAttribute('aria-controls', form.id || (form.id = 'filters-' + Math.random().toString(36).slice(2, 8)));
    toolbar.insertBefore(button, toolbar.firstChild);

    function draw() {
      const count = active(form);
      button.textContent = count ? label + ' (' + count + ')' : label;
    }

    function apply() {
      const fold = narrow.matches && active(form) === 0 && !toolbar.classList.contains('is-open');
      toolbar.classList.toggle('is-folded', fold);
      button.setAttribute('aria-expanded', String(!fold));
    }

    button.addEventListener('click', function () {
      const opening = toolbar.classList.contains('is-folded');
      toolbar.classList.toggle('is-open', opening);
      toolbar.classList.toggle('is-folded', !opening);
      button.setAttribute('aria-expanded', String(opening));
      if (opening) {
        const first = form.querySelector('.ui-select__button, .filter-field select, .filter-field input');
        if (first) {
          first.focus();
        }
      }
    });
    form.addEventListener('change', draw);
    narrow.addEventListener('change', apply);
    draw();
    apply();
  });
})();
