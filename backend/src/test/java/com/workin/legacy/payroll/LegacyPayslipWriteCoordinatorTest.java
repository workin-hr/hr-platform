package com.workin.legacy.payroll;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.sql.Connection;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

import com.workin.legacy.wire.LegacyApiException;

class LegacyPayslipWriteCoordinatorTest {

	private final LegacyPayslipService service = mock(LegacyPayslipService.class);
	private final LegacyPayslipStore payslipStore = mock(LegacyPayslipStore.class);
	private final LegacyPayrollBatchStore batchStore = mock(LegacyPayrollBatchStore.class);

	@Test
	void updateDoesNotMutateWhenFinalizationWinsAfterTargetPreflight() throws Exception {
		LegacyPayslipWriteCoordinator coordinator = coordinator();
		when(payslipStore.withBatchStatus(71L, 9L)).thenReturn(Map.of("batch_id", 41L, "batch_status", "draft"));
		when(batchStore.scopedForUpdate(41L, 9L)).thenReturn(Map.of("id", 41L, "status", "finalized"));

		assertThatThrownBy(() -> coordinator.update(9L, 71L, Map.of("basic_salary", 1000), "P", "R", "H"))
				.isInstanceOfSatisfying(LegacyApiException.class, ex ->
						org.assertj.core.api.Assertions.assertThat(ex.getMessageKey()).isEqualTo("batch_already_finalized"));
		verify(service, never()).update(9L, 71L, Map.of("basic_salary", 1000), "P", "R", "H");
	}

	@Test
	void createDoesNotInsertWhenBatchIsFinalizedBeforeTheLifecycleLock() throws Exception {
		LegacyPayslipWriteCoordinator coordinator = coordinator();
		Map<String, Object> body = Map.of("batch_id", 41L, "employee_id", 51L);
		when(batchStore.scopedForUpdate(41L, 9L)).thenReturn(Map.of("id", 41L, "status", "finalized"));

		assertThatThrownBy(() -> coordinator.create(9L, body))
				.isInstanceOf(LegacyApiException.class);
		verify(service, never()).create(9L, body);
	}

	@Test
	void deleteDoesNotRemovePayslipWhenFinalizationWinsAfterTargetPreflight() throws Exception {
		LegacyPayslipWriteCoordinator coordinator = coordinator();
		when(payslipStore.withBatchStatus(71L, 9L)).thenReturn(Map.of("batch_id", 41L, "batch_status", "draft"));
		when(batchStore.scopedForUpdate(41L, 9L)).thenReturn(Map.of("id", 41L, "status", "finalized"));

		assertThatThrownBy(() -> coordinator.delete(9L, 71L))
				.isInstanceOf(LegacyApiException.class);
		verify(service, never()).delete(9L, 71L);
	}

	@Test
	void mutableBatchDelegatesTheWriteWhileTheLifecycleLockIsHeld() throws Exception {
		LegacyPayslipWriteCoordinator coordinator = coordinator();
		Map<String, Object> body = Map.of("batch_id", 41L, "employee_id", 51L);
		when(batchStore.scopedForUpdate(41L, 9L)).thenReturn(Map.of("id", 41L, "status", "draft"));
		when(service.create(9L, body)).thenReturn(Map.of("id", 71L));

		coordinator.create(9L, body);

		verify(batchStore).scopedForUpdate(41L, 9L);
		verify(service).create(9L, body);
		verify(service, never()).update(
				org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.anyLong(), anyMap(),
				org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString(),
				org.mockito.ArgumentMatchers.anyString());
	}

	private LegacyPayslipWriteCoordinator coordinator() throws Exception {
		Connection connection = mock(Connection.class);
		when(connection.getAutoCommit()).thenReturn(true);
		SingleConnectionDataSource dataSource = new SingleConnectionDataSource(connection, true);
		return new LegacyPayslipWriteCoordinator(service, payslipStore, batchStore, dataSource);
	}
}
