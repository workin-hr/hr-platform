"""The site visit as one guided command: ``python3 -m workin_devices visit``.

It walks docs/devices/field-visit-runbook.md in the runbook's order -- the rules, the photos,
finding the terminal, a backup of its log, the push receiver, the agent, the USB export,
Hikvision, putting everything back -- asks the operator only what a program cannot see for
itself, runs every read and check, and ends with one report: what passed, what did not and what
to do about it, and the runbook's results sheet filled in, with no employee code, name or card
number in it. It speaks Egyptian Arabic, as the runbook does, and every question is answered
with a number, so the operator never switches keyboard layout.

Mode A only: every punch goes to the local lab stack, and a terminal is allocated there by
``scripts/devices-lab.sh allocate``. Production (Mode B, runbook section 12) is the repository
owner's to run, and this command has no path to it.

It changes nothing on a terminal: it reads, as the rest of the kit does. Every setting the visit
needs changed, the operator changes by hand, from a photo taken first, and puts back.
"""
from __future__ import annotations

import getpass
import ipaddress
import json
import os
import re
import shutil
import subprocess
import socket
import threading
import time
import traceback
from collections import Counter
from dataclasses import dataclass
from datetime import datetime, timedelta, timezone
from pathlib import Path
from urllib.parse import parse_qs, urlparse

from . import agent, capture, probe, usb
from . import config as cfg
from . import gateway as gw
from . import zk4370 as zk
from .config import DeviceConfig
from .sources import HikvisionSource, SourceError

AGENT_DIR = Path(__file__).resolve().parent.parent
CAPTURE_PORT = 8081
HIK_ATTENDANCE = set(cfg.DEFAULT_HIK_ATTENDANCE_MINORS)
USB_NAME = re.compile(r"attlog.*\.dat$", re.IGNORECASE)

# The runbook's results sheet (its appendix), row for row. test_visit keeps the two identical.
SHEET_ROWS = (
    "الشركة / الفرع", "التاريخ", "الماركة والموديل", "السيريال", "الـ Firmware", "الـ Platform",
    "Push / ADMS موجود؟", "الـ pushver", "الـ DeviceType", "HTTPS موجود في المنيو؟", "الـ Content-Type",
    "الوقت بيتبعت", "أكبر عدد سجلات في رفعة", "ظهر 413؟", "عليه Comm Key؟", "بيرد على",
    "فرق ساعة الجهاز عن اللابتوب", "الـ Time zone بتاع الجهاز", "التوقيت الصيفي (Daylight Saving)", "الساعة",
    "Enable Domain Name موجود؟", "الـ language", "الـ PushOptionsFlag", "الـ Stamp في رفع ATTLOG",
    "الـ TLS versions", "الـ TimeZone بالدقايق", "الـ Acknowledgement dropped", "سطر ATTLOG: عدد الحقول",
    "سطر ATTLOG: الفاصل", "طول كود الموظف", "عدد السجلات على الجهاز", "عدد الموظفين", "بصمة وصلت خلال",
    "شلنا الكابل: البصمات وصلت بعد الرجوع؟", "وقفنا الـ capture 10 دقايق: الجهاز عاد الإرسال؟",
    "البصمة دي اتسجلت مرة واحدة؟", "الدخول", "الخروج", "الـ in_out_field الصح",
    "توزيع أكواد الدخول/الخروج في سجل الجهاز",
    "الموظفين بيدوسوا زرار الدخول/الخروج؟", "USB: ترتيب الأعمدة زي الـ Push؟", "Hikvision: أكواد الحضور اللي ظهرت",
    "حالة الشبكة وقت المشكلة", "مشاكل تانية",
)
# What this visit cannot test (runbook 5.4, under the table): the recorder speaks plain HTTP only,
# and the other two need a change on the terminal or a refusal after an upload.
UNTESTED = ("الـ TLS versions", "الـ TimeZone بالدقايق", "الـ Acknowledgement dropped")
MARK = {"ok": "✅", "warn": "⚠️", "bad": "❌"}

PUSH_HINTS = (
    "الـ firewall: في terminal تاني اكتب  sudo ufw allow 8081/tcp",
    "الـ Server Address على الجهاز لازم يكون IP اللابتوب اللي فوق بالظبط",
    "خلي Enable Domain Name و HTTPS و Proxy كلهم OFF",
    "لو الجهاز طلب restart بعد الحفظ، اعمله",
    "استخدم كابل مش WiFi: شبكات WiFi كتير بتمنع الأجهزة تشوف بعض",
)
PUNCH_HINTS = (
    "اتأكد إن الجهاز قبل البصمة (قال Thank you أو ظهر الاسم)",
    "فيه أجهزة بتبعت كل شوية مش على طول: استنى كمان شوية",
)
AGENT_FIXES = (
    ("not registered", "الجهاز مش متخصص لشركة اللاب: شغّل الأمر تاني واختار نفس الجهاز"),
    ("configured as", "الـ IP ده لجهاز تاني: اتأكد من IP الجهاز (صورة رقم 3)"),
    ("communication key", "الـ Comm Key غلط: اتأكد منه من المنيو"),
    ("did not answer", "الجهاز مش بيرد: استنى شوية وجرّب تاني"),
    ("unreachable", "سيستم اللاب واقف: scripts/devices-lab.sh up"),
    ("retry", "سيستم اللاب مش بيرد: scripts/devices-lab.sh up"),
)


class Console:
    """The terminal. A test replaces it with a scripted one."""

    def say(self, text: str = "") -> None:
        print(text, flush=True)

    def ask(self, prompt: str) -> str:
        return input(prompt + " ").strip()

    def secret(self, prompt: str) -> str:
        return getpass.getpass(prompt + " ")


class Lab:
    """The local lab stack, where a Mode A visit delivers everything (scripts/devices-lab.sh)."""

    def __init__(self, root: Path = AGENT_DIR.parent, env=os.environ):
        self.script = root / "scripts" / "devices-lab.sh"
        self.token_path = root / "devices-agent" / "lab" / "agent.token"
        self.server_url = f"https://localhost:{env.get('LAB_HTTPS_PORT', '18443')}"
        self.receiver_url = f"http://127.0.0.1:{env.get('LAB_HTTP_PORT', '18080')}"
        self.receiver_host = "devices.localhost"

    def gateway(self) -> gw.Gateway:
        token = self.token_path.read_text(encoding="utf-8").strip()
        return gw.Gateway(self.server_url, token, insecure_skip_tls_verify=True)

    def check(self) -> str | None:
        """None when the lab is up and takes the lab agent token; otherwise the command that fixes it.
        The probe is an empty heartbeat: it reads no terminal and delivers nothing."""
        if not self.token_path.exists():
            return "seed"
        try:
            self.gateway().heartbeat([])
        except gw.Unauthorized:
            return "seed"
        except (gw.Retryable, gw.Refused, OSError):
            return "up"
        return None

    def reachable(self) -> bool:
        """Is a lab stack listening, whatever token this checkout has? A stack is one per Docker
        daemon, not one per checkout, so `seed` here rotates the token of whoever is using it."""
        target = urlparse(self.receiver_url)
        try:
            with socket.create_connection((target.hostname, target.port or 80), timeout=2):
                return True
        except OSError:
            return False

    def run(self, command: str) -> int:
        """devices-lab.sh up or seed, printing as it goes: they take minutes."""
        return subprocess.run(["bash", str(self.script), command], check=False).returncode

    def allocate(self, serial: str, vendor: str, zone: str) -> tuple[bool, str]:
        try:
            done = subprocess.run(["bash", str(self.script), "allocate", serial, vendor, zone],
                                  capture_output=True, text=True, timeout=180, check=False)
        except (subprocess.SubprocessError, OSError) as exc:
            return False, f"devices-lab.sh allocate: {exc}"
        return done.returncode == 0, (done.stdout + done.stderr).strip()


@dataclass
class Exchange:
    """One request a terminal made, as the recorder kept it: the body only in capture.recordable's
    shape, held in memory for the checks and never written anywhere by the visit."""
    at: float
    serial: str
    method: str
    path: str
    status: int
    content_type: str
    kept: bytes
    response: bytes
    # What `capture.recordable` did not keep (kinds and sizes, never content), and the body's
    # size. An upload whose lines the recorder could not parse still arrived: the visit says so
    # rather than reporting that nothing came.
    withheld: dict | None = None
    request_bytes: int = 0

    @property
    def query(self) -> dict[str, str]:
        return {key: values[0] for key, values in parse_qs(urlparse(self.path).query).items()}

    def is_handshake(self) -> bool:
        return self.method == "GET" and urlparse(self.path).path == "/iclock/cdata"

    def is_attlog(self) -> bool:
        return (self.method == "POST" and urlparse(self.path).path == "/iclock/cdata"
                and self.query.get("table", "").upper() == "ATTLOG")

    def lines(self) -> list[list[str]]:
        text = self.kept.decode("utf-8", "replace")
        return [[field.strip() for field in line.split("\t")]
                for line in text.splitlines() if line.strip() and not line.startswith("#")]

    def shapes(self) -> list[str]:
        prefix = "# unparsed line shape:"
        return [line[len(prefix):].strip() for line in self.kept.decode("utf-8", "replace").splitlines()
                if line.startswith(prefix)]


class Receiver:
    """capture.py's recorder in front of the lab receiver, handing the visit each exchange as it
    happens. What reaches the disk is exactly what `capture` writes."""

    def __init__(self, out_dir: str, upstream: str | None, host_header: str | None, host: str = "0.0.0.0",
                 port: int = CAPTURE_PORT, clock=time.monotonic):
        self.out_dir, self.upstream, self.host_header = out_dir, upstream, host_header
        self.host, self.port, self.clock = host, port, clock
        self.exchanges: list[Exchange] = []
        self.arrived = threading.Condition()
        self.server = None

    def start(self) -> None:
        receiver = self

        class Watching(capture.Recorder):
            def save(self, identity, meta, request_body, response_body):
                saved = super().save(identity, meta, request_body, response_body)
                content_type = next((value for name, value in meta["request_headers"].items()
                                     if name.lower() == "content-type"), "")
                receiver._add(Exchange(receiver.clock(), identity, meta["method"], meta["path"], meta["status"],
                                       content_type, request_body, response_body,
                                       meta.get("request_body_withheld"), meta.get("request_bytes", 0)))
                return saved

        self.server = capture.serve(self.host, self.port, self.out_dir, self.upstream, self.host_header,
                                    out=lambda line: None, recorder=Watching(self.out_dir))
        self.port = self.server.server_address[1]
        threading.Thread(target=self.server.serve_forever, daemon=True).start()

    def stop(self) -> None:
        if self.server is not None:
            self.server.shutdown()
            self.server.server_close()
            self.server = None

    def _add(self, exchange: Exchange) -> None:
        with self.arrived:
            self.exchanges.append(exchange)
            self.arrived.notify_all()

    def mark(self) -> int:
        with self.arrived:
            return len(self.exchanges)

    def wait(self, until, mark: int, timeout: float):
        """until(exchanges since mark) as soon as it returns something, or None after timeout."""
        deadline = time.monotonic() + timeout
        with self.arrived:
            while True:
                found = until(self.exchanges[mark:])
                if found:
                    return found
                remaining = deadline - time.monotonic()
                if remaining <= 0:
                    return None
                self.arrived.wait(remaining)


@dataclass
class Arrival:
    """One punch as the platform received it; `fields` is None when the recorder kept no line."""
    at: float
    fields: list[str] | None

    @property
    def in_out(self) -> str | None:
        return self.fields[2] if self.fields and len(self.fields) > 2 else None


@dataclass
class PunchTest:
    """What one punch test saw: how long it took, what arrived, and whether that can be said to
    be the punch the operator was just asked for at all."""
    seconds: float
    arrived: list[Arrival]
    blind: bool


@dataclass
class Finding:
    level: str
    text: str
    fix: str = ""


@dataclass
class Push:
    """What the push part learnt that the agent and USB parts compare against."""
    serial: str
    in_value: str | None = None
    out_value: str | None = None


def line_time(fields: list[str]):
    """A punch line's own time, comparable with another line's: the wall clock as the terminal
    wrote it, or the Unix seconds some firmware sends instead. None when it is neither."""
    when = fields[1] if len(fields) > 1 else ""
    if re.fullmatch(r"\d{9,11}", when):
        return int(when)
    return when if re.fullmatch(r"\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2}", when) else None


def newer_than(fields: list[str], latest) -> bool:
    """Is this line's own time at least the newest already seen? A terminal's stored records all
    come from its own clock, so the punch just made cannot be older than one it already holds --
    and a record still draining out of the backlog cannot be newer."""
    when = line_time(fields)
    if when is None or latest is None or type(when) is not type(latest):
        return True
    return when >= latest


def fresh_punches(found: list[Arrival], seen: Counter, latest) -> list[Arrival]:
    """Of what arrived after the operator was asked for a punch, the ones that can BE that punch.

    Counted, not a set: two punches in the same second are two identical lines, and both are
    real. A line the terminal has already delivered as many times as it delivers it is the
    backlog being re-sent, and a record older than one already delivered cannot be the punch
    just made. An upload the recorder kept no line from counts: something did arrive."""
    counts: Counter = Counter()
    fresh = []
    for arrival in found:
        if arrival.fields is None:
            fresh.append(arrival)
            continue
        key = (arrival.fields[0], arrival.fields[1])
        counts[key] += 1
        if counts[key] > seen[key] and newer_than(arrival.fields, latest):
            fresh.append(arrival)
    return fresh


def arrivals(exchanges: list[Exchange], serial: str, since: float = 0.0) -> list[Arrival]:
    """What reached the platform after `since`: one entry per punch line, and one per accepted
    upload the recorder could keep no line from -- that punch arrived too, and reporting it as
    "nothing came" would send the operator back to the terminal for a fault that is not there."""
    found = []
    for exchange in exchanges:
        if exchange.serial != serial or not exchange.is_attlog() or exchange.status != 200 or exchange.at < since:
            continue
        lines = exchange.lines()
        found.extend(Arrival(exchange.at, fields) for fields in lines)
        if not lines:
            found.append(Arrival(exchange.at, None))
    return found


def classify(found: dict) -> tuple[str, str]:
    """A scan result as (kind, label): zk, push, hik or other."""
    ip = found["ip"]
    zk_info = found.get("zk_tcp") if "zk_tcp" in found else found.get("zk_udp")
    if zk_info is not None:
        via = "UDP" if "zk_udp" in found else "4370"
        if zk_info.get("serial"):
            return "zk", f"{ip}: ZKTeco ({via})، سيريال {zk_info['serial']}"
        if zk_info.get("comm_key_required"):
            return "zk", f"{ip}: ZKTeco ({via})، عليه Comm Key"
        return "zk", f"{ip}: ZKTeco؟ ({via} مفتوح بس مارَدّش)"
    guesses = " ".join(str(found.get(f"http_{port}", {}).get("guess", "")) for port in (80, 8080, 443))
    ports = found.get("open_ports", {})
    if "hikvision" in guesses:
        return "hik", f"{ip}: Hikvision"
    if "zkteco" in guesses:
        return "push", f"{ip}: ZKTeco (صفحة ويب، مابيردش على 4370)"
    if "dahua" in guesses or 37777 in ports:
        return "other", f"{ip}: Dahua"
    if 5010 in ports:
        return "other", f"{ip}: Anviz"
    return "other", f"{ip}: جهاز مش معروف (ports {', '.join(str(port) for port in ports)})"


def _masked(text: str) -> str:
    return re.sub(r"[0-9]", "9", re.sub(r"[^\W\d_]", "a", text))[:40]


def push_facts(exchanges: list[Exchange], serial: str) -> tuple[dict[str, str], list[Finding]]:
    """The results-sheet rows a recording answers by itself, and what in it is a problem. Nothing
    here carries a PIN: lengths, counts and formats only."""
    mine = [exchange for exchange in exchanges if exchange.serial == serial]
    facts: dict[str, str] = {}
    findings: list[Finding] = []
    hello = next((exchange for exchange in mine if exchange.is_handshake() and "options" in exchange.query),
                 next((exchange for exchange in mine if exchange.is_handshake()), None))
    if hello is not None:
        for row, key in (("الـ pushver", "pushver"), ("الـ DeviceType", "DeviceType"), ("الـ language", "language"),
                         ("الـ PushOptionsFlag", "PushOptionsFlag")):
            facts[row] = hello.query.get(key, "مابعتهوش")
    uploads = [exchange for exchange in mine if exchange.is_attlog()]
    if not uploads:
        return facts, findings
    facts["الـ Content-Type"] = "، ".join(sorted({exchange.content_type or "مفيش" for exchange in uploads}))
    stamps = {exchange.query.get("Stamp", "") for exchange in uploads}
    facts["الـ Stamp في رفع ATTLOG"] = ("رقم عادي" if all(stamp.isdigit() for stamp in stamps)
                                         else "شكل تاني: " + "، ".join(sorted(_masked(stamp) or "مفيش" for stamp in stamps)))
    facts["أكبر عدد سجلات في رفعة"] = str(max(len(exchange.lines()) + len(exchange.shapes()) for exchange in uploads))
    facts["ظهر 413؟"] = "نعم" if any(exchange.status == 413 for exchange in uploads) else "لا"
    if facts["ظهر 413؟"] == "نعم":
        findings.append(Finding("bad", "الجهاز بعت أكتر من 5000 سجل في رفعة واحدة، والسيستم رفضها (413)",
                                "معلومة مهمة: اكتبها في الـ issue. الجهاز محتفظ بسجلاته ومفيش حاجة ضاعت"))
    lines = [fields for exchange in uploads for fields in exchange.lines()]
    shapes = [shape for exchange in uploads for shape in exchange.shapes()]
    if lines:
        unix = sum(1 for fields in lines if re.fullmatch(r"\d{9,11}", fields[1]))
        facts["الوقت بيتبعت"] = "رقم طويل" if unix == len(lines) else "تاريخ ووقت" if unix == 0 else "الاتنين"
        facts["سطر ATTLOG: عدد الحقول"] = "، ".join(str(count) for count in sorted({len(fields) for fields in lines}))
        facts["سطر ATTLOG: الفاصل"] = "Tab"
        facts["طول كود الموظف"] = "، ".join(str(n) for n in sorted({len(fields[0]) for fields in lines})) + " أرقام"
        if unix:
            findings.append(Finding("warn", "الجهاز بيبعت وقت البصمة رقم طويل (Unix) مش تاريخ ووقت",
                                    "لو الجهاز ده اتقرأ بالـ Push والـ agent مع بعض، كل بصمة هتتسجل مرتين: "
                                    "في البرود استخدم طريقة واحدة بس، واكتبها في الـ issue"))
    if shapes:
        if not lines:
            facts["سطر ATTLOG: الفاصل"] = "غيره"
        findings.append(Finding("bad", f"فيه {len(shapes)} سطر حضور السيستم مافهمهوش، شكله: {shapes[0]}",
                                "الشكل ده (كل رقم 9 وكل حرف a) بيوري الفاصل والطول: اكتبه في الـ issue"))
    return facts, findings


# Below this many records the log cannot carry the argument in_out_field makes from it: a branch
# of a few dozen employees writes this in about two days, and a shorter log can hold a run of
# entries with no exit in it at all, whose one-sided column would then be read as meaningful.
IN_OUT_LOG_MINIMUM = 200
# How much of a long log the second-commonest value in a column must hold for that column to be
# telling entries from exits. Everyone who comes in goes out again, so that column is close to
# balanced; a tenth leaves room for a branch where people often forget the exit key.
IN_OUT_MINORITY_SHARE = 0.10
# And how one-sided a column must be before the log can say it is *not* carrying in/out at all.
# Being balanced is not on its own evidence of meaning -- a terminal where two verification methods
# are both common has a balanced `status` that records how people identified themselves. So the log
# elects nothing by itself: it may only rule a column out, and the other column stands only once
# this says the first cannot be it.
IN_OUT_CONSTANT_SHARE = 0.01


def code_spread(records) -> dict[str, dict[int, int]]:
    """How many records carry each value of the two codes, across the whole log just read."""
    return {name: dict(sorted(Counter(getattr(record, name) for record in records).items()))
            for name in ("punch", "status")}


def in_out_field(check_in: tuple[int, int], check_out: tuple[int, int], out_value: int,
                 spread: dict[str, dict[int, int]] | None = None) -> str | None:
    """Which of a 4370 record's two codes carries in/out, from a check-in punch and a check-out
    punch as (punch, status): the code that CHANGED to the check-out value. One record cannot
    answer this -- a terminal whose verify mode is 1 (fingerprint) reads 1 in `status` on every
    punch, and reading a single check-out record would call that the in/out code.

    A terminal can store the check-out with both codes identical to the check-in's, and then the
    two punches answer nothing. The log already on the laptop can still *rule a column out*: a
    column holding one value in all but a hundredth of thousands of punches is not the one that
    separates an arrival from a departure, because a log that long contains both. Only then does
    the other column stand, and only if it is itself balanced.

    The log never elects a column on its own. A branch where people rarely press the exit key has a
    one-sided in/out column, and a terminal used with two verification methods has a balanced
    `status`; electing "the balanced one" there would hand back the verification column, and
    `agent_zk` writes that straight into the config and sends the whole log under it. Ambiguous
    stays None: the visit reports it rather than picking one."""
    changed = [name for index, name in ((0, "punch"), (1, "status"))
               if check_out[index] == out_value and check_in[index] != check_out[index]]
    if len(changed) == 1:
        return changed[0]
    if not spread or sum(spread.get("punch", {}).values()) < IN_OUT_LOG_MINIMUM:
        return None
    for name, other in (("punch", "status"), ("status", "punch")):
        if separates(spread.get(name) or {}) and cannot_separate(spread.get(other) or {}):
            return name
    return None


def separates(counts: dict[int, int]) -> bool:
    """Is this column balanced enough to be the one telling an entry from an exit? Necessary, and
    on its own never sufficient -- see `in_out_field`."""
    counted = sorted(counts.values(), reverse=True)
    return len(counted) > 1 and counted[1] >= sum(counted) * IN_OUT_MINORITY_SHARE


def cannot_separate(counts: dict[int, int]) -> bool:
    """Is this column so one-sided that it cannot be carrying in/out at all?

    Not "does it ever change": TERMINAL-A's `status` reads 1 in 11 424 of its 11 426 records and
    15 in the other two. Two records out of eleven thousand are somebody identifying themselves
    differently, not the branch going home."""
    counted = sorted(counts.values(), reverse=True)
    total = sum(counted)
    return total > 0 and (len(counted) == 1 or counted[1] < total * IN_OUT_CONSTANT_SHARE)


def unreachable_errno(error) -> int | None:
    """The errno of a connect that never reached the terminal, read out of whatever the visit holds
    -- the exception, or the text an agent pass has already flattened it into.

    The number is taken from the exception wherever one survives -- `ZkError` chains the `OSError`
    it was raised from -- because that holds on every platform. Only the flattened text needs
    parsing, and then both spellings count: Python writes `[Errno 113]` on Linux and
    `[WinError 10065]` on Windows for the same condition, and this ships as a Windows executable.
    Only the errnos that mean the packet never left qualify: they are the ones that say nothing at
    all about the terminal."""
    found = getattr(error, "errno", None)
    if found is None:
        found = getattr(getattr(error, "__cause__", None), "errno", None)
    if found is None:
        posix = re.search(r"\[Errno (\d+)\]", str(error))
        windows = re.search(r"\[WinError (\d+)\]", str(error))
        if posix:
            found = int(posix.group(1))
        elif windows:
            found = zk.WINDOWS_TRANSIENT_CODES.get(int(windows.group(1)))
    return found if found in zk.TRANSIENT_CONNECT_ERRNOS else None


def describe_network(facts: dict) -> str:
    """This laptop's own link, in one line an operator can read out and paste into an issue."""
    parts = []
    wifi = facts.get("wifi") or {}
    if wifi.get("level") is not None:
        parts.append(f"الواي فاي {wifi['interface']} {wifi['level']} dBm "
                     f"(link {wifi['link']}، discarded misc {wifi['misc']})")
    elif wifi.get("unavailable"):
        parts.append(f"الواي فاي: {wifi['unavailable']}")
    if facts.get("arp"):
        # The state, never the address: this line is written to be pasted into a public issue, and
        # the customer's internal addressing stays in field-report/ on the laptop.
        parts.append(f"ARP الجهاز: {facts['arp']}")
    ping = facts.get("ping") or {}
    if "loss_percent" in ping:
        spread = ping.get("rtt_ms") or {}
        parts.append(f"ping {ping['loss_percent']}% loss، {ping['duplicates']} مكرر"
                     + (f"، {spread['min']}-{spread['max']} ms" if spread else ""))
    elif ping.get("unavailable"):
        parts.append(f"ping: {ping['unavailable']}")
    return "، ".join(parts) if parts else "مفيش أرقام عن شبكة اللابتوب ده"


def usb_files(out_dir: Path, user: str | None = None) -> list[Path]:
    """USB exports already copied into field-report, and any on a mounted flash drive."""
    user = user or getpass.getuser()
    roots = [out_dir] + [base / user / "*" for base in (Path("/media"), Path("/run/media"))]
    found: list[Path] = []
    for root in roots:
        for top in sorted(root.parent.glob(root.name)) if "*" in root.name else [root]:
            if not top.is_dir():
                continue
            for depth in ("*", "*/*"):
                found.extend(path for path in sorted(top.glob(depth)) if path.is_file() and USB_NAME.search(path.name))
    return found


def _toml(value: str) -> str:
    # JSON's string escapes are TOML basic-string escapes.
    return json.dumps(value)


def _md(value: str) -> str:
    """Text for the report: a terminal's own strings reach it, so nothing in them is markup."""
    return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("|", "\\|")


DOTTED = re.compile(r"\b\d{1,3}(?:\.\d{1,3}){3}\b")
# Not covered by `is_private` on every Python this ships on, and a branch can sit behind it.
CARRIER_NAT = ipaddress.ip_network("100.64.0.0/10")


def _no_address(value: str) -> str:
    """One seam for the whole report: no line of it names an address on the customer's network.

    The report exists to be pasted into a public issue, and a terminal's own error text names the
    address it could not reach -- so redacting at each call site would hold only until the next
    finding was written. The console still shows the operator which address it was, and the address
    stays in field-report/ on the laptop.

    Only addresses that are private, loopback, link-local or carrier-NAT: those are what a branch
    LAN is made of, and the exemption is what keeps a four-part firmware version -- which is also a
    valid dotted quad -- readable in the report it is evidence in."""
    def one(match):
        try:
            found = ipaddress.IPv4Address(match.group(0))
        except ValueError:
            return match.group(0)
        internal = (found.is_private or found.is_loopback or found.is_link_local
                    or found in CARRIER_NAT)
        return "<device-ip>" if internal else match.group(0)
    return DOTTED.sub(one, value)


def _ltr(value: str) -> str:
    """A report cell GitHub would draw backwards in a right-to-left table: one with no Arabic."""
    value = _md(value)
    return f'<span dir="ltr">{value}</span>' if value and not re.search(r"[\u0600-\u06ff]", value) else value


class Visit:
    def __init__(self, console=None, lab: Lab | None = None, out_dir: str | Path | None = None,
                 capture_host: str = "0.0.0.0", capture_port: int = CAPTURE_PORT, wait_seconds: float = 180,
                 settle_seconds: float = 15, pause_seconds: float = 600, quiet_seconds: float = 25,
                 sleep=time.sleep, clock=time.monotonic,
                 now=datetime.now, utc_now=lambda: datetime.now(timezone.utc), networks=probe.lan_networks,
                 scan=probe.scan, laptop_network=probe.laptop_network):
        self.console = console or Console()
        self.lab = lab or Lab()
        self.out = Path(out_dir or AGENT_DIR / "field-report")
        self.capture_host, self.capture_port = capture_host, capture_port
        self.wait_seconds, self.settle_seconds, self.pause_seconds = wait_seconds, settle_seconds, pause_seconds
        self.quiet_seconds = quiet_seconds
        self.sleep, self.clock, self.now, self.utc_now = sleep, clock, now, utc_now
        self.networks, self.scan, self.laptop_network = networks, scan, laptop_network
        self.sheet = {row: "لم يُختبر" if row in UNTESTED else "" for row in SHEET_ROWS}
        self.code_spread: dict[str, dict[int, int]] = {}
        self.findings: list[Finding] = []
        self.serial: str | None = None
        self.allocated: dict[str, str] = {}
        self.receiver: Receiver | None = None
        self.push_mark = 0
        self.push: Push | None = None
        self.agent_sent = False
        self.push_configured = False
        self.network_ok = True
        self.secrets: list[Path] = []
        self.zk_link: tuple[str, int, int, bool] | None = None
        self.device_started = False
        self.interrupted = False
        self.restored = False
        self.started = now()

    # -- talking ---------------------------------------------------------------------------

    def say(self, text: str = "") -> None:
        self.console.say(text)

    def title(self, text: str) -> None:
        self.say()
        self.say(f"━━━━━━━━  {text}  ━━━━━━━━")

    def note(self, level: str, text: str, fix: str = "") -> None:
        self.findings.append(Finding(level, text, fix))
        self.say(f"{MARK[level]} {text}" + (f"\n   ← الحل: {fix}" if fix else ""))

    def choose(self, question: str, options: list[tuple[object, str]]):
        self.say(f"👉 {question}")
        for number, (_, label) in enumerate(options, 1):
            self.say(f"   {number}) {label}")
        while True:
            answer = self.console.ask("   اكتب الرقم (Enter = 1):")
            if not answer:
                return options[0][0]
            if answer.isdigit() and 1 <= int(answer) <= len(options):
                return options[int(answer) - 1][0]
            self.say("   الرقم ده مش في القايمة.")

    def yes(self, question: str, default: bool = True) -> bool:
        options = [(True, "أيوه"), (False, "لأ")]
        return self.choose(question, options if default else options[::-1])

    def enter(self, text: str) -> None:
        self.console.ask(f"👉 {text}" if "Enter" in text else f"👉 {text}، ودوس Enter")

    # -- the visit -------------------------------------------------------------------------

    def run(self) -> int:
        try:
            self.welcome()
            if self.preflight() and self.rules():
                self.photos()
                self.device_started = True
                self.visit_device()
        except (KeyboardInterrupt, EOFError):
            self.say()
            self.interrupted = True
            self.note("warn", "الزيارة اتوقفت قبل ما تخلص",
                      "رجّع أي إعداد غيّرته على الجهاز زي الصورة، وشغّل الأمر تاني لما تكون جاهز")
        except Exception as exc:  # noqa: BLE001 - a bug in the wizard must not cost the visit its report
            traceback.print_exc()
            self.note("bad", f"الأمر وقف بسبب غلطة فيه: {exc!r}",
                      "كمّل الخطوات اللي فاضلة يدوي من الدليل، ورجّع أي إعداد غيّرته، وابعت الرسالة اللي فوق للفريق")
        finally:
            # Ctrl-C, a bug, a terminal that never answered: the terminal may already be pointed
            # at this laptop, so the checklist that points it back is not on the happy path.
            self.restore()
            self.close()
        report = self.write_report() if self.device_started else None
        self.summary(report)
        # A visit that examined nothing is not a pass. Zero says "this model behaved", which is
        # what the caller and the operator both read it as.
        return 0 if self.examined() and not self.interrupted and not any(
            finding.level == "bad" for finding in self.findings) else 1

    def welcome(self) -> None:
        self.title("زيارة الشركة: خطوة بخطوة")
        self.say("الأمر ده بيمشي معاك في الزيارة كلها بالترتيب، وبيعمل الفحوصات لوحده.")
        self.say("لما يحتاج منك حاجة هيكتب 👉 وتجاوب برقم. في الآخر هيقولك النتيجة ويكتب تقرير.")
        self.say("كل حاجة بتروح لسيستم اللاب على اللابتوب (وضع A). البرود (وضع B) لصاحب الريبو بس.")
        self.sheet["الشركة / الفرع"] = self.console.ask("👉 اسم الشركة والفرع (للتقرير بس، ممكن تسيبه فاضي):")
        self.sheet["التاريخ"] = f"{self.started:%Y-%m-%d}"

    def preflight(self) -> bool:
        self.title("قبل ما نبدأ: اللابتوب")
        problem = self.lab.check()
        if problem == "up" and self.yes("سيستم اللاب مش شغال على اللابتوب. أشغّله دلوقتي؟ (أول مرة بياخد كام دقيقة)"):
            self.lab.run("up")
            problem = self.lab.check()
        if problem == "seed":
            if self.lab.reachable():
                self.say("⚠️ سيستم اللاب شغال بس مفيش توكن agent في الفولدر ده. ممكن يكون شغال من نسخة تانية من "
                         "الريبو: لو عملت seed، التوكن القديم هيتلغي والـ agent اللي شغال هناك هيقف.")
            if self.yes("أعمل seed دلوقتي؟ (بيعمل توكن جديد ويلغي القديم)", default=False):
                self.lab.run("seed")
                problem = self.lab.check()
        if problem:
            self.note("bad", "سيستم اللاب مش جاهز",
                      "في terminal في فولدر hr-platform: scripts/devices-lab.sh up وبعدها scripts/devices-lab.sh seed، "
                      "وبعدين شغّل الأمر ده تاني")
            return False
        self.out.mkdir(parents=True, exist_ok=True)
        self.say("✅ سيستم اللاب شغال والتوكن تمام.")
        return True

    def rules(self) -> bool:
        self.title("0. قواعد ممنوع تكسرها")
        for rule in ("ماتمسحش أي حاجة من الجهاز (لا سجلات ولا موظفين ولا بصمات)، ولا من المنيو.",
                     "ماتغيّرش ساعة أو تاريخ أو time zone الجهاز.",
                     "صوّر أي شاشة إعدادات قبل ما تغيّرها، ورجّعها زي الصورة في الآخر.",
                     "ابعد عن مواعيد الحضور والانصراف (أول ساعة وآخر ساعة في الشيفت).",
                     "ملفات field-report فيها أكواد موظفين ومواعيد: خليها على اللابتوب بس."):
            self.say(f"   • {rule}")
        if not self.yes("العميل وافق إنك توصّل اللابتوب على الشبكة بتاعته وتدوّر على الأجهزة؟"):
            self.network_ok = False
            self.say("تمام: من غير موافقة مش هنلمس الشبكة. نقدر نرفع ملف USB بس.")
        return True

    def photos(self) -> None:
        self.title("2. صوّر الجهاز قبل ما تلمسه")
        for number, screen in enumerate(("الستيكر اللي ورا الجهاز (Model و SN و MAC)",
                                         "Menu → System Info → Device Info",
                                         "Menu → Comm. → Ethernet (IP الجهاز)",
                                         "Menu → Comm. → Cloud Server Setting أو ADMS (دي اللي هترجّعها في الآخر)",
                                         "Menu → System → Date Time (الساعة والتوقيت الصيفي و NTP)",
                                         "Menu → Data Mgt. (عدد السجلات والموظفين)"), 1):
            self.say(f"   {number}) {screen}")
        self.say("   أجهزة Hikvision: نفس الحاجات من صفحة الويب بتاعتها.")
        self.enter("لما تخلص الصور")

    def visit_device(self) -> None:
        kind, host, port = ("usb", None, None) if not self.network_ok else self.find()
        if kind == "zk":
            self.zk_flow(host, port)
        elif kind == "push":
            self.push_flow(None, host)
            self.usb_flow(self.serial)
        elif kind == "hik":
            self.hik_flow(host, port)
        elif kind == "other":
            self.other_flow(host)
        else:
            self.usb_flow(None)

    def find(self) -> tuple[str, str | None, int | None]:
        self.title("3. دوّر على الجهاز")
        networks = self.networks()
        for address, network in networks:
            self.say(f"   اللابتوب: {address} على الشبكة {network}")
        results: list[dict] = []
        if not networks:
            self.note("warn", "اللابتوب مش متوصّل بأي شبكة", "وصّل كابل الشبكة واتأكد إن اللابتوب خد IP")
        else:
            address, cidr = networks[0] if len(networks) == 1 else self.choose(
                "أنهي شبكة فيها الجهاز؟", [((address, network), f"{network} (اللابتوب {address})")
                                          for address, network in networks])
            if ipaddress.ip_network(cidr).num_addresses > 1024:
                cidr = str(ipaddress.ip_interface(f"{address}/24").network)
                self.say(f"   الشبكة كبيرة، هدوّر في {cidr} بس.")
            self.say(f"🔎 بدوّر على الأجهزة في {cidr} (ممكن ياخد دقيقة)...")
            results = self.scan(cidr, out=lambda line: None)
            with open(self.out / f"scan-{self.now():%Y%m%d-%H%M%S}.json", "w", encoding="utf-8") as handle:
                json.dump(results, handle, indent=2, ensure_ascii=False)
            self.say(f"   لقيت {len(results)} جهاز.")
        options = [((kind, found["ip"], None), label) for found in results for kind, label in [classify(found)]]
        options += [(("push", None, None), "جهاز ZKTeco بيعمل Push ومش ظاهر هنا"),
                    (("manual", None, None), "اكتب IP الجهاز بإيدك"),
                    (("usb", None, None), "ملف USB بس (الجهاز مش على الشبكة)")]
        kind, host, port = self.choose("أنهي جهاز هنجرّب؟", options)
        if kind != "manual":
            return kind, host, port
        while True:
            text = self.console.ask("👉 اكتب IP الجهاز (مثلاً 192.168.1.201):")
            host, _, port_text = text.partition(":")
            try:
                ipaddress.ip_address(host)
                port = int(port_text) if port_text else None
                break
            except ValueError:
                self.say("   ده مش IP.")
        if port is not None:
            kind = self.choose("نوع الجهاز؟", [("zk", "ZKTeco (4370)"), ("hik", "Hikvision"), ("other", "حاجة تانية")])
            return kind, host, port
        found = probe.probe_host(host, retry_transient=True)
        if not found:
            self.say(f"   مفيش حاجة بترد على {host}.")
            self.note("bad", "مفيش حاجة بترد على العنوان اللي اتكتب",
                      "اتأكد من IP الجهاز (صورة رقم 3) ومن الكابل")
            return "none", None, None
        kind, label = classify(found)
        self.say(f"   {label}")
        return kind, host, None

    def device(self, serial: str, vendor: str, model: str | None = None, firmware: str | None = None,
               platform: str | None = None) -> None:
        self.serial = serial
        self.sheet["السيريال"] = serial
        self.sheet["الماركة والموديل"] = f"{vendor} {model or ''}".strip()
        if firmware:
            self.sheet["الـ Firmware"] = firmware
        if platform:
            self.sheet["الـ Platform"] = platform

    # -- ZKTeco on 4370 (runbook 4 and 6) -----------------------------------------------------

    def zk_flow(self, host: str, port: int | None) -> None:
        summary = self.zk_connect(host, port or 4370)
        if summary is None:
            return
        serial = summary["serial"]
        if not self.backup(serial):
            return
        if not self.yes(f"السيريال على الستيكر هو {serial}؟"):
            self.note("bad", "السيريال اللي الجهاز بيقوله مش زي الستيكر",
                      "اكتب الاتنين في الـ issue مع صورة الستيكر وشاشة Device Info")
        if not self.yes(f"عدد السجلات على شاشة الجهاز (Data Mgt.) {summary['records']}؟"):
            self.note("warn", "عدد السجلات على الشاشة مش زي اللي اتقرأ",
                      "ممكن حد عمل بصمة دلوقتي. لو الفرق كبير اكتبه في الـ issue")
        if self.yes("الجهاز فيه شاشة Cloud Server Setting أو ADMS؟ (صورة رقم 4)", default=False):
            self.push_flow(serial, host)
        else:
            self.sheet["Push / ADMS موجود؟"] = "لا"
        self.agent_zk(summary)
        self.usb_flow(serial)

    def zk_connect(self, host: str, port: int) -> dict | None:
        self.title("4. قراءة جهاز ZKTeco ونسخة احتياطية (قراءة بس)")
        udp, key = False, 0
        summary = probe.zk_summary(host, port, key, udp, timeout=8)
        if "error" in summary:
            self.say("   مارَدّش على TCP، بجرّب UDP...")
            other = probe.zk_summary(host, port, key, True, timeout=8)
            if "error" not in other:
                summary, udp = other, True
        while summary.get("comm_key_required"):
            text = self.console.ask("👉 الجهاز عليه Comm Key (Menu → Comm. → Connection). اكتبه، أو Enter عشان توقف:")
            if not text:
                self.note("bad", "الجهاز عليه Comm Key ومقدرناش نقرأه", "اسأل العميل على الـ Comm Key وشغّل الأمر تاني")
                return None
            if not text.isdigit():
                self.say("   الـ Comm Key أرقام بس.")
                continue
            key = int(text)
            summary = probe.zk_summary(host, port, key, udp, timeout=8)
        if "error" in summary:
            self.note("bad", f"الجهاز مارَدّش: {summary['error']}",
                      self.unreachable_path_fix(summary["error"], host)
                      or "اتأكد من IP الجهاز (صورة رقم 3) ومن الكابل، واستنى شوية وجرّب تاني (ممكن الجهاز مشغول)")
            return None
        serial = str(summary.get("serial") or "")
        if not cfg.SERIAL.match(serial):
            # The serial names every file the visit writes for this terminal; one the platform
            # refuses (or one with a path in it) goes no further.
            self.note("bad", f"الجهاز رد بسيريال السيستم مش هيقبله: {serial!r}",
                      "اكتبه في الـ issue مع صورة الستيكر وشاشة Device Info")
            return None
        self.zk_link = (host, port, key, udp)
        self.device(serial, "ZKTeco", summary.get("device_name"), summary.get("firmware"),
                    summary.get("platform"))
        self.sheet["عليه Comm Key؟"] = "نعم" if key else "لا"
        self.sheet["بيرد على"] = "UDP" if udp else "TCP"
        self.sheet["عدد السجلات على الجهاز"] = str(summary.get("records"))
        self.sheet["عدد الموظفين"] = str(summary.get("users"))
        self.note("ok", f"الجهاز رد: {summary['serial']}، firmware {summary.get('firmware')}، "
                        f"{summary.get('records')} سجل، {summary.get('users')} موظف")
        seconds = self.clock_skew(summary)
        self.sheet["فرق ساعة الجهاز عن اللابتوب"] = (f"{seconds} ثانية قدام اللابتوب" if seconds > 0 else
                                                  f"{-seconds} ثانية ورا اللابتوب" if seconds < 0 else "مفيش فرق")
        if abs(seconds) > 120:
            self.note("warn", f"ساعة الجهاز بعيدة عن ساعة اللابتوب بـ {abs(seconds)} ثانية",
                      "ماتغيّرهاش (قاعدة 2): اكتبها بس في الـ issue")
        return summary

    def clock_skew(self, summary: dict) -> int:
        return round((datetime.fromisoformat(summary["device_time"])
                      - datetime.fromisoformat(summary["laptop_time"])).total_seconds())

    def check_the_clock_did_not_move(self, before: int) -> None:
        """Rule 2: the visit must not change the terminal's clock. It does not set one -- but the
        platform sends an allocated terminal its time zone on every handshake, and some firmware
        applies it. Reading the clock again is the only way to notice that happening."""
        host, port, key, udp = self.zk_link
        summary = probe.zk_summary(host, port, key, udp, timeout=8)
        if "error" in summary or "device_time" not in summary:
            return
        moved = self.clock_skew(summary) - before
        if abs(moved) > 120:
            self.note("bad", f"ساعة الجهاز اتغيرت {abs(moved)} ثانية من أول الزيارة",
                      "ماتغيّرهاش إنت: بلّغ العميل، وشوف الـ time zone اللي اتخصص للجهاز، "
                      "واكتبها في الـ issue فوراً (ده معناه إن الجهاز بيغيّر ساعته من السيستم)")
        else:
            self.note("ok", "ساعة الجهاز زي ما كانت من أول الزيارة")

    def backup(self, serial: str) -> bool:
        host, port, key, udp = self.zk_link
        path = self.out / f"{serial}-attlog-backup.tsv"
        self.say("   بخزّن نسخة من سجل الحضور على اللابتوب...")
        try:
            count = probe.backup_attendance(host, str(path), port, key, udp)
        except zk.ZkError as exc:
            self.note("bad", f"النسخة الاحتياطية ماتعملتش: {exc}",
                      self.unreachable_path_fix(exc)
                      or "ماتغيّرش أي إعداد على الجهاز من غير نسخة. استنى شوية وشغّل الأمر تاني")
            return False
        self.note("ok", f"النسخة الاحتياطية اتعملت: {count} سجل في field-report/{path.name}")
        return True

    def unreachable_path_fix(self, error, host: str | None = None) -> str | None:
        """The remedy for a terminal this laptop never reached, with the measured numbers in it.

        EHOSTUNREACH means the kernel could not resolve the address's hardware address and no
        packet left, so whatever is wrong, the terminal has not answered and cannot be the thing
        the error describes. That is as far as the errno goes: the same one arrives from a mistyped
        address, a terminal that is switched off, and a lost ARP exchange on a weak link. The
        remedy therefore names the path, gives the operator both ends to check, and leaves the
        numbers beside it to say which end -- rather than clearing the terminal, which would file a
        real device fault as somebody else's problem."""
        if unreachable_errno(error) is None:
            return None
        target = host or (self.zk_link[0] if self.zk_link else None)
        facts = self.laptop_network(target, ping_count=5)
        measured = describe_network(facts)
        self.sheet["حالة الشبكة وقت المشكلة"] = measured
        wireless = (facts.get("wifi") or {}).get("level") is not None
        return ("مفيش ولا حزمة وصلت للجهاز أصلاً (الـ ARP فشل)، فالجهاز ماردّش عشان ماتسألش. "
                + measured
                + ". اتأكد الأول إن الـ IP ده بتاع الجهاز فعلاً (صورة رقم 3) وإن الجهاز شغال؛ "
                "لو الاتنين تمام، فالمشكلة في الشبكة: "
                + ("قرّب اللابتوب من الراوتر أو اتوصّل بكابل"
                   if wireless else "اتأكد من الكابل والـ switch وإن الجهاز على نفس الشبكة")
                + "، وبعدين جرّب الخطوة دي تاني")

    def zk_records(self) -> list[zk.RawAttendance]:
        host, port, key, udp = self.zk_link
        with zk.ZkClient(host, port, 15, key, udp) as client:
            records = client.attendance()
        # Kept for in_out_field: every full read refreshes what the stored log says about the codes.
        self.code_spread = code_spread(records)
        return records

    def agent_zk(self, summary: dict) -> None:
        self.title("6. قراءة الجهاز عن طريق الـ agent")
        serial = summary["serial"]
        device_time = datetime.fromisoformat(summary["device_time"])
        if not self.allocate(serial, "zkteco", self._offset(device_time.replace(tzinfo=timezone.utc))):
            return
        field = self.find_in_out()
        host, port, key, udp = self.zk_link
        path = self.out / f"{serial}-zk.toml"
        path.write_text(self._agent_toml(f"{serial}-zk-{self.started:%Y%m%d-%H%M}.sqlite3",
                                         in_out_field=field or "punch") +
                        f"\n[[devices]]\nserial = {_toml(serial)}\nkind = \"zk\"\nhost = {_toml(host)}\n"
                        f"port = {port}\ncomm_key = {key}\nudp = {'true' if udp else 'false'}\n", encoding="utf-8")
        self.send_twice(path)
        self.check_the_clock_did_not_move(self.clock_skew(summary))

    def find_in_out(self) -> str | None:
        """Runbook 6.3, before anything is sent: which of the record's two codes carries in/out.

        It takes a check-in punch and a check-out punch from the employee, because one record
        cannot answer the question -- on a terminal whose verify mode is 1, a single check-out
        record reads 1 in both codes. The code that CHANGED between the two is the in/out one.
        """
        check_in = self.one_new_punch("لما تدوس Enter، خلّي الموظف يعمل بصمة دخول عادية")
        if check_in is None:
            return None
        check_out = self.one_new_punch("لما تدوس Enter، خلّي الموظف يدوس زرار Check-Out (أو F2) ويعمل بصمة")
        if check_out is None:
            return None
        out_value, source = 1, "الرقم المعتاد للخروج"
        pushed = self.pushed_line(check_out)
        if pushed and pushed.in_out and pushed.in_out.isdigit():
            out_value, source = int(pushed.in_out), "الرقم اللي الجهاز بعته بالـ Push لنفس البصمة"
        elif self.push and self.push.out_value and self.push.out_value != self.push.in_value:
            out_value, source = int(self.push.out_value), "رقم الخروج في تجربة الـ Push"
        self.say(f"   بصمة الدخول: punch={check_in.punch}، status={check_in.status}")
        self.say(f"   بصمة الخروج: punch={check_out.punch}، status={check_out.status} ({source}: {out_value})")
        if self.code_spread:
            self.sheet["توزيع أكواد الدخول/الخروج في سجل الجهاز"] = (
                f"punch {self.code_spread['punch']}، status {self.code_spread['status']}")
        field = in_out_field((check_in.punch, check_in.status), (check_out.punch, check_out.status), out_value,
                             self.code_spread)
        if field is None:
            self.sheet["الـ in_out_field الصح"] = "مش واضح"
            self.note("bad", f"مش واضح أنهي عمود فيه الدخول والخروج: الدخول (punch={check_in.punch}، "
                             f"status={check_in.status}) والخروج (punch={check_out.punch}، status={check_out.status})"
                             + (f"، وفي سجل الجهاز punch {self.code_spread['punch']} و status "
                                f"{self.code_spread['status']}" if self.code_spread else ""),
                      "اتأكد إن الموظف داس زرار الخروج في البصمة التانية بس، وجرّب تاني؛ "
                      "لو نفس النتيجة اكتب الأرقام دي في الـ issue")
            return None
        if (check_in.punch, check_in.status) == (check_out.punch, check_out.status):
            self.note("warn", f"الجهاز سجّل بصمة الخروج بنفس الكودين بتاعين الدخول "
                              f"(punch={check_out.punch}، status={check_out.status})، "
                              f"والعمود اتحدد من سجل الجهاز نفسه",
                      "يا إما الموظف مادسش زرار الخروج، يا إما الفيرموير ده مابيغيّرش الكود: "
                      "اكتب التوزيع اللي في ورقة النتائج في الـ issue")
        self.sheet["الـ in_out_field الصح"] = field
        index = 0 if field == "punch" else 1
        self.sheet["الدخول"] = str((check_in.punch, check_in.status)[index])
        self.sheet["الخروج"] = str((check_out.punch, check_out.status)[index])
        self.note("ok", f"الدخول والخروج بيتقروا من عمود {field}")
        return field

    def one_new_punch(self, instruction: str):
        """The record the agreed employee just made. Another employee can punch in the same
        seconds, so more than one new record is a question, never a guess."""
        try:
            # Counted, not a set: a terminal stores time to the second, so two punches in the
            # same second are two records that look identical, and a set would hide the second.
            before = Counter((record.user_id, record.timestamp) for record in self.zk_records())
        except zk.ZkError as exc:
            self.note("bad", f"مقدرناش نقرأ سجلات الجهاز: {exc}",
                      self.unreachable_path_fix(exc) or "استنى شوية وشغّل الأمر تاني")
            return None
        self.mark_push()
        self.enter(instruction)
        while True:
            self.sleep(self.settle_seconds)
            try:
                seen, new, error = Counter(), [], None
                for record in self.zk_records():
                    key = (record.user_id, record.timestamp)
                    seen[key] += 1
                    if seen[key] > before[key]:
                        new.append(record)
            except zk.ZkError as exc:
                new, error = [], exc
            if new or not self.yes("مالقيتش بصمة جديدة على الجهاز" + (f" ({error})" if error else "") +
                                   ". أستنى وأقرأ تاني؟"):
                break
        if not new:
            # The last read's error, not just the retry prompt's: an unreachable terminal after the
            # punch is a network finding with the ARP and ping numbers behind it, and reporting it as
            # "the punch was not found" would say the terminal rejected a punch it never saw.
            self.note("bad", "مالقيناش البصمة على الجهاز" + (f": {error}" if error else ""),
                      (self.unreachable_path_fix(error) if error else None)
                      or "جرّب تاني، وخلي الموظف يستنى لحد ما الجهاز يقبل البصمة")
            return None
        if len(new) == 1:
            return new[0]
        if len(new) > 6:
            self.note("bad", f"لقينا {len(new)} بصمة جديدة في نفس الوقت", "استنى لحد ما الزحمة تخف وشغّل الأمر تاني")
            return None
        return self.choose("فيه أكتر من بصمة جديدة. أنهي واحدة بتاعة الموظف اللي معاك؟",
                           [(record, f"كود {record.user_id} الساعة {record.timestamp:%H:%M:%S}") for record in new])

    def mark_push(self) -> None:
        self.push_mark = self.receiver.mark() if self.receiver else 0

    def pushed_line(self, record) -> Arrival | None:
        """The same punch as the terminal pushed it, when it pushes too: the in/out value the
        platform will store, read from the terminal's own upload rather than assumed."""
        if not self.receiver or not self.receiver.server:
            return None
        serial = self.push.serial if self.push else self.serial
        stamp = f"{record.timestamp:%Y-%m-%d %H:%M:%S}"
        return self.receiver.wait(
            lambda exchanges: next((arrival for arrival in arrivals(exchanges, serial)
                                    if arrival.fields and arrival.fields[0] == record.user_id
                                    and arrival.fields[1] == stamp), None),
            self.push_mark, self.wait_seconds / 6)

    def _agent_toml(self, spool: str, in_out_field: str = "punch") -> str:
        return (f"# Written by `workin_devices visit` for the lab (Mode A).\n"
                f"server_url = {_toml(self.lab.server_url)}\ntoken_file = {_toml(str(self.lab.token_path))}\n"
                f"spool_path = {_toml(spool)}\ninsecure_skip_tls_verify = true\nin_out_field = {_toml(in_out_field)}\n")

    def send_twice(self, path: Path) -> None:
        """Runbook 6.4: the first pass sends the log, the second must store nothing."""
        try:
            config = cfg.load(str(path))
        except cfg.ConfigError as exc:
            self.note("bad", f"ملف الإعداد فيه مشكلة: {exc}", "اكتب الرسالة دي في الـ issue")
            return
        self.say("   ببعت سجل الجهاز لسيستم اللاب (once)...")
        try:
            first = agent.run_once(config)[0]
            if not first.error:
                self.sleep(1)
                second = agent.run_once(config)[0]
        except gw.Unauthorized:
            self.note("bad", "سيستم اللاب رفض التوكن", "scripts/devices-lab.sh seed وبعدين شغّل الأمر تاني")
            return
        if first.error:
            # Only when the terminal itself was not reached. `reachable` is set after it answered, so
            # the same errno arriving with it set came from the delivery to the lab server, and
            # telling the operator to check the terminal's network path would be false.
            fix = ((self.unreachable_path_fix(first.error) if not first.reachable else None)
                   or next((text for needle, text in AGENT_FIXES if needle in first.error),
                           "اكتب الرسالة دي في الـ issue"))
            self.note("bad", f"الإرسال فشل: {first.error}", fix)
            return
        self.agent_sent = True
        self.note("ok", f"أول إرسال: اتقرأ {first.read} سجل، واتسجل منهم {first.stored} جديد")
        if first.read and not first.stored:
            self.note("warn", "مفيش ولا سجل جديد اتسجل في السيستم",
                      "يا إما السجلات دي وصلت قبل كده (بالـ Push أو زيارة سابقة) وده عادي، يا إما فيه مشكلة: "
                      "افتح صفحة الجهاز في الداشبورد واتأكد إن بصماته ظاهرة")
        if self.push and first.read >= 20 and first.stored > first.read // 10:
            self.note("warn", f"الـ agent سجّل {first.stored} بصمة جديدة مع إن الجهاز بعت سجله بالـ Push",
                      "افتح صفحة الجهاز في الداشبورد: لو كل بصمة ظاهرة مرتين، ماتقراش الجهاز ده بالطريقتين "
                      "في البرود، واكتبها في الـ issue")
        if second.error:
            self.note("bad", f"تاني إرسال فشل: {second.error}", "اكتب الرسالة دي في الـ issue")
        elif second.stored and second.read > first.read:
            self.note("warn", f"تاني إرسال سجّل {second.stored}، بس الجهاز كان فيه {second.read - first.read} بصمة "
                              "جديدة اتعملت في اللحظة دي",
                      "مش تكرار على الأغلب: أعد الأمر بعيد عن وقت البصمات عشان تتأكد")
        elif second.stored:
            self.note("bad", f"تاني إرسال سجّل {second.stored} تاني من نفس السجلات",
                      "ده معناه إن البصمات بتتكرر: ماتبعتش تاني، واكتبها في الـ issue")
        else:
            self.note("ok", "تاني إرسال ماسجّلش حاجة جديدة (صح)")

    # -- push (runbook 5) ------------------------------------------------------------------

    def start_receiver(self, upstream: bool = True) -> bool:
        if self.receiver is None:
            self.receiver = Receiver(str(self.out / "captures"), self.lab.receiver_url if upstream else None,
                                     self.lab.receiver_host if upstream else None, self.capture_host,
                                     self.capture_port, self.clock)
        try:
            self.receiver.start()
        except OSError as exc:
            # The object stays: it holds everything recorded so far, and the caller reads it.
            self.note("bad", f"مقدرتش أفتح port {self.capture_port}: {exc}",
                      "فيه برنامج تاني شغال عليه (capture قديم؟): وقّفه بـ Ctrl+C وشغّل الأمر تاني")
            return False
        return True

    def stop_receiver(self) -> None:
        if self.receiver is not None:
            self.receiver.stop()

    def laptop_address(self, device_ip: str | None) -> str:
        networks = self.networks()
        if device_ip:
            for address, network in networks:
                if ipaddress.ip_address(device_ip) in ipaddress.ip_network(network):
                    return address
        return " أو ".join(address for address, _ in networks) or "IP اللابتوب"

    def wait(self, until, mark: int, what: str, hints) -> object:
        while True:
            found = self.receiver.wait(until, mark, self.wait_seconds)
            if found:
                return found
            self.say(f"⏳ لسه ماوصلش {what} بعد {int(self.wait_seconds)} ثانية. جرّب:")
            for hint in hints:
                self.say(f"   • {hint}")
            if not self.yes("أستنى تاني؟"):
                return None

    def push_flow(self, expected: str | None, device_ip: str | None) -> None:
        self.title("5. الجهاز يبعت للابتوب (Push)")
        if not self.start_receiver():
            return
        self.say("👉 على الجهاز افتح Cloud Server Setting (اتصوّرت؟) وحط:")
        for line in ("Server Mode = ADMS", "Enable Domain Name = OFF",
                     f"Server Address = {self.laptop_address(device_ip)}", f"Server Port = {self.receiver.port}",
                     "HTTPS = OFF و Proxy = OFF", "Save، ولو طلب restart اعمله"):
            self.say(f"   • {line}")
        mark = self.receiver.mark()
        self.push_configured = True
        self.enter("لما تحفظ الإعدادات")
        self.say("⏳ مستني الجهاز يكلّمني (عادةً أقل من دقيقة)...")
        hello = self.wait(lambda exchanges: next((exchange for exchange in exchanges if exchange.is_handshake()), None),
                          mark, "أي اتصال من الجهاز", PUSH_HINTS)
        if hello is None:
            self.note("bad", "الجهاز مابعتش حاجة للابتوب", "راجع الإعدادات والـ firewall (sudo ufw allow 8081/tcp) وجرّب تاني")
            return
        serial = hello.serial
        if not cfg.SERIAL.match(serial):
            self.note("bad", f"الجهاز بعت سيريال السيستم مش هيقبله: {serial}", "اكتبه في الـ issue مع صورة الستيكر")
            return
        if expected is not None and serial != expected:
            if not self.yes(f"وصلني اتصال بالسيريال {serial}، مش {expected} اللي الجهاز قاله على 4370. ده نفس الجهاز؟",
                            default=False):
                self.note("bad", f"جهاز تاني ({serial}) هو اللي بعت للابتوب",
                          "اتأكد إن الإعداد اتحط على الجهاز الصح، وشغّل الأمر تاني")
                return
            self.note("bad", f"الجهاز بيبعت بالـ Push بسيريال {serial} وعلى 4370 بيقول {expected}",
                      "معلومة مهمة: اكتب الاتنين في الـ issue مع صورة الستيكر")
        self.sheet["Push / ADMS موجود؟"] = "نعم"
        if expected is None:
            if not self.yes(f"الجهاز بعت السيريال {serial}. نفس اللي على الستيكر؟"):
                self.note("bad", "السيريال اللي الجهاز بيبعته مش زي الستيكر",
                          "اكتب الاتنين في الـ issue مع صورة الستيكر وشاشة Device Info")
            self.device(serial, "ZKTeco")
        self.note("ok", "الجهاز كلّم اللابتوب (handshake)")
        zone = self.allocate(serial, "zkteco", None)
        if not zone:
            return
        allocated = self.receiver.mark()
        self.say("⏳ مستني الجهاز يبعت سجلاته القديمة (ممكن ياخد دقيقة أو اتنين)...")
        backlog = self.wait(lambda exchanges: arrivals(exchanges, serial), allocated, "سجلات الحضور",
                            ("لو الجهاز مافيهوش سجلات، اختار لأ ونكمل",) + PUSH_HINTS)
        if backlog:
            self.note("ok", "سجلات الجهاز بدأت توصل لسيستم اللاب")
            self.settle_backlog(serial)
        self.push = Push(serial)
        self.punch_tests(serial)
        facts, findings = push_facts(self.receiver.exchanges, serial)
        self.sheet.update(facts)
        for finding in findings:
            self.note(finding.level, finding.text, finding.fix)
        told = next((exchange for exchange in self.receiver.exchanges[allocated:]
                     if exchange.serial == serial and exchange.is_handshake() and b"TimeZone=" in exchange.response), None)
        if told:
            line = next(line for line in told.response.decode("ascii", "replace").splitlines()
                        if line.startswith("TimeZone="))
            self.say(f"   بعد التخصيص السيستم بعت للجهاز {line}")
        # Rule 2's safeguard reads the clock back over 4370, which this terminal does not answer
        # on. The row says that rather than staying blank: the hazard is real -- some firmware
        # applies the zone the platform sends -- and an empty cell in the report reads as "no
        # difference" rather than "not measured". Whether the zone was actually sent is added
        # when it was seen, because that is the half that makes the hazard live (#313).
        self.sheet["فرق ساعة الجهاز عن اللابتوب"] = ("مااتقاسش: الجهاز مابيردش على 4370"
                                                     + ("، والسيستم بعتله TimeZone" if told else ""))
        self.sheet["HTTPS موجود في المنيو؟"] = "نعم" if self.yes(
            "فيه اختيار HTTPS في شاشة Cloud Server Setting؟", default=False) else "لا"
        self.sheet["Enable Domain Name موجود؟"] = "نعم" if self.yes(
            "فيه اختيار Enable Domain Name في نفس الشاشة؟", default=False) else "لا"
        last = [arrival for arrival in arrivals(self.receiver.exchanges, serial) if arrival.fields][-3:]
        if last:
            self.say("   آخر بصمات وصلت للسيستم:")
            for arrival in last:
                self.say(f"      كود {arrival.fields[0]}   {arrival.fields[1]}")
            if not self.yes("نفس الكود ونفس الوقت في شاشة البحث على الجهاز (Menu → Attendance Search)؟"):
                self.note("bad", "البصمات اللي وصلت مش زي اللي على الجهاز",
                          "صوّر شاشة البحث واكتب الفرق في الـ issue (من غير أكواد الموظفين)")

    def settle_backlog(self, serial: str) -> None:
        """Wait until the terminal stops uploading. A terminal with months of records sends them in
        batches over minutes, and a batch landing during a punch test would answer the test: the
        latency, the two-in-a-row and the in/out values would all be read off old records.

        Any accepted upload counts, readable or not: a terminal whose line shape the recorder
        cannot parse is exactly the one this visit exists to characterise, and calling it quiet
        because nothing could be read of it would start the punch tests mid-backlog."""
        deadline = time.monotonic() + max(self.wait_seconds * 4, self.quiet_seconds)
        while True:
            mark = self.receiver.mark()
            if self.receiver.wait(lambda exchanges: arrivals(exchanges, serial), mark, self.quiet_seconds) is None:
                return
            self.say("   ⏳ الجهاز لسه بيبعت سجلاته القديمة...")
            if time.monotonic() > deadline:
                if not self.yes("لسه بيبعت. أستنى تاني؟ (لأ = نكمل التجارب دلوقتي)"):
                    self.note("warn", "بدأنا التجارب والجهاز لسه بيبعت سجلاته القديمة",
                              "الأرقام اللي تحت ممكن تكون لسجل قديم مش للبصمة اللي اتعملت دلوقتي: اكتب ده في الـ issue")
                    return
                # Only an answered "wait again" moves the deadline. Moving it on every pass --
                # which is what this line used to do, outside the branch -- put it permanently
                # ahead of the check that reads it, so the question was never asked and a
                # terminal that never goes quiet left the operator nothing but Ctrl-C.
                deadline = time.monotonic() + self.wait_seconds

    def punch_test(self, serial: str, instruction: str, count: int) -> PunchTest | None:
        """Times the punch the operator was just asked for, and nothing else: an upload that was
        already on its way, or a line the terminal has sent before, or a record older than one it
        has already delivered, is the backlog -- counting it would time a two-day-old punch.

        On a terminal whose lines the recorder keeps nothing from, none of those rules can run:
        an upload has no code, no time and nothing to match against what was delivered before.
        If such uploads were already arriving before the operator was asked, the next one is as
        likely to be the backlog as the punch, and the test says so (`blind`) rather than timing
        it -- the one case where the answer is a number nobody can stand behind."""
        already = arrivals(self.receiver.exchanges, serial)
        unreadable_before = any(arrival.fields is None for arrival in already)
        already = [arrival for arrival in already if arrival.fields]
        seen = Counter((arrival.fields[0], arrival.fields[1]) for arrival in already)
        latest = max((time for time in (line_time(arrival.fields) for arrival in already) if time is not None),
                     default=None)
        self.enter(instruction)
        started = self.clock()
        mark = self.receiver.mark()

        def arrived(exchanges):
            found = fresh_punches(arrivals(exchanges, serial, since=started), seen, latest)
            return (found[count - 1].at, found[:count]) if len(found) >= count else None

        found = self.wait(arrived, mark, "البصمة", PUNCH_HINTS)
        if found is None:
            return None
        blind = unreadable_before and not any(arrival.fields for arrival in found[1])
        return PunchTest(max(0.0, found[0] - started), found[1], blind)

    def punch_tests(self, serial: str) -> None:
        self.say()
        self.say("👉 دلوقتي محتاج الموظف اللي متفق معاه، بعيد عن مواعيد الحضور.")
        normal = self.punch_test(serial, "لما تدوس Enter، خلّي الموظف يعمل بصمة عادية على طول", 1)
        if normal is None:
            self.note("bad", "البصمة العادية ماوصلتش", "راجع الإعدادات على الجهاز، وجرّب تاني")
            return
        if normal.blind:
            self.unmeasurable("البصمة", "بصمة وصلت خلال")
            return
        self.push.in_value = normal.arrived[-1].in_out
        self.sheet["بصمة وصلت خلال"] = f"{normal.seconds:.0f} ثانية"
        self.note("ok", f"بصمة عادية وصلت للسيستم خلال {normal.seconds:.0f} ثانية")
        if self.unreadable(normal.arrived):
            return
        checkout = self.punch_test(serial, "لما تدوس Enter، خلّي الموظف يدوس زرار Check-Out (أو F2) ويعمل بصمة", 1)
        if checkout is not None and not self.unreadable(checkout.arrived):
            self.push.out_value = checkout.arrived[-1].in_out
            self.sheet["الدخول"], self.sheet["الخروج"] = self.push.in_value or "", self.push.out_value or ""
            if self.push.in_value == self.push.out_value:
                self.note("bad", f"بصمة الخروج جت بنفس رقم الدخول ({self.push.in_value})",
                          "اتأكد إن الموظف داس زرار الخروج قبل البصمة وجرّب تاني؛ لو نفس النتيجة اكتبها في الـ issue")
            else:
                self.note("ok", f"الدخول بيوصل {self.push.in_value} والخروج {self.push.out_value}")
            self.sheet["الموظفين بيدوسوا زرار الدخول/الخروج؟"] = "نعم" if self.yes(
                "الموظفين أصلاً بيدوسوا زرار الدخول والخروج؟", default=False) else "لا"
        twice = self.punch_test(serial, "لما تدوس Enter، خلّي الموظف يعمل بصمتين ورا بعض (في أقل من 10 ثواني)", 2)
        if twice is None:
            self.note("bad", "البصمتين ورا بعض ماوصلوش الاتنين", "اكتبها في الـ issue: ممكن الجهاز بيرفض بصمة مكررة بسرعة")
        elif twice.blind:
            self.unmeasurable("البصمتين ورا بعض")
        else:
            self.note("ok", "البصمتين ورا بعض اتسجلوا الاتنين")
        if self.yes("نعمل تجربة شيل كابل الشبكة من الجهاز؟ (دقيقتين)"):
            cable = self.punch_test(serial, "شيل كابل الشبكة من الجهاز، خلّي الموظف يعمل بصمتين، ورجّع الكابل. "
                                            "أول ما ترجّعه", 2)
            if cable is None:
                self.sheet["شلنا الكابل: البصمات وصلت بعد الرجوع؟"] = "لا"
                self.note("bad", "البصمات اللي اتعملت والكابل مشيل ماوصلتش", "اكتبها في الـ issue، وخلي العميل يتأكد إنها على الجهاز")
            elif cable.blind:
                self.unmeasurable("رجوع البصمات بعد ما الكابل رجع", "شلنا الكابل: البصمات وصلت بعد الرجوع؟")
            else:
                self.sheet["شلنا الكابل: البصمات وصلت بعد الرجوع؟"] = f"نعم، بعد {cable.seconds:.0f} ثانية"
                self.note("ok", f"بعد ما الكابل رجع، البصمات وصلت خلال {cable.seconds:.0f} ثانية")
        if self.yes(f"نعمل تجربة وقف الاستقبال {self.pause_seconds / 60:.0f} دقايق؟ (الجهاز لازم يعيد الإرسال لوحده)"):
            self.pause_test(serial)

    def unmeasurable(self, what: str, row: str | None = None) -> None:
        """The terminal was already uploading records the recorder keeps no line from, so an
        upload landing after the prompt is as likely to be one of those as the punch just made.
        Timing it would put a number nobody can stand behind in the sheet. Every test that ends
        in a number or a tick goes through here, not only the headline one."""
        if row:
            self.sheet[row] = "مااتقاسش"
        self.note("bad", f"مش قادرين نقيس {what} على الموديل ده: الجهاز بيرفع سجلات الـ capture مش فاهم "
                         "سطورها، فأي رفعة ممكن تكون سجل قديم مش البصمة اللي اتعملت دلوقتي",
                  "دي أهم معلومة عن الموديل ده: شكل السطر في التقرير وفي field-report/captures، "
                  "اكتبه في الـ issue عشان نضيف الشكل ده للسيستم. لحد ما ده يحصل، الجهاز ده مايتقاسش من هنا")

    def unreadable(self, arrived: list[Arrival]) -> bool:
        """The punch reached the platform, but the recorder kept no readable line from the upload:
        the terminal is working and the line shape is the finding, so the visit says which."""
        if any(arrival.fields for arrival in arrived):
            return False
        self.note("bad", "البصمة وصلت للسيستم، بس الـ capture مافهمش سطور الرفعة",
                  "دي معلومة مهمة عن الموديل ده: شكل السطر في التقرير وفي field-report/captures، "
                  "اكتبه في الـ issue. البصمة نفسها وصلت ومش ضايعة")
        return True

    def pause_test(self, serial: str) -> None:
        self.receiver.stop()
        mark = self.receiver.mark()
        self.enter("وقّفت الاستقبال. لما تدوس Enter خلّي الموظف يعمل بصمة")
        remaining = self.pause_seconds
        while remaining > 0:
            self.say(f"   ⏳ فاضل {int(remaining) // 60:02d}:{int(remaining) % 60:02d}")
            step = min(60.0, remaining)
            self.sleep(step)
            remaining -= step
        if not self.start_receiver():
            return
        restarted = self.clock()
        self.say("   رجّعت الاستقبال. مستني الجهاز يعيد الإرسال...")
        found = self.wait(lambda exchanges: arrivals(exchanges, serial), mark, "البصمة", PUNCH_HINTS)
        row = "وقفنا الـ capture 10 دقايق: الجهاز عاد الإرسال؟"
        if not found:
            self.sheet[row] = "لا"
            self.note("bad", "الجهاز ماعادش إرسال البصمة بعد ما الاستقبال رجع",
                      "معلومة مهمة: اكتبها في الـ issue. البصمة لسه على الجهاز")
            return
        first = found[0]
        self.sheet[row] = f"نعم، بعد {max(0.0, first.at - restarted):.0f} ثانية"
        self.sleep(self.settle_seconds)
        times = sum(1 for other in arrivals(self.receiver.exchanges[mark:], serial)
                    if other.fields and first.fields and other.fields[:2] == first.fields[:2])
        # What the terminal did is what was watched here; whether the platform kept one row is a
        # question for the device's page in the dashboard, so the sheet does not answer it.
        self.sheet["البصمة دي اتسجلت مرة واحدة؟"] = ("الجهاز بعتها مرة واحدة" if times <= 1 else
                                                     f"الجهاز بعتها {times} مرات: شوف صفحة الجهاز في الداشبورد")
        self.note("ok", f"الجهاز عاد إرسال البصمة بعد ما الاستقبال رجع (بعتها {max(times, 1)} مرة)")

    # -- Hikvision (runbook 8) -------------------------------------------------------------

    def hik_flow(self, host: str, port: int | None) -> None:
        self.title("8. جهاز Hikvision")
        username = self.console.ask("👉 اسم المستخدم (admin) بتاع صفحة الجهاز (Enter = admin):") or "admin"
        password = self.console.secret("👉 الباسورد (مش هيظهر وإنت بتكتبه):").strip()
        if not password:
            self.note("bad", "مفيش باسورد", "اسأل العميل على باسورد الـ admin وشغّل الأمر تاني")
            return
        secret = self.out / "hik.pw"
        secret.unlink(missing_ok=True)
        self.secrets.append(secret)
        with os.fdopen(os.open(secret, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600), "w", encoding="utf-8") as handle:
            handle.write(password)
        info, device, error = None, None, None
        for https in (False, True):
            device = DeviceConfig(serial="probe", kind="hikvision", host=host, https=https,
                                  port=port or (443 if https else 80), username=username, password=password,
                                  timeout_seconds=10)
            try:
                info = probe.hik_summary(device, 7, str(self.out / "hik-events.json"))
                break
            except SourceError as exc:
                if "401" in str(exc):
                    self.note("bad", "اليوزر أو الباسورد غلط", "اتأكد منهم مع العميل وشغّل الأمر تاني")
                    return
                error = exc
        if info is None:
            self.note("bad", f"الجهاز مارَدّش: {error}", "اتأكد من الـ IP وإن صفحة الجهاز بتفتح في المتصفح")
            return
        serial = str(info["serial"] or "")
        if not cfg.SERIAL.match(serial):
            self.note("bad", f"سيريال الجهاز السيستم مش هيقبله: {serial!r}", "اكتبه في الـ issue مع صورة الستيكر")
            return
        self.device(serial, "Hikvision", info.get("model"), info.get("firmware"))
        self.note("ok", f"الجهاز رد: {serial}، {info.get('model')}، {info['events']} حدث في آخر 7 أيام")
        minors = info["events_with_employee_by_minor"]
        self.sheet["Hikvision: أكواد الحضور اللي ظهرت"] = "، ".join(f"{code} ({count})" for code, count in sorted(minors.items()))
        unexpected = sorted(code for code in minors if not code.isdigit() or int(code) not in HIK_ATTENDANCE)
        if unexpected:
            self.note("warn", f"فيه أحداث فيها موظف بأكواد {', '.join(unexpected)} مش بتتحسب حضور",
                      "لو دي بصمات حضور فعلاً، اكتب الأكواد في الـ issue (هنضيفها في attendance_minors)")
        source = HikvisionSource(device)
        started = self.now()
        self.enter("لما تدوس Enter، خلّي الموظف يعمل بصمة على الجهاز")
        self.sleep(self.settle_seconds)
        try:
            events, error = source.events(started - timedelta(minutes=2), self.now() + timedelta(minutes=1)), None
        except SourceError as exc:
            events, error = [], exc
        with_employee = [event for event in events if event.get("employeeNoString") or event.get("employeeNo")]
        offset = None
        if with_employee:
            event = with_employee[-1]
            minor = int(event.get("minor", -1))
            status = event.get("attendanceStatus")
            offset = str(event.get("time", ""))[19:] or None
            if minor in HIK_ATTENDANCE:
                self.note("ok", f"البصمة وصلت بكود {minor}" + (f" و {status}" if status else "") + "، وده بيتحسب حضور")
            else:
                self.note("bad", f"البصمة جت بكود {minor}، والكود ده مش بيتحسب حضور",
                          f"اكتب الكود {minor} في الـ issue (هنضيفه في attendance_minors)")
        else:
            self.note("bad", "مالقيتش البصمة في سجل أحداث الجهاز" + (f" ({error})" if error else ""),
                      "جرّب تاني، وبص على صفحة الأحداث في الجهاز")
        if not self.allocate(serial, "hikvision", offset if offset and re.fullmatch(r"[+-]\d{2}:00", offset) else None):
            return
        path = self.out / f"{serial}-hik.toml"
        path.write_text(self._agent_toml(f"{serial}-hik-{self.started:%Y%m%d-%H%M}.sqlite3") +
                        f"\n[[devices]]\nserial = {_toml(serial)}\nkind = \"hikvision\"\nhost = {_toml(host)}\n"
                        f"port = {device.port}\nhttps = {'true' if device.https else 'false'}\n"
                        f"username = {_toml(username)}\npassword_file = \"hik.pw\"\n", encoding="utf-8")
        self.send_twice(path)

    # -- USB (runbook 7) and anything else (runbook 9) ----------------------------------------

    def usb_flow(self, serial: str | None) -> None:
        self.title("7. ملف الـ USB")
        if not self.yes("معاك ملف USB من الجهاز ده؟ (Menu → USB Manager → Download → Attendance Data)", default=False):
            return
        files = usb_files(self.out)
        choice = self.choose("أنهي ملف؟", [(path, str(path)) for path in files] + [(None, "اكتب مكان الملف بإيدك")])
        path = choice or Path(os.path.expanduser(self.console.ask("👉 مكان الملف:")))
        if not path.is_file():
            self.note("bad", f"مالقيتش الملف {path}", "اتأكد من الاسم والمكان وشغّل الأمر تاني")
            return
        if path.resolve().parent != self.out.resolve():
            copy = self.out / path.name
            # Two terminals both export `1_attlog.dat`; the first one's evidence stays.
            for number in range(1, 100):
                if not copy.exists():
                    break
                copy = self.out / f"{path.stem}-{number}{path.suffix}"
            shutil.copy2(path, copy)
            self.say(f"   نسخت الملف في field-report/{copy.name}، والأصلي على الفلاشة زي ما هو.")
            path = copy
        if serial is None:
            while True:
                serial = self.console.ask("👉 سيريال الجهاز (من الستيكر):")
                if cfg.SERIAL.match(serial):
                    break
                self.say("   السيريال حروف إنجليزي وأرقام و . _ : @ - بس، من غير مسافات.")
            self.device(serial, "ZKTeco")
        if not self.allocate(serial, "zkteco", None):
            return
        read_before = self.push is not None or self.agent_sent
        try:
            totals = usb.import_file(self.lab.gateway(), serial, path.read_bytes(), cfg.DEFAULT_BATCH)
        except (gw.Unauthorized, gw.NotRegistered, gw.Refused, gw.Retryable, gw.TooLarge) as exc:
            self.note("bad", f"رفع الملف فشل: {exc}", "اكتب الرسالة دي في الـ issue")
            return
        self.note("ok", f"الملف اترفع: {totals['lines']} سطر، {totals['stored']} جديد، {totals['duplicates']} متكرر")
        if totals["malformed"]:
            self.note("warn", f"{totals['malformed']} سطر في الملف السيستم مافهمهوش",
                      "هتلاقيهم في صفحة الجهاز في الداشبورد تحت Unreadable lines")
        if read_before and totals["lines"]:
            share = totals["duplicates"] / totals["lines"]
            row = "USB: ترتيب الأعمدة زي الـ Push؟"
            if share >= 0.5:
                self.sheet[row] = "نعم"
                self.note("ok", "سطور الملف طلعت نفس البصمات اللي وصلت قبل كده: ترتيب الأعمدة زي الـ Push")
            elif totals["stored"] >= totals["lines"] * 0.9:
                self.sheet[row] = "لا"
                self.note("bad", "سطور الملف اتسجلت كأنها بصمات جديدة: ترتيب الأعمدة أو شكل الوقت مختلف",
                          "اكتبها في الـ issue، وحط أول 3 سطور من الملف بعد ما تغيّر أكواد الموظفين")
            else:
                self.sheet[row] = "مش واضح"
                self.note("warn", "جزء من سطور الملف بس طلع متكرر",
                          "قارن آخر كام سطر في الملف ببصمات الجهاز في الداشبورد، واكتب الفرق في الـ issue")

    def other_flow(self, host: str) -> None:
        self.title("9. جهاز من نوع تاني")
        # The report is written to be pasted into an issue: the customer's own addressing stays
        # on the laptop, in field-report/, not in it.
        self.serial = self.serial or "device"
        self.sheet["الماركة والموديل"] = self.console.ask("👉 الماركة والموديل (من الستيكر):")
        if self.yes("الجهاز فيه إعداد server أو cloud نقدر نوجّهه للابتوب؟", default=False) and \
                self.start_receiver(upstream=False):
            self.push_configured = True
            self.say(f"👉 حط في إعداد الـ server على الجهاز: {self.laptop_address(host)} و port {self.receiver.port}")
            self.enter("لما الموظف يعمل بصمة أو اتنين")
            self.stop_receiver()
            recorded = list(self.receiver.exchanges)
            identities = sorted({exchange.serial for exchange in recorded})
            self.say(f"   اتسجل {len(recorded)} طلب من: {', '.join(identities) or 'ولا حاجة'}")
            self.sheet["مشاكل تانية"] = f"اتسجل {len(recorded)} طلب في field-report/captures"
            if recorded:
                self.note("ok", f"الجهاز كلّم اللابتوب: اتسجل {len(recorded)} طلب في field-report/captures")
        self.note("warn", "النوع ده السيستم مابيدعموش لسه",
                  "افتح issue بالصور ونتيجة الـ scan وفولدر field-report/captures: دي اللي هتخلينا نعمل دعم")

    # -- allocation, restore, report ---------------------------------------------------------

    def _offset(self, device_clock_as_utc: datetime) -> str:
        hours = round((device_clock_as_utc - self.utc_now()).total_seconds() / 3600)
        return f"{hours:+03d}:00"

    def allocate(self, serial: str, vendor: str, suggested: str | None) -> str | None:
        if serial in self.allocated:
            return self.allocated[serial]
        self.say("⚠️ مهم: الـ time zone لازم يكون نفس اللي الجهاز متظبط عليه (صورة رقم 5). السيستم بيبعته للجهاز، "
                 "وفيه أجهزة بتغيّر ساعتها على حسبه.")
        utc = self.utc_now()
        # "مش متأكد" is first, so the Enter default is the answer that guesses nothing: the
        # platform sends this zone to the terminal on every handshake and some firmware moves
        # its clock to match, which rule 2 forbids the visit from causing.
        options = [(None, "مش متأكد (ماتخمّنش)"),
                   ("Africa/Cairo", "التوقيت الصيفي (Daylight Saving) شغال على الجهاز")]
        for zone, hours in (("+02:00", 2), ("+03:00", 3)):
            options.append((zone, f"التوقيت الصيفي مقفول، والساعة على الجهاز دلوقتي حوالي {utc + timedelta(hours=hours):%H:%M}"))
        if suggested and suggested not in (zone for zone, _ in options):
            options.append((suggested, f"{suggested}: ده اللي ساعة الجهاز بتقوله"))
        zone = self.choose("الجهاز متظبط على إيه؟" + (f" (ساعة الجهاز بتقول {suggested})" if suggested else ""), options)
        if zone is None:
            self.say("   افتح Menu → System → Date Time على الجهاز: شوف Daylight Saving شغال ولا لأ، والساعة كام.")
            zone = self.choose("الجهاز متظبط على إيه؟", options)
        if zone is None:
            self.note("bad", "الجهاز ماتخصصش لأن الـ time zone بتاعه مش معروف",
                      "ماتخمّنش: اعرفه من شاشة Date Time وشغّل الأمر تاني")
            return None
        self.sheet["الـ Time zone بتاع الجهاز"] = zone
        self.sheet["التوقيت الصيفي (Daylight Saving)"] = "شغال" if zone == "Africa/Cairo" else "مقفول"
        self.sheet["الساعة"] = self.choose("الساعة على الجهاز بتتظبط لوحدها ولا يدوي؟",
                                           [("NTP", "لوحدها (NTP)"), ("يدوي", "يدوي"), ("", "مش عارف")])
        ok, text = self.lab.allocate(serial, vendor, zone)
        if not ok:
            self.note("bad", f"تخصيص الجهاز في اللاب فشل: {text}",
                      "اتأكد إن اللاب شغال (scripts/devices-lab.sh up و seed) وشغّل الأمر تاني")
            return None
        self.allocated[serial] = zone
        earlier = re.match(r"already allocated \S+ zone (\S+) active (\d)", text)
        if earlier:
            self.say("   الجهاز متخصص في اللاب من قبل كده.")
            if earlier.group(1) != zone:
                self.note("warn", f"الجهاز متخصص في اللاب بـ time zone {earlier.group(1)} مش {zone}",
                          "مواعيد البصمات هتتحسب على القديم: غيّره من صفحة الجهاز في داشبورد اللاب، أو ابدأ اللاب "
                          "من الأول (runbook 6.4: down --wipe و up و seed)")
            if earlier.group(2) != "1":
                self.note("bad", "الجهاز متوقف (Inactive) في اللاب، فالسيستم هيرفض بصماته",
                          "فعّله من صفحة الجهاز في داشبورد اللاب (Activate device) وشغّل الأمر تاني")
        else:
            self.note("ok", f"خصّصت الجهاز لفرع اللاب، time zone {zone}")
        return zone

    def restore(self) -> None:
        if self.restored or not self.device_started:
            return
        self.restored = True
        self.title("10. قبل ما تمشي: رجّع كل حاجة")
        if self.receiver is not None and self.receiver.server is not None:
            self.stop_receiver()
            self.say(f"✅ وقّفت الاستقبال على port {self.receiver.port}.")
        checks = []
        if self.push_configured:
            checks.append(("رجّعت إعداد الـ server على الجهاز (Cloud Server Setting) زي الصورة بالظبط، أو قفلته لو كان مقفول؟",
                           "إعداد الـ server على الجهاز مارجعش زي الأول",
                           "رجّعه زي الصورة حرف حرف قبل ما تمشي، وإلا برنامج العميل مش هيستلم"))
        checks.append(("لو العميل بيستخدم برنامج (ZKTime.Net أو BioTime أو غيره): موظف عمل بصمة جديدة ووصلت للبرنامج "
                       "بتاعهم؟ (لو مابيستخدموش اختار أيوه)",
                       "برنامج العميل مااستلمش البصمة الجديدة",
                       "قارن الإعدادات بالصورة حرف حرف واعمل restart للجهاز؛ الجهاز محتفظ بسجلاته"))
        checks.append(("شلت أي كابل أو switch إنت اللي حطيته؟", "فيه كابل أو switch لسه متركب", "شيله قبل ما تمشي"))
        try:
            for question, problem, fix in checks:
                if not self.yes(question):
                    self.note("bad", problem, fix)
        except (KeyboardInterrupt, EOFError):
            self.note("bad", "ماكمّلناش خطوات الرجوع",
                      "رجّع إعداد الـ server على الجهاز زي الصورة، واتأكد إن برنامج العميل بيستلم بصمة جديدة، "
                      "وشيل أي كابل أو switch حطيته")
        self.say("   لو فتحت port 8081 في الـ firewall، اقفله:  sudo ufw delete allow 8081/tcp")

    def close(self) -> None:
        self.stop_receiver()
        for path in self.secrets:
            if path.exists():
                path.unlink()
                self.say("✅ مسحت باسورد الجهاز من اللابتوب.")

    def write_report(self) -> Path:
        bad = [finding for finding in self.findings if finding.level != "ok"]
        # "مفيش" is a claim that the visit looked and found nothing, so it is only written when
        # the visit did look.
        nothing = ("الزيارة اتوقفت قبل ما تخلص" if self.interrupted else
                   "مفيش" if self.examined() else "الزيارة ماعملتش أي فحص: مافيش جهاز كلّم اللابتوب ولا ملف اتقرا")
        self.sheet["مشاكل تانية"] = self.sheet["مشاكل تانية"] or "، ".join(
            finding.text for finding in bad if finding.level == "bad") or nothing
        verdict = self.verdict()
        lines = [f"# تقرير زيارة: جهاز {_md(self.serial or 'مش معروف')}", "", '<div dir="rtl">', "",
                 f"**النتيجة:** {verdict}", "",
                 f"اتعمل بالأمر `workin_devices visit` يوم {_ltr(f'{self.started:%Y-%m-%d %H:%M}')}، "
                 "في وضع A (سيستم اللاب على اللابتوب). مفيش فيه أكواد موظفين ولا أسامي ولا أرقام كروت.", ""]
        if bad:
            lines += ["## المشاكل والحل", ""]
            lines += [f"- {MARK[finding.level]} {_md(_no_address(finding.text))}" +
                      (f" ← **الحل:** {_md(_no_address(finding.fix))}" if finding.fix else "") for finding in bad] + [""]
        done = [finding for finding in self.findings if finding.level == "ok"]
        if done:
            lines += ["## اللي اشتغل", ""] + [f"- {MARK['ok']} {_md(_no_address(finding.text))}" for finding in done] + [""]
        lines += ["## ورقة النتائج", "", "| البند | النتيجة |", "|---|---|"]
        lines += [f"| {row} | {_ltr(_no_address(self.sheet[row])) or '—'} |" for row in SHEET_ROWS]
        lines += ["", "</div>", ""]
        name = self.serial if self.serial and cfg.SERIAL.match(self.serial) else "device"
        path = self.out / f"visit-{name}-{self.started:%Y%m%d-%H%M}.md"
        self.out.mkdir(parents=True, exist_ok=True)
        path.write_text("\n".join(lines), encoding="utf-8")
        return path

    def examined(self) -> bool:
        """Did anything actually get looked at?

        Two ways to have looked. Most paths record an `ok` finding on the way -- the handshake,
        the 4370 connection, the export read. The other is runbook 9: a terminal found,
        identified and classified as one the platform does not support yet records a `warn` and
        a results sheet, and `self.serial` is set the moment it is identified. That visit
        examined a terminal and its answer is the model; a headline saying nothing was examined
        would deny its own sheet."""
        return self.serial is not None or any(finding.level == "ok" for finding in self.findings)

    def verdict(self) -> str:
        """What the report is headed with, and the first thing anyone reads. It answers "was
        anything examined?" before "did it pass?": this report is pasted into a
        device-compatibility issue, where a ✅ reads as "this model is supported"."""
        bad = sum(1 for finding in self.findings if finding.level == "bad")
        warn = sum(1 for finding in self.findings if finding.level == "warn")
        if bad:
            return f"❌ فيه {bad} مشكلة" + (f" و {warn} تنبيه" if warn else "")
        if self.interrupted:
            return "⛔ الزيارة اتوقفت قبل ما تخلص: اللي في التقرير هو اللي اتفحص لحد اللحظة دي بس"
        if not self.examined():
            return "❓ الزيارة ماعملتش أي فحص: مافيش جهاز كلّم اللابتوب ولا ملف اتقرا"
        if warn:
            return f"⚠️ اشتغل، بس فيه {warn} تنبيه"
        return "✅ كله تمام"

    def summary(self, report: Path | None) -> None:
        self.title("النتيجة")
        self.say(self.verdict())
        for finding in self.findings:
            if finding.level != "ok":
                self.say(f"{MARK[finding.level]} {finding.text}" + (f"\n   ← الحل: {finding.fix}" if finding.fix else ""))
        if report is not None:
            self.say(f"📄 التقرير: {report}")
            self.say("   حطه في issue بقالب device-compatibility-finding، مع الصور.")
            self.say("   لجهاز تاني: شغّل الأمر تاني.")
