import { readFileSync } from 'node:fs';
import { test, expect } from '@playwright/test';

/**
 * job-title-form.js in a browser, with no stack behind it.
 *
 * <p>With no company chosen, the job title add form's department select waits,
 * and choosing a company lists that company's departments from the JSON the page
 * renders. The Java end-to-end tests read that markup; they cannot run the
 * script. So the copied legacy script is loaded from the source tree onto a page
 * shaped like job-titles.jte's add form, and what it does is observed in Chromium.
 */

const SCRIPT = readFileSync(
	new URL('../../../backend/src/main/resources/static/admin/_assets/job-title-form.js', import.meta.url),
	'utf8');

const DEPARTMENTS = {
	11: [{ id: 101, name: 'Alpha Dept' }, { id: 102, name: 'Alpha Ops' }],
	12: [{ id: 201, name: 'Beta Dept' }],
};

// Shaped like job-titles.jte with no company filter: the company field inside the
// data-org-jt-form wrapper, and the department select waiting for it.
const PAGE = `<!doctype html>
<form method="POST">
  <div class="org-form-grid" data-org-jt-form
       data-departments='${JSON.stringify(DEPARTMENTS)}'
       data-selected-department=""
       data-select-company-msg="Select a company first">
    <div class="form-row org-form-span-2">
      <label for="jt_add_company">Company</label>
      <select name="company_id" id="jt_add_company" data-jt-company="1" required>
        <option value="">Company...</option>
        <option value="11">Alpha Co</option>
        <option value="12">Beta Co</option>
      </select>
    </div>
    <div class="form-row org-form-span-2">
      <label for="department_id">Department</label>
      <select id="department_id" name="department_id" data-jt-department disabled>
        <option value="0">Select a company first</option>
      </select>
    </div>
  </div>
</form>`;

test.beforeEach(async ({ page }) => {
	await page.setContent(PAGE);
	await page.addScriptTag({ content: SCRIPT });
});

async function options(page) {
	return page.locator('#department_id option')
		.evaluateAll((all) => all.map((option) => [option.value, option.textContent.trim()]));
}

test('with no company chosen, the department select waits for one', async ({ page }) => {
	await expect(page.locator('#department_id')).toBeDisabled();
	expect(await options(page)).toEqual([['0', 'Select a company first']]);
});

test("choosing a company lists that company's departments and no other's", async ({ page }) => {
	await page.selectOption('#jt_add_company', '11');

	await expect(page.locator('#department_id')).toBeEnabled();
	expect(await options(page)).toEqual([['0', '—'], ['101', 'Alpha Dept'], ['102', 'Alpha Ops']]);
});

test('switching company drops a department of the company left behind', async ({ page }) => {
	await page.selectOption('#jt_add_company', '11');
	await page.selectOption('#department_id', '102');

	await page.selectOption('#jt_add_company', '12');

	expect(await options(page)).toEqual([['0', '—'], ['201', 'Beta Dept']]);
	await expect(page.locator('#department_id')).toHaveValue('0');
});

test('clearing the company makes the select wait again, and the form posts no department', async ({ page }) => {
	await page.selectOption('#jt_add_company', '11');
	await page.selectOption('#department_id', '101');

	await page.selectOption('#jt_add_company', '');

	await expect(page.locator('#department_id')).toBeDisabled();
	const posted = await page.evaluate(() => new FormData(document.querySelector('form')).get('department_id'));
	expect(posted, 'a disabled select is not submitted, which the controller reads as none').toBeNull();
});
