// Read-only capture of the production dashboard, for visual parity work.
//
// STRICTLY READ-ONLY, and deliberately so:
//   * one POST, the login form, because there is no other way in;
//   * every other request is a plain GET of a page URL;
//   * no clicks, no form submissions, no `?action=` query strings, no logout;
//   * the page list is the committed manifest, not a crawl, so it cannot
//     wander onto a URL nobody chose.
//
// The screenshots hold REAL CUSTOMER DATA -- names, phone numbers, salaries.
// They are written outside the repository and must not be committed.
import { chromium } from '@playwright/test';
import { readFileSync, mkdirSync } from 'node:fs';

const BASE = 'https://workin.company/dashboard';
const OUT = process.env.PROD_SHOT_DIR;
const MANIFEST = process.env.PROD_MANIFEST;
const SECRETS = process.env.PROD_SECRET_FILE;

if (!OUT || !MANIFEST || !SECRETS) {
  console.error('PROD_SHOT_DIR, PROD_MANIFEST and PROD_SECRET_FILE are required');
  process.exit(2);
}

// The password never reaches a command line or a log: it is read here and
// handed straight to fill().
const password = (readFileSync(SECRETS, 'utf8').match(/password is (\S+)/) || [])[1];
if (!password) {
  console.error('no password found in the secrets file');
  process.exit(2);
}

// Pages that need a row id, plus the two that would end the session.
const SKIP = new Set(['login', 'logout', 'company_detail', 'employee_detail']);
const pages = readFileSync(MANIFEST, 'utf8')
  .split('\n').map((line) => line.trim())
  .filter((line) => line && !line.startsWith('#') && !SKIP.has(line));

mkdirSync(OUT, { recursive: true });

const browser = await chromium.launch();
const context = await browser.newContext({ viewport: { width: 1440, height: 900 } });
const page = await context.newPage();

// Refuse anything that is not a GET, except the one login POST. A guard, not a
// hope: if some page fires a POST on load, this run does not perform it.
await context.route('**/*', (route) => {
  const request = route.request();
  // GET anything; POST only the login form. Everything else -- an approve, a
  // delete, anything a page might fire on load -- is aborted before it leaves.
  const allowed = request.method() === 'GET'
    || (request.method() === 'POST' && request.url().endsWith('/login.php'));
  if (allowed) {
    route.continue();
  } else {
    console.log(`BLOCKED ${request.method()} ${request.url()}`);
    route.abort();
  }
});

await page.goto(`${BASE}/login.php`, { waitUntil: 'domcontentloaded' });
await page.screenshot({ path: `${OUT}/00-login.png`, fullPage: true, animations: 'disabled' });

// The admin tab: user_type=admin, password only -- the phone field is not
// required for that type.
await page.evaluate(() => {
  const field = document.querySelector('input[name="user_type"]');
  if (field) { field.value = 'admin'; }
});
await page.fill('input[name="password"]', password);
await Promise.all([
  page.waitForNavigation({ waitUntil: 'domcontentloaded', timeout: 45000 }).catch(() => {}),
  page.locator('form').first().evaluate((form) => form.submit()),
]);

const landed = page.url();
console.log(`after login -> ${landed}`);
if (landed.includes('login.php')) {
  const body = await page.locator('body').innerText();
  console.log('LOGIN FAILED. Page says:', body.slice(0, 300).replace(/\s+/g, ' '));
  await page.screenshot({ path: `${OUT}/00-login-failed.png`, fullPage: true });
  await browser.close();
  process.exit(1);
}

await page.screenshot({ path: `${OUT}/01-index.png`, fullPage: true, animations: 'disabled' });

for (const name of pages) {
  const url = `${BASE}/${name === 'index' ? 'index' : name}.php`;
  try {
    const response = await page.goto(url, { waitUntil: 'domcontentloaded', timeout: 45000 });
    const status = response ? response.status() : 0;
    await page.screenshot({ path: `${OUT}/${name}.png`, fullPage: true, animations: 'disabled' });
    console.log(`${name} -> ${status}`);
  } catch (error) {
    console.log(`${name} -> ERROR ${error.message.split('\n')[0]}`);
  }
}

await browser.close();
