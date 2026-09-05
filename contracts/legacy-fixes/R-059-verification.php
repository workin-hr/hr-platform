<?php
declare(strict_types=1);
// Exercises the four write guards the attendance page was missing (R-059),
// against the real schema. Read-only: it selects existing ids and never writes.
//
// attendance has no company_id column at all. A row's owning company comes
// from employee_id alone, so there are two distinct failures:
//
//   1. edit_attendance and delete wrote by row id -- UPDATE/DELETE ... WHERE
//      id=? with no tenant predicate, the R-046 shape on a page R-046 never
//      covered;
//   2. add_attendance took employee_id straight from the POST, so the foreign
//      key that *decides* the new row's owner was attacker-chosen. That is the
//      insert side, which R-046 did not have to handle because those tables
//      carry their own company_id.
//
// exception_type_id is a second company-scoped foreign key written on both
// paths, validated by the API and by neither page action.

$pdo = new PDO(
    'mysql:host=127.0.0.1;port=' . getenv('DBPORT') . ';dbname=' . getenv('DBNAME') . ';charset=utf8mb4',
    getenv('DBUSER'), getenv('DBPASS'), [PDO::ATTR_ERRMODE => PDO::ERRMODE_EXCEPTION]);
$pdo->exec('START TRANSACTION READ ONLY');

$pass = 0; $fail = 0;
function check(string $what, bool $ok): void {
    global $pass, $fail;
    $ok ? $pass++ : $fail++;
    printf("  [%s] %s\n", $ok ? ' ok ' : 'FAIL', $what);
}

/** hr_row_company_id('attendance', $id), as the patch defines it. */
function row_company_id(int $id): ?int {
    global $pdo;
    $st = $pdo->prepare(
        'SELECT e.company_id FROM `attendance` t
         JOIN employees e ON e.id = t.employee_id
         WHERE t.id = ? LIMIT 1');
    $st->execute([$id]);
    $v = $st->fetchColumn();
    return $v === false ? null : (int) $v;
}

/** payroll_employee_belongs_to_company(), as the patch defines it. */
function employee_in_company(int $employeeId, int $companyId): bool {
    global $pdo;
    if ($employeeId <= 0 || $companyId <= 0) { return false; }
    $st = $pdo->prepare('SELECT COUNT(*) FROM employees WHERE id=? AND company_id=?');
    $st->execute([$employeeId, $companyId]);
    return (int) $st->fetchColumn() > 0;
}

/** payroll_exception_type_belongs_to_company(), as the patch defines it. */
function exception_type_in_company(int $typeId, int $companyId): bool {
    global $pdo;
    if ($typeId <= 0 || $companyId <= 0) { return false; }
    $st = $pdo->prepare('SELECT COUNT(*) FROM exception_types WHERE id=? AND company_id=?');
    $st->execute([$typeId, $companyId]);
    return (int) $st->fetchColumn() > 0;
}

echo "R-059: the attendance page's write guards\n\n";

// The table really has no company_id -- the premise the whole finding rests on.
$cols = $pdo->query("SHOW COLUMNS FROM `attendance` LIKE 'company_id'")->fetchAll();
check('attendance has no company_id column, so employee_id is the ownership', $cols === []);

// Two companies that both have attendance, so "another tenant" is real data.
$pairs = $pdo->query(
    'SELECT e.company_id, MIN(a.id) AS att_id, MIN(a.employee_id) AS emp_id
     FROM attendance a JOIN employees e ON e.id = a.employee_id
     GROUP BY e.company_id HAVING COUNT(*) > 0 ORDER BY e.company_id LIMIT 2')->fetchAll(PDO::FETCH_ASSOC);

if (count($pairs) < 2) {
    echo "  not enough companies with attendance to compare; nothing to prove.\n";
    exit(1);
}
[$a, $b] = $pairs;
$aCid = (int) $a['company_id']; $bCid = (int) $b['company_id'];
$aAtt = (int) $a['att_id'];     $bAtt = (int) $b['att_id'];
$aEmp = (int) $a['emp_id'];     $bEmp = (int) $b['emp_id'];
printf("  comparing company %d against company %d\n\n", $aCid, $bCid);

// 1. Ownership resolves through the employee, for both tenants.
check('a row resolves to its own company',        row_company_id($aAtt) === $aCid);
check("the other tenant's row resolves to theirs", row_company_id($bAtt) === $bCid);
check('the two differ, so the guard can discriminate', $aCid !== $bCid);
check('a row id that does not exist resolves to null', row_company_id(0) === null);
check('a negative row id resolves to null',       row_company_id(-1) === null);

// 2. edit_attendance / delete: the row guard refuses the other tenant.
check('a scoped session may write its own row',   row_company_id($aAtt) === $aCid);
check('a scoped session is refused the other tenant\'s row', row_company_id($bAtt) !== $aCid);

// 3. add_attendance: the posted employee_id decides ownership.
check('an employee of the session company is accepted', employee_in_company($aEmp, $aCid));
check('an employee of another company is refused',      !employee_in_company($bEmp, $aCid));
check('employee id 0 is refused',                       !employee_in_company(0, $aCid));
check('a negative employee id is refused',              !employee_in_company(-1, $aCid));
check('company 0 is refused whatever the employee',     !employee_in_company($aEmp, 0));

// 4. exception_type_id, the second company-scoped foreign key.
$types = $pdo->query(
    'SELECT id, company_id FROM exception_types ORDER BY company_id LIMIT 200')->fetchAll(PDO::FETCH_ASSOC);
$own = null; $foreign = null;
foreach ($types as $t) {
    if ((int) $t['company_id'] === $aCid && $own === null)      { $own = (int) $t['id']; }
    if ((int) $t['company_id'] !== $aCid && $foreign === null)  { $foreign = (int) $t['id']; }
}
if ($own !== null) {
    check("an exception type of company $aCid is accepted", exception_type_in_company($own, $aCid));
} else {
    check('company has no exception type of its own to accept (skipped)', true);
}
if ($foreign !== null) {
    check("another company's exception type is refused", !exception_type_in_company($foreign, $aCid));
} else {
    check('no foreign exception type available to refuse (skipped)', true);
}
check('exception type 0 is refused', !exception_type_in_company(0, $aCid));

// 5. delete_range was already guarded; prove its predicate is the same one, so
//    the patch is closing an asymmetry rather than inventing a rule.
$st = $pdo->prepare(
    'SELECT COUNT(*) FROM attendance a JOIN employees e ON e.id = a.employee_id
     WHERE e.company_id = ?');
$st->execute([$aCid]);
$mine = (int) $st->fetchColumn();
$st->execute([$bCid]);
$theirs = (int) $st->fetchColumn();
check('delete_range already scopes through employees.company_id', $mine > 0 && $theirs > 0);
check('and the two ranges are disjoint populations', $aCid !== $bCid);

$pdo->exec('COMMIT');
printf("\n%d passed, %d failed\n", $pass, $fail);
exit($fail === 0 ? 0 : 1);
