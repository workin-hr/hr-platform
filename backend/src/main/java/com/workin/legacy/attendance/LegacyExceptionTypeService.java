package com.workin.legacy.attendance;

import java.util.Map;
import java.util.Optional;

import jakarta.persistence.EntityManager;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.workin.backend.i18n.ApiException;
import com.workin.legacy.employees.LegacyEmployee;

/**
 * Wave 12.1's five endpoints, ported behaviour-for-behaviour from {@code
 * hr-legacy/apis/api/attendance_exception_types/*.php}, with D-046/D-048's
 * delete-path FK clearing.
 *
 * <h2>Name uniqueness is global (D-7/D-051), not company-scoped</h2>
 * <p>D-047 originally settled "scope uniqueness to the company... allow
 * the same name across different companies", on the premise that
 * legacy's global check was an <em>application-layer</em> defect
 * ({@code exception_type_name_exists()} has no {@code company_id}
 * predicate). Building this module found that premise incomplete: the
 * vendored schema itself carries {@code ALTER TABLE exception_types ADD
 * UNIQUE KEY unique_exception_type_name (name)}
 * (`mysql_workin.schema.sql:1158`) -- a real, table-wide, database-level
 * unique constraint on {@code name} alone. D-050 recorded the discovery;
 * D-051 resolved it: the repository owner accepted global uniqueness
 * (D-7 option (a)) rather than authorizing a schema migration, so this
 * is now the deliberate, final behaviour, not an interim gap.
 *
 * <p>Two enforcement layers, both intentional: {@link
 * LegacyExceptionTypeRepository#existsByCompanyIdAndName}-style checks
 * give a clean, pre-emptive 409 for the common case -- a duplicate
 * within the caller's own company -- without a round trip through the
 * database constraint. {@link #saveOrConflict} additionally catches the
 * {@link DataIntegrityViolationException} the real unique index raises
 * for a genuine cross-company collision, converting it to the same 409
 * rather than letting it surface as an unhandled 500. Net effect,
 * matching legacy exactly: a name is unique across every company,
 * regardless of which company already used it.
 */
@Service
public class LegacyExceptionTypeService {

	private static final int DEFAULT_LIMIT = 20;
	private static final int MAX_LIMIT = 100;

	private final LegacyExceptionTypeRepository legacyExceptionTypeRepository;
	private final EntityManager entityManager;

	public LegacyExceptionTypeService(
			LegacyExceptionTypeRepository legacyExceptionTypeRepository, EntityManager entityManager) {
		this.legacyExceptionTypeRepository = legacyExceptionTypeRepository;
		this.entityManager = entityManager;
	}

	/**
	 * {@code list.php}. No permission gate -- legacy's own list endpoint
	 * has none. {@code isActiveFilter}: {@code null} means "not
	 * specified" ({@code isset($_GET[Request::IS_ACTIVE])} was false);
	 * when null and {@code role} is {@code EMPLOYEE}, legacy forces
	 * {@code is_active = 1} -- management roles see both by default.
	 */
	@Transactional(readOnly = true)
	public LegacyExceptionTypePage list(
			long companyId, LegacyEmployee.Role role, Integer isActiveFilter, String search, int page, int limit) {
		int safePage = Math.max(1, page);
		int safeLimit = Math.min(Math.max(1, limit <= 0 ? DEFAULT_LIMIT : limit), MAX_LIMIT);
		Integer effectiveIsActive = isActiveFilter;
		if (effectiveIsActive == null && role == LegacyEmployee.Role.EMPLOYEE) {
			effectiveIsActive = 1;
		}
		String trimmedSearch = (search == null || search.trim().isEmpty()) ? null : search.trim();

		Pageable pageable = PageRequest.of(
				safePage - 1, safeLimit, Sort.by(Sort.Order.asc("name"), Sort.Order.asc("id")));

		Page<LegacyExceptionType> result;
		if (effectiveIsActive != null && trimmedSearch != null) {
			result = legacyExceptionTypeRepository
					.findByCompanyIdAndIsActiveAndNameContaining(companyId, effectiveIsActive, trimmedSearch, pageable);
		} else if (effectiveIsActive != null) {
			result = legacyExceptionTypeRepository.findByCompanyIdAndIsActive(companyId, effectiveIsActive, pageable);
		} else if (trimmedSearch != null) {
			result = legacyExceptionTypeRepository.findByCompanyIdAndNameContaining(companyId, trimmedSearch, pageable);
		} else {
			result = legacyExceptionTypeRepository.findByCompanyId(companyId, pageable);
		}

		return new LegacyExceptionTypePage(
				result.map(LegacyExceptionTypeView::of).getContent(),
				safePage, safeLimit, result.getTotalElements(), result.getTotalPages(),
				safePage < result.getTotalPages(), safePage > 1);
	}

	/** {@code one.php}. No permission gate. */
	@Transactional(readOnly = true)
	public LegacyExceptionTypeView one(long companyId, long id) {
		return LegacyExceptionTypeView.of(findOwnedOrNotFound(companyId, id));
	}

	/** {@code create.php}. Caller has already run the role/company-active/permission gates. */
	@Transactional
	public LegacyExceptionTypeView create(long companyId, String name, Boolean isActive) {
		String trimmedName = requireNonBlankName(name);
		if (legacyExceptionTypeRepository.existsByCompanyIdAndName(companyId, trimmedName)) {
			throw new ApiException(HttpStatus.CONFLICT, "already_exists");
		}
		LegacyExceptionType created = saveOrConflict(new LegacyExceptionType(companyId, trimmedName, isActive == null || isActive));
		return LegacyExceptionTypeView.of(created);
	}

	/**
	 * {@code update.php}. {@code body} is the raw request map so
	 * presence (not value) decides which fields are touched, exactly
	 * {@code whitelist_update_fields}'s {@code array_key_exists} shape --
	 * a typed DTO cannot distinguish "field omitted" from "field sent as
	 * null" without the same trick.
	 */
	@Transactional
	public LegacyExceptionTypeView update(long companyId, long id, Map<String, Object> body) {
		LegacyExceptionType exceptionType = findOwnedOrNotFound(companyId, id);

		boolean touchesName = body.containsKey("name");
		boolean touchesIsActive = body.containsKey("isActive");
		if (!touchesName && !touchesIsActive) {
			throw new ApiException(HttpStatus.BAD_REQUEST, "nothing_to_update");
		}

		if (touchesName) {
			String trimmedName = requireNonBlankName((String) body.get("name"));
			if (legacyExceptionTypeRepository.existsByCompanyIdAndNameAndIdNot(companyId, trimmedName, id)) {
				throw new ApiException(HttpStatus.CONFLICT, "already_exists");
			}
			exceptionType.setName(trimmedName);
		}
		if (touchesIsActive) {
			Object raw = body.get("isActive");
			exceptionType.setActive(Boolean.TRUE.equals(raw) || "1".equals(String.valueOf(raw)) || Integer.valueOf(1).equals(raw));
		}
		return LegacyExceptionTypeView.of(saveOrConflict(exceptionType));
	}

	/**
	 * The layer that actually enforces global uniqueness (D-7/D-051):
	 * {@code unique_exception_type_name} rejects a cross-company write
	 * this service's own company-scoped pre-check has no visibility
	 * into. {@code flush()} forces the write now, inside this method's
	 * try block, rather than letting it defer to end-of-transaction
	 * commit -- where this catch could not reach it.
	 */
	private LegacyExceptionType saveOrConflict(LegacyExceptionType exceptionType) {
		try {
			LegacyExceptionType saved = legacyExceptionTypeRepository.save(exceptionType);
			entityManager.flush();
			return saved;
		} catch (DataIntegrityViolationException ex) {
			throw new ApiException(HttpStatus.CONFLICT, "already_exists", ex);
		}
	}

	/**
	 * {@code delete.php} (D-046/D-048): clears {@code attendance}/{@code
	 * request_types} FKs with native, company-scoped statements, then
	 * hard-deletes -- one transaction, so a failure at any step rolls
	 * every prior write back. No JPA entity for either dependent table
	 * (D-048's Impact: persistence wiring, not shared business logic).
	 */
	@Transactional
	public void delete(long companyId, long id) {
		findOwnedOrNotFound(companyId, id);
		clearAttendanceReferences(id, companyId);
		clearRequestTypeReferences(id, companyId);
		legacyExceptionTypeRepository.deleteByIdAndCompanyId(id, companyId);
	}

	private LegacyExceptionType findOwnedOrNotFound(long companyId, long id) {
		Optional<LegacyExceptionType> row = legacyExceptionTypeRepository.findByIdAndCompanyId(id, companyId);
		return row.orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "not_found"));
	}

	private static String requireNonBlankName(String rawName) {
		String trimmed = rawName == null ? "" : rawName.trim();
		if (trimmed.isEmpty()) {
			throw new ApiException(HttpStatus.BAD_REQUEST, "field_required");
		}
		return trimmed;
	}

	/**
	 * {@code UPDATE attendance a INNER JOIN employees e ON e.id = a.employee_id
	 * SET a.exception_type_id = NULL WHERE a.exception_type_id = ? AND e.company_id = ?}
	 * -- {@code attendance} has no {@code company_id} of its own
	 * (derived tenancy, F-1/P-1b, not yet built for a full adapter), so
	 * this reaches it the same one-hop way legacy's own delete does. A
	 * native query through the same {@link EntityManager}/persistence
	 * context the surrounding {@code @Transactional} method and {@link
	 * #legacyExceptionTypeRepository}'s own writes share -- one
	 * connection, one transaction, so this participates in the same
	 * atomicity/rollback as the delete that follows it, with no separate
	 * connection-handling of its own (D-048's atomicity requirement).
	 */
	private void clearAttendanceReferences(long exceptionTypeId, long companyId) {
		entityManager.createNativeQuery(
				"UPDATE attendance a INNER JOIN employees e ON e.id = a.employee_id "
						+ "SET a.exception_type_id = NULL WHERE a.exception_type_id = :exceptionTypeId "
						+ "AND e.company_id = :companyId")
				.setParameter("exceptionTypeId", exceptionTypeId)
				.setParameter("companyId", companyId)
				.executeUpdate();
	}

	private void clearRequestTypeReferences(long exceptionTypeId, long companyId) {
		entityManager.createNativeQuery(
				"UPDATE request_types SET exception_type_id = NULL "
						+ "WHERE exception_type_id = :exceptionTypeId AND company_id = :companyId")
				.setParameter("exceptionTypeId", exceptionTypeId)
				.setParameter("companyId", companyId)
				.executeUpdate();
	}

}
