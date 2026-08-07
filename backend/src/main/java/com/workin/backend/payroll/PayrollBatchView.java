package com.workin.backend.payroll;

import java.time.LocalDate;

public record PayrollBatchView(Long id, Short month, Short year, LocalDate periodFrom, LocalDate periodTo, BatchStatus status) {

	static PayrollBatchView of(PayrollBatch b) {
		return new PayrollBatchView(b.getId(), b.getMonth(), b.getYear(), b.getPeriodFrom(), b.getPeriodTo(), b.getStatus());
	}

}
