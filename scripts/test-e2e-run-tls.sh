#!/usr/bin/env bash
# Where deploy/e2e/run.sh puts the integration proxy's TLS key, with docker, npm
# and npx stubbed. Nothing else runs run.sh in CI, and shellcheck cannot tell
# where a key lands. These cases are the ways it has gone wrong: a certificate
# under /tmp that a reboot removed, HOME required by profiles with no proxy, a
# relative XDG_STATE_HOME that put the key inside the checkout, a directory
# Docker created for a missing bind-mount source, and a symlink into /tmp that
# went without a warning.
#
# Each case runs a copy of run.sh in a throwaway tree laid out like the
# repository. The key, the generated .env file and the "inside the repository"
# check therefore all concern that tree, never this checkout. Only the warning
# cases need a directory outside /tmp; they use a throwaway one under the user's
# cache directory.
set -uo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
WORK="$(mktemp -d)"
OUTSIDE=""
trap 'chmod -R u+rwx "$WORK" 2>/dev/null; rm -rf "$WORK" ${OUTSIDE:+"$OUTSIDE"}' EXIT
fails=0

REPO="$WORK/repo"
mkdir -p "$REPO/deploy/e2e/node_modules" "$WORK/bin" "$WORK/tmp"
cp "$HERE/../deploy/e2e/run.sh" "$REPO/deploy/e2e/run.sh"

# The stack and the suite are not under test: docker reports a healthy
# application and npm and npx do nothing. openssl is the real one.
cat > "$WORK/bin/docker" <<'STUB'
#!/usr/bin/env bash
[ "${1:-}" = inspect ] && echo healthy
exit 0
STUB
printf '#!/usr/bin/env bash\nexit 0\n' > "$WORK/bin/npm"
cp "$WORK/bin/npm" "$WORK/bin/npx"
chmod +x "$WORK/bin/docker" "$WORK/bin/npm" "$WORK/bin/npx"

rc=0
run() {  # $1=profile, then NAME=value pairs: the whole environment -> $WORK/out, $rc
  local profile="$1"
  shift
  rc=0
  (cd "$REPO/deploy/e2e" && env -i PATH="$WORK/bin:$PATH" TMPDIR="$WORK/tmp" "$@" \
    timeout 120 bash ./run.sh "$profile") >"$WORK/out" 2>&1 || rc=$?
}

check() {  # $1=0 when the case holds, $2=label
  if [ "$1" = 0 ]; then printf '  ok    %s\n' "$2"
  else printf '  FAIL  %s\n' "$2"; sed 's/^/          /' "$WORK/out"; fails=$((fails + 1)); fi
}

no_key_in_repo() {
  [ -z "$(find "$REPO" -name server.key)" ]
}

# 1. Only the integration profile has a proxy, so the others need no HOME.
run local
ok=1; [ "$rc" -eq 0 ] && grep -q 'running the suite' "$WORK/out" && ok=0
check "$ok" "local runs with HOME, XDG_STATE_HOME and E2E_TLS_DIR all unset"

# 2. The default: the user's state directory, private to them.
run integration HOME="$WORK/home"
dir="$WORK/home/.local/state/workin-e2e/tls"
ok=1; [ "$rc" -eq 0 ] && [ -f "$dir/server.key" ] && [ -f "$dir/server.crt" ] && ok=0
check "$ok" "integration writes the key and certificate under HOME/.local/state by default"
ok=1; [ -d "$dir" ] && [ -n "$(find "$dir" -maxdepth 0 -perm 700)" ] && ok=0
check "$ok" "the default directory is readable by its owner only"

# 3. An absolute XDG_STATE_HOME is used.
run integration HOME="$WORK/home-xdg" XDG_STATE_HOME="$WORK/state"
ok=1; [ "$rc" -eq 0 ] && [ -f "$WORK/state/workin-e2e/tls/server.key" ] && [ ! -e "$WORK/home-xdg/.local" ] && ok=0
check "$ok" "an absolute XDG_STATE_HOME replaces HOME/.local/state"

# 4. A relative XDG_STATE_HOME is ignored, as the XDG specification says. Before,
#    it put the key inside the checkout, under the directory run.sh ran from.
run integration HOME="$WORK/home-rel" XDG_STATE_HOME=state
ok=1; [ "$rc" -eq 0 ] && [ -f "$WORK/home-rel/.local/state/workin-e2e/tls/server.key" ] && no_key_in_repo && ok=0
check "$ok" "a relative XDG_STATE_HOME is ignored, and no key is written in the repository"

# 5. With neither HOME nor XDG_STATE_HOME there is no default, so say so.
run integration
ok=1; [ "$rc" -ne 0 ] && grep -q 'HOME is not set' "$WORK/out" && no_key_in_repo && ok=0
check "$ok" "integration without HOME or XDG_STATE_HOME stops and says HOME is not set"

# 6. An explicit E2E_TLS_DIR must be absolute.
run integration HOME="$WORK/home" E2E_TLS_DIR=tls
ok=1; [ "$rc" -ne 0 ] && grep -q 'must be an absolute path' "$WORK/out" \
  && [ ! -e "$REPO/deploy/e2e/tls" ] && no_key_in_repo && ok=0
check "$ok" "a relative E2E_TLS_DIR is refused before anything is created"

# 7. Nor may it be inside the repository, whether named directly or through a
#    symlink.
run integration HOME="$WORK/home" E2E_TLS_DIR="$REPO/deploy/e2e/tls"
ok=1; [ "$rc" -ne 0 ] && grep -q 'inside the repository' "$WORK/out" && no_key_in_repo && ok=0
check "$ok" "an E2E_TLS_DIR inside the repository is refused"
ln -s "$REPO/deploy" "$WORK/deploy-link"
run integration HOME="$WORK/home" E2E_TLS_DIR="$WORK/deploy-link/tls"
ok=1; [ "$rc" -ne 0 ] && grep -q 'inside the repository' "$WORK/out" && no_key_in_repo && ok=0
check "$ok" "an E2E_TLS_DIR reaching the repository through a symlink is refused"

# 8. A directory a reboot clears still works, with a warning, whether it is
#    under /tmp, /var/tmp or TMPDIR as written or only once resolved. This tree
#    is under /tmp itself, which would match first and leave the rest untested,
#    so these cases run from a throwaway directory under the cache directory.
cache="${XDG_CACHE_HOME:-}"
if [ -z "$cache" ] && [ -n "${HOME:-}" ]; then cache="$HOME/.cache"; fi
case "$cache" in /*) ;; *) cache="" ;; esac
if [ -n "$cache" ] && mkdir -p "$cache" 2>/dev/null; then
  OUTSIDE="$(mktemp -d "$cache/test-e2e-run-tls.XXXXXX" 2>/dev/null)"
fi
outside_real=""
if [ -n "$OUTSIDE" ]; then outside_real="$(cd "$OUTSIDE" && pwd -P)/"; fi
case "$outside_real" in
  "" | /tmp/* | /var/tmp/*)
    echo "  skip  the reboot warning: no directory outside /tmp and /var/tmp to run it from" ;;
  *)
    O="$OUTSIDE"
    mkdir -p "$O/tmp" "$O/kept"
    ln -s "$O/tmp" "$O/tmp-link"
    run integration HOME="$WORK/home" TMPDIR="$O/tmp" E2E_TLS_DIR="$O/tmp/tls"
    ok=1; [ "$rc" -eq 0 ] && grep -q 'is cleared on reboot' "$WORK/out" && [ -f "$O/tmp/tls/server.key" ] && ok=0
    check "$ok" "an E2E_TLS_DIR under TMPDIR warns and still gets a certificate"
    run integration HOME="$WORK/home" TMPDIR="$O/tmp/" E2E_TLS_DIR="$O/tmp/tls-slash"
    ok=1; [ "$rc" -eq 0 ] && grep -q 'is cleared on reboot' "$WORK/out" && ok=0
    check "$ok" "a TMPDIR ending in /, as macOS sets it, still warns"
    run integration HOME="$WORK/home" TMPDIR="$O/tmp" E2E_TLS_DIR="$O/tmp-link/tls"
    ok=1; [ "$rc" -eq 0 ] && grep -q 'is cleared on reboot' "$WORK/out" && [ -f "$O/tmp/tls/server.key" ] && ok=0
    check "$ok" "an E2E_TLS_DIR reaching TMPDIR through a symlink warns"
    run integration HOME="$WORK/home" TMPDIR="$O/tmp" E2E_TLS_DIR="$O/kept/../tmp/tls-dotdot"
    ok=1; [ "$rc" -eq 0 ] && grep -q 'is cleared on reboot' "$WORK/out" && [ -f "$O/tmp/tls-dotdot/server.key" ] && ok=0
    check "$ok" "an E2E_TLS_DIR reaching TMPDIR through .. warns"
    run integration HOME="$WORK/home" TMPDIR="$O/tmp-link" E2E_TLS_DIR="$O/tmp/tls-alias"
    ok=1; [ "$rc" -eq 0 ] && grep -q 'is cleared on reboot' "$WORK/out" && ok=0
    check "$ok" "a TMPDIR that is itself a symlink, as macOS's is, still warns"
    run integration HOME="$WORK/home" TMPDIR="$O/tmp" E2E_TLS_DIR="$O/kept/tls"
    ok=1; [ "$rc" -eq 0 ] && ! grep -q 'is cleared on reboot' "$WORK/out" && [ -f "$O/kept/tls/server.key" ] && ok=0
    check "$ok" "an E2E_TLS_DIR outside /tmp, /var/tmp and TMPDIR does not warn" ;;
esac

# 9. What Docker leaves for a missing bind-mount source: every missing level,
#    empty and owned by root. run.sh stops and says what to do, instead of
#    failing in mkdir, cd or chmod, and names no directory for removal. A
#    directory of your own with the wrong mode is repaired, as it always was.
if [ "$(id -u)" = 0 ]; then
  echo "  skip  directories that are not writable: running as root, for whom every directory is"
else
  gone="/workin-e2e-tls-test-$$/tls"
  run integration HOME="$WORK/home" E2E_TLS_DIR="$gone"
  ok=1; [ "$rc" -ne 0 ] && grep -q 'is not a directory of yours' "$WORK/out" \
    && grep -q 'sudo rmdir' "$WORK/out" && ! grep -q 'sudo rmdir "' "$WORK/out" \
    && [ ! -e "$gone" ] && ok=0
  check "$ok" "a directory that cannot be created stops with the advice, not a bare mkdir error"

  run integration HOME="$WORK/home" E2E_TLS_DIR=/usr
  ok=1; [ "$rc" -ne 0 ] && grep -q 'is not a directory of yours' "$WORK/out" \
    && ! grep -q 'sudo rmdir "' "$WORK/out" && ok=0
  check "$ok" "an existing directory that is not yours and not writable stops with the advice"

  mkdir "$WORK/via"
  ln -s /usr "$WORK/via/link"
  run integration HOME="$WORK/home" E2E_TLS_DIR="$WORK/via/link//"
  ok=1; [ "$rc" -ne 0 ] && grep -q 'is not a directory of yours' "$WORK/out" \
    && ! grep -q 'sudo rmdir "' "$WORK/out" && ok=0
  check "$ok" "a symlink to such a directory, with trailing slashes, stops the same way"

  # A shared directory you can write to but do not own is no place for the key,
  # and chmod 700 would fail on it. It gets the advice too.
  if [ -d /var/tmp ] && [ ! -O /var/tmp ] && [ -w /var/tmp ]; then
    run integration HOME="$WORK/home" E2E_TLS_DIR=/var/tmp
    ok=1; [ "$rc" -ne 0 ] && grep -q 'is not a directory of yours' "$WORK/out" \
      && ! grep -q 'Operation not permitted' "$WORK/out" && ok=0
    check "$ok" "a shared directory you can write to but do not own stops with the advice, not a bare chmod error"
  else
    echo "  skip  a shared directory you do not own: /var/tmp is missing, yours, or not writable here"
  fi

  for mode in 555 600; do
    mkdir "$WORK/mine-$mode"
    chmod "$mode" "$WORK/mine-$mode"
    run integration HOME="$WORK/home" E2E_TLS_DIR="$WORK/mine-$mode"
    ok=1; [ "$rc" -eq 0 ] && [ -f "$WORK/mine-$mode/server.key" ] \
      && [ -n "$(find "$WORK/mine-$mode" -maxdepth 0 -perm 700)" ] && ok=0
    check "$ok" "a directory of yours with mode $mode is repaired to 700 and used"
  done
fi

echo
if [ "$fails" -eq 0 ]; then echo "e2e run.sh TLS directory: all cases pass."
else echo "$fails case(s) failed." >&2; fi
exit "$fails"
