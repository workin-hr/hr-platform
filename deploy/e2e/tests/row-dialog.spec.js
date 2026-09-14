import { readFileSync } from 'node:fs';
import { test, expect } from '@playwright/test';

/**
 * row-dialog.js in a browser, with no stack behind it.
 *
 * <p>The script fills a row action's dialog from the row's `data-dialog-*`
 * attributes. The Java end-to-end tests read the rendered pages and fill the
 * dialog by the rules this file holds the real script to; they cannot run the
 * script. So the script is loaded from the source tree onto a page shaped like
 * rowDialog.jte, and what it does is observed in Chromium.
 */

const SCRIPT = readFileSync(
	new URL('../../../backend/src/main/resources/static/admin/_assets/row-dialog.js', import.meta.url),
	'utf8');

// Shaped like rowDialog.jte and the faqs edit dialog: one dialog, one trigger
// per row, and the checkbox with no `checked` of its own.
const PAGE = `<!doctype html>
<button type="button" data-dialog="edit" data-dialog-id="1" data-dialog-subject="Open"
        data-dialog-nameEn="Open" data-dialog-isActive="1">edit 1</button>
<button type="button" data-dialog="edit" data-dialog-id="2" data-dialog-subject="Closed"
        data-dialog-nameEn="Closed" data-dialog-isActive="0">edit 2</button>
<button type="button" data-dialog="edit" data-dialog-id="3" data-dialog-subject="Unstated"
        data-dialog-nameEn="Unstated">edit 3</button>
<dialog class="row-dialog" id="edit">
  <form method="POST" class="row-dialog__form">
    <input type="hidden" name="id" data-dialog-field="id">
    <p class="row-dialog__subject" data-dialog-field="subject"></p>
    <div class="row-dialog__body">
      <input type="text" name="nameEn" data-dialog-field="nameEn">
      <input type="checkbox" name="isActive" value="1" data-dialog-field="isActive">
    </div>
    <button type="button" data-dialog-close>cancel</button>
    <button type="submit">save</button>
  </form>
</dialog>`;

async function open(page, id) {
	await page.locator(`[data-dialog-id="${id}"]`).click();
	const dialog = page.locator('#edit');
	await expect(dialog, 'the dialog opens').toBeVisible();
	return dialog;
}

async function cancel(page) {
	await page.locator('#edit [data-dialog-close]').click();
	await expect(page.locator('#edit')).toBeHidden();
}

test.beforeEach(async ({ page }) => {
	await page.setContent(PAGE);
	await page.addScriptTag({ content: SCRIPT });
});

test('a checkbox is ticked from the row that opened the dialog', async ({ page }) => {
	let dialog = await open(page, 1);
	await expect(dialog.locator('[name="nameEn"]'), 'the row filled the dialog').toHaveValue('Open');
	await expect(dialog.locator('[name="isActive"]'), 'an active row opens ticked').toBeChecked();
	await cancel(page);

	dialog = await open(page, 2);
	await expect(dialog.locator('[name="isActive"]'), 'an inactive row opens unticked').not.toBeChecked();
});

test('a checkbox keeps the value it submits when ticked', async ({ page }) => {
	// A browser submits a ticked box as its value attribute. Writing the row's
	// "0" or "1" into that value is what the script used to do instead.
	const dialog = await open(page, 2);
	await expect(dialog.locator('[name="isActive"]')).toHaveValue('1');
	await cancel(page);
	await open(page, 1);
	await expect(dialog.locator('[name="isActive"]')).toHaveValue('1');
});

test('a row with no state leaves the checkbox as the form renders it', async ({ page }) => {
	// The reset runs first, so the previous row's tick does not carry over.
	await open(page, 1);
	await cancel(page);
	const dialog = await open(page, 3);
	await expect(dialog.locator('[name="nameEn"]')).toHaveValue('Unstated');
	await expect(dialog.locator('[name="isActive"]')).not.toBeChecked();
});

test('a filled dialog says so before it opens', async ({ page }) => {
	// emp-picker.js redraws its label from what the fill wrote; it can only do
	// that if it hears about the fill after it and before the dialog shows.
	await page.evaluate(() => {
		window.filled = [];
		document.addEventListener('row-dialog:filled', (event) => {
			window.filled.push({
				dialog: event.target.id,
				name: event.target.querySelector('[name="nameEn"]').value,
				open: event.target.open,
			});
		});
	});
	await open(page, 2);
	expect(await page.evaluate(() => window.filled)).toEqual([{ dialog: 'edit', name: 'Closed', open: false }]);
});

test('Enter in a field submits the dialog through Save, and Cancel closes without submitting', async ({ page }) => {
	// Enter submits through the form's first submit button. While Cancel was a
	// formmethod="dialog" submit ahead of Save, Enter closed the dialog instead.
	await page.evaluate(() => {
		window.submitted = [];
		document.querySelector('#edit form').addEventListener('submit', (event) => {
			window.submitted.push(event.submitter ? event.submitter.textContent : null);
			event.preventDefault();
		});
	});
	const dialog = await open(page, 1);
	await dialog.locator('[name="nameEn"]').press('Enter');
	expect(await page.evaluate(() => window.submitted), 'Enter submitted through Save').toEqual(['save']);
	await expect(dialog, 'and the dialog is still there to show the result').toBeVisible();

	await cancel(page);
	expect(await page.evaluate(() => window.submitted), 'Cancel submitted nothing').toEqual(['save']);
});
