import { test, expect } from '@playwright/test';
import { scalar, rows, rowFingerprint } from '../lib/db.js';

/**
 * The surface both Flutter clients call, exercised as journeys rather than as
 * endpoint pings.
 *
 * The parity harness (spike/parity-harness/) already answers "does this route
 * respond the same as PHP" for every endpoint in the clients' constants. What
 * it does not do is drive a sequence -- log in, carry the token, read a scoped
 * list, attempt something belonging to somebody else, check what was written --
 * and that sequence is where authorization and tenant scoping actually live.
 *
 * Every case that expects a refusal also checks the target row BEFORE and AFTER.
 * A 404 proves the caller was told no. It does not prove nothing was written.
 */

const API = '/apis/api';
const PASSWORD = 'devpassword';

/** Two seeded companies, picked by employee count so the lists are non-trivial. */
let tenant;
let other;

test.beforeAll(() => {
	const candidates = rows(`
		SELECT c.id, c.phone, c.country_code,
		       (SELECT COUNT(*) FROM employees e WHERE e.company_id = c.id AND e.is_active = 1) AS employees
		FROM companies c
		WHERE c.status = 'active'
		HAVING employees > 20
		ORDER BY employees DESC
		LIMIT 2`);
	expect(candidates.length, 'the seed holds at least two active companies with employees').toBe(2);
	[tenant, other] = candidates;
});

async function loginCompany(request, company, password = PASSWORD) {
	return request.post(`${API}/auth/login_company`, {
		data: { phone: company.phone, country_code: company.country_code, password },
	});
}

async function tokenFor(request, company) {
	const response = await loginCompany(request, company);
	expect(response.status(), 'company login succeeds').toBe(200);
	return (await response.json()).data.token;
}

function bearer(token) {
	return { headers: { Authorization: `Bearer ${token}` } };
}

test.describe('the unauthenticated surface', () => {
	// Three routes answer without a token because the clients call them before
	// anyone has one: the app decides whether to force an update, and the login
	// form needs the country list to render. Everything else must not.
	for (const endpoint of ['configs/get', 'phone_countries/list']) {
		test(`${endpoint} answers without a token`, async ({ request }) => {
			const response = await request.get(`${API}/${endpoint}`);
			expect(response.status()).toBe(200);
			expect((await response.json()).success).toBe(true);
		});
	}

	for (const endpoint of ['employees/list', 'attendance/list', 'advances/list', 'profile/company', 'time/now']) {
		test(`${endpoint} refuses an anonymous caller`, async ({ request }) => {
			const response = await request.get(`${API}/${endpoint}`);
			expect(response.status()).toBe(401);
			expect((await response.json()).success).toBe(false);
		});
	}

	test('a malformed bearer token is refused, not parsed', async ({ request }) => {
		const response = await request.get(`${API}/employees/list`, bearer('not.a.token'));
		expect(response.status()).toBe(401);
	});

	test('a token signed with the wrong secret is refused', async ({ request }) => {
		// Structurally valid, correct claims, wrong signature -- the case a
		// token minted on another environment would produce, which is why the
		// three profiles deliberately do not share a signing secret.
		const header = Buffer.from(JSON.stringify({ alg: 'HS256', typ: 'JWT' })).toString('base64url');
		const payload = Buffer.from(JSON.stringify({
			type: 'company', company_id: Number(tenant.id), role: 'company_admin',
			exp: Math.floor(Date.now() / 1000) + 3600,
		})).toString('base64url');
		const forged = `${header}.${payload}.${Buffer.from('not-the-signature').toString('base64url')}`;

		const response = await request.get(`${API}/employees/list`, bearer(forged));

		expect(response.status()).toBe(401);
	});
});

test.describe('company login', () => {
	test('correct credentials return the company and a token', async ({ request }) => {
		const response = await loginCompany(request, tenant);

		expect(response.status()).toBe(200);
		const body = await response.json();
		expect(body.success).toBe(true);
		expect(body.data.company.id).toBe(Number(tenant.id));
		expect(body.data.token).toMatch(/^[\w-]+\.[\w-]+\.[\w-]+$/);
	});

	test('a wrong password is refused, and says nothing about which half was wrong', async ({ request }) => {
		const response = await loginCompany(request, tenant, 'not-the-password');

		const body = await response.json();
		expect(body.success).toBe(false);
		// One message for both failures, so the response cannot be used to
		// enumerate which phone numbers are registered.
		expect(body.message).toBe('Invalid phone or password');
	});

	test('an unknown phone gets the same refusal as a wrong password', async ({ request }) => {
		const response = await request.post(`${API}/auth/login_company`, {
			data: { phone: '01000099999', country_code: '+20', password: PASSWORD },
		});

		expect((await response.json()).message).toBe('Invalid phone or password');
	});

	test('the refusal is translated, and the Arabic survives the wire intact', async ({ request }) => {
		// R-067: a filter ordering tie once let this reach the client as
		// mojibake, with every status code still correct.
		const response = await request.post(`${API}/auth/login_company`, {
			headers: { 'Accept-Language': 'ar' },
			data: { phone: tenant.phone, country_code: tenant.country_code, password: 'no' },
		});

		const body = await response.json();
		expect(body.message).toBe('رقم الهاتف أو كلمة المرور غير صحيحة');
		expect(body.message).not.toContain('Ø');
	});
});

test.describe('the token carries the tenant, and only the tenant', () => {
	test('a company sees its own employees and no one else\'s', async ({ request }) => {
		const token = await tokenFor(request, tenant);

		const response = await request.get(`${API}/employees/list`, bearer(token));

		expect(response.status()).toBe(200);
		const employees = (await response.json()).data;
		expect(employees.length).toBeGreaterThan(0);
		const foreign = employees.filter((employee) => employee.company_id !== Number(tenant.id));
		expect(foreign, 'no row from another company appears in the list').toEqual([]);
	});

	test('reading another company\'s employee by id answers not-found', async ({ request }) => {
		const token = await tokenFor(request, tenant);
		const victim = scalar(
			`SELECT id FROM employees WHERE company_id = ${other.id} AND is_active = 1 LIMIT 1`);

		const response = await request.get(`${API}/employees/one?id=${victim}`, bearer(token));

		expect(response.status()).toBe(404);
		// Not-found, not forbidden: a distinguishable "forbidden" would confirm
		// the row exists, which is itself information about another tenant.
		expect((await response.json()).message).toBe('Employee not found');
	});

	test('updating another company\'s employee is refused AND writes nothing', async ({ request }) => {
		const token = await tokenFor(request, tenant);
		const victim = scalar(
			`SELECT id FROM employees WHERE company_id = ${other.id} AND is_active = 1 LIMIT 1`);
		const before = rowFingerprint('employees', victim);

		const response = await request.put(`${API}/employees/update?id=${victim}`, {
			...bearer(token),
			data: { first_name: 'CROSS-TENANT-WRITE', is_active: 0 },
		});

		expect(response.status()).toBe(404);
		expect(rowFingerprint('employees', victim), 'the foreign row is byte-for-byte unchanged')
			.toBe(before);
	});

	test('deleting another company\'s employee is refused AND writes nothing', async ({ request }) => {
		const token = await tokenFor(request, tenant);
		const victim = scalar(
			`SELECT id FROM employees WHERE company_id = ${other.id} AND is_active = 1 LIMIT 1`);
		const before = rowFingerprint('employees', victim);

		const response = await request.delete(`${API}/employees/delete?id=${victim}`, bearer(token));

		expect(response.status()).toBe(404);
		expect(rowFingerprint('employees', victim), 'the foreign row is byte-for-byte unchanged')
			.toBe(before);
	});

	test('every scoped list is scoped, not just the one that is easy to check', async ({ request }) => {
		const token = await tokenFor(request, tenant);
		const mine = Number(tenant.id);

		for (const endpoint of ['departments/list', 'branches/list', 'job_titles/list']) {
			const response = await request.get(`${API}/${endpoint}`, bearer(token));
			expect(response.status(), endpoint).toBe(200);
			const data = (await response.json()).data;
			const leaked = (Array.isArray(data) ? data : [])
				.filter((row) => row.company_id !== undefined && row.company_id !== mine);
			expect(leaked, `${endpoint} returned another company's rows`).toEqual([]);
		}
	});
});

test.describe('the request contract the clients depend on', () => {
	test('the wrong verb answers PHP\'s own 405, in PHP\'s own envelope', async ({ request }) => {
		// Spring must not answer this first: its 405 has a different body, and
		// a client parsing `{success, message}` would get nothing it expects.
		const response = await request.get(`${API}/auth/login_company`);

		expect(response.status()).toBe(405);
		expect(await response.json()).toEqual({ success: false, message: 'Invalid method' });
	});

	test('both URL forms are served, which is the divergence that makes cutover safe', async ({ request }) => {
		// Production PHP answers 500 for the .php form and 200 without it, and
		// the clients use the suffix-less form. This port serves both, so a
		// caller that hard-codes either one keeps working.
		const bare = await request.get(`${API}/configs/get`);
		const suffixed = await request.get(`${API}/configs/get.php`);

		expect(bare.status()).toBe(200);
		expect(suffixed.status()).toBe(200);
		expect(await suffixed.json()).toEqual(await bare.json());
	});

	test('every response is the same envelope', async ({ request }) => {
		const token = await tokenFor(request, tenant);

		for (const [endpoint, expectedSuccess] of [
			['configs/get', true],
			['employees/list', true],
			['employees/one?id=0', false],
		]) {
			const response = await request.get(`${API}/${endpoint}`, bearer(token));
			const body = await response.json();
			expect(typeof body.success, endpoint).toBe('boolean');
			expect(body.success, endpoint).toBe(expectedSuccess);
			expect(typeof body.message, endpoint).toBe('string');
		}
	});

	test('a paginated list carries the meta block the clients read', async ({ request }) => {
		const token = await tokenFor(request, tenant);

		const response = await request.get(`${API}/requests/list?page=1&limit=20`, bearer(token));

		const body = await response.json();
		expect(body.meta).toMatchObject({
			page: expect.any(Number),
			limit: expect.any(Number),
			total: expect.any(Number),
			total_pages: expect.any(Number),
			has_next: expect.any(Boolean),
			has_previous: expect.any(Boolean),
		});
	});
});

test.describe('OTP delivery degrades the way legacy does', () => {
	test('an OTP route answers rather than throwing when WhatsApp is unconfigured', async ({ request }) => {
		// Deliberately unconfigured on every environment but production: the
		// habit of running with live messaging credentials is the one that
		// eventually sends a real message to a real person. Legacy's own
		// behaviour without credentials is a refusal, not a crash.
		const response = await request.post(`${API}/auth/forgot_password`, {
			data: { phone: tenant.phone, country_code: tenant.country_code },
		});

		expect(response.status(), 'answers, and not with a 500 stack').not.toBe(500);
		const body = await response.json();
		expect(typeof body.success).toBe('boolean');
		expect(typeof body.message).toBe('string');
	});
});

test.describe('an employee sees an employee\'s surface', () => {
	test('an employee logs in and reads their own profile', async ({ request }) => {
		const employee = rows(`
			SELECT id, phone, country_code, company_id
			FROM employees
			WHERE company_id = ${tenant.id} AND is_active = 1 AND role = 'employee'
			LIMIT 1`)[0];
		test.skip(!employee, 'the seed holds no active employee for this company');

		const login = await request.post(`${API}/auth/login_employee`, {
			data: { phone: employee.phone, country_code: employee.country_code, password: PASSWORD },
		});
		expect(login.status(), await login.text()).toBe(200);
		const token = (await login.json()).data.token;

		const profile = await request.get(`${API}/profile/employee`, bearer(token));

		expect(profile.status()).toBe(200);
		const body = await profile.json();
		expect(body.success).toBe(true);
		// The token identifies the employee; the response must be that
		// employee, whatever the request said.
		expect(String(body.data.employee?.id ?? body.data.id)).toBe(String(employee.id));
	});

	test('an employee token does not open the company surface', async ({ request }) => {
		const employee = rows(`
			SELECT id, phone, country_code FROM employees
			WHERE company_id = ${tenant.id} AND is_active = 1 AND role = 'employee' LIMIT 1`)[0];
		test.skip(!employee, 'the seed holds no active employee for this company');

		const login = await request.post(`${API}/auth/login_employee`, {
			data: { phone: employee.phone, country_code: employee.country_code, password: PASSWORD },
		});
		const token = (await login.json()).data.token;
		const victim = scalar(
			`SELECT id FROM employees WHERE company_id = ${tenant.id} AND id <> ${employee.id} LIMIT 1`);
		const before = rowFingerprint('employees', victim);

		const response = await request.delete(`${API}/employees/delete?id=${victim}`, bearer(token));

		expect(response.status(), 'an ordinary employee cannot delete a colleague').not.toBe(200);
		expect(rowFingerprint('employees', victim)).toBe(before);
	});
});
