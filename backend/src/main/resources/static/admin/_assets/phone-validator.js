(function (global) {
  'use strict';

  function digitsOnly(value) {
    return String(value || '').replace(/\D/g, '');
  }

  function ruleFor(countryCode) {
    var rules = global.WorkinPhoneCountriesRules || {};
    return rules[countryCode] || null;
  }

  /**
   * Excel often drops the leading 0 (010… → 10…). Restore it when prefixes start with 0.
   */
  function normalizeLocal(countryCode, localPhone) {
    var digits = digitsOnly(localPhone);
    if (!digits) {
      return '';
    }

    var rule = ruleFor(countryCode);
    if (!rule) {
      return digits;
    }

    var length = parseInt(rule.phone_length, 10) || 0;
    var prefixes = Array.isArray(rule.phone_prefixes) ? rule.phone_prefixes : [];

    if (length > 0 && digits.length === length) {
      return digits;
    }

    if (length > 0 && digits.length === length - 1) {
      for (var i = 0; i < prefixes.length; i++) {
        var prefix = String(prefixes[i] || '');
        if (!prefix || prefix.charAt(0) !== '0') {
          continue;
        }
        var withoutZero = prefix.slice(1);
        if (withoutZero && digits.indexOf(withoutZero) === 0) {
          return '0' + digits;
        }
      }
    }

    return digits;
  }

  function isValidLocal(countryCode, localPhone) {
    var digits = normalizeLocal(countryCode, localPhone);
    if (!digits) {
      return false;
    }

    var rule = ruleFor(countryCode);
    if (!rule) {
      return false;
    }

    var length = parseInt(rule.phone_length, 10) || 0;
    if (length > 0 && digits.length !== length) {
      return false;
    }

    var prefixes = Array.isArray(rule.phone_prefixes) ? rule.phone_prefixes : [];
    if (!prefixes.length) {
      return true;
    }

    for (var i = 0; i < prefixes.length; i++) {
      var prefix = String(prefixes[i] || '');
      if (prefix && digits.indexOf(prefix) === 0) {
        return true;
      }
    }

    return false;
  }

  function maxLocalDigits(countryCode) {
    var rule = ruleFor(countryCode);
    if (rule && rule.phone_length) {
      return parseInt(rule.phone_length, 10) || 11;
    }
    return 11;
  }

  global.WorkinPhoneValidator = {
    digitsOnly: digitsOnly,
    normalizeLocal: normalizeLocal,
    isValidLocal: isValidLocal,
    maxLocalDigits: maxLocalDigits,
    bindCountryPhone: function (countrySelect, phoneInput, options) {
      options = options || {};
      if (!countrySelect || !phoneInput) {
        return function () {
          return true;
        };
      }

      function syncMaxLength() {
        phoneInput.setAttribute('maxlength', String(maxLocalDigits(countrySelect.value)));
      }

      function validate(allowEmpty) {
        var phone = normalizeLocal(countrySelect.value, phoneInput.value);
        if (!phone) {
          return !!allowEmpty;
        }
        return isValidLocal(countrySelect.value, phone);
      }

      countrySelect.addEventListener('change', syncMaxLength);
      phoneInput.addEventListener('input', function () {
        phoneInput.value = digitsOnly(phoneInput.value);
      });
      phoneInput.addEventListener('blur', function () {
        var normalized = normalizeLocal(countrySelect.value, phoneInput.value);
        if (normalized) {
          phoneInput.value = normalized;
        }
      });
      syncMaxLength();

      return validate;
    },
  };
})(window);
