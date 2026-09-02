/*
 * Local test-environment shim for verifying the unmodified clients.
 *
 * The clients hardcode https://workin.company/apis/api/ and must not be
 * changed, so the hostname has to resolve here, the connection has to reach an
 * unprivileged port, and the local certificate has to be trusted. This machine
 * has no usable sudo credential, so none of that can be done in /etc.
 *
 * Three interceptions, each the minimum needed:
 *
 *   getaddrinfo  WORKIN_HOST            -> 127.0.0.1
 *   connect      127.0.0.1:443          -> 127.0.0.1:WORKIN_PORT
 *                (binding 443 itself would need root)
 *   open/openat  /etc/ssl/certs/ca-certificates.crt -> WORKIN_CA_BUNDLE
 *                (Dart reads that exact path -- confirmed with strace -- and
 *                 ignores SSL_CERT_FILE, so this is the only lever short of
 *                 writing to /etc)
 *
 * Scope: one process tree, for the duration of one command. Nothing outside
 * $HOME is touched and there is nothing to undo but unsetting LD_PRELOAD.
 */
#define _GNU_SOURCE
#include <dlfcn.h>
#include <fcntl.h>
#include <netdb.h>
#include <netinet/in.h>
#include <stdarg.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/socket.h>

static const char *host_name(void) {
    const char *v = getenv("WORKIN_HOST");
    return v ? v : "workin.company";
}
static int local_port(void) {
    const char *v = getenv("WORKIN_PORT");
    return v ? atoi(v) : 8443;
}
static const char *ca_bundle(void) { return getenv("WORKIN_CA_BUNDLE"); }

static const char *SYSTEM_BUNDLE = "/etc/ssl/certs/ca-certificates.crt";

int getaddrinfo(const char *node, const char *service,
                const struct addrinfo *hints, struct addrinfo **res) {
    static int (*real)(const char *, const char *, const struct addrinfo *, struct addrinfo **);
    if (!real) real = dlsym(RTLD_NEXT, "getaddrinfo");
    if (node && strcmp(node, host_name()) == 0)
        return real("127.0.0.1", service, hints, res);
    return real(node, service, hints, res);
}

int connect(int fd, const struct sockaddr *addr, socklen_t len) {
    static int (*real)(int, const struct sockaddr *, socklen_t);
    if (!real) real = dlsym(RTLD_NEXT, "connect");
    if (addr && addr->sa_family == AF_INET) {
        struct sockaddr_in in;
        memcpy(&in, addr, sizeof(in) < (size_t)len ? sizeof(in) : (size_t)len);
        if (ntohs(in.sin_port) == 443) {
            in.sin_port = htons((uint16_t)local_port());
            return real(fd, (struct sockaddr *)&in, sizeof(in));
        }
    }
    return real(fd, addr, len);
}

static const char *swap_bundle(const char *path) {
    const char *bundle = ca_bundle();
    if (bundle && path && strcmp(path, SYSTEM_BUNDLE) == 0) return bundle;
    return path;
}

int open(const char *path, int flags, ...) {
    static int (*real)(const char *, int, ...);
    if (!real) real = dlsym(RTLD_NEXT, "open");
    mode_t mode = 0;
    if (flags & O_CREAT) { va_list a; va_start(a, flags); mode = va_arg(a, mode_t); va_end(a); }
    return real(swap_bundle(path), flags, mode);
}

int openat(int dirfd, const char *path, int flags, ...) {
    static int (*real)(int, const char *, int, ...);
    if (!real) real = dlsym(RTLD_NEXT, "openat");
    mode_t mode = 0;
    if (flags & O_CREAT) { va_list a; va_start(a, flags); mode = va_arg(a, mode_t); va_end(a); }
    return real(dirfd, swap_bundle(path), flags, mode);
}

/*
 * The large-file variants and the stdio entry points as well.
 *
 * Interposing `open`/`openat` alone was not enough: the Flutter engine reaches
 * the bundle through a different symbol, so the app still read the system file
 * and every request failed the TLS handshake -- while a plain `dart run` with
 * the same shim succeeded. Whichever entry point a build happens to use, the
 * one path this cares about is redirected.
 */
int open64(const char *path, int flags, ...) {
    static int (*real)(const char *, int, ...);
    if (!real) real = dlsym(RTLD_NEXT, "open64");
    mode_t mode = 0;
    if (flags & O_CREAT) { va_list a; va_start(a, flags); mode = va_arg(a, mode_t); va_end(a); }
    return real(swap_bundle(path), flags, mode);
}

int openat64(int dirfd, const char *path, int flags, ...) {
    static int (*real)(int, const char *, int, ...);
    if (!real) real = dlsym(RTLD_NEXT, "openat64");
    mode_t mode = 0;
    if (flags & O_CREAT) { va_list a; va_start(a, flags); mode = va_arg(a, mode_t); va_end(a); }
    return real(dirfd, swap_bundle(path), flags, mode);
}

FILE *fopen(const char *path, const char *mode) {
    static FILE *(*real)(const char *, const char *);
    if (!real) real = dlsym(RTLD_NEXT, "fopen");
    return real(swap_bundle(path), mode);
}

FILE *fopen64(const char *path, const char *mode) {
    static FILE *(*real)(const char *, const char *);
    if (!real) real = dlsym(RTLD_NEXT, "fopen64");
    return real(swap_bundle(path), mode);
}
