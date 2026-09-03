package com.workin.backend.platformadmin.mfa;

import java.util.Base64;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TotpSeedCipherTest {

	private static final String KEY = Base64.getEncoder().encodeToString(new byte[32]);

	private final TotpSeedCipher cipher = new TotpSeedCipher(KEY, 1);

	@Test
	void aSeedSurvivesARoundTrip() {
		byte[] seed = Totp.newSeed();

		assertThat(this.cipher.decrypt(this.cipher.encrypt(seed, 7L), 7L)).isEqualTo(seed);
	}

	@Test
	void theSameSeedEncryptsDifferentlyEveryTime() {
		byte[] seed = Totp.newSeed();

		assertThat(this.cipher.encrypt(seed, 7L).ciphertext())
			.as("a repeated nonce in GCM is catastrophic, so each encryption must draw a fresh one")
			.isNotEqualTo(this.cipher.encrypt(seed, 7L).ciphertext());
	}

	@Test
	void aSeedCannotBeMovedToAnotherAdministratorsRow() {
		TotpSeedCipher.Encrypted encrypted = this.cipher.encrypt(Totp.newSeed(), 7L);

		assertThatThrownBy(() -> this.cipher.decrypt(encrypted, 8L))
			.as("without binding, an attacker with write access could bind their own "
					+ "authenticator to someone else's account by copying a row")
			.isInstanceOf(IllegalStateException.class);
	}

	@Test
	void aTamperedCiphertextIsRefusedRatherThanReturningRubbish() {
		TotpSeedCipher.Encrypted encrypted = this.cipher.encrypt(Totp.newSeed(), 7L);
		encrypted.ciphertext()[0] ^= 0x01;

		assertThatThrownBy(() -> this.cipher.decrypt(encrypted, 7L))
			.isInstanceOf(IllegalStateException.class);
	}

	@Test
	void theKeyVersionTravelsWithTheCiphertextSoRotationHasAPath() {
		assertThat(new TotpSeedCipher(KEY, 4).encrypt(Totp.newSeed(), 7L).keyVersion()).isEqualTo(4);
	}

	@Test
	void anUnconfiguredKeyRefusesToHandleASeedRatherThanStoringItInTheClear() {
		TotpSeedCipher unconfigured = new TotpSeedCipher("", 1);

		assertThat(unconfigured.isConfigured()).isFalse();
		assertThatThrownBy(() -> unconfigured.encrypt(Totp.newSeed(), 7L))
			.isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("not configured");
	}

	@Test
	void aKeyOfTheWrongLengthIsRejectedAtConstruction() {
		String shortKey = Base64.getEncoder().encodeToString(new byte[16]);

		assertThatThrownBy(() -> new TotpSeedCipher(shortKey, 1))
			.isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("32 bytes");
	}

}
