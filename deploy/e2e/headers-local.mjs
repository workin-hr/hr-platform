// The same, against this application, so the two can be diffed.
import { chromium } from '@playwright/test';
import { readFileSync, writeFileSync } from 'node:fs';

const BASE = 'https://127.0.0.1:8443';
const PASSWORD = 'e2e-verify-Pass123!';
const SKIP = new Set(['login', 'logout', 'company_detail', 'employee_detail',
  'company_settings', 'profile', 'change_password']);
const pages = readFileSync(process.env.LOCAL_MANIFEST, 'utf8')
  .split('\n').map((l) => l.trim()).filter((l) => l && !l.startsWith('#') && !SKIP.has(l));

const browser = await chromium.launch();
const page = await (await browser.newContext({ ignoreHTTPSErrors: true, baseURL: BASE,
  viewport: { width: 1600, height: 900 } })).newPage();
await page.goto('/admin/login');
await page.fill('input[name="password"]', PASSWORD);
await page.click('button[type="submit"]');

const out = {};
for (const name of pages) {
  const path = name === 'index' ? '/admin' : `/admin/${name}`;
  try {
    await page.goto(path, { waitUntil: 'domcontentloaded', timeout: 45000 });
    out[name] = await page.evaluate(() => [...document.querySelectorAll('table')].map(
      (t) => [...t.querySelectorAll('thead th')].map((th) => th.textContent.trim()).filter(Boolean)));
  } catch (error) {
    out[name] = [`ERROR ${error.message.split('\n')[0]}`];
  }
}
writeFileSync(process.env.LOCAL_HEADERS_OUT, JSON.stringify(out, null, 1));
console.log(`wrote headers for ${Object.keys(out).length} pages`);
await browser.close();
