import { readFileSync } from 'node:fs';
import { test, expect } from '@playwright/test';

/**
 * phone-validator.js and phone-form-bind.js on the company and employee forms, with no stack
 * behind them (D-261).
 *
 * <p>The Java end-to-end tests read the select, the message and the rules a page carries; they
 * cannot run the scripts. So the page here ends with layout.jte's own phone block, read from the
 * template and rendered with rules as JTE writes an attribute, and the scripts are served from
 * the source tree as the browser would fetch them: the rules tag, then the validator, then the
 * bind, all before the document finishes parsing.
 */

const BACKEND = new URL('../../../backend/', import.meta.url);
const source = (path) => readFileSync(new URL(path, BACKEND), 'utf8');

const ORIGIN = 'http://admin.test';

const RULES = {
	'+20': { phone_length: 11, phone_prefixes: ['010', '011', '012', '015'] },
	'+966': { phone_length: 10, phone_prefixes: ['05'] },
};

const INVALID = 'رقم الهاتف غير صالح لهذه الدولة';

/** An attribute value as JTE writes it: double-quoted, with `"`, `'`, `<`, `>` and `&` escaped. */
const attr = (value) => String(value)
	.replaceAll('&', '&amp;').replaceAll('"', '&#34;').replaceAll("'", '&#39;')
	.replaceAll('<', '&lt;').replaceAll('>', '&gt;');

/** layout.jte's phone block, as it renders for a page that hands it these rules. */
function phoneScripts(rules) {
	const block = source('src/main/jte/admin/layout.jte').match(/@if\(phoneCountryRules != null\)\n([\s\S]*?)@endif/);
	if (!block) {
		throw new Error('layout.jte has no phone block to render');
	}
	return block[1].replace('${phoneCountryRules}', attr(JSON.stringify(rules)));
}

const option = (code, label, selected) => `<option value="${code}"${selected ? ' selected' : ''}>${label}</option>`;

/** companies.jte's form: a required select with +20 chosen, and a required phone. */
function companyForm({ phone = '', code = '+20' } = {}) {
	return `<form method="POST" action="/admin/companies/save" enctype="multipart/form-data"
        data-invalid-phone-msg="${INVALID}">
  <select id="co_code" name="country_code" required>
    ${option('+20', 'مصر (+20)', code === '+20')}
    ${option('+966', 'السعودية (+966)', code === '+966')}
  </select>
  <input type="tel" id="co_phone" name="phone" dir="ltr" required autocomplete="tel" inputmode="numeric" value="${phone}">
  <button type="submit">save</button>
</form>`;
}

/** employees.jte's form: an optional select, a stored code no country has listed first, and an optional phone. */
function employeeForm({ phone = '', code = '', edit = false } = {}) {
	const stored = code && !(code in RULES) ? option(code, code, true) : '';
	return `<form method="POST" data-employee-form data-invalid-phone-msg="${INVALID}"${edit ? ' data-phone-keep-untouched' : ''}>
  <select id="country_code" name="country_code">
    <option value="">اختياري</option>
    ${stored}
    ${option('+20', 'مصر (+20)', code === '+20')}
    ${option('+966', 'السعودية (+966)', code === '+966')}
  </select>
  <input type="text" id="phone" name="phone" inputmode="tel" autocomplete="off" value="${phone}">
  <button type="submit">save</button>
</form>`;
}

/** Opens a page holding `form`, served with the layout's phone block. Records what posts and what alerts. */
async function open(page, form) {
	const posts = [];
	const alerts = [];
	await page.route(`${ORIGIN}/**`, async (route) => {
		const request = route.request();
		const path = new URL(request.url()).pathname;
		if (path.startsWith('/admin/_assets/')) {
			await route.fulfill({ contentType: 'text/javascript', body: source(`src/main/resources/static${path}`) });
		} else if (request.method() === 'POST') {
			posts.push(request.postData() ?? '');
			await route.fulfill({ contentType: 'text/html; charset=utf-8', body: '<p id="saved">saved</p>' });
		} else {
			await route.fulfill({
				contentType: 'text/html; charset=utf-8',
				body: `<!doctype html><html lang="ar" dir="rtl"><head><meta charset="utf-8"></head><body>
${form}
${phoneScripts(RULES)}
</body></html>`,
			});
		}
	});
	page.on('dialog', async (dialog) => {
		alerts.push(dialog.message());
		await dialog.dismiss();
	});
	await page.goto(`${ORIGIN}/admin/page`);
	return { posts, alerts };
}

/** Submits and reports whether the form posted. A refused submit alerts and stays; either must happen. */
async function submit(page, session) {
	const posts = session.posts.length;
	const alerts = session.alerts.length;
	await page.click('button[type="submit"]');
	await expect.poll(() => session.posts.length > posts || session.alerts.length > alerts).toBe(true);
	const posted = session.posts.length > posts;
	if (posted) {
		await page.waitForSelector('#saved');
	}
	return posted;
}

test('the rules reach the validator through the tag\'s escaped attribute', async ({ page }) => {
	await open(page, companyForm());

	expect(await page.evaluate(() => window.WorkinPhoneCountriesRules)).toEqual(RULES);
});

test('the company phone takes digits only, as many as the chosen country\'s numbers have', async ({ page }) => {
	await open(page, companyForm());
	const phone = page.locator('#co_phone');

	await expect(phone).toHaveAttribute('maxlength', '11');
	await phone.pressSequentially('01a0-1 2');
	await expect(phone).toHaveValue('01012');

	await page.selectOption('#co_code', '+966');
	await expect(phone).toHaveAttribute('maxlength', '10');
});

test('a number typed without its leading zero gets it back on leaving the field', async ({ page }) => {
	await open(page, companyForm());

	await page.locator('#co_phone').fill('1012345678');
	await page.locator('#co_phone').blur();

	await expect(page.locator('#co_phone')).toHaveValue('01012345678');
});

test('a number the country refuses stops the save with the form\'s message; a valid one saves', async ({ page }) => {
	const session = await open(page, companyForm());

	await page.locator('#co_phone').fill('09912345678');
	expect(await submit(page, session)).toBe(false);
	expect(session.alerts).toEqual([INVALID]);
	expect(await page.evaluate(() => document.activeElement.id)).toBe('co_phone');

	await page.locator('#co_phone').fill('01012345678');
	expect(await submit(page, session)).toBe(true);
	expect(session.posts[0]).toContain('01012345678');
	expect(session.alerts).toHaveLength(1);
});

test('a company edit opened with a number its country refuses is refused as legacy refuses it', async ({ page }) => {
	const session = await open(page, companyForm({ phone: '0712345678', code: '+966' }));

	expect(await submit(page, session)).toBe(false);
	expect(session.alerts).toEqual([INVALID]);
});

test('an employee needs no phone, but a phone needs a country', async ({ page }) => {
	const session = await open(page, employeeForm());

	expect(await submit(page, session), 'no phone, no country').toBe(true);

	await page.goto(`${ORIGIN}/admin/page`);
	await page.locator('#phone').fill('01012345678');
	expect(await submit(page, session), 'a phone with the optional choice left').toBe(false);
	expect(session.alerts).toEqual([INVALID]);

	await page.selectOption('#country_code', '+20');
	expect(await submit(page, session)).toBe(true);
});

test('an employee edit saves the phone and country it was opened with, even a pair the rules refuse', async ({ context }) => {
	// A joined employee's phone with no country code (R-019), and a code no active country has.
	for (const opened of [{ phone: '01012345678', code: '' }, { phone: '0712345678', code: '+882' }]) {
		const page = await context.newPage();
		const session = await open(page, employeeForm({ ...opened, edit: true }));
		expect(await submit(page, session), JSON.stringify(opened)).toBe(true);
		expect(session.alerts).toEqual([]);
		await page.close();
	}
});

test('an employee edit left through its phone field still saves the phone it was opened with, as stored', async ({ context }) => {
	// Leaving the field rewrites it to digits with a leading zero. A phone stored with a dial
	// prefix or spaces (register_employee.php stores it as typed), or without its zero, is still
	// the opened phone, and the save posts the stored text so the server keeps it.
	for (const opened of [
		{ phone: '+201012345678', code: '' },
		{ phone: '010 1234 5678', code: '' },
		{ phone: '1012345678', code: '+20' },
	]) {
		const page = await context.newPage();
		const session = await open(page, employeeForm({ ...opened, edit: true }));
		await page.locator('#phone').focus();
		await page.locator('#phone').blur();
		expect(await page.locator('#phone').inputValue(), 'the field was rewritten').not.toBe(opened.phone);

		expect(await submit(page, session), JSON.stringify(opened)).toBe(true);
		expect(session.alerts).toEqual([]);
		expect(new URLSearchParams(session.posts[0]).get('phone'), 'the stored text, not the rewrite').toBe(opened.phone);
		await page.close();
	}
});

test('an employee edit still checks a phone or a country that was changed', async ({ page }) => {
	const session = await open(page, employeeForm({ phone: '01012345678', code: '', edit: true }));

	await page.locator('#phone').fill('01012345679');
	expect(await submit(page, session), 'a new phone with no country').toBe(false);

	await page.goto(`${ORIGIN}/admin/page`);
	await page.selectOption('#country_code', '+966');
	expect(await submit(page, session), 'the same phone under a country that refuses it').toBe(false);
	expect(session.alerts).toEqual([INVALID, INVALID]);
});

test('an add is not marked, so a phone with no country is refused however it was opened', async ({ page }) => {
	const session = await open(page, employeeForm({ phone: '01012345678', code: '' }));

	expect(await submit(page, session)).toBe(false);
	expect(session.alerts).toEqual([INVALID]);
});
