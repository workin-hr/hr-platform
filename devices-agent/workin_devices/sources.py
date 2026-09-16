"""Where punches come from: a ZKTeco terminal over 4370, a Hikvision terminal over ISAPI, a file.

Each source answers two questions -- what are you (describe) and what have you recorded (read) --
and does nothing else. None of them writes to a terminal.
"""
from __future__ import annotations

import json
import ssl
import urllib.error
import urllib.request
import xml.etree.ElementTree as ElementTree
from dataclasses import asdict, dataclass
from datetime import datetime, timedelta

from .config import DeviceConfig
from .spool import Punch
from .zk4370 import ZkClient, ZkError


class SourceError(Exception):
    pass


@dataclass
class Description:
    """What a terminal says about itself. Reported to the server in the heartbeat."""
    serial: str | None = None
    vendor: str | None = None
    model: str | None = None
    firmware: str | None = None
    platform: str | None = None
    records: int | None = None
    record_capacity: int | None = None
    users: int | None = None
    device_time: str | None = None

    def as_report(self) -> dict:
        return {key: value for key, value in asdict(self).items() if value is not None}


def open_source(device: DeviceConfig, in_out_field: str = "punch"):
    if device.kind == "zk":
        return ZkSource(device, in_out_field)
    if device.kind == "hikvision":
        return HikvisionSource(device)
    if device.kind == "file":
        return FileSource(device)
    raise SourceError(f"unknown source kind {device.kind}")


class ZkSource:
    def __init__(self, device: DeviceConfig, in_out_field: str):
        self.device = device
        self.in_out_field = in_out_field
        self.client = ZkClient(device.host, device.port or 4370, device.timeout_seconds, device.comm_key, device.udp)

    def __enter__(self):
        try:
            self.client.connect()
        except ZkError as exc:
            raise SourceError(f"{self.device.serial}: {exc}") from exc
        return self

    def __exit__(self, *exc):
        self.client.disconnect()
        return False

    def describe(self) -> Description:
        try:
            sizes = self.client.sizes()
            return Description(
                serial=self.client.serial_number(), vendor="zkteco",
                model=self.client.option("~DeviceName"), firmware=self.client.firmware_version(),
                platform=self.client.option("~Platform"), records=sizes.records,
                record_capacity=sizes.records_capacity, users=sizes.users,
                device_time=self.client.device_time().strftime("%Y-%m-%d %H:%M:%S"))
        except ZkError as exc:
            raise SourceError(f"{self.device.serial}: {exc}") from exc

    def read(self) -> list[Punch]:
        try:
            raw = self.client.attendance()
        except ZkError as exc:
            raise SourceError(f"{self.device.serial}: {exc}") from exc
        punches = []
        for record in raw:
            in_out = record.punch if self.in_out_field == "punch" else record.status
            verify = record.status if self.in_out_field == "punch" else record.punch
            punches.append(Punch(record.user_id.strip(), record.timestamp.strftime("%Y-%m-%d %H:%M:%S"), in_out, verify))
        return punches


class HikvisionSource:
    """A Hikvision access terminal's own event log, over ISAPI with digest authentication.

    The cursor is a time window rather than the event serial number: field reports document gaps
    in that sequence. Re-reading an overlapping window each pass is cheap, and the spool and the
    server's dedup key absorb the repeats.
    """

    ATTENDANCE_STATUS = {"checkin": 0, "checkout": 1, "breakout": 2, "breakin": 3, "overtimein": 4, "overtimeout": 5}
    MAJOR_ACCESS_CONTROL = 5

    def __init__(self, device: DeviceConfig, now=datetime.now):
        self.device = device
        self.now = now
        scheme = "https" if device.https else "http"
        self.base = f"{scheme}://{device.host}:{device.port}"
        manager = urllib.request.HTTPPasswordMgrWithDefaultRealm()
        manager.add_password(None, self.base, device.username, device.password)
        handlers = [urllib.request.HTTPDigestAuthHandler(manager)]
        if device.https:
            # Terminals ship self-signed certificates; the LAN is the trust boundary here.
            handlers.append(urllib.request.HTTPSHandler(context=ssl._create_unverified_context()))  # noqa: S323
        self.opener = urllib.request.build_opener(*handlers)

    def __enter__(self):
        return self

    def __exit__(self, *exc):
        return False

    def describe(self) -> Description:
        text = self._request("GET", "/ISAPI/System/deviceInfo")
        try:
            root = ElementTree.fromstring(text)
        except ElementTree.ParseError as exc:
            raise SourceError(f"{self.device.serial}: deviceInfo was not XML") from exc

        def field(name):
            for element in root.iter():
                if element.tag.rsplit("}", 1)[-1] == name and element.text:
                    return element.text.strip()
            return None

        return Description(serial=field("serialNumber"), vendor="hikvision", model=field("model"),
                           firmware=field("firmwareVersion"))

    def read(self) -> list[Punch]:
        end = self.now()
        start = end - timedelta(hours=self.device.window_hours)
        return [punch for punch in (self._punch(row) for row in self.events(start, end)) if punch]

    def events(self, start: datetime, end: datetime) -> list[dict]:
        """Every access-control event in the window, as the terminal returns it."""
        events: list[dict] = []
        position = 0
        for _ in range(500):
            condition = json.dumps({"AcsEventCond": {
                "searchID": f"workin-{self.device.serial}",
                "searchResultPosition": position,
                "maxResults": self.device.page_size,
                "major": self.MAJOR_ACCESS_CONTROL,
                "minor": 0,
                "startTime": start.astimezone().isoformat(timespec="seconds"),
                "endTime": end.astimezone().isoformat(timespec="seconds"),
            }})
            answer = self._request("POST", "/ISAPI/AccessControl/AcsEvent?format=json", condition)
            try:
                page = json.loads(answer).get("AcsEvent") or {}
            except ValueError as exc:
                raise SourceError(f"{self.device.serial}: AcsEvent search did not answer JSON") from exc
            rows = page.get("InfoList") or []
            events.extend(rows)
            position += int(page.get("numOfMatches", len(rows)) or 0)
            # MORE is the only signal that another page exists. The loop bound stops a firmware
            # that always says MORE from looping forever.
            if str(page.get("responseStatusStrg", "")).upper() != "MORE" or not rows:
                return events
        return events

    def _punch(self, row: dict) -> Punch | None:
        try:
            major, minor = int(row.get("major")), int(row.get("minor"))
        except (TypeError, ValueError):
            return None
        if major != self.MAJOR_ACCESS_CONTROL or minor not in self.device.attendance_minors:
            return None
        pin = str(row.get("employeeNoString") or row.get("employeeNo") or "").strip()
        when = str(row.get("time") or "").strip()
        if not pin or len(when) < 19:
            return None
        # The terminal's own wall clock, offset dropped: what the server stores for every vendor.
        local = when[:19].replace("T", " ")
        status = self.ATTENDANCE_STATUS.get(str(row.get("attendanceStatus", "")).strip().lower())
        return Punch(pin, local, status, None)

    def _request(self, method: str, path: str, body: str | None = None) -> str:
        request = urllib.request.Request(self.base + path, data=body.encode("utf-8") if body else None, method=method)
        if body:
            request.add_header("Content-Type", "application/json")
        try:
            with self.opener.open(request, timeout=self.device.timeout_seconds) as response:
                return response.read().decode("utf-8", "replace")
        except urllib.error.HTTPError as exc:
            raise SourceError(f"{self.device.serial}: {method} {path} answered {exc.code}") from exc
        except (urllib.error.URLError, OSError, TimeoutError) as exc:
            raise SourceError(f"{self.device.serial}: {method} {path} failed: {exc}") from exc


class FileSource:
    """A TSV or USB export standing in for a terminal: how the lab exercises everything downstream."""

    def __init__(self, device: DeviceConfig):
        self.device = device

    def __enter__(self):
        return self

    def __exit__(self, *exc):
        return False

    def describe(self) -> Description:
        return Description(serial=self.device.serial, vendor="zkteco", model="file")

    def read(self) -> list[Punch]:
        try:
            with open(self.device.path, encoding="utf-8", errors="replace") as handle:
                return parse_attlog_lines(handle.read())
        except OSError as exc:
            raise SourceError(f"{self.device.serial}: {exc}") from exc


def parse_attlog_lines(text: str) -> list[Punch]:
    """ATTLOG-shaped lines, as a terminal pushes them or exports them to USB (PIN padded in spaces)."""
    punches = []
    for line in text.lstrip("﻿").splitlines():
        fields = [field.strip() for field in line.split("\t")]
        if len(fields) < 2 or not fields[0]:
            continue
        punches.append(Punch(fields[0], fields[1], _int(fields, 2), _int(fields, 3)))
    return punches


def _int(fields: list[str], index: int) -> int | None:
    try:
        return int(fields[index])
    except (IndexError, ValueError):
        return None
