import { readFileSync } from 'node:fs';
import { test, expect } from '@playwright/test';

/**
 * The employee window's sections and grids, under the stylesheets that lay them out.
 *
 * <p>`_employee_form.php` groups the form into four titled sections, each a two-column
 * grid with full-width rows for the long fields, and gives the salary section two
 * columns of its own. `employee-form.css` and `org-form.css` are legacy's byte for
 * byte, and the port rendered one flat column of `form-row`s, so every one of those
 * rules matched nothing. Nothing in Java can see that: `AdminEmployeesEndToEndTest`
 * reads the markup, and markup is not layout. So the window is drawn here with the
 * template's nesting under the layout's sheets and the page's own, and measured at a
 * desktop and a phone width.
 */

const asset = (name) => readFileSync(
	new URL(`../../../backend/src/main/resources/static/admin/_assets/${name}`, import.meta.url),
	'utf8');

const template = (name) => readFileSync(
	new URL(`../../../backend/src/main/jte/admin/${name}`, import.meta.url),
	'utf8');

/** The shared stylesheets `layout.jte` links, in its order; see employee-detail.spec.js. */
function layoutSheets() {
	const layout = template('layout.jte');
	const sheets = [...layout.matchAll(/<link rel="stylesheet" href="\/admin\/_assets\/((?:vendor\/)?[\w.-]+\.css)">/g)].map((match) => match[1]);
	if (sheets.length === 0 || !sheets.includes('app-ui.css')) {
		throw new Error(`layout.jte's stylesheet links did not read as expected: ${sheets}`);
	}
	return sheets;
}

/** `employees.jte`'s own sheets, which the layout links after the shared ones. */
function pageSheets() {
	const declared = template('employees.jte').match(/pageStyles = java\.util\.List\.of\(([^)]*)\)/);
	if (!declared) {
		throw new Error('employees.jte declares no pageStyles');
	}
	const sheets = [...declared[1].matchAll(/"([\w-]+\.css)"/g)].map((match) => match[1]);
	if (!sheets.includes('employee-form.css') || !sheets.includes('org-form.css')) {
		throw new Error(`employees.jte's pageStyles did not read as expected: ${declared[1]}`);
	}
	return sheets;
}

const SHEETS = [...layoutSheets(), ...pageSheets()];

/** A `form-row`, half the grid or the whole of it, as the template writes them. */
const row = (probe, span) =>
	`<div class="form-row${span ? ' org-form-span-2' : ''}" data-probe="${probe}">`
	+ '<label for="' + probe + '">label</label><input type="text" id="' + probe + '" name="' + probe + '"></div>';

/**
 * The add window, nested as `employees.jte` nests it: the open backdrop, the wide
 * modal, the `org-form-grid-wrap` form, and inside it the sections with their titles
 * and grids.
 */
const WINDOW = `<!doctype html><html lang="ar" dir="rtl"><body class="lang-ar">
<div class="shell"><main class="main" id="main-content"><div class="content hr-page">
<div class="modal-bg open">
	<div class="modal modal--employee-form">
		<a href="#" class="modal-close" aria-label="close">&#215;</a>
		<h2>add employee</h2>
		<form method="POST" class="org-form-grid-wrap">
			<section class="emp-form-section org-form-span-2">
				<h3 class="emp-form-section__title" data-probe="title">personal data</h3>
				<div class="org-form-grid" data-probe="personal-grid">
					${row('half-row', false)}
					${row('second-half-row', false)}
					${row('full-row', true)}
					<div class="form-row org-form-span-2 emp-login-credentials">
						<p class="form-hint emp-login-credentials__hint">login hint</p>
						<div class="emp-login-credentials__grid" data-probe="login-grid">
							<div class="emp-login-credentials__country"><label for="country_code">country</label>
								<select id="country_code" name="country_code"><option>+20</option></select></div>
							<div class="emp-login-credentials__phone"><label for="phone">phone</label>
								<input type="text" id="phone" name="phone"></div>
							<div class="emp-login-credentials__password"><label for="password">password</label>
								<input type="password" id="password" name="password"></div>
						</div>
					</div>
				</div>
			</section>
			<section class="emp-form-section org-form-span-2">
				<h3 class="emp-form-section__title">salary data</h3>
				<div class="org-form-grid emp-salary-grid" data-probe="salary-grid">
					<div><h4 class="emp-form-subtitle" data-probe="subtitle">entitlements</h4>
						${row('basic_salary', false)}</div>
					<div><h4 class="emp-form-subtitle">deductions</h4>
						${row('penalty', false)}</div>
				</div>
			</section>
			<div class="form-footer org-form-span-2"><a href="#" class="btn btn-gray">cancel</a>
				<button type="submit" class="btn btn-blue">add employee</button></div>
		</form>
	</div>
</div>
</div></main></div>
</body></html>`;

async function measure(context, width) {
	const tab = await context.newPage();
	await tab.setViewportSize({ width, height: 900 });
	await tab.setContent(WINDOW);
	for (const sheet of SHEETS) {
		await tab.addStyleTag({ content: asset(sheet) });
	}
	const measured = await tab.evaluate(() => {
		const probe = (name) => document.querySelector(`[data-probe="${name}"]`);
		const columns = (name) => getComputedStyle(probe(name)).gridTemplateColumns.trim().split(/\s+/).length;
		const width = (name) => Math.round(probe(name).getBoundingClientRect().width);
		return {
			personalColumns: columns('personal-grid'),
			salaryColumns: columns('salary-grid'),
			loginColumns: columns('login-grid'),
			half: width('half-row'),
			full: width('full-row'),
			titleBorder: getComputedStyle(probe('title')).borderBottomWidth,
			subtitleWeight: getComputedStyle(probe('subtitle')).fontWeight,
			documentScrollWidth: document.documentElement.scrollWidth,
			viewport: document.documentElement.clientWidth,
		};
	});
	await tab.close();
	return measured;
}

test('the sections lay out two across at a desktop width', async ({ context }) => {
	const measured = await measure(context, 1440);

	expect(measured.personalColumns, 'org-form-grid-wrap .org-form-grid is 1fr 1fr').toBe(2);
	expect(measured.salaryColumns, 'emp-salary-grid is 1fr 1fr').toBe(2);
	expect(measured.loginColumns, 'the country, phone and password sit in one row').toBe(3);
	expect(measured.full, 'org-form-span-2 runs the whole grid').toBeGreaterThan(measured.half * 1.8);
	expect(measured.titleBorder, 'the section title is a ruled heading').toBe('2px');
	expect(measured.subtitleWeight).toBe('600');
});

test('every grid collapses to one column on a phone', async ({ context }) => {
	const measured = await measure(context, 390);

	expect(measured.personalColumns).toBe(1);
	expect(measured.salaryColumns).toBe(1);
	expect(measured.loginColumns).toBe(1);
	expect(measured.full, 'a full-width row and a half-width one are the same width').toBe(measured.half);
	expect(measured.documentScrollWidth, 'the window does not scroll sideways')
		.toBeLessThanOrEqual(measured.viewport);
});

test('employees.jte nests the window the way this spec measures it', () => {
	// The markup above is a copy of the template's nesting, so without this the
	// spec would keep passing against a shape nothing renders.
	const markup = template('employees.jte');

	expect(markup, 'the form is the grid wrapper employee-form.css scopes its columns to')
		.toContain('<form method="POST" class="org-form-grid-wrap" data-employee-form');

	const sections = [...markup.matchAll(
		/<section class="emp-form-section org-form-span-2">\s*<h3 class="emp-form-section__title">\$\{t\.apply\("(\w+)"\)\}<\/h3>\s*<div class="org-form-grid([^"]*)">/g,
	)].map((match) => [match[1], match[2].trim()]);
	expect(sections, "_employee_form.php's four sections, in its order").toEqual([
		['personal_data', ''],
		['job_details', ''],
		['mobile_attendance', ''],
		['salary_data', 'emp-salary-grid'],
	]);

	expect(markup, 'the login block, which places the country, phone and password')
		.toContain('<div class="form-row org-form-span-2 emp-login-credentials">');
	for (const name of ['emp-login-credentials__hint', 'emp-login-credentials__grid',
		'emp-login-credentials__country', 'emp-login-credentials__phone',
		'emp-login-credentials__password', 'emp-form-subtitle',
		'modal--employee-form', 'modal-close']) {
		expect(markup, `employees.jte writes ${name}`).toContain(name);
	}
});
