# Verifying The Signing Secret Before Cutover

Closes the checkable part of **R-024**. Phase 1's zero-client-change
property (**D-111**) depends on the Java and PHP deployments signing
HS256 tokens with the **same** secret. Nothing has ever compared them.

If they differ:

- **at cutover**, every live PHP-issued session becomes invalid at once —
  a forced logout of the entire active user base;
- **at rollback**, every session Java issued since cutover is invalidated
  too — the same population logged out a second time.

That inverts the phase's central assumption. Phase 1 was accepted on the
strength of a cheap rollback; a secret mismatch makes the rollback the
second-most disruptive event of the release. If the secrets match, both
transitions are invisible to users.

This check costs about a minute, and it is the difference between
discovering the problem now and discovering it from a support queue.

## The fingerprint

Neither secret may be printed, pasted into a ticket, or read aloud to be
compared. So both sides print a *fingerprint* instead:

```text
HMAC-SHA256(secret, "workin-jwt-secret-fingerprint-v1"), first 16 hex characters
```

This is the construction an SSH host key fingerprint uses, and it is safe
to log for the same reason: HMAC is a pseudo-random function, so the
digest reveals nothing about the key, and 64 bits is far too short to
serve as a signature while being long enough that two different secrets
will not collide in practice.

**Java** prints it at every startup:

```text
JWT signing secret fingerprint: <16 hex chars> -- must equal the PHP deployment's
```

**PHP** — run this on the host serving the legacy API, against the same
`constants.php` that deployment loads:

```bash
php -r 'require "apis/config/constants.php";
        echo substr(hash_hmac("sha256", "workin-jwt-secret-fingerprint-v1", AppConfig::JWT_SECRET), 0, 16), "\n";'
```

> Mind the argument order. PHP's `hash_hmac($algo, $data, $key)` takes the
> **message second and the key third**, the opposite way round from most
> APIs. Swapping them yields a stable, plausible, entirely wrong value —
> and it will differ from Java's, so you would "discover" a mismatch that
> does not exist and go looking for the wrong problem.
> `JwtSecretFingerprintTest` pins the Java side to an independently
> computed vector for exactly this reason.

**Equal fingerprints mean the secrets are identical.** Unequal means stop
— do not begin the cutover.

## Then prove it end to end

The fingerprint proves the configured values match. It does not prove
that both stacks *use* that value the same way — a difference in encoding
or claim handling would pass the fingerprint and still reject the token.
Before the cutover window, run the exchange R-024 asks for:

1. Mint an access token from Java (`POST /apis/api/auth/login_employee`).
2. Present it to **PHP** on an authenticated endpoint. Expect the same
   success a PHP-issued token gets.
3. Reverse the direction: mint from PHP, present to Java.

Either rejection means the deployments disagree despite matching
fingerprints. Stop and find out why.

Wire compatibility either side of the secret is already pinned by
`LegacyPhpJwtWireCompatibilityTest` (the codec) and
`LegacyLoginEndToEndTest` (real HTTP through the production filter
chain), both building expectations from an independent reimplementation
of `jwtEncode()` — so a failure here is a configuration difference
between the two deployments, not a defect in the port.

## If a mismatch is found after cutover

Rolling back does **not** restore the logged-out sessions; that
population has already been forced to re-authenticate, and rolling back
forces it again. The decision becomes forward-fix versus rollback on
other grounds, and a user communication is required either way — see
`docs/operations/customer-communication.md`.

## Where the secrets live

| Stack | Location |
|---|---|
| Java | `app.jwt.secret`, supplied by the deployment's secret mechanism |
| PHP | `AppConfig::JWT_SECRET` in `apis/config/constants.php` |

`constants.php` is git-ignored in `hr-legacy` and holds real credentials.
It must not be copied into this repository, quoted in a ticket, or pasted
into a chat — the fingerprint exists so that it never has to be.
