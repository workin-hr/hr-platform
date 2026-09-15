import { readFileSync } from 'node:fs';
import { test, expect } from '@playwright/test';

/**
 * emp-picker.js in a browser, with no stack behind it, beside row-dialog.js.
 *
 * <p>The Java end-to-end tests read the rendered pages: the picker's markup, the
 * row's employee and label on its Edit trigger, and the page's list. They cannot
 * run the scripts, so both are loaded from the source tree onto a page shaped
 * like employeePicker.jte on advances.jte, and what they do is observed in
 * Chromium.
 */

const asset = (name) => readFileSync(
	new URL(`../../../backend/src/main/resources/static/admin/_assets/${name}`, import.meta.url),
	'utf8');

const EMPLOYEES = Array.from({ length: 45 }, (_, i) => ({
	id: 100 + i, label: `Employee ${i} (E${i})`, code: `E${i}`, name: `Employee ${i}`,
}));
EMPLOYEES.push({ id: 7, label: 'Aya Alpha (A100) — Alpha Co', code: 'A100', name: 'Aya Alpha' });

// As jte writes an attribute value: quotes and ampersands escaped.
const LIST = JSON.stringify(EMPLOYEES).replaceAll('&', '&amp;').replaceAll('"', '&quot;');

function picker(inputId, fromRow) {
	const field = (key) => (fromRow ? ` data-dialog-field="${key}"` : '');
	return `<div class="emp-picker" data-emp-picker data-emp-source="employee-picker-list">
	<input type="search" id="${inputId}" class="emp-picker__search" data-emp-search autocomplete="off">
	<input type="hidden" name="employee_id" value="" data-emp-id${field('employee_id')}>
	<input type="hidden" value="" data-emp-fallback-label${field('employee_label')}>
	<p class="emp-picker__selected" data-emp-selected hidden></p>
	<ul class="emp-picker__results" data-emp-results data-empty-label="No data found"></ul>
</div>`;
}

// Employee 900 is in no list: the row's employee has been deactivated since.
const PAGE = `<!doctype html>
<style>.emp-picker__results { display: none; } .emp-picker__results.is-open { display: block; }</style>
<div hidden id="employee-picker-list" data-employees="${LIST}"></div>
<form id="add" method="POST">
	${picker('employee_id', false)}
	<button type="submit" id="add-save">save</button>
</form>
<button type="button" data-dialog="advance-edit" data-dialog-id="1" data-dialog-subject="Aya Alpha"
        data-dialog-employee_id="7" data-dialog-employee_label="Aya Alpha (A100)">edit listed</button>
<button type="button" data-dialog="advance-edit" data-dialog-id="2" data-dialog-subject="Gone Away"
        data-dialog-employee_id="900" data-dialog-employee_label="Gone Away (G1)">edit deactivated</button>
<dialog class="row-dialog" id="advance-edit">
	<form method="POST" class="row-dialog__form">
		<input type="hidden" name="id" data-dialog-field="id">
		<p class="row-dialog__subject" data-dialog-field="subject"></p>
		<div class="row-dialog__body">${picker('advance-edit-employee', true)}</div>
		<button type="button" data-dialog-close id="cancel">cancel</button>
		<button type="submit" id="save">save</button>
	</form>
</dialog>`;

test.beforeEach(async ({ page }) => {
	await page.setContent(PAGE);
	await page.addScriptTag({ content: asset('row-dialog.js') });
	await page.addScriptTag({ content: asset('emp-picker.js') });
	// Registered after the picker's own listener, so it sees the picker's verdict.
	// It stops every real POST; no dialog button submits in order to close.
	await page.evaluate(() => {
		window.submits = [];
		document.querySelectorAll('form').forEach((form) => form.addEventListener('submit', (event) => {
			window.submits.push({
				prevented: event.defaultPrevented,
				employee: new FormData(form).get('employee_id'),
			});
			event.preventDefault();
		}));
	});
});

const addPicker = (page) => page.locator('#add [data-emp-picker]');
const editPicker = (page) => page.locator('#advance-edit [data-emp-picker]');
const lastSubmit = (page) => page.evaluate(() => window.submits.at(-1));

async function openEdit(page, name) {
	await page.getByRole('button', { name }).click();
	await expect(page.locator('#advance-edit'), 'the dialog opens').toBeVisible();
}

test('typing filters by name or code, forty at most', async ({ page }) => {
	const results = addPicker(page).locator('[data-emp-results]');
	await page.locator('#employee_id').focus();
	await expect(results, 'focus lists before anything is typed').toHaveClass(/is-open/);
	await expect(results.locator('li'), 'forty, not all forty-six').toHaveCount(40);

	await page.locator('#employee_id').fill('A100');
	await expect(results.locator('li')).toHaveText(['Aya Alpha (A100) — Alpha Co']);

	await page.locator('#employee_id').fill('employee 1');
	await expect(results.locator('li'), 'Employee 1 and 10 to 19').toHaveCount(11);

	await page.locator('#employee_id').fill('nobody');
	await expect(results.locator('li.emp-picker__empty')).toHaveText('No data found');
});

test('choosing sets the id and shows the label; typing again clears the id', async ({ page }) => {
	const picker = addPicker(page);
	await page.locator('#employee_id').fill('A100');
	await picker.getByRole('button', { name: 'Aya Alpha (A100) — Alpha Co' }).click();
	await expect(picker.locator('[data-emp-id]')).toHaveValue('7');
	await expect(picker.locator('[data-emp-selected]')).toHaveText('Aya Alpha (A100) — Alpha Co');
	await expect(picker.locator('[data-emp-selected]')).toBeVisible();
	await expect(picker.locator('[data-emp-results]')).not.toHaveClass(/is-open/);

	await page.locator('#employee_id').fill('Aya Al');
	await expect(picker.locator('[data-emp-id]'), 'an edited search is no longer a choice').toHaveValue('');
	await expect(picker.locator('[data-emp-selected]')).toBeHidden();
});

test('a form with no employee chosen does not submit, and says why', async ({ page }) => {
	const picker = addPicker(page);
	await page.locator('#add-save').click();
	expect(await lastSubmit(page)).toMatchObject({ prevented: true });
	await expect(picker).toHaveClass(/is-invalid/);
	await expect(page.locator('#employee_id')).toBeFocused();

	await page.locator('#employee_id').fill('E3');
	await picker.getByRole('button', { name: 'Employee 3 (E3)' }).click();
	await expect(picker).not.toHaveClass(/is-invalid/);
	await page.locator('#add-save').click();
	expect(await lastSubmit(page)).toMatchObject({ prevented: false, employee: '103' });
});

test('an edit shows the row\'s employee as the list labels it, without listing others', async ({ page }) => {
	await openEdit(page, 'edit listed');
	const picker = editPicker(page);
	await expect(picker.locator('[data-emp-id]')).toHaveValue('7');
	await expect(picker.locator('[data-emp-selected]')).toHaveText('Aya Alpha (A100) — Alpha Co');
	await expect(page.locator('#advance-edit-employee'), 'the dialog focused the search box').toBeFocused();
	await expect(picker.locator('[data-emp-results]'), 'and did not open a list over the form').not.toHaveClass(/is-open/);
});

test('an edit keeps a row\'s employee the list no longer holds, and saves it', async ({ page }) => {
	await openEdit(page, 'edit deactivated');
	const picker = editPicker(page);
	await expect(picker.locator('[data-emp-id]')).toHaveValue('900');
	await expect(page.locator('#advance-edit-employee')).toHaveValue('Gone Away (G1)');
	await expect(picker.locator('[data-emp-selected]')).toHaveText('Gone Away (G1)');
	await expect(picker.locator('[data-emp-selected]')).toBeVisible();

	await page.locator('#save').click();
	expect(await lastSubmit(page), 'the form submits the row\'s employee')
		.toMatchObject({ prevented: false, employee: '900' });
});

test('cancel still closes, and the next row replaces the last one', async ({ page }) => {
	await openEdit(page, 'edit deactivated');
	await page.locator('#advance-edit-employee').fill('Emp');
	await page.locator('#cancel').click();
	await expect(page.locator('#advance-edit'), 'no employee chosen does not block cancel').toBeHidden();
	expect(await page.evaluate(() => window.submits.length), 'Cancel submits nothing').toBe(0);

	await openEdit(page, 'edit listed');
	const picker = editPicker(page);
	await expect(picker.locator('[data-emp-id]')).toHaveValue('7');
	await expect(picker.locator('[data-emp-selected]')).toHaveText('Aya Alpha (A100) — Alpha Co');
	await expect(picker.locator('[data-emp-results]')).not.toHaveClass(/is-open/);
});

test('resetting the add form clears the choice', async ({ page }) => {
	// crud.js resets the add form each time it opens.
	const picker = addPicker(page);
	await page.locator('#employee_id').fill('A100');
	await picker.getByRole('button', { name: 'Aya Alpha (A100) — Alpha Co' }).click();
	await page.evaluate(() => document.getElementById('add').reset());
	await expect(picker.locator('[data-emp-id]')).toHaveValue('');
	await expect(page.locator('#employee_id')).toHaveValue('');
	await expect(picker.locator('[data-emp-selected]')).toBeHidden();
});

test('Enter in the search box chooses a single match and never submits', async ({ page }) => {
	// Enter in the search box chooses; it must not submit the dialog's form.
	await openEdit(page, 'edit deactivated');
	const picker = editPicker(page);
	const search = page.locator('#advance-edit-employee');

	await search.fill('Employee 1');
	await search.press('Enter');
	await expect(page.locator('#advance-edit'), 'Enter did not close the dialog').toBeVisible();
	await expect(picker.locator('[data-emp-id]'), 'eleven matches: nothing chosen').toHaveValue('');

	await search.fill('A100');
	await search.press('Enter');
	await expect(picker.locator('[data-emp-id]'), 'one match: chosen').toHaveValue('7');
	await expect(page.locator('#advance-edit')).toBeVisible();
	expect(await page.evaluate(() => window.submits.length), 'nothing was submitted').toBe(0);
});
