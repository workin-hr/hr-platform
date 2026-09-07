// A stored image whose file is gone draws the browser's broken-image icon,
// which is worse than the placeholder it would otherwise have had.
//
// Legacy avoids this server-side: company_logo_src() calls is_file() on the
// upload path before using it. That check is not available here -- the stored
// value is a URL, which in a real deployment may be served from another host --
// so the fallback happens when the load actually fails.
//
// One capturing listener, because `error` on an <img> does not bubble. It runs
// once per broken image and never for a good one.
(function () {
  document.addEventListener('error', function (event) {
    const img = event.target;
    if (!(img instanceof HTMLImageElement) || !img.dataset.fallbackInitials) {
      return;
    }
    const avatar = document.createElement('span');
    avatar.className = img.dataset.fallbackClass || 'emp-tbl-avatar';
    avatar.setAttribute('aria-hidden', 'true');
    avatar.textContent = img.dataset.fallbackInitials;
    img.replaceWith(avatar);
  }, true);
})();
