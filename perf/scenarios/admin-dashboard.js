// Surface 1: the JTE platform-admin dashboard.
//
// Server-rendered, so the cost is the page's queries plus template render, and
// the enrichment loops live here. This repository has already had one page turn
// into hundreds of round trips through a per-row, per-day lookup (D-114), which
// is the failure this scenario is shaped to expose: watch the deep pages, not
// the landing page.
import http from 'k6/http';
import { check } from 'k6';
import { BASE } from './lib.js';

export const options = {
  // Fewer VUs than the API scenarios: an admin dashboard has a handful of
  // concurrent users, and pretending otherwise measures a load that will never
  // happen while hiding the per-request cost that will.
  stages: [
    { duration: '15s', target: 2 },
    { duration: '30s', target: 5 },
    { duration: '15s', target: 0 },
  ],
  insecureSkipTLSVerify: __ENV.PERF_INSECURE === '1',
  thresholds: {
    // 400 comes from a measured baseline -- 1,810 requests at 5 VUs, p95=213ms,
    // avg=83ms, 0 failures (2026-09-10, laptop) -- but that run signed in on
    // every iteration, so three of its five requests were the login form and
    // the POST. This measures two dashboard GETs and nothing else, which is a
    // different and almost certainly faster distribution. Kept as an upper
    // bound that still catches a real regression; it wants re-measuring on an
    // idle machine before it is tightened.
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
//   deploy/e2e/run.sh integration    # the profile argument matters: `local`
//                                    # is the default and puts nothing on 8443
//   PERF_ADMIN_PASSWORD='e2e-verify-Pass123!' \
//     BASE_URL=https://127.0.0.1:8443 ./run.sh admin-dashboard
//
// NOT `docker compose -f compose.local.yaml -f e2e/compose.proxy.yaml`, which
// this comment used to say. compose.proxy.yaml carries `name: workin-integration`
// and a later file's project name wins the merge, so that command runs the LOCAL
// stack's definitions inside the INTEGRATION project -- recreating its
// containers and attaching the local database to workin-integration_db-data.
// It also fails outright without E2E_TLS_DIR and a generated certificate, which
// deploy/e2e/run.sh is what provides.
//
// insecureSkipTLSVerify in the options above follows run.sh's PERF_INSECURE,
// which is set only for an https LOOPBACK target. It used to be unconditional,
// which meant the precheck verified a remote certificate and the load
// generator did not -- one decision, made twice, in opposite directions.

// Sign in ONCE, in setup, and hand the session to every VU.
//
// It used to run per iteration, which made a wrong password destructive rather
// than merely wrong: a 60s run at 5 VUs threw hundreds of failed sign-ins.
//
// PlatformAdminLoginService charges those to `web:` + getRemoteAddr() rather
// than to the account, so that nobody can lock the one administrator out from
// anywhere. In THIS deployment that buys less than it sounds like: the app is
// containerised, so a hit on a published port arrives from the project
// network's gateway (172.17.0.1 on the default bridge; a compose project gets
// its own), not 127.0.0.1 -- and compose.remote-db.yaml never passes
// SERVER_FORWARD_HEADERS_STRATEGY, so it is `none` there and anything through
// a proxy arrives as the PROXY's address. deploy/e2e/run.sh sets `native`, and
// that does NOT buy a bucket per operator here: with the generator on the host
// and the app behind a published port, X-Forwarded-For carries the project
// gateway, so it is still one address. Either way it is one shared bucket, and
// eight misses in 15 minutes closes dashboard sign-in for everyone using it.
// Hundreds of attempts, which a per-iteration sign-in produced, empties it
// immediately and fills the attempts table with junk.
//
// Throwing here aborts the whole run before any load, so a bad credential now
// costs exactly one failed attempt. `client-api.js` already worked this way.
export function setup() {
  const page = http.get(`${BASE}/admin/login`);
  // Check the GET before parsing it. k6 returns a response with a null body
  // when the request itself failed, and `page.html()` then throws `the body is
  // null so we can't transform it to HTML` -- which names neither the target
  // nor the cause. This is the commonest way an operator gets here: the stack
  // was torn down, or an https:// URL points at a plain-HTTP port.
  if (page.status !== 200) {
    throw new Error(
      `the login page did not load: ${BASE}/admin/login answered ` +
      `${page.status}${page.error ? ` (${page.error})` : ''}. That is the target, ` +
      `not the credentials. run.sh probes /actuator/health first, so if that ` +
      `passed and this did not, check BASE_URL's scheme and port.`,
    );
  }
  const token = page.html().find('input[name="_csrf"]').attr('value');
  // `password` only: PlatformAdminWebController.login takes no username, and
  // PlatformAdminLoginService uses a constant identifier. A `username` field
  // here was discarded, and naming it in the error below sent an operator
  // looking for a knob that does not exist.
  const login = http.post(`${BASE}/admin/login`, {
    password: __ENV.PERF_ADMIN_PASSWORD || 'devpassword',
    _csrf: token,
  });
  // A 200 or 403 on /admin/login means the form came back -- it did NOT work.
  if (login.status >= 400 || login.url.endsWith('/admin/login')) {
    throw new Error(
      login.status === 0
        ? `admin sign-in never reached ${login.url}: ${login.error || 'no response'} ` +
          `(${login.error_code || 'no code'}). Nothing answered, so this is the ` +
          `target, not the credentials -- a torn-down stack, the wrong port, or a ` +
          `plain-HTTP server behind an https:// URL.`
        : login.status === 403
        ? `admin sign-in was refused with 403 at ${login.url}. That is the CSRF ` +
          `check, not the password: over plain HTTP k6 drops the \`Secure\` ` +
          `session cookie, so the token cannot be validated against a session. ` +
          `Use an https BASE_URL -- deploy/e2e/run.sh integration puts a TLS ` +
          `proxy on https://127.0.0.1:8443.`
        : login.status >= 500 || login.status === 404
          ? `admin sign-in got ${login.status} at ${login.url}. That is the target, ` +
            `not the credentials -- the application is probably still starting or ` +
            `no longer there. run.sh's health probe passed moments earlier, so ` +
            `check the stack rather than PERF_ADMIN_PASSWORD.`
          : `admin sign-in failed (${login.status} at ${login.url}). The form came ` +
            `back, which means the password was rejected. Set PERF_ADMIN_PASSWORD ` +
            `(deploy/e2e/run.sh integration uses 'e2e-verify-Pass123!'; ` +
            `compose.local.yaml defaults to 'devpassword').`,
    );
  }
  // Read the cookies back rather than naming one: the session cookie is
  // `WORKIN_ADMIN_SESSION` today, and a rename should not silently produce a
  // run that measures the login page.
  const jar = http.cookieJar();
  const found = jar.cookiesForURL(`${BASE}/admin`);
  const session = {};
  for (const name of Object.keys(found)) {
    session[name] = found[name][0];
  }
  if (Object.keys(session).length === 0) {
    throw new Error('signed in but no session cookie came back; nothing to measure.');
  }
  return { session };
}

export default function (data) {
  // Per-VU jar, so each VU installs the shared session once per iteration.
  // Cheap and local -- no request -- and re-setting it keeps a VU that somehow
  // lost the cookie from silently measuring redirects to the login page.
  const jar = http.cookieJar();
  for (const name of Object.keys(data.session)) {
    jar.set(BASE, name, data.session[name]);
  }

  check(http.get(`${BASE}/admin`), {
    'dashboard renders': (r) => r.status === 200,
  });
  // Where the enrichment loops live: a page that reads per row, per day.
  check(http.get(`${BASE}/admin/companies`), {
    'companies page renders': (r) => r.status === 200,
  });
}
