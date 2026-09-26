(function () {
  const GROUPS_KEY = 'workin_sidebar_groups';
  const SCROLL_KEY = 'workin_sidebar_scroll';

  const nav = document.querySelector('.sidebar nav');
  if (!nav) {
    return;
  }

  const groups = nav.querySelectorAll('details.nav-group[data-nav-group]');

  function readGroupsState() {
    try {
      return JSON.parse(sessionStorage.getItem(GROUPS_KEY) || '{}');
    } catch (_) {
      return {};
    }
  }

  function saveGroupsState() {
    const state = {};
    groups.forEach((g) => {
      state[g.dataset.navGroup] = g.open;
    });
    sessionStorage.setItem(GROUPS_KEY, JSON.stringify(state));
  }

  function saveScroll() {
    sessionStorage.setItem(SCROLL_KEY, String(nav.scrollTop));
  }

  function restoreGroupsState() {
    const saved = readGroupsState();
    groups.forEach((g) => {
      const id = g.dataset.navGroup;
      if (typeof saved[id] === 'boolean') {
        g.open = saved[id];
      }
    });
    // The current page's group is the one open. A saved state kept every
    // group the operator had ever passed through open, and by the third page
    // the menu was taller than the screen with its last groups out of view.
    const active = nav.querySelector('.nav-link.active');
    const parent = active ? active.closest('details.nav-group') : null;
    if (parent) {
      groups.forEach((g) => {
        g.open = g === parent;
      });
    }
  }

  function restoreScroll() {
    const raw = sessionStorage.getItem(SCROLL_KEY);
    if (raw === null || raw === '') {
      return;
    }
    const top = parseInt(raw, 10);
    if (!Number.isNaN(top) && top >= 0) {
      nav.scrollTop = top;
    }
  }

  restoreGroupsState();
  restoreScroll();

  requestAnimationFrame(() => {
    groups.forEach((g) => {
      g.classList.add('nav-group--ready');
    });
  });

  // An accordion: opening a group closes the others, so the menu stays one
  // screen tall.
  groups.forEach((g) => {
    g.addEventListener('toggle', () => {
      if (g.open) {
        groups.forEach((other) => {
          if (other !== g && other.open) {
            other.open = false;
          }
        });
      }
      saveGroupsState();
    });
  });

  nav.addEventListener('click', (e) => {
    const link = e.target.closest('a[href]');
    if (!link || !nav.contains(link)) {
      return;
    }
    saveScroll();
    saveGroupsState();
  });

  window.addEventListener('beforeunload', () => {
    saveScroll();
    saveGroupsState();
  });
})();
