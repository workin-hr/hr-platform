package com.workin.backend.i18n;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.servlet.filter.OrderedCharacterEncodingFilter;
import org.springframework.core.annotation.AnnotationUtils;
import org.springframework.core.annotation.Order;

/**
 * {@link LocaleResolutionFilter} must run <b>after</b> the filter that sets the
 * request's character encoding.
 *
 * <p>This looks like a triviality and is not. The filter reads {@code lang}
 * with {@code request.getParameter()}, and the first {@code getParameter()}
 * call makes the container parse the request — query string and form body
 * together — fixing the body's charset for the remainder of the request.
 * {@code OrderedCharacterEncodingFilter} is what sets that charset to UTF-8,
 * and it sits at {@code HIGHEST_PRECEDENCE}. While this filter sat there too,
 * the order between them was undefined, and whenever this one won every
 * non-ASCII form field on the admin surface was decoded as ISO-8859-1 — an
 * Arabic name or title posted from a form was stored as mojibake.
 *
 * <p>The behavioural proof is
 * {@code AdminGuideVideosEndToEndTest.addingWritesTheFiveColumnsTheFormCarries},
 * which posts Arabic through a real form. This test exists beside it because
 * that one is attached to a single page: if the page were ever retired, the
 * only guard on a cross-cutting invariant would go with it.
 */
class LocaleResolutionFilterOrderTest {

	@Test
	void itRunsAfterTheCharacterEncodingFilter() {
		Order order = AnnotationUtils.findAnnotation(LocaleResolutionFilter.class, Order.class);
		assertThat(order).as("the ordering is the whole point; it must be declared").isNotNull();
		// Read off an instance rather than a constant: Boot 4 sets the default in
		// the constructor and exposes no DEFAULT_ORDER, and this is the number
		// the running application would actually use.
		int encodingFilterOrder = new OrderedCharacterEncodingFilter().getOrder();
		assertThat(order.value())
				.as("a filter that calls getParameter() must not outrank the encoding filter")
				.isGreaterThan(encodingFilterOrder);
	}

}
