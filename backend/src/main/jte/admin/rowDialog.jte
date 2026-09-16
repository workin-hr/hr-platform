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

     The window is legacy's: .modal-bg > .modal, with its close button and a
     form-footer, the markup requests/page.php:143-163 and advances/page.php:270
     draw. It was a native <dialog> (D-198), which looked like no other window on
     the page -- an add opened legacy's modal and an edit of the same row opened
     this. crud.js closes it on the close button and the backdrop; modal-a11y.js
     adds Escape, the Tab trap and focus back to the opener (D-258). --%>
@param String id
@param String title
@param String action
@param String csrfParameterName
@param String csrfToken
@param Function<String, String> t
@param Content fields
@param String submitLabel
@param String submitVariant = "blue"
<%-- Null when the page's own handler takes the post; jte's smart attributes
     omit the attribute then rather than emitting action="". --%>
@param String formAction = null
<%-- Legacy's complaint and company-reject windows carry a Cancel beside Save
     (complaints/page.php:240, companies/page.php:288); its decision windows do
     not. Not .modal-close: that class is the ×'s, absolutely positioned, and on
     a footer button it lays Cancel over Save -- a tap meant to save would close
     the window and lose what was typed. row-dialog.js closes on data-dialog-cancel. --%>
@param String cancelLabel = null
<div class="modal-bg" id="${id}" aria-hidden="true">
	<div class="modal" role="dialog" aria-modal="true" aria-labelledby="${id}-title">
		<button type="button" class="modal-close" aria-label="${t.apply("close")}">&times;</button>
		<h2 id="${id}-title">${title}</h2>
		<%-- Who or what the action is about, so the window is not a form
		     floating free of the row it came from. Legacy has no such line. --%>
		<p class="row-dialog__subject" data-dialog-field="subject"></p>
		<form method="POST" action="${formAction}">
			<input type="hidden" name="${csrfParameterName}" value="${csrfToken}">
			<input type="hidden" name="action" value="${action}">
			<%-- Filled from the trigger's data-dialog-id before the window opens. --%>
			<input type="hidden" name="id" data-dialog-field="id">
			${fields}
			<%-- Save is the form's only submit button, and the close button above
			     is type="button": Enter in a field submits through Save rather
			     than dismissing what was typed. --%>
			<div class="form-footer">
				@if(cancelLabel != null)
					<button type="button" class="btn btn-gray" data-dialog-cancel>${cancelLabel}</button>
				@endif
				<button type="submit" class="btn btn-${submitVariant}">${submitLabel}</button>
			</div>
		</form>
	</div>
</div>
