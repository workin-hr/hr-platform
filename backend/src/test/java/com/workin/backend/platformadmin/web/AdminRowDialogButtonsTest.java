package com.workin.backend.platformadmin.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;

/**
 * The row dialog's buttons, read from {@code rowDialog.jte} itself.
 *
 * <p>Enter in a field submits a form through its first submit button. When
 * Cancel was a {@code formmethod="dialog"} submit ahead of Save, Enter in any
 * row dialog closed it and lost what had been typed. The browser spec checks
 * the behaviour on a page shaped like this template; this checks the template
 * still has that shape, on every page that includes it.
 */
class AdminRowDialogButtonsTest {

	private static final Path ROW_DIALOG = Path.of("src/main/jte/admin/rowDialog.jte");

	private static final Pattern SUBMIT = Pattern.compile("type=\"submit\"");

	private static final Pattern JTE_COMMENT = Pattern.compile("<%--.*?--%>", Pattern.DOTALL);

	@Test
	void saveIsTheDialogsOnlySubmitButtonAndCancelOnlyCloses() throws IOException {
		// The markup only: the template's comments explain the old button, by name.
		String template = JTE_COMMENT.matcher(Files.readString(ROW_DIALOG, StandardCharsets.UTF_8)).replaceAll("");

		assertThat(SUBMIT.matcher(template).results().count())
				.as("Save is the only submit button, so Enter in a field submits through it")
				.isEqualTo(1);
		assertThat(template)
				.as("Cancel closes the dialog without submitting the form")
				.contains("<button type=\"button\" class=\"btn btn-gray\" data-dialog-close>")
				.doesNotContain("formmethod=\"dialog\"");
	}
}
