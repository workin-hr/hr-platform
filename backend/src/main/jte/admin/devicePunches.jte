@import java.util.Map
@import java.util.function.Function
@import com.workin.backend.platformadmin.web.AdminDeviceView
@import com.workin.backend.platformadmin.web.DashboardPage
@param DashboardPage<Map<String, Object>> result
@param String title
@param Map<String, String> carry
@param Function<String, String> t
<%-- The punch table the devices page shows twice: every company's latest, and
     one terminal's. Newest received first, so a scan at the terminal appears at
     the top on the next refresh. --%>
<div class="data-table-card">
	<div class="data-table-head">
		<h2 class="data-table-title">${title} (${result.total()})</h2>
	</div>
	<div class="table-wrap">
	<table class="tbl">
		<thead>
			<tr>
				<th>${t.apply("device_punched_at")}</th>
				<th>${t.apply("device_serial")}</th>
				<th>${t.apply("company")}</th>
				<th>PIN</th>
				<th>${t.apply("employee")}</th>
				<th>${t.apply("status")}</th>
				<th>${t.apply("device_delivered_via")}</th>
				<th>${t.apply("device_in_out")}</th>
				<th>${t.apply("device_review_flag")}</th>
				<th>${t.apply("device_received_at")}</th>
			</tr>
		</thead>
		<tbody>
			@for(Map<String, Object> row : result.data())
				<tr>
					<td class="bold">${AdminDeviceView.text(row, "punched_at_local")}</td>
					<td>${AdminDeviceView.text(row, "serial_number")}</td>
					<td class="text-muted">${AdminDeviceView.text(row, "company_name")}</td>
					<td>${AdminDeviceView.text(row, "pin")}</td>
					<td>${AdminDeviceView.text(row, "employee_name")}</td>
					<td class="bold">${AdminDeviceView.text(row, "processing_state")}</td>
					<td>${AdminDeviceView.text(row, "delivered_via")}</td>
					<td>${AdminDeviceView.text(row, "status_code")} / ${AdminDeviceView.text(row, "verify_code")}</td>
					<td class="text-muted">${AdminDeviceView.text(row, "review_flag")}</td>
					<td class="text-muted">${AdminDeviceView.text(row, "received_at")}</td>
				</tr>
			@endfor
			@if(result.isEmpty())
				<tr><td colspan="10" class="data-table-empty">${t.apply("no_data")}</td></tr>
			@endif
		</tbody>
	</table>
	</div>
</div>
@template.admin.pager(basePath = "/admin/devices", result = result,
		carry = carry, t = t, pageParam = "punch_page")
