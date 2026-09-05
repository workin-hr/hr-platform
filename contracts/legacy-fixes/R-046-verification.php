<?php
declare(strict_types=1);
// Exercises hr_row_company_id() and hr_verify_post_row()'s decision table
// against the real schema, with the dashboard's globals stubbed.

$GLOBALS['__scoped'] = false;
$GLOBALS['__sessionCompany'] = 0;
$GLOBALS['__refused'] = false;

function org_is_scoped_company(): bool { return $GLOBALS['__scoped']; }
function hr_is_scoped_company(): bool { return org_is_scoped_company(); }
function getCurrentCompanyId(): ?int { return $GLOBALS['__sessionCompany']; }
function __(string $k): string { return $k; }
function flash(string $m, string $t = 'ok'): void { }
function org_redirect(string $p, int $c = 0): never { throw new RuntimeException('REFUSED'); }
function hr_redirect(string $p, int $c = 0): never { org_redirect($p, $c); }

$pdo = new PDO(
    'mysql:host=127.0.0.1;port=' . getenv('DBPORT') . ';dbname=' . getenv('DBNAME') . ';charset=utf8mb4',
    getenv('DBUSER'), getenv('DBPASS'), [PDO::ATTR_ERRMODE => PDO::ERRMODE_EXCEPTION]);

function dbValue(string $sql, array $params = []) {
    global $pdo;
    $st = $pdo->prepare($sql);
    $st->execute($params);
    $v = $st->fetchColumn();
    return $v === false ? null : $v;
}

// Only the two functions under test, lifted from the patched helper.
$src = file_get_contents('/src/dashboard/includes/hr_helper.php');
preg_match('/function hr_row_company_id.*?\n\}/s', $src, $m1);
preg_match('/function hr_verify_post_row.*?\n\}/s', $src, $m2);
eval($m1[0]);
eval($m2[0]);

function attempt(callable $fn): string {
    try { $fn(); return 'ALLOWED'; }
    catch (RuntimeException $e) { return 'REFUSED'; }
}

$pass = 0; $fail = 0;
function check(string $name, string $got, string $want) {
    global $pass, $fail;
    if ($got === $want) { $pass++; printf("OK   %-58s %s\n", $name, $got); }
    else { $fail++; printf("FAIL %-58s got %s want %s\n", $name, $got, $want); }
}

// A real row and its true owner, plus a company that does not own it.
$tables = ['leave_balance', 'penalties', 'advances', 'requests', 'assets', 'complaints',
    'workforce_planning'];
foreach ($tables as $t) {
    $id = (int) dbValue("SELECT MIN(id) FROM `$t`");
    if ($id <= 0) { printf("SKIP %s (no rows)\n", $t); continue; }
    $owner = hr_row_company_id($t, $id);
    $other = (int) dbValue('SELECT MIN(id) FROM companies WHERE id <> ?', [$owner]);

    // 1. Scoped session, own row -> allowed
    $GLOBALS['__scoped'] = true; $GLOBALS['__sessionCompany'] = $owner;
    check("$t: owner edits own row", attempt(fn() => hr_verify_post_row($t, $id, $owner)), 'ALLOWED');

    // 2. Scoped session, ANOTHER company's row -> refused  (this is R-046)
    $GLOBALS['__sessionCompany'] = $other;
    check("$t: other company edits it (R-046)", attempt(fn() => hr_verify_post_row($t, $id, $other)), 'REFUSED');

    // 3. Admin, no filter -> allowed (cross-company mode is intended)
    $GLOBALS['__scoped'] = false; $GLOBALS['__sessionCompany'] = 0;
    check("$t: admin unfiltered", attempt(fn() => hr_verify_post_row($t, $id, 0)), 'ALLOWED');

    // 4. Admin filtered to another company -> refused
    check("$t: admin filtered elsewhere", attempt(fn() => hr_verify_post_row($t, $id, $other)), 'REFUSED');

    // 5. Admin filtered to the owner -> allowed
    check("$t: admin filtered to owner", attempt(fn() => hr_verify_post_row($t, $id, $owner)), 'ALLOWED');
}

// An add (id 0) must never be blocked.
$GLOBALS['__scoped'] = true; $GLOBALS['__sessionCompany'] = 1;
check('id 0 (an add) is not blocked', attempt(fn() => hr_verify_post_row('penalties', 0, 1)), 'ALLOWED');
// A row that does not exist must be refused, not allowed through.
check('missing row is refused', attempt(fn() => hr_verify_post_row('penalties', 999999999, 1)), 'REFUSED');
// An unknown table must fail closed.
check('unknown table fails closed', attempt(fn() => hr_verify_post_row('not_a_table', 5, 1)), 'REFUSED');

printf("\n%d passed, %d failed\n", $pass, $fail);
exit($fail === 0 ? 0 : 1);
