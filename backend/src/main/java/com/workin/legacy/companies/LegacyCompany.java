package com.workin.legacy.companies;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * The legacy {@code companies} row, narrowed to exactly what the login
 * path needs (punch-list item #9): whether the company is active, per
 * {@link com.workin.legacy.auth.LegacyLoginCandidate#companyStatus}.
 *
 * <p>Not tenant-owned -- {@code companies} is the tenant root itself,
 * with no {@code company_id} column of its own -- so
 * {@code TenantFilterCoverageTest} correctly does not require a
 * {@code @Filter} here, the same reasoning
 * {@link com.workin.legacy.auth.LegacyRefreshToken}'s javadoc already
 * gives for its own table.
 *
 * <p>Deliberately narrow, matching {@code LegacyEmployee}'s own stated
 * policy: fields are added as the modules that need them are ported,
 * not spun up in full ahead of use. {@code status} stays a raw
 * {@link String} rather than an enum for the same reason
 * {@code LegacyEmployee} keeps {@code role}/{@code gender} raw
 * internally -- {@link com.workin.legacy.LegacyValues#fromEnum} already
 * round-trips a Java enum back to legacy's lower-case spelling when a
 * caller needs one, so nothing here needs its own enum yet.
 */
@Entity
@Table(name = "companies")
public class LegacyCompany {

	@Id
	private Long id;

	@Column(nullable = false)
	private String status;

	/**
	 * Nullable: a pending signup legitimately has no name yet -- 81 of 317
	 * production companies were unnamed and all of them pending (D-035). Mapped
	 * now because the platform-admin surface lists companies; the entity stays
	 * minimal otherwise, per this class's own note.
	 *
	 * <p>The column is {@code company_name}, not {@code name} -- PostgreSQL's
	 * own {@code companies} table uses {@code name}, and the two are not the
	 * same schema. That difference is exactly why the admin surface reaches
	 * companies through {@code PlatformAdminCompanyDirectory} rather than an
	 * entity.
	 */
	@Column(name = "company_name")
	private String name;

	public Long getId() {
		return id;
	}

	public String getName() {
		return name;
	}

	/** Raw legacy text -- {@code pending}, {@code active}, {@code rejected}, {@code suspended}. */
	public String getStatus() {
		return status;
	}

}
