package com.workin.backend.holidays;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

public interface OfficialHolidayRepository extends JpaRepository<OfficialHoliday, Long> {

	Optional<OfficialHoliday> findByIdAndCompanyId(Long id, Long companyId);

	Optional<OfficialHoliday> findByCompanyIdAndHolidayDate(Long companyId, LocalDate holidayDate);

	/**
	 * official_holidays_by_date_in_range
	 * (official_holidays_helper.php:61-81): inclusive on both ends,
	 * ascending. The ordering is load-bearing — legacy builds an
	 * insertion-ordered map from it and iterates that map directly.
	 */
	List<OfficialHoliday> findByCompanyIdAndHolidayDateBetweenOrderByHolidayDateAsc(
			Long companyId, LocalDate from, LocalDate to);

	/** list.php's ordering: {@code holiday_date ASC, id ASC}. */
	List<OfficialHoliday> findByCompanyIdOrderByHolidayDateAscIdAsc(Long companyId);

}
