import { readFileSync } from 'node:fs';
import { test, expect } from '@playwright/test';
import { scalar, rows, exec, rowFingerprint } from '../lib/db.js';

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
let company;
let employee;

test.beforeAll(async () => {
	// ADR-0018: one administrator, provisioned at boot from ADMIN_PASSWORD
	// through the real encoder -- which is why this suite does not write a
	// password hash by hand.
	adminId = scalar(`SELECT id FROM platform_admins WHERE phone = 'admin'`);
	expect(adminId, 'no platform administrator. Start the stack with ADMIN_PASSWORD set.')
		.not.toBeNull();

	company = rows(
		`SELECT id, company_name, status FROM companies WHERE status = 'active' ORDER BY id LIMIT 1`)[0];
	employee = rows(
		`SELECT id, company_id FROM employees WHERE company_id = ${company.id} LIMIT 1`)[0];
});

/** PHP's login, class for class: one password and nothing else (ADR-0018). */
async function signIn(page) {
	await page.goto('/admin/login');
	await page.fill('input[name="password"]', PASSWORD);
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

	test('a wrong password does not open a session', async ({ page }) => {
		await page.goto('/admin/login');
		await page.fill('input[name="password"]', 'not-the-password');
		await page.click('button[type="submit"]');

		await expect(page).toHaveURL(/\/admin\/login/);
		// :not(.login-validation) -- login.jte renders TWO elements with this
		// class: the server-rendered error, and a hidden container the
		// client-side validation fills in. Both arrived in the same commit as
		// this assertion, so it has never passed: Playwright's strict mode
		// refuses a locator matching two elements. The server-rendered one is
		// what "the page says so" means here.
		await expect(page.locator('.login-alert--error:not(.login-validation)'),
			'and the page says so').toBeVisible();
		await shot(page, '01-login-refused');

		// The refused attempt leaves no session behind it.
		await page.goto('/admin');
		await expect(page).toHaveURL(/\/admin\/login/);
	});

	test('the password opens the dashboard', async ({ page }) => {
		await signIn(page);

		// The page's own content, not just a 200: the layout renders for every
		// route, so asserting the shell would pass on an empty page. Counts,
		// not placeholders. A stat card with no number is the shape
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
			if (status !== 200 || /\/admin\/login/.test(url)) {
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
		await signIn(page);

		for (const [alias, tab] of [['app_content', 'app_content'], ['setting_templates', 'setting_templates']]) {
			await page.goto(`/admin/${alias}`);
			expect(page.url(), alias).toContain(`tab=${tab}`);
		}
	});

	test('a state-changing form without its CSRF token is refused', async ({ page, request }) => {
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

	test('an administrative action is one post, applied and audited together', async ({ page }) => {
		test.skip(process.env.E2E_ADMIN_ACTIONS !== 'true',
			'administrative actions are disabled (ADR-0015 prerequisite 7); '
			+ 'set ADMIN_ACTIONS_ENABLED=true to exercise this');
		await signIn(page);

		const target = rows(
			`SELECT id, status FROM companies WHERE status = 'active' ORDER BY id DESC LIMIT 1`)[0];
		const before = rowFingerprint('companies', target.id);

		await page.goto('/admin/companies');
		await shot(page, '05-companies');

		// The same post the row's menu makes -- with the page's own token, so
		// the CSRF chain is in the path rather than stepped around.
		const csrf = await page.locator('input[name="_csrf"]').first().inputValue();
		const response = await page.request.post('/admin/companies/action', {
			form: {
				action: 'COMPANY_SUSPEND', companyId: String(target.id),
				reason: 'e2e run', _csrf: csrf,
			},
		});
		// try/finally, not a trailing statement: the runner reuses the stack's
		// named volume, so a company left suspended by a failed assertion stays
		// suspended across runs, and the next run picks a different active
		// company and suspends that one too. The fixture would erode a company
		// per failure, and the second failure would be about the erosion.
		try {
			expect(response.status(), await response.text()).toBeLessThan(400);
			expect(scalar(`SELECT status FROM companies WHERE id = ${target.id}`)).toBe('suspended');
			expect(rowFingerprint('companies', target.id)).not.toBe(before);

			// The write and its audit row are one transaction: a company suspended
			// with no row saying who did it is the failure mode the trail exists for.
			const audit = rows(`SELECT event_type, target_type, target_id
			                    FROM platform_admin_audit_events
			                    WHERE platform_admin_id = ${adminId} AND target_id = '${target.id}'
			                    ORDER BY id DESC LIMIT 1`)[0];
			expect(audit, 'the action is in the audit log').toBeTruthy();
			expect(audit.event_type).toBe('COMPANY_SUSPENDED');
		}
		finally {
			exec(`UPDATE companies SET status = '${target.status}' WHERE id = ${target.id}`);
		}
	});

	test('a row action that needs typing opens a dialog, and the dialog writes', async ({ page }) => {
		test.setTimeout(120_000);
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

	test('an add form is a modal behind a button, not a form open over the table', async ({ page }) => {
		test.setTimeout(180_000);
		await signIn(page);

		// Eleven pages rendered their add form inline and permanently expanded.
		// What changed is presentation, so what is checked is presentation: the
		// form must not be on the page until it is asked for, and the POST it
		// carries must still be the one it carried before.
		for (const [path, modalId] of [
			['/admin/advances', 'advModal'],
			['/admin/penalties', 'penModal'],
			['/admin/faqs', 'faqCatModal'],
		]) {
			await page.goto(path, { waitUntil: 'domcontentloaded' });
			const modal = page.locator(`#${modalId}`);
			await expect(modal, `${path}: the modal exists`).toHaveCount(1);
			await expect(modal, `${path}: and is closed on arrival`).toBeHidden();

			// The form is intact -- same action, same CSRF token -- so this is a
			// change of where it lives and not of what it does.
			await expect(modal.locator('form input[name="action"]')).toHaveCount(1);
			await expect(modal.locator('form input[name="_csrf"]'),
				`${path}: the CSRF token came with it`).toHaveCount(1);

			await page.locator(`[onclick="crudOpenAdd('${modalId}')"]`).click();
			await expect(modal, `${path}: the button opens it`).toBeVisible();

			// Escape and focus restore are the port's addition; crud.js closes
			// on a click and nothing else.
			await page.keyboard.press('Escape');
			await expect(modal, `${path}: Escape closes it`).toBeHidden();
			await expect(page.locator(`[onclick="crudOpenAdd('${modalId}')"]`),
				`${path}: and focus goes back to the button that opened it`).toBeFocused();
		}
	});

	test('the pager windows, carries the filters, and keeps the page size', async ({ page }) => {
		test.setTimeout(180_000);
		await signIn(page);

		// Sixteen pages emitted one link per page under a class no stylesheet
		// defines -- 378 bare numbers on the employees list. What replaced it
		// is shared, so it is checked once, here, rather than trusted.
		await page.goto('/admin/employees', { waitUntil: 'domcontentloaded' });
		const pager = page.locator('.pager-wrap').first();
		await expect(pager, 'the dashboard component, not the invented one').toHaveCount(1);
		await expect(page.locator('.pager'), 'and not the class nothing styles').toHaveCount(0);

		const numbered = await pager.locator('.pager-pages .pager-btn').count();
		expect(numbered, 'a window of pages, not all of them').toBeLessThanOrEqual(7);
		await expect(pager.locator('.pager-dots').first(), 'with an ellipsis for the rest')
			.toBeVisible();
		await expect(pager.locator('.pager-summary'), 'and a count of what is shown')
			.toContainText(/\d/);

		// per_page travels with the page links. Legacy drops it, so choosing
		// 100 and turning the page silently returned you to 10.
		await page.goto('/admin/employees?per_page=25', { waitUntil: 'domcontentloaded' });
		const next = pager.locator('.pager-pages .pager-btn').nth(1);
		await expect(next).toHaveAttribute('href', /per_page=25/);
		await expect(page.locator('.pager-size-select'), 'the selector says what is on screen')
			.toHaveValue('25');

		// A size the presets do not list is still represented, rather than the
		// box reading "10" over a 200-row page.
		await page.goto('/admin/employees?per_page=200', { waitUntil: 'domcontentloaded' });
		await expect(page.locator('.pager-size-select')).toHaveValue('200');

		// A page past the end returns no rows while reporting the last page --
		// dbPaginate's own behaviour, deliberately kept. What must not happen
		// is the summary printing an impossible range under the empty table.
		await page.goto('/admin/employees?page=9999', { waitUntil: 'domcontentloaded' });
		const summary = await page.locator('.pager-summary').first().innerText();
		expect(summary, 'no reversed range on an out-of-range page').not.toMatch(/\d+\s*–\s*\d/);

		// The page's own filters survive the size form, which submits its own
		// hidden inputs rather than the address bar.
		await page.goto('/admin/employees?filter_job_title=1&date_from=2020-01-01',
			{ waitUntil: 'domcontentloaded' });
		const hidden = page.locator('.pager-size input[type="hidden"]');
		const names = await hidden.evaluateAll((els) => els.map((e) => e.name));
		expect(names, 'the job-title filter is carried').toContain('filter_job_title');
		expect(names, 'and so is the date range').toContain('date_from');
	});

	test('a row-action menu opens from the keyboard and has no empty triggers', async ({ page }) => {
		test.setTimeout(180_000);
		await signIn(page);

		await page.goto('/admin/employees', { waitUntil: 'domcontentloaded' });
		const trigger = page.locator('.row-actions__trigger').first();
		await expect(trigger).toHaveAttribute('aria-expanded', 'false');

		// The open was bound to `mousedown`, which a keyboard never fires: Enter
		// on a <button> dispatches `click` alone. Every menu was unreachable
		// without a mouse, and the stacked buttons it replaced were not.
		await trigger.focus();
		await page.keyboard.press('Enter');
		await expect(trigger, 'Enter opens it').toHaveAttribute('aria-expanded', 'true');
		const menuId = await trigger.getAttribute('aria-controls');
		await expect(page.locator(`#${menuId}`), 'and the menu it names is the one shown')
			.toBeVisible();

		await page.keyboard.press('Escape');
		await expect(trigger, 'Escape closes it').toHaveAttribute('aria-expanded', 'false');

		// Two tables on one page, two independent id sequences: without a
		// per-table prefix both emitted row-actions-menu-1 and each trigger's
		// aria-controls named the other's menu as often as its own.
		await page.goto('/admin/faqs', { waitUntil: 'domcontentloaded' });
		const ids = await page.locator('.row-actions__menu').evaluateAll(
			(els) => els.map((e) => e.id));
		expect(new Set(ids).size, 'every menu on the page has its own id').toBe(ids.length);

		// A trigger with nothing behind it opens an empty popover. Every menu
		// rendered must have at least one item in it.
		for (const path of ['/admin/employees', '/admin/faqs', '/admin/banners',
			'/admin/phone_countries', '/admin/assets', '/admin/penalties']) {
			await page.goto(path, { waitUntil: 'domcontentloaded' });
			const empty = await page.locator('.row-actions__menu').evaluateAll(
				(els) => els.filter((e) => e.querySelectorAll('[role="menuitem"]').length === 0).length);
			expect(empty, `${path}: no menu opens onto nothing`).toBe(0);
		}
	});

	test('logout ends the session, server-side', async ({ page }) => {
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
