# Client runtime verification

The **real** Flutter clients, built and executed against the local Java backend,
with their source and their hardcoded URLs unchanged. This is the layer
`spike/client-contract` explicitly is not: that one reasons about parsers, this
one runs the application.

Status is reported as three separate numbers, never merged:

| | |
|---|---|
| static contract verified | desktop 89 contracts, mobile 32 — see `spike/client-contract` |
| **desktop runtime verified** | **yes** — see below |
| **mobile runtime verified** | **not yet** |

## What the clients were not allowed to have done to them

- no source change, no URL change, no request-shape change;
- the pinned submodules are never built in place. `flutter pub get` rewrites
  `pubspec.lock` and `analysis_options.yaml`, so the build runs from a copy and
  the submodule is left clean. The copy is verified byte-identical: every
  `lib/**/*.dart` hashes the same and `baseUrl` is still
  `https://workin.company/apis/api/`.

## How `workin.company` reaches the local backend without root

This machine has no usable sudo credential, so `/etc/hosts`, the system trust
store and port 443 are all unavailable. Everything below is therefore per
process and lives under `$HOME`; there is nothing in `/etc` to undo.

`localnet.c` is an `LD_PRELOAD` shim with three interceptions, each the minimum
needed:

| call | rewrite | why |
|---|---|---|
| `getaddrinfo` | `workin.company` → `127.0.0.1` | the client's URL is fixed |
| `connect` | `127.0.0.1:443` → `:8443` | binding 443 needs root |
| `open`/`openat`/`open64`/`openat64`/`fopen`/`fopen64` | `/etc/ssl/certs/ca-certificates.crt` → local bundle | Dart reads that exact path and ignores `SSL_CERT_FILE` |

The large-file variants matter: intercepting `open`/`openat` alone worked for
`dart run` and **not** for the Flutter app, which reaches the bundle through
`open64`/`openat64`/`fopen64` — every request failed the TLS handshake until
those were added. `nm -D` on `libflutter_linux_gtk.so` confirms which it imports.

`tls-proxy.py` terminates TLS on 8443 with a certificate for `workin.company`
signed by a local test CA, and forwards plaintext to the chosen backend.

**Traffic containment is verified, not assumed.** `strace -e trace=connect` on a
full run shows the app's only API connections are to `127.0.0.1:8443`. The sole
non-loopback traffic is the app's connectivity checker reaching public DNS
resolvers on port 53 (1.1.1.1, 8.8.4.4, 208.67.222.222) — no connection to the
production host at any point.

## Toolchain, all in userspace

`flutter doctor` named four missing Linux requirements; none needed root:

- **cmake**, **ninja** — upstream prebuilt binaries under `~/dev/sdk/tools`;
- **clang** and the **GTK 3 development headers** — the Ubuntu package closure
  resolved with `apt-get install --print-uris` (a simulation, no root) and
  extracted with `dpkg -x` into `~/dev/sdk/sysroot`. The runtime libraries are
  already on the host, so the sysroot's dangling `.so` symlinks are repointed at
  `/usr/lib/x86_64-linux-gnu`. `pkg-config` is aimed at the sysroot through
  `PKG_CONFIG_PATH` and `PKG_CONFIG_SYSROOT_DIR`.

`flutter doctor` then reports the Linux toolchain green.

## Reproducing

```sh
. env.sh                       # PATH, sysroot, shim, CA bundle
./launch-desktop.sh 18081      # 18081 Java, 18080 PHP -- starts the TLS proxy too
python3 drive-desktop.py java  # fixed click journey, screenshots to /tmp/java-*.png
```

`drive-desktop-crud.py` runs the create -> update -> delete journey. It takes the
id from the create response and asserts that the `update` and `delete` requests
the app actually sent carry it, so the journey cannot silently act on some other
row when the list re-sorts.

### Mobile

```sh
export ANDROID_SDK_ROOT=~/dev/sdk/android JAVA_HOME=~/.sdkman/candidates/java/21.0.8-tem
sdkmanager platform-tools 'platforms;android-36' 'build-tools;36.0.0' emulator \
           'system-images;android-36;google_apis;x86_64'
avdmanager create avd -n workin_verify -k 'system-images;android-36;google_apis;x86_64' -d pixel_6
emulator -avd workin_verify -writable-system -no-snapshot-load -gpu swiftshader_indirect &

adb root && adb remount && adb reboot && adb wait-for-device   # remount needs the reboot
adb root && adb remount
adb push tls/ca.crt /data/local/tmp/e6559632.0                 # openssl x509 -subject_hash_old
adb shell 'cp /data/local/tmp/e6559632.0 /system/etc/security/cacerts/ && chmod 644 $_'
adb shell 'echo "127.0.0.1 workin.company" >> /system/etc/hosts'

python3 tls-proxy.py 8443 127.0.0.1 18081 &
adb reverse tcp:443 tcp:8443

cd workin_mobile && flutter config --jdk-dir="$JAVA_HOME" && flutter build apk --debug
adb install -r build/app/outputs/flutter-apk/app-debug.apk
adb shell am start -n com.app.workin/.MainActivity
```

The system CA is required because API 36 does not trust user CAs. `key.properties`
must exist in the work copy (any throwaway keystore) or even a debug build NPEs.
GPS must be injected with `cmd location providers set-test-provider-location`;
`adb emu geo fix` answers `OK` without ever setting a location.

`mob.sh` provides `tap`/`typ`/`key`/`shot` helpers over adb.

The journey is scripted precisely so the same clicks in the same order can be
replayed against each backend and compared. The app logs every `ENDPOINT`,
`QUERY`, `BODY` and `Response` itself, so its own log is the evidence rather
than anything the driver asserts.

## Removing it

Delete `~/dev/sdk`, `~/dev/runtime-verify` and the generated certificate, and
delete the AVD (`avdmanager delete avd -n workin_verify`), which takes the
modified system partition, the installed CA and the hosts entry with it. Drop
the port bridge with `adb reverse --remove tcp:443`. No system package was
installed, no file outside those trees or the emulator was modified, and no
service is left running except the local TLS proxy, which the launcher reaps on
exit.
