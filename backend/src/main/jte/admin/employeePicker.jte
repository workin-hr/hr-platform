@import java.util.function.Function
@param String inputId
@param String source
@param Function<String, String> t
@param boolean fromRow = false
<%-- Legacy's emp-picker (dashboard/pages/advances/page.php), driven by
     emp-picker.js. `source` is the id of the element whose data-employees
     holds the page's list. In a row dialog (fromRow), row-dialog.js copies the
     row's employee id and the row's own label in, so an employee the list no
     longer holds is still the one the edit saves. --%>
<div class="emp-picker" data-emp-picker data-emp-source="${source}">
	<input type="search" id="${inputId}" class="emp-picker__search" data-emp-search
	       autocomplete="off" placeholder="${t.apply("employee_picker_placeholder")}">
	<input type="hidden" name="employee_id" value="" data-emp-id
	       data-dialog-field="${fromRow ? "employee_id" : null}">
	<input type="hidden" value="" data-emp-fallback-label
	       data-dialog-field="${fromRow ? "employee_label" : null}">
	<p class="emp-picker__selected" data-emp-selected hidden></p>
	<ul class="emp-picker__results" data-emp-results data-empty-label="${t.apply("no_data")}"></ul>
</div>
