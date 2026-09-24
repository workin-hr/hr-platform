package com.workin.backend.platformadmin.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

/**
 * R-044's third mitigation: the admin surface's tenant scoping fails the build
 * rather than a review.
 *
 * <p>ADR-0016 ports ~30 company-scoped pages onto a surface whose every session
 * is a platform administrator, and R-044 rates the risk High for one reason:
 * <b>the failure is silent</b>. A page that forgets its company predicate
 * returns data and looks correct, and after cutover there is no PHP to compare
 * it against. The first two mitigations — the administrator's company selection
 * as explicit session state, and scoped stores rather than the API's
 * tenant-scoped services — are design rules that a new page can simply not
 * follow. This is the one that notices.
 *
 * <p><b>Why the rule is not "every query carries a company predicate".</b>
 * That was the shape R-044 sketched, and measuring it against the ported
 * surface showed it to be the wrong invariant: of the SQL statements in these
 * stores that touch a tenant-owned table, most legitimately carry no company
 * predicate. They are the ownership lookups themselves
 * ({@code SELECT company_id FROM x WHERE id = ?}), or writes by row id that are
 * safe precisely <em>because</em> the service resolved and checked that row's
 * company first. A predicate-counting rule would have produced a wall of
 * exemptions and told nobody anything.
 *
 * <p>The invariant that actually holds is D-176's, one level up: <b>a service
 * method that reaches a tenant-owned row on behalf of a session must resolve
 * that row's owning company and compare it against the session.</b> That is
 * what R-046, R-053, R-059 and R-064 were each an instance of in legacy, and
 * what every guard on this surface implements.
 *
 * <h2>Rule one</h2>
 *
 * Taking a {@link DashboardSession} is how a method declares itself
 * tenant-relevant, so every such method must reach a tenant guard — a call to
 * {@code DashboardSession.companyId()}, {@code isScopedToOneCompany()} or
 * {@code DashboardOrgScope.canOpenRow()} — directly or through a helper on the
 * same class. All 60 such methods do today; the rule exists so that the
 * sixty-first cannot quietly not.
 *
 * <p><b>The guard has to be used, not just named.</b> Comments and string
 * literals are stripped before the match, and a guard call whose result is
 * thrown away ({@code session.companyId();} as a statement of its own) does not
 * count — it compares nothing and denies nobody. Both shapes used to satisfy
 * this rule, which means the gate could be laundered by mentioning the thing it
 * asks for.
 *
 * <h2>Rule two</h2>
 *
 * Rule one is opt-in by signature, so on its own a write could evade it by not
 * taking a session at all. Rule two closes that: <b>every public service method
 * that reaches a store method writing a tenant-owned table must take a
 * session</b>, unless that method is named in
 * {@link #DELIBERATELY_CROSS_TENANT} with a reason.
 *
 * <p><b>Per method, because per service was launderable.</b> Rule two used to
 * ask whether the service's source mentioned {@code DashboardSession} anywhere.
 * One guarded method satisfied that for every unguarded write in the same class
 * — and that is not hypothetical: it is how {@code BroadcastAdminService}'s
 * broadcast slipped out of both rules the moment its delete gained a session
 * (D-276). Rule one skipped the broadcast because its own signature took no
 * session, and rule two passed the whole class because the delete's did. The
 * exemption that had documented the gap was, correctly, deleted at the same
 * time, so nothing was left saying it existed.
 *
 * <p>The list is still self-policing: an entry whose method stops writing a
 * tenant-owned table, or starts taking a session, fails the test rather than
 * outliving its reason.
 *
 * <p>Both rules read the source rather than the bytecode, and the vendored
 * schema decides what "tenant-owned" means, exactly as
 * {@code TenantFilterCoverageTest} does for the API's entities: a table's own
 * columns, not a hand-applied marker, so a new table cannot opt itself out by
 * omission.
 */
class AdminTenantGuardCoverageTest {

	private static final Path ADMIN_ROOT =
			Path.of("src/main/java/com/workin/backend/platformadmin");

	/** Rule three walks outward from the admin root, so it needs the whole tree. */
	private static final Path MAIN_ROOT = Path.of("src/main/java/com/workin");

	private static final String VENDORED_SCHEMA = "legacy/mysql_workin.schema.sql";

	/**
	 * This repository's own tables in the same database, which the vendored
	 * schema does not contain.
	 *
	 * <p>Tenant ownership is a property of the database, not of which file
	 * created the table. Eight tables here carry {@code company_id} or
	 * {@code employee_id} -- {@code attendance_devices}, {@code device_agents},
	 * {@code device_punches}, {@code employee_device_identities},
	 * {@code device_assignment_history}, {@code device_malformed_punches},
	 * {@code device_operation_logs} and {@code legacy_refresh_tokens} -- and
	 * until this was read, no rule below could see a write to any of them. The
	 * class javadoc's reason for deriving ownership from columns rather than a
	 * marker ("so a new table cannot opt itself out by omission") is exactly the
	 * reason this directory has to be read too: those eight had opted out by
	 * living in the other file.
	 */
	private static final Path PHASE1_SCHEMA = Path.of("src/main/resources/db/phase1-mysql");

	/**
	 * {@code Service::method} entries that write a tenant-owned table without a
	 * {@link DashboardSession}, and why that is correct rather than an oversight.
	 *
	 * <p>Self-policing: an entry whose method no longer writes a tenant-owned
	 * table, or that starts taking a session, fails this test. A list that cannot
	 * outlive its reason is the only kind worth keeping.
	 *
	 * <p><b>Empty, and that is the point.</b> Its one entry used to be the whole
	 * of {@code BroadcastAdminService}, exempted because a platform broadcast has
	 * no single owning company to compare a session against. That reason covered
	 * the audience and not the target: the broadcast's company comes from the
	 * request, and legacy forces a scoped session's own company and refuses a
	 * different one ({@code pages/notifications/helper.php:328-334}). Both of that
	 * service's write paths now take a session, so no entry is needed — and
	 * because the rule is per method, a future service cannot inherit one
	 * method's guard for another's write.
	 *
	 * <p>Leaving the field here rather than deleting it is deliberate: the rule it
	 * feeds is what the next such method has to argue with, and an empty allowlist
	 * is a stricter starting point than a missing one.
	 */
	private static final Map<String, String> DELIBERATELY_CROSS_TENANT = Map.of();

	/**
	 * Classes that write a tenant-owned table, are reachable from the admin
	 * surface, and that neither rule above can scan -- with the reason each is
	 * correct rather than an oversight.
	 *
	 * <p><b>Why this list exists.</b> Rule one scans {@code *AdminService.java};
	 * rule two scans {@code <X>Store.java} only when {@code <X>AdminService.java}
	 * sits beside it, and both look only under the admin root. So a file could be
	 * invisible to the whole gate by being named something else, or by living
	 * somewhere else -- and ten are. {@code LegacyPlatformAdminCompanyDirectory}
	 * writes {@code branches} from inside the admin root under a name matching
	 * neither pattern; the device stores write seven tables the gate could not
	 * even call tenant-owned. None of them was a hole. All of them were
	 * invisible, which is the part a gate is supposed to make impossible -- and
	 * the list grew from two to five to nine to ten as three review rounds each
	 * found a different reason the gate could not see a writer.
	 *
	 * <p>Self-policing in both directions, like {@link #DELIBERATELY_CROSS_TENANT}:
	 * an entry that stops writing a tenant-owned table, or that becomes
	 * scannable by rule one or rule two, fails this test rather than outliving
	 * its reason. And a new writer that reaches the admin surface fails until
	 * someone writes down why -- which is the whole point, because the answer
	 * for all ten below is good and none of them had been written down.
	 */
	private static final Map<String, String> ACCOUNTED_FOR_OUTSIDE_THE_RULES = Map.ofEntries(
			Map.entry("AttendanceDeviceStore",
					"Two shapes, and the distinction matters. `claim` and `update` -- the writes the "
							+ "admin surface reaches -- take an explicit companyId, and the caller "
							+ "derives it from the row rather than the request: "
							+ "DeviceAdministrationService.setActive reads device.companyId() after "
							+ "requireDevice(deviceId), and .allocate reads branchCompanyId(branchId). "
							+ "What each does with it differs, and the difference is the point: `update` "
							+ "makes it a predicate (`WHERE company_id = ? AND id = ?`, after the same "
							+ "predicate under `FOR UPDATE`), so another company's row is unreachable "
							+ "even with a crafted id, while `claim` is an INSERT -- there is no prior "
							+ "row to scope, and the company is the value written into the new one. "
							+ "Either way the company is server-resolved, which is D-176's invariant, "
							+ "satisfied one layer above the store. `touchSeen`, `recordHandshake`, "
							+ "`recordSelfDescription` and `recordAttlogStamp` are `WHERE id = ?` with no "
							+ "company at all, and they are NOT admin-session writes: their only callers "
							+ "are DeviceAgentIngestService and ZkTecoAdmsService, where the terminal has "
							+ "already authenticated by serial or agent token and that identity resolved "
							+ "the row -- the id is not independently attacker-chosen. This entry has now "
							+ "been corrected twice by review: first for saying 'every write takes an "
							+ "explicit companyId', false of those four, and then for attributing "
							+ "`WHERE company_id = ? AND id = ?` to `claim`, which has no WHERE clause. "
							+ "Both times the error was one sentence generalised across a group of "
							+ "methods; an exemption's SQL shape has to be read off each method."),

			Map.entry("DeviceAgentStore",
					"Four writes, and the admin surface reaches two of them -- which an earlier version "
							+ "of this entry did not say, because it described only one. "
							+ "`setAgentActive(agentId, active)` reaches `setActive`, `WHERE id = ?` with "
							+ "no company predicate, and its audit row resolves the company from the "
							+ "returned row (AdminDeviceActions reads agent.companyId()). "
							+ "`issueAgent(companyId, name)` reaches `create`, an INSERT whose "
							+ "company_id is the *posted* form field: existence-checked in "
							+ "DeviceAdministrationService.issueAgent and then audited as posted. Both "
							+ "are inside the trust model rather than outside it -- "
							+ "AdminDevicesController constructs DashboardSession.admin(...) and is "
							+ "reachable only as a platform administrator, who is cross-company by "
							+ "design (R-044's deliberate exception, R-061), so no company-scoped "
							+ "session reaches either. The other two, `recordContact` and "
							+ "`recordHeartbeat`, are `WHERE id = ?` and are not admin-session writes at "
							+ "all: their caller is the agent API, where the presented token resolved "
							+ "the row."),

			Map.entry("DeviceAssignmentHistoryStore",
					"Append-only history. Its single write takes the companyId its caller already "
							+ "resolved -- from the device row on an update, and from the branch on a "
							+ "claim, where no device row exists yet -- so it records an ownership "
							+ "decision rather than making one."),

			Map.entry("DevicePunchStore",
					"Reached from the admin surface only through a file import, which starts from a "
							+ "device the server resolved: AdminDeviceActions passes the posted id, and "
							+ "DeviceAdministrationService.importAttlog turns it into an AttendanceDevice "
							+ "with requireDevice(deviceId) before DeviceFileImportService sees it. That "
							+ "import reaches TWO writes, not one, and an earlier version of this entry "
							+ "named only the first: `insert(deviceId, companyId, ...)`, which takes the "
							+ "company explicitly, and `adoptUnmatched(companyId, employeeId, pin)` -- "
							+ "`WHERE company_id = ? AND pin = ?` -- which DevicePunchIngestionService "
							+ "calls with the same device.companyId(). Both land in the company the "
							+ "device belongs to, which is not a value the request supplies. The store's "
							+ "third write, `confirmInferredAssignment`, is also company-predicated and "
							+ "is reached only from the tenant API."),

			Map.entry("DeviceMalformedPunchStore",
					"The same file import as DevicePunchStore, and the same answer: "
							+ "`quarantine(deviceId, companyId, rawLines, receivedAt)` takes the company "
							+ "explicitly and writes it as the row's column, and every caller passes "
							+ "`device.companyId()` off an AttendanceDevice already resolved -- on the "
							+ "admin path by `requireDevice(deviceId)` in "
							+ "DeviceAdministrationService.importAttlog. It is an `INSERT IGNORE INTO`, "
							+ "which is why it was missing from this list until the third review round: "
							+ "the write detector knew three verbs and this is a fourth, so the rule "
							+ "reported full coverage of a set it had never looked at. That is recorded "
							+ "here rather than only in the log, because the next reader of this list is "
							+ "entitled to know it was once incomplete and how."),

			Map.entry("EmployeeDeviceIdentityStore",
					"Reachable by class, not by write -- a stronger reason than the one an earlier "
							+ "version of this entry gave. The admin surface's file import reaches only "
							+ "`resolveEmployeeIds`, a read; the write, `bind`, has exactly one caller, "
							+ "DeviceManagementService.bindIdentity, which is the tenant API reached from "
							+ "DeviceManagementController with context.companyId(). No admin-surface path "
							+ "reaches it. And when it is reached, "
							+ "`bind(companyId, employeeId, pin, ...)` takes the company explicitly and "
							+ "every statement carries it: the uniqueness check before writing and the "
							+ "UPDATE are predicated on it (`WHERE company_id = ? AND pin = ?`, "
							+ "`WHERE company_id = ? AND employee_id = ?`), and the INSERT -- having no "
							+ "prior row to predicate on -- writes it as the row's company_id."),

			Map.entry("LegacyCompanyDelete",
					"The platform administrator deleting a whole company: every statement is predicated "
							+ "on the company being deleted -- directly as `WHERE company_id = ?`, or "
							+ "through the owning parent for a child table that has no company column "
							+ "(`WHERE e.company_id = ?` and its siblings) -- and the last one is `DELETE "
							+ "FROM companies WHERE id = ?`, the company row itself. There is no session "
							+ "company to compare any of it against, because the operation's subject IS "
							+ "the company. ADR-0015's typed-name confirmation and its audit row are the "
							+ "controls here, not a tenant predicate."),

			Map.entry("LegacyPayrollBatchStore",
					"The admin surface reaches it only through PayrollAdminService, and its five "
							+ "batch-write paths each take a DashboardSession: createRun, finalizeRun, "
							+ "reopenRun and deleteRun write through this store directly, and calculate "
							+ "writes through LegacyPayrollBatchService's connection-scoped copy of it. "
							+ "Five, not six: `editDetail` writes through PayrollStore, which rule two "
							+ "scans, and `actionsEnabled` and the static helpers write nothing. The "
							+ "store's other holders are LegacyPayslipService and "
							+ "LegacyPayslipWriteCoordinator, which only read it, and "
							+ "LegacyPayrollBatchService, whose own entry points are the legacy API's "
							+ "with its own tenant control -- so `reached only through "
							+ "PayrollAdminService`, which an earlier version of this entry claimed "
							+ "without qualification, is true of the admin surface and of no wider "
							+ "scope. Rule one enforces the guard, one layer "
							+ "above. The store sits outside the admin root because payroll's arithmetic "
							+ "is shared with the legacy API, not because it is unguarded."),

			Map.entry("LegacyPayslipStore",
					"Reachable by class, not by write. The admin surface touches "
							+ "LegacyPayslipService for one method -- enrichRows, a read -- and this "
							+ "closure follows classes rather than methods, so the service's own write "
							+ "path comes with it. Those service writes -- `create`, `update` and "
							+ "`delete` on LegacyPayslipService, the only way to reach the store's "
							+ "`update` and `delete`, which are `WHERE id=?`, and its `insert`, which "
							+ "has no predicate at all -- each take an explicit "
							+ "companyId and belong to the legacy API, which has its own "
							+ "tenant control (TenantFilterCoverageTest, the tenant filter, "
							+ "LegacyTenantContext). No admin-surface path reaches them."),

			Map.entry("LegacyPlatformAdminCompanyDirectory",
					"create() makes a company and its first branch, so there is no prior owner to "
							+ "compare a session against. update() writes the main branch two ways and "
							+ "both are safe for a reason visible in the method itself, not in the class "
							+ "javadoc (which is about not mutating the entity, and says nothing about "
							+ "branches): its `SELECT id FROM branches WHERE company_id = ? ORDER BY id "
							+ "ASC LIMIT 1` resolves the id inside the company being edited, so the "
							+ "following `UPDATE branches ... WHERE id = ?` cannot reach another "
							+ "company's row, and when that select finds nothing the "
							+ "`INSERT INTO branches (company_id, ...)` beside it writes the same "
							+ "resolved company as a column. (An earlier version of this sentence "
							+ "called that INSERT the else-branch; it is the if-branch, and a reader "
							+ "who checks a claim against the method should find the method.)"));

	/**
	 * The write detector sees every verb this repository writes with.
	 *
	 * <p>Not a style test. Rule three's claim is exhaustive, and a verb it
	 * cannot see makes a writer neither found nor unaccounted for -- invisible
	 * to both directions of the ratchet. This is the assertion that was missing
	 * when {@code INSERT IGNORE INTO} and {@code DELETE a FROM} were, so the
	 * next narrowing of the pattern fails here instead of going quiet.
	 */
	@Test
	void theWriteDetectorSeesEveryVerbThisRepositoryUses() {
		Set<String> tables = Set.of("attendance", "device_malformed_punches", "employees", "notifications");
		Map<String, String> noEntities = Map.of();

		assertThat(writtenTenantTables(
				"jdbc.update(\"INSERT INTO employees (company_id) VALUES (?)\");", tables, noEntities))
				.containsExactly("employees");
		assertThat(writtenTenantTables(
				"jdbc.update(\"INSERT IGNORE INTO device_malformed_punches (company_id) VALUES (?)\");",
				tables, noEntities))
				.as("the device ingest idiom").containsExactly("device_malformed_punches");
		assertThat(writtenTenantTables(
				"jdbc.update(\"REPLACE INTO employees (id) VALUES (?)\");", tables, noEntities))
				.containsExactly("employees");
		assertThat(writtenTenantTables(
				"jdbc.update(\"UPDATE employees SET is_active = 0 WHERE id = ?\");", tables, noEntities))
				.containsExactly("employees");
		assertThat(writtenTenantTables(
				"jdbc.update(\"DELETE FROM notifications WHERE company_id = ?\");", tables, noEntities))
				.containsExactly("notifications");
		assertThat(writtenTenantTables(
				"jdbc.update(\"DELETE a FROM attendance a JOIN employees e ON e.id = a.employee_id\");",
				tables, noEntities))
				.as("MySQL's multi-table delete puts an alias between the verb and the table")
				.contains("attendance");

		assertThat(writtenTenantTables(
				"jdbc.query(\"SELECT * FROM employees WHERE company_id = ?\");", tables, noEntities))
				.as("a read is not a write").isEmpty();
		assertThat(writtenTenantTables(
				"jdbc.update(\"DELETE FROM sessions WHERE id = ?\");", tables, noEntities))
				.as("a table the schema does not call tenant-owned").isEmpty();
	}

	/**
	 * A write verb wearing a modifier is still a write.
	 *
	 * <p>{@code INSERT IGNORE} was the third round's finding and was fixed as
	 * itself. MariaDB allows that family on the other verbs too, and the pattern
	 * then read the modifier <em>as the table</em>: {@code UPDATE IGNORE employees}
	 * captured {@code IGNORE}, a name in no ground truth, so the statement was
	 * seen as a write to nothing -- neither found nor unaccounted for.
	 *
	 * <p>None of these is in the repository today, which is why this is a test and
	 * not a measurement: there is nothing to measure until somebody writes one,
	 * and by then the gate has already said the file was clean.
	 */
	@Test
	void aWriteVerbWearingAModifierIsStillAWrite() {
		Set<String> tables = Set.of("employees", "notifications");
		Map<String, String> noEntities = Map.of();

		assertThat(writtenTenantTables(
				"jdbc.update(\"UPDATE IGNORE employees SET is_active = 0 WHERE id = ?\");",
				tables, noEntities))
				.as("one keyword away from INSERT IGNORE, which this repository does use")
				.containsExactly("employees");
		assertThat(writtenTenantTables(
				"jdbc.update(\"UPDATE LOW_PRIORITY IGNORE employees SET is_active = 0\");",
				tables, noEntities))
				.as("both of UPDATE's modifiers at once").containsExactly("employees");
		assertThat(writtenTenantTables(
				"jdbc.update(\"DELETE LOW_PRIORITY QUICK FROM notifications WHERE id = ?\");",
				tables, noEntities))
				.as("the pattern allowed exactly one word between DELETE and FROM -- room for an "
						+ "alias, but not for two modifiers")
				.containsExactly("notifications");
		assertThat(writtenTenantTables(
				"jdbc.update(\"INSERT HIGH_PRIORITY INTO employees (company_id) VALUES (?)\");",
				tables, noEntities))
				.containsExactly("employees");
		assertThat(writtenTenantTables(
				"jdbc.update(\"DELETE a FROM notifications a JOIN employees e ON e.id = a.employee_id\");",
				tables, noEntities))
				.as("an alias is not a modifier, and still reads as one table")
				.containsExactly("notifications");

		assertThat(writtenTenantTables(
				"jdbc.update(\"UPDATE ignored_signals SET seen = 1 WHERE id = ?\");",
				Set.of("ignored_signals"), noEntities))
				.as("a table whose name merely begins with a modifier is a table")
				.containsExactly("ignored_signals");
	}

	/**
	 * A schema-qualified write names its table, not its schema.
	 *
	 * <p>Nothing here qualifies a table name today. The capture used to take the
	 * first word after the verb, so {@code workin.employees} read as a write to
	 * {@code workin} -- again a name in no ground truth, and again silence in both
	 * directions rather than a failure.
	 */
	@Test
	void aSchemaQualifiedWriteNamesItsTable() {
		Set<String> tables = Set.of("employees");
		Map<String, String> noEntities = Map.of();

		assertThat(writtenTenantTables(
				"jdbc.update(\"DELETE FROM workin.employees WHERE id = ?\");", tables, noEntities))
				.containsExactly("employees");
		assertThat(writtenTenantTables(
				"jdbc.update(\"UPDATE `workin`.`employees` SET is_active = 0\");", tables, noEntities))
				.as("backticked on both halves, which is how a dump writes it")
				.containsExactly("employees");
		assertThat(writtenTenantTables(
				"jdbc.update(\"INSERT INTO workin.employees (company_id) VALUES (?)\");",
				tables, noEntities))
				.containsExactly("employees");

		assertThat(writtenTenantTables(
				"-- the caller already resolved the company, so this can safely update\n"
						+ "\t\tDELETE FROM employees WHERE id = ?",
				tables, noEntities))
				.as("nor a prose word that is itself a write verb, with no punctuation at all -- this is "
						+ "the general shape, and the full stop above was only one instance of it")
				.containsExactly("employees");

		assertThat(writtenTenantTables(
				"-- see the note above about the payslip update\n"
						+ "\t\tUPDATE employees SET x = 1",
				tables, noEntities))
				.as("the same when the statement below is an UPDATE")
				.containsExactly("employees");

		assertThat(writtenTenantTables(
				"-- rows to delete\n\t\tDELETE FROM employees WHERE id = ?",
				tables, noEntities))
				.as("DELETE's alias slot does consume the statement's own verb here, and the "
						+ "capture is still its table -- the invariant is that no match ends at or "
						+ "past a table, not that no match eats a verb")
				.containsExactly("employees");

		assertThat(writtenTenantTables(
				"jdbc.update(\"DELETE FROM Employees WHERE id = ?\");", tables, noEntities))
				.as("the scanner is case-insensitive and the schema readers canonicalise, so the "
						+ "two sides must be compared canonically -- a table declared one way and "
						+ "written another is the same table")
				.containsExactly("employees");

		assertThat(writtenTenantTables(
				"-- resolve the row first. Then update it.\n"
						+ "\t\tDELETE FROM employees WHERE id = ?",
				tables, noEntities))
				.as("a comment sentence's full stop must not swallow the verb on the line below it")
				.containsExactly("employees");

		assertThat(writtenTenantTables(
				"/** The value the dynamic UPDATE binds. The keys were written back. */\n"
						+ "\t\tjdbc.update(\"DELETE FROM employees WHERE id = ?\");",
				Set.of("the", "employees"), noEntities))
				.as("the price of a recogniser that reads whole files, stated rather than "
						+ "discovered: a sentence can parse as a write, which costs an exemption "
						+ "for a file that writes nothing -- and because the tail is a lookahead, "
						+ "the wrong capture is ADDED to the real one instead of hiding it")
				.containsExactly("employees", "the");
	}

	/**
	 * No tenant-owned table is named like a statement modifier, because one would
	 * be invisible.
	 *
	 * <p>{@link #STATEMENT_MODIFIERS} is one union applied to all four verbs, so
	 * {@code UPDATE delayed SET x = 1} reads {@code delayed} as a modifier and
	 * captures {@code SET} as the table. A name that merely <em>begins</em> with a
	 * modifier is fine -- {@code ignored_signals} is asserted above -- but a name
	 * that <em>is</em> one is lost.
	 *
	 * <p>That is a consequence of the one union, not of SQL being ambiguous, and the
	 * ninth round corrected this paragraph on exactly that point: MariaDB's
	 * {@code UPDATE} takes only {@code LOW_PRIORITY} and {@code IGNORE}, so
	 * {@code UPDATE delayed ...}, {@code UPDATE quick ...} and
	 * {@code UPDATE high_priority ...} are unambiguous statements naming that table,
	 * and a per-verb modifier set would read them correctly. One union is still the
	 * right trade -- five names forbidden is cheaper to keep true than four grammars
	 * kept in step with MariaDB's -- but the cost is this assertion, not an
	 * inherent ambiguity.
	 *
	 * <p>So the bound is checked rather than described. No table in either schema
	 * half is named that way today, and a schema that added one would fail here
	 * instead of quietly leaving its writes unseen. Case is not a way round it:
	 * both schema readers lower-case the names they collect, because the scanner is
	 * case-insensitive and a {@code CREATE TABLE Delayed} would otherwise pass this
	 * assertion and still be read as a modifier.
	 */
	@Test
	void noTenantOwnedTableIsNamedLikeAStatementModifier() {
		Set<String> modifiers = Set.of("low_priority", "high_priority", "delayed", "quick", "ignore");
		assertThat(tenantOwnedTables())
				.as("a table named exactly like a modifier is read as the modifier, and the word "
						+ "after it is captured instead -- so the write reads as a write to nothing")
				.doesNotContainAnyElementsOf(modifiers);

		// A JPQL write names the ENTITY, in the same slot, so an entity called
		// `Delayed` is lost the same way -- raised by the tenth round, and narrow
		// rather than theoretical: it needs a `@Modifying` write outside a
		// repository interface, which this repository does write through
		// EntityManager elsewhere.
		assertThat(entityTables().keySet().stream().map(AdminTenantGuardCoverageTest::canonical).toList())
				.as("nor an entity named like one, because a JPQL write puts the entity name where "
						+ "the table name goes")
				.doesNotContainAnyElementsOf(modifiers);
	}

	/**
	 * A JPQL write is a write to the entity's table.
	 *
	 * <p>{@code @Modifying @Query("update LegacyEmployee e set ...")} lands in
	 * {@code employees}. Without the translation the pattern matches, compares
	 * "LegacyEmployee" against table names, and reports nothing written -- a
	 * second whole category of write that the rule's exhaustive claim did not
	 * cover. No such writer is reachable from the admin surface today, which is
	 * exactly why this needs a test: there is nothing else to notice if it stops
	 * working.
	 */
	@Test
	void aJpqlWriteIsReadAsAWriteToTheEntitysTable() {
		Set<String> tables = Set.of("employees", "legacy_refresh_tokens");
		Map<String, String> entities = Map.of(
				"LegacyEmployee", "employees", "LegacyRefreshToken", "legacy_refresh_tokens");

		assertThat(writtenTenantTables(
				"@Query(\"update LegacyEmployee e set e.tokenVersion = e.tokenVersion + 1 where e.id = :id\")",
				tables, entities))
				.containsExactly("employees");
		assertThat(writtenTenantTables(
				"@Query(\"DELETE FROM LegacyRefreshToken t WHERE t.employeeId = :id\")", tables, entities))
				.containsExactly("legacy_refresh_tokens");
		assertThat(writtenTenantTables(
				"@Query(\"update LegacyEmployee e set e.x = 1\")", tables, Map.of()))
				.as("without the map the same text reads as no write, which is the bug this closes")
				.isEmpty();

		assertThat(writtenTenantTables(
				"entityManager.createQuery(\"update com.workin.backend.hr.LegacyEmployee e"
						+ " set e.x = 1\");",
				tables, entities))
				.as("JPQL permits the entity's fully-qualified name, and a one-segment schema "
						+ "prefix read com.workin.backend.hr.LegacyEmployee as a write to `workin` "
						+ "-- which is no table, so the write vanished")
				.containsExactly("employees");
		assertThat(writtenTenantTables(
				"jdbc.update(\"DELETE FROM a.b.c.employees WHERE id = ?\");", tables, entities))
				.as("and the same prefix now spans however many segments it is given")
				.containsExactly("employees");
	}

	/** The entity-to-table map is read from the sources, not hard-coded. */
	@Test
	void theEntityTableMapComesFromTheEntitiesThemselves() {
		assertThat(entityTables())
				.as("every entity declares its table explicitly")
				.containsEntry("LegacyEmployee", "employees")
				.containsEntry("LegacyCompany", "companies")
				.containsEntry("LegacyRefreshToken", "legacy_refresh_tokens")
				.containsEntry("PlatformAdminAuditEvent", "platform_admin_audit_events");
		// On the RAW capture, not on the map: entityTables() canonicalises its values,
		// so asserting the same property there can no longer fail -- which the tenth
		// round caught, because this commit is what made it unfalsifiable.
		classesByName().forEach((name, path) -> {
			String declared = tableNameOf(read(path));
			if (declared == null) {
				return;
			}
			assertThat(declared)
					.as("%s declares @Table(name = \"%s\") -- a table name, never a class name",
							name, declared)
					.isEqualTo(canonical(declared));
		});
	}

	/**
	 * A repository's write has no statement to find, so the entity it is declared
	 * over is what names the table.
	 *
	 * <p>The shape this pins is a derived delete -- a method with a body nowhere
	 * in the source -- but the rule is deliberately broader than that: inheriting
	 * {@code save} is enough, because a service injects the repository in order
	 * to use it.
	 */
	@Test
	void aRepositoryWriteWithNoStatementIsSeenThroughItsEntity() {
		Set<String> tables = Set.of("branches", "employees");
		Map<String, String> entities = Map.of("LegacyBranch", "branches", "PlatformAdmin", "platform_admins");

		assertThat(writtenTenantTables("""
				public interface LegacyBranchRepository extends JpaRepository<LegacyBranch, Long> {
					void deleteByIdAndCompanyId(Long id, Long companyId);
				}""", tables, entities))
				.as("a derived delete has no SQL text at all").containsExactly("branches");

		assertThat(writtenTenantTables("""
				public interface LegacyBranchRepository extends JpaRepository<LegacyBranch, Long> {
					java.util.Optional<LegacyBranch> findById(Long id);
				}""", tables, entities))
				.as("inheriting save() is enough; a read-only repository still counts, "
						+ "which over-approximates in the safe direction")
				.containsExactly("branches");

		assertThat(writtenTenantTables("""
				public interface PlatformAdminRepository extends JpaRepository<PlatformAdmin, Long> {
					void deleteById(Long id);
				}""", tables, entities))
				.as("a repository over a table the schema does not call tenant-owned").isEmpty();

		assertThat(writtenTenantTables("""
				public interface Thing extends JpaRepository<UnknownEntity, Long> {
				}""", tables, entities))
				.as("an entity the map does not know names no table").isEmpty();
	}

	/**
	 * {@code @Table}'s {@code name} is found wherever in the annotation it sits.
	 *
	 * <p>Coverage of today's entities cannot see this: all twelve write
	 * {@code name} first, so narrowing the pattern back to "first attribute only"
	 * leaves every other assertion green. The shapes below are the ones a future
	 * entity is most likely to be written in -- an index or a unique constraint
	 * declared before the name -- and they are the ones that would silently drop
	 * an entity out of the map.
	 */
	@Test
	void theTableNameIsFoundWhereverItSitsInTheAnnotation() {
		assertThat(tableNameOf("@Entity\n@Table(name = \"widgets\")\npublic class W {"))
				.isEqualTo("widgets");
		assertThat(tableNameOf(
				"@Entity\n@Table(name = \"widgets\", indexes = @Index(columnList = \"a\"))\npublic class W {"))
				.isEqualTo("widgets");
		assertThat(tableNameOf(
				"@Entity\n@Table(indexes = @Index(columnList = \"a\"), name = \"widgets\")\npublic class W {"))
				.as("an index declared before the name").isEqualTo("widgets");
		assertThat(tableNameOf(
				"@Entity\n@Table(schema = \"db\", name = \"widgets\")\npublic class W {"))
				.as("a schema declared before the name").isEqualTo("widgets");
		assertThat(tableNameOf("@Entity\n@Table(uniqueConstraints = @UniqueConstraint(columnNames = "
				+ "{\"a\", \"b\"}), name = \"widgets\")\npublic class W {"))
				.as("a nested annotation before the name -- the shape that defeats a naive [^)] scan")
				.isEqualTo("widgets");
		assertThat(tableNameOf("@Entity\n@Table(indexes = @Index(columnList = \"a\"))\n"
				+ "public class W {\n\tprivate String name = \"nonsense\";"))
				.as("and the scan must not run past the annotation into a field called name")
				.isNull();
		assertThat(tableNameOf("@Entity\npublic class W {"))
				.as("an entity with no @Table declares no name here").isNull();
		assertThat(tableNameOf("@Table(name = \"widgets\")\npublic class NotAnEntity {"))
				.as("@Table without @Entity is not an entity").isNull();
	}

	private static String tableNameOf(String source) {
		Matcher table = ENTITY_TABLE.matcher(source);
		return table.find() ? table.group(1) : null;
	}

	/**
	 * Every entity is in the map, so a {@code @Table} written a different way
	 * cannot drop one silently.
	 *
	 * <p>{@link #ENTITY_TABLE} reads {@code name} out of the annotation, and its
	 * first version required {@code name} to be the annotation's *first*
	 * attribute -- so {@code @Table(indexes = {...}, name = "employees")} would
	 * have left {@code LegacyEmployee} out of the map, and every JPQL write to it
	 * would have read as no write. Nothing would have failed: the map's four
	 * spot-checked entries were the only thing asserted. Coverage is the
	 * assertion that makes the pattern's shape self-policing.
	 */
	@Test
	void everyEntityIsInTheEntityTableMap() {
		Map<String, String> tables = entityTables();
		List<String> entities = classesByName().entrySet().stream()
				.filter(entry -> ENTITY_DECLARATION.matcher(read(entry.getValue())).find())
				.map(Map.Entry::getKey)
				.toList();

		assertThat(entities).as("the entities must be findable, or this passes vacuously")
				.hasSizeGreaterThanOrEqualTo(10);
		assertThat(tables.keySet())
				.as("every @Entity class is in the map: %s", entities)
				.containsAll(entities);
		// The javadoc's other claim, which nothing asserted: the entity name is
		// the class name, so keying the map on the file stem is sound.
		assertThat(entities).allSatisfy(name -> assertThat(read(classesByName().get(name)))
				.as("%s must not rename its entity, or the map's key is wrong", name)
				.doesNotContain("@Entity("));
	}

	/**
	 * The production entry point uses the real entity map, not an empty one.
	 *
	 * <p>Both synthetic tests above pass their own map in, so the line that wires
	 * {@code entityTables()} into the scan was covered by nothing: replacing it
	 * with {@code Map.of()} disconnected this commit's whole feature and left
	 * every test green, because no reachable unscanned class has a JPQL write
	 * today. This asserts the join on a real file.
	 */
	@Test
	void theProductionScanUsesTheRealEntityMap() {
		Path repository = Path.of("src/main/java/com/workin/legacy/auth/LegacyRefreshTokenRepository.java");
		assertThat(repository).exists();
		assertThat(writtenTenantTables(repository, tenantOwnedTables()))
				.as("three @Modifying JPQL updates on LegacyRefreshToken, over a tenant-owned table")
				.contains("legacy_refresh_tokens");
	}

	/**
	 * What the detector still cannot see, asserted so that it is a known limit
	 * rather than a discovery.
	 *
	 * <p>A statement assembled around a variable table name has no table name in
	 * its text. {@code LegacyCompanyDelete} writes <b>32</b> tables that way --
	 * the four cascade lists it loops over -- and is on the exemption list for
	 * its own reasons, so nothing is hidden today --
	 * but a store whose <em>only</em> writes were of this shape would be
	 * invisible to rule three, and that is a sentence this gate should say out
	 * loud rather than leave for the next review round to find.
	 */
	@Test
	void aTableNameBuiltFromAVariableIsNotSeenAndThatIsRecorded() {
		assertThat(writtenTenantTables(
				"jdbc.update(\"DELETE FROM \" + table + \" WHERE company_id = ?\", companyId);",
				Set.of("employees"), Map.of()))
				.as("a dynamic table name is invisible to a text scan -- the documented limit")
				.isEmpty();
	}

	/**
	 * A table owned only through a non-employee parent is not in the ground
	 * truth, so no rule looks at a write to it.
	 *
	 * <p>Asserted rather than left to be found. {@code department_branches} is
	 * company-owned through {@code departments} and carries neither tenant
	 * column, so {@code DepartmentStore.syncBranches}'s two statements are
	 * invisible to this gate -- harmless today, because that store also writes
	 * {@code departments} and its service takes a session, and a stated bound
	 * rather than a fifth review round's discovery.
	 *
	 * <p>Widening the ground truth to follow a foreign key into a tenant-owned
	 * parent is tracked as #334; the {@code doesNotContain} below is what makes
	 * that a deliberate change rather than a silent one.
	 */
	@Test
	void aTableOwnedOnlyThroughItsParentIsNotInTheGroundTruth() {
		Set<String> tables = tenantOwnedTables();
		assertThat(tables).as("the parent is tenant-owned").contains("departments", "company_settings");
		assertThat(tables)
				.as("the link tables are not, because they carry neither tenant column")
				.doesNotContain("department_branches", "company_setting_values");

		assertThat(writtenTenantTables(
				"jdbc.update(\"DELETE FROM department_branches WHERE department_id = ?\", id);",
				tables, entityTables()))
				.as("so a write to one is seen by nothing").isEmpty();
	}

	/** A call that resolves or enforces the session's company. */
	private static final Pattern TENANT_GUARD = Pattern.compile(
			"\\bcompanyId\\s*\\(\\s*\\)|\\bisScopedToOneCompany\\s*\\(|\\bcanOpenRow\\s*\\(");

	private static final Pattern PUBLIC_METHOD = Pattern.compile(
			"public\\s+[\\w.<>,?\\[\\]\\s]+?\\s(\\w+)\\s*\\(([^)]*)\\)\\s*\\{", Pattern.DOTALL);

	/**
	 * Any method declaration, for following a call into a helper on the same class.
	 *
	 * <p>The return-type class accepts {@code ?} as well: without it
	 * {@code public List<? extends Row> rows()} matches neither pattern, so rule one
	 * would not count it as session-taking and rule two would not count it as a write
	 * path. No such signature exists under an admin service today -- the twelfth round
	 * checked -- which is why one character was the whole fix.
	 */
	private static final Pattern ANY_METHOD = Pattern.compile(
			"(?:public|private|protected|static)\\s+[\\w.<>,?\\[\\]\\s]+?\\s(\\w+)\\s*\\(([^)]*)\\)\\s*\\{",
			Pattern.DOTALL);

	private static final Pattern CREATE_TABLE = Pattern.compile(
			"CREATE TABLE `(\\w+)` \\((.*?)\\n\\)\\s*ENGINE", Pattern.DOTALL);

	/**
	 * The Phase 1 files' own shape: unquoted names, an optional
	 * {@code IF NOT EXISTS}, and a statement that ends at the semicolon rather
	 * than at {@code ENGINE}.
	 */
	private static final Pattern CREATE_TABLE_PHASE1 = Pattern.compile(
			"CREATE TABLE (?:IF NOT EXISTS )?`?(\\w+)`?\\s*\\((.*?)\\n\\)[^;]*;", Pattern.DOTALL);

	/** A column declaration in the Phase 1 files, which do not backtick names. */
	private static final Pattern COLUMN_NAME_PHASE1 = Pattern.compile("(?m)^\\s*`?(\\w+)`?\\s+\\w");

	private static final Pattern COLUMN_NAME = Pattern.compile("(?m)^\\s*`(\\w+)`");

	private static final Pattern IMPORTED_CLASS =
			Pattern.compile("(?m)^import\\s+com\\.workin\\.[\\w.]+\\.(\\w+);");

	/** A bare capitalised identifier, resolved only against the file's own package. */
	private static final Pattern BARE_CLASS = Pattern.compile("\\b([A-Z]\\w+)\\b");

	/** This repository uses fully-qualified names inline a great deal, so both shapes count. */
	private static final Pattern QUALIFIED_CLASS =
			Pattern.compile("\\bcom\\.workin\\.[\\w.]+\\.([A-Z]\\w+)\\b");

	/**
	 * MariaDB's optional statement modifiers, which sit between the verb and the
	 * rest of the statement.
	 *
	 * <p>One union for all four verbs, which accepts a handful of combinations SQL
	 * does not ({@code UPDATE QUICK}). That is the right direction to err: this
	 * decides whether a file is looked at, so accepting too much costs an
	 * exemption somebody has to write, and accepting too little hides a writer
	 * from both halves of the ratchet. {@code INSERT IGNORE} was the third round's
	 * finding and was fixed as itself rather than as a class; the seventh round
	 * asked for the rest of the family and found {@code UPDATE IGNORE} -- one
	 * keyword away from an idiom this repository already uses -- invisible.
	 */
	private static final String STATEMENT_MODIFIERS =
			"(?:\\s+(?:LOW_PRIORITY|HIGH_PRIORITY|DELAYED|QUICK|IGNORE))*";

	/**
	 * Any number of {@code schema.}, {@code `schema`.} or {@code schema . }
	 * segments before the table name.
	 *
	 * <p>Every statement here is unqualified today. Without this the capture
	 * stopped at the schema name, which is in no ground truth, so a qualified write
	 * was read as a write to nothing -- invisible, not over-approximated.
	 *
	 * <p>It takes <em>any</em> number of segments rather than one because SQL is not
	 * the only dialect scanned here: JPQL permits an entity's fully-qualified name,
	 * and with one segment {@code update com.workin.backend.hr.LegacyEmployee} read
	 * as a write to {@code workin} -- the one segment was spent on {@code com.} and
	 * the capture landed on the next word -- which is in no ground truth, so the
	 * write vanished. That is this comment's own first paragraph happening again one
	 * dot further along. The eleventh round found it with no instance in the tree;
	 * making the prefix repeatable changes nothing measured -- all 797 classes in
	 * both source trees yield identical per-file <em>tenant-owned table</em> sets
	 * either way -- and it is safe for the same reason the spaced dot is, below.
	 *
	 * <p>It accepts whitespace around the dot on purpose, and the reason is
	 * {@link #WRITE_STATEMENT}'s lookahead rather than anything about SQL: a wider
	 * prefix cannot hide a statement below the match, because no match consumes what
	 * follows it. So the question "does MariaDB accept {@code workin . employees}?"
	 * stops mattering -- if it does, the write is seen; if it does not, nothing was
	 * written that way to miss. Deciding it the other way round is what made the
	 * spaced form invisible between the eighth and ninth rounds.
	 *
	 * <p>Not "additive", which an earlier draft of this paragraph claimed and the
	 * tenth round corrected: the prefix is greedy and a match has one capture, so a
	 * wider prefix <em>replaces</em> the captured word rather than adding to it.
	 * Measured over {@code src/main/java}, which is the only tree this gate reads,
	 * that changes three captures, all of them prose and none of them a tenant-owned
	 * table ({@code entirely}&rarr;{@code on},
	 * {@code binds}&rarr;{@code the}, {@code carried}&rarr;{@code return}). What the
	 * lookahead guarantees is the part that matters -- a wrong capture cannot cost a
	 * later statement its own.
	 *
	 * <p>Because this scan reads whole files rather than stripped ones, prose can
	 * still match: {@code "the dynamic UPDATE binds. The normalised keys"} parses as
	 * a write to {@code the}. That costs an exemption for a file that writes
	 * nothing, and what makes it harmless is not that the real capture survives
	 * alongside it -- the paragraph above says it does not, and this one said the
	 * opposite until self-review caught the two sitting one apart. It is harmless
	 * because the <em>match</em> ends at the verb: the prose capture belongs to a
	 * match that consumed no table, so the next statement is still scanned from its
	 * own verb and still names its own table.
	 * {@link #aSchemaQualifiedWriteNamesItsTable} asserts exactly that.
	 *
	 * <p>Stripping comments first would be the tighter fix and a riskier one: a
	 * stripper that mistakes {@code //} inside a string literal for a comment
	 * deletes real SQL, which fails in the direction this gate exists to prevent.
	 */
	private static final String SCHEMA_PREFIX = "(?:`?\\w+`?\\s*\\.\\s*)*";

	/**
	 * Every write verb this repository actually uses, which is more than the
	 * three a reader assumes.
	 *
	 * <p>It began as {@code INSERT INTO|UPDATE|DELETE FROM} and that was wrong
	 * in two ways at once, both found by review. {@code INSERT IGNORE INTO} is
	 * this repository's idiom for de-duplicated device ingest
	 * ({@code DeviceMalformedPunchStore}, {@code DeviceOperationLogStore}), and
	 * MySQL's multi-table delete puts an alias between the verb and the table
	 * ({@code DELETE a FROM attendance a JOIN ...}) -- eight files use one or
	 * the other. A writer the verb set cannot see is worse than one the closure
	 * cannot reach, because it is neither <em>found</em> nor <em>unaccounted
	 * for</em>: no direction of the ratchet below can notice it, and the gate
	 * reports full coverage of a set it silently never looked at.
	 *
	 * <p>SQL text is not the only way this repository writes, and the other two
	 * ways are handled rather than ignored: a JPQL {@code @Modifying @Query}
	 * names an entity ({@link #entityTables()}), and a Spring Data repository
	 * writes its entity's table with no statement written down at all
	 * ({@link #JPA_REPOSITORY}).
	 *
	 * <p><b>The table is matched in a lookahead, so no match consumes it.</b> This
	 * is the one thing in this pattern that is about safety rather than coverage,
	 * and two rounds were spent on it. With a consuming tail, {@link Matcher#find()}
	 * resumes past the captured token -- so whenever the word immediately before a
	 * statement's verb was itself a write verb, prose included, that statement's
	 * <em>verb</em> was captured as the table name and eaten, and its real table was
	 * never examined:
	 *
	 * <pre>
	 * -- the caller already resolved the company, so this can safely update
	 * DELETE FROM employees WHERE id = ?        -- yielded [DELETE], never employees
	 * </pre>
	 *
	 * The file then writes no tenant-owned table as far as every rule below is
	 * concerned: neither <em>found</em> nor <em>unaccounted for</em>. Nine comment
	 * lines under {@code com.workin} already end in a write verb, and this
	 * repository's house style puts {@code --} prose directly above the statement
	 * inside a SQL text block. The eighth round found the variant where a full stop
	 * supplied the separator and the fix closed only that one; the ninth round found
	 * that the separator was never the point. A lookahead ends the match at the
	 * verb, so a wrong capture is additive and can hide nothing.
	 *
	 * <p>That is a claim about the <em>class</em> and not about these inputs, so here
	 * is the invariant rather than a sample: <b>no match can end at or past a
	 * statement's table</b>, because the table is only ever read in the lookahead.
	 * Every capture is therefore the table of some statement beginning at or after
	 * the match's own start, and no statement can be skipped over.
	 *
	 * <p><b>One alternative is the exception, and it is the modifier union.</b>
	 * {@code UPDATE delayed SET x = 1} matches {@code "UPDATE delayed"} and captures
	 * {@code SET}: {@link #STATEMENT_MODIFIERS} read the table, not the lookahead, so
	 * that match does end past its own statement's table. It costs that statement and
	 * nothing after it -- {@code UPDATE delayed SET x = 1; DELETE FROM employees}
	 * still yields {@code employees} -- and the only thing holding it to that is
	 * {@link #noTenantOwnedTableIsNamedLikeAStatementModifier}, four hundred lines
	 * away. Anyone widening the union, which that test's javadoc contemplates, is
	 * widening this exception with it.
	 *
	 * <p>It is stated that way because the obvious stronger claim -- that a match
	 * never consumes another statement's verb -- is false, and the tenth round caught
	 * it here. {@code DELETE}'s alias slot takes one word before {@code FROM}, and a
	 * comment ending in {@code delete} supplies it:
	 *
	 * <pre>
	 * -- rows to delete
	 * DELETE FROM employees WHERE id = ?      -- one match, consuming BOTH deletes
	 * </pre>
	 *
	 * That match spans the prose verb, the statement's verb and its {@code FROM} --
	 * and captures {@code employees} anyway, because after the consumed {@code FROM}
	 * the next token is that statement's own table. Nothing is lost, which is the
	 * invariant above doing its work; the reasoning that said this could not happen
	 * was simply wrong, and a reader widening the alias slot later needs the true
	 * reason rather than the comfortable one.
	 *
	 * <p>Two shapes are still invisible <em>and asserted here</em>, the second
	 * being a limit of what "tenant-owned" means rather than a detection gap.
	 * They are not the whole list: issue #334 records the shapes review has named
	 * with no instance to assert against -- {@code TRUNCATE TABLE}, a multi-table
	 * {@code DELETE t1, t2 FROM}, an {@code UPDATE a JOIN b SET}, a tenant column
	 * added by a later {@code ALTER TABLE}, and a repository reaching
	 * {@code JpaRepository} through an intermediate interface. Read this as the two
	 * bounds with a live example, not as a closed enumeration.
	 *
	 * <p><b>A variable table name</b> ({@code "DELETE FROM " + table}) has no
	 * table name in its text.
	 *
	 * <p><b>A table owned only through a non-employee parent</b> carries neither
	 * {@code company_id} nor {@code employee_id}, so the ground truth does not
	 * call it tenant-owned at all and no rule looks at a write to it.
	 * {@code department_branches} (owned through {@code departments}) and
	 * {@code company_setting_values} (through {@code company_settings}) are the
	 * live examples, and {@code DepartmentStore.syncBranches} writes the first
	 * with no company predicate. Nothing is hidden today -- that store also
	 * writes {@code departments}, and every {@code DepartmentAdminService} method
	 * reaching it takes a session, so rule one covers it -- but a future store
	 * whose <em>only</em> write were to a link table of that shape would be
	 * invisible to all three rules and to the ratchet. Widening the ground truth
	 * to follow a foreign key into a tenant-owned parent is a change to what this
	 * gate covers rather than to how it looks, so it is tracked separately rather
	 * than folded in here.
	 */
	private static final Pattern WRITE_STATEMENT = Pattern.compile(
			"\\b(?:INSERT" + STATEMENT_MODIFIERS + "\\s+INTO"
					+ "|REPLACE" + STATEMENT_MODIFIERS + "\\s+INTO"
					+ "|UPDATE" + STATEMENT_MODIFIERS
					+ "|DELETE" + STATEMENT_MODIFIERS + "(?:\\s+\\w+)?\\s+FROM)"
					+ "(?=\\s+" + SCHEMA_PREFIX + "`?(\\w+)`?)", Pattern.CASE_INSENSITIVE);

	/** An {@code @Entity} declaration, at the start of a line so a mention in prose is not one. */
	private static final Pattern ENTITY_DECLARATION = Pattern.compile("(?m)^@Entity\\b");

	/**
	 * A Spring Data repository, and the entity it is declared over.
	 *
	 * <p>The third blind spot review found in this rule, and the one with no SQL
	 * text to look for at all:
	 * {@code interface LegacyBranchRepository extends JpaRepository<LegacyBranch, Long>}
	 * can write {@code branches} through {@code save}, {@code delete}, or a
	 * derived {@code deleteByIdAndCompanyId} whose statement is never written
	 * down anywhere. Two such derived deletes exist here today.
	 *
	 * <p>Treating the interface as a writer of its entity's table closes the
	 * whole category at once -- {@code save}, {@code saveAll}, {@code delete},
	 * {@code deleteAll}, every derived write -- because reachability is by class
	 * reference: a service that injects the repository in order to call
	 * {@code save} makes the repository reachable, and the repository is then a
	 * writer that must be scanned or accounted for. Chasing the call sites
	 * instead would mean resolving a field's type across files, and would miss
	 * exactly the shapes it most needs to catch.
	 *
	 * <p>It over-approximates -- a repository only ever read from still counts --
	 * and that is the safe direction: it can demand an exemption that was not
	 * needed, never hide a writer. No repository of a tenant-owned entity is
	 * reachable from the admin surface today, so it adds nobody.
	 */
	private static final Pattern JPA_REPOSITORY = Pattern.compile(
			"extends\\s+(?:Jpa|Crud|PagingAndSorting|ListCrud|ListPagingAndSorting)Repository\\s*<\\s*(\\w+)");

	/** {@code @Entity} ... {@code @Table(name = "x")}, in any attribute order. */
	private static final Pattern ENTITY_TABLE = Pattern.compile(
			"@Entity\\b[^;{]*?@Table\\s*\\((?:[^()]|\\([^()]*\\))*?\\bname\\s*=\\s*\"(\\w+)\"", Pattern.DOTALL);

	@Test
	void everySessionTakingServiceMethodReachesATenantGuard() {
		List<String> unguarded = new ArrayList<>();
		int checked = 0;

		for (Path file : adminServices()) {
			Scan scan = scan(read(file));
			checked += scan.sessionTaking();
			scan.unguarded().forEach(method -> unguarded.add(file.getFileName() + "::" + method));
		}

		assertThat(checked)
				.as("the rule is worthless if it matched nothing; these services exist")
				.isGreaterThan(40);
		assertThat(unguarded)
				.as("a method that takes a DashboardSession has declared itself "
						+ "tenant-relevant, so it must resolve the row's company and check it "
						+ "against that session -- directly or through a helper. See D-176.")
				.isEmpty();
	}

	/**
	 * Rule three: nothing the admin surface can reach writes a tenant-owned table
	 * without one of the rules above seeing it, or an entry saying why not.
	 *
	 * <p>Rules one and two are both selective by <em>name and location</em>:
	 * {@code *AdminService.java} for one, {@code <X>Store.java} beside an
	 * {@code <X>AdminService.java} for the other, and only under the admin root
	 * for either. That is a coverage assumption, and it was wrong in two ways at
	 * once. {@code LegacyPlatformAdminCompanyDirectory} writes {@code branches}
	 * from inside the admin root under a name matching neither pattern. The device
	 * stores write {@code attendance_devices} and {@code device_assignment_history}
	 * from outside it -- and those tables were not even in the gate's vocabulary
	 * until {@link #PHASE1_SCHEMA} was read, because they are declared in this
	 * repository's own schema rather than the vendored one.
	 *
	 * <p>Neither was a vulnerability -- but not for one reason, and saying it as
	 * one reason is how an exemption drifts from its code. Most resolve the
	 * row's company before writing, which is what D-176 asks;
	 * {@code LegacyPlatformAdminCompanyDirectory.create} has no prior row to
	 * resolve because it is creating the company, and
	 * {@code LegacyPayrollBatchStore} is guarded a layer above by rule one. The
	 * defect was that the gate could not have told anyone either way. This rule closes that by making the
	 * gate's coverage an enumerated claim instead of an implied one: reachability
	 * is computed from the admin root outward, and every writer it finds must be
	 * scanned or listed.
	 *
	 * <p><b>Why reachability rather than "every writer in the repository".</b>
	 * Measured with this rule's own predicate at this head: <b>68 files</b>
	 * contain a write to a tenant-owned table -- <b>59</b> by SQL text alone,
	 * <b>61</b> once a JPQL {@code @Modifying} write counts, <b>68</b> once a
	 * repository declared over a tenant-owned entity counts.
	 *
	 * <p>Decomposed like that on purpose, because a bare figure here has now been
	 * wrong twice for the same reason. It said "120 write methods across 39
	 * files", which had been true of a narrower gate; the correction said 61,
	 * measured minutes before the same commit taught the rule to count
	 * repositories, which added seven more. A number that moves when the
	 * predicate widens should show which predicate it belongs to. It is not
	 * asserted anywhere -- a ratchet on it would fail on every unrelated store --
	 * and is re-derived by printing {@code writtenTenantTables} over
	 * {@code classesByName()}. Most of the 68 belong to the legacy API, which has its own tenant control
	 * ({@code TenantFilterCoverageTest}, the tenant filter, {@code
	 * LegacyTenantContext}). Demanding an entry for each would produce the wall of
	 * exemptions this class's javadoc already rejected once, for the same reason:
	 * it would tell nobody anything. Scoped to what the admin surface can reach,
	 * the answer is ten.
	 */
	@Test
	void everyWriterTheAdminSurfaceCanReachIsScannedOrAccountedFor() {
		Set<String> tenantTables = tenantOwnedTables();
		Map<String, Path> byName = classesByName();
		Set<String> scanned = scannedByRuleOneOrTwo();

		List<String> unaccounted = new ArrayList<>();
		Set<String> entriesStillNeeded = new HashSet<>();
		Set<String> found = new java.util.TreeSet<>();

		for (String name : reachableFromAdminSurface(byName)) {
			Path file = byName.get(name);
			if (scanned.contains(file.getFileName().toString())) {
				continue;
			}
			if (writtenTenantTables(file, tenantTables).isEmpty()) {
				continue;
			}
			found.add(name);
			if (ACCOUNTED_FOR_OUTSIDE_THE_RULES.containsKey(name)) {
				entriesStillNeeded.add(name);
				continue;
			}
			unaccounted.add(name + " writes " + writtenTenantTables(file, tenantTables)
					+ " and is reachable from the admin surface, but neither rule scans it: "
					+ "rule one wants *AdminService.java, rule two wants <X>Store.java beside an "
					+ "<X>AdminService.java, and both look only under " + ADMIN_ROOT);
		}

		// Exact, not a floor. A floor is a dead ratchet: the review round pointed out
		// that `>= 5` could be loosened to `>= 0` with nothing noticing, because
		// nothing else re-derives the count. An equality catches drift in both
		// directions -- a writer appearing, and a writer quietly disappearing
		// because the closure stopped finding it, which is how the same-package gap
		// hid four of them.
		assertThat(found)
				.as("by name, not by count: the rule is worthless if it matched nothing, and a floor "
						+ "would not notice the closure narrowing -- which is how the same-package gap "
						+ "hid four of these. Naming them makes both directions of drift a failure "
						+ "that says which writer moved.")
				.containsExactlyInAnyOrderElementsOf(ACCOUNTED_FOR_OUTSIDE_THE_RULES.keySet());
		assertThat(unaccounted)
				.as("a write to a tenant-owned table that no rule can see is the failure mode this "
						+ "whole class exists to prevent. Either move it where a rule scans it, or "
						+ "add it to ACCOUNTED_FOR_OUTSIDE_THE_RULES with the reason it is safe.")
				.isEmpty();

		Set<String> stale = new java.util.TreeSet<>(ACCOUNTED_FOR_OUTSIDE_THE_RULES.keySet());
		stale.removeAll(entriesStillNeeded);
		assertThat(stale)
				.as("an entry here has outlived its reason: the class no longer writes a "
						+ "tenant-owned table, is no longer reachable from the admin surface, or is "
						+ "now scanned by rule one or rule two. A list that cannot outlive its "
						+ "reason is the only kind worth keeping.")
				.isEmpty();
	}

	/**
	 * Reach means a reference in code, not a mention in prose.
	 *
	 * <p>Rule three demands a written reason for every writer the admin surface
	 * can reach, so what counts as reaching decides who has to argue. A
	 * {@code @link} naming a store in a javadoc is documentation, not a call, and
	 * treating it as one would demand an entry for a class the admin surface only
	 * talks about -- and entries that are not really needed are how a list stops
	 * being read.
	 *
	 * <p>Asserted on the helper directly, because the repository as it stands
	 * cannot show the difference: removing the comment stripping today changes
	 * nothing, so nothing else here would notice if it went.
	 */
	@Test
	void aClassNamedOnlyInACommentIsNotReached() {
		Set<String> known = Set.of("LegacyCompanyDelete", "PayrollAdminService", "AdminNav");
		String source = """
				package com.workin.backend.platformadmin.web;

				import com.workin.backend.platformadmin.hr.PayrollAdminService;

				/**
				 * See {@link com.workin.legacy.profile.LegacyCompanyDelete} for the cascade.
				 */
				public class Example {
					// com.workin.backend.platformadmin.web.AdminNav is mentioned here only
					private final PayrollAdminService payroll = null;
				}
				""";

		// No package siblings for this fixture: the bare-name edge has its own test.
		Set<String> reached = referencedClasses(source, known, Set.of());

		assertThat(reached)
				.as("an imported and used collaborator is reached")
				.contains("PayrollAdminService");
		assertThat(reached)
				.as("a javadoc {@link} is prose; treating it as reach would demand an exemption "
						+ "for a class this one never calls")
				.doesNotContain("LegacyCompanyDelete");
		assertThat(reached)
				.as("and so is a line comment")
				.doesNotContain("AdminNav");
	}

	/**
	 * A class named with no import and no qualifier is still reached.
	 *
	 * <p>Java requires neither for a class in the same package, so
	 * {@code private final DeviceAgentStore agents;} is a real edge with nothing
	 * for {@link #IMPORTED_CLASS} or {@link #QUALIFIED_CLASS} to match. Matching
	 * only those two missed **every edge inside a package**, and rule three's
	 * claim to have found every writer the admin surface can reach was therefore
	 * false: four more existed -- {@code DeviceAgentStore},
	 * {@code DevicePunchStore}, {@code EmployeeDeviceIdentityStore} and
	 * {@code LegacyPayslipStore}. Found by the review round on this change, which
	 * reimplemented the closure and compared.
	 *
	 * <p>The resolution is deliberately narrow: a bare capitalised word counts only
	 * when it names a class in <em>this file's own package</em>. Widened to every
	 * known simple name, {@code Map} or {@code List} in a comment-stripped body
	 * would invent edges to anything that happened to share a name.
	 */
	@Test
	void aClassInTheSamePackageIsReachedWithNoImportAndNoQualifier() {
		Set<String> known = Set.of("DeviceAgentStore", "DeviceAgentService", "LegacyCompanyDelete");
		String source = """
				package com.workin.devices.agent;

				public class DeviceAgentService {
					private final DeviceAgentStore agents;
				}
				""";

		assertThat(referencedClasses(source, known, Set.of("DeviceAgentStore")))
				.as("a bare same-package field type is an edge, and it is the one that was missed")
				.contains("DeviceAgentStore");
		assertThat(referencedClasses(source, known, Set.of()))
				.as("and without the sibling set there is nothing to match it against -- which is "
						+ "exactly the state that hid four writers")
				.doesNotContain("DeviceAgentStore");
		assertThat(referencedClasses(source, known, Set.of("LegacyCompanyDelete")))
				.as("a sibling that is not named in the source is not invented")
				.doesNotContain("LegacyCompanyDelete");
		assertThat(referencedClasses(
				"package com.workin.devices.agent;\n class X { LegacyCompanyDelete d; }",
				known, Set.of("DeviceAgentStore")))
				.as("and a bare name that is NOT this file's sibling is not an edge either, however "
						+ "well known it is elsewhere -- widened to every known class, a bare word in "
						+ "any file would invent an edge to anything sharing its name")
				.doesNotContain("LegacyCompanyDelete");
	}

	/**
	 * The eight tables that were invisible, named, so that widening the ground
	 * truth cannot be quietly reverted.
	 *
	 * <p>Deleting the {@link #PHASE1_SCHEMA} read would take rule three's ten
	 * writers down to four: each of the six device stores writes exactly one
	 * phase-1 table and nothing else, so all six would stop being writers,
	 * because the tables they write would stop counting as tenant-owned.
	 *
	 * <p>That revert does <em>not</em> pass silently, and this javadoc claimed it
	 * did until a review round measured it: {@code found} would no longer equal
	 * the exemption keys, and six entries would be reported stale, so
	 * {@link #everyWriterTheAdminSurfaceCanReachIsScannedOrAccountedFor} fails
	 * twice over. What this test adds is not the detection but the diagnosis --
	 * six stale exemptions is a confusing way to be told that a schema file
	 * stopped being read, and these eight names say it directly.
	 */
	@Test
	void theRepositorysOwnTenantOwnedTablesAreNotMissingFromTheGroundTruth() {
		Set<String> legacy = legacyTenantOwnedTables();
		Set<String> phase1 = phase1TenantOwnedTables();

		assertThat(phase1)
				.as("read from src/main/resources/db/phase1-mysql, by the same column test the "
						+ "vendored schema gets")
				.contains("attendance_devices", "device_agents", "device_punches",
						"employee_device_identities", "device_assignment_history",
						"device_malformed_punches", "device_operation_logs", "legacy_refresh_tokens");
		assertThat(legacy)
				.as("and none of them is in the vendored schema, which is why the gate could not "
						+ "see a write to any of them before")
				.doesNotContain("attendance_devices", "device_agents", "device_punches",
						"employee_device_identities", "device_assignment_history",
						"device_malformed_punches", "device_operation_logs", "legacy_refresh_tokens");
		assertThat(tenantOwnedTables())
				.as("the gate's ground truth is the database, not the half of it this port inherited")
				.containsAll(legacy)
				.containsAll(phase1);
	}

	@Test
	void everyPublicMethodThatWritesATenantOwnedTableTakesASession() {
		Set<String> tenantTables = tenantOwnedTables();
		List<String> offenders = new ArrayList<>();
		Set<String> exemptionsStillNeeded = new HashSet<>();
		int checked = 0;

		for (Map.Entry<String, Set<String>> entry
				: storeWriteMethodsByService(tenantTables).entrySet()) {
			String service = entry.getKey();
			WriteScan scan = scanWrites(read(serviceFile(service)), entry.getValue());
			checked += scan.writing();
			for (String method : scan.sessionless()) {
				String key = service + "::" + method;
				if (DELIBERATELY_CROSS_TENANT.containsKey(key)) {
					exemptionsStillNeeded.add(key);
					continue;
				}
				offenders.add(key + " reaches a store write on a tenant-owned table but takes "
						+ "no DashboardSession, so rule one never sees it");
			}
			for (String method : scan.guarded()) {
				String key = service + "::" + method;
				if (DELIBERATELY_CROSS_TENANT.containsKey(key)) {
					offenders.add(key + " is listed as deliberately cross-tenant but now takes a "
							+ "DashboardSession; the exemption has outlived its reason");
				}
			}
		}

		assertThat(checked)
				.as("the rule is worthless if it matched nothing; 55 write paths exist today, and a "
						+ "regex that quietly stopped matching would otherwise pass this vacuously")
				.isGreaterThan(40);
		assertThat(offenders).isEmpty();
		assertThat(exemptionsStillNeeded)
				.as("every declared cross-tenant exemption must still be a sessionless write, "
						+ "or it is a stale entry to delete")
				.containsExactlyInAnyOrderElementsOf(DELIBERATELY_CROSS_TENANT.keySet());
	}

	/**
	 * Every write statement in a paired store <em>sits inside a method body</em>
	 * rule two can see -- which is the property rule three relies on when it calls
	 * that store "scanned", and it is per statement rather than per table.
	 *
	 * <p>Two ways a write hides from rule two, both live shapes. {@link #ANY_METHOD}
	 * needs an explicit {@code public}, {@code private}, {@code protected} or
	 * {@code static}, so a <b>package-private</b> method is not a method as far as it
	 * is concerned, so the statement is in the file, in no method rule two names, and
	 * rule three skips the file on rule two's behalf. Three rules, one blind spot.
	 *
	 * <p>Method <b>overloads</b> were the second way in, and they are not this test's
	 * to catch: the overload <em>is</em> inside a method's braces, so an offset check
	 * passes. {@link #methodBodies} handles that one, and the twelfth round's mutant
	 * confirms the division -- the overload exploit is killed by rule two itself and
	 * survives this test. A sentence here once claimed otherwise.
	 *
	 * <p><b>Comparing the tables written would not close it, and the twelfth round
	 * proved that with a working exploit.</b> A file-wide table set minus a
	 * per-method table set only differs when the hidden write targets a table the
	 * store writes <em>nowhere else</em>; a package-private
	 * {@code DELETE FROM employees} in {@code EmployeeStore}, reached from a
	 * sessionless {@code EmployeeAdminService} method, passed every test in this
	 * class, because {@code insert} and {@code update} already write
	 * {@code employees}. That is the R-046 shape this class exists to fail on. So
	 * the comparison is by <b>offset</b>: every match of {@link #WRITE_STATEMENT} on
	 * a tenant-owned table must start inside some method's braces. A duplicate table
	 * cannot mask a statement, because statements are not compared to each other.
	 *
	 * <p>Comments are excluded by offset too, not stripped. This file's house style
	 * quotes legacy SQL in javadoc constantly, and a javadoc above the class body
	 * saying {@code DELETE FROM employees WHERE id = ?} is not a write -- without
	 * this it would fail the build with the wrong diagnosis. Stripping them instead
	 * is what {@link #code} cannot do here: it blanks string literals, which is where
	 * the real SQL lives.
	 */
	@Test
	void everyWriteInAPairedStoreSitsInAMethodRuleTwoCanSee() {
		Set<String> tenantTables = tenantOwnedTables();
		Map<String, String> entities = entityTables();
		List<String> hidden = new ArrayList<>();
		int compared = 0;
		int statements = 0;

		for (Path store : pairedStores()) {
			compared++;
			// Flattened first, and every span measured on the flattened text, so
			// the offsets below and the write matches are in the same coordinates.
			String source = flattened(read(store));
			List<int[]> methods = methodSpans(source);
			List<int[]> comments = maskNonCode(source).comments();
			Matcher write = WRITE_STATEMENT.matcher(source);
			while (write.find()) {
				String named = canonical(write.group(1));
				String table = entities.getOrDefault(write.group(1), named);
				if (!tenantTables.contains(table) || within(comments, write.start())) {
					continue;
				}
				statements++;
				if (!within(methods, write.start())) {
					hidden.add(store.getFileName() + " writes " + table + " at offset "
							+ write.start() + ", which is inside no method rule two can see, "
							+ "and rule three counts this file as scanned");
				}
			}
		}

		assertThat(compared)
				.as("the paired stores rule two walks; a glob that stopped matching would make "
						+ "this pass by comparing nothing")
				.isEqualTo(21);
		assertThat(statements)
				.as("the write statements checked; 60 exist across the 21 paired stores today, "
						+ "and a pattern that stopped matching would otherwise make this pass by "
						+ "checking nothing")
				.isGreaterThan(40);
		assertThat(hidden).isEmpty();
	}

	/** Whether {@code offset} falls inside any of {@code spans}. */
	private static boolean within(List<int[]> spans, int offset) {
		return spans.stream().anyMatch(span -> offset >= span[0] && offset < span[1]);
	}

	/**
	 * The braces of every method {@link #ANY_METHOD} finds, as {@code [open, close)}.
	 *
	 * <p>Every method, not every distinct name: {@link #methodBodies} keeps one entry
	 * per name and this keeps all of them, which is the whole point of measuring by
	 * offset.
	 *
	 * <p>Both the declaration match and the brace counting run on
	 * {@link #maskNonCode}'s output, so a brace or a method-shaped phrase inside a
	 * string or a comment is neither a span nor an opening one. Counting on the raw
	 * text is what the thirteenth round defeated with a single {@code "{"}.
	 */
	private static List<int[]> methodSpans(String source) {
		String code = maskNonCode(source).code();
		List<int[]> spans = new ArrayList<>();
		Matcher method = ANY_METHOD.matcher(code);
		while (method.find()) {
			int[] span = braceSpan(code, method.end() - 1);
			if (span != null) {
				spans.add(span);
			}
		}
		return spans;
	}

	/**
	 * The braced block opening at or after {@code from}, as {@code [open, close)},
	 * counted over already-masked code.
	 */
	private static int[] braceSpan(String code, int from) {
		int open = code.indexOf('{', from);
		if (open < 0) {
			return null;
		}
		int depth = 0;
		for (int index = open; index < code.length(); index++) {
			char character = code.charAt(index);
			if (character == '{') {
				depth++;
			}
			else if (character == '}' && --depth == 0) {
				return new int[] { open, index + 1 };
			}
		}
		return new int[] { open, code.length() };
	}

	/**
	 * Java concatenation collapsed, so a statement split across lines reads as one.
	 *
	 * <p><b>It must not touch a text-block delimiter, and the fourteenth round
	 * showed why with a red control.</b> In {@code "SELECT " + """} the five
	 * characters this collapses are the closing quote of {@code "SELECT "}, the
	 * {@code " + "}, and the <em>first</em> quote of the {@code """} opener. What is
	 * left reads as a string, an empty string, then the block's content as
	 * <em>code</em> and the block's closing {@code """} as an opener with no close
	 * -- so {@link #maskNonCode} blanks the rest of the file, every later brace
	 * disappears, and the enclosing method's span covers the remainder of the class.
	 * A package-private write below it then reads as covered. The lookarounds keep
	 * the collapse away from any quote that is part of a longer run.
	 *
	 * <p>One method for all three callers. Three copies of one rule is how the tenth
	 * round's finding happened.
	 */
	private static String flattened(String source) {
		return source.replaceAll("(?<!\")\"\\s*\\+\\s*\"(?!\")", "");
	}

	/**
	 * One pass over a Java source: which characters are code, and where the
	 * comments are.
	 *
	 * <p>{@code code} is the source with every character of a string literal, a
	 * character literal, a text block and a comment replaced by a space --
	 * <b>same length, so every offset still lines up with the real source</b>.
	 * {@code comments} are the spans of the comments, for the one caller that
	 * needs to tell prose from code rather than ignore it.
	 */
	private record SourceMask(String code, List<int[]> comments) {
	}

	/**
	 * Mask the parts of a Java source that are not code.
	 *
	 * <p><b>This exists because counting braces on raw text is wrong, and the
	 * thirteenth round proved it with one line.</b> A {@code String prefix = "{";}
	 * anywhere in a store left the brace counter one level deep at the end of that
	 * method, so its span ran to the end of the class and every later write --
	 * including a package-private one nothing else sees -- read as "inside a
	 * method". That is the gap the twelfth round's fix had just closed, re-opened
	 * by the fix itself. Eighty of the 502 classes the gate indexes contain a brace inside a
	 * string literal; one {@code LIKE '%{'} in a paired store would have done it.
	 *
	 * <p>Text blocks are handled as text blocks, not as two strings. A
	 * {@code """} opened as {@code ""} plus {@code "} leaves the scanner reading
	 * the block's <em>content</em> as code, so an unescaped quote inside it
	 * desynchronises everything after -- and six of the twenty-one paired stores
	 * write their SQL in text blocks.
	 *
	 * <p>Escapes are consumed as a unit, so a literal ending in {@code \\"} does
	 * not swallow the rest of the file.
	 */
	private static SourceMask maskNonCode(String source) {
		char[] code = source.toCharArray();
		List<int[]> comments = new ArrayList<>();
		int index = 0;
		while (index < source.length()) {
			char character = source.charAt(index);
			if (character == '/' && index + 1 < source.length()) {
				char next = source.charAt(index + 1);
				if (next == '/') {
					int end = source.indexOf('\n', index);
					end = end < 0 ? source.length() : end;
					comments.add(new int[] { index, end });
					index = blank(code, index, end);
					continue;
				}
				if (next == '*') {
					int end = source.indexOf("*/", index + 2);
					end = end < 0 ? source.length() : end + 2;
					comments.add(new int[] { index, end });
					index = blank(code, index, end);
					continue;
				}
			}
			if (source.startsWith("\"\"\"", index)) {
				int end = source.indexOf("\"\"\"", index + 3);
				// An unterminated block ends at its own line rather than at the end
				// of the file, for the same reason the string branch does: a scanner
				// that blanks the rest of a class hides writes, and this one is
				// reached by a desynchronised opener rather than by real source.
				int line = source.indexOf('\n', index + 3);
				end = end < 0 ? (line < 0 ? source.length() : line) : end + 3;
				index = blank(code, index, end);
				continue;
			}
			if (character == '"' || character == '\'') {
				int at = index + 1;
				while (at < source.length() && source.charAt(at) != character) {
					// A newline ends an unterminated literal rather than letting it
					// run to the end of the file: a scanner that swallows the rest
					// of a class hides writes, which is the one direction that must
					// not happen quietly.
					if (source.charAt(at) == '\n') {
						break;
					}
					at += source.charAt(at) == '\\' ? 2 : 1;
				}
				at = Math.min(at + 1, source.length());
				index = blank(code, index, at);
				continue;
			}
			index++;
		}
		return new SourceMask(new String(code), comments);
	}

	/** Blank {@code [from, to)} and return {@code to}. */
	private static int blank(char[] code, int from, int to) {
		for (int at = from; at < to && at < code.length; at++) {
			if (code[at] != '\n') {
				code[at] = ' ';
			}
		}
		return Math.max(to, from + 1);
	}

	/**
	 * A table declared in any case lands in the ground truth as its canonical form
	 * -- in <b>both</b> schema readers.
	 *
	 * <p>{@code tenantTables.contains(canonical(captured))} rests on two halves. The
	 * tenth round's finding was one side of the comparison missing
	 * {@link #canonical}; the other half is that the set being searched needs no
	 * normalising, which is a property of the readers and not of the comparison.
	 *
	 * <p>Two things this test got wrong before, both caught by a later round, both
	 * recorded here because they are the same mistake in two forms. First it
	 * asserted the property over {@link #tenantOwnedTables()}, <b>which is built by
	 * calling {@code canonical}</b> -- so it held by construction, removing
	 * {@code canonical} from every insertion point left the class green, and it was
	 * the dead-assertion shape round 10 had already found in this file. Then, once
	 * it drove a synthetic schema, it drove only the <em>vendored</em> reader:
	 * {@link #phase1TenantOwnedFrom} keeps its own calls, and removing them was
	 * still invisible. Both readers are measured here now.
	 *
	 * <p>There is no assertion over {@code entityTables()}. One was here and it
	 * could not fail either -- all twelve {@code @Table} names are lower-case, so it
	 * passed whether the production call existed or not.
	 * {@link #theEntityTableMapComesFromTheEntitiesThemselves} reads the raw capture,
	 * which is where that half can actually fail.
	 *
	 * <p>MySQL's table names are case-sensitive on Linux and the vendored half
	 * already ships upper-case {@code CREATE TABLE} ({@code SPRING_SESSION}), so a
	 * mixed-case tenant-owned table is a re-vendored dump away.
	 */
	@Test
	void aTableDeclaredInAnyCaseIsCanonicalInBothSchemaReaders() {
		assertThat(tenantOwnedFrom("""
				CREATE TABLE `TimeSheets` (
				  `id` bigint NOT NULL,
				  `Company_Id` bigint NOT NULL
				) ENGINE=InnoDB;
				CREATE TABLE `Audits` (
				  `id` bigint NOT NULL,
				  `note` varchar(64) DEFAULT NULL
				) ENGINE=InnoDB;
				"""))
				.as("the vendored reader: the declared case is normalised on the way in, and a "
						+ "tenant column is recognised whatever case it was declared in -- MySQL's "
						+ "column names are case-insensitive even where its table names are not")
				.containsExactly("timesheets");

		assertThat(phase1TenantOwnedFrom("""
				CREATE TABLE IF NOT EXISTS Device_Logs (
				  id BIGINT NOT NULL,
				  Company_Id BIGINT NOT NULL
				);
				CREATE TABLE IF NOT EXISTS Device_Kinds (
				  id BIGINT NOT NULL,
				  label VARCHAR(64)
				);
				"""))
				.as("and the Phase 1 reader, which parses a different shape through its own "
						+ "calls -- removing them was invisible until this half existed")
				.containsExactly("device_logs");
	}

	/**
	 * A brace, a quote or a comment marker inside a string literal does not move a
	 * method's boundary.
	 *
	 * <p>This is the property both {@link #methodSpans} and {@link #blockAt} rest
	 * on, and until the thirteenth round neither had it. It is asserted on synthetic
	 * sources rather than through a mutant on the tree, because the end-to-end
	 * mutants are defeated by an unrelated detail: a service method calling
	 * {@code this.store.delete(id)} is followed into the <em>service's own</em>
	 * {@code delete}, so the guard it inherits has nothing to do with the brace.
	 *
	 * <p>The fixtures are built by concatenation rather than as text blocks, because
	 * a text block containing {@code """} and stray quotes is exactly the thing being
	 * tested and its own escaping made the first version of this test assert
	 * something other than what it read -- four mutants survived it.
	 *
	 * <p>Each case is a real shape here. Eighty of the 502 classes the gate indexes put
	 * a brace inside a string literal; six of the twenty-one paired stores write
	 * their SQL in text blocks; and {@code AdminPageAvailability.pageOf} has the
	 * first shape today, its body over-running its true end by 78 characters -- which
	 * cost nothing only because no rule scans that file.
	 */
	@Test
	void aBraceInsideAStringDoesNotMoveAMethodsBoundary() {
		String braceInAString = "class Store {\n"
				+ "\tpublic void first() {\n"
				+ "\t\tString brace = \"{\";\n"
				+ "\t}\n"
				+ "\tvoid hidden() {\n"
				+ "\t\tjdbc.update(\"DELETE FROM employees WHERE id = ?\");\n"
				+ "\t}\n"
				+ "}\n";
		assertThat(methodSpans(braceInAString))
				.as("one span: `first` is a method to ANY_METHOD and the package-private `hidden` "
						+ "is not, which is the blind spot the positional rule exists to see")
				.hasSize(1);
		assertThat(within(methodSpans(braceInAString), braceInAString.indexOf("DELETE FROM")))
				.as("and the write in `hidden` is inside NO span, which is what makes it visible. "
						+ "Count the brace in the string and `first` never closes, its span runs to "
						+ "the end of the class, this is true instead of false, and the write reads "
						+ "as covered")
				.isFalse();
		assertThat(blockAt(braceInAString, braceInAString.indexOf("public void first")))
				.as("blockAt stops at first's own brace -- the same defect defeats rule one, where "
						+ "a method whose body ran on would inherit every later method's guard")
				.doesNotContain("DELETE FROM employees");

		// A bare brace inside a text block, which six of the paired stores could
		// write today: `LIKE '%{'` and JSON fragments both appear in this tree.
		String braceInATextBlock = "class Store {\n"
				+ "\tpublic void first() {\n"
				+ "\t\tString sql = \"\"\"\n"
				+ "\t\t\t\tSELECT json FROM t WHERE json LIKE { \n"
				+ "\t\t\t\t\"\"\";\n"
				+ "\t}\n"
				+ "\tvoid hidden() {\n"
				+ "\t\tjdbc.update(\"DELETE FROM employees WHERE id = ?\");\n"
				+ "\t}\n"
				+ "}\n";
		assertThat(within(methodSpans(braceInATextBlock),
						braceInATextBlock.indexOf("DELETE FROM")))
				.as("read the text block as two strings rather than as a block and its content is "
						+ "code, the brace in it is counted, `first` never closes, and the write in "
						+ "the package-private method below reads as inside a method")
				.isFalse();
		assertThat(maskNonCode(braceInATextBlock).comments())
				.as("nothing in that block opens a comment")
				.isEmpty();

		// The escape and the brace on one line: a scanner that ends the literal at
		// the escaped quote is one position out for the rest of that line, and the
		// brace that should have been inside a string is counted.
		String escapedQuote = "class Store {\n"
				+ "\tpublic void first() {\n"
				+ "\t\tString q = \"\\\"\" + \"{\";\n"
				+ "\t}\n"
				+ "\tvoid hidden() {\n"
				+ "\t\tjdbc.update(\"DELETE FROM employees WHERE id = ?\");\n"
				+ "\t}\n"
				+ "}\n";
		assertThat(within(methodSpans(escapedQuote), escapedQuote.indexOf("DELETE FROM")))
				.as("an escaped quote does not end its literal; treat it as if it did and the "
						+ "brace in the next string is read as code, `first` runs on, and the write "
						+ "below reads as covered")
				.isFalse();
	}

	/**
	 * An overload that does not guard cannot borrow its sibling's guard, and an
	 * overload that does write cannot hide behind a sibling that does not.
	 *
	 * <p>The twelfth and thirteenth rounds each found one half of this, and the
	 * fourteenth found that <b>neither half was pinned</b>: four mutants reverting
	 * those fixes -- {@code reachesGuard} accepting any overload, {@code
	 * methodBodies} keeping only the first body, {@code reachesCall} consulting only
	 * the first -- all passed a green suite. They were invisible because the rule has
	 * no live subject: no admin service declares an overload at all, and the two
	 * paired stores that do have none that writes. A property with no instance is
	 * exactly the one that needs a synthetic fixture.
	 *
	 * <p>The two rules want opposite approximations, so both directions are here.
	 */
	@Test
	void anOverloadNeitherBorrowsAGuardNorHidesAWrite() {
		String borrowedGuard = """
				class Service {
					public long remove(DashboardSession session, long id) {
						return allow(id);
					}

					private long allow(long id) {
						return id;
					}

					private long allow(DashboardSession session, long id) {
						if (id != session.companyId()) {
							throw new IllegalStateException("other company");
						}
						return id;
					}
				}
				""";
		assertThat(scan(borrowedGuard).unguarded())
				.as("`remove` calls `allow(id)`, and the overload that takes no session does "
						+ "not guard. Let a name inherit any overload's guard and this is empty, "
						+ "which is a sessionless write reported as guarded")
				.contains("remove");

		String guardedEverywhere = """
				class Service {
					public long remove(DashboardSession session, long id) {
						return allow(session, id);
					}

					private long allow(DashboardSession session, long id) {
						if (id != session.companyId()) {
							throw new IllegalStateException("other company");
						}
						return id;
					}
				}
				""";
		assertThat(scan(guardedEverywhere).unguarded())
				.as("the control: one overload, and it guards")
				.isEmpty();

		// The write is reached THROUGH the overloaded name, not called by it: a call
		// whose name is already in the wanted set returns before any overload is
		// consulted, which is why the first version of this fixture pinned nothing.
		String writeInTheSecondOverload = """
				class Service {
					public void publish(long id) {
						apply(id, "now");
					}

					private void apply(long id) {
						read(id);
					}

					private void apply(long id, String at) {
						save(id, at);
					}
				}
				""";
		assertThat(scanWrites(writeInTheSecondOverload, Set.of("save")).sessionless())
				.as("`publish` reaches the store write only through the SECOND `apply`. Keep one "
						+ "body per name -- which is what this did until the twelfth round -- or "
						+ "consult only the first, and rule two never asks `publish` for a session")
				.contains("publish");

		String declarationInAComment = """
				class Store {
					// private void ghost(long id) {
					public void real(long id) {
						jdbc.update("DELETE FROM employees WHERE id = ?");
					}
				}
				""";
		assertThat(methodBodies(declarationInAComment).keySet())
				.as("a declaration inside a comment is not a method; matched on raw text it is, "
						+ "and its body becomes the next real block")
				.containsExactly("real");
	}

	/**
	 * {@link #maskNonCode} blanks what is not code, and each branch is pinned.
	 *
	 * <p>Four of its branches were covered by nothing: deleting the {@code //}
	 * handling, the {@code /* *}{@code /} handling, the character-literal branch or
	 * the newline guard each left the suite green. The comment branches matter in
	 * both directions -- this file quotes legacy SQL in javadoc constantly, so a
	 * dropped comment branch turns prose into a write, and a brace inside a comment
	 * counted as code extends a method span over a later one.
	 */
	@Test
	void theMaskBlanksEveryKindOfNonCode() {
		String source = """
				class Store {
					// DELETE FROM employees WHERE id = ?
					public void first() {
						char quote = '"';
						char open = '{';
						char slash = '\\\\';
					}

					/* legacy: INSERT INTO employees (company_id) VALUES (?) { */
					public void second() {
						jdbc.update("DELETE FROM penalties WHERE id = ?");
					}
				}
				""";
		SourceMask mask = maskNonCode(source);
		assertThat(mask.comments())
				.as("both comment forms are recorded; a masker that records none satisfies an "
						+ "emptiness assertion and nothing else")
				.hasSize(2);
		assertThat(within(mask.comments(), source.indexOf("DELETE FROM employees")))
				.as("the line comment's SQL is prose")
				.isTrue();
		assertThat(within(mask.comments(), source.indexOf("INSERT INTO employees")))
				.as("and the block comment's is too")
				.isTrue();
		assertThat(mask.code())
				.as("no SQL survives in the code view, whichever comment carried it")
				.doesNotContain("DELETE FROM employees")
				.doesNotContain("INSERT INTO employees")
				.doesNotContain("DELETE FROM penalties");
		assertThat(methodSpans(source)).as("two methods").hasSize(2);
		assertThat(blockAt(source, source.indexOf("public void first")))
				.as("the first method ends at its own brace. The brace in the block comment and "
						+ "the one in the character literal are each enough to keep the counter "
						+ "open, and its body then swallows the second method and the write in it "
						+ "-- a count of two spans says nothing about that, which is why this "
						+ "reads the boundary instead")
				.doesNotContain("DELETE FROM penalties");

		String unterminated = """
				class Store {
					public void first() {
						String broken = "unterminated;
					}

					public void second() {
						jdbc.update("DELETE FROM employees WHERE id = ?");
					}
				}
				""";
		assertThat(methodSpans(unterminated))
				.as("an unterminated literal ends at its own line. Let it run and it reaches the "
						+ "next quote two methods down, blanking the second declaration on the "
						+ "way -- a whole method, and the write in it, gone from the scan")
				.hasSize(2);

		// The flatten runs BEFORE the mask, so the two have to agree about what a
		// quote is. An unguarded collapse eats the first quote of a `"""` opener and
		// the mask then reads the block's content as code and its closing delimiter
		// as an opener with no close -- blanking the rest of the file.
		String concatenatedTextBlock = "class Store {\n"
				+ "\tprivate String joined() {\n"
				+ "\t\treturn \"SELECT \" + \"\"\"\n"
				+ "\t\t\t\t1 FROM employees\n"
				+ "\t\t\t\t\"\"\";\n"
				+ "\t}\n"
				+ "\tvoid hidden() {\n"
				+ "\t\tjdbc.update(\"DELETE FROM employees WHERE id = ?\");\n"
				+ "\t}\n"
				+ "}\n";
		String flat = flattened(concatenatedTextBlock);
		assertThat(flat.split("\"\"\"", -1).length - 1)
				.as("both delimiters survive the collapse -- counted, because the closing one "
						+ "alone satisfies a `contains` while the opener has had its first quote "
						+ "eaten, which is the whole defect")
				.isEqualTo(2);
		assertThat(within(methodSpans(flat), flat.indexOf("DELETE FROM")))
				.as("and the package-private write below the block is still inside no method "
						+ "rule two can see -- which is what makes it visible. Collapse the "
						+ "delimiter and the first method's span covers the rest of the class")
				.isFalse();
	}

	@Test
	void theSchemaDecidesWhatIsTenantOwned() {
		Set<String> tenantTables = tenantOwnedTables();
		// A sanity check on the input, so a regex that silently stopped matching
		// cannot turn both rules above into vacuous passes.
		assertThat(tenantTables)
				.contains("attendance", "payslips", "payroll_batches", "penalties",
						"advances", "employees", "notifications")
				.doesNotContain("guide_videos", "faq_items", "banners", "phone_countries");
	}

	/** What one service source yields: how many methods were in scope, and which failed. */
	private record Scan(int sessionTaking, List<String> unguarded) {
	}

	/**
	 * The rule itself, over one source. Separated so the cases below can drive
	 * it with sources written to fail, which is the only way a coverage test
	 * demonstrates it is not passing vacuously.
	 */
	private static Scan scan(String source) {
		Map<String, List<String>> bodies = methodBodies(source);
		List<String> unguarded = new ArrayList<>();
		int sessionTaking = 0;
		for (Map.Entry<String, String> method : publicMethodBodies(source).entrySet()) {
			if (!method.getKey().contains("DashboardSession")) {
				continue;
			}
			sessionTaking++;
			if (!reachesGuard(method.getValue(), bodies)) {
				unguarded.add(name(method.getKey()));
			}
		}
		return new Scan(sessionTaking, unguarded);
	}

	@Test
	void theRuleCatchesASessionTakingMethodThatNeverChecksTheCompany() {
		// The R-046 shape: the posted id is the whole authorization.
		String source = """
				class Example {
					public long delete(DashboardSession session, long id) {
						this.store.delete(id);
						return 1L;
					}
				}""";
		Scan scan = scan(source);
		assertThat(scan.sessionTaking()).isOne();
		assertThat(scan.unguarded()).containsExactly("delete");
	}

	@Test
	void theRuleRejectsAGuardWhoseAnswerIsThrownAway() {
		// Matches TENANT_GUARD exactly, enforces nothing.
		String source = """
				class Example {
					public long delete(DashboardSession session, long id) {
						session.companyId();
						this.store.delete(id);
						return 1L;
					}
				}""";
		assertThat(scan(source).unguarded()).containsExactly("delete");
	}

	/**
	 * Naming a guarded <em>helper</em> in a comment does not guard either.
	 *
	 * <p>{@link #theRuleRejectsAGuardThatIsOnlyMentionedInAComment} covers a direct
	 * mention of the guard. One level down it did not: the callee walk in
	 * {@link #reachesGuard} ran on the raw body while the guard match beside it ran
	 * on the stripped one, so a comment saying "the row was resolved by
	 * {@code assertRowVisible(session, id)} upstream" made the rule follow a call
	 * that is not there and find the guard at the other end. That is this
	 * repository's commenting style, and it satisfied the primary D-176 rule.
	 */
	@Test
	void theRuleRejectsAGuardedHelperThatIsOnlyNamedInAComment() {
		String laundered = """
				class Service {
					public long remove(DashboardSession session, long id) {
						// the row was resolved by assertRowVisible(session, id) upstream
						store.delete(id);
						return 1L;
					}

					private long assertRowVisible(DashboardSession session, long id) {
						if (session.companyId() != id) {
							throw new IllegalStateException("other company");
						}
						return id;
					}
				}
				""";
		assertThat(scan(laundered).unguarded())
				.as("`remove` calls nothing that guards; it only mentions one")
				.contains("remove");

		String called = """
				class Service {
					public long remove(DashboardSession session, long id) {
						assertRowVisible(session, id);
						store.delete(id);
						return 1L;
					}

					private long assertRowVisible(DashboardSession session, long id) {
						if (session.companyId() != id) {
							throw new IllegalStateException("other company");
						}
						return id;
					}
				}
				""";
		assertThat(scan(called).unguarded())
				.as("the control: the same helper, actually called")
				.isEmpty();
	}

	@Test
	void theRuleRejectsAGuardThatIsOnlyMentionedInAComment() {
		String source = """
				class Example {
					public long delete(DashboardSession session, long id) {
						// safe: see session.companyId() and canOpenRow(session, id)
						this.store.delete(id);
						return 1L;
					}
				}""";
		assertThat(scan(source).unguarded()).containsExactly("delete");
	}

	@Test
	void theRuleStillAcceptsAGuardWhoseAnswerIsUsed() {
		// The three shapes a real guard takes on this surface, so the rule above
		// cannot be satisfied by refusing everything.
		String source = """
				class Example {
					public long a(DashboardSession session, long id) {
						long owner = session.companyId();
						return owner;
					}

					public long b(DashboardSession session, long id) {
						if (id != session.companyId()) {
							throw new IllegalStateException();
						}
						return id;
					}

					public long c(DashboardSession session, long id) {
						return session.isScopedToOneCompany() ? id : 0L;
					}
				}""";
		Scan scan = scan(source);
		assertThat(scan.sessionTaking()).isEqualTo(3);
		assertThat(scan.unguarded()).isEmpty();
	}

	@Test
	void theRuleAcceptsAGuardReachedThroughTwoHelpers() {
		// PayrollAdminService's shape: assertBatchVisible defers to assertVisible,
		// and only the second one touches the session. A rule that followed one
		// level would have reported all five payroll actions as unguarded.
		String source = """
				class Example {
					public long finalizeRun(DashboardSession session, long id) {
						long owner = assertBatchVisible(session, id);
						this.store.updateStatus(id, "finalized");
						return owner;
					}

					private long assertBatchVisible(DashboardSession session, long id) {
						return assertVisible(session, this.store.companyOfBatch(id));
					}

					private long assertVisible(DashboardSession session, Long owner) {
						if (owner != session.companyId()) {
							throw new IllegalStateException();
						}
						return owner;
					}
				}""";
		assertThat(scan(source).unguarded()).isEmpty();
	}

	/** What one service source yields for rule two: the write paths, split by whether they guard. */
	private record WriteScan(int writing, List<String> sessionless, List<String> guarded) {
	}

	/**
	 * Rule two over one source, separated for the same reason rule one's scanner
	 * is: the cases below drive it with sources written to fail.
	 *
	 * <p>A public method counts as writing when it reaches one of the named store
	 * write methods, directly or through a helper on the same class -- the same
	 * walk rule one uses to find a guard, for the same reason: the write is often
	 * one level down.
	 */
	private static WriteScan scanWrites(String source, Set<String> storeWrites) {
		Map<String, List<String>> bodies = methodBodies(source);
		List<String> sessionless = new ArrayList<>();
		List<String> guarded = new ArrayList<>();
		int writing = 0;
		for (Map.Entry<String, String> method : publicMethodBodies(source).entrySet()) {
			if (!reachesCall(method.getValue(), bodies, storeWrites, new HashSet<>(), 0)) {
				continue;
			}
			writing++;
			(method.getKey().contains("DashboardSession") ? guarded : sessionless)
					.add(name(method.getKey()));
		}
		return new WriteScan(writing, sessionless, guarded);
	}

	/** Does this body call one of {@code wanted}, within three levels of helper? */
	private static boolean reachesCall(
			String body, Map<String, List<String>> bodies, Set<String> wanted, Set<String> seen,
			int depth) {
		// `name(` and `::name` both reach `name`. Without the second, a write
		// behind `ids.forEach(this::writeRow)` was invisible to this rule and its
		// public method was never asked for a session.
		Matcher call = Pattern.compile("\\b(\\w+)\\s*\\(|::\\s*(\\w+)").matcher(code(body));
		List<String> callees = new ArrayList<>();
		while (call.find()) {
			String callee = call.group(1) != null ? call.group(1) : call.group(2);
			if (wanted.contains(callee)) {
				return true;
			}
			callees.add(callee);
		}
		if (depth >= 3) {
			return false;
		}
		for (String callee : callees) {
			// Keyed on depth as well: a callee first met at depth 2 is explored one
			// level further, and memoising the bare name would then skip it when a
			// shallower sibling reaches it -- missing a write three levels down,
			// which is the unsafe direction for this rule.
			if (!bodies.containsKey(callee) || !seen.add(callee + "@" + depth)) {
				continue;
			}
			// Any overload reaching the write is enough: the call site names no
			// parameters, so it could be any of them.
			for (String overload : bodies.get(callee)) {
				if (reachesCall(overload, bodies, wanted, seen, depth + 1)) {
					return true;
				}
			}
		}
		return false;
	}

	@Test
	void ruleTwoCatchesAnUnguardedWriteBesideAGuardedOneInTheSameClass() {
		// The D-276 shape, and the reason this rule is per method: the old
		// whole-file check passed this class because `delete` mentions a session.
		String source = """
				class Example {
					public Result delete(DashboardSession session, long id) {
						if (session.companyId() > 0) {
							return null;
						}
						this.store.deleteRow(id);
						return null;
					}

					public Result send(long adminId, Long companyId) {
						this.store.insertRows(companyId);
						return null;
					}
				}""";
		WriteScan scan = scanWrites(source, Set.of("deleteRow", "insertRows"));
		assertThat(scan.writing()).isEqualTo(2);
		assertThat(scan.sessionless()).containsExactly("send");
		assertThat(scan.guarded()).containsExactly("delete");
	}

	@Test
	void ruleTwoFindsAWriteOneHelperDown() {
		// A public method that writes through a private helper is still a write
		// path; a rule that only read the public body would miss it.
		String source = """
				class Example {
					public Result send(long adminId, Long companyId) {
						return dispatch(companyId);
					}

					private Result dispatch(Long companyId) {
						this.store.insertRows(companyId);
						return null;
					}
				}""";
		WriteScan scan = scanWrites(source, Set.of("insertRows"));
		assertThat(scan.writing()).isOne();
		assertThat(scan.sessionless()).containsExactly("send");
	}

	@Test
	void ruleTwoFindsAWriteBehindAMethodReference() {
		// `name(` is not the only way to reach `name`.
		String source = """
				class Example {
					public Result sendAll(java.util.List<Long> ids) {
						ids.forEach(this::writeRow);
						return null;
					}

					private void writeRow(Long id) {
						this.store.insertRows(id);
					}
				}""";
		WriteScan scan = scanWrites(source, Set.of("insertRows"));
		assertThat(scan.writing()).isOne();
		assertThat(scan.sessionless()).containsExactly("sendAll");
	}

	@Test
	void ruleTwoIgnoresAMethodThatOnlyReads() {
		String source = """
				class Example {
					public int reach() {
						return this.store.countEmployees();
					}
				}""";
		assertThat(scanWrites(source, Set.of("insertRows")).writing()).isZero();
	}

	@Test
	void theRuleIgnoresMethodsThatTakeNoSession() {
		// Platform-wide content has no company to check against; rule two is
		// what decides whether a service is allowed to be in this position.
		String source = """
				class Example {
					public void create(long adminId, String title) {
						this.store.insert(title);
					}
				}""";
		Scan scan = scan(source);
		assertThat(scan.sessionTaking()).isZero();
		assertThat(scan.unguarded()).isEmpty();
	}

	// ------------------------------------------------------------------

	private static Set<String> tenantOwnedTables() {
		Set<String> tenant = new HashSet<>(legacyTenantOwnedTables());
		tenant.addAll(phase1TenantOwnedTables());
		return tenant;
	}

	/** The inherited half of the database. */
	private static Set<String> legacyTenantOwnedTables() {
		return tenantOwnedFrom(readResource(VENDORED_SCHEMA));
	}

	/**
	 * The tenant-owned tables one schema text declares.
	 *
	 * <p>Split from the file read for the same reason
	 * {@link #writtenTenantTables(String, Set, Map)} was: so a synthetic schema can
	 * be measured with the production rule rather than with a copy of it. Asserting
	 * the canonical form against {@link #tenantOwnedTables()} cannot fail -- that set
	 * is built by calling {@link #canonical} -- and this class has already recorded
	 * once, in the tenth round, what an assertion that cannot fail is worth.
	 */
	private static Set<String> tenantOwnedFrom(String schema) {
		Set<String> tenant = new HashSet<>();
		Matcher table = CREATE_TABLE.matcher(schema);
		while (table.find()) {
			Set<String> columns = new HashSet<>();
			Matcher column = COLUMN_NAME.matcher(table.group(2));
			while (column.find()) {
				// Column names are case-insensitive in MySQL unconditionally, unlike
				// table names -- a re-vendored dump writing `Company_Id` would drop
				// the whole table out of the ground truth.
				columns.add(canonical(column.group(1)));
			}
			// Directly owned, or owned through the employee -- the two shapes
			// R-059 had to distinguish. Both make a row somebody's.
			if (columns.contains("company_id") || columns.contains("employee_id")) {
				tenant.add(canonical(table.group(1)));
			}
		}
		return tenant;
	}

	/**
	 * This repository's own half, by the same test on the same columns.
	 *
	 * <p>{@code upgrade_device_agents_and_delivery.sql} re-declares
	 * {@code device_agents} with {@code IF NOT EXISTS}; a set absorbs that.
	 */
	private static Set<String> phase1TenantOwnedTables() {
		Set<String> tenant = new HashSet<>();
		for (Path file : phase1SchemaFiles()) {
			tenant.addAll(phase1TenantOwnedFrom(read(file)));
		}
		return tenant;
	}

	/**
	 * The Phase 1 half, split for the same reason {@link #tenantOwnedFrom} was.
	 *
	 * <p>It keeps its own {@link #canonical} calls -- the two readers parse
	 * different shapes and share no loop -- so pinning one pins one. The twelfth
	 * round's test measured the vendored reader only, and removing {@code canonical}
	 * from <em>this</em> one left every test in the class green.
	 */
	private static Set<String> phase1TenantOwnedFrom(String schema) {
		Set<String> tenant = new HashSet<>();
		Matcher table = CREATE_TABLE_PHASE1.matcher(schema);
		while (table.find()) {
			Set<String> columns = new HashSet<>();
			Matcher column = COLUMN_NAME_PHASE1.matcher(table.group(2));
			while (column.find()) {
				columns.add(canonical(column.group(1)));
			}
			if (columns.contains("company_id") || columns.contains("employee_id")) {
				tenant.add(canonical(table.group(1)));
			}
		}
		return tenant;
	}

	private static List<Path> phase1SchemaFiles() {
		try (Stream<Path> tree = Files.walk(PHASE1_SCHEMA)) {
			List<Path> files = tree.filter(Files::isRegularFile)
					.filter(path -> path.getFileName().toString().endsWith(".sql"))
					.sorted()
					.toList();
			assertThat(files).as("the Phase 1 schema is half of this test's ground truth; "
					+ "an empty read would silently narrow every rule below").isNotEmpty();
			return files;
		}
		catch (IOException ex) {
			throw new IllegalStateException("could not read " + PHASE1_SCHEMA.toAbsolutePath(), ex);
		}
	}

	/**
	 * Service simple name to the names of its store's methods that write a
	 * tenant-owned table.
	 *
	 * <p>Per method rather than per store, because rule two now asks which
	 * service methods reach a write, and that answer needs the writes named.
	 */
	private static Map<String, Set<String>> storeWriteMethodsByService(Set<String> tenantTables) {
		Map<String, Set<String>> byService = new LinkedHashMap<>();
		// Read once: it parses every entity in the repository and does not depend
		// on the store being walked.
		Map<String, String> entities = entityTables();
		for (Path store : pairedStores()) {
			String stem = store.getFileName().toString().replace("Store.java", "");
			Set<String> writes = new TreeSet<>();
			String source = read(store);
			for (Map.Entry<String, List<String>> method : methodBodies(source).entrySet()) {
				// The same predicate rule three uses, not a second copy of it. Two
				// copies is how the tenth round's finding happened: one was
				// normalised for case and the other was not, and the one that was
				// is unreferenced (#335). Verified behaviour-preserving before
				// switching -- both forms produce the identical collector today.
				for (String overload : method.getValue()) {
					if (!writtenTenantTables(overload, tenantTables, entities).isEmpty()) {
						writes.add(method.getKey());
						break;
					}
				}
			}
			if (!writes.isEmpty()) {
				byService.put(stem + "AdminService", writes);
			}
		}
		return byService;
	}

	/** Service simple name to the tenant-owned tables its paired store writes. */
	private static Map<String, Set<String>> servicesWritingTenantTables(Set<String> tenantTables) {
		Map<String, Set<String>> byService = new LinkedHashMap<>();
		for (Path store : files("*Store.java")) {
			String stem = store.getFileName().toString().replace("Store.java", "");
			Path service = serviceFile(stem + "AdminService");
			if (service == null) {
				continue;
			}
			Set<String> written = new TreeSet<>();
			// Java concatenation is collapsed so a statement split across lines
			// reads as one.
			String flattened = flattened(read(store));
			Matcher write = WRITE_STATEMENT.matcher(flattened);
			while (write.find()) {
				String table = canonical(write.group(1));
				if (tenantTables.contains(table)) {
					written.add(table);
				}
			}
			if (!written.isEmpty()) {
				byService.put(stem + "AdminService", written);
			}
		}
		return byService;
	}

	private static boolean reachesGuard(String body, Map<String, List<String>> bodies) {
		return reachesGuard(body, bodies, new HashSet<>(), 0);
	}

	/**
	 * Comments and string literals, gone, so that naming a guard cannot stand in
	 * for calling one.
	 *
	 * <p>`// see session.companyId() for why this is fine` used to satisfy rule
	 * one, which is the gate laundering itself with prose.
	 */
	private static String code(String body) {
		return body
				.replaceAll("(?s)/\\*.*?\\*/", " ")
				.replaceAll("(?m)//[^\n]*", " ")
				.replaceAll("\"(?:\\\\.|[^\"\\\\])*\"", "\"\"");
	}

	/**
	 * Is this guard match load-bearing, or is its answer thrown away?
	 *
	 * <p>{@code session.companyId();} as a statement of its own compares nothing
	 * and denies nobody, yet it matches {@link #TENANT_GUARD} exactly as
	 * {@code if (owner != session.companyId())} does. The statement around the
	 * match decides: a bare call expression is discarded, anything else --
	 * assigned, compared, returned, passed on -- is used.
	 */
	private static boolean used(String code, int start, int end) {
		int from = Math.max(Math.max(code.lastIndexOf(';', start), code.lastIndexOf('{', start)),
				code.lastIndexOf('}', start)) + 1;
		int semicolon = code.indexOf(';', end);
		String statement = code.substring(from, semicolon < 0 ? code.length() : semicolon).trim();
		return !statement.matches("(?:[\\w.]*\\.)?(?:companyId|isScopedToOneCompany|canOpenRow)"
				+ "\\s*\\([^()]*\\)");
	}

	/**
	 * The guard may be a helper, and the helper may be a helper: payroll's
	 * {@code assertBatchVisible} defers to {@code assertVisible}, which is where
	 * the session comparison actually lives. Three levels is enough for every
	 * shape on this surface and stops a cycle from running away.
	 */
	private static boolean reachesGuard(
			String body, Map<String, List<String>> bodies, Set<String> seen, int depth) {
		String code = code(body);
		Matcher guard = TENANT_GUARD.matcher(code);
		while (guard.find()) {
			if (used(code, guard.start(), guard.end())) {
				return true;
			}
		}
		if (depth >= 3) {
			return false;
		}
		// On `code`, not on `body`. The guard match three lines up already strips
		// comments and string literals; the callee walk did not, so a comment that
		// merely NAMED a guarded helper -- "the row was resolved by
		// assertRowVisible(session, id) upstream" -- satisfied rule one. That is the
		// laundering this class's own javadoc says it forbids, one level down, and
		// it is this repository's commenting style.
		Matcher call = Pattern.compile("\\b(\\w+)\\s*\\(").matcher(code);
		while (call.find()) {
			String callee = call.group(1);
			if (!bodies.containsKey(callee) || !seen.add(callee)) {
				continue;
			}
			// Every overload must guard. One that does not is the one the call
			// might be reaching, and a guard it does not have cannot be borrowed
			// from a sibling that does.
			List<String> overloads = bodies.get(callee);
			boolean allGuard = !overloads.isEmpty();
			for (String overload : overloads) {
				if (!reachesGuard(overload, bodies, seen, depth + 1)) {
					allGuard = false;
					break;
				}
			}
			if (allGuard) {
				return true;
			}
		}
		return false;
	}

	private static Map<String, String> publicMethodBodies(String source) {
		Map<String, String> bodies = new LinkedHashMap<>();
		// Masked, like methodBodies: a `public T name(...) {` inside a javadoc or a
		// string is otherwise a phantom method whose body is the next real block,
		// and because this map is keyed on the signature a phantom can replace a
		// real one. No drift today -- 350 matches either way across the 43 scanned
		// files -- and the two collectors reading the same text is the point.
		Matcher method = PUBLIC_METHOD.matcher(maskNonCode(source).code());
		while (method.find()) {
			String signature = method.group(1) + "(" + normalise(method.group(2)) + ")";
			bodies.put(signature, blockAt(source, method.end() - 1));
		}
		return bodies;
	}

	/**
	 * Every method on the class, by name, for following helper calls.
	 *
	 * <p><b>Every overload, kept separately.</b> This was {@code putIfAbsent}, which
	 * kept the first declaration of each name, so a later overload's body was in the
	 * file and in no value here -- and a call site names a method without its
	 * parameters, so rule two asked the first overload whether the name writes and
	 * took that for the answer. The twelfth round reproduced it: a second
	 * {@code belongsToCompany} on {@code EmployeeStore} doing
	 * {@code DELETE FROM employees}, reached from a sessionless service method,
	 * passed every test in this class.
	 *
	 * <p>The fix for that was to <em>concatenate</em> the overloads, and the
	 * thirteenth round showed why a list is needed instead: <b>the two rules want
	 * opposite approximations, so one merged body cannot serve both.</b> Rule two
	 * asks "does this name reach a write", where seeing every overload is the safe
	 * answer -- over-demanding a session costs an exemption with a reason. Rule one
	 * asks "does this name reach a tenant guard", where seeing every overload is the
	 * <em>unsafe</em> answer: an unguarded overload inherits its sibling's guard and
	 * a sessionless write is reported as guarded. Reproduced with a red control --
	 * an unguarded {@code allow(long)} beside a guarded {@code allow(Session, long)}
	 * passed at that head and fails at the one before it.
	 *
	 * <p>So the callers choose: {@link #reachesCall} succeeds if <b>any</b> overload
	 * reaches the write, {@link #reachesGuard} only if <b>every</b> one reaches a
	 * guard. Both err towards demanding more.
	 */
	private static Map<String, List<String>> methodBodies(String source) {
		Map<String, List<String>> bodies = new HashMap<>();
		Matcher method = ANY_METHOD.matcher(maskNonCode(source).code());
		while (method.find()) {
			bodies.computeIfAbsent(method.group(1), name -> new ArrayList<>())
					.add(blockAt(source, method.end() - 1));
		}
		return bodies;
	}

	/**
	 * The braced block whose opening brace is at or after {@code from}.
	 *
	 * <p>The braces are counted over {@link #maskNonCode}'s output and the text is
	 * cut from the <em>real</em> source, because the SQL this class looks for lives
	 * in the string literals the mask blanks. Counting on the raw text let one
	 * {@code String brace = "{";} in a service method swallow the rest of its class,
	 * so that method inherited every other method's tenant guard -- a defeat of rule
	 * one, found by the thirteenth round and older than the round that introduced
	 * {@link #methodSpans}.
	 */
	private static String blockAt(String source, int from) {
		int[] span = braceSpan(maskNonCode(source).code(), from);
		return span == null ? "" : source.substring(span[0], span[1]);
	}

	private static String name(String signature) {
		return signature.substring(0, signature.indexOf('('));
	}

	private static String normalise(String parameters) {
		return String.join(" ", parameters.split("\\s+")).trim();
	}

	/**
	 * No two classes share a simple name, because the index rule three walks is
	 * keyed on one.
	 *
	 * <p>{@link #classesByName()} maps a simple name to a path with
	 * {@code putIfAbsent}, and rule three decides a file is already scanned by
	 * comparing file <em>names</em>. A second {@code EmployeeStore} anywhere under
	 * {@code com.workin} would therefore be dropped from the index outright: the
	 * closure could not reach it, no rule would scan it, and it would be neither
	 * <em>found</em> nor <em>unaccounted for</em> -- the silence in both directions
	 * this ratchet exists to remove. An {@code ACCOUNTED_FOR_OUTSIDE_THE_RULES}
	 * entry would also go on naming a class while describing a different file.
	 *
	 * <p>The seventh round established it with a mutant: a second
	 * {@code EmployeeStore} deleting from {@code employees}, imported by a class in
	 * the admin root, left the class count, the reachable count and {@code found}
	 * all unchanged. The same class named {@code WidgetStore} was caught.
	 *
	 * <p>This repository mirrors package structure deliberately
	 * ({@code platformadmin.hr}, {@code legacy}, {@code devices} all name the same
	 * concepts), so a collision is a plausible accident and not a hypothetical.
	 * Forbidding it is three lines; making the index path-keyed would change what
	 * every rule below compares. It is also what the closure's "never less" promise
	 * needs in order to be true.
	 *
	 * <p>{@code package-info.java} is exempt, and is the only name repeated today:
	 * it declares no class, so it can write nothing, and one per package is the
	 * whole point of it.
	 */
	@Test
	void noTwoClassesShareASimpleName() {
		Map<String, List<Path>> byName = new LinkedHashMap<>();
		try (Stream<Path> tree = Files.walk(MAIN_ROOT)) {
			tree.filter(Files::isRegularFile)
					.filter(path -> path.getFileName().toString().endsWith(".java"))
					.sorted()
					.forEach(path -> byName
							.computeIfAbsent(path.getFileName().toString(), key -> new ArrayList<>())
							.add(path));
		}
		catch (IOException ex) {
			throw new IllegalStateException("could not read " + MAIN_ROOT.toAbsolutePath(), ex);
		}
		byName.remove("package-info.java");
		assertThat(byName).as("the main sources must be findable, or this passes vacuously")
				.isNotEmpty();

		Map<String, List<Path>> collisions = new LinkedHashMap<>(byName);
		collisions.values().removeIf(paths -> paths.size() == 1);
		assertThat(collisions)
				.as("two classes with one simple name: the index keeps one and the walk never "
						+ "sees the other, so a write in it fails no assertion in either direction")
				.isEmpty();
	}

	/**
	 * One canonical case for every table or column name this rule compares.
	 *
	 * <p>{@link #WRITE_STATEMENT} is case-insensitive, so the name it captures
	 * carries whatever case the source wrote; the schema readers carry whatever case
	 * the {@code CREATE TABLE} declared. Comparing the two raw means a table
	 * declared {@code Timesheets} and written {@code timesheets} is not the same
	 * table, and the write is invisible -- neither <em>found</em> nor
	 * <em>unaccounted for</em>.
	 *
	 * <p>It is a named call rather than an inline {@code toLowerCase} because of how
	 * the tenth round found the gap: the previous commit normalised the comparisons
	 * it could see and missed one, and the one it happened to fix was in
	 * {@code servicesWritingTenantTables} -- the unreferenced twin (#335) -- while
	 * rule two's live collector went on comparing raw. With one named form, a
	 * comparison that does not use it is visibly the odd one out.
	 */
	private static String canonical(String name) {
		return name.toLowerCase(java.util.Locale.ROOT);
	}

	/** Every class under {@code com.workin}, by simple name, for the walk below. */
	private static Map<String, Path> classesByName() {
		Map<String, Path> byName = new LinkedHashMap<>();
		try (Stream<Path> tree = Files.walk(MAIN_ROOT)) {
			tree.filter(Files::isRegularFile)
					.filter(path -> path.getFileName().toString().endsWith(".java"))
					.sorted()
					.forEach(path -> {
						String name = path.getFileName().toString().replace(".java", "");
						byName.putIfAbsent(name, path);
					});
		}
		catch (IOException ex) {
			throw new IllegalStateException("could not read " + MAIN_ROOT.toAbsolutePath(), ex);
		}
		assertThat(byName).as("the main sources must be findable, or rule three passes vacuously")
				.isNotEmpty();
		return byName;
	}

	/**
	 * What rules one and two actually open, by file name.
	 *
	 * <p>Derived from the same calls those rules make rather than restated, so a
	 * change to either rule's selection changes what rule three considers covered.
	 * A second copy of that logic here would drift, and it would drift towards
	 * claiming more coverage than exists.
	 */
	private static Set<String> scannedByRuleOneOrTwo() {
		Set<String> scanned = new HashSet<>();
		adminServices().forEach(path -> scanned.add(path.getFileName().toString()));
		pairedStores().forEach(store -> scanned.add(store.getFileName().toString()));
		return scanned;
	}

	/**
	 * The stores rule two walks: an {@code <X>Store.java} with an
	 * {@code <X>AdminService.java} beside it.
	 *
	 * <p>One definition, because three had to agree -- rule two's collector, rule
	 * three's idea of what was scanned, and the check that rule two saw the whole
	 * file. Widening any one of them without the others is how rule three comes to
	 * claim coverage that the check above never looked for.
	 */
	private static List<Path> pairedStores() {
		List<Path> paired = new ArrayList<>();
		for (Path store : files("*Store.java")) {
			String stem = store.getFileName().toString().replace("Store.java", "");
			if (serviceFile(stem + "AdminService") != null) {
				paired.add(store);
			}
		}
		return paired;
	}

	/**
	 * The classes the admin surface can reach, as a transitive closure over
	 * references from the admin root outward.
	 *
	 * <p>Comments are stripped first, so a {@code @link} in a javadoc does not
	 * invent a call the code never makes -- which would demand an entry for a
	 * class the admin surface merely talks about. The closure over-approximates in
	 * the safe direction otherwise: it follows any reference in code, so it
	 * ordinarily asks for more accounting than strictly necessary.
	 *
	 * <p>It is not <em>incapable</em> of asking for less, and saying so plainly is
	 * worth more than the reassurance: a class reached only through an interface it
	 * implements is invisible to a walk over class names, because the name in the
	 * calling code is the interface's. The shape exists here --
	 * {@code PlatformAdminCompanyService} injects {@code PlatformAdminCompanyDirectory}
	 * -- and hides nothing, because that interface's only implementation,
	 * {@code LegacyPlatformAdminCompanyDirectory}, sits inside the admin root and is
	 * accounted for by name. A simple-name collision is the other way to ask for
	 * less, and {@link #noTwoClassesShareASimpleName} forbids it outright.
	 */
	private static Set<String> reachableFromAdminSurface(Map<String, Path> byName) {
		Map<Path, Set<String>> siblings = classesByPackage(byName);
		Set<String> seen = new HashSet<>();
		List<String> frontier = new ArrayList<>();
		for (Path path : files("*.java")) {
			String name = path.getFileName().toString().replace(".java", "");
			if (seen.add(name)) {
				frontier.add(name);
			}
		}
		while (!frontier.isEmpty()) {
			List<String> next = new ArrayList<>();
			for (String name : frontier) {
				Path file = byName.get(name);
				if (file == null) {
					continue;
				}
				for (String referenced : referencedClasses(read(file), byName.keySet(),
						siblings.getOrDefault(file.getParent(), Set.of()))) {
					if (seen.add(referenced)) {
						next.add(referenced);
					}
				}
			}
			frontier = next;
		}
		return seen;
	}

	/**
	 * @param packageSiblings the other classes declared in this file's own package,
	 *        which Java lets it name with no import and no qualification at all
	 */
	private static Set<String> referencedClasses(
			String source, Set<String> known, Set<String> packageSiblings) {
		String code = source.replaceAll("(?s)/\\*.*?\\*/", " ").replaceAll("//[^\n]*", " ");
		Set<String> referenced = new HashSet<>();
		Matcher imported = IMPORTED_CLASS.matcher(code);
		while (imported.find()) {
			referenced.add(imported.group(1));
		}
		Matcher qualified = QUALIFIED_CLASS.matcher(code);
		while (qualified.find()) {
			referenced.add(qualified.group(1));
		}
		// A same-package reference has neither an import nor a qualifier -- it is a
		// bare `DeviceAgentStore agents;` -- so matching only the two patterns above
		// misses every edge inside a package. That is not hypothetical: it hid four
		// real writers, and the rule below claimed exhaustiveness it did not have.
		// Restricted to this file's own siblings, so a bare capitalised word cannot
		// pull in an unrelated class that merely shares a name.
		if (!packageSiblings.isEmpty()) {
			Matcher bare = BARE_CLASS.matcher(code);
			while (bare.find()) {
				if (packageSiblings.contains(bare.group(1))) {
					referenced.add(bare.group(1));
				}
			}
		}
		referenced.retainAll(known);
		return referenced;
	}

	/** Which classes share each package directory, for the same-package edges above. */
	private static Map<Path, Set<String>> classesByPackage(Map<String, Path> byName) {
		Map<Path, Set<String>> byPackage = new LinkedHashMap<>();
		for (Map.Entry<String, Path> entry : byName.entrySet()) {
			byPackage.computeIfAbsent(entry.getValue().getParent(), key -> new HashSet<>())
					.add(entry.getKey());
		}
		return byPackage;
	}

	/** Which tenant-owned tables one file writes, by the same reading rule two uses. */
	private static Set<String> writtenTenantTables(Path file, Set<String> tenantTables) {
		return writtenTenantTables(read(file), tenantTables, entityTables());
	}

	/**
	 * The tenant-owned tables one file's source writes.
	 *
	 * <p>Split from the file read so a synthetic source can be measured with
	 * the production rule rather than a copy of it.
	 */
	private static Set<String> writtenTenantTables(
			String source, Set<String> tenantTables, Map<String, String> entityTables) {
		String flattened = flattened(source);
		Set<String> written = new java.util.TreeSet<>();
		// A repository declared over a tenant-owned entity can write that table
		// with no statement anywhere in its source -- see JPA_REPOSITORY.
		Matcher repository = JPA_REPOSITORY.matcher(flattened);
		while (repository.find()) {
			String inherited = entityTables.get(repository.group(1));
			if (inherited != null && tenantTables.contains(inherited)) {
				written.add(inherited);
			}
		}
		Matcher write = WRITE_STATEMENT.matcher(flattened);
		while (write.find()) {
			// A JPQL write names the entity; the table it lands in is what the
			// schema calls tenant-owned. Anything that is not an entity name is
			// already a table name.
			String named = canonical(write.group(1));
			String table = entityTables.getOrDefault(write.group(1), named);
			if (tenantTables.contains(table)) {
				written.add(table);
			}
		}
		return written;
	}

	/**
	 * Entity simple name to table name.
	 *
	 * <p>A {@code @Modifying @Query} writes in JPQL, which names the entity:
	 * {@code update LegacyEmployee e set e.tokenVersion = ...} lands in
	 * {@code employees}. {@link #WRITE_STATEMENT} matches that text perfectly
	 * well and then compares "LegacyEmployee" against a set of table names,
	 * concludes nothing tenant-owned was written, and moves on -- so a whole
	 * category of write was invisible to a rule whose product is an exhaustive
	 * claim. Translating the name is all it takes, because JPQL's verbs are
	 * SQL's.
	 *
	 * <p>No entity here overrides its name ({@code @Entity(name = ...)}), so
	 * the class name is the entity name, and every one declares its table
	 * explicitly.
	 */
	private static Map<String, String> entityTables() {
		Map<String, String> tables = new LinkedHashMap<>();
		classesByName().forEach((name, path) -> {
			Matcher table = ENTITY_TABLE.matcher(read(path));
			if (table.find()) {
				tables.put(name, canonical(table.group(1)));
			}
		});
		assertThat(tables).as("the entities must be findable, or a JPQL write reads as no write at all")
				.isNotEmpty();
		return tables;
	}

	private static List<Path> adminServices() {
		List<Path> services = files("*AdminService.java");
		assertThat(services).as("the admin services must be findable from the test's "
				+ "working directory, or both rules pass vacuously").isNotEmpty();
		return services;
	}

	private static Path serviceFile(String simpleName) {
		return files(simpleName + ".java").stream().findFirst().orElse(null);
	}

	private static List<Path> files(String glob) {
		try (Stream<Path> tree = Files.walk(ADMIN_ROOT)) {
			return tree.filter(Files::isRegularFile)
					.filter(path -> path.getFileSystem().getPathMatcher("glob:" + glob)
							.matches(path.getFileName()))
					.sorted()
					.toList();
		}
		catch (IOException ex) {
			throw new IllegalStateException("could not read " + ADMIN_ROOT.toAbsolutePath(), ex);
		}
	}

	private static String read(Path file) {
		try {
			return Files.readString(file, StandardCharsets.UTF_8);
		}
		catch (IOException ex) {
			throw new IllegalStateException("could not read " + file, ex);
		}
	}

	private static String readResource(String resource) {
		try (InputStream stream =
				AdminTenantGuardCoverageTest.class.getClassLoader().getResourceAsStream(resource)) {
			assertThat(stream).as("the vendored schema is this test's ground truth").isNotNull();
			return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
		}
		catch (IOException ex) {
			throw new IllegalStateException("could not read " + resource, ex);
		}
	}

}
