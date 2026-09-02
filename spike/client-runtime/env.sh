# Runtime-verification environment, entirely under $HOME -- no root, no system
# packages, nothing installed outside these directories.
#
# Why a sysroot rather than `apt install libgtk-3-dev`: this machine has no
# usable sudo credential, so the four packages flutter doctor asks for are
# provided in userspace instead. cmake and ninja are upstream prebuilt
# binaries; the GTK development headers are the Ubuntu .deb closure
# (`apt-get install --print-uris -y libgtk-3-dev`, 87 packages) extracted with
# `dpkg -x`. The runtime libraries already exist on the host, so the sysroot's
# dangling .so symlinks are repointed at /usr/lib/x86_64-linux-gnu.
export SDK=/home/afaqy/dev/sdk
export SYSROOT=$SDK/sysroot
export PATH="$SDK/flutter/bin:$SDK/tools/cmake/bin:$SDK/tools/ninja-bin:$SDK/tools/bin:$SDK/android/cmdline-tools/latest/bin:$PATH"
export PKG_CONFIG_PATH="$SYSROOT/usr/lib/x86_64-linux-gnu/pkgconfig:$SYSROOT/usr/share/pkgconfig"
export PKG_CONFIG_SYSROOT_DIR="$SYSROOT"
export ANDROID_SDK_ROOT=$SDK/android
export ANDROID_HOME=$SDK/android

# The shim that makes the UNMODIFIED clients reach the local Java backend:
# workin.company -> 127.0.0.1, :443 -> :8443, and the CA bundle Dart reads
# swapped for one containing the local test CA. Scoped to a single process tree.
export WORKIN_HOST=workin.company
export WORKIN_PORT=8443
export WORKIN_CA_BUNDLE=/home/afaqy/dev/runtime-verify/tls/bundle.crt
export WORKIN_SHIM=/home/afaqy/dev/runtime-verify/localnet.so
