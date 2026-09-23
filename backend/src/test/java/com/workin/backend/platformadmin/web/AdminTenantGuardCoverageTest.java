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
 * same class. All 58 such methods do today; the rule exists so that the
 * fifty-ninth cannot quietly not.
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

	private static final String VENDORED_SCHEMA = "legacy/mysql_workin.schema.sql";

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

	/** A call that resolves or enforces the session's company. */
	private static final Pattern TENANT_GUARD = Pattern.compile(
			"\\bcompanyId\\s*\\(\\s*\\)|\\bisScopedToOneCompany\\s*\\(|\\bcanOpenRow\\s*\\(");

	private static final Pattern PUBLIC_METHOD = Pattern.compile(
			"public\\s+[\\w.<>,\\[\\]\\s]+?\\s(\\w+)\\s*\\(([^)]*)\\)\\s*\\{", Pattern.DOTALL);

	/** Any method declaration, for following a call into a helper on the same class. */
	private static final Pattern ANY_METHOD = Pattern.compile(
			"(?:public|private|protected|static)\\s+[\\w.<>,\\[\\]\\s]+?\\s(\\w+)\\s*\\(([^)]*)\\)\\s*\\{",
			Pattern.DOTALL);

	private static final Pattern CREATE_TABLE = Pattern.compile(
			"CREATE TABLE `(\\w+)` \\((.*?)\\n\\)\\s*ENGINE", Pattern.DOTALL);

	private static final Pattern COLUMN_NAME = Pattern.compile("(?m)^\\s*`(\\w+)`");

	private static final Pattern WRITE_STATEMENT = Pattern.compile(
			"\\b(?:INSERT\\s+INTO|UPDATE|DELETE\\s+FROM)\\s+`?(\\w+)`?", Pattern.CASE_INSENSITIVE);

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
				.as("the rule is worthless if it matched nothing; 54 write paths exist today, and a "
						+ "regex that quietly stopped matching would otherwise pass this vacuously")
				.isGreaterThan(40);
		assertThat(offenders).isEmpty();
		assertThat(exemptionsStillNeeded)
				.as("every declared cross-tenant exemption must still be a sessionless write, "
						+ "or it is a stale entry to delete")
				.containsExactlyInAnyOrderElementsOf(DELIBERATELY_CROSS_TENANT.keySet());
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
		Map<String, String> bodies = methodBodies(source);
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
		Map<String, String> bodies = methodBodies(source);
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
			String body, Map<String, String> bodies, Set<String> wanted, Set<String> seen, int depth) {
		Matcher call = Pattern.compile("\\b(\\w+)\\s*\\(").matcher(body);
		List<String> callees = new ArrayList<>();
		while (call.find()) {
			String callee = call.group(1);
			if (wanted.contains(callee)) {
				return true;
			}
			callees.add(callee);
		}
		if (depth >= 3) {
			return false;
		}
		for (String callee : callees) {
			if (!bodies.containsKey(callee) || !seen.add(callee)) {
				continue;
			}
			if (reachesCall(bodies.get(callee), bodies, wanted, seen, depth + 1)) {
				return true;
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
		String schema = readResource(VENDORED_SCHEMA);
		Set<String> tenant = new HashSet<>();
		Matcher table = CREATE_TABLE.matcher(schema);
		while (table.find()) {
			Set<String> columns = new HashSet<>();
			Matcher column = COLUMN_NAME.matcher(table.group(2));
			while (column.find()) {
				columns.add(column.group(1));
			}
			// Directly owned, or owned through the employee -- the two shapes
			// R-059 had to distinguish. Both make a row somebody's.
			if (columns.contains("company_id") || columns.contains("employee_id")) {
				tenant.add(table.group(1));
			}
		}
		return tenant;
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
		for (Path store : files("*Store.java")) {
			String stem = store.getFileName().toString().replace("Store.java", "");
			if (serviceFile(stem + "AdminService") == null) {
				continue;
			}
			Set<String> writes = new TreeSet<>();
			String source = read(store);
			for (Map.Entry<String, String> method : methodBodies(source).entrySet()) {
				String flattened = method.getValue().replaceAll("\"\\s*\\+\\s*\"", "");
				Matcher write = WRITE_STATEMENT.matcher(flattened);
				while (write.find()) {
					if (tenantTables.contains(write.group(1))) {
						writes.add(method.getKey());
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
			String flattened = read(store).replaceAll("\"\\s*\\+\\s*\"", "");
			Matcher write = WRITE_STATEMENT.matcher(flattened);
			while (write.find()) {
				if (tenantTables.contains(write.group(1))) {
					written.add(write.group(1));
				}
			}
			if (!written.isEmpty()) {
				byService.put(stem + "AdminService", written);
			}
		}
		return byService;
	}

	private static boolean reachesGuard(String body, Map<String, String> bodies) {
		return reachesGuard(body, bodies, new HashSet<>(), 0);
	}

	/**
	 * The guard may be a helper, and the helper may be a helper: payroll's
	 * {@code assertBatchVisible} defers to {@code assertVisible}, which is where
	 * the session comparison actually lives. Three levels is enough for every
	 * shape on this surface and stops a cycle from running away.
	 */
	private static boolean reachesGuard(
			String body, Map<String, String> bodies, Set<String> seen, int depth) {
		if (TENANT_GUARD.matcher(body).find()) {
			return true;
		}
		if (depth >= 3) {
			return false;
		}
		Matcher call = Pattern.compile("\\b(\\w+)\\s*\\(").matcher(body);
		while (call.find()) {
			String callee = call.group(1);
			if (!bodies.containsKey(callee) || !seen.add(callee)) {
				continue;
			}
			if (reachesGuard(bodies.get(callee), bodies, seen, depth + 1)) {
				return true;
			}
		}
		return false;
	}

	private static Map<String, String> publicMethodBodies(String source) {
		Map<String, String> bodies = new LinkedHashMap<>();
		Matcher method = PUBLIC_METHOD.matcher(source);
		while (method.find()) {
			String signature = method.group(1) + "(" + normalise(method.group(2)) + ")";
			bodies.put(signature, blockAt(source, method.end() - 1));
		}
		return bodies;
	}

	/** Every method on the class, by name, for following helper calls. */
	private static Map<String, String> methodBodies(String source) {
		Map<String, String> bodies = new HashMap<>();
		Matcher method = ANY_METHOD.matcher(source);
		while (method.find()) {
			bodies.putIfAbsent(method.group(1), blockAt(source, method.end() - 1));
		}
		return bodies;
	}

	/** The braced block whose opening brace is at or after {@code from}. */
	private static String blockAt(String source, int from) {
		int open = source.indexOf('{', from);
		int depth = 0;
		for (int index = open; index < source.length(); index++) {
			char character = source.charAt(index);
			if (character == '{') {
				depth++;
			}
			else if (character == '}' && --depth == 0) {
				return source.substring(open, index + 1);
			}
		}
		return source.substring(Math.max(open, 0));
	}

	private static String name(String signature) {
		return signature.substring(0, signature.indexOf('('));
	}

	private static String normalise(String parameters) {
		return String.join(" ", parameters.split("\\s+")).trim();
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
