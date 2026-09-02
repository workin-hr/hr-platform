#!/usr/bin/env python3
"""A local stand-in for the Whats360 send endpoint.

Why it exists: the OTP flows cannot be compared without one. Both stacks store
the code only after the send SUCCEEDS, and the harness deliberately holds
placeholder integration tokens so nothing can reach a real person -- so
`forgot_password` answers 503 on both, the code is never written, and every OTP
case would compare two identical failures. That is a matching error, not parity.

This is also STRICTLY SAFER than the placeholder it replaces: the shipped
config points at the real https://pro.whats360.live host and attempts an actual
outbound request with a dummy token. Pointing both stacks at 127.0.0.1 means no
request can leave the machine at all.

Every message is logged to whatsapp-stub.log so a case can assert what would
have been sent, and the code itself is read from each stack's OWN database --
the two stacks generate different codes, exactly as two independent systems
should.
"""
import datetime
import json
import sys
from http.server import BaseHTTPRequestHandler, HTTPServer

LOG = "whatsapp-stub.log"


class Handler(BaseHTTPRequestHandler):
    def do_POST(self):
        length = int(self.headers.get("Content-Length") or 0)
        raw = self.rfile.read(length).decode("utf-8", "replace") if length else ""
        with open(LOG, "a", encoding="utf-8") as log:
            log.write(f"{datetime.datetime.now().isoformat()} {self.path} {raw}\n")
        # Both senders require HTTP < 400 AND a truthy `success` in the body:
        # functions.php's whatsapp send checks `!empty($decoded['success'])`,
        # and LegacyWhatsAppHttpSender.classify() checks the same.
        body = json.dumps({"success": True, "message": "stubbed"}).encode()
        self.send_response(200)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    do_GET = do_POST

    def log_message(self, *args):
        pass


if __name__ == "__main__":
    port = int(sys.argv[1]) if len(sys.argv) > 1 else 18099
    HTTPServer(("0.0.0.0", port), Handler).serve_forever()
