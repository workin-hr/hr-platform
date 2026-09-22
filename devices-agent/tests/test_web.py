"""The page drives the real wizard, and nothing else on the laptop may drive the page."""
import ipaddress
import json
import os
import tempfile
import types
import socket
import threading
import time
import unittest
import urllib.error
import urllib.request
from datetime import datetime
from pathlib import Path
from unittest import mock

from workin_devices import probe, visit, web
from workin_devices.sim.zk4370 import Emulator, Terminal, populate
from tests.support import FakePlatform
from tests.test_visit import FakeLab, PIN


def call(url, body=None, headers=None, method=None, raw=None):
    # `raw` sends bytes the JSON encoder would never produce -- a list, a bare string -- which is
    # what a hand-rolled client or a stale script sends.
    data = raw if raw is not None else (json.dumps(body).encode("utf-8") if body is not None else None)
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
        # Every answer names the question it answers, as the page's own script does.
        taken = self.post("answer", {"id": question["id"], "choice": value} if question["mode"] == "choice"
                          else {"id": question["id"], "value": "" if value is None else str(value)})
        assert taken.get("taken"), f"the wizard refused the answer to {question['prompt']!r}: {taken}"

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
        super().__init__(out_dir, lab, wait_seconds=10, networks=lambda: [("127.0.0.1", "127.0.0.0/30")])
        self.scans: list[str] = []

    def new_visit(self):
        return visit.Visit(console=self.console, lab=self.lab, out_dir=self.out, capture_host="127.0.0.1",
                           capture_port=0, wait_seconds=10, settle_seconds=0.3, pause_seconds=0.5,
                           quiet_seconds=0.4, sleep=self._sleep, networks=self.networks,
                           scan=lambda cidr, **kwargs: self.scans.append(cidr) or [])

    def _sleep(self, seconds: float) -> None:
        self.console.stopping.wait(min(seconds, 0.3))


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

    def test_stopping_mid_visit_still_asks_the_checklist_that_puts_the_terminal_back(self):
        """The step that matters most when a visit ends early, and the one a page cannot skip.

        `restore()` runs *after* the interrupt is caught and asks its questions through this same
        console. While the stop flag was sticky, every one of them raised, `restore()` caught that
        and filed "the restore steps were not completed" -- even when the operator had done all of
        it -- and the verdict became "N problems" instead of "stopped early". The earlier stop test
        could not see it, because it stopped at the first question, before `device_started`."""
        self.assertTrue(self.page.post("start")["started"])
        # Far enough in that the wizard has a terminal and something to put back.
        self.page.next_prompt(self.script()[:9], seconds=60)
        self.assertTrue(self.session.visit.device_started, "stopped before there was anything to restore")
        self.page.post("stop")

        asked = []
        deadline = time.monotonic() + 60
        while time.monotonic() < deadline and self.session.thread.is_alive():
            for event in self.page.poll()["events"]:
                if event["kind"] != "question":
                    continue
                asked.append(event["prompt"])
                self.assertTrue(self.page.post("answer", {"id": event["id"], "choice": 0})["taken"],
                                f"the checklist question could not be answered: {event['prompt']!r}")
        self.assertFalse(self.session.thread.is_alive(), "the run did not finish its ending")
        self.assertTrue(any("Cloud Server Setting" in prompt or "برنامج" in prompt for prompt in asked),
                        f"the restore checklist was never asked: {asked}")
        self.assertTrue(any("كابل أو switch" in prompt for prompt in asked), asked)
        texts = [found.text for found in self.session.visit.findings]
        self.assertTrue(any("اتوقفت" in text for text in texts), texts)
        self.assertNotIn("ماكمّلناش خطوات الرجوع", texts,
                         "the checklist was answered, so nothing may say it was not")
        self.assertTrue(any(name.startswith("visit-") for name in self.page.poll()["reports"]))

    def test_a_second_tab_cannot_answer_the_question_that_replaced_its_own(self):
        """The token URL opened twice shows both tabs the same question. Once one has answered and
        the wizard has moved on, the other tab's press must not land on whatever is waiting now:
        the question after this one is the consent to scan the customer's network, and a typed
        answer arriving at a choice prompt would choose option 0 -- which is yes."""
        self.assertTrue(self.page.post("start")["started"])
        first = self.await_question(1)
        self.assertEqual(first["mode"], "text")
        self.assertEqual(self.page.post("answer", {"id": 1, "value": "شركة تجربة"}), {"taken": True, "asked": 1})

        consent = self.await_question(2)
        self.assertIn("العميل وافق", consent["prompt"])
        self.assertEqual(consent["mode"], "choice")
        stale = self.page.post("answer", {"id": 1, "value": "شركة تجربة"})
        self.assertEqual(stale, {"status": 409}, "the first tab's answer was applied to the next question")
        self.assertFalse(self.session.console.answered.is_set(), "the wizard took an answer for a past question")

        # Answered as the operator meant it -- option 1, no consent -- the scan never runs.
        self.page.post("answer", {"id": 2, "choice": 1})
        self.await_question(3)
        self.assertEqual(self.session.scans, [], "the stale answer granted consent to scan the customer's LAN")

    def await_question(self, number: int, seconds: float = 30) -> dict:
        """The question the wizard is waiting on, by its id, as the page sees it."""
        deadline = time.monotonic() + seconds
        while time.monotonic() < deadline:
            for event in self.page.poll()["events"]:
                if event.get("kind") == "question" and event["id"] == number:
                    return event
        raise AssertionError(f"question {number} never arrived")

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

    def test_a_standalone_send_reports_through_the_transcript_and_not_twice(self):
        """`send_twice` writes its findings through the shared console, so they are in the
        transcript already. Returned in the response as well, the page drew each one a second time
        and one send read as two."""
        answer = self.page.post("tool/once", {"host": f"127.0.0.1:{self.emulator.port}",
                                              "in_out_field": "punch"})
        self.assertNotIn("error", answer, answer)
        self.assertEqual(answer["in_out"], "punch")
        self.assertIsInstance(answer["findings"], int, "the response carried the findings a second time")
        self.assertGreater(answer["findings"], 0)
        drawn = [event for event in self.page.poll()["events"] if event["kind"] == "finding"]
        self.assertEqual(len(drawn), answer["findings"])

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
        self.session = web.Session(self.dir.name, networks=lambda: [("127.0.0.1", "127.0.0.0/30")])
        self.token = "right-" + "r" * 20
        self.server = web.serve(self.session, self.token, "127.0.0.1", 0)
        threading.Thread(target=self.server.serve_forever, daemon=True).start()
        self.port = self.server.server_address[1]
        self.base = f"http://127.0.0.1:{self.port}"

    def tearDown(self):
        self.session.close(timeout=10)
        self.server.shutdown()
        self.server.server_close()
        self.dir.cleanup()

    def test_it_listens_on_loopback_only(self):
        """Not a default the operator can talk past. Bound anywhere else, terminal reads, the
        report downloads and lab-backed sends are reachable from the customer's LAN over plaintext,
        with the URL token as the whole defence."""
        self.assertEqual(self.server.server_address[0], "127.0.0.1")
        for outside in ("0.0.0.0", "::", "192.168.1.7"):
            with self.assertRaises(ValueError, msg=outside):
                web.loopback(outside)
        for inside in ("127.0.0.1", "localhost", "::1", "127.0.0.9"):
            self.assertEqual(web.loopback(inside), inside)
        said = []
        self.assertEqual(web.run(host="0.0.0.0", out=said.append), 2, said)
        self.assertTrue(any("اللابتوب نفسه" in line for line in said), said)

    def test_a_loopback_host_that_resolves_to_ipv6_can_actually_bind(self):
        """`loopback()` blessed `::1` and `serve()` could not bind it: `ThreadingHTTPServer` is
        `AF_INET`, so `--host ::1` passed the allowlist and then died with `[Errno -9] Address family
        for hostname not supported`. The set of hosts the check accepts and the set the server can
        bind have to be the same set."""
        server = web.serve(self.session, self.token, "::1", 0)
        try:
            self.assertEqual(server.address_family, socket.AF_INET6)
            self.assertEqual(server.server_address[0], "::1")
        finally:
            server.server_close()
        # And the IPv4 default is untouched.
        self.assertEqual(self.server.address_family, socket.AF_INET)

    def test_a_typo_in_the_port_is_answered_rather_than_dropping_the_connection(self):
        """An out-of-range port raises `OverflowError` at `connect()`, which is not an `OSError`, so
        it escaped `tool`'s refusal: the socket was reset with no response and a traceback printed to
        the terminal `log_message` exists to keep quiet and the runbook tells the operator to watch.
        One extra digit in the address box read as "the page is broken"."""
        answered = self.session.tool("zk-info", {"host": "127.0.0.1:437000"})
        self.assertIn("error", answered, answered)
        self.assertIn("65535", answered["error"])
        # Over HTTP, and the listener is still serving afterwards.
        reply = call(f"{self.base}/tool/zk-info?t={self.token}", {"host": "127.0.0.1:99999999999"})
        self.assertEqual(reply[0], 200, reply)
        self.assertIn("error", json.loads(reply[1]))
        self.assertEqual(call(f"{self.base}/events?since=0&t={self.token}")[0], 200,
                         "the listener did not survive")
        self.assertFalse(self.session.gate.locked(), "the gate was not released")

    def test_a_body_that_is_not_an_object_is_answered_rather_than_dropping_the_connection(self):
        """A JSON list parses fine and then has no `.get`. Same class as the port typo: the route
        body raised, `socketserver` closed the connection, and the page showed `Failed to fetch`."""
        for shape in ("[1, 2]", '"a string"', "7"):
            status, body = call(f"{self.base}/tool/zk-info?t={self.token}", raw=shape.encode("utf-8"))
            self.assertEqual(status, 200, f"{shape} -> {status}")
            self.assertIn("error", json.loads(body), shape)
        status, body = call(f"{self.base}/answer?t={self.token}", {"value": "x", "choice": "not-a-number"})
        self.assertEqual(status, 400, body)
        self.assertIn("must be numbers", json.loads(body)["error"])

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
        self.assertFalse(console.reply(choice=1, qid=console.asked + 1), "an answer to another question was taken")
        self.assertFalse(picked, "the wizard took an answer meant for another question")
        self.assertTrue(console.reply(choice=1, qid=console.asked))
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


class WhatTheToolsMustNotAssume(unittest.TestCase):
    """The buttons that repeat one step alone. Each of these was a way the page could do something
    the wizard would not: answer the wrong question, send the wrong column, read a terminal the
    wizard was already reading, or scan the wrong network."""

    def setUp(self):
        self.dir = tempfile.TemporaryDirectory()
        self.out = os.path.join(self.dir.name, "field-report")
        os.makedirs(self.out)
        self.session = web.Session(self.out, networks=lambda: [("10.0.0.5", "10.0.0.0/24")],
                                   now=lambda: datetime(2026, 9, 22, 10, 30, 0))

    def tearDown(self):
        self.dir.cleanup()

    # -- the connection a repeated step uses ------------------------------------------------

    def test_a_repeated_step_reuses_the_comm_key_and_udp_the_wizard_discovered(self):
        """A terminal behind a Comm Key, or speaking UDP, answers nothing on port 4370/key 0/TCP.
        The wizard found the tuple that works; a button that repeats its step must use it."""
        self.assertEqual(self.session.link("192.168.1.201", {}), (4370, 0, False))
        self.session.visit = types.SimpleNamespace(zk_link=("192.168.1.201", 4371, 9876, True), in_out=None)
        self.assertEqual(self.session.link("192.168.1.201", {}), (4371, 9876, True))

    def test_the_page_may_still_say_otherwise_and_another_address_gets_the_defaults(self):
        self.session.visit = types.SimpleNamespace(zk_link=("192.168.1.201", 4371, 9876, True), in_out=None)
        self.assertEqual(self.session.link("192.168.1.201", {"comm_key": "5", "udp": False}), (4371, 5, False))
        self.assertEqual(self.session.link("192.168.1.9", {}), (4370, 0, False),
                         "another terminal's settings were carried over to this one")

    # -- the column a standalone send writes ------------------------------------------------

    def test_a_standalone_send_refuses_to_guess_the_in_out_column(self):
        """On a terminal whose direction lives in `status`, a pass written for `punch` sends every
        arrival with the wrong direction and verification, and re-mapping afterwards makes the whole
        log look new and send again. Silence is not `punch`."""
        answer = self.session.tool("once", {"host": "127.0.0.1", "port": 9})
        self.assertIn("punch", answer.get("error", ""))
        self.assertIn("status", answer["error"])

    def test_a_standalone_send_uses_the_column_this_visit_proved_for_that_address(self):
        self.session.visit = types.SimpleNamespace(zk_link=("127.0.0.1", 9, 0, False), in_out="status")
        self.assertEqual(self.session.proved_in_out("127.0.0.1"), "status")
        # Reaches the terminal read, which is what fails here -- the column is no longer the reason.
        answer = self.session.tool("once", {"host": "127.0.0.1", "port": 9})
        self.assertNotIn("punch ولا status", answer.get("error", ""))

    def test_the_column_one_terminal_proved_is_not_carried_to_another(self):
        """The twin of the connection-settings guard, with a worse outcome: a column carried to a
        different terminal sends that terminal's whole log with every direction inverted."""
        self.session.visit = self.stub(zk_link=("192.168.1.201", 4370, 0, False), in_out="status")
        self.assertEqual(self.session.proved_in_out("10.0.0.9"), "")
        answer = self.session.tool("once", {"host": "10.0.0.9"})
        self.assertIn("punch ولا status", answer.get("error", ""),
                      "another terminal's in/out column was reused without being asked")
        # `state()` reports the column *and* the address it was proved on, so a client can tell
        # whose it is. It is the page that must stop offering it once another address is typed --
        # the server cannot see the address box, and a column the request names explicitly wins.
        shown = self.session.state()
        self.assertEqual((shown["host"], shown["in_out"]), ("192.168.1.201", "status"))
        self.session.visit = self.stub(zk_link=None, in_out="status")
        self.assertEqual(self.session.state()["in_out"], "")

    @staticmethod
    def stub(**fields):
        """A `Visit` as `state()` reads one."""
        return types.SimpleNamespace(serial="", sheet={}, findings=[], **fields)

    # -- the network a standalone scan walks ------------------------------------------------

    def test_a_scan_on_a_laptop_with_several_networks_asks_which_one(self):
        """Customer Ethernet plus Wi-Fi plus a VPN: taking the first would search the wrong one and
        report the terminal missing."""
        self.session.seen_networks = [("10.0.0.5", "10.0.0.0/24"), ("192.168.1.7", "192.168.1.0/24")]
        self.session.seen_at = self.session.clock()
        answer = self.session.tool("scan", {"consent": True})
        self.assertIn("اختار الشبكة", answer["error"])
        self.assertEqual(answer["networks"], ["10.0.0.0/24", "192.168.1.0/24"])

    def test_a_scan_from_a_button_needs_the_customer_permission_the_wizard_asks_for(self):
        """The runbook requires the customer's permission before an active scan of their network.
        The wizard asks; a button that skipped the question would be a way around it."""
        with mock.patch.object(web.probe, "scan", side_effect=AssertionError("scanned without consent")):
            answer = self.session.tool("scan", {})
        self.assertIn("إذنه", answer["error"])

    def test_the_narrowing_threshold_follows_the_scanners_own(self):
        """It has to *follow* the constant, not merely agree with today's value -- a literal `1024`
        passes any assertion that only checks the boundary at 1024."""
        with mock.patch.object(web.probe, "SCAN_LIMIT", 256):
            self.assertEqual(web.narrow("10.0.0.0/23", [("10.0.1.7", "10.0.0.0/23")]), "10.0.1.0/24",
                             "narrow ignored the scanner's limit and used a literal")
            self.assertEqual(web.narrow("10.0.0.0/24", [("10.0.0.5", "10.0.0.0/24")]), "10.0.0.0/24")
        edge = ipaddress.ip_network("10.0.0.0/22")
        self.assertEqual(edge.num_addresses, web.probe.SCAN_LIMIT)
        self.assertEqual(web.narrow(str(edge), [("10.0.3.7", str(edge))]), str(edge))
        self.assertEqual(web.narrow("10.0.0.0/21", [("10.0.3.7", "10.0.0.0/21")]), "10.0.3.0/24")

    def test_a_large_network_is_narrowed_the_way_the_wizard_narrows_it(self):
        self.assertEqual(web.narrow("10.0.0.0/16", [("10.0.3.7", "10.0.0.0/16")]), "10.0.3.0/24")
        self.assertEqual(web.narrow("10.0.0.0/24", [("10.0.0.5", "10.0.0.0/24")]), "10.0.0.0/24")
        with self.assertRaises(ValueError):
            web.narrow("172.16.0.0/16", [("10.0.0.5", "10.0.0.0/24")])

    def test_a_scan_run_from_a_button_is_kept_like_any_other_evidence(self):
        """The CLI scan and the wizard both write `scan-*.json`. A result that lives only in one
        JSON response disappears on a reload and cannot be attached to the visit."""
        self.session._networks = lambda: [("10.0.0.5", "10.0.0.0/24")]
        with mock.patch.object(web.probe, "scan", return_value=[{"ip": "10.0.0.9", "ports": [4370]}]):
            answer = self.session.tool("scan", {"consent": True})
        self.assertEqual(answer["cidr"], "10.0.0.0/24")
        self.assertEqual(answer["file"], "scan-20260922-103000.json")
        kept = json.loads(Path(self.out, answer["file"]).read_text(encoding="utf-8"))
        self.assertEqual(kept, [{"ip": "10.0.0.9", "ports": [4370]}])
        self.assertIn("scan-20260922-103000.json", self.session.state()["scans"])

    # -- one terminal, one reader ------------------------------------------------------------

    def test_a_visit_cannot_start_while_a_tool_is_still_reading(self):
        """A ZK terminal serves one session at a time, and both write the same config and backup
        files. The visit used to start anyway, because tools took no lock at all."""
        holding, release = threading.Event(), threading.Event()

        def hold(host, form):
            holding.set()
            release.wait(5)
            return {"held": True}

        self.session._tool_hold = hold
        runner = threading.Thread(target=lambda: self.session.tool("hold", {"host": "10.0.0.9"}), daemon=True)
        runner.start()
        self.assertTrue(holding.wait(5))
        self.assertFalse(self.session.start(), "the wizard started while a tool was reading a terminal")
        release.set()
        runner.join(5)


class WhatABackupMustNotLeaveBehind(unittest.TestCase):
    """A backup is the safety net taken before anything on a terminal is touched. Half of one that
    looks complete is worse than none, and the page's files panel lists it."""

    def test_a_read_that_fails_midway_leaves_the_previous_backup_untouched(self):
        with tempfile.TemporaryDirectory() as folder:
            path = Path(folder) / "ZK-1-attlog-backup.tsv"
            path.write_text("# the complete backup from the visit before\n", encoding="utf-8")
            kept = path.read_bytes()

            class Breaks:
                def __enter__(self):
                    return self

                def __exit__(self, *_):
                    return False

                def attendance(self):
                    # Fails partway through the write, which is the only case a temporary file and a
                    # rename protect against: raising before it leaves the target untouched anyway.
                    def rows():
                        yield types.SimpleNamespace(user_id="1001", timestamp=datetime(2026, 9, 21, 8, 0),
                                                    status=1, punch=0, record_size=40)
                        raise probe.zk.ZkError("the link went away halfway through")
                    return rows()

            with mock.patch.object(probe.zk, "ZkClient", lambda *a, **k: Breaks()):
                with self.assertRaises(probe.zk.ZkError):
                    probe.backup_attendance("127.0.0.1", str(path))
            self.assertTrue(path.exists(), "the previous backup was deleted outright")
            self.assertEqual(path.read_bytes(), kept, "the previous backup was overwritten")
            self.assertEqual(sorted(p.name for p in Path(folder).iterdir()), [path.name],
                             "a .part file was left in field-report/")

    def test_a_backup_that_succeeds_is_moved_into_place_whole(self):
        with tempfile.TemporaryDirectory() as folder:
            path = Path(folder) / "ZK-1-attlog-backup.tsv"

            class Reads:
                def __enter__(self):
                    return self

                def __exit__(self, *_):
                    return False

                def attendance(self):
                    return [types.SimpleNamespace(user_id="1001", timestamp=datetime(2026, 9, 21, 8, 0),
                                                  status=1, punch=0, record_size=40)]

            with mock.patch.object(probe.zk, "ZkClient", lambda *a, **k: Reads()):
                self.assertEqual(probe.backup_attendance("127.0.0.1", str(path)), 1)
            self.assertEqual(sorted(p.name for p in Path(folder).iterdir()), [path.name])
            self.assertIn("1001\t2026-09-21 08:00:00", path.read_text(encoding="utf-8"))


class WhatTheWizardWaitsFor(unittest.TestCase):
    """Stopping is not the same as abandoning: everything that puts a terminal and this laptop back
    runs after the interrupt reaches the wizard."""

    class Ending:
        """A visit that ends the way the real one ends: its own work happens after the interrupt."""

        def __init__(self, console, out: Path, delay: float):
            self.console, self.out, self.delay = console, out, delay
            self.serial, self.sheet, self.findings, self.zk_link, self.in_out = "", {}, [], None, None

        def run(self) -> int:
            try:
                self.console.ask("مستني")
            except KeyboardInterrupt:
                time.sleep(self.delay)
                (self.out / "report.md").write_text("التقرير", encoding="utf-8")
            return 0

    def session(self, out: Path, delay: float) -> web.Session:
        made = web.Session(out, networks=lambda: [])
        made.new_visit = lambda: self.Ending(made.console, out, delay)
        return made

    def test_closing_waits_for_the_report_the_interrupted_visit_still_has_to_write(self):
        with tempfile.TemporaryDirectory() as folder:
            out = Path(folder)
            made = self.session(out, delay=0.6)
            self.assertTrue(made.start())
            time.sleep(0.3)
            self.assertTrue(made.close(timeout=10), "the process would have exited mid-report")
            self.assertEqual((out / "report.md").read_text(encoding="utf-8"), "التقرير")
            self.assertFalse(made.thread.is_alive())

    def test_a_stop_nothing_consumed_does_not_kill_the_next_visit(self):
        """An interrupt is for the run it was pressed during. Nothing consumes one unless a question
        is posed, so a Stop pressed while a tool ran -- or while nothing ran -- used to sit waiting
        and interrupt the *next* visit at its first question, before it had asked anything."""
        with tempfile.TemporaryDirectory() as folder:
            out = Path(folder)
            made = self.session(out, delay=0.1)
            self.assertFalse(made.stop(), "a stop was accepted with no run to stop")
            self.assertFalse(made.console.pending_stop, "the refused stop was armed anyway")
            self.assertTrue(made.start())
            asked = []
            deadline = time.monotonic() + 10
            while time.monotonic() < deadline and not asked:
                asked = [item for item in made.events.items if item["kind"] == "question"]
                time.sleep(0.05)
            self.assertTrue(asked, "the new visit was interrupted before it asked anything")
            self.assertTrue(made.console.reply(answer="", qid=asked[-1]["id"]),
                            "the new visit's first question could not be answered")
            made.close(timeout=10)

    def test_a_tools_waits_are_real_waits_after_a_stop(self):
        """`_sleep` waits on `console.stopping`, which a Stop leaves set until the next run's
        `rearm()` -- and a tool never rearms, because `rearm()` also clears `abandoned` and would
        un-abandon a process that is shutting down. So after any Stop every wait inside a tool
        returned instantly, including the gap `send_twice` leaves between its two passes. A tool is
        not interruptible by Stop anyway (`stop()` refuses with no visit running), so its `Visit`
        gets a plain `time.sleep`."""
        with tempfile.TemporaryDirectory() as folder:
            made = web.Session(folder, networks=lambda: [])
            made.console.stop()                      # as a real run's Stop leaves it
            self.assertTrue(made.console.stopping.is_set())
            slept = []
            with mock.patch.object(web.time, "sleep", slept.append):
                made._tool_visit().sleep(1)
            self.assertEqual(slept, [1], "a tool's wait was skipped by a Stop from a finished run")
            # The wizard's own waits stay interruptible, which is what Stop is for.
            started = time.monotonic()
            made.new_visit().sleep(1)
            self.assertLess(time.monotonic() - started, 0.5,
                            "the wizard's wait stopped being interruptible")

    def test_a_single_step_keeps_its_own_config_beside_the_wizards(self):
        """Both go through `write_zk_agent_config`, which is the point -- but that means the same
        writer and, before this, the same path. A standalone `once` on the same address with the
        in/out dropdown changed rewrote the wizard's `<serial>-zk.toml`, `in_out_field` included,
        leaving `field-report/` disagreeing with the visit report beside it. The `.toml` is not in the
        page's file lists either, so nothing showed that it had changed."""
        with tempfile.TemporaryDirectory() as folder:
            visited = visit.Visit(console=visit.Console(), out_dir=folder, wait_seconds=1)
            visited.out.mkdir(parents=True, exist_ok=True)
            visited.zk_link = ("127.0.0.1", 4370, 0, False)
            wizard = visited.write_zk_agent_config("SN1", "SN1-zk-20260922-1030.sqlite3", "punch")
            kept = wizard.read_text(encoding="utf-8")

            visited.zk_link = ("127.0.0.1", 4371, 9876, True)
            tool = visited.write_zk_agent_config("SN1", "SN1-zk-tool.sqlite3", "status",
                                                 name="SN1-zk-tool.toml")
            self.assertNotEqual(tool, wizard, "the button overwrote the wizard's config")
            self.assertEqual(wizard.read_text(encoding="utf-8"), kept,
                             "the wizard's config changed under it")
            self.assertIn('in_out_field = "status"', tool.read_text(encoding="utf-8"))
            self.assertIn('in_out_field = "punch"', kept)
            self.assertEqual(sorted(p.name for p in visited.out.glob("*.toml")),
                             ["SN1-zk-tool.toml", "SN1-zk.toml"])

    def test_a_stop_armed_by_a_run_that_ended_does_not_reach_the_next_one(self):
        """The state a stop pressed during a run leaves behind when the run ends before any question
        consumes it. `start()` has to forget it, which is what `stopping.clear()` alone did not."""
        with tempfile.TemporaryDirectory() as folder:
            out = Path(folder)
            made = self.session(out, delay=0.1)
            made.console.stop()                      # armed, as a real run's Stop arms it
            self.assertTrue(made.console.pending_stop)
            self.assertTrue(made.start())
            asked = []
            deadline = time.monotonic() + 10
            while time.monotonic() < deadline and not asked:
                asked = [item for item in made.events.items if item["kind"] == "question"]
                time.sleep(0.05)
            self.assertTrue(asked, "the armed stop was delivered to the next visit")
            self.assertFalse(made.console.pending_stop)
            self.assertFalse(made.console.abandoned)
            made.close(timeout=10)

    def test_closing_waits_for_a_tool_that_is_still_writing(self):
        """A tool does not run on the visit thread -- it runs inline on an HTTP request handler,
        which is a daemon. `probe.backup_attendance` writes its file row by row, so exiting mid-tool
        left a truncated backup with a valid-looking header, listed in the page's files panel."""
        with tempfile.TemporaryDirectory() as folder:
            made = web.Session(folder, networks=lambda: [])
            holding, release, done = threading.Event(), threading.Event(), []

            def hold(host, form):
                holding.set()
                release.wait(10)
                done.append(True)
                return {"held": True}

            made._tool_hold = hold
            runner = threading.Thread(target=lambda: made.tool("hold", {"host": "10.0.0.9"}), daemon=True)
            runner.start()
            self.assertTrue(holding.wait(5))
            self.assertFalse(made.close(timeout=0.4), "an unfinished tool was reported as closed")
            self.assertEqual(done, [], "the tool finished on its own, so this proves nothing")
            release.set()
            self.assertTrue(made.close(timeout=10))
            self.assertEqual(done, [True])
            runner.join(5)

    def test_closing_says_so_when_the_visit_did_not_finish_in_time(self):
        with tempfile.TemporaryDirectory() as folder:
            out = Path(folder)
            made = self.session(out, delay=5)
            self.assertTrue(made.start())
            time.sleep(0.3)
            self.assertFalse(made.close(timeout=0.5), "an unfinished visit was reported as closed")


if __name__ == "__main__":
    unittest.main()
