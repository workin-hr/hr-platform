import { readFileSync } from 'node:fs';
import { test, expect } from '@playwright/test';

/**
 * The employee detail page's `emp-detail-*` classes against the style attributes they replace.
 *
 * <p>`employees/detail.php` writes its layout inline, and a class is not a style attribute. An
 * inline style beats every rule: `.emp-detail-empty` carried legacy's padding and still lost it to
 * app-ui.css's `.tbl td`. And a rule can select on the attribute itself: app-ui.css's
 * `.content > div[style*="border-radius:12px"]` gives legacy's card a larger radius and a shadow
 * that a class never matches. So the page is drawn twice, once with the port's classes and once
 * with legacy's attributes, each element where the template puts it, under the layout's
 * stylesheets and the page's own in the layout's order; and every computed property of every
 * marked element must be the same on both.
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
	const sheets = [...layout.matchAll(/<link rel="stylesheet" href="\/admin\/_assets\/((?:vendor\/)?[\w.-]+\.css)">/g)].map((match) => match[1]);
	const links = [...layout.matchAll(/<link\b[^>]*\brel=["']?stylesheet\b[^>]*>/g)]
		.filter((link) => !link[0].includes('href="/admin/_assets/${pageStyle}"'));
	if (sheets.length !== links.length || !sheets.includes('app-ui.css') || !sheets.includes('admin-extra.css')) {
		throw new Error(`layout.jte's stylesheet links did not read as expected: ${sheets} of ${links.length} links`);
	}
	return sheets;
}

/** The page's own sheets, which the layout links after the shared ones. */
function pageSheets() {
	const declared = template('employee-detail.jte').match(/pageStyles = java\.util\.List\.of\(([^)]*)\)/);
	if (!declared) {
		throw new Error('employee-detail.jte declares no pageStyles');
	}
	const sheets = [...declared[1].matchAll(/"([\w-]+\.css)"/g)].map((match) => match[1]);
	if (sheets.length === 0 || sheets.length !== declared[1].split(',').length) {
		throw new Error(`employee-detail.jte's pageStyles did not read as expected: ${declared[1]}`);
	}
	return sheets;
}

const SHEETS = [...layoutSheets(), ...pageSheets()];

/**
 * One pair per classed element: the port's markup, and legacy's with the same content and its
 * style attribute (`detail.php:40-138`). `%` marks the element the pair compares.
 */
const PAIRS = {
	bar: ['<div % class="emp-detail-bar"><a class="btn btn-outline btn-sm">back</a><form method="GET"><select><option>1</option></select><button class="btn btn-blue btn-sm">go</button></form></div>',
		'<div % style="display:flex;align-items:center;gap:10px;margin-bottom:16px;flex-wrap:wrap"><a class="btn btn-outline btn-sm">back</a><form method="GET" style="display:flex;gap:8px"><select><option>1</option></select><button class="btn btn-blue btn-sm">go</button></form></div>'],
	barForm: ['<div class="emp-detail-bar"><form % method="GET"><select><option>1</option></select><button class="btn btn-blue btn-sm">go</button></form></div>',
		'<div style="display:flex;align-items:center;gap:10px;margin-bottom:16px;flex-wrap:wrap"><form % method="GET" style="display:flex;gap:8px"><select><option>1</option></select><button class="btn btn-blue btn-sm">go</button></form></div>'],
	card: ['<div % class="emp-detail-card"><div class="emp-detail-avatar">A A</div><div>name</div></div>',
		'<div % style="background:#fff;border:1px solid #e8e6e0;border-radius:12px;padding:20px;margin-bottom:20px;display:grid;grid-template-columns:auto 1fr;gap:20px"><div class="emp-detail-avatar">A A</div><div>name</div></div>'],
	photo: ['<div class="emp-detail-card"><img % alt="" class="emp-detail-photo"><div>name</div></div>',
		'<div class="emp-detail-card"><img % alt="" style="width:72px;height:72px;border-radius:50%;object-fit:cover;border:1px solid #eee;flex-shrink:0"><div>name</div></div>'],
	avatar: ['<div class="emp-detail-card"><div % class="emp-detail-avatar">A A</div><div>name</div></div>',
		'<div class="emp-detail-card"><div % style="width:72px;height:72px;border-radius:50%;background:#E6F1FB;display:flex;align-items:center;justify-content:center;font-size:20px;font-weight:700;color:#185FA5;flex-shrink:0;letter-spacing:0.05em">A A</div><div>name</div></div>'],
	name: ['<div class="emp-detail-card"><div class="emp-detail-avatar">A A</div><div><div % class="emp-detail-name">aya alpha <span class="badge badge-green">Yes</span></div></div></div>',
		'<div class="emp-detail-card"><div class="emp-detail-avatar">A A</div><div><div % style="font-size:20px;font-weight:600;margin-bottom:4px">aya alpha <span class="badge badge-green">Yes</span></div></div></div>'],
	fields: ['<div class="emp-detail-card"><div class="emp-detail-avatar">A A</div><div><div % class="emp-detail-fields"><span>&#9632; code: <strong>1001</strong></span><span>&#9632; phone: <strong>1012345678</strong></span></div></div></div>',
		'<div class="emp-detail-card"><div class="emp-detail-avatar">A A</div><div><div % style="display:grid;grid-template-columns:repeat(auto-fill,minmax(200px,1fr));gap:6px;margin-top:8px;font-size:13px;color:#5F5E5A"><span>&#9632; code: <strong>1001</strong></span><span>&#9632; phone: <strong>1012345678</strong></span></div></div></div>'],
	stats: ['<div % class="stats-grid emp-detail-stats"><div class="stat-card green"><div class="stat-num">1</div><div class="stat-label">days</div></div></div>',
		'<div % class="stats-grid" style="margin-bottom:20px"><div class="stat-card green"><div class="stat-num">1</div><div class="stat-label">days</div></div></div>'],
	tables: ['<div % class="emp-detail-tables"><div>left</div><div>right</div></div>',
		'<div % style="display:grid;grid-template-columns:1fr 1fr;gap:20px"><div>left</div><div>right</div></div>'],
	emptyTall: ['<div class="table-wrap"><table class="tbl"><tbody><tr><td % colspan="5" class="emp-detail-empty emp-detail-empty--tall">no data</td></tr></tbody></table></div>',
		'<div class="table-wrap"><table class="tbl"><tbody><tr><td % colspan="5" style="text-align:center;padding:20px;color:#888">no data</td></tr></tbody></table></div>'],
	empty: ['<div class="table-wrap"><table class="tbl"><tbody><tr><td % colspan="4" class="emp-detail-empty">no data</td></tr></tbody></table></div>',
		'<div class="table-wrap"><table class="tbl"><tbody><tr><td % colspan="4" style="text-align:center;padding:16px;color:#888">no data</td></tr></tbody></table></div>'],
	section: ['<div % class="emp-detail-section"><div class="section-head"><h2>payroll</h2></div></div>',
		'<div % style="margin-top:20px"><div class="section-head"><h2>payroll</h2></div></div>'],
	payslip: ['<div class="emp-detail-section"><div % class="emp-detail-payslip"><div class="emp-detail-figures"><div>figure</div></div></div></div>',
		'<div style="margin-top:20px"><div % style="background:#fff;border:1px solid #e8e6e0;border-radius:12px;padding:20px"><div class="emp-detail-figures"><div>figure</div></div></div></div>'],
	figures: ['<div class="emp-detail-payslip"><div % class="emp-detail-figures"><div><div class="text-muted">basic</div><div class="bold">8,000</div></div></div></div>',
		'<div class="emp-detail-payslip"><div % style="display:grid;grid-template-columns:repeat(auto-fill,minmax(150px,1fr));gap:14px;font-size:13px"><div><div class="text-muted">basic</div><div class="bold">8,000</div></div></div></div>'],
	net: ['<div class="emp-detail-figures"><div % class="emp-detail-net"><div class="text-muted">net</div><div class="emp-detail-net-value">8,330 ج.م</div></div></div>',
		'<div class="emp-detail-figures"><div % style="border-right:3px solid #185FA5;padding-right:10px"><div class="text-muted">net</div><div class="emp-detail-net-value">8,330 ج.م</div></div></div>'],
	netValue: ['<div class="emp-detail-figures"><div class="emp-detail-net"><div class="text-muted">net</div><div % class="emp-detail-net-value">8,330 ج.م</div></div></div>',
		'<div class="emp-detail-figures"><div class="emp-detail-net"><div class="text-muted">net</div><div % style="font-size:18px;font-weight:700;color:#185FA5">8,330 ج.م</div></div></div>'],
	docs: ['<div % class="emp-detail-docs"><a href="#" class="emp-detail-doc">id_card</a></div>',
		'<div % style="display:flex;flex-wrap:wrap;gap:8px"><a href="#" class="emp-detail-doc">id_card</a></div>'],
	doc: ['<div class="emp-detail-docs"><a % href="#" target="_blank" rel="noopener" class="emp-detail-doc">id_card</a></div>',
		'<div class="emp-detail-docs"><a % href="#" target="_blank" style="display:inline-block;padding:6px 14px;background:#E6F1FB;color:#185FA5;border-radius:8px;font-size:12px;text-decoration:none">id_card</a></div>'],
};

/**
 * D-284 re-pointed legacy's literal colours at the design tokens; the rest of each
 * style attribute is legacy's to the pixel. The legacy side is drawn through that
 * table, so every property -- colours included -- is still compared exactly: a class
 * that drifts from the token D-284 chose for legacy's colour fails, as one that
 * drifts from legacy's spacing does.
 */
const D284 = {
	'#fff': 'var(--ui-surface)',
	'#e8e6e0': 'var(--ui-border-soft)',
	'#eee': 'var(--ui-neutral-2)',
	'#e6f1fb': 'var(--ui-info-weak)',
	'#185fa5': 'var(--ui-accent-500)',
	'#5f5e5a': 'var(--ui-text-soft)',
	'#888': 'var(--ui-text-muted)',
};
const throughD284 = (markup) => markup.replace(/(?<!&)#[0-9a-fA-F]{3,6}\b/g, (hex) => {
	const token = D284[hex.toLowerCase()];
	if (!token) {
		throw new Error(`legacy colour ${hex} has no D-284 token in this spec's table`);
	}
	return token;
});

/** One side of the page: each pair's markup for that side, a direct child of `.content`, in order. */
function page(side) {
	const blocks = Object.entries(PAIRS).map(([pair, sides]) => (side === 'port' ? sides[0] : throughD284(sides[1])).replace('%', `data-pair="${pair}"`));
	return `<!doctype html><html lang="ar" dir="rtl"><body class="lang-ar">
<div class="shell"><main class="main" id="main-content"><div class="content">${blocks.join('\n')}</div></main></div>
</body></html>`;
}

/** Every computed property of every marked element, keyed by pair. */
async function computed(context, side, width) {
	const tab = await context.newPage();
	await tab.setViewportSize({ width, height: 900 });
	await tab.setContent(page(side));
	for (const sheet of SHEETS) {
		await tab.addStyleTag({ content: asset(sheet) });
	}
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

// Below 768px the four tables stack, where legacy keeps them two across (D-262).
for (const width of [1440, 1024]) {
	test(`every emp-detail class computes as legacy's style attribute does, at ${width}px`, async ({ context }) => {
		const port = await computed(context, 'port', width);
		const legacy = await computed(context, 'legacy', width);
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
}

test('every emp-detail class in the sheet has a pair', () => {
	// A class added to the sheet without a pair here would not be compared at all.
	const declared = new Set([...asset('admin-extra.css').matchAll(/\.(emp-detail-[\w-]+)/g)].map((match) => match[1]));
	const drawn = Object.values(PAIRS).map(([port]) => [...port.matchAll(/class="([^"]*)"/g)].flatMap((match) => match[1].split(' '))).flat();
	expect([...declared].filter((name) => !drawn.includes(name)), 'every emp-detail class has a pair').toEqual([]);
	expect(declared.size, 'the sheet declared the classes this compares').toBeGreaterThanOrEqual(17);
});

test('below 768px the tables stack, and a wide one scrolls in its wrap rather than past the screen', async ({ page }) => {
	await page.setViewportSize({ width: 390, height: 900 });
	const table = (count) => `<div class="table-wrap"><table class="tbl"><thead><tr>${Array.from({ length: count }, (_, at) => `<th>column heading ${at + 1}</th>`).join('')}</tr></thead></table></div>`;
	await page.setContent(`<!doctype html><html lang="ar" dir="rtl"><body class="lang-ar">
<div class="shell"><main class="main" id="main-content"><div class="content"><div class="emp-detail-tables"><div>${table(8)}</div><div>${table(2)}</div><div>${table(2)}</div><div>${table(2)}</div></div></div></main></div>
</body></html>`);
	for (const sheet of SHEETS) {
		await page.addStyleTag({ content: asset(sheet) });
	}
	await page.waitForFunction(() => document.getAnimations().every((animation) => animation.playState !== 'running'));
	const layout = await page.evaluate(() => {
		const grid = document.querySelector('.emp-detail-tables').getBoundingClientRect();
		const items = [...document.querySelectorAll('.emp-detail-tables > div')].map((item) => item.getBoundingClientRect());
		const wraps = [...document.querySelectorAll('.emp-detail-tables .table-wrap')];
		return {
			outside: items.filter((item) => item.left < grid.left - 0.5 || item.right > grid.right + 0.5).length,
			stacked: items.every((item, at) => at === 0 || item.top >= items[at - 1].bottom),
			scrolls: wraps.map((wrap) => wrap.scrollWidth > wrap.clientWidth),
		};
	});
	expect(layout.stacked, 'one table under another').toBe(true);
	expect(layout.outside, 'no table wider than the page').toBe(0);
	expect(layout.scrolls, 'only the wide table scrolls, inside its own wrap').toEqual([true, false, false, false]);
});
