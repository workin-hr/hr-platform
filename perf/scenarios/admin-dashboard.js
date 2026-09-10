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
    // Ratchet, from a measured baseline: 1,810 requests at 5 VUs gave
    // p95=213ms, avg=83ms, 0 failures (2026-09-10, laptop). 400 leaves room
    // for a busier machine; 1500 was a guess and would have caught nothing.
    'http_req_duration': ['p(95)<400'],
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
//   deploy/e2e/run.sh            # brings up the TLS proxy and mints its cert
//   BASE_URL=https://127.0.0.1:8443 ./run.sh admin-dashboard
//
// NOT `docker compose -f compose.local.yaml -f e2e/compose.proxy.yaml`, which
// this comment used to say. compose.proxy.yaml carries `name: workin-integration`
// and a later file's project name wins the merge, so that command runs the LOCAL
// stack's definitions inside the INTEGRATION project -- recreating its
// containers and attaching the local database to workin-integration_db-data.
// It also fails outright without E2E_TLS_DIR and a generated certificate, which
// deploy/e2e/run.sh is what provides.
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
