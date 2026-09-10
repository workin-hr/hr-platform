// Surface 2: the client API the Flutter and desktop apps call.
//
// Reads dominate here, and the seed's volumes are what make them interesting:
// a list endpoint that is instant against five fabricated rows behaves
// differently against 3,783 employees and 44,756 attendance rows.
import http from 'k6/http';
import { check } from 'k6';
import { API, RAMP, login, ok } from './lib.js';

export const options = {
  stages: RAMP,
  thresholds: {
    // Ratchet, from a measured baseline: 22,417 requests at 20 VUs gave
    // p95=107ms, avg=33ms, 0 failures (2026-09-10, laptop). 250 leaves room
    // for a busier machine while still catching a real regression -- 800 would
    // have caught nothing.
    'http_req_duration{endpoint:list}': ['p(95)<250'],
    'http_req_failed': ['rate<0.01'],
  },
};

export function setup() {
  // An HR account in the seed's busiest company, chosen by measurement: an
  // ordinary employee sees only their own rows -- often zero -- so the same
  // request against the wrong account times an empty result set and reports a
  // very fast number for nothing at all. This one has real pages behind it.
  return {
    token: login(__ENV.PERF_PHONE || '01000007868', __ENV.PERF_PASSWORD || 'devpassword'),
  };
}

export default function (data) {
  const auth = {
    headers: { Authorization: `Bearer ${data.token}` },
    tags: { endpoint: 'list' },
  };

  // Page 1 against a deep page. The gap between them is the measurement that
  // matters: an offset scan degrades with depth while a keyset does not, and
  // only one of those shows up on page 1. Measured flat at this volume --
  // 47ms, 47ms, 44ms for pages 1, 10 and 25 -- so a gap appearing here is a
  // regression, not a discovery.
  ok(http.get(`${API}/attendance/list.php?page=1`, auth), 'attendance page 1');
  ok(http.get(`${API}/attendance/list.php?page=25`, auth), 'attendance deep page');

  // Listing employees is refused for role=employee (403) and allowed for HR.
  // With the wrong account this line measures the authorization check.
  check(http.get(`${API}/employees/list.php?page=1`, auth), {
    'employees listed': (r) => r.status === 200,
  });
}
