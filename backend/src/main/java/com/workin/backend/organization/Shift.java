package com.workin.backend.organization;

import java.time.LocalTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * Shift template (V29). company_id is NOT NULL here although legacy
 * allows null -- unowned rows are invisible under RLS (recorded
 * normalization). days_off keeps legacy's "Fri,Sat" free text.
 */
@Entity
@Table(name = "shifts")
public class Shift {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "company_id", nullable = false)
	private Long companyId;

	@Column(nullable = false)
	private String name;

	@Column(name = "start_time", nullable = false)
	private LocalTime startTime;

	@Column(name = "end_time", nullable = false)
	private LocalTime endTime;

	@Column(name = "days_off")
	private String daysOff;

	@Column(name = "is_active", nullable = false)
	private boolean active = true;

	protected Shift() {
	}

	public Shift(Long companyId) {
		this.companyId = companyId;
	}

	public void apply(UpsertShiftRequest request) {
		this.name = request.name();
		this.startTime = request.startTime();
		this.endTime = request.endTime();
		this.daysOff = request.daysOff();
		this.active = request.isActive() == null || request.isActive();
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

	public LocalTime getStartTime() {
		return startTime;
	}

	public LocalTime getEndTime() {
		return endTime;
	}

	public String getDaysOff() {
		return daysOff;
	}

	public boolean isActive() {
		return active;
	}

}
