import { createHash } from 'node:crypto';
import { readFileSync } from 'node:fs';
import { test, expect } from '@playwright/test';
import { scalar, rows, exec, rowFingerprint } from '../lib/db.js';
import { totp, nextWindow } from '../lib/totp.js';

/**
 * The platform-admin dashboard, in a browser, over TLS.
 *
 * <p>Over TLS because the session cookie is `secure` unconditionally
 * (ADR-0015 prerequisite 6). Every other way of running this suite would have
 * meant relaxing that flag for convenience, which is the one thing the flag
 * exists to prevent. A self-signed proxy in front costs less than the control.
 *
 * <p>The page list is read from the SAME committed manifest the port is
 * measured against, minus the three pages D-192 put out of scope. A page ported
 * later is covered here the day it lands, without this file being edited --
 * which is the only version of "covers every page" that stays true.
 */

const PHONE = process.env.E2E_ADMIN_PHONE ?? '+201000000042';
const PASSWORD = process.env.E2E_ADMIN_PASSWORD ?? 'e2e-verify-Pass123!';
const MANIFEST = new URL('../../../contracts/legacy-dashboard-pages.txt', import.meta.url);
// NOT under report/: Playwright clears the HTML reporter's output folder at
// the start of every run, which silently deleted the whole screenshot set.
const SHOTS = 'screenshots';

/** D-192: withdrawn from ADR-0016's scope, not unported work. */
const OUT_OF_SCOPE = new Set(['company_settings', 'profile', 'change_password']);

/** Not list pages: one is the entry point, two need an id, one is the login form. */
const SPECIAL = new Set(['index', 'login', 'company_detail', 'employee_detail']);

function portedListPages() {
	return readFileSync(MANIFEST, 'utf8')
		.split('\n')
		.map((line) => line.trim())
		.filter((line) => line && !line.startsWith('#'))
		.filter((page) => !OUT_OF_SCOPE.has(page) && !SPECIAL.has(page));
}

/**
 * One full-page capture, with animations fast-forwarded.
 *
 * `.content` carries `animation: appFadeUp .5s ... both`, so its first frame is
 * `opacity: 0` -- and a capture that races it produces a screenshot of an empty
 * page while the DOM underneath is complete. That is a misleading artifact, not
 * a defect: `animations: 'disabled'` fast-forwards finite animations to their
 * end state, which is what the page settles to and what a reader of the report
 * should see.
 */
async function shot(page, name) {
	await page.screenshot({ path: `${SHOTS}/${name}.png`, fullPage: true, animations: 'disabled' });
}

let adminId;
let seed;
let company;
let employee;

test.beforeAll(async () => {
	adminId = scalar(`SELECT id FROM platform_admins WHERE phone = '${PHONE}'`);
	expect(adminId,
		`no platform administrator ${PHONE}. Start the stack with ADMIN_PHONE/ADMIN_PASSWORD set `
		+ '-- the application provisions it at boot through the real encoder, which is why this '
		+ 'suite does not write a password hash by hand.').not.toBeNull();

	company = rows(
		`SELECT id, company_name, status FROM companies WHERE status = 'active' ORDER BY id LIMIT 1`)[0];
	employee = rows(
		`SELECT id, company_id FROM employees WHERE company_id = ${company.id} LIMIT 1`)[0];
});

/**
 * Issues a bootstrap token the way an operator does, and enrols the factor.
 *
 * There is no API for either: both are deliberately operator-provisioned, so
 * the token is written to the table exactly as the runbook has a human write it.
 */
/**
 * Un-enrols the administrator and issues a fresh bootstrap token.
 *
 * Called by every case that has something to say about enrolment, because the
 * stack is long-lived: a previous run leaves a bound factor behind, and a case
 * asserting "no factor was enrolled" would then be reading the last run's row
 * and failing for a reason that has nothing to do with what it tests.
 *
 * @returns the raw token an operator would hand over out of band
 */
function issueBootstrapToken() {
	const raw = `e2e-${Date.now()}-${Math.random().toString(36).slice(2)}`;
	const hash = createHash('sha256').update(raw).digest('hex');
	exec(`DELETE FROM platform_admin_mfa WHERE platform_admin_id = ${adminId}`);
	exec(`DELETE FROM platform_admin_mfa_bootstrap_tokens WHERE platform_admin_id = ${adminId}`);
	exec(`INSERT INTO platform_admin_mfa_bootstrap_tokens
	          (platform_admin_id, token_hash, issued_at, expires_at)
	      VALUES (${adminId}, '${hash}', NOW(6), NOW(6) + INTERVAL 30 MINUTE)`);
	return raw;
}

async function enrol(page) {
	const raw = issueBootstrapToken();

	await page.goto('/admin/enrol');
	await page.fill('input[name="phone"]', PHONE);
	await page.fill('input[name="password"]', PASSWORD);
	await page.fill('input[name="bootstrapToken"]', raw);
	await page.click('button[type="submit"]');

	const shown = await page.locator('code').first().textContent();
	expect(shown, 'enrolment shows the seed once').toMatch(/^[A-Z2-7]{16,}$/);
	seed = shown.trim();

	await page.fill('input[name="code"]', totp(seed));
	await page.click('button[type="submit"]');

	expect(scalar(`SELECT bound_at IS NOT NULL FROM platform_admin_mfa
	               WHERE platform_admin_id = ${adminId}`), 'the factor is bound').toBe('1');
}

/** Clears the accepted step so a fresh code in the same window is not a replay. */
function allowAnotherCode() {
	exec(`UPDATE platform_admin_mfa SET last_accepted_time_step = NULL
	      WHERE platform_admin_id = ${adminId}`);
}

async function signIn(page) {
	// Enrol on demand rather than depending on an earlier case having run.
	// The describe is serial, but `-g` and `--last-failed` both run a subset,
	// and a helper that only works in one order turns "re-run just that one" --
	// the first thing anybody does with a failure -- into a second failure with
	// a different cause.
	if (!seed) {
		await enrol(page);
	}
	await page.goto('/admin/login');
	await page.fill('input[name="phone"]', PHONE);
	await page.fill('input[name="password"]', PASSWORD);
	await page.click('button[type="submit"]');
	await expect(page).toHaveURL(/\/admin\/mfa/);

	allowAnotherCode();
	await page.fill('input[name="code"]', totp(seed));
	await page.click('button[type="submit"]');
	await expect(page).toHaveURL(/\/admin(\?|$)/);
}

test.describe.serial('the platform-admin dashboard', () => {
	test('an anonymous request never reaches a page', async ({ page }) => {
		const response = await page.goto('/admin');

		await expect(page).toHaveURL(/\/admin\/login/);
		expect(response.status()).toBe(200);
		await shot(page, '00-login');
	});

	test('enrolment refuses the wrong bootstrap token before it refuses anything else', async ({ page }) => {
		// A real, unused token exists; only the one submitted is wrong. That is
		// the case worth checking -- the password alone must not be enough.
		issueBootstrapToken();

		await page.goto('/admin/enrol');
		await page.fill('input[name="phone"]', PHONE);
		await page.fill('input[name="password"]', PASSWORD);
		await page.fill('input[name="bootstrapToken"]', 'not-the-token');
		await page.click('button[type="submit"]');

		await expect(page.locator('body')).not.toContainText(/[A-Z2-7]{16,}/);
		expect(scalar(`SELECT COUNT(*) FROM platform_admin_mfa WHERE platform_admin_id = ${adminId}`),
			'no factor is enrolled by a failed attempt').toBe('0');
		await shot(page, '01-enrol-refused');
	});

	test('a factor enrols, and the seed is shown exactly once', async ({ page }) => {
		await enrol(page);
		await shot(page, '02-enrolled');
	});

	test('the password alone reaches the challenge and nothing behind it', async ({ page }) => {
		await page.goto('/admin/login');
		await page.fill('input[name="phone"]', PHONE);
		await page.fill('input[name="password"]', PASSWORD);
		await page.click('button[type="submit"]');

		await expect(page).toHaveURL(/\/admin\/mfa/);
		await shot(page, '03-mfa-challenge');

		// The half-authenticated session must not open the dashboard.
		await page.goto('/admin');
		await expect(page).toHaveURL(/\/admin\/(login|mfa)/);
	});

	test('a wrong code does not complete the session', async ({ page }) => {
		await page.goto('/admin/login');
		await page.fill('input[name="phone"]', PHONE);
		await page.fill('input[name="password"]', PASSWORD);
		await page.click('button[type="submit"]');

		await page.fill('input[name="code"]', '000000');
		await page.click('button[type="submit"]');

		await page.goto('/admin');
		await expect(page).toHaveURL(/\/admin\/(login|mfa)/);
	});

	test('the second factor completes the session', async ({ page }) => {
		await nextWindow();
		await signIn(page);

		// The page's own content, not just a 200: the layout renders for every
		// route, so asserting the shell would pass on an empty page.
		await expect(page.locator('.content')).toContainText(PHONE);
		// Counts, not placeholders. A stat card with no number is the shape
		// this page had before it read anything.
		const cards = page.locator('.home-stat-card');
		expect(await cards.count(), 'the overview renders its stat cards').toBeGreaterThan(8);
		await expect(cards.first().locator('.num')).toHaveText(/\d/);
		// The charts draw client-side; a canvas with no width means Chart.js
		// never ran, which a status code cannot tell you.
		const canvas = page.locator('.home-chart-card canvas').first();
		await expect(canvas).toBeVisible();
		expect(await canvas.evaluate((node) => node.width),
			'Chart.js sized the canvas, so it ran').toBeGreaterThan(0);
		await shot(page, '04-dashboard');
	});

	test('every ported page renders for a signed-in administrator', async ({ page }) => {
		test.setTimeout(600_000);
		await nextWindow();
		await signIn(page);

		const pages = portedListPages();
		expect(pages.length, 'the manifest yielded pages to visit').toBeGreaterThan(20);

		const failures = [];
		for (const name of pages) {
			const response = await page.goto(`/admin/${name}`, { waitUntil: 'domcontentloaded' });
			const status = response.status();
			const url = page.url();
			// A redirect to login means the session was lost, not that the page
			// is missing -- worth separating, because they look identical here.
			if (status !== 200 || /\/admin\/(login|mfa)/.test(url)) {
				failures.push(`${name}: HTTP ${status} at ${url}`);
			}
			// A JTE catalog miss renders the key itself: "nav_guide_videos"
			// rather than a label. Every gate for that is a build gate; this
			// is the one that looks at the rendered page.
			const body = await page.locator('body').innerText();
			if (/\b(nav|page|label|btn)_[a-z_]{3,}\b/.test(body)) {
				failures.push(`${name}: an untranslated message key rendered`);
			}
			await shot(page, `page-${name}`);
		}

		expect(failures, 'every page in the manifest renders').toEqual([]);
	});

	test('the two detail pages render for a real row', async ({ page }) => {
		await nextWindow();
		await signIn(page);

		const detail = await page.goto(`/admin/companies/${company.id}`);
		expect(detail.status()).toBe(200);
		await expect(page.locator('body')).toContainText(String(company.company_name).slice(0, 6));
		await shot(page, 'page-company_detail');

		const employeeDetail = await page.goto(`/admin/employee_detail?id=${employee.id}`);
		expect(employeeDetail.status()).toBe(200);
		await shot(page, 'page-employee_detail');
	});

	test('the three aliases land in the settings tab they name', async ({ page }) => {
		await nextWindow();
		await signIn(page);

		for (const [alias, tab] of [['app_content', 'app_content'], ['setting_templates', 'setting_templates']]) {
			await page.goto(`/admin/${alias}`);
			expect(page.url(), alias).toContain(`tab=${tab}`);
		}
	});

	test('a state-changing form without its CSRF token is refused', async ({ page, request }) => {
		await nextWindow();
		await signIn(page);
		const cookies = await page.context().cookies();
		const session = cookies.find((cookie) => cookie.name === 'WORKIN_ADMIN_SESSION');
		expect(session, 'the session cookie was set').toBeTruthy();
		expect(session.secure, 'and it is Secure, which is why this runs over TLS').toBe(true);
		expect(session.httpOnly).toBe(true);

		const response = await request.post('/admin/logout', {
			headers: { Cookie: `${session.name}=${session.value}` },
			form: {},
			maxRedirects: 0,
		});

		expect(response.status(), 'CSRF is enforced on this chain').toBe(403);
	});

	test('an administrative action needs a step-up, and the approval is single use', async ({ page }) => {
		test.skip(process.env.E2E_ADMIN_ACTIONS !== 'true',
			'administrative actions are disabled (ADR-0015 prerequisite 7); '
			+ 'set ADMIN_ACTIONS_ENABLED=true to exercise this');
		await nextWindow();
		await signIn(page);

		const target = rows(
			`SELECT id, status FROM companies WHERE status = 'active' ORDER BY id DESC LIMIT 1`)[0];
		const before = rowFingerprint('companies', target.id);

		await page.goto('/admin/companies');
		await shot(page, '05-companies');

		allowAnotherCode();
		const csrf = await page.locator('input[name="_csrf"]').first().inputValue();
		const confirm = await page.request.post('/admin/companies/confirm', {
			form: {
				action: 'COMPANY_SUSPEND', companyId: String(target.id),
				reason: 'e2e run', code: totp(seed), _csrf: csrf,
			},
		});
		expect(confirm.status(), await confirm.text()).toBeLessThan(400);
		const approval = (await confirm.text()).match(/name="approvalId" value="([0-9a-f]+)"/)?.[1];
		expect(approval, 'the step-up issued an approval').toBeTruthy();

		expect(rowFingerprint('companies', target.id),
			'the step-up alone changes nothing -- it authorises, it does not apply').toBe(before);

		const apply = await page.request.post('/admin/companies/apply', {
			form: {
				action: 'COMPANY_SUSPEND', companyId: String(target.id),
				reason: 'e2e run', approvalId: approval, _csrf: csrf,
			},
		});
		expect(apply.status()).toBeLessThan(400);
		expect(scalar(`SELECT status FROM companies WHERE id = ${target.id}`)).toBe('suspended');

		const audit = rows(`SELECT event_type, target_type, target_id, step_up_approval_id
		                    FROM platform_admin_audit_events
		                    WHERE platform_admin_id = ${adminId} AND target_id = '${target.id}'
		                    ORDER BY id DESC LIMIT 1`)[0];
		expect(audit, 'the action is in the audit log').toBeTruthy();
		expect(audit.step_up_approval_id, 'and carries the approval that authorised it').toBe(approval);

		const replay = await page.request.post('/admin/companies/apply', {
			form: {
				action: 'COMPANY_SUSPEND', companyId: String(target.id),
				reason: 'e2e run', approvalId: approval, _csrf: csrf,
			},
		});
		expect((await replay.text()).length).toBeGreaterThan(0);

		// The approval leaves TWO audit rows -- STEP_UP_APPROVED when it is
		// issued and the action's own event when it is spent -- and both carry
		// its id, which is what makes the trail readable: the authorisation and
		// the thing it authorised are joined. What must be single is the
		// ACTION, so that is what is counted.
		const trail = rows(`SELECT event_type FROM platform_admin_audit_events
		                    WHERE step_up_approval_id = '${approval}'
		                    ORDER BY id`).map((row) => row.event_type);
		expect(trail, 'the approval and the action it authorised are both recorded, once each')
			.toEqual(['STEP_UP_APPROVED', 'COMPANY_SUSPENDED']);
		expect(scalar(`SELECT consumed_at IS NOT NULL
		               FROM platform_admin_step_up_approvals WHERE id = '${approval}'`),
			'the approval is marked consumed').toBe('1');

		// Put the seed back the way it was found.
		exec(`UPDATE companies SET status = '${target.status}' WHERE id = ${target.id}`);
	});

	test('a row action that needs typing opens a dialog, and the dialog writes', async ({ page }) => {
		test.setTimeout(120_000);
		await nextWindow();
		await signIn(page);

		// Four list pages used to carry a text or number box inside every row.
		// The field is in a modal now, and what matters is that the modal still
		// writes what the row form wrote -- a dialog that opens and does
		// nothing is the failure mode worth catching.
		const target = rows(`SELECT a.id, a.employee_id FROM advances a
		                     WHERE a.status = 'pending' ORDER BY a.id LIMIT 1`)[0];
		test.skip(!target, 'the seed holds no pending advance');
		const before = rowFingerprint('advances', target.id);

		await page.goto('/admin/advances?status=pending');
		const trigger = page.locator(`[data-dialog="advance-reject"][data-dialog-id="${target.id}"]`);
		await expect(trigger, 'the row offers the action').toHaveCount(1);
		// The menu is closed until its trigger is used, which is the point of it.
		await expect(trigger).toBeHidden();

		await page.locator(`tr:has([data-dialog-id="${target.id}"]) .row-actions__trigger`).click();
		await trigger.click();

		const dialog = page.locator('#advance-reject');
		await expect(dialog, 'the dialog is open, not merely present').toBeVisible();
		await expect(dialog.locator('[data-dialog-field="id"]'))
			.toHaveValue(String(target.id));
		await expect(dialog.locator('.row-dialog__subject'),
			'and names the row it came from').not.toBeEmpty();

		const reason = `refused by the e2e run ${Date.now()}`;
		await dialog.locator('textarea[name="rejection_reason"]').fill(reason);
		await dialog.locator('button[type="submit"]:not([formmethod])').click();
		await page.waitForLoadState('domcontentloaded');

		const after = rows(`SELECT status, rejection_reason FROM advances WHERE id = ${target.id}`)[0];
		expect(after.status, 'the dialog submitted the same action the row form did')
			.toBe('rejected');
		expect(after.rejection_reason, 'with the text typed into it').toBe(reason);
		expect(rowFingerprint('advances', target.id)).not.toBe(before);
	});

	test('logout ends the session, server-side', async ({ page }) => {
		await nextWindow();
		await signIn(page);

		await page.goto('/admin/sessions');
		await shot(page, '06-sessions');

		// This session, by id -- not a count for the administrator. Every case
		// above signs in and leaves its own row behind, so a count would be
		// non-zero however correctly logout worked, and the assertion would be
		// about the suite rather than about the application.
		const cookies = await page.context().cookies();
		// Spring Session base64-encodes the id into the cookie
		// (DefaultCookieSerializer's default), so the cookie value is not the
		// SESSION_ID column. Decoding is what makes this an assertion about one
		// session rather than about all of them.
		const sessionId = Buffer.from(
			cookies.find((cookie) => cookie.name === 'WORKIN_ADMIN_SESSION').value, 'base64')
			.toString('utf8');
		expect(sessionId, 'the decoded cookie is a session id')
			.toMatch(/^[0-9a-f-]{36}$/);
		expect(scalar(`SELECT COUNT(*) FROM SPRING_SESSION WHERE SESSION_ID = '${sessionId}'`),
			'the session is in the shared store, not one worker\'s heap').toBe('1');

		const csrf = await page.locator('input[name="_csrf"]').first().inputValue();
		await page.request.post('/admin/logout', { form: { _csrf: csrf } });

		await page.goto('/admin');
		await expect(page).toHaveURL(/\/admin\/login/);
		expect(scalar(`SELECT COUNT(*) FROM SPRING_SESSION WHERE SESSION_ID = '${sessionId}'`),
			'the row is gone from the store, not only the cookie from the browser').toBe('0');
	});
});
