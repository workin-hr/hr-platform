package com.workin.backend.platformadmin.web;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reading a JTE template as text, for the gates that do.
 *
 * <p>Four of them parse the admin templates to hold a rule the compiler cannot
 * -- that every field is its own labelled cell, that a window the server opens
 * can be dismissed, that no page writes one id twice, that every row action is
 * written in legacy's variant. Each had written its own reader, and each was
 * wrong in its own way about the same two things: that a comment is not markup,
 * and that a class attribute can be built rather than written. #305's rounds 1
 * to 3 found four such misreads, every one of them permissive. One reader
 * serves all four now, so the next misread is fixed once.
 *
 * <p>The gates cannot pin this reader: each asks it only about the templates
 * that exist, so a reader that stops seeing a shape no template writes today
 * leaves every gate green. {@code TemplateTextTest} pins it directly.
 */
final class TemplateText {

	private TemplateText() {
	}

	/**
	 * The template with its {@code <%-- --%>} comments removed.
	 *
	 * <p>A comment renders nothing, so text inside one is not markup. A gate
	 * that reads it can be defeated by prose: a comment mentioning
	 * {@code t.apply("cancel")} satisfied the rule that a window keeps its
	 * Cancel, for a window whose Cancel had been deleted.
	 */
	static String withoutComments(String source) {
		return JTE_COMMENT.matcher(source).replaceAll("");
	}

	private static final Pattern JTE_COMMENT = Pattern.compile("(?s)<%--.*?--%>");

	/**
	 * The template with its comments blanked rather than deleted, so every line
	 * keeps its number.
	 *
	 * <p>{@link #withoutComments(String)} is what a gate wants when it reports a
	 * file; this is what one wants when it reports a <b>line</b>, because deleting
	 * a comment that spans lines moves everything under it.
	 */
	static String blankComments(String source) {
		return JTE_COMMENT.matcher(source).replaceAll(comment -> comment.group().replaceAll("[^\n]", ""));
	}

	/**
	 * A tag's {@code class} attribute exactly as the template writes it, with its
	 * expressions left in, or empty when it has none.
	 *
	 * <p>{@link #classesOf(String)} answers "which classes will certainly be
	 * there", which is what a rule about one element needs. This answers "what
	 * could this class be", which is what a rule about *finding* elements needs:
	 * {@code class="modal-bg${formOpen ? " open" : ""}"} is a window that can be
	 * open, and `classesOf` deliberately reports neither {@code modal-bg} as a
	 * standalone class nor {@code open} at all, because it cannot promise either.
	 */
	static String rawClassOf(String tag, Object source) {
		Matcher attribute = ATTRIBUTE.matcher(tag);
		int from = 0;
		while (from >= 0 && attribute.find(from)) {
			char quote = tag.charAt(attribute.end());
			int at = attribute.end() + 1;
			int depth = 0;
			for (; at < tag.length(); at++) {
				char character = tag.charAt(at);
				if (character == '$' && at + 1 < tag.length() && tag.charAt(at + 1) == '{') {
					depth++;
					at++;
				}
				else if (depth > 0) {
					depth += character == '{' ? 1 : character == '}' ? -1 : 0;
				}
				else if (character == quote) {
					break;
				}
			}
			if (at >= tag.length()) {
				// A `>` inside an attribute truncates the caller's tag, so this is where that
				// shows up. Naming the file matters: this runs over every div in every template.
				throw new AssertionError(source + ": an attribute that never closes: " + tag);
			}
			if (attribute.group(1).equals("class")) {
				return tag.substring(attribute.end() + 1, at);
			}
			from = at + 1;
		}
		return "";
	}

	/**
	 * The value of a tag's own {@code class} attribute as the browser will see it -- its literal
	 * text, with every JTE expression taken out -- or empty when it has none.
	 *
	 * <p>Four shapes were being read as a class that is not one, each of which hides an offence
	 * rather than inventing one. {@code data-dialog-class="x"} ends in {@code class="x"} on a word
	 * boundary. {@code class="${row.cssClass()}"} is text in the template and can render to nothing.
	 * An expression carries its own quotes ({@code class="${t.apply("x")}"}), which a
	 * {@code class="([^"]*)"} pattern cuts in the middle and leaves half an expression reading as a
	 * class. And a {@code class="..."} sequence inside a different attribute's single-quoted value
	 * is not this element's class at all. So the tag's attributes are walked in order: a name, then
	 * a quoted value whose quotes and braces belong to any {@code ${...}} around them, and only the
	 * one named {@code class} is read.
	 *
	 * <p>Two things fail rather than passing as unclassed: an attribute that never closes, which is
	 * what a {@code >} inside an expression looks like because the caller's tag pattern stops there,
	 * and -- because the walk only reads quoted values -- an unquoted class, which no template
	 * writes, simply reads as no class, which is the strict direction.
	 */
	static String classesOf(String tag) {
		Matcher attribute = ATTRIBUTE.matcher(tag);
		// ATTRIBUTE itself requires the leading \s, so starting the walk at 0 finds an attribute
		// separated by a tab or a newline too; seeding it from indexOf(' ') alone read such a tag
		// as carrying no attributes at all, and so no class (#304).
		int from = 0;
		while (from >= 0 && attribute.find(from)) {
			char quote = tag.charAt(attribute.end());
			StringBuilder value = new StringBuilder();
			int depth = 0;
			int at = attribute.end() + 1;
			for (; at < tag.length(); at++) {
				char character = tag.charAt(at);
				if (character == '$' && at + 1 < tag.length() && tag.charAt(at + 1) == '{') {
					depth++;
					at++;
					// The expression's rendered text is unknown. Dropping it silently let
					// `form-row${x}` and `${x}form-row` read as the literal class `form-row`,
					// which may not be the token the render produces (#304); a marker keeps it
					// from gluing onto the literal text on either side.
					value.append(EXPRESSION_MARKER);
				}
				else if (depth > 0) {
					depth += character == '{' ? 1 : character == '}' ? -1 : 0;
				}
				else if (character == quote) {
					break;
				}
				else {
					value.append(character);
				}
			}
			if (at >= tag.length()) {
				throw new AssertionError("an attribute that never closes: " + tag);
			}
			if (attribute.group(1).equals("class")) {
				String classes = value.toString().replaceAll("\\s+", " ").trim();
				// A value built only of expression markers, with no literal text at all, is the
				// existing `class="${row.cssClass()}"` shape: it can render to nothing, so it
				// reads as no class, hiding an offence rather than inventing one.
				return classes.replace(String.valueOf(EXPRESSION_MARKER), "").isBlank() ? "" : classes;
			}
			from = at + 1;
		}
		return "";
	}

	/**
	 * Stands in for a JTE expression's unknown rendered text inside a {@code class} value.
	 *
	 * <p>{@code U+FFFF} is a noncharacter: no template can contain one, so it cannot collide with
	 * real class text. It is written as an escape rather than as itself, because a literal
	 * noncharacter in source is invisible and an editor may drop it.
	 */
	private static final char EXPRESSION_MARKER = '\uFFFF';

	/** Whether {@code classes}, as {@link #classesOf(String)} read them, hold {@code name} as a whole class. */
	static boolean hasClass(String classes, String name) {
		return (" " + classes + " ").contains(" " + name + " ");
	}
	/** An attribute's name, up to the quote its value opens with; a valueless attribute has none. */
	private static final Pattern ATTRIBUTE = Pattern.compile("(?s)\\s([\\w:@.-]+)=(?=[\"'])");

}
