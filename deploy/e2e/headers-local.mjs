// The same, against this application, so the two can be diffed.
import { chromium } from '@playwright/test';
import { createHash, createHmac } from 'node:crypto';
import { execFileSync } from 'node:child_process';
import { readFileSync, writeFileSync } from 'node:fs';

const DB = 'workin-integration-db-1';
const PW = 'e2e-throwaway-db-password';
const BASE = 'https://127.0.0.1:8443';
const PHONE = '+201000000042';
const PASSWORD = 'e2e-verify-Pass123!';
const sql = (q) => execFileSync('docker', ['exec', '-i', DB, 'mariadb', '-uworkin', `-p${PW}`, 'workin', '-N', '-B', '-e', q],
  { encoding: 'utf8' }).split('\n').filter((l) => !l.includes('Using a password')).join('\n').trim();
function totp(seed) {
  const A = 'ABCDEFGHIJKLMNOPQRSTUVWXYZ234567';
  let bits = '';
  for (const c of seed.replace(/=+$/, '').toUpperCase()) bits += A.indexOf(c).toString(2).padStart(5, '0');
  const bytes = []; for (let i = 0; i + 8 <= bits.length; i += 8) bytes.push(parseInt(bits.slice(i, i + 8), 2));
  const ctr = Buffer.alloc(8); ctr.writeBigUInt64BE(BigInt(Math.floor(Date.now() / 30000)));
  const d = createHmac('sha1', Buffer.from(bytes)).update(ctr).digest();
  const o = d[d.length - 1] & 15;
  return String((d.readUInt32BE(o) & 0x7fffffff) % 1000000).padStart(6, '0');
}
const SKIP = new Set(['login', 'logout', 'company_detail', 'employee_detail',
  'company_settings', 'profile', 'change_password']);
const pages = readFileSync(process.env.LOCAL_MANIFEST, 'utf8')
  .split('\n').map((l) => l.trim()).filter((l) => l && !l.startsWith('#') && !SKIP.has(l));

const adminId = sql(`SELECT id FROM platform_admins WHERE phone='${PHONE}'`);
const raw = 'headers-' + Date.now();
sql(`DELETE FROM platform_admin_mfa WHERE platform_admin_id=${adminId}`);
sql(`DELETE FROM platform_admin_mfa_bootstrap_tokens WHERE platform_admin_id=${adminId}`);
sql(`INSERT INTO platform_admin_mfa_bootstrap_tokens (platform_admin_id, token_hash, issued_at, expires_at)
     VALUES (${adminId}, '${createHash('sha256').update(raw).digest('hex')}', NOW(6), NOW(6)+INTERVAL 30 MINUTE)`);

const browser = await chromium.launch();
const page = await (await browser.newContext({ ignoreHTTPSErrors: true, baseURL: BASE,
  viewport: { width: 1600, height: 900 } })).newPage();
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
