package com.workin.backend.platformadmin;

import java.util.List;

/**
 * The companies this surface administers, over whichever database the active
 * profile selects.
 *
 * <p>This exists because the platform-admin surface runs under both profiles
 * and they do not share a company mapping: the PostgreSQL domain has
 * {@code identity.Company}, and the Phase 1 profile has
 * {@code legacy.companies.LegacyCompany} over the frozen MySQL table. The
 * alternative -- letting {@link PlatformAdminCompanyService} depend on one of
 * them -- is what made it fail to start on MySQL with
 * {@code NoSuchBeanDefinitionException: CompanyRepository}.
 *
 * <p>Deliberately narrow. The admin surface needs to list companies and change
 * one company's lifecycle status; it has no business with the rest of either
 * entity, and a wider interface would invite it to grow one.
 */
public interface PlatformAdminCompanyDirectory {

	/**
	 * @param id the company's identifier
	 * @param name its name, which is null for a pending signup (D-035)
	 * @param status one of {@code active}, {@code pending}, {@code rejected}, {@code suspended}
	 */
	record CompanyView(long id, String name, String status) {
	}

	/**
	 * One company, with the counts an operator needs before deciding.
	 *
	 * <p>Mirrors what the PHP dashboard's {@code detail.php} shows: the work
	 * outstanding against a company is the thing that makes suspending it a
	 * decision rather than a click.
	 */
	record CompanyDetail(CompanyView company, String rejectionReason,
			long pendingRequests, long pendingAdvances) {
	}

	List<CompanyView> list(int limit);

	java.util.Optional<CompanyDetail> detail(long companyId);

	/**
	 * @return whether a company with that id existed and was updated
	 */
	boolean updateStatus(long companyId, String status);

	/**
	 * Rejects a company, recording why.
	 *
	 * <p>Separate from {@link #updateStatus} rather than a nullable parameter on
	 * it, because the reason is written on rejection and on nothing else. PHP
	 * behaves the same way: approving a previously rejected company leaves the
	 * old reason in place rather than clearing it, and a single method with an
	 * optional argument would quietly invite the opposite.
	 *
	 * @return whether a company with that id existed and was updated
	 */
	boolean reject(long companyId, String reason);

}
