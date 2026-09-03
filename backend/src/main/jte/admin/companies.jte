@import java.util.List
@import com.workin.backend.identity.Company
@param String csrfParameterName
@param String csrfToken
@param String currentAdminPhone
@param boolean actionsEnabled
@param List<Company> companies
@param String error = null
@template.admin.layout(
	title = "Companies",
	csrfParameterName = csrfParameterName,
	csrfToken = csrfToken,
	currentAdminPhone = currentAdminPhone,
	body = @`
		<div class="card">
			<h1>Companies</h1>
			<p class="sub">Platform-wide oversight and lifecycle.</p>
			@if(error != null)
				<p class="error">${error}</p>
			@endif
			@if(!actionsEnabled)
				<p class="notice">
					Administrative actions are disabled on this deployment. ADR-0015
					requires the legacy PHP admin surface to be unreachable before this
					surface performs a privileged operation.
				</p>
			@endif
			<table class="grid">
				<thead>
					<tr><th>#</th><th>Name</th><th>Status</th><th></th></tr>
				</thead>
				<tbody>
					@for(Company company : companies)
						<tr>
							<td>${company.getId()}</td>
							<td>${company.getName() == null ? "—" : company.getName()}</td>
							<td>${company.getStatus()}</td>
							<td>
								@if(actionsEnabled)
									<form method="post" action="/admin/companies/confirm">
										<input type="hidden" name="${csrfParameterName}" value="${csrfToken}">
										<input type="hidden" name="companyId" value="${company.getId()}">
										@if("suspended".equals(company.getStatus()))
											<button type="submit" name="action" value="COMPANY_RESTORE" class="linkish">Restore</button>
										@else
											<button type="submit" name="action" value="COMPANY_SUSPEND" class="linkish">Suspend</button>
										@endif
									</form>
								@endif
							</td>
						</tr>
					@endfor
				</tbody>
			</table>
		</div>
	`)
