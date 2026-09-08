# Runtime-verification environment. Nothing is installed outside these
# directories and nothing in /etc is modified, so removal is deleting them.
#
# Every path is derived from $HOME and this file's own location: hard-coding one
# developer's home made the documented commands unrunnable for anyone else.
# Override WORKIN_SDK or WORKIN_WORK to relocate either tree.
_env_dir="$(cd "$(dirname "${BASH_SOURCE[0]:-$0}")" && pwd)"

export SDK="${WORKIN_SDK:-$HOME/dev/sdk}"
export SYSROOT="$SDK/sysroot"
# The working tree that holds the client build copies, the local CA and the
# compiled shim. Defaults next to the SDK; falls back to this directory when the
# repository copy is being used directly.
export WORKIN_WORK="${WORKIN_WORK:-$HOME/dev/runtime-verify}"

export PATH="$SDK/flutter/bin:$SDK/tools/cmake/bin:$SDK/tools/ninja-bin:$SDK/tools/bin:$SDK/android/cmdline-tools/latest/bin:$SDK/android/platform-tools:$SDK/android/emulator:$PATH"
export PKG_CONFIG_PATH="$SYSROOT/usr/lib/x86_64-linux-gnu/pkgconfig:$SYSROOT/usr/share/pkgconfig"
export PKG_CONFIG_SYSROOT_DIR="$SYSROOT"
export ANDROID_SDK_ROOT="$SDK/android"
export ANDROID_HOME="$SDK/android"

# The shim that makes the UNMODIFIED clients reach the local backend:
# workin.company -> 127.0.0.1, :443 -> :8443 on loopback only, and the CA bundle
# Dart reads swapped for one containing the local test CA.
export WORKIN_HOST="${WORKIN_HOST:-workin.company}"
export WORKIN_PORT="${WORKIN_PORT:-8443}"
export WORKIN_CA_BUNDLE="${WORKIN_CA_BUNDLE:-$WORKIN_WORK/tls/bundle.crt}"
export WORKIN_SHIM="${WORKIN_SHIM:-$WORKIN_WORK/localnet.so}"
unset _env_dir
