package com.workin.backend.platformadmin.web;

import java.util.function.Function;

import org.springframework.ui.Model;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/**
 * Legacy's {@code flash($msg, $type)} ({@code includes/auth.php:264-276}): one message a write
 * leaves for the page it redirects to, which {@code includes/layout.php:174-176} shows once as
 * {@code <div class="flash flash-{type}">} and then forgets (D-253).
 *
 * <p>Spring's flash attributes are that mechanism. They are kept in the session for the
 * redirect's target, merged into that request's model, and removed. The attribute names are
 * the layout's own parameters, so a page hands them straight on. The text is resolved when the
 * write succeeds, in the operator's language, because legacy calls {@code __()} inside
 * {@code flash()}.
 */
public final class AdminFlash {

	private AdminFlash() {
	}

	/** {@code flash($msg)}: legacy's default type. */
	public static void success(RedirectAttributes redirect, String message) {
		put(redirect, message, "success");
	}

	/** {@code flash($msg, 'warning')}: a rejection, a deactivation or a reopened batch. */
	public static void warning(RedirectAttributes redirect, String message) {
		put(redirect, message, "warning");
	}

	/** {@code flash($msg, 'error')}: legacy's type for a delete that succeeded. */
	public static void error(RedirectAttributes redirect, String message) {
		put(redirect, message, "error");
	}

	/** {@code flash(__('saved_ok'))}. */
	public static void saved(RedirectAttributes redirect, Model model) {
		success(redirect, t(model).apply("saved_ok"));
	}

	/** {@code flash(__('deleted_ok'), 'error')}. */
	public static void deleted(RedirectAttributes redirect, Model model) {
		error(redirect, t(model).apply("deleted_ok"));
	}

	/** {@code flash(__('approved_ok'))}. */
	public static void approved(RedirectAttributes redirect, Model model) {
		success(redirect, t(model).apply("approved_ok"));
	}

	/** {@code flash(__('rejected_ok'), 'warning')}. */
	public static void rejected(RedirectAttributes redirect, Model model) {
		warning(redirect, t(model).apply("rejected_ok"));
	}

	/** The advice's translator, which every handler in this package finds in its model. */
	@SuppressWarnings("unchecked")
	public static Function<String, String> t(Model model) {
		return (Function<String, String>) model.getAttribute("t");
	}

	private static void put(RedirectAttributes redirect, String message, String type) {
		redirect.addFlashAttribute("flash", message);
		redirect.addFlashAttribute("flashType", type);
	}

}
