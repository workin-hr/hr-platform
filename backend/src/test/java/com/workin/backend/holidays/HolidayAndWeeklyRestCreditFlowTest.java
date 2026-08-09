package com.workin.backend.holidays;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import javax.sql.DataSource;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

import com.workin.backend.AbstractIntegrationTest;
import com.workin.backend.attendance.CalendarDayView;
import com.workin.backend.attendance.WeeklyRestCredit;
import com.workin.backend.identity.AuthResponse;
import com.workin.backend.identity.RegisterCompanyRequest;

/**
 * Step 2: the official-holidays module and the weekly-rest credit that
 * sits on top of it, against the legacy rules they port
 * (weekly_rest_credit_helper.php / official_holidays_helper.php @
 * d113204).
 *
 * <p>March 2026 starts on a Sunday, so 2026-03-02 is a Monday and
 * 2026-03-07 a Saturday. All dates are historical, which keeps
 * {@code asOf} comparisons and the calendar's auto-close deterministic.
 */
class HolidayAndWeeklyRestCreditFlowTest extends AbstractIntegrationTest {

	private static final AtomicLong PHONE = new AtomicLong(7_800_000_000L);

	@Autowired
	private TestRestTemplate restTemplate;

	@Autowired
	@Qualifier("flywayDataSource")
	private DataSource flywayDataSource;

	private JdbcTemplate jdbc() {
		return new JdbcTemplate(flywayDataSource);
	}

	private static String uniquePhone() {
		return "+2" + PHONE.incrementAndGet();
	}

	private AuthResponse registerCompanyAdmin() {
		return restTemplate.postForEntity(
				"/api/auth/register",
				new RegisterCompanyRequest("Holiday Co", uniquePhone(), "correct horse battery staple"),
				AuthResponse.class).getBody();
	}

	private HttpHeaders bearer(String token) {
		HttpHeaders headers = new HttpHeaders();
		if (token != null) {
			headers.setBearerAuth(token);
		}
		headers.setContentType(MediaType.APPLICATION_JSON);
		return headers;
	}

	/** An employee on 09:00-17:00 resting Fridays and Saturdays. */
	private Long employeeRestingWeekends(Long companyId) {
		Long employeeId = jdbc().queryForObject(
				"INSERT INTO employees (company_id, first_name, last_name) VALUES (?, 'Hol', 'Emp') RETURNING id",
				Long.class, companyId);
		Long shiftId = jdbc().queryForObject(
				"INSERT INTO shifts (company_id, name, start_time, end_time, days_off) "
						+ "VALUES (?, 'Day', '09:00'::time, '17:00'::time, 'Friday,Saturday') RETURNING id",
				Long.class, companyId);
		jdbc().update(
				"INSERT INTO employee_shift_assignments (company_id, employee_id, shift_id, effective_from) "
						+ "VALUES (?, ?, ?, '2026-01-01'::date)",
				companyId, employeeId, shiftId);
		return employeeId;
	}

	private void punch(Long companyId, Long employeeId, String date) {
		jdbc().update(
				"INSERT INTO attendance (company_id, employee_id, check_in, check_out, method) "
						+ "VALUES (?, ?, ?::timestamptz, ?::timestamptz, 'app')",
				companyId, employeeId, date + "T09:00:00Z", date + "T17:00:00Z");
	}

	private ResponseEntity<List<CalendarDayView>> calendar(String token, Long employeeId, String from, String to) {
		return restTemplate.exchange(
				"/api/tenant/attendance/" + employeeId + "/calendar?from=" + from + "&to=" + to,
				HttpMethod.GET, new HttpEntity<>(bearer(token)),
				new ParameterizedTypeReference<List<CalendarDayView>>() {
				});
	}

	private CalendarDayView day(String token, Long employeeId, String date) {
		ResponseEntity<List<CalendarDayView>> response = calendar(token, employeeId, date, date);
		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
		return response.getBody().get(0);
	}

	private ResponseEntity<String> createHolidays(String token, String name, String... dates) {
		String body = "{\"name\":\"" + name + "\",\"holidayDates\":[\"" + String.join("\",\"", dates) + "\"]}";
		return restTemplate.exchange("/api/tenant/official-holidays", HttpMethod.POST,
				new HttpEntity<>(body, bearer(token)), String.class);
	}

	// ---------- holidays CRUD ----------

	@Test
	void oneNameIsAppliedAcrossSeveralDates() {
		AuthResponse admin = registerCompanyAdmin();

		ResponseEntity<String> created = createHolidays(
				admin.accessToken(), "Eid", "2026-03-19", "2026-03-20", "2026-03-21");

		assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
		ResponseEntity<List<OfficialHolidayView>> listed = restTemplate.exchange(
				"/api/tenant/official-holidays", HttpMethod.GET,
				new HttpEntity<>(bearer(admin.accessToken())),
				new ParameterizedTypeReference<List<OfficialHolidayView>>() {
				});
		assertThat(listed.getBody()).extracting(OfficialHolidayView::holidayDate)
				.containsExactly(
						LocalDate.parse("2026-03-19"), LocalDate.parse("2026-03-20"), LocalDate.parse("2026-03-21"));
		assertThat(listed.getBody()).allSatisfy(h -> assertThat(h.name()).isEqualTo("Eid"));
	}

	@Test
	void creatingOnAnOccupiedDateRenamesItRatherThanFailing() {
		AuthResponse admin = registerCompanyAdmin();
		createHolidays(admin.accessToken(), "Provisional", "2026-03-19");

		ResponseEntity<String> second = createHolidays(admin.accessToken(), "Eid", "2026-03-19");

		// Legacy upserts here. Only update rejects a collision.
		assertThat(second.getStatusCode()).isEqualTo(HttpStatus.CREATED);
		assertThat(jdbc().queryForObject(
				"SELECT name FROM company_official_holidays WHERE holiday_date = '2026-03-19'::date",
				String.class)).isEqualTo("Eid");
		assertThat(jdbc().queryForObject(
				"SELECT count(*) FROM company_official_holidays WHERE holiday_date = '2026-03-19'::date",
				Integer.class)).isEqualTo(1);
	}

	@Test
	void movingAHolidayOntoAnOccupiedDateIsAConflict() {
		AuthResponse admin = registerCompanyAdmin();
		createHolidays(admin.accessToken(), "First", "2026-03-19");
		createHolidays(admin.accessToken(), "Second", "2026-03-20");
		Long secondId = jdbc().queryForObject(
				"SELECT id FROM company_official_holidays WHERE holiday_date = '2026-03-20'::date", Long.class);

		ResponseEntity<String> response = restTemplate.exchange(
				"/api/tenant/official-holidays/" + secondId, HttpMethod.PUT,
				new HttpEntity<>("{\"name\":\"Second\",\"holidayDate\":\"2026-03-19\"}", bearer(admin.accessToken())),
				String.class);

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
		assertThat(response.getBody()).contains("holidays.date_already_taken");
	}

	@Test
	void anotherCompanysHolidayIsNotFound() {
		AuthResponse admin = registerCompanyAdmin();
		AuthResponse other = registerCompanyAdmin();
		createHolidays(other.accessToken(), "Theirs", "2026-03-19");
		Long foreignId = jdbc().queryForObject(
				"SELECT id FROM company_official_holidays WHERE holiday_date = '2026-03-19'::date", Long.class);

		ResponseEntity<String> response = restTemplate.exchange(
				"/api/tenant/official-holidays/" + foreignId, HttpMethod.GET,
				new HttpEntity<>(bearer(admin.accessToken())), String.class);

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
	}

	// ---------- holidays in the calendar ----------

	@Test
	void aHolidayOnAWorkingDayIsNoLongerAnAbsence() {
		AuthResponse admin = registerCompanyAdmin();
		Long employeeId = employeeRestingWeekends(admin.companyId());
		createHolidays(admin.accessToken(), "National Day", "2026-03-04");

		CalendarDayView holiday = day(admin.accessToken(), employeeId, "2026-03-04");

		assertThat(holiday.isOfficialHoliday()).isTrue();
		assertThat(holiday.isWeeklyRest()).isFalse();
		assertThat(holiday.isMissing()).isFalse();
		assertThat(holiday.expectedDurationMinutes()).isZero();
		assertThat(holiday.exceptionTypeName()).isEqualTo("National Day");
	}

	@Test
	void aHolidayFallingOnAWeeklyRestDayOutranksIt() {
		AuthResponse admin = registerCompanyAdmin();
		Long employeeId = employeeRestingWeekends(admin.companyId());
		// 2026-03-06 is a Friday, already a rest day.
		createHolidays(admin.accessToken(), "National Day", "2026-03-06");

		CalendarDayView both = day(admin.accessToken(), employeeId, "2026-03-06");

		// The holiday wins, and the day carries no weekly-rest credit --
		// this is what stops it being counted on both sides.
		assertThat(both.isOfficialHoliday()).isTrue();
		assertThat(both.isWeeklyRest()).isFalse();
		assertThat(both.weeklyRestCredit()).isNull();
	}

	// ---------- weekly-rest credit ----------

	@Test
	void threeCoveredWorkdaysEarnTheRestDay() {
		AuthResponse admin = registerCompanyAdmin();
		Long employeeId = employeeRestingWeekends(admin.companyId());
		// Mon-Wed worked; Friday 2026-03-06 is the rest day under test.
		punch(admin.companyId(), employeeId, "2026-03-02");
		punch(admin.companyId(), employeeId, "2026-03-03");
		punch(admin.companyId(), employeeId, "2026-03-04");

		CalendarDayView friday = day(admin.accessToken(), employeeId, "2026-03-06");

		assertThat(friday.isWeeklyRest()).isTrue();
		assertThat(friday.weeklyRestCredit()).isEqualTo(WeeklyRestCredit.EARNED);
		assertThat(friday.isWeeklyRestVoid()).isFalse();
	}

	@Test
	void twoCoveredWorkdaysVoidTheRestDay() {
		AuthResponse admin = registerCompanyAdmin();
		Long employeeId = employeeRestingWeekends(admin.companyId());
		punch(admin.companyId(), employeeId, "2026-03-02");
		punch(admin.companyId(), employeeId, "2026-03-03");

		CalendarDayView friday = day(admin.accessToken(), employeeId, "2026-03-06");

		// Short of three, and already reached: void immediately, not
		// pending -- legacy's pending branches are unreachable.
		assertThat(friday.weeklyRestCredit()).isEqualTo(WeeklyRestCredit.VOID);
		assertThat(friday.isWeeklyRestVoid()).isTrue();
	}

	@Test
	void bothDaysOfARestBlockCarryTheSameCredit() {
		AuthResponse admin = registerCompanyAdmin();
		Long employeeId = employeeRestingWeekends(admin.companyId());
		punch(admin.companyId(), employeeId, "2026-03-02");
		punch(admin.companyId(), employeeId, "2026-03-03");
		punch(admin.companyId(), employeeId, "2026-03-04");

		// Friday and Saturday resolve to the same block start, so both are
		// credited -- there is no one-per-week cap.
		assertThat(day(admin.accessToken(), employeeId, "2026-03-06").weeklyRestCredit())
				.isEqualTo(WeeklyRestCredit.EARNED);
		assertThat(day(admin.accessToken(), employeeId, "2026-03-07").weeklyRestCredit())
				.isEqualTo(WeeklyRestCredit.EARNED);
	}

	@Test
	void aFutureRestDayIsStillPending() {
		AuthResponse admin = registerCompanyAdmin();
		Long employeeId = employeeRestingWeekends(admin.companyId());
		LocalDate nextYear = LocalDate.now().plusYears(1);
		// Walk to the next Friday so the day under test is genuinely rest.
		while (nextYear.getDayOfWeek().getValue() != 5) {
			nextYear = nextYear.plusDays(1);
		}

		CalendarDayView future = day(admin.accessToken(), employeeId, nextYear.toString());

		assertThat(future.isWeeklyRest()).isTrue();
		assertThat(future.weeklyRestCredit()).isEqualTo(WeeklyRestCredit.PENDING);
	}

	@Test
	void aHolidayInsideTheCoverageWindowCostsTheEmployeeTheirRestDay() {
		AuthResponse admin = registerCompanyAdmin();
		Long employeeId = employeeRestingWeekends(admin.companyId());
		punch(admin.companyId(), employeeId, "2026-03-02");
		punch(admin.companyId(), employeeId, "2026-03-03");
		// Worked Wednesday too -- but it is declared a holiday, and legacy
		// counts a holiday as covering nothing while still consuming a slot.
		createHolidays(admin.accessToken(), "National Day", "2026-03-04");

		CalendarDayView friday = day(admin.accessToken(), employeeId, "2026-03-06");

		// Ported deliberately: the in-code comment in legacy claims the
		// opposite intent, but the implementation excludes holidays from
		// coverage. Filed for a keep-or-fix decision.
		assertThat(friday.weeklyRestCredit()).isEqualTo(WeeklyRestCredit.VOID);
	}

}
