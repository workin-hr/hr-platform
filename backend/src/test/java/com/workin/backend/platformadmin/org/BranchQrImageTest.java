package com.workin.backend.platformadmin.org;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.HashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.google.zxing.BinaryBitmap;
import com.google.zxing.RGBLuminanceSource;
import com.google.zxing.common.HybridBinarizer;
import com.google.zxing.qrcode.QRCodeReader;

import org.junit.jupiter.api.Test;

/**
 * The check-in QR is drawn here and says exactly the stored code (D-285, #298).
 *
 * <p>Decoded by ZXing, a second implementation, from the pixels the SVG
 * describes -- so this proves what a phone's scanner would read, not that the
 * encoder agrees with itself.
 */
class BranchQrImageTest {

	private static final Pattern MODULE = Pattern.compile("M(\\d+),(\\d+)h1v1h-1z");

	private static final Pattern VIEW_BOX = Pattern.compile("viewBox=\"0 0 (\\d+) (\\d+)\"");

	@Test
	void theImageDecodesToTheStoredCode() throws Exception {
		// The shape generate_qr stores: bin2hex(random_bytes(16)).
		String code = "3f9a0c1d2e4b5a6978f0e1d2c3b4a596";
		assertThat(decode(BranchQrImage.dataUri(code))).isEqualTo(code);
	}

	@Test
	void anyStoredTextDecodesAsWritten() throws Exception {
		// qr_code is a free VARCHAR in the vendored schema, so a row written by
		// something other than generate_qr must still come back unchanged.
		for (String code : new String[] {"A", "branch-7 check in", "كود الفرع 12"}) {
			assertThat(decode(BranchQrImage.dataUri(code))).as(code).isEqualTo(code);
		}
	}

	@Test
	void aMissingOrBlankCodeDrawsNothing() {
		assertThat(BranchQrImage.dataUri(null)).isEmpty();
		assertThat(BranchQrImage.dataUri("")).isEmpty();
		assertThat(BranchQrImage.dataUri("   ")).as("legacy's own blank-is-no-image rule").isEmpty();
	}

	@Test
	void theImageNamesNoHostAndCarriesTheQuietZone() {
		String svg = BranchQrImage.svg("3f9a0c1d2e4b5a6978f0e1d2c3b4a596");
		assertThat(svg.replace("xmlns=\"http://www.w3.org/2000/svg\"", ""))
				.as("the namespace is an identifier, not a fetch; nothing else may name a host")
				.doesNotContain("://");
		Matcher box = VIEW_BOX.matcher(svg);
		assertThat(box.find()).isTrue();
		int side = Integer.parseInt(box.group(1));
		int minimum = side;
		int maximum = 0;
		Matcher module = MODULE.matcher(svg);
		while (module.find()) {
			int x = Integer.parseInt(module.group(1));
			int y = Integer.parseInt(module.group(2));
			minimum = Math.min(minimum, Math.min(x, y));
			maximum = Math.max(maximum, Math.max(x, y));
		}
		assertThat(minimum).as("four blank modules before the symbol").isEqualTo(4);
		assertThat(maximum).as("and four after it").isEqualTo(side - 5);
	}

	/** Rasterises the SVG's modules at four pixels each and reads it back. */
	private static String decode(String dataUri) throws Exception {
		String prefix = "data:image/svg+xml;base64,";
		assertThat(dataUri).startsWith(prefix);
		String svg = new String(Base64.getDecoder().decode(dataUri.substring(prefix.length())),
				StandardCharsets.UTF_8);
		Matcher box = VIEW_BOX.matcher(svg);
		assertThat(box.find()).as("a square viewBox").isTrue();
		int side = Integer.parseInt(box.group(1));
		Set<Long> dark = new HashSet<>();
		Matcher module = MODULE.matcher(svg);
		while (module.find()) {
			dark.add(Long.parseLong(module.group(1)) * 100_000 + Long.parseLong(module.group(2)));
		}
		assertThat(dark).isNotEmpty();
		int scale = 4;
		int pixels = side * scale;
		int[] argb = new int[pixels * pixels];
		for (int y = 0; y < pixels; y++) {
			for (int x = 0; x < pixels; x++) {
				boolean on = dark.contains((long) (x / scale) * 100_000 + y / scale);
				argb[y * pixels + x] = on ? 0xFF000000 : 0xFFFFFFFF;
			}
		}
		BinaryBitmap bitmap = new BinaryBitmap(new HybridBinarizer(
				new RGBLuminanceSource(pixels, pixels, argb)));
		return new QRCodeReader().decode(bitmap).getText();
	}
}
