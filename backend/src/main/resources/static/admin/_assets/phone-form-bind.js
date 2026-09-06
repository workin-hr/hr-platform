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
      const validatePhone = window.WorkinPhoneValidator.bindCountryPhone(countrySelect, phoneInput);

      form.addEventListener('submit', function (event) {
        const phone = phoneInput.value.trim();
        if (!phone) {
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
