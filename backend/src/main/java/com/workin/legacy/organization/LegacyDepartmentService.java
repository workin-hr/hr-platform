package com.workin.legacy.organization;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceException;

import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.workin.backend.i18n.ApiException;
import com.workin.legacy.LegacyValues;
import com.workin.legacy.employees.LegacyEmployee;
import com.workin.legacy.employees.LegacyEmployeeRepository;

/** Wave 12.3b's five legacy department endpoints, including their owned junction-row lifecycle. */
@Service
public class LegacyDepartmentService {

	private final LegacyDepartmentRepository departmentRepository;
	private final LegacyDepartmentBranchRepository departmentBranchRepository;
	private final LegacyBranchRepository branchRepository;
	private final LegacyOrganizationBranchRepository organizationBranchRepository;
	private final LegacyEmployeeRepository employeeRepository;
	private final LegacyOrganizationEmployeeRepository organizationEmployeeRepository;
	private final EntityManager entityManager;

	public LegacyDepartmentService(
			LegacyDepartmentRepository departmentRepository,
			LegacyDepartmentBranchRepository departmentBranchRepository,
			LegacyBranchRepository branchRepository,
			LegacyOrganizationBranchRepository organizationBranchRepository,
			LegacyEmployeeRepository employeeRepository,
			LegacyOrganizationEmployeeRepository organizationEmployeeRepository,
			EntityManager entityManager) {
		this.departmentRepository = departmentRepository;
		this.departmentBranchRepository = departmentBranchRepository;
		this.branchRepository = branchRepository;
		this.organizationBranchRepository = organizationBranchRepository;
		this.employeeRepository = employeeRepository;
		this.organizationEmployeeRepository = organizationEmployeeRepository;
		this.entityManager = entityManager;
	}

	/**
	 * {@code list.php}. Its inner join plus {@code b.is_active = 1} means a branchless department,
	 * or one linked only to inactive branches, is absent. The optional branch filter checks the
	 * complete link set first; the response aggregation still contains active branches only.
	 */
	@Transactional(readOnly = true)
	public List<LegacyDepartmentView> list(long companyId, Long branchId, Collection<?> branchIds) {
		List<LegacyDepartment> departments = departmentRepository
				.findByCompanyIdAndIsActiveOrderByCreatedAtDescIdDesc(companyId, 1);
		Map<Long, List<Long>> links = linksByDepartment(departments.stream().map(LegacyDepartment::getId).toList());
		Set<Long> requested = normalizePositiveIds(branchIds);

		List<LegacyDepartment> filtered = departments.stream()
				.filter(department -> matchesBranchFilter(
						links.getOrDefault(department.getId(), List.of()), branchId, requested))
				.toList();
		ViewContext views = viewContext(companyId, filtered, links, true);
		return filtered.stream()
				.filter(department -> !views.branchesByDepartment()
						.getOrDefault(department.getId(), List.of()).isEmpty())
				.map(department -> toView(department, true, views))
				.toList();
	}

	/** {@code one.php}: active department, but unlike list it aggregates active and inactive branches. */
	@Transactional(readOnly = true)
	public LegacyDepartmentView one(long companyId, long id) {
		LegacyDepartment department = departmentRepository.findByIdAndCompanyId(id, companyId)
				.filter(LegacyDepartment::active)
				.orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "forbidden"));
		Map<Long, List<Long>> links = linksByDepartment(List.of(id));
		ViewContext views = viewContext(companyId, List.of(department), links, false);
		if (views.branchesByDepartment().getOrDefault(id, List.of()).isEmpty()) {
			throw new ApiException(HttpStatus.NOT_FOUND, "forbidden");
		}
		return toView(department, true, views);
	}

	/** {@code create.php}: branch and manager validation precede one atomic row-plus-link insert. */
	@Transactional
	public LegacyDepartmentView create(long companyId, Map<String, Object> body) {
		Object rawName = value(body, "name", "name");
		if (rawName == null || String.valueOf(rawName).isEmpty()) {
			throw new ApiException(HttpStatus.BAD_REQUEST, "field_required");
		}
		Set<Long> branchIds = normalizePositiveIds(
				LegacyValues.phpArrayValues(value(body, "branchIds", "branch_ids")));
		if (branchIds.isEmpty()) {
			throw new ApiException(HttpStatus.BAD_REQUEST, "invalid_branch_ids");
		}
		validateActiveBranchesForCreate(companyId, branchIds);

		Object rawManagerId = value(body, "managerId", "manager_id");
		Long managerId = LegacyValues.isPhpEmpty(rawManagerId) ? null : LegacyValues.toPhpLong(rawManagerId);
		// create.php casts first and validates only when the cast result remains truthy.
		if (managerId != null && managerId != 0L) {
			validateManager(companyId, managerId);
		}

		try {
			LegacyDepartment department = departmentRepository.save(
					new LegacyDepartment(companyId, String.valueOf(rawName), managerId));
			entityManager.flush();
			entityManager.refresh(department);
			departmentBranchRepository.saveAll(branchIds.stream()
					.map(branchId -> new LegacyDepartmentBranch(department.getId(), branchId)).toList());
			entityManager.flush();
			return mutationView(companyId, department).orElseThrow();
		} catch (DataAccessException | PersistenceException ex) {
			throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "field_required", ex);
		}
	}

	/**
	 * {@code update.php}. D-055 is deliberate: explicit {@code manager_id: null} does not clear the
	 * manager because legacy binds NULL into {@code COALESCE(?, manager_id)}. A non-null branch set
	 * replaces every junction row; any invalid set returns 400 and rolls the whole transaction back.
	 */
	@Transactional
	public Optional<LegacyDepartmentView> update(long companyId, long id, Map<String, Object> body) {
		LegacyDepartment department = findOwned(companyId, id);

		boolean managerPresent = contains(body, "managerId", "manager_id");
		Object rawManagerId = value(body, "managerId", "manager_id");
		if (managerPresent && !LegacyValues.isPhpEmpty(rawManagerId)) {
			validateManager(companyId, LegacyValues.toPhpLong(rawManagerId));
		}

		try {
			Object rawName = value(body, "name", "name");
			if (contains(body, "name", "name") && rawName != null) {
				department.setName(String.valueOf(rawName));
			}
			// COALESCE(NULL, manager_id): null (and omission) is a deliberate no-op, D-055.
			if (managerPresent && rawManagerId != null) {
				department.setManagerId(LegacyValues.toPhpLong(rawManagerId));
			}

			if (containsNonNull(body, "branchIds", "branch_ids")) {
				Set<Long> branchIds = normalizePositiveIds(
						LegacyValues.phpArrayValues(value(body, "branchIds", "branch_ids")));
				if (branchIds.isEmpty() || !allActiveBranchesBelongTo(companyId, branchIds)) {
					throw new ApiException(HttpStatus.BAD_REQUEST, "invalid_branch_ids");
				}
				replaceBranchSet(companyId, id, branchIds);
			}

			departmentRepository.save(department);
			entityManager.flush();
			// PHP re-selects after UPDATE. Refresh before rendering so MariaDB-normalized values
			// (notably non-strict VARCHAR truncation) are the values returned to the caller.
			entityManager.refresh(department);
		} catch (DataAccessException | PersistenceException ex) {
			// update.php catches every database failure in its transaction, rolls back,
			// and reports invalid_branch_ids even when the failed statement was the row update.
			throw new ApiException(HttpStatus.BAD_REQUEST, "invalid_branch_ids", ex);
		}
		return mutationView(companyId, department);
	}

	/** {@code delete.php}: soft-delete only, with no dependent-row pre-check. */
	@Transactional
	public void delete(long companyId, long id) {
		LegacyDepartment department = findOwned(companyId, id);
		department.setActive(false);
		departmentRepository.save(department);
		entityManager.flush();
	}

	private Optional<LegacyDepartmentView> mutationView(long companyId, LegacyDepartment department) {
		Map<Long, List<Long>> links = linksByDepartment(List.of(department.getId()));
		ViewContext views = viewContext(companyId, List.of(department), links, false);
		if (views.branchesByDepartment().getOrDefault(department.getId(), List.of()).isEmpty()) {
			return Optional.empty();
		}
		return Optional.of(toView(department, false, views));
	}

	private ViewContext viewContext(
			long companyId, List<LegacyDepartment> departments, Map<Long, List<Long>> links,
			boolean activeBranchesOnly) {
		Set<Long> allBranchIds = links.values().stream().flatMap(Collection::stream)
				.collect(Collectors.toCollection(LinkedHashSet::new));
		List<LegacyBranch> branches = allBranchIds.isEmpty() ? List.of()
				: activeBranchesOnly
						? organizationBranchRepository
								.findByCompanyIdAndIsActiveAndIdInOrderByNameAscIdAsc(companyId, 1, allBranchIds)
						: organizationBranchRepository.findByCompanyIdAndIdInOrderByNameAscIdAsc(companyId, allBranchIds);

		Map<Long, List<LegacyBranch>> branchesByDepartment = departments.stream().collect(Collectors.toMap(
				LegacyDepartment::getId,
				department -> {
					Set<Long> linked = Set.copyOf(links.getOrDefault(department.getId(), List.of()));
					return branches.stream().filter(branch -> linked.contains(branch.getId())).toList();
				}));

		Set<Long> managerIds = departments.stream().map(LegacyDepartment::getManagerId)
				.filter(java.util.Objects::nonNull).collect(Collectors.toSet());
		Map<Long, LegacyEmployee> managers = managerIds.isEmpty() ? Map.of()
				: organizationEmployeeRepository.findByCompanyIdAndIdIn(companyId, managerIds).stream()
						.collect(Collectors.toMap(LegacyEmployee::getId, Function.identity()));
		return new ViewContext(branchesByDepartment, managers);
	}

	private static LegacyDepartmentView toView(
			LegacyDepartment department, boolean includeCompanyId, ViewContext context) {
		List<LegacyBranch> branches = context.branchesByDepartment().getOrDefault(department.getId(), List.of());
		String branchIds = branches.stream().map(LegacyBranch::getId).distinct().sorted()
				.map(String::valueOf).collect(Collectors.joining(","));
		String branchNames = branches.stream().map(LegacyBranch::getName).distinct()
				.collect(Collectors.joining(", "));
		LegacyEmployee manager = department.getManagerId() == null
				? null : context.managers().get(department.getManagerId());
		String managerName = manager == null ? "" : displayName(manager);
		return new LegacyDepartmentView(
				department.getId(), includeCompanyId ? department.getCompanyId() : null,
				department.getManagerId(), department.getName(), department.active(), department.getCreatedAt(),
				branchIds.isEmpty() ? null : branchIds, branchNames.isEmpty() ? null : branchNames, managerName);
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

	private void replaceBranchSet(long companyId, long departmentId, Set<Long> branchIds) {
		// Native for PHP's delete-then-reinsert shape, but explicitly tenant-scoped because native SQL
		// does not receive Hibernate's P-1c filter automatically.
		entityManager.createNativeQuery(
				"DELETE db FROM department_branches db INNER JOIN departments d ON d.id = db.department_id "
						+ "WHERE db.department_id = :departmentId AND d.company_id = :companyId")
				.setParameter("departmentId", departmentId)
				.setParameter("companyId", companyId)
				.executeUpdate();
		departmentBranchRepository.saveAll(branchIds.stream()
				.map(branchId -> new LegacyDepartmentBranch(departmentId, branchId)).toList());
		entityManager.flush();
	}

	private void validateActiveBranchesForCreate(long companyId, Set<Long> branchIds) {
		for (Long branchId : branchIds) {
			LegacyBranch branch = branchRepository.findByIdAndCompanyId(branchId, companyId).orElse(null);
			if (branch == null || !branch.active()) {
				throw new ApiException(HttpStatus.NOT_FOUND, "branch_not_found");
			}
		}
	}

	private boolean allActiveBranchesBelongTo(long companyId, Set<Long> branchIds) {
		return branchIds.stream().allMatch(branchId -> branchRepository.findByIdAndCompanyId(branchId, companyId)
				.map(LegacyBranch::active).orElse(false));
	}

	private void validateManager(long companyId, Long managerId) {
		if (managerId != null && employeeRepository.findByIdAndCompanyId(managerId, companyId).isEmpty()) {
			throw new ApiException(HttpStatus.NOT_FOUND, "employee_not_found");
		}
	}

	private LegacyDepartment findOwned(long companyId, long id) {
		return departmentRepository.findByIdAndCompanyId(id, companyId)
				.orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "department_not_found"));
	}

	private static boolean matchesBranchFilter(List<Long> linked, Long branchId, Set<Long> branchIds) {
		if (branchId != null && branchId > 0) {
			return linked.contains(branchId);
		}
		return branchIds.isEmpty() || linked.stream().anyMatch(branchIds::contains);
	}

	private static Set<Long> normalizePositiveIds(Collection<?> raw) {
		Set<Long> ids = new LinkedHashSet<>();
		if (raw == null) {
			return ids;
		}
		for (Object value : raw) {
			long id = LegacyValues.toPhpLong(value);
			if (id > 0) {
				ids.add(id);
			}
		}
		return ids;
	}

	private static String displayName(LegacyEmployee employee) {
		String first = employee.getFirstName() == null ? "" : employee.getFirstName();
		String last = employee.getLastName() == null ? "" : employee.getLastName();
		return (first + " " + last).trim();
	}

	private static boolean contains(Map<String, Object> body, String camel, String snake) {
		return body.containsKey(camel) || body.containsKey(snake);
	}

	private static boolean containsNonNull(Map<String, Object> body, String camel, String snake) {
		return contains(body, camel, snake) && value(body, camel, snake) != null;
	}

	private static Object value(Map<String, Object> body, String camel, String snake) {
		return body.containsKey(snake) ? body.get(snake) : body.get(camel);
	}

	private record ViewContext(
			Map<Long, List<LegacyBranch>> branchesByDepartment,
			Map<Long, LegacyEmployee> managers) {
	}

}
