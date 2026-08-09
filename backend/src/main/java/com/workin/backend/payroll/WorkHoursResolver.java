package com.workin.backend.payroll;

import java.math.BigDecimal;

import org.springframework.stereotype.Component;

import com.workin.backend.employees.Employee;
import com.workin.backend.employees.EmployeeRepository;
import com.workin.backend.organization.JobTitle;
import com.workin.backend.organization.JobTitleRepository;

/**
 * {@code payroll_employee_work_hours_per_day}
 * (hr-legacy/apis/helpers/payroll_calculation.php:743-756 @ d113204) —
 * the divisor behind the overtime hourly rate, and the yardstick for how
 * many hours a present day was supposed to produce.
 *
 * <p><b>Shifts are deliberately not in this chain.</b> Payroll resolves
 * daily hours from the employee, then the job title, then a flat 8 —
 * it never consults the assigned shift, even though the attendance
 * calendar's own expected-minutes resolution is shift-first. The two
 * answers can therefore disagree for the same employee on the same day,
 * and legacy relies on this one for money.
 *
 * <p>NULL and 0 both mean "unset" at each step, which is what legacy's
 * {@code NULLIF(...,0)} buys it; a non-positive final result falls back
 * to 8.
 */
@Component
public class WorkHoursResolver {

	private static final BigDecimal DEFAULT_WORK_HOURS_PER_DAY = BigDecimal.valueOf(8);

	private final EmployeeRepository employeeRepository;
	private final JobTitleRepository jobTitleRepository;

	public WorkHoursResolver(EmployeeRepository employeeRepository, JobTitleRepository jobTitleRepository) {
		this.employeeRepository = employeeRepository;
		this.jobTitleRepository = jobTitleRepository;
	}

	public BigDecimal forEmployee(Long companyId, Long employeeId) {
		Employee employee = employeeRepository.findByIdAndCompanyId(employeeId, companyId).orElse(null);
		if (employee == null) {
			return DEFAULT_WORK_HOURS_PER_DAY;
		}
		if (isSet(employee.getExpectedDailyHours())) {
			return employee.getExpectedDailyHours();
		}
		BigDecimal fromJobTitle = employee.getJobTitleId() == null
				? null
				: jobTitleRepository.findByIdAndCompanyId(employee.getJobTitleId(), companyId)
						.map(JobTitle::getWorkHours)
						.orElse(null);
		return isSet(fromJobTitle) ? fromJobTitle : DEFAULT_WORK_HOURS_PER_DAY;
	}

	private static boolean isSet(BigDecimal value) {
		return value != null && value.signum() > 0;
	}

}
