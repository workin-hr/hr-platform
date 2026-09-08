/* The sidebar as a drawer below 1024px. Above it, this does nothing at all:
   the sidebar is a column and the button that opens it is display:none.

   The breakpoint is written twice -- here and in app-responsive.css -- and a
   media query is what keeps them from drifting: this asks the browser which
   layout is in force rather than measuring the window itself. */
(function () {
  const shell = document.querySelector('.shell');
  const sidebar = document.querySelector('.sidebar');
  const toggle = document.querySelector('.nav-toggle');
  const backdrop = document.querySelector('.nav-backdrop');
  if (!shell || !sidebar || !toggle || !backdrop) {
    return;
  }

  const drawerWidth = window.matchMedia('(max-width: 1024px)');
  const FOCUSABLE = 'a[href], button:not([disabled]), summary, input, select, textarea';

  function isOpen() {
    return shell.classList.contains('nav-open');
  }

  function open() {
    shell.classList.add('nav-open');
    document.body.classList.add('nav-locked');
    toggle.setAttribute('aria-expanded', 'true');
    const first = sidebar.querySelector(FOCUSABLE);
    if (first) {
      first.focus();
    }
  }

  function close(restoreFocus) {
    shell.classList.remove('nav-open');
    document.body.classList.remove('nav-locked');
    toggle.setAttribute('aria-expanded', 'false');
    // Only when the user closed it deliberately. Stealing focus back on a
    // resize would yank it out of whatever they were typing in.
    if (restoreFocus) {
      toggle.focus();
    }
  }

  toggle.addEventListener('click', () => {
    if (isOpen()) {
      close(true);
    } else {
      open();
    }
  });

  backdrop.addEventListener('click', () => close(true));

  // Following a link closes the drawer. Without this the new page renders
  // behind a drawer that is still open, over its own content.
  sidebar.addEventListener('click', (e) => {
    if (drawerWidth.matches && e.target.closest('a[href]')) {
      close(false);
    }
  });

  document.addEventListener('keydown', (e) => {
    if (!isOpen()) {
      return;
    }
    if (e.key === 'Escape') {
      close(true);
      return;
    }
    if (e.key !== 'Tab') {
      return;
    }
    // A drawer over a backdrop is modal: tabbing out of it lands on controls
    // the user cannot see.
    const items = Array.from(sidebar.querySelectorAll(FOCUSABLE))
      .filter((el) => el.offsetParent !== null);
    if (items.length === 0) {
      return;
    }
    const first = items[0];
    const last = items[items.length - 1];
    if (e.shiftKey && document.activeElement === first) {
      e.preventDefault();
      last.focus();
    } else if (!e.shiftKey && document.activeElement === last) {
      e.preventDefault();
      first.focus();
    }
  });

  // Widening the window past the breakpoint turns the drawer back into a
  // column; the open state and the scroll lock have to go with it.
  drawerWidth.addEventListener('change', (e) => {
    if (!e.matches && isOpen()) {
      close(false);
    }
  });
})();
