import { readFileSync } from 'node:fs';
import { test, expect } from '@playwright/test';
import { rows } from '../lib/db.js';

/**
 * Every endpoint the Flutter clients actually call, reached with a real token.
 *
 * <p>The Flutter apps themselves cannot be built here -- there is no Flutter
 * SDK on this machine and no Android system image to run one on -- so this is
 * the part of "do the clients work against this backend" that CAN be measured:
 * whether each URL their `api_constants.dart` names is served, at the verb they
 * use, in the form they use it. A missing route reaches a user as a 404 from a
 * screen that worked yesterday, and that is the cutover failure this catches.
 *
 * <p>The list is `spike/parity-harness/client-endpoints.txt`, which
 * `scripts/check_client_endpoints_drift.py` keeps equal to the clients' own
 * constants. The verbs are `legacy/route-methods.txt`, extracted from the
 * handlers' guards and gated by `check_openapi_route_methods_drift.py`. Neither
 * is written here, so neither can be quietly narrowed to make this pass.
 *
 * <p>Writes are called with NO parameters. Most ported handlers validate before
 * they touch anything, so the answer is a 4xx and nothing is persisted. The
 * handful that legitimately take no parameters are named below with the reason;
 * a fifth one appearing is a finding, which is the point of naming them rather
 * than dropping the check.
 *
 * <p><b>This sweep mutates.</b> It calls every write the clients call, as the
 * seeded company, so it empties that company's notification inbox and generates
 * its leave balances. Run it against a disposable stack -- which is what
 * `run.sh` builds -- never against anything whose data matters.
 */

const API = '/apis/api';
const ENDPOINTS = new URL('../../../spike/parity-harness/client-endpoints.txt', import.meta.url);
const METHODS = new URL(
	'../../../backend/src/main/resources/legacy/route-methods.txt', import.meta.url);

function clientEndpoints() {
	return readFileSync(ENDPOINTS, 'utf8')
		.split('\n').map((line) => line.trim())
		.filter((line) => line && !line.startsWith('#'));
}

function verbsByEndpoint() {
	const table = new Map();
	for (const line of readFileSync(METHODS, 'utf8').split('\n')) {
		if (!line.startsWith('/apis/api/')) {
			continue;
		}
		const at = line.lastIndexOf(' ');
		const path = line.slice('/apis/api/'.length, at).replace(/\.php$/, '');
		table.set(path, line.slice(at + 1).split(','));
	}
	return table;
}

/**
 * Endpoints a client declares and no controller maps -- on purpose.
 *
 * F-05: the desktop client's `api_constants.dart` declares
 * `setEmployeeAttendanceMethodEndpoint` and has no call site for it. There is
 * no `attendance/set_employee_attendance_method.php` either: legacy's router
 * answers **501 not implemented**, so that is what this port answers. Mapping a
 * controller would be inventing an endpoint the system being replaced does not
 * have.
 *
 * The list is self-policing in both directions: an entry that gains a mapping
 * fails here, and an endpoint that quietly loses one cannot hide by being
 * absent from the inventory.
 */
const DECLARED_UNIMPLEMENTED = new Map([
	['attendance/set_employee_attendance_method',
		'F-05: declared by the desktop client, never called, no PHP file either'],
]);

/**
 * Writes that correctly answer 200 with no parameters at all.
 *
 * Not a relaxation: each one is a handler whose whole input is the caller's
 * identity, which the token already carries. The two notification routes are
 * the interesting pair -- legacy treats a missing or non-numeric id as "all of
 * mine", so `DELETE notifications/delete` with no id empties the caller's
 * inbox. D-058 puts the burden of proof on changing legacy behaviour, so it is
 * ported as-is and asserted in the backend suite; this list exists so nobody
 * reads the 200 here as a validation gap and "fixes" it.
 */
const NO_INPUT_WRITES = new Map([
	['leave_balances/generate', 'generates for the caller\'s whole company; the token is the input'],
	['notifications/delete', 'D-058: a missing id means "all of mine", which is legacy\'s own rule'],
	['notifications/mark_read', 'D-058: same rule, marking rather than deleting'],
	['profile/logout', 'the token is the whole request'],
]);

let token;

test.beforeAll(async ({ playwright }) => {
	const company = rows(`
		SELECT c.phone, c.country_code
		FROM companies c
		WHERE c.status = 'active'
		  AND (SELECT COUNT(*) FROM employees e WHERE e.company_id = c.id) > 20
		ORDER BY c.id LIMIT 1`)[0];
	const context = await playwright.request.newContext({ baseURL: process.env.E2E_HTTP_BASE });
	const response = await context.post(`${API}/auth/login_company`, {
		data: { phone: company.phone, country_code: company.country_code, password: 'devpassword' },
	});
	token = (await response.json()).data.token;
	expect(token, 'a company token for the sweep').toBeTruthy();
	await context.dispose();
});

test('every endpoint the clients call is served, at the verb they call it with', async ({ request }) => {
	test.setTimeout(300_000);
	const verbs = verbsByEndpoint();
	const endpoints = clientEndpoints();
	expect(endpoints.length, 'the vendored client surface is non-empty').toBeGreaterThan(150);

	const missingFromInventory = [];
	const notServed = [];
	const wroteWithoutInput = [];
	const wronglyImplemented = [];
	const notRefused = [];
	const stillNeedsInput = [];
	const seen = [];

	for (const endpoint of endpoints) {
		const allowed = verbs.get(endpoint);
		if (DECLARED_UNIMPLEMENTED.has(endpoint)) {
			if (allowed) {
				wronglyImplemented.push(
					`${endpoint} now has a controller mapping but is still listed as unimplemented`);
			}
			// PHP's router answers 501 for a module with no file. Anything else
			// -- a 404, a 200 -- is a divergence from the system this replaces.
			const response = await request.get(`${API}/${endpoint}`, {
				headers: { Authorization: `Bearer ${token}` }, failOnStatusCode: false,
			});
			seen.push(`GET ${endpoint} -> ${response.status()} (declared unimplemented)`);
			if (response.status() !== 501) {
				notRefused.push(
					`${endpoint} answered ${response.status()}; PHP's router answers 501`);
			}
			continue;
		}
		if (!allowed) {
			missingFromInventory.push(endpoint);
			continue;
		}
		// ANY means the handler checks nothing, which is faithful; GET is the
		// safe way to touch such a route.
		const verb = allowed[0] === 'ANY' ? 'GET' : allowed[0];
		const options = { headers: { Authorization: `Bearer ${token}` }, failOnStatusCode: false };
		const response = await request.fetch(`${API}/${endpoint}`, { method: verb, ...options });
		const status = response.status();
		seen.push(`${verb} ${endpoint} -> ${status}`);

		if (status === 404) {
			// 404 from the router is a missing route. 404 from a handler is
			// "no such row", which is the correct answer to a call with no id.
			const body = await response.text();
			if (!body.includes('"success"')) {
				notServed.push(`${verb} ${endpoint}`);
			}
		}
		if (status === 405) {
			notServed.push(`${verb} ${endpoint} answered 405 to the verb the inventory declares`);
		}
		// 501 is the router's answer for an allowed module whose handler is
		// absent -- which is exactly a missing route, and the one shape that
		// used to reach the report without failing the sweep. The endpoints
		// that answer it legitimately are handled above, and never reach here.
		if (status === 501) {
			notServed.push(`${verb} ${endpoint} answered 501; its module is allowed but nothing serves it`);
		}
		if (verb !== 'GET' && status === 200 && !NO_INPUT_WRITES.has(endpoint)) {
			wroteWithoutInput.push(`${verb} ${endpoint} answered 200 with no parameters at all`);
		}
		if (NO_INPUT_WRITES.has(endpoint) && status !== 200) {
			stillNeedsInput.push(
				`${verb} ${endpoint} answered ${status}; it is listed as needing no input`);
		}
	}

	// Attached rather than printed: 194 lines is a record, not a failure
	// message, and it is the thing to read when one of the lists below is not
	// empty.
	test.info().attach('client-surface-sweep.txt', {
		body: seen.join('\n'), contentType: 'text/plain',
	});

	expect(missingFromInventory,
		'endpoints the clients call that no controller maps -- a 404 at cutover').toEqual([]);
	expect(wronglyImplemented,
		'DECLARED_UNIMPLEMENTED is stale: remove the entry rather than leaving it').toEqual([]);
	expect(notRefused,
		'a declared-unimplemented endpoint must answer PHP\'s own 501, not a 404').toEqual([]);
	expect(notServed, 'endpoints that did not answer from a handler').toEqual([]);
	expect(wroteWithoutInput,
		'a write that succeeded with no input is a validation gap, not coverage').toEqual([]);
	expect(stillNeedsInput,
		'NO_INPUT_WRITES is stale: an entry that now validates should be removed from it').toEqual([]);
});
