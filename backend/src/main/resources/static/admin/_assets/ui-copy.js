// A copy button beside every value marked data-copy (D-288): an employee code,
// a phone number, a terminal serial, a one-time token. Those are the values an
// administrator carries into a phone call, a message or another system, and
// selecting a number inside a table cell by dragging is fiddly on a desktop
// and nearly impossible on a phone.
//
//   <span data-copy>E008288</span>            copies the text
//   <span data-copy="+20 0100...">…</span>    copies the attribute instead
//
// One delegated listener for the page, and one polite live region, so a list
// of two hundred rows costs two hundred small buttons and nothing more.
(function () {
  const strings = (document.getElementById('ui-strings') || {}).dataset || {};
  const label = strings.strCopy || 'Copy';
  const done = strings.strCopied || 'Copied';
  const failed = strings.strCopyFailed || 'Could not copy';

  const ICON = '<svg viewBox="0 0 24 24" width="14" height="14" fill="none" stroke="currentColor"'
    + ' stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">'
    + '<rect x="9" y="9" width="13" height="13" rx="2"/><path d="M5 15H4a2 2 0 0 1-2-2V4a2 2 0 0 1 2-2h9a2 2 0 0 1 2 2v1"/></svg>';
  const CHECK = '<svg viewBox="0 0 24 24" width="14" height="14" fill="none" stroke="currentColor"'
    + ' stroke-width="2.5" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path d="M20 6L9 17l-5-5"/></svg>';

  const live = document.createElement('div');
  live.className = 'visually-hidden';
  live.setAttribute('aria-live', 'polite');
  document.body.appendChild(live);

  function valueOf(element) {
    const own = element.getAttribute('data-copy');
    if (own && own.trim()) {
      return own.trim();
    }
    return ('value' in element && element.tagName === 'INPUT' ? element.value : element.textContent).trim();
  }

  function decorate(root) {
    root.querySelectorAll('[data-copy]:not([data-copy-ready])').forEach(function (element) {
      element.setAttribute('data-copy-ready', '');
      if (!valueOf(element) || valueOf(element) === '—') {
        return;
      }
      const button = document.createElement('button');
      button.type = 'button';
      button.className = 'ui-copy';
      button.setAttribute('aria-label', label + ': ' + valueOf(element));
      button.title = label;
      button.innerHTML = ICON;
      element.insertAdjacentElement('afterend', button);
    });
  }

  function fallbackCopy(text) {
    const area = document.createElement('textarea');
    area.value = text;
    area.setAttribute('readonly', '');
    area.style.position = 'fixed';
    area.style.opacity = '0';
    document.body.appendChild(area);
    area.select();
    let ok = false;
    try {
      ok = document.execCommand('copy');
    } catch (_) {
      ok = false;
    }
    area.remove();
    return ok ? Promise.resolve() : Promise.reject(new Error('copy'));
  }

  function copy(text) {
    if (navigator.clipboard && window.isSecureContext) {
      return navigator.clipboard.writeText(text).catch(function () { return fallbackCopy(text); });
    }
    return fallbackCopy(text);
  }

  document.addEventListener('click', function (event) {
    const button = event.target.closest('.ui-copy');
    if (!button) {
      return;
    }
    event.preventDefault();
    event.stopPropagation();
    const source = button.previousElementSibling;
    if (!source || !source.hasAttribute('data-copy')) {
      return;
    }
    copy(valueOf(source)).then(function () {
      button.classList.add('is-copied');
      button.innerHTML = CHECK;
      button.title = done;
      live.textContent = done;
    }, function () {
      button.classList.add('is-failed');
      button.title = failed;
      live.textContent = failed;
    }).then(function () {
      setTimeout(function () {
        button.classList.remove('is-copied', 'is-failed');
        button.innerHTML = ICON;
        button.title = label;
      }, 1600);
    });
  });

  decorate(document);
  // Rows a script adds later (a live devices table, a dialog) get theirs too.
  new MutationObserver(function (records) {
    records.forEach(function (record) {
      record.addedNodes.forEach(function (node) {
        if (node.nodeType === 1) {
          decorate(node.hasAttribute && node.hasAttribute('data-copy') ? node.parentNode || node : node);
        }
      });
    });
  }).observe(document.body, { childList: true, subtree: true });
})();
