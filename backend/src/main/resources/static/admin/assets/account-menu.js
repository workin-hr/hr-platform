(function () {
  const accountBtn = document.getElementById('homeAccountBtn');
  const accountWrap = document.querySelector('.home-topbar-account-wrap');
  if (!accountBtn || !accountWrap) {
    return;
  }

  function closeMenu() {
    accountBtn.setAttribute('aria-expanded', 'false');
    accountWrap.classList.remove('is-open');
  }

  function openMenu() {
    accountBtn.setAttribute('aria-expanded', 'true');
    accountWrap.classList.add('is-open');
  }

  function toggleMenu() {
    if (accountWrap.classList.contains('is-open')) {
      closeMenu();
    } else {
      openMenu();
    }
  }

  accountBtn.addEventListener('click', (e) => {
    e.preventDefault();
    e.stopPropagation();
    toggleMenu();
  });

  document.addEventListener('click', (e) => {
    if (!accountWrap.contains(e.target)) {
      closeMenu();
    }
  });

  document.addEventListener('keydown', (e) => {
    if (e.key === 'Escape') {
      closeMenu();
    }
  });

  accountWrap.querySelectorAll('.home-topbar-menu a[href]').forEach((link) => {
    link.addEventListener('click', closeMenu);
  });

  window.addEventListener('pageshow', closeMenu);
  window.addEventListener('pagehide', closeMenu);

  closeMenu();
})();
