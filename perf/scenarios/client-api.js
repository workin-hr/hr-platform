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
    'http_req_duration{endpoint:list}': ['p(95)<800'],
    'http_req_failed': ['rate<0.01'],
  },
};

export function setup() {
  return {
    token: login(__ENV.PERF_PHONE || '+201100246011', __ENV.PERF_PASSWORD || 'password'),
  };
}

export default function (data) {
  const auth = {
    headers: { Authorization: `Bearer ${data.token}` },
    tags: { endpoint: 'list' },
  };

  // Page 1 and a deep page. The gap between them is the measurement that
  // matters: an offset scan degrades with depth while a keyset does not, and
  // only one of those shows up on page 1.
  ok(http.get(`${API}/attendance/list.php?page=1`, auth), 'attendance page 1');
  ok(http.get(`${API}/attendance/list.php?page=40`, auth), 'attendance deep page');

  check(http.get(`${API}/employees/list.php?page=1`, auth), {
    'employees listed': (r) => r.status === 200,
  });
}
