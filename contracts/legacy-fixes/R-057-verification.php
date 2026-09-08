<?php
declare(strict_types=1);
// Exercises the read guard employee_detail.php was missing (R-057), against
// the real schema, with the dashboard's session flags stubbed.
//
// Two independent failures, so two independent checks:
//   1. the page called requireLogin() and not hr_require_section('employees'),
//      so an HR session with no permission at all reached it;
//   2. its scoping was `if (isCompany())`, which is the company-owner flag
//      only, so an HR session took neither branch and read every tenant.

$GLOBALS['__company'] = false;
$GLOBALS['__hr'] = false;
$GLOBALS['__admin'] = false;
$GLOBALS['__sessionCompany'] = 0;
$GLOBALS['__permissions'] = [];

function isCompany(): bool { return $GLOBALS['__company']; }
function isHr(): bool { return $GLOBALS['__hr']; }
function isAdmin(): bool { return $GLOBALS['__admin']; }
function getCurrentCompanyId(): ?int { return $GLOBALS['__sessionCompany']; }

// org_helper.php:12-15, verbatim.
function org_is_scoped_company(): bool { return isCompany() || isHr(); }
function hr_is_scoped_company(): bool { return org_is_scoped_company(); }

// The section rule the page was missing: an administrator or company owner has
// full access, an HR employee needs the named permission.
function can_view_section(string $section): bool
{
    if (isAdmin() || isCompany()) {
        return true;
    }
    return in_array($section, $GLOBALS['__permissions'], true);
}

$pdo = new PDO(
    'mysql:host=127.0.0.1;port=' . getenv('DBPORT') . ';dbname=' . getenv('DBNAME') . ';charset=utf8mb4',
    getenv('DBUSER'), getenv('DBPASS'), [PDO::ATTR_ERRMODE => PDO::ERRMODE_EXCEPTION]);

/** The page's own query, with the guard applied exactly as the patch leaves it. */
function detail_row_visible(int $employeeId): bool
{
    global $pdo;
    $sql = 'SELECT e.id FROM employees e WHERE e.id = ?';
    $params = [$employeeId];
    if (hr_is_scoped_company()) {
        $sql .= ' AND e.company_id = ?';
        $params[] = (int) (getCurrentCompanyId() ?? 0);
    }
    $st = $pdo->prepare($sql);
    $st->execute($params);

    return $st->fetchColumn() !== false;
}

function as_company(int $companyId): void
{
    $GLOBALS['__company'] = true; $GLOBALS['__hr'] = false; $GLOBALS['__admin'] = false;
    $GLOBALS['__sessionCompany'] = $companyId; $GLOBALS['__permissions'] = [];
}

function as_hr(int $companyId, array $permissions): void
{
    $GLOBALS['__company'] = false; $GLOBALS['__hr'] = true; $GLOBALS['__admin'] = false;
    $GLOBALS['__sessionCompany'] = $companyId; $GLOBALS['__permissions'] = $permissions;
}

function as_admin(): void
{
    $GLOBALS['__company'] = false; $GLOBALS['__hr'] = false; $GLOBALS['__admin'] = true;
    $GLOBALS['__sessionCompany'] = 0; $GLOBALS['__permissions'] = [];
}

$pass = 0; $fail = 0;
function check(string $name, $got, $want) {
    global $pass, $fail;
    $g = var_export($got, true); $w = var_export($want, true);
    if ($got === $want) { $pass++; printf("OK   %-62s %s\n", $name, $g); }
    else { $fail++; printf("FAIL %-62s got %s want %s\n", $name, $g, $w); }
}

$row = $pdo->query(
    'SELECT id, company_id FROM employees ORDER BY id LIMIT 1')->fetch(PDO::FETCH_ASSOC);
if (!$row) {
    fwrite(STDERR, "no employees to test against\n");
    exit(1);
}
$employeeId = (int) $row['id'];
$owner = (int) $row['company_id'];
$other = (int) $pdo->query(
    'SELECT MIN(id) FROM companies WHERE id <> ' . $owner)->fetchColumn();

// --- 1. the missing section guard -------------------------------------------
as_hr($owner, []);
check('HR with no permissions may not open the page', can_view_section('employees'), false);
as_hr($owner, ['employees']);
check('HR holding the permission may', can_view_section('employees'), true);
as_company($owner);
check('a company owner may', can_view_section('employees'), true);
as_admin();
check('an administrator may', can_view_section('employees'), true);

// --- 2. the scoping that skipped HR entirely --------------------------------
as_company($owner);
check('owner reads own employee', detail_row_visible($employeeId), true);
as_company($other);
check('owner cannot read another company\'s', detail_row_visible($employeeId), false);

as_hr($owner, ['employees']);
check('HR reads own employee', detail_row_visible($employeeId), true);
as_hr($other, ['employees']);
check('HR cannot read another company\'s (R-057)', detail_row_visible($employeeId), false);

as_admin();
check('an administrator reads any', detail_row_visible($employeeId), true);

// --- 3. the rule underneath -------------------------------------------------
as_company($owner); check('isCompany session is scoped', hr_is_scoped_company(), true);
as_hr($owner, []);  check('HR session is scoped too -- the whole bug',
    hr_is_scoped_company(), true);
as_admin();         check('an administrator is not scoped', hr_is_scoped_company(), false);

printf("\n%d passed, %d failed\n", $pass, $fail);
exit($fail === 0 ? 0 : 1);
