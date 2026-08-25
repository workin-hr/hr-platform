package com.workin.legacy.payroll;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.workin.legacy.LegacyQueryParameters;
import com.workin.legacy.attendance.LegacyWeeklyOffDays;
import com.workin.legacy.wire.LegacyApiException;

class LegacyPayrollBatchServiceTest {

	private final LegacyPayrollBatchStore store = mock(LegacyPayrollBatchStore.class);
	private final LegacyPayrollFiscalSettings fiscalSettings = mock(LegacyPayrollFiscalSettings.class);
	private final LegacyWeeklyOffDays weeklyOffDays = mock(LegacyWeeklyOffDays.class);
	private final LegacyPayrollBatchService service =
			new LegacyPayrollBatchService(store, fiscalSettings, weeklyOffDays);

	@Test
	void oneThrowsBatchNotFoundWhenTheStoreReturnsNothing() {
		when(store.withStats(91L, 9L)).thenReturn(null);
		assertThatThrownBy(() -> service.one(9L, 91L))
				.isInstanceOf(LegacyApiException.class)
				.satisfies(ex -> assertThat(((LegacyApiException) ex).getMessageKey()).isEqualTo("batch_not_found"));
	}

	@Test
	void createResolvesFiscalBoundsAndInsertsAsDraft() {
		when(fiscalSettings.fiscalPeriodBounds(9L, 2026, 4)).thenReturn(new String[] {"2026-04-01", "2026-04-30"});
		when(store.existsForPeriod(9L, 4, 2026)).thenReturn(false);
		when(store.insert(9L, 4, 2026, "2026-04-01", "2026-04-30", "draft")).thenReturn(77L);
		when(store.withStats(77L, 9L)).thenReturn(Map.of("id", 77L, "status", "draft"));

		Map<String, Object> row = service.create(9L, Map.of("month", 4, "year", 2026));

		assertThat(row).containsEntry("id", 77L);
		verify(store).insert(9L, 4, 2026, "2026-04-01", "2026-04-30", "draft");
	}

	@Test
	void createRejectsADuplicatePeriodBeforeInserting() {
		when(fiscalSettings.fiscalPeriodBounds(9L, 2026, 4)).thenReturn(new String[] {"2026-04-01", "2026-04-30"});
		when(store.existsForPeriod(9L, 4, 2026)).thenReturn(true);

		assertThatThrownBy(() -> service.create(9L, Map.of("month", 4, "year", 2026)))
				.isInstanceOf(LegacyApiException.class)
				.satisfies(ex -> assertThat(((LegacyApiException) ex).getMessageKey()).isEqualTo("already_exists"));
		verify(store, never()).insert(9L, 4, 2026, null, null, "draft");
	}

	@Test
	void createRequiresMonthAndYear() {
		assertThatThrownBy(() -> service.create(9L, Map.of("year", 2026)))
				.isInstanceOf(LegacyApiException.class)
				.satisfies(ex -> {
					LegacyApiException e = (LegacyApiException) ex;
					assertThat(e.getMessageKey()).isEqualTo("field_required");
					assertThat(e.getReplace()).containsEntry("field", "month");
				});
	}

	@Test
	void updateRefusesAFinalizedBatch() {
		when(store.scoped(91L, 9L)).thenReturn(Map.of("id", 91L, "status", "finalized"));
		assertThatThrownBy(() -> service.update(9L, 91L, Map.of()))
				.isInstanceOf(LegacyApiException.class)
				.satisfies(ex -> {
					LegacyApiException e = (LegacyApiException) ex;
					assertThat(e.getStatus()).isEqualTo(400);
					assertThat(e.getMessageKey()).isEqualTo("batch_already_finalized");
				});
	}

	@Test
	void updateWithNoBodyFieldsKeepsTheBatchsExistingMonthAndYear() {
		Map<String, Object> batch = new LinkedHashMap<>();
		batch.put("id", 91L);
		batch.put("status", "draft");
		batch.put("month", 3);
		batch.put("year", 2026);
		when(store.scoped(91L, 9L)).thenReturn(batch);
		when(fiscalSettings.fiscalPeriodBounds(9L, 2026, 3)).thenReturn(new String[] {"2026-03-01", "2026-03-31"});
		when(store.withStats(91L, 9L)).thenReturn(Map.of("id", 91L));

		service.update(9L, 91L, Map.of());

		verify(store).updatePeriod(91L, 3, 2026, "2026-03-01", "2026-03-31");
	}

	@Test
	void deleteRefusesAFinalizedBatchAndNeverTouchesPayslips() {
		when(store.scoped(91L, 9L)).thenReturn(Map.of("id", 91L, "status", "finalized"));
		assertThatThrownBy(() -> service.delete(9L, 91L)).isInstanceOf(LegacyApiException.class);
		verify(store, never()).deleteWithPayslips(91L);
	}

	@Test
	void deleteOfADraftBatchCascadesToPayslips() {
		when(store.scoped(91L, 9L)).thenReturn(Map.of("id", 91L, "status", "draft"));
		service.delete(9L, 91L);
		verify(store).deleteWithPayslips(91L);
	}

	@Test
	void fiscalPeriodRejectsAYearBeforeTwoThousand() {
		assertThatThrownBy(() -> service.fiscalPeriod(9L, 1999, 4))
				.isInstanceOf(LegacyApiException.class)
				.satisfies(ex -> assertThat(((LegacyApiException) ex).getMessageKey()).isEqualTo("invalid_input"));
	}

	@Test
	void fiscalPeriodRejectsAMonthOutsideOneToTwelve() {
		assertThatThrownBy(() -> service.fiscalPeriod(9L, 2026, 13))
				.isInstanceOf(LegacyApiException.class)
				.satisfies(ex -> assertThat(((LegacyApiException) ex).getMessageKey()).isEqualTo("invalid_input"));
	}

	@Test
	void fiscalPeriodCountsWorkingDaysAgainstTheResolvedBounds() {
		when(fiscalSettings.fiscalPeriodBounds(9L, 2026, 4)).thenReturn(new String[] {"2026-04-01", "2026-04-07"});
		when(weeklyOffDays.forCompany(9L)).thenReturn(List.of("friday"));

		Map<String, Object> result = service.fiscalPeriod(9L, 2026, 4);

		assertThat(result).containsEntry("period_from", "2026-04-01").containsEntry("period_to", "2026-04-07");
		// 2026-04-01..07 is Wed..Tue; Friday (04-03) is the only weekly-rest day in range -> 6 working days.
		assertThat(result).containsEntry("working_days", 6);
	}

	@Test
	void listPassesThroughStoreRowsAndPaginationMeta() {
		when(store.countForList(eq(9L), eq(null), eq(null), eq(null))).thenReturn(1L);
		when(store.list(eq(9L), eq(null), eq(null), eq(null), org.mockito.ArgumentMatchers.any()))
				.thenReturn(List.of(Map.of("id", 1L)));

		LegacyPayrollBatchService.Page page = service.list(9L, LegacyQueryParameters.parse(null));

		assertThat(page.rows()).hasSize(1);
		assertThat(page.meta()).containsEntry("total", 1L);
	}
}
