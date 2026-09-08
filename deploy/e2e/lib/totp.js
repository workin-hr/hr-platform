import { createHmac } from 'node:crypto';

// RFC 6238, SHA-1, 6 digits, 30-second step -- the parameters
// PlatformAdminMfaService issues seeds for. Reimplemented rather than pulled
// from npm: it is eleven lines, and a test dependency that generates
// authentication codes is a dependency worth not having.

function base32Decode(seed) {
	const alphabet = 'ABCDEFGHIJKLMNOPQRSTUVWXYZ234567';
	let bits = '';
	for (const character of seed.replace(/=+$/, '').toUpperCase()) {
		const index = alphabet.indexOf(character);
		if (index < 0) {
			throw new Error(`not base32: ${character}`);
		}
		bits += index.toString(2).padStart(5, '0');
	}
	const bytes = [];
	for (let at = 0; at + 8 <= bits.length; at += 8) {
		bytes.push(parseInt(bits.slice(at, at + 8), 2));
	}
	return Buffer.from(bytes);
}

/** @param stepOffset shift the window, to exercise drift tolerance. */
export function totp(seed, stepOffset = 0) {
	const step = Math.floor(Date.now() / 30_000) + stepOffset;
	const counter = Buffer.alloc(8);
	counter.writeBigUInt64BE(BigInt(step));
	const digest = createHmac('sha1', base32Decode(seed)).update(counter).digest();
	const offset = digest[digest.length - 1] & 0x0f;
	const binary = digest.readUInt32BE(offset) & 0x7fffffff;
	return String(binary % 1_000_000).padStart(6, '0');
}

/**
 * Waits for the next 30-second window.
 *
 * A TOTP code is single-use: the service records the time step it accepted and
 * refuses it again. Two consecutive prompts inside one window would otherwise
 * fail on the second, and it would look like the code was wrong.
 */
export async function nextWindow() {
	const msIntoStep = Date.now() % 30_000;
	await new Promise((resolve) => setTimeout(resolve, 30_000 - msIntoStep + 500));
}
