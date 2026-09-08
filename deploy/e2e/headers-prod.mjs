// Read-only: the column headers production renders, per page.
// Same guard as prod-shots.mjs -- GET only, one login POST, nothing else.
import { chromium } from '@playwright/test';
import { readFileSync, writeFileSync } from 'node:fs';

const BASE = 'https://workin.company/dashboard';
const password = (readFileSync(process.env.PROD_SECRET_FILE, 'utf8').match(/password is (\S+)/) || [])[1];
const SKIP = new Set(['login', 'logout', 'company_detail', 'employee_detail']);
const pages = readFileSync(process.env.PROD_MANIFEST, 'utf8')
  .split('\n').map((l) => l.trim())
  .filter((l) => l && !l.startsWith('#') && !SKIP.has(l));

const browser = await chromium.launch();
const context = await browser.newContext({ viewport: { width: 1600, height: 900 } });
await context.route('**/*', (route) => {
  const r = route.request();
  const allowed = r.method() === 'GET' || (r.method() === 'POST' && r.url().endsWith('/login.php'));
  if (allowed) { route.continue(); } else { console.log(`BLOCKED ${r.method()} ${r.url()}`); route.abort(); }
});
const page = await context.newPage();
await page.goto(`${BASE}/login.php`, { waitUntil: 'domcontentloaded' });
await page.evaluate(() => { const f = document.querySelector('input[name="user_type"]'); if (f) f.value = 'admin'; });
await page.fill('input[name="password"]', password);
await Promise.all([
  page.waitForNavigation({ waitUntil: 'domcontentloaded' }).catch(() => {}),
  page.locator('form').first().evaluate((f) => f.submit()),
]);
if (page.url().includes('login.php')) { console.error('login failed'); process.exit(1); }

const out = {};
for (const name of pages) {
  try {
    await page.goto(`${BASE}/${name}.php`, { waitUntil: 'domcontentloaded', timeout: 45000 });
    out[name] = await page.evaluate(() => [...document.querySelectorAll('table')].map(
      (t) => [...t.querySelectorAll('thead th')].map((th) => th.textContent.trim()).filter(Boolean)));
  } catch (error) {
    out[name] = [`ERROR ${error.message.split('\n')[0]}`];
  }
}
writeFileSync(process.env.PROD_HEADERS_OUT, JSON.stringify(out, null, 1));
console.log(`wrote headers for ${Object.keys(out).length} pages`);
await browser.close();
