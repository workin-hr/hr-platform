"""A pretend Hikvision access terminal: the two ISAPI calls the agent makes, behind digest auth.

Enough for the lab to prove the agent's Hikvision path end to end. The event log mixes
attendance (fingerprint and face matches) with events that are not (a refused card, a door
held open), so the agent's minor-code allowlist is exercised rather than assumed.
"""
from __future__ import annotations

import hashlib
import http.server
import json
import os
import re
import threading
from datetime import datetime, timedelta, timezone

REALM = "DS-K1T671"


class HikTerminal:
    def __init__(self, serial="DS-K1T671TM-3XF20240101V030700ENK12345678", username="admin", password="lab-password",
                 model="DS-K1T671TM-3XF", firmware="V3.7.0 build 240101", offset_hours=3):
        self.serial, self.username, self.password = serial, username, password
        self.model, self.firmware = model, firmware
        self.zone = timezone(timedelta(hours=offset_hours))
        self.events: list[dict] = []
        self.lock = threading.Lock()

    def add_event(self, employee: str | None, when: datetime, minor: int, attendance_status: str | None = None):
        event = {"major": 5, "minor": minor, "time": when.replace(microsecond=0, tzinfo=self.zone).isoformat(),
                 "serialNo": len(self.events) + 1}
        if employee:
            event["employeeNoString"] = employee
        if attendance_status:
            event["attendanceStatus"] = attendance_status
        with self.lock:
            self.events.append(event)

    def populate(self, employees: list[str], days: int, now: datetime | None = None):
        today = (now or datetime.now()).replace(hour=0, minute=0, second=0, microsecond=0)
        for offset in range(days, 0, -1):
            day = today - timedelta(days=offset)
            for index, employee in enumerate(employees):
                self.add_event(employee, day + timedelta(hours=8, minutes=5 + index), 75 if index % 2 else 38, "checkIn")
                self.add_event(employee, day + timedelta(hours=17, minutes=index), 75 if index % 2 else 38, "checkOut")
            self.add_event(None, day + timedelta(hours=12), 21)          # door held open: not attendance
            self.add_event(employees[0], day + timedelta(hours=9), 9)    # a refused card: not attendance


def serve(terminal: HikTerminal, host="127.0.0.1", port=0):
    nonce = os.urandom(8).hex()

    class Handler(http.server.BaseHTTPRequestHandler):
        def log_message(self, *args):
            pass

        def _authorised(self) -> bool:
            header = self.headers.get("Authorization", "")
            if not header.startswith("Digest "):
                return False
            fields = dict(re.findall(r'(\w+)="?([^",]+)"?', header[7:]))
            ha1 = hashlib.md5(f"{terminal.username}:{REALM}:{terminal.password}".encode()).hexdigest()
            ha2 = hashlib.md5(f"{self.command}:{fields.get('uri', '')}".encode()).hexdigest()
            if fields.get("qop"):
                expected = hashlib.md5(
                    f"{ha1}:{fields.get('nonce')}:{fields.get('nc')}:{fields.get('cnonce')}:{fields.get('qop')}:{ha2}".encode()).hexdigest()
            else:
                expected = hashlib.md5(f"{ha1}:{fields.get('nonce')}:{ha2}".encode()).hexdigest()
            return fields.get("username") == terminal.username and fields.get("response") == expected

        def _challenge(self):
            self.send_response(401)
            self.send_header("WWW-Authenticate", f'Digest realm="{REALM}", qop="auth", nonce="{nonce}", algorithm="MD5"')
            self.send_header("Content-Length", "0")
            self.end_headers()

        def _send(self, body: bytes, content_type: str):
            self.send_response(200)
            self.send_header("Content-Type", content_type)
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)

        def do_GET(self):
            if not self._authorised():
                return self._challenge()
            if self.path.startswith("/ISAPI/System/deviceInfo"):
                xml = (f'<?xml version="1.0" encoding="UTF-8"?><DeviceInfo xmlns="http://www.isapi.org/ver20/XMLSchema">'
                       f"<deviceName>Gate</deviceName><model>{terminal.model}</model>"
                       f"<serialNumber>{terminal.serial}</serialNumber><firmwareVersion>{terminal.firmware}</firmwareVersion>"
                       f"</DeviceInfo>").encode()
                return self._send(xml, "application/xml")
            self.send_error(404)

        def do_POST(self):
            length = int(self.headers.get("Content-Length") or 0)
            body = self.rfile.read(length) if length else b""
            if not self._authorised():
                return self._challenge()
            if not self.path.startswith("/ISAPI/AccessControl/AcsEvent"):
                return self.send_error(404)
            condition = json.loads(body or b"{}").get("AcsEventCond", {})
            start = datetime.fromisoformat(condition["startTime"])
            end = datetime.fromisoformat(condition["endTime"])
            with terminal.lock:
                matching = [e for e in terminal.events if start <= datetime.fromisoformat(e["time"]) <= end]
            position = int(condition.get("searchResultPosition", 0))
            size = int(condition.get("maxResults", 30))
            page = matching[position:position + size]
            status = "MORE" if position + size < len(matching) else "OK"
            answer = {"AcsEvent": {"searchID": condition.get("searchID"), "responseStatusStrg": status,
                                   "numOfMatches": len(page), "totalMatches": len(matching), "InfoList": page}}
            return self._send(json.dumps(answer).encode(), "application/json")

    server = http.server.ThreadingHTTPServer((host, port), Handler)
    thread = threading.Thread(target=server.serve_forever, daemon=True)
    thread.start()
    return server
