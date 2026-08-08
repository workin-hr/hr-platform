package com.workin.backend.schedule;

import java.time.DayOfWeek;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Day-name token parsing ported verbatim from
 * hr-legacy/apis/helpers/schedule_helper.php @ d113204
 * (schedule_parse_days_off_to_dows / schedule_company_weekly_rest_dows),
 * including the intentional double spellings (with and without hamza)
 * for Sunday, Monday, and Wednesday. Shift days_off accepts name
 * tokens only; company weekly_off_days values additionally accept
 * legacy numeric indexes 0=Sunday..6=Saturday.
 */
public final class DaysOffParser {

	private static final Pattern SPLITTER = Pattern.compile("[,،;]+");

	private static final Map<String, DayOfWeek> TOKENS = Map.ofEntries(
			Map.entry("sunday", DayOfWeek.SUNDAY), Map.entry("monday", DayOfWeek.MONDAY),
			Map.entry("tuesday", DayOfWeek.TUESDAY), Map.entry("wednesday", DayOfWeek.WEDNESDAY),
			Map.entry("thursday", DayOfWeek.THURSDAY), Map.entry("friday", DayOfWeek.FRIDAY),
			Map.entry("saturday", DayOfWeek.SATURDAY),
			Map.entry("sun", DayOfWeek.SUNDAY), Map.entry("mon", DayOfWeek.MONDAY),
			Map.entry("tue", DayOfWeek.TUESDAY), Map.entry("wed", DayOfWeek.WEDNESDAY),
			Map.entry("thu", DayOfWeek.THURSDAY), Map.entry("fri", DayOfWeek.FRIDAY),
			Map.entry("sat", DayOfWeek.SATURDAY),
			Map.entry("الأحد", DayOfWeek.SUNDAY), Map.entry("الاحد", DayOfWeek.SUNDAY),
			Map.entry("الإثنين", DayOfWeek.MONDAY), Map.entry("الاثنين", DayOfWeek.MONDAY),
			Map.entry("الثلاثاء", DayOfWeek.TUESDAY),
			Map.entry("الأربعاء", DayOfWeek.WEDNESDAY), Map.entry("الاربعاء", DayOfWeek.WEDNESDAY),
			Map.entry("الخميس", DayOfWeek.THURSDAY),
			Map.entry("الجمعة", DayOfWeek.FRIDAY),
			Map.entry("السبت", DayOfWeek.SATURDAY));

	public static Set<DayOfWeek> parseDaysOff(String daysOff) {
		Set<DayOfWeek> out = new LinkedHashSet<>();
		if (daysOff == null || daysOff.trim().isEmpty()) {
			return out;
		}
		for (String part : SPLITTER.split(daysOff)) {
			DayOfWeek day = TOKENS.get(part.trim().toLowerCase(Locale.ROOT));
			if (day != null) {
				out.add(day);
			}
		}
		return out;
	}

	public static Set<DayOfWeek> parseCompanyRestDays(List<String> values) {
		Set<DayOfWeek> out = new LinkedHashSet<>();
		for (String raw : values) {
			String token = raw == null ? "" : raw.trim();
			if (token.isEmpty()) {
				continue;
			}
			if (token.chars().allMatch(Character::isDigit)) {
				int index = Integer.parseInt(token);
				if (index >= 0 && index <= 6) {
					out.add(fromLegacyIndex(index));
				}
				continue;
			}
			DayOfWeek day = TOKENS.get(token.toLowerCase(Locale.ROOT));
			if (day != null) {
				out.add(day);
			}
		}
		return out;
	}

	/** 0=Sunday..6=Saturday, PHP date('w') -- the legacy wire format. */
	public static int toLegacyIndex(DayOfWeek dow) {
		return dow.getValue() % 7;
	}

	public static DayOfWeek fromLegacyIndex(int legacyIndex) {
		return legacyIndex == 0 ? DayOfWeek.SUNDAY : DayOfWeek.of(legacyIndex);
	}

	public static String englishLabel(DayOfWeek dow) {
		return dow.getDisplayName(java.time.format.TextStyle.FULL, Locale.ENGLISH);
	}

	private DaysOffParser() {
	}

}
