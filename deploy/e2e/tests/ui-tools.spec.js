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
	'ui-pickers.js', 'ui-select-search.js', 'ui-copy.js', 'ui-confirm.js', 'ui-filters.js'];

// A list page's own sheet, after the layout's as its pageStyles load: it caps a
// filter at 280px with a rule that outranks ui-tools.css by order alone.
const PAGE_SHEETS = [...SHEETS, 'hr-pages.css'];

// The layout's #ui-strings and #ui-confirm, as layout.jte renders them in Arabic.
const SHELL_TAIL = `
<div id="ui-strings" hidden data-str-copy="نسخ" data-str-copied="تم النسخ" data-str-copy-failed="تعذّر النسخ"
     data-str-select-search="ابحث…" data-str-select-no-results="لا توجد نتائج" data-str-filters="الفلاتر"></div>
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

const MONTHS = ['يناير', 'فبراير', 'مارس', 'أبريل', 'مايو', 'يونيو', 'يوليو', 'أغسطس', 'سبتمبر',
	'أكتوبر', 'نوفمبر', 'ديسمبر'];

const NAMES = ['أحمد سالم', 'إيمان علي', 'مدرسة النور', 'سارة', 'يوسف', 'مريم', 'خالد', 'ليلى', 'نور', 'عمر'];

// employees.jte's filter bar: each select's first option is its "all", with
// the value that page gives it ("", "all", "0").
const FILTERS = `
<div class="toolbar page-toolbar page-toolbar-filters" id="filters">
  <form method="GET" class="toolbar-form toolbar-form--labeled">
    <div class="filter-field"><label for="f-company">الشركة</label>
      <select id="f-company" name="company_id"><option value="">كل الشركات</option><option value="4">شركة</option></select></div>
    <div class="filter-field"><label for="f-search">بحث</label>
      <input type="search" id="f-search" name="search" placeholder="بحث..."></div>
    <div class="filter-field"><label for="f-status">الحالة</label>
      <select id="f-status" name="filter"><option value="all">الكل</option><option value="active">نشط</option></select></div>
    <div class="filter-field"><label for="f-branch">الفرع</label>
      <select id="f-branch" name="filter_branch"><option value="0">الكل</option><option value="9">فرع</option></select></div>
    <div class="filter-field"><label for="f-from">تاريخ التعيين</label>
      <input type="date" id="f-from" name="date_from"></div>
    <button type="submit" class="btn btn-blue">بحث</button>
  </form>
</div>`;

const BODY = `${FILTERS}
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
        data-dialog-day="2026-01-05" data-dialog-check_in="2026-09-26T08:00:45" id="edit-trigger">edit</button>
<div class="modal-bg" id="edit" aria-hidden="true">
  <div class="modal" role="dialog" aria-modal="true" aria-labelledby="edit-title">
    <button type="button" class="modal-close" aria-label="close">&times;</button>
    <h2 id="edit-title">edit</h2>
    <p class="row-dialog__subject" data-dialog-field="subject"></p>
    <form method="POST" action="/edit">
      <input type="hidden" name="id" data-dialog-field="id">
      <div class="form-row"><label for="day">اليوم</label>
        <input type="date" id="day" name="day" data-dialog-field="day"></div>
      <div class="form-row"><label for="punch">الحضور</label>
        <input type="datetime-local" id="punch" name="check_in" required step="1" data-dialog-field="check_in"></div>
      <div class="form-footer"><button type="submit" class="btn btn-blue">save</button></div>
    </form>
  </div>
</div>

<button type="button" data-dialog="run" id="run-trigger">run</button>
<div class="modal-bg" id="run" aria-hidden="true">
  <div class="modal" role="dialog" aria-modal="true" aria-labelledby="run-title">
    <button type="button" class="modal-close" aria-label="close">&times;</button>
    <h2 id="run-title">run</h2>
    <form method="POST" action="/run" data-confirm="تشغيل؟" id="run-form">
      <div class="form-row"><label for="run-month">الشهر</label>
        <select id="run-month" name="month">${MONTHS.map((name, index) => `<option value="${index + 1}">${name}</option>`).join('')}</select></div>
      <div class="form-row"><label for="run-note">ملاحظة</label><input id="run-note" name="note"></div>
      <div class="form-row"><label for="run-year">السنة</label>
        <select id="run-year" name="year">${[2027, 2026, 2025, 2024, 2023, 2022, 2021, 2020].map((year) => `<option>${year}</option>`).join('')}</select></div>
      <div class="form-footer"><button type="submit" class="btn btn-blue" id="run-submit">run</button></div>
    </form>
  </div>
</div>

<div class="form-row">
  <label for="kind">النوع</label>
  <select id="kind" name="kind">
    <option value="">— اختر —</option>
    <option value="zd" disabled>زد</option>
    <option value="zy">زيد</option>
    ${['أ', 'ب', 'ت', 'ث', 'ج', 'ح'].map((name) => `<option value="${name}">${name}</option>`).join('')}
  </select>
</div>

<form method="POST" action="/named" data-confirm="حفظ؟" id="named-form">
  <input id="named" name="name" required value="x">
  <button type="submit" id="named-submit">save</button>
</form>`;

const PAGE = `<!doctype html>
<html lang="ar" dir="rtl"><head><meta charset="utf-8">
${PAGE_SHEETS.map((sheet) => `<style>${asset(sheet)}</style>`).join('\n')}
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
		// /filtered is the same list rendered with one filter in force.
		// /filtered is one filter in force; /edit-open is the edit dialog rendered
		// open, as a page that reopens a dialog after a failed save does.
		const path = new URL(request.url()).pathname;
		const body = path === '/filtered'
			? PAGE.replace('<option value="active">', '<option value="active" selected>')
			: path === '/edit-open' ? PAGE.replace('<div class="modal-bg" id="edit"', '<div class="modal-bg open" id="edit"')
				: PAGE;
		await route.fulfill({ status: 200, contentType: 'text/html; charset=utf-8', body });
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
		const search = page.locator('.ui-select__popup:not([hidden])').getByRole('searchbox');
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
		await page.locator('.ui-select__popup:not([hidden])').getByRole('searchbox').fill('zzz');
		await expect(page.locator('.ui-select__popup:not([hidden]) .ui-select__empty')).toBeVisible();
		await page.keyboard.press('Escape');
		await expect(page.locator('.ui-select__popup:not([hidden])')).toHaveCount(0);
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

test.describe('layout on a phone', () => {
	test('a wide table scrolls in its card and never widens the page', async ({ page }) => {
		await page.setViewportSize({ width: 390, height: 844 });
		await page.evaluate(() => {
			const cells = Array.from({ length: 14 }, (_, i) => `<td>عمود ${i} قيمة طويلة نسبيًا<span class="sr-only">label</span></td>`).join('');
			document.querySelector('.content').insertAdjacentHTML('beforeend',
				`<div class="data-table-card"><div class="table-wrap"><table class="tbl" id="wide"><tbody><tr>${cells}</tr></tbody></table></div></div>`);
		});
		// Measured once the entrance animations end: while .content still animates, its
		// transform makes it the containing block and hides exactly this defect (D-288).
		await page.waitForFunction(() => document.getAnimations().every((animation) => animation.playState !== 'running'));
		const wide = await page.locator('#wide').evaluate((table) => table.getBoundingClientRect().width);
		expect(wide, 'the table really is wider than the screen').toBeGreaterThan(600);
		expect(await page.evaluate(() => document.documentElement.scrollWidth), 'the document stays one screen wide').toBe(390);
		expect(await page.locator('.table-wrap').last().evaluate((wrap) => wrap.scrollWidth > wrap.clientWidth),
			'the card scrolls instead').toBe(true);
	});

	test('an unfiltered filter bar folds behind one button, and opens to full-width fields', async ({ page }) => {
		await page.setViewportSize({ width: 390, height: 844 });
		await page.reload();
		const toggle = page.locator('#filters .ui-filters-toggle');
		const form = page.locator('#filters form');
		await expect(toggle, 'every select still shows its "all" option').toHaveText('الفلاتر');
		await expect(form).toBeHidden();
		await expect(toggle).toHaveAttribute('aria-expanded', 'false');

		await toggle.click();
		await expect(form).toBeVisible();
		await expect(toggle).toHaveAttribute('aria-expanded', 'true');
		const widths = await form.locator('.filter-field').evaluateAll((fields) => fields.map((field) => {
			const control = field.querySelector('.ui-picker, input[type="search"], select');
			return [field.getBoundingClientRect().width, control.getBoundingClientRect().width];
		}));
		for (const [field, control] of widths) {
			expect(control, 'a field fills the phone\'s width, the search box and the date picker too').toBeCloseTo(field, 0);
		}
	});

	test('a filtered list keeps its filters open and counts them', async ({ page }) => {
		await page.setViewportSize({ width: 390, height: 844 });
		await page.goto(`${ORIGIN}/filtered`);
		await expect(page.locator('#filters .ui-filters-toggle')).toHaveText('الفلاتر (1)');
		await expect(page.locator('#filters form'), 'a filtered list never hides why it is filtered').toBeVisible();
	});

	test('from 640px up the filter bar is unchanged', async ({ page }) => {
		await expect(page.locator('#filters form')).toBeVisible();
		await expect(page.locator('#filters .ui-filters-toggle')).toBeHidden();
	});
});

test.describe('inside a dialog, and after a round of review (D-288)', () => {
	test('a long select in a dialog opens its list above the backdrop, and a choice keeps the dialog open', async ({ page }) => {
		await page.locator('#run-trigger').click();
		const dialog = page.locator('#run');
		await expect(dialog).toHaveClass(/open/);
		await page.locator('#run-month').locator('xpath=..').getByRole('combobox').click();
		const popup = page.locator('.ui-select__popup:not([hidden])');
		await expect(popup).toBeVisible();
		const hit = await popup.evaluate((node) => {
			const box = node.getBoundingClientRect();
			const top = document.elementFromPoint(box.left + box.width / 2, box.top + box.height / 2);
			return node.contains(top);
		});
		expect(hit, 'the list is what a click at its centre reaches, not the backdrop').toBe(true);
		await popup.getByRole('option', { name: 'مارس' }).click();
		await expect(page.locator('#run-month')).toHaveValue('3');
		await expect(dialog, 'choosing did not close the dialog').toHaveClass(/open/);
	});

	test('Tab out of an open list moves to the next field, inside the dialog', async ({ page }) => {
		await page.locator('#run-trigger').click();
		await page.locator('#run-month').locator('xpath=..').getByRole('combobox').click();
		await expect(page.locator('.ui-select__popup:not([hidden])')).toBeVisible();
		await page.keyboard.press('Tab');
		await expect(page.locator('#run-note')).toBeFocused();
		await expect(page.locator('.ui-select__popup:not([hidden])')).toHaveCount(0);
	});

	test('Escape on a confirm window over a dialog closes the window and leaves the dialog', async ({ page }) => {
		await page.locator('#run-trigger').click();
		await page.locator('#run-submit').click();
		await expect(page.locator('#ui-confirm')).toHaveClass(/open/);
		await page.keyboard.press('Escape');
		await expect(page.locator('#ui-confirm')).not.toHaveClass(/open/);
		await expect(page.locator('#run')).toHaveClass(/open/);
		expect(posts).toEqual([]);
	});

	test('a row whose punch has seconds posts them back unchanged', async ({ page }) => {
		await page.locator('#edit-trigger').click();
		await expect(page.locator('#punch')).toHaveValue('2026-09-26T08:00:45');
		await page.locator('#edit form button[type="submit"]').click();
		await expect.poll(() => posts.length).toBe(1);
		expect(new URLSearchParams(posts[0].body).get('check_in')).toBe('2026-09-26T08:00:45');
	});

	test('a reset puts back the date the page rendered, not the last one picked', async ({ page }) => {
		await page.locator('#from-picker').click();
		await page.locator('.flatpickr-calendar.open .flatpickr-day:not(.prevMonthDay):not(.nextMonthDay)', { hasText: /^14$/ }).click();
		await expect(page.locator('#from')).toHaveValue('2026-09-14');
		await page.locator('#pick-form').evaluate((form) => form.reset());
		await expect(page.locator('#from')).toHaveValue('2026-09-26');
		await expect(page.locator('#from-picker')).toHaveValue('26/09/2026');
	});

	test('Enter never chooses a disabled option', async ({ page }) => {
		await page.locator('#kind').locator('xpath=..').getByRole('combobox').click();
		await page.keyboard.type('ز');
		await page.keyboard.press('Enter');
		await expect(page.locator('#kind')).toHaveValue('zy');
	});

	test('a confirmed submit that validation refuses asks again next time', async ({ page }) => {
		await page.locator('#named-submit').click();
		await expect(page.locator('#ui-confirm')).toHaveClass(/open/);
		await page.locator('#named').evaluate((input) => { input.value = ''; });
		await page.locator('#ui-confirm [data-confirm-ok]').click();
		await page.waitForTimeout(200);
		expect(posts, 'the empty required field stopped the post').toEqual([]);
		await page.locator('#named').fill('y');
		await page.locator('#named-submit').click();
		await expect(page.locator('#ui-confirm'), 'the window asks again rather than posting unasked').toHaveClass(/open/);
		expect(posts).toEqual([]);
	});

	test('a dialog that focuses a date field does not open its calendar over the footer', async ({ page }) => {
		await page.locator('#edit-trigger').click();
		await expect(page.locator('#day-picker')).toBeFocused();
		await expect(page.locator('.flatpickr-calendar.open')).toHaveCount(0);
		await page.keyboard.press('ArrowDown');
		await expect(page.locator('.flatpickr-calendar.open'), 'ArrowDown opens it from the keyboard').toHaveCount(1);
	});

	test('the attendance edit dialog allows seconds, as #punch here does', () => {
		const attendance = readFileSync(new URL('../../../backend/src/main/jte/admin/attendance.jte', import.meta.url), 'utf8');
		for (const id of ['att_edit_check_in', 'att_edit_check_out']) {
			expect(attendance, `${id} carries step="1", which is what keeps a punch's seconds`)
				.toMatch(new RegExp(`<input type="datetime-local" id="${id}"[^>]*\\sstep="1"`));
		}
	});

	test('a punch keeps its seconds after its picker is opened and closed, and after Enter', async ({ page }) => {
		await page.locator('#edit-trigger').click();
		await page.locator('#punch-picker').click();
		await expect(page.locator('.flatpickr-calendar.open')).toHaveCount(1);
		// Leaving the field is what re-reads its visible text.
		await page.keyboard.press('Tab');
		await expect(page.locator('.flatpickr-calendar.open')).toHaveCount(0);
		await expect(page.locator('#punch')).toHaveValue('2026-09-26T08:00:45');
		// Enter in the field submits the form, as in any text field, and the
		// punch goes with its seconds.
		await page.locator('#punch-picker').focus();
		await page.keyboard.press('Enter');
		await expect.poll(() => posts.length).toBe(1);
		expect(new URLSearchParams(posts[0].body).get('check_in')).toBe('2026-09-26T08:00:45');
	});

	test('Tab from a list onto the dialog\'s last control stays there, and Shift+Tab goes back a field', async ({ page }) => {
		await page.locator('#run-trigger').click();
		await page.locator('#run-year').locator('xpath=..').getByRole('combobox').click();
		await page.keyboard.press('Tab');
		await expect(page.locator('#run-submit'), 'not wrapped round to the dialog\'s ×').toBeFocused();
		await page.locator('#run-year').locator('xpath=..').getByRole('combobox').click();
		await page.keyboard.press('Shift+Tab');
		await expect(page.locator('#run-note')).toBeFocused();
	});

	test('Escape with a calendar open closes the calendar and leaves the dialog', async ({ page }) => {
		await page.locator('#edit-trigger').click();
		await page.locator('#day-picker').focus();
		await page.keyboard.press('ArrowDown');
		await expect(page.locator('.flatpickr-calendar.open')).toHaveCount(1);
		await page.keyboard.press('Escape');
		await expect(page.locator('.flatpickr-calendar.open')).toHaveCount(0);
		await expect(page.locator('#edit')).toHaveClass(/open/);
	});

	test('a dialog rendered open keeps focus on its first field\'s visible picker', async ({ page }) => {
		await page.goto(`${ORIGIN}/edit-open`);
		await expect(page.locator('#day-picker')).toBeFocused();
	});

	test('a field with seconds is set with the spinner, and a PM time keeps its PM', async ({ page }) => {
		await page.locator('#edit-trigger').click();
		await expect(page.locator('#punch-picker'), 'typed text would lose م with the missing seconds')
			.toHaveAttribute('readonly', /.*/);
		await page.locator('#punch-picker').click();
		const calendar = page.locator('.flatpickr-calendar.open');
		await calendar.locator('.flatpickr-hour').fill('5');
		await calendar.locator('.flatpickr-minute').fill('30');
		const ampm = calendar.locator('.flatpickr-am-pm');
		if ((await ampm.textContent()).trim() !== 'م') {
			await ampm.click();
		}
		await calendar.locator('.flatpickr-minute').press('Tab');
		await expect(page.locator('#punch')).toHaveValue('2026-09-26T17:30:45');
	});

	test('Escape in a date-time calendar\'s hour field closes the calendar and leaves the dialog', async ({ page }) => {
		await page.locator('#edit-trigger').click();
		await page.locator('#punch-picker').click();
		const calendar = page.locator('.flatpickr-calendar.open');
		await calendar.locator('.flatpickr-hour').focus();
		await page.keyboard.press('Escape');
		await expect(page.locator('.flatpickr-calendar.open')).toHaveCount(0);
		await expect(page.locator('#edit')).toHaveClass(/open/);
		await expect(page.locator('#punch-picker')).toBeFocused();
	});
});

test.describe('a read-only date-time field (review round 4)', () => {
	test('Escape in the field closes its calendar and keeps the dialog', async ({ page }) => {
		await page.locator('#edit-trigger').click();
		await page.locator('#punch-picker').click();
		await expect(page.locator('.flatpickr-calendar.open')).toHaveCount(1);
		await page.keyboard.press('Escape');
		await expect(page.locator('.flatpickr-calendar.open')).toHaveCount(0);
		await expect(page.locator('#edit')).toHaveClass(/open/);
		await expect(page.locator('#punch-picker')).toBeFocused();
	});

	test('Enter with the calendar open closes it without submitting', async ({ page }) => {
		await page.locator('#edit-trigger').click();
		await page.locator('#punch-picker').click();
		await page.keyboard.press('Enter');
		await expect(page.locator('.flatpickr-calendar.open')).toHaveCount(0);
		await page.waitForTimeout(200);
		expect(posts).toEqual([]);
	});

	test('Backspace cannot empty a required read-only field', async ({ page }) => {
		await page.locator('#edit-trigger').click();
		await page.locator('#punch-picker').click();
		await page.keyboard.press('Backspace');
		await expect(page.locator('#punch')).toHaveValue('2026-09-26T08:00:45');
	});
});

test.describe('on a phone', () => {
	test.use({ userAgent: 'Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/129.0 Mobile Safari/537.36',
		viewport: { width: 390, height: 844 }, hasTouch: true, isMobile: true });

	test('a dialog rendered open keeps focus on its first field\'s native picker', async ({ page }) => {
		await page.goto(`${ORIGIN}/edit-open`);
		const focused = await page.evaluate(() => [document.activeElement.tagName, document.activeElement.type]);
		expect(focused).toEqual(['INPUT', 'date']);
	});
});
