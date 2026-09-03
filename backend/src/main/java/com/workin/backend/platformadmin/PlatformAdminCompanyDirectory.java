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

	List<CompanyView> list(int limit);

	/**
	 * @return whether a company with that id existed and was updated
	 */
	boolean updateStatus(long companyId, String status);

}
