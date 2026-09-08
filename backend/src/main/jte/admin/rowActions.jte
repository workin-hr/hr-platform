@import gg.jte.Content
@import java.util.function.Function
<%-- `row_actions_menu()` (dashboard/includes/layout.php): the ⋮ trigger and the
     menu it opens.

     Most list pages here rendered their actions as two or three stacked
     buttons instead, which made every row three lines tall and left a ragged
     column -- while app-ui.css carried this component, and row-actions.js the
     script that opens it, both unused. Legacy's own branches page calls
     `row_actions_menu`, so the stack was neither the legacy behaviour nor the
     better one.

     The items are a slot rather than a data model: they are links, and forms
     with different hidden fields, and the two are not worth flattening into a
     record. What the slot cannot get wrong is the wrapper -- the trigger, the
     ARIA wiring and the menu element row-actions.js looks for. --%>
<%-- Object, not long: most callers pass a row's id, and payroll's detail
     table passes the id it read out of a map as a String. Only the menu's
     DOM id is built from it. --%>
@param Object rowId
@param Function<String, String> t
@param Content items
@param String menuPrefix = "row-actions-menu"
!{String menuId = menuPrefix + "-" + rowId;}
<div class="row-actions" data-row-actions>
	<button type="button" class="row-actions__trigger" aria-label="${t.apply("actions")}"
			aria-haspopup="menu" aria-expanded="false" aria-controls="${menuId}">
		<svg width="18" height="18" viewBox="0 0 24 24" fill="currentColor" aria-hidden="true">
			<circle cx="12" cy="5" r="1.75"/><circle cx="12" cy="12" r="1.75"/><circle cx="12" cy="19" r="1.75"/>
		</svg>
	</button>
	<div class="row-actions__menu" id="${menuId}" role="menu">
		${items}
	</div>
</div>
