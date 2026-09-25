// Date and time pickers in the page's language (D-288).
//
// The browser's own <input type="date"> takes its format and its calendar from
// the browser's locale, not the page's: an Arabic dashboard showed
// "mm/dd/yyyy" in English, differently in every browser, with a calendar that
// ignores RTL and the Saturday week. flatpickr (vendor/, MIT) draws one picker
// everywhere, in Arabic with Saturday first when the page is Arabic.
//
// The server contract does not change. The original input keeps its name and
// receives the same ISO value it always did -- 2026-09-26, 14:30,
// 2026-09-26T14:30 -- while a second, visible input shows it as a person
// reads it. On phones flatpickr hands over to the native picker, which is the
// better control there.
//
// An input opts out with data-no-picker.
(function () {
  if (typeof window.flatpickr !== 'function') {
    return;
  }
  const arabic = document.documentElement.lang === 'ar';
  const locale = arabic && window.flatpickr.l10ns && window.flatpickr.l10ns.ar
    ? Object.assign({}, window.flatpickr.l10ns.ar) : undefined;

  function options(input) {
    const type = input.getAttribute('type');
    const seconds = input.step && Number(input.step) > 0 && Number(input.step) < 60;
    const base = {
      locale: locale,
      altInput: true,
      allowInput: true,
      disableMobile: false,
      // The month as text beside the arrows: the dropdown sized itself to Latin
      // names and clipped the Arabic ones ("سبتمبر" read "ستمبر").
      monthSelectorType: 'static',
      minDate: input.min || null,
      maxDate: input.max || null,
      onReady: function (_, __, instance) {
        if (instance.altInput) {
          instance.altInput.classList.add('ui-picker');
          instance.altInput.setAttribute('dir', 'auto');
          if (input.id) {
            // The label still names the field a person types into.
            const label = document.querySelector('label[for="' + CSS.escape(input.id) + '"]');
            if (label) {
              instance.altInput.id = input.id + '-picker';
              label.htmlFor = instance.altInput.id;
            }
          }
          if (input.getAttribute('aria-label')) {
            instance.altInput.setAttribute('aria-label', input.getAttribute('aria-label'));
          }
        }
      },
    };
    if (type === 'time') {
      return Object.assign(base, {
        enableTime: true, noCalendar: true, time_24hr: !arabic, enableSeconds: seconds,
        dateFormat: seconds ? 'H:i:S' : 'H:i', altFormat: seconds ? 'h:i:S K' : 'h:i K',
      });
    }
    if (type === 'datetime-local') {
      return Object.assign(base, {
        enableTime: true, time_24hr: !arabic, enableSeconds: seconds,
        dateFormat: seconds ? 'Y-m-d\\TH:i:S' : 'Y-m-d\\TH:i',
        altFormat: seconds ? 'd/m/Y h:i:S K' : 'd/m/Y h:i K',
      });
    }
    return Object.assign(base, { dateFormat: 'Y-m-d', altFormat: 'd/m/Y' });
  }

  function attach(input) {
    if (input._flatpickr || input.hasAttribute('data-no-picker') || !input.closest('.main, .modal')) {
      return;
    }
    window.flatpickr(input, options(input));
  }

  function scan(root) {
    root.querySelectorAll('input[type="date"], input[type="time"], input[type="datetime-local"]')
      .forEach(attach);
  }

  // A script that fills an input by assigning .value (row-dialog.js, a reset)
  // updates the hidden original but not the visible copy; bring it along.
  function refresh(input) {
    if (input && input._flatpickr) {
      input._flatpickr.setDate(input.value || null, false);
    }
  }

  document.addEventListener('row-dialog:filled', function (event) {
    event.target.querySelectorAll('input').forEach(refresh);
  });
  document.addEventListener('reset', function (event) {
    const form = event.target;
    setTimeout(function () {
      form.querySelectorAll('input').forEach(refresh);
    }, 0);
  }, true);

  scan(document);
  new MutationObserver(function (records) {
    records.forEach(function (record) {
      record.addedNodes.forEach(function (node) {
        if (node.nodeType === 1 && !node.closest('.flatpickr-calendar')) {
          if (node.matches && node.matches('input')) {
            if (['date', 'time', 'datetime-local'].includes(node.getAttribute('type'))) {
              attach(node);
            }
          } else {
            scan(node);
          }
        }
      });
    });
  }).observe(document.body, { childList: true, subtree: true });

})();
