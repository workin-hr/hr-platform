import { readFileSync } from 'node:fs';
import { test, expect } from '@playwright/test';

/**
 * employee-form.js and employee-shift.js in a browser, with no stack behind them.
 *
 * <p>The employee form fills its branch, department and job title selects through
 * legacy's employee-form.js, copied unchanged, from maps the page carries: a company
 * lists its branches and departments, a branch its own departments, a department its
 * job titles, and a company its job titles when nothing narrower applies. When the form
 * names its company, the port's employee-shift.js fills the shift select from a map of
 * shifts by company (D-250). The Java end-to-end tests read the maps; they cannot run
 * the scripts. So both are loaded from the source tree onto pages shaped like
 * employees.jte's add and edit forms, and what they do is observed in Chromium.
 */

const ASSETS = new URL('../../../backend/src/main/resources/static/admin/_assets/', import.meta.url);
const FORM_SCRIPT = readFileSync(new URL('employee-form.js', ASSETS), 'utf8');
const SHIFT_SCRIPT = readFileSync(new URL('employee-shift.js', ASSETS), 'utf8');

const MAPS = {
	branches: { 11: [{ id: 101, name: 'Alpha HQ' }, { id: 102, name: 'Alpha North' }], 12: [{ id: 201, name: 'Beta HQ' }] },
	departmentsByCompany: {
		11: [{ id: 301, name: 'Alpha Ops' }, { id: 302, name: 'Alpha Sales' }, { id: 303, name: 'Alpha Stores' }],
		12: [{ id: 311, name: 'Beta Ops' }],
	},
	departmentsByBranch: {
		101: [{ id: 301, name: 'Alpha Ops' }, { id: 302, name: 'Alpha Sales' }],
		201: [{ id: 311, name: 'Beta Ops' }],
	},
	jobsByDepartment: {
		301: [{ id: 401, name: 'Alpha Fitter' }],
		302: [{ id: 402, name: 'Alpha Seller' }],
		311: [{ id: 411, name: 'Beta Fitter' }],
	},
	jobsByCompany: {
		11: [{ id: 403, name: 'Alpha Driver' }, { id: 401, name: 'Alpha Fitter' }, { id: 402, name: 'Alpha Seller' }],
		12: [{ id: 411, name: 'Beta Fitter' }],
	},
	shifts: { 11: [{ id: 501, name: 'Alpha Day' }, { id: 502, name: 'Alpha Night' }], 12: [{ id: 511, name: 'Beta Day' }] },
};

// What employees.jte writes for an add: legacy's form puts the page's company, 0 with no filter. The shift
// select's empty choice is "" on an add, as legacy's is, and 0 on an edit (shiftNone).
const ADD = { company: '0', branch: '0', department: '0', job: '0', jobLabel: '' };

function page({ company, selected = ADD, maps = MAPS, branchOptions = '', shiftOptions = '', shiftNone = '' }) {
	const json = (value) => JSON.stringify(value);
	return `<!doctype html>
<div class="modal-bg open"><div class="modal modal--employee-form">
  <form method="POST" data-employee-form
        data-branches-by-company='${json(maps.branches)}'
        data-departments-by-company='${json(maps.departmentsByCompany)}'
        data-departments-by-branch='${json(maps.departmentsByBranch)}'
        data-job-titles-by-dept='${json(maps.jobsByDepartment)}'
        data-job-titles-by-company='${json(maps.jobsByCompany)}'
        data-shifts-by-company='${json(maps.shifts)}'
        data-select-company-msg="Select company first"
        data-select-branch-msg="Pick branch"
        data-select-dept-msg="Pick department"
        data-selected-company="${selected.company}"
        data-selected-branch="${selected.branch}"
        data-selected-department="${selected.department}"
        data-selected-job="${selected.job}"
        data-selected-job-label="${selected.jobLabel}">
    ${company}
    <select id="branch_id" name="branch_id" data-emp-branch><option value="0">—</option>${branchOptions}</select>
    <select id="department_id" name="department_id" data-emp-department><option value="0">—</option></select>
    <select id="job_title_id" name="job_title_id" data-emp-job><option value="0">—</option></select>
    <select id="shift_id" name="shift_id" required data-emp-shift><option value="${shiftNone}">—</option>${shiftOptions}</select>
    <button type="submit">Save</button>
  </form>
</div></div>`;
}

// Shaped like employees.jte: the select on an unfiltered add, the hidden input on a
// filtered add, and nothing on an edit, which takes its company from the row (D-176).
const PICK = `<select name="company_id" id="emp_company" required>
      <option value="">Search...</option><option value="11">Alpha Co</option><option value="12">Beta Co</option>
    </select>`;
const FILTERED = '<input type="hidden" name="company_id" value="11">';
const EDIT = '';

async function load(browserPage, html) {
	await browserPage.setContent(html);
	await browserPage.addScriptTag({ content: FORM_SCRIPT });
	await browserPage.addScriptTag({ content: SHIFT_SCRIPT });
}

async function options(browserPage, selector) {
	return browserPage.locator(`${selector} option`)
		.evaluateAll((all) => all.map((option) => [option.value, option.textContent.trim()]));
}

async function valid(browserPage) {
	return browserPage.locator('form').evaluate((form) => form.checkValidity());
}

async function posted(browserPage) {
	return browserPage.locator('form').evaluate((form) => Object.fromEntries(new FormData(form).entries()));
}

test.describe('an add with no company chosen', () => {
	test.beforeEach(async ({ page: browserPage }) => {
		// The server renders every company's branches and shifts, labelled; the scripts replace them.
		await load(browserPage, page({
			company: PICK,
			branchOptions: '<option value="101">Alpha HQ — Alpha Co</option><option value="201">Beta HQ — Beta Co</option>',
			shiftOptions: '<option value="501">Alpha Day — Alpha Co</option><option value="511">Beta Day — Beta Co</option>',
		}));
	});

	test('on load the lists wait for a company, and no shift is offered', async ({ page: browserPage }) => {
		expect(await options(browserPage, '#branch_id')).toEqual([['0', 'Pick branch']]);
		expect(await options(browserPage, '#department_id')).toEqual([['0', '—']]);
		await expect(browserPage.locator('#department_id')).toBeDisabled();
		expect(await options(browserPage, '#job_title_id')).toEqual([['0', '—'], ['0', 'Pick department']]);
		await expect(browserPage.locator('#job_title_id')).toBeDisabled();
		expect(await options(browserPage, '#shift_id')).toEqual([['', '—']]);
	});

	test("choosing a company lists its branches, departments, job titles and shifts", async ({ page: browserPage }) => {
		await browserPage.selectOption('#emp_company', '11');

		expect(await options(browserPage, '#branch_id')).toEqual([['0', 'Pick branch'], ['101', 'Alpha HQ'], ['102', 'Alpha North']]);
		expect(await options(browserPage, '#department_id')).toEqual(
			[['0', '—'], ['301', 'Alpha Ops'], ['302', 'Alpha Sales'], ['303', 'Alpha Stores']]);
		expect(await options(browserPage, '#job_title_id')).toEqual(
			[['0', '—'], ['403', 'Alpha Driver'], ['401', 'Alpha Fitter'], ['402', 'Alpha Seller']]);
		expect(await options(browserPage, '#shift_id')).toEqual([['', '—'], ['501', 'Alpha Day'], ['502', 'Alpha Night']]);
	});

	test('a branch narrows the departments to its own and the job titles to theirs; a department to its own', async ({ page: browserPage }) => {
		await browserPage.selectOption('#emp_company', '11');
		await browserPage.selectOption('#branch_id', '101');

		expect(await options(browserPage, '#department_id')).toEqual([['0', '—'], ['301', 'Alpha Ops'], ['302', 'Alpha Sales']]);
		expect(await options(browserPage, '#job_title_id')).toEqual([['0', '—'], ['401', 'Alpha Fitter'], ['402', 'Alpha Seller']]);

		await browserPage.selectOption('#department_id', '302');
		expect(await options(browserPage, '#job_title_id')).toEqual([['0', '—'], ['402', 'Alpha Seller']]);
	});

	test("switching company lists the other company's shifts and drops the one chosen", async ({ page: browserPage }) => {
		await browserPage.selectOption('#emp_company', '11');
		await browserPage.selectOption('#shift_id', '502');

		await browserPage.selectOption('#emp_company', '12');

		expect(await options(browserPage, '#shift_id')).toEqual([['', '—'], ['511', 'Beta Day']]);
		await expect(browserPage.locator('#shift_id')).toHaveValue('');
		expect(await options(browserPage, '#branch_id')).toEqual([['0', 'Pick branch'], ['201', 'Beta HQ']]);
	});

	test('clearing the company makes the lists wait again and offers no shift', async ({ page: browserPage }) => {
		await browserPage.selectOption('#emp_company', '11');
		await browserPage.selectOption('#emp_company', '');

		expect(await options(browserPage, '#branch_id')).toEqual([['0', 'Select company first']]);
		await expect(browserPage.locator('#branch_id')).toBeDisabled();
		await expect(browserPage.locator('#department_id')).toBeDisabled();
		await expect(browserPage.locator('#job_title_id')).toBeDisabled();
		expect(await options(browserPage, '#shift_id')).toEqual([['', '—']]);
	});

	test('a completed add posts the company, branch, department, job title and shift chosen', async ({ page: browserPage }) => {
		await browserPage.selectOption('#emp_company', '12');
		await browserPage.selectOption('#branch_id', '201');
		await browserPage.selectOption('#department_id', '311');
		await browserPage.selectOption('#job_title_id', '411');
		await browserPage.selectOption('#shift_id', '511');

		expect(await posted(browserPage)).toMatchObject(
			{ company_id: '12', branch_id: '201', department_id: '311', job_title_id: '411', shift_id: '511' });
	});

	test('an add with no shift chosen fails the browser\'s validation, and passes once one is', async ({ page: browserPage }) => {
		await browserPage.selectOption('#emp_company', '11');
		await browserPage.selectOption('#branch_id', '101');
		expect(await valid(browserPage)).toBe(false);

		await browserPage.selectOption('#shift_id', '501');
		expect(await valid(browserPage)).toBe(true);
	});
});

test("a filtered add lists its company's departments and job titles on load, and keeps the server's shifts", async ({ page: browserPage }) => {
	await load(browserPage, page({
		company: FILTERED,
		selected: { ...ADD, company: '11' },
		branchOptions: '<option value="101">Alpha HQ</option><option value="102">Alpha North</option>',
		shiftOptions: '<option value="501">Alpha Day</option><option value="502">Alpha Night</option>',
	}));

	expect(await options(browserPage, '#branch_id')).toEqual([['0', '—'], ['101', 'Alpha HQ'], ['102', 'Alpha North']]);
	expect(await options(browserPage, '#department_id')).toEqual(
		[['0', '—'], ['301', 'Alpha Ops'], ['302', 'Alpha Sales'], ['303', 'Alpha Stores']]);
	expect(await options(browserPage, '#job_title_id')).toEqual(
		[['0', '—'], ['403', 'Alpha Driver'], ['401', 'Alpha Fitter'], ['402', 'Alpha Seller']]);
	expect(await options(browserPage, '#shift_id')).toEqual([['', '—'], ['501', 'Alpha Day'], ['502', 'Alpha Night']]);
});

test("an edit's maps list the employee's department under their branch, so it shows by name and is posted", async ({ page: browserPage }) => {
	// Department 303 is linked to no branch. The port's edit maps list it under the employee's branch (D-250).
	const maps = {
		...MAPS,
		departmentsByBranch: { ...MAPS.departmentsByBranch, 102: [{ id: 303, name: 'Alpha Stores' }] },
	};
	await load(browserPage, page({
		company: EDIT,
		shiftNone: '0',
		selected: { company: '11', branch: '102', department: '303', job: '0', jobLabel: '' },
		maps,
		branchOptions: '<option value="102" selected>Alpha North</option>',
		shiftOptions: '<option value="501" selected>Alpha Day</option>',
	}));

	await expect(browserPage.locator('#department_id')).toBeEnabled();
	await expect(browserPage.locator('#department_id')).toHaveValue('303');
	expect(await options(browserPage, '#department_id')).toEqual([['0', '—'], ['303', 'Alpha Stores']]);
	expect(await posted(browserPage)).toMatchObject({ branch_id: '102', department_id: '303', shift_id: '501' });
});

test("an edit keeps another company's stored department by id and posts it, which the service then refuses", async ({ page: browserPage }) => {
	// Department 999 belongs to another company, as legacy's unguarded save_edit can leave it. The maps are the employee's
	// company's, so the script keeps it under #999; the service refuses the post until it is changed (D-250).
	await load(browserPage, page({
		company: EDIT,
		shiftNone: '0',
		selected: { company: '11', branch: '101', department: '999', job: '0', jobLabel: '' },
		branchOptions: '<option value="101" selected>Alpha HQ</option>',
	}));

	await expect(browserPage.locator('#department_id')).toHaveValue('999');
	expect(await options(browserPage, '#department_id')).toEqual([['0', '—'], ['301', 'Alpha Ops'], ['302', 'Alpha Sales'], ['999', '#999']]);
	expect(await posted(browserPage)).toMatchObject({ department_id: '999' });
});

test("with legacy's maps, an edit whose branch lists no departments disables the department select, which posts nothing", async ({ page: browserPage }) => {
	// Why D-250 lists the employee's department under their branch: the port reads a missing department_id as none,
	// so an unchanged save from this form would clear the department, as legacy's does.
	await load(browserPage, page({
		company: EDIT,
		shiftNone: '0',
		selected: { company: '11', branch: '102', department: '303', job: '0', jobLabel: '' },
		branchOptions: '<option value="102" selected>Alpha North</option>',
		shiftOptions: '<option value="501" selected>Alpha Day</option>',
	}));

	await expect(browserPage.locator('#department_id')).toBeDisabled();
	expect(await posted(browserPage)).not.toHaveProperty('department_id');
});

test("an edit keeps the employee's job title under its name when the department does not list it", async ({ page: browserPage }) => {
	// 409 is retired: the port's edit maps list it only by company, which is where employee-form.js looks names up.
	const maps = {
		...MAPS,
		jobsByCompany: { ...MAPS.jobsByCompany, 11: [...MAPS.jobsByCompany[11], { id: 409, name: 'Alpha Welder' }] },
	};
	await load(browserPage, page({
		company: EDIT,
		shiftNone: '0',
		selected: { company: '11', branch: '101', department: '301', job: '409', jobLabel: 'Alpha Welder' },
		maps,
		branchOptions: '<option value="101" selected>Alpha HQ</option>',
	}));

	await expect(browserPage.locator('#job_title_id')).toHaveValue('409');
	expect(await options(browserPage, '#job_title_id')).toEqual([['0', '—'], ['401', 'Alpha Fitter'], ['409', 'Alpha Welder']]);
	expect(await posted(browserPage)).toMatchObject({ department_id: '301', job_title_id: '409' });
});
