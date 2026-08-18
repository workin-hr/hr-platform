package com.workin.legacy.organization;

import java.math.BigDecimal;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
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
import com.workin.legacy.employees.LegacyEmployeeRepository;

/**
 * Wave 12.3a's six endpoints, ported behaviour-for-behaviour from {@code
 * hr-legacy/apis/api/branches/*.php}, with D-056's delete pre-check and
 * one explicitly approved security divergence -- see {@link #update} below.
 */
@Service
public class LegacyBranchService {

	private static final int DEFAULT_LIMIT = 20;
	private static final int MAX_LIMIT = 100;
	private static final SecureRandom RANDOM = new SecureRandom();

	private final LegacyBranchRepository legacyBranchRepository;
	private final LegacyEmployeeRepository legacyEmployeeRepository;
	private final EntityManager entityManager;

	public LegacyBranchService(
			LegacyBranchRepository legacyBranchRepository, LegacyEmployeeRepository legacyEmployeeRepository,
			EntityManager entityManager) {
		this.legacyBranchRepository = legacyBranchRepository;
		this.legacyEmployeeRepository = legacyEmployeeRepository;
		this.entityManager = entityManager;
	}

	/** {@code list.php}. Always {@code is_active = 1} -- legacy's own list has no toggle for inactive rows. */
	@Transactional(readOnly = true)
	public LegacyBranchPage list(long companyId, String search, int page, int limit) {
		int safePage = Math.max(1, page);
		int safeLimit = Math.min(Math.max(1, limit <= 0 ? DEFAULT_LIMIT : limit), MAX_LIMIT);
		String trimmedSearch = (search == null || search.trim().isEmpty()) ? null : search.trim();

		Pageable pageable = PageRequest.of(
				safePage - 1, safeLimit, Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id")));

		Page<LegacyBranch> result = trimmedSearch != null
				? legacyBranchRepository.searchByCompanyIdAndIsActive(
						companyId, 1, "%" + trimmedSearch + "%", "%" + trimmedSearch + "%", pageable)
				: legacyBranchRepository.findByCompanyIdAndIsActive(companyId, 1, pageable);

		return new LegacyBranchPage(
				result.getContent().stream().map(this::toListItem).toList(),
				safePage, safeLimit, result.getTotalElements(), result.getTotalPages(),
				safePage < result.getTotalPages(), safePage > 1);
	}

	private LegacyBranchListItem toListItem(LegacyBranch branch) {
		long count = legacyEmployeeRepository.countActiveRosterByBranchIdAndCompanyId(branch.getId(), branch.getCompanyId());
		return LegacyBranchListItem.of(branch, count);
	}

	/**
	 * {@code one.php}. Also filters {@code is_active = 1} -- an inactive
	 * branch is invisible here even to a direct id lookup, matching the
	 * PHP query exactly. Not-found uses legacy's own (unusual) pairing:
	 * message key {@code forbidden}, HTTP 404.
	 */
	@Transactional(readOnly = true)
	public LegacyBranchView one(long companyId, long id) {
		LegacyBranch branch = legacyBranchRepository.findByIdAndCompanyId(id, companyId)
				.filter(LegacyBranch::active)
				.orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "forbidden"));
		return LegacyBranchView.of(branch);
	}

	/** {@code create.php}. Caller has already run the role/company-active gates (D-057: no permission gate exists). */
	@Transactional
	public LegacyBranchView create(long companyId, Map<String, Object> body) {
		String name = (String) body.get("name");
		if (name == null || name.isEmpty()) {
			throw new ApiException(HttpStatus.BAD_REQUEST, "field_required");
		}
		String address = (String) body.get("address");
		Integer radiusMeters = toInteger(body.get("radiusMeters"));

		LegacyBranchLocationResolver.Coordinates coords = resolveLocation(body);
		LegacyBranch branch = new LegacyBranch(
				companyId, name, address,
				coords == null ? null : coords.latitude(), coords == null ? null : coords.longitude(),
				radiusMeters);
		branch = legacyBranchRepository.save(branch);
		entityManager.flush();

		applyQrIfExpiresGiven(branch, body);
		return LegacyBranchView.of(branch);
	}

	/**
	 * {@code update.php}, with the D-060 approved security divergence.
	 *
	 * <h2>Not reproduced: a confirmed cross-tenant read in legacy's own {@code update.php}</h2>
	 * <p>Legacy's update has <b>no existence/ownership check at all</b>
	 * before writing: it runs {@code UPDATE branches SET ... WHERE id = ?
	 * AND company_id = ?} (silently affecting zero rows for a wrong-company
	 * or nonexistent id -- no error), then re-fetches with {@code SELECT *
	 * FROM branches WHERE id = ?} -- <b>no {@code company_id} predicate at
	 * all</b> -- and returns whatever that finds as a 200 {@code
	 * branch_updated}. Given a valid id belonging to a <em>different</em>
	 * company, this returns that other company's branch data, mislabelled
	 * as a successful update of the caller's own resource. Verified
	 * directly against the source twice this conversation; not a
	 * misreading.
	 *
	 * <p>D-060 explicitly approves this security divergence. A company-scoped
	 * lookup returns the same 404 branch_not_found for both a nonexistent id
	 * and an id owned by another tenant. It deliberately reproduces neither
	 * legacy path: no cross-tenant 200 response and no public_row(null) 500.
	 * The uniform response prevents disclosure and id enumeration.
	 *
	 * <h2>{@code isset()} semantics, not {@code array_key_exists()}</h2>
	 * <p>Unlike Wave 12.1's {@code exception_types} update (which used
	 * PHP's {@code array_key_exists}, so an explicit {@code null}
	 * clears/errors), legacy's {@code branches/update.php} gates every
	 * field with {@code isset($request_body[$column])} -- which is
	 * {@code false} for an explicit {@code null}. Sending a field as
	 * {@code null} is therefore indistinguishable from omitting it
	 * entirely: the existing value is left untouched, silently, for
	 * every one of {@code name}/{@code address}/{@code latitude}/{@code
	 * longitude}/{@code radiusMeters}/{@code isActive}. Also unlike Wave
	 * 12.1: legacy's branch update does <b>not</b> throw when nothing is
	 * touched -- an empty update is a silent no-op, 200, unchanged row.
	 */
	@Transactional
	public LegacyBranchView update(long companyId, long id, Map<String, Object> body) {
		LegacyBranch branch = legacyBranchRepository.findByIdAndCompanyId(id, companyId)
				.orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "branch_not_found"));

		if (isSet(body, "name")) {
			branch.setName((String) body.get("name"));
		}
		if (isSet(body, "address")) {
			branch.setAddress((String) body.get("address"));
		}
		if (isSet(body, "radiusMeters")) {
			branch.setRadiusMeters(toInteger(body.get("radiusMeters")));
		}
		if (isSet(body, "isActive")) {
			branch.setActive(toBoolean(body.get("isActive")));
		}

		boolean hasLocationInput = body.containsKey("latitude") || body.containsKey("longitude")
				|| (body.get("locationLink") != null && !String.valueOf(body.get("locationLink")).trim().isEmpty());
		if (hasLocationInput) {
			LegacyBranchLocationResolver.Coordinates coords = resolveLocation(body);
			// A resolved-but-null pair (isset()-false on both sides in PHP) leaves lat/lng untouched, same as an omitted field.
			if (coords != null) {
				branch.setLatitude(coords.latitude());
				branch.setLongitude(coords.longitude());
			}
		}

		// No try/catch here, deliberately: branches carries no unique constraint beyond its
		// primary key (verified against mysql_workin.schema.sql), so unlike exception_types'
		// update (D-047/D-051), there is no real constraint-violation case to translate --
		// inventing one would be exactly the "invented behaviour" D-058 forbids.
		legacyBranchRepository.save(branch);
		entityManager.flush();
		return LegacyBranchView.of(branch);
	}

	/**
	 * {@code delete.php} (D-056): explicit pre-check
	 * ({@code branch_assigned_employees_count}-equivalent, any employee
	 * row, active or not) returns 409 <em>before</em> attempting any
	 * write -- this is the primary mechanism, not a side effect of a
	 * caught constraint. Cleanup + hard delete run atomically in this
	 * one {@code @Transactional} method; the {@code
	 * DataIntegrityViolationException} catch around the write is a
	 * documented race-condition fallback only (an employee assigned to
	 * this branch between the pre-check and the delete), not the
	 * primary check.
	 */
	@Transactional
	public void delete(long companyId, long id) {
		legacyBranchRepository.findByIdAndCompanyId(id, companyId)
				.orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "branch_not_found"));

		long assignedEmployees = legacyEmployeeRepository.countByBranchIdAndCompanyId(id, companyId);
		if (assignedEmployees > 0) {
			throw new ApiException(HttpStatus.CONFLICT, "branch_has_employees_cannot_delete");
		}

		try {
			entityManager.createNativeQuery("DELETE FROM department_branches WHERE branch_id = :branchId")
					.setParameter("branchId", id)
					.executeUpdate();
			legacyBranchRepository.deleteByIdAndCompanyId(id, companyId);
			entityManager.flush();
		} catch (DataIntegrityViolationException ex) {
			// Race-condition fallback only (D-056): an employee assigned between the pre-check above and this write.
			throw new ApiException(HttpStatus.CONFLICT, "branch_has_employees_cannot_delete", ex);
		}
	}

	/**
	 * {@code generate_qr.php}. Ownership check filters {@code is_active
	 * = 1} (a QR cannot be (re)generated for an inactive branch) and,
	 * unusually, its failure is message key {@code forbidden} at HTTP
	 * <b>400</b> -- legacy's {@code fail(LangKey::FORBIDDEN)} call omits
	 * the status argument, which defaults to 400, not 403/404. Verified
	 * directly against {@code functions.php}'s {@code fail()} signature
	 * (`$status_code = 400`); preserved exactly, not "corrected" to 403.
	 */
	@Transactional
	public LegacyBranchView generateQr(long companyId, long id, Map<String, Object> body) {
		Object expiresAtRaw = body.get("expiresAt");
		if (expiresAtRaw == null || String.valueOf(expiresAtRaw).isEmpty()) {
			throw new ApiException(HttpStatus.BAD_REQUEST, "field_required");
		}
		Instant expiresAt = parseExpiresAt(String.valueOf(expiresAtRaw));

		LegacyBranch branch = legacyBranchRepository.findByIdAndCompanyId(id, companyId)
				.filter(LegacyBranch::active)
				.orElseThrow(() -> new ApiException(HttpStatus.BAD_REQUEST, "forbidden"));

		branch.setQrCode(randomQrCode());
		branch.setExpiresAt(expiresAt);
		legacyBranchRepository.save(branch);
		entityManager.flush();
		return LegacyBranchView.of(branch);
	}

	private void applyQrIfExpiresGiven(LegacyBranch branch, Map<String, Object> body) {
		Object expiresAtRaw = body.get("expiresAt");
		if (expiresAtRaw == null || String.valueOf(expiresAtRaw).isEmpty()) {
			return;
		}
		branch.setQrCode(randomQrCode());
		branch.setExpiresAt(parseExpiresAt(String.valueOf(expiresAtRaw)));
		legacyBranchRepository.save(branch);
		entityManager.flush();
	}

	private static String randomQrCode() {
		byte[] raw = new byte[16];
		RANDOM.nextBytes(raw);
		StringBuilder hex = new StringBuilder(32);
		for (byte b : raw) {
			hex.append(String.format("%02x", b));
		}
		return hex.toString();
	}

	/**
	 * {@code strtotime()} equivalent: PHP's date parser accepts a very
	 * wide format range. This ports the one format the mobile/web
	 * clients are documented to send (ISO-8601 / {@code
	 * yyyy-MM-dd['T'HH:mm:ss]}), not the full breadth of {@code
	 * strtotime}'s grammar -- flagged as a potential gap, not silently
	 * assumed complete, since a client sending some other PHP-parseable
	 * format {@code strtotime} accepts (e.g. {@code "next Tuesday"})
	 * would behave differently here.
	 */
	private static Instant parseExpiresAt(String raw) {
		try {
			return Instant.parse(raw);
		} catch (DateTimeParseException ignored) {
			// fall through to the date-only / local-datetime attempt below
		}
		try {
			return java.time.LocalDateTime.parse(raw, DateTimeFormatter.ISO_LOCAL_DATE_TIME).toInstant(ZoneOffset.UTC);
		} catch (DateTimeParseException ignored) {
			// fall through to date-only
		}
		try {
			return java.time.LocalDate.parse(raw).atStartOfDay(ZoneOffset.UTC).toInstant();
		} catch (DateTimeParseException ex) {
			throw new ApiException(HttpStatus.BAD_REQUEST, "invalid_date", ex);
		}
	}

	private LegacyBranchLocationResolver.Coordinates resolveLocation(Map<String, Object> body) {
		Object latRaw = body.get("latitude");
		Object lngRaw = body.get("longitude");
		boolean latPresent = latRaw != null && !String.valueOf(latRaw).isEmpty();
		boolean lngPresent = lngRaw != null && !String.valueOf(lngRaw).isEmpty();

		if (latPresent && lngPresent) {
			double lat = Double.parseDouble(String.valueOf(latRaw));
			double lng = Double.parseDouble(String.valueOf(lngRaw));
			LegacyBranchLocationResolver.Coordinates pair = LegacyBranchLocationResolver.validPair(lat, lng);
			if (pair == null) {
				throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "invalid_branch_location");
			}
			return pair;
		}

		String link = body.get("locationLink") == null ? "" : String.valueOf(body.get("locationLink")).trim();
		if (link.isEmpty()) {
			return null;
		}
		LegacyBranchLocationResolver.Coordinates parsed = LegacyBranchLocationResolver.parseLink(link);
		if (parsed == null) {
			throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "invalid_branch_location");
		}
		return parsed;
	}

	private static boolean isSet(Map<String, Object> body, String key) {
		return body.containsKey(key) && body.get(key) != null;
	}

	private static Integer toInteger(Object raw) {
		if (raw == null) {
			return null;
		}
		if (raw instanceof Number number) {
			return number.intValue();
		}
		return Integer.parseInt(String.valueOf(raw));
	}

	private static boolean toBoolean(Object raw) {
		if (raw instanceof Boolean bool) {
			return bool;
		}
		return "1".equals(String.valueOf(raw)) || "true".equalsIgnoreCase(String.valueOf(raw));
	}

}
