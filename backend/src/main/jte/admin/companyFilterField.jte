@import java.util.List
@import java.util.function.Function
@import java.util.function.Supplier
@import com.workin.backend.platformadmin.org.ActiveCompanies
@param long companyId
@param Supplier<List<ActiveCompanies.CompanyOption>> companies
@param Function<String, String> t
<%-- org_render_admin_company_filter_field() (org_helper.php:895-910): the toolbar's
     company filter as a select of the active companies, "All companies" first, with the
     filtered company selected. A page renders it only for a session not scoped to one
     company, as legacy does, and the list is read only when it renders (D-252). --%>
<div class="filter-field">
	<label class="filter-field__label" for="org_company">${t.apply("company")}</label>
	<select name="company_id" id="org_company" data-filter-company>
		<option value="">${t.apply("filter_all_companies")}</option>
		@for(ActiveCompanies.CompanyOption company : companies.get())
			<option value="${company.id()}" selected="${companyId == company.id()}">${company.name()}</option>
		@endfor
	</select>
</div>
