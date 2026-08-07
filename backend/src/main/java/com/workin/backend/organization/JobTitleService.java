package com.workin.backend.organization;

import java.util.List;
import java.util.Optional;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import com.workin.backend.tenancy.AuthorizationContext;
import com.workin.backend.tenancy.TenantSessionVariable;

/** Module template; departmentId tenant-validated -> the same 404. */
@Service
public class JobTitleService {

	private final JobTitleRepository jobTitleRepository;
	private final DepartmentRepository departmentRepository;
	private final TenantSessionVariable tenantSessionVariable;

	public JobTitleService(
			JobTitleRepository jobTitleRepository,
			DepartmentRepository departmentRepository,
			TenantSessionVariable tenantSessionVariable) {
		this.jobTitleRepository = jobTitleRepository;
		this.departmentRepository = departmentRepository;
		this.tenantSessionVariable = tenantSessionVariable;
	}

	@Transactional(readOnly = true)
	public List<JobTitleView> list(AuthorizationContext context) {
		tenantSessionVariable.apply(context.companyId());
		return jobTitleRepository.findByCompanyIdOrderById(context.companyId()).stream()
				.map(JobTitleView::of).toList();
	}

	@Transactional(readOnly = true)
	public Optional<JobTitleView> get(AuthorizationContext context, Long jobTitleId) {
		tenantSessionVariable.apply(context.companyId());
		return jobTitleRepository.findByIdAndCompanyId(jobTitleId, context.companyId()).map(JobTitleView::of);
	}

	@Transactional
	public Optional<JobTitleView> create(AuthorizationContext context, UpsertJobTitleRequest request) {
		tenantSessionVariable.apply(context.companyId());
		if (!departmentResolves(context, request.departmentId())) {
			return Optional.empty();
		}
		JobTitle jobTitle = new JobTitle(context.companyId());
		jobTitle.apply(request);
		return Optional.of(JobTitleView.of(jobTitleRepository.save(jobTitle)));
	}

	@Transactional
	public Optional<JobTitleView> update(AuthorizationContext context, Long jobTitleId, UpsertJobTitleRequest request) {
		tenantSessionVariable.apply(context.companyId());
		Optional<JobTitle> found = jobTitleRepository.findByIdAndCompanyId(jobTitleId, context.companyId());
		if (found.isEmpty() || !departmentResolves(context, request.departmentId())) {
			return Optional.empty();
		}
		found.get().apply(request);
		return Optional.of(JobTitleView.of(found.get()));
	}

	@Transactional
	public boolean delete(AuthorizationContext context, Long jobTitleId) {
		tenantSessionVariable.apply(context.companyId());
		Optional<JobTitle> found = jobTitleRepository.findByIdAndCompanyId(jobTitleId, context.companyId());
		if (found.isEmpty()) {
			return false;
		}
		try {
			jobTitleRepository.delete(found.get());
			jobTitleRepository.flush();
		} catch (DataIntegrityViolationException ex) {
			throw new ResponseStatusException(HttpStatus.CONFLICT, "job title is still referenced", ex);
		}
		return true;
	}

	private boolean departmentResolves(AuthorizationContext context, Long departmentId) {
		return departmentId == null
				|| departmentRepository.findByIdAndCompanyId(departmentId, context.companyId()).isPresent();
	}

}
