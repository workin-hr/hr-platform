@import java.util.function.Function
@import com.workin.backend.platformadmin.org.Branch
@import com.workin.backend.platformadmin.web.DashboardListFilters
@import com.workin.backend.platformadmin.web.DashboardSession
@param Branch branch = null
@param String csrfParameterName
@param String csrfToken
@param DashboardSession session
@param DashboardListFilters filters
@param boolean canWrite
@param Function<String, String> t
<%-- dashboard/pages/branches/_branch_form.php, serving both add and edit --
     one form, distinguished by whether a row was passed. --%>
<div class="data-table-card">
	<div class="data-table-head">
		<h2 class="data-table-title">${t.apply(branch == null ? "add_branch" : "edit")}</h2>
	</div>
	<form method="POST" class="account-form org-form">
		<input type="hidden" name="${csrfParameterName}" value="${csrfToken}">
		<input type="hidden" name="action" value="${branch == null ? "add" : "save_edit"}">
		@if(branch != null)
			<input type="hidden" name="id" value="${branch.id()}">
		@endif
		<%-- company_id travels with the form so an administrator's write lands
		     in the company they were looking at, and so the redirect can put
		     the filter back. A scoped session's is its own and the field is
		     ignored by the service. --%>
		<input type="hidden" name="company_id"
		       value="${branch != null ? branch.companyId() : filters.companyId()}">

		<div class="form-row">
			<label for="name">${t.apply("branch")}</label>
			<input type="text" id="name" name="name" required maxlength="255"
			       value="${branch == null ? "" : branch.name()}">
		</div>
		<div class="form-row">
			<label for="address">${t.apply("address")}</label>
			<input type="text" id="address" name="address" maxlength="500"
			       value="${branch == null || branch.address() == null ? "" : branch.address()}">
		</div>
		<div class="form-row">
			<label for="lat">${t.apply("latitude")}</label>
			<input type="text" id="lat" name="lat" inputmode="decimal"
			       value="${branch == null || branch.latitude() == null ? "" : branch.latitude().toPlainString()}">
		</div>
		<div class="form-row">
			<label for="lng">${t.apply("longitude")}</label>
			<input type="text" id="lng" name="lng" inputmode="decimal"
			       value="${branch == null || branch.longitude() == null ? "" : branch.longitude().toPlainString()}">
		</div>
		<div class="form-row">
			<label for="radius_meters">${t.apply("branch_attendance_radius")}</label>
			<input type="number" id="radius_meters" name="radius_meters" min="1" max="5000"
			       value="${branch == null ? 200 : branch.radiusMeters()}">
		</div>
		@if(branch != null)
			<%-- Always sent, never a bare checkbox: `(int) ($_POST['is_active'] ?? 1)`
			     reads an absent field as active, so an unchecked box that simply
			     vanished would silently reactivate a deactivated branch. --%>
			<div class="form-row">
				<label for="is_active">${t.apply("status")}</label>
				<select id="is_active" name="is_active">
					<option value="1" selected="${branch.active()}">${t.apply("status_active")}</option>
					<option value="0" selected="${!branch.active()}">${t.apply("status_inactive")}</option>
				</select>
			</div>
		@endif

		<div class="account-form__footer">
			<a href="/admin/branches" class="btn btn-gray">${t.apply("cancel")}</a>
			@if(canWrite)
				<button type="submit" class="btn btn-yellow">${t.apply("save")}</button>
			@endif
		</div>
	</form>
</div>
