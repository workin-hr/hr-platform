#!/usr/bin/env python3
"""TLS terminator for the local test hostname.

The clients hardcode https://workin.company/apis/api/ and are never modified, so
verification needs that name to resolve locally and speak TLS. This listens on
an unprivileged port with a certificate for `workin.company` signed by the local
test CA, and forwards plaintext to whichever backend is selected.

Port 8443, not 443: binding below 1024 needs root, which this environment does
not have. The LD_PRELOAD shim rewrites the client's :443 connect to :8443, so
the client's own URL is untouched.
"""
import selectors
import socket
import ssl
import sys
import threading

LISTEN = ('127.0.0.1', int(sys.argv[1]) if len(sys.argv) > 1 else 8443)
BACKEND = (sys.argv[2] if len(sys.argv) > 2 else '127.0.0.1',
           int(sys.argv[3]) if len(sys.argv) > 3 else 18081)


def pump(source, sink):
    try:
        while True:
            data = source.recv(65536)
            if not data:
                break
            sink.sendall(data)
    except OSError:
        pass
    finally:
        for s in (source, sink):
            try:
                s.shutdown(socket.SHUT_RDWR)
            except OSError:
                pass
            s.close()


def serve(client):
    try:
        upstream = socket.create_connection(BACKEND, timeout=30)
    except OSError as error:
        print(f'  backend {BACKEND} unreachable: {error}', flush=True)
        client.close()
        return
    threading.Thread(target=pump, args=(client, upstream), daemon=True).start()
    threading.Thread(target=pump, args=(upstream, client), daemon=True).start()


def main():
    context = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
    context.load_cert_chain('tls/server.crt', 'tls/server.key')
    listener = socket.socket()
    listener.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    listener.bind(LISTEN)
    listener.listen(64)
    print(f'tls-proxy: https://{LISTEN[0]}:{LISTEN[1]} -> {BACKEND[0]}:{BACKEND[1]}', flush=True)
    while True:
        raw, _ = listener.accept()
        try:
            serve(context.wrap_socket(raw, server_side=True))
        except ssl.SSLError as error:
            print(f'  tls handshake failed: {error}', flush=True)
            raw.close()


if __name__ == '__main__':
    main()
