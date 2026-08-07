package com.workin.backend.attendance;

import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.workin.backend.tenancy.AuthorizationContext;
import com.workin.backend.tenancy.TenantSessionVariable;

/** Module template (see SalaryContractService); no conflict states exist here. */
@Service
public class ExceptionTypeService {

	private final ExceptionTypeRepository exceptionTypeRepository;
	private final TenantSessionVariable tenantSessionVariable;

	public ExceptionTypeService(
			ExceptionTypeRepository exceptionTypeRepository, TenantSessionVariable tenantSessionVariable) {
		this.exceptionTypeRepository = exceptionTypeRepository;
		this.tenantSessionVariable = tenantSessionVariable;
	}

	@Transactional(readOnly = true)
	public List<ExceptionTypeView> list(AuthorizationContext context) {
		tenantSessionVariable.apply(context.companyId());
		return exceptionTypeRepository.findByCompanyIdOrderById(context.companyId()).stream()
				.map(ExceptionTypeView::of)
				.toList();
	}

	@Transactional
	public ExceptionTypeView create(AuthorizationContext context, CreateExceptionTypeRequest request) {
		tenantSessionVariable.apply(context.companyId());
		return ExceptionTypeView.of(
				exceptionTypeRepository.save(new ExceptionType(context.companyId(), request.name())));
	}

}
