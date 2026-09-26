// A search box for every long drop-down (D-288).
//
// A select of eight or more options becomes a combobox: a button showing the
// choice, and a popup with a search field over the filtered list. The native
// <select> stays in the form, visually hidden, and remains the single source
// of truth: it is what the form posts, what `required` validates, and what the
// cascade scripts (org-filter-cascade.js, request-filter-cascade.js,
// row-dialog.js) read, rebuild and set. Every change made here is made to it,
// with the `input` and `change` events a user's choice would fire; every
// change made to it -- new options, a new value, disabled -- is mirrored back.
//
// Matching ignores case and Arabic spelling variants a typist does not control:
// tashkeel and tatweel are dropped, أ إ آ ٱ read as ا, ة as ه, ى as ي.
//
// A select opts out with data-no-search, or in below the threshold with
// data-search.
(function () {
  const THRESHOLD = 8;
  const strings = (document.getElementById('ui-strings') || {}).dataset || {};
  const placeholder = strings.strSelectSearch || 'Search…';
  const emptyText = strings.strSelectNoResults || 'No matches';
  const nativeValue = Object.getOwnPropertyDescriptor(HTMLSelectElement.prototype, 'value');
  const nativeIndex = Object.getOwnPropertyDescriptor(HTMLSelectElement.prototype, 'selectedIndex');
  let sequence = 0;
  let current = null;

  function normalize(text) {
    return (text || '')
      .toLowerCase()
      .replace(/[ً-ٰٟـ]/g, '')
      .replace(/[أإآٱ]/g, 'ا')
      .replace(/ة/g, 'ه')
      .replace(/ى/g, 'ي')
      .replace(/\s+/g, ' ')
      .trim();
  }

  function eligible(select) {
    if (select.multiple || select.size > 1 || select.hasAttribute('data-no-search')
        || select.classList.contains('pager-size-select') || select.closest('.ui-select')
        || !select.closest('.main, .modal')) {
      return false;
    }
    return select.hasAttribute('data-search') || select.options.length >= THRESHOLD;
  }

  function labelFor(select) {
    let label = select.id ? document.querySelector('label[for="' + CSS.escape(select.id) + '"]') : null;
    if (!label) {
      label = select.closest('label');
    }
    return label;
  }

  function enhance(select) {
    const id = 'ui-select-' + (++sequence);
    const wrap = document.createElement('div');
    wrap.className = 'ui-select';
    select.parentNode.insertBefore(wrap, select);
    wrap.appendChild(select);
    select.classList.add('ui-select__native');
    select.tabIndex = -1;
    select.setAttribute('aria-hidden', 'true');

    const button = document.createElement('button');
    button.type = 'button';
    button.className = 'ui-select__button';
    button.setAttribute('role', 'combobox');
    button.setAttribute('aria-haspopup', 'listbox');
    button.setAttribute('aria-expanded', 'false');
    button.setAttribute('aria-controls', id + '-list');
    const label = labelFor(select);
    if (label) {
      if (!label.id) {
        label.id = id + '-label';
      }
      button.setAttribute('aria-labelledby', label.id + ' ' + id + '-value');
    } else if (select.getAttribute('aria-label')) {
      button.setAttribute('aria-label', select.getAttribute('aria-label'));
    }
    const valueText = document.createElement('span');
    valueText.className = 'ui-select__value';
    valueText.id = id + '-value';
    button.appendChild(valueText);
    wrap.appendChild(button);

    const popup = document.createElement('div');
    popup.className = 'ui-select__popup';
    popup.hidden = true;
    const search = document.createElement('input');
    search.type = 'search';
    search.className = 'ui-select__search';
    search.placeholder = placeholder;
    search.setAttribute('aria-label', placeholder);
    search.setAttribute('role', 'searchbox');
    search.setAttribute('aria-controls', id + '-list');
    search.setAttribute('aria-autocomplete', 'list');
    search.autocomplete = 'off';
    const list = document.createElement('ul');
    list.className = 'ui-select__list';
    list.id = id + '-list';
    list.setAttribute('role', 'listbox');
    const empty = document.createElement('p');
    empty.className = 'ui-select__empty';
    empty.textContent = emptyText;
    empty.hidden = true;
    popup.append(search, list, empty);
    // Inside the dialog when there is one: the dialog's backdrop sits above
    // everything outside it, so a popup in <body> opened underneath it, and a
    // click on the list landed on the backdrop and closed the dialog.
    (select.closest('.modal-bg') || document.body).appendChild(popup);

    const state = { select: select, wrap: wrap, button: button, valueText: valueText, popup: popup,
      search: search, list: list, empty: empty, items: [], active: -1 };

    function sync() {
      const option = select.options[nativeIndex.get.call(select)];
      valueText.textContent = option ? option.text : '';
      button.disabled = select.disabled;
      button.classList.toggle('is-placeholder', !option || option.value === '');
      if (select.required) {
        button.setAttribute('aria-required', 'true');
      }
    }
    state.sync = sync;

    // A script that sets .value or .selectedIndex fires no event; these
    // instance properties route through the prototype's and then re-read it.
    Object.defineProperty(select, 'value', {
      configurable: true,
      get: function () { return nativeValue.get.call(select); },
      set: function (v) { nativeValue.set.call(select, v); sync(); },
    });
    Object.defineProperty(select, 'selectedIndex', {
      configurable: true,
      get: function () { return nativeIndex.get.call(select); },
      set: function (v) { nativeIndex.set.call(select, v); sync(); },
    });

    select.addEventListener('change', sync);
    select.addEventListener('focus', function () { button.focus(); });
    select.addEventListener('invalid', function () {
      button.classList.add('is-invalid');
    });
    const form = select.form;
    if (form) {
      form.addEventListener('reset', function () { setTimeout(sync, 0); });
    }
    new MutationObserver(function () {
      sync();
      if (current === state) {
        render(state);
      }
    }).observe(select, { childList: true, subtree: true, attributes: true,
      attributeFilter: ['disabled', 'selected', 'label', 'hidden'] });

    button.addEventListener('click', function () {
      if (current === state) {
        close(true);
      } else {
        open(state, '');
      }
    });
    button.addEventListener('keydown', function (event) {
      if (['ArrowDown', 'ArrowUp', 'Enter', ' '].includes(event.key)) {
        event.preventDefault();
        open(state, '');
      } else if (event.key.length === 1 && !event.ctrlKey && !event.metaKey && !event.altKey) {
        event.preventDefault();
        open(state, event.key);
      }
    });
    search.addEventListener('input', function () {
      render(state);
    });
    search.addEventListener('keydown', function (event) {
      if (event.key === 'ArrowDown' || event.key === 'ArrowUp') {
        event.preventDefault();
        move(state, event.key === 'ArrowDown' ? 1 : -1);
      } else if (event.key === 'Home' || event.key === 'End') {
        event.preventDefault();
        state.active = -1;
        move(state, event.key === 'Home' ? 1 : -1, event.key === 'End');
      } else if (event.key === 'Enter') {
        event.preventDefault();
        const item = state.items[state.active];
        if (item && item.getAttribute('aria-disabled') !== 'true') {
          choose(state, item);
        }
      } else if (event.key === 'Escape') {
        event.preventDefault();
        event.stopPropagation();
        close(true);
      } else if (event.key === 'Tab') {
        // The search box lives at the end of <body> or its dialog, so the
        // browser's own Tab would leave from there. Leave from the field.
        // Handled here, so modal-a11y's wrap-around does not act on it too.
        event.preventDefault();
        event.stopPropagation();
        close(false);
        neighbour(state.button, event.shiftKey ? -1 : 1).focus();
      }
    });
    list.addEventListener('mousedown', function (event) {
      event.preventDefault();
    });
    list.addEventListener('click', function (event) {
      const item = event.target.closest('[role="option"]');
      if (item && item.getAttribute('aria-disabled') !== 'true') {
        choose(state, item);
      }
    });

    sync();
    return state;
  }

  function render(state) {
    const query = normalize(state.search.value);
    const fragment = document.createDocumentFragment();
    const selected = nativeIndex.get.call(state.select);
    state.items = [];
    state.active = -1;
    let group = null;
    Array.from(state.select.options).forEach(function (option, index) {
      if (option.hidden) {
        return;
      }
      if (query && !normalize(option.text).includes(query)) {
        return;
      }
      const parent = option.parentElement;
      if (parent && parent.tagName === 'OPTGROUP' && parent !== group) {
        group = parent;
        const heading = document.createElement('li');
        heading.className = 'ui-select__group';
        heading.setAttribute('role', 'presentation');
        heading.textContent = parent.label;
        fragment.appendChild(heading);
      }
      const item = document.createElement('li');
      item.className = 'ui-select__option';
      item.id = state.list.id + '-' + index;
      item.setAttribute('role', 'option');
      item.dataset.index = String(index);
      item.textContent = option.text;
      if (option.value === '') {
        item.classList.add('is-placeholder');
      }
      if (option.disabled || (option.parentElement && option.parentElement.disabled)) {
        item.setAttribute('aria-disabled', 'true');
      }
      const isSelected = index === selected;
      item.setAttribute('aria-selected', String(isSelected));
      if (isSelected) {
        state.active = state.items.length;
      }
      state.items.push(item);
      fragment.appendChild(item);
    });
    state.list.replaceChildren(fragment);
    state.empty.hidden = state.items.length > 0;
    if (state.active < 0 && query) {
      state.active = state.items.findIndex(function (item) {
        return item.getAttribute('aria-disabled') !== 'true';
      });
    }
    highlight(state);
  }

  function highlight(state) {
    state.items.forEach(function (item, position) {
      item.classList.toggle('is-active', position === state.active);
    });
    const item = state.items[state.active];
    if (item) {
      state.search.setAttribute('aria-activedescendant', item.id);
      item.scrollIntoView({ block: 'nearest' });
    } else {
      state.search.removeAttribute('aria-activedescendant');
    }
  }

  function move(state, step, fromEnd) {
    if (!state.items.length) {
      return;
    }
    let position = fromEnd ? state.items.length : state.active;
    for (let tries = 0; tries < state.items.length; tries++) {
      position = (position + step + state.items.length) % state.items.length;
      if (state.items[position].getAttribute('aria-disabled') !== 'true') {
        state.active = position;
        break;
      }
    }
    highlight(state);
  }

  function place(state) {
    const rect = state.button.getBoundingClientRect();
    const popup = state.popup;
    const width = Math.max(rect.width, 240);
    popup.style.width = width + 'px';
    const rtl = getComputedStyle(state.button).direction === 'rtl';
    const left = rtl ? rect.right - width : rect.left;
    popup.style.left = Math.max(8, Math.min(left, window.innerWidth - width - 8)) + 'px';
    const below = window.innerHeight - rect.bottom;
    const height = Math.min(popup.scrollHeight, 360);
    if (below < height + 12 && rect.top > below) {
      popup.style.top = '';
      popup.style.bottom = (window.innerHeight - rect.top + 4) + 'px';
    } else {
      popup.style.bottom = '';
      popup.style.top = (rect.bottom + 4) + 'px';
    }
  }

  const FOCUSABLE = 'a[href], button:not([disabled]), input:not([disabled]):not([type="hidden"]),'
    + ' select:not([disabled]), textarea:not([disabled]), [tabindex]:not([tabindex="-1"])';

  // The field before or after this one, within its dialog when it has one;
  // the field itself when it is the only one.
  function neighbour(button, step) {
    const scope = button.closest('.modal-bg') || document;
    const fields = Array.from(scope.querySelectorAll(FOCUSABLE)).filter(function (node) {
      return node.offsetParent !== null && !node.closest('.ui-select__popup')
        && node.getAttribute('tabindex') !== '-1';
    });
    const at = fields.indexOf(button);
    if (at < 0 || fields.length < 2) {
      return button;
    }
    return fields[(at + step + fields.length) % fields.length];
  }

  function open(state, seed) {
    if (state.select.disabled) {
      return;
    }
    if (current && current !== state) {
      close(false);
    }
    current = state;
    state.search.value = seed;
    state.popup.hidden = false;
    state.button.setAttribute('aria-expanded', 'true');
    render(state);
    place(state);
    state.search.focus();
  }

  function close(returnFocus) {
    const state = current;
    if (!state) {
      return;
    }
    current = null;
    state.popup.hidden = true;
    state.button.setAttribute('aria-expanded', 'false');
    if (returnFocus) {
      state.button.focus();
    }
  }

  function choose(state, item) {
    const index = Number(item.dataset.index);
    const changed = index !== nativeIndex.get.call(state.select);
    nativeIndex.set.call(state.select, index);
    state.sync();
    state.button.classList.remove('is-invalid');
    close(true);
    if (changed) {
      state.select.dispatchEvent(new Event('input', { bubbles: true }));
      state.select.dispatchEvent(new Event('change', { bubbles: true }));
    }
  }

  document.addEventListener('mousedown', function (event) {
    if (current && !current.popup.contains(event.target) && !current.button.contains(event.target)) {
      close(false);
    }
  });
  window.addEventListener('resize', function () { if (current) { place(current); } });
  document.addEventListener('scroll', function (event) {
    if (current && !current.popup.contains(event.target)) {
      place(current);
    }
  }, true);

  function scan(root) {
    const selects = root.tagName === 'SELECT' ? [root] : root.querySelectorAll('select');
    selects.forEach(function (select) {
      if (eligible(select)) {
        enhance(select);
      }
    });
  }

  scan(document);
  // A cascade that fills a short select past the threshold, or a script that
  // adds a form, gets the search too.
  new MutationObserver(function (records) {
    const seen = new Set();
    records.forEach(function (record) {
      const target = record.target;
      if (target.tagName === 'SELECT' && !seen.has(target)) {
        seen.add(target);
        if (eligible(target)) {
          enhance(target);
        }
      }
      record.addedNodes.forEach(function (node) {
        if (node.nodeType === 1 && node.tagName !== 'OPTION' && !node.closest('.ui-select__popup')) {
          scan(node);
        }
      });
    });
  }).observe(document.body, { childList: true, subtree: true });
})();
