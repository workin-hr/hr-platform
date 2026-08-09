package com.workin.backend.organization;

import java.util.List;
import java.util.Optional;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.workin.backend.i18n.ApiException;
import com.workin.backend.i18n.MessageKeys;
import com.workin.backend.tenancy.AuthorizationContext;
import com.workin.backend.tenancy.TenantSessionVariable;

/** Module template. Delete of an externally-referenced branch -> 409 via the real FK. */
@Service
public class BranchService {

	private final BranchRepository branchRepository;
	private final TenantSessionVariable tenantSessionVariable;

	public BranchService(BranchRepository branchRepository, TenantSessionVariable tenantSessionVariable) {
		this.branchRepository = branchRepository;
		this.tenantSessionVariable = tenantSessionVariable;
	}

	@Transactional(readOnly = true)
	public List<BranchView> list(AuthorizationContext context) {
		tenantSessionVariable.apply(context.companyId());
		return branchRepository.findByCompanyIdOrderById(context.companyId()).stream()
				.map(BranchView::of).toList();
	}

	@Transactional(readOnly = true)
	public Optional<BranchView> get(AuthorizationContext context, Long branchId) {
		tenantSessionVariable.apply(context.companyId());
		return branchRepository.findByIdAndCompanyId(branchId, context.companyId()).map(BranchView::of);
	}

	@Transactional
	public BranchView create(AuthorizationContext context, UpsertBranchRequest request) {
		tenantSessionVariable.apply(context.companyId());
		Branch branch = new Branch(context.companyId());
		branch.apply(request);
		return BranchView.of(branchRepository.save(branch));
	}

	@Transactional
	public Optional<BranchView> update(AuthorizationContext context, Long branchId, UpsertBranchRequest request) {
		tenantSessionVariable.apply(context.companyId());
		return branchRepository.findByIdAndCompanyId(branchId, context.companyId())
				.map(branch -> {
					branch.apply(request);
					return BranchView.of(branch);
				});
	}

	@Transactional
	public boolean delete(AuthorizationContext context, Long branchId) {
		tenantSessionVariable.apply(context.companyId());
		Optional<Branch> branch = branchRepository.findByIdAndCompanyId(branchId, context.companyId());
		if (branch.isEmpty()) {
			return false;
		}
		try {
			branchRepository.delete(branch.get());
			branchRepository.flush();
		} catch (DataIntegrityViolationException ex) {
			// Referenced from outside (junction rows, employees) -- thrown,
			// not returned (the rollback-only lesson).
			throw new ApiException(HttpStatus.CONFLICT, MessageKeys.ORGANIZATION_BRANCH_REFERENCED, ex);
		}
		return true;
	}

}
