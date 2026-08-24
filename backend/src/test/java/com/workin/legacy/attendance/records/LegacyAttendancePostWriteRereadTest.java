package com.workin.legacy.attendance.records;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.workin.legacy.LegacyClock;
import com.workin.legacy.attendance.LegacyExceptionTypeService;
import com.workin.legacy.wire.LegacyApiException;

/**
 * `public_row($row)` requires an array, so a post-write re-read that returns
 * null is an uncaught PHP TypeError and therefore D-084's generic 500.
 *
 * <p>Only a concurrent delete or an employee cascade can open that window, and
 * legacy has no transaction to close it. Forcing the race through HTTP would
 * mean a timing-dependent integration test, so the seam is exercised directly:
 * the store is stubbed to return null from the re-read while every earlier
 * step succeeds.
 *
 * <p>What is being prevented is a silent success -- the row is written, and
 * answering 201/200 with the data key merely omitted would tell the caller
 * nothing went wrong while handing them no record.
 */
class LegacyAttendancePostWriteRereadTest {

	private static final long COMPANY = 501L;
	private static final long EMPLOYEE = 601L;

	private final LegacyAttendanceStore store = mock(LegacyAttendanceStore.class);
	private final LegacyClock clock = mock(LegacyClock.class);
	private final LegacyExceptionTypeService exceptionTypes = mock(LegacyExceptionTypeService.class);
	private final LegacyAttendanceService service =
			new LegacyAttendanceService(store, clock, exceptionTypes);

	private static Map<String, Object> row() {
		Map<String, Object> row = new LinkedHashMap<>();
		row.put("id", 9L);
		row.put("employee_id", EMPLOYEE);
		row.put("check_in", "2026-01-15 09:00:00");
		row.put("check_out", null);
		row.put("exception_type_id", null);
		return row;
	}

	@Test
	void createTurnsANullPostInsertRereadIntoAnUnexpectedFailure() {
		when(clock.now()).thenReturn(LocalDateTime.of(2026, 3, 11, 14, 37, 19));
		when(store.employeeExistsInCompany(COMPANY, EMPLOYEE)).thenReturn(true);
		when(store.latestCheckIn(EMPLOYEE)).thenReturn(null);
		when(store.insert(anyLong(), anyString(), any(), any(), any())).thenReturn(9L);
		// The race: the row is gone by the time it is read back.
		when(store.recordFull(COMPANY, 9L)).thenReturn(null);

		Map<String, Object> body = new LinkedHashMap<>();
		body.put("employee_id", EMPLOYEE);
		body.put("check_in", "2026-01-15 09:00:00");

		assertThatThrownBy(() -> service.create(COMPANY, body))
				// Not a LegacyApiException: it must reach D-084's generic 500
				// rather than render a keyed failure.
				.isNotInstanceOf(LegacyApiException.class)
				.isInstanceOf(IllegalStateException.class);
	}

	@Test
	void updateTurnsANullPostUpdateRereadIntoAnUnexpectedFailure() {
		when(clock.now()).thenReturn(LocalDateTime.of(2026, 3, 11, 14, 37, 19));
		// First read succeeds, the post-update read does not.
		when(store.recordFull(COMPANY, 9L)).thenReturn(row(), (Map<String, Object>) null);

		Map<String, Object> body = new LinkedHashMap<>();
		body.put("check_out", "2026-01-15 17:00:00");

		assertThatThrownBy(() -> service.update(COMPANY, 9L, () -> body))
				.isNotInstanceOf(LegacyApiException.class)
				.isInstanceOf(IllegalStateException.class);
	}

	/**
	 * The deliberate exception: update's hard-delete branch calls
	 * `ok(ATTENDANCE_RECORD_UPDATED, null)`, so an omitted data key is the
	 * contract there and must not be turned into a failure.
	 */
	@Test
	void theHardDeleteBranchStillReturnsSuccessWithNoRow() {
		when(clock.now()).thenReturn(LocalDateTime.of(2026, 3, 11, 14, 37, 19));
		when(store.recordFull(COMPANY, 9L)).thenReturn(row());

		Map<String, Object> body = new LinkedHashMap<>();
		body.put("clear_check_in", 1);
		body.put("clear_check_out", 1);

		LegacyAttendanceService.UpdateOutcome outcome = service.update(COMPANY, 9L, () -> body);

		assertThat(outcome.deleted()).isTrue();
		assertThat(outcome.row()).isNull();
	}

	/**
	 * The body is read <b>exactly once</b> for an existing row.
	 *
	 * <p>Not bookkeeping. `LegacyJsonBody.read(request)` consumes the
	 * servlet input stream, so a second invocation would not merely repeat
	 * work -- it would read a drained stream and yield an empty body. In
	 * `update.php` an empty body is a legitimate no-op that answers 200, so
	 * a future refactor calling the supplier twice would turn a real update
	 * into a silent no-op with nothing thrown to notice. Zero and two are
	 * both wrong, and only a counter distinguishes them.
	 */
	@Test
	void theBodyIsReadExactlyOnceForAnExistingRow() {
		when(clock.now()).thenReturn(LocalDateTime.of(2026, 3, 11, 14, 37, 19));
		when(store.recordFull(COMPANY, 9L)).thenReturn(row());

		java.util.concurrent.atomic.AtomicInteger reads =
				new java.util.concurrent.atomic.AtomicInteger();
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("check_out", "2026-01-15 17:00:00");

		service.update(COMPANY, 9L, () -> {
			reads.incrementAndGet();
			return body;
		});

		assertThat(reads.get())
				.describedAs("PHP evaluates body() once; a second read would drain the stream")
				.isEqualTo(1);
	}

	/**
	 * The body is not read until the scoped row has been found. A supplier that
	 * would blow up proves the 404 wins.
	 */
	@Test
	void aMissingRowIsAnswered404WithoutEverReadingTheBody() {
		when(store.recordFull(COMPANY, 9L)).thenReturn(null);

		assertThatThrownBy(() -> service.update(COMPANY, 9L, () -> {
			throw new IllegalStateException("the body must not be read");
		}))
				.isInstanceOf(LegacyApiException.class)
				.hasFieldOrPropertyWithValue("status", 404);
	}

}
