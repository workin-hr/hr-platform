package com.workin.legacy.spreadsheet;

/**
 * {@code detect_spreadsheet_upload_format()}
 * ({@code hr-legacy/apis/helpers/xlsx_parser.php:57-79}): the format comes from
 * the first bytes, never from the filename.
 */
public enum LegacySpreadsheetFormat {

	XLSX,
	XLS,
	CSV,
	EMPTY,
	UNKNOWN;

	/** {@code PK} means a ZIP container, the OLE2 signature means a legacy .xls, anything else is treated as CSV. */
	public static LegacySpreadsheetFormat detect(byte[] content) {
		if (content == null) {
			return UNKNOWN;
		}
		if (content.length == 0) {
			return EMPTY;
		}
		if (content.length >= 2 && content[0] == 'P' && content[1] == 'K') {
			return XLSX;
		}
		byte[] ole2 = {(byte) 0xD0, (byte) 0xCF, 0x11, (byte) 0xE0, (byte) 0xA1, (byte) 0xB1, 0x1A, (byte) 0xE1};
		if (content.length >= ole2.length) {
			boolean matches = true;
			for (int index = 0; index < ole2.length; index++) {
				if (content[index] != ole2[index]) {
					matches = false;
					break;
				}
			}
			if (matches) {
				return XLS;
			}
		}
		return CSV;
	}

}
