// A stored image whose file is gone draws the browser's broken-image icon,
// which is worse than the placeholder it would otherwise have had.
//
// Legacy avoids this server-side: company_logo_src() calls is_file() on the
// upload path before using it. That check is not available here -- the stored
// value is a URL, which in a real deployment may be served from another host --
// so the fallback happens when the load actually fails.
//
// One capturing listener, because `error` on an <img> does not bubble. This
// script loads at the end of the body, so an image that failed before then
// (a fast 404, a cached miss) fired its `error` with nobody listening; the
// sweep below catches those. An image either names initials to draw in its
// place, or a container to remove (`data-fallback-remove`, a selector for its
// closest ancestor) when the picture was the container's whole point.
(function () {
  function fallback(img) {
    if (img.dataset.fallbackRemove) {
      const box = img.closest(img.dataset.fallbackRemove);
      if (box) {
        box.remove();
        return;
      }
    }
    if (!img.dataset.fallbackInitials) {
      return;
    }
    const avatar = document.createElement('span');
    avatar.className = img.dataset.fallbackClass || 'emp-tbl-avatar';
    avatar.setAttribute('aria-hidden', 'true');
    avatar.textContent = img.dataset.fallbackInitials;
    img.replaceWith(avatar);
  }

  document.addEventListener('error', function (event) {
    const img = event.target;
    if (img instanceof HTMLImageElement) {
      fallback(img);
    }
  }, true);

  // naturalWidth 0 is only a hint: an SVG without intrinsic size can report
  // it after loading fine. decode() rejects only for an image that cannot be
  // drawn, so it is the test, and a good logo is never swapped for initials.
  document.querySelectorAll('img[data-fallback-initials], img[data-fallback-remove]').forEach(function (img) {
    if (img.complete && img.naturalWidth === 0 && typeof img.decode === 'function') {
      img.decode().catch(function () {
        if (img.isConnected) {
          fallback(img);
        }
      });
    }
  });
})();
