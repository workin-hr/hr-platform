import { readFileSync } from 'node:fs';
import { test, expect } from '@playwright/test';

/**
 * attendance-form.js in a browser, with no stack behind it, beside crud.js and
 * emp-picker.js: the two behaviours attendance.jte needs beyond what those two
 * shared scripts already give every other picker-driven add form
 * (attendanceOpenAdd, dashboard/pages/attendance/assets/attendance-form.js) --
 * the add modal's check-in defaults to now when it opens, and its save stays
 * disabled until an employee is chosen and a check-in is set.
 *
 * <p>The Java end-to-end tests read the rendered page's markup (the labels,
 * the "disabled" attribute at load, novalidate). They cannot run the script,
 * so it is loaded from the source tree onto a page shaped like attendance.jte's
 * toolbar button and add modal, and what it does is observed in Chromium.
 */

const asset = (name) => readFileSync(
	new URL(`../../../backend/src/main/resources/static/admin/_assets/${name}`, import.meta.url),
	'utf8');

const EMPLOYEES = [
	{ id: 7, label: 'Aya Alpha (A100)', code: 'A100', name: 'Aya Alpha' },
	{ id: 8, label: 'Basma Beta (B100)', code: 'B100', name: 'Basma Beta' },
];
const LIST = JSON.stringify(EMPLOYEES).replaceAll('&', '&amp;').replaceAll('"', '&quot;');

const PAGE = `<!doctype html>
<style>.emp-picker__results { display: none; } .emp-picker__results.is-open { display: block; }
.modal-bg { display: none; } .modal-bg.open { display: block; }</style>
<button type="button" id="opener" onclick="crudOpenAdd('attModal')">+ add</button>
<div hidden id="employee-picker-list" data-employees="${LIST}"></div>
<div class="modal-bg" id="attModal">
	<div class="modal" role="dialog" aria-modal="true">
		<button type="button" class="modal-close">&times;</button>
		<h2 id="attModal-title">add</h2>
		<form method="POST" novalidate>
			<input type="hidden" name="action" value="add_attendance" data-add="add_attendance">
			<div class="form-row" data-emp-picker data-emp-source="employee-picker-list">
				<input type="search" id="employee_id" class="emp-picker__search" data-emp-search autocomplete="off">
				<input type="hidden" name="employee_id" value="" data-emp-id>
				<p class="emp-picker__selected" data-emp-selected hidden></p>
				<ul class="emp-picker__results" data-emp-results data-empty-label="No data found"></ul>
			</div>
			<div class="form-row">
				<input type="datetime-local" id="check_in" name="check_in" required>
			</div>
			<div class="form-row">
				<input type="datetime-local" id="check_out" name="check_out">
			</div>
			<div class="form-footer">
				<button type="submit" id="save" data-att-submit disabled>save</button>
			</div>
		</form>
	</div>
</div>`;

test.beforeEach(async ({ page }) => {
	await page.setContent(PAGE);
	// The layout's order: crud.js, then the page's own scripts (emp-picker.js, attendance-form.js).
	await page.addScriptTag({ content: asset('crud.js') });
	await page.addScriptTag({ content: asset('emp-picker.js') });
	await page.addScriptTag({ content: asset('attendance-form.js') });
});

const picker = (page) => page.locator('[data-emp-picker]');
const save = (page) => page.locator('#save');

function pickEmployee(page, search, name) {
	return page.locator('#employee_id').fill(search)
		.then(() => picker(page).getByRole('button', { name }).click());
}

test('save starts disabled, as the template renders it', async ({ page }) => {
	// Before opening the modal: the static "disabled" attribute attendance.jte
	// renders, matching legacy's data-att-submit disabled.
	await expect(save(page)).toBeDisabled();
});

test('opening the add modal sets check-in to now', async ({ page }) => {
	const before = Date.now();
	await page.locator('#opener').click();
	const value = await page.locator('#check_in').inputValue();
	expect(value).not.toBe('');
	// datetime-local's value has no timezone; the page reads the browser's
	// local wall clock the same way defaultDateTimeLocal() writes it.
	const asOfPage = new Date(value.replace('T', ' ') + ' UTC').getTime()
		+ new Date().getTimezoneOffset() * 60000;
	expect(Math.abs(asOfPage - before)).toBeLessThan(60000);
});

test('save is disabled until an employee and a check-in are both set', async ({ page }) => {
	await page.locator('#opener').click();
	// check-in is already set by the open; only the employee is missing.
	await expect(save(page)).toBeDisabled();

	await pickEmployee(page, 'Aya', 'Aya Alpha (A100)');
	await expect(save(page)).toBeEnabled();

	await page.locator('#check_in').fill('');
	await expect(save(page), 'an employee alone is not enough').toBeDisabled();

	await page.locator('#check_in').fill('2026-03-02T09:00');
	await expect(save(page)).toBeEnabled();

	// Typing over the choice clears employee_id (emp-picker.js); the gate reads that.
	await page.locator('#employee_id').fill('Aya Al');
	await expect(save(page), 'the choice was edited away').toBeDisabled();
});

test('reopening resets the picker and sets a fresh check-in', async ({ page }) => {
	await page.locator('#opener').click();
	await pickEmployee(page, 'Basma', 'Basma Beta (B100)');
	await expect(save(page)).toBeEnabled();
	const firstCheckIn = await page.locator('#check_in').inputValue();

	await page.waitForTimeout(1100);
	await page.locator('#opener').click();
	await expect(picker(page).locator('[data-emp-id]'), 'the picker was reset').toHaveValue('');
	await expect(save(page), 'no employee again: back to disabled').toBeDisabled();
	const secondCheckIn = await page.locator('#check_in').inputValue();
	expect(secondCheckIn >= firstCheckIn).toBe(true);
});
