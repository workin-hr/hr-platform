package com.workin.devices.agentapi;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import com.workin.backend.BackendApplication;
import com.workin.devices.agent.DeviceAgentService;
import com.workin.devices.api.DeviceAdministrationService;
import com.workin.legacy.LegacyMariaDb;

/**
 * The on-premises agent surface end to end, against a real MariaDB: the test
 * plays an agent that has read a terminal over its LAN protocol, and the same
 * terminal pushing over ADMS, so the two paths are proven to be one punch.
 */
@SpringBootTest(classes = BackendApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
class DeviceAgentEndToEndTest {

	private static final LegacyMariaDb.Handle MARIADB = LegacyMariaDb.freshDatabase();

	private static final long COMPANY_1 = 9601L;
	private static final long COMPANY_2 = 9602L;
	private static final long BRANCH_1 = 9611L;
	private static final long BRANCH_2 = 9612L;
	private static final long EMPLOYEE_2001 = 96013L;

	private static final String TWO_PUNCHES = "2001\t2026-09-16 08:02:11\t0\t1\n2001\t2026-09-16 17:31:05\t1\t1\n";

	@Autowired
	private TestRestTemplate restTemplate;

	@Autowired
	private DeviceAgentService agents;

	@Autowired
	private DeviceAdministrationService administration;

	static {
		try {
			seed();
		} catch (Exception ex) {
			throw new IllegalStateException("could not prepare the device-agent e2e fixture", ex);
		}
	}

	@DynamicPropertySource
	static void registerProperties(DynamicPropertyRegistry registry) {
		registry.add("app.jwt.secret", () -> "test-only-secret-not-used-in-production-000000000000");
		registry.add("app.legacy-db.jdbc-url", MARIADB::getJdbcUrl);
		registry.add("app.legacy-db.username", MARIADB::getUsername);
		registry.add("app.legacy-db.password", MARIADB::getPassword);
		registry.add("app.devices.agents.enabled", () -> "true");
		registry.add("app.devices.ingest.enabled", () -> "true");
		registry.add("app.devices.ingest.host", () -> "localhost");
		registry.add("app.devices.ingest.max-records-per-upload", () -> "10");
		registry.add("app.devices.agents.max-body-bytes", () -> "4096");
	}

	@Test
	void anAgentsPunchesAreStoredAgainstItsCompanysDeviceAndResolveTheirEmployee() throws Exception {
		allocate(BRANCH_1, "AGENT-DEV-1");
		String token = issue(COMPANY_1, "Branch PC").token();

		ResponseEntity<Map<String, Object>> first = punches(token, "AGENT-DEV-1", TWO_PUNCHES);
		assertThat(first.getStatusCode().value()).isEqualTo(200);
		assertThat(first.getBody()).containsEntry("stored", 2).containsEntry("duplicates", 0)
				.containsEntry("accepted", 2).containsEntry("unmatched", 0);

		assertThat(count("SELECT COUNT(*) FROM device_punches p JOIN attendance_devices d ON d.id = p.device_id"
				+ " WHERE d.serial_number = 'AGENT-DEV-1' AND p.delivered_via = 'AGENT'"
				+ " AND p.employee_id = " + EMPLOYEE_2001 + " AND p.company_id = " + COMPANY_1)).isEqualTo(2);
		assertThat(text("SELECT last_seen_at IS NOT NULL FROM device_agents WHERE name = 'Branch PC'")).isEqualTo("1");

		ResponseEntity<Map<String, Object>> again = punches(token, "AGENT-DEV-1", TWO_PUNCHES);
		assertThat(again.getBody())
				.as("the agent re-sends after a lost answer; nothing is stored twice")
				.containsEntry("stored", 0).containsEntry("duplicates", 2).containsEntry("accepted", 2);
	}

	@Test
	void aUsbExportHandedToTheAgentIsRecordedAsAFileAndAnUnknownDeliveryIsRefused() throws Exception {
		allocate(BRANCH_1, "AGENT-USB");
		String token = issue(COMPANY_1, "USB import").token();

		ResponseEntity<Map<String, Object>> refused = restTemplate.exchange(
				URI.create(restTemplate.getRootUri() + "/api/v1/device-agents/punches?serial=AGENT-USB&delivery=push"),
				HttpMethod.POST, new HttpEntity<>(TWO_PUNCHES, textHeaders(token)), new ParameterizedTypeReference<>() { });
		assertThat(refused.getStatusCode().value()).as("an agent cannot claim to be the terminal").isEqualTo(400);

		ResponseEntity<Map<String, Object>> imported = restTemplate.exchange(
				URI.create(restTemplate.getRootUri() + "/api/v1/device-agents/punches?serial=AGENT-USB&delivery=file"),
				HttpMethod.POST, new HttpEntity<>(TWO_PUNCHES, textHeaders(token)), new ParameterizedTypeReference<>() { });
		assertThat(imported.getBody()).containsEntry("stored", 2);
		assertThat(text("SELECT GROUP_CONCAT(DISTINCT delivered_via) FROM device_punches p JOIN attendance_devices d"
				+ " ON d.id = p.device_id WHERE d.serial_number = 'AGENT-USB'")).isEqualTo("FILE");
	}

	@Test
	void theSameScanPushedByTheTerminalAndReadByAnAgentIsOnePunch() throws Exception {
		allocate(BRANCH_1, "AGENT-DEV-BOTH");
		String token = issue(COMPANY_1, "Both paths").token();
		String line = "2001\t2026-09-15 09:00:00\t0\t1\n";

		ResponseEntity<String> pushed = restTemplate.exchange(
				URI.create(restTemplate.getRootUri() + "/iclock/cdata?SN=AGENT-DEV-BOTH&table=ATTLOG&Stamp=1"),
				HttpMethod.POST, new HttpEntity<>(line, textHeaders(null)), String.class);
		assertThat(pushed.getStatusCode().value()).isEqualTo(200);

		assertThat(punches(token, "AGENT-DEV-BOTH", line).getBody()).containsEntry("duplicates", 1).containsEntry("stored", 0);
		assertThat(text("SELECT GROUP_CONCAT(delivered_via) FROM device_punches p JOIN attendance_devices d"
				+ " ON d.id = p.device_id WHERE d.serial_number = 'AGENT-DEV-BOTH'"))
				.as("one row, credited to whichever path arrived first")
				.isEqualTo("PUSH");
	}

	@Test
	void anotherCompanysDeviceAnUnknownSerialAndAnInactiveDeviceAllAnswerTheSame() throws Exception {
		allocate(BRANCH_2, "AGENT-OTHER-CO");
		long inactive = allocate(BRANCH_1, "AGENT-INACTIVE");
		inRequest(() -> administration.setActive(inactive, false));
		String token = issue(COMPANY_1, "Nosy").token();

		for (String serial : List.of("AGENT-OTHER-CO", "AGENT-NEVER-SEEN", "AGENT-INACTIVE")) {
			ResponseEntity<Map<String, Object>> refused = punches(token, serial, TWO_PUNCHES);
			assertThat(refused.getStatusCode().value()).as(serial).isEqualTo(404);
			assertThat(refused.getBody()).as(serial).isEqualTo(Map.of("error", "device_not_registered"));
		}
		assertThat(count("SELECT COUNT(*) FROM device_punches p JOIN attendance_devices d ON d.id = p.device_id"
				+ " WHERE d.serial_number IN ('AGENT-OTHER-CO', 'AGENT-INACTIVE')")).isZero();
	}

	@Test
	void noTokenAMalformedOneAndARevokedOneAreAllUnauthorized() throws Exception {
		allocate(BRANCH_1, "AGENT-AUTH");
		DeviceAgentService.IssuedAgent issued = issue(COMPANY_1, "Revoked later");
		inRequest(() -> agents.setActive(issued.agent().id(), false));

		for (String token : new String[] {null, "not-a-token", "wda_" + "A".repeat(43), issued.token()}) {
			ResponseEntity<Map<String, Object>> refused = punches(token, "AGENT-AUTH", TWO_PUNCHES);
			assertThat(refused.getStatusCode().value()).as(String.valueOf(token)).isEqualTo(401);
			assertThat(refused.getHeaders().getFirst(HttpHeaders.WWW_AUTHENTICATE)).isEqualTo("Bearer");
		}
		assertThat(count("SELECT COUNT(*) FROM device_punches p JOIN attendance_devices d ON d.id = p.device_id"
				+ " WHERE d.serial_number = 'AGENT-AUTH'")).isZero();
		assertThat(text("SELECT COUNT(*) FROM device_agents WHERE token_sha256 = '" + issued.token() + "'"))
				.as("the token itself is never stored").isEqualTo("0");
	}

	@Test
	void anUnreadableLineIsQuarantinedAndTheRestOfTheBatchStored() throws Exception {
		allocate(BRANCH_1, "AGENT-MALFORMED");
		String token = issue(COMPANY_1, "Malformed").token();

		ResponseEntity<Map<String, Object>> response = punches(token, "AGENT-MALFORMED",
				"2001\t2026-09-14 08:00:00\t0\t1\nnot a punch\n");

		assertThat(response.getBody()).containsEntry("stored", 1).containsEntry("malformed", 1).containsEntry("accepted", 2);
		assertThat(count("SELECT COUNT(*) FROM device_malformed_punches m JOIN attendance_devices d ON d.id = m.device_id"
				+ " WHERE d.serial_number = 'AGENT-MALFORMED' AND m.raw_line = 'not a punch'")).isEqualTo(1);
	}

	@Test
	void aBatchAboveTheRecordCapIsRefusedWholeSoTheAgentSplitsIt() throws Exception {
		allocate(BRANCH_1, "AGENT-CAP");
		String token = issue(COMPANY_1, "Cap").token();
		StringBuilder eleven = new StringBuilder();
		for (int second = 0; second < 11; second++) {
			eleven.append("2001\t2026-09-13 08:00:").append(String.format("%02d", second)).append("\t0\t1\n");
		}

		ResponseEntity<Map<String, Object>> refused = punches(token, "AGENT-CAP", eleven.toString());

		assertThat(refused.getStatusCode().value()).isEqualTo(413);
		assertThat(refused.getBody()).isEqualTo(Map.of("error", "too_many_records"));
		assertThat(count("SELECT COUNT(*) FROM device_punches p JOIN attendance_devices d ON d.id = p.device_id"
				+ " WHERE d.serial_number = 'AGENT-CAP'")).isZero();
	}

	@Test
	void aHeartbeatRefreshesItsOwnDevicesAndLeavesAnUnknownSerialForTheAdministratorToAllocate() throws Exception {
		allocate(BRANCH_1, "AGENT-HB-OWN");
		allocate(BRANCH_2, "AGENT-HB-OTHER");
		String token = issue(COMPANY_1, "Heartbeat").token();
		String report = """
				{"agent_version":"0.1.0","devices":[
				  {"serial":"AGENT-HB-OWN","vendor":"zkteco","reachable":true,"model":"K40","firmware":"Ver 6.60",
				   "records":1200,"record_capacity":100000,"users":40,"device_time":"2026-09-16 10:00:00"},
				  {"serial":"AGENT-HB-OTHER","vendor":"zkteco","reachable":true,"model":"STOLEN"},
				  {"serial":"AGENT-HB-NEW","vendor":"hikvision","reachable":true,"model":"DS-K1T"},
				  {"serial":"bad serial with spaces","reachable":true}
				]}""";

		HttpHeaders headers = textHeaders(token);
		headers.setContentType(MediaType.APPLICATION_JSON);
		ResponseEntity<Map<String, Object>> response = restTemplate.exchange(
				URI.create(restTemplate.getRootUri() + "/api/v1/device-agents/heartbeat"), HttpMethod.POST,
				new HttpEntity<>(report, headers), new ParameterizedTypeReference<>() { });

		assertThat(response.getStatusCode().value()).isEqualTo(200);
		assertThat(response.getBody().get("devices")).asList().containsExactly(
				Map.of("serial", "AGENT-HB-OWN", "registered", true),
				Map.of("serial", "AGENT-HB-OTHER", "registered", false),
				Map.of("serial", "AGENT-HB-NEW", "registered", false));
		assertThat(text("SELECT model FROM attendance_devices WHERE serial_number = 'AGENT-HB-OWN'")).isEqualTo("K40");
		assertThat(text("SELECT model FROM attendance_devices WHERE serial_number = 'AGENT-HB-OTHER'"))
				.as("another company's device learns nothing from this agent").isNull();
		assertThat(count("SELECT COUNT(*) FROM unclaimed_device_sightings WHERE serial_number = 'AGENT-HB-OTHER'"))
				.as("and is not offered for allocation either").isZero();
		assertThat(text("SELECT device_type FROM unclaimed_device_sightings WHERE serial_number = 'AGENT-HB-NEW'"))
				.as("the sighting names the agent and company whose word it is")
				.matches("agent \\d+ of company " + COMPANY_1 + " \\(hikvision\\)");
		assertThat(text("SELECT agent_version FROM device_agents WHERE name = 'Heartbeat'")).isEqualTo("0.1.0");
		assertThat(text("SELECT last_report FROM device_agents WHERE name = 'Heartbeat'"))
				.contains("\"record_capacity\":100000").doesNotContain("bad serial");
	}

	private long allocate(long branchId, String serial) {
		return inRequest(() -> administration.allocate(branchId, "zkteco", serial, serial, "Africa/Cairo").id());
	}

	private DeviceAgentService.IssuedAgent issue(long companyId, String name) {
		return inRequest(() -> agents.issue(companyId, name));
	}

	/**
	 * The legacy clock is request-scoped (it reads the runtime offset once per
	 * request), so a fixture that calls a service directly has to stand in a
	 * request the way every production caller does.
	 */
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

	private ResponseEntity<Map<String, Object>> punches(String token, String serial, String body) {
		return restTemplate.exchange(
				URI.create(restTemplate.getRootUri() + "/api/v1/device-agents/punches?serial=" + serial),
				HttpMethod.POST, new HttpEntity<>(body, textHeaders(token)), new ParameterizedTypeReference<>() { });
	}

	private static HttpHeaders textHeaders(String token) {
		HttpHeaders headers = new HttpHeaders();
		headers.setContentType(MediaType.TEXT_PLAIN);
		if (token != null) {
			headers.setBearerAuth(token);
		}
		return headers;
	}

	private static Connection connect() throws Exception {
		return DriverManager.getConnection(MARIADB.getJdbcUrl(), MARIADB.getUsername(), MARIADB.getPassword());
	}

	private static long count(String sql) throws Exception {
		try (Connection connection = connect(); Statement st = connection.createStatement(); ResultSet rs = st.executeQuery(sql)) {
			return rs.next() ? rs.getLong(1) : 0L;
		}
	}

	private static String text(String sql) throws Exception {
		try (Connection connection = connect(); Statement st = connection.createStatement(); ResultSet rs = st.executeQuery(sql)) {
			return rs.next() ? rs.getString(1) : null;
		}
	}

	private static void seed() throws Exception {
		try (Connection connection = connect(); Statement st = connection.createStatement()) {
			st.execute("SET SESSION sql_mode = ''");
			st.execute("""
					INSERT INTO companies (id, company_name, phone, status, created_at) VALUES
					  (9601, 'Agent Co 1', '+201000009601', 'active', '2025-01-15 09:00:00'),
					  (9602, 'Agent Co 2', '+201000009602', 'active', '2025-01-15 09:00:00')
					""");
			st.execute("""
					INSERT INTO branches (id, company_id, name, is_active, created_at) VALUES
					  (9611, 9601, 'Agent Co1 HQ', 1, '2025-03-01 10:00:00'),
					  (9612, 9602, 'Agent Co2 HQ', 1, '2025-03-01 10:00:00')
					""");
			st.execute("""
					INSERT INTO employees
					  (id, company_id, branch_id, employee_code, first_name, last_name, phone, role,
					   is_active, is_mobile_attendance_enabled, can_check_in_any_branch,
					   join_request_status, token_version, created_at)
					VALUES
					  (96013, 9601, 9611, '2001', 'Agent', 'Punch', '+201100096013', 'employee', 1, 1, 0, 'accepted', 1, '2025-04-01 08:00:00')
					""");
		}
	}
}
