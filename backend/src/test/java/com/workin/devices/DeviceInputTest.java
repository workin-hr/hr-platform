package com.workin.devices;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class DeviceInputTest {

	@Test
	void aSerialNumberMustBeSomethingATerminalCouldPresent() {
		assertThat(DeviceInput.isValidSerialNumber("BOCK200961014")).isTrue();
		assertThat(DeviceInput.isValidSerialNumber("CJ-1234_56.78")).isTrue();

		assertThat(DeviceInput.isValidSerialNumber(null)).isFalse();
		assertThat(DeviceInput.isValidSerialNumber("")).isFalse();
		assertThat(DeviceInput.isValidSerialNumber(" ")).isFalse();
		assertThat(DeviceInput.isValidSerialNumber("-leading-punctuation")).isFalse();
		assertThat(DeviceInput.isValidSerialNumber("has space")).isFalse();
		assertThat(DeviceInput.isValidSerialNumber("has\r\nnewline")).isFalse();
		assertThat(DeviceInput.isValidSerialNumber("x'; DROP TABLE attendance_devices--")).isFalse();
		// One character past the column: accepting it would let the non-strict
		// database truncate two different serials onto one registry key.
		assertThat(DeviceInput.isValidSerialNumber("A".repeat(64))).isTrue();
		assertThat(DeviceInput.isValidSerialNumber("A".repeat(65))).isFalse();
	}

	@Test
	void aStampMustBeDigitsBecauseItIsEchoedBackToTheDevice() {
		assertThat(DeviceInput.isValidStamp("4711")).isTrue();
		assertThat(DeviceInput.isValidStamp("0")).isTrue();

		assertThat(DeviceInput.isValidStamp(null)).isFalse();
		assertThat(DeviceInput.isValidStamp("")).isFalse();
		assertThat(DeviceInput.isValidStamp("-1")).isFalse();
		assertThat(DeviceInput.isValidStamp("9".repeat(33))).isFalse();
		// The injection this rule exists for: extra lines in the handshake.
		assertThat(DeviceInput.isValidStamp("0\r\nTransFlag=TransData EnrollFP")).isFalse();
	}

	@Test
	void boundedStripsTrimsAndNeverReturnsAnEmptyString() {
		assertThat(DeviceInput.bounded(null, 10)).isNull();
		assertThat(DeviceInput.bounded("   ", 10)).isNull();
		assertThat(DeviceInput.bounded("  value  ", 10)).isEqualTo("value");
		assertThat(DeviceInput.bounded("abcdefghijkl", 4)).isEqualTo("abcd");
	}

	@Test
	void forLogNeutralisesControlCharactersAndBoundsLength() {
		assertThat(DeviceInput.forLog("plain", 20)).isEqualTo("plain");
		assertThat(DeviceInput.forLog(null, 20)).isEmpty();
		assertThat(DeviceInput.forLog("a\r\nWARN forged line", 40)).isEqualTo("a..WARN forged line");
		assertThat(DeviceInput.forLog("x".repeat(500), 10)).hasSize(10);
	}
}
