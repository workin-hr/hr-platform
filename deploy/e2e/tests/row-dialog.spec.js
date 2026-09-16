import { readFileSync } from 'node:fs';
import { test, expect } from '@playwright/test';

/**
 * row-dialog.js in a browser, with no stack behind it.
 *
 * <p>The script fills a row action's window from the row's `data-dialog-*`
 * attributes and opens it. The window is legacy's `.modal-bg > .modal`, which
 * crud.js closes on its close button and backdrop, and modal-a11y.js gives
 * Escape, the Tab trap and focus. The Java end-to-end tests read the rendered
 * pages; they cannot run the scripts. So the three scripts and style.css are
 * loaded from the source tree, in the layout's order, onto a page shaped like
 * rowDialog.jte, and what they do is observed in Chromium.
 */

const asset = (name) => readFileSync(
	new URL(`../../../backend/src/main/resources/static/admin/_assets/${name}`, import.meta.url),
	'utf8');

const WINDOW = (id, body) => `<div class="modal-bg" id="${id}" aria-hidden="true">
  <div class="modal" role="dialog" aria-modal="true" aria-labelledby="${id}-title">
    <button type="button" class="modal-close" aria-label="close">&times;</button>
    <h2 id="${id}-title">edit</h2>
    <p class="row-dialog__subject" data-dialog-field="subject"></p>
    <form method="POST">
      <input type="hidden" name="action" value="edit">
      <input type="hidden" name="id" data-dialog-field="id">
      ${body}
      <div class="form-footer">
        <button type="submit">save</button>
      </div>
    </form>
  </div>
</div>`;

// Shaped like rowDialog.jte and the faqs edit window: one window, one trigger
// per row, and the checkbox with no `checked` of its own.
const PAGE = `<!doctype html>
<button type="button" data-dialog="edit" data-dialog-id="1" data-dialog-subject="Open"
        data-dialog-nameEn="Open" data-dialog-isActive="1">edit 1</button>
<button type="button" data-dialog="edit" data-dialog-id="2" data-dialog-subject="Closed"
        data-dialog-nameEn="Closed" data-dialog-isActive="0">edit 2</button>
<button type="button" data-dialog="edit" data-dialog-id="3" data-dialog-subject="Unstated"
        data-dialog-nameEn="Unstated">edit 3</button>
${WINDOW('edit', `<div class="form-row">
        <input type="text" name="nameEn" data-dialog-field="nameEn">
        <input type="checkbox" name="isActive" value="1" data-dialog-field="isActive">
      </div>`)}`;

async function load(page, content) {
	await page.setContent(content);
	await page.addStyleTag({ content: asset('style.css') });
	for (const script of ['crud.js', 'row-dialog.js', 'modal-a11y.js']) {
		await page.addScriptTag({ content: asset(script) });
	}
}

async function open(page, id) {
	await page.locator(`[data-dialog-id="${id}"]`).click();
	const modal = page.locator('#edit');
	await expect(modal, 'the window opens').toBeVisible();
	return modal;
}

async function close(page) {
	await page.locator('#edit .modal-close').click();
	await expect(page.locator('#edit')).toBeHidden();
}

test.beforeEach(async ({ page }) => {
	await load(page, PAGE);
});

test('the window is hidden until a row opens it', async ({ page }) => {
	await expect(page.locator('#edit')).toBeHidden();
	const modal = await open(page, 1);
	await expect(modal).toHaveClass(/\bopen\b/);
	await expect(modal.locator('.row-dialog__subject'), 'it names the row').toHaveText('Open');
});

test('a checkbox is ticked from the row that opened the window', async ({ page }) => {
	let modal = await open(page, 1);
	await expect(modal.locator('[name="nameEn"]'), 'the row filled the window').toHaveValue('Open');
	await expect(modal.locator('[name="isActive"]'), 'an active row opens ticked').toBeChecked();
	await close(page);

	modal = await open(page, 2);
	await expect(modal.locator('[name="isActive"]'), 'an inactive row opens unticked').not.toBeChecked();
});

test('a checkbox keeps the value it submits when ticked', async ({ page }) => {
	// A browser submits a ticked box as its value attribute. Writing the row's
	// "0" or "1" into that value is what the script used to do instead.
	const modal = await open(page, 2);
	await expect(modal.locator('[name="isActive"]')).toHaveValue('1');
	await close(page);
	await open(page, 1);
	await expect(modal.locator('[name="isActive"]')).toHaveValue('1');
});

test('a row with no state leaves the checkbox as the form renders it', async ({ page }) => {
	// The reset runs first, so the previous row's tick does not carry over.
	await open(page, 1);
	await close(page);
	const modal = await open(page, 3);
	await expect(modal.locator('[name="nameEn"]')).toHaveValue('Unstated');
	await expect(modal.locator('[name="isActive"]')).not.toBeChecked();
});

test('a filled window says so before it opens', async ({ page }) => {
	// emp-picker.js redraws its label from what the fill wrote; it can only do
	// that if it hears about the fill after it and before the window shows.
	await page.evaluate(() => {
		window.filled = [];
		document.addEventListener('row-dialog:filled', (event) => {
			window.filled.push({
				window: event.target.id,
				name: event.target.querySelector('[name="nameEn"]').value,
				open: event.target.classList.contains('open'),
			});
		});
	});
	await open(page, 2);
	expect(await page.evaluate(() => window.filled)).toEqual([{ window: 'edit', name: 'Closed', open: false }]);
});

test('Enter in a field submits through Save, and the close button closes without submitting', async ({ page }) => {
	// Enter submits through the form's first submit button, and the close button
	// is type="button" outside the form, so Enter never dismisses what was typed.
	await page.evaluate(() => {
		window.submitted = [];
		document.querySelector('#edit form').addEventListener('submit', (event) => {
			window.submitted.push(event.submitter ? event.submitter.textContent : null);
			event.preventDefault();
		});
	});
	const modal = await open(page, 1);
	await modal.locator('[name="nameEn"]').press('Enter');
	expect(await page.evaluate(() => window.submitted), 'Enter submitted through Save').toEqual(['save']);
	await expect(modal, 'and the window is still there to show the result').toBeVisible();

	await close(page);
	expect(await page.evaluate(() => window.submitted), 'closing submitted nothing').toEqual(['save']);
});

test('Escape and the backdrop close the window, and a click inside it does not', async ({ page }) => {
	let modal = await open(page, 1);
	await page.keyboard.press('Escape');
	await expect(modal, 'Escape closes it').toBeHidden();

	modal = await open(page, 1);
	await modal.locator('h2').click();
	await expect(modal, 'a click inside the window leaves it open').toBeVisible();
	await page.mouse.click(5, 5);
	await expect(modal, 'a click on the backdrop closes it').toBeHidden();
});

test('opening focuses the first field, and closing returns focus to the row', async ({ page }) => {
	const trigger = page.locator('[data-dialog-id="2"]');
	await trigger.focus();
	await page.keyboard.press('Enter');
	await expect(page.locator('#edit')).toBeVisible();
	await expect(page.locator('#edit [name="nameEn"]'), 'the first field, not the close button').toBeFocused();
	await page.keyboard.press('Escape');
	await expect(trigger, 'back to the row that opened it').toBeFocused();
});

test('a current-only option is disabled for any row that does not already carry it', async ({ page }) => {
	// A retired exception type appears in the shared attendance edit window for every
	// row on the page. It stays available only to the row that already has it.
	await load(page, `<!doctype html>
<button type="button" data-dialog="typed" data-dialog-id="1" data-dialog-type="7">edit 1</button>
<button type="button" data-dialog="typed" data-dialog-id="2" data-dialog-type="0">edit 2</button>
${WINDOW('typed', `<select name="type" data-dialog-field="type">
        <option value="0">—</option>
        <option value="5">Active</option>
        <option value="7" data-dialog-current-only="1">Retired — Inactive</option>
      </select>`)}`);
	const select = page.locator('#typed [name="type"]');
	const retired = page.locator('#typed option[value="7"]');
	const closeTyped = async () => {
		await page.locator('#typed .modal-close').click();
		await expect(page.locator('#typed')).toBeHidden();
	};

	await page.locator('[data-dialog-id="1"]').click();
	await expect(select, 'the row that has the retired type opens with it').toHaveValue('7');
	await expect(retired, 'and may keep it').toBeEnabled();
	await closeTyped();

	await page.locator('[data-dialog-id="2"]').click();
	await expect(select, 'another row opens with its own value').toHaveValue('0');
	await expect(retired, 'and cannot choose the retired type').toBeDisabled();
	await closeTyped();

	await page.locator('[data-dialog-id="1"]').click();
	await expect(retired, 'reopening the row that has it enables it again').toBeEnabled();
});
