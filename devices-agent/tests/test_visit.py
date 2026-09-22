import contextlib
import errno
import http.server
import io
import os
import re
import shutil
import subprocess
import tempfile
import threading
import time
import types
import urllib.request
import unittest
from collections import Counter
from datetime import datetime, timedelta
from pathlib import Path
from unittest import mock

from workin_devices import probe, visit
from workin_devices import __main__ as main
from workin_devices.sim import adms
from workin_devices.sim.hikvision import HikTerminal, serve as serve_hik
from workin_devices.sim.zk4370 import Emulator, Terminal, populate
from tests.support import FakePlatform

ROOT = Path(__file__).resolve().parents[2]
PIN = "90417"


class Unscripted(BaseException):
    """Not an Exception: the visit catches those, and a test's complaint must reach the test."""


class ScriptedConsole:
    """The operator: each answer names a fragment of the question it answers, so a test fails on
    the question the wizard asked rather than on whatever answer happened to come next. An answer
    may be a callable, run when the question is reached -- the terminal doing what the operator
    was just asked to make it do."""

    def __init__(self, answers):
        self.answers = list(answers)
        self.output = []
        self.question = ""

    def say(self, text=""):
        self.output.append(text)
        if text.startswith("👉"):
            self.question = text

    def ask(self, prompt):
        question = prompt if prompt.startswith("👉") else self.question
        if not self.answers:
            raise Unscripted(f"unexpected question {question!r}; last output:\n" + "\n".join(self.output[-15:]))
        fragment, answer = self.answers.pop(0)
        if fragment not in question:
            raise Unscripted(f"expected a question about {fragment!r}, got {question!r}; last output:\n"
                             + "\n".join(self.output[-15:]))
        return answer() if callable(answer) else answer

    secret = ask

    @property
    def text(self):
        return "\n".join(self.output)


class FakeReceiver:
    """The platform's /iclock receiver as a terminal meets it: 403 to uploads until the serial is
    allocated, then 200 "OK: n", and a time zone in the handshake once allocated."""

    def __init__(self):
        self.allocated = set()
        self.hosts = []
        receiver = self

        class Handler(http.server.BaseHTTPRequestHandler):
            def log_message(self, *args):
                pass

            def _answer(self, status, text):
                body = text.encode()
                self.send_response(status)
                self.send_header("Content-Type", "text/plain")
                self.send_header("Content-Length", str(len(body)))
                self.end_headers()
                self.wfile.write(body)

            def do_GET(self):
                receiver.hosts.append(self.headers.get("Host"))
                serial = re.search(r"SN=([^&]+)", self.path).group(1)
                if self.path.startswith("/iclock/cdata"):
                    zone = "TimeZone=2\r\n" if serial in receiver.allocated else ""
                    return self._answer(200, f"GET OPTION FROM: {serial}\r\nATTLOGStamp=0\r\n{zone}Delay=10\r\n")
                return self._answer(200, "OK")

            def do_POST(self):
                body = self.rfile.read(int(self.headers.get("Content-Length") or 0)).decode("utf-8", "replace")
                serial = re.search(r"SN=([^&]+)", self.path).group(1)
                if serial not in receiver.allocated:
                    return self._answer(403, "ERROR: device is not registered")
                return self._answer(200, f"OK: {len([line for line in body.splitlines() if line])}")

        self.server = http.server.ThreadingHTTPServer(("127.0.0.1", 0), Handler)
        self.url = f"http://127.0.0.1:{self.server.server_address[1]}"
        threading.Thread(target=self.server.serve_forever, daemon=True).start()

    def close(self):
        self.server.shutdown()
        self.server.server_close()


class FakeLab(visit.Lab):
    def __init__(self, platform, receiver, directory):
        self.server_url = platform.url
        self.token_path = Path(directory) / "agent.token"
        self.token_path.write_text(platform.token + "\n")
        os.chmod(self.token_path, 0o600)
        self.receiver_url = receiver.url if receiver else "http://127.0.0.1:9"
        self.receiver_host = "devices.localhost"
        self.receiver = receiver
        self.allocations = []

    def run(self, command):
        raise AssertionError(f"the lab is up; nothing should run devices-lab.sh {command}")

    def allocate(self, serial, vendor, zone):
        self.allocations.append((serial, vendor, zone))
        if self.receiver:
            self.receiver.allocated.add(serial)
        return True, f"allocated {serial} zone {zone}"


class PushDevice:
    """A push terminal's own loop: whatever it holds it keeps sending until the receiver says 200,
    and it says hello again after an outage, as firmware does."""

    def __init__(self, url, serial="PUSH-VISIT-1"):
        self.terminal = adms.PushTerminal(url, serial, timeout=2)
        self.pending = [self.line(datetime.now() - timedelta(days=1, hours=hours), hours % 2, 1)
                        for hours in range(1, 5)]
        self.lock = threading.Lock()
        self.tick = 0
        self.connected = True
        self.stopped = threading.Event()
        self.terminal.handshake()
        threading.Thread(target=self._loop, daemon=True).start()

    def line(self, when, in_out, verify=1):
        """One ATTLOG line as this firmware writes it."""
        return adms.punch_line(PIN, when, in_out, verify)

    def drip_old_record(self):
        """One more record out of the backlog, queued now so the loop uploads it in a moment:
        the batch that lands while the operator is being asked for a punch. Not a punch -- it
        is two days old and nobody has touched the terminal."""
        with self.lock:
            self.pending.append(self.line(datetime.now() - timedelta(days=2), 1, 15))
        return ""

    def drip_old_records(self, count=2, every=0.25):
        """The same, `count` times and spaced, from a thread -- so they are uploaded one at a
        time and after the caller returns, which is what a backlog landing *during* a test
        looks like. Queued together they would go in one POST and be one arrival."""
        def drip():
            for _ in range(count):
                time.sleep(every)
                self.drip_old_record()

        threading.Thread(target=drip, daemon=True).start()
        return ""

    def send_old_batch(self, lines=3, in_out=1):
        """A batch of old records, sent now and synchronously: a backlog still draining."""
        old = datetime.now() - timedelta(days=2)
        return self.terminal.attlog([self.line(old + timedelta(minutes=n), in_out, 15)
                                     for n in range(lines)])

    def send_now(self, in_out=1):
        """One brand-new record, uploaded synchronously: an upload already on its way when the
        operator was asked. Nothing in the line marks it as backlog -- it has never been sent
        before and it is newer than everything delivered -- so only the moment it was recorded
        tells it from the punch about to be made."""
        with self.lock:
            self.tick += 5
            when = datetime.now().replace(microsecond=0) + timedelta(seconds=self.tick)
        return self.terminal.attlog([self.line(when, in_out, 15)])

    def punch(self, in_out=0):
        """One scan. Its clock moves forward between punches, as a terminal's does -- a test where
        every punch shares one second would not tell a live punch from a re-sent one."""
        with self.lock:
            self.tick += 5
            self.pending.append(self.line(datetime.now().replace(microsecond=0)
                                          + timedelta(seconds=self.tick), in_out, 15))
        return ""

    def _loop(self):
        failed = False
        while not self.stopped.wait(0.05):
            if failed:
                if self.terminal.handshake().status == 0:
                    continue
                failed = False
            with self.lock:
                batch = list(self.pending)
            if not batch or not self.connected:
                continue
            answer = self.terminal.attlog(batch)
            if answer.status == 0:
                failed = True
            elif answer.status == 200:
                with self.lock:
                    del self.pending[:len(batch)]

    def stop(self):
        self.stopped.set()


class TricklingPushDevice(PushDevice):
    """Firmware with months of records: it uploads them a batch at a time over minutes rather
    than all in one POST. The backlog wait ends on the first accepted line, so this is the state
    the punch tests would start in."""

    def __init__(self, url, batches=4, interval=0.1):
        self.batches, self.interval, self.sent = batches, interval, 0
        super().__init__(url)
        threading.Thread(target=self._trickle, daemon=True).start()

    def _trickle(self):
        # Retried until accepted: the receiver refuses every upload until the serial is allocated.
        while self.sent < self.batches and not self.stopped.is_set():
            if self.send_old_batch(lines=10, in_out=1).status == 200:
                self.sent += 1
            time.sleep(self.interval)


class CommaLines:
    """Firmware that separates its ATTLOG fields with commas where the platform expects tabs --
    the unknown shape the visit exists to characterise. The platform still answers 200; the
    recorder keeps no line from the upload, so nothing about it has a code or a time."""

    def line(self, when, in_out, verify=1):
        return f"{PIN},{when:%Y-%m-%d %H:%M:%S},{in_out},{verify},0,0,0"


class UnreadablePushDevice(CommaLines, PushDevice):
    pass


class UnreadableTricklingDevice(CommaLines, TricklingPushDevice):
    pass


class GarblingPushDevice(PushDevice):
    """Firmware whose lines parse until they do not -- an optional column that only some
    employees have, say. The first punch is readable and the later ones are not, which is the
    state a test that only looks at the first punch cannot see."""

    garbled = False

    def garble(self):
        self.garbled = True
        return ""

    def line(self, when, in_out, verify=1):
        if not self.garbled:
            return super().line(when, in_out, verify)
        return f"{PIN},{when:%Y-%m-%d %H:%M:%S},{in_out},{verify},0,0,0"


def quick_visit(console, lab, out, wait_seconds=10):
    """A visit with every wait cut to test length. The scan finds nothing, so a test picks its
    terminal by address; it records what it was asked to scan. `wait_seconds` is a parameter
    because the backlog wait's deadline is four times it, and one test is about that deadline."""
    scans = []
    visited = visit.Visit(console=console, lab=lab, out_dir=out, capture_host="127.0.0.1", capture_port=0,
                          wait_seconds=wait_seconds, settle_seconds=0.3, pause_seconds=0.5, quiet_seconds=0.4,
                          sleep=lambda seconds: time.sleep(min(seconds, 0.3)),
                          networks=lambda: [("127.0.0.1", "127.0.0.0/30")],
                          scan=lambda cidr, **kwargs: scans.append(cidr) or [])
    visited.scans = scans
    return visited


def report_of(out):
    reports = sorted(Path(out).glob("visit-*.md"))
    return reports[-1].read_text(encoding="utf-8") if reports else ""


class VisitStart(list):
    """The questions every visit opens with: the company, consent, the photos."""

    def __init__(self, consent="1"):
        super().__init__([("اسم الشركة", "شركة تجربة / فرع الاختبار"), ("العميل وافق", consent), ("الصور", "")])


class AVisitOfAPushTerminal(unittest.TestCase):

    def setUp(self):
        self.dir = tempfile.TemporaryDirectory()
        self.platform = FakePlatform()
        self.receiver = FakeReceiver()
        self.lab = FakeLab(self.platform, self.receiver, self.dir.name)
        self.out = os.path.join(self.dir.name, "field-report")
        self.device = None

    def tearDown(self):
        if self.device:
            self.device.stop()
        self.receiver.close()
        self.platform.close()
        self.dir.cleanup()

    def test_a_pushed_serial_the_system_rejects_is_kept_out_of_the_report(self):
        """The refusal quotes the identifier, and nothing had recorded it: both `saw_serial` calls
        sat below the `return`. `pasteable` removes only what it was told about, so the report
        published an identifier while its own preamble promises it carries none and step 11 tells
        the operator to paste it into a public issue. A terminal whose identifier `config.SERIAL`
        refuses -- a `/`, a space, over 64 characters -- is exactly the visit worth reporting."""
        rejected = "PUSH/VISIT/2"
        visited = None

        def the_terminal_says_hello():
            adms.PushTerminal(f"http://127.0.0.1:{visited.receiver.port}", rejected, timeout=2).handshake()
            return ""

        console = ScriptedConsole([("لما تحفظ الإعدادات", the_terminal_says_hello)])
        visited = quick_visit(console, self.lab, self.out)
        try:
            visited.push_flow(None, "192.168.1.201")
            text = visited.write_report().read_text(encoding="utf-8")
        finally:
            visited.stop_receiver()
        self.assertEqual(console.answers, [])
        self.assertIn("مش هيقبله", text)
        self.assertNotIn(rejected, text, "the report published a serial the visit refused")
        self.assertIn("device-serial", text)
        self.assertIn(rejected, console.text, "the operator still sees it on screen")

    def test_the_whole_push_visit_runs_every_check_and_reports_without_an_employee_code(self):
        console = ScriptedConsole([])
        visited = quick_visit(console, self.lab, self.out)

        def configure_the_terminal():
            self.device = PushDevice(f"http://127.0.0.1:{visited.receiver.port}")
            return ""

        def unplug_punch_twice_replug():
            self.device.connected = False
            self.device.punch(0)
            self.device.punch(1)
            self.device.connected = True
            return ""

        answers = VisitStart() + [
            ("أنهي جهاز", "1"),                       # a push terminal the scan did not show
            ("لما تحفظ", configure_the_terminal),
            ("نفس اللي على الستيكر", "1"),
            ("الجهاز متظبط على إيه", "2"),            # daylight saving on: Africa/Cairo
            ("بتتظبط لوحدها", "1"),
            ("بصمة عادية", lambda: self.device.punch(0)),
            ("Check-Out", lambda: self.device.punch(1)),
            ("بيدوسوا زرار", "1"),                    # no
            ("بصمتين ورا بعض", lambda: self.device.punch(0) + self.device.punch(0)),
            ("شيل كابل", "1"),
            ("شيل كابل الشبكة من الجهاز", unplug_punch_twice_replug),
            ("وقف الاستقبال", "1"),
            ("وقّفت الاستقبال", lambda: self.device.punch(0)),
            ("HTTPS", "1"),
            ("Enable Domain Name", "2"),
            ("Attendance Search", "1"),
            ("ملف USB", "1"),                        # no USB file
            ("Cloud Server Setting", "1"),
            ("برنامج", "1"),
            ("كابل أو switch", "1"),
        ]
        console.answers = answers
        code = visited.run()
        self.assertEqual(console.answers, [], "every question was asked")
        self.assertEqual(code, 0, console.text)
        self.assertEqual(visited.verdict(), "✅ كله تمام", console.text)
        self.assertEqual(self.lab.allocations, [("PUSH-VISIT-1", "zkteco", "Africa/Cairo")])
        self.assertEqual(set(self.receiver.hosts), {"devices.localhost"}, "forwarded as the lab receiver's name")

        sheet = visited.sheet
        self.assertEqual((sheet["الـ pushver"], sheet["الـ DeviceType"], sheet["الـ language"]), ("2.4.1", "att", "69"))
        self.assertEqual(sheet["الـ Content-Type"], "application/x-www-form-urlencoded")
        self.assertEqual((sheet["الوقت بيتبعت"], sheet["سطر ATTLOG: عدد الحقول"], sheet["سطر ATTLOG: الفاصل"]),
                         ("تاريخ ووقت", "7", "Tab"))
        self.assertEqual(sheet["طول كود الموظف"], "5 أرقام")
        self.assertEqual((sheet["الدخول"], sheet["الخروج"]), ("0", "1"))
        self.assertEqual(sheet["ظهر 413؟"], "لا")
        self.assertEqual(sheet["الـ Stamp في رفع ATTLOG"], "رقم عادي")
        self.assertEqual((sheet["HTTPS موجود في المنيو؟"], sheet["Enable Domain Name موجود؟"]), ("لا", "نعم"))
        self.assertTrue(sheet["شلنا الكابل: البصمات وصلت بعد الرجوع؟"].startswith("نعم"))
        self.assertTrue(sheet["وقفنا الـ capture 10 دقايق: الجهاز عاد الإرسال؟"].startswith("نعم"))
        self.assertEqual(sheet["البصمة دي اتسجلت مرة واحدة؟"], "الجهاز بعتها مرة واحدة")
        self.assertIn("TimeZone=2", console.text, "the handshake after allocation is read back")

        report = report_of(self.out)
        self.assertIn("✅ كله تمام", report)
        self.assertIn('<div dir="rtl">', report)
        self.assertNotIn(PIN, report, "the report is issue-ready: no employee code")
        captured = list(Path(self.out, "captures", "PUSH-VISIT-1").glob("*.request.bin"))
        self.assertTrue(captured, "the recorder kept the exchanges, as `capture` does")


class AVisitOfA4370Terminal(unittest.TestCase):

    def setUp(self):
        self.dir = tempfile.TemporaryDirectory()
        self.platform = FakePlatform()
        self.lab = FakeLab(self.platform, None, self.dir.name)
        self.out = os.path.join(self.dir.name, "field-report")
        os.makedirs(self.out)
        self.terminal = Terminal(serial="ZK-VISIT-1")
        populate(self.terminal, [PIN, "90418"], days=5)
        self.emulator = Emulator(self.terminal, port=0).start()

    def tearDown(self):
        self.emulator.stop()
        self.platform.close()
        self.dir.cleanup()

    def usb_export_of_the_terminal(self):
        with open(os.path.join(self.out, "1_attlog.dat"), "w", newline="\r\n") as handle:
            for record in self.terminal.records:
                handle.write(f"{record.user_id:>9}\t{record.when:%Y-%m-%d %H:%M:%S}\t{record.in_out}\t{record.verify}\t0\t0\n")

    def test_backup_in_out_from_a_check_out_punch_two_sends_and_a_usb_export_with_the_same_columns(self):
        answers = VisitStart() + [
            ("أنهي جهاز", "2"),                                  # type the address
            ("اكتب IP", f"127.0.0.1:{self.emulator.port}"),
            ("نوع الجهاز", "1"),
            ("الستيكر", "1"),
            ("عدد السجلات", "1"),
            ("Cloud Server Setting", "1"),                        # no push screen
            ("الجهاز متظبط على إيه", "3"),                       # +02:00
            ("بتتظبط لوحدها", "3"),
            ("بصمة دخول", lambda: self.terminal.add_punch(PIN, in_out=0, verify=15) and ""),
            ("Check-Out", lambda: self.terminal.add_punch(PIN, in_out=1, verify=15) and ""),
            ("ملف USB", lambda: self.usb_export_of_the_terminal() or "2"),
            ("أنهي ملف", "1"),
            ("برنامج", "1"),
            ("كابل أو switch", "1"),
        ]
        console = ScriptedConsole(answers)
        visited = quick_visit(console, self.lab, self.out)
        code = visited.run()
        self.assertEqual(console.answers, [], "every question was asked")
        self.assertEqual(code, 0, console.text)
        self.assertEqual(self.terminal.write_attempts, [], "the visit only ever read the terminal")
        self.assertEqual(self.lab.allocations, [("ZK-VISIT-1", "zkteco", "+02:00")])

        backup = Path(self.out, "ZK-VISIT-1-attlog-backup.tsv").read_text().splitlines()
        self.assertEqual(len(backup) - 1, len(self.terminal.records) - 2,
                         "the backup is the log as it was before the two test punches")
        sheet = visited.sheet
        self.assertEqual(sheet["الـ in_out_field الصح"], "punch", "the code that read 1 on the check-out punch")
        self.assertEqual((sheet["بيرد على"], sheet["عليه Comm Key؟"], sheet["Push / ADMS موجود؟"]), ("TCP", "لا", "لا"))
        self.assertEqual(sheet["USB: ترتيب الأعمدة زي الـ Push؟"], "نعم")
        self.assertIn("تاني إرسال ماسجّلش حاجة جديدة", console.text)
        stored = sum(len(submission["lines"]) for submission in self.platform.submissions
                     if submission["query"]["delivery"] == "agent")
        self.assertEqual(stored, len(self.terminal.records))
        report = report_of(self.out)
        self.assertNotIn(PIN, report)
        self.assertIn("in_out_field", report)

    def test_another_employee_punching_at_the_same_moment_is_asked_about_not_assumed(self):
        def the_agreed_employee_and_a_stranger():
            self.terminal.add_punch(PIN, in_out=1, verify=15)              # the check-out we asked for
            self.terminal.add_punch("90999", when=datetime.now() + timedelta(seconds=2), in_out=0, verify=1)
            return ""

        answers = VisitStart() + [
            ("أنهي جهاز", "2"), ("اكتب IP", f"127.0.0.1:{self.emulator.port}"), ("نوع الجهاز", "1"),
            ("الستيكر", "1"), ("عدد السجلات", "1"), ("Cloud Server Setting", "1"),
            ("الجهاز متظبط على إيه", "3"), ("بتتظبط لوحدها", "3"),
            ("بصمة دخول", lambda: self.terminal.add_punch(PIN, in_out=0, verify=15) and ""),
            ("Check-Out", the_agreed_employee_and_a_stranger),
            ("أنهي واحدة بتاعة الموظف", "1"),                                # ours is the earlier one
            ("ملف USB", "1"), ("برنامج", "1"), ("كابل أو switch", "1"),
        ]
        console = ScriptedConsole(answers)
        visited = quick_visit(console, self.lab, self.out)
        code = visited.run()
        self.assertEqual(console.answers, [])
        self.assertEqual(code, 0, console.text)
        self.assertEqual(visited.sheet["الـ in_out_field الصح"], "punch",
                         "read from the agreed employee's punch, not from whoever punched next")

    def test_a_delivery_that_stored_nothing_is_not_reported_as_a_success(self):
        """A second visit runs with a new spool, so the agent re-sends and the platform dedups.
        Stored=0 then means "the platform already has it" -- or that nothing arrived at all, which
        is what the runbook's `down --wipe` note warns about. Either way it is not a plain ✅."""
        console = ScriptedConsole([])
        visited = quick_visit(console, self.lab, self.out)
        visited.out.mkdir(parents=True, exist_ok=True)
        visited.zk_link = ("127.0.0.1", self.emulator.port, 0, False)
        config = Path(self.out, "send.toml")
        first_spool, second_spool = "first.sqlite3", "second.sqlite3"
        config.write_text(visited._agent_toml(first_spool) + f"""
[[devices]]
serial = "ZK-VISIT-1"
kind = "zk"
host = "127.0.0.1"
port = {self.emulator.port}
""", encoding="utf-8")
        visited.send_twice(config, "127.0.0.1")
        self.assertFalse([f for f in visited.findings if f.level != "ok"], "the first delivery is clean")

        config.write_text(config.read_text().replace(first_spool, second_spool), encoding="utf-8")
        visited.findings.clear()
        visited.send_twice(config, "127.0.0.1")
        warned = [f for f in visited.findings if f.level == "warn"]
        self.assertTrue(warned, [f.text for f in visited.findings])
        self.assertIn("مفيش ولا سجل جديد اتسجل في السيستم", warned[0].text)

    def test_a_punch_made_between_the_two_sends_is_not_reported_as_a_duplicate(self):
        """Runbook 6.4's second pass must store nothing -- unless the employee punched again while
        it ran, which is a new record and not the platform storing the same one twice. Reporting
        that as ❌ "البصمات بتتكرر" would stop a delivery that is working."""
        console = ScriptedConsole([])
        visited = quick_visit(console, self.lab, self.out)
        visited.out.mkdir(parents=True, exist_ok=True)
        visited.zk_link = ("127.0.0.1", self.emulator.port, 0, False)

        def a_punch_while_the_second_pass_runs(seconds):
            self.terminal.add_punch(PIN, in_out=0, verify=15)

        # The wait between the two passes is exactly when a terminal at a live site takes one.
        visited.sleep = a_punch_while_the_second_pass_runs
        config = Path(self.out, "send.toml")
        config.write_text(visited._agent_toml("between.sqlite3") + f"""
[[devices]]
serial = "ZK-VISIT-1"
kind = "zk"
host = "127.0.0.1"
port = {self.emulator.port}
""", encoding="utf-8")
        visited.send_twice(config, "127.0.0.1")
        warned = [f for f in visited.findings if f.level == "warn"]
        self.assertTrue(warned, [f.text for f in visited.findings])
        self.assertIn("بصمة جديدة اتعملت في اللحظة دي", warned[0].text)
        self.assertEqual([f.text for f in visited.findings if f.level == "bad"], [],
                         "a punch made in the meantime is not the platform storing a record twice")

    def test_a_terminal_clock_that_moves_during_the_visit_is_a_finding(self):
        def check_out_and_the_clock_jumps():
            self.terminal.add_punch(PIN, in_out=1, verify=15)
            # What firmware that applies the platform's TimeZone line does to itself.
            self.terminal.clock_offset = timedelta(hours=1)
            return ""

        answers = VisitStart() + [
            ("أنهي جهاز", "2"), ("اكتب IP", f"127.0.0.1:{self.emulator.port}"), ("نوع الجهاز", "1"),
            ("الستيكر", "1"), ("عدد السجلات", "1"), ("Cloud Server Setting", "1"),
            ("الجهاز متظبط على إيه", "3"), ("بتتظبط لوحدها", "3"),
            ("بصمة دخول", lambda: self.terminal.add_punch(PIN, in_out=0, verify=15) and ""),
            ("Check-Out", check_out_and_the_clock_jumps),
            ("ملف USB", "1"), ("برنامج", "1"), ("كابل أو switch", "1"),
        ]
        console = ScriptedConsole(answers)
        visited = quick_visit(console, self.lab, self.out)
        code = visited.run()
        self.assertEqual(console.answers, [])
        self.assertEqual(code, 1)
        self.assertIn("ساعة الجهاز اتغيرت", report_of(self.out))

    def test_a_check_out_punch_that_changes_neither_code_to_the_out_value_is_reported_not_guessed(self):
        answers = VisitStart() + [
            ("أنهي جهاز", "2"), ("اكتب IP", f"127.0.0.1:{self.emulator.port}"), ("نوع الجهاز", "1"),
            ("الستيكر", "1"), ("عدد السجلات", "1"), ("Cloud Server Setting", "1"),
            ("الجهاز متظبط على إيه", "4"), ("بتتظبط لوحدها", "1"),
            ("بصمة دخول", lambda: self.terminal.add_punch(PIN, in_out=0, verify=15) and ""),
            ("Check-Out", lambda: self.terminal.add_punch(PIN, in_out=4, verify=15) and ""),
            ("ملف USB", "1"), ("برنامج", "1"), ("كابل أو switch", "1"),
        ]
        console = ScriptedConsole(answers)
        visited = quick_visit(console, self.lab, self.out)
        code = visited.run()
        self.assertEqual(code, 1)
        self.assertEqual(visited.sheet["الـ in_out_field الصح"], "مش واضح")
        self.assertIn("مش واضح أنهي عمود فيه الدخول والخروج", report_of(self.out))


class ATerminalThatSaysSomethingOdd(unittest.TestCase):

    def setUp(self):
        self.dir = tempfile.TemporaryDirectory()
        self.platform = FakePlatform()
        self.lab = FakeLab(self.platform, None, self.dir.name)
        self.out = os.path.join(self.dir.name, "field-report")

    def tearDown(self):
        self.platform.close()
        self.dir.cleanup()

    def visit_terminal(self, terminal, answers):
        emulator = Emulator(terminal, port=0).start()
        try:
            console = ScriptedConsole(VisitStart() + [
                ("أنهي جهاز", "2"), ("اكتب IP", f"127.0.0.1:{emulator.port}"), ("نوع الجهاز", "1")] + answers)
            visited = quick_visit(console, self.lab, self.out)
            return visited, visited.run(), console
        finally:
            emulator.stop()

    def test_a_serial_with_a_path_in_it_names_no_file_and_allocates_nothing(self):
        terminal = Terminal(serial="../../evil")
        populate(terminal, [PIN], days=1)
        visited, code, console = self.visit_terminal(terminal, [("برنامج", "1"), ("كابل أو switch", "1")])
        self.assertEqual(console.answers, [])
        self.assertEqual(code, 1)
        report = report_of(self.out)
        self.assertIn("مش هيقبله", report)
        self.assertNotIn("evil", report, "a refused serial is still a serial the report must not publish")
        self.assertIn("device-serial", report)
        self.assertIn("evil", console.text, "the operator still sees it on screen")
        self.assertEqual(self.lab.allocations, [])
        self.assertEqual([path.name for path in Path(self.dir.name).rglob("*evil*")], [])

    def test_an_unknown_device_names_no_address_in_the_report(self):
        """The report is written to be pasted into a GitHub issue. A device the visit could not
        identify is `device`, not `device-<ip>`, and the customer's internal addressing stays in
        field-report/ on the laptop."""
        address = "192.168.44.77"
        console = ScriptedConsole(VisitStart() + [
            ("أنهي جهاز", "2"), ("اكتب IP", f"{address}:8080"), ("نوع الجهاز", "3"),
            ("الماركة والموديل", "Anviz W1"), ("إعداد server", "1"),
            ("برنامج", "1"), ("كابل أو switch", "1")])
        visited = quick_visit(console, self.lab, self.out)
        code = visited.run()
        self.assertEqual(console.answers, [])
        self.assertEqual(code, 0, console.text)
        # It examined a terminal: found, identified, and answered "not supported yet". The
        # headline must not say nothing was examined -- the sheet two rows down says otherwise.
        self.assertNotIn("الزيارة ماعملتش أي فحص", report_of(self.out))
        self.assertEqual(visited.serial, "device")
        self.assertNotIn(address, report_of(self.out))
        self.assertEqual([path.name for path in Path(self.out).glob(f"*{address}*")], [])

    def test_an_address_nothing_answers_is_a_finding_that_does_not_name_it(self):
        address = "192.168.44.78"
        original = visit.probe.probe_host
        visit.probe.probe_host = lambda *args, **kwargs: {}
        try:
            console = ScriptedConsole(VisitStart() + [
                ("أنهي جهاز", "2"), ("اكتب IP", address),
                ("ملف USB", "1"), ("برنامج", "1"), ("كابل أو switch", "1")])
            code = quick_visit(console, self.lab, self.out).run()
        finally:
            visit.probe.probe_host = original
        self.assertEqual(console.answers, [])
        self.assertEqual(code, 1)
        report = report_of(self.out)
        self.assertIn("مفيش حاجة بترد على العنوان اللي اتكتب", report)
        self.assertNotIn(address, report)
        self.assertIn(address, console.text, "the operator still sees which address it was")

    def test_a_bug_in_the_wizard_still_leaves_a_report_saying_so(self):
        terminal = Terminal(serial="ZK-ODD-1", device_name="K40 <b>|x</b>")
        populate(terminal, [PIN], days=1)

        def broken(*args):
            raise RuntimeError("allocation exploded")

        self.lab.allocate = broken
        visited, code, console = self.visit_terminal(terminal, [
            ("الستيكر", "1"), ("عدد السجلات", "1"), ("Cloud Server Setting", "1"),
            ("الجهاز متظبط على إيه", "2"), ("بتتظبط لوحدها", "1"),
            ("برنامج", "1"), ("كابل أو switch", "1")])
        self.assertEqual(code, 1)
        report = report_of(self.out)
        self.assertIn("allocation exploded", report)
        self.assertIn("K40 &lt;b&gt;\\|x&lt;/b&gt;", report, "a terminal's own strings are text, not markup")
        self.assertNotIn("<b>", report)


class AVisitOfAHikvisionTerminal(unittest.TestCase):

    def setUp(self):
        self.dir = tempfile.TemporaryDirectory()
        self.platform = FakePlatform()
        self.lab = FakeLab(self.platform, None, self.dir.name)
        self.out = os.path.join(self.dir.name, "field-report")
        self.terminal = HikTerminal(serial="HIK-VISIT-1", password="s3cret-pass")
        self.terminal.populate([PIN, "90418"], days=3)
        self.server = serve_hik(self.terminal)

    def tearDown(self):
        self.server.shutdown()
        self.server.server_close()
        self.platform.close()
        self.dir.cleanup()

    def punch_now(self):
        now = datetime.now(self.terminal.zone).replace(tzinfo=None)
        self.terminal.add_event(PIN, now, 75, "checkIn")
        return ""

    def test_a_serial_the_system_rejects_is_kept_out_of_the_report(self):
        """The same hole, and here nothing recorded the identifier at any point: `device()` is the
        only other writer of `self.serials` and it sits below the `return` too."""
        rejected = "HIK/VISIT/2"
        terminal = HikTerminal(serial=rejected, password="s3cret-pass")
        terminal.populate([PIN], days=1)
        server = serve_hik(terminal)
        Path(self.out).mkdir(parents=True, exist_ok=True)
        try:
            console = ScriptedConsole([("اسم المستخدم", ""), ("الباسورد", "s3cret-pass")])
            visited = quick_visit(console, self.lab, self.out)
            visited.hik_flow("127.0.0.1", server.server_address[1])
            text = visited.write_report().read_text(encoding="utf-8")
        finally:
            server.shutdown()
            server.server_close()
        self.assertEqual(console.answers, [])
        self.assertIn("مش هيقبله", text)
        self.assertNotIn(rejected, text, "the report published a serial the visit refused")
        self.assertIn("device-serial", text)
        self.assertIn(rejected, console.text, "the operator still sees it on screen")
        self.assertEqual(self.lab.allocations, [], "nothing is claimed under a refused serial")

    def test_codes_a_live_punch_and_two_sends_and_the_password_does_not_outlive_the_visit(self):
        answers = VisitStart() + [
            ("أنهي جهاز", "2"), ("اكتب IP", f"127.0.0.1:{self.server.server_address[1]}"), ("نوع الجهاز", "2"),
            ("اسم المستخدم", ""), ("الباسورد", "s3cret-pass"),
            ("يعمل بصمة على الجهاز", self.punch_now),
            ("الجهاز متظبط على إيه", "4"), ("بتتظبط لوحدها", "1"),
            ("برنامج", "1"), ("كابل أو switch", "1"),
        ]
        console = ScriptedConsole(answers)
        visited = quick_visit(console, self.lab, self.out)
        code = visited.run()
        self.assertEqual(console.answers, [])
        self.assertEqual(code, 0, console.text)
        self.assertEqual(self.lab.allocations, [("HIK-VISIT-1", "hikvision", "+03:00")])
        self.assertIn("البصمة وصلت بكود 75", console.text)
        self.assertIn("(ساعة الجهاز بتقول +03:00)", console.text, "the zone the terminal's own events carry")
        self.assertIn("9", visited.sheet["Hikvision: أكواد الحضور اللي ظهرت"])
        self.assertTrue(any(f.level == "warn" and "بأكواد 9" in f.text for f in visited.findings),
                        "a refused card carries an employee but is not attendance: the operator is told")
        self.assertFalse(Path(self.out, "hik.pw").exists(), "the password file is deleted at the end")
        for path in Path(self.out).rglob("*"):
            if path.is_file():
                self.assertNotIn(b"s3cret-pass", path.read_bytes(), path)
        self.assertNotIn(PIN, report_of(self.out))
        self.assertTrue(self.platform.submissions, "delivered through the agent path")


class AVisitWithoutNetworkConsent(unittest.TestCase):

    def test_only_the_usb_export_is_offered_and_a_bad_serial_is_asked_again(self):
        with tempfile.TemporaryDirectory() as directory:
            platform = FakePlatform()
            try:
                lab = FakeLab(platform, None, directory)
                out = os.path.join(directory, "field-report")
                os.makedirs(out)
                with open(os.path.join(out, "1_attlog.dat"), "w") as handle:
                    handle.write("\n".join(adms.backlog([PIN], 3)) + "\n")
                answers = VisitStart(consent="2") + [
                    ("ملف USB", "2"), ("أنهي ملف", "1"),
                    ("سيريال الجهاز", "HAS SPACES"), ("سيريال الجهاز", "USB-VISIT-1"),
                    ("الجهاز متظبط على إيه", "2"), ("بتتظبط لوحدها", "2"),
                    ("برنامج", "1"), ("كابل أو switch", "1"),
                ]
                console = ScriptedConsole(answers)
                visited = quick_visit(console, lab, out)
                code = visited.run()
                self.assertEqual(console.answers, [])
                self.assertEqual(code, 0, console.text)
                self.assertEqual(visited.scans, [], "no consent, no scan")
                self.assertEqual(lab.allocations, [("USB-VISIT-1", "zkteco", "Africa/Cairo")])
                self.assertEqual({s["query"]["delivery"] for s in platform.submissions}, {"file"})
                self.assertIn("الملف اترفع: 6 سطر، 6 جديد", console.text)
            finally:
                platform.close()


class TheLabNotReady(unittest.TestCase):

    def test_a_lab_that_is_down_stops_the_visit_before_any_question_about_a_terminal(self):
        class DownLab(visit.Lab):
            def __init__(self):
                self.ran = []

            def check(self):
                return "up"

            def run(self, command):
                self.ran.append(command)
                return 1

        lab = DownLab()
        with tempfile.TemporaryDirectory() as directory:
            console = ScriptedConsole([("اسم الشركة", ""), ("مش شغال", "2")])
            code = quick_visit(console, lab, os.path.join(directory, "out")).run()
            self.assertEqual(code, 1)
            self.assertEqual(lab.ran, [], "declined: nothing started")
            self.assertEqual(list(Path(directory).glob("**/visit-*.md")), [], "no device, no report")
            self.assertIn("scripts/devices-lab.sh up", console.text)


class TheQuestionsThatMustNotGuess(unittest.TestCase):

    def setUp(self):
        self.dir = tempfile.TemporaryDirectory()
        self.platform = FakePlatform()
        self.lab = FakeLab(self.platform, None, self.dir.name)
        self.out = os.path.join(self.dir.name, "field-report")
        self.terminal = Terminal(serial="ZK-TZ-1")
        populate(self.terminal, [PIN], days=1)
        self.emulator = Emulator(self.terminal, port=0).start()

    def tearDown(self):
        self.emulator.stop()
        self.platform.close()
        self.dir.cleanup()

    def test_pressing_enter_at_the_time_zone_question_allocates_nothing(self):
        # The platform sends this zone to the terminal on every handshake and some firmware sets
        # its clock from it, which runbook rule 2 forbids. Enter must not pick one.
        answers = VisitStart() + [
            ("أنهي جهاز", "2"), ("اكتب IP", f"127.0.0.1:{self.emulator.port}"), ("نوع الجهاز", "1"),
            ("الستيكر", "1"), ("عدد السجلات", "1"), ("Cloud Server Setting", "1"),
            ("الجهاز متظبط على إيه", ""), ("الجهاز متظبط على إيه", ""),
            ("ملف USB", "1"), ("برنامج", "1"), ("كابل أو switch", "1"),
        ]
        console = ScriptedConsole(answers)
        visited = quick_visit(console, self.lab, self.out)
        code = visited.run()
        self.assertEqual(console.answers, [])
        self.assertEqual(code, 1)
        self.assertEqual(self.lab.allocations, [], "nothing was allocated on a guess")
        self.assertIn("الجهاز ماتخصصش لأن الـ time zone بتاعه مش معروف", report_of(self.out))
        self.assertIn("Date Time", console.text, "it says where to read the answer")

    def test_a_lab_running_from_another_checkout_is_not_reseeded_by_pressing_enter(self):
        seeded = []

        class TokenlessLab(FakeLab):
            def check(inner):
                return "seed"

            def reachable(inner):
                return True

            def run(inner, command):
                seeded.append(command)
                return 0

        console = ScriptedConsole([("اسم الشركة", ""), ("أعمل seed", "")])   # Enter at the seed question
        code = quick_visit(console, TokenlessLab(self.platform, None, self.dir.name), self.out).run()
        self.assertEqual(console.answers, [])
        self.assertEqual(seeded, [], "Enter does not rotate a token another session may be using")
        self.assertEqual(code, 1)
        self.assertIn("ممكن يكون شغال من نسخة تانية من الريبو", console.text)


class ThePiecesTheWizardDecidesWith(unittest.TestCase):

    def exchange(self, body, stamp="9", status=200, content_type="text/plain"):
        return visit.Exchange(0.0, "SN1", "POST", f"/iclock/cdata?SN=SN1&table=ATTLOG&Stamp={stamp}", status,
                              content_type, body.encode(), b"OK")

    def test_the_results_sheet_is_the_runbooks_appendix_row_for_row(self):
        text = (ROOT / "docs" / "devices" / "field-visit-runbook.md").read_text(encoding="utf-8")
        appendix = text[text.index("## ملحق"):]
        rows = [line.split("|")[1:3] for line in appendix.splitlines() if line.startswith("| ")]
        self.assertEqual(tuple(cells[0].strip() for cells in rows[1:]), visit.SHEET_ROWS)
        self.assertEqual({cells[0].strip() for cells in rows[1:] if cells[1].strip() == "لم يُختبر"}, set(visit.UNTESTED))

    def test_unix_times_unparsed_lines_and_a_413_are_each_reported(self):
        facts, findings = visit.push_facts([self.exchange(f"{PIN}\t1758000000\t0\t1\n")], "SN1")
        self.assertEqual(facts["الوقت بيتبعت"], "رقم طويل")
        self.assertEqual([f.level for f in findings], ["warn"])
        facts, findings = visit.push_facts(
            [self.exchange("# unparsed line shape: 99999,9999-99-99 99:99:99\n"),
             self.exchange("", stamp="a1b", status=413)], "SN1")
        self.assertEqual(facts["سطر ATTLOG: الفاصل"], "غيره")
        self.assertEqual(facts["ظهر 413؟"], "نعم")
        self.assertEqual(facts["الـ Stamp في رفع ATTLOG"], "شكل تاني: 9، a9a")
        self.assertEqual(sorted(f.level for f in findings), ["bad", "bad"])
        self.assertTrue(all(PIN not in f.text for f in findings))

    def test_the_in_out_code_is_the_one_that_changed_between_the_two_punches(self):
        # (punch, status) of the check-in punch, then of the check-out punch.
        self.assertEqual(visit.in_out_field((0, 15), (1, 15), out_value=1), "punch")
        self.assertEqual(visit.in_out_field((15, 0), (15, 1), out_value=1), "status")
        self.assertEqual(visit.in_out_field((0, 4), (1, 4), out_value=1), "punch")
        # A terminal whose verify mode is 1 reads 1 in `status` on BOTH punches: reading one
        # check-out record alone would call that the in/out code.
        self.assertEqual(visit.in_out_field((0, 1), (1, 1), out_value=1), "punch")
        # Both codes changed to the check-out value, or neither did: the visit says so.
        self.assertIsNone(visit.in_out_field((0, 0), (1, 1), out_value=1))
        self.assertIsNone(visit.in_out_field((0, 15), (4, 15), out_value=1))

    def test_only_a_punch_that_can_be_the_one_just_made_counts(self):
        def line(pin, when, in_out="0"):
            return visit.Arrival(1.0, [pin, when, in_out, "1", "0", "0", "0"])

        seen = Counter({(PIN, "2026-09-20 08:00:00"): 1})
        latest = "2026-09-20 08:00:00"
        backlog = line(PIN, "2026-09-18 07:15:00", "1")
        resent = line(PIN, "2026-09-20 08:00:00")
        live = line(PIN, "2026-09-20 08:04:31")
        twice = [line(PIN, "2026-09-20 08:04:35"), line(PIN, "2026-09-20 08:04:35")]
        unreadable = visit.Arrival(1.0, None)
        self.assertEqual(visit.fresh_punches([backlog, resent], seen, latest), [],
                         "a two-day-old record and a line already delivered are the backlog")
        self.assertEqual(visit.fresh_punches([backlog, live], seen, latest), [live])
        self.assertEqual(visit.fresh_punches(twice, seen, latest), twice,
                         "two punches in the same second are two punches")
        self.assertEqual(visit.fresh_punches([unreadable], seen, latest), [unreadable])
        # Unix-seconds firmware is compared in its own units, never against a wall clock.
        unix_old, unix_new = line(PIN, "1758000000"), line(PIN, "1758009999")
        self.assertEqual(visit.fresh_punches([unix_old, unix_new], Counter(), 1758005000), [unix_new])
        self.assertEqual(visit.fresh_punches([unix_old], Counter(), latest), [unix_old],
                         "a format the visit cannot compare is never dropped")

    def test_scan_results_are_named_by_what_answered(self):
        cases = [
            ({"ip": "10.0.0.2", "zk_tcp": {"serial": "A1"}}, "zk"),
            ({"ip": "10.0.0.3", "zk_tcp": {"comm_key_required": True}}, "zk"),
            ({"ip": "10.0.0.4", "zk_udp": {"serial": "A2"}}, "zk"),
            ({"ip": "10.0.0.5", "http_80": {"guess": "hikvision (ISAPI)"}}, "hik"),
            ({"ip": "10.0.0.6", "http_80": {"guess": "zkteco web"}}, "push"),
            ({"ip": "10.0.0.7", "open_ports": {37777: "Dahua"}}, "other"),
            ({"ip": "10.0.0.8", "open_ports": {80: "HTTP"}, "http_80": {"guess": "unknown http"}}, "other"),
        ]
        for found, kind in cases:
            self.assertEqual(visit.classify(found)[0], kind, found)
        self.assertIn("UDP", visit.classify(cases[2][0])[1])

    def test_lan_networks_skip_loopback_docker_and_bridges(self):
        output = ("1: lo    inet 127.0.0.1/8 scope host lo\n"
                  "2: enp3s0    inet 192.168.1.57/24 brd 192.168.1.255 scope global enp3s0\n"
                  "3: wlp0s20f3    inet 172.20.10.4/28 brd 172.20.10.15 scope global wlp0s20f3\n"
                  "4: docker0    inet 172.17.0.1/16 scope global docker0\n"
                  "5: br-30fc515e80a8    inet 192.168.48.1/20 scope global br-30fc515e80a8\n")
        self.assertEqual(probe.lan_networks(output),
                         [("192.168.1.57", "192.168.1.0/24"), ("172.20.10.4", "172.20.10.0/28")])
        # A link-local address means DHCP failed, and a point-to-point /32 is not a LAN: offering
        # either as the "Server Address" to type into a terminal sends the operator nowhere.
        self.assertEqual(probe.lan_networks("6: eth2    inet 169.254.9.9/16 scope link eth2\n"
                                            "7: ppp0    inet 10.9.9.9 peer 10.9.9.1/32 scope global ppp0\n"
                                            "8: tailscale0    inet 100.90.1.2/32 scope global tailscale0\n"
                                            "9: eth3    inet 10.3.0.4/24 scope global eth3\n"),
                         [("10.3.0.4", "10.3.0.0/24")])

    def test_the_inventory_claims_no_record_format_the_client_cannot_read(self):
        """The device inventory is the authority on what a model stores, and this client is what
        reads it. A model entered there with a layout the client cannot parse is a visit that fails
        on the wall, so the two are held together here rather than discovered at a customer."""
        text = (ROOT / "docs" / "devices" / "attendance-device-model-and-firmware-inventory.md").read_text(
            encoding="utf-8")
        formats = re.findall(r"^\| Record format \| (\d+)-byte \|$", text, re.M)
        self.assertTrue(formats, "no model records a format; the drift gate would pass vacuously")
        for size in formats:
            self.assertIn(int(size), visit.zk.RECORD_SIZES, f"{size}-byte is claimed but unreadable")
        columns = re.findall(r"^\| In/out column \| (\w+) \|$", text, re.M)
        self.assertTrue(columns)
        for column in columns:
            self.assertIn(column, ("punch", "status"), "a record has exactly these two codes")

    def test_the_arp_state_is_read_for_the_address_asked_about(self):
        """The state that decides a visit is FAILED: it answers every connect with EHOSTUNREACH at
        once, so the wizard reads the same "no route to host" twice seconds apart and blames a
        terminal that is on the wall and answering."""
        # Locally-administered MACs: the shape is what this parses, and a captured hardware address
        # identifies the site it was captured at.
        table = ("192.168.1.1 dev wlp0s20f3 lladdr 02:00:00:00:00:01 router REACHABLE\n"
                 "192.168.1.201 dev wlp0s20f3 lladdr 02:00:00:00:00:02 STALE\n")
        self.assertEqual(probe.arp_state("192.168.1.201", table), "STALE")
        self.assertEqual(probe.arp_state("192.168.1.1", table), "REACHABLE", "read past `router`")
        self.assertEqual(probe.arp_state("192.168.1.201", "192.168.1.201 dev wlp0s20f3  FAILED\n"), "FAILED")
        # An address the table does not carry has no state, and one address is not another's prefix.
        self.assertIsNone(probe.arp_state("192.168.1.20", table))
        self.assertIsNone(probe.arp_state("192.168.1.201", ""))

    def test_the_wifi_link_reads_the_column_the_driver_actually_moves(self):
        """/proc/net/wireless ends with Missed beacon, which stays 0 on this card while `misc`
        climbs on a marginal link: a reader that takes the last column calls that link healthy."""
        link = probe.wifi_link(
            "Inter-| sta-|   Quality        |   Discarded packets               | Missed | WE\n"
            " face | tus | link level noise |  nwid  crypt   frag  retry   misc | beacon | 22\n"
            "wlp0s20f3: 0000   42.  -68.  -256        0      0      0      4    403        0\n")
        self.assertEqual(link["interface"], "wlp0s20f3")
        self.assertEqual((link["link"], link["level"]), (42, -68))
        self.assertEqual((link["misc"], link["missed_beacon"]), (403, 0))
        self.assertIsNone(probe.wifi_link("Inter-| sta-|\n face | tus |\n"), "a laptop with no Wi-Fi")

    def test_ping_counts_duplicates_and_never_invents_a_healthy_result(self):
        """0% loss is not a healthy network. A repeated or bridged Wi-Fi answers every echo, loses
        the ARP exchange anyway, and gives itself away by duplicating replies and spreading the
        round trip over two orders of magnitude."""
        measured = probe.ping_facts("192.168.1.201", ping_output=(
            "--- 192.168.1.201 ping statistics ---\n"
            "120 packets transmitted, 120 received, +9 duplicates, 0% packet loss, time 119193ms\n"
            "rtt min/avg/max/mdev = 0.890/41.690/441.490/75.562 ms\n"))
        self.assertEqual((measured["transmitted"], measured["received"], measured["duplicates"],
                          measured["loss_percent"]), (120, 120, 9, 0.0))
        self.assertEqual(measured["rtt_ms"]["max"], 441.49)
        lossy = probe.ping_facts("10.0.0.9", ping_output=(
            "5 packets transmitted, 3 received, 40% packet loss, time 4050ms\n"
            "rtt min/avg/max/mdev = 1.000/2.000/3.000/0.500 ms\n"))
        self.assertEqual((lossy["received"], lossy["duplicates"], lossy["loss_percent"]), (3, 0, 40.0))
        # Windows counts with -n and prints a different summary. Reporting 0% loss from output this
        # does not understand would send an operator back to a terminal that is not the problem.
        self.assertIn("unavailable", probe.ping_facts("10.0.0.9", ping_output="Reply from 10.0.0.9: bytes=32"))

    def test_the_discovery_probe_retries_a_lost_arp_exchange_on_one_address(self):
        """`_open` runs *before* ZkClient.connect(), so without this a lost ARP exchange makes a
        terminal that is there vanish from the answer and the client's retry never runs. Off for a
        scan, which asks this of every port of every address and reads a closed port as normal."""
        calls = []

        def flaky(address, timeout=None):
            calls.append(address)
            raise OSError(errno.EHOSTUNREACH, "No route to host")

        with mock.patch.object(visit.zk, "CONNECT_RETRY_SECONDS", 0), \
             mock.patch.object(probe.socket, "create_connection", flaky):
            self.assertFalse(probe._open("192.0.2.1", 4370, 0.1, retry_transient=True))
            self.assertEqual(len(calls), visit.zk.CONNECT_ATTEMPTS)
            calls.clear()
            self.assertFalse(probe._open("192.0.2.1", 4370, 0.1))
            self.assertEqual(len(calls), 1, "a scan must not pay the retry for every closed port")

        refusals = []

        def refused(address, timeout=None):
            refusals.append(address)
            raise OSError(errno.ECONNREFUSED, "Connection refused")

        with mock.patch.object(visit.zk, "CONNECT_RETRY_SECONDS", 0), \
             mock.patch.object(probe.socket, "create_connection", refused):
            self.assertFalse(probe._open("192.0.2.1", 4370, 0.1, retry_transient=True))
        self.assertEqual(len(refusals), 1, "a refusal is the answer, not a blip")

    def test_the_laptop_network_pings_nothing_when_it_was_given_no_address(self):
        facts = probe.laptop_network()
        self.assertIn("addresses", facts)
        self.assertEqual(set(facts) & {"target", "arp", "ping"}, set())

    def test_the_push_cross_check_reads_the_serial_the_terminal_pushes_under(self):
        """A terminal that reports one serial on 4370 and pushes under another is a finding of its
        own, and the visit carries on. The in/out cross-check has to follow it to the serial the
        upload actually carried, or it quietly falls back to assuming 1 is the check-out value."""
        punch = visit.zk.RawAttendance(user_id=PIN, timestamp=datetime(2026, 9, 20, 8, 4, 31),
                                       status=15, punch=1, record_size=40)
        with tempfile.TemporaryDirectory() as directory:
            visited = visit.Visit(console=ScriptedConsole([]), out_dir=directory, wait_seconds=1)
            visited.serial = "ZK-4370-1"
            visited.push = visit.Push("PUSH-OTHER-1")
            visited.receiver = visit.Receiver(directory, None, None)
            visited.receiver.server = object()          # nothing is served: wait() only reads the list
            visited.receiver.exchanges = [visit.Exchange(
                0.0, "PUSH-OTHER-1", "POST", "/iclock/cdata?SN=PUSH-OTHER-1&table=ATTLOG&Stamp=9", 200,
                "text/plain", f"{PIN}\t2026-09-20 08:04:31\t1\t15\t0\t0\n".encode(), b"OK: 1")]
            found = visited.pushed_line(punch)
        self.assertIsNotNone(found, "the punch as the terminal pushed it, under its push serial")
        self.assertEqual(found.in_out, "1")

    def test_a_lab_script_that_never_answers_is_a_finding_not_a_crash(self):
        lab = visit.Lab(root=Path("/nonexistent-checkout"))
        original = visit.subprocess.run

        def never_answers(*args, **kwargs):
            raise visit.subprocess.TimeoutExpired(cmd="devices-lab.sh", timeout=180)

        visit.subprocess.run = never_answers
        try:
            ok, text = lab.allocate("SN-1", "zkteco", "+02:00")
        finally:
            visit.subprocess.run = original
        self.assertFalse(ok)
        self.assertIn("devices-lab.sh allocate", text, "a wedged docker is a finding, not a traceback")
        self.assertFalse(lab.allocate("SN-1", "zkteco", "+02:00")[0], "and a missing script is one too")

    def test_a_second_usb_export_of_the_same_name_does_not_overwrite_the_first(self):
        with tempfile.TemporaryDirectory() as directory:
            platform = FakePlatform()
            try:
                out = Path(directory, "field-report")
                out.mkdir()
                (out / "1_attlog.dat").write_text("the first terminal's export\n")
                stick = Path(directory, "stick")
                stick.mkdir()
                (stick / "1_attlog.dat").write_text("\n".join(adms.backlog([PIN], 2)) + "\n")
                console = ScriptedConsole(VisitStart(consent="2") + [
                    ("ملف USB", "2"), ("أنهي ملف", "2"), ("مكان الملف", str(stick / "1_attlog.dat")),
                    ("سيريال الجهاز", "USB-TWO-1"),
                    ("الجهاز متظبط على إيه", "2"), ("بتتظبط لوحدها", "3"),
                    ("برنامج", "1"), ("كابل أو switch", "1")])
                quick_visit(console, FakeLab(platform, None, directory), str(out)).run()
                self.assertEqual(console.answers, [])
                self.assertEqual((out / "1_attlog.dat").read_text(), "the first terminal's export\n")
                self.assertTrue((out / "1_attlog-1.dat").exists(), sorted(p.name for p in out.iterdir()))
            finally:
                platform.close()

    def test_the_lab_allocation_refuses_what_it_would_put_into_sql(self):
        with tempfile.TemporaryDirectory() as directory:
            # A docker that only records being called: a refusal must come before any database.
            marker = Path(directory, "docker-called")
            docker = Path(directory, "docker")
            docker.write_text(f"#!/bin/sh\n: > {marker}\nexit 99\n")
            docker.chmod(0o755)
            for tool in ("dirname", "cut"):
                Path(directory, tool).symlink_to(shutil.which(tool))
            for arguments in (["has space", "zkteco", "+02:00"], ["SN1'; DROP TABLE x; --", "zkteco", "+02:00"],
                              ["SN1", "dahua", "+02:00"], ["SN1", "zkteco", "+02:30"], ["SN1", "zkteco", "Europe/X'"],
                              ["", "zkteco", "+02:00"]):
                done = subprocess.run([shutil.which("bash"), str(ROOT / "scripts" / "devices-lab.sh"), "allocate",
                                       *arguments], capture_output=True, text=True, env={"PATH": directory})
                self.assertEqual(done.returncode, 2, (arguments, done.stdout, done.stderr))
                self.assertIn("STOPPED", done.stderr)
            self.assertFalse(marker.exists(), "no refused allocation reached docker")
            done = subprocess.run([shutil.which("bash"), str(ROOT / "scripts" / "devices-lab.sh"), "allocate",
                                   "SN1", "zkteco", "Africa/Cairo"], capture_output=True, text=True,
                                  env={"PATH": directory})
            self.assertTrue(marker.exists(), "a valid allocation does go to the lab database")
            self.assertNotEqual(done.returncode, 0)


    def test_code_spread_counts_both_columns_across_the_log(self):
        def record(punch, status):
            return visit.zk.RawAttendance("1", datetime(2026, 9, 1, 8, 0), status=status, punch=punch,
                                          record_size=40)
        self.assertEqual(visit.code_spread([record(0, 1), record(1, 1), record(0, 1)]),
                         {"punch": {0: 2, 1: 1}, "status": {1: 3}})

    # TERMINAL-A's own log, counted from the backup the 2026-09-21 visit took: 11 426 records.
    REAL_SPREAD = {"punch": {0: 5779, 1: 5640, 4: 2, 5: 5}, "status": {1: 11424, 15: 2}}

    def test_the_log_decides_the_in_out_code_when_the_two_punches_cannot(self):
        """That terminal stored the check-out with the same two codes as the check-in, so the pair
        answers nothing. Its log does -- but not by either column being constant: `status` is 1 in
        11 424 records and 15 in two, which is two people identifying themselves differently, not
        the branch going home. The column that tells an arrival from a departure carries both in
        numbers, and `punch` splits 5 779 to 5 640."""
        same = ((5, 1), (5, 1))
        self.assertIsNone(visit.in_out_field(*same, out_value=1))
        self.assertEqual(visit.in_out_field(*same, out_value=1, spread=self.REAL_SPREAD), "punch")
        # A handful of exceptions is not a second value in the sense that matters.
        self.assertFalse(visit.separates({1: 11424, 15: 2}))
        self.assertTrue(visit.separates({0: 5779, 1: 5640, 4: 2, 5: 5}))
        # A balanced column is never enough on its own. Here the real in/out column is one-sided --
        # a branch where people rarely press the exit key -- while two verification methods are both
        # common, so electing "the balanced one" would hand back the verification column and
        # agent_zk would send the whole log under it.
        self.assertIsNone(visit.in_out_field(*same, out_value=1,
                                             spread={"punch": {0: 10450, 1: 550},
                                                     "status": {1: 6600, 2: 4400}}))
        self.assertTrue(visit.cannot_separate({1: 11424, 15: 2}))
        self.assertFalse(visit.cannot_separate({0: 10450, 1: 550}), "550 exits is not 'no exits'")
        # Two balanced columns are ambiguous again, and so is a log too short to carry the argument.
        self.assertIsNone(visit.in_out_field(*same, out_value=1,
                                             spread={"punch": {0: 600, 1: 400}, "status": {1: 600, 2: 400}}))
        self.assertIsNone(visit.in_out_field(*same, out_value=1,
                                             spread={"punch": {0: 100, 1: 50}, "status": {1: 150}}))
        # What was watched keeps precedence: `status` changed between these two punches, so it stays
        # the answer even though the log's `punch` column is the balanced one.
        self.assertEqual(visit.in_out_field((15, 0), (15, 1), out_value=1, spread=self.REAL_SPREAD), "status")

    def test_only_an_errno_that_means_the_packet_never_left_counts(self):
        """113 is EHOSTUNREACH: the kernel never resolved the hardware address, so nothing was sent
        and the terminal said nothing. 111 is the terminal refusing, which is the terminal talking."""
        self.assertEqual(visit.unreachable_errno(
            "cannot reach 192.168.1.201:4370 over TCP: [Errno 113] No route to host"), 113)
        self.assertEqual(visit.unreachable_errno(OSError(113, "No route to host")), 113)
        self.assertIsNone(visit.unreachable_errno("[Errno 111] Connection refused"))
        self.assertIsNone(visit.unreachable_errno("the terminal did not answer in time"))

class ABacklogThatIsStillDraining(unittest.TestCase):
    """Runbook 5.4 asks how long a punch takes to arrive and which code carries in/out. A terminal
    with months of records is still uploading them while those tests run, and an old record
    answering a test would put a fabricated latency and a wrong in/out value in the report."""

    def setUp(self):
        self.dir = tempfile.TemporaryDirectory()
        self.platform = FakePlatform()
        self.receiver = FakeReceiver()
        self.lab = FakeLab(self.platform, self.receiver, self.dir.name)
        self.out = os.path.join(self.dir.name, "field-report")
        self.device = None

    def tearDown(self):
        if self.device:
            self.device.stop()
        self.receiver.close()
        self.platform.close()
        self.dir.cleanup()

    def test_an_old_record_arriving_during_the_prompt_does_not_answer_the_punch_test(self):
        console = ScriptedConsole([])
        visited = quick_visit(console, self.lab, self.out)

        def configure():
            self.device = PushDevice(f"http://127.0.0.1:{visited.receiver.port}")
            return ""

        def old_batch_then_a_real_punch():
            # The batch lands first -- every line of it a check-out (1), like the day's history.
            self.device.send_old_batch(lines=3, in_out=1)
            self.device.punch(0)
            return ""

        console.answers = VisitStart() + [
            ("أنهي جهاز", "1"), ("لما تحفظ", configure), ("نفس اللي على الستيكر", "1"),
            ("الجهاز متظبط على إيه", "2"), ("بتتظبط لوحدها", "1"),
            ("بصمة عادية", old_batch_then_a_real_punch),
            ("Check-Out", lambda: self.device.punch(1)), ("بيدوسوا زرار", "1"),
            ("بصمتين ورا بعض", lambda: self.device.punch(0) + self.device.punch(0)),
            ("شيل كابل", "2"), ("وقف الاستقبال", "2"),
            ("HTTPS", "1"), ("Enable Domain Name", "1"), ("Attendance Search", "1"),
            ("ملف USB", "1"), ("Cloud Server Setting", "1"), ("برنامج", "1"), ("كابل أو switch", "1"),
        ]
        code = visited.run()
        self.assertEqual(console.answers, [])
        self.assertEqual((visited.sheet["الدخول"], visited.sheet["الخروج"]), ("0", "1"),
                         "the live check-in punch, not the check-out records still draining: " + console.text[-2000:])
        self.assertIn("الدخول بيوصل 0 والخروج 1", console.text)
        self.assertEqual(code, 0, console.text)

    def test_an_upload_already_on_its_way_does_not_answer_the_punch_test(self):
        """The freshness rule reads the line itself: one the terminal has sent before, or one
        older than a record it has already delivered. An upload already in flight when the
        operator is asked carries neither mark, so only the moment it was recorded tells it from
        the punch -- which is why the stopwatch starts after `enter()` returns, not before."""
        console = ScriptedConsole([])
        visited = quick_visit(console, self.lab, self.out)

        def configure():
            self.device = PushDevice(f"http://127.0.0.1:{visited.receiver.port}")
            return ""

        def an_upload_in_flight_then_the_real_punch():
            recorded = visited.receiver.mark()
            self.device.send_now(in_out=1)
            while visited.receiver.mark() == recorded:      # it is in the recording before Enter
                time.sleep(0.01)
            self.device.punch(0)
            return ""

        console.answers = VisitStart() + [
            ("أنهي جهاز", "1"), ("لما تحفظ", configure), ("نفس اللي على الستيكر", "1"),
            ("الجهاز متظبط على إيه", "2"), ("بتتظبط لوحدها", "1"),
            ("بصمة عادية", an_upload_in_flight_then_the_real_punch),
            ("Check-Out", lambda: self.device.punch(1)), ("بيدوسوا زرار", "1"),
            ("بصمتين ورا بعض", lambda: self.device.punch(0) + self.device.punch(0)),
            ("شيل كابل", "2"), ("وقف الاستقبال", "2"),
            ("HTTPS", "1"), ("Enable Domain Name", "1"), ("Attendance Search", "1"),
            ("ملف USB", "1"), ("Cloud Server Setting", "1"), ("برنامج", "1"), ("كابل أو switch", "1"),
        ]
        code = visited.run()
        self.assertEqual(console.answers, [])
        self.assertEqual((visited.sheet["الدخول"], visited.sheet["الخروج"]), ("0", "1"),
                         "the punch the operator was asked for, not the upload already in flight: "
                         + console.text[-2000:])
        self.assertEqual(code, 0, console.text)

    def test_the_punch_tests_wait_for_the_terminal_to_stop_uploading(self):
        """The backlog wait ends as soon as one line is accepted, but a terminal with months of
        records is still sending. Timing a punch while batches are landing measures whichever
        record arrives next, so the visit waits for the terminal to go quiet first."""
        console = ScriptedConsole([])
        visited = quick_visit(console, self.lab, self.out)
        still_to_send = []

        def configure():
            self.device = TricklingPushDevice(f"http://127.0.0.1:{visited.receiver.port}")
            return ""

        def the_operator_is_asked_for_a_punch():
            still_to_send.append(self.device.batches - self.device.sent)
            self.device.punch(0)
            return ""

        console.answers = VisitStart() + [
            ("أنهي جهاز", "1"), ("لما تحفظ", configure), ("نفس اللي على الستيكر", "1"),
            ("الجهاز متظبط على إيه", "2"), ("بتتظبط لوحدها", "1"),
            ("بصمة عادية", the_operator_is_asked_for_a_punch),
            ("Check-Out", lambda: self.device.punch(1)), ("بيدوسوا زرار", "1"),
            ("بصمتين ورا بعض", lambda: self.device.punch(0) + self.device.punch(0)),
            ("شيل كابل", "2"), ("وقف الاستقبال", "2"),
            ("HTTPS", "1"), ("Enable Domain Name", "1"), ("Attendance Search", "1"),
            ("ملف USB", "1"), ("Cloud Server Setting", "1"), ("برنامج", "1"), ("كابل أو switch", "1"),
        ]
        code = visited.run()
        self.assertEqual(console.answers, [])
        self.assertEqual(still_to_send, [0],
                         "the terminal had emptied its backlog before the operator was asked: "
                         + console.text[-2000:])
        self.assertIn("لسه بيبعت سجلاته القديمة", console.text, "and the operator was told why the wait")
        self.assertEqual(code, 0, console.text)

    def test_the_backlog_wait_offers_a_way_out_once_its_deadline_passes(self):
        """A terminal draining months of records never goes quiet, and the wait is written to
        offer the operator a way on once it has waited long enough. The deadline used to be
        pushed forward on every pass -- including the passes that were only checking it -- so
        it was never reached, the question was never asked, and the only way on was Ctrl-C."""
        console = ScriptedConsole([])
        # wait_seconds=2, so the wait's own deadline is eight seconds; the terminal keeps
        # uploading for nine, which is the shape the question exists for.
        visited = quick_visit(console, self.lab, self.out, wait_seconds=2)

        def configure():
            self.device = TricklingPushDevice(f"http://127.0.0.1:{visited.receiver.port}",
                                              batches=60, interval=0.15)
            return ""

        console.answers = VisitStart() + [
            ("أنهي جهاز", "1"), ("لما تحفظ", configure), ("نفس اللي على الستيكر", "1"),
            ("الجهاز متظبط على إيه", "2"), ("بتتظبط لوحدها", "1"),
            ("أستنى تاني", "2"),
            ("بصمة عادية", lambda: self.device.punch(0)),
            ("Check-Out", lambda: self.device.punch(1)), ("بيدوسوا زرار", "1"),
            ("بصمتين ورا بعض", lambda: self.device.punch(0) + self.device.punch(0)),
            ("شيل كابل", "2"), ("وقف الاستقبال", "2"),
            ("HTTPS", "1"), ("Enable Domain Name", "1"), ("Attendance Search", "1"),
            ("ملف USB", "1"), ("Cloud Server Setting", "1"), ("برنامج", "1"), ("كابل أو switch", "1"),
        ]
        visited.run()

        self.assertEqual(console.answers, [])
        self.assertEqual(console.text.count("أستنى تاني"), 1, "asked once, after the deadline")
        self.assertIn("بدأنا التجارب والجهاز لسه بيبعت سجلاته القديمة", console.text,
                      "and the report says the numbers below it may be a stale record")
        # The same visit's clock row: this path cannot read the terminal's clock back over 4370,
        # and the platform has just sent it a time zone, so the cell says so rather than nothing.
        self.assertIn("| فرق ساعة الجهاز عن اللابتوب | مااتقاسش: الجهاز مابيردش على 4370",
                      report_of(self.out))




class WhenTheVisitCannotFinish(unittest.TestCase):

    def setUp(self):
        self.dir = tempfile.TemporaryDirectory()
        self.platform = FakePlatform()
        self.receiver = FakeReceiver()
        self.lab = FakeLab(self.platform, self.receiver, self.dir.name)
        self.out = os.path.join(self.dir.name, "field-report")
        self.device = None

    def tearDown(self):
        if self.device:
            self.device.stop()
        self.receiver.close()
        self.platform.close()
        self.dir.cleanup()

    def test_a_terminal_already_pointed_at_the_laptop_still_gets_the_restore_checklist(self):
        console = ScriptedConsole([])
        visited = quick_visit(console, self.lab, self.out)

        def configure():
            self.device = PushDevice(f"http://127.0.0.1:{visited.receiver.port}")
            return ""

        def explode(*args):
            raise RuntimeError("the lab went away")

        self.lab.allocate = explode
        console.answers = VisitStart() + [
            ("أنهي جهاز", "1"), ("لما تحفظ", configure), ("نفس اللي على الستيكر", "1"),
            ("الجهاز متظبط على إيه", "2"), ("بتتظبط لوحدها", "1"),
            # The visit dies here, with the terminal already sending to this laptop.
            ("Cloud Server Setting", "2"), ("برنامج", "1"), ("كابل أو switch", "1"),
        ]
        code = visited.run()
        self.assertEqual(console.answers, [], "the checklist was asked even though the visit crashed")
        self.assertEqual(code, 1)
        report = report_of(self.out)
        self.assertIn("إعداد الـ server على الجهاز مارجعش زي الأول", report)
        self.assertIn("the lab went away", report)

    def test_a_receiver_that_cannot_take_its_port_back_keeps_what_it_recorded(self):
        console = ScriptedConsole([])
        visited = quick_visit(console, self.lab, self.out)
        original = visit.capture.serve
        calls = []

        def serve_once(*args, **kwargs):
            calls.append(1)
            if len(calls) > 1:
                raise OSError(98, "Address already in use")
            return original(*args, **kwargs)

        def configure():
            self.device = PushDevice(f"http://127.0.0.1:{visited.receiver.port}")
            return ""

        console.answers = VisitStart() + [
            ("أنهي جهاز", "1"), ("لما تحفظ", configure), ("نفس اللي على الستيكر", "1"),
            ("الجهاز متظبط على إيه", "2"), ("بتتظبط لوحدها", "1"),
            ("بصمة عادية", lambda: self.device.punch(0)), ("Check-Out", lambda: self.device.punch(1)),
            ("بيدوسوا زرار", "1"),
            ("بصمتين ورا بعض", lambda: self.device.punch(0) + self.device.punch(0)),
            ("شيل كابل", "2"), ("وقف الاستقبال", "1"), ("وقّفت الاستقبال", lambda: self.device.punch(0)),
            ("HTTPS", "1"), ("Enable Domain Name", "1"), ("Attendance Search", "1"),
            ("ملف USB", "1"), ("Cloud Server Setting", "1"), ("برنامج", "1"), ("كابل أو switch", "1"),
        ]
        visit.capture.serve = serve_once
        try:
            code = visited.run()
        finally:
            visit.capture.serve = original
        self.assertEqual(console.answers, [])
        self.assertEqual(code, 1, "the port it could not take back is a finding")
        self.assertIn("مقدرتش أفتح port", console.text)
        self.assertEqual(visited.sheet["الـ pushver"], "2.4.1",
                         "what the terminal already sent is still in the report")
        self.assertIn("الـ Content-Type", report_of(self.out))

    def test_an_upload_the_recorder_cannot_read_is_not_reported_as_a_punch_that_never_came(self):
        console = ScriptedConsole([])
        visited = quick_visit(console, self.lab, self.out)

        def configure():
            self.device = PushDevice(f"http://127.0.0.1:{visited.receiver.port}")
            return ""

        def punch_in_a_charset_nobody_asked_for():
            # A line the recorder keeps nothing from: the platform still answered 200.
            body = f"{PIN}\t2026-09-20 08:00:00\t0\t1\t".encode() + bytes([0xB4, 0xF7, 0xC3, 0xFB])
            request = urllib.request.Request(
                f"http://127.0.0.1:{visited.receiver.port}/iclock/cdata?SN=PUSH-VISIT-1&table=ATTLOG&Stamp=9",
                data=body, method="POST", headers={"Content-Type": "application/x-www-form-urlencoded"})
            urllib.request.urlopen(request, timeout=5).read()
            return ""

        console.answers = VisitStart() + [
            ("أنهي جهاز", "1"), ("لما تحفظ", configure), ("نفس اللي على الستيكر", "1"),
            ("الجهاز متظبط على إيه", "2"), ("بتتظبط لوحدها", "1"),
            ("بصمة عادية", lambda: self.device.punch(0)),
            ("Check-Out", punch_in_a_charset_nobody_asked_for),
            ("بصمتين ورا بعض", lambda: self.device.punch(0) + self.device.punch(0)),
            ("شيل كابل", "2"), ("وقف الاستقبال", "2"),
            ("HTTPS", "1"), ("Enable Domain Name", "1"), ("Attendance Search", "1"),
            ("ملف USB", "1"), ("Cloud Server Setting", "1"), ("برنامج", "1"), ("كابل أو switch", "1"),
        ]
        code = visited.run()
        self.assertEqual(console.answers, [])
        self.assertEqual(code, 1)
        self.assertIn("البصمة وصلت للسيستم، بس الـ capture مافهمش سطور الرفعة", console.text)
        self.assertNotIn("ماوصلتش", console.text, "the punch did arrive; the line shape is the finding")
        self.assertIn("البصمتين ورا بعض اتسجلوا الاتنين", console.text, "the visit carried on")

    def test_a_terminal_whose_lines_cannot_be_read_gets_no_latency_for_a_punch_never_made(self):
        """The headline number of the whole visit is "بصمة وصلت خلال". On a terminal whose line
        shape the recorder keeps nothing from, every rule that tells the punch just made from a
        record still draining out of the backlog is blind: there is no code, no time and nothing
        to match against what was delivered before. So the visit refuses to time it. Before this,
        a backlog record landing after the prompt answered the test and the report said a punch
        arrived in 0 seconds -- for a punch nobody made."""
        console = ScriptedConsole([])
        visited = quick_visit(console, self.lab, self.out)

        def configure():
            self.device = UnreadablePushDevice(f"http://127.0.0.1:{visited.receiver.port}")
            return ""

        console.answers = VisitStart() + [
            ("أنهي جهاز", "1"), ("لما تحفظ", configure), ("نفس اللي على الستيكر", "1"),
            ("الجهاز متظبط على إيه", "2"), ("بتتظبط لوحدها", "1"),
            # The operator presses Enter and makes no punch at all; the backlog keeps draining.
            ("بصمة عادية", lambda: self.device.drip_old_record()),
            ("HTTPS", "1"), ("Enable Domain Name", "1"),
            ("ملف USB", "1"), ("Cloud Server Setting", "1"), ("برنامج", "1"), ("كابل أو switch", "1"),
        ]
        code = visited.run()
        self.assertEqual(console.answers, [])
        self.assertEqual(code, 1, console.text)
        self.assertNotIn("وصلت للسيستم خلال", console.text, "nothing was timed: no punch was made")
        self.assertIn("مش قادرين نقيس البصمة على الموديل ده", console.text)
        self.assertIn("| بصمة وصلت خلال | مااتقاسش |", report_of(self.out))

    def test_the_backlog_wait_counts_uploads_whose_lines_cannot_be_read(self):
        """The wait for a terminal to finish sending its old records asks "has anything arrived
        in the last few seconds?". Asking it about readable lines only made the answer "no" for
        exactly the terminals this visit is for, so the punch tests began mid-backlog."""
        console = ScriptedConsole([])
        visited = quick_visit(console, self.lab, self.out)
        still_to_send = []

        def configure():
            # Six batches a third of a second apart: long enough that the questions between the
            # allocation and the punch prompt cannot outlast the backlog by themselves, so the
            # zero below is the wait doing its job and not the test being slow.
            self.device = UnreadableTricklingDevice(f"http://127.0.0.1:{visited.receiver.port}",
                                                    batches=6, interval=0.3)
            return ""

        def the_operator_is_asked_for_a_punch():
            still_to_send.append(self.device.batches - self.device.sent)
            return self.device.punch(0)

        console.answers = VisitStart() + [
            ("أنهي جهاز", "1"), ("لما تحفظ", configure), ("نفس اللي على الستيكر", "1"),
            ("الجهاز متظبط على إيه", "2"), ("بتتظبط لوحدها", "1"),
            ("بصمة عادية", the_operator_is_asked_for_a_punch),
            ("HTTPS", "1"), ("Enable Domain Name", "1"),
            ("ملف USB", "1"), ("Cloud Server Setting", "1"), ("برنامج", "1"), ("كابل أو switch", "1"),
        ]
        code = visited.run()
        self.assertEqual(console.answers, [])
        self.assertEqual(still_to_send, [0],
                         "the terminal had emptied its backlog before the operator was asked: "
                         + console.text[-2000:])
        self.assertEqual(code, 1, console.text)

    def test_a_later_punch_test_the_capture_cannot_read_is_not_ticked_either(self):
        """The headline row is not the only one that ends in a number or a tick. Firmware whose
        lines parse for some punches and not others gets past the first test, and the ones after
        it were ticked unconditionally."""
        console = ScriptedConsole([])
        visited = quick_visit(console, self.lab, self.out)

        def configure():
            self.device = GarblingPushDevice(f"http://127.0.0.1:{visited.receiver.port}")
            return ""

        def garble_then_punch():
            self.device.garble()
            return self.device.punch(1)

        def the_backlog_answers_instead():
            # No punch: two old records, uploaded one at a time after the prompt returns.
            return self.device.drip_old_records(2)

        console.answers = VisitStart() + [
            ("أنهي جهاز", "1"), ("لما تحفظ", configure), ("نفس اللي على الستيكر", "1"),
            ("الجهاز متظبط على إيه", "2"), ("بتتظبط لوحدها", "1"),
            ("بصمة عادية", lambda: self.device.punch(0)),
            ("Check-Out", garble_then_punch),
            ("بصمتين ورا بعض", the_backlog_answers_instead),
            ("شيل كابل", "2"), ("وقف الاستقبال", "2"),
            ("HTTPS", "1"), ("Enable Domain Name", "1"), ("Attendance Search", "1"),
            ("ملف USB", "1"), ("Cloud Server Setting", "1"), ("برنامج", "1"), ("كابل أو switch", "1"),
        ]
        visited.run()

        self.assertEqual(console.answers, [])
        self.assertIn("بصمة عادية وصلت للسيستم خلال", console.text, "the first punch was readable")
        self.assertIn("مش قادرين نقيس البصمتين ورا بعض", console.text)
        self.assertNotIn("البصمتين ورا بعض اتسجلوا الاتنين", console.text,
                         "nothing readable answered that test: " + console.text[-1200:])




class WhenNothingWasExamined(unittest.TestCase):
    """A report is pasted into a device-compatibility issue, where its first line is read as the
    answer about the model. A visit that never reached a terminal must not head that report with
    a tick, and must not tell the caller it passed."""

    def setUp(self):
        self.dir = tempfile.TemporaryDirectory()
        self.platform = FakePlatform()
        self.receiver = FakeReceiver()
        self.lab = FakeLab(self.platform, self.receiver, self.dir.name)
        self.out = os.path.join(self.dir.name, "field-report")

    def tearDown(self):
        self.receiver.close()
        self.platform.close()
        self.dir.cleanup()

    def assert_examined_nothing(self, console, code):
        report = report_of(self.out)
        self.assertEqual(console.answers, [])
        self.assertEqual(code, 1, console.text)
        self.assertIn("❓ الزيارة ماعملتش أي فحص", report)
        self.assertNotIn("✅ كله تمام", report)
        self.assertIn("| مشاكل تانية | الزيارة ماعملتش أي فحص", report)

    def test_a_visit_the_customer_declined_and_that_had_no_usb_file_is_not_a_pass(self):
        console = ScriptedConsole(VisitStart(consent="2") + [
            ("معاك ملف USB", "1"), ("برنامج", "1"), ("كابل أو switch", "1")])
        self.assert_examined_nothing(console, quick_visit(console, self.lab, self.out).run())

    def test_a_usb_only_visit_with_no_file_is_not_a_pass(self):
        console = ScriptedConsole(VisitStart() + [
            ("أنهي جهاز", "3"), ("معاك ملف USB", "1"), ("برنامج", "1"), ("كابل أو switch", "1")])
        self.assert_examined_nothing(console, quick_visit(console, self.lab, self.out).run())


class WhatTheHeadlineMustNotDeny(unittest.TestCase):
    """The report's first line is read as the answer about the model. It has to agree with the
    rest of the report, including a results sheet that says a terminal was heard from."""

    def setUp(self):
        self.dir = tempfile.TemporaryDirectory()
        self.platform = FakePlatform()
        self.receiver = FakeReceiver()
        self.lab = FakeLab(self.platform, self.receiver, self.dir.name)
        self.out = os.path.join(self.dir.name, "field-report")
        self.device = None

    def tearDown(self):
        if self.device:
            self.device.stop()
        self.receiver.close()
        self.platform.close()
        self.dir.cleanup()

    def test_an_unsupported_brand_that_reached_the_recorder_is_not_reported_as_nothing_examined(self):
        console = ScriptedConsole([])
        visited = quick_visit(console, self.lab, self.out)

        def point_it_at_the_laptop():
            self.device = UnreadablePushDevice(f"http://127.0.0.1:{visited.receiver.port}", serial="ANVIZ-1")
            time.sleep(0.5)
            return ""

        console.answers = VisitStart() + [
            ("أنهي جهاز", "2"), ("اكتب IP", "192.168.44.79:8080"), ("نوع الجهاز", "3"),
            ("الماركة والموديل", "Anviz W1"), ("إعداد server", "2"),
            ("لما الموظف يعمل بصمة", point_it_at_the_laptop),
            ("Cloud Server Setting", "1"), ("برنامج", "1"), ("كابل أو switch", "1")]
        code = visited.run()

        report = report_of(self.out)
        self.assertEqual(console.answers, [])
        self.assertIn("طلب في field-report/captures", report, "the sheet says the terminal was heard from")
        self.assertIn("✅ الجهاز كلّم اللابتوب: اتسجل", report, "and the report counts that as something that worked")
        self.assertNotIn("الزيارة ماعملتش أي فحص", report, "so the headline cannot say it was not: " + report[:400])
        self.assertEqual(code, 0, console.text)

    def test_a_visit_stopped_with_control_c_says_so_rather_than_that_it_worked(self):
        # Ctrl-C at the device question: inside the visit, before the restore checklist, which
        # has its own handler and its own (bad) finding.
        interrupt = ScriptedConsole(VisitStart() + [("برنامج", "1"), ("كابل أو switch", "1")])
        original = interrupt.ask

        def stop_at_the_device_question(prompt):
            # The prompt for a numbered choice is "اكتب الرقم"; the question itself is the last
            # line the console was told to print, which is where the scripted console looks too.
            if "أنهي جهاز" in (interrupt.question or ""):
                raise KeyboardInterrupt
            return original(prompt)

        interrupt.ask = stop_at_the_device_question
        code = quick_visit(interrupt, self.lab, self.out).run()

        report = report_of(self.out)
        self.assertEqual(code, 1, interrupt.text)
        self.assertIn("⛔ الزيارة اتوقفت قبل ما تخلص", report)
        self.assertNotIn("⚠️ اشتغل", report, "an aborted visit did not work; it stopped")
        self.assertIn("| مشاكل تانية | الزيارة اتوقفت قبل ما تخلص |", report)




class WhenTheLaptopNeverReachedTheTerminal(unittest.TestCase):
    """EHOSTUNREACH means no packet left this laptop, so the terminal was never asked and has
    answered nothing. None of it belongs in a device-compatibility finding."""

    # `interface` is always present in the real shape: `probe.laptop_network` sets it to the egress
    # route or to None. Omitting it here made this fixture unreal, which is how a remedy that names
    # an unresolved interface's numbers passed its own test.
    FACTS = {"addresses": [{"ip": "192.168.1.26", "network": "192.168.1.0/24"}],
             "interface": "wlp0s20f3",
             "wifi": {"interface": "wlp0s20f3", "link": 42, "level": -68, "misc": 403, "missed_beacon": 0},
             "target": "192.168.1.201", "arp": "FAILED",
             "ping": {"transmitted": 5, "received": 5, "duplicates": 3, "loss_percent": 0.0,
                      "rtt_ms": {"min": 0.89, "avg": 41.6, "max": 441.49, "mdev": 75.5}}}

    def visit_whose_read_fails(self, directory, message):
        visited = visit.Visit(console=ScriptedConsole([]), out_dir=directory, wait_seconds=1,
                              laptop_network=lambda ip=None, ping_count=10: dict(self.FACTS))
        visited.zk_link = ("192.168.1.201", 4370, 0, False)

        def refuse():
            raise visit.zk.ZkError(message)
        visited.zk_records = refuse
        return visited

    def test_the_remedy_names_this_laptops_network_instead_of_the_issue_tracker(self):
        with tempfile.TemporaryDirectory() as directory:
            visited = self.visit_whose_read_fails(
                directory, "cannot reach 192.168.1.201:4370 over TCP: [Errno 113] No route to host")
            self.assertIsNone(visited.one_new_punch("خلي الموظف يعمل بصمة"))
        finding = visited.findings[-1]
        self.assertIn("[Errno 113]", finding.text)
        for measured in ("-68 dBm", "FAILED", "3 مكرر", "441.49"):
            self.assertIn(measured, finding.fix)
        # It names the path and hands over both ends. The same errno arrives from a mistyped
        # address and a terminal that is switched off, so clearing the terminal here would file a
        # real device fault as somebody else's problem.
        self.assertIn("الـ IP", finding.fix)
        self.assertIn("الجهاز شغال", finding.fix)
        self.assertIn("الشبكة", finding.fix)
        self.assertNotIn("مش في الجهاز", finding.fix)
        # And the report carries the numbers, so the operator need not remember them.
        self.assertIn("FAILED", visited.sheet["حالة الشبكة وقت المشكلة"])

    def test_a_terminal_that_refuses_keeps_the_terminals_own_remedy(self):
        """A refusal is the terminal answering. Rewriting that as a laptop problem would send an
        operator to move the laptop while the terminal sits there refusing on 4370."""
        with tempfile.TemporaryDirectory() as directory:
            visited = self.visit_whose_read_fails(
                directory, "cannot reach 192.168.1.201:4370 over TCP: [Errno 111] Connection refused")
            self.assertIsNone(visited.one_new_punch("خلي الموظف يعمل بصمة"))
        self.assertEqual(visited.findings[-1].fix, "استنى شوية وشغّل الأمر تاني")
        self.assertEqual(visited.sheet["حالة الشبكة وقت المشكلة"], "")


class WhatReviewRoundTwoAsked(unittest.TestCase):
    """Each of these was a way the round-one fix could say something untrue: blame the terminal's
    network for the lab server, lose the reason a punch was never found, print the wrong laptop
    interface, publish a customer address, or spend half a minute proving an address is absent."""

    FACTS = dict(WhenTheLaptopNeverReachedTheTerminal.FACTS)
    UNREACHABLE = "cannot reach 192.168.1.201:4370 over TCP: [Errno 113] No route to host"

    def visit_in(self, directory, answers=()):
        made = visit.Visit(console=ScriptedConsole(list(answers)), out_dir=directory, wait_seconds=1,
                           settle_seconds=0, sleep=lambda seconds: None,
                           laptop_network=lambda ip=None, ping_count=10: dict(self.FACTS))
        made.zk_link = ("192.168.1.201", 4370, 0, False)
        made.serial = "ZK-RT2-1"
        return made

    # -- the reason a punch was never found ------------------------------------------------

    def test_a_link_lost_after_the_punch_keeps_the_network_evidence(self):
        """The baseline read succeeded, the employee punched, and then the link went away. Reported
        as "the punch was not found", that says the terminal rejected a punch it never saw."""
        reads = []

        with tempfile.TemporaryDirectory() as directory:
            visited = self.visit_in(directory, [("ودوس Enter", ""), ("أستنى وأقرأ تاني", "2")])

            def read():
                reads.append(len(reads) + 1)
                if len(reads) == 1:
                    return []
                raise visit.zk.ZkError(self.UNREACHABLE)

            visited.zk_records = read
            self.assertIsNone(visited.one_new_punch("خلي الموظف يعمل بصمة"))
        finding = visited.findings[-1]
        self.assertIn("[Errno 113]", finding.text, "the read's own error was dropped")
        for measured in ("FAILED", "3 مكرر", "-68 dBm"):
            self.assertIn(measured, finding.fix)
        self.assertNotIn("خلي الموظف يستنى", finding.fix, "a network failure was reported as a rejected punch")
        self.assertIn("FAILED", visited.sheet["حالة الشبكة وقت المشكلة"])

    def test_a_punch_that_simply_did_not_arrive_still_gets_the_plain_remedy(self):
        """The control: nothing failed, the punch is not there, and the remedy is to try again."""
        with tempfile.TemporaryDirectory() as directory:
            visited = self.visit_in(directory, [("ودوس Enter", ""), ("أستنى وأقرأ تاني", "2")])
            visited.zk_records = lambda: []
            self.assertIsNone(visited.one_new_punch("خلي الموظف يعمل بصمة"))
        self.assertEqual(visited.findings[-1].fix, "جرّب تاني، وخلي الموظف يستنى لحد ما الجهاز يقبل البصمة")
        self.assertEqual(visited.sheet["حالة الشبكة وقت المشكلة"], "")

    # -- which endpoint was unreachable ----------------------------------------------------

    def send_once(self, directory, **fields):
        visited = self.visit_in(directory)
        path = Path(directory) / "zk.toml"
        token = Path(directory) / "agent.token"
        token.write_text("lab-token", encoding="utf-8")
        token.chmod(0o600)
        path.write_text(visited._agent_toml("spool.sqlite3", "punch").replace(
                            str(visit.AGENT_DIR / "lab" / "agent.token"), str(token)) +
                        '\n[[devices]]\nserial = "ZK-RT2-1"\nkind = "zk"\nhost = "192.168.1.201"\nport = 4370\ncomm_key = 0\nudp = false\n',
                        encoding="utf-8")
        result = visit.agent.DeviceResult("ZK-RT2-1")
        for name, value in fields.items():
            setattr(result, name, value)
        original = visit.agent.run_once
        visit.agent.run_once = lambda config: [result]
        try:
            visited.send_twice(path, "192.168.1.201")
        finally:
            visit.agent.run_once = original
        return visited

    def test_a_lab_server_that_is_unreachable_is_not_the_terminals_network_path(self):
        """The terminal answered -- `reachable` says so -- and the delivery to the lab failed. Telling
        the operator to move the laptop closer to the router would be false, and the remedy for a
        stopped lab stack is a different one."""
        with tempfile.TemporaryDirectory() as directory:
            visited = self.send_once(directory, reachable=True,
                                     error="retry: POST https://localhost/iclock: [Errno 113] No route to host")
        finding = visited.findings[-1]
        self.assertIn("سيستم اللاب", finding.fix)
        self.assertNotIn("ARP", finding.fix)
        self.assertNotIn("قرّب اللابتوب", finding.fix)
        self.assertEqual(visited.sheet["حالة الشبكة وقت المشكلة"], "", "the terminal's path was measured for nothing")

    def test_a_terminal_that_was_never_reached_still_gets_the_network_remedy(self):
        with tempfile.TemporaryDirectory() as directory:
            visited = self.send_once(directory, reachable=False, error=self.UNREACHABLE)
        self.assertIn("FAILED", visited.findings[-1].fix)
        self.assertIn("FAILED", visited.sheet["حالة الشبكة وقت المشكلة"])

    # -- the same condition, as Windows spells it -------------------------------------------

    def test_the_windows_spelling_of_the_same_condition_counts(self):
        """This package ships as a Windows executable, where the message reads `[WinError 10065]`
        for the errno Linux writes as 113. Read only in the Linux spelling, the whole remedy is
        silently skipped on Windows for the same unreachable terminal."""
        self.assertEqual(visit.unreachable_errno("[Errno 113] No route to host"), errno.EHOSTUNREACH)
        self.assertEqual(visit.unreachable_errno(
            "cannot reach x:4370 over TCP: [WinError 10065] A socket operation was attempted "
            "to an unreachable host"), errno.EHOSTUNREACH)
        self.assertEqual(visit.unreachable_errno("[WinError 10051]"), errno.ENETUNREACH)
        # And a refusal is still the terminal answering, in either spelling.
        self.assertIsNone(visit.unreachable_errno("[Errno 111] Connection refused"))
        self.assertIsNone(visit.unreachable_errno("[WinError 10061] Connection refused"))

    def test_the_errno_is_taken_from_the_exception_where_one_survives(self):
        """Text is the fallback, not the source: `ZkError` chains the `OSError` it was raised from,
        and that number is the same on every platform."""
        wrapped = visit.zk.ZkError("cannot reach the terminal")
        wrapped.__cause__ = OSError(errno.EHOSTUNREACH, "No route to host")
        self.assertEqual(visit.unreachable_errno(wrapped), errno.EHOSTUNREACH)
        refused = visit.zk.ZkError("refused")
        refused.__cause__ = OSError(errno.ECONNREFUSED, "Connection refused")
        self.assertIsNone(visit.unreachable_errno(refused))

    # -- what the pasteable report may carry ------------------------------------------------

    def test_the_network_line_states_the_arp_state_and_not_the_address(self):
        line = visit.describe_network(self.FACTS)
        self.assertIn("FAILED", line)
        self.assertNotIn("192.168.1.201", line)

    def test_no_line_of_the_report_names_an_address_on_the_customers_network(self):
        """One seam for the whole report. A terminal's own error text names the address it could not
        reach, so redacting per finding would hold only until the next finding was written."""
        with tempfile.TemporaryDirectory() as directory:
            visited = self.visit_in(directory)
            visited.note("bad", f"مقدرناش نقرأ سجلات الجهاز: {self.UNREACHABLE}",
                         visited.unreachable_path_fix(self.UNREACHABLE))
            visited.note("ok", "الجهاز على 10.4.4.9 رد")
            visited.sheet["السيريال"] = "ZK-RT2-1"
            text = visited.write_report().read_text(encoding="utf-8")
        for address in ("192.168.1.201", "10.4.4.9", "192.168.1.26"):
            self.assertNotIn(address, text)
        self.assertIn("device-ip", text)
        # The operator still sees it on screen: it is the report that travels.
        self.assertIn("192.168.1.201", visited.console.text)

    def test_every_address_class_is_redacted_including_a_public_one(self):
        """A terminal reachable from outside its branch has a public address, and that is the class
        that identifies a customer outright -- so exempting it to keep a four-part firmware version
        readable had the rule backwards. The firmware this visit read is three parts."""
        for address in ("172.20.3.1", "10.0.0.9", "127.0.0.1", "169.254.3.4", "100.64.0.5",
                        "41.33.7.9", "8.8.8.8"):
            self.assertEqual(visit._no_address(f"terminal {address}"), "terminal <device-ip>", address)
        self.assertEqual(visit._no_address("firmware Ver 6.60 Oct 12 2021"), "firmware Ver 6.60 Oct 12 2021")

    def test_only_a_rooted_path_is_redacted_and_a_url_is_not_one(self):
        """This seam runs over every line of the report, so what it must **not** match matters as
        much as what it must: the lab's own URL, the `field-report/` paths the runbook tells the
        operator to open, the firewall rule a remedy prints, and the `/` in the customer row's own
        label are all text the report exists to carry."""
        self.assertEqual(visit._no_path("/home/k/عميل/SN-attlog.dat"), "<path>/SN-attlog.dat")
        self.assertEqual(visit._no_path("مالقيتش الملف /a/b/c.dat خالص"), "مالقيتش الملف <path>/c.dat خالص")
        # A last component with no suffix is a directory, and neither an exception's closing quote
        # nor a sentence's full stop is one: each would otherwise publish a username.
        self.assertEqual(visit._no_path("/media/afaqy/USB/"), "<path>")
        self.assertEqual(visit._no_path("/home/karim"), "<path>")
        self.assertEqual(visit._no_path("مش لاقي /home/karim."), "مش لاقي <path>.")
        self.assertEqual(visit._no_path("ping: '/usr/bin/ping'"), "ping: '<path>'")
        for kept in ("https://localhost:18443/api", "field-report/captures/x.json", "الشركة / الفرع",
                     "sudo ufw allow 8081/tcp", "Menu → USB Manager → Download", "/", "2026-09-22",
                     # The terminals' own request namespaces: `sources` puts them in its error text
                     # and a device-compatibility report exists to carry them. Redacting them would
                     # read as "something private was here", which is the opposite of the truth.
                     "HIK-1: GET /ISAPI/System/deviceInfo answered 401",
                     "/iclock/cdata?SN=X&table=ATTLOG"):
            self.assertEqual(visit._no_path(kept), kept, kept)
        # And what this seam cannot do, asserted rather than assumed: a directory name containing a
        # space is prose as far as any regex is concerned. That is why the one place that knows it
        # holds a path does not rely on this, and why a path inside an exception's text is a
        # backstop rather than a guarantee.
        self.assertIn("النور", visit._no_path("/home/k/شركة النور/x.dat"))

    def test_the_report_names_no_serial_because_the_runbook_says_to_paste_it_in_public(self):
        """A serial is the only thing that identifies a terminal to the device endpoint (R-041,
        R-042), and step 11 tells the operator to paste the results sheet into a public issue. It
        stays on the console and in the file name."""
        with tempfile.TemporaryDirectory() as directory:
            visited = self.visit_in(directory)
            visited.serial = "ZK-PASTE-1"
            visited.sheet["السيريال"] = visited.serial
            visited.note("bad", f"{visited.serial}: read failed on 192.168.1.201", "جرّب تاني")
            visited.note("ok", "الجهاز رد")
            path = visited.write_report()
            text = path.read_text(encoding="utf-8")
        self.assertNotIn("ZK-PASTE-1", text)
        # `_md` escapes the placeholder, which is how GitHub renders it back as one word.
        self.assertIn("device-serial", text)
        self.assertNotIn("192.168.1.201", text)
        self.assertIn("ZK-PASTE-1", path.name, "the file name is local and keeps it")
        self.assertIn("ZK-PASTE-1", visited.console.text, "the operator still sees it")

    def test_every_serial_the_visit_saw_is_redacted_not_only_the_one_it_settled_on(self):
        """A terminal that pushes under a different serial than it reports on 4370 is one of the
        findings this visit exists to produce, and both serials are the credential R-041/R-042
        describe. A rejected serial is seen before there is a settled one at all."""
        with tempfile.TemporaryDirectory() as directory:
            visited = self.visit_in(directory)
            visited.serial = "ZK-ON-4370"
            visited.saw_serial("ZK-PUSHED-9")
            visited.note("bad", "الجهاز بيبعت بالـ Push بسيريال ZK-PUSHED-9 وعلى 4370 بيقول ZK-ON-4370",
                         "معلومة مهمة")
            text = visited.write_report().read_text(encoding="utf-8")
        self.assertNotIn("ZK-PUSHED-9", text, "the pushed serial was published")
        self.assertNotIn("ZK-ON-4370", text)
        # H1, both serials in the finding, and both again in the `مشاكل تانية` row it feeds.
        self.assertEqual(text.count("device-serial"), 5)

    def test_a_serial_short_enough_to_be_ordinary_text_is_left_alone(self):
        """`config.SERIAL` accepts a single character, so an operator typo at the USB serial prompt
        must not rewrite the report's own numbers -- the in/out cells are one digit each."""
        with tempfile.TemporaryDirectory() as directory:
            visited = self.visit_in(directory)
            visited.serial = "1"
            visited.sheet["الدخول"] = "1"
            visited.sheet["الخروج"] = "0"
            text = visited.write_report().read_text(encoding="utf-8")
        self.assertNotIn("device-serial", text)
        self.assertIn("| الدخول | <span dir=\"ltr\">1</span> |", text)

    def test_the_report_does_not_name_the_customer(self):
        """The last identifier in an artefact whose preamble enumerates what it omits. It stays on
        the console and in field-report/, which is where step 11 says to keep the folder."""
        with tempfile.TemporaryDirectory() as directory:
            visited = self.visit_in(directory)
            visited.sheet["الشركة / الفرع"] = "شركة الاختبار / فرع مدينة نصر"
            text = visited.write_report().read_text(encoding="utf-8")
        self.assertNotIn("مدينة نصر", text)
        self.assertNotIn("شركة الاختبار", text)
        self.assertIn("(في field-report)", text)

    def test_no_path_the_operator_typed_is_published_whatever_shape_it_is(self):
        """`usb_flow` quoted the path back when the file was not there, and on a real visit that path
        is under the operator's home, in a folder named after the customer, holding a file named
        after the terminal. Redacting it at the report's seam fixed only the shape the first version
        of this test used: a directory name **with a space in it** is indistinguishable from prose,
        so the seam left `شركة النور فرع المعادي` standing, and typing the folder rather than the
        file published the customer as the "file name".

        The call site does it now, where the program knows the whole string is a path and a public
        issue needs none of it -- a file the operator could not point at is not a fact about the
        terminal. It stays on the console, which is where they read it."""
        for typed in ("/home/karim/شركة-النور-فرع-المعادي/TERMINAL-A-attlog.dat",
                      "/home/karim/شركة النور فرع المعادي/TERMINAL-A-attlog.dat",
                      "/home/karim/Client Files/TERMINAL-A.dat",
                      "/home/karim/شركة-النور-فرع-المعادي",
                      "/home/karim",
                      "عميل-النور/TERMINAL-A-attlog.dat"):
            with tempfile.TemporaryDirectory() as directory:
                visited = self.visit_in(directory, [("معاك ملف USB", "2"), ("أنهي ملف", "1"),
                                                    ("مكان الملف", typed)])
                # Not the machine's own removable drives: `usb_files` globs `/media/<user>/*`, so a
                # mounted stick would change the list this test picks from.
                with mock.patch.object(visit, "usb_files", return_value=[]):
                    visited.usb_flow(None)
                text = visited.write_report().read_text(encoding="utf-8")
            self.assertIn("مالقيتش الملف", text, typed)
            for secret in ("karim", "النور", "Client", "TERMINAL-A"):
                self.assertNotIn(secret, text, f"{typed!r} published {secret!r}")
            self.assertIn(typed, visited.console.text, f"{typed!r} left the operator's screen")

    def test_a_placeholder_serial_takes_no_ordinary_text_with_it(self):
        with tempfile.TemporaryDirectory() as directory:
            visited = self.visit_in(directory)
            visited.serial = "device"
            visited.note("bad", "الجهاز ده مش مدعوم", "اكتبها في الـ issue")
            text = visited.write_report().read_text(encoding="utf-8")
        self.assertIn("الجهاز ده مش مدعوم", text)
        self.assertNotIn("device-serial", text)

    def test_the_remedy_claims_only_what_was_measured(self):
        """Without `ip` or `ping` -- the Windows build, or a stripped laptop -- there is no ARP state
        to assert and no interface to advise about. The errno alone can be ENETUNREACH or EHOSTDOWN,
        neither of which is a failed ARP exchange."""
        blind = {"addresses": [], "interface": None, "wifi": None,
                 "target": "192.168.1.201", "arp": None, "ping": {"unavailable": "ping did not run"}}
        with tempfile.TemporaryDirectory() as directory:
            visited = visit.Visit(console=ScriptedConsole([]), out_dir=directory, wait_seconds=1,
                                  laptop_network=lambda ip=None, ping_count=10: dict(blind))
            visited.zk_link = ("192.168.1.201", 4370, 0, False)
            fix = visited.unreachable_path_fix(self.UNREACHABLE)
        self.assertNotIn("الـ ARP فشل", fix, "an ARP failure was asserted without an ARP state")
        self.assertNotIn("قرّب اللابتوب من الراوتر", fix, "Wi-Fi advice with no interface known")
        self.assertNotIn("اتأكد من الكابل والـ switch", fix, "cable advice with no interface known")
        self.assertIn("الكابل أو الواي فاي", fix)

    def test_a_path_with_no_terminal_to_measure_gives_no_interface_advice(self):
        """`probe.laptop_network(None)` reports the **first** wireless interface in
        /proc/net/wireless -- any interface, not the one that would carry this terminal -- with no
        `interface`, no `arp` and no `ping`. Advising "move closer to the router" from that is the
        false remedy review round 2 raised, on the one path its `egress` fix does not cover: the
        Hikvision flow never sets `zk_link`, so before this there was no target at all.

        The previous test of this behaviour injected `wifi: None`, which the real no-target call
        never returns, so it passed while the live path was broken."""
        no_target = {"addresses": [{"ip": "10.0.0.5", "network": "10.0.0.0/24"}],
                     "interface": None,
                     "wifi": {"interface": "wlp0s20f3", "link": 58, "level": -52,
                              "misc": 7, "missed_beacon": 0},
                     "target": None, "arp": None, "ping": None}
        with tempfile.TemporaryDirectory() as directory:
            visited = visit.Visit(console=ScriptedConsole([]), out_dir=directory, wait_seconds=1,
                                  laptop_network=lambda ip=None, ping_count=10: dict(no_target))
            self.assertIsNone(visited.zk_link, "this path is the one with no link to measure")
            fix = visited.unreachable_path_fix(self.UNREACHABLE)
        self.assertNotIn("قرّب اللابتوب من الراوتر", fix,
                         "Wi-Fi advice from an interface that was never shown to carry this terminal")
        self.assertNotIn("الـ ARP فشل", fix, "an ARP failure asserted without an ARP state")
        self.assertIn("الكابل أو الواي فاي", fix)
        # The numbers, not only the advice: naming an interface's signal in the row the runbook
        # says to paste is the same false claim, made with more authority.
        self.assertNotIn("wlp0s20f3", fix, "an unrelated interface was named in the remedy")
        self.assertNotIn("-52", fix, "an unrelated interface's signal was reported")
        self.assertNotIn("wlp0s20f3", visited.sheet["حالة الشبكة وقت المشكلة"],
                         "an unrelated interface was named in the results sheet")

    def test_the_hikvision_path_measures_its_own_terminal(self):
        """`send_twice` is called from the ZKTeco flow, where `zk_link` carries the address, and from
        `hik_flow`, where it never has. The host travels with the config now, so the remedy measures
        the terminal the delivery was for."""
        asked = []
        with tempfile.TemporaryDirectory() as directory:
            visited = self.visit_in(directory)
            visited.laptop_network = lambda ip=None, ping_count=10: (asked.append(ip), dict(self.FACTS))[1]
            path = Path(directory) / "hik.toml"
            token = Path(directory) / "agent.token"
            token.write_text("lab-token", encoding="utf-8")
            token.chmod(0o600)
            path.write_text(
                visited._agent_toml("spool.sqlite3").replace(
                    str(visit.AGENT_DIR / "lab" / "agent.token"), str(token))
                + '\n[[devices]]\nserial = "HIK-1"\nkind = "hikvision"\nhost = "10.4.4.9"\n'
                  'port = 80\nhttps = false\nusername = "admin"\npassword_file = "hik.pw"\n',
                encoding="utf-8")
            secret = Path(directory, "hik.pw")
            secret.write_text("secret", encoding="utf-8")
            secret.chmod(0o600)
            result = visit.agent.DeviceResult("HIK-1")
            result.reachable = False
            result.error = "HIK-1: GET /ISAPI/System/deviceInfo failed: <urlopen error [Errno 113] No route to host>"
            original = visit.agent.run_once
            visit.agent.run_once = lambda config: [result]
            try:
                visited.send_twice(path, "10.4.4.9")
            finally:
                visit.agent.run_once = original
        self.assertIn("10.4.4.9", asked, f"the remedy measured {asked} instead of the terminal")

    def test_a_delivery_cannot_be_sent_without_naming_the_terminal_it_is_for(self):
        """`host` was optional, so `hik_flow` dropping it again would be *semantically* silent: the
        in-function fallback produced `None` and `AVisitOfAHikvisionTerminal`, which does drive
        `hik_flow` end to end against `sim/hikvision.py`, asserts nothing about the resulting remedy.
        Required, the argument cannot be dropped at all -- and that end-to-end test now fails on the
        `TypeError` if it is. This pins the signature directly, so neither test relies on the other."""
        with tempfile.TemporaryDirectory() as directory:
            visited = self.visit_in(directory)
            with self.assertRaises(TypeError):
                visited.send_twice(Path(directory) / "any.toml")

    def test_no_tracked_file_publishes_a_terminal_serial(self):
        """A serial is the only thing that identifies a terminal to the device endpoint (R-041,
        R-042) and this repository is public, so a serial read on a visit stays in field-report/ on
        the laptop. Verified terminals are named by pseudonym.

        **What this does and does not enforce.** It fails on the ZKTeco form -- two to six letters
        followed by six to fourteen digits -- in any tracked document, agent file, contract or spec.
        It is a backstop for the mistake that was actually made, not a proof that no identifier can
        slip through: an all-digit serial is indistinguishable from a record count or a date, and a
        one-letter or punctuated serial that `config.SERIAL` would accept is not matched. The rule
        the inventory states is the rule; this catches the shape that broke it."""
        shaped = re.compile(r"\b[A-Za-z]{2,6}\d{6,14}\b")
        suffixes = {".md", ".py", ".toml", ".json", ".yml", ".yaml", ".txt"}
        roots = ("docs", "devices-agent", "contracts", "specs", ".github")
        # **Tracked**, from git, not a filesystem walk. A visit writes its real serial into
        # `devices-agent/field-report/<serial>-zk.toml`, which `.gitignore` covers and which the
        # inventory, the runbook and D-274 all say is where it belongs. A walk found that file and
        # failed this test on the operator's own laptop, accusing them of publishing the serial they
        # had correctly kept local -- and the obvious way to go green was to delete the evidence.
        listed = subprocess.run(["git", "-C", str(ROOT), "ls-files", "-z"],
                                capture_output=True, text=True, check=False)
        if listed.returncode != 0:
            # ROOT is not a git repository. A missing `git` *binary* is a different case and is
            # deliberately not caught: `check=False` suppresses an exit status, not a
            # `FileNotFoundError`, so that errors rather than quietly passing.
            self.skipTest("this tree is not a git repository, so the tracked file set is unknown")
        tracked = [ROOT / name for name in listed.stdout.split("\0") if name]
        found = []
        for path in sorted(tracked):
            # Every tracked `.md` anywhere -- `evidence/`, `deploy/`, `perf/` and the vendored trees
            # are where a pasted report would most plausibly land -- plus the roots above.
            wanted = path.suffix == ".md" or (path.suffix in suffixes and path.parts[len(ROOT.parts):][:1]
                                              and path.relative_to(ROOT).parts[0] in roots)
            if not wanted or not path.is_file():
                continue
            for number, line in enumerate(path.read_text(encoding="utf-8", errors="replace").split("\n"), 1):
                found += [f"{path.relative_to(ROOT)}:{number}: {match.group(0)}"
                          for match in shaped.finditer(line)]
        self.assertEqual(found, [], "a terminal serial reached a tracked file")
        self.assertGreater(len(tracked), 100, "the tracked file list came back too short to be real")


class WhatTheProbeMustNotCost(unittest.TestCase):

    def test_an_absent_address_is_retried_once_and_not_once_per_candidate_port(self):
        """`_open` returns instantly on EHOSTUNREACH, so retrying every port would spend 28 s on an
        address that is simply not there -- against the four seconds the runbook promises."""
        attempts = []

        def refuse(ip, port, timeout, retry_transient=False):
            attempts.append((port, retry_transient))
            return False

        original = probe._open
        probe._open = refuse
        try:
            self.assertIsNone(probe.probe_host("192.168.44.99", udp_probe=False, retry_transient=True))
        finally:
            probe._open = original
        self.assertEqual([port for port, retried in attempts if retried], [probe.ZK_PORT],
                         "the retry was paid for on ports this tool does not exist for")
        self.assertEqual(len(attempts), len(probe.PORTS))

    def test_the_wifi_facts_describe_the_interface_that_reaches_the_terminal(self):
        """Customer Ethernet plus a phone hotspot: the terminal is reached over the cable, and the
        hotspot's signal says nothing about the link that failed."""
        wireless = ("Inter-| sta-|   Quality        |   Discarded packets               | Missed | WE\n"
                    " face | tus | link level noise |  nwid  crypt   frag  retry   misc | beacon | 22\n"
                    " wlan0: 0000   42.  -68.  -256        0      0      0      7    403        0\n")
        self.assertEqual(probe.wifi_link(wireless)["interface"], "wlan0")
        self.assertEqual(probe.wifi_link(wireless, interface="wlan0")["level"], -68)
        self.assertIsNone(probe.wifi_link(wireless, interface="enp0s31f6"),
                          "an Ethernet interface was given a wireless link's numbers")

    def test_the_egress_interface_comes_from_the_kernels_own_route(self):
        self.assertEqual(probe.egress("192.168.1.201", "192.168.1.201 dev enp0s31f6 src 192.168.1.26 uid 1000"),
                         "enp0s31f6")
        self.assertIsNone(probe.egress("192.168.1.201", ""))
        self.assertIsNone(probe.egress("192.168.1.201", "192.168.1.201 dev"))

    def test_a_terminal_reached_over_a_cable_reports_no_wireless_numbers(self):
        original_egress, original_wifi, original_addresses = probe.egress, probe.wifi_link, probe.lan_networks
        probe.egress = lambda ip, route_output=None: "enp0s31f6"
        probe.wifi_link = lambda proc_wireless=None, interface=None: (
            None if interface == "enp0s31f6" else {"interface": "wlan0", "level": -80, "link": 20, "misc": 9,
                                                  "missed_beacon": 0})
        probe.lan_networks = lambda ip_output=None: [("192.168.1.26", "192.168.1.0/24")]
        try:
            facts = probe.laptop_network("192.168.1.201", ping_count=0)
        finally:
            probe.egress, probe.wifi_link, probe.lan_networks = original_egress, original_wifi, original_addresses
        self.assertEqual(facts["interface"], "enp0s31f6")
        self.assertIn("unavailable", facts["wifi"])
        line = visit.describe_network(facts)
        self.assertNotIn("-80", line, "the hotspot's signal was reported for a terminal on the cable")
        self.assertIn("مش واي فاي", line)


class WhatDoctorMustNotLetTheOperatorDo(unittest.TestCase):
    """Mode B decides `in_out_field` from what `doctor` prints, and the next `once` sends the whole
    log under it. Read from the last punch alone, that choice inverts every direction on a terminal
    that stores a check-out with the same codes as a check-in."""

    def log(self, pairs):
        from workin_devices.spool import Punch
        return [Punch("1001", "2026-09-21 08:00:00", in_out, verify) for in_out, verify in pairs]

    def real_log(self):
        """TERMINAL-A's own distribution: `punch` split 5779/5640, `status` 1 in 11 424 and 15 in 2."""
        return self.log([(0, 1)] * 5779 + [(1, 1)] * 5640 + [(4, 1)] * 2 + [(5, 1)] * 3 + [(5, 15)] * 2)

    def test_it_rules_out_the_column_the_manual_rule_would_have_chosen(self):
        verdict = main.in_out_verdict("punch", self.real_log())
        self.assertIn("in_out_field=punch", verdict)
        self.assertIn("status", verdict)

    def test_it_says_to_switch_back_when_the_configured_column_is_the_constant_one(self):
        flipped = [type(punch)(punch.pin, punch.local_time, punch.verify, punch.status)
                   for punch in self.real_log()]
        verdict = main.in_out_verdict("status", flipped)
        self.assertIn("غيّر in_out_field لـ punch", verdict)

    def test_a_column_nobody_ever_presses_is_not_evidence_against_itself(self):
        """A single value across the whole log is exactly what an in/out key nobody presses looks
        like. Ruling that column out elects the *other* one -- and on a terminal used with both a
        fingerprint and a card, the other one is verification: every card punch becomes a check-out,
        and correcting the mapping afterwards resends the whole log."""
        constant = self.log([(0, 1)] * 7000 + [(0, 4)] * 4426)
        verdict = main.in_out_verdict("punch", constant)
        self.assertIn("مش واضح", verdict)
        self.assertIn("ماتشغّلش once", verdict)

    def test_two_balanced_columns_stay_unclear_rather_than_guessing(self):
        verdict = main.in_out_verdict("punch", self.log([(0, 1)] * 100 + [(1, 15)] * 100))
        self.assertIn("مش واضح", verdict)
        self.assertIn("ماتشغّلش once", verdict)

    def test_a_source_that_sets_only_one_column_is_not_judged_on_the_other(self):
        """Hikvision and file sources leave `verify` unset and can leave `status` unset too. Counted
        as values, a `None` cannot be sorted against an int -- which aborts `doctor` before the
        remaining terminals are read -- and an all-`None` column read as "constant" would tell an
        operator not to deliver from a terminal that is perfectly healthy."""
        mixed = self.log([(0, None)] * 120 + [(1, None)] * 100 + [(None, None)] * 30)
        verdict = main.in_out_verdict("punch", mixed)
        self.assertIn("مش واضح", verdict, "a column that exists only for zk decided the answer")

    def test_only_a_zk_source_is_judged_at_all(self):
        """`config.in_out_field` steers that source and no other, so a Hikvision or file source --
        which has no second column -- must not be told its column is unclear.

        This asserted the guard's **source text** before, which a gate on behaviour must not do: it
        would have passed on `device.kind == "zk" or True` and failed on any equivalent refactor.
        It runs `doctor` now and reads what the operator reads."""
        with tempfile.TemporaryDirectory() as directory:
            log = Path(directory, "attlog.tsv")
            # Every punch the same shape, which is what a constant column looks like: if the
            # verdict ran for this source it would report the column unclear.
            log.write_text("".join(f"  100\t2026-03-0{1 + i % 9} 09:0{i % 10}:00\t0\t1\n"
                                   for i in range(250)), encoding="utf-8")
            config = Path(directory, "agent.toml")
            token = Path(directory, "agent.token")
            token.write_text("lab-token", encoding="utf-8")
            token.chmod(0o600)
            config.write_text(
                f'server_url = "https://localhost:18443"\ntoken_file = "{token}"\n'
                f'spool_path = "{Path(directory, "spool.sqlite3")}"\nin_out_field = "punch"\n'
                f'\n[[devices]]\nserial = "FILE-1"\nkind = "file"\npath = "{log}"\n',
                encoding="utf-8")
            printed = io.StringIO()
            with contextlib.redirect_stdout(printed):
                main.cmd_doctor(types.SimpleNamespace(config=str(config), allow_plain_http=False))
        said = printed.getvalue()
        self.assertIn("FILE-1", said, said)
        self.assertNotIn("in_out_field", said,
                         "a source with no second column was judged on one")
        self.assertNotIn("مش واضح", said, said)

    def test_in_out_field_itself_refuses_a_constant_column(self):
        """The `doctor` wrapper is pinned above; this pins the other caller, which is what
        `agent_zk` writes into the config."""
        self.assertIsNone(visit.in_out_field((0, 1), (0, 1), 1,
                                             {"punch": {0: 11426}, "status": {1: 7000, 4: 4426}}))
        self.assertEqual(visit.in_out_field((5, 1), (5, 1), 1,
                                            {"punch": {0: 5779, 1: 5640}, "status": {1: 11424, 15: 2}}),
                         "punch")

    def test_a_log_too_short_to_carry_the_argument_says_nothing(self):
        self.assertIsNone(main.in_out_verdict("punch", self.log([(0, 1)] * 5)))


if __name__ == "__main__":
    unittest.main()
