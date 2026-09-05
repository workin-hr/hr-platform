(function () {
  const form = document.getElementById('loginForm');
  const userTypeInput = document.getElementById('userType');
  const phoneField = document.getElementById('phoneField');
  const phoneInput = document.getElementById('loginPhone');
  const passwordInput = document.getElementById('loginPassword');
  const indicator = document.getElementById('typeIndicator');
  const submitBtn = form?.querySelector('.login-submit');
  const validationBox = document.getElementById('loginValidation');

  const msgRequired = form?.dataset.msgRequired || 'Required';
  const countrySelect = document.getElementById('loginCountryCode');
  const msgInvalidPhone = form?.dataset.msgInvalidPhone || 'Invalid phone';

  const msgFill = form?.dataset.msgFill || 'Please fill in the required fields first';

  const validateLoginPhone = window.WorkinPhoneValidator
    ? window.WorkinPhoneValidator.bindCountryPhone(countrySelect, phoneInput)
    : function () {
        return true;
      };

  const adminOnlyLogin = form?.dataset.adminOnly === '1';
  const tabs = {
    admin: document.getElementById('adminBtn'),
    company: document.getElementById('companyBtn'),
    hr: document.getElementById('hrBtn'),
  };

  const tabOrder = ['admin', 'company', 'hr'];

  function tabIndex(type) {
    const i = tabOrder.indexOf(type);
    return i >= 0 ? i : 0;
  }

  function updateIndicator(type) {
    if (!indicator) return;
    indicator.style.setProperty('--tab-index', String(tabIndex(type)));
  }

  function hideValidation() {
    validationBox?.setAttribute('hidden', '');
    phoneInput?.classList.remove('login-invalid');
    passwordInput?.classList.remove('login-invalid');
  }

  function showValidation(message, focusEl) {
    if (validationBox) {
      validationBox.textContent = message;
      validationBox.removeAttribute('hidden');
    }
    document.body.classList.add('login-shake');
    window.setTimeout(() => document.body.classList.remove('login-shake'), 500);
    focusEl?.focus();
  }

  function validateForm() {
    hideValidation();
    const type = userTypeInput.value;
    const needPhone = type !== 'admin';
    const phoneEmpty = needPhone && phoneInput.value.trim() === '';
    const passEmpty = passwordInput.value.trim() === '';

    if (phoneEmpty) {
      phoneInput.classList.add('login-invalid');
    }
    if (passEmpty) {
      passwordInput.classList.add('login-invalid');
    }

    if (phoneEmpty || passEmpty) {
      const focusEl = phoneEmpty ? phoneInput : passwordInput;
      showValidation(msgFill, focusEl);
      return false;
    }

    if (needPhone && !validateLoginPhone(false)) {
      phoneInput.classList.add('login-invalid');
      showValidation(msgInvalidPhone, phoneInput);
      return false;
    }

    return true;
  }

  function setLoginType(type) {
    userTypeInput.value = type;
    updateIndicator(type);
    hideValidation();

    const isAdmin = type === 'admin';
    if (isAdmin) {
      phoneField.setAttribute('hidden', '');
      phoneInput.removeAttribute('required');
      passwordInput.focus();
    } else {
      phoneField.removeAttribute('hidden');
      phoneInput.setAttribute('required', '');
      phoneInput.focus();
    }

    Object.entries(tabs).forEach(([key, btn]) => {
      if (!btn) return;
      const active = key === type;
      btn.classList.toggle('is-active', active);
      btn.setAttribute('aria-selected', active ? 'true' : 'false');
    });
  }

  if (!adminOnlyLogin) {
    tabs.admin?.addEventListener('click', () => setLoginType('admin'));
    tabs.company?.addEventListener('click', () => setLoginType('company'));
    tabs.hr?.addEventListener('click', () => setLoginType('hr'));
  } else if (userTypeInput) {
    userTypeInput.value = 'admin';
    phoneField?.setAttribute('hidden', '');
    phoneInput?.removeAttribute('required');
  }

  [phoneInput, passwordInput].forEach((el) => {
    el?.addEventListener('input', hideValidation);
  });

  if (!adminOnlyLogin) {
    updateIndicator(userTypeInput.value);
    if (userTypeInput.value !== 'admin') {
      phoneField.removeAttribute('hidden');
      phoneInput.setAttribute('required', '');
    }
  }

  form?.addEventListener('submit', (e) => {
    submitBtn?.classList.remove('is-loading');
    if (!validateForm()) {
      e.preventDefault();
      return;
    }
    submitBtn?.classList.add('is-loading');
  });

  document.body.classList.remove('login-shake');

  const passwordToggle = document.getElementById('passwordToggle');
  passwordToggle?.addEventListener('click', () => {
    const visible = passwordInput.type === 'text';
    passwordInput.type = visible ? 'password' : 'text';
    passwordToggle.classList.toggle('is-visible', !visible);
    passwordToggle.setAttribute('aria-pressed', visible ? 'false' : 'true');
  });
})();
