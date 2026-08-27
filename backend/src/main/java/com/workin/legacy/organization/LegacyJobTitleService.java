package com.workin.legacy.organization;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceException;

import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.workin.legacy.LegacyValues;
import com.workin.legacy.wire.LegacyApiException;

/** Wave 12.3c's five job-title endpoints, ported from {@code hr-legacy/apis/api/job_titles/*.php}. */
@Service
public class LegacyJobTitleService {

	private final LegacyJobTitleRepository jobTitleRepository;
	private final LegacyDepartmentRepository departmentRepository;
	private final LegacyDepartmentBranchRepository departmentBranchRepository;
	private final LegacyOrganizationBranchRepository branchRepository;
	private final EntityManager entityManager;

	public LegacyJobTitleService(
			LegacyJobTitleRepository jobTitleRepository,
			LegacyDepartmentRepository departmentRepository,
			LegacyDepartmentBranchRepository departmentBranchRepository,
			LegacyOrganizationBranchRepository branchRepository,
			EntityManager entityManager) {
		this.jobTitleRepository = jobTitleRepository;
		this.departmentRepository = departmentRepository;
		this.departmentBranchRepository = departmentBranchRepository;
		this.branchRepository = branchRepository;
		this.entityManager = entityManager;
	}

	/** {@code list.php}: active titles only, optional branch and department filters, no pagination. */
	@Transactional(readOnly = true)
	public List<LegacyJobTitleView> list(long companyId, Long branchId, Long departmentId) {
		List<LegacyJobTitle> rows = jobTitleRepository
				.findByCompanyIdAndIsActiveOrderByCreatedAtDescIdDesc(companyId, 1);
		Map<Long, List<Long>> links = linksByDepartment(rows.stream()
				.map(LegacyJobTitle::getDepartmentId).filter(java.util.Objects::nonNull).toList());
		List<LegacyJobTitle> filtered = rows.stream()
				.filter(row -> branchId == null || branchId <= 0
						|| (row.getDepartmentId() != null
								&& links.getOrDefault(row.getDepartmentId(), List.of()).contains(branchId)))
				.filter(row -> departmentId == null || departmentId <= 0
						|| departmentId.equals(row.getDepartmentId()))
				.toList();
		return views(companyId, filtered, links);
	}

	/** {@code one.php}: inactive rows are deliberately invisible. */
	@Transactional(readOnly = true)
	public LegacyJobTitleView one(long companyId, long id) {
		LegacyJobTitle row = jobTitleRepository.findByIdAndCompanyId(id, companyId)
				.filter(LegacyJobTitle::active)
				.orElseThrow(() -> new LegacyApiException(404, "job_title_not_found"));
		Map<Long, List<Long>> links = linksByDepartment(
				row.getDepartmentId() == null ? List.of() : List.of(row.getDepartmentId()));
		return views(companyId, List.of(row), links).getFirst();
	}

	/**
	 * Create requires department_id despite the nullable legacy column, and requires positive
	 * work hours.
	 *
	 * <p>{@code required($body, [NAME, DEPARTMENT_ID, WORK_HOURS])}: checked in that order,
	 * failing on the first missing/empty-string field with {@code field_required} naming it
	 * (PHP's {@code required()} helper itself supplies the {@code {field}} replace value). The
	 * later blank-name check deliberately carries <b>no</b> replace map -- verified against the
	 * frozen source, not an oversight here: {@code if ($name === '') fail(LangKey::FIELD_REQUIRED, 400);}
	 * passes no fourth argument, so a client sees the literal, unsubstituted {@code {field}}
	 * placeholder for that specific path. The work-hours-not-positive check, by contrast, does
	 * carry {@code field=work_hours} in PHP.
	 */
	@Transactional
	public LegacyJobTitleView create(long companyId, Map<String, Object> body) {
		Object rawName = value(body, "name", "name");
		Object rawDepartmentId = value(body, "departmentId", "department_id");
		Object rawWorkHours = value(body, "workHours", "work_hours");
		if (rawName == null || "".equals(rawName)) {
			throw new LegacyApiException(400, "field_required", null, Map.of("field", "name"));
		}
		if (rawDepartmentId == null || "".equals(rawDepartmentId)) {
			throw new LegacyApiException(400, "field_required", null, Map.of("field", "department_id"));
		}
		if (rawWorkHours == null || "".equals(rawWorkHours)) {
			throw new LegacyApiException(400, "field_required", null, Map.of("field", "work_hours"));
		}

		String name = LegacyValues.toPhpString(rawName).trim();
		if (name.isEmpty()) {
			throw new LegacyApiException(400, "field_required");
		}
		long departmentId = LegacyValues.toPhpLong(rawDepartmentId);
		if (departmentId <= 0 || !activeDepartmentBelongsTo(companyId, departmentId)) {
			throw new LegacyApiException(404, "department_not_found");
		}
		BigDecimal workHours = LegacyValues.toPhpDecimal(rawWorkHours);
		if (workHours.compareTo(BigDecimal.ZERO) <= 0) {
			throw new LegacyApiException(400, "field_required", null, Map.of("field", "work_hours"));
		}

		try {
			LegacyJobTitle row = jobTitleRepository.save(
					new LegacyJobTitle(companyId, departmentId, name, workHours));
			entityManager.flush();
			entityManager.refresh(row);
			return view(companyId, row);
		} catch (DataAccessException | PersistenceException ex) {
			LegacyApiException failure = new LegacyApiException(500, "job_title_not_found");
			failure.initCause(ex);
			throw failure;
		}
	}

	/**
	 * {@code update.php}: an empty/unknown-only body is 400; name may become blank; work hours must
	 * stay positive; department_id must resolve to an active owned department and can never be
	 * cleared back to NULL.
	 */
	@Transactional
	public LegacyJobTitleView update(long companyId, long id, Map<String, Object> body) {
		LegacyJobTitle row = findOwned(companyId, id);
		if (body.isEmpty()) {
			throw new LegacyApiException(400, "nothing_to_update");
		}

		boolean touched = false;
		if (contains(body, "name", "name")) {
			Object raw = value(body, "name", "name");
			row.setName(LegacyValues.toPhpString(raw).trim());
			touched = true;
		}
		if (contains(body, "workHours", "work_hours")) {
			Object raw = value(body, "workHours", "work_hours");
			if (raw == null || "".equals(raw)) {
				throw new LegacyApiException(400, "field_required", null, Map.of("field", "work_hours"));
			}
			BigDecimal workHours = LegacyValues.toPhpDecimal(raw);
			if (workHours.compareTo(BigDecimal.ZERO) <= 0) {
				throw new LegacyApiException(400, "field_required", null, Map.of("field", "work_hours"));
			}
			row.setWorkHours(workHours);
			touched = true;
		}
		if (contains(body, "departmentId", "department_id")) {
			Object raw = value(body, "departmentId", "department_id");
			long departmentId = raw == null || "".equals(raw) ? 0L : LegacyValues.toPhpLong(raw);
			if (departmentId <= 0 || !activeDepartmentBelongsTo(companyId, departmentId)) {
				throw new LegacyApiException(404, "department_not_found");
			}
			row.setDepartmentId(departmentId);
			touched = true;
		}
		if (!touched) {
			throw new LegacyApiException(400, "nothing_to_update");
		}

		jobTitleRepository.save(row);
		entityManager.flush();
		// Legacy re-selects after UPDATE. Refresh so MariaDB-normalized values (notably
		// DECIMAL(5,2) rounding) are also what this response exposes.
		entityManager.refresh(row);
		return view(companyId, row);
	}

	/** {@code delete.php}: repeatable soft-delete, no dependent-row guard. */
	@Transactional
	public void delete(long companyId, long id) {
		LegacyJobTitle row = findOwned(companyId, id);
		row.setActive(false);
		jobTitleRepository.save(row);
		entityManager.flush();
	}

	private LegacyJobTitleView view(long companyId, LegacyJobTitle row) {
		Map<Long, List<Long>> links = linksByDepartment(
				row.getDepartmentId() == null ? List.of() : List.of(row.getDepartmentId()));
		return views(companyId, List.of(row), links).getFirst();
	}

	private List<LegacyJobTitleView> views(
			long companyId, List<LegacyJobTitle> rows, Map<Long, List<Long>> links) {
		Set<Long> departmentIds = rows.stream().map(LegacyJobTitle::getDepartmentId)
				.filter(java.util.Objects::nonNull).collect(Collectors.toSet());
		Map<Long, LegacyDepartment> departments = departmentIds.isEmpty() ? Map.of()
				: departmentRepository.findByCompanyIdAndIdIn(companyId, departmentIds).stream()
						.collect(Collectors.toMap(LegacyDepartment::getId, Function.identity()));

		Set<Long> branchIds = links.values().stream().flatMap(Collection::stream)
				.collect(Collectors.toCollection(LinkedHashSet::new));
		List<LegacyBranch> branches = branchIds.isEmpty() ? List.of()
				: branchRepository.findByCompanyIdAndIdInOrderByNameAscIdAsc(companyId, branchIds);

		return rows.stream().map(row -> {
			LegacyDepartment department = departments.get(row.getDepartmentId());
			Set<Long> linked = Set.copyOf(links.getOrDefault(row.getDepartmentId(), List.of()));
			String summary = branches.stream().filter(branch -> linked.contains(branch.getId()))
					.map(LegacyBranch::getName).distinct().collect(Collectors.joining(", "));
			return new LegacyJobTitleView(
					row.getId(), row.getCompanyId(), row.getDepartmentId(), row.getName(), row.getWorkHours(),
					row.active(), row.getCreatedAt(), department == null ? null : department.getName(),
					summary.isEmpty() ? null : summary);
		}).toList();
	}

	private Map<Long, List<Long>> linksByDepartment(Collection<Long> departmentIds) {
		if (departmentIds.isEmpty()) {
			return Map.of();
		}
		return departmentBranchRepository.findByDepartmentIdIn(departmentIds).stream()
				.collect(Collectors.groupingBy(
						LegacyDepartmentBranch::getDepartmentId,
						Collectors.mapping(LegacyDepartmentBranch::getBranchId, Collectors.toList())));
	}

	private boolean activeDepartmentBelongsTo(long companyId, long departmentId) {
		return departmentRepository.findByIdAndCompanyId(departmentId, companyId)
				.map(LegacyDepartment::active).orElse(false);
	}

	private LegacyJobTitle findOwned(long companyId, long id) {
		return jobTitleRepository.findByIdAndCompanyId(id, companyId)
				.orElseThrow(() -> new LegacyApiException(404, "job_title_not_found"));
	}

	private static boolean contains(Map<String, Object> body, String camel, String snake) {
		return body.containsKey(camel) || body.containsKey(snake);
	}

	private static Object value(Map<String, Object> body, String camel, String snake) {
		return body.containsKey(snake) ? body.get(snake) : body.get(camel);
	}

}
