package com.workin.backend.platformadmin.devices;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

import com.workin.backend.BackendApplication;
import com.workin.devices.agent.DeviceAgentService;
import com.workin.legacy.LegacyMariaDb;

/**
 * {@code /admin/devices} over real HTTP against a real MariaDB: the page an
 * operator watches during a site visit, and the writes that allocate a terminal,
 * issue an agent and import a USB export -- each audited with the change.
 */
@SpringBootTest(classes = BackendApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
class AdminDevicesEndToEndTest {

	private static final LegacyMariaDb.Handle MARIADB = LegacyMariaDb.freshDatabase();

	private static final String PASSWORD = "correct horse battery staple";

	private static final Pattern CSRF = Pattern.compile("name=\"([^\"]*_csrf[^\"]*)\" value=\"([^\"]+)\"");

	@DynamicPropertySource
	static void registerProperties(DynamicPropertyRegistry registry) {
		registry.add("app.jwt.secret", () -> "test-only-secret-not-used-in-production-000000000000");
		registry.add("app.legacy-db.jdbc-url", MARIADB::getJdbcUrl);
		registry.add("app.legacy-db.username", MARIADB::getUsername);
		registry.add("app.legacy-db.password", MARIADB::getPassword);
		registry.add("app.platform-admin.password", () -> PASSWORD);
		registry.add("app.platform-admin.actions.enabled", () -> "true");
	}

	@Autowired
	private TestRestTemplate restTemplate;

	@Autowired
	private javax.sql.DataSource legacyDataSource;

	@Autowired
	private DeviceAgentService agents;

	@Autowired
	private AdminDeviceActions actions;

	private JdbcTemplate jdbc;

	private String cookie;

	private long company;

	private long branch;

	@BeforeEach
	void signIn() {
		this.restTemplate.getRestTemplate().setRequestFactory(new JdkClientHttpRequestFactory(
				HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build()));
		this.jdbc = new JdbcTemplate(this.legacyDataSource);
		for (String table : List.of("device_punches", "device_malformed_punches", "device_assignment_history",
				"attendance_devices", "unclaimed_device_sightings", "device_agents", "platform_admin_audit_events")) {
			this.jdbc.update("DELETE FROM " + table);
		}
		ResponseEntity<String> login = get("/admin/login", null);
		this.cookie = cookieOf(post("/admin/login", cookieOf(login), csrfOf(login), "password", PASSWORD));

		String phone = "01" + System.nanoTime() % 1_000_000_000L;
		this.jdbc.update("INSERT INTO companies (company_name, phone, status, created_at) VALUES ('Devices Co', ?, 'active', NOW())", phone);
		this.company = this.jdbc.queryForObject("SELECT id FROM companies WHERE phone = ?", Long.class, phone);
		this.jdbc.update("INSERT INTO branches (company_id, name, is_active, created_at) VALUES (?, 'Gate Branch', 1, NOW())", this.company);
		this.branch = this.jdbc.queryForObject("SELECT id FROM branches WHERE company_id = ?", Long.class, this.company);
		this.jdbc.update("INSERT INTO employees (company_id, branch_id, employee_code, first_name, last_name, phone, role,"
				+ " is_active, is_mobile_attendance_enabled, can_check_in_any_branch, join_request_status, token_version, created_at)"
				+ " VALUES (?, ?, '3001', 'Usb', 'Person', ?, 'employee', 1, 1, 0, 'accepted', 1, NOW())",
				this.company, this.branch, "011" + System.nanoTime() % 100_000_000L);
	}

	@Test
	void anUnclaimedTerminalIsListedAndAllocatingItMovesItToTheCompanyWithAnAuditRow() {
		this.jdbc.update("INSERT INTO unclaimed_device_sightings (serial_number, first_seen_at, last_seen_at, last_seen_ip,"
				+ " push_version, device_type, hit_count) VALUES ('CGE7203560125', NOW(), NOW(), '10.0.0.7', '2.4.1', NULL, 3)");
		ResponseEntity<String> page = get("/admin/devices", this.cookie);
		assertThat(page.getStatusCode().value()).isEqualTo(200);
		assertThat(page.getBody()).contains("CGE7203560125").contains("10.0.0.7").contains("Gate Branch");

		ResponseEntity<String> allocated = post("/admin/devices", this.cookie, csrfOf(page),
				"action", "allocate", "serial_number", "CGE7203560125", "vendor", "zkteco", "name", "Main gate",
				"branch_id", String.valueOf(this.branch), "device_time_zone", "Africa/Cairo");

		long deviceId = this.jdbc.queryForObject(
				"SELECT id FROM attendance_devices WHERE serial_number = 'CGE7203560125' AND company_id = ? AND branch_id = ?",
				Long.class, this.company, this.branch);
		assertThat(allocated.getHeaders().getLocation()).asString().endsWith("/admin/devices?device=" + deviceId);
		assertThat(this.jdbc.queryForObject("SELECT registered_by_employee_id IS NULL FROM attendance_devices WHERE id = ?",
				Boolean.class, deviceId)).isTrue();
		assertThat(this.jdbc.queryForObject("SELECT COUNT(*) FROM unclaimed_device_sightings", Integer.class)).isZero();
		assertThat(this.jdbc.queryForObject("SELECT COUNT(*) FROM device_assignment_history WHERE device_id = ?",
				Integer.class, deviceId)).isEqualTo(1);
		assertThat(this.jdbc.queryForObject("SELECT COUNT(*) FROM platform_admin_audit_events WHERE event_type = 'DEVICE_ALLOCATED'"
				+ " AND target_type = 'DEVICE' AND target_id = ?", Integer.class, String.valueOf(deviceId))).isEqualTo(1);
		assertThat(get("/admin/devices?device=" + deviceId, this.cookie).getBody()).contains("Main gate").contains("Africa/Cairo");
	}

	@Test
	void aSerialAlreadyAllocatedIsRefusedWithAMessageAndNoAuditRow() {
		ResponseEntity<String> page = get("/admin/devices", this.cookie);
		post("/admin/devices", this.cookie, csrfOf(page), "action", "allocate", "serial_number", "DUP-1",
				"vendor", "zkteco", "name", "First", "branch_id", String.valueOf(this.branch));

		ResponseEntity<String> refused = post("/admin/devices", this.cookie, csrfOf(page), "action", "allocate",
				"serial_number", "DUP-1", "vendor", "zkteco", "name", "Second", "branch_id", String.valueOf(this.branch));

		assertThat(refused.getHeaders().getLocation()).asString().contains("error=devices.serial_already_claimed");
		assertThat(get("/admin/devices?lang=en&error=devices.serial_already_claimed", this.cookie).getBody())
				.contains("That serial number is already allocated.");
		assertThat(this.jdbc.queryForObject("SELECT COUNT(*) FROM platform_admin_audit_events WHERE event_type = 'DEVICE_ALLOCATED'",
				Integer.class)).isEqualTo(1);
		assertThat(this.jdbc.queryForObject("SELECT name FROM attendance_devices WHERE serial_number = 'DUP-1'", String.class))
				.isEqualTo("First");
	}

	@Test
	void anIssuedAgentTokenIsShownOnceStoredOnlyAsADigestAndItWorksUntilRevoked() {
		ResponseEntity<String> page = get("/admin/devices", this.cookie);
		ResponseEntity<String> issued = post("/admin/devices", this.cookie, csrfOf(page),
				"action", "agent_issue", "company_id", String.valueOf(this.company), "name", "Reception PC");

		assertThat(issued.getStatusCode().value()).as("rendered, never redirected with the token").isEqualTo(200);
		Matcher token = Pattern.compile("value=\"(wda_[A-Za-z0-9_-]{43})\"").matcher(issued.getBody());
		assertThat(token.find()).isTrue();
		assertThat(this.jdbc.queryForObject("SELECT COUNT(*) FROM device_agents WHERE token_sha256 = SHA2(?, 256)"
				+ " AND company_id = ?", Integer.class, token.group(1), this.company)).isEqualTo(1);
		assertThat(get("/admin/devices", this.cookie).getBody())
				.as("never shown again").doesNotContain(token.group(1)).contains("Reception PC");
		assertThat(this.agents.authenticate(token.group(1))).isPresent();

		long agentId = this.jdbc.queryForObject("SELECT id FROM device_agents WHERE name = 'Reception PC'", Long.class);
		post("/admin/devices", this.cookie, csrfOf(page), "action", "agent_active", "id", String.valueOf(agentId), "active", "0");

		assertThat(this.agents.authenticate(token.group(1))).as("revoked").isEmpty();
		assertThat(this.jdbc.queryForObject("SELECT COUNT(*) FROM platform_admin_audit_events WHERE event_type IN"
				+ " ('DEVICE_AGENT_ISSUED', 'DEVICE_AGENT_UPDATED')", Integer.class)).isEqualTo(2);
	}

	@Test
	void aUsbExportIsImportedAgainstTheDeviceOnceAndItsUnreadableLinesAreShown() {
		ResponseEntity<String> page = get("/admin/devices", this.cookie);
		post("/admin/devices", this.cookie, csrfOf(page), "action", "allocate", "serial_number", "USB-1",
				"vendor", "zkteco", "name", "Offline terminal", "branch_id", String.valueOf(this.branch),
				"device_time_zone", "Africa/Cairo");
		long deviceId = this.jdbc.queryForObject("SELECT id FROM attendance_devices WHERE serial_number = 'USB-1'", Long.class);
		byte[] export = "      3001\t2026-09-10 08:01:00\t0\t1\t0\t0\r\n      3001\t2026-09-10 16:59:00\t1\t1\t0\t0\r\ngarbage\r\n"
				.getBytes(StandardCharsets.US_ASCII);

		ResponseEntity<String> imported = upload(deviceId, csrfOf(get("/admin/devices?device=" + deviceId, this.cookie)), export);
		assertThat(imported.getHeaders().getLocation()).asString().endsWith("/admin/devices?device=" + deviceId);
		upload(deviceId, csrfOf(get("/admin/devices?device=" + deviceId, this.cookie)), export);

		assertThat(this.jdbc.queryForObject("SELECT COUNT(*) FROM device_punches WHERE device_id = ? AND delivered_via = 'FILE'"
				+ " AND employee_id IS NOT NULL", Integer.class, deviceId)).as("twice imported, stored once").isEqualTo(2);
		assertThat(this.jdbc.queryForObject("SELECT COUNT(*) FROM platform_admin_audit_events WHERE event_type = 'DEVICE_PUNCHES_IMPORTED'",
				Integer.class)).isEqualTo(2);
		assertThat(get("/admin/devices?device=" + deviceId, this.cookie).getBody())
				.contains("garbage").contains("FILE").contains("Usb Person");
	}

	/**
	 * platform_admin_audit_events has a foreign key to platform_admins, so an administrator id that
	 * does not exist makes the audit insert fail at commit -- after the write itself succeeded. The
	 * device module's own transaction template used to commit that write mid-way, leaving a change
	 * with no audit row.
	 */
	@Test
	void aWriteWhoseAuditRowCannotBeStoredIsNotCommittedEither() {
		long nobody = 987_654_321L;
		assertThatThrownBy(() -> inRequest(() -> this.actions.allocate(
				nobody, this.branch, "zkteco", "AUDIT-FAIL-1", "Gate", "Africa/Cairo")))
				.isInstanceOf(RuntimeException.class);
		assertThat(this.jdbc.queryForObject(
				"SELECT COUNT(*) FROM attendance_devices WHERE serial_number = 'AUDIT-FAIL-1'", Integer.class))
				.as("the allocation rolls back with its audit row").isZero();
		assertThat(this.jdbc.queryForObject("SELECT COUNT(*) FROM device_assignment_history", Integer.class)).isZero();

		long adminId = this.jdbc.queryForObject("SELECT id FROM platform_admins WHERE phone = 'admin'", Long.class);
		long deviceId = inRequest(() -> this.actions.allocate(
				adminId, this.branch, "zkteco", "AUDIT-FAIL-2", "Gate", "Africa/Cairo").id());
		assertThatThrownBy(() -> inRequest(() -> this.actions.setActive(nobody, deviceId, false)))
				.isInstanceOf(RuntimeException.class);
		assertThat(this.jdbc.queryForObject("SELECT is_active FROM attendance_devices WHERE id = ?", Boolean.class, deviceId))
				.as("the deactivation rolls back with its audit row").isTrue();
	}

	private static <T> T inRequest(java.util.function.Supplier<T> action) {
		org.springframework.web.context.request.RequestContextHolder.setRequestAttributes(
				new org.springframework.web.context.request.ServletRequestAttributes(
						new org.springframework.mock.web.MockHttpServletRequest()));
		try {
			return action.get();
		} finally {
			org.springframework.web.context.request.RequestContextHolder.resetRequestAttributes();
		}
	}

	@Test
	void aSignedOutVisitorIsSentToTheLoginPage() {
		ResponseEntity<String> response = get("/admin/devices", null);
		assertThat(response.getStatusCode().is3xxRedirection()).isTrue();
		assertThat(response.getHeaders().getLocation()).asString().contains("/admin/login");
	}

	private ResponseEntity<String> upload(long deviceId, String[] csrf, byte[] content) {
		HttpHeaders headers = new HttpHeaders();
		headers.setContentType(MediaType.MULTIPART_FORM_DATA);
		headers.add(HttpHeaders.COOKIE, "WORKIN_ADMIN_SESSION=" + this.cookie);
		MultiValueMap<String, Object> form = new LinkedMultiValueMap<>();
		form.add("action", "import");
		form.add("id", String.valueOf(deviceId));
		form.add(csrf[0], csrf[1]);
		form.add("file", new ByteArrayResource(content) {
			@Override
			public String getFilename() {
				return "1_attlog.dat";
			}
		});
		return this.restTemplate.exchange("/admin/devices", HttpMethod.POST, new HttpEntity<>(form, headers), String.class);
	}

	private ResponseEntity<String> get(String path, String sessionCookie) {
		HttpHeaders headers = new HttpHeaders();
		if (sessionCookie != null) {
			headers.add(HttpHeaders.COOKIE, "WORKIN_ADMIN_SESSION=" + sessionCookie);
		}
		return this.restTemplate.exchange(path, HttpMethod.GET, new HttpEntity<>(headers), String.class);
	}

	private ResponseEntity<String> post(String path, String sessionCookie, String[] csrf, String... fields) {
		HttpHeaders headers = new HttpHeaders();
		headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);
		headers.add(HttpHeaders.COOKIE, "WORKIN_ADMIN_SESSION=" + sessionCookie);
		MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
		for (int index = 0; index < fields.length; index += 2) {
			form.add(fields[index], fields[index + 1]);
		}
		form.add(csrf[0], csrf[1]);
		return this.restTemplate.exchange(path, HttpMethod.POST, new HttpEntity<>(form, headers), String.class);
	}

	private static String[] csrfOf(ResponseEntity<String> response) {
		Matcher matcher = CSRF.matcher(response.getBody());
		assertThat(matcher.find()).as("expected a CSRF token").isTrue();
		return new String[] {matcher.group(1), matcher.group(2)};
	}

	private static String cookieOf(ResponseEntity<String> response) {
		return response.getHeaders().get(HttpHeaders.SET_COOKIE).stream()
				.filter(value -> value.startsWith("WORKIN_ADMIN_SESSION="))
				.map(header -> header.substring(header.indexOf('=') + 1, header.indexOf(';')))
				.findFirst().orElseThrow();
	}
}
