import { readFileSync } from 'node:fs';
import { test, expect } from '@playwright/test';

/**
 * setting-templates.js in a browser, with no stack behind it.
 *
 * <p>The script is legacy's. It fills the setting templates tab's two dialogs
 * from the button that opened them. AdminSettingsEndToEndTest holds the
 * rendered tab to every id and attribute the script reads, but it cannot run
 * the script. So the script is loaded from the source tree onto a page shaped
 * like settings-templates.jte, and what it does is observed in Chromium.
 */

const SCRIPT = readFileSync(
	new URL('../../../backend/src/main/resources/static/admin/_assets/setting-templates.js', import.meta.url),
	'utf8');

// Shaped like settings-templates.jte: one definition, an unused option, an
// option in use, and the two dialogs, with the Arabic titles the tab renders.
const PAGE = `<!doctype html>
<button type="button" data-setting-definition-edit data-definition-id="7" data-setting-key="attendance_methods"
        data-label-ar="طرق الحضور" data-label-en="Attendance methods" data-description-en="How staff check in"
        data-sort-order="3">edit definition</button>
<button type="button" data-setting-option-add data-definition-id="7" data-definition-label="طرق الحضور">add option</button>
<button type="button" data-setting-option-edit data-option-id="11" data-definition-id="7" data-value="sat"
        data-label-en="Satellite" data-sort-order="2" data-definition-label="طرق الحضور" data-in-use="false">edit unused</button>
<button type="button" data-setting-option-edit data-option-id="12" data-definition-id="7" data-value="fingerprint"
        data-label-ar="بصمة" data-sort-order="1" data-definition-label="طرق الحضور" data-in-use="true">edit used</button>
<button type="button" data-setting-option-blocked="لا يمكن الحذف: القيمة مستخدمة من 1">delete used</button>
<div class="modal-bg" id="settingDefinitionModal"><div class="modal"><form>
  <input type="hidden" name="id" id="settingDefinitionId" value="">
  <input type="text" id="settingDefinitionKey" disabled>
  <input type="text" name="label_ar" id="settingDefinitionLabelAr">
  <input type="text" name="label_en" id="settingDefinitionLabelEn">
  <textarea name="description_ar" id="settingDefinitionDescAr"></textarea>
  <textarea name="description_en" id="settingDefinitionDescEn"></textarea>
  <input type="number" name="sort_order" id="settingDefinitionSort" value="0">
</form></div></div>
<div class="modal-bg" id="settingOptionModal" data-label-add="إضافة قيمة" data-label-edit="تعديل قيمة"><div class="modal">
  <h2 id="settingOptionModalTitle">x</h2><p id="settingOptionModalDef"></p><form>
  <input type="hidden" name="action" id="settingOptionAction" value="add_option">
  <input type="hidden" name="id" id="settingOptionId" value="">
  <input type="hidden" name="setting_definition_id" id="settingOptionDefId" value="">
  <input type="text" name="value" id="settingOptionValue">
  <small id="settingOptionValueHint">hint</small><small id="settingOptionValueLockedHint" hidden>locked</small>
  <input type="text" name="label_ar" id="settingOptionLabelAr">
  <input type="text" name="label_en" id="settingOptionLabelEn">
  <input type="number" name="sort_order" id="settingOptionSort" value="0">
</form></div></div>`;

let errors;

test.beforeEach(async ({ page }) => {
	errors = [];
	page.on('pageerror', (error) => errors.push(error.message));
	await page.setContent(PAGE);
	await page.addScriptTag({ content: SCRIPT });
});

test.afterEach(() => {
	// The defect this covers threw before either option dialog opened.
	expect(errors, 'the script threw').toEqual([]);
});

test('edit definition fills the dialog from its button', async ({ page }) => {
	await page.getByText('edit definition').click();
	await expect(page.locator('#settingDefinitionModal')).toHaveClass(/open/);
	await expect(page.locator('#settingDefinitionId')).toHaveValue('7');
	await expect(page.locator('#settingDefinitionKey')).toHaveValue('attendance_methods');
	await expect(page.locator('#settingDefinitionLabelAr')).toHaveValue('طرق الحضور');
	await expect(page.locator('#settingDefinitionDescEn')).toHaveValue('How staff check in');
	await expect(page.locator('#settingDefinitionSort')).toHaveValue('3');
});

test('add option opens for its definition under the page\'s own title', async ({ page }) => {
	await page.getByText('add option').click();
	await expect(page.locator('#settingOptionModal')).toHaveClass(/open/);
	await expect(page.locator('#settingOptionModalTitle')).toHaveText('إضافة قيمة');
	await expect(page.locator('#settingOptionDefId')).toHaveValue('7');
	await expect(page.locator('#settingOptionAction')).toHaveValue('add_option');
});

test('edit option fills the dialog, and an unused value stays editable', async ({ page }) => {
	await page.getByText('edit unused').click();
	await expect(page.locator('#settingOptionModalTitle')).toHaveText('تعديل قيمة');
	await expect(page.locator('#settingOptionAction')).toHaveValue('edit_option');
	await expect(page.locator('#settingOptionId')).toHaveValue('11');
	await expect(page.locator('#settingOptionDefId')).toHaveValue('7');
	await expect(page.locator('#settingOptionValue')).toHaveValue('sat');
	await expect(page.locator('#settingOptionLabelEn')).toHaveValue('Satellite');
	await expect(page.locator('#settingOptionSort')).toHaveValue('2');
	await expect(page.locator('#settingOptionValue')).not.toHaveAttribute('readonly', '');
	await expect(page.locator('#settingOptionValueLockedHint')).toBeHidden();
});

test('an option in use has its value locked', async ({ page }) => {
	await page.getByText('edit used').click();
	await expect(page.locator('#settingOptionValue')).toHaveValue('fingerprint');
	await expect(page.locator('#settingOptionValue')).toHaveAttribute('readonly', '');
	await expect(page.locator('#settingOptionValueLockedHint')).toBeVisible();
	await expect(page.locator('#settingOptionValueHint')).toBeHidden();
});

test('delete on an option in use explains the refusal', async ({ page }) => {
	const shown = [];
	page.on('dialog', async (dialog) => {
		shown.push(dialog.message());
		await dialog.dismiss();
	});
	await page.getByText('delete used').click();
	await expect.poll(() => shown).toEqual(['لا يمكن الحذف: القيمة مستخدمة من 1']);
});

test('delete on an option in use closes the row menu it came from', async ({ page }) => {
	// The handler stops the click from reaching row-actions.js, so it has to ask
	// that script to close the menu, as the add and edit branches do.
	await page.evaluate(() => {
		window.rowActionsClosed = 0;
		document.addEventListener('row-actions:close', () => { window.rowActionsClosed += 1; });
	});
	page.on('dialog', (dialog) => dialog.dismiss());
	await page.getByText('delete used').click();
	await expect.poll(() => page.evaluate(() => window.rowActionsClosed)).toBe(1);
});
