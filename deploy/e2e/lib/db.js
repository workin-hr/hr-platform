import { execFileSync } from 'node:child_process';

// The database is reached through `docker exec`, not a driver, on purpose: this
// suite has no dependencies beyond Playwright, and every query here is fixture
// setup or an assertion about what a request PERSISTED -- which is the half a
// response body cannot show you. An endpoint can answer correctly and write the
// wrong row.
const CONTAINER = process.env.E2E_DB_CONTAINER ?? 'workin-integration-db-1';
const DATABASE = process.env.E2E_DB_NAME ?? 'workin';
const USER = process.env.E2E_DB_USER ?? 'workin';
const PASSWORD = process.env.E2E_DB_PASSWORD ?? '';

function run(sql, extraArgs = []) {
	const args = [
		'exec', '-i', CONTAINER,
		'mariadb', `-u${USER}`, `-p${PASSWORD}`, DATABASE,
		...extraArgs, '-e', sql,
	];
	return execFileSync('docker', args, { encoding: 'utf8', maxBuffer: 32 * 1024 * 1024 })
		// MariaDB prints this to stdout on every invocation with -p.
		.split('\n').filter((line) => !line.includes('Using a password on the command line'))
		.join('\n');
}

/** One scalar, or null when the query selected no row. */
export function scalar(sql) {
	const out = run(sql, ['-N', '-B']).trim();
	return out === '' || out === 'NULL' ? null : out;
}

/** Rows as objects, keyed by column name. */
export function rows(sql) {
	const lines = run(sql, ['-B']).trim().split('\n').filter(Boolean);
	if (lines.length < 1) {
		return [];
	}
	const columns = lines[0].split('\t');
	return lines.slice(1).map((line) => Object.fromEntries(
		line.split('\t').map((value, index) => [columns[index], value === 'NULL' ? null : value])));
}

/** A statement whose result is not read. */
export function exec(sql) {
	run(sql);
}

/**
 * Every column of one row, as a single string.
 *
 * Used to prove a REFUSED request wrote nothing: capture before, capture after,
 * compare. Checking a status code only proves the caller was told no.
 */
export function rowFingerprint(table, id) {
	const [row] = rows(`SELECT * FROM \`${table}\` WHERE id = ${Number(id)}`);
	return row ? JSON.stringify(row) : null;
}
