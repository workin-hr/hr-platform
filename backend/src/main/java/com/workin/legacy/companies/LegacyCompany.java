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

	public Long getId() {
		return id;
	}

	/** Raw legacy text -- {@code pending}, {@code active}, {@code rejected}, {@code suspended}. */
	public String getStatus() {
		return status;
	}

}
