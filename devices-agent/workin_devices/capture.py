"""The field recorder: a small HTTP server a terminal is pointed at during a site visit.

It keeps every byte of every exchange -- request line, headers, body, and the answer -- under
one folder per terminal, so what a real firmware sends becomes evidence the team can replay
later instead of a memory of the visit.

With --upstream it is a recording reverse proxy in front of the platform: the terminal talks to
the real receiver, and the Host header is set to the name the receiver answers on, so the
laptop's LAN address can change from site to site without reconfiguring the stack. Without it,
it answers like a receiver that is temporarily unavailable: a handshake with no time zone and OK
to a command poll, but 503 to every upload. A firmware that is told an upload was received may
drop it from its queue, and then the customer's own system never gets those punches; a 503
keeps them queued, and the recorder still has every byte of each attempt.
"""
from __future__ import annotations

import http.client
import http.server
import json
import os
import re
import threading
import time
from datetime import datetime
from urllib.parse import parse_qs, urlparse

HOP_BY_HOP = {"connection", "keep-alive", "proxy-authenticate", "proxy-authorization", "te", "trailers",
              "transfer-encoding", "upgrade", "host", "content-length"}
# Credentials never reach a capture file: a terminal sends none, so anything carrying one is not
# a terminal, and the file would otherwise hold it in clear on the laptop.
NOT_RECORDED = {"authorization", "cookie", "proxy-authorization"}
# Only the terminal receiver is forwarded. The recorder listens on the customer's LAN; forwarding
# every path would put the platform's sign-in and API on that LAN through it.
FORWARDED_PREFIX = "/iclock"
SAFE_NAME = re.compile(r"[^A-Za-z0-9._-]")


class Recorder:
    def __init__(self, out_dir: str):
        self.out_dir = out_dir
        self.counter = 0
        self.lock = threading.Lock()
        self.seen: dict[str, int] = {}
        os.makedirs(out_dir, exist_ok=True)

    def save(self, identity: str, meta: dict, request_body: bytes, response_body: bytes) -> tuple[str, bool]:
        with self.lock:
            self.counter += 1
            sequence = self.counter
            first = identity not in self.seen
            self.seen[identity] = self.seen.get(identity, 0) + 1
        folder = os.path.join(self.out_dir, SAFE_NAME.sub("_", identity)[:80] or "unknown")
        os.makedirs(folder, exist_ok=True)
        stem = os.path.join(folder, f"{sequence:05d}-{datetime.now():%Y%m%d-%H%M%S}-{meta['method']}")
        with open(stem + ".json", "w", encoding="utf-8") as handle:
            json.dump(meta, handle, indent=2, ensure_ascii=False)
        if request_body:
            with open(stem + ".request.bin", "wb") as handle:
                handle.write(request_body)
        if response_body:
            with open(stem + ".response.bin", "wb") as handle:
                handle.write(response_body)
        return stem, first


def identity_of(path: str, client_ip: str) -> str:
    query = parse_qs(urlparse(path).query)
    serial = (query.get("SN") or query.get("sn") or [None])[0]
    if serial:
        return serial
    segments = [s for s in urlparse(path).path.split("/") if s]
    return f"{client_ip}-{segments[0] if segments else 'root'}"


def standalone_answer(method: str, path: str) -> tuple[int, bytes]:
    parsed = urlparse(path)
    if method not in ("GET", "HEAD"):
        return 503, b"ERROR"
    if parsed.path == "/iclock/cdata":
        serial = (parse_qs(parsed.query).get("SN") or [""])[0]
        lines = [f"GET OPTION FROM: {serial}", "ATTLOGStamp=0", "OPERLOGStamp=0", "ATTPHOTOStamp=0", "ErrorDelay=30",
                 "Delay=10", "TransTimes=00:00;14:05", "TransInterval=1", "TransFlag=TransData AttLog\tOpLog",
                 "Realtime=1", "Encrypt=None"]
        return 200, ("\r\n".join(lines) + "\r\n").encode("ascii")
    return 200, b"OK"


def make_handler(recorder: Recorder, upstream: str | None, host_header: str | None, out=print):
    target = urlparse(upstream) if upstream else None

    class Handler(http.server.BaseHTTPRequestHandler):
        protocol_version = "HTTP/1.1"

        def log_message(self, *args):
            pass

        def _body(self) -> bytes:
            if self.headers.get("Transfer-Encoding", "").lower() == "chunked":
                data = bytearray()
                while True:
                    size = int(self.rfile.readline().strip().split(b";")[0] or b"0", 16)
                    if size == 0:
                        self.rfile.readline()
                        return bytes(data)
                    data += self.rfile.read(size)
                    self.rfile.readline()
            length = int(self.headers.get("Content-Length") or 0)
            return self.rfile.read(length) if length else b""

        def _handle(self):
            started = time.monotonic()
            request_body = self._body()
            client_ip = self.client_address[0]
            error = None
            forwardable = urlparse(self.path).path == FORWARDED_PREFIX or \
                urlparse(self.path).path.startswith(FORWARDED_PREFIX + "/")
            if target is None:
                status, response_body = standalone_answer(self.command, self.path)
                response_headers = {"Content-Type": "text/plain"}
            elif not forwardable:
                status, response_body, response_headers = 404, b"", {"Content-Type": "text/plain"}
            else:
                try:
                    status, response_headers, response_body = self._forward(request_body, client_ip)
                except OSError as exc:
                    # The platform is down: answer the terminal with an error, never with OK, so it
                    # keeps its records and retries.
                    status, response_headers, response_body, error = 502, {"Content-Type": "text/plain"}, b"ERROR", str(exc)
            self.send_response(status)
            for name, value in response_headers.items():
                if name.lower() not in HOP_BY_HOP:
                    self.send_header(name, value)
            self.send_header("Content-Length", str(len(response_body)))
            self.end_headers()
            self.wfile.write(response_body)
            identity = identity_of(self.path, client_ip)
            meta = {"at": datetime.now().isoformat(timespec="milliseconds"), "client": client_ip,
                    "method": self.command, "path": self.path,
                    "request_headers": {name: value for name, value in self.headers.items()
                                        if name.lower() not in NOT_RECORDED},
                    "request_bytes": len(request_body), "status": status, "response_headers": response_headers,
                    "response_bytes": len(response_body), "elapsed_ms": round((time.monotonic() - started) * 1000),
                    "upstream": upstream, "upstream_error": error}
            _, first = recorder.save(identity, meta, request_body, response_body)
            preview = request_body[:90].decode("utf-8", "replace").replace("\t", " ").replace("\r", "").replace("\n", " | ")
            out(f"{datetime.now():%H:%M:%S} {'NEW ' if first else ''}[{identity}] {self.command} {self.path[:70]} "
                f"-> {status}{' ' + response_body[:20].decode('utf-8', 'replace').strip() if response_body else ''}"
                f"{'  body: ' + preview if preview else ''}{'  UPSTREAM ERROR: ' + error if error else ''}")

        def _forward(self, body: bytes, client_ip: str):
            connection_class = http.client.HTTPSConnection if target.scheme == "https" else http.client.HTTPConnection
            connection = connection_class(target.hostname, target.port or (443 if target.scheme == "https" else 80),
                                          timeout=30)
            headers = {name: value for name, value in self.headers.items() if name.lower() not in HOP_BY_HOP}
            headers["Host"] = host_header or self.headers.get("Host", target.netloc)
            headers["X-Forwarded-For"] = client_ip
            headers["Content-Length"] = str(len(body))
            try:
                connection.request(self.command, self.path, body=body if body or self.command in ("POST", "PUT") else None,
                                   headers=headers)
                response = connection.getresponse()
                return response.status, dict(response.getheaders()), response.read()
            finally:
                connection.close()

        do_GET = do_POST = do_PUT = do_DELETE = _handle

    return Handler


def serve(listen_host: str, listen_port: int, out_dir: str, upstream: str | None = None, host_header: str | None = None,
          out=print) -> http.server.ThreadingHTTPServer:
    server = http.server.ThreadingHTTPServer((listen_host, listen_port),
                                             make_handler(Recorder(out_dir), upstream, host_header, out))
    server.daemon_threads = True
    return server
