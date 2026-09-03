@import java.util.List
@import com.workin.backend.platformadmin.PlatformAdminCompanyDirectory.CompanyView
@param String csrfParameterName
@param String csrfToken
@param String currentAdminPhone
@param boolean actionsEnabled
@param List<CompanyView> companies
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
					<tr><th>#</th><th>Name</th><th>Status</th><th>Actions</th></tr>
				</thead>
				<tbody>
					@for(CompanyView company : companies)
						<tr>
							<td>${company.id()}</td>
							<td>${company.name() == null ? "—" : company.name()}</td>
							<td>${company.status()}</td>
							<td class="actions">
								<a href="/admin/companies/${company.id()}">Detail</a>
								@if(actionsEnabled)
									<form method="post" action="/admin/companies/confirm">
										<input type="hidden" name="${csrfParameterName}" value="${csrfToken}">
										<input type="hidden" name="companyId" value="${company.id()}">
										@if("pending".equals(company.status()))
											<button type="submit" name="action" value="COMPANY_APPROVE" class="linkish">Approve</button>
											<button type="submit" name="action" value="COMPANY_REJECT" class="linkish danger">Reject</button>
										@elseif("suspended".equals(company.status()))
											<button type="submit" name="action" value="COMPANY_RESTORE" class="linkish">Restore</button>
										@elseif("rejected".equals(company.status()))
											<button type="submit" name="action" value="COMPANY_APPROVE" class="linkish">Approve</button>
										@else
											<button type="submit" name="action" value="COMPANY_SUSPEND" class="linkish danger">Suspend</button>
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
