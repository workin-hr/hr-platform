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

/** Module template. */
@Service
public class ShiftService {

	private final ShiftRepository shiftRepository;
	private final TenantSessionVariable tenantSessionVariable;

	public ShiftService(ShiftRepository shiftRepository, TenantSessionVariable tenantSessionVariable) {
		this.shiftRepository = shiftRepository;
		this.tenantSessionVariable = tenantSessionVariable;
	}

	@Transactional(readOnly = true)
	public List<ShiftView> list(AuthorizationContext context) {
		tenantSessionVariable.apply(context.companyId());
		return shiftRepository.findByCompanyIdOrderById(context.companyId()).stream()
				.map(ShiftView::of).toList();
	}

	@Transactional(readOnly = true)
	public Optional<ShiftView> get(AuthorizationContext context, Long shiftId) {
		tenantSessionVariable.apply(context.companyId());
		return shiftRepository.findByIdAndCompanyId(shiftId, context.companyId()).map(ShiftView::of);
	}

	@Transactional
	public ShiftView create(AuthorizationContext context, UpsertShiftRequest request) {
		tenantSessionVariable.apply(context.companyId());
		Shift shift = new Shift(context.companyId());
		shift.apply(request);
		return ShiftView.of(shiftRepository.save(shift));
	}

	@Transactional
	public Optional<ShiftView> update(AuthorizationContext context, Long shiftId, UpsertShiftRequest request) {
		tenantSessionVariable.apply(context.companyId());
		return shiftRepository.findByIdAndCompanyId(shiftId, context.companyId())
				.map(shift -> {
					shift.apply(request);
					return ShiftView.of(shift);
				});
	}

	@Transactional
	public boolean delete(AuthorizationContext context, Long shiftId) {
		tenantSessionVariable.apply(context.companyId());
		Optional<Shift> found = shiftRepository.findByIdAndCompanyId(shiftId, context.companyId());
		if (found.isEmpty()) {
			return false;
		}
		try {
			shiftRepository.delete(found.get());
			shiftRepository.flush();
		} catch (DataIntegrityViolationException ex) {
			throw new ResponseStatusException(HttpStatus.CONFLICT, "shift is still referenced", ex);
		}
		return true;
	}

}
