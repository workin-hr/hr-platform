// A searchable employee picker, ported from legacy's emp-picker scripts
// (dashboard/pages/advances/assets/advance-form.js and its siblings).
//
// Markup: employeePicker.jte. The employee list is rendered once per page, as
// JSON in the data-employees attribute of the element a picker names in
// data-emp-source, so an add form and an edit dialog share one copy.
//
// The chosen id travels in a hidden input. A row's employee can be missing from
// the list -- it holds active employees, and the row's employee may since have
// been deactivated -- so an edit keeps the row's id and shows the row's own
// label, as legacy's advanceOpenEdit does, instead of leaving the field empty
// and the form impossible to save.
(function () {
  const LIMIT = 40;
  const lists = new Map();

  function employeesFor(picker) {
    const sourceId = picker.getAttribute('data-emp-source');
    if (!lists.has(sourceId)) {
      let parsed = [];
      try {
        const source = document.getElementById(sourceId);
        parsed = JSON.parse((source && source.getAttribute('data-employees')) || '[]');
      } catch (e) {
        parsed = [];
      }
      lists.set(sourceId, Array.isArray(parsed) ? parsed : []);
    }
    return lists.get(sourceId);
  }

  function parts(picker) {
    return {
      search: picker.querySelector('[data-emp-search]'),
      id: picker.querySelector('[data-emp-id]'),
      fallback: picker.querySelector('[data-emp-fallback-label]'),
      selected: picker.querySelector('[data-emp-selected]'),
      results: picker.querySelector('[data-emp-results]'),
    };
  }

  function matches(employees, query) {
    const q = query.trim().toLowerCase();
    const found = q === '' ? employees : employees.filter(function (emp) {
      return String(emp.label || '').toLowerCase().includes(q)
        || String(emp.name || '').toLowerCase().includes(q)
        || String(emp.code || '').toLowerCase().includes(q);
    });
    return found.slice(0, LIMIT);
  }

  function show(picker, id, label) {
    const p = parts(picker);
    p.id.value = id;
    p.search.value = label;
    p.selected.textContent = label;
    p.selected.hidden = label === '';
    picker.classList.remove('is-invalid');
    p.results.classList.remove('is-open');
  }

  function render(picker, list) {
    const p = parts(picker);
    p.results.replaceChildren();
    if (list.length === 0) {
      const empty = document.createElement('li');
      empty.className = 'emp-picker__empty';
      empty.textContent = p.results.getAttribute('data-empty-label') || '—';
      p.results.appendChild(empty);
    }
    list.forEach(function (emp) {
      const item = document.createElement('li');
      const button = document.createElement('button');
      button.type = 'button';
      button.textContent = emp.label || '';
      button.addEventListener('click', function () {
        show(picker, String(emp.id), emp.label || '');
      });
      item.appendChild(button);
      p.results.appendChild(item);
    });
    p.results.classList.add('is-open');
  }

  // Shows what the hidden id now holds: the listed employee's label, or the
  // row's own label when the row's employee is not in the list.
  function sync(picker) {
    const p = parts(picker);
    const id = p.id.value;
    if (id === '') {
      show(picker, '', '');
      return;
    }
    const listed = employeesFor(picker).find(function (emp) {
      return String(emp.id) === id;
    });
    show(picker, id, listed ? (listed.label || '') : (p.fallback ? p.fallback.value : ''));
  }

  const pickers = Array.from(document.querySelectorAll('[data-emp-picker]')).filter(function (picker) {
    const p = parts(picker);
    return p.search && p.id && p.selected && p.results;
  });

  pickers.forEach(function (picker) {
    const p = parts(picker);

    p.search.addEventListener('input', function () {
      p.id.value = '';
      p.selected.hidden = true;
      render(picker, matches(employeesFor(picker), p.search.value));
    });

    p.search.addEventListener('focus', function () {
      render(picker, matches(employeesFor(picker), p.search.value));
    });

    const form = picker.closest('form');
    if (!form) {
      return;
    }
    form.addEventListener('submit', function (event) {
      // A row dialog's Cancel submits with method="dialog" to close it.
      const submitter = event.submitter;
      if (submitter && (submitter.getAttribute('formmethod') === 'dialog' || submitter.hasAttribute('formnovalidate'))) {
        return;
      }
      if (parseInt(p.id.value, 10) > 0) {
        return;
      }
      event.preventDefault();
      picker.classList.add('is-invalid');
      p.search.focus();
    });
    // Setting a hidden input's value sets its default too, so reset() alone
    // would bring back the last choice. Clear both here, before reset() runs,
    // and redraw once it has -- after row-dialog.js has filled them, if it did.
    form.addEventListener('reset', function () {
      p.id.value = '';
      if (p.fallback) {
        p.fallback.value = '';
      }
      setTimeout(function () {
        sync(picker);
      }, 0);
    });
  });

  document.addEventListener('click', function (event) {
    pickers.forEach(function (picker) {
      if (!picker.contains(event.target)) {
        parts(picker).results.classList.remove('is-open');
      }
    });
  });

  // row-dialog.js has just copied a row's employee into a dialog's picker.
  document.addEventListener('row-dialog:filled', function (event) {
    pickers.forEach(function (picker) {
      if (event.target.contains(picker)) {
        sync(picker);
      }
    });
  });
})();
