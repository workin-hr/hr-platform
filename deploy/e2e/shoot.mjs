// Capture tool for work on the dashboard's appearance: sign in once, then
// screenshot the pages named on the command line.
//
//   node shoot.mjs employees attendance payroll settings
//   SHOT_DIR=/tmp/shots node shoot.mjs branches
//   SHOT_PROBE='(() => JSON.stringify(...))()' node shoot.mjs branches
//
// It exists because the alternative -- rebuild, click through the login by
// hand, look -- is a minute of typing per iteration, and CSS work is many
// iterations. SHOT_PROBE runs an expression in the page after each capture and
// prints the result, which is how you find out that a table is 813px wide
// inside a 1130px card rather than guessing from a picture.
import { chromium } from '@playwright/test';
import { mkdirSync } from 'node:fs';

const BASE = process.env.E2E_HTTPS_BASE ?? 'https://127.0.0.1:8443';
const PASSWORD = 'e2e-verify-Pass123!';
const OUT = process.env.SHOT_DIR ?? 'shots';

const pages = process.argv.slice(2);
if (!pages.length) { console.error('usage: node shoot.mjs <page> [page...]'); process.exit(2); }
mkdirSync(OUT, { recursive: true });

const browser = await chromium.launch();
const ctx = await browser.newContext({
  ignoreHTTPSErrors: true, baseURL: BASE, viewport: { width: 1440, height: 900 },
});
const page = await ctx.newPage();
await page.goto('/admin/login');
await page.fill('input[name="password"]', PASSWORD);
await page.click('button[type="submit"]');

for (const name of pages) {
  const path = name === 'index' ? '/admin' : `/admin/${name}`;
  const response = await page.goto(path, { waitUntil: 'domcontentloaded' });
  await page.screenshot({ path: `${OUT}/${name}.png`, fullPage: true, animations: 'disabled' });
  console.log(`${name} -> ${response.status()} ${page.url()}`);
  if (process.env.SHOT_PROBE) {
    console.log(await page.evaluate(process.env.SHOT_PROBE));
  }
}
await browser.close();
