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
  thresholds: {
    'http_req_duration': ['p(95)<1500'],
    'http_req_failed': ['rate<0.02'],
  },
};

export function setup() {
  const jar = http.cookieJar();
  const login = http.post(`${BASE}/admin/login`, {
    username: __ENV.PERF_ADMIN_USER || 'admin',
    password: __ENV.PERF_ADMIN_PASSWORD || 'devpassword',
  });
  if (login.status >= 400) {
    throw new Error(
      `admin login failed (${login.status}). Set PERF_ADMIN_PASSWORD, or note ` +
      `that the session cookie is Secure and this stack is plain HTTP -- see ` +
      `docs/operations/running-the-backend-for-client-developers.md.`,
    );
  }
  return { cookies: jar.cookiesForURL(BASE) };
}

export default function () {
  check(http.get(`${BASE}/admin/companies`), {
    'companies page renders': (r) => r.status === 200,
  });
  // Payroll and attendance are where the enrichment loops are.
  check(http.get(`${BASE}/admin/payroll?page=3`), {
    'payroll page renders': (r) => r.status === 200,
  });
}
