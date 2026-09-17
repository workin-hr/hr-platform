import { readFileSync } from 'node:fs';
import { test, expect } from '@playwright/test';

/**
 * org-filter-cascade.js and request-filter-cascade.js in a browser, with no stack behind them.
 *
 * <p>A list page's toolbar narrows its selects as they change: a company lists its branches, a
 * branch its departments, a department its job titles, and the requests page's company its
 * request types. The Java end-to-end tests read the maps the toolbar carries; they cannot run
 * the scripts. So the copied legacy scripts are loaded from the source tree onto toolbars shaped
 * like the templates' -- the maps written into attributes as JTE writes them, quotes escaped --
 * and what they do is observed in Chromium.
 */

const asset = (name) => readFileSync(
	new URL(`../../../backend/src/main/resources/static/admin/_assets/${name}`, import.meta.url),
	'utf8');

const MAPS = {
	branchesByCompany: {
		11: [{ id: 21, name: 'Alpha HQ' }, { id: 22, name: 'Alpha North' }, { id: 24, name: 'Alpha Empty' }],
		12: [{ id: 23, name: 'Beta HQ' }],
	},
	departmentsByCompany: {
		11: [{ id: 31, name: 'Alpha Ops' }, { id: 32, name: 'Alpha Sales' }],
		12: [{ id: 33, name: 'Beta Ops' }],
	},
	departmentsByBranch: {
		21: [{ id: 31, name: 'Alpha Ops' }, { id: 32, name: 'Alpha Sales' }],
		22: [{ id: 32, name: 'Alpha Sales' }],
		23: [{ id: 33, name: 'Beta Ops' }],
	},
	jobTitlesByDepartment: {
		31: [{ id: 41, name: 'Alpha Fitter' }],
		32: [{ id: 42, name: 'Alpha "Seller" <b>x</b>' }],
		33: [{ id: 43, name: 'Beta Fitter' }],
	},
};

/** An attribute value as JTE writes it: double-quoted, with `"`, `'`, `<`, `>` and `&` escaped. */
const attr = (value) => String(value)
	.replaceAll('&', '&amp;').replaceAll('"', '&#34;').replaceAll("'", '&#39;')
	.replaceAll('<', '&lt;').replaceAll('>', '&gt;');

// companyFilterField.jte, as it renders for an administrator.
const company = (selected) => `<div class="filter-field">
  <label class="filter-field__label" for="org_company">company</label>
  <select name="company_id" id="org_company" data-filter-company>
    <option value="">All companies</option>
    <option value="11"${selected === '11' ? ' selected' : ''}>Alpha Co</option>
    <option value="12"${selected === '12' ? ' selected' : ''}>Beta Co</option>
  </select>
</div>`;

// The server's own options, which the script replaces: every company's, as the port lists
// them for an unfiltered administrator.
const serverOptions = (rows) => `<option value="0">All</option>${rows.map((row) => `<option value="${row.id}">${row.name}</option>`).join('')}`;

/** A toolbar shaped like employees.jte's, attendance.jte's, departments.jte's or job-titles.jte's. */
function toolbar({ selectedCompany = '', selected = {}, selects = ['branch', 'department', 'job'], attached = true, withCompany = true }) {
	const cascade = attached ? ` data-org-filters="1"
      data-branches-by-company="${attr(JSON.stringify(MAPS.branchesByCompany))}"
      data-departments-by-company="${attr(JSON.stringify(MAPS.departmentsByCompany))}"
      data-departments-by-branch="${attr(JSON.stringify(MAPS.departmentsByBranch))}"
      data-job-titles-by-dept="${attr(JSON.stringify(MAPS.jobTitlesByDepartment))}"` : '';
	const fields = {
		branch: `<select id="emp_branch_f" name="filter_branch" data-filter-branch>${serverOptions([{ id: 21, name: 'Alpha Co — Alpha HQ' }, { id: 23, name: 'Beta Co — Beta HQ' }])}</select>`,
		department: `<select id="emp_dept_f" name="filter_department" data-filter-department>${serverOptions([{ id: 31, name: 'Alpha Ops' }, { id: 33, name: 'Beta Ops' }])}</select>`,
		job: `<select id="emp_job_f" name="filter_job_title" data-filter-job-title>${serverOptions([{ id: 41, name: 'Alpha Fitter' }, { id: 43, name: 'Beta Fitter' }])}</select>`,
	};
	return `<!doctype html><html lang="ar" dir="rtl"><body>
<div class="toolbar page-toolbar page-toolbar-filters">
  <form method="GET" class="toolbar-form toolbar-form--labeled"${cascade}
        data-filter-all="الكل"
        data-select-company-msg="كل الشركات"
        data-pick-branch-first-msg="اختر الفرع"
        data-pick-dept-first-msg="اختر القسم"
        data-selected-branch="${selected.branch ?? '0'}"
        data-selected-department="${selected.department ?? '0'}"
        data-selected-job-title="${selected.job ?? '0'}">
    ${withCompany ? company(selectedCompany) : ''}
    ${selects.map((name) => fields[name]).join('\n    ')}
    <button type="submit" class="btn btn-blue">search</button>
  </form>
</div></body></html>`;
}

async function load(page, html, scripts = ['org-filter-cascade.js']) {
	await page.setContent(html);
	for (const script of scripts) {
		await page.addScriptTag({ content: asset(script) });
	}
}

/** A select's options as [value, text, disabled], and its value. */
async function options(page, selector) {
	return page.locator(selector).evaluate((select) => ({
		value: select.value,
		disabled: select.disabled,
		options: [...select.options].map((option) => [option.value, option.textContent, option.disabled]),
	}));
}

test('a filtered page lists the company\'s branches, the branch\'s departments and the department\'s job titles, keeping each choice', async ({ page }) => {
	await load(page, toolbar({ selectedCompany: '11', selected: { branch: '22', department: '32', job: '42' } }));

	expect(await options(page, '#emp_branch_f')).toEqual({ value: '22', disabled: false, options: [
		['0', 'الكل', false], ['21', 'Alpha HQ', false], ['22', 'Alpha North', false], ['24', 'Alpha Empty', false]] });
	expect(await options(page, '#emp_dept_f')).toEqual({ value: '32', disabled: false, options: [
		['0', 'الكل', false], ['32', 'Alpha Sales', false]] });
	expect(await options(page, '#emp_job_f'), 'a name is text, not markup').toEqual({ value: '42', disabled: false, options: [
		['0', 'الكل', false], ['42', 'Alpha "Seller" <b>x</b>', false]] });
});

test('changing the company lists its branches and its departments\' job titles, and clears the narrower choices', async ({ page }) => {
	await load(page, toolbar({ selectedCompany: '11', selected: { branch: '22', department: '32', job: '42' } }));

	await page.selectOption('#org_company', '12');

	expect(await options(page, '#emp_branch_f')).toEqual({ value: '0', disabled: false, options: [
		['0', 'الكل', false], ['23', 'Beta HQ', false]] });
	expect(await options(page, '#emp_dept_f')).toEqual({ value: '0', disabled: false, options: [
		['0', 'الكل', false], ['33', 'Beta Ops', false]] });
	expect(await options(page, '#emp_job_f')).toEqual({ value: '0', disabled: false, options: [
		['0', 'الكل', false], ['43', 'Beta Fitter', false]] });
});

test('changing the branch lists its departments and their job titles; changing the department, its own', async ({ page }) => {
	await load(page, toolbar({ selectedCompany: '11' }));

	await page.selectOption('#emp_branch_f', '21');
	expect((await options(page, '#emp_dept_f')).options.map(([value]) => value)).toEqual(['0', '31', '32']);
	expect((await options(page, '#emp_job_f')).options.map(([value]) => value)).toEqual(['0', '41', '42']);

	await page.selectOption('#emp_dept_f', '31');
	expect((await options(page, '#emp_job_f')).options.map(([value]) => value)).toEqual(['0', '41']);
});

test('a branch with no departments shows the pick-a-department hint', async ({ page }) => {
	await load(page, toolbar({ selectedCompany: '11' }));

	await page.selectOption('#emp_branch_f', '24');

	expect(await options(page, '#emp_dept_f')).toEqual({ value: '0', disabled: false, options: [
		['0', 'الكل', false], ['0', 'اختر القسم', true]] });
});

test('with every company, the branch, department and job title filters wait for a company, as legacy\'s do', async ({ page }) => {
	await load(page, toolbar({ selectedCompany: '' }));

	for (const select of ['#emp_branch_f', '#emp_dept_f', '#emp_job_f']) {
		expect(await options(page, select), select).toEqual({ value: '0', disabled: false, options: [['0', 'الكل', false]] });
	}
});

test('a department-only toolbar, as on job titles, lists the chosen company\'s departments', async ({ page }) => {
	await load(page, toolbar({ selectedCompany: '11', selected: { department: '32' }, selects: ['department'] }));

	expect(await options(page, '#emp_dept_f')).toEqual({ value: '32', disabled: false, options: [
		['0', 'الكل', false], ['31', 'Alpha Ops', false], ['32', 'Alpha Sales', false]] });

	await page.selectOption('#org_company', '12');
	expect((await options(page, '#emp_dept_f')).options.map(([value]) => value)).toEqual(['0', '33']);
});

test('a branch-only toolbar, as on departments, lists the chosen company\'s branches', async ({ page }) => {
	await load(page, toolbar({ selectedCompany: '12', selected: { branch: '23' }, selects: ['branch'] }));

	expect(await options(page, '#emp_branch_f')).toEqual({ value: '23', disabled: false, options: [
		['0', 'الكل', false], ['23', 'Beta HQ', false]] });
});

test('a toolbar without data-org-filters, as for a session bound to one company, keeps the server\'s options', async ({ page }) => {
	await load(page, toolbar({ attached: false, withCompany: false }));

	expect((await options(page, '#emp_branch_f')).options.map(([value]) => value)).toEqual(['0', '21', '23']);
	expect((await options(page, '#emp_dept_f')).options.map(([value]) => value)).toEqual(['0', '31', '33']);
	expect((await options(page, '#emp_job_f')).options.map(([value]) => value)).toEqual(['0', '41', '43']);
});

const TYPES = { 11: [{ id: 51, name: 'Annual' }, { id: 52, name: 'Unpaid' }], 12: [{ id: 53, name: 'Annual' }] };

// requests.jte's toolbar: the select as the server renders it, disabled and asking for a
// company when none is chosen.
function requestsToolbar({ selectedCompany = '', selectedType = '0' }) {
	const needsCompany = selectedCompany === '';
	const listed = needsCompany ? '<option value="0" disabled>اختر الشركة أولاً</option>'
		: TYPES[selectedCompany].map((type) => `<option value="${type.id}"${String(type.id) === selectedType ? ' selected' : ''}>${type.name}</option>`).join('');
	return `<!doctype html><html lang="ar" dir="rtl"><body>
<form method="GET" class="toolbar-form toolbar-form--labeled"
      data-request-filters="1"
      data-request-types-by-company="${attr(JSON.stringify(TYPES))}"
      data-filter-all="الكل"
      data-select-company-msg="اختر الشركة أولاً"
      data-selected-request-type="${selectedType}">
  ${company(selectedCompany)}
  <select id="rq_type" name="type_id" data-filter-request-type${needsCompany ? ' disabled' : ''}>
    <option value="0">الكل</option>${listed}
  </select>
</form></body></html>`;
}

test('the request type filter waits for a company, then lists that company\'s types', async ({ page }) => {
	await load(page, requestsToolbar({}), ['request-filter-cascade.js']);

	expect(await options(page, '#rq_type')).toEqual({ value: '0', disabled: true, options: [
		['0', 'الكل', false], ['0', 'اختر الشركة أولاً', true]] });

	await page.selectOption('#org_company', '12');
	expect(await options(page, '#rq_type')).toEqual({ value: '0', disabled: false, options: [
		['0', 'الكل', false], ['53', 'Annual', false]] });

	await page.selectOption('#org_company', '');
	expect((await options(page, '#rq_type')).disabled).toBe(true);
});

test('a filtered requests page keeps its chosen type', async ({ page }) => {
	await load(page, requestsToolbar({ selectedCompany: '11', selectedType: '52' }), ['request-filter-cascade.js']);

	expect(await options(page, '#rq_type')).toEqual({ value: '52', disabled: false, options: [
		['0', 'الكل', false], ['51', 'Annual', false], ['52', 'Unpaid', false]] });
});
