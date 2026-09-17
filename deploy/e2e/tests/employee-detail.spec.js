import { readFileSync } from 'node:fs';
import { test, expect } from '@playwright/test';

/**
 * The employee detail page's `emp-detail-*` classes against the style attributes they replace.
 *
 * <p>`employees/detail.php` writes its layout inline, and an inline style beats every rule. A class
 * does not: `.emp-detail-empty` carried legacy's padding and still lost it to app-ui.css's
 * `.tbl td`. So each classed element is drawn beside a twin carrying legacy's style attribute,
 * in the same place, under the layout's stylesheets and the page's own in the layout's order, and
 * every property legacy's attribute sets must compute the same on both.
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
		'<div % style="display:flex;align-items:center;gap:10px;margin-bottom:16px;flex-wrap:wrap"><a class="btn btn-outline btn-sm">back</a><form method="GET"><select><option>1</option></select><button class="btn btn-blue btn-sm">go</button></form></div>'],
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

function page() {
	const blocks = Object.entries(PAIRS).map(([pair, [port, legacy]]) => {
		const mark = (side) => `data-pair="${pair}" data-side="${side}"`;
		return `<section>${port.replace('%', mark('port'))}</section><section>${legacy.replace('%', mark('legacy'))}</section>`;
	});
	return `<!doctype html><html lang="ar" dir="rtl"><body class="lang-ar">
<div class="shell"><main class="main" id="main-content"><div class="content">${blocks.join('\n')}</div></main></div>
</body></html>`;
}

test('every emp-detail class computes to the style attribute legacy writes in its place', async ({ page: browser }) => {
	await browser.setViewportSize({ width: 1440, height: 900 });
	await browser.setContent(page());
	for (const sheet of SHEETS) {
		await browser.addStyleTag({ content: asset(sheet) });
	}

	const differences = await browser.evaluate(() => {
		const found = [];
		for (const legacy of document.querySelectorAll('[data-side="legacy"]')) {
			const pair = legacy.dataset.pair;
			const port = document.querySelector(`[data-side="port"][data-pair="${pair}"]`);
			const ported = getComputedStyle(port);
			const original = getComputedStyle(legacy);
			// The longhands legacy's attribute sets: `padding:20px` is four of them.
			for (let index = 0; index < legacy.style.length; index++) {
				const property = legacy.style[index];
				if (ported.getPropertyValue(property) !== original.getPropertyValue(property)) {
					found.push(`${pair} ${property}: port ${ported.getPropertyValue(property)}, legacy ${original.getPropertyValue(property)}`);
				}
			}
		}
		return found;
	});

	const compared = await browser.evaluate(() => [...document.querySelectorAll('[data-side="legacy"]')]
		.map((legacy) => legacy.dataset.pair));
	expect(compared, 'every pair was drawn, each with its port twin').toEqual(Object.keys(PAIRS));
	// A class added to the sheet without a pair here would not be compared at all.
	const declared = new Set([...asset('admin-extra.css').matchAll(/\.(emp-detail-[\w-]+)/g)].map((match) => match[1]));
	const drawn = Object.values(PAIRS).map(([port]) => [...port.matchAll(/class="([^"]*)"/g)].flatMap((match) => match[1].split(' '))).flat();
	expect([...declared].filter((name) => !drawn.includes(name)), 'every emp-detail class has a pair').toEqual([]);
	expect(declared.size, 'the sheet declared the classes this compares').toBeGreaterThanOrEqual(17);
	expect(differences).toEqual([]);
});
