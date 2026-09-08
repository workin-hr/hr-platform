package com.workin.backend.platformadmin.hr;

import java.time.LocalDateTime;
import java.util.function.Function;

/**
 * One row of the recent-activity feed
 * ({@code activities_fetch_list()}, {@code pages/home/home_service.php}).
 *
 * <p>Two different tables arrive here through a {@code UNION ALL} -- an
 * attendance punch and a request -- so half the fields are null for any given
 * row and which half depends on {@link #kind()}.
 *
 * @param kind {@code attendance} or {@code request}
 * @param at the moment the feed sorts and displays by: an attendance row's
 *     check-out if it has one and its check-in otherwise, a request's
 *     creation time
 */
public record Activity(
		String kind,
		long rowId,
		String empCode,
		String employeeName,
		LocalDateTime at,
		LocalDateTime checkIn,
		LocalDateTime checkOut,
		String status,
		String requestTypeName) {

	public boolean attendance() {
		return "attendance".equals(this.kind);
	}

	/** A punch with a check-out is a departure; without one it is an arrival. */
	private boolean departure() {
		return attendance() && this.checkOut != null;
	}

	/** {@code $present['badge_class']}, which the stylesheet keys off. */
	public String badgeClass() {
		if (!attendance()) {
			return "request";
		}
		return departure() ? "departure" : "checkin";
	}

	/** {@code $present['badge']}. */
	public String badgeKey() {
		if (!attendance()) {
			return "activity_request";
		}
		return departure() ? "activity_departure" : "activity_checkin";
	}

	/** {@code $present['name']}, with legacy's em dash for a blank name. */
	public String name() {
		return this.employeeName == null || this.employeeName.isBlank()
				? "—" : this.employeeName.trim();
	}

	/**
	 * {@code $present['detail']}.
	 *
	 * <p>Takes the translator because the three messages it needs carry PHP's
	 * {@code %s} verbatim -- {@code "Check-out at %s"}, {@code "Request: %s"} --
	 * and are the only three in the catalogue that do. Passing them through
	 * Spring's {@code MessageSource} arguments would leave the {@code %s} in
	 * the output and silently drop the value, so they are formatted with
	 * {@link String#format} here, where a test can pin it.
	 */
	public String detail(Function<String, String> translate, String lang) {
		if (attendance()) {
			return String.format(
					translate.apply(departure() ? "activity_departure_at" : "activity_checkin_at"),
					time(occurredAt(), lang));
		}
		String type = this.requestTypeName == null ? "" : this.requestTypeName.trim();
		return type.isEmpty()
				? translate.apply("activity_request")
				: String.format(translate.apply("activity_request_of"), type);
	}

	/**
	 * {@code home_activity_occurred_at()}: check-out, else check-in, else the
	 * union's own {@code at}.
	 */
	public LocalDateTime occurredAt() {
		if (attendance()) {
			return this.checkOut != null ? this.checkOut
					: (this.checkIn != null ? this.checkIn : this.at);
		}
		return this.at;
	}

	/**
	 * {@code home_format_time()}, which is <b>language-dependent</b> and is not
	 * a 24-hour clock in either language.
	 *
	 * <p>English is PHP's {@code date('g:i A')}: a 12-hour hour with no leading
	 * zero, and an upper-case meridiem. Arabic is the same clock with
	 * {@code ص} and {@code م}. Midnight and noon both render as 12, which is
	 * what {@code $h12 === 0} handles there.
	 */
	static String time(LocalDateTime moment, String lang) {
		if (moment == null) {
			return "—";
		}
		int hour = moment.getHour();
		int hour12 = hour % 12 == 0 ? 12 : hour % 12;
		String minute = String.format("%02d", moment.getMinute());
		if ("ar".equals(lang)) {
			return hour12 + ":" + minute + " " + (hour < 12 ? "ص" : "م");
		}
		return hour12 + ":" + minute + " " + (hour < 12 ? "AM" : "PM");
	}

	/**
	 * {@code hr_format_datetime_cell()}: the timestamp truncated to sixteen
	 * characters, which is a 24-hour {@code YYYY-MM-DD HH:MM}. A different
	 * function from the one above and a different clock -- the row's time
	 * column and the sentence beside it genuinely disagree in legacy, and the
	 * port keeps both.
	 */
	public String atCell() {
		if (this.at == null) {
			return "—";
		}
		String text = this.at.toString().replace("T", " ");
		return text.length() >= 16 ? text.substring(0, 16) : text;
	}

	public String code() {
		return this.empCode == null || this.empCode.isBlank() ? "—" : this.empCode;
	}

}
