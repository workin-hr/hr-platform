import { readFileSync } from 'node:fs';
import { test, expect } from '@playwright/test';

/**
 * workforce-form.js in a browser, with no stack behind it.
 *
 * <p>The workforce planning form fills its branch, department and job title
 * selects from maps the page carries: a company lists its branches, a branch its
 * departments and the job titles they hold, a department its own job titles, and
 * the company's job titles when nothing narrower applies. The save stays disabled
 * until a company, branch, job title and count are set. The Java end-to-end tests
 * read the maps; they cannot run the script. So the copied legacy script is loaded
 * from the source tree onto pages shaped like workforce-planning.jte's add and edit
 * forms, and what it does is observed in Chromium.
 */

const SCRIPT = readFileSync(
	new URL('../../../backend/src/main/resources/static/admin/_assets/workforce-form.js', import.meta.url),
	'utf8');

const MAPS = {
	branches: { 11: [{ id: 101, name: 'Alpha HQ' }, { id: 102, name: 'Alpha North' }], 12: [{ id: 201, name: 'Beta HQ' }] },
	departmentsByBranch: { 101: [{ id: 301, name: 'Alpha Ops' }, { id: 302, name: 'Alpha Sales' }], 103: [{ id: 303, name: 'Alpha Stores' }] },
	jobsByDepartment: { 301: [{ id: 401, name: 'Alpha Fitter' }], 302: [{ id: 402, name: 'Alpha Seller' }] },
	jobsByCompany: {
		11: [{ id: 403, name: 'Alpha Driver' }, { id: 401, name: 'Alpha Fitter' }, { id: 402, name: 'Alpha Seller' }],
		12: [{ id: 501, name: 'Beta Fitter' }],
	},
};

function page({ company, selected = { branch: '', department: '', job: '' }, planned = '1', maps = MAPS }) {
	return `<!doctype html>
<div class="modal-bg open" id="wpModal"><div class="modal modal--org-form">
  <form method="POST" novalidate data-org-wp-form
        data-branches-by-company='${JSON.stringify(maps.branches)}'
        data-departments-by-branch='${JSON.stringify(maps.departmentsByBranch)}'
        data-job-titles-by-dept='${JSON.stringify(maps.jobsByDepartment)}'
        data-job-titles-by-company='${JSON.stringify(maps.jobsByCompany)}'
        data-placeholder-branch="Branch..."
        data-placeholder-job="Job title..."
        data-selected-branch="${selected.branch}"
        data-selected-department="${selected.department}"
        data-selected-job-title="${selected.job}">
    ${company}
    <select name="branch_id" id="wp_branch" data-wp-branch required></select>
    <select name="department_id" id="wp_department" data-wp-department></select>
    <select name="job_title_id" id="wp_job" data-wp-job-title required></select>
    <input type="number" id="wp_planned" min="0" name="planned_count" required value="${planned}">
    <button type="submit" data-wp-submit disabled>Save</button>
  </form>
</div></div>`;
}

// Shaped like companyField.jte with cascade "wp": the select on an unfiltered add,
// the hidden input on a filtered add, and the edit's unnamed input.
const PICK = `<select name="company_id" id="wp_company_id" data-filter-company="1" required>
      <option value="">Company...</option><option value="11">Alpha Co</option><option value="12">Beta Co</option>
    </select>`;
const FILTERED = '<input type="hidden" name="company_id" value="11" data-fixed-company="11">';
const EDIT = '<input type="hidden" data-fixed-company="11" value="11">';

async function load(browserPage, html) {
	await browserPage.setContent(html);
	await browserPage.addScriptTag({ content: SCRIPT });
}

async function options(browserPage, selector) {
	return browserPage.locator(`${selector} option`)
		.evaluateAll((all) => all.map((option) => [option.value, option.textContent.trim()]));
}

const save = (browserPage) => browserPage.locator('[data-wp-submit]');

test.describe('an add with no company chosen', () => {
	test.beforeEach(async ({ page: browserPage }) => {
		await load(browserPage, page({ company: PICK }));
	});

	test('the branch select holds only its placeholder, and the save waits', async ({ page: browserPage }) => {
		expect(await options(browserPage, '#wp_branch')).toEqual([['', 'Branch...']]);
		await expect(save(browserPage)).toBeDisabled();
	});

	test("choosing a company lists its branches, and its job titles until a branch narrows them", async ({ page: browserPage }) => {
		await browserPage.selectOption('#wp_company_id', '11');

		expect(await options(browserPage, '#wp_branch')).toEqual([['', 'Branch...'], ['101', 'Alpha HQ'], ['102', 'Alpha North']]);
		expect(await options(browserPage, '#wp_department')).toEqual([['0', '—']]);
		expect(await options(browserPage, '#wp_job')).toEqual(
			[['', 'Job title...'], ['403', 'Alpha Driver'], ['401', 'Alpha Fitter'], ['402', 'Alpha Seller']]);
	});

	test('a branch lists its departments and the job titles they hold; a department narrows to its own', async ({ page: browserPage }) => {
		await browserPage.selectOption('#wp_company_id', '11');
		await browserPage.selectOption('#wp_branch', '101');

		expect(await options(browserPage, '#wp_department')).toEqual([['0', '—'], ['301', 'Alpha Ops'], ['302', 'Alpha Sales']]);
		expect(await options(browserPage, '#wp_job')).toEqual([['', 'Job title...'], ['401', 'Alpha Fitter'], ['402', 'Alpha Seller']]);

		await browserPage.selectOption('#wp_department', '301');
		expect(await options(browserPage, '#wp_job')).toEqual([['', 'Job title...'], ['401', 'Alpha Fitter']]);
	});

	test("a branch with no departments offers every job title of the company", async ({ page: browserPage }) => {
		await browserPage.selectOption('#wp_company_id', '11');
		await browserPage.selectOption('#wp_branch', '102');

		expect(await options(browserPage, '#wp_department')).toEqual([['0', '—']]);
		expect(await options(browserPage, '#wp_job')).toEqual(
			[['', 'Job title...'], ['403', 'Alpha Driver'], ['401', 'Alpha Fitter'], ['402', 'Alpha Seller']]);
	});

	test('the save opens once a branch, a job title and a count are set, and closes when the count is emptied', async ({ page: browserPage }) => {
		await browserPage.selectOption('#wp_company_id', '11');
		await browserPage.selectOption('#wp_branch', '101');
		await expect(save(browserPage)).toBeDisabled();

		await browserPage.selectOption('#wp_job', '401');
		await expect(save(browserPage)).toBeEnabled();

		await browserPage.fill('#wp_planned', '');
		await expect(save(browserPage)).toBeDisabled();
	});

	test("switching company lists the new company's branches and the save waits again", async ({ page: browserPage }) => {
		await browserPage.selectOption('#wp_company_id', '11');
		await browserPage.selectOption('#wp_branch', '101');
		await browserPage.selectOption('#wp_job', '401');

		await browserPage.selectOption('#wp_company_id', '12');

		expect(await options(browserPage, '#wp_branch')).toEqual([['', 'Branch...'], ['201', 'Beta HQ']]);
		await expect(browserPage.locator('#wp_branch')).toHaveValue('');
		await expect(save(browserPage)).toBeDisabled();
	});
});

test("a job title no department lists is offered on an add only when the chosen branch's or department's list is empty, as legacy's is", async ({ page: browserPage }) => {
	// 403 belongs to no department. Branch 101's departments list only 401, so choosing the branch drops 403; department
	// 304 lists no job titles, so choosing it falls back to the company's, the only path on which an add reaches 403.
	const maps = { ...MAPS, departmentsByBranch: { 101: [{ id: 301, name: 'Alpha Ops' }, { id: 304, name: 'Alpha Empty' }] } };
	await load(browserPage, page({ company: PICK, maps }));
	await browserPage.selectOption('#wp_company_id', '11');
	await browserPage.selectOption('#wp_branch', '101');
	expect(await options(browserPage, '#wp_job')).toEqual([['', 'Job title...'], ['401', 'Alpha Fitter']]);

	await browserPage.selectOption('#wp_department', '304');
	expect(await options(browserPage, '#wp_job')).toEqual(
		[['', 'Job title...'], ['403', 'Alpha Driver'], ['401', 'Alpha Fitter'], ['402', 'Alpha Seller']]);
	await browserPage.selectOption('#wp_job', '403');
	await expect(save(browserPage)).toBeEnabled();
});

test('a filtered add reads its company from the hidden input and lists that company on load', async ({ page: browserPage }) => {
	await load(browserPage, page({ company: FILTERED }));

	expect(await options(browserPage, '#wp_branch')).toEqual([['', 'Branch...'], ['101', 'Alpha HQ'], ['102', 'Alpha North']]);
});

test("an edit selects the row's branch, department and job title, and its save is open", async ({ page: browserPage }) => {
	await load(browserPage, page({ company: EDIT, selected: { branch: '101', department: '301', job: '401' }, planned: '3' }));

	await expect(browserPage.locator('#wp_branch')).toHaveValue('101');
	await expect(browserPage.locator('#wp_department')).toHaveValue('301');
	await expect(browserPage.locator('#wp_job')).toHaveValue('401');
	await expect(save(browserPage)).toBeEnabled();
});

test("an edit shows a department not linked to the row's branch by id, and keeps it, as legacy's does", async ({ page: browserPage }) => {
	// Department 303 is linked to branch 103 only. The script lists a branch's own departments, so on an edit of a
	// plan on branch 101 it keeps 303 selected under an option labelled #303; a retired department is absent the same way.
	await load(browserPage, page({ company: EDIT, selected: { branch: '101', department: '303', job: '401' }, planned: '3' }));

	await expect(browserPage.locator('#wp_department')).toHaveValue('303');
	expect(await options(browserPage, '#wp_department')).toEqual(
		[['0', '—'], ['301', 'Alpha Ops'], ['302', 'Alpha Sales'], ['303', '#303']]);
	await expect(save(browserPage)).toBeEnabled();
});

test("an edit with no department shows a job title outside the branch's departments by id, as legacy's does", async ({ page: browserPage }) => {
	// Branch 101's departments hold only 401 and 402. With no department chosen the script lists those, and
	// falls back to the company's job titles only when the branch's list is empty, so an active 403 is not listed.
	await load(browserPage, page({ company: EDIT, selected: { branch: '101', department: '0', job: '403' }, planned: '3' }));

	await expect(browserPage.locator('#wp_job')).toHaveValue('403');
	expect(await options(browserPage, '#wp_job')).toEqual(
		[['', 'Job title...'], ['401', 'Alpha Fitter'], ['402', 'Alpha Seller'], ['403', '#403']]);
});

test("an edit whose branch is no longer active shows it by id, as legacy's does", async ({ page: browserPage }) => {
	await load(browserPage, page({ company: EDIT, selected: { branch: '109', department: '0', job: '403' }, planned: '3' }));

	await expect(browserPage.locator('#wp_branch')).toHaveValue('109');
	expect(await options(browserPage, '#wp_branch')).toContainEqual(['109', '#109']);
});
