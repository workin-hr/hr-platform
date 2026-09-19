import http.server
import os
import re
import shutil
import subprocess
import tempfile
import threading
import time
import unittest
from datetime import datetime, timedelta
from pathlib import Path

from workin_devices import probe, visit
from workin_devices.sim import adms
from workin_devices.sim.hikvision import HikTerminal, serve as serve_hik
from workin_devices.sim.zk4370 import Emulator, Terminal, populate
from tests.support import FakePlatform

ROOT = Path(__file__).resolve().parents[2]
PIN = "90417"


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
            raise AssertionError(f"unexpected question {question!r}; last output:\n" + "\n".join(self.output[-15:]))
        fragment, answer = self.answers.pop(0)
        if fragment not in question:
            raise AssertionError(f"expected a question about {fragment!r}, got {question!r}; last output:\n"
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
                body = self.rfile.read(int(self.headers.get("Content-Length") or 0)).decode()
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
        self.pending = [adms.punch_line(PIN, datetime.now() - timedelta(days=1, hours=hours), hours % 2)
                        for hours in range(1, 5)]
        self.lock = threading.Lock()
        self.connected = True
        self.stopped = threading.Event()
        self.terminal.handshake()
        threading.Thread(target=self._loop, daemon=True).start()

    def punch(self, in_out=0, seconds_ago=0):
        when = (datetime.now() - timedelta(seconds=seconds_ago)).replace(microsecond=0)
        with self.lock:
            self.pending.append(adms.punch_line(PIN, when, in_out, 15))
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


def quick_visit(console, lab, out):
    """A visit with every wait cut to test length. The scan finds nothing, so a test picks its
    terminal by address; it records what it was asked to scan."""
    scans = []
    visited = visit.Visit(console=console, lab=lab, out_dir=out, capture_host="127.0.0.1", capture_port=0,
                          wait_seconds=10, settle_seconds=0.3, pause_seconds=0.5,
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

    def test_the_whole_push_visit_runs_every_check_and_reports_without_an_employee_code(self):
        console = ScriptedConsole([])
        visited = quick_visit(console, self.lab, self.out)

        def configure_the_terminal():
            self.device = PushDevice(f"http://127.0.0.1:{visited.receiver.port}")
            return ""

        def unplug_punch_twice_replug():
            self.device.connected = False
            self.device.punch(0, seconds_ago=40)
            self.device.punch(1, seconds_ago=20)
            self.device.connected = True
            return ""

        answers = VisitStart() + [
            ("أنهي جهاز", "1"),                       # a push terminal the scan did not show
            ("لما تحفظ", configure_the_terminal),
            ("نفس اللي على الستيكر", "1"),
            ("الجهاز متظبط على إيه", "1"),            # daylight saving on: Africa/Cairo
            ("بتتظبط لوحدها", "1"),
            ("بصمة عادية", lambda: self.device.punch(0)),
            ("Check-Out", lambda: self.device.punch(1)),
            ("بيدوسوا زرار", "1"),                    # no
            ("بصمتين ورا بعض", lambda: self.device.punch(0, seconds_ago=3) + self.device.punch(0)),
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
        self.assertEqual(sheet["البصمة دي اتسجلت مرة واحدة؟"], "نعم")
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
            ("الجهاز متظبط على إيه", "2"),                       # +02:00
            ("بتتظبط لوحدها", "3"),
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
        self.assertEqual(len(backup) - 1, len(self.terminal.records) - 1, "the backup is the log before the check-out")
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

    def test_a_check_out_punch_that_reads_1_in_neither_code_is_reported_not_guessed(self):
        answers = VisitStart() + [
            ("أنهي جهاز", "2"), ("اكتب IP", f"127.0.0.1:{self.emulator.port}"), ("نوع الجهاز", "1"),
            ("الستيكر", "1"), ("عدد السجلات", "1"), ("Cloud Server Setting", "1"),
            ("الجهاز متظبط على إيه", "3"), ("بتتظبط لوحدها", "1"),
            ("Check-Out", lambda: self.terminal.add_punch(PIN, in_out=4, verify=15) and ""),
            ("ملف USB", "1"), ("برنامج", "1"), ("كابل أو switch", "1"),
        ]
        console = ScriptedConsole(answers)
        visited = quick_visit(console, self.lab, self.out)
        code = visited.run()
        self.assertEqual(code, 1)
        self.assertEqual(visited.sheet["الـ in_out_field الصح"], "مش واضح")
        self.assertIn("بصمة الخروج مش باينة 1", report_of(self.out))


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
        self.assertIn("مش هيقبله", report_of(self.out))
        self.assertEqual(self.lab.allocations, [])
        self.assertEqual([path.name for path in Path(self.dir.name).rglob("*evil*")], [])

    def test_a_bug_in_the_wizard_still_leaves_a_report_saying_so(self):
        terminal = Terminal(serial="ZK-ODD-1", device_name="K40 <b>|x</b>")
        populate(terminal, [PIN], days=1)

        def broken(*args):
            raise RuntimeError("allocation exploded")

        self.lab.allocate = broken
        visited, code, console = self.visit_terminal(terminal, [
            ("الستيكر", "1"), ("عدد السجلات", "1"), ("Cloud Server Setting", "1"),
            ("الجهاز متظبط على إيه", "1"), ("بتتظبط لوحدها", "1")])
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

    def test_codes_a_live_punch_and_two_sends_and_the_password_does_not_outlive_the_visit(self):
        answers = VisitStart() + [
            ("أنهي جهاز", "2"), ("اكتب IP", f"127.0.0.1:{self.server.server_address[1]}"), ("نوع الجهاز", "2"),
            ("اسم المستخدم", ""), ("الباسورد", "s3cret-pass"),
            ("يعمل بصمة على الجهاز", self.punch_now),
            ("الجهاز متظبط على إيه", "3"), ("بتتظبط لوحدها", "1"),
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
                    ("الجهاز متظبط على إيه", "1"), ("بتتظبط لوحدها", "2"),
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

    def test_the_in_out_code_is_the_one_that_reads_the_check_out_value(self):
        self.assertEqual(visit.in_out_field(punch=1, status=15, out_value=1), "punch")
        self.assertEqual(visit.in_out_field(punch=15, status=1, out_value=1), "status")
        self.assertEqual(visit.in_out_field(punch=5, status=4, out_value=4), "status")
        self.assertIsNone(visit.in_out_field(punch=0, status=15, out_value=1))

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


if __name__ == "__main__":
    unittest.main()
