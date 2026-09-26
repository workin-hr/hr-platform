(function (global) {
  'use strict';

  // The browser no longer judges a number (D-291, ADR-0020). The server reads
  // every phone with libphonenumber, and a length or prefix rule here would
  // either block a number the server accepts -- a Bahraini mobile its row never
  // listed, a number typed with +, spaces or Arabic-Indic digits -- or pass one
  // it refuses. What stays is the one thing the form itself knows: a phone
  // needs a country to be read in. window.WorkinPhoneCountriesRules is still
  // published for display, and enforces nothing.

  function digitsOnly(value) {
    return String(value || '').replace(/\D/g, '');
  }

  global.WorkinPhoneValidator = {
    digitsOnly: digitsOnly,
    bindCountryPhone: function (countrySelect, phoneInput) {
      if (!countrySelect || !phoneInput) {
        return function () {
          return true;
        };
      }

      return function validate(allowEmpty) {
        if (!String(phoneInput.value || '').trim()) {
          return !!allowEmpty;
        }
        return String(countrySelect.value || '').trim() !== '';
      };
    },
  };
})(window);
