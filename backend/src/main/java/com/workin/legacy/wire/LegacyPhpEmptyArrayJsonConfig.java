package com.workin.legacy.wire;

import java.util.Map;

import tools.jackson.core.JsonGenerator;
import tools.jackson.databind.BeanDescription;
import tools.jackson.databind.BeanProperty;
import tools.jackson.databind.SerializationConfig;
import tools.jackson.databind.SerializationContext;
import tools.jackson.databind.ValueSerializer;
import tools.jackson.databind.module.SimpleModule;
import tools.jackson.databind.ser.ValueSerializerModifier;
import tools.jackson.databind.type.MapType;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

/**
 * Renders an empty structure the way PHP's {@code json_encode} does:
 * {@code []}, not <code>{}</code>.
 *
 * <h2>The difference this closes</h2>
 * <p>PHP has one array type, and {@code json_encode([])} is {@code []}
 * regardless of what the keys would have been. A Java {@code Map} always
 * serialises as an object, so every legacy response that builds a map in a loop
 * answers <code>{}</code> where legacy answers {@code []} the moment the loop
 * adds nothing. The <em>value</em> is the same; the <b>JSON type</b> is not,
 * and a client asking {@code field_errors.length} gets {@code 0} from one and
 * {@code undefined} from the other.
 *
 * <p>Measured against the running legacy PHP on 2026-09-02, three endpoints on
 * the current surface were diverging on exactly this, in three unrelated
 * modules:
 *
 * <pre>
 * employees/analyze_excel     rows[].field_errors   []  vs  {}   (a valid row)
 * attendance/analyze_excel    summary               []  vs  {}   (unknown layout)
 * employees/import_bulk       failed[].data         []  vs  {}   (row sent as {})
 * </pre>
 *
 * <p>Three instances of one rule is why this is a serializer and not three
 * call-site fixes: the next map built in a loop would have been the fourth.
 *
 * <h2>The one exception, and it is explicit</h2>
 * <p>PHP can emit <code>{}</code> only by casting -- {@code (object)[]} -- and
 * a survey of the frozen tree finds that cast in exactly one file:
 * {@code dashboard/stats.php}, on eight keys. Those keys are routed through
 * {@link LegacyPhpArrayJson#encode} instead, which answers
 * {@link LegacyPhpArrayJson#EMPTY_OBJECT} for an empty map and so keeps the
 * cast's shape. Nothing else in the frozen tree constructs {@code stdClass},
 * passes {@code JSON_FORCE_OBJECT}, or implements {@code JsonSerializable}, and
 * PDO is configured {@code FETCH_ASSOC}, so no row arrives as an object either.
 *
 * <h2>Scope</h2>
 * <p>Registered only under {@code phase1-mysql}, for the same reason
 * {@link LegacyPhpNumberJsonConfig} is: on that profile the served surface is
 * the legacy contract, and PHP's rendering is a compatibility obligation rather
 * than a house style. The platform's own API keeps Jackson's defaults.
 */
@Configuration
@Profile("phase1-mysql")
public class LegacyPhpEmptyArrayJsonConfig {

	/**
	 * Wraps the map serializer Jackson would otherwise use, and takes over only
	 * the empty case. A non-empty map is handed to the delegate untouched, so
	 * key order, key serializers and null handling stay Jackson's.
	 *
	 * <p>{@link #resolve} and {@link #createContextual} forward and re-wrap.
	 * They are not optional politeness: Jackson resolves a {@code MapSerializer}
	 * after construction, and a wrapper that swallows those calls leaves the
	 * delegate with a null key serializer, so the first <em>non-empty</em> map
	 * fails with {@code "keySerializer" is null}. Which is to say the wrapper
	 * breaks every case except the one it was written for.
	 */
	private static final class PhpEmptyMapSerializer extends ValueSerializer<Object> {

		private final ValueSerializer<Object> delegate;

		private PhpEmptyMapSerializer(ValueSerializer<Object> delegate) {
			this.delegate = delegate;
		}

		@Override
		public void serialize(Object value, JsonGenerator generator, SerializationContext context) {
			if (value instanceof Map<?, ?> map && map.isEmpty()) {
				generator.writeStartArray();
				generator.writeEndArray();
				return;
			}
			delegate.serialize(value, generator, context);
		}

		@Override
		public void resolve(SerializationContext context) {
			delegate.resolve(context);
		}

		@Override
		@SuppressWarnings("unchecked")
		public ValueSerializer<?> createContextual(SerializationContext context, BeanProperty property) {
			ValueSerializer<?> contextual = delegate.createContextual(context, property);
			if (contextual == delegate) {
				return this;
			}
			return new PhpEmptyMapSerializer((ValueSerializer<Object>) contextual);
		}

	}

	@Bean
	public SimpleModule legacyPhpEmptyArrayModule() {
		SimpleModule module = new SimpleModule("legacy-php-empty-array-rendering");
		module.setSerializerModifier(new ValueSerializerModifier() {
			@Override
			@SuppressWarnings("unchecked")
			public ValueSerializer<?> modifyMapSerializer(
					SerializationConfig config, MapType valueType,
					BeanDescription.Supplier beanDescription, ValueSerializer<?> serializer) {
				return new PhpEmptyMapSerializer((ValueSerializer<Object>) serializer);
			}
		});
		return module;
	}

}
