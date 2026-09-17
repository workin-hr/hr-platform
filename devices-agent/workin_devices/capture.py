"""The field recorder: a small HTTP server a terminal is pointed at during a site visit.

It keeps each exchange -- request line, headers, body, and the answer -- under one folder per
terminal, so what a real firmware sends becomes evidence the team can replay later instead of a
memory of the visit. It writes, and prints, only what the platform itself keeps, in the shape the
platform parses it (``recordable``): attendance lines, option pairs, operation lines and command
results, and the structure -- never the values -- of other brands' JSON and XML. Fingerprint and
face templates, pictures, names, card numbers and enrolment records never reach the disk: the
platform discards them, and a recorder that kept them would be the one place they survived, on a
laptop.

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
import xml.etree.ElementTree
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
# What a recording may keep is what the platform itself keeps, in the shape it parses it:
# ATTLOG lines (ZkTecoAttlogParser: a PIN of digits, a wall-clock or Unix-seconds time, short
# fields), OPTIONS pairs (ZkTecoOptionsUpload: values of at most 100 characters), the OPLOG lines
# of OPERLOG (ZkTecoOperlogFilter) and command results. Every other table -- templates, photos,
# enrolment and ID-card records, whatever a firmware calls them -- is withheld whole. The
# platform receives nothing from other brands' pushes, so their JSON and XML are recorded as
# structure only: names, value types and lengths, small numbers (event codes) and timestamps.
# Bounding every kept field is what keeps an encoded template out, however it is wrapped or
# escaped. Forwarding is unaffected.
ATTLOG_PIN = re.compile(r"^\d{1,32}$")
ATTLOG_TIME = re.compile(r"^(\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2}|\d{9,11})$")
OPTION_PAIR = re.compile(r"^~?[A-Za-z][A-Za-z0-9_.]{0,63}=[^\r\n]{0,100}$")
MAX_FIELD = 32
COMMAND_RESULT = re.compile(r"^ID=[^&\s]{0,32}&Return=-?\d{1,6}&CMD=[A-Za-z_]{0,32}$")
JSON_TYPES = ("application/json",)
XML_TYPES = ("application/xml", "text/xml")
TIMESTAMP = re.compile(r"^\d{4}-\d{2}-\d{2}[T ]\d{2}:\d{2}:\d{2}(\.\d{1,6})?(Z|[+-]\d{2}:?\d{2})?$")
NAME = re.compile(r"^[A-Za-z_][A-Za-z0-9_.:-]{0,63}$")
# Enumerations whose value is what a visit needs to read (in/out, how the employee verified), and
# which carry no identity: kept when the value is a plain word such as checkIn or cardOrFace.
ENUM_KEYS = {"attendanceStatus", "currentVerifyMode", "userType", "eventType", "type"}
ENUM_VALUE = re.compile(r"^[A-Za-z][A-Za-z0-9_]{0,31}$")
# A field that names a person or a credential keeps no value at all, not even a small number:
# an employee number of 7 is as much an identity as "1001".
IDENTITY_NAME = re.compile(r"employee|card|user|person|name|phone|mail|face|finger|picture|photo", re.IGNORECASE)
STRUCTURE_ONLY = {"values": "structure only"}


def recordable(path: str, content_type: str, body: bytes) -> tuple[bytes, dict | None]:
    """The part of a request body that may be written to disk or printed, and what was withheld
    (kinds and sizes, never content). Only what the platform keeps is kept."""
    if not body:
        return body, None
    parsed = urlparse(path)
    media = content_type.lower().split(";")[0].strip()
    if parsed.path == FORWARDED_PREFIX or parsed.path.startswith(FORWARDED_PREFIX + "/"):
        if parsed.path == FORWARDED_PREFIX + "/devicecmd":
            return _kept_lines(body, lambda line: COMMAND_RESULT.match(line.strip()) is not None, shape=False,
                               kinds=False)
        table = (parse_qs(parsed.query).get("table") or [""])[0].upper()
        if table == "ATTLOG":
            return _kept_lines(body, _attlog_line, shape=True)
        if table == "OPTIONS":
            return _kept_options(body)
        if table == "OPERLOG":
            return _kept_lines(body, _oplog_line, shape=False)
        return b"", {"table": table or "none", "bytes": len(body)}
    if media.startswith("multipart/"):
        return _recordable_parts(content_type, body)
    return _structure_of(media, body)


def _attlog_line(line: str) -> bool:
    fields = [field.strip() for field in line.split("\t")]
    return (len(fields) >= 2 and ATTLOG_PIN.match(fields[0]) is not None and ATTLOG_TIME.match(fields[1]) is not None
            and all(len(field) <= MAX_FIELD for field in fields[2:]))


def _oplog_line(line: str) -> bool:
    fields = line.strip().split("\t")
    return fields[0].split(maxsplit=1)[0].upper() == "OPLOG" and all(len(field) <= MAX_FIELD for field in fields)


def _shape(line: str) -> str:
    """What an unparsed line looked like -- separators and lengths -- with no character of it."""
    masked = re.sub(r"[0-9]", "9", re.sub(r"[^\W\d_]", "a", line.strip()))
    return masked[:120]


def _decoded(body: bytes) -> str | None:
    try:
        return body.decode("utf-8")
    except UnicodeDecodeError:
        return None


def _kind_label(line: str) -> str:
    """A dropped line's record kind (USER, FP, ...) when it is a plain word; never its content."""
    first = line.strip().split(maxsplit=1)[0] if line.strip() else ""
    return first.upper() if re.fullmatch(r"[A-Za-z]{1,16}", first) else "other"


def _kept_lines(body: bytes, keep, shape: bool, kinds: bool = True) -> tuple[bytes, dict | None]:
    text = _decoded(body)
    if text is None:
        return b"", {"binary": True, "bytes": len(body)}
    kept, dropped = [], {}
    for line in text.splitlines(keepends=True):
        if not line.strip() or keep(line):
            kept.append(line)
            continue
        label = "unparsed" if shape else (_kind_label(line) if kinds else "other")
        dropped[label] = dropped.get(label, 0) + 1
        if shape:
            kept.append(f"# unparsed line shape: {_shape(line)}\n")
    return "".join(kept).encode("utf-8"), ({"lines": dropped} if dropped else None)


def _kept_options(body: bytes) -> tuple[bytes, dict | None]:
    text = _decoded(body)
    if text is None:
        return b"", {"binary": True, "bytes": len(body)}
    kept, unparsed = [], 0
    for pair in re.split(r"[,\r\n]+", text):
        if not pair.strip():
            continue
        if OPTION_PAIR.match(pair.strip()):
            kept.append(pair.strip())
        else:
            unparsed += 1
    return ("\n".join(kept) + "\n").encode("utf-8") if kept else b"", ({"lines": {"unparsed": unparsed}} if unparsed else None)


def _structure_of(media: str, body: bytes) -> tuple[bytes, dict | None]:
    text = _decoded(body)
    if text is not None and media in JSON_TYPES:
        try:
            value = json.loads(text)
        except (ValueError, RecursionError):
            value = None
        else:
            return json.dumps(structure(value), ensure_ascii=False, indent=1).encode("utf-8") + b"\n", STRUCTURE_ONLY
    if text is not None and media in XML_TYPES and "<!DOCTYPE" not in text and "<!ENTITY" not in text:
        try:
            root = xml.etree.ElementTree.fromstring(text)
        except xml.etree.ElementTree.ParseError:
            root = None
        if root is not None:
            lines = []
            _xml_structure(root, 0, lines)
            return ("\n".join(lines) + "\n").encode("utf-8"), STRUCTURE_ONLY
    return b"", {"type": media or "none", "bytes": len(body)}


def _scalar(value, identity: bool = False):
    if value is None or isinstance(value, bool):
        return value
    if isinstance(value, (int, float)):
        return value if -1000 < value < 1000 and not identity else "<number>"
    if isinstance(value, str):
        return value if TIMESTAMP.match(value) else f"<string {len(value)}>"
    return f"<{type(value).__name__}>"


def structure(value, depth: int = 0, identity: bool = False):
    """A parsed JSON value with its names, types and lengths, small numbers, timestamps and
    enumerations, and nothing that identifies a person or carries a template."""
    if depth > 16:
        return "<nested>"
    if isinstance(value, dict):
        return {(key if isinstance(key, str) and NAME.match(key) else f"<key {len(str(key))}>"):
                (item if key in ENUM_KEYS and isinstance(item, str) and ENUM_VALUE.match(item)
                 else structure(item, depth + 1, identity or _names_identity(key)))
                for key, item in list(value.items())[:64]}
    if isinstance(value, list):
        return ([structure(item, depth + 1, identity) for item in value] if len(value) <= 16
                else f"<array {len(value)}>")
    return _scalar(value, identity)


def _names_identity(key) -> bool:
    return not isinstance(key, str) or (key not in ENUM_KEYS and IDENTITY_NAME.search(key) is not None)


def _xml_structure(element, depth: int, lines: list[str], identity: bool = False) -> None:
    if depth > 16 or len(lines) > 256:
        return
    local = element.tag.split("}")[-1]
    tag = local if NAME.match(local) else f"<tag {len(element.tag)}>"
    identity = identity or _names_identity(local)
    attributes = " ".join(f"{name if NAME.match(name) else '<name>'}={_scalar(value, identity or _names_identity(name))}"
                          for name, value in list(element.attrib.items())[:16])
    text = (element.text or "").strip()
    shown = (text if local in ENUM_KEYS and ENUM_VALUE.match(text) else _scalar(_number(text), identity)) if text else None
    lines.append("  " * depth + tag + (f" [{attributes}]" if attributes else "") + (f": {shown}" if text else ""))
    for child in list(element)[:64]:
        _xml_structure(child, depth + 1, lines, identity)


def _number(text: str):
    return int(text) if re.fullmatch(r"-?\d{1,3}", text) else text


def _recordable_parts(content_type: str, body: bytes) -> tuple[bytes, dict | None]:
    message = email.parser.BytesParser(policy=email.policy.HTTP).parsebytes(
        b"Content-Type: " + content_type.encode("latin-1", "replace") + b"\r\n\r\n" + body)
    if not message.is_multipart():
        return b"", {"type": content_type, "bytes": len(body)}
    kept, notes = [], []
    for part in message.iter_parts():
        part_type = part.get_content_type()
        payload = part.get_payload(decode=True) or b""
        name = part.get_param("name", header="content-disposition")
        label = f"--- part name={name if isinstance(name, str) and NAME.match(name) else '<name>'} type={part_type} bytes={len(payload)}"
        shown, note = _structure_of(part_type, payload)
        kept.append(label.encode("utf-8") + (b"\n" + shown if shown else b" [withheld]\n"))
        notes.append({"type": part_type, **(note or {})})
    return b"".join(kept), {"parts": notes}


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
