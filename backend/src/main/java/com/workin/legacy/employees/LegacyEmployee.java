package com.workin.legacy.employees;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import org.hibernate.annotations.Filter;

import com.workin.legacy.LegacyValues;
import com.workin.legacy.TenantFilter;

/**
 * The legacy {@code employees} row, mapped exactly as MySQL holds it.
 *
 * <p>Phase 1 treats the legacy schema as an external contract
 * (ADR-0011): Java adapts to storage, storage does not adapt to Java.
 * So this mirrors {@code hr-legacy/mysql_workin.schema.sql}'s 27 columns
 * verbatim -- including the seven the PostgreSQL {@code Employee} entity
 * dropped ({@code token_version}, {@code address}, {@code photo_url},
 * {@code contract_duration_months}, {@code is_mobile_attendance_enabled},
 * {@code can_check_in_any_branch}, {@code join_request_status}) and the
 * legacy spellings the target schema renamed
 * ({@code is_active}, not {@code active}).
 *
 * <p><b>This is a persistence type, not a domain type.</b> It exists to
 * be a faithful reading of a legacy row and nothing else. Business code
 * should not consume it directly; the accessors marked below produce
 * domain values, and a mapper turns those into the domain model when the
 * domain layer is reworked. Keeping the seam here is what lets Phase 2
 * swap the adapter instead of rewriting business services.
 *
 * <h2>Why the two date columns are Strings</h2>
 * <p>{@code birth_date} and {@code hire_date} hold MySQL zero dates
 * ({@code '0000-00-00'}) on 2 and 22 live rows respectively
 * (docs/migration/invalid-date-analysis.md). The JDBC driver raises when
 * it reads one into a date type, <em>before</em> any
 * {@code AttributeConverter} could intervene -- so the raw text is read
 * and {@link LegacyValues#toDate} interprets it. Same rule the ETL
 * proved: guard the raw value, never cast first and repair after
 * (D-035/A2).
 *
 * <p>The enum and {@code tinyint(1)} columns carry no such hazard, so
 * they are read as their natural JDBC types and interpreted by the same
 * helper for consistency.
 */
@Entity
@Table(name = "employees")
@Filter(name = TenantFilter.NAME, condition = TenantFilter.CONDITION)
public class LegacyEmployee {

	/**
	 * Legacy enum, lower snake case in the database. Java's constants
	 * are the same values by name -- see
	 * {@link LegacyValues#toEnum}, which maps by name and not by
	 * ordinal so a reorder on either side cannot silently re-point.
	 */
	public enum Role { COMPANY_ADMIN, HR, MANAGER, EMPLOYEE }

	public enum Gender { MALE, FEMALE, OTHER }

	public enum JoinRequestStatus { PENDING, ACCEPTED, REJECTED }

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "company_id", nullable = false)
	private Long companyId;

	/** {@code NOT NULL} in legacy, unlike every other org reference. */
	@Column(name = "branch_id", nullable = false)
	private Long branchId;

	@Column(name = "department_id")
	private Long departmentId;

	@Column(name = "job_title_id")
	private Long jobTitleId;

	@Column(name = "employee_code", length = 64)
	private String employeeCode;

	@Column(name = "expected_daily_hours", precision = 5, scale = 2)
	private BigDecimal expectedDailyHours;

	@Column(name = "first_name", nullable = false)
	private String firstName;

	/** {@code NOT NULL DEFAULT ''} -- absent is an empty string here. */
	@Column(name = "last_name", nullable = false)
	private String lastName;

	@Column(name = "phone", length = 20)
	private String phone;

	@Column(name = "country_code", length = 10)
	private String countryCode;

	@Column(name = "password_hash")
	private String passwordHash;

	@Column(name = "token_version", nullable = false)
	private Integer tokenVersion;

	@Column(name = "role", nullable = false)
	private String role;

	@Column(name = "national_id", length = 50)
	private String nationalId;

	/** Raw legacy text -- may be {@code '0000-00-00'}. See class javadoc. */
	@Column(name = "birth_date")
	private String birthDate;

	@Column(name = "gender")
	private String gender;

	@Column(name = "address", length = 500)
	private String address;

	@Column(name = "photo_url", length = 500)
	private String photoUrl;

	/** Raw legacy text -- may be {@code '0000-00-00'}. See class javadoc. */
	@Column(name = "hire_date")
	private String hireDate;

	@Column(name = "contract_duration_months")
	private Integer contractDurationMonths;

	@Column(name = "is_active", nullable = false)
	private Integer isActive;

	@Column(name = "is_mobile_attendance_enabled", nullable = false)
	private Integer isMobileAttendanceEnabled;

	@Column(name = "can_check_in_any_branch", nullable = false)
	private Integer canCheckInAnyBranch;

	@Column(name = "join_request_status", nullable = false)
	private String joinRequestStatus;

	@Column(name = "created_at", nullable = false, insertable = false, updatable = false)
	private Instant createdAt;

	/**
	 * {@code ON UPDATE current_timestamp()} -- the database maintains
	 * this, so Java never writes it. Marked non-insertable/non-updatable
	 * so Hibernate cannot start disagreeing with the column's own
	 * default, which is the trap D-033/OQ-3 describes.
	 */
	@Column(name = "updated_at", nullable = false, insertable = false, updatable = false)
	private Instant updatedAt;

	// ---------- domain-shaped accessors ----------
	// The point of the adapter: callers get real types, and the legacy
	// representation stops at this class.

	public LocalDate birthDate() {
		return LegacyValues.toDate(birthDate);
	}

	public LocalDate hireDate() {
		return LegacyValues.toDate(hireDate);
	}

	public boolean active() {
		return LegacyValues.toBoolean(isActive);
	}

	public boolean mobileAttendanceEnabled() {
		return LegacyValues.toBoolean(isMobileAttendanceEnabled);
	}

	public boolean canCheckInAnyBranch() {
		return LegacyValues.toBoolean(canCheckInAnyBranch);
	}

	public Role role() {
		return LegacyValues.toEnum(Role.class, role);
	}

	public Gender gender() {
		return LegacyValues.toEnum(Gender.class, gender);
	}

	public JoinRequestStatus joinRequestStatus() {
		return LegacyValues.toEnum(JoinRequestStatus.class, joinRequestStatus);
	}

	// ---------- raw accessors ----------

	public Long getId() {
		return id;
	}

	public Long getCompanyId() {
		return companyId;
	}

	public Long getBranchId() {
		return branchId;
	}

	public Long getDepartmentId() {
		return departmentId;
	}

	public Long getJobTitleId() {
		return jobTitleId;
	}

	public String getEmployeeCode() {
		return employeeCode;
	}

	public BigDecimal getExpectedDailyHours() {
		return expectedDailyHours;
	}

	public String getFirstName() {
		return firstName;
	}

	public String getLastName() {
		return lastName;
	}

	public String getPhone() {
		return phone;
	}

	public String getCountryCode() {
		return countryCode;
	}

	public String getPasswordHash() {
		return passwordHash;
	}

	public Integer getTokenVersion() {
		return tokenVersion;
	}

	public String getNationalId() {
		return nationalId;
	}

	public String getAddress() {
		return address;
	}

	public String getPhotoUrl() {
		return photoUrl;
	}

	public Integer getContractDurationMonths() {
		return contractDurationMonths;
	}

	public Instant getCreatedAt() {
		return createdAt;
	}

	public Instant getUpdatedAt() {
		return updatedAt;
	}

	// ---------- writes ----------
	// Deliberately narrow. Phase 1 adds setters as the modules that need
	// them are ported, so an unported write path is a compile error
	// rather than a silent partial write.

	public void setBirthDate(LocalDate value) {
		this.birthDate = LegacyValues.fromDate(value);
	}

	public void setHireDate(LocalDate value) {
		this.hireDate = LegacyValues.fromDate(value);
	}

	public void setActive(boolean value) {
		this.isActive = LegacyValues.fromBoolean(value);
	}

	public void setRole(Role value) {
		this.role = LegacyValues.fromEnum(value);
	}

	public void setGender(Gender value) {
		this.gender = LegacyValues.fromEnum(value);
	}

	public void setJoinRequestStatus(JoinRequestStatus value) {
		this.joinRequestStatus = LegacyValues.fromEnum(value);
	}

}
