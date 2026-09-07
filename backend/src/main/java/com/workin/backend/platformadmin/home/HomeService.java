package com.workin.backend.platformadmin.home;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.SequencedMap;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

import com.workin.backend.platformadmin.hr.Activity;
import com.workin.backend.platformadmin.hr.ActivityStore;
import com.workin.backend.platformadmin.hr.Complaint;
import com.workin.backend.platformadmin.hr.ComplaintStore;
import com.workin.backend.platformadmin.web.DashboardAccess;
import com.workin.backend.platformadmin.web.DashboardListFilters;
import com.workin.backend.platformadmin.web.DashboardSession;

/**
 * The dashboard's home page, for the audience ADR-0016 kept: the platform
 * administrator.
 *
 * <p>Legacy gates each block of the page on a permission and each query on the
 * session's company, and both are reproduced here. The permissions matter even
 * on a surface where every session is an administrator -- {@code hasFullAccess}
 * short-circuits them today, and the day it does not, a page that never asked
 * would be the one that leaks.
 *
 * <p>The company is {@link DashboardSession#companyId()}: the administrator's
 * filter, where {@code 0} means every company at once. That is the deliberate
 * cross-tenant read R-044 was filed for, and it is why this service takes a
 * session rather than a company id -- so the tenant guard can see it.
 */
@Service
@Profile("phase1-mysql")
public class HomeService {

	private final HomeStore store;

	private final ActivityStore activityStore;

	private final ComplaintStore complaintStore;

	public HomeService(HomeStore store, ActivityStore activityStore, ComplaintStore complaintStore) {
		this.store = store;
		this.activityStore = activityStore;
		this.complaintStore = complaintStore;
	}

	/**
	 * @return the counts across the top of the page, all scoped to
	 *     {@code session.companyId()}
	 */
	public HomeSummary summary(DashboardSession session) {
		return this.store.summary(session.companyId());
	}

	/**
	 * The charts, keyed by the message key that titles each one.
	 *
	 * <p>A {@link SequencedMap} because the order is the page's layout, and an
	 * empty series is dropped rather than rendered: a chart card that says
	 * "gender" over an empty canvas reads as a broken page, not as "no data".
	 */
	public SequencedMap<String, HomeChart> charts(DashboardSession session) {
		SequencedMap<String, HomeChart> charts = new LinkedHashMap<>();
		if (!DashboardAccess.can(session, DashboardAccess.PERM_DASHBOARD)) {
			return charts;
		}
		long companyId = session.companyId();
		put(charts, "chart_new_monthly", this.store.newEmployeesByMonth(companyId));
		put(charts, "chart_daily_att", this.store.dailyAttendance(companyId));
		put(charts, "chart_gender", this.store.employeesByGender(companyId));
		put(charts, "chart_age", this.store.employeesByAge(companyId));
		put(charts, "chart_emp_dept", this.store.employeesByDepartment(companyId));
		put(charts, "chart_emp_branch", this.store.employeesByBranch(companyId));
		put(charts, "chart_salary_dept", this.store.salaryByDepartment(companyId));
		put(charts, "chart_att_dept", this.store.attendanceByDepartment(companyId));
		put(charts, "chart_pen_dept", this.store.penaltiesByDepartment(companyId));
		return charts;
	}

	/**
	 * The active banners, in the order the clients render them.
	 *
	 * <p>No permission gate and no company scope: they are platform content
	 * every audience sees, and the page that administers them is already behind
	 * its own permission.
	 */
	public java.util.List<com.workin.backend.platformadmin.content.Banner> banners() {
		return this.store.banners();
	}

	/**
	 * Planned headcount against actual, as two series over one label set.
	 *
	 * <p>Separate from {@link #charts} because that map is one series per key
	 * and this chart is two; widening the map's value type for one entry would
	 * make every other caller carry the shape it does not use.
	 *
	 * @return planned first, then actual; both empty when there is nothing to draw
	 */
	public java.util.List<HomeChart> workforcePlanning(DashboardSession session) {
		if (!DashboardAccess.can(session, DashboardAccess.PERM_DASHBOARD)) {
			return java.util.List.of(HomeChart.EMPTY, HomeChart.EMPTY);
		}
		return this.store.workforcePlanning(session.companyId());
	}

	/**
	 * The three turnover percentages the dashboard prints beside the counts.
	 *
	 * <p>Today is read once and passed down, so the monthly, annual and 90-day
	 * windows are all measured against the same date -- three calls to the
	 * clock could straddle midnight and produce a set that does not add up.
	 */
	public HomeStore.Turnover turnover(DashboardSession session) {
		if (!DashboardAccess.can(session, DashboardAccess.PERM_DASHBOARD)) {
			return new HomeStore.Turnover(0, 0, 0);
		}
		return this.store.turnover(session.companyId(), java.time.LocalDate.now());
	}

	/**
	 * {@code home_get_recent_activities()}: the last few punches and requests.
	 *
	 * <p>The same store the activities page reads, with the same two permission
	 * gates -- a session that may see one half and not the other sees half the
	 * feed here too, rather than an empty panel or somebody else's data.
	 */
	public java.util.List<Activity> recentActivities(DashboardSession session, int limit) {
		if (!DashboardAccess.can(session, DashboardAccess.PERM_RECENT_ACTIVITIES)) {
			return java.util.List.of();
		}
		java.time.LocalDate today = java.time.LocalDate.now();
		return this.activityStore.list(
				session.companyId(), "all",
				today.minusDays(30).toString(), today.toString(), limit, 0,
				DashboardAccess.can(session, DashboardAccess.PERM_ATTENDANCE),
				DashboardAccess.can(session, DashboardAccess.PERM_REQUESTS)).rows();
	}

	/**
	 * {@code home_get_employee_complaints()}: the pending queue, newest first.
	 *
	 * <p>Which queue depends on the audience, and the store already knows: an
	 * administrator with no company filter is looking at
	 * {@code source='company_support'} -- the complaints addressed to the
	 * platform rather than to a company's own HR.
	 */
	public java.util.List<Complaint> openComplaints(
			DashboardSession session, DashboardListFilters filters, int limit) {
		if (!DashboardAccess.can(session, DashboardAccess.PERM_EMPLOYEES)) {
			return java.util.List.of();
		}
		String source = session.isScopedToOneCompany() || filters.companyId() > 0
				? "employee" : "company_support";
		return this.complaintStore
				.paginate(session, filters, source, "pending", "", "")
				.data().stream().limit(limit).toList();
	}

	private static void put(Map<String, HomeChart> charts, String key, HomeChart chart) {
		if (!chart.isEmpty()) {
			charts.put(key, chart);
		}
	}

}
