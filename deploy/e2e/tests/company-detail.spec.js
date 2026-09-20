import { readFileSync } from 'node:fs';
import { test, expect } from '@playwright/test';

/**
 * The company detail page's `company-detail-*` classes against the style attributes they replace.
 *
 * <p>`companies/detail.php` writes its layout inline, as `employees/detail.php` does, and the same
 * two traps apply (see employee-detail.spec.js): an inline style beats every rule, and app-ui.css's
 * `.content > div[style*="border-radius:12px"]` gives legacy's card a radius and a shadow no class
 * matches. So the page is drawn twice, once with the port's classes and once with legacy's
 * attributes, each element where the template puts it, under the layout's stylesheets and the
 * page's own in the layout's order; and every computed property of every marked element must be
 * the same on both.
 */

const asset = (name) => readFileSync(
	new URL(`../../../backend/src/main/resources/static/admin/_assets/${name}`, import.meta.url),
	'utf8');

const template = (name) => readFileSync(
	new URL(`../../../backend/src/main/jte/admin/${name}`, import.meta.url),
	'utf8');

/** The shared stylesheets `layout.jte` links, in its order; see nav-drawer.spec.js. */
function layoutSheets() {
	const layout = template('layout.jte');
	const sheets = [...layout.matchAll(/<link rel="stylesheet" href="\/admin\/_assets\/([\w-]+\.css)">/g)].map((match) => match[1]);
	const links = [...layout.matchAll(/<link\b[^>]*\brel=["']?stylesheet\b[^>]*>/g)]
		.filter((link) => !link[0].includes('href="/admin/_assets/${pageStyle}"'));
	if (sheets.length !== links.length || !sheets.includes('app-ui.css') || !sheets.includes('admin-extra.css')) {
		throw new Error(`layout.jte's stylesheet links did not read as expected: ${sheets} of ${links.length} links`);
	}
	return sheets;
}

/** The page's own sheets, which the layout links after the shared ones. */
function pageSheets() {
	const declared = template('company-detail.jte').match(/pageStyles = java\.util\.List\.of\(([^)]*)\)/);
	if (!declared) {
		throw new Error('company-detail.jte declares no pageStyles');
	}
	const sheets = [...declared[1].matchAll(/"([\w-]+\.css)"/g)].map((match) => match[1]);
	if (sheets.length === 0 || sheets.length !== declared[1].split(',').length) {
		throw new Error(`company-detail.jte's pageStyles did not read as expected: ${declared[1]}`);
	}
	return sheets;
}

const SHEETS = [...layoutSheets(), ...pageSheets()];

const CARD = 'background:#fff;border:1px solid #e8e6e0;border-radius:12px;padding:20px;margin-bottom:20px;display:grid;grid-template-columns:auto 1fr;gap:20px';
const LOGO = 'width:72px;height:72px;border-radius:10px;object-fit:cover;border:1px solid #e8e6e0';
const TABLES = 'display:grid;grid-template-columns:1fr 1fr;gap:20px';

/**
 * One pair per classed element: the port's markup, and legacy's with the same content and its
 * style attribute (`detail.php:33-84`). `%` marks the element the pair compares.
 */
const PAIRS = {
	bar: ['<div % class="company-detail-bar"><a class="btn btn-outline btn-sm">back</a><a class="btn btn-blue btn-sm">branches</a></div>',
		'<div % style="margin-bottom:14px;display:flex;gap:8px;flex-wrap:wrap"><a class="btn btn-outline btn-sm">back</a><a class="btn btn-blue btn-sm">branches</a></div>'],
	card: ['<div % class="company-detail-card"><img alt="" class="company-detail-logo"><div>name</div></div>',
		`<div % style="${CARD}"><img alt="" class="company-detail-logo"><div>name</div></div>`],
	logo: ['<div class="company-detail-card"><img % alt="" class="company-detail-logo"><div>name</div></div>',
		`<div class="company-detail-card"><img % alt="" style="${LOGO}"><div>name</div></div>`],
	name: ['<div class="company-detail-card"><img alt="" class="company-detail-logo"><div><div % class="company-detail-name">Flow Co <span class="badge badge-green">Active</span></div></div></div>',
		'<div class="company-detail-card"><img alt="" class="company-detail-logo"><div><div % style="font-size:20px;font-weight:600;margin-bottom:6px">Flow Co <span class="badge badge-green">Active</span></div></div></div>'],
	fields: ['<div class="company-detail-card"><img alt="" class="company-detail-logo"><div><div % class="company-detail-fields"><span>&#9632; phone: <strong>0100</strong></span><span>&#9632; OTP: <strong><span class="badge badge-green">Yes</span></strong></span></div></div></div>',
		'<div class="company-detail-card"><img alt="" class="company-detail-logo"><div><div % style="display:grid;grid-template-columns:repeat(auto-fill,minmax(220px,1fr));gap:6px;font-size:13px;color:#5F5E5A"><span>&#9632; phone: <strong>0100</strong></span><span>&#9632; OTP: <strong><span class="badge badge-green">Yes</span></strong></span></div></div></div>'],
	reg: ['<div class="company-detail-card"><img alt="" class="company-detail-logo"><div><div class="company-detail-fields"><span>x</span></div><a % href="#" target="_blank" rel="noopener" class="btn btn-outline btn-sm company-detail-reg">reg</a></div></div>',
		'<div class="company-detail-card"><img alt="" class="company-detail-logo"><div><div class="company-detail-fields"><span>x</span></div><a % href="#" target="_blank" class="btn btn-outline btn-sm" style="margin-top:10px">reg</a></div></div>'],
	stats: ['<div % class="stats-grid company-detail-stats"><div class="stat-card green"><div class="stat-num">3</div><div class="stat-label">employees</div></div></div>',
		'<div % class="stats-grid" style="margin-bottom:20px"><div class="stat-card green"><div class="stat-num">3</div><div class="stat-label">employees</div></div></div>'],
	tables: ['<div % class="company-detail-tables"><div>left</div><div>right</div><div class="company-detail-wide">wide</div></div>',
		`<div % style="${TABLES}"><div>left</div><div>right</div><div style="grid-column:1/-1">wide</div></div>`],
	wide: ['<div class="company-detail-tables"><div>left</div><div>right</div><div % class="company-detail-wide">wide</div></div>',
		`<div class="company-detail-tables"><div>left</div><div>right</div><div % style="grid-column:1/-1">wide</div></div>`],
	more: ['<div class="table-wrap"><table class="tbl"><tbody><tr><td % colspan="8" class="company-detail-more">و 1 موظف — <a href="#">الكل</a></td></tr></tbody></table></div>',
		'<div class="table-wrap"><table class="tbl"><tbody><tr><td % colspan="8" style="text-align:center;padding:10px;color:#888">و 1 موظف — <a href="#">الكل</a></td></tr></tbody></table></div>'],
};

/**
 * The initials drawn where no logo is stored. Legacy draws ui-avatars.com's image in the logo's
 * box instead (`company_logo_src()`); the port draws the letters locally (D-264), so only the box
 * is legacy's, and only the box is compared.
 */
const AVATAR = ['<div class="company-detail-card"><span % class="company-detail-avatar" aria-hidden="true">F C</span><div>name</div></div>',
	`<div class="company-detail-card"><img % alt="" style="${LOGO}"><div>name</div></div>`];

const BOX = ['width', 'height', 'box-sizing', 'margin-top', 'margin-right', 'margin-bottom', 'margin-left',
	'border-top-left-radius', 'border-top-right-radius', 'border-bottom-left-radius', 'border-bottom-right-radius',
	...['top', 'right', 'bottom', 'left'].flatMap((side) => [`border-${side}-width`, `border-${side}-style`, `border-${side}-color`])];

/** One side of the page: each pair's markup for that side, a direct child of `.content`, in order. */
function page(side, pairs) {
	const blocks = Object.entries(pairs).map(([pair, sides]) => sides[side === 'port' ? 0 : 1].replace('%', `data-pair="${pair}"`));
	return `<!doctype html><html lang="ar" dir="rtl"><body class="lang-ar">
<div class="shell"><main class="main" id="main-content"><div class="content">${blocks.join('\n')}</div></main></div>
</body></html>`;
}

/** Every computed property of every marked element, keyed by pair. */
async function computed(context, side, width, pairs) {
	const tab = await context.newPage();
	await tab.setViewportSize({ width, height: 900 });
	await tab.setContent(page(side, pairs));
	for (const sheet of SHEETS) {
		await tab.addStyleTag({ content: asset(sheet) });
	}
	// The sheets arrive after the markup, so `.btn`'s transition runs; read the values it ends on.
	await tab.waitForFunction(() => document.getAnimations().every((animation) => animation.playState !== 'running'));
	const styles = await tab.evaluate(() => Object.fromEntries([...document.querySelectorAll('[data-pair]')].map((element) => {
		const style = getComputedStyle(element);
		const values = {};
		for (let index = 0; index < style.length; index++) {
			values[style[index]] = style.getPropertyValue(style[index]);
		}
		return [element.dataset.pair, values];
	})));
	await tab.close();
	return styles;
}

// Below 768px the two tables stack, where legacy keeps them two across (D-264).
for (const width of [1440, 1024]) {
	test(`every company-detail class computes as legacy's style attribute does, at ${width}px`, async ({ context }) => {
		const port = await computed(context, 'port', width, PAIRS);
		const legacy = await computed(context, 'legacy', width, PAIRS);
		expect(Object.keys(port), 'every pair was drawn with the port\'s classes').toEqual(Object.keys(PAIRS));
		expect(Object.keys(legacy), 'every pair was drawn with legacy\'s attributes').toEqual(Object.keys(PAIRS));
		expect(Object.keys(legacy.card).length, 'the whole computed style was read').toBeGreaterThan(200);

		const differences = [];
		for (const pair of Object.keys(PAIRS)) {
			for (const [property, value] of Object.entries(legacy[pair])) {
				if (port[pair][property] !== value) {
					differences.push(`${pair} ${property}: port ${port[pair][property]}, legacy ${value}`);
				}
			}
		}
		expect(differences).toEqual([]);
	});

	test(`the initials fill legacy's logo box, at ${width}px`, async ({ context }) => {
		const port = await computed(context, 'port', width, { avatar: AVATAR });
		const legacy = await computed(context, 'legacy', width, { avatar: AVATAR });
		const differences = BOX.filter((property) => port.avatar[property] !== legacy.avatar[property])
			.map((property) => `${property}: port ${port.avatar[property]}, legacy ${legacy.avatar[property]}`);
		expect(legacy.avatar.width, 'the box was read').toBe('72px');
		expect(differences).toEqual([]);
	});
}

test('every company-detail class in the sheet has a pair', () => {
	// A class added to the sheet without a pair here would not be compared at all.
	const declared = new Set([...asset('admin-extra.css').matchAll(/\.(company-detail-[\w-]+)/g)].map((match) => match[1]));
	const drawn = [...Object.values(PAIRS), AVATAR].map(([port]) => [...port.matchAll(/class="([^"]*)"/g)].flatMap((match) => match[1].split(' '))).flat();
	expect([...declared].filter((name) => !drawn.includes(name)), 'every company-detail class has a pair').toEqual([]);
	expect(declared.size, 'the sheet declared the classes this compares').toBeGreaterThanOrEqual(11);
});

test('below 768px the tables stack, and a wide one scrolls in its wrap rather than past the screen', async ({ page }) => {
	await page.setViewportSize({ width: 390, height: 900 });
	const table = (count) => `<div class="table-wrap"><table class="tbl"><thead><tr>${Array.from({ length: count }, (_, at) => `<th>column heading ${at + 1}</th>`).join('')}</tr></thead></table></div>`;
	await page.setContent(`<!doctype html><html lang="ar" dir="rtl"><body class="lang-ar">
<div class="shell"><main class="main" id="main-content"><div class="content"><div class="company-detail-tables"><div>${table(2)}</div><div>${table(2)}</div><div class="company-detail-wide">${table(8)}</div></div></div></main></div>
</body></html>`);
	for (const sheet of SHEETS) {
		await page.addStyleTag({ content: asset(sheet) });
	}
	await page.waitForFunction(() => document.getAnimations().every((animation) => animation.playState !== 'running'));
	const layout = await page.evaluate(() => {
		const grid = document.querySelector('.company-detail-tables').getBoundingClientRect();
		const items = [...document.querySelectorAll('.company-detail-tables > div')].map((item) => item.getBoundingClientRect());
		const wraps = [...document.querySelectorAll('.company-detail-tables .table-wrap')];
		return {
			outside: items.filter((item) => item.left < grid.left - 0.5 || item.right > grid.right + 0.5).length,
			stacked: items.every((item, at) => at === 0 || item.top >= items[at - 1].bottom),
			scrolls: wraps.map((wrap) => wrap.scrollWidth > wrap.clientWidth),
		};
	});
	expect(layout.stacked, 'one table under another').toBe(true);
	expect(layout.outside, 'no table wider than the page').toBe(0);
	expect(layout.scrolls, 'only the wide table scrolls, inside its own wrap').toEqual([false, false, true]);
});
