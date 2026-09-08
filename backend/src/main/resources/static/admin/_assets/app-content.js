(function () {
  const root = document.querySelector('.app-content-page');
  if (!root) {
    return;
  }

  const tabs = root.querySelectorAll('[data-app-content-tab]');
  const panels = root.querySelectorAll('[data-app-content-panel]');

  function activate(key) {
    tabs.forEach(function (tab) {
      const active = tab.getAttribute('data-app-content-tab') === key;
      tab.classList.toggle('is-active', active);
      tab.setAttribute('aria-selected', active ? 'true' : 'false');
    });
    panels.forEach(function (panel) {
      panel.classList.toggle('is-active', panel.getAttribute('data-app-content-panel') === key);
    });
    if (window.history && window.history.replaceState) {
      const url = new URL(window.location.href);
      url.searchParams.set('section', key);
      // Keep hub tab=app_content when present.
      if (!url.searchParams.get('tab')) {
        url.searchParams.set('tab', 'app_content');
      }
      window.history.replaceState({}, '', url);
    }
  }

  tabs.forEach(function (tab) {
    tab.addEventListener('click', function () {
      activate(tab.getAttribute('data-app-content-tab') || '');
    });
  });

  const initial = new URL(window.location.href).searchParams.get('section');
  if (initial && root.querySelector('[data-app-content-panel="' + initial + '"]')) {
    activate(initial);
  }

  /** Detect Arabic vs Latin letters and set textarea direction. */
  function detectTextDirection(text, fallback) {
    const sample = String(text || '').trim();
    if (!sample) {
      return fallback === 'ltr' ? 'ltr' : 'rtl';
    }

    const arabic = (sample.match(/[\u0600-\u06FF\u0750-\u077F\u08A0-\u08FF]/g) || []).length;
    const latin = (sample.match(/[A-Za-z]/g) || []).length;

    if (arabic === 0 && latin === 0) {
      return fallback === 'ltr' ? 'ltr' : 'rtl';
    }
    if (arabic === latin) {
      return fallback === 'ltr' ? 'ltr' : 'rtl';
    }
    return arabic > latin ? 'rtl' : 'ltr';
  }

  function applyTextareaDirection(textarea) {
    const name = textarea.getAttribute('name') || '';
    const fallback = name.indexOf('_en') !== -1 ? 'ltr' : 'rtl';
    const dir = detectTextDirection(textarea.value, fallback);
    textarea.setAttribute('dir', dir);
    textarea.style.textAlign = dir === 'rtl' ? 'right' : 'left';
  }

  root.querySelectorAll('textarea[name="content_value_ar"], textarea[name="content_value_en"]').forEach(function (textarea) {
    applyTextareaDirection(textarea);
    textarea.addEventListener('input', function () {
      applyTextareaDirection(textarea);
    });
    textarea.addEventListener('paste', function () {
      setTimeout(function () {
        applyTextareaDirection(textarea);
      }, 0);
    });
  });
})();
