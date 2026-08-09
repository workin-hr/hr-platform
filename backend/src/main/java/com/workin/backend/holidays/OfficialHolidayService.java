package com.workin.backend.holidays;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.workin.backend.i18n.ApiException;
import com.workin.backend.i18n.MessageKeys;
import com.workin.backend.tenancy.AuthorizationContext;
import com.workin.backend.tenancy.TenantSessionVariable;

/**
 * Ported from hr-legacy/apis/api/company_official_holidays/* and
 * {@code official_holidays_helper.php} @ d113204.
 *
 * <p>Two legacy behaviours here look like bugs and are ported anyway,
 * because they are the observable contract:
 *
 * <ul>
 * <li><b>Create upserts; update rejects.</b> Creating a holiday on a
 * date that already has one silently <em>overwrites its name</em> and
 * answers 201. Moving a holiday onto an occupied date via update answers
 * 409. Same domain rule, two answers.</li>
 * <li><b>Malformed dates are dropped silently.</b> Legacy's normalizer
 * discards anything that is not strictly {@code YYYY-MM-DD} without
 * telling the caller which entry was rejected; a request containing only
 * bad dates comes back as "the dates field is required". Here the date
 * list is bound as real {@code LocalDate}s, so a malformed entry is a
 * 400 from the framework before this service is reached — better, and
 * not a behavioural difference anyone can depend on.</li>
 * </ul>
 *
 * <p>Legacy's create is also non-transactional and race-prone: it
 * pre-checks for an existing row and inserts, so a concurrent request
 * raises a raw duplicate-key 500. Here the whole batch is one
 * transaction, and a lost race surfaces as 409 — the CompanySettings
 * precedent.
 */
@Service
public class OfficialHolidayService {

	private final OfficialHolidayRepository holidayRepository;
	private final TenantSessionVariable tenantSessionVariable;

	public OfficialHolidayService(
			OfficialHolidayRepository holidayRepository, TenantSessionVariable tenantSessionVariable) {
		this.holidayRepository = holidayRepository;
		this.tenantSessionVariable = tenantSessionVariable;
	}

	@Transactional(readOnly = true)
	public List<OfficialHolidayView> list(AuthorizationContext context, LocalDate from, LocalDate to) {
		tenantSessionVariable.apply(context.companyId());
		return holidayRepository.findByCompanyIdOrderByHolidayDateAscIdAsc(context.companyId()).stream()
				.filter(holiday -> from == null || !holiday.getHolidayDate().isBefore(from))
				.filter(holiday -> to == null || !holiday.getHolidayDate().isAfter(to))
				.map(OfficialHolidayView::of)
				.toList();
	}

	@Transactional(readOnly = true)
	public Optional<OfficialHolidayView> get(AuthorizationContext context, Long holidayId) {
		tenantSessionVariable.apply(context.companyId());
		return holidayRepository.findByIdAndCompanyId(holidayId, context.companyId())
				.map(OfficialHolidayView::of);
	}

	/**
	 * create.php: one name applied across a list of dates, each date
	 * upserted. Duplicate dates inside a single request collapse, and the
	 * response carries every affected row in request order.
	 */
	@Transactional
	public List<OfficialHolidayView> create(AuthorizationContext context, CreateHolidaysRequest request) {
		tenantSessionVariable.apply(context.companyId());
		String name = request.name().trim();
		if (name.isEmpty()) {
			throw new ApiException(HttpStatus.BAD_REQUEST, MessageKeys.HOLIDAYS_NAME_REQUIRED);
		}
		// Dedupe preserving first-occurrence order, as legacy's normalizer does.
		LinkedHashSet<LocalDate> dates = new LinkedHashSet<>(request.holidayDates());
		if (dates.isEmpty()) {
			throw new ApiException(HttpStatus.BAD_REQUEST, MessageKeys.HOLIDAYS_DATES_REQUIRED);
		}

		List<OfficialHolidayView> affected = new ArrayList<>();
		for (LocalDate date : dates) {
			OfficialHoliday holiday = holidayRepository
					.findByCompanyIdAndHolidayDate(context.companyId(), date)
					.map(existing -> {
						existing.rename(name);
						return existing;
					})
					.orElseGet(() -> new OfficialHoliday(context.companyId(), name, date));
			affected.add(OfficialHolidayView.of(save(holiday)));
		}
		return affected;
	}

	/** update.php: 409 when the target date already belongs to another row. */
	@Transactional
	public Optional<OfficialHolidayView> update(
			AuthorizationContext context, Long holidayId, UpdateHolidayRequest request) {
		tenantSessionVariable.apply(context.companyId());
		Optional<OfficialHoliday> existing =
				holidayRepository.findByIdAndCompanyId(holidayId, context.companyId());
		if (existing.isEmpty()) {
			return Optional.empty();
		}
		String name = request.name().trim();
		if (name.isEmpty()) {
			throw new ApiException(HttpStatus.BAD_REQUEST, MessageKeys.HOLIDAYS_NAME_REQUIRED);
		}
		OfficialHoliday holiday = existing.get();
		LocalDate target = request.holidayDate() != null ? request.holidayDate() : holiday.getHolidayDate();

		boolean occupiedByAnother = holidayRepository
				.findByCompanyIdAndHolidayDate(context.companyId(), target)
				.filter(other -> !other.getId().equals(holiday.getId()))
				.isPresent();
		if (occupiedByAnother) {
			throw new ApiException(HttpStatus.CONFLICT, MessageKeys.HOLIDAYS_DATE_ALREADY_TAKEN);
		}

		holiday.update(name, target);
		return Optional.of(OfficialHolidayView.of(save(holiday)));
	}

	@Transactional
	public boolean delete(AuthorizationContext context, Long holidayId) {
		tenantSessionVariable.apply(context.companyId());
		Optional<OfficialHoliday> existing =
				holidayRepository.findByIdAndCompanyId(holidayId, context.companyId());
		existing.ifPresent(holidayRepository::delete);
		return existing.isPresent();
	}

	/**
	 * official_holidays_by_date_in_range: the seam the schedule and
	 * attendance-calendar modules read, replacing the empty stub they
	 * have carried since PR #67. Insertion-ordered by date, because
	 * legacy's callers iterate the map directly.
	 *
	 * <p>Caller must already hold a tenant transaction.
	 */
	public Map<LocalDate, String> holidaysByDate(Long companyId, LocalDate from, LocalDate to) {
		if (to.isBefore(from)) {
			return Map.of();
		}
		Map<LocalDate, String> byDate = new LinkedHashMap<>();
		for (OfficialHoliday holiday : holidayRepository
				.findByCompanyIdAndHolidayDateBetweenOrderByHolidayDateAsc(companyId, from, to)) {
			byDate.put(holiday.getHolidayDate(), holiday.getName());
		}
		return byDate;
	}

	private OfficialHoliday save(OfficialHoliday holiday) {
		try {
			return holidayRepository.saveAndFlush(holiday);
		} catch (DataIntegrityViolationException ex) {
			// The UNIQUE (company_id, holiday_date) backstop under a
			// concurrent write. Legacy leaks this as a 500.
			throw new ApiException(HttpStatus.CONFLICT, MessageKeys.HOLIDAYS_DATE_ALREADY_TAKEN, ex);
		}
	}

}
