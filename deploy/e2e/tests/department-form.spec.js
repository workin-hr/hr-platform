import { readFileSync } from 'node:fs';
import { test, expect } from '@playwright/test';

/**
 * department-form.js in a browser, with no stack behind it.
 *
 * <p>With no company chosen, the department add form's branch picker waits, and
 * choosing a company renders that company's branches as cards from the JSON the
 * page carries. The Java end-to-end tests read that markup; they cannot run the
 * script. So the copied legacy script is loaded from the source tree onto pages
 * shaped like departments.jte's add and edit forms, and what it does is observed
 * in Chromium.
 */

const SCRIPT = readFileSync(
	new URL('../../../backend/src/main/resources/static/admin/_assets/department-form.js', import.meta.url),
	'utf8');

const BRANCHES = {
	11: [{ id: 101, name: 'Alpha North' }, { id: 102, name: 'Alpha South' }],
	12: [{ id: 201, name: 'Beta Central' }],
};

const LABELS = `data-select-company-msg="Select a company first"
       data-label-select-all="Select all"
       data-label-clear-all="Clear"
       data-label-search="Search branches"
       data-label-selected-one="branch selected"
       data-label-selected-many="branches selected"`;

const TOOLBAR_HIDDEN = (hidden) => `<div class="dept-branches-toolbar" data-dept-toolbar${hidden ? ' hidden' : ''}>
          <input type="search" class="dept-branches-search" data-dept-search placeholder="Search branches" autocomplete="off">
          <div class="dept-branches-toolbar__actions">
            <button type="button" class="btn btn-outline btn-sm" data-dept-select-all>Select all</button>
            <button type="button" class="btn btn-outline btn-sm" data-dept-clear-all>Clear</button>
          </div>
        </div>`;

const CARD = (id, name, checked) => `<label class="dept-branch-card${checked ? ' is-selected' : ''}">
            <input type="checkbox" class="dept-branch-card__input" name="branch_ids[]" value="${id}"${checked ? ' checked' : ''}>
            <span class="dept-branch-card__box" aria-hidden="true"></span>
            <span class="dept-branch-card__name">${name}</span>
          </label>`;

// Shaped like departments.jte with no company filter: the company field inside the
// data-org-dept-form wrapper, and the branch picker waiting for it.
const ADD_PAGE = `<!doctype html>
<form method="POST">
  <div class="org-form-grid" data-org-dept-form
       data-branches='${JSON.stringify(BRANCHES)}'
       data-selected-branches="[]"
       ${LABELS}>
    <div class="form-row org-form-span-2">
      <label for="dp_company_id">Company</label>
      <select name="company_id" id="dp_company_id" data-dept-company="1" required>
        <option value="">Company...</option>
        <option value="11">Alpha Co</option>
        <option value="12">Beta Co</option>
      </select>
    </div>
    <div class="form-row org-form-span-2 dept-branches-field">
      <div class="dept-branches-field__label-row">
        <span class="form-label-static">Branches</span>
        <span class="dept-branches-badge" data-dept-count-badge hidden>0</span>
      </div>
      <div class="dept-branches-panel is-disabled" data-dept-branches-panel>
        ${TOOLBAR_HIDDEN(true)}
        <div class="dept-branches-picker" data-dept-branches-picker data-disabled="1">
          <div class="dept-branches-picker__empty">
            <span class="dept-branches-picker__empty-icon" aria-hidden="true">◎</span>
            <p>Select a company first</p>
          </div>
        </div>
      </div>
    </div>
  </div>
</form>`;

// Shaped like departments.jte's edit: the company travels hidden, there is no
// data-fixed-company, and the server rendered the row's company's active branches
// plus a retired one the department is linked to.
const EDIT_PAGE = `<!doctype html>
<form method="POST">
  <input type="hidden" name="company_id" value="11">
  <div class="org-form-grid" data-org-dept-form
       data-branches="{}"
       data-selected-branches="[102,103]"
       ${LABELS}>
    <div class="form-row org-form-span-2 dept-branches-field">
      <div class="dept-branches-field__label-row">
        <span class="form-label-static">Branches</span>
        <span class="dept-branches-badge" data-dept-count-badge>2 branches selected</span>
      </div>
      <div class="dept-branches-panel" data-dept-branches-panel>
        ${TOOLBAR_HIDDEN(false)}
        <div class="dept-branches-picker" data-dept-branches-picker>
          <div class="dept-branches-grid" data-dept-branches-grid>
          ${CARD(101, 'Alpha North', false)}
          ${CARD(102, 'Alpha South', true)}
          ${CARD(103, 'Alpha Retired', true)}
          </div>
        </div>
      </div>
    </div>
  </div>
</form>`;

async function load(page, html) {
	await page.setContent(html);
	await page.addScriptTag({ content: SCRIPT });
}

async function cards(page) {
	return page.locator('.dept-branch-card').evaluateAll((all) => all.map((card) => [
		card.querySelector('input').name,
		card.querySelector('input').value,
		card.querySelector('.dept-branch-card__name').textContent.trim(),
		card.querySelector('input').checked,
	]));
}

async function posted(page) {
	return page.locator('form').evaluate((form) => new FormData(form).getAll('branch_ids[]'));
}

const card = (page, name) => page.locator('.dept-branch-card', { hasText: name });

test.describe('the add form with no company chosen', () => {
	test.beforeEach(async ({ page }) => {
		await load(page, ADD_PAGE);
	});

	test('the picker waits for a company and offers no branch', async ({ page }) => {
		await expect(page.locator('[data-dept-branches-picker]')).toHaveAttribute('data-disabled', '1');
		await expect(page.locator('[data-dept-toolbar]')).toBeHidden();
		await expect(page.locator('[data-dept-branches-picker]')).toContainText('Select a company first');
		expect(await cards(page)).toEqual([]);
	});

	test("choosing a company renders that company's branches and no other's", async ({ page }) => {
		await page.selectOption('#dp_company_id', '11');

		await expect(page.locator('[data-dept-branches-panel]')).not.toHaveClass(/is-disabled/);
		await expect(page.locator('[data-dept-branches-picker]')).not.toHaveAttribute('data-disabled', '1');
		await expect(page.locator('[data-dept-toolbar]')).toBeVisible();
		expect(await cards(page)).toEqual([
			['branch_ids[]', '101', 'Alpha North', false],
			['branch_ids[]', '102', 'Alpha South', false],
		]);
		await expect(page.locator('[data-dept-count-badge]')).toBeHidden();
	});

	test('select all, a card click and clear all move the count badge', async ({ page }) => {
		await page.selectOption('#dp_company_id', '11');

		await page.click('[data-dept-select-all]');
		await expect(page.locator('[data-dept-count-badge]')).toHaveText('2 branches selected');

		await card(page, 'Alpha North').click();
		await expect(page.locator('[data-dept-count-badge]')).toHaveText('1 branch selected');
		expect(await posted(page)).toEqual(['102']);

		await page.click('[data-dept-clear-all]');
		await expect(page.locator('[data-dept-count-badge]')).toBeHidden();
		expect(await posted(page)).toEqual([]);
	});

	test('the search hides branches that do not match, and select all leaves those alone', async ({ page }) => {
		await page.selectOption('#dp_company_id', '11');

		await page.fill('[data-dept-search]', 'south');
		await expect(card(page, 'Alpha North')).toHaveClass(/is-hidden/);
		await expect(card(page, 'Alpha South')).not.toHaveClass(/is-hidden/);

		await page.click('[data-dept-select-all]');
		expect(await posted(page)).toEqual(['102']);
	});

	test('switching company leaves no branch of the company left behind', async ({ page }) => {
		await page.selectOption('#dp_company_id', '11');
		await page.click('[data-dept-select-all]');

		await page.selectOption('#dp_company_id', '12');

		expect(await cards(page)).toEqual([['branch_ids[]', '201', 'Beta Central', false]]);
		expect(await posted(page)).toEqual([]);
	});

	test('clearing the company makes the picker wait again, and the form posts no branch', async ({ page }) => {
		await page.selectOption('#dp_company_id', '11');
		await page.click('[data-dept-select-all]');

		await page.selectOption('#dp_company_id', '');

		await expect(page.locator('[data-dept-branches-picker]')).toHaveAttribute('data-disabled', '1');
		await expect(page.locator('[data-dept-toolbar]')).toBeHidden();
		expect(await posted(page)).toEqual([]);
	});
});

test('an edit keeps the cards the server rendered, a retired branch the department has included', async ({ page }) => {
	await load(page, EDIT_PAGE);

	expect(await cards(page)).toEqual([
		['branch_ids[]', '101', 'Alpha North', false],
		['branch_ids[]', '102', 'Alpha South', true],
		['branch_ids[]', '103', 'Alpha Retired', true],
	]);
	await expect(page.locator('[data-dept-count-badge]')).toHaveText('2 branches selected');

	await card(page, 'Alpha North').click();

	await expect(page.locator('[data-dept-count-badge]')).toHaveText('3 branches selected');
	expect(await posted(page)).toEqual(['101', '102', '103']);
});
