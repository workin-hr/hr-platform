import { readFileSync } from 'node:fs';
import { test, expect } from '@playwright/test';

/**
 * The shared interaction tools (D-288) in a browser, with no stack behind them:
 * ui-confirm.js, ui-select-search.js, ui-copy.js and ui-pickers.js over the
 * vendored flatpickr.
 *
 * <p>The page is served from a routed https origin rather than set as content,
 * so a form really posts -- each POST is captured -- and the clipboard API runs
 * in the secure context it requires. The markup keeps the layout's nesting
 * (.shell > main.main > .content.hr-page) and loads the layout's sheets and
 * scripts in the layout's order, because the tools only act inside .main and
 * .modal, and a spec that skips the shell passes against a page nobody sees.
 */

const ORIGIN = 'https://ui.test';

const asset = (name) => readFileSync(
	new URL(`../../../backend/src/main/resources/static/admin/_assets/${name}`, import.meta.url),
	'utf8');

// The layout's own stylesheet links, in its order (as row-dialog.spec.js reads them):
// ui-tools.css restyles flatpickr's sheet only by loading after it.
const SHEETS = [...readFileSync(new URL('../../../backend/src/main/jte/admin/layout.jte', import.meta.url), 'utf8')
	.matchAll(/<link rel="stylesheet" href="\/admin\/_assets\/((?:vendor\/)?[\w.-]+\.css)">/g)]
	.map((match) => match[1]);
if (!SHEETS.includes('vendor/flatpickr.min.css') || !SHEETS.includes('ui-tools.css')) {
	throw new Error(`layout.jte's stylesheet links did not read as expected: ${SHEETS}`);
}

const SCRIPTS = ['crud.js', 'row-dialog.js', 'modal-a11y.js', 'vendor/flatpickr.min.js', 'vendor/flatpickr-ar.js',
	'ui-pickers.js', 'ui-select-search.js', 'ui-copy.js', 'ui-confirm.js'];

// The layout's #ui-strings and #ui-confirm, as layout.jte renders them in Arabic.
const SHELL_TAIL = `
<div id="ui-strings" hidden data-str-copy="نسخ" data-str-copied="تم النسخ" data-str-copy-failed="تعذّر النسخ"
     data-str-select-search="ابحث…" data-str-select-no-results="لا توجد نتائج"></div>
<div class="modal-bg" id="ui-confirm" aria-hidden="true">
  <div class="modal ui-confirm" role="alertdialog" aria-modal="true"
       aria-labelledby="ui-confirm-title" aria-describedby="ui-confirm-message">
    <button type="button" class="modal-close" aria-label="إغلاق">&times;</button>
    <div class="ui-confirm__icon" aria-hidden="true"></div>
    <h2 id="ui-confirm-title">يرجى التأكيد</h2>
    <p class="ui-confirm__message" id="ui-confirm-message"></p>
    <p class="ui-confirm__detail" data-confirm-slot="detail" dir="auto"></p>
    <div class="form-footer">
      <button type="button" class="btn btn-gray" data-confirm-cancel>إلغاء</button>
      <button type="button" class="btn btn-blue" data-confirm-ok>تأكيد</button>
    </div>
  </div>
</div>`;

const NAMES = ['أحمد سالم', 'إيمان علي', 'مدرسة النور', 'سارة', 'يوسف', 'مريم', 'خالد', 'ليلى', 'نور', 'عمر'];

const BODY = `
<form method="POST" action="/delete" data-confirm="هل تريد الحذف؟" data-confirm-detail="2026-09-01 → 2026-09-30"
      data-confirm-tone="danger" id="delete-form">
  <input type="hidden" name="id" value="7">
  <button type="submit" name="action" value="delete" id="delete">حذف</button>
</form>
<form method="POST" action="/approve" data-confirm="اعتماد؟" id="approve-form">
  <button type="submit" name="action" value="approve" id="approve">اعتماد</button>
</form>

<form method="POST" action="/pick" id="pick-form">
  <div class="form-row">
    <label for="employee">الموظف</label>
    <select id="employee" name="employee_id" required>
      <option value="">— اختر —</option>
      ${NAMES.map((name, index) => `<option value="${index + 1}">${name}</option>`).join('')}
    </select>
  </div>
  <div class="form-row">
    <label for="short">قصيرة</label>
    <select id="short" name="short"><option value="a">أ</option><option value="b">ب</option></select>
  </div>
  <div class="form-row">
    <label for="from">من تاريخ</label>
    <input type="date" id="from" name="from" value="2026-09-26">
  </div>
  <div class="form-row">
    <label for="at">الوقت</label>
    <input type="time" id="at" name="at" value="14:30">
  </div>
  <button type="submit" id="pick-submit">حفظ</button>
</form>

<table class="tbl"><tbody><tr>
  <td><span data-copy dir="ltr">E008288</span></td>
  <td><span data-copy="+20 01000008288" dir="ltr">0100…</span></td>
  <td><span data-copy dir="ltr">—</span></td>
</tr></tbody></table>

<button type="button" data-dialog="edit" data-dialog-id="3" data-dialog-subject="row"
        data-dialog-day="2026-01-05" id="edit-trigger">edit</button>
<div class="modal-bg" id="edit" aria-hidden="true">
  <div class="modal" role="dialog" aria-modal="true" aria-labelledby="edit-title">
    <button type="button" class="modal-close" aria-label="close">&times;</button>
    <h2 id="edit-title">edit</h2>
    <p class="row-dialog__subject" data-dialog-field="subject"></p>
    <form method="POST" action="/edit">
      <input type="hidden" name="id" data-dialog-field="id">
      <div class="form-row"><label for="day">اليوم</label>
        <input type="date" id="day" name="day" data-dialog-field="day"></div>
      <div class="form-footer"><button type="submit" class="btn btn-blue">save</button></div>
    </form>
  </div>
</div>`;

const PAGE = `<!doctype html>
<html lang="ar" dir="rtl"><head><meta charset="utf-8">
${SHEETS.map((sheet) => `<style>${asset(sheet)}</style>`).join('\n')}
</head><body class="lang-ar">
<div class="shell"><main class="main" id="main-content"><div class="content hr-page">${BODY}</div></main></div>
${SHELL_TAIL}
${SCRIPTS.map((script) => `<script>${asset(script)}</script>`).join('\n')}
</body></html>`;

let posts;

test.beforeEach(async ({ page, context }) => {
	posts = [];
	await context.grantPermissions(['clipboard-read', 'clipboard-write'], { origin: ORIGIN });
	await page.route(`${ORIGIN}/**`, async (route) => {
		const request = route.request();
		if (request.method() === 'POST') {
			posts.push({ path: new URL(request.url()).pathname, body: request.postData() });
			await route.fulfill({ status: 200, contentType: 'text/html', body: '<!doctype html><p>posted</p>' });
			return;
		}
		await route.fulfill({ status: 200, contentType: 'text/html; charset=utf-8', body: PAGE });
	});
	await page.goto(`${ORIGIN}/`);
});

test.describe('confirm window', () => {
	test('a data-confirm form asks in the page, and cancelling posts nothing', async ({ page }) => {
		await page.locator('#delete').click();
		const window = page.locator('#ui-confirm');
		await expect(window).toHaveClass(/open/);
		await expect(window.locator('#ui-confirm-message')).toHaveText('هل تريد الحذف؟');
		await expect(window.locator('[data-confirm-slot="detail"]')).toHaveText('2026-09-01 → 2026-09-30');
		await expect(window.locator('[data-confirm-ok]'), 'a destructive action is red').toHaveClass(/btn-red/);
		await expect(window.locator('[data-confirm-cancel]'), 'Cancel takes focus, not Confirm').toBeFocused();

		await window.locator('[data-confirm-cancel]').click();
		await expect(window).not.toHaveClass(/open/);
		await page.waitForTimeout(200);
		expect(posts).toEqual([]);
	});

	test('Escape abandons the submit', async ({ page }) => {
		await page.locator('#delete').click();
		await expect(page.locator('#ui-confirm')).toHaveClass(/open/);
		await page.keyboard.press('Escape');
		await expect(page.locator('#ui-confirm')).not.toHaveClass(/open/);
		await page.waitForTimeout(200);
		expect(posts).toEqual([]);
	});

	test('confirming posts once, with the button that was pressed', async ({ page }) => {
		await page.locator('#delete').click();
		await page.locator('#ui-confirm [data-confirm-ok]').click();
		await expect.poll(() => posts.length).toBe(1);
		expect(posts[0].path).toBe('/delete');
		expect(posts[0].body).toContain('action=delete');
		expect(posts[0].body).toContain('id=7');
	});

	test('a confirm that is not destructive stays blue', async ({ page }) => {
		await page.locator('#approve').click();
		await expect(page.locator('#ui-confirm [data-confirm-ok]')).toHaveClass(/btn-blue/);
		await expect(page.locator('#ui-confirm [data-confirm-slot="detail"]')).toBeHidden();
	});
});

test.describe('searchable select', () => {
	test('a long select becomes a combobox and a short one does not', async ({ page }) => {
		await expect(page.locator('#employee')).toHaveAttribute('aria-hidden', 'true');
		const combobox = page.getByRole('combobox', { name: /الموظف/ });
		await expect(combobox).toBeVisible();
		await expect(combobox).toContainText('— اختر —');
		await expect(page.locator('#short')).toBeVisible();
	});

	test('the search ignores the hamza and ta marbuta, and a choice fires one change', async ({ page }) => {
		await page.evaluate(() => {
			window.changes = 0;
			document.getElementById('employee').addEventListener('change', () => { window.changes += 1; });
		});
		await page.getByRole('combobox', { name: /الموظف/ }).click();
		const search = page.getByRole('searchbox');
		await expect(search).toBeFocused();
		await search.fill('احمد');
		await expect(page.locator('.ui-select__popup:not([hidden])').getByRole('option')).toHaveText(['أحمد سالم']);
		await search.fill('مدرسه');
		await expect(page.locator('.ui-select__popup:not([hidden])').getByRole('option')).toHaveText(['مدرسة النور']);
		await search.press('Enter');

		await expect(page.locator('#employee')).toHaveValue('3');
		await expect(page.getByRole('combobox', { name: /الموظف/ })).toContainText('مدرسة النور');
		await expect(page.getByRole('combobox', { name: /الموظف/ }), 'focus returns to the field').toBeFocused();
		expect(await page.evaluate(() => window.changes)).toBe(1);
	});

	test('nothing matching says so, and Escape closes without changing the value', async ({ page }) => {
		await page.getByRole('combobox', { name: /الموظف/ }).click();
		await page.getByRole('searchbox').fill('zzz');
		await expect(page.locator('.ui-select__empty')).toBeVisible();
		await page.keyboard.press('Escape');
		await expect(page.locator('.ui-select__popup')).toBeHidden();
		await expect(page.locator('#employee')).toHaveValue('');
	});

	test('a value set by a script shows on the button', async ({ page }) => {
		await page.evaluate(() => { document.getElementById('employee').value = '5'; });
		await expect(page.getByRole('combobox', { name: /الموظف/ })).toContainText('يوسف');
	});

	test('a short select a script fills past eight options gets the search too', async ({ page }) => {
		await page.evaluate(() => {
			const select = document.getElementById('short');
			for (let i = 0; i < 10; i++) {
				select.add(new Option('خيار ' + i, 'o' + i));
			}
		});
		await expect(page.locator('#short')).toHaveAttribute('aria-hidden', 'true');
		await expect(page.getByRole('combobox', { name: /قصيرة/ })).toBeVisible();
	});

	test('a required select left empty still blocks the submit', async ({ page }) => {
		await page.locator('#pick-submit').click();
		await page.waitForTimeout(200);
		expect(posts).toEqual([]);
		expect(await page.locator('#employee').evaluate((select) => select.validity.valueMissing)).toBe(true);
	});
});

test.describe('copy', () => {
	test('a code copies its text and an attribute copies itself', async ({ page }) => {
		const buttons = page.locator('.ui-copy');
		await expect(buttons, 'no button for a dash').toHaveCount(2);

		await buttons.nth(0).click();
		await expect(buttons.nth(0)).toHaveClass(/is-copied/);
		expect(await page.evaluate(() => navigator.clipboard.readText())).toBe('E008288');

		await buttons.nth(1).click();
		expect(await page.evaluate(() => navigator.clipboard.readText())).toBe('+20 01000008288');
		await expect(buttons.nth(1)).toHaveAttribute('aria-label', 'نسخ: +20 01000008288');
	});
});

test.describe('date and time pickers', () => {
	test('the field reads in the page\'s format and posts the same ISO value', async ({ page }) => {
		const visible = page.locator('#from-picker');
		await expect(visible).toHaveValue('26/09/2026');
		await expect(page.locator('#from')).toHaveValue('2026-09-26');
		await expect(page.getByLabel('من تاريخ'), 'the label names the field a person uses').toHaveId('from-picker');

		await visible.click();
		const calendar = page.locator('.flatpickr-calendar.open');
		await expect(calendar).toBeVisible();
		await expect(calendar.locator('.flatpickr-weekday').first(), 'Saturday first, in Arabic').toHaveText('سبت');
		await calendar.locator('.flatpickr-day:not(.prevMonthDay):not(.nextMonthDay)', { hasText: /^14$/ }).click();
		await expect(page.locator('#from')).toHaveValue('2026-09-14');
		await expect(visible).toHaveValue('14/09/2026');
	});

	test('a time keeps its 24-hour value and reads with ص/م', async ({ page }) => {
		await expect(page.locator('#at')).toHaveValue('14:30');
		await expect(page.locator('#at-picker')).toHaveValue('2:30 م');
	});

	test('a row dialog that fills a date field shows the row\'s date', async ({ page }) => {
		await page.locator('#edit-trigger').click();
		await expect(page.locator('#edit')).toHaveClass(/open/);
		await expect(page.locator('#day')).toHaveValue('2026-01-05');
		await expect(page.locator('#day-picker')).toHaveValue('05/01/2026');
	});
});
