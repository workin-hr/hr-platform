"""The field recorder: a small HTTP server a terminal is pointed at during a site visit.

It keeps each exchange -- request line, headers, body, and the answer -- under one folder per
terminal, so what a real firmware sends becomes evidence the team can replay later instead of a
memory of the visit. What it never writes, or prints, is biometric: fingerprint and face
templates, pictures, and the enrolment records that carry a name, a device password and a card
number (``recordable``). The platform discards the same records; a recorder that kept them would
be the one place they survived, on a laptop.

With --upstream it is a recording reverse proxy in front of the platform: the terminal talks to
the real receiver, and the Host header is set to the name the receiver answers on, so the
laptop's LAN address can change from site to site without reconfiguring the stack. Without it,
it answers like a receiver that is temporarily unavailable: a handshake with no time zone and OK
to a command poll, but 503 to every upload. A firmware that is told an upload was received may
drop it from its queue, and then the customer's own system never gets those punches; a 503
keeps them queued, and the recorder still has each attempt.
"""
from __future__ import annotations

import email.parser
import email.policy
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
# Upload tables whose whole body is a template or a picture, and USERINFO, whose every line is an
# enrolment record (ZkTecoAdmsService's known tables); and the record kinds inside a mixed body
# such as OPERLOG that are the same (ZkTecoOperlogFilter). Anything forwarded is unaffected.
WITHHELD_TABLES = {"FINGERTMP", "BIODATA", "BIOPHOTO", "USERPIC", "ATTPHOTO", "FACE", "USERINFO"}
WITHHELD_LINE_KINDS = {"USER", "FP", "FACE", "BIODATA", "BIOPHOTO", "USERPIC", "FVEIN", "PALM"}
TEXT_TYPES = ("text/", "application/json", "application/xml", "application/x-www-form-urlencoded")


def recordable(path: str, content_type: str, body: bytes) -> tuple[bytes, dict | None]:
    """The part of a request body that may be written to disk, and what was withheld (kinds and
    sizes, never content). Closed by default: a body that is not text is withheld whole."""
    if not body:
        return body, None
    parsed = urlparse(path)
    table = (parse_qs(parsed.query).get("table") or [""])[0].upper()
    if table in WITHHELD_TABLES:
        return b"", {"table": table, "bytes": len(body)}
    if content_type.lower().startswith("multipart/"):
        return _recordable_parts(content_type, body)
    try:
        text = body.decode("utf-8")
    except UnicodeDecodeError:
        return b"", {"binary": content_type or "unknown", "bytes": len(body)}
    kept, withheld = [], {}
    for line in text.splitlines(keepends=True):
        kind = line.strip().split(maxsplit=1)[0].upper() if line.strip() else ""
        if kind in WITHHELD_LINE_KINDS:
            withheld[kind] = withheld.get(kind, 0) + 1
        else:
            kept.append(line)
    if not withheld:
        return body, None
    return "".join(kept).encode("utf-8"), {"lines": withheld}


def _recordable_parts(content_type: str, body: bytes) -> tuple[bytes, dict | None]:
    message = email.parser.BytesParser(policy=email.policy.HTTP).parsebytes(
        b"Content-Type: " + content_type.encode("latin-1", "replace") + b"\r\n\r\n" + body)
    if not message.is_multipart():
        return b"", {"binary": content_type, "bytes": len(body)}
    kept, withheld = [], []
    for part in message.iter_parts():
        part_type = part.get_content_type()
        payload = part.get_payload(decode=True) or b""
        label = f"--- part name={part.get_param('name', header='content-disposition')} type={part_type} bytes={len(payload)}"
        if part_type.startswith(TEXT_TYPES):
            text, inner = recordable("", part_type, payload)
            kept.append(label.encode("utf-8") + b"\n" + text + b"\n")
            if inner:
                withheld.append({"type": part_type, **inner})
        else:
            kept.append(label.encode("utf-8") + b" [withheld]\n")
            withheld.append({"type": part_type, "bytes": len(payload)})
    return b"".join(kept), ({"parts": withheld} if withheld else None)


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
            kept_body, withheld = recordable(self.path, self.headers.get("Content-Type", ""), request_body)
            meta = {"at": datetime.now().isoformat(timespec="milliseconds"), "client": client_ip,
                    "method": self.command, "path": self.path,
                    "request_headers": {name: value for name, value in self.headers.items()
                                        if name.lower() not in NOT_RECORDED},
                    "request_bytes": len(request_body), "status": status, "response_headers": response_headers,
                    "response_bytes": len(response_body), "elapsed_ms": round((time.monotonic() - started) * 1000),
                    "upstream": upstream, "upstream_error": error}
            if withheld:
                meta["request_body_withheld"] = withheld
            _, first = recorder.save(identity, meta, kept_body, response_body)
            preview = kept_body[:90].decode("utf-8", "replace").replace("\t", " ").replace("\r", "").replace("\n", " | ")
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
