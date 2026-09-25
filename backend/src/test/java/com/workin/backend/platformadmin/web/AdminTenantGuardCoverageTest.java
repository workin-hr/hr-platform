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
 * <p><b>Used is as far as this rule goes.</b> It does not check that the company
 * the guard resolved is the one the write touches: a {@code session.companyId()}
 * passed to an audit call counts as used, because this class reads text and not
 * data flow. {@link #used} is where that approximation lives and says so; it is
 * named here as well, because a limit stated only beside the helper is a limit
 * the reader of the rule does not have.
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
	 * Store writes reached from a class neither rule scans, and accounted for, keyed
	 * on {@code <file>: <field>.<method>()} with the reason.
	 *
	 * <p>Keyed on the <b>call</b> and not on the field's type, deliberately.
	 * Skipping any field whose type is in
	 * {@link #ACCOUNTED_FOR_OUTSIDE_THE_RULES} would be the shorter spelling and
	 * the wrong one: those entries each state why <em>their own</em> writers are
	 * safe, not that any class may call them. {@code LegacyPayrollBatchStore}'s
	 * reason is literally "rule one enforces the guard, one layer above", which
	 * says nothing about a second caller, and the device stores rest on the device
	 * endpoint's own authentication. A type-keyed skip licences every future caller
	 * of all of them at once; a call-keyed one names the caller, so a second one has
	 * to be read.
	 *
	 * <p>Self-policing in both directions, like
	 * {@link #ACCOUNTED_FOR_OUTSIDE_THE_RULES}: a call absent from this map fails
	 * the rule, and a key here the rule no longer reports fails it too, so a call
	 * that moves or gains a guard cannot leave a licence behind.
	 *
	 * <p>Every reason below was read at the call site, not inferred from the store's
	 * entry. A handful of shapes account for all thirty-nine, and the shape is the reason:
	 * the company is an argument of the write itself, or a company-scoped read of
	 * the same row precedes it, or the surface reaching it is the platform
	 * administrator's, who is cross-company by design.
	 */
	private static final Map<String, String> REACHED_WRITES_ACCOUNTED_FOR = reachedWrites();

	/**
	 * The company is an argument of the write, not something resolved beside it.
	 *
	 * <p>The strongest of the three shapes, and the reason a caller cannot weaken
	 * it: the statement is predicated on the company it is handed, so it cannot
	 * address another tenant's row whatever the caller passes -- a wrong company
	 * writes nothing rather than writing somebody else's row. The company reaching
	 * these calls is resolved from the authenticated device, the agent's presented
	 * token, or a device row already read, never from a request parameter.
	 */
	private static final String COMPANY_IS_AN_ARGUMENT_OF_THE_WRITE =
			"The company is an argument of this write, so the statement is predicated on it and a "
					+ "wrong company writes no rows rather than another tenant's. It is resolved "
					+ "from the authenticated device, the agent's token, or a device row already "
					+ "read -- never from a request parameter.";

	/**
	 * The row is resolved against the company before it is written.
	 *
	 * <p>The legacy payroll idiom, and D-176's shape one surface over: the write is
	 * by bare id, and the same method first reads that id scoped to the company --
	 * {@code store.withBatchStatus(payslipId, companyId)},
	 * {@code store.scoped(batchId, companyId)},
	 * {@code store.scopedForUpdate(batchId, companyId)} -- and stops when it comes
	 * back empty. An insert has no prior row of its own, so what is resolved first
	 * is its parents: the batch and the employee.
	 */
	private static final String ROW_RESOLVED_AGAINST_THE_COMPANY_FIRST =
			"The write is by bare id, and the same method resolves that id against the company "
					+ "first -- the row itself for an update or a delete, its batch and its "
					+ "employee for an insert -- and stops when the scoped read comes back empty. "
					+ "The companyId is the legacy API's own tenant parameter, covered by "
					+ "TenantFilterCoverageTest, the tenant filter and LegacyTenantContext.";

	/** Reachable only as a platform administrator, who is cross-company by design. */
	private static final String PLATFORM_ADMINISTRATOR_ONLY =
			"Reachable only as a platform administrator, who is cross-company by design (R-044's "
					+ "deliberate exception, R-061), so no company-scoped session reaches it and "
					+ "there is no session company to compare it against.";

	private static Map<String, String> reachedWrites() {
		Map<String, String> accounted = new LinkedHashMap<>();
		for (String call : List.of(
				"DeviceAdministrationService.java: devices.update()",
				"DeviceAgentService.java: agents.create()",
				"DeviceFileImportService.java: malformedPunches.quarantine()",
				"DeviceManagementService.java: devices.claimWithHistory()",
				"DeviceManagementService.java: devices.update()",
				"DeviceManagementService.java: identities.bind()",
				"DeviceManagementService.java: punches.adoptUnmatched()",
				"DeviceManagementService.java: punches.confirmInferredAssignment()",
				"DevicePunchIngestionService.java: punches.insert()",
				"DevicePunchIngestionService.java: punches.adoptUnmatched()")) {
			accounted.put(call, COMPANY_IS_AN_ARGUMENT_OF_THE_WRITE);
		}
		// `store.insert()` was in the group below until review read its call site:
		// `LegacyPayrollBatchService.create` calls
		// `store.insert(companyId, month, year, ...)`, an INSERT whose company_id is
		// that argument. It is not by bare id, nothing precedes it but
		// `existsForPeriod(companyId, month, year)`, and a payroll batch has no batch
		// or employee parent for that group's sentence to be about. The stronger of
		// the two shapes was the true one.
		accounted.put("LegacyPayrollBatchService.java: store.insert()",
				COMPANY_IS_AN_ARGUMENT_OF_THE_WRITE);
		for (String call : List.of(
				"LegacyPayrollBatchService.java: store.updatePeriod()",
				"LegacyPayrollBatchService.java: store.finalizeBatchIfNotAlready()",
				"LegacyPayslipService.java: store.insert()",
				"LegacyPayslipService.java: store.delete()",
				"LegacyPayslipService.java: store.update()")) {
			accounted.put(call, ROW_RESOLVED_AGAINST_THE_COMPANY_FIRST);
		}
		// The platform-admin device chain, and the company delete's controller. Each
		// of these is a hop the resolver follows because the callee takes no
		// DashboardSession -- which is true of this chain by design, since the actor
		// is a platform administrator and the surface is deliberately cross-company.
		for (String call : List.of(
				"AdminDevicesController.java: actions.allocate()",
				"AdminDevicesController.java: actions.importAttlog()",
				"AdminDevicesController.java: actions.issueAgent()",
				"AdminDevicesController.java: actions.setActive()",
				"AdminDeviceActions.java: devices.allocate()",
				"AdminDeviceActions.java: devices.importAttlog()",
				"AdminDeviceActions.java: devices.issueAgent()",
				"AdminDeviceActions.java: devices.setActive()",
				"DeviceAdministrationService.java: agents.issue()",
				"DeviceAdministrationService.java: fileImport.importAttlog()",
				"DeviceAdministrationService.java: management.allocate()",
				"PlatformAdminCompaniesController.java: companyService.delete()")) {
			accounted.put(call, PLATFORM_ADMINISTRATOR_ONLY
					+ " The whole chain is AdminDevicesController and PlatformAdminCompaniesController "
					+ "on PlatformAdminWebSecurityConfig's paths, where `anyRequest().authenticated()` "
					+ "and only the login page and the assets are public. Each action takes the "
					+ "administrator's id rather than a session and writes an audit row naming them "
					+ "-- AdminDeviceActions does so five times, once per action -- and the company "
					+ "each write uses is read off the device, branch or company row the method "
					+ "resolved first, not off the request. The one exception is issueAgent, whose "
					+ "companyId is the posted form field, existence-checked in "
					+ "DeviceAdministrationService.issueAgent and audited as posted, which "
					+ "DeviceAgentStore's own entry states.");
		}
		// Agent deactivation, all three layers of it, and the one path in this chain
		// where no company is resolved before the write. The group above says the
		// company is read off a row the method resolved first, and review found that
		// sentence false here: DeviceAdministrationService calls
		// `agents.setActive(agentId, active)` immediately, the store's statement is
		// `UPDATE device_agents SET is_active = ?, updated_at = ? WHERE id = ?`, and
		// the audit row is written AFTER it, reading the company off the row that
		// comes back. DeviceAgentStore's own entry said exactly that and warned that
		// the two must not drift; they had. One sentence generalised across a group
		// of methods, for the fourth time in this file.
		for (String call : List.of(
				"AdminDevicesController.java: actions.setAgentActive()",
				"AdminDeviceActions.java: devices.setAgentActive()",
				"DeviceAdministrationService.java: agents.setActive()")) {
			accounted.put(call, "Agent deactivation, by bare agent id: no company predicate and "
					+ "no company resolved above the write, and the audit row is written after it "
					+ "from the row that comes back rather than before it from a row that was "
					+ "checked. " + PLATFORM_ADMINISTRATOR_ONLY + " That is the whole of the "
					+ "control here, and DeviceAgentStore's entry says the same of the same "
					+ "write.");
		}
		// The interface hop. `PlatformAdminCompanyService` holds
		// `PlatformAdminCompanyDirectory`, an interface, beside the concrete
		// `LegacyCompanyDelete`, and only the concrete field was reported until the
		// resolver learned to follow an interface to its implementation.
		accounted.put("PlatformAdminCompanyService.java: companies.create()",
				"Creating a company and its first branch, so there is no prior owner to compare "
						+ "anything against -- the INSERT's company_id is the company just made, "
						+ "which is what LegacyPlatformAdminCompanyDirectory's own entry states. "
						+ PLATFORM_ADMINISTRATOR_ONLY + " PlatformAdminCompanyService.create takes "
						+ "the administrator's id and writes an audit row naming them.");
		accounted.put("PlatformAdminCompanyService.java: companies.update()",
				"Editing a company's main branch. The branch id is resolved inside the company "
						+ "being edited -- `SELECT id FROM branches WHERE company_id = ? ORDER BY "
						+ "id ASC LIMIT 1` -- and the UPDATE writes that id; where the company has "
						+ "no branch yet the INSERT carries the same companyId. So the id the "
						+ "write uses cannot be another company's, which is the same shape as the "
						+ "legacy payroll services one surface over. " + PLATFORM_ADMINISTRATOR_ONLY);
		for (String call : List.of(
				"PlatformAdminCompaniesController.java: companyService.create()",
				"PlatformAdminCompaniesController.java: companyService.update()")) {
			accounted.put(call, "The controller of the two calls above, one hop further out, and "
					+ "the reason is theirs: it passes `principal.platformAdminId()` and the "
					+ "service resolves the branch inside the company or creates both together. "
					+ PLATFORM_ADMINISTRATOR_ONLY);
		}
		accounted.put("DeviceFileImportService.java: ingestion.ingest()",
				"Not a surface call: the argument is the device row the caller already resolved "
						+ "(requireDevice on the admin path, the presented serial or agent token on "
						+ "the device path), and DevicePunchIngestionService predicates every write "
						+ "on device.companyId() taken from it. " + COMPANY_IS_AN_ARGUMENT_OF_THE_WRITE);
		accounted.put("DeviceAgentService.java: agents.setActive()",
				"`UPDATE device_agents SET is_active = ? WHERE id = ?`, with no company predicate "
						+ "and no company resolved above it -- the one write here that is neither "
						+ "of the other two shapes. " + PLATFORM_ADMINISTRATOR_ONLY
						+ " AdminDevicesController builds DashboardSession.admin(...) and "
						+ "AdminDeviceActions audits the company it reads back off the returned "
						+ "row. DeviceAgentStore's own entry says the same of the same write, "
						+ "which is why the two must not drift.");
		accounted.put("AttendanceDeviceStore.java: history.append()",
				"Store to store, not surface to store: the append records the ownership decision "
						+ "its caller has already made, with the companyId that caller resolved -- "
						+ "from the device row on an update, and from the branch on a claim, where "
						+ "no device row exists yet. DeviceAssignmentHistoryStore's entry is the "
						+ "same statement from the callee's side.");
		accounted.put(
				"PlatformAdminCompanyService.java: companyDelete.cascadeDeleteInCurrentTransaction()",
				"The platform administrator deleting a whole company, and the caller of the "
						+ "cascade whose own entry says why it carries no tenant predicate: the "
						+ "operation's subject IS the company. " + PLATFORM_ADMINISTRATOR_ONLY
						+ " The controls are the ones ADR-0015 names -- the actionsEnabled flag, a "
						+ "deletionTarget that must exist, the company's own name typed back and "
						+ "normalised, and an audit row written before the cascade in the same "
						+ "transaction, so a cascade that fails rolls the row back and a row that "
						+ "cannot be written stops the cascade. A tenant predicate here would be a "
						+ "predicate on the row being deleted.");
		return Map.copyOf(accounted);
	}

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
							+ "the row -- the id is not independently attacker-chosen. Seven, not the "
							+ "six those two groups name: `claimWithHistory` holds no SQL of its own "
							+ "and is a write because it reaches `claim` and appends the assignment "
							+ "row, which is what the gate's own closure counts -- so counting the "
							+ "SELECT-free statements gives six and counting write methods gives "
							+ "seven. Its company is the one DeviceManagementService resolved from "
							+ "the branch under `FOR UPDATE`. This entry has now been corrected three "
							+ "times by review: for saying 'every write takes an explicit companyId', "
							+ "false of those four; for attributing `WHERE company_id = ? AND id = ?` "
							+ "to `claim`, which has no WHERE clause; and for counting statements "
							+ "where the gate counts methods. Every time the error was one sentence "
							+ "generalised across a group of methods; an exemption's shape has to be "
							+ "read off each method."),

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
							+ "LegacyTenantContext). Not 'no admin-surface path reaches them', which "
							+ "is what this entry said and is the wrong claim to rest on: "
							+ "AdminPayrollController holds the service in a field, so the write is "
							+ "one call away, and it is a coverage question rather than a "
							+ "reachability one. The answer is that the write itself is now "
							+ "enumerated -- `LegacyPayslipService.java: store.insert()`, "
							+ "`store.delete()` and `store.update()` are three of the keys in "
							+ "REACHED_WRITES_ACCOUNTED_FOR, each with the scoped read that precedes "
							+ "it -- and so is the controller's own call. That second half was "
							+ "wrong here first: this said rule one would ask a controller where "
							+ "its companyId came from, and rule one scans no controllers. What "
							+ "asks is rule four, once it follows a field whose class holds no SQL "
							+ "of its own, which is the hop `AdminPayrollController` sits on."),

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

	/**
	 * A call on this object, for a walk that is defined as "a helper on the same
	 * class".
	 *
	 * <p>{@link #reachesGuard} walked {@code \b(\w+)\s*\(} until the fifteenth
	 * round, which matches the <em>method name</em> and never looks at the receiver.
	 * This surface names a store's write after the service method that calls it --
	 * {@code delete}, {@code setActive}, {@code approve}, {@code reject},
	 * {@code markPaid}, {@code reply} -- so {@code this.store.delete(id)} resolved to
	 * the service's own guarded {@code delete} and <b>the unguarded write was accepted
	 * as its own guard</b>. Twelve of the twenty-one paired stores have that collision
	 * live. An unguarded {@code purge(DashboardSession, long)} whose body is
	 * {@code this.store.delete(id)} passed all thirty-six tests; renaming the store's
	 * method to {@code removeRow} and changing nothing else failed rule one. That is
	 * R-046 -- delete by posted row id with no tenant check -- which is the defect the
	 * port exists not to import.
	 *
	 * <p>Only {@code reachesGuard} uses this. {@link #reachesCall} must keep following
	 * {@code store.delete(...)}, because reaching the store is the whole point of it,
	 * and it matches {@code wanted} by name before the walk.
	 */
	private static final Pattern OWN_CALL =
			Pattern.compile("(?<![.\\w])(?:this\\s*\\.\\s*)?(\\w+)\\s*\\(");

	/**
	 * A call to a name, however it is spelled: {@code name(} or {@code ::name}.
	 *
	 * <p>One copy, used by every walk of a call graph in this class. It was two
	 * copies for one round, and the copy that lacked the {@code ::} arm lost a store
	 * that handed its own write to a stream. A shared constant cannot drift; a
	 * second literal can, and did.
	 */
	private static final Pattern CALL_OR_REFERENCE =
			Pattern.compile("\\b(\\w+)\\s*\\(|::\\s*(\\w+)");

	/** The name a {@link #CALL_OR_REFERENCE} match reached, from whichever arm matched. */
	private static String calledName(Matcher call) {
		return call.group(1) != null ? call.group(1) : call.group(2);
	}

	/** A call that resolves or enforces the session's company. */
	private static final Pattern TENANT_GUARD = Pattern.compile(
			"\\bcompanyId\\s*\\(\\s*\\)|\\bisScopedToOneCompany\\s*\\(|\\bcanOpenRow\\s*\\(");

	/**
	 * A parameter list, which may itself contain a parenthesis.
	 *
	 * <p>{@code \(([^)]*)\)} until the seventeenth round, so
	 * {@code purge(DashboardSession session, @SuppressWarnings("unused") long id)}
	 * matched neither method pattern <b>nor the sweep</b>, which had copied the
	 * group verbatim -- a session-taking method with no guard, invisible to rule
	 * one, to rule two and to the check that exists to report what the patterns
	 * cannot see. No admin service method carries an annotated parameter today, but
	 * four constructors in the same files do
	 * ({@code @Value("${app.platform-admin.actions.enabled:false}")}), which is one
	 * position away from live house style -- the same argument the sixteenth round
	 * accepted for an annotation in return position.
	 */
	private static final String PARAMETERS = "\\((([^()]|\\([^()]*\\))*)\\)";

	/**
	 * The clause between a signature and its body.
	 *
	 * <p>Both patterns ended at {@code \)\s*\{} until the fifteenth round, so one
	 * conventional keyword took a method out of every rule in this class at once: out
	 * of rule one's subjects, out of rule two's, and out of both reachability walks,
	 * while rule three still counted its file as scanned. Six methods in two of the
	 * twenty-one paired stores already carry one.
	 */
	private static final String THROWS = "(?:\\s*throws[\\w.,@\\s]+?)?";

	/**
	 * Annotations between the modifier and the return type.
	 *
	 * <p>The sixteenth round's second finding, and the same shape as {@link #THROWS}
	 * one position to the left: both patterns spelled the return type
	 * {@code [\w.<>,?\[\]\s]+?}, which has no {@code @}, so
	 * {@code public @Nullable Boolean purge(DashboardSession, long)} matched neither
	 * and rule one never asked it for a guard. It passed all thirty-nine tests; the
	 * identical method without the annotation failed. The shape is live house style
	 * in this very package -- {@code AdminAssetCaching:58} declares
	 * {@code private @Nullable String etag(Resource)}.
	 *
	 * <p>A store is protected positionally when a method goes invisible: the write
	 * then sits in no span and the positional check fires. <b>A service has no such
	 * backstop</b>, so any head-side gap in {@link #PUBLIC_METHOD} is a straight rule
	 * one bypass, which is why the sweep below had to be loosened at the head too.
	 */
	private static final String TYPE_ANNOTATIONS = "(?:@\\w+(?:\\([^)]*\\))?\\s+)*";

	private static final Pattern PUBLIC_METHOD = Pattern.compile(
			"public\\s+" + TYPE_ANNOTATIONS + "[\\w.<>,?\\[\\]\\s]+?\\s(\\w+)\\s*" + PARAMETERS
					+ THROWS + "\\s*\\{",
			Pattern.DOTALL);

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
			"(?:public|private|protected|static)\\s+" + TYPE_ANNOTATIONS
					+ "[\\w.<>,?\\[\\]\\s]+?\\s(\\w+)\\s*" + PARAMETERS + THROWS + "\\s*\\{",
			Pattern.DOTALL);

	/**
	 * A field declaration: the type expression it names, and the name it is held
	 * under.
	 *
	 * <p>Three shapes were outside the first spelling of this, and each of them on
	 * its own made a store write invisible to the rule that reads it:
	 *
	 * <ul>
	 * <li>a <b>fully-qualified type</b>, because the type group was {@code (\w+)}
	 * and a dot is not a word character. This one is live: eight fields under the
	 * admin root are declared with their package, two of them
	 * {@code com.workin.legacy.profile.LegacyCompanyDelete}, which clears
	 * thirty-two tenant tables.
	 * <li>a <b>modifier the pattern did not list</b> -- {@code static} between the
	 * access keyword and the type, which is exactly the eleventh round's finding on
	 * a method, one construct to the left.
	 * <li>an <b>annotation in type position</b>, which is the round before last's
	 * finding on a method, on a field.
	 * <li><b>no access modifier at all.</b> {@code final PenaltyStore store;} is
	 * Java's default access and is constructor-injected exactly as the
	 * {@code private final} form is, and the pattern opened
	 * {@code (?:private|protected|public)\s+}, so deleting one keyword from this
	 * rule's own stated exploit made the field invisible again. This is the
	 * eleventh round's finding a third time -- it found it on a method, where
	 * {@code ANY_METHOD} has a positional backstop; a field has none. There is no
	 * such field today, which is why it is driven by a fixture.
	 * </ul>
	 *
	 * <p>Anchored at a line start and requiring at least one of
	 * {@code static|final|transient|volatile}, because dropping the access modifier
	 * without either would match a bare statement inside a method body --
	 * {@code Row row = ...} is not a field, and treating it as one would report the
	 * write it leads to against whatever local happened to be named.
	 *
	 * <p>The type expression is captured whole and narrowed by {@link #simpleName},
	 * because the index this is resolved against is keyed on the simple name. A
	 * generic or array type is matched so the declaration is <em>seen</em>, but the
	 * writer it may contain is reached through an access this rule does not model --
	 * {@code stores.get(0).delete(id)} names no field followed by the write -- so
	 * {@link #noUnscannedClassHoldsAWriterInsideAContainer} refuses the shape
	 * outright rather than letting it pass as a resolved read. Group 2 is that
	 * decoration, captured for it.
	 */
	private static final Pattern FIELD_DECLARATION = Pattern.compile(
			"(?m)^[ \\t]*(?:(?:private|protected|public)\\s+)?"
					+ "(?:(?:static|final|transient|volatile)\\s+)+"
					+ TYPE_ANNOTATIONS
					+ "([\\w.]+)((?:\\s*<[^;=]*>)?(?:\\s*\\[\\s*\\])*)\\s+(\\w+)\\s*[;=]");

	/**
	 * A type whose methods have no bodies, so reading its own text finds no writes.
	 *
	 * <p>An interface, or an abstract class. Both are ordinary things to hold a field
	 * of, and both made every write behind that field invisible to the rule that reads
	 * declared types.
	 */
	private static final Pattern DECLARES_NO_BODIES =
			Pattern.compile("\\b(?:interface|abstract\\s+class)\\s+\\w+");

	/** {@code implements A, B<C>} -- the names, before the generics are stripped. */
	private static final Pattern IMPLEMENTS_CLAUSE =
			Pattern.compile("\\bimplements\\s+([\\w.,<>\\s]+?)\\s*\\{");

	/** The last segment of a possibly-qualified type name. */
	private static String simpleName(String type) {
		return type.substring(type.lastIndexOf('.') + 1);
	}

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
			Verdict verdict = judge(service, scan, DELIBERATELY_CROSS_TENANT);
			offenders.addAll(verdict.offenders());
			exemptionsStillNeeded.addAll(verdict.stillNeeded());
		}

		assertThat(checked)
				.as("the rule is worthless if it matched nothing. 59 write paths exist today under "
						+ "\"every store the service references\"; it was 55 under \"the paired store\", "
						+ "and this sentence said 55 for one commit after the predicate widened -- which "
						+ "is why the figure names the predicate it belongs to now")
				.isGreaterThan(40);
		assertThat(offenders).isEmpty();
		assertThat(exemptionsStillNeeded)
				.as("every declared cross-tenant exemption must still be a sessionless write, "
						+ "or it is a stale entry to delete")
				.containsExactlyInAnyOrderElementsOf(DELIBERATELY_CROSS_TENANT.keySet());
	}

	/** One service's sessionless and guarded writes, judged against an exemption map. */
	private record Verdict(List<String> offenders, Set<String> stillNeeded) {
	}

	/**
	 * What rule one does with a write it found, given the exemptions in force.
	 *
	 * <p>Extracted so it has a subject. {@link #DELIBERATELY_CROSS_TENANT} is empty
	 * -- no admin service needs the escape hatch today -- so both branches that
	 * consult it were unreachable, and an unreachable branch in a gate is a branch
	 * nothing has ever run. This class has now found that shape three times: the
	 * callee set, the caller set, and this. The fixture below is the subject.
	 */
	private static Verdict judge(String service, WriteScan scan, Map<String, String> exempt) {
		List<String> offenders = new ArrayList<>();
		Set<String> stillNeeded = new HashSet<>();
		for (String method : scan.sessionless()) {
			String key = service + "::" + method;
			if (exempt.containsKey(key)) {
				stillNeeded.add(key);
				continue;
			}
			offenders.add(key + " reaches a store write on a tenant-owned table but takes "
					+ "no DashboardSession, so rule one never sees it");
		}
		for (String method : scan.guarded()) {
			String key = service + "::" + method;
			if (exempt.containsKey(key)) {
				offenders.add(key + " is listed as deliberately cross-tenant but now takes a "
						+ "DashboardSession; the exemption has outlived its reason");
			}
		}
		return new Verdict(offenders, stillNeeded);
	}

	/**
	 * The cross-tenant escape hatch, exercised.
	 *
	 * <p>Both directions, because both are the point: a listed method stops being an
	 * offender, and a listed method that has since gained a session <em>becomes</em>
	 * one. The second is the half that keeps an exemption honest, and with an empty
	 * map it had never executed.
	 */
	@Test
	void aCrossTenantExemptionSilencesAWriteAndExpiresWhenItGainsASession() {
		WriteScan sessionless = new WriteScan(1, List.of("purge"), List.of());
		assertThat(judge("PenaltyAdminService", sessionless, Map.of()).offenders())
				.as("with nothing listed, a sessionless write is an offender")
				.hasSize(1);

		Map<String, String> listed = Map.of("PenaltyAdminService::purge", "a reason");
		Verdict silenced = judge("PenaltyAdminService", sessionless, listed);
		assertThat(silenced.offenders())
				.as("and a listed one is not")
				.isEmpty();
		assertThat(silenced.stillNeeded())
				.as("but it is recorded, so the stale-entry check can see it is still needed")
				.containsExactly("PenaltyAdminService::purge");

		WriteScan nowGuarded = new WriteScan(1, List.of(), List.of("purge"));
		Verdict expired = judge("PenaltyAdminService", nowGuarded, listed);
		assertThat(expired.offenders())
				.as("the same method, now taking a session: the licence has outlived its reason "
						+ "and saying so is the whole value of listing it")
				.hasSize(1);
		assertThat(expired.stillNeeded())
				.as("and it is no longer needed, which is what fails the stale-entry check")
				.isEmpty();
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

		for (Path store : storesRuleTwoReadsWritesFrom()) {
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
				.as("every class rule two takes write names from -- the 21 paired stores and the "
						+ "one other the sixteenth round's widening reached. A glob that stopped "
						+ "matching would make this pass by comparing nothing, and a scope that "
						+ "drifted from the set's would leave a class whose writes rule two wants "
						+ "but whose hidden writes nothing reports")
				.isEqualTo(22);
		assertThat(statements)
				.as("the write statements checked across those 22 classes. Exact, not a floor: "
						+ "this figure was written as 73 and was 74, the third bare number in this "
						+ "class to be wrong, and a prose figure nothing compares against is a "
						+ "number that rots. Pinned, a new write to a tenant-owned table fails "
						+ "here until somebody has read this file, which is the point of it")
				.isEqualTo(74);
		assertThat(hidden).isEmpty();
	}

	/**
	 * The classes rule two takes write-method names from.
	 *
	 * <p>The 21 paired stores, plus every other class an admin service references
	 * that writes a tenant-owned table. This is the <b>same</b> set
	 * {@link #storeWriteMethodsByService} builds from, deliberately: the sixteenth
	 * round widened that set and left this check scoped to {@code pairedStores()},
	 * so a write hidden from {@link #ANY_METHOD} in one of the newly spanned classes
	 * would be silently absent from the set with nothing reporting it. Today the
	 * difference is one class, {@code LegacyPayrollBatchStore}, whose package holds
	 * no service -- so the seventeenth round's exploit for it did not compile. The
	 * point is that the two scopes cannot drift apart again.
	 */
	private static List<Path> storesRuleTwoReadsWritesFrom() {
		Set<String> tenantTables = tenantOwnedTables();
		Map<String, String> entities = entityTables();
		Map<String, Path> known = classesByName();
		Map<Path, Set<String>> siblingsByPackage = classesByPackage(known);
		Map<Path, Path> byPath = new java.util.LinkedHashMap<>();
		pairedStores().forEach(store -> byPath.put(store, store));
		for (Path service : adminServices()) {
			Set<String> siblings = siblingsByPackage.getOrDefault(service.getParent(), Set.of());
			for (String referenced : referencedClasses(read(service), known.keySet(), siblings)) {
				Path file = known.get(referenced);
				if (file != null && !byPath.containsKey(file)
						&& !writeMethodsOf(file, tenantTables, entities).isEmpty()) {
					byPath.put(file, file);
				}
			}
		}
		return new ArrayList<>(byPath.keySet());
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
	 * sources rather than through a mutant on the tree, because the shapes it pins
	 * -- a stray brace inside a literal, an odd quote in a text block -- have no
	 * instance in this repository to mutate.
	 *
	 * <p>This paragraph used to give a different reason: that an end-to-end mutant
	 * could not discriminate, because a service method calling
	 * {@code this.store.delete(id)} was followed into the service's own
	 * {@code delete}. The fifteenth round found that was not a nuisance but the
	 * worst defect in the sequence, and {@link #OWN_CALL} fixed it --
	 * {@link #theWriteItselfIsNotAGuardBecauseItSharesAName} now requires exactly
	 * that shape to be reported unguarded. The sentence outlived its reason by two
	 * rounds, which is how a maintainer skips a mutant that would work.
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
	 * The write cannot be the guard it reaches.
	 *
	 * <p>The fifteenth round's first finding, and the worst one in the sequence. The
	 * callee walk matched {@code \b(\w+)\s*\(}, which reads the method name and never
	 * the receiver, while the class javadoc defines the walk as "a helper <em>on the
	 * same class</em>". This surface names a store's write after the service method
	 * that calls it, so {@code this.store.delete(id)} resolved to the service's own
	 * guarded {@code delete} -- and rule one accepted the unguarded write as its own
	 * guard. Twelve of the twenty-one paired stores share a name this way today.
	 *
	 * <p>Reproduced on the shipped tree before it was fixed: an unguarded
	 * {@code purge(DashboardSession, long)} whose whole body was
	 * {@code this.store.delete(id)} passed all thirty-six tests, and renaming
	 * {@code PenaltyStore.delete} to {@code removeRow} -- changing nothing else --
	 * failed rule one. That is R-046 arriving in the port through the gate built to
	 * keep it out.
	 */
	@Test
	void theWriteItselfIsNotAGuardBecauseItSharesAName() {
		String throughTheStore = """
				class Service {
					public long purge(DashboardSession session, long id) {
						this.store.delete(id);
						return id;
					}

					public long delete(DashboardSession session, long id) {
						if (id != session.companyId()) {
							throw new IllegalStateException("other company");
						}
						return this.store.delete(id);
					}
				}
				""";
		assertThat(scan(throughTheStore).unguarded())
				.as("`purge` calls the STORE's `delete`. Resolve a callee by bare name and it "
						+ "lands in the service's own guarded `delete`, so the unguarded write "
						+ "reports as guarded -- which is what shipped until this round")
				.contains("purge");

		String throughAHelper = """
				class Service {
					public long purge(DashboardSession session, long id) {
						return delete(session, id);
					}

					public long delete(DashboardSession session, long id) {
						if (id != session.companyId()) {
							throw new IllegalStateException("other company");
						}
						return id;
					}
				}
				""";
		assertThat(scan(throughAHelper).unguarded())
				.as("the control: an unqualified call to a method of the same class is exactly "
						+ "what the walk is for, and narrowing it must not break that")
				.isEmpty();

		String throughThis = """
				class Service {
					public long purge(DashboardSession session, long id) {
						return this.delete(session, id);
					}

					public long delete(DashboardSession session, long id) {
						if (id != session.companyId()) {
							throw new IllegalStateException("other company");
						}
						return id;
					}
				}
				""";
		assertThat(scan(throughThis).unguarded())
				.as("`this.` is the same call written the other way, and this repository writes "
						+ "it both ways")
				.isEmpty();

		// OWN_CALL's lookbehind inspects the ONE character before the name, so a
		// space -- or a dot left at the end of a line -- puts a qualifier out of its
		// reach and walks straight back into this defect. No instance exists today
		// and no formatter gate keeps it that way, so the walk normalises the
		// spacing first and this is what says so.
		String spacedQualifier = """
				class Service {
					public long purge(DashboardSession session, long id) {
						this.store . delete(id);
						return id;
					}

					public long delete(DashboardSession session, long id) {
						if (id != session.companyId()) {
							throw new IllegalStateException("other company");
						}
						return this.store.delete(id);
					}
				}
				""";
		assertThat(scan(spacedQualifier).unguarded())
				.as("a space around the qualifier's dot is the same call on the same object, "
						+ "and must not make the store's write readable as the service's guard")
				.contains("purge");
	}

	/**
	 * A {@code throws} clause is not a different kind of method.
	 *
	 * <p>Both method patterns ended at {@code \)\s*\{} until this round, so one
	 * conventional keyword removed a method from rule one's subjects, from rule two's,
	 * and from both reachability walks at once, while rule three went on counting its
	 * file as scanned. The eleventh round found this shape for a missing modifier and
	 * wrote it into the javadoc; the {@code throws} variant was never mentioned.
	 *
	 * <p>The second half is the part that matters more than the fix. A pattern this
	 * class cannot see through is not a bug it can enumerate -- it is the shape of the
	 * next one -- so every declaration a deliberately looser pattern finds in the
	 * scanned files must also be found by the real one. When this round was opened,
	 * that assertion failed on six methods in two paired stores, which is the right
	 * way round: the rule found the gap before a write walked through it.
	 */
	@Test
	void aThrowsClauseHidesAMethodFromNoRule() {
		String declared = """
				class Service {
					public long purge(DashboardSession session, long id) throws java.sql.SQLException {
						this.store.remove(id);
						return id;
					}
				}
				""";
		assertThat(scan(declared).sessionTaking())
				.as("a method that declares a checked exception is still a method")
				.isEqualTo(1);
		assertThat(scan(declared).unguarded())
				.as("and it is still asked for a guard")
				.contains("purge");

		List<String> invisible = new ArrayList<>();
		for (Path file : scannedByBothRules()) {
			for (String name : declarationsTheRealPatternMisses(read(file))) {
				invisible.add(file.getFileName() + ": " + name);
			}
		}
		assertThat(invisible)
				.as("a declaration the real pattern cannot see is a method no rule applies to, "
						+ "and rule three still counts its file as scanned")
				.isEmpty();

		// The live half has no subject -- every declaration in the 43 files is one
		// ANY_METHOD sees -- so the sweep is itself a property with no instance,
		// which this class has learned is the kind that needs a fixture. In
		// `String @Nullable []` the annotation sits INSIDE the type, where
		// TYPE_ANNOTATIONS does not reach, so ANY_METHOD cannot see the method.
		assertThat(declarationsTheRealPatternMisses("""
				class Store {
					public String @Nullable [] names() {
						return null;
					}
				}"""))
				.as("a shape the real pattern cannot see must be one the sweep reports, or the "
						+ "sweep is loose in no direction that matters")
				.containsExactly("names");
		assertThat(declarationsTheRealPatternMisses("""
				class Store {
					public String[] names() {
						return null;
					}
				}"""))
				.as("the control: the same declaration without the type annotation is visible, "
						+ "so the sweep reports nothing")
				.isEmpty();

		// And the gap on the parameter side, which this sweep could not report for
		// as long as it interpolated PARAMETERS: one level of paren nesting is all
		// that pattern allows, and a nested annotation is two.
		assertThat(declarationsTheRealPatternMisses("""
				class Store {
					public int purge(@ArraySchema(schema = @Schema(name = "code")) String code) {
						return jdbcTemplate.update("DELETE FROM penalties WHERE code = ?", code);
					}
				}"""))
				.as("a nested annotation in the parameter list is legal Java and puts the whole "
						+ "method outside ANY_METHOD, so the sweep must report it")
				.containsExactly("purge");
		assertThat(declarationsTheRealPatternMisses("""
				class Store {
					public int purge(@Schema(name = "code") String code) {
						return jdbcTemplate.update("DELETE FROM penalties WHERE code = ?", code);
					}
				}"""))
				.as("the control: one level of nesting is inside PARAMETERS, so ANY_METHOD sees "
						+ "this one and the sweep reports nothing")
				.isEmpty();
	}

	/**
	 * A string literal cannot launder a write, or a guard, out of sight.
	 *
	 * <p>{@link #code} was three regex passes until this round -- the naive stripper
	 * this class's own {@code SCHEMA_PREFIX} javadoc had already written down as
	 * unsafe, and the sibling {@link #maskNonCode} was built to replace. It removed
	 * {@code //} before it knew what a string was, so a URL in a literal ate that
	 * literal's closing quote and the next pairing deleted everything up to the
	 * following string.
	 *
	 * <p>Reproduced on the shipped tree: a sessionless {@code scrub(long)} holding a
	 * {@code "https://..."} above {@code this.store.delete(id)} and any second string
	 * below it passed all thirty-six tests, while the same method with the slashes
	 * removed failed rule two. A char literal holding a quote is the other direction:
	 * it leaves the pairing one quote out of phase, so a string's <em>content</em>
	 * stands as code and a guard merely named in prose counts as called.
	 *
	 * <p>Built by concatenation. A fixture about quotes cannot be written as a text
	 * block and still say what it appears to say.
	 */
	@Test
	void aStringLiteralLaundersNeitherAWriteNorAGuard() {
		String namedInAString = "class Service {\n"
				+ "	public long remove(DashboardSession session, long id) {\n"
				+ "		char quote = '\"';\n"
				+ "		String note = \"resolved against session.companyId() upstream\";\n"
				+ "		this.store.wipe(id);\n"
				+ "		return id + quote + note.length();\n"
				+ "	}\n"
				+ "}\n";
		assertThat(namedInAString)
				.as("the fixture really does carry a char literal holding a quote")
				.contains("char quote = '\"';");
		assertThat(scan(namedInAString).unguarded())
				.as("the guard is NAMED in a string, never called. One quote out of phase and "
						+ "the string's content stands as code, which is the laundering this "
						+ "class's javadoc says it forbids")
				.contains("remove");

		String calledForReal = "class Service {\n"
				+ "	public long remove(DashboardSession session, long id) {\n"
				+ "		char quote = '\"';\n"
				+ "		String note = \"a note\";\n"
				+ "		if (id != session.companyId()) {\n"
				+ "			throw new IllegalStateException(note);\n"
				+ "		}\n"
				+ "		this.store.wipe(id);\n"
				+ "		return id + quote;\n"
				+ "	}\n"
				+ "}\n";
		assertThat(scan(calledForReal).unguarded())
				.as("the control: the same shape with the guard actually called")
				.isEmpty();
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
						+ "rule two can see, which is what makes it visible to the positional "
						+ "check. The count above is what pins the collapse: this assertion "
						+ "stays false under a flatten-only regression too, because the "
						+ "unterminated-block guard below truncates the desynchronised block at "
						+ "its own line. The fifteenth round measured that and said so, rather "
						+ "than leaving the sentence claiming a consequence it cannot see")
				.isFalse();

		// The guard that assertion just credited, pinned on its own, the way the
		// unterminated STRING above is. Round 14 added it as a safety net and
		// nothing checked it: reverting it alone left the whole suite green, which
		// is this class's own recurring failure -- a fix shipped with a claim no
		// assertion backs -- in the commit written to close that very shape.
		String unterminatedBlock = "class Store {\n"
				+ "\tprivate String broken() {\n"
				+ "\t\treturn \"\"\"\n"
				+ "\t}\n"
				+ "\tprivate void alsoAMethod() {\n"
				+ "\t\tjdbc.update(\"DELETE FROM penalties WHERE id = ?\");\n"
				+ "\t}\n"
				+ "}\n";
		assertThat(unterminatedBlock.split("\"\"\"", -1).length - 1)
				.as("the fixture really does open a text block and never close it")
				.isEqualTo(1);
		assertThat(methodSpans(unterminatedBlock))
				.as("an unterminated text block ends at its own line, so the second method is "
						+ "still a method. Blank to the end of the file instead and this is one "
						+ "span -- every later brace gone, and with it every write below. Both "
						+ "declarations carry a modifier on purpose: ANY_METHOD needs one, so a "
						+ "package-private second method would make this fixture agree with the "
						+ "broken scanner for a reason that has nothing to do with the guard")
				.hasSize(2);
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

	/** The 43 files rules one and two open. */
	private static List<Path> scannedByBothRules() {
		List<Path> scanned = new ArrayList<>(adminServices());
		scanned.addAll(pairedStores());
		return scanned;
	}

	/**
	 * Declarations a deliberately looser pattern finds and {@link #ANY_METHOD} does
	 * not.
	 *
	 * <p>Loose at <b>both</b> ends. It was loosened only in the tail until the
	 * sixteenth round, so it shared the real pattern's entire head and was blind to
	 * exactly the head-side gap that round found -- a sweep that cannot discriminate
	 * in the direction it claims to is the vacuous pass this class keeps finding.
	 *
	 * <p>A constructor is excluded by name: it is not a narrowing of
	 * {@code ANY_METHOD} but a construct {@code ANY_METHOD} deliberately does not
	 * match, since it requires a return type and a constructor has none, and the
	 * name is the only thing that separates the two.
	 */
	private static List<String> declarationsTheRealPatternMisses(String rawSource) {
		String source = maskNonCode(rawSource).code();
		// Loose in the parameters too, and not by interpolating PARAMETERS. Sharing
		// the real pattern's parameter arm is the same mistake as sharing its head:
		// a sweep that spells the gap the same way the pattern does cannot report
		// it. PARAMETERS allows one level of nesting, so a parameter list holding a
		// nested annotation -- `@ArraySchema(schema = @Schema(...))`, legal and
		// idiomatic -- is outside ANY_METHOD and was outside this sweep with it.
		// `\(.*?\)` plus the permissive tail spans any parameter list at all.
		Pattern loose = Pattern.compile(
				"(?:public|private|protected|static)[^;{)=]*?\\s(\\w+)\\s*\\(.*?\\)[^;{]*?\\{",
				Pattern.DOTALL);
		Set<String> types = new java.util.HashSet<>();
		Matcher typeName = Pattern.compile("\\b(?:class|interface|enum|record)\\s+(\\w+)")
				.matcher(source);
		while (typeName.find()) {
			types.add(typeName.group(1));
		}
		Set<String> seen = new java.util.HashSet<>();
		Matcher strict = ANY_METHOD.matcher(source);
		while (strict.find()) {
			seen.add(strict.group(1) + "@" + strict.start());
		}
		List<String> missed = new ArrayList<>();
		Matcher wide = loose.matcher(source);
		while (wide.find()) {
			if (!types.contains(wide.group(1))
					&& !seen.contains(wide.group(1) + "@" + wide.start())) {
				missed.add(wide.group(1));
			}
		}
		return missed;
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

		// The form this repository actually writes. `canOpenRow`'s third argument is
		// a resolved company at every live call site -- `row.companyId()`,
		// `store.companyOf(id)` -- so an argument matcher that cannot span a call
		// recognises the discard only in the shape nobody uses. This passed until
		// the sixteenth round.
		String nestedArgument = """
				class Example {
					public long delete(DashboardSession session, DashboardListFilters filters, long id) {
						DashboardOrgScope.canOpenRow(session, filters, this.store.companyOf(id));
						this.store.delete(id);
						return 1L;
					}
				}""";
		assertThat(scan(nestedArgument).unguarded())
				.as("the boolean is thrown away, so nothing is enforced, however the third "
						+ "argument is spelled")
				.containsExactly("delete");

		String answered = """
				class Example {
					public long delete(DashboardSession session, DashboardListFilters filters, long id) {
						if (!DashboardOrgScope.canOpenRow(session, filters, this.store.companyOf(id))) {
							throw new IllegalStateException("other company");
						}
						this.store.delete(id);
						return 1L;
					}
				}""";
		assertThat(scan(answered).unguarded())
				.as("the control: the same call, its answer acted on")
				.isEmpty();
	}

	/**
	 * Nothing outside the two rules calls a store's write.
	 *
	 * <p>The seventeenth round's finding, and the mirror of the sixteenth's. That
	 * round made the <em>callee</em> set come from the code rather than from a
	 * filename; the <em>caller</em> set was still exactly
	 * {@code files("*AdminService.java")}. A class not named that way could call a
	 * store's write and no rule looked: rule one scans only services, rule two scans
	 * only services, and rule three skips the caller because its own text holds no
	 * write statement and skips the store because it is already scanned.
	 *
	 * <p>It is one token away from live. <b>Twenty controllers under the admin root
	 * already inject a store</b> and already call it for reads --
	 * {@code AdminPenaltiesController} holds {@code PenaltyStore} and calls
	 * {@code paginate}, {@code employeeOptions} and {@code exportRows} on it -- so
	 * changing one line of its dispatch from
	 * {@code this.service.delete(session, adminId, id)} to
	 * {@code this.store.delete(id)} deletes a penalty by posted id with no tenant
	 * check. That passed all forty-one tests; the identical call written in the
	 * service failed rule two. The layer was the only difference.
	 *
	 * <p>Matched through the field, not by bare name. A controller calling
	 * {@code this.service.delete(...)} is the correct shape and shares its name with
	 * {@code PenaltyStore.delete}, so a name-only check would report every
	 * well-written controller. The receiver's declared type decides.
	 *
	 * <p><b>Everywhere the admin surface reaches, not everywhere under the admin
	 * root.</b> This rule shipped iterating {@code files("*.java")}, which made a
	 * rule about the code into a rule about a directory for the third time in this
	 * class -- the same mistake as the callee set and the caller set, one scope out.
	 * It also made this javadoc's own title false, since a class outside the root is
	 * very much outside the two rules. Rule three already computes what the admin
	 * surface reaches, and deliberately does not stop at the root, because the device
	 * stores that prompted it live outside; this now reads the same set, and
	 * <b>thirty-nine calls</b> are enumerated there today. None of them is
	 * unguarded -- every one was read at its call site, and they fall into three
	 * shapes -- but none of them was covered by anything either, which is the
	 * difference this rule exists to remove. They are enumerated in
	 * {@link #REACHED_WRITES_ACCOUNTED_FOR}.
	 */
	@Test
	void noClassOutsideTheTwoRulesCallsAStoreWrite() {
		Set<String> tenantTables = tenantOwnedTables();
		Map<String, String> entities = entityTables();
		Map<String, Path> known = classesByName();
		WriteResolver resolver = new WriteResolver(known, tenantTables, entities);
		java.util.function.Function<String, Set<String>> writesOf = resolver::exportedBy;

		Set<String> offenders = new TreeSet<>();
		Set<String> accepted = new TreeSet<>();
		int fieldsChecked = 0;
		for (Path file : unscannedReachableFiles(known)) {
			StoreCalls found = storeWriteCallsIn(read(file), writesOf);
			fieldsChecked += found.fieldsResolved();
			for (String call : found.calls()) {
				String key = file.getFileName() + ": " + call;
				if (REACHED_WRITES_ACCOUNTED_FOR.containsKey(key)) {
					accepted.add(key);
					continue;
				}
				offenders.add(key + " writes a tenant-owned table, "
						+ "and this class is scanned by neither rule");
			}
		}

		assertThat(offenders)
				.as("a write reached from outside a service is a write no rule asks for a guard")
				.isEmpty();
		assertThat(accepted)
				.as("and every licence in REACHED_WRITES_ACCOUNTED_FOR is still describing a call this "
						+ "rule reports. A key the rule stopped reporting is a reason nobody "
						+ "re-read, for a call that moved or gained a guard")
				.containsExactlyInAnyOrderElementsOf(REACHED_WRITES_ACCOUNTED_FOR.keySet());
		assertThat(fieldsChecked)
				.as("fields whose declared type writes a tenant-owned table, held by a class "
						+ "neither rule scans. Exact, and with no comparative figure beside it: "
						+ "this said thirty-four with a note about twenty-one under the old scope, "
						+ "and both were the numbers from before the commit that widened the "
						+ "resolver in the same round -- the fourth bare figure in this file to "
						+ "rot, and the third to rot inside the round that wrote it. A floor "
						+ "would not have caught it; an equality does, and a figure that exists "
						+ "only here cannot disagree with one somewhere else")
				.isEqualTo(45);
		assertThat(resolver.cycles())
				.as("a cycle in the field graph would make what a class exports depend on where "
						+ "the walk started, so the resolver records one rather than returning "
						+ "less; there is none, and if one appears this is where it says so")
				.isEmpty();

		// The live half has thirty-nine subjects now that the scope is the reachable set
		// rather than a directory, and every one of them is accounted for above. The
		// fixtures stay: an accounted call cannot demonstrate that an UNaccounted one
		// would be reported, and it is the reporting that is the rule.
		Set<String> penaltyWrites = Set.of("delete", "insert");
		java.util.function.Function<String, Set<String>> fixtureWrites = type ->
				"PenaltyStore".equals(type) ? penaltyWrites : Set.of();
		String throughTheStore = """
				class AdminPenaltiesController {
					private final PenaltyStore store;
					private final PenaltyAdminService service;

					public String submit(long id) {
						this.store.delete(id);
						return "ok";
					}
				}""";
		assertThat(storeWriteCallsIn(throughTheStore, fixtureWrites).calls())
				.as("one line of a controller's dispatch, and a penalty is deleted by posted id "
						+ "with no tenant check")
				.containsExactly("store.delete()");

		String throughTheService = """
				class AdminPenaltiesController {
					private final PenaltyStore store;
					private final PenaltyAdminService service;

					public String submit(DashboardSession session, long adminId, long id) {
						this.service.delete(session, adminId, id);
						return "ok";
					}
				}""";
		assertThat(storeWriteCallsIn(throughTheService, fixtureWrites).calls())
				.as("the control, and the reason this matches through the FIELD and not by name: "
						+ "`service.delete` shares its name with `PenaltyStore.delete`, so a "
						+ "name-only check would report every correctly written controller")
				.isEmpty();

		String readsOnly = """
				class AdminPenaltiesController {
					private final PenaltyStore store;

					public String list() {
						return this.store.paginate(1, 10).toString();
					}
				}""";
		assertThat(storeWriteCallsIn(readsOnly, fixtureWrites).calls())
				.as("a read through the same field is what twenty controllers already do")
				.isEmpty();

		// The declaration is half the rule, and it was the narrow half. Three shapes
		// the shipped pattern could not span, each of them ordinary Java and each of
		// them enough to make the write invisible: a fully-qualified type, a
		// `static` modifier, and an annotation in type position. The first is live
		// -- eight fields under the admin root are declared with their package, two
		// of them the company delete -- so this is not a hypothetical.
		String qualified = """
				class AdminPenaltiesController {
					private final com.workin.backend.platformadmin.hr.PenaltyStore store;

					public String submit(long id) {
						this.store.delete(id);
						return "ok";
					}
				}""";
		assertThat(storeWriteCallsIn(qualified, fixtureWrites).calls())
				.as("writing the package before the type is not a way out of the rule; eight "
						+ "fields under the admin root are declared exactly this way")
				.containsExactly("store.delete()");

		String statick = """
				class AdminPenaltiesController {
					private static PenaltyStore store;

					public String submit(long id) {
						store.delete(id);
						return "ok";
					}
				}""";
		assertThat(storeWriteCallsIn(statick, fixtureWrites).calls())
				.as("nor is a modifier the pattern did not list")
				.containsExactly("store.delete()");

		String annotated = """
				class AdminPenaltiesController {
					private final @Lazy PenaltyStore store;

					public String submit(long id) {
						this.store.delete(id);
						return "ok";
					}
				}""";
		assertThat(storeWriteCallsIn(annotated, fixtureWrites).calls())
				.as("nor an annotation in type position -- the same position that hid a method "
						+ "from every rule two rounds ago, now on a field")
				.containsExactly("store.delete()");

		String unmodified = """
				class AdminPenaltiesController {
					final PenaltyStore store;

					public String submit(long id) {
						this.store.delete(id);
						return "ok";
					}
				}""";
		assertThat(storeWriteCallsIn(unmodified, fixtureWrites).calls())
				.as("nor the absence of one. Java's default access is constructor-injected "
						+ "exactly as `private final` is, and deleting that one keyword from this "
						+ "rule's own stated exploit made the field invisible again -- the "
						+ "eleventh round's finding a third time, and on a field there is no "
						+ "positional backstop the way there is on a method")
				.containsExactly("store.delete()");

		String local = """
				class AdminPenaltiesController {
					public String submit(long id) {
						PenaltyStore store = lookup();
						store.delete(id);
						return "ok";
					}
				}""";
		assertThat(storeWriteCallsIn(local, fixtureWrites).calls())
				.as("the control for dropping the modifier: a local variable is not a field, and "
						+ "reporting one would attribute a write to whatever a method happened to "
						+ "name. That is why the pattern is anchored at a line start and still "
						+ "requires a field modifier")
				.isEmpty();
	}

	/**
	 * Only an admin service may hide a write behind a session.
	 *
	 * <p>The premise the resolver's seam rests on, turned into an assertion. It
	 * suppresses any public method whose signature names a {@code DashboardSession},
	 * on the ground that such a method is rule one's subject and rule one asks it for
	 * a guard. Rule one is {@code files("*AdminService.java")} -- <b>22 files</b>, out
	 * of the couple of hundred the closure reaches. For any other class the
	 * suppression hands the method to nobody: its callers go silent, and nothing asks
	 * it for a guard either, because rule two only harvests a store's write-method
	 * names and never asks a store method to resolve anything.
	 *
	 * <p>The shape that would exploit it: {@code PenaltyStore.purge(DashboardSession,
	 * long)} holding the DELETE, called from a controller. Rule one does not scan
	 * stores, rule three counts the store as scanned, rule four suppresses the call
	 * because the signature names a session, and the positional check passes because
	 * the write is inside a method. Nothing compares that session against the row.
	 *
	 * <p>Green today -- no reachable class outside the admin services has a
	 * session-taking method that reaches a write; the only session-taking store method
	 * anywhere is {@code ComplaintStore.paginate}, a read -- which is exactly why the
	 * premise needed writing down as a check rather than as a sentence.
	 */
	@Test
	void onlyAnAdminServiceMayHideAWriteBehindASession() {
		Set<String> tenantTables = tenantOwnedTables();
		Map<String, String> entities = entityTables();
		Map<String, Path> known = classesByName();
		WriteResolver resolver = new WriteResolver(known, tenantTables, entities);
		Set<String> services = adminServices().stream()
				.map(path -> path.getFileName().toString())
				.collect(java.util.stream.Collectors.toSet());

		List<String> hidden = new ArrayList<>();
		int considered = 0;
		for (String type : new TreeSet<>(reachableFromAdminSurface(known))) {
			Path file = known.get(type);
			if (file == null || services.contains(file.getFileName().toString())) {
				continue;
			}
			Set<String> reaching = resolver.reachingBy(type);
			if (reaching.isEmpty()) {
				continue;
			}
			considered++;
			for (String signature : publicMethodBodies(read(file)).keySet()) {
				if (signature.contains("DashboardSession") && reaching.contains(name(signature))) {
					hidden.add(type + "::" + signature + " reaches a write and takes a session, "
							+ "but it is not an admin service, so rule one never asks it for a "
							+ "guard and the resolver hides it from the rule that would");
				}
			}
		}
		assertThat(hidden)
				.as("the seam suppresses a session-taking write on the ground that rule one owns "
						+ "it; rule one owns 22 files, and outside them the suppression hands the "
						+ "method to nobody")
				.isEmpty();
		assertThat(considered)
				.as("the reachable non-service classes that reach a write at all; pinned so a "
						+ "closure that stopped resolving cannot make this pass on nothing")
				.isGreaterThan(15);

		// And the shape itself, which no live class has.
		Map<String, String> sources = new HashMap<>();
		sources.put("PenaltyStore", """
				class PenaltyStore {
					public int purge(DashboardSession session, long id) {
						return this.jdbcTemplate.update("DELETE FROM penalties WHERE id = ?", id);
					}
				}""");
		WriteResolver fixture =
				new WriteResolver(sources::get, sources.keySet(), tenantTables, entities);
		assertThat(fixture.reachingBy("PenaltyStore"))
				.as("the write is there and the walk finds it")
				.containsExactly("purge");
		assertThat(fixture.exportedBy("PenaltyStore"))
				.as("and the seam hides it, which is correct only if something else asks -- a "
						+ "store is not something rule one asks")
				.isEmpty();
	}

	/**
	 * No class outside the two rules holds a writer inside a container.
	 *
	 * <p>The companion to {@link #noClassOutsideTheTwoRulesCallsAStoreWrite}, and the
	 * honest half of it. That rule matches a write through the field that holds it --
	 * {@code this.store.delete(id)} -- which is the only access it models. A writer
	 * reached out of a list or an array is written
	 * {@code stores.get(0).delete(id)} or {@code stores[0].delete(id)}, and neither
	 * names the field immediately before the write, so that rule looks at the
	 * declaration, resolves {@code List} or nothing, and reports a class that writes.
	 *
	 * <p>So this one refuses the shape instead of modelling it. There is no such
	 * field today; if one is written, this fails and says which, and whoever writes
	 * it chooses between scanning the class and teaching the other rule to follow a
	 * container. Silently resolving {@code List} to no writes is the third option and
	 * the one this class keeps finding: a rule that passes because it looked in the
	 * wrong place.
	 */
	@Test
	void noUnscannedClassHoldsAWriterInsideAContainer() {
		Set<String> tenantTables = tenantOwnedTables();
		Map<String, String> entities = entityTables();
		Map<String, Path> known = classesByName();
		WriteResolver resolver = new WriteResolver(known, tenantTables, entities);
		java.util.function.Function<String, Set<String>> writesOf = resolver::exportedBy;

		List<String> held = new ArrayList<>();
		for (Path file : unscannedReachableFiles(known)) {
			for (String found : writersHeldInContainersIn(read(file), writesOf)) {
				held.add(file.getFileName() + ": " + found);
			}
		}
		assertThat(held)
				.as("a writer held in a container is reached by an access the rule beside this "
						+ "one does not model, so that rule would pass on it while meaning "
						+ "nothing")
				.isEmpty();
		assertThat(resolver.cycles())
				.as("this rule built its own resolver and never asked it about cycles, so with a "
						+ "cycle present it would have read truncated export sets and passed -- "
						+ "which is the outcome its own javadoc refuses. The rule beside it asks; "
						+ "so does this one now")
				.isEmpty();

		// Nothing live, so the rule is pinned by fixtures or by nothing at all.
		Set<String> penaltyWrites = Set.of("delete", "insert");
		java.util.function.Function<String, Set<String>> fixtureWrites = type ->
				"PenaltyStore".equals(type) ? penaltyWrites : Set.of();

		assertThat(writersHeldInContainersIn("""
				class AdminPenaltiesController {
					private final List<PenaltyStore> stores;
				}""", fixtureWrites))
				.as("a writer in a list: `stores.get(0).delete(id)` writes, and resolving the "
						+ "declared type finds `List`, which writes nothing")
				.containsExactly("stores holds PenaltyStore in <PenaltyStore>");

		assertThat(writersHeldInContainersIn("""
				class AdminPenaltiesController {
					private final Map<Long, PenaltyStore> byCompany;
				}""", fixtureWrites))
				.as("and in a type argument that is not the first")
				.containsExactly("byCompany holds PenaltyStore in <Long,PenaltyStore>");

		assertThat(writersHeldInContainersIn("""
				class AdminPenaltiesController {
					private final PenaltyStore[] stores;
				}""", fixtureWrites))
				.as("and in an array, where the declared type IS the writer but the access is "
						+ "`stores[0].delete(id)`, which names no field before the write")
				.containsExactly("stores holds PenaltyStore in []");

		assertThat(writersHeldInContainersIn("""
				class AdminPenaltiesController {
					private final PenaltyStore store;
					private final List<String> names;
					private final Map<String, Long> counts;
				}""", fixtureWrites))
				.as("the control: a writer held plainly is the other rule's subject, and a "
						+ "container of anything else is not this rule's business")
				.isEmpty();
	}

	/**
	 * Fields whose container holds a writer, as {@code <name> holds <type> in
	 * <decoration>}.
	 */
	private static List<String> writersHeldInContainersIn(
			String rawSource, java.util.function.Function<String, Set<String>> writesOf) {
		String source = maskNonCode(rawSource).code();
		List<String> held = new ArrayList<>();
		Matcher field = FIELD_DECLARATION.matcher(source);
		while (field.find()) {
			String decoration = field.group(2).strip();
			if (decoration.isEmpty()) {
				continue;
			}
			// An array's element type is the declared type; a generic's is in the
			// arguments. Both are candidates, and the decoration says which applies.
			Set<String> candidates = new java.util.LinkedHashSet<>();
			if (decoration.contains("[")) {
				candidates.add(simpleName(field.group(1)));
			}
			Matcher argument = Pattern.compile("\\w+").matcher(decoration);
			while (argument.find()) {
				candidates.add(simpleName(argument.group()));
			}
			for (String candidate : candidates) {
				if (!writesOf.apply(candidate).isEmpty()) {
					held.add(field.group(3) + " holds " + candidate + " in "
							+ decoration.replaceAll("\\s+", ""));
				}
			}
		}
		return held;
	}

	/**
	 * The files rules one and two do not scan, from everywhere the admin surface
	 * reaches.
	 *
	 * <p>{@code files("*.java")} was the first spelling, and it made the two rules
	 * that read this into rules about a directory -- the third time in this class
	 * that a rule meant to be about the code was written as a rule about a path.
	 * Rule three already computes what the admin surface reaches and does not stop
	 * at the admin root, because the device stores that prompted it live outside:
	 * a class out there whose own text holds no write is invisible to rule three,
	 * and was invisible to these two as well, so a controller or a legacy service
	 * the admin surface reaches could call a store's write with nothing looking.
	 */
	private static List<Path> unscannedReachableFiles(Map<String, Path> known) {
		Set<String> scanned = scannedByRuleOneOrTwo();
		List<Path> files = new ArrayList<>();
		for (String name : new TreeSet<>(reachableFromAdminSurface(known))) {
			Path file = known.get(name);
			if (file != null && !scanned.contains(file.getFileName().toString())) {
				files.add(file);
			}
		}
		return files;
	}

	/** What one class's source says about the store writes it calls. */
	private record StoreCalls(List<String> calls, int fieldsResolved) {
	}

	/**
	 * The store writes a class calls through a field it declares.
	 *
	 * <p>Through the field, not by bare name: a controller calling
	 * {@code this.service.delete(...)} is the correct shape and shares its name with
	 * {@code PenaltyStore.delete}, so a name-only check reports every well-written
	 * controller. The receiver's declared type decides.
	 */
	private static StoreCalls storeWriteCallsIn(
			String rawSource, java.util.function.Function<String, Set<String>> writesOf) {
		String source = maskNonCode(rawSource).code();
		List<String> calls = new ArrayList<>();
		int fieldsResolved = 0;
		Matcher field = FIELD_DECLARATION.matcher(source);
		while (field.find()) {
			// Group 2 is the container decoration, captured for
			// `writersHeldInContainersIn` and deliberately unused here: a writer
			// inside one is not reached through `field.write(`, so this rule would
			// report nothing and mean nothing. That rule refuses the shape instead.
			String type = simpleName(field.group(1));
			String name = field.group(3);
			Set<String> writes = writesOf.apply(type);
			if (writes.isEmpty()) {
				continue;
			}
			fieldsResolved++;
			for (String write : fieldWriteCallsIn(source, name, writes)) {
				calls.add(name + "." + write + "()");
			}
		}
		return new StoreCalls(calls, fieldsResolved);
	}

	/**
	 * A write two classes away, and the session that separates a hole from the
	 * correct shape.
	 *
	 * <p>The rule below reported nothing when the field's own class held no SQL,
	 * which is the ordinary arrangement: {@code AdminPayrollController} holds
	 * {@code LegacyPayslipService}, that service holds the store, and the SQL is in
	 * the store. Adding {@code this.payslipService.delete(1L, 2L)} to the real
	 * controller passed all forty-four tests before this existed.
	 *
	 * <p>And the control that makes the rule usable rather than merely loud: the
	 * twenty controllers that call {@code this.service.delete(session, adminId, id)}
	 * reach a write too, and must not be reported, because that is the shape the
	 * admin surface is supposed to use and rule one already asks it for a guard.
	 */
	@Test
	void aWriteTwoClassesAwayIsReportedUnlessASessionVouchesForIt() {
		Set<String> tenantTables = tenantOwnedTables();
		Map<String, String> entities = entityTables();
		Map<String, String> sources = new HashMap<>();
		sources.put("PenaltyStore", """
				class PenaltyStore {
					public int delete(long id) {
						return this.jdbcTemplate.update("DELETE FROM penalties WHERE id = ?", id);
					}
				}""");
		sources.put("LegacyPenaltyService", """
				class LegacyPenaltyService {
					private final PenaltyStore store;

					public void delete(long companyId, long id) {
						this.store.delete(id);
					}
				}""");
		sources.put("PenaltyAdminService", """
				class PenaltyAdminService {
					private final PenaltyStore store;

					public void delete(DashboardSession session, long adminId, long id) {
						this.store.delete(id);
					}
				}""");
		WriteResolver resolver = new WriteResolver(sources::get, sources.keySet(), tenantTables, entities);

		assertThat(resolver.exportedBy("LegacyPenaltyService"))
				.as("the service holds no SQL of its own, and `delete(companyId, id)` is still a "
						+ "way for a caller to delete a penalty. Resolve one hop only and this is "
						+ "empty, which is what let the controller's call through")
				.containsExactly("delete");
		assertThat(resolver.exportedBy("PenaltyAdminService"))
				.as("the same write behind a DashboardSession is rule one's subject, not this "
						+ "rule's. Export it and every correctly written controller in the tree "
						+ "becomes an offender, which is a gate nobody can keep")
				.isEmpty();

		java.util.function.Function<String, Set<String>> writesOf = resolver::exportedBy;
		assertThat(storeWriteCallsIn("""
				class AdminPenaltiesController {
					private final LegacyPenaltyService legacy;

					public String submit(long companyId, long id) {
						this.legacy.delete(companyId, id);
						return "ok";
					}
				}""", writesOf).calls())
				.as("so the controller two hops from the SQL is reported, and the companyId it "
						+ "passes is a number nobody asked it to justify")
				.containsExactly("legacy.delete()");
		assertThat(storeWriteCallsIn("""
				class AdminPenaltiesController {
					private final PenaltyAdminService service;

					public String submit(DashboardSession session, long adminId, long id) {
						this.service.delete(session, adminId, id);
						return "ok";
					}
				}""", writesOf).calls())
				.as("and the control: the shape twenty controllers already use is silent")
				.isEmpty();

		// And through an interface, which is how Spring is ordinarily written and was
		// the one hop the resolver did not take: an interface declares no bodies, so
		// reading its own text found no writes and it exported nothing at all.
		Map<String, String> behindAnInterface = new HashMap<>();
		behindAnInterface.put("PenaltyStore", sources.get("PenaltyStore"));
		behindAnInterface.put("PenaltyDirectory", """
				interface PenaltyDirectory {
					void erase(long companyId, long id);
				}""");
		behindAnInterface.put("LegacyPenaltyDirectory", """
				class LegacyPenaltyDirectory implements PenaltyDirectory {
					private final PenaltyStore store;

					@Override
					public void erase(long companyId, long id) {
						this.store.delete(id);
					}
				}""");
		WriteResolver throughTheInterface = new WriteResolver(
				behindAnInterface::get, behindAnInterface.keySet(), tenantTables, entities);
		assertThat(throughTheInterface.exportedBy("PenaltyDirectory"))
				.as("the interface's own text holds no body and therefore no write, and a field "
						+ "declared as the interface is the shape a Spring service actually has. "
						+ "PlatformAdminCompanyService holds one beside a concrete field, and only "
						+ "the concrete one was reported: two fields, one file, one seen")
				.containsExactly("erase");
		assertThat(storeWriteCallsIn("""
				class AdminPenaltiesController {
					private final PenaltyDirectory penalties;

					public String submit(long companyId, long id) {
						this.penalties.erase(companyId, id);
						return "ok";
					}
				}""", throughTheInterface::exportedBy).calls())
				.as("so the call through the interface is reported")
				.containsExactly("penalties.erase()");
		assertThat(throughTheInterface.exportedBy("PenaltyDirectory"))
				.as("and the answer is memoised rather than recomputed per field")
				.containsExactly("erase");
	}

	/**
	 * The hops are a fixed point, not a number, and a cycle is named rather than
	 * silently shortened.
	 */
	@Test
	void theResolverFollowsEveryHopAndRefusesToGuessAtACycle() {
		Set<String> tenantTables = tenantOwnedTables();
		Map<String, String> entities = entityTables();

		// Four classes deep. `pass < 4` was the bound this loop shipped with, and
		// one pass is enough for every fixture that existed, so nothing pinned it:
		// capping it at one pass killed no test.
		Map<String, String> deep = new HashMap<>();
		deep.put("PenaltyStore", """
				class PenaltyStore {
					public int purge(long id) {
						return step(id);
					}

					private int step(long id) {
						return again(id);
					}

					private int again(long id) {
						return last(id);
					}

					private int last(long id) {
						return this.jdbcTemplate.update("DELETE FROM penalties WHERE id = ?", id);
					}
				}""");
		assertThat(new WriteResolver(deep::get, deep.keySet(), tenantTables, entities).exportedBy("PenaltyStore"))
				.as("`purge` is four calls from the statement, and it is the only name a caller "
						+ "can spell; a bound below four drops it and asks nobody for a guard")
				.containsExactly("purge");

		Map<String, String> circular = new HashMap<>();
		circular.put("PenaltyStore", """
				class PenaltyStore {
					private final PenaltyRetry retry;

					public int delete(long id) {
						return this.jdbcTemplate.update("DELETE FROM penalties WHERE id = ?", id);
					}
				}""");
		circular.put("PenaltyRetry", """
				class PenaltyRetry {
					private final PenaltyStore store;

					public int again(long id) {
						return this.store.delete(id);
					}
				}""");
		WriteResolver walked = new WriteResolver(circular::get, circular.keySet(), tenantTables, entities);
		walked.exportedBy("PenaltyStore");
		assertThat(walked.cycles())
				.as("A holding B holding A: what each exports would depend on which one the walk "
						+ "reached first, so the resolver says so instead of returning the smaller "
						+ "answer. The live tree has none, which is why this is a fixture")
				.isNotEmpty();

		Map<String, String> sentinel = new HashMap<>();
		sentinel.put("OrgFilterCascade", """
				class OrgFilterCascade {
					public static final OrgFilterCascade NONE = new OrgFilterCascade();
				}""");
		WriteResolver itself = new WriteResolver(sentinel::get, sentinel.keySet(), tenantTables, entities);
		itself.exportedBy("OrgFilterCascade");
		assertThat(itself.cycles())
				.as("the control, and four live classes: a sentinel constant of the class's own "
						+ "type is not a cycle, and reporting it would make this assertion fail "
						+ "for a shape that hides nothing")
				.isEmpty();
	}

	/**
	 * Which of a class's methods another class can call to reach a write, following
	 * the fields it holds.
	 *
	 * <p><b>Why a hop further than the field's own type.</b> Resolving one hop
	 * answers "does this field's class hold the SQL", which is the wrong question
	 * once a service sits between the caller and the store. {@code AdminPayrollController}
	 * holds {@code LegacyPayslipService}, whose own text contains no SQL at all, and
	 * {@code this.payslipService.delete(companyId, payslipId)} deletes a payslip. One
	 * hop resolves {@code LegacyPayslipService} to no writes and reports nothing --
	 * verified as a survivor against the live tree before this existed.
	 *
	 * <p><b>Why the session is the seam.</b> Following hops without one would report
	 * every correctly written controller in the tree:
	 * {@code this.service.delete(session, adminId, id)} reaches a write too, and it
	 * is the shape the admin surface is supposed to use. What separates the two is
	 * not the depth, it is who vouches for the company. A method taking a
	 * {@code DashboardSession} is rule one's subject and rule one asks it for a
	 * guard; a method taking a bare {@code long companyId} is asked by nobody where
	 * that number came from, and a controller is free to read it off the request. So
	 * a class exports, to its callers, exactly the writes it can be asked for
	 * <em>without</em> a session -- and a store, whose methods take none, exports
	 * all of them, which is the behaviour this rule already had.
	 *
	 * <p>Overloads resolve in the unsafe-to-miss direction: a name is exported when
	 * <em>any</em> overload of it lacks a session, since the call site names no
	 * parameters. Private methods are never exported, whatever they reach.
	 *
	 * <p>A field graph with a cycle would make the answer depend on where the walk
	 * started, so {@link #cycles} records one instead of silently returning less,
	 * and rule four fails on it.
	 */
	private static final class WriteResolver {

		private final java.util.function.Function<String, String> sourceOf;

		private final Set<String> allTypes;

		private @org.jspecify.annotations.Nullable Map<String, Set<String>> implementors;

		private final Set<String> tenantTables;

		private final Map<String, String> entities;

		private final Map<String, Set<String>> memo = new HashMap<>();

		private final Set<String> onStack = new java.util.LinkedHashSet<>();

		private final List<String> cycles = new ArrayList<>();

		/** Over the repository: a simple name resolves to the file's text. */
		WriteResolver(Map<String, Path> known, Set<String> tenantTables,
				Map<String, String> entities) {
			this(type -> known.get(type) == null ? null : read(known.get(type)),
					known.keySet(), tenantTables, entities);
		}

		/**
		 * Over any source at all, so the fixtures below can drive it. The live half
		 * of a rule cannot demonstrate the rule: an accounted call proves nothing
		 * about whether an unaccounted one would be reported.
		 */
		WriteResolver(java.util.function.Function<String, String> sourceOf, Set<String> allTypes,
				Set<String> tenantTables, Map<String, String> entities) {
			this.sourceOf = sourceOf;
			this.allTypes = allTypes;
			this.tenantTables = tenantTables;
			this.entities = entities;
		}

		/** The writes a caller can reach on this type, by method name. */
		Set<String> exportedBy(String type) {
			Set<String> done = this.memo.get(type);
			if (done != null) {
				return done;
			}
			String source = this.sourceOf.apply(type);
			if (source == null) {
				return Set.of();
			}
			if (!this.onStack.add(type)) {
				// A self-typed field is not a cycle worth reporting: four classes
				// hold a sentinel constant of their own type
				// (`static final OrgFilterCascade NONE`), and such a field adds no
				// path the class's own closure misses, because `callsAnyOf` matches
				// a call by name whatever the receiver. A re-entry that is NOT the
				// type just pushed is the real thing -- A holding B holding A --
				// where what each exports would depend on which one the walk
				// reached first.
				String innermost = null;
				for (String pushed : this.onStack) {
					innermost = pushed;
				}
				if (!type.equals(innermost)) {
					this.cycles.add(String.join(" -> ", this.onStack) + " -> " + type);
				}
				return Set.of();
			}
			Set<String> exported = new TreeSet<>(exported(source));
			// An interface declares no bodies, so `publicMethodBodies` finds nothing
			// in it and it exported nothing at all -- which made every write behind
			// an interface-typed field invisible. `PlatformAdminCompanyService`
			// holds `PlatformAdminCompanyDirectory` beside the concrete
			// `LegacyCompanyDelete`, and only the concrete one was reported: two
			// fields, one file, one seen. Injecting the interface is the idiomatic
			// shape, so this is the ordinary case rather than the exotic one.
			//
			// An abstract class is the same shape for the same reason, so it is
			// included; a simple-name collision cannot confuse the lookup, because
			// `noTwoClassesShareASimpleName` forbids one outright.
			if (DECLARES_NO_BODIES.matcher(maskNonCode(source).code()).find()) {
				for (String implementation : implementorsOf(type)) {
					exported.addAll(exportedBy(implementation));
				}
			}
			this.onStack.remove(type);
			this.memo.put(type, exported);
			return exported;
		}

		private Set<String> exported(String source) {
			Set<String> reaching = reaching(source);
			Set<String> exported = new TreeSet<>();
			for (Map.Entry<String, String> method : publicMethodBodies(source).entrySet()) {
				String name = name(method.getKey());
				if (reaching.contains(name) && !method.getKey().contains("DashboardSession")) {
					exported.add(name);
				}
			}
			return exported;
		}

		/** Every method of this class that reaches a write, public or not. */
		private Set<String> reaching(String source) {
			Map<String, Set<String>> throughFields = new LinkedHashMap<>();
			Matcher field = FIELD_DECLARATION.matcher(maskNonCode(source).code());
			while (field.find()) {
				Set<String> held = exportedBy(simpleName(field.group(1)));
				if (!held.isEmpty()) {
					throughFields.put(field.group(3), held);
				}
			}
			return reachingWrites(source, this.tenantTables, this.entities,
					body -> callsAFieldWrite(body, throughFields));
		}

		private static boolean callsAFieldWrite(
				String body, Map<String, Set<String>> throughFields) {
			String code = code(body);
			for (Map.Entry<String, Set<String>> held : throughFields.entrySet()) {
				if (!fieldWriteCallsIn(code, held.getKey(), held.getValue()).isEmpty()) {
					return true;
				}
			}
			return false;
		}

		/**
		 * Every class declaring {@code implements <type>}, by the interface's simple
		 * name.
		 *
		 * <p>Built once and lazily, because a tree with no interface-typed field
		 * should not pay for the scan.
		 */
		private Set<String> implementorsOf(String type) {
			if (this.implementors == null) {
				Map<String, Set<String>> found = new java.util.HashMap<>();
				for (String name : new TreeSet<>(this.allTypes)) {
					String source = this.sourceOf.apply(name);
					if (source == null) {
						continue;
					}
					Matcher implemented = IMPLEMENTS_CLAUSE.matcher(maskNonCode(source).code());
					while (implemented.find()) {
						for (String each : implemented.group(1).split(",")) {
							String simple = simpleName(
									each.replaceAll("<[^>]*>", "").strip());
							if (!simple.isEmpty()) {
								found.computeIfAbsent(simple, key -> new TreeSet<>()).add(name);
							}
						}
					}
				}
				this.implementors = found;
			}
			return this.implementors.getOrDefault(type, Set.of());
		}

		/**
		 * Every method of this type that reaches a write, exported or not.
		 *
		 * <p>{@link #exportedBy} hides the session-taking ones on the ground that rule
		 * one asks them for a guard. This is the same set before that hiding, so the
		 * ground can be checked rather than asserted in prose.
		 */
		Set<String> reachingBy(String type) {
			String source = this.sourceOf.apply(type);
			return source == null ? Set.of() : reaching(source);
		}

		List<String> cycles() {
			return this.cycles;
		}
	}

	/**
	 * The writes this code calls on one named field, in source order.
	 *
	 * <p>One copy, read by the rule that reports these calls and by the resolver
	 * that follows them. The two-copies mistake is the one this round opened with,
	 * three lines apart, and it cost the gate a store.
	 */
	private static List<String> fieldWriteCallsIn(
			String code, String field, Set<String> writes) {
		List<String> found = new ArrayList<>();
		// `(?<![\w.])` so a field named `store` does not match `backupStore.delete(`.
		// That over-reported rather than hid anything -- it can only demand an
		// accounting -- but it also meant a mutant deleting the optional `this.`
		// changed no behaviour, so the boundary is what makes that mutant mean
		// something.
		Matcher call = Pattern.compile(
				"(?<![\\w.])(?:this\\s*\\.\\s*)?" + Pattern.quote(field)
						+ "\\s*\\.\\s*(\\w+)\\s*\\(")
				.matcher(code);
		while (call.find()) {
			if (writes.contains(call.group(1))) {
				found.add(call.group(1));
			}
		}
		return found;
	}

	/**
	 * Rule two's wanted set spans every store a service reaches.
	 *
	 * <p>The set was built from {@code <X>Store.java} alone until the sixteenth
	 * round -- a rule about a filename rather than about the code. A service writing
	 * through any other store reached no name in it, so rule two never asked for a
	 * session, rule one skipped the method because it takes none, and rule three
	 * counted the file as scanned. Reproduced on the shipped tree: a sessionless
	 * {@code purgeRun(long)} calling
	 * {@code this.batchStore.deleteWithPayslips(batchId)} passed all thirty-nine
	 * tests, while the same shape through the paired {@code PayrollStore} failed
	 * rule two -- the pairing was the only difference.
	 *
	 * <p>Pinned against the live tree rather than a fixture, because the defect was
	 * in which files the set is built from, and a synthetic source cannot have the
	 * wrong files. {@code PayrollAdminService} is the live instance:
	 * {@code LegacyPayrollBatchStore} is the store it reaches that is not its own,
	 * and it is one of the ten entries in {@link #ACCOUNTED_FOR_OUTSIDE_THE_RULES}
	 * whose reason is the sentence "rule one enforces the guard, one layer above".
	 * Nothing made that true of a sixth path until this.
	 */
	@Test
	void ruleTwoWantsTheWritesOfEveryStoreAServiceReaches() {
		Map<String, Set<String>> wanted = storeWriteMethodsByService(tenantOwnedTables());

		assertThat(wanted)
				.as("every admin service that reaches a tenant-owned write is a subject")
				.containsKey("PayrollAdminService");
		// A store whose public method delegates to a private helper holding the SQL
		// put the name the service calls outside this set, so rule two asked that
		// service method for nothing. No live store is written that way, so the
		// property has no instance and needs a fixture -- the shape the sixteenth
		// round learned. Extracting a method is not a change of behaviour and must
		// not be a change of coverage.
		String delegating = """
				class PenaltyStore {
					public int purgeRow(long id) {
						return runPurge(id);
					}

					private int runPurge(long id) {
						return this.jdbcTemplate.update("DELETE FROM penalties WHERE id = ?", id);
					}
				}""";
		assertThat(writeMethodsIn(delegating, tenantOwnedTables(), entityTables()))
				.as("the name the service calls is `purgeRow`, and it reaches the write")
				.contains("purgeRow", "runPurge");

		String inlined = """
				class PenaltyStore {
					public int purgeRow(long id) {
						return this.jdbcTemplate.update("DELETE FROM penalties WHERE id = ?", id);
					}
				}""";
		assertThat(writeMethodsIn(inlined, tenantOwnedTables(), entityTables()))
				.as("the control: the same write one hop shorter")
				.containsExactly("purgeRow");

		// And the same delegation written as a method reference. `reachesCall` has
		// carried the `::name` arm since the round that found
		// `ids.forEach(this::writeRow)`; the closure added one layer down did not,
		// so a store handing its own write to a stream dropped out of the wanted
		// set again -- the identical defect, in the identical position, one round
		// later.
		String referenced = """
				class PenaltyStore {
					public void purgeRows(List<Long> ids) {
						ids.forEach(this::runPurge);
					}

					private int runPurge(long id) {
						return this.jdbcTemplate.update("DELETE FROM penalties WHERE id = ?", id);
					}
				}""";
		assertThat(writeMethodsIn(referenced, tenantOwnedTables(), entityTables()))
				.as("`purgeRows` reaches the write through a method reference, and the name the "
						+ "service calls is `purgeRows`")
				.contains("purgeRows", "runPurge");

		assertThat(wanted.get("PayrollAdminService"))
				.as("deleteWithPayslips is declared on LegacyPayrollBatchStore, which is not "
						+ "PayrollAdminService's paired store. Build the set from the paired "
						+ "store alone and this name is absent, which is what let a sessionless "
						+ "caller of it through")
				.contains("deleteWithPayslips")
				.as("and the paired store's writes are still there")
				.contains("updatePayslipDetail");
	}

	/**
	 * An annotation in type position hides a method from no rule.
	 *
	 * <p>{@link #TYPE_ANNOTATIONS} exists because of this, and it is
	 * {@link #THROWS}'s finding one position to the left: both patterns spelled the
	 * return type without an {@code @}, so
	 * {@code public @Nullable Boolean purge(DashboardSession, long)} matched neither
	 * and rule one never asked it for a guard. Reproduced on the shipped tree -- it
	 * passed all thirty-nine tests, and the identical method without the annotation
	 * failed -- with the shape already live in this package as
	 * {@code AdminAssetCaching:58}'s {@code private @Nullable String etag(Resource)}.
	 */
	@Test
	void anAnnotationInTypePositionHidesAMethodFromNoRule() {
		String annotated = """
				class Service {
					public @Nullable Boolean purge(DashboardSession session, long id) {
						this.store.delete(id);
						return Boolean.TRUE;
					}
				}""";
		assertThat(scan(annotated).sessionTaking())
				.as("an annotated return type is still a return type")
				.isEqualTo(1);
		assertThat(scan(annotated).unguarded())
				.as("and the method is still asked for a guard")
				.contains("purge");

		String withArguments = """
				class Service {
					public @SuppressWarnings("unchecked") @Nullable Boolean purge(DashboardSession session, long id) {
						this.store.delete(id);
						return Boolean.TRUE;
					}
				}""";
		assertThat(scan(withArguments).unguarded())
				.as("an annotation carrying arguments, and more than one of them")
				.contains("purge");

		// The seventeenth round found the same gap one position further right: the
		// parameter group was `([^)]*)`, which cannot span a parenthesis, so an
		// annotated parameter hid the method from both patterns AND from the sweep,
		// which had copied the group verbatim. Four constructors in these same files
		// carry @Value("${...}") today.
		String annotatedParameter = """
				class Service {
					public Integer wipe(DashboardSession session, @SuppressWarnings("unused") long id) {
						this.store.delete(id);
						return 1;
					}
				}""";
		assertThat(scan(annotatedParameter).sessionTaking())
				.as("a method whose parameter carries an annotation is still a method")
				.isEqualTo(1);
		assertThat(scan(annotatedParameter).unguarded())
				.as("and it is still asked for a guard")
				.contains("wipe");
		assertThat(declarationsTheRealPatternMisses(annotatedParameter))
				.as("and the sweep agrees the real pattern can see it")
				.isEmpty();
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
		Matcher call = CALL_OR_REFERENCE.matcher(code(body));
		List<String> callees = new ArrayList<>();
		while (call.find()) {
			String callee = calledName(call);
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
	 * Service simple name to the names of the store methods it can reach that write
	 * a tenant-owned table.
	 *
	 * <p>Per method rather than per store, because rule two asks which service
	 * methods reach a write, and that answer needs the writes named.
	 *
	 * <p><b>Across every store the service reaches, not just its own.</b> This was
	 * built from {@code <X>Store.java} alone until the sixteenth round, which is a
	 * rule about a filename rather than about the code: a service writing through
	 * any other store reached no name in the set, so rule two never asked it for a
	 * session, rule one skipped it because it takes none, and rule three counted the
	 * file as scanned. It is not a hypothetical wiring -- {@code PayrollAdminService}
	 * injects {@code LegacyPayrollBatchStore}, whose thirteen write methods cover
	 * {@code payroll_batches}, {@code payslips}, {@code penalties} and
	 * {@code advances}, and whose exemption rests on the sentence "its five batch-write
	 * paths each take a DashboardSession". Nothing made a sixth path do so: a
	 * sessionless {@code purgeRun(long)} calling
	 * {@code this.batchStore.deleteWithPayslips(batchId)} passed all thirty-nine
	 * tests, while the same shape through the paired {@code PayrollStore} failed rule
	 * two. That is the difference the pairing made, and nothing else.
	 *
	 * <p>Widening it can only ask for a session where one was not asked before. A
	 * method that legitimately needs none says so in
	 * {@link #DELIBERATELY_CROSS_TENANT} with a reason, which is the direction this
	 * class chooses everywhere else.
	 */
	private static Map<String, Set<String>> storeWriteMethodsByService(Set<String> tenantTables) {
		Map<String, Set<String>> byService = new LinkedHashMap<>();
		// Read once: they parse every entity and every class in the repository and
		// do not depend on the service being walked.
		Map<String, String> entities = entityTables();
		Map<String, Path> known = classesByName();
		Map<Path, Set<String>> siblingsByPackage = classesByPackage(known);
		Map<String, Set<String>> writesByClass = new HashMap<>();
		for (Path service : adminServices()) {
			String simpleName = service.getFileName().toString().replace(".java", "");
			String source = read(service);
			Set<String> siblings = siblingsByPackage.getOrDefault(service.getParent(), Set.of());
			Set<String> writes = new TreeSet<>();
			for (String referenced : referencedClasses(source, known.keySet(), siblings)) {
				writes.addAll(writesByClass.computeIfAbsent(referenced,
						name -> writeMethodsOf(known.get(name), tenantTables, entities)));
			}
			if (!writes.isEmpty()) {
				byService.put(simpleName, writes);
			}
		}
		return byService;
	}

	/** The methods of one class that write a tenant-owned table, by name. */
	private static Set<String> writeMethodsOf(
			Path file, Set<String> tenantTables, Map<String, String> entities) {
		return file == null ? new TreeSet<>() : writeMethodsIn(read(file), tenantTables, entities);
	}

	/** The same, over source text, so a fixture can drive it. */
	private static Set<String> writeMethodsIn(
			String source, Set<String> tenantTables, Map<String, String> entities) {
		return reachingWrites(source, tenantTables, entities, body -> false);
	}

	/**
	 * Every method of one class that reaches a write, by name.
	 *
	 * <p>One closure, with a seam. {@link WriteResolver} needs the same walk plus one
	 * extra way for a body to count -- a write called on a field it holds -- and
	 * wrote its own copy of the loop to get it. Two copies of a walk is what this
	 * round opened with, three lines apart, and the copy that drifted cost the gate
	 * a store; the mutant runner refusing an ambiguous substitution is how the second
	 * copy was noticed.
	 *
	 * @param alsoWrites a further reason a body reaches a write, beyond calling a
	 *        name already known to
	 */
	private static Set<String> reachingWrites(
			String source, Set<String> tenantTables, Map<String, String> entities,
			java.util.function.Predicate<String> alsoWrites) {
		Set<String> writes = new TreeSet<>();
		Map<String, List<String>> bodies = methodBodies(source);
		for (Map.Entry<String, List<String>> method : bodies.entrySet()) {
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
		// And every method that REACHES one. The seventeenth round: a store whose
		// public method delegates to a private helper holding the SQL put the name
		// the service calls outside this set, so rule two asked that service method
		// for nothing. `reachesCall` walked the service's call graph and never the
		// store's; this is that same walk, applied inside the store. Extracting a
		// method is not a change of behaviour, and it must not be a change of
		// coverage.
		// To a fixed point, not to a number. This read `pass < 4`, which is a hop
		// bound nothing stated and nothing justified: a store delegating five deep
		// would have dropped its outermost name out of the set, which is the unsafe
		// direction. Each pass that changes anything adds at least one name, so
		// `bodies.size()` passes cannot be reached before the loop settles -- it is
		// a termination guard, not a depth limit.
		for (int pass = 0; pass <= bodies.size(); pass++) {
			Set<String> reaching = new TreeSet<>();
			for (Map.Entry<String, List<String>> method : bodies.entrySet()) {
				if (writes.contains(method.getKey())) {
					continue;
				}
				for (String overload : method.getValue()) {
					if (callsAnyOf(overload, writes) || alsoWrites.test(overload)) {
						reaching.add(method.getKey());
						break;
					}
				}
			}
			if (!writes.addAll(reaching)) {
				break;
			}
		}
		return writes;
	}

	/**
	 * Does this body call any of these names on anything?
	 *
	 * <p>Both arms, for the reason {@link #reachesCall} carries them: {@code name(}
	 * and {@code ::name} reach {@code name} alike. This helper shipped with only the
	 * first, so a store handing its own write to a stream --
	 * {@code ids.forEach(this::deleteRow)} -- left the name the service calls out of
	 * the wanted set, and rule two asked that service method for nothing. It was the
	 * same omission an earlier round had already fixed in {@link #reachesCall},
	 * re-introduced one layer down by a second copy of the literal, which is why
	 * both now read the one {@link #CALL_OR_REFERENCE}.
	 */
	private static boolean callsAnyOf(String body, Set<String> names) {
		Matcher call = CALL_OR_REFERENCE.matcher(code(body));
		while (call.find()) {
			if (names.contains(calledName(call))) {
				return true;
			}
		}
		return false;
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
	 *
	 * <p>This was three regex passes until the fifteenth round, and it was the
	 * sibling that kept the defect {@link #maskNonCode} was built to fix. It
	 * stripped {@code //} before it knew what a string was, so a URL in a literal
	 * -- {@code "https://wiki/hr#scrub"} -- ate the literal's closing quote and the
	 * next pairing deleted everything up to the following string, taking a
	 * {@code store.delete(id)} call with it. {@code SCHEMA_PREFIX}'s own javadoc
	 * had already written down why that is unsafe. And a char literal holding a
	 * quote left the pairing one quote out of phase, so a string's content stood
	 * as code and a guard <em>named</em> in prose counted as called -- the exact
	 * laundering this method exists to prevent. Twenty string literals containing
	 * {@code //} and nine {@code '"'} char literals already live in this package
	 * tree.
	 */
	private static String code(String body) {
		return maskNonCode(body).code();
	}

	/**
	 * An argument list that may itself contain calls, two levels deep.
	 *
	 * <p>This was {@code \([^()]*\)} until the sixteenth round, which recognises a
	 * discarded guard only when its arguments are plain identifiers -- and the
	 * argument shape this repository actually writes is a call. The real guard is
	 * {@code DashboardOrgScope.canOpenRow(session, filters, long rowCompanyId)}, and
	 * every live call site resolves that third argument with
	 * {@code row.companyId()} or {@code store.companyOf(id)}. So the one form the
	 * codebase uses was the form that defeated the check: a bare
	 * {@code canOpenRow(session, filters, this.store.companyOf(id));} -- the boolean
	 * thrown away, nothing enforced -- read as a load-bearing guard and passed all
	 * thirty-nine tests.
	 */
	private static final String BALANCED_ARGUMENTS =
			"\\((?:[^()]|\\((?:[^()]|\\([^()]*\\))*\\))*\\)";

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
				+ "\\s*" + BALANCED_ARGUMENTS);
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
		// Spacing normalised first: OWN_CALL's lookbehind inspects the one character
		// before the name, so `this.store . delete(id)` -- or a dot left at the end of
		// a line -- would walk straight back into the fifteenth round's defect. No
		// instance exists today and no formatter gate keeps it that way. The walk
		// uses no offsets, so collapsing the spacing costs it nothing.
		Matcher call = OWN_CALL.matcher(code.replaceAll("\\s*\\.\\s*", "."));
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
		// The masker, not a third copy of the stripper the fifteenth round deleted
		// from code(): this one strips `//` before it knows what a string is too, so
		// a URL in a literal takes the rest of a line -- and an import or a field
		// type with it -- out of what this class considers reached.
		String code = maskNonCode(source).code();
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
