"""The site visit as a local page: the same wizard, in a browser instead of a terminal.

Why a page at all. The terminal wizard asks its questions in one fixed order and cannot repeat a
step, so one lost ARP exchange at step 6 ended a whole visit and the operator answered everything
again from the start. This runs the same `visit.Visit` -- its logic is untouched, and it reaches the
browser only through `visit.Console`, the seam a test already replaces -- and adds the two things a
terminal cannot: the transcript, the findings, the results sheet and the report are on screen at
once, and each step a page button can drive is also runnable on its own, against one address, while
the wizard is not running.

Stopping. The page's Stop ends a run the way a single Ctrl-C ends one in a terminal: the report is
written, the "stopped early" finding is filed, and the checklist that puts the terminal's settings
back is asked *on the page*, because those questions come through this same console after the
interrupt. Ctrl-C in the command serving the page is the other case -- nobody is left to answer --
so it abandons the run and waits for the report and for any single step still writing a file.

Where it listens, and why that matters. 127.0.0.1 only, and every request carries a token this
process invents at startup and puts in the URL it opens. This process reads terminals on a
customer's LAN and holds the lab's agent token; nothing on that LAN, and no other page the operator
has open, may drive it. The token is never written into the page's markup, and a request carrying
somebody else's `Origin` is refused before it reaches any of this.
"""
from __future__ import annotations

import http.server
import ipaddress
import json
import secrets
import socket
import threading
import time
import webbrowser
from datetime import datetime
from pathlib import Path
from urllib.parse import parse_qs, urlparse

from . import config as cfg, gateway as gw, probe, visit
from . import zk4370 as zk

DEFAULT_PORT = 18100
DEFAULT_ZK_PORT = 4370
# Long enough that a browser is not asking every second, short enough that a page which has just
# been reopened does not sit blank waiting for the previous poll to time out.
POLL_SECONDS = 20.0
# `/events` answers the moment a transcript line lands, and reading the laptop's addresses forks
# `ip`. Cached for this long so a talkative step does not fork once per line.
NETWORK_SECONDS = 30.0
# `probe.SCAN_LIMIT` is the authority: this narrows to fit what the scanner will walk, so a literal
# here would let the two drift and hand `probe.scan` a network it then refuses.
# On top of `wait_seconds`, which is the longest a stopped visit can stay parked in `Visit.wait`
# before it asks the console a question and hears the stop.
CLOSE_GRACE_SECONDS = 20.0


def narrow(cidr: str, addresses: list[tuple[str, str]]) -> str:
    """The wizard's own rule, so a page scan reaches the same network a terminal scan reaches:
    anything larger than the scanner walks becomes the /24 around this laptop's address on it."""
    if ipaddress.ip_network(cidr).num_addresses <= probe.SCAN_LIMIT:
        return cidr
    here = next((address for address, network in addresses if network == cidr), None)
    if here is None:
        raise ValueError(f"الشبكة {cidr} كبيرة واللابتوب مش عليها: اكتب شبكة /24")
    return str(ipaddress.ip_interface(f"{here}/24").network)


class Events:
    """The transcript, as an append-only list a page reads from a cursor.

    Append-only and never trimmed on purpose: a visit lasts under an hour and its whole transcript
    is what the operator reads at the end, so a page reopened halfway through is served the run from
    its beginning rather than from whenever it reconnected."""

    def __init__(self):
        self.items: list[dict] = []
        self.ready = threading.Condition()

    def add(self, kind: str, **fields) -> None:
        with self.ready:
            self.items.append({"kind": kind, **fields})
            self.ready.notify_all()

    def since(self, cursor: int, timeout: float = POLL_SECONDS) -> list[dict]:
        with self.ready:
            if cursor >= len(self.items):
                self.ready.wait(timeout)
            return self.items[max(cursor, 0):]


class WebConsole(visit.Console):
    """The visit's terminal, redrawn as events a page renders and answers.

    A `Stop` from the page becomes `KeyboardInterrupt` inside whichever question is waiting, which
    is the only way to reach the wizard's own interrupted path: `KeyboardInterrupt` is delivered to
    the main thread and the run is not on it. That path writes the report, files the "stopped early"
    finding, and asks the checklist that puts a terminal's settings back -- so stopping from the page
    ends a visit as a *single* Ctrl-C ends one in a terminal.

    **Which is why the interrupt is one-shot.** Those checklist questions come through this same
    console after the interrupt has been caught. A flag that stayed set would raise on every one of
    them, `Visit.restore` would catch that and file "the restore steps were not completed" -- the
    step that confirms the customer's terminal was pointed back at their own software -- and it
    would file it even when the operator had done all of it. `abandon()` is the other case: the
    process is going away and there is nobody left at the page to answer, so then every question
    does raise."""

    def __init__(self, events: Events):
        self.events = events
        self.asked = 0
        self.turn = threading.Lock()
        self.answered = threading.Event()
        # `stopping` stays set once stopped, because `Session._sleep` waits on it: a stopped visit
        # must not sit out a ten-minute pause. The interrupt itself is `pending_stop`, and one Stop
        # delivers exactly one.
        self.stopping = threading.Event()
        self.pending_stop = False
        self.abandoned = False
        self.answer: str = ""
        self.choice: int = 0

    # -- what the page shows ---------------------------------------------------------------

    def say(self, text: str = "") -> None:
        self.events.add("line", text=text)

    def title(self, text: str) -> None:
        self.events.add("title", text=text)

    def note(self, level: str, text: str, fix: str = "") -> None:
        self.events.add("finding", level=level, text=text, fix=fix)

    # -- what the page answers -------------------------------------------------------------

    def ask(self, prompt: str) -> str:
        self._pose("text", prompt)
        return self.answer

    def secret(self, prompt: str) -> str:
        self._pose("secret", prompt)
        return self.answer

    def enter(self, text: str) -> None:
        self._pose("done", text)

    def choose(self, question: str, labels: list[str]) -> int:
        self._pose("choice", question, labels)
        return self.choice if 0 <= self.choice < len(labels) else 0

    def _pose(self, kind: str, prompt: str, labels: list[str] | None = None) -> None:
        if self._take_stop():
            raise KeyboardInterrupt
        with self.turn:
            self.asked += 1
            self.answered.clear()
            asked = self.asked
        self.events.add("question", id=asked, mode=kind, prompt=prompt, labels=labels or [])
        while not self.answered.wait(0.2):
            if self._take_stop():
                raise KeyboardInterrupt
        if self._take_stop():
            raise KeyboardInterrupt
        self.events.add("answered", id=asked)

    def _take_stop(self) -> bool:
        """True once per Stop -- or every time, once the run has been abandoned."""
        with self.turn:
            if self.abandoned:
                return True
            if not self.pending_stop:
                return False
            self.pending_stop = False
            return True

    def reply(self, answer: str = "", choice: int = 0, qid: int = 0) -> bool:
        """An answer belongs to the question it was rendered for, and says so.

        The token URL can be open in two tabs, so an answer typed in one can arrive after the
        other has already answered and the wizard has moved on. Applied blind it would answer
        whichever question is now waiting -- and a typed answer landing on a choice prompt would
        pick option 0, which on the consent prompts is yes. False means nothing was taken."""
        with self.turn:
            if qid != self.asked or self.answered.is_set():
                return False
            self.answer, self.choice = answer, choice
            self.answered.set()
            return True

    def stop(self) -> None:
        """End the visit: one interrupt, delivered to whichever question is waiting."""
        with self.turn:
            self.pending_stop = True
        self.stopping.set()
        self.answered.set()

    def rearm(self) -> None:
        """Forget any interrupt nothing consumed, before a new run starts.

        An interrupt is for the run it was pressed during. Nothing consumes one unless a question is
        posed, so a Stop pressed while a tool was running -- or while nothing was -- used to sit
        waiting and kill the *next* visit at its first question, before it asked anything."""
        with self.turn:
            self.pending_stop = False
            self.abandoned = False
        self.stopping.clear()

    def abandon(self) -> None:
        """Give up on the visit: every question from here on raises.

        Ctrl-C in the terminal that started the page is not the page's Stop. There is nobody left at
        the page to answer the restore checklist, so the ending path must not wait for an answer
        that cannot come; the report is still written and the checklist still reaches it as the
        wizard's own "not completed" finding."""
        self.abandoned = True
        self.stop()


class Session:
    """One page, one laptop, at most one run at a time."""

    def __init__(self, out_dir: str | Path | None = None, lab: visit.Lab | None = None,
                 wait_seconds: float = 180, now=datetime.now, clock=time.monotonic,
                 networks=probe.lan_networks):
        self.out = Path(out_dir) if out_dir else visit.AGENT_DIR / "field-report"
        self.lab = lab or visit.Lab()
        self.wait_seconds = wait_seconds
        self.now, self.clock, self._networks = now, clock, networks
        self.seen_networks: list[tuple[str, str]] | None = None
        self.seen_at = 0.0
        self.events = Events()
        # One line before anything happens, so the page's first poll answers at once and paints the
        # state instead of sitting blank for a whole long-poll while nothing is running.
        self.events.add("ready")
        self.console = WebConsole(self.events)
        self.visit: visit.Visit | None = None
        self.thread: threading.Thread | None = None
        # One gate over both doors. A tool holds it for its whole run, so Start cannot slip past
        # while one is still reading the terminal -- the two would read the same terminal and
        # write the same config and backup files.
        self.gate = threading.Lock()

    # -- state the page draws beside the transcript ----------------------------------------

    def state(self) -> dict:
        running = bool(self.thread and self.thread.is_alive())
        visited = self.visit
        link = (visited.zk_link if visited else None) or ("", DEFAULT_ZK_PORT, 0, False)
        return {"running": running,
                "serial": (visited.serial if visited else None) or "",
                "sheet": {row: value for row, value in (visited.sheet if visited else {}).items() if value},
                "findings": [{"level": found.level, "text": found.text, "fix": found.fix}
                             for found in (visited.findings if visited else [])],
                # What the wizard discovered, so the buttons that repeat a step alone repeat it
                # against the same terminal on the same terms instead of the defaults.
                "host": link[0], "port": link[1], "comm_key": link[2], "udp": bool(link[3]),
                # Paired with `host` above, which is the address it was proved on -- the page
                # compares the two and stops offering it once the operator types another address.
                # The server-side guard is `proved_in_out` in `_tool_once`, for a request that
                # names no column at all; it cannot see what the operator typed.
                # Not published without an address to pair it with: a column on its own is
                # something the page cannot judge the relevance of.
                "in_out": ((visited.in_out if visited else None) or "") if link[0] else "",
                "networks": [network for _, network in self.networks()],
                "reports": sorted(path.name for path in self.out.glob("*.md")),
                "backups": sorted(path.name for path in self.out.glob("*.tsv")),
                "scans": sorted(path.name for path in self.out.glob("*.json"))}

    def networks(self) -> list[tuple[str, str]]:
        """Cached: this is read on every `/events` answer, and that answer comes back the moment a
        transcript line lands."""
        if self.seen_networks is None or self.clock() - self.seen_at > NETWORK_SECONDS:
            self.seen_networks, self.seen_at = self._networks(), self.clock()
        return self.seen_networks

    # -- the wizard ------------------------------------------------------------------------

    def start(self) -> bool:
        """The whole visit, on a worker thread. False when one is already running."""
        if not self.gate.acquire(blocking=False):
            return False
        try:
            if self.thread and self.thread.is_alive():
                return False
            self.console.rearm()
            self.out.mkdir(parents=True, exist_ok=True)
            self.visit = self.new_visit()
            self.thread = threading.Thread(target=self._run, args=(self.visit,), daemon=True)
            self.thread.start()
            return True
        finally:
            self.gate.release()

    def _run(self, visited: visit.Visit) -> None:
        try:
            code = visited.run()
        except Exception as exc:  # noqa: BLE001 - the page must hear about it, not the void
            self.events.add("finding", level="bad", text=f"الصفحة وقعت: {exc!r}", fix="ابعت الرسالة دي للفريق")
        else:
            self.events.add("done", code=code)

    def new_visit(self) -> visit.Visit:
        return visit.Visit(console=self.console, lab=self.lab, out_dir=self.out,
                           wait_seconds=self.wait_seconds, sleep=self._sleep)

    def _sleep(self, seconds: float) -> None:
        """The wizard's own waits, made interruptible. Stop should not have to wait out the ten
        minutes of the capture-paused step before the visit hears it."""
        self.console.stopping.wait(seconds)

    def stop(self) -> bool:
        """False when there is no run to stop, so the page says that rather than nothing."""
        if not (self.thread and self.thread.is_alive()):
            return False
        self.console.stop()
        return True

    def abandon(self) -> None:
        self.console.abandon()

    def close(self, timeout: float | None = None) -> bool:
        """Abandon the run, and wait for whatever is in flight to finish. False when it did not.

        Two doors. The visit writes its report, files its findings and shuts down its receiver
        *after* the interrupt reaches it, on a daemon thread the interpreter kills where it stands at
        exit; a visit parked in `Visit.wait` hears the interrupt only when that wait times out, so
        the wait here covers one `wait_seconds`. And a tool does not run on that thread at all -- it
        runs inline on an HTTP request handler, also a daemon, and `probe.backup_attendance` writes
        its file row by row, so exiting mid-tool leaves a truncated backup that looks complete.

        This abandons rather than stops: there is nobody left at the page to answer the checklist."""
        self.abandon()
        limit = self.wait_seconds + CLOSE_GRACE_SECONDS if timeout is None else timeout
        deadline = self.clock() + limit
        thread = self.thread
        if thread is not None and thread.is_alive():
            thread.join(limit)
            if thread.is_alive():
                return False
        if not self.gate.acquire(timeout=max(0.0, deadline - self.clock())):
            return False
        self.gate.release()
        return True

    # -- one step at a time, when no wizard is running --------------------------------------

    def tool(self, name: str, form: dict) -> dict:
        """A single step against one address, so a step that failed can be repeated alone.

        Held under the same gate `start` takes, for the whole operation: a tool and a visit reading
        one terminal at once would fight over a session it serves one at a time, and would write
        the same config and backup files."""
        if not self.gate.acquire(blocking=False):
            return {"error": "في خطوة شغالة دلوقتي: استنى لما تخلص"}
        try:
            if self.thread and self.thread.is_alive():
                return {"error": "الزيارة شغالة دلوقتي: استنى لما تخلص، أو أوقفها"}
            # `ip:port` as the wizard's own "type the address" step accepts it, so the simulator and
            # a terminal on an unusual port are reachable from here too.
            host, _, typed_port = (form.get("host") or "").strip().partition(":")
            if typed_port.isdigit():
                form = {**form, "port": int(typed_port)}
            runner = getattr(self, f"_tool_{name.replace('-', '_')}", None)
            if runner is None:
                return {"error": f"مفيش أداة اسمها {name}"}
            if name != "scan" and not host:
                return {"error": "اكتب IP الجهاز"}
            try:
                return runner(host, form)
            except (zk.ZkError, gw.Unauthorized, cfg.ConfigError, OSError, ValueError) as exc:
                return {"error": str(exc)}
        finally:
            self.gate.release()

    def link(self, host: str, form: dict) -> tuple[int, int, bool]:
        """Port, Comm Key and UDP for one address: what the page sent, else what the visit found.

        A terminal behind a Comm Key or speaking UDP answers nothing on the defaults, so a button
        that repeated a step with port 4370, key 0 and TCP could not repeat the step that failed."""
        found = (self.visit.zk_link if self.visit else None) or ()
        known = found if found and found[0] == host else ()
        said = lambda name: str(form.get(name) or "").strip()
        port = int(said("port") or (known[1] if known else 0) or DEFAULT_ZK_PORT)
        key = int(said("comm_key")) if said("comm_key") else (known[2] if known else 0)
        udp = bool(form["udp"]) if "udp" in form else bool(known[3]) if known else False
        return port, key, udp

    def proved_in_out(self, host: str) -> str:
        """The in/out column this visit proved **for this address**, or nothing.

        Scoped the way `link` scopes the connection settings, and for the same reason with a worse
        outcome: a column carried from the terminal a visit ran against to a different terminal in
        the tools row sends that terminal's whole log with every direction inverted."""
        found = (self.visit.zk_link if self.visit else None) or ()
        if not found or found[0] != host:
            return ""
        return (self.visit.in_out if self.visit else "") or ""

    def _tool_netcheck(self, host: str, form: dict) -> dict:
        return {"network": probe.laptop_network(host, ping_count=int(form.get("count") or 10))}

    def _tool_scan(self, host: str, form: dict) -> dict:
        """The network the operator picked, narrowed as the wizard narrows it, and kept on disk.

        A laptop on customer Ethernet *and* Wi-Fi *and* a VPN has several; scanning whichever came
        back first would quietly search the wrong one and report the terminal missing."""
        # The runbook requires the customer's permission before an active scan of their network,
        # and the wizard asks for it. A button that skipped the question would be a way around it.
        if not form.get("consent"):
            return {"error": "البحث في شبكة العميل لازم يكون بعد إذنه: علّم على \"العميل وافق\" الأول"}
        addresses = self.networks()
        cidr = (form.get("cidr") or "").strip()
        if not cidr:
            if not addresses:
                return {"error": "اللابتوب مش على شبكة"}
            if len(addresses) > 1:
                return {"error": "اللابتوب على أكتر من شبكة: اختار الشبكة اللي فيها الجهاز",
                        "networks": [network for _, network in addresses]}
            cidr = addresses[0][1]
        cidr = narrow(cidr, addresses)
        found = probe.scan(cidr, out=lambda line: None)
        # Written where the wizard writes its own scan, so a scan run from a button is evidence
        # that survives the reload and can be attached to the visit like any other.
        self.out.mkdir(parents=True, exist_ok=True)
        path = self.out / f"scan-{self.now():%Y%m%d-%H%M%S}.json"
        path.write_text(json.dumps(found, indent=2, ensure_ascii=False), encoding="utf-8")
        return {"cidr": cidr, "found": found, "file": path.name}

    def _tool_zk_info(self, host: str, form: dict) -> dict:
        port, key, udp = self.link(host, form)
        return {"device": probe.zk_summary(host, port, key, udp, timeout=8)}

    def _tool_backup(self, host: str, form: dict) -> dict:
        port, key, udp = self.link(host, form)
        summary = probe.zk_summary(host, port, key, udp, timeout=8)
        if "error" in summary:
            return {"error": summary["error"]}
        serial = str(summary.get("serial") or "")
        if not cfg.SERIAL.match(serial):
            return {"error": f"الجهاز رد بسيريال السيستم مش هيقبله: {serial!r}"}
        self.out.mkdir(parents=True, exist_ok=True)
        path = self.out / f"{serial}-attlog-backup.tsv"
        count = probe.backup_attendance(host, str(path), port, key, udp)
        return {"serial": serial, "records": count, "file": path.name}

    def _tool_once(self, host: str, form: dict) -> dict:
        """The agent's own pass, written and run the way the wizard's step 6.4 writes and runs it."""
        port, key, udp = self.link(host, form)
        # Never a default. On a terminal whose in/out lives in `status`, a pass written for `punch`
        # sends every arrival with the direction and verification taken from the wrong column, and
        # correcting the mapping afterwards makes the whole log look new and send again.
        field = (form.get("in_out_field") or "").strip() or self.proved_in_out(host)
        if field not in ("punch", "status"):
            return {"error": "اختار العمود اللي فيه الدخول والخروج (punch ولا status) قبل الإرسال: "
                             "لو اتبعت غلط، الدخول هيتسجل خروج"}
        summary = probe.zk_summary(host, port, key, udp, timeout=8)
        if "error" in summary:
            return {"error": summary["error"]}
        serial = str(summary.get("serial") or "")
        if not cfg.SERIAL.match(serial):
            return {"error": f"الجهاز رد بسيريال السيستم مش هيقبله: {serial!r}"}
        visited = self.new_visit()
        visited.serial, visited.zk_link = serial, (host, port, key, udp)
        self.out.mkdir(parents=True, exist_ok=True)
        visited.send_twice(visited.write_zk_agent_config(serial, f"{serial}-zk-tool.sqlite3", field))
        # The findings are not returned: `send_twice` wrote them through the shared console, so they
        # are already in the transcript. Returned as well, the page would draw each one twice and
        # every warning would read as two.
        return {"serial": serial, "in_out": field, "findings": len(visited.findings)}


def make_handler(session: Session, token: str, out: Path):
    class Handler(http.server.BaseHTTPRequestHandler):
        server_version = "workin-devices-web"

        def log_message(self, *args):
            """The page is the log. A request line per poll would bury the operator."""

        # -- the two checks every request passes first -------------------------------------

        def _allowed(self, query: dict) -> bool:
            given = (self.headers.get("X-Visit-Token") or (query.get("t") or [""])[0])
            if not secrets.compare_digest(given.encode("utf-8"), token.encode("utf-8")):
                return False
            # A page on another origin may send a request but must not read or drive this one. Its
            # Origin is the browser's word, not the sender's, which is exactly what makes it useful.
            origin = self.headers.get("Origin")
            return not origin or urlparse(origin).hostname in ("127.0.0.1", "localhost")

        def _json(self, payload: dict, status: int = 200) -> None:
            body = json.dumps(payload, ensure_ascii=False).encode("utf-8")
            self.send_response(status)
            self.send_header("Content-Type", "application/json; charset=utf-8")
            self.send_header("Content-Length", str(len(body)))
            self.send_header("Cache-Control", "no-store")
            self.end_headers()
            self.wfile.write(body)

        def _form(self) -> dict:
            length = int(self.headers.get("Content-Length") or 0)
            if not length:
                return {}
            try:
                return json.loads(self.rfile.read(length).decode("utf-8")) or {}
            except (ValueError, UnicodeDecodeError):
                return {}

        # -- reads ------------------------------------------------------------------------

        def do_GET(self):
            route = urlparse(self.path)
            query = parse_qs(route.query)
            if route.path in ("/", "/index.html"):
                body = PAGE.encode("utf-8")
                self.send_response(200)
                self.send_header("Content-Type", "text/html; charset=utf-8")
                self.send_header("Content-Length", str(len(body)))
                # The page is served from one origin and loads nothing from anywhere else.
                self.send_header("Content-Security-Policy",
                                 "default-src 'none'; connect-src 'self'; style-src 'unsafe-inline'; "
                                 "script-src 'unsafe-inline'")
                self.end_headers()
                return self.wfile.write(body)
            if not self._allowed(query):
                return self._json({"error": "forbidden"}, 403)
            if route.path == "/events":
                given = (query.get("since") or ["0"])[0]
                cursor = int(given) if given.isdigit() else 0
                items = session.events.since(cursor)
                return self._json({"events": items, "next": cursor + len(items), **session.state()})
            if route.path == "/report":
                return self._send_file((query.get("name") or [""])[0])
            return self._json({"error": "not found"}, 404)

        def _send_file(self, name: str) -> None:
            """A file this visit wrote, by name only. Everything else is refused: the name is
            reduced to its last component and the result must still resolve inside the report
            directory, so neither a separator nor a link out of it reaches the disk read."""
            try:
                path = (out / Path(name).name).resolve()
                if not name or path.parent != out.resolve() or not path.is_file():
                    return self._json({"error": "not found"}, 404)
                body = path.read_bytes()
            except (OSError, ValueError):
                return self._json({"error": "not found"}, 404)
            self.send_response(200)
            self.send_header("Content-Type", "text/plain; charset=utf-8")
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)

        # -- writes -----------------------------------------------------------------------

        def do_POST(self):
            route = urlparse(self.path)
            if not self._allowed(parse_qs(route.query)):
                return self._json({"error": "forbidden"}, 403)
            form = self._form()
            if route.path == "/start":
                return self._json({"started": session.start()})
            if route.path == "/stop":
                return self._json({"stopping": session.stop()})
            if route.path == "/answer":
                taken = session.console.reply(str(form.get("value") or ""), int(form.get("choice") or 0),
                                              int(form.get("id") or 0))
                return self._json({"taken": taken, "asked": session.console.asked},
                                  200 if taken else 409)
            if route.path.startswith("/tool/"):
                return self._json(session.tool(route.path[len("/tool/"):], form))
            return self._json({"error": "not found"}, 404)

    return Handler


def loopback(host: str) -> str:
    """`host`, if every address it resolves to is loopback. Otherwise a refusal.

    The only thing standing between this page and a customer's LAN is where it listens. It reads
    their terminals, downloads what a visit wrote and sends through the lab's agent token, over
    plaintext, with a URL token as its whole defence -- so a bind that anyone else can reach is
    refused here rather than explained in a document."""
    try:
        found = socket.getaddrinfo(host, None, type=socket.SOCK_STREAM)
    except socket.gaierror as exc:
        raise ValueError(f"لا يمكن تحويل {host!r} إلى عنوان: {exc}") from exc
    addresses = {info[4][0] for info in found}
    outside = sorted(a for a in addresses if not ipaddress.ip_address(a.partition("%")[0]).is_loopback)
    if outside or not addresses:
        raise ValueError(f"الصفحة دي بتشتغل على اللابتوب نفسه بس: {host!r} معناه "
                         f"{', '.join(outside) or 'مفيش عنوان'}، وده الشبكة تقدر توصله.")
    return host


def serve(session: Session, token: str, host: str = "127.0.0.1", port: int = DEFAULT_PORT):
    """Built and returned, not started: the caller owns the thread, as capture.serve leaves it."""
    server = http.server.ThreadingHTTPServer((loopback(host), port),
                                             make_handler(session, token, session.out))
    server.daemon_threads = True
    return server


def _tell(line: str) -> None:
    """Flushed, because the address carries the token: redirected to a file or a service log, a
    block-buffered stdout holds the one line the operator needs until the process ends."""
    print(line, flush=True)


def run(host: str = "127.0.0.1", port: int = DEFAULT_PORT, out_dir: str | None = None,
        open_browser: bool = True, out=_tell) -> int:
    session = Session(out_dir)
    token = secrets.token_urlsafe(24)
    try:
        server = serve(session, token, host, port)
    except (ValueError, OSError) as exc:
        out(f"❌ {exc}")
        return 2
    address = f"http://{host}:{server.server_address[1]}/?t={token}"
    out(f"صفحة الزيارة: {address}")
    out("سيبها مفتوحة. لما تخلص، اقفل الأمر ده بـ Ctrl-C.")
    if open_browser:
        threading.Thread(target=webbrowser.open, args=(address,), daemon=True).start()
    finished = True
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        out("")
    finally:
        if session.thread and session.thread.is_alive():
            out("بوقّف الزيارة وبستنى تخلّص آخر خطوة (التقرير ورجوع إعدادات الجهاز). Ctrl-C تاني يقفل فورًا.")
        try:
            finished = session.close()
        except KeyboardInterrupt:
            # The second Ctrl-C the line above offers. It must still close the listener rather than
            # escape `main` as a traceback, which is not what `visit` does.
            finished = False
        if not finished:
            out("⚠️ الزيارة ماخلصتش لوحدها: التقرير ممكن يكون ناقص، وإعدادات الجهاز محتاجة مراجعة يدوي.")
        server.shutdown()
        server.server_close()
    return 0 if finished else 1


PAGE = """<!DOCTYPE html>
<html lang="ar" dir="rtl">
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>زيارة جهاز</title>
<style>
  :root {
    --ink: #10151c; --dim: #5a6673; --line: #d8dee6; --card: #ffffff; --bg: #f2f4f7;
    --ok: #0f7a4a; --warn: #8a5a00; --bad: #b3261e; --accent: #1b4e8f; --accent-ink: #ffffff;
  }
  @media (prefers-color-scheme: dark) {
    :root:not([data-theme="light"]) {
      --ink: #e7ecf2; --dim: #9aa7b4; --line: #2b3440; --card: #161d26; --bg: #0d1116;
      --ok: #52c98d; --warn: #e0ae54; --bad: #ff7b72; --accent: #7cb0ef; --accent-ink: #0d1116;
    }
  }
  * { box-sizing: border-box; }
  body { margin: 0; background: var(--bg); color: var(--ink); font: 15px/1.6 system-ui, "Segoe UI", sans-serif; }
  header { padding: 14px 16px; border-bottom: 1px solid var(--line); background: var(--card);
           display: flex; gap: 10px; align-items: center; flex-wrap: wrap; position: sticky; top: 0; z-index: 5; }
  h1 { font-size: 17px; margin: 0 0 0 4px; }
  .grow { flex: 1; }
  main { display: grid; grid-template-columns: minmax(0, 1.6fr) minmax(0, 1fr); gap: 16px; padding: 16px; align-items: start; }
  @media (max-width: 900px) { main { grid-template-columns: 1fr; } }
  section { background: var(--card); border: 1px solid var(--line); border-radius: 10px; padding: 14px 16px; margin-bottom: 16px; }
  h2 { font-size: 14px; margin: 0 0 10px; color: var(--dim); font-weight: 600; }
  button { font: inherit; padding: 7px 14px; border-radius: 8px; border: 1px solid var(--line);
           background: var(--card); color: var(--ink); cursor: pointer; }
  button:hover { border-color: var(--accent); }
  button.go { background: var(--accent); color: var(--accent-ink); border-color: var(--accent); font-weight: 600; }
  input { font: inherit; padding: 7px 10px; border-radius: 8px; border: 1px solid var(--line);
          background: var(--bg); color: var(--ink); min-width: 0; }
  #log { font: 13px/1.7 ui-monospace, "Cascadia Code", Menlo, monospace; white-space: pre-wrap;
         word-break: break-word; max-height: 46vh; overflow: auto; }
  .t { font-weight: 700; margin: 14px 0 6px; padding-bottom: 4px; border-bottom: 1px solid var(--line);
       font-family: system-ui, sans-serif; }
  .f { border-inline-start: 3px solid var(--line); padding: 6px 10px; margin: 7px 0; border-radius: 4px;
       background: var(--bg); font-family: system-ui, sans-serif; }
  .f.ok { border-color: var(--ok); } .f.warn { border-color: var(--warn); } .f.bad { border-color: var(--bad); }
  .f .fix { color: var(--dim); font-size: 13px; margin-top: 3px; }
  #q { position: sticky; bottom: 0; }
  #q.idle { display: none; }
  #q .prompt { font-weight: 600; margin-bottom: 10px; }
  #q .row { display: flex; gap: 8px; flex-wrap: wrap; }
  table { width: 100%; border-collapse: collapse; font-size: 13px; }
  td { padding: 5px 4px; border-bottom: 1px solid var(--line); vertical-align: top; }
  td:first-child { color: var(--dim); width: 45%; }
  .tools { display: flex; gap: 8px; flex-wrap: wrap; align-items: center; }
  .dim { color: var(--dim); font-size: 13px; }
  .num { unicode-bidi: plaintext; }
  a { color: var(--accent); }
</style>

<header>
  <h1>زيارة جهاز</h1>
  <span id="status" class="dim">…</span>
  <span class="grow"></span>
  <button id="start" class="go">ابدأ الزيارة</button>
  <button id="halt">أوقف</button>
</header>

<main>
  <div>
    <section><h2>اللي بيحصل</h2><div id="log"></div></section>
    <section id="q" class="idle">
      <div class="prompt" id="qtext"></div>
      <div class="row" id="qbody"></div>
      <div class="dim" id="qwarn"></div>
    </section>
    <section>
      <h2>أدوات: خطوة واحدة لوحدها</h2>
      <div class="tools">
        <label>IP <input id="host" size="14" placeholder="192.168.1.201" inputmode="decimal"></label>
        <label>Comm Key <input id="key" size="5" placeholder="0" inputmode="numeric"></label>
        <label><input id="udp" type="checkbox"> UDP</label>
        <label>عمود الدخول/الخروج
          <select id="field"><option value="">—</option><option value="punch">punch</option>
          <option value="status">status</option></select></label>
        <label>الشبكة <select id="cidr"><option value="">—</option></select></label>
        <span id="known" class="dim"></span>
        <label title="البحث في شبكة العميل لازم يكون بعد إذنه"><input id="consent" type="checkbox"> العميل وافق على البحث</label>
        <button data-tool="zk-info">اقرأ الجهاز</button>
        <button data-tool="backup">نسخة احتياطية</button>
        <button data-tool="once">إرسال once</button>
        <button data-tool="netcheck">افحص الشبكة</button>
        <button data-tool="scan">دوّر على الأجهزة</button>
      </div>
      <div id="tool" class="dim" style="margin-top:10px"></div>
    </section>
  </div>
  <div>
    <section><h2>ورقة النتائج</h2><table id="sheet"></table></section>
    <section><h2>الملفات</h2><div id="files" class="dim">—</div></section>
  </div>
</main>

<script>
const TOKEN = new URLSearchParams(location.search).get("t") || "";
const $ = (id) => document.getElementById(id);
let since = 0, lastId = 0;

const text = (parent, cls, value) => {
  const node = document.createElement("div");
  if (cls) node.className = cls;
  node.textContent = value;
  parent.appendChild(node);
  return node;
};

function render(event) {
  const log = $("log");
  if (event.kind === "line") text(log, "", event.text);
  if (event.kind === "title") text(log, "t", event.text);
  if (event.kind === "finding") {
    const mark = { ok: "\\u2705", warn: "\\u26a0\\ufe0f", bad: "\\u274c" }[event.level] || "";
    const card = text(log, "f " + event.level, mark + " " + event.text);
    if (event.fix) text(card, "fix", "\\u2190 " + event.fix);
  }
  if (event.kind === "question") ask(event);
  if (event.kind === "answered" && event.id === lastId) close();
  if (event.kind === "done") text(log, "t", "خلصت الزيارة");
  log.scrollTop = log.scrollHeight;
}

function ask(event) {
  lastId = event.id;
  $("q").className = "";
  $("qtext").textContent = event.prompt;
  const body = $("qbody");
  body.textContent = "";
  if (event.mode === "choice") {
    event.labels.forEach((label, index) => {
      const button = document.createElement("button");
      button.textContent = label;
      if (index === 0) button.className = "go";
      button.onclick = () => send({ choice: index });
      body.appendChild(button);
    });
  } else if (event.mode === "done") {
    const button = document.createElement("button");
    button.className = "go";
    button.textContent = "عملتها، كمّل";
    button.onclick = () => send({ value: "" });
    body.appendChild(button);
  } else {
    const field = document.createElement("input");
    field.size = 28;
    if (event.mode === "secret") field.type = "password";
    field.onkeydown = (key) => { if (key.key === "Enter") send({ value: field.value }); };
    const button = document.createElement("button");
    button.className = "go";
    button.textContent = "تمام";
    button.onclick = () => send({ value: field.value });
    body.append(field, button);
    field.focus();
  }
}

const close = () => { $("q").className = "idle"; $("qbody").textContent = ""; $("qwarn").textContent = ""; };
async function post(path, body) {
  try {
    const answer = await fetch(path + "?t=" + encodeURIComponent(TOKEN),
      { method: "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify(body || {}) });
    return await answer.json();
  } catch (problem) {
    // A dropped connection must not leave a button disabled with nothing said.
    return { failed: String(problem) };
  }
}

// The box closes when the wizard says it took the answer, not when the button was pressed: the
// same token can be open in another tab, and an answer to a question that has already moved on
// is refused rather than applied to whatever is waiting now.
async function send(body) {
  const buttons = $("qbody").querySelectorAll("button, input");
  buttons.forEach((control) => { control.disabled = true; });
  const answer = await post("answer", Object.assign({ id: lastId }, body));
  if (!answer.taken) {
    buttons.forEach((control) => { control.disabled = false; });
    $("qwarn").textContent = answer.failed
      ? "الإجابة ماوصلتش للأمر: " + answer.failed
      : "الإجابة دي كانت لسؤال فات \u2014 التبويب ده كان قديم.";
  }
  return answer;
}

function paint(state) {
  $("status").textContent = state.running
    ? "شغالة" + (state.serial ? " \\u00b7 " + state.serial : "")
    : "مش شغالة";
  $("start").disabled = state.running;
  if (!state.running) close();
  if (state.host && !$("host").value) $("host").value = state.host;
  // Everything below describes the terminal the visit ran against. The moment the operator types a
  // different address none of it applies, and sending `once` with the previous terminal's in/out
  // column would invert this terminal's whole log -- the server guard cannot see the address box,
  // so the page must stop offering what the guard would refuse.
  const elsewhere = $("host").value && state.host && $("host").value !== state.host;
  if (elsewhere) {
    if ($("key").value === String(state.comm_key)) $("key").value = "";
    if (!$("udp").dataset.touched) $("udp").checked = false;
    if ($("field").value === state.in_out) $("field").value = "";
    $("known").textContent = "";
  } else {
    if (state.comm_key && !$("key").value) $("key").value = String(state.comm_key);
    // Only until the operator says otherwise: re-checking it on every poll would undo a deliberate
    // uncheck within one long-poll, and `link()` lets the page's value win.
    if (state.udp && !$("udp").dataset.touched) $("udp").checked = true;
    if (state.in_out && !$("field").value) $("field").value = state.in_out;
    $("known").textContent = state.in_out ? "\u0645\u0646 \u0632\u064a\u0627\u0631\u0629 " + (state.serial || state.host) : "";
  }
  fillNetworks(state.networks || []);
  const sheet = $("sheet");
  sheet.textContent = "";
  for (const [row, value] of Object.entries(state.sheet || {})) {
    const line = sheet.insertRow();
    line.insertCell().textContent = row;
    const cell = line.insertCell();
    cell.className = "num";
    cell.textContent = value;
  }
  const files = [].concat(state.reports || [], state.backups || [], state.scans || []);
  $("files").textContent = "";
  if (!files.length) $("files").textContent = "\\u2014";
  files.forEach((name) => {
    const link = document.createElement("a");
    link.href = "report?name=" + encodeURIComponent(name) + "&t=" + encodeURIComponent(TOKEN);
    link.textContent = name;
    link.target = "_blank";
    const holder = text($("files"), "", "");
    holder.appendChild(link);
  });
}

function fillNetworks(list) {
  const box = $("cidr");
  if (box.dataset.list === list.join(",")) return;
  box.dataset.list = list.join(",");
  const chosen = box.value;
  box.textContent = "";
  const none = document.createElement("option");
  none.value = "";
  none.textContent = "\u2014";
  box.appendChild(none);
  list.forEach((cidr) => {
    const option = document.createElement("option");
    option.value = cidr;
    option.textContent = cidr;
    box.appendChild(option);
  });
  box.value = list.indexOf(chosen) >= 0 ? chosen : (list.length === 1 ? list[0] : "");
}

async function poll() {
  try {
    const answer = await fetch("events?since=" + since + "&t=" + encodeURIComponent(TOKEN));
    const data = await answer.json();
    if (data.error) { $("status").textContent = data.error; return setTimeout(poll, 4000); }
    since = data.next;
    (data.events || []).forEach(render);
    paint(data);
  } catch (problem) {
    $("status").textContent = "الصفحة فقدت الاتصال بالأمر";
    return setTimeout(poll, 3000);
  }
  setTimeout(poll, 0);
}

$("udp").onchange = () => { $("udp").dataset.touched = "1"; };
$("start").onclick = async () => {
  const answer = await post("start");
  if (!answer.started) {
    $("status").textContent = answer.failed
      ? "مش قادر أكلم الأمر: " + answer.failed
      : "مابدأتش: في خطوة شغالة دلوقتي \u2014 استنى لما تخلص";
  }
};
$("halt").onclick = () => post("stop");
document.querySelectorAll("[data-tool]").forEach((button) => {
  button.onclick = async () => {
    const name = button.dataset.tool;
    $("tool").textContent = "…" + button.textContent;
    const answer = await post("tool/" + name, {
      host: $("host").value.trim(), comm_key: $("key").value.trim(), udp: $("udp").checked,
      in_out_field: $("field").value, cidr: $("cidr").value, consent: $("consent").checked });
    $("tool").textContent = "";
    const block = document.createElement("pre");
    block.style.whiteSpace = "pre-wrap";
    block.style.margin = "0";
    block.dir = "ltr";
    block.textContent = JSON.stringify(answer, null, 1);
    $("tool").appendChild(block);
  };
});
poll();
</script>
</html>
"""
