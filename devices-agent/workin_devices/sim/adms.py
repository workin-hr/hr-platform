"""A pretend ZKTeco terminal that pushes over ADMS, pointed at the platform's /iclock receiver.

Speaks the requests a push-firmware terminal makes, in the shapes documented for them, including
the form-urlencoded content type that would lose the body to a careless server. Each scenario
prints what it sent and what came back, so the lab can be read like a site visit.
"""
from __future__ import annotations

import random
import time
import urllib.error
import urllib.parse
import urllib.request
from dataclasses import dataclass
from datetime import datetime, timedelta


@dataclass
class Answer:
    status: int
    text: str


class PushTerminal:
    def __init__(self, server: str, serial: str, host_header: str | None = None, push_version: str = "2.4.1",
                 device_name: str = "SpeedFace-V5L", firmware: str = "ZAM180-NF-Ver1.3.7", timeout: float = 15.0):
        self.server = server.rstrip("/")
        self.serial = serial
        self.host_header = host_header
        self.push_version = push_version
        self.device_name = device_name
        self.firmware = firmware
        self.timeout = timeout

    def handshake(self) -> Answer:
        query = urllib.parse.urlencode({"SN": self.serial, "options": "all", "pushver": self.push_version,
                                        "language": "69", "DeviceType": "att", "PushOptionsFlag": "1"})
        return self._call("GET", f"/iclock/cdata?{query}")

    def poll(self) -> Answer:
        return self._call("GET", f"/iclock/getrequest?SN={urllib.parse.quote(self.serial)}")

    def attlog(self, lines: list[str], stamp: int = 1) -> Answer:
        body = "\r\n".join(lines) + "\r\n"
        return self._call("POST", f"/iclock/cdata?SN={urllib.parse.quote(self.serial)}&table=ATTLOG&Stamp={stamp}", body)

    def options(self) -> Answer:
        body = f"~DeviceName={self.device_name},FirmVer={self.firmware},PushVersion={self.push_version},UserCount=12"
        return self._call("POST", f"/iclock/cdata?SN={urllib.parse.quote(self.serial)}&table=options", body)

    def operlog(self, lines: list[str]) -> Answer:
        body = "\r\n".join(lines) + "\r\n"
        return self._call("POST", f"/iclock/cdata?SN={urllib.parse.quote(self.serial)}&table=OPERLOG&OpStamp=1", body)

    def _call(self, method: str, path: str, body: str | None = None) -> Answer:
        request = urllib.request.Request(self.server + path, data=body.encode("utf-8") if body is not None else None,
                                         method=method)
        # Terminals post punch batches as a form; a server that parses parameters eagerly loses them.
        if body is not None:
            request.add_header("Content-Type", "application/x-www-form-urlencoded")
        request.add_header("User-Agent", "iClock Proxy/1.09")
        if self.host_header:
            request.add_header("Host", self.host_header)
        try:
            with urllib.request.urlopen(request, timeout=self.timeout) as response:
                return Answer(response.status, response.read().decode("utf-8", "replace"))
        except urllib.error.HTTPError as exc:
            return Answer(exc.code, exc.read().decode("utf-8", "replace"))
        except (urllib.error.URLError, OSError) as exc:
            return Answer(0, str(exc))


def punch_line(pin: str, when: datetime, in_out: int = 0, verify: int = 1) -> str:
    return f"{pin}\t{when:%Y-%m-%d %H:%M:%S}\t{in_out}\t{verify}\t0\t0\t0"


def backlog(pins: list[str], days: int, now: datetime | None = None, seed: int = 3) -> list[str]:
    rng = random.Random(seed)
    today = (now or datetime.now()).replace(hour=0, minute=0, second=0, microsecond=0)
    lines = []
    for offset in range(days, 0, -1):
        day = today - timedelta(days=offset)
        for pin in pins:
            lines.append(punch_line(pin, day + timedelta(hours=8, minutes=rng.randint(0, 45)), 0))
            lines.append(punch_line(pin, day + timedelta(hours=17, minutes=rng.randint(0, 45)), 1))
    return lines


SCENARIOS = ("handshake", "options", "realtime", "duplicate", "backlog", "malformed", "form", "operlog", "oversize")
# Not in "all": these describe a terminal nobody has allocated yet.
UNREGISTERED_SCENARIOS = ("unregistered",)


def run_scenario(terminal: PushTerminal, name: str, pins: list[str], out=print) -> bool:
    """@return whether the answer was what a correct receiver gives a REGISTERED terminal."""
    now = datetime.now().replace(microsecond=0)
    if name == "handshake":
        answer = terminal.handshake()
        good = answer.status == 200 and answer.text.startswith(f"GET OPTION FROM: {terminal.serial}")
        detail = "TimeZone sent (registered)" if "TimeZone=" in answer.text else "no TimeZone (unregistered or inactive)"
    elif name == "options":
        answer = terminal.options()
        good, detail = answer.status == 200, "model and firmware reported"
    elif name == "realtime":
        answer = terminal.attlog([punch_line(pins[0], now, 0)])
        good, detail = answer.status == 200, f"one punch for PIN {pins[0]} at {now}"
    elif name == "duplicate":
        line = punch_line(pins[0], now - timedelta(minutes=5), 0)
        first, answer = terminal.attlog([line]), terminal.attlog([line])
        good, detail = first.status == 200 and answer.status == 200, "the same punch twice; stored once"
    elif name == "backlog":
        lines = backlog(pins, 30, now)
        answer = terminal.attlog(lines[:4000])
        good, detail = answer.status == 200, f"{min(len(lines), 4000)} buffered punches in one upload"
    elif name == "malformed":
        answer = terminal.attlog([punch_line(pins[-1], now - timedelta(minutes=1), 1), "not-a-pin\tyesterday", "12\t2026-02-30 10:00:00"])
        good, detail = answer.status == 200, "one good line and two unreadable ones (quarantined, batch acknowledged)"
    elif name == "form":
        answer = terminal.attlog([punch_line(pins[0], now - timedelta(minutes=2), 1)])
        good, detail = answer.status == 200 and answer.text.startswith("OK"), "form-urlencoded body kept intact"
    elif name == "operlog":
        answer = terminal.operlog(["OPLOG 4\t0\t2026-09-16 09:00:00\t0\t0\t0\t0", "FP PIN=1001\tFID=0\tSize=512\tValid=1\tTMP=AAAA"])
        good, detail = answer.status == 200, "an operation line kept, a fingerprint template discarded"
    elif name == "unregistered":
        hello = terminal.handshake()
        answer = terminal.attlog([punch_line(pins[0], now, 0)])
        good = hello.status == 200 and "TimeZone=" not in hello.text and answer.status == 403
        detail = ("handshake carries no time zone and the upload is refused, so the terminal keeps its punches "
                  "until someone allocates it")
    elif name == "oversize":
        lines = backlog([str(9000 + n) for n in range(100)], 30, now)
        answer = terminal.attlog(lines[:6000])
        good, detail = answer.status == 413, "6,000 lines, above the 5,000 cap: refused whole so the terminal re-sends smaller"
    else:
        raise ValueError(f"unknown scenario {name}")
    out(f"  [{'PASS' if good else 'FAIL'}] {name:<10} HTTP {answer.status} {answer.text.strip()[:60]!r} -- {detail}")
    return good


def live(terminal: PushTerminal, pins: list[str], every_seconds: float, count: int, out=print) -> None:
    """A terminal in use: one scan every few seconds, the way a dashboard watcher will see them arrive."""
    rng = random.Random()
    for index in range(count):
        pin = rng.choice(pins)
        now = datetime.now().replace(microsecond=0)
        answer = terminal.attlog([punch_line(pin, now, index % 2)], stamp=index + 1)
        out(f"  {now:%H:%M:%S} PIN {pin} -> HTTP {answer.status} {answer.text.strip()[:30]}")
        terminal.poll()
        time.sleep(every_seconds)
