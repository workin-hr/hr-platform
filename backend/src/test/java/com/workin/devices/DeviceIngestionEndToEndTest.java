package com.workin.devices;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;
import org.testcontainers.containers.MariaDBContainer;

import com.workin.backend.BackendApplication;
import com.workin.backend.identity.JwtService;

/**
 * Slice A end to end (design section 13), against a real MariaDB carrying
 * the vendored legacy schema plus the Phase-1 extension tables: the test
 * plays a ZKTeco terminal speaking the documented protocol verbatim, and a
 * tenant administrator using the claim API. Each test claims its own serial
 * numbers, so order does not matter.
 */
@SpringBootTest(classes = BackendApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@ActiveProfiles("phase1-mysql")
class DeviceIngestionEndToEndTest {

	private static final MariaDBContainer<?> MARIADB = new MariaDBContainer<>("mariadb:11.8");

	private static final long COMPANY_1 = 9501L;
	private static final long COMPANY_2 = 9502L;
	private static final long COMPANY_SUSPENDED = 9503L;
	private static final long BRANCH_1 = 9511L;
	private static final long BRANCH_2 = 9512L;

	private static final long ADMIN_1 = 95011L;       // COMPANY_1, company_admin
	private static final long HR_1 = 95012L;          // COMPANY_1, hr
	private static final long EMPLOYEE_1001 = 95013L; // COMPANY_1, employee, employee_code 1001
	private static final long EMPLOYEE_1002 = 95014L; // COMPANY_1, employee, employee_code 1002
	private static final long ADMIN_2 = 95021L;       // COMPANY_2, company_admin
	private static final long ADMIN_SUSPENDED = 95031L;

	private static final String ATTLOG_TWO_PUNCHES =
			"1001\t2024-07-28 08:02:11\t0\t1\t\t0\t0\r\n1001\t2024-07-28 17:31:05\t0\t1\t\t0\t0\r\n";

	@Autowired
	private TestRestTemplate restTemplate;

	@Autowired
	private JwtService jwtService;

	@Autowired
	@Qualifier("requestMappingHandlerMapping")
	private RequestMappingHandlerMapping handlerMapping;

	static {
		MARIADB.start();
		try {
			applySchema("legacy/mysql_workin.schema.sql");
			applySchema("legacy/phase1_extensions.schema.sql");
			seed();
		} catch (Exception ex) {
			throw new IllegalStateException("could not prepare the device-ingestion e2e fixture", ex);
		}
	}

	@DynamicPropertySource
	static void registerProperties(DynamicPropertyRegistry registry) {
		registry.add("app.jwt.secret", () -> "test-only-secret-not-used-in-production-000000000000");
		registry.add("app.legacy-db.jdbc-url", MARIADB::getJdbcUrl);
		registry.add("app.legacy-db.username", MARIADB::getUsername);
		registry.add("app.legacy-db.password", MARIADB::getPassword);
		registry.add("app.devices.ingest.enabled", () -> "true");
		// Small on purpose so the oversized-body refusal is testable without a megabyte.
		registry.add("app.devices.ingest.max-body-bytes", () -> "2048");
	}

	// ---------- the device side ----------

	@Test
	void anUnclaimedSerialIsAnsweredRecordedAndRefusedSoItKeepsBuffering() throws Exception {
		ResponseEntity<String> handshake = deviceGet(
				"/iclock/cdata?SN=UNCLAIMED-1&options=all&language=69&pushver=2.4.0&DeviceType=middle%20east&PushOptionsFlag=1");
		assertThat(handshake.getStatusCode().value()).isEqualTo(200);
		assertThat(handshake.getBody()).startsWith("GET OPTION FROM: UNCLAIMED-1\r\n")
				.contains("ATTLOGStamp=0\r\n").contains("TransFlag=TransData AttLog\tOpLog\r\n")
				.doesNotContain("EnrollFP");

		ResponseEntity<String> upload = devicePost("/iclock/cdata?SN=UNCLAIMED-1&table=ATTLOG&Stamp=9999", ATTLOG_TWO_PUNCHES);
		assertThat(upload.getStatusCode().value()).isEqualTo(403);
		assertThat(upload.getBody()).isEqualTo("ERROR: device is not registered");

		assertThat(count("SELECT COUNT(*) FROM device_punches WHERE pin = '1001' AND device_id NOT IN (SELECT id FROM attendance_devices)")).isZero();
		assertThat(count("SELECT hit_count FROM unclaimed_device_sightings WHERE serial_number = 'UNCLAIMED-1'")).isEqualTo(2);
		assertThat(text("SELECT push_version FROM unclaimed_device_sightings WHERE serial_number = 'UNCLAIMED-1'")).isEqualTo("2.4.0");
		assertThat(text("SELECT device_type FROM unclaimed_device_sightings WHERE serial_number = 'UNCLAIMED-1'")).isEqualTo("middle east");

		Map<String, Object> seen = api(HttpMethod.GET, "/api/v1/devices/unclaimed?serial_number=UNCLAIMED-1", ADMIN_1, null, 200);
		assertThat(seen.get("seen")).isEqualTo(true);
		assertThat(seen.get("claimed")).isEqualTo(false);
		// Whether a device exists is all a tenant may learn; when it was seen,
		// from where, and what it runs are not theirs to read.
		assertThat(seen).containsOnlyKeys("serial_number", "seen", "claimed");
		Map<String, Object> never = api(HttpMethod.GET, "/api/v1/devices/unclaimed?serial_number=NEVER-SEEN", ADMIN_1, null, 200);
		assertThat(never.get("seen")).isEqualTo(false);
	}

	@Test
	void aClaimedDevicesPunchesCarryTheRegistrysTenantAndResolveThroughEmployeeCode() throws Exception {
		long deviceId = claim(ADMIN_1, "DEV-A", BRANCH_1, "Gate A", "+02:00");

		ResponseEntity<String> upload = devicePost("/iclock/cdata?SN=DEV-A&table=ATTLOG&Stamp=4711",
				ATTLOG_TWO_PUNCHES + "7777\t2024-07-28 08:05:00\t0\t1\t\t0\t0\r\n");
		assertThat(upload.getStatusCode().value()).isEqualTo(200);
		assertThat(upload.getBody()).isEqualTo("OK: 3");

		assertThat(count("SELECT COUNT(*) FROM device_punches WHERE device_id = " + deviceId)).isEqualTo(3);
		assertThat(count("SELECT COUNT(*) FROM device_punches WHERE device_id = " + deviceId + " AND company_id = " + COMPANY_1)).isEqualTo(3);
		assertThat(count("SELECT employee_id FROM device_punches WHERE device_id = " + deviceId + " AND pin = '1001' ORDER BY punched_at_local LIMIT 1"))
				.isEqualTo(EMPLOYEE_1001);
		assertThat(text("SELECT processing_state FROM device_punches WHERE device_id = " + deviceId + " AND pin = '1001' ORDER BY punched_at_local LIMIT 1"))
				.isEqualTo("RECEIVED");
		assertThat(text("SELECT processing_state FROM device_punches WHERE device_id = " + deviceId + " AND pin = '7777'")).isEqualTo("UNMATCHED");
		assertThat(text("SELECT employee_id FROM device_punches WHERE device_id = " + deviceId + " AND pin = '7777'")).isNull();
		// Device wall-clock kept verbatim; UTC derived through the device's zone (+02:00).
		assertThat(text("SELECT punched_at_local FROM device_punches WHERE device_id = " + deviceId + " AND pin = '1001' ORDER BY punched_at_local LIMIT 1"))
				.isEqualTo("2024-07-28 08:02:11");
		assertThat(text("SELECT punched_at_utc FROM device_punches WHERE device_id = " + deviceId + " AND pin = '1001' ORDER BY punched_at_local LIMIT 1"))
				.isEqualTo("2024-07-28 06:02:11");
		assertThat(text("SELECT raw_line FROM device_punches WHERE device_id = " + deviceId + " AND pin = '7777'"))
				.isEqualTo("7777\t2024-07-28 08:05:00\t0\t1\t\t0\t0");
		assertThat(text("SELECT last_attlog_stamp FROM attendance_devices WHERE id = " + deviceId)).isEqualTo("4711");
		assertThat(text("SELECT last_seen_at FROM attendance_devices WHERE id = " + deviceId)).isNotNull();

		ResponseEntity<String> handshake = deviceGet("/iclock/cdata?SN=DEV-A&options=all&pushver=2.4.0");
		assertThat(handshake.getBody()).contains("ATTLOGStamp=4711\r\n").contains("TimeZone=2\r\n");
		assertThat(text("SELECT push_version FROM attendance_devices WHERE id = " + deviceId)).isEqualTo("2.4.0");
		assertThat(text("SELECT last_handshake_at FROM attendance_devices WHERE id = " + deviceId)).isNotNull();
	}

	@Test
	void redeliveringTheSameBatchStoresNothingNewAndIsStillAcknowledged() throws Exception {
		long deviceId = claim(ADMIN_1, "DEV-B", BRANCH_1, "Gate B", "+02:00");

		assertThat(devicePost("/iclock/cdata?SN=DEV-B&table=ATTLOG&Stamp=1", ATTLOG_TWO_PUNCHES).getBody()).isEqualTo("OK: 2");
		assertThat(devicePost("/iclock/cdata?SN=DEV-B&table=ATTLOG&Stamp=1", ATTLOG_TWO_PUNCHES).getBody()).isEqualTo("OK: 2");

		assertThat(count("SELECT COUNT(*) FROM device_punches WHERE device_id = " + deviceId)).isEqualTo(2);
	}

	@Test
	void aMalformedLineIsQuarantinedWithoutRefusingTheBatch() throws Exception {
		long deviceId = claim(ADMIN_1, "DEV-C", BRANCH_1, "Gate C", "+02:00");

		ResponseEntity<String> upload = devicePost("/iclock/cdata?SN=DEV-C&table=ATTLOG&Stamp=1",
				"1001\t2024-07-29 08:00:00\t0\t1\r\nabc\tnot-a-time\t0\t1\r\n\r\n");
		assertThat(upload.getStatusCode().value()).isEqualTo(200);
		assertThat(upload.getBody()).isEqualTo("OK: 1");
		assertThat(count("SELECT COUNT(*) FROM device_punches WHERE device_id = " + deviceId)).isEqualTo(1);
	}

	@Test
	void operationLogsAreKeptAndBiometricTemplateLinesNeverReachStorage() throws Exception {
		long deviceId = claim(ADMIN_1, "DEV-D", BRANCH_1, "Gate D", "+02:00");

		ResponseEntity<String> upload = devicePost("/iclock/cdata?SN=DEV-D&table=OPERLOG&Stamp=9999", String.join("\r\n",
				"OPLOG 13\t0\t2024-03-12 11:03:28\t0\t0\t0\t0",
				"USER PIN=1001\tName=Ahmed Secret\tPri=0",
				"FP PIN=1001\tFID=0\tSize=4\tValid=1\tTMP=SUPERSECRETTEMPLATE",
				"OPLOG 0\t0\t2024-03-12 11:03:48"));
		assertThat(upload.getStatusCode().value()).isEqualTo(200);
		assertThat(upload.getBody()).isEqualTo("OK: 4");

		assertThat(count("SELECT COUNT(*) FROM device_operation_logs WHERE device_id = " + deviceId)).isEqualTo(2);
		assertThat(count("SELECT COUNT(*) FROM device_operation_logs WHERE raw_line LIKE '%SUPERSECRET%' OR raw_line LIKE '%Ahmed%'")).isZero();
		assertThat(count("SELECT COUNT(*) FROM device_punches WHERE device_id = " + deviceId)).isZero();
	}

	@Test
	void templateAndPhotoTablesAreAcknowledgedAndDiscarded() throws Exception {
		long deviceId = claim(ADMIN_1, "DEV-E", BRANCH_1, "Gate E", "+02:00");

		for (String table : List.of("FINGERTMP", "BIODATA", "ATTPHOTO", "USERPIC")) {
			ResponseEntity<String> upload = devicePost("/iclock/cdata?SN=DEV-E&table=" + table + "&Stamp=1", "PIN=1001\tTMP=SUPERSECRET");
			assertThat(upload.getStatusCode().value()).as(table).isEqualTo(200);
			assertThat(upload.getBody()).as(table).isEqualTo("OK");
		}
		assertThat(count("SELECT COUNT(*) FROM device_operation_logs WHERE device_id = " + deviceId)).isZero();
		assertThat(count("SELECT COUNT(*) FROM device_punches WHERE device_id = " + deviceId)).isZero();
	}

	@Test
	void theDeviceDescribingItselfUpdatesModelFirmwareAndPushVersion() throws Exception {
		long deviceId = claim(ADMIN_1, "DEV-F", BRANCH_1, "Gate F", "+02:00");

		assertThat(devicePost("/iclock/cdata?SN=DEV-F&table=options", "~DeviceName=K40,FirmVer=Ver 8.0.4.2,PushVersion=2.4.1,UserCount=12").getBody())
				.isEqualTo("OK");

		Map<String, Object> device = deviceView(ADMIN_1, deviceId);
		assertThat(device.get("model")).isEqualTo("K40");
		assertThat(device.get("firmware")).isEqualTo("Ver 8.0.4.2");
		assertThat(device.get("push_version")).isEqualTo("2.4.1");
	}

	@Test
	void theCommandPollIsTheHeartbeatAndCommandResultsAreAcknowledged() throws Exception {
		long deviceId = claim(ADMIN_1, "DEV-G", BRANCH_1, "Gate G", "+02:00");
		assertThat(text("SELECT last_seen_at FROM attendance_devices WHERE id = " + deviceId)).isNull();

		ResponseEntity<String> poll = deviceGet("/iclock/getrequest?SN=DEV-G");
		assertThat(poll.getStatusCode().value()).isEqualTo(200);
		assertThat(poll.getBody()).isEqualTo("OK");
		assertThat(text("SELECT last_seen_at FROM attendance_devices WHERE id = " + deviceId)).isNotNull();
		assertThat(text("SELECT last_seen_ip FROM attendance_devices WHERE id = " + deviceId)).isNotNull();

		ResponseEntity<String> result = devicePost("/iclock/devicecmd?SN=DEV-G", "ID=1&Return=0&CMD=INFO");
		assertThat(result.getStatusCode().value()).isEqualTo(200);
		assertThat(result.getBody()).isEqualTo("OK");
	}

	@Test
	void anOversizedBodyIsRefused() throws Exception {
		claim(ADMIN_1, "DEV-H", BRANCH_1, "Gate H", "+02:00");

		ResponseEntity<String> upload = devicePost("/iclock/cdata?SN=DEV-H&table=ATTLOG&Stamp=1", "1001\t2024-07-28 08:00:00\t0\t1\r\n".repeat(120));
		assertThat(upload.getStatusCode().value()).isEqualTo(413);
	}

	@Test
	void aDeactivatedDeviceIsRefusedLikeAnUnclaimedOne() throws Exception {
		long deviceId = claim(ADMIN_1, "DEV-I", BRANCH_1, "Gate I", "+02:00");

		Map<String, Object> updated = api(HttpMethod.PATCH, "/api/v1/devices/" + deviceId, ADMIN_1, "{\"is_active\":false}", 200);
		assertThat(updated.get("is_active")).isEqualTo(false);

		assertThat(devicePost("/iclock/cdata?SN=DEV-I&table=ATTLOG&Stamp=1", ATTLOG_TWO_PUNCHES).getStatusCode().value()).isEqualTo(403);
		assertThat(count("SELECT COUNT(*) FROM device_punches WHERE device_id = " + deviceId)).isZero();
	}

	/**
	 * The finding this pins: a device that posts its batch as
	 * {@code application/x-www-form-urlencoded} has its body consumed by the
	 * container while the servlet parameter map is built. A handler reading
	 * the body through {@code @RequestParam} would then see an empty stream,
	 * answer {@code OK: 0}, and the terminal would drop every punch in the
	 * upload -- silently, permanently, and counted as a success.
	 */
	@Test
	void punchesSurviveAFormUrlencodedUploadRatherThanBeingSilentlyDropped() throws Exception {
		long deviceId = claim(ADMIN_1, "DEV-O", BRANCH_1, "Gate O", "+02:00");

		ResponseEntity<String> upload = devicePostForm("/iclock/cdata?SN=DEV-O&table=ATTLOG&Stamp=77", ATTLOG_TWO_PUNCHES);

		assertThat(upload.getStatusCode().value()).isEqualTo(200);
		assertThat(upload.getBody()).isEqualTo("OK: 2");
		assertThat(count("SELECT COUNT(*) FROM device_punches WHERE device_id = " + deviceId)).isEqualTo(2);
	}

	/**
	 * The stamp comes back to the device as its resume bookmark, so a value
	 * carrying CR/LF would write extra lines into the handshake -- here, a
	 * TransFlag that asks for fingerprint templates.
	 */
	@Test
	void aStampCarryingNewlinesIsRefusedAndNeverReachesTheHandshake() throws Exception {
		long deviceId = claim(ADMIN_1, "DEV-P", BRANCH_1, "Gate P", "+02:00");

		devicePost("/iclock/cdata?SN=DEV-P&table=ATTLOG&Stamp=0%0D%0ATransFlag%3DTransData%20EnrollFP", ATTLOG_TWO_PUNCHES);

		assertThat(text("SELECT last_attlog_stamp FROM attendance_devices WHERE id = " + deviceId)).isNull();
		assertThat(deviceGet("/iclock/cdata?SN=DEV-P&options=all").getBody())
				.contains("ATTLOGStamp=0\r\n")
				.doesNotContain("EnrollFP");
	}

	/**
	 * A caller who knows a serial could otherwise push the bookmark past the
	 * records the real terminal still holds, and those punches would never be
	 * uploaded -- attendance lost with nothing to show for it.
	 */
	@Test
	void aStampOnADeliveryCarryingNoPunchesDoesNotMoveTheBookmark() throws Exception {
		long deviceId = claim(ADMIN_1, "DEV-Q", BRANCH_1, "Gate Q", "+02:00");
		devicePost("/iclock/cdata?SN=DEV-Q&table=ATTLOG&Stamp=100", ATTLOG_TWO_PUNCHES);
		assertThat(text("SELECT last_attlog_stamp FROM attendance_devices WHERE id = " + deviceId)).isEqualTo("100");

		devicePost("/iclock/cdata?SN=DEV-Q&table=ATTLOG&Stamp=999999999999", "");
		devicePost("/iclock/cdata?SN=DEV-Q&table=ATTLOG&Stamp=999999999998", "garbage-with-no-tab");

		assertThat(text("SELECT last_attlog_stamp FROM attendance_devices WHERE id = " + deviceId)).isEqualTo("100");
	}

	@Test
	void anUnusableSerialIsRefusedBeforeItCanCreateASighting() throws Exception {
		for (String serial : List.of("", "A".repeat(80), "has%20space", "semi%3Bcolon")) {
			ResponseEntity<String> response = deviceGet("/iclock/getrequest?SN=" + serial);
			assertThat(response.getStatusCode().value()).as(serial).isEqualTo(400);
			assertThat(response.getBody()).as(serial).isEqualTo("ERROR: invalid SN");
		}
		assertThat(count("SELECT COUNT(*) FROM unclaimed_device_sightings WHERE LENGTH(serial_number) > 64 "
				+ "OR serial_number = '' OR serial_number LIKE '% %' OR serial_number LIKE '%;%'")).isZero();
	}

	/** Deactivating must stop the flow of information, not only of uploads. */
	@Test
	void aDeactivatedDeviceGetsANeutralHandshakeCarryingNoStampOrZone() throws Exception {
		long deviceId = claim(ADMIN_1, "DEV-R", BRANCH_1, "Gate R", "+03:00");
		devicePost("/iclock/cdata?SN=DEV-R&table=ATTLOG&Stamp=4242", ATTLOG_TWO_PUNCHES);
		assertThat(deviceGet("/iclock/cdata?SN=DEV-R&options=all").getBody())
				.contains("ATTLOGStamp=4242\r\n").contains("TimeZone=3\r\n");

		api(HttpMethod.PATCH, "/api/v1/devices/" + deviceId, ADMIN_1, "{\"is_active\":false}", 200);

		ResponseEntity<String> handshake = deviceGet("/iclock/cdata?SN=DEV-R&options=all&pushver=9.9.9");
		assertThat(handshake.getBody()).contains("ATTLOGStamp=0\r\n").contains("TimeZone=0\r\n");
		// It is still recorded as knocking -- an operator needs to see that --
		// but nothing new is learned from it.
		assertThat(text("SELECT push_version FROM attendance_devices WHERE id = " + deviceId)).isNotEqualTo("9.9.9");
		assertThat(text("SELECT last_seen_at FROM attendance_devices WHERE id = " + deviceId)).isNotNull();
	}

	@Test
	void aMissingSerialIsAPlainTextBadRequestNotAJsonError() {
		ResponseEntity<String> response = deviceGet("/iclock/getrequest");
		assertThat(response.getStatusCode().value()).isEqualTo(400);
		assertThat(response.getBody()).isEqualTo("ERROR: missing SN");
	}

	// ---------- the tenant side ----------

	@Test
	void deviceManagementIsRoleGatedTenantScopedAndValidated() throws Exception {
		long deviceId = claim(HR_1, "DEV-J", BRANCH_1, "Gate J", "Africa/Cairo");

		ResponseEntity<Map<String, Object>> anonymous = restTemplate.exchange(
				URI.create(restTemplate.getRootUri() + "/api/v1/devices"), HttpMethod.GET, new HttpEntity<>(new HttpHeaders()), mapType());
		assertThat(anonymous.getStatusCode().value()).isEqualTo(401);

		Map<String, Object> asEmployee = api(HttpMethod.GET, "/api/v1/devices", EMPLOYEE_1001, null, 403);
		assertThat(asEmployee.get("code")).isEqualTo("forbidden_insufficient_role");
		api(HttpMethod.GET, "/api/v1/devices", ADMIN_SUSPENDED, null, 403);

		List<Map<String, Object>> ownDevices = listOf(api(HttpMethod.GET, "/api/v1/devices", ADMIN_1, null, 200), "devices");
		assertThat(ownDevices).extracting(d -> d.get("serial_number")).contains("DEV-J");
		List<Map<String, Object>> otherTenant = listOf(api(HttpMethod.GET, "/api/v1/devices", ADMIN_2, null, 200), "devices");
		assertThat(otherTenant).extracting(d -> d.get("serial_number")).doesNotContain("DEV-J");

		Map<String, Object> foreignPatch = api(HttpMethod.PATCH, "/api/v1/devices/" + deviceId, ADMIN_2, "{\"name\":\"stolen\"}", 404);
		assertThat(foreignPatch.get("code")).isEqualTo("devices.not_found");
		assertThat(deviceView(ADMIN_1, deviceId).get("name")).isEqualTo("Gate J");

		Map<String, Object> foreignBranch = api(HttpMethod.POST, "/api/v1/devices", ADMIN_1,
				"{\"serial_number\":\"DEV-K\",\"branch_id\":" + BRANCH_2 + ",\"name\":\"Wrong branch\"}", 404);
		assertThat(foreignBranch.get("code")).isEqualTo("devices.branch_not_found");

		Map<String, Object> duplicate = api(HttpMethod.POST, "/api/v1/devices", ADMIN_2,
				"{\"serial_number\":\"DEV-J\",\"branch_id\":" + BRANCH_2 + ",\"name\":\"Same serial\"}", 409);
		assertThat(duplicate.get("code")).isEqualTo("devices.serial_already_claimed");
		assertThat(count("SELECT company_id FROM attendance_devices WHERE serial_number = 'DEV-J'")).isEqualTo(COMPANY_1);

		assertThat(api(HttpMethod.POST, "/api/v1/devices", ADMIN_1, "{\"branch_id\":" + BRANCH_1 + ",\"name\":\"No serial\"}", 400).get("code"))
				.isEqualTo("devices.serial_number_required");
		assertThat(api(HttpMethod.POST, "/api/v1/devices", ADMIN_1,
				"{\"serial_number\":\"DEV-L\",\"branch_id\":" + BRANCH_1 + ",\"name\":\"Bad zone\",\"device_time_zone\":\"Mars/Olympus\"}", 400).get("code"))
				.isEqualTo("devices.time_zone_invalid");
		assertThat(api(HttpMethod.PATCH, "/api/v1/devices/" + deviceId, ADMIN_1, "{}", 400).get("code")).isEqualTo("devices.nothing_to_update");
		assertThat(api(HttpMethod.GET, "/api/v1/devices/unclaimed", ADMIN_1, null, 400).get("code")).isEqualTo("devices.serial_number_query_required");

		assertThat(deviceView(ADMIN_1, deviceId).get("device_time_zone")).isEqualTo("Africa/Cairo");
	}

	@Test
	void aBoundIdentityOverridesTheEmployeeCodeFallback() throws Exception {
		long deviceId = claim(ADMIN_1, "DEV-M", BRANCH_1, "Gate M", "+02:00");

		Map<String, Object> bound = api(HttpMethod.PUT, "/api/v1/devices/identities", ADMIN_1,
				"{\"employee_id\":" + EMPLOYEE_1002 + ",\"pin\":\"5555\",\"card_no\":\"0012345678\"}", 200);
		assertThat(bound.get("pin")).isEqualTo("5555");

		devicePost("/iclock/cdata?SN=DEV-M&table=ATTLOG&Stamp=1", "5555\t2024-07-30 08:00:00\t0\t1\r\n");
		assertThat(count("SELECT employee_id FROM device_punches WHERE device_id = " + deviceId + " AND pin = '5555'")).isEqualTo(EMPLOYEE_1002);

		assertThat(api(HttpMethod.PUT, "/api/v1/devices/identities", ADMIN_1,
				"{\"employee_id\":" + EMPLOYEE_1001 + ",\"pin\":\"5555\"}", 409).get("code")).isEqualTo("devices.pin_already_bound");
		api(HttpMethod.PUT, "/api/v1/devices/identities", ADMIN_1, "{\"employee_id\":" + EMPLOYEE_1002 + ",\"pin\":\"5556\"}", 200);
		List<Map<String, Object>> identities = listOf(api(HttpMethod.GET, "/api/v1/devices/identities", ADMIN_1, null, 200), "identities");
		assertThat(identities).anySatisfy(row -> {
			assertThat(row.get("employee_id")).isEqualTo((int) EMPLOYEE_1002);
			assertThat(row.get("pin")).isEqualTo("5556");
		});
		assertThat(identities).noneMatch(row -> "5555".equals(row.get("pin")));

		assertThat(api(HttpMethod.PUT, "/api/v1/devices/identities", ADMIN_1,
				"{\"employee_id\":" + ADMIN_2 + ",\"pin\":\"1\"}", 404).get("code")).isEqualTo("devices.employee_not_found");
		assertThat(api(HttpMethod.PUT, "/api/v1/devices/identities", ADMIN_1,
				"{\"employee_id\":" + EMPLOYEE_1001 + ",\"pin\":\"12ab\"}", 400).get("code")).isEqualTo("devices.pin_invalid");
	}

	@Test
	void aHalfHourZoneIsRefusedBecauseTheHandshakeCannotExpressIt() {
		Map<String, Object> refused = api(HttpMethod.POST, "/api/v1/devices", ADMIN_1,
				"{\"serial_number\":\"DEV-S\",\"branch_id\":" + BRANCH_1
						+ ",\"name\":\"Half hour\",\"device_time_zone\":\"Asia/Kolkata\"}", 400);

		assertThat(refused.get("code")).isEqualTo("devices.time_zone_not_whole_hour");
	}

	@Test
	void aSerialTheReceiverWouldRejectCannotBeClaimedEither() {
		assertThat(api(HttpMethod.POST, "/api/v1/devices", ADMIN_1,
				"{\"serial_number\":\"has space\",\"branch_id\":" + BRANCH_1 + ",\"name\":\"Bad serial\"}", 400)
				.get("code")).isEqualTo("devices.serial_number_invalid");
	}

	/** A serial another company owns must look exactly like one nobody has ever seen. */
	@Test
	void theUnclaimedLookupTellsOneTenantNothingAboutAnothersDevice() {
		claim(ADMIN_1, "DEV-T", BRANCH_1, "Gate T", "+02:00");

		Map<String, Object> owner = api(HttpMethod.GET, "/api/v1/devices/unclaimed?serial_number=DEV-T", ADMIN_1, null, 200);
		assertThat(owner.get("claimed")).isEqualTo(true);
		assertThat(owner.get("seen")).isEqualTo(true);

		Map<String, Object> stranger = api(HttpMethod.GET, "/api/v1/devices/unclaimed?serial_number=DEV-T", ADMIN_2, null, 200);
		Map<String, Object> neverSeen = api(HttpMethod.GET, "/api/v1/devices/unclaimed?serial_number=DEV-U", ADMIN_2, null, 200);
		assertThat(stranger).containsAllEntriesOf(Map.of("claimed", false, "seen", false));
		assertThat(stranger.keySet()).isEqualTo(neverSeen.keySet());
	}

	@Test
	void aPunchOnADepartedEmployeesPinIsUnmatchedRatherThanAttributedToThem() throws Exception {
		long deviceId = claim(ADMIN_1, "DEV-V", BRANCH_1, "Gate V", "+02:00");

		devicePost("/iclock/cdata?SN=DEV-V&table=ATTLOG&Stamp=1", "1003\t2024-09-01 08:00:00\t0\t1\r\n");

		assertThat(text("SELECT processing_state FROM device_punches WHERE device_id = " + deviceId + " AND pin = '1003'"))
				.isEqualTo("UNMATCHED");
		assertThat(text("SELECT employee_id FROM device_punches WHERE device_id = " + deviceId + " AND pin = '1003'")).isNull();
	}

	@Test
	void thePunchesEndpointShowsOnlyTheCallersCompany() throws Exception {
		long deviceId = claim(ADMIN_1, "DEV-N", BRANCH_1, "Gate N", "+02:00");
		devicePost("/iclock/cdata?SN=DEV-N&table=ATTLOG&Stamp=1", "1002\t2024-08-01 08:00:00\t0\t1\r\n8888\t2024-08-01 08:01:00\t0\t1\r\n");

		List<Map<String, Object>> own = listOf(api(HttpMethod.GET, "/api/v1/devices/punches?device_id=" + deviceId, ADMIN_1, null, 200), "punches");
		assertThat(own).hasSize(2);
		assertThat(own.get(0).get("punched_at_local")).isEqualTo("2024-08-01 08:01:00");
		assertThat(own.get(0).get("device_name")).isEqualTo("Gate N");

		List<Map<String, Object>> unmatched = listOf(
				api(HttpMethod.GET, "/api/v1/devices/punches?device_id=" + deviceId + "&state=unmatched", ADMIN_1, null, 200), "punches");
		assertThat(unmatched).extracting(p -> p.get("pin")).containsExactly("8888");

		List<Map<String, Object>> foreign = listOf(api(HttpMethod.GET, "/api/v1/devices/punches?device_id=" + deviceId, ADMIN_2, null, 200), "punches");
		assertThat(foreign).isEmpty();
	}

	@Test
	void theReceiverIsMappedUnderIclockAndNothingOfThisModuleUnderApis() {
		List<String> patterns = handlerMapping.getHandlerMethods().entrySet().stream()
				.filter(entry -> entry.getValue().getBeanType().getPackageName().startsWith("com.workin.devices"))
				.flatMap(entry -> entry.getKey().getPatternValues().stream())
				.sorted().toList();
		assertThat(patterns).contains("/iclock/cdata", "/iclock/getrequest", "/iclock/devicecmd", "/api/v1/devices");
		assertThat(patterns).noneMatch(pattern -> pattern.startsWith("/apis/"));
	}

	// ---------- helpers ----------

	private long claim(long actor, String serial, long branchId, String name, String zone) {
		Map<String, Object> created = api(HttpMethod.POST, "/api/v1/devices", actor,
				"{\"serial_number\":\"" + serial + "\",\"branch_id\":" + branchId + ",\"name\":\"" + name + "\",\"device_time_zone\":\"" + zone + "\"}",
				201);
		assertThat(created.get("serial_number")).isEqualTo(serial);
		assertThat(created.get("is_active")).isEqualTo(true);
		return ((Number) created.get("id")).longValue();
	}

	private Map<String, Object> deviceView(long actor, long deviceId) {
		return listOf(api(HttpMethod.GET, "/api/v1/devices", actor, null, 200), "devices").stream()
				.filter(d -> ((Number) d.get("id")).longValue() == deviceId).findFirst().orElseThrow();
	}

	private ResponseEntity<String> deviceGet(String pathAndQuery) {
		return restTemplate.exchange(URI.create(restTemplate.getRootUri() + pathAndQuery), HttpMethod.GET,
				new HttpEntity<>(new HttpHeaders()), String.class);
	}

	private ResponseEntity<String> devicePost(String pathAndQuery, String body) {
		return devicePost(pathAndQuery, body, MediaType.TEXT_PLAIN);
	}

	/** What a firmware that posts its batch as a form would send. */
	private ResponseEntity<String> devicePostForm(String pathAndQuery, String body) {
		return devicePost(pathAndQuery, body, MediaType.APPLICATION_FORM_URLENCODED);
	}

	private ResponseEntity<String> devicePost(String pathAndQuery, String body, MediaType contentType) {
		HttpHeaders headers = new HttpHeaders();
		headers.setContentType(contentType);
		return restTemplate.exchange(URI.create(restTemplate.getRootUri() + pathAndQuery), HttpMethod.POST,
				new HttpEntity<>(body, headers), String.class);
	}

	private Map<String, Object> api(HttpMethod method, String path, long actor, String json, int expectedStatus) {
		HttpHeaders headers = new HttpHeaders();
		headers.setBearerAuth(tokenFor(actor));
		headers.set("Accept-Language", "en");
		headers.setContentType(MediaType.APPLICATION_JSON);
		ResponseEntity<Map<String, Object>> response = restTemplate.exchange(
				URI.create(restTemplate.getRootUri() + path), method, new HttpEntity<>(json, headers), mapType());
		assertThat(response.getStatusCode().value()).as("%s %s -> %s", method, path, response.getBody()).isEqualTo(expectedStatus);
		return response.getBody();
	}

	private String tokenFor(long employeeId) {
		String role = employeeId == ADMIN_1 || employeeId == ADMIN_2 || employeeId == ADMIN_SUSPENDED ? "company_admin"
				: employeeId == HR_1 ? "hr" : "employee";
		long companyId = employeeId == ADMIN_2 ? COMPANY_2 : employeeId == ADMIN_SUSPENDED ? COMPANY_SUSPENDED : COMPANY_1;
		return jwtService.issueAccessToken(employeeId, employeeId, companyId, "test-session", Map.of("role", role, "token_version", 1L));
	}

	private static ParameterizedTypeReference<Map<String, Object>> mapType() {
		return new ParameterizedTypeReference<Map<String, Object>>() { };
	}

	@SuppressWarnings("unchecked")
	private static List<Map<String, Object>> listOf(Map<String, Object> body, String key) {
		return (List<Map<String, Object>>) body.get(key);
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

	private static void applySchema(String resourceName) throws Exception {
		String schema = readResource(resourceName);
		try (Connection connection = connect(); Statement st = connection.createStatement()) {
			for (String statement : schema.split(";\\s*\\R")) {
				if (!statement.isBlank()) {
					st.execute(statement);
				}
			}
		}
	}

	private static void seed() throws Exception {
		try (Connection connection = connect(); Statement st = connection.createStatement()) {
			st.execute("SET SESSION sql_mode = ''");
			st.execute("""
					INSERT INTO companies (id, company_name, phone, status, created_at) VALUES
					  (9501, 'Devices Co 1', '+201000009501', 'active', '2025-01-15 09:00:00'),
					  (9502, 'Devices Co 2', '+201000009502', 'active', '2025-01-15 09:00:00'),
					  (9503, 'Devices Co Suspended', '+201000009503', 'suspended', '2025-01-15 09:00:00')
					""");
			st.execute("""
					INSERT INTO branches (id, company_id, name, is_active, created_at) VALUES
					  (9511, 9501, 'Co1 HQ', 1, '2025-03-01 10:00:00'),
					  (9512, 9502, 'Co2 HQ', 1, '2025-03-01 10:00:00'),
					  (9513, 9503, 'Suspended HQ', 1, '2025-03-01 10:00:00')
					""");
			st.execute("""
					INSERT INTO employees
					  (id, company_id, branch_id, employee_code, first_name, last_name, phone, role,
					   is_active, is_mobile_attendance_enabled, can_check_in_any_branch,
					   join_request_status, token_version, created_at)
					VALUES
					  (95011, 9501, 9511, NULL,   'Admin', 'One', '+201100095011', 'company_admin', 1, 1, 0, 'accepted', 1, '2025-04-01 08:00:00'),
					  (95012, 9501, 9511, NULL,   'Hr', 'One', '+201100095012', 'hr', 1, 1, 0, 'accepted', 1, '2025-04-01 08:00:00'),
					  (95013, 9501, 9511, '1001', 'Punch', 'One', '+201100095013', 'employee', 1, 1, 0, 'accepted', 1, '2025-04-01 08:00:00'),
					  (95014, 9501, 9511, '1002', 'Punch', 'Two', '+201100095014', 'employee', 1, 1, 0, 'accepted', 1, '2025-04-01 08:00:00'),
					  (95015, 9501, 9511, '1003', 'Departed', 'One', '+201100095015', 'employee', 0, 1, 0, 'accepted', 1, '2025-04-01 08:00:00'),
					  (95021, 9502, 9512, '1001', 'Admin', 'Two', '+201100095021', 'company_admin', 1, 1, 0, 'accepted', 1, '2025-04-01 08:00:00'),
					  (95031, 9503, 9513, NULL,   'Admin', 'Suspended', '+201100095031', 'company_admin', 1, 1, 0, 'accepted', 1, '2025-04-01 08:00:00')
					""");
		}
	}

	private static Connection connect() throws Exception {
		return DriverManager.getConnection(MARIADB.getJdbcUrl(), MARIADB.getUsername(), MARIADB.getPassword());
	}

	private static String readResource(String name) throws Exception {
		try (InputStream in = DeviceIngestionEndToEndTest.class.getClassLoader().getResourceAsStream(name)) {
			if (in == null) {
				throw new IllegalStateException("missing test resource: " + name);
			}
			return new String(in.readAllBytes(), StandardCharsets.UTF_8);
		}
	}
}
