(function () {
  const menuHome = new WeakMap();

  function getMenu(wrap) {
    return wrap._actionsMenu || wrap.querySelector('.row-actions__menu');
  }

  function rememberHome(menu) {
    if (!menuHome.has(menu)) {
      menuHome.set(menu, {
        parent: menu.parentNode,
        next: menu.nextSibling,
      });
    }
  }

  function restoreMenu(menu) {
    const home = menuHome.get(menu);
    if (!home || !home.parent) {
      return;
    }
    home.parent.insertBefore(menu, home.next);
    menuHome.delete(menu);
  }

  function resetMenu(menu) {
    if (!menu) return;
    menu.style.cssText = '';
    menu.removeAttribute('data-portaled');
  }

  function positionMenu(wrap, menu) {
    const trigger = wrap.querySelector('.row-actions__trigger');
    if (!trigger || !menu) return;

    const isRtl = document.documentElement.getAttribute('dir') === 'rtl';

    menu.style.display = 'flex';
    menu.style.flexDirection = 'column';
    menu.style.gap = '3px';
    menu.style.position = 'fixed';
    menu.style.zIndex = '10050';
    menu.style.minWidth = '10.5rem';
    menu.style.padding = '6px';
    menu.style.margin = '0';
    menu.style.background = '#fff';
    menu.style.border = '1px solid #e5e7eb';
    menu.style.borderRadius = '12px';
    menu.style.boxShadow = '0 10px 40px rgba(15, 23, 42, 0.14)';
    menu.style.visibility = 'hidden';
    menu.style.pointerEvents = 'auto';

    const tr = trigger.getBoundingClientRect();
    const mr = menu.getBoundingClientRect();
    const gap = 6;
    const pad = 8;

    let top = tr.bottom + gap;
    let left = isRtl ? tr.right - mr.width : tr.left;

    if (left + mr.width > window.innerWidth - pad) {
      left = window.innerWidth - mr.width - pad;
    }
    if (left < pad) {
      left = pad;
    }
    if (top + mr.height > window.innerHeight - pad) {
      top = tr.top - mr.height - gap;
    }
    if (top < pad) {
      top = pad;
    }

    menu.style.top = Math.round(top) + 'px';
    menu.style.left = Math.round(left) + 'px';
    menu.style.right = 'auto';
    menu.style.visibility = 'visible';
  }

  function closeAll() {
    document.querySelectorAll('[data-row-actions].is-open').forEach((wrap) => {
      wrap.classList.remove('is-open');
      const trigger = wrap.querySelector('.row-actions__trigger');
      const menu = getMenu(wrap);
      if (trigger) {
        trigger.setAttribute('aria-expanded', 'false');
      }
      if (menu) {
        resetMenu(menu);
        restoreMenu(menu);
        delete wrap._actionsMenu;
      }
    });
  }

  function openMenu(wrap) {
    const menu = wrap.querySelector('.row-actions__menu');
    if (!menu) return;

    closeAll();

    wrap._actionsMenu = menu;
    wrap.classList.add('is-open');

    const trigger = wrap.querySelector('.row-actions__trigger');
    if (trigger) {
      trigger.setAttribute('aria-expanded', 'true');
    }

    rememberHome(menu);
    document.body.appendChild(menu);
    menu.setAttribute('data-portaled', '1');
    positionMenu(wrap, menu);
  }

  document.addEventListener(
    'mousedown',
    (e) => {
      const trigger = e.target.closest('.row-actions__trigger');
      const wrap = trigger?.closest('[data-row-actions]');
      if (!trigger || !wrap) {
        return;
      }

      e.preventDefault();
      e.stopPropagation();

      if (wrap.classList.contains('is-open')) {
        closeAll();
      } else {
        openMenu(wrap);
      }
    },
    true
  );

  document.addEventListener('click', (e) => {
    if (e.target.closest('.row-actions__trigger')) {
      return;
    }
    const menuItem = e.target.closest('.row-actions__menu [role="menuitem"]');
    if (menuItem) {
      // Keep form submits working; close after non-submit actions open a modal.
      if (menuItem.type !== 'submit') {
        setTimeout(closeAll, 0);
      }
      return;
    }
    if (e.target.closest('.row-actions__menu')) {
      return;
    }
    closeAll();
  });

  document.addEventListener('keydown', (e) => {
    if (e.key === 'Escape') {
      closeAll();
    }
  });

  window.addEventListener('resize', closeAll);
})();
