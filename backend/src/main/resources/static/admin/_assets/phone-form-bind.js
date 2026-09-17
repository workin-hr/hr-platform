(function () {
  function bindPhoneForms() {
    if (!window.WorkinPhoneValidator) {
      return;
    }

    document.querySelectorAll('form').forEach(function (form) {
      if (form.dataset.phoneBound === '1') {
        return;
      }

      const countrySelect = form.querySelector('[name="country_code"]');
      const phoneInput = form.querySelector('input[name="phone"]');
      if (!countrySelect || !phoneInput) {
        return;
      }

      form.dataset.phoneBound = '1';
      const invalidMsg = form.getAttribute('data-invalid-phone-msg') || '';
      const openedPhone = phoneInput.value;
      const openedCountry = countrySelect.value;
      let phoneTyped = false;
      const validatePhone = window.WorkinPhoneValidator.bindCountryPhone(countrySelect, phoneInput);

      phoneInput.addEventListener('input', function () {
        phoneTyped = true;
      });
      // Not in legacy, whose add form opens with a page load. crud.js reopens a
      // form with reset(), which puts the country back without a change event,
      // so the phone kept the last country's maxlength. Sync it once the reset
      // has run.
      form.addEventListener('reset', function () {
        phoneTyped = false;
        setTimeout(function () {
          phoneInput.setAttribute('maxlength', String(window.WorkinPhoneValidator.maxLocalDigits(countrySelect.value)));
        }, 0);
      });

      form.addEventListener('submit', function (event) {
        const phone = phoneInput.value.trim();
        if (!phone) {
          return;
        }
        // Not in legacy. The port's employee edit saves the phone and country
        // code it was opened with unchecked, and checks only a replaced pair:
        // stored rows hold pairs the rules refuse, such as a joined employee's
        // phone with no code (R-019). A form marked data-phone-keep-untouched
        // lets that pair through, rather than refusing a save the server
        // accepts. Legacy refuses it here and again in page.php.
        //
        // Leaving the field rewrites it to digits with a restored leading zero,
        // without an input event. So a phone nobody typed in counts as opened
        // while it normalizes to what it opened as, and the opened value is put
        // back: the server compares the stored text, and would otherwise write
        // the rewrite as a new phone. A typed phone is the admin's and is judged,
        // even when it is the same number written in full.
        if (form.hasAttribute('data-phone-keep-untouched') && countrySelect.value === openedCountry
          && (phoneInput.value === openedPhone || (!phoneTyped
            && window.WorkinPhoneValidator.normalizeLocal(openedCountry, phoneInput.value)
              === window.WorkinPhoneValidator.normalizeLocal(openedCountry, openedPhone)))) {
          phoneInput.value = openedPhone;
          return;
        }
        if (!validatePhone(true)) {
          event.preventDefault();
          window.alert(invalidMsg || 'Invalid phone');
          phoneInput.focus();
        }
      });
    });
  }

  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', bindPhoneForms);
  } else {
    bindPhoneForms();
  }
})();
