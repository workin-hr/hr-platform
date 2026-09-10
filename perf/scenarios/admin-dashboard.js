// Surface 1: the JTE platform-admin dashboard.
//
// Server-rendered, so the cost is the page's queries plus template render, and
// the enrichment loops live here. This repository has already had one page turn
// into hundreds of round trips through a per-row, per-day lookup (D-114), which
// is the failure this scenario is shaped to expose: watch the deep pages, not
// the landing page.
import http from 'k6/http';
import { check } from 'k6';
import { BASE, RAMP } from './lib.js';

export const options = {
  // Fewer VUs than the API scenarios: an admin dashboard has a handful of
  // concurrent users, and pretending otherwise measures a load that will never
  // happen while hiding the per-request cost that will.
  stages: [
    { duration: '15s', target: 2 },
    { duration: '30s', target: 5 },
    { duration: '15s', target: 0 },
  ],
  insecureSkipTLSVerify: true,
  thresholds: {
    'http_req_duration': ['p(95)<1500'],
    'http_req_failed': ['rate<0.02'],
  },
};

// THIS SCENARIO NEEDS AN https BASE_URL. Measured, not assumed: the admin
// session cookie is `Secure`, and while browsers and curl treat
// http://127.0.0.1 as a secure context and send it anyway, k6 does not. So
// over plain HTTP the session from the GET never comes back on the POST, the
// CSRF token cannot be validated against it, and every sign-in is a 403.
//
// Run it against the TLS proxy:
//   docker compose -f compose.local.yaml -f e2e/compose.proxy.yaml up -d --wait
//   BASE_URL=https://127.0.0.1:8443 ./run.sh admin-dashboard
//
// insecureSkipTLSVerify is in the options below for that proxy's self-signed
// certificate; it is a local measurement, not a trust decision.
function signIn() {
  const page = http.get(`${BASE}/admin/login`);
  const token = page.html().find('input[name="_csrf"]').attr('value');
  const login = http.post(`${BASE}/admin/login`, {
    username: __ENV.PERF_ADMIN_USER || 'admin',
    password: __ENV.PERF_ADMIN_PASSWORD || 'devpassword',
    _csrf: token,
  });
  // A 200 or 403 on /admin/login means the form came back -- it did NOT work.
  return login.status < 400 && !login.url.endsWith('/admin/login');
}

export default function () {
  if (!signIn()) {
    check(null, {
      'admin signed in (needs an https BASE_URL -- see the note above signIn)': () => false,
    });
    return;
  }

  check(http.get(`${BASE}/admin`), {
    'dashboard renders': (r) => r.status === 200,
  });
  // Where the enrichment loops live: a page that reads per row, per day.
  check(http.get(`${BASE}/admin/companies`), {
    'companies page renders': (r) => r.status === 200,
  });
}
