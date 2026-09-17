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
      const validatePhone = window.WorkinPhoneValidator.bindCountryPhone(countrySelect, phoneInput);

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
        if (form.hasAttribute('data-phone-keep-untouched')
          && phoneInput.value === openedPhone && countrySelect.value === openedCountry) {
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
