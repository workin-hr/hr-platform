@import gg.jte.Content
@import java.util.function.Function
<%-- A modal for a row action that needs typing.

     Four list pages put a text or number box directly in the actions column,
     which is why they could not become a ⋮ menu with the rest: a field inside
     a dropdown is a worse control, and a form inside every row makes the row
     three lines tall whether or not anyone is editing.

     Legacy solves it the same way -- hr_list_helper's advance and asset menus
     open a dialog rather than submitting inline. One dialog per page, not one
     per row: the trigger carries the row's values in data- attributes and the
     script copies them in, so a 200-row page has one dialog rather than 200.

     Native <dialog>: the focus trap, the Escape key, the backdrop and the
     inertness of everything behind it are the browser's, not ours. --%>
@param String id
@param String title
@param String action
@param String csrfParameterName
@param String csrfToken
@param Function<String, String> t
@param Content fields
@param String submitLabel
@param String submitVariant = "blue"
<dialog class="row-dialog" id="${id}" aria-labelledby="${id}-title">
	<form method="POST" class="row-dialog__form">
		<input type="hidden" name="${csrfParameterName}" value="${csrfToken}">
		<input type="hidden" name="action" value="${action}">
		<%-- Filled from the trigger's data-dialog-id before the dialog opens. --%>
		<input type="hidden" name="id" data-dialog-field="id">

		<header class="row-dialog__head">
			<h3 id="${id}-title">${title}</h3>
			<%-- Who or what the action is about, so the dialog is not a form
			     floating free of the row it came from. --%>
			<p class="row-dialog__subject" data-dialog-field="subject"></p>
		</header>

		<div class="row-dialog__body">
			${fields}
		</div>

		<footer class="row-dialog__foot">
			<%-- formnovalidate: cancelling must not be blocked by a required
			     field the user has decided not to fill in. --%>
			<button type="submit" class="btn btn-gray" value="cancel" formmethod="dialog"
					formnovalidate>${t.apply("cancel")}</button>
			<button type="submit" class="btn btn-${submitVariant}">${submitLabel}</button>
		</footer>
	</form>
</dialog>
