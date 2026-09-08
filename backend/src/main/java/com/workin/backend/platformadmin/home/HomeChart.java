package com.workin.backend.platformadmin.home;

import java.util.List;

/**
 * {@code home_chart_kv()}: one chart's labels and values, already paired.
 *
 * <p>The values are rendered into a {@code <script type="application/json">}
 * block and read by the page's own script, so they are numbers and strings and
 * nothing else -- no dates, no money formatting, no HTML. Anything that needs
 * to look like a label is already one by the time it gets here.
 */
public record HomeChart(List<String> labels, List<Double> values) {

	public static final HomeChart EMPTY = new HomeChart(List.of(), List.of());

	public HomeChart {
		labels = List.copyOf(labels);
		values = List.copyOf(values);
	}

	public boolean isEmpty() {
		return this.labels.isEmpty();
	}

	/**
	 * A new chart with each label passed through {@code translate}.
	 *
	 * <p>The gender and age series come out of SQL as keys -- {@code twenties},
	 * {@code unknown} -- because grouping on a translated string would group
	 * differently per language.
	 */
	public HomeChart translateLabels(java.util.function.UnaryOperator<String> translate) {
		return new HomeChart(this.labels.stream().map(translate).toList(), this.values);
	}

	/**
	 * The labels as a JSON array.
	 *
	 * <p>Rendered into a {@code data-} attribute, where JTE's HTML escaping is
	 * the outer layer and this is the inner one -- so a company named
	 * {@code "} or {@code </script>} is a string in an array and nothing else.
	 * Hand-written rather than pulled from Jackson because it serialises two
	 * shapes, both of them known.
	 */
	public String labelsJson() {
		StringBuilder json = new StringBuilder("[");
		for (int at = 0; at < this.labels.size(); at++) {
			json.append(at == 0 ? "" : ",");
			quote(json, this.labels.get(at));
		}
		return json.append(']').toString();
	}

	/** The values as a JSON array. Doubles, so no quoting question arises. */
	public String valuesJson() {
		StringBuilder json = new StringBuilder("[");
		for (int at = 0; at < this.values.size(); at++) {
			json.append(at == 0 ? "" : ",").append(this.values.get(at));
		}
		return json.append(']').toString();
	}

	private static void quote(StringBuilder json, String value) {
		json.append('"');
		for (int at = 0; at < value.length(); at++) {
			char character = value.charAt(at);
			switch (character) {
				case '"' -> json.append("\\\"");
				case '\\' -> json.append("\\\\");
				case '\n' -> json.append("\\n");
				case '\r' -> json.append("\\r");
				case '\t' -> json.append("\\t");
				default -> {
					if (character < 0x20) {
						json.append(String.format("\\u%04x", (int) character));
					}
					else {
						json.append(character);
					}
				}
			}
		}
		json.append('"');
	}

}
