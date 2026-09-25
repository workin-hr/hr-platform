package com.workin.backend.platformadmin.org;

import static org.assertj.core.api.Assertions.assertThat;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.geom.AffineTransform;
import java.awt.geom.Path2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.xml.parsers.DocumentBuilderFactory;

import com.google.zxing.BinaryBitmap;
import com.google.zxing.RGBLuminanceSource;
import com.google.zxing.Result;
import com.google.zxing.ResultMetadataType;
import com.google.zxing.common.HybridBinarizer;
import com.google.zxing.qrcode.QRCodeReader;

import io.nayuki.qrcodegen.QrCode;

import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

/**
 * The check-in QR is drawn here and says exactly the stored code (D-285, #298).
 *
 * <p>The SVG is parsed as XML and painted as it says -- its background
 * {@code <rect>}, its path, each in its own {@code fill} -- then decoded by
 * ZXing, a second implementation, with no hint to try an inverted image. So a
 * missing background, swapped colours or a lost quiet zone each fail here as
 * they would at a phone. A transposed matrix still decodes, because a scanner
 * reads a mirrored symbol too; the painted modules are therefore also compared
 * with the encoder's own matrix, cell by cell.
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
		// Legacy URL-encoded the code as stored, surrounding spaces included.
		for (String code : new String[] {"A", "branch-7 check in", "كود الفرع 12", " lead", "trail "}) {
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

	@Test
	void theModulesAreDrawnWhereTheEncoderPutThem() throws Exception {
		String code = "3f9a0c1d2e4b5a6978f0e1d2c3b4a596";
		QrCode expected = QrCode.encodeText(code, QrCode.Ecc.MEDIUM);
		BufferedImage image = render(BranchQrImage.svg(code), 1);
		assertThat(image.getWidth()).as("the symbol plus four modules each side")
				.isEqualTo(expected.size + 8);
		for (int y = 0; y < expected.size; y++) {
			for (int x = 0; x < expected.size; x++) {
				assertThat(isDark(image.getRGB(x + 4, y + 4)))
						.as("module (%d, %d) -- not transposed, not inverted", x, y)
						.isEqualTo(expected.getModule(x, y));
			}
		}
	}

	@Test
	void theStoredCodeGetsAtLeastMediumCorrection() throws Exception {
		// qrcodegen raises the level while the symbol keeps its version, so a
		// generate_qr code -- 32 bytes, version 3 -- is read back at quartile.
		Result result = read(BranchQrImage.svg("3f9a0c1d2e4b5a6978f0e1d2c3b4a596"));
		assertThat(result.getResultMetadata().get(ResultMetadataType.ERROR_CORRECTION_LEVEL))
				.isEqualTo("Q");
	}

	private static String decode(String dataUri) throws Exception {
		String prefix = "data:image/svg+xml;base64,";
		assertThat(dataUri).startsWith(prefix);
		return read(new String(Base64.getDecoder().decode(dataUri.substring(prefix.length())),
				StandardCharsets.UTF_8)).getText();
	}

	private static Result read(String svg) throws Exception {
		BufferedImage image = render(svg, 4);
		int[] argb = image.getRGB(0, 0, image.getWidth(), image.getHeight(), null, 0, image.getWidth());
		return new QRCodeReader().decode(new BinaryBitmap(new HybridBinarizer(
				new RGBLuminanceSource(image.getWidth(), image.getHeight(), argb))));
	}

	/**
	 * Paints the SVG at {@code scale} pixels per unit: each {@code <rect>} and
	 * {@code <path>} in document order, in its own {@code fill}, over a canvas
	 * that is transparent -- which a scanner reads as dark -- where nothing is
	 * painted. Only the path commands a module grid needs are understood, and
	 * anything else fails rather than being skipped.
	 */
	private static BufferedImage render(String svg, int scale) throws Exception {
		Document document = DocumentBuilderFactory.newInstance().newDocumentBuilder()
				.parse(new ByteArrayInputStream(svg.getBytes(StandardCharsets.UTF_8)));
		Element root = document.getDocumentElement();
		Matcher box = VIEW_BOX.matcher(svg);
		assertThat(box.find()).as("a square viewBox").isTrue();
		int side = Integer.parseInt(box.group(1));
		assertThat(Integer.parseInt(box.group(2))).isEqualTo(side);
		BufferedImage image = new BufferedImage(side * scale, side * scale, BufferedImage.TYPE_INT_ARGB);
		Graphics2D g = image.createGraphics();
		g.setTransform(AffineTransform.getScaleInstance(scale, scale));
		NodeList children = root.getChildNodes();
		for (int i = 0; i < children.getLength(); i++) {
			if (!(children.item(i) instanceof Element element)) {
				continue;
			}
			g.setColor(Color.decode(sixDigit(element.getAttribute("fill"))));
			switch (element.getTagName()) {
				case "rect" -> {
					assertThat(element.getAttribute("width")).isEqualTo("100%");
					assertThat(element.getAttribute("height")).isEqualTo("100%");
					g.fillRect(0, 0, side, side);
				}
				case "path" -> g.fill(path(element.getAttribute("d")));
				default -> throw new AssertionError("an element this renderer cannot paint: "
						+ element.getTagName());
			}
		}
		g.dispose();
		return image;
	}

	private static final Pattern PATH_COMMAND = Pattern.compile("([MhvHVz])([-\\d.,]*)");

	private static Path2D path(String d) {
		Path2D.Double path = new Path2D.Double();
		Matcher command = PATH_COMMAND.matcher(d);
		int consumed = 0;
		while (command.find()) {
			assertThat(command.start()).as("an unread path command at " + consumed).isEqualTo(consumed);
			consumed = command.end();
			String argument = command.group(2);
			switch (command.group(1)) {
				case "M" -> {
					String[] xy = argument.split(",");
					path.moveTo(Double.parseDouble(xy[0]), Double.parseDouble(xy[1]));
				}
				case "h" -> path.lineTo(path.getCurrentPoint().getX() + Double.parseDouble(argument),
						path.getCurrentPoint().getY());
				case "v" -> path.lineTo(path.getCurrentPoint().getX(),
						path.getCurrentPoint().getY() + Double.parseDouble(argument));
				case "H" -> path.lineTo(Double.parseDouble(argument), path.getCurrentPoint().getY());
				case "V" -> path.lineTo(path.getCurrentPoint().getX(), Double.parseDouble(argument));
				case "z" -> path.closePath();
				default -> throw new AssertionError(command.group(1));
			}
		}
		assertThat(consumed).as("the whole path was read").isEqualTo(d.length());
		return path;
	}

	private static String sixDigit(String fill) {
		assertThat(fill).as("a fill this renderer can read").matches("#[0-9a-fA-F]{3}|#[0-9a-fA-F]{6}");
		if (fill.length() == 4) {
			return "#" + fill.charAt(1) + fill.charAt(1) + fill.charAt(2) + fill.charAt(2)
					+ fill.charAt(3) + fill.charAt(3);
		}
		return fill;
	}

	private static boolean isDark(int argb) {
		int alpha = argb >>> 24;
		int red = (argb >> 16) & 0xFF;
		return alpha < 128 || red < 128;
	}
}
