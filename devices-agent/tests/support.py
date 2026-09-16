"""A stand-in for the platform's /api/v1/device-agents surface, recording what it is sent."""
from __future__ import annotations

import http.server
import json
import threading
from urllib.parse import parse_qs, urlparse


class FakePlatform:
    def __init__(self, token="wda_" + "t" * 43):
        self.token = token
        self.submissions: list[dict] = []
        self.heartbeats: list[dict] = []
        self.seen_keys: set[tuple] = set()
        self.fail_with: int | None = None
        self.max_lines: int | None = None
        self.registered: set[str] | None = None
        platform = self

        class Handler(http.server.BaseHTTPRequestHandler):
            def log_message(self, *args):
                pass

            def do_POST(self):
                body = self.rfile.read(int(self.headers.get("Content-Length") or 0))
                if self.headers.get("Authorization") != f"Bearer {platform.token}":
                    return self._json(401, {"error": "unauthorized"})
                if platform.fail_with:
                    return self._json(platform.fail_with, {"error": "forced"})
                parsed = urlparse(self.path)
                if parsed.path.endswith("/heartbeat"):
                    platform.heartbeats.append(json.loads(body))
                    return self._json(200, {"devices": []})
                query = {k: v[0] for k, v in parse_qs(parsed.query).items()}
                if platform.registered is not None and query.get("serial") not in platform.registered:
                    return self._json(404, {"error": "device_not_registered"})
                lines = [line for line in body.decode().split("\n") if line]
                if platform.max_lines is not None and len(lines) > platform.max_lines:
                    return self._json(413, {"error": "too_many_records"})
                stored = 0
                for line in lines:
                    fields = line.split("\t")
                    key = (query.get("serial"), fields[0], fields[1] if len(fields) > 1 else "", fields[2] if len(fields) > 2 else "")
                    if key not in platform.seen_keys:
                        platform.seen_keys.add(key)
                        stored += 1
                platform.submissions.append({"query": query, "lines": lines, "content_type": self.headers.get("Content-Type")})
                return self._json(200, {"accepted": len(lines), "stored": stored, "duplicates": len(lines) - stored,
                                        "unmatched": 0, "malformed": 0})

            def _json(self, status, payload):
                data = json.dumps(payload).encode()
                self.send_response(status)
                self.send_header("Content-Type", "application/json")
                self.send_header("Content-Length", str(len(data)))
                self.end_headers()
                self.wfile.write(data)

        self.server = http.server.ThreadingHTTPServer(("127.0.0.1", 0), Handler)
        self.url = f"http://127.0.0.1:{self.server.server_address[1]}"
        threading.Thread(target=self.server.serve_forever, daemon=True).start()

    def close(self):
        self.server.shutdown()
        self.server.server_close()
