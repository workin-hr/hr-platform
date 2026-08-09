package com.workin.backend.holidays;

import java.time.Instant;
import java.time.LocalDate;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * One named, single-day company holiday (V38, ported from legacy's
 * {@code company_official_holidays}).
 *
 * <p>Legacy models a holiday as exactly one date — there is no range, no
 * recurrence, and no soft delete. A multi-day holiday is several rows,
 * which is why the create endpoint takes a list of dates.
 */
@Entity
@Table(name = "company_official_holidays")
public class OfficialHoliday {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "company_id", nullable = false)
	private Long companyId;

	@Column(nullable = false)
	private String name;

	@Column(name = "holiday_date", nullable = false)
	private LocalDate holidayDate;

	@Column(name = "updated_at", nullable = false)
	private Instant updatedAt = Instant.now();

	protected OfficialHoliday() {
	}

	public OfficialHoliday(Long companyId, String name, LocalDate holidayDate) {
		this.companyId = companyId;
		this.name = name;
		this.holidayDate = holidayDate;
	}

	/** Legacy's update writes both columns every time, even when unchanged. */
	public void update(String name, LocalDate holidayDate) {
		this.name = name;
		this.holidayDate = holidayDate;
		this.updatedAt = Instant.now();
	}

	public void rename(String name) {
		this.name = name;
		this.updatedAt = Instant.now();
	}

	public Long getId() {
		return id;
	}

	public Long getCompanyId() {
		return companyId;
	}

	public String getName() {
		return name;
	}

	public LocalDate getHolidayDate() {
		return holidayDate;
	}

}
