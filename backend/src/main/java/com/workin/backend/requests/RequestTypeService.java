package com.workin.backend.requests;

import java.util.List;
import java.util.Optional;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.workin.backend.attendance.ExceptionTypeRepository;
import com.workin.backend.tenancy.AuthorizationContext;
import com.workin.backend.tenancy.TenantSessionVariable;

/**
 * Request-type lookup, module template. is_active is stored and
 * exposed but not enforced on request creation in this slice (open
 * question recorded in the slice spec). exception_type_id is kept
 * only alongside add_attendance_exception, matching legacy's
 * request_type_fields_from_post exactly (silently nulled otherwise).
 */
@Service
public class RequestTypeService {

	private final RequestTypeRepository requestTypeRepository;
	private final ExceptionTypeRepository exceptionTypeRepository;
	private final TenantSessionVariable tenantSessionVariable;

	public RequestTypeService(
			RequestTypeRepository requestTypeRepository,
			ExceptionTypeRepository exceptionTypeRepository,
			TenantSessionVariable tenantSessionVariable) {
		this.requestTypeRepository = requestTypeRepository;
		this.exceptionTypeRepository = exceptionTypeRepository;
		this.tenantSessionVariable = tenantSessionVariable;
	}

	@Transactional(readOnly = true)
	public List<RequestTypeView> list(AuthorizationContext context) {
		tenantSessionVariable.apply(context.companyId());
		return requestTypeRepository.findByCompanyIdOrderById(context.companyId()).stream()
				.map(RequestTypeView::of)
				.toList();
	}

	@Transactional
	public Optional<RequestTypeView> create(AuthorizationContext context, CreateRequestTypeRequest request) {
		tenantSessionVariable.apply(context.companyId());
		boolean addException = Boolean.TRUE.equals(request.addAttendanceException());
		Long exceptionTypeId = addException ? request.exceptionTypeId() : null;
		if (exceptionTypeId != null
				&& exceptionTypeRepository.findByIdAndCompanyId(exceptionTypeId, context.companyId()).isEmpty()) {
			return Optional.empty();
		}
		RequestType type = new RequestType(
				context.companyId(), request.name(),
				request.isActive() == null || request.isActive(),
				Boolean.TRUE.equals(request.deductBalance()),
				request.countsAsPaidLeave() == null || request.countsAsPaidLeave(),
				addException, exceptionTypeId);
		return Optional.of(RequestTypeView.of(requestTypeRepository.save(type)));
	}

}
