// Shared setup. Kept small on purpose: a scenario should read as the thing it
// measures, not as a framework.
import http from 'k6/http';
import { check } from 'k6';

// Host-published port, not `app:8080` on the compose network. Two reasons,
// both learned by running it: the admin dashboard's session cookie is
// `Secure`, and only `localhost`/`127.0.0.1` count as a secure context -- from
// inside the network the cookie is dropped and the scenario measures a login
// page. And a single base URL for all three surfaces beats one exception.
// The cost is Docker's port forwarding in the path, which is constant across
// runs and so does not affect a comparison.
export const BASE = __ENV.BASE_URL || 'http://127.0.0.1:8080';
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
    `${API}/auth/login_employee.php`,
    JSON.stringify({ phone, password }),
    { headers: { 'Content-Type': 'application/json' } },
  );
  if (response.status !== 200) {
    throw new Error(
      `login failed (${response.status}). Accounts differ between seed dumps -- ` +
      `set PERF_PHONE and PERF_PASSWORD. The sanitised seed uses ` +
      `010000NNNNN numbers with the plaintext 'devpassword'.`,
    );
  }
  // The envelope is {success, message, data:{token, employee}} -- not a bare
  // token. Reading `.token` yields undefined and every later request is
  // unauthenticated, which measures the 401 path at a convincing speed.
  return JSON.parse(response.body).data.token;
}
