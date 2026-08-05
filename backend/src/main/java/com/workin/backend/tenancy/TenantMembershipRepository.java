package com.workin.backend.tenancy;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

/**
 * RLS-scoped access to tenant_memberships -- every query through this
 * repository runs against the {@code app_runtime} DataSource
 * (RlsDataSourceConfig's {@code @Primary} bean) and is therefore
 * filtered by whatever {@code app.current_company_id} the current
 * transaction has set. Use this once a tenant context is already
 * established. Resolving *which* tenant an identity belongs to, before
 * that context exists, is a different, narrower operation -- see
 * {@link IdentityMembershipIndexService}.
 */
public interface TenantMembershipRepository extends JpaRepository<TenantMembership, Long> {

	Optional<TenantMembership> findByIdAndCompanyId(Long id, Long companyId);

}
