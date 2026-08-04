package com.workin.spike.referencedata.isolation;

import com.workin.spike.referencedata.Branch;
import com.workin.spike.referencedata.BranchRepository;
import com.workin.spike.referencedata.BranchService;
import com.workin.spike.referencedata.BranchView;
import com.workin.spike.security.CurrentTenant;
import java.util.List;
import java.util.Optional;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * H2 spike, "isolation-guard" arm. No RLS exists in this database at all
 * (see application-isolation-guard.properties) -- every query here
 * explicitly filters by {@code company_id} at the repository-call site,
 * mirroring hr-legacy's own {@code org_verify_post_row()} pattern. This
 * arm proves the repository-guard pattern works correctly *when
 * application code remembers to use it* -- see
 * GuardCrossTenantIsolationTest's "forgot to scope" case for what
 * happens when it doesn't, which is the discipline-dependence risk this
 * arm is explicitly named for.
 */
@Service
@Profile("isolation-guard")
public class GuardBranchService implements BranchService {

    private final BranchRepository branchRepository;
    private final CurrentTenant currentTenant;

    public GuardBranchService(BranchRepository branchRepository, CurrentTenant currentTenant) {
        this.branchRepository = branchRepository;
        this.currentTenant = currentTenant;
    }

    @Override
    @Transactional(readOnly = true)
    public List<BranchView> listForCurrentCompany() {
        return branchRepository.findAllByCompanyId(currentTenant.getCompanyId()).stream().map(BranchView::of).toList();
    }

    @Override
    @Transactional
    public BranchView create(String name) {
        Branch branch = new Branch(currentTenant.getCompanyId(), name);
        return BranchView.of(branchRepository.save(branch));
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<BranchView> findById(Long id) {
        return branchRepository.findByIdAndCompanyId(id, currentTenant.getCompanyId()).map(BranchView::of);
    }
}
