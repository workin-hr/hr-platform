"""The page drives the real wizard, and nothing else on the laptop may drive the page."""
import json
import os
import tempfile
import threading
import time
import unittest
import urllib.error
import urllib.request
from pathlib import Path

from workin_devices import visit, web
from workin_devices.sim.zk4370 import Emulator, Terminal, populate
from tests.support import FakePlatform
from tests.test_visit import FakeLab, PIN


def call(url, body=None, headers=None, method=None):
    data = json.dumps(body).encode("utf-8") if body is not None else None
    request = urllib.request.Request(url, data=data, method=method or ("POST" if data is not None else "GET"),
                                     headers={"Content-Type": "application/json", **(headers or {})})
    try:
        with urllib.request.urlopen(request, timeout=40) as answer:
            return answer.status, answer.read()
    except urllib.error.HTTPError as exc:
        return exc.code, exc.read()


class Page:
    """The browser, as a test: it polls, answers one question at a time, and reads the sheet."""

    def __init__(self, base: str, token: str):
        self.base, self.token, self.cursor = base, token, 0
        self.asked: list[str] = []

    def url(self, path: str, **query) -> str:
        parts = "&".join(f"{name}={value}" for name, value in {"t": self.token, **query}.items())
        return f"{self.base}/{path}?{parts}"

    def get(self, path: str, **query) -> dict:
        status, body = call(self.url(path, **query))
        return json.loads(body) if status == 200 else {"status": status, **json.loads(body or b"{}")}

    def post(self, path: str, body=None, **query) -> dict:
        status, answer = call(self.url(path, **query), body if body is not None else {})
        return json.loads(answer) if status == 200 else {"status": status}

    def poll(self) -> dict:
        state = self.get("events", since=self.cursor)
        self.cursor = state.get("next", self.cursor)
        return state

    def answer(self, question: dict, script: list) -> None:
        self.asked.append(question["prompt"])
        assert script, f"unscripted question {question['prompt']!r}"
        fragment, value = script.pop(0)
        assert fragment in question["prompt"], f"expected a question about {fragment!r}, got {question['prompt']!r}"
        if callable(value):
            value = value()
        if question["mode"] == "choice":
            self.post("answer", {"choice": value})
        else:
            self.post("answer", {"value": "" if value is None else str(value)})

    def next_prompt(self, script: list, seconds: float = 60) -> str:
        """Answer the script, then hand back the question that follows it, unanswered."""
        deadline = time.monotonic() + seconds
        while time.monotonic() < deadline:
            for event in self.poll()["events"]:
                if event["kind"] != "question":
                    continue
                if not script:
                    return event["prompt"]
                self.answer(event, script)
        raise AssertionError("no question followed the script")

    def drive(self, script: list, seconds: float = 120) -> dict:
        deadline = time.monotonic() + seconds
        while time.monotonic() < deadline:
            state = self.poll()
            for event in state["events"]:
                if event["kind"] == "question":
                    self.answer(event, script)
                elif event["kind"] == "done":
                    return state
            if not state["running"] and not state["events"]:
                return state
        raise AssertionError("the visit never finished")


class FastSession(web.Session):
    """The session a test runs: the same page and the same wizard, with every wait cut to test
    length and the LAN scan finding nothing, so the test picks its terminal by address."""

    def __init__(self, out_dir, lab):
        super().__init__(out_dir, lab)
        self.scans: list[str] = []

    def new_visit(self):
        return visit.Visit(console=self.console, lab=self.lab, out_dir=self.out, capture_host="127.0.0.1",
                           capture_port=0, wait_seconds=10, settle_seconds=0.3, pause_seconds=0.5,
                           quiet_seconds=0.4, sleep=lambda seconds: time.sleep(min(seconds, 0.3)),
                           networks=lambda: [("127.0.0.1", "127.0.0.0/30")],
                           scan=lambda cidr, **kwargs: self.scans.append(cidr) or [])


class APageRunningAVisit(unittest.TestCase):

    def setUp(self):
        self.dir = tempfile.TemporaryDirectory()
        self.platform = FakePlatform()
        self.lab = FakeLab(self.platform, None, self.dir.name)
        self.out = os.path.join(self.dir.name, "field-report")
        os.makedirs(self.out)
        self.terminal = Terminal(serial="ZK-WEB-1")
        populate(self.terminal, [PIN, "90418"], days=5)
        self.emulator = Emulator(self.terminal, port=0).start()
        self.session = FastSession(self.out, self.lab)
        self.token = "test-" + "k" * 20
        self.server = web.serve(self.session, self.token, "127.0.0.1", 0)
        threading.Thread(target=self.server.serve_forever, daemon=True).start()
        self.page = Page(f"http://127.0.0.1:{self.server.server_address[1]}", self.token)

    def tearDown(self):
        self.session.stop()
        self.server.shutdown()
        self.server.server_close()
        self.emulator.stop()
        self.platform.close()
        self.dir.cleanup()

    def script(self):
        """The same answers the terminal visit is driven with, as a page gives them: a choice is
        the index of the button pressed, not the number typed."""
        return [("اسم الشركة", "شركة تجربة / فرع الاختبار"), ("العميل وافق", 0), ("الصور", None),
                ("أنهي جهاز", 1), ("اكتب IP", f"127.0.0.1:{self.emulator.port}"), ("نوع الجهاز", 0),
                ("الستيكر", 0), ("عدد السجلات", 0), ("Cloud Server Setting", 0),
                ("الجهاز متظبط على إيه", 2), ("بتتظبط لوحدها", 2),
                ("بصمة دخول", lambda: self.terminal.add_punch(PIN, in_out=0, verify=15) and None),
                ("Check-Out", lambda: self.terminal.add_punch(PIN, in_out=1, verify=15) and None),
                ("ملف USB", 0), ("برنامج", 0), ("كابل أو switch", 0)]

    def test_the_page_runs_the_whole_wizard_and_writes_the_same_report(self):
        self.assertTrue(self.page.post("start")["started"])
        state = self.page.drive(self.script())
        self.assertFalse(state["running"])
        self.assertEqual(self.terminal.write_attempts, [], "the page only ever read the terminal")
        self.assertEqual(self.lab.allocations, [("ZK-WEB-1", "zkteco", "+02:00")])
        sheet = self.session.visit.sheet
        self.assertEqual(sheet["الـ in_out_field الصح"], "punch")
        self.assertEqual(sheet["السيريال"], "ZK-WEB-1")
        # The sheet the page draws is the visit's own, and the report is on disk beside the backup.
        self.assertEqual(state["sheet"]["السيريال"], "ZK-WEB-1")
        self.assertTrue(any(name.startswith("visit-ZK-WEB-1") for name in state["reports"]), state["reports"])
        self.assertIn("ZK-WEB-1-attlog-backup.tsv", state["backups"])
        self.assertEqual(self.session.scans, ["127.0.0.0/30"], "consent was given, so the LAN was scanned")

    def test_a_choice_reaches_the_wizard_as_the_value_behind_the_button(self):
        """The page sends the index of the button pressed. Were the wizard to receive that index in
        place of the option behind it, every yes/no in the visit would be answered by position and
        nothing would look wrong. Here the second button is "the customer did not agree", and what
        hangs on it is whether this laptop scans the customer's network at all."""
        self.assertTrue(self.page.post("start")["started"])
        prompt = self.page.next_prompt([("اسم الشركة", ""), ("العميل وافق", 1), ("الصور", None)])
        self.assertEqual(self.session.scans, [], "no consent, no scan")
        self.assertIn("USB", prompt, "without consent only the USB export is offered")

    def test_stopping_from_the_page_ends_the_visit_the_way_ctrl_c_ends_one(self):
        """A run on a worker thread cannot be reached by SIGINT, so the page's Stop has to arrive as
        KeyboardInterrupt inside the waiting question -- otherwise the wizard's own interrupted path,
        which writes the report and the restore checklist, never runs."""
        self.assertTrue(self.page.post("start")["started"])
        deadline = time.monotonic() + 30
        while time.monotonic() < deadline:
            state = self.page.poll()
            if any(event["kind"] == "question" for event in state["events"]):
                break
        self.page.post("stop")
        for _ in range(100):
            if not self.session.thread.is_alive():
                break
            time.sleep(0.1)
        self.assertFalse(self.session.thread.is_alive(), "the run did not stop")
        self.assertTrue(any("اتوقفت" in found.text for found in self.session.visit.findings),
                        [found.text for found in self.session.visit.findings])

    def test_a_single_step_runs_on_its_own_and_not_while_the_wizard_is_running(self):
        """The whole point of the tools row: the step that failed can be repeated by itself."""
        read = self.page.post("tool/zk-info", {"host": f"127.0.0.1", "port": self.emulator.port})
        self.assertEqual(read["device"]["serial"], "ZK-WEB-1")
        saved = self.page.post("tool/backup", {"host": "127.0.0.1", "port": self.emulator.port})
        self.assertEqual(saved["records"], len(self.terminal.records))
        self.assertTrue(Path(self.out, saved["file"]).is_file())
        self.assertEqual(self.terminal.write_attempts, [])
        self.assertTrue(self.page.post("start")["started"])
        self.assertIn("الزيارة شغالة", self.page.post("tool/zk-info", {"host": "127.0.0.1"})["error"])

    def test_an_address_may_carry_its_port_as_the_wizard_accepts_one(self):
        """The simulator, and a terminal on an unusual port, are typed the way the wizard's own
        "type the address" step takes them."""
        read = self.page.post("tool/zk-info", {"host": f"127.0.0.1:{self.emulator.port}"})
        self.assertEqual(read["device"]["serial"], "ZK-WEB-1")

    def test_a_tool_reports_a_terminal_that_is_not_there_instead_of_raising(self):
        answer = self.page.post("tool/zk-info", {"host": "127.0.0.1", "port": 9})
        self.assertIn("error", answer["device"], answer)
        self.assertIn("error", self.page.post("tool/zk-info", {"host": ""}))


class WhatThePageRefuses(unittest.TestCase):
    """This process reads a customer's terminals and holds the lab's agent token. The page is the
    only thing allowed to drive it."""

    def setUp(self):
        self.dir = tempfile.TemporaryDirectory()
        self.session = web.Session(self.dir.name)
        self.token = "right-" + "r" * 20
        self.server = web.serve(self.session, self.token, "127.0.0.1", 0)
        threading.Thread(target=self.server.serve_forever, daemon=True).start()
        self.port = self.server.server_address[1]
        self.base = f"http://127.0.0.1:{self.port}"

    def tearDown(self):
        self.server.shutdown()
        self.server.server_close()
        self.dir.cleanup()

    def test_it_listens_on_loopback_only(self):
        self.assertEqual(self.server.server_address[0], "127.0.0.1")

    def test_the_page_itself_loads_without_a_token_and_carries_none(self):
        status, body = call(self.base + "/")
        page = body.decode("utf-8")
        self.assertEqual(status, 200)
        self.assertNotIn(self.token, page, "the token is in the address bar, never in the markup")
        self.assertIn('dir="rtl"', page)

    def test_every_other_route_needs_the_token(self):
        for path, method in (("events", "GET"), ("start", "POST"), ("stop", "POST"),
                             ("answer", "POST"), ("tool/netcheck", "POST"), ("report?name=x", "GET")):
            for query in ("", "?t=", "?t=wrong", "?t=" + self.token[:-1]):
                joiner = "&" if "?" in path else "?"
                url = f"{self.base}/{path}{joiner if query else ''}{query.lstrip('?')}"
                status, _ = call(url, {} if method == "POST" else None, method=method)
                self.assertEqual(status, 403, f"{method} {url} was not refused")
        # A token with characters no token can contain is a refusal, not a crash.
        status, _ = call(f"{self.base}/events?t=%D8%B9%D8%B1%D8%A8%D9%8A")
        self.assertEqual(status, 403)

    def test_a_page_on_another_origin_cannot_drive_this_one(self):
        """A site the operator has open in another tab may send a request; the browser tells us
        where it came from, and that is exactly the word worth believing here."""
        for origin in ("http://evil.example", "https://evil.example", "http://192.168.1.50"):
            status, _ = call(f"{self.base}/start?t={self.token}", {}, {"Origin": origin})
            self.assertEqual(status, 403, origin)
        status, _ = call(f"{self.base}/start?t={self.token}", {}, {"Origin": self.base})
        self.assertEqual(status, 200)

    def test_a_download_cannot_leave_the_report_directory(self):
        secret = Path(self.dir.name).parent / "outside.md"
        secret.write_text("not yours", encoding="utf-8")
        Path(self.dir.name, "inside.md").write_text("# report", encoding="utf-8")
        try:
            for name in ("../outside.md", "..%2Foutside.md", "/etc/passwd", "", "%00inside.md",
                         str(secret), "sub/../../outside.md"):
                status, _ = call(f"{self.base}/report?t={self.token}&name={name}")
                self.assertEqual(status, 404, name)
            status, body = call(f"{self.base}/report?t={self.token}&name=inside.md")
            self.assertEqual((status, body), (200, b"# report"))
        finally:
            secret.unlink()

    def test_an_unknown_route_is_not_found_rather_than_a_traceback(self):
        self.assertEqual(call(f"{self.base}/nope?t={self.token}")[0], 404)
        self.assertEqual(call(f"{self.base}/tool/nope?t={self.token}", {})[0], 200)
        self.assertIn("error", json.loads(call(f"{self.base}/tool/nope?t={self.token}", {})[1]))


class TheTranscriptThePageReads(unittest.TestCase):

    def test_a_page_opened_late_is_served_the_run_from_its_beginning(self):
        events = web.Events()
        events.add("line", text="one")
        events.add("title", text="two")
        self.assertEqual([item["kind"] for item in events.since(0, timeout=0.1)], ["line", "title"])
        self.assertEqual(len(events.since(1, timeout=0.1)), 1)
        # A cursor past the end waits for the next line rather than spinning, and a negative one
        # cannot make the page miss the start of the run.
        started = time.monotonic()
        self.assertEqual(events.since(2, timeout=0.2), [])
        self.assertGreaterEqual(time.monotonic() - started, 0.15)
        self.assertEqual(len(events.since(-5, timeout=0.1)), 2)

    def test_the_console_reaches_the_page_as_the_wizard_wrote_it(self):
        events = web.Events()
        console = web.WebConsole(events)
        console.say("سطر")
        console.title("خطوة")
        console.note("bad", "مشكلة", "الحل")
        self.assertEqual([(item["kind"], item.get("text")) for item in events.since(0, timeout=0)],
                         [("line", "سطر"), ("title", "خطوة"), ("finding", "مشكلة")])
        self.assertEqual(events.items[-1]["fix"], "الحل")
        self.assertEqual(events.items[-1]["level"], "bad")

    def test_a_question_waits_for_the_page_and_a_stop_raises_where_it_waits(self):
        console = web.WebConsole(web.Events())
        picked = []
        asker = threading.Thread(target=lambda: picked.append(console.choose("أنهي واحد؟", ["أ", "ب"])),
                                 daemon=True)
        asker.start()
        time.sleep(0.3)
        self.assertFalse(picked, "the wizard carried on without an answer")
        console.reply(choice=1)
        asker.join(5)
        self.assertEqual(picked, [1])

        stopped = []

        def wait_then_record():
            try:
                console.ask("اكتب حاجة")
            except KeyboardInterrupt:
                stopped.append(True)
        waiter = threading.Thread(target=wait_then_record, daemon=True)
        waiter.start()
        time.sleep(0.3)
        console.stop()
        waiter.join(5)
        self.assertEqual(stopped, [True], "Stop must arrive inside the waiting question")


if __name__ == "__main__":
    unittest.main()
