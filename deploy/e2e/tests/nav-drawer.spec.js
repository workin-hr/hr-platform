import { readFileSync } from 'node:fs';
import { test, expect } from '@playwright/test';

/**
 * The navigation drawer, on a page shaped like layout.jte, with no stack behind it.
 *
 * <p>At 1024px and below the sidebar is a drawer over a backdrop. The backdrop is fixed over the
 * whole page and stays in the layout while the drawer is closed, faded out, so the drawer can fade
 * it in. Faded out, it still took every tap: nothing on the page could be clicked, the menu button
 * that opens the drawer included. So the layout's own stylesheets, in the layout's order, and
 * nav-drawer.js run on the layout's shell, and what a tap reaches is observed in Chromium.
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

/**
 * The shared stylesheets `layout.jte` links, in its order. Read from the template, because
 * the order is load-bearing: `admin-extra.css` overrides `app-ui.css` rules of the same
 * specificity only by loading after it, and a hard-coded list would stay green if the
 * layout's links were reordered.
 */
function layoutSheets() {
	const layout = readFileSync(new URL('../../../backend/src/main/jte/admin/layout.jte', import.meta.url), 'utf8');
	const sheets = [...layout.matchAll(/<link rel="stylesheet" href="\/admin\/_assets\/([\w-]+\.css)">/g)].map((match) => match[1]);
	// Every stylesheet link but the per-page one: one that gains a query string,
	// an attribute or other quotes must fail here, not drop its sheet.
	const links = [...layout.matchAll(/<link\b[^>]*\brel=["']?stylesheet\b[^>]*>/g)]
		.filter((link) => !link[0].includes('href="/admin/_assets/${pageStyle}"'));
	if (sheets.length !== links.length || !sheets.includes('app-ui.css') || !sheets.includes('admin-extra.css')) {
		throw new Error(`layout.jte's stylesheet links did not read as expected: ${sheets} of ${links.length} links`);
	}
	return sheets;
}

const SHEETS = layoutSheets();

// layout.jte's shell: the sidebar, the backdrop before .main, the topbar's menu button, and a
// control in .content.
const PAGE = `<!doctype html><html lang="ar" dir="rtl"><body class="lang-ar">
<a class="skip-link" href="#main-content">skip</a>
<div class="shell">
  <aside class="sidebar" id="admin-sidebar">
    <div class="logo"><span class="logo-text">Work In</span></div>
    <nav><a href="#employees" class="nav-link">employees</a><a href="#requests" class="nav-link">requests</a></nav>
  </aside>
  <div class="nav-backdrop" aria-hidden="true"></div>
  <main class="main" id="main-content">
    <div class="topbar">
      <button type="button" class="nav-toggle" aria-expanded="false" aria-controls="admin-sidebar" aria-label="menu">
        <span class="nav-toggle-bars" aria-hidden="true"></span>
      </button>
      <h1 class="page-title">list</h1>
    </div>
    <div class="content hr-page">
      <div class="data-table-card"><button type="button" id="target" class="btn btn-blue">add</button></div>
    </div>
  </main>
</div></body></html>`;

async function load(page, width) {
	await page.setViewportSize({ width, height: 800 });
	await page.setContent(PAGE);
	for (const sheet of SHEETS) {
		await page.addStyleTag({ content: asset(sheet) });
	}
	await page.addScriptTag({ content: asset('nav-drawer.js') });
	await page.evaluate(() => {
		window.clicks = 0;
		document.getElementById('target').addEventListener('click', () => { window.clicks++; });
	});
	await settled(page);
}

/** Taps the centre of `selector`, and says what was under it: the element itself (or a child), or what covered it. */
async function tap(page, selector) {
	const box = await page.locator(selector).boundingBox();
	const x = box.x + box.width / 2;
	const y = box.y + box.height / 2;
	const hit = await page.evaluate(([px, py, sel]) => {
		const element = document.elementFromPoint(px, py);
		return element.closest(sel) ? 'itself' : element.id || element.className;
	}, [x, y, selector]);
	await page.mouse.click(x, y);
	return hit;
}

for (const width of [390, 768, 1024]) {
	test(`at ${width}px, with the drawer closed, a tap reaches the page and the menu button`, async ({ page }) => {
		await load(page, width);

		expect(await tap(page, '#target'), 'the control under the tap').toBe('itself');
		expect(await page.evaluate(() => window.clicks), 'the tap reached it').toBe(1);

		expect(await tap(page, '.nav-toggle'), 'the menu button under the tap').toBe('itself');
		await expect(page.locator('.shell'), 'the tap opened the drawer').toHaveClass(/nav-open/);
		await expect(page.locator('.nav-toggle')).toHaveAttribute('aria-expanded', 'true');
	});
}

test('at 390px the open drawer\'s backdrop takes the tap and closes it, and the page takes taps again', async ({ page }) => {
	await load(page, 390);
	await page.locator('.nav-toggle').click();
	await expect(page.locator('.shell')).toHaveClass(/nav-open/);
	await settled(page);

	// The drawer sits at the inline start, which is the right in RTL; the backdrop shows at the left.
	const hit = await page.evaluate(() => document.elementFromPoint(10, 400).className);
	expect(hit, 'over the page, the open drawer\'s backdrop').toBe('nav-backdrop');
	await page.mouse.click(10, 400);
	await expect(page.locator('.shell'), 'a tap on the backdrop closes the drawer').not.toHaveClass(/nav-open/);
	await expect(page.locator('.nav-toggle'), 'and returns focus to the menu button').toBeFocused();

	await settled(page);
	expect(await tap(page, '#target')).toBe('itself');
	expect(await page.evaluate(() => window.clicks)).toBe(1);
});

test('at 1280px the sidebar is a column: no menu button, and a tap reaches the page', async ({ page }) => {
	await load(page, 1280);

	await expect(page.locator('.nav-toggle')).toBeHidden();
	expect(await tap(page, '#target')).toBe('itself');
	expect(await page.evaluate(() => window.clicks)).toBe(1);
});
