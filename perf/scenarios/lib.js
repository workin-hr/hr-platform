// Shared setup. Kept small on purpose: a scenario should read as the thing it
// measures, not as a framework.
import http from 'k6/http';
import { check } from 'k6';

export const BASE = __ENV.BASE_URL || 'http://app:8080';
export const API = `${BASE}/apis/api`;

// A stage shape that separates "slow because it is warming up" from "slow
// because it is loaded": the JIT and the connection pool both need the ramp.
export const RAMP = [
  { duration: '20s', target: 5 },
  { duration: '40s', target: 20 },
  { duration: '20s', target: 0 },
];

export function ok(response, name) {
  return check(response, {
    [`${name}: 2xx`]: (r) => r.status >= 200 && r.status < 300,
  });
}

/**
 * Signs in with a seeded account and returns the bearer token.
 *
 * The seed is sanitised, so these credentials are fake by construction --
 * every phone number and password in it was replaced when the copy was taken.
 */
export function login(phone, password) {
  const response = http.post(
    `${API}/auth/login_employee`,
    JSON.stringify({ phone, password }),
    { headers: { 'Content-Type': 'application/json' } },
  );
  if (response.status !== 200) {
    throw new Error(
      `login failed (${response.status}). The seed's accounts differ between ` +
      `dumps -- set PERF_PHONE and PERF_PASSWORD, or run against the dev seed.`,
    );
  }
  return JSON.parse(response.body).token;
}
