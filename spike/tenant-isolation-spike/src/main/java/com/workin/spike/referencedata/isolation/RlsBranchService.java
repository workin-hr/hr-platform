package com.workin.spike.referencedata.isolation;

import com.workin.spike.referencedata.Branch;
import com.workin.spike.referencedata.BranchRepository;
import com.workin.spike.referencedata.BranchService;
import com.workin.spike.referencedata.BranchView;
import com.workin.spike.security.CurrentTenant;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.util.List;
import java.util.Optional;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * H2 spike, "isolation-rls" arm. Deliberately calls the repository's
 * UNSCOPED methods ({@code findAll}/{@code findById}) -- correctness
 * depends entirely on {@link #setTenantSessionVariable()} having run
 * first in the same transaction, which sets the Postgres session
 * variable {@code app.current_company_id} that
 * V3__enable_row_level_security.sql's policy filters on. This is the
 * point of this arm: prove tenant isolation holds even when application
 * code "forgets" to filter explicitly.
 */
@Service
@Profile("isolation-rls")
public class RlsBranchService implements BranchService {

    private final BranchRepository branchRepository;
    private final CurrentTenant currentTenant;

    @PersistenceContext
    private EntityManager entityManager;

    public RlsBranchService(BranchRepository branchRepository, CurrentTenant currentTenant) {
        this.branchRepository = branchRepository;
        this.currentTenant = currentTenant;
    }

    /**
     * SET LOCAL is transaction-scoped in Postgres -- it resets
     * automatically at transaction end, so there is no risk of a pooled
     * connection leaking one request's tenant context into the next
     * request that happens to reuse the same physical connection.
     */
    private void setTenantSessionVariable() {
        entityManager
                .createNativeQuery("SELECT set_config('app.current_company_id', :companyId, true)")
                .setParameter("companyId", String.valueOf(currentTenant.getCompanyId()))
                .getSingleResult();
    }

    @Override
    @Transactional
    public List<BranchView> listForCurrentCompany() {
        setTenantSessionVariable();
        return branchRepository.findAll().stream().map(BranchView::of).toList(); // unscoped on purpose
    }

    @Override
    @Transactional
    public BranchView create(String name) {
        setTenantSessionVariable();
        Branch branch = new Branch(currentTenant.getCompanyId(), name);
        return BranchView.of(branchRepository.save(branch));
    }

    @Override
    @Transactional
    public Optional<BranchView> findById(Long id) {
        setTenantSessionVariable();
        return branchRepository.findById(id).map(BranchView::of); // unscoped on purpose -- RLS must block cross-tenant reads
    }
}
