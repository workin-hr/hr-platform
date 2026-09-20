package com.workin.backend.platformadmin.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/**
 * The reader the template gates share.
 *
 * <p>It is tested directly because the gates cannot test it: each one asks it
 * about the templates that exist, so a reader that stops seeing a shape no
 * template currently writes keeps every gate green. #305's review round 4 found
 * exactly that -- with {@link TemplateText#rawClassOf} reverted to the pattern it
 * replaced, {@code --tests 'com.workin.backend.*'} ran 937 tests across 100
 * classes with no failure, because the two windows it was written to see are the
 * two the Cancel rule skips and they carry their dialog semantics already.
 *
 * <p>The two accessors answer different questions on purpose, and the pair of
 * them is the thing worth pinning: {@code classesOf} says which classes will
 * <b>certainly</b> be there, which is what a rule about one element needs;
 * {@code rawClassOf} says what the class <b>could</b> be, which is what a rule
 * that finds elements needs.
 */
class TemplateTextTest {

	@Test
	void classesOfReadsOnlyTheElementsOwnClass() {
		assertThat(TemplateText.classesOf("<div>")).isEmpty();
		assertThat(TemplateText.classesOf("<div class=\"form-row\">")).isEqualTo("form-row");
		assertThat(TemplateText.classesOf("<div id=\"x\" class=\"form-row\">")).isEqualTo("form-row");
		assertThat(TemplateText.classesOf("<div data-dialog-class=\"x\">"))
				.as("an attribute whose name merely ends in `class`").isEmpty();
		assertThat(TemplateText.classesOf("<div data-help='pick class=\"cell\"'>"))
				.as("a class= sequence inside another attribute's value").isEmpty();
		assertThat(TemplateText.classesOf("<div class=\"\">")).isEmpty();
		assertThat(TemplateText.classesOf("<div class=\"  a   b \">"))
				.as("whitespace collapsed").isEqualTo("a b");
	}

	@Test
	void classesOfReportsOnlyWhatTheRenderCannotTakeAway() {
		assertThat(TemplateText.classesOf("<div class=\"${row.cssClass()}\">"))
				.as("a wholly dynamic class can render to nothing").isEmpty();
		assertThat(TemplateText.hasClass(TemplateText.classesOf("<div class=\"form-row${x}\">"), "form-row"))
				.as("an expression glued to the literal").isFalse();
		assertThat(TemplateText.hasClass(TemplateText.classesOf("<div class=\"${x}form-row\">"), "form-row"))
				.isFalse();
		assertThat(TemplateText.hasClass(TemplateText.classesOf("<div class=\"form-row ${x}\">"), "form-row"))
				.as("a real space between them keeps the literal a class of its own").isTrue();
		assertThat(TemplateText.hasClass(TemplateText.classesOf("<div class=\"${t.apply(\"x\")} form-row\">"),
				"form-row")).as("an expression carrying its own quotes").isTrue();
		assertThat(TemplateText.hasClass(TemplateText.classesOf("<div\tclass=\"form-row\">"), "form-row"))
				.as("a tab before the attribute").isTrue();
		assertThat(TemplateText.hasClass(TemplateText.classesOf("<div\nclass=\"form-row\">"), "form-row"))
				.isTrue();
	}

	@Test
	void rawClassOfKeepsWhatTheRenderMightAdd() {
		assertThat(TemplateText.rawClassOf("<div class=\"modal-bg${formOpen ? \" open\" : \"\"}\">", "t"))
				.as("the expression is kept, so a caller can see the `open` it may render")
				.isEqualTo("modal-bg${formOpen ? \" open\" : \"\"}");
		assertThat(TemplateText.rawClassOf("<div id=\"m\" class=\"modal-bg open\">", "t"))
				.as("the class need not be the first attribute").isEqualTo("modal-bg open");
		assertThat(TemplateText.rawClassOf("<div data-open-label=\"x\" class=\"modal\">", "t"))
				.as("`open` in another attribute is not this element's class").isEqualTo("modal");
		assertThat(TemplateText.rawClassOf("<div>", "t")).isEmpty();
	}

	/**
	 * The caller's tag pattern stops at the first {@code >}, so a {@code >} written
	 * inside an attribute hands this a tag whose quote never closes. It fails, and
	 * names the file: this runs over every {@code <div>} in every admin template,
	 * and a bare "never closes" would send a reader looking through all of them.
	 */
	@Test
	void anAttributeThatNeverClosesFailsAndNamesItsFile() {
		assertThatThrownBy(() -> TemplateText.rawClassOf("<div class=\"modal-bg${a >", "branches.jte"))
				.isInstanceOf(AssertionError.class)
				.hasMessageContaining("branches.jte")
				.hasMessageContaining("never closes");
	}

	/**
	 * Why both exist: a gate that reports a line number cannot delete a comment
	 * that spans lines, because everything under it moves.
	 */
	@Test
	void blankCommentsKeepsEveryLineWhereItWas() {
		String source = "a\n<%--\nb\n--%>\nc";
		String blanked = TemplateText.blankComments(source);
		assertThat(blanked.split("\n", -1)).as("the same number of lines")
				.hasSameSizeAs(source.split("\n", -1));
		assertThat(blanked.split("\n", -1)[4]).as("the line after the comment is still the fifth")
				.isEqualTo("c");
		assertThat(TemplateText.withoutComments(source).split("\n", -1)).as("deleting moves them")
				.hasSize(3);
		assertThat(TemplateText.blankComments("x<%-- id=\"a\" --%>y"))
				.as("what the comment says is gone either way").isEqualTo("xy");
	}

	@Test
	void withoutCommentsRemovesWhatRendersNothing() {
		assertThat(TemplateText.withoutComments("a<%-- b --%>c")).isEqualTo("ac");
		assertThat(TemplateText.withoutComments("a<%--\nb\n--%>c")).as("across lines").isEqualTo("ac");
		assertThat(TemplateText.withoutComments("<%--a--%>x<%--b--%>")).as("each one").isEqualTo("x");
		assertThat(TemplateText.withoutComments("a<%-- b --%>c<%-- d")).as("an unterminated one is left")
				.isEqualTo("ac<%-- d");
	}
}
