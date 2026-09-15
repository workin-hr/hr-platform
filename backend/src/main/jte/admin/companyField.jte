@import java.util.List
@import java.util.function.Function
@import com.workin.backend.platformadmin.org.ActiveCompanies
@import com.workin.backend.platformadmin.web.DashboardSession
@param DashboardSession session
@param long companyId
@param List<ActiveCompanies.CompanyOption> companies
@param String inputId
@param boolean wide = true
@param String cascade = null
@param Function<String, String> t
<%-- org_render_company_field_in_form(): a scoped session's own company, or the
     company the page is already filtered to, travels hidden. With neither, the
     add names its company from the active ones instead of posting 0, which the
     service can only refuse. `wide` spans the org-form grid; pages without that
     grid pass false. `cascade` is legacy's $formPrefix marker: the page's form
     script finds the select by it ("jt" for job-title-form.js). --%>
@if(session.isScopedToOneCompany())
	<input type="hidden" name="company_id" value="${session.companyId()}">
@elseif(companyId > 0)
	<input type="hidden" name="company_id" value="${companyId}">
@else
	!{gg.jte.Content field = @`
		<label for="${inputId}">${t.apply("company")}</label>
		<select name="company_id" id="${inputId}" data-jt-company="${"jt".equals(cascade) ? "1" : null}" required>
			<option value="">${t.apply("company")}...</option>
			@for(ActiveCompanies.CompanyOption company : companies)
				<option value="${company.id()}">${company.name()}</option>
			@endfor
		</select>
	`;}
	@if(wide)
		<div class="form-row org-form-span-2">${field}</div>
	@else
		<div class="form-row">${field}</div>
	@endif
@endif
