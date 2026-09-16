// The devices page's ?live=1: reload on the interval the page declares, so an
// operator at a site visit sees a terminal's punches arrive without pressing
// refresh. Nothing else on the page depends on it.
(function () {
	var holder = document.querySelector('[data-live-seconds]');
	var seconds = holder ? parseInt(holder.getAttribute('data-live-seconds'), 10) : 0;
	if (seconds > 0) {
		window.setTimeout(function () { window.location.reload(); }, seconds * 1000);
	}
})();

// The issued-token page is the answer to a POST. Refreshing it would post again and issue a
// second token, so the history entry is replaced with the plain page: a refresh then only reloads
// the list, and the token is never shown twice.
(function () {
	if (document.querySelector('[data-issued-token]') && window.history && window.history.replaceState) {
		window.history.replaceState(null, '', '/admin/devices');
	}
})();
