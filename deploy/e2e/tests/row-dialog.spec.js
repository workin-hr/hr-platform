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

/**
 * Waits until no animation or transition is running. Not by awaiting each animation's `finished`
 * promise: a transition cancelled by the next state change rejects it, and the one replacing it
 * was never awaited, so a measurement could land mid-flight.
 */
async function settled(page) {
	await page.waitForFunction(() => document.getAnimations().every((animation) => animation.playState !== 'running'));
}

const asset = (name) => readFileSync(
	new URL(`../../../backend/src/main/resources/static/admin/_assets/${name}`, import.meta.url),
	'utf8');

const WINDOW = (id, body, cancel = '') => `<div class="modal-bg" id="${id}" aria-hidden="true">
  <div class="modal" role="dialog" aria-modal="true" aria-labelledby="${id}-title">
    <button type="button" class="modal-close" aria-label="close">&times;</button>
    <h2 id="${id}-title">edit</h2>
    <p class="row-dialog__subject" data-dialog-field="subject"></p>
    <form method="POST">
      <input type="hidden" name="action" value="edit">
      <input type="hidden" name="id" data-dialog-field="id">
      ${body}
      <div class="form-footer">
        ${cancel ? `<button type="button" class="btn btn-gray" data-dialog-cancel>${cancel}</button>` : ''}
        <button type="submit" class="btn btn-blue">save</button>
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

// Shaped like a real list page: the layout's shell and stylesheets, in the layout's order,
// a table card taller than the viewport holding the window, and the rows' ⋮ menus. A
// window written straight under <body> cannot show what the page's containers do to it:
// an animated card kept a transform, became the containing block of the fixed window, and
// laid it out against itself -- off-centre, scrolling the page, its title off-screen.
/**
 * The shared stylesheets `layout.jte` links, in its order. Read from the template, because
 * the order is load-bearing: `admin-extra.css` overrides `app-ui.css` rules of the same
 * specificity only by loading after it, and a hard-coded list would stay green if the
 * layout's links were reordered.
 */
function layoutSheets() {
	const layout = readFileSync(new URL('../../../backend/src/main/jte/admin/layout.jte', import.meta.url), 'utf8');
	const sheets = [...layout.matchAll(/<link rel="stylesheet" href="\/admin\/_assets\/((?:vendor\/)?[\w.-]+\.css)">/g)].map((match) => match[1]);
	// Every stylesheet link but the per-page one: one that gains a query string,
	// an attribute or other quotes must fail here, not drop its sheet.
	const links = [...layout.matchAll(/<link\b[^>]*\brel=["']?stylesheet\b[^>]*>/g)]
		.filter((link) => !link[0].includes('href="/admin/_assets/${pageStyle}"'));
	if (sheets.length !== links.length || !sheets.includes('app-ui.css') || !sheets.includes('admin-extra.css')) {
		throw new Error(`layout.jte's stylesheet links did not read as expected: ${sheets} of ${links.length} links`);
	}
	return sheets;
}

const SHEETS = [...layoutSheets(), 'hr-pages.css'];

const NAME_FIELD = '<div class="form-row"><label for="f">name</label><input type="text" id="f" name="nameEn" data-dialog-field="nameEn"></div>';

function listPage(rows, cancel = '', fields = NAME_FIELD) {
	const body = Array.from({ length: rows }, (_, i) => `<tr><td>row ${i}</td><td class="col-actions">
  <div class="row-actions" data-row-actions>
    <button type="button" class="row-actions__trigger" aria-label="actions" aria-haspopup="menu"
            aria-expanded="false" aria-controls="menu-${i}">⋮</button>
    <div class="row-actions__menu" id="menu-${i}" role="menu">
      <button type="button" role="menuitem" class="row-actions__item" data-dialog="edit"
              data-dialog-id="${i}" data-dialog-subject="Row ${i}" data-dialog-nameEn="Row ${i}">edit</button>
    </div>
  </div></td></tr>`).join('');
	return `<!doctype html><html lang="ar" dir="rtl"><body class="lang-ar">
<div class="shell"><main class="main" id="main-content">
  <div class="topbar"><h1 class="page-title">list</h1></div>
  <div class="content hr-page"><div class="data-table-card">
    <div class="table-wrap"><table class="tbl"><tbody>${body}</tbody></table></div>
    ${WINDOW('edit', fields, cancel)}
  </div></div>
</main></div></body></html>`;
}

async function loadListPage(page, rows, { width = 1280, height = 720, cancel = '', fields = NAME_FIELD } = {}) {
	await page.setViewportSize({ width, height });
	await page.setContent(listPage(rows, cancel, fields));
	for (const sheet of SHEETS) {
		await page.addStyleTag({ content: asset(sheet) });
	}
	for (const script of ['row-actions.js', 'crud.js', 'row-dialog.js', 'modal-a11y.js']) {
		await page.addScriptTag({ content: asset(script) });
	}
	// Let the entrance animations settle into the state they keep.
	await settled(page);
}

async function openFromMenu(page, row) {
	await page.locator(`[aria-controls="menu-${row}"]`).click();
	await page.locator(`[role="menu"] [data-dialog-id="${row}"]`).click();
	await expect(page.locator('#edit'), 'the window opens').toBeVisible();
}

const scrolls = (page) => page.evaluate(() => [window.scrollY, document.querySelector('.main').scrollTop]);

for (const rows of [40, 2]) {
	test(`in a table card of ${rows} rows, the window covers the viewport and the page stays put`, async ({ page }) => {
		await loadListPage(page, rows);
		const before = await scrolls(page);
		await openFromMenu(page, 1);

		expect(await page.locator('#edit').boundingBox(), 'the backdrop is the viewport, not the card')
			.toEqual({ x: 0, y: 0, width: 1280, height: 720 });
		const box = await page.locator('#edit .modal').boundingBox();
		expect(box.y, 'the title and × are on screen').toBeGreaterThanOrEqual(0);
		expect(box.y + box.height, 'and so is Save').toBeLessThanOrEqual(720);
		expect(await scrolls(page), 'opening does not scroll the page').toEqual(before);

		await page.mouse.click(5, 5);
		await expect(page.locator('#edit'), 'a click on the backdrop, over the topbar, closes it').toBeHidden();
	});
}

// complaints and company reject carry legacy's Cancel beside Save. Written with the ×'s
// .modal-close class it was positioned as the × is -- absolutely, inside the sticky footer --
// and lay over Save: a tap meant to save closed the window and lost what was typed.
for (const [width, height] of [[1280, 720], [390, 844]]) {
	test(`at ${width}px a footer Cancel sits beside Save, a tap on Save submits, and Cancel closes`, async ({ page }) => {
		await loadListPage(page, 3, { width, height, cancel: 'إلغاء' });
		await page.evaluate(() => {
			window.submitted = 0;
			document.querySelector('#edit form').addEventListener('submit', (event) => {
				window.submitted++;
				event.preventDefault();
			});
		});
		await openFromMenu(page, 1);
		const modal = page.locator('#edit');
		await modal.locator('[name="nameEn"]').fill('typed');

		const cancel = await modal.locator('[data-dialog-cancel]').boundingBox();
		const save = await modal.locator('button[type="submit"]').boundingBox();
		const overlap = !(cancel.x + cancel.width <= save.x || save.x + save.width <= cancel.x
			|| cancel.y + cancel.height <= save.y || save.y + save.height <= cancel.y);
		expect(overlap, 'Cancel and Save do not overlap').toBe(false);

		await page.mouse.click(save.x + save.width / 2, save.y + save.height / 2);
		expect(await page.evaluate(() => window.submitted), 'a tap on Save submits').toBe(1);
		await expect(modal, 'and does not close the window').toBeVisible();

		await modal.locator('[data-dialog-cancel]').click();
		await expect(modal, 'Cancel closes it').toBeHidden();
		expect(await page.evaluate(() => window.submitted), 'without submitting').toBe(1);
	});
}

// The footer sticks to the bottom of the scrolling .modal. Cut for 28px of padding against the
// 26px (and, on a phone, 20px) the sheets give the modal, it rode up over the last field: the
// field's bottom edge sat under the footer, and a click there landed on the footer.
const REPLY_FIELDS = `<div class="form-row"><label for="r">reply</label><textarea id="r" name="reply" rows="4" data-dialog-field="reply"></textarea></div>
<div class="form-row"><label for="s">status</label><select id="s" name="status" data-dialog-field="status"><option>open</option></select></div>`;
for (const [width, height] of [[1280, 720], [390, 844]]) {
	test(`at ${width}px the footer does not cover the window's last field`, async ({ page }) => {
		await loadListPage(page, 3, { width, height, cancel: 'إلغاء', fields: REPLY_FIELDS });
		await openFromMenu(page, 1);
		await settled(page);
		const modal = page.locator('#edit');

		const last = await modal.locator('#s').boundingBox();
		const footer = await modal.locator('.form-footer').boundingBox();
		expect(last.y + last.height, 'the last field ends above the footer').toBeLessThanOrEqual(footer.y);
		expect(await page.evaluate(([x, y]) => document.elementFromPoint(x, y)?.getAttribute('id'),
			[last.x + last.width / 2, last.y + last.height - 2]), 'its bottom edge is the field\'s').toBe('s');
	});
}

const LONG_FIELDS = Array.from({ length: 14 }, (_, i) => `<div class="form-row"><label for="l${i}">field ${i}</label><input type="text" id="l${i}" name="l${i}"></div>`).join('')
	+ '<div class="form-row"><label for="s">status</label><select id="s" name="status"><option>open</option></select></div>';
for (const [width, height] of [[1280, 720], [390, 844]]) {
	test(`at ${width}px a window longer than the screen keeps Save in view, and its last field clears the footer`, async ({ page }) => {
		await loadListPage(page, 3, { width, height, fields: LONG_FIELDS });
		await openFromMenu(page, 1);
		await settled(page);
		const modal = page.locator('#edit .modal');
		expect(await modal.evaluate((box) => box.scrollHeight > box.clientHeight), 'the window scrolls').toBe(true);

		const inView = async (when) => {
			const box = await modal.boundingBox();
			const save = await modal.locator('button[type="submit"]').boundingBox();
			const footer = await modal.locator('.form-footer').boundingBox();
			expect(save.y >= box.y && save.y + save.height <= Math.min(box.y + box.height, height), `Save is in view ${when}`).toBe(true);
			expect(footer.y + footer.height, `the stuck footer ends at the window's edge ${when}, not clipped past it`)
				.toBeLessThanOrEqual(box.y + box.height + 0.5);
		};
		await inView('before scrolling');

		await modal.evaluate((box) => { box.scrollTop = box.scrollHeight; });
		await inView('after scrolling');
		const last = await modal.locator('#s').boundingBox();
		const footer = await modal.locator('.form-footer').boundingBox();
		expect(last.y + last.height, 'the last field ends above the footer').toBeLessThanOrEqual(footer.y);
	});
}

test('a window opened from a row menu returns focus to that row\'s ⋮ button', async ({ page }) => {
	await loadListPage(page, 5);
	const menuButton = page.locator('[aria-controls="menu-3"]');
	await menuButton.focus();
	await page.keyboard.press('Enter');
	await page.locator('[role="menu"] [data-dialog-id="3"]').focus();
	await page.keyboard.press('Enter');
	await expect(page.locator('#edit [name="nameEn"]'), 'the window takes focus').toBeFocused();
	await page.keyboard.press('Escape');
	await expect(page.locator('#edit')).toBeHidden();
	await expect(menuButton, 'focus is back on the row acted on').toBeFocused();
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

/**
 * A window the server renders already open, which is what `?action=qr&id=N` and
 * `?action=edit&id=N` produce: the `open` class is in the HTML, so nothing ever
 * mutates it and modal-a11y.js's observer never fires for it. Before #305 that
 * left focus outside the overlay, and the Tab trap only engages once focus is
 * already on the window's first or last control -- so Tab walked the page
 * behind it. Legacy renders these itself, so it is the normal path, not an edge
 * case.
 */
function serverOpenPage(fields = NAME_FIELD) {
	return `<!doctype html><html lang="ar" dir="rtl"><body class="lang-ar">
<div class="shell"><main class="main" id="main-content">
  <div class="topbar"><h1 class="page-title">list</h1></div>
  <div class="content hr-page">
    <a href="/admin/branches" id="behind">a link behind the overlay</a>
    <div class="modal-bg open">
      <div class="modal modal--org-form" role="dialog" aria-modal="true" aria-labelledby="t">
        <h2 id="t">open already</h2>
        <form method="POST">${fields}
          <div class="form-footer"><a href="/admin/branches" class="btn btn-gray">cancel</a></div>
        </form>
      </div>
    </div>
  </div>
</main></div></body></html>`;
}

async function loadServerOpen(page) {
	await page.setViewportSize({ width: 1280, height: 720 });
	await page.setContent(serverOpenPage());
	for (const sheet of SHEETS) {
		await page.addStyleTag({ content: asset(sheet) });
	}
	for (const script of ['row-actions.js', 'crud.js', 'row-dialog.js', 'modal-a11y.js']) {
		await page.addScriptTag({ content: asset(script) });
	}
	await settled(page);
}

test('a window the server renders open takes focus, keeps Tab inside it, and closes on Escape', async ({ page }) => {
	await loadServerOpen(page);

	expect(await page.evaluate(() => document.activeElement.closest('.modal') !== null),
		'focus starts inside the window, not on the page behind it').toBe(true);
	expect(await page.evaluate(() => document.activeElement.id), 'on the first field').toBe('f');

	// From the last control, Tab wraps back inside rather than reaching the page behind.
	await page.evaluate(() => document.querySelector('.modal .form-footer a').focus());
	await page.keyboard.press('Tab');
	expect(await page.evaluate(() => document.activeElement.closest('.modal') !== null),
		'Tab from the last control stays inside the window').toBe(true);
	expect(await page.evaluate(() => document.activeElement.id === 'behind'),
		'and never lands on the link behind the overlay').toBe(false);

	await page.keyboard.press('Escape');
	await expect(page.locator('.modal-bg')).not.toHaveClass(/\bopen\b/);
});
