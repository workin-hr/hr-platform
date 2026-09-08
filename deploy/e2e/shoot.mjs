// Capture tool for work on the dashboard's appearance: sign in once, then
// screenshot the pages named on the command line.
//
//   node shoot.mjs employees attendance payroll settings
//   SHOT_DIR=/tmp/shots node shoot.mjs branches
//   SHOT_PROBE='(() => JSON.stringify(...))()' node shoot.mjs branches
//
// It exists because the alternative -- rebuild, click through the MFA flow by
// hand, look -- is a minute of typing per iteration, and CSS work is many
// iterations. SHOT_PROBE runs an expression in the page after each capture and
// prints the result, which is how you find out that a table is 813px wide
// inside a 1130px card rather than guessing from a picture.
import { chromium } from '@playwright/test';
import { createHash, createHmac } from 'node:crypto';
import { execFileSync } from 'node:child_process';
import { mkdirSync } from 'node:fs';

const DB = process.env.E2E_DB_CONTAINER ?? 'workin-integration-db-1';
const PW = process.env.E2E_DB_PASSWORD ?? 'e2e-throwaway-db-password';
const BASE = process.env.E2E_HTTPS_BASE ?? 'https://127.0.0.1:8443';
const PHONE = '+201000000042';
const PASSWORD = 'e2e-verify-Pass123!';
const OUT = process.env.SHOT_DIR ?? 'shots';

function sql(q) {
  return execFileSync('docker', ['exec', '-i', DB, 'mariadb', '-uworkin', `-p${PW}`, 'workin', '-N', '-B', '-e', q],
    { encoding: 'utf8' }).split('\n').filter((l) => !l.includes('Using a password')).join('\n').trim();
}
function totp(seed) {
  const A = 'ABCDEFGHIJKLMNOPQRSTUVWXYZ234567';
  let bits = '';
  for (const c of seed.replace(/=+$/, '').toUpperCase()) bits += A.indexOf(c).toString(2).padStart(5, '0');
  const bytes = [];
  for (let i = 0; i + 8 <= bits.length; i += 8) bytes.push(parseInt(bits.slice(i, i + 8), 2));
  const ctr = Buffer.alloc(8);
  ctr.writeBigUInt64BE(BigInt(Math.floor(Date.now() / 30000)));
  const d = createHmac('sha1', Buffer.from(bytes)).update(ctr).digest();
  const o = d[d.length - 1] & 15;
  return String((d.readUInt32BE(o) & 0x7fffffff) % 1000000).padStart(6, '0');
}

const pages = process.argv.slice(2);
if (!pages.length) { console.error('usage: node shoot.mjs <page> [page...]'); process.exit(2); }
mkdirSync(OUT, { recursive: true });

const adminId = sql(`SELECT id FROM platform_admins WHERE phone='${PHONE}'`);
const raw = 'shoot-' + Date.now();
sql(`DELETE FROM platform_admin_mfa WHERE platform_admin_id=${adminId}`);
sql(`DELETE FROM platform_admin_mfa_bootstrap_tokens WHERE platform_admin_id=${adminId}`);
sql(`INSERT INTO platform_admin_mfa_bootstrap_tokens (platform_admin_id, token_hash, issued_at, expires_at)
     VALUES (${adminId}, '${createHash('sha256').update(raw).digest('hex')}', NOW(6), NOW(6)+INTERVAL 30 MINUTE)`);

const browser = await chromium.launch();
const ctx = await browser.newContext({
  ignoreHTTPSErrors: true, baseURL: BASE, viewport: { width: 1440, height: 900 },
});
const page = await ctx.newPage();
await page.goto('/admin/enrol');
await page.fill('input[name="phone"]', PHONE);
await page.fill('input[name="password"]', PASSWORD);
await page.fill('input[name="bootstrapToken"]', raw);
await page.click('button[type="submit"]');
const seed = (await page.locator('code').first().textContent()).trim();
await page.fill('input[name="code"]', totp(seed));
await page.click('button[type="submit"]');
sql(`UPDATE platform_admin_mfa SET last_accepted_time_step=NULL WHERE platform_admin_id=${adminId}`);
await page.goto('/admin/login');
await page.fill('input[name="phone"]', PHONE);
await page.fill('input[name="password"]', PASSWORD);
await page.click('button[type="submit"]');
await page.fill('input[name="code"]', totp(seed));
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
