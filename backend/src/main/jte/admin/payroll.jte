@import java.util.List
@import java.util.function.Function
@import com.workin.backend.platformadmin.hr.PayrollRecord
@import com.workin.backend.platformadmin.web.DashboardListFilters
@import com.workin.backend.platformadmin.web.DashboardPage
@import com.workin.backend.platformadmin.web.DashboardSession
@param String csrfParameterName
@param String csrfToken
@param String currentAdminPhone
@param DashboardPage<PayrollRecord.BatchRow> batchResult
@param DashboardPage<java.util.Map<String, Object>> detailResult
@param PayrollRecord.BatchTotals batchTotals
@param PayrollRecord.CurrentBatch currentBatch
@param PayrollRecord.PayslipRow editDetail
@param List<PayrollRecord.CompanyOption> companyOptions
@param DashboardListFilters filters
@param int month
@param int year
@param long viewRunId
@param java.time.LocalDate today
@param boolean showCompanyColumn
@param boolean canManage
@param boolean actionsEnabled
@param String errorKey = null
@param String currentPage
@param String lang
@param DashboardSession session
@param Function<String, String> t
@param Function<String, Boolean> available
!{boolean canWrite = canManage && actionsEnabled;}
<%-- `clean($row['x'] ?? '—')`: a missing or null value renders as an
     em dash, never as the literal "null". --%>
!{java.util.function.BiFunction<java.util.Map<String, Object>, String, String> cell =
		(row, key) -> row.get(key) == null ? "—" : String.valueOf(row.get(key));}
<%-- dashboard/pages/payroll/page.php.

     Two views behind one URL: the batch list, and one batch's payslips once
     run_id is set. The write actions are guarded here where the source guards
     nothing -- see PayrollAdminService and R-064. --%>
@template.admin.layout(
	title = viewRunId > 0 && currentBatch != null
		? t.apply("payroll_batch_detail") + " — " + currentBatch.companyName()
			+ " (" + currentBatch.month() + "/" + currentBatch.year() + ")"
		: t.apply("nav_payroll"),
	csrfParameterName = csrfParameterName,
	csrfToken = csrfToken,
	currentAdminPhone = currentAdminPhone,
	currentPage = currentPage,
	lang = lang,
	session = session,
	t = t,
	available = available,
	pageStyles = java.util.List.of("payroll-pages.css", "hr-pages.css"),
	body = @`
		@if(errorKey != null)
			<div class="flash flash-error">${t.apply(errorKey)}</div>
		@endif
		@if(canManage && !actionsEnabled)
			<div class="flash flash-warning">${t.apply("admin_actions_disabled")}</div>
		@endif

		@if(editDetail != null)
			<%-- ?action=edit_detail&id=: one payslip's twelve writable fields.
			     The other thirteen columns are not on this form and are not
			     rewritten, which is why a hand-edited payslip's net stops
			     reconciling with its own stored totals. --%>
			<%-- page.php renders this in modal over a
			     modal-bg open. The port had it as a data-table-card in the
			     page flow, wrapped in account-form org-form -- classes no
			     stylesheet defines here or in legacy. --%>
			<div class="modal-bg open">
				<div class="modal">
					<h2>${t.apply("edit")} — ${editDetail.employeeName()}</h2>
					<form method="POST">
					<input type="hidden" name="${csrfParameterName}" value="${csrfToken}">
					<input type="hidden" name="action" value="edit_detail">
					<input type="hidden" name="id" value="${editDetail.id()}">
					<div class="form-row">
						<label for="days_present">${t.apply("days_present")}</label>
						<input type="number" step="0.01" id="days_present" name="days_present"
						       value="${editDetail.daysPresent().toPlainString()}">
					</div>
					<div class="form-row">
						<label for="days_absent">${t.apply("days_absent")}</label>
						<input type="number" step="0.01" id="days_absent" name="days_absent"
						       value="${editDetail.daysAbsent().toPlainString()}">
					</div>
					<div class="form-row">
						<label for="days_leave">${t.apply("days_leave")}</label>
						<input type="number" step="0.01" id="days_leave" name="days_leave"
						       value="${editDetail.daysLeave().toPlainString()}">
					</div>
					<div class="form-row">
						<label for="overtime_hours">${t.apply("overtime_hours")}</label>
						<input type="number" step="0.01" id="overtime_hours" name="overtime_hours"
						       value="${editDetail.overtimeHours().toPlainString()}">
					</div>
					<div class="form-row">
						<label for="basic_salary">${t.apply("basic_salary")}</label>
						<input type="number" step="0.01" id="basic_salary" name="basic_salary"
						       value="${editDetail.basicSalary().toPlainString()}">
					</div>
					<div class="form-row">
						<label for="allowances">${t.apply("allowances")}</label>
						<input type="number" step="0.01" id="allowances" name="allowances"
						       value="${editDetail.allowances().toPlainString()}">
					</div>
					<div class="form-row">
						<label for="overtime_pay">${t.apply("overtime_pay")}</label>
						<input type="number" step="0.01" id="overtime_pay" name="overtime_pay"
						       value="${editDetail.overtimePay().toPlainString()}">
					</div>
					<div class="form-row">
						<label for="penalties_total">${t.apply("penalties")}</label>
						<input type="number" step="0.01" id="penalties_total" name="penalties_total"
						       value="${editDetail.penaltiesTotal().toPlainString()}">
					</div>
					<div class="form-row">
						<label for="advance_deduction">${t.apply("advance_deduction")}</label>
						<input type="number" step="0.01" id="advance_deduction" name="advance_deduction"
						       value="${editDetail.advanceDeduction().toPlainString()}">
					</div>
					<div class="form-row">
						<label for="advances_deduction">${t.apply("advance_deduction")}</label>
						<input type="number" step="0.01" id="advances_deduction" name="advances_deduction"
						       value="${editDetail.advancesDeduction().toPlainString()}">
					</div>
					<div class="form-row">
						<label for="other_deductions">${t.apply("other_fixed_deductions")}</label>
						<input type="number" step="0.01" id="other_deductions" name="other_deductions"
						       value="${editDetail.otherDeductions().toPlainString()}">
					</div>
					<div class="form-row">
						<label for="net_salary">${t.apply("net_salary")}</label>
						<input type="number" step="0.01" id="net_salary" name="net_salary"
						       value="${editDetail.netSalary().toPlainString()}">
					</div>
					<div class="form-footer">
						<a class="btn btn-gray"
						   href="/admin/payroll?run_id=${viewRunId}">${t.apply("cancel")}</a>
						<button type="submit" class="btn btn-blue">${t.apply("save")}</button>
					</div>
					</form>
				</div>
			</div>
		@elseif(viewRunId > 0 && currentBatch != null)
			<%-- One batch: its totals strip, then its payslips. --%>
			<div class="toolbar page-toolbar">
				<a class="btn" href="/admin/payroll">${t.apply("back")}</a>
			</div>

			@if(batchTotals != null)
				<div class="data-table-card">
					<div class="data-table-head">
						<h2 class="data-table-title">${t.apply("payroll_batch_detail")}</h2>
					</div>
					<div class="table-wrap">
					<table class="tbl">
						<thead>
							<tr>
								<th>${t.apply("employees")}</th>
								<th>${t.apply("total_entitlements")}</th>
								<th>${t.apply("total_deductions")}</th>
								<th>${t.apply("net_salary")}</th>
								<th>${t.apply("period_from")}</th>
								<th>${t.apply("period_to")}</th>
								<th>${t.apply("status")}</th>
							</tr>
						</thead>
						<tbody>
							<tr>
								<td>${batchTotals.employeeCount()}</td>
								<td>${batchTotals.totalEntitlements().toPlainString()}</td>
								<td>${batchTotals.totalDeductions().toPlainString()}</td>
								<td class="bold">${batchTotals.netSalary().toPlainString()}</td>
								<td>${currentBatch.periodFrom()}</td>
								<td>${currentBatch.periodTo()}</td>
								<td><span class="badge ${currentBatch.finalized() ? "badge-green" : "badge-yellow"}">${t.apply("status_" + currentBatch.status())}</span></td>
							</tr>
						</tbody>
					</table>
					</div>
				</div>
			@endif

			<%-- The columns page.php renders, in its order. Seven of them are
			     recomputed by payroll_enrich_payslip_row() rather than read from
			     the payslip: days present/absent/leave, penalties_total, and the
			     three totals. An edit_detail write to net_salary is therefore
			     stored but not shown here -- it surfaces in the totals strip
			     above and the list's total_net. That is the source's behaviour. --%>
			<div class="data-table-card">
				<div class="table-wrap">
				<table class="tbl">
					<thead>
						<tr>
							<th>${t.apply("employee")}</th>
							<th>${t.apply("branch")}</th>
							<th>${t.apply("department")}</th>
							<th>${t.apply("job_title")}</th>
							<th class="col-center">${t.apply("payroll_days_present")}</th>
							<th class="col-center">${t.apply("days_absent")}</th>
							<th class="col-center">${t.apply("days_leave")}</th>
							<th class="col-center">${t.apply("overtime_hours")}</th>
							<th class="col-center">${t.apply("contract_basic_salary")}</th>
							<th class="col-center">${t.apply("daily_basic_rate")}</th>
							<th class="col-center">${t.apply("salary_by_work_days")}</th>
							<th class="col-center">${t.apply("salary_by_paid_leave")}</th>
							<th class="col-center">${t.apply("allowances")}</th>
							<th class="col-center">${t.apply("overtime_pay")}</th>
							<th class="col-center">${t.apply("transport_allowance")}</th>
							<th class="col-center">${t.apply("food_allowance")}</th>
							<th class="col-center">${t.apply("risk_allowance")}</th>
							<th class="col-center">${t.apply("incentives")}</th>
							<th class="col-center">${t.apply("total_entitlements")}</th>
							<th class="col-center">${t.apply("insurance_deduction")}</th>
							<th class="col-center">${t.apply("tax_deduction")}</th>
							<th class="col-center">${t.apply("fund_deduction")}</th>
							<th class="col-center">${t.apply("advance_deduction")}</th>
							<th class="col-center">${t.apply("penalties")}</th>
							<th class="col-center">${t.apply("other_fixed_deductions")}</th>
							<th class="col-center">${t.apply("total_deductions")}</th>
							<th class="col-center">${t.apply("net_salary")}</th>
							<th class="col-actions">${t.apply("actions")}</th>
						</tr>
					</thead>
					<tbody>
						@for(java.util.Map<String, Object> row : detailResult.data())
							<tr>
								<td class="bold">${String.valueOf(row.get("employee_name"))}
									<span class="text-muted">${String.valueOf(row.get("emp_code"))}</span></td>
								<td class="text-muted">${cell.apply(row, "branch_name")}</td>
								<td class="text-muted">${cell.apply(row, "department_name")}</td>
								<td>${cell.apply(row, "job_title_name")}</td>
								<td class="col-center">${cell.apply(row, "days_present_ratio")}</td>
								<td class="col-center">${cell.apply(row, "days_absent")}</td>
								<td class="col-center">${cell.apply(row, "days_leave")}</td>
								<td class="col-center">${cell.apply(row, "overtime_hours")}</td>
								<td class="col-center">${cell.apply(row, "contract_basic_salary")}</td>
								<td class="col-center">${cell.apply(row, "daily_basic_rate")}</td>
								<td class="col-center">${cell.apply(row, "salary_by_present_days")}</td>
								<td class="col-center">${cell.apply(row, "salary_by_paid_leave_days")}</td>
								<td class="col-center">${cell.apply(row, "allowances")}</td>
								<td class="col-center">${cell.apply(row, "overtime_pay")}</td>
								<td class="col-center">${cell.apply(row, "transport_allowance")}</td>
								<td class="col-center">${cell.apply(row, "food_allowance")}</td>
								<td class="col-center">${cell.apply(row, "risk_allowance")}</td>
								<td class="col-center">${cell.apply(row, "incentives")}</td>
								<td class="col-center bold">${cell.apply(row, "total_entitlements")}</td>
								<td class="col-center text-red">${cell.apply(row, "insurance_deduction")}</td>
								<td class="col-center text-red">${cell.apply(row, "tax_deduction")}</td>
								<td class="col-center text-red">${cell.apply(row, "fund_deduction")}</td>
								<td class="col-center text-red">${cell.apply(row, "advance_deduction")}</td>
								<td class="col-center text-red">${cell.apply(row, "penalties_total")}</td>
								<td class="col-center text-red">${cell.apply(row, "other_deductions")}</td>
								<td class="col-center text-red">${cell.apply(row, "total_deductions")}</td>
								<td class="col-center bold">${cell.apply(row, "net_salary")}</td>
								<td class="col-actions">
									@if(canWrite && !currentBatch.finalized())
										@template.admin.rowActions(rowId = cell.apply(row, "id"), t = t, items = @`
											<a role="menuitem" class="row-actions__item"
											   href="/admin/payroll?action=edit_detail&id=${cell.apply(row, "id")}&run_id=${viewRunId}">${t.apply("edit")}</a>
										`)
									@else
										<span class="text-muted">—</span>
									@endif
								</td>
							</tr>
						@endfor
						@if(detailResult.isEmpty())
							<tr><td colspan="28" class="data-table-empty">${t.apply("no_data")}</td></tr>
						@endif
					</tbody>
				</table>
				</div>
				<%-- The run this table belongs to travels with every page link and with
				     the size form; without it, turning the page loses the batch. --%>
				!{java.util.SequencedMap<String, String> detailCarry = new java.util.LinkedHashMap<>();}
				!{detailCarry.put("run_id", String.valueOf(viewRunId));}
				@template.admin.pager(basePath = "/admin/payroll", result = detailResult,
						carry = detailCarry, t = t)
			</div>
		@else
			<div class="toolbar page-toolbar page-toolbar-filters">
				<form method="GET" class="toolbar-form toolbar-form--labeled">
					@if(!session.isScopedToOneCompany())
						<div class="filter-field">
							<label class="filter-field__label" for="pay_company">${t.apply("company")}</label>
							<input type="number" id="pay_company" name="company_id" min="0"
							       placeholder="${t.apply("filter_all")}"
							       value="${filters.companyId() > 0 ? String.valueOf(filters.companyId()) : ""}">
						</div>
					@endif
					<div class="filter-field">
						<label class="filter-field__label" for="pay_month">${t.apply("month")}</label>
						<select name="month" id="pay_month">
							<option value="0">${t.apply("filter_all")}</option>
							@for(int m = 1; m <= 12; m++)
								<option value="${m}" selected="${month == m}">${m}</option>
							@endfor
						</select>
					</div>
					<div class="filter-field">
						<label class="filter-field__label" for="pay_year">${t.apply("year")}</label>
						<select name="year" id="pay_year">
							<option value="0">${t.apply("filter_all")}</option>
							@for(int y = today.getYear() + 1; y >= 2020; y--)
								<option value="${y}" selected="${year == y}">${y}</option>
							@endfor
						</select>
					</div>
					<button type="submit" class="btn btn-blue">${t.apply("search")}</button>
				</form>
			</div>

			@if(canWrite)
				<%-- page.php:320 keeps this in createRunModal, behind the "+" button
				     on the batches table below. Rendered inline it sat open above
				     that table on every visit, whether or not anyone was creating
				     a batch. --%>
				<div class="modal-bg" id="createRunModal">
					<div class="modal" role="dialog" aria-modal="true"
							aria-labelledby="createRunModal-title">
						<button type="button" class="modal-close"
								aria-label="${t.apply("cancel")}">&times;</button>
						<h2 id="createRunModal-title">${t.apply("create_payroll_batch")}</h2>
					<form method="POST">
						<input type="hidden" name="${csrfParameterName}" value="${csrfToken}">
						<input type="hidden" name="action" value="create_run">
						@if(!session.isScopedToOneCompany())
							<div class="form-row">
								<label for="run_company">${t.apply("company")}</label>
								<select id="run_company" name="company_id" required>
									<option value="0">—</option>
									@for(PayrollRecord.CompanyOption option : companyOptions)
										<option value="${option.id()}">${option.name()}</option>
									@endfor
								</select>
							</div>
						@endif
						<div class="form-row">
							<label for="run_month">${t.apply("month")}</label>
							<select id="run_month" name="month" required>
								@for(int m = 1; m <= 12; m++)
									<option value="${m}" selected="${today.getMonthValue() == m}">${m}</option>
								@endfor
							</select>
						</div>
						<div class="form-row">
							<label for="run_year">${t.apply("year")}</label>
							<select id="run_year" name="year" required>
								@for(int y = today.getYear() + 1; y >= 2020; y--)
									<option value="${y}" selected="${today.getYear() == y}">${y}</option>
								@endfor
							</select>
						</div>
						<div class="form-footer">
							<button type="submit" class="btn btn-blue">${t.apply("save")}</button>
						</div>
					</form>
					</div>
				</div>
			@endif

			<div class="data-table-card">
				<div class="data-table-head">
					<h2 class="data-table-title">${t.apply("nav_payroll")}</h2>
					<div class="data-table-head__actions">
						@if(canWrite)
							<%-- page.php:195. The modal is rendered above; this is what
							     opens it. --%>
							<button type="button" class="btn btn-blue btn-sm"
									onclick="crudOpenAdd('createRunModal')">+ ${t.apply("create_payroll_batch")}</button>
						@endif
					</div>
				</div>
				<div class="table-wrap">
				<table class="tbl">
					<thead>
						<tr>
							<th>#</th>
							@if(showCompanyColumn)
								<th>${t.apply("company")}</th>
							@endif
							<th>${t.apply("month")}</th>
							<th>${t.apply("year")}</th>
							<th>${t.apply("period_from")}</th>
							<th>${t.apply("period_to")}</th>
							<th>${t.apply("employees")}</th>
							<th>${t.apply("net_salary")}</th>
							<th>${t.apply("status")}</th>
							<th class="col-actions">${t.apply("actions")}</th>
						</tr>
					</thead>
					<tbody>
						@for(int index = 0; index < batchResult.data().size(); index++)
							!{PayrollRecord.BatchRow row = batchResult.data().get(index);}
							<tr>
								<td class="text-muted">${batchResult.from() + index}</td>
								@if(showCompanyColumn)
									<td>${row.companyName()}</td>
								@endif
								<td>${row.month()}</td>
								<td>${row.year()}</td>
								<td>${row.periodFrom()}</td>
								<td>${row.periodTo()}</td>
								<td>${row.employeeCount()}</td>
								<td class="bold">${row.totalNet().toPlainString()}</td>
								<td><span class="badge ${"finalized".equals(row.status()) ? "badge-green" : "badge-yellow"}">${t.apply("status_" + row.status())}</span></td>
								<td class="col-actions">
									@template.admin.rowActions(rowId = row.id(), t = t, items = @`
										<a role="menuitem" class="row-actions__item" href="/admin/payroll?run_id=${row.id()}">${t.apply("edit")}</a>
										@if(canWrite)
											@if(!"finalized".equals(row.status()))
												<form method="POST" class="row-actions__form" role="none">
													<input type="hidden" name="${csrfParameterName}" value="${csrfToken}">
													<input type="hidden" name="action" value="calculate">
													<input type="hidden" name="id" value="${row.id()}">
													<button type="submit" role="menuitem" class="row-actions__item">${t.apply("calculate")}</button>
												</form>
												<form method="POST" class="row-actions__form" role="none">
													<input type="hidden" name="${csrfParameterName}" value="${csrfToken}">
													<input type="hidden" name="action" value="finalize">
													<input type="hidden" name="id" value="${row.id()}">
													<button type="submit" role="menuitem" class="row-actions__item">${t.apply("finalize")}</button>
												</form>
											@else
												<form method="POST" class="row-actions__form" role="none">
													<input type="hidden" name="${csrfParameterName}" value="${csrfToken}">
													<input type="hidden" name="action" value="reopen">
													<input type="hidden" name="id" value="${row.id()}">
													<button type="submit" role="menuitem" class="row-actions__item">${t.apply("reopen")}</button>
												</form>
											@endif
											<form method="POST" class="row-actions__form" role="none"
											      onsubmit="return confirm('${t.apply("confirm_delete")}')">
												<input type="hidden" name="${csrfParameterName}" value="${csrfToken}">
												<input type="hidden" name="action" value="delete_run">
												<input type="hidden" name="id" value="${row.id()}">
												<button type="submit" role="menuitem" class="row-actions__item row-actions__item--danger">${t.apply("delete")}</button>
											</form>
										@endif
									`)
								</td>
							</tr>
						@endfor
						@if(batchResult.isEmpty())
							<tr><td colspan="${showCompanyColumn ? 10 : 9}" class="data-table-empty">${t.apply("no_data")}</td></tr>
						@endif
					</tbody>
				</table>
				</div>
				<%-- The month and the year, which are this page's whole filter and
				     which the size selector was dropping. --%>
				!{java.util.SequencedMap<String, String> batchCarry =
						new java.util.LinkedHashMap<>(filters.asQueryParameters());}
				!{if (month > 0) { batchCarry.put("month", String.valueOf(month)); }}
				!{if (year > 0) { batchCarry.put("year", String.valueOf(year)); }}
				@template.admin.pager(basePath = "/admin/payroll", result = batchResult,
						carry = batchCarry, t = t)
			</div>
		@endif
	`)
