"""The site visit as a local page: the same wizard, in a browser instead of a terminal.

Why a page at all. The terminal wizard asks its questions in one fixed order and cannot repeat a
step, so one lost ARP exchange at step 6 ended a whole visit and the operator answered everything
again from the start. This runs the same `visit.Visit` -- its logic is untouched, and it reaches the
browser only through `visit.Console`, the seam a test already replaces -- and adds the two things a
terminal cannot: the transcript, the findings, the results sheet and the report are on screen at
once, and each step a page button can drive is also runnable on its own, against one address, while
the wizard is not running.

Where it listens, and why that matters. 127.0.0.1 only, and every request carries a token this
process invents at startup and puts in the URL it opens. This process reads terminals on a
customer's LAN and holds the lab's agent token; nothing on that LAN, and no other page the operator
has open, may drive it. The token is never written into the page's markup, and a request carrying
somebody else's `Origin` is refused before it reaches any of this.
"""
from __future__ import annotations

import http.server
import json
import secrets
import threading
import webbrowser
from pathlib import Path
from urllib.parse import parse_qs, urlparse

from . import config as cfg, gateway as gw, probe, visit
from . import zk4370 as zk

DEFAULT_PORT = 18100
# Long enough that a browser is not asking every second, short enough that a page which has just
# been reopened does not sit blank waiting for the previous poll to time out.
POLL_SECONDS = 20.0


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
    the main thread and the run is not on it. That path writes the report, prints the checklist that
    puts a terminal's settings back, and files the "stopped early" finding -- so stopping from the
    page ends a visit exactly as Ctrl-C ends one in a terminal."""

    def __init__(self, events: Events):
        self.events = events
        self.asked = 0
        self.answered = threading.Event()
        self.stopping = threading.Event()
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
        self.asked += 1
        self.answered.clear()
        self.events.add("question", id=self.asked, mode=kind, prompt=prompt, labels=labels or [])
        while not self.answered.wait(0.2):
            if self.stopping.is_set():
                raise KeyboardInterrupt
        if self.stopping.is_set():
            raise KeyboardInterrupt
        self.events.add("answered", id=self.asked)

    def reply(self, answer: str = "", choice: int = 0) -> None:
        self.answer, self.choice = answer, choice
        self.answered.set()

    def stop(self) -> None:
        self.stopping.set()
        self.answered.set()


class Session:
    """One page, one laptop, at most one run at a time."""

    def __init__(self, out_dir: str | Path | None = None, lab: visit.Lab | None = None,
                 wait_seconds: float = 180):
        self.out = Path(out_dir) if out_dir else visit.AGENT_DIR / "field-report"
        self.lab = lab or visit.Lab()
        self.wait_seconds = wait_seconds
        self.events = Events()
        # One line before anything happens, so the page's first poll answers at once and paints the
        # state instead of sitting blank for a whole long-poll while nothing is running.
        self.events.add("ready")
        self.console = WebConsole(self.events)
        self.visit: visit.Visit | None = None
        self.thread: threading.Thread | None = None
        self.busy = threading.Lock()

    # -- state the page draws beside the transcript ----------------------------------------

    def state(self) -> dict:
        running = bool(self.thread and self.thread.is_alive())
        visited = self.visit
        return {"running": running,
                "serial": (visited.serial if visited else None) or "",
                "sheet": {row: value for row, value in (visited.sheet if visited else {}).items() if value},
                "findings": [{"level": found.level, "text": found.text, "fix": found.fix}
                             for found in (visited.findings if visited else [])],
                "reports": sorted(path.name for path in self.out.glob("*.md")),
                "backups": sorted(path.name for path in self.out.glob("*.tsv"))}

    # -- the wizard ------------------------------------------------------------------------

    def start(self) -> bool:
        """The whole visit, on a worker thread. False when one is already running."""
        if not self.busy.acquire(blocking=False):
            return False
        try:
            if self.thread and self.thread.is_alive():
                return False
            self.console.stopping.clear()
            self.out.mkdir(parents=True, exist_ok=True)
            self.visit = self.new_visit()
            self.thread = threading.Thread(target=self._run, args=(self.visit,), daemon=True)
            self.thread.start()
            return True
        finally:
            self.busy.release()

    def _run(self, visited: visit.Visit) -> None:
        try:
            code = visited.run()
        except Exception as exc:  # noqa: BLE001 - the page must hear about it, not the void
            self.events.add("finding", level="bad", text=f"الصفحة وقعت: {exc!r}", fix="ابعت الرسالة دي للفريق")
        else:
            self.events.add("done", code=code)

    def new_visit(self) -> visit.Visit:
        return visit.Visit(console=self.console, lab=self.lab, out_dir=self.out,
                           wait_seconds=self.wait_seconds)

    def stop(self) -> None:
        self.console.stop()

    # -- one step at a time, when no wizard is running --------------------------------------

    def tool(self, name: str, form: dict) -> dict:
        """A single step against one address, so a step that failed can be repeated alone."""
        if self.thread and self.thread.is_alive():
            return {"error": "الزيارة شغالة دلوقتي: استنى لما تخلص، أو أوقفها"}
        # `ip:port` as the wizard's own "type the address" step accepts it, so the simulator and a
        # terminal on an unusual port are reachable from here too.
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

    def _tool_netcheck(self, host: str, form: dict) -> dict:
        return {"network": probe.laptop_network(host, ping_count=int(form.get("count") or 10))}

    def _tool_scan(self, host: str, form: dict) -> dict:
        cidr = (form.get("cidr") or "").strip()
        if not cidr:
            addresses = probe.lan_networks()
            if not addresses:
                return {"error": "اللابتوب مش على شبكة"}
            cidr = addresses[0][1]
        return {"cidr": cidr, "found": probe.scan(cidr, out=lambda line: None)}

    def _tool_zk_info(self, host: str, form: dict) -> dict:
        return {"device": probe.zk_summary(host, int(form.get("port") or 4370),
                                           int(form.get("comm_key") or 0),
                                           bool(form.get("udp")), timeout=8)}

    def _tool_backup(self, host: str, form: dict) -> dict:
        port, key, udp = int(form.get("port") or 4370), int(form.get("comm_key") or 0), bool(form.get("udp"))
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
        port, key, udp = int(form.get("port") or 4370), int(form.get("comm_key") or 0), bool(form.get("udp"))
        summary = probe.zk_summary(host, port, key, udp, timeout=8)
        if "error" in summary:
            return {"error": summary["error"]}
        serial = str(summary.get("serial") or "")
        if not cfg.SERIAL.match(serial):
            return {"error": f"الجهاز رد بسيريال السيستم مش هيقبله: {serial!r}"}
        visited = self.new_visit()
        visited.serial, visited.zk_link = serial, (host, port, key, udp)
        self.out.mkdir(parents=True, exist_ok=True)
        field = "status" if form.get("in_out_field") == "status" else "punch"
        visited.send_twice(visited.write_zk_agent_config(serial, f"{serial}-zk-tool.sqlite3", field))
        return {"serial": serial, "findings": [{"level": f.level, "text": f.text, "fix": f.fix}
                                               for f in visited.findings]}


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
                session.stop()
                return self._json({"stopping": True})
            if route.path == "/answer":
                session.console.reply(str(form.get("value") or ""), int(form.get("choice") or 0))
                return self._json({"taken": True})
            if route.path.startswith("/tool/"):
                return self._json(session.tool(route.path[len("/tool/"):], form))
            return self._json({"error": "not found"}, 404)

    return Handler


def serve(session: Session, token: str, host: str = "127.0.0.1", port: int = DEFAULT_PORT):
    """Built and returned, not started: the caller owns the thread, as capture.serve leaves it."""
    server = http.server.ThreadingHTTPServer((host, port), make_handler(session, token, session.out))
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
    server = serve(session, token, host, port)
    address = f"http://{host}:{server.server_address[1]}/?t={token}"
    out(f"صفحة الزيارة: {address}")
    out("سيبها مفتوحة. لما تخلص، اقفل الأمر ده بـ Ctrl-C.")
    if open_browser:
        threading.Thread(target=webbrowser.open, args=(address,), daemon=True).start()
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        out("")
    finally:
        session.stop()
        server.shutdown()
        server.server_close()
    return 0


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
    </section>
    <section>
      <h2>أدوات: خطوة واحدة لوحدها</h2>
      <div class="tools">
        <label>IP <input id="host" size="14" placeholder="192.168.1.201" inputmode="decimal"></label>
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

const close = () => { $("q").className = "idle"; $("qbody").textContent = ""; };
const post = (path, body) => fetch(path + "?t=" + encodeURIComponent(TOKEN),
  { method: "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify(body || {}) })
  .then((answer) => answer.json());
const send = (body) => { close(); return post("answer", body); };

function paint(state) {
  $("status").textContent = state.running
    ? "شغالة" + (state.serial ? " \\u00b7 " + state.serial : "")
    : "مش شغالة";
  $("start").disabled = state.running;
  const sheet = $("sheet");
  sheet.textContent = "";
  for (const [row, value] of Object.entries(state.sheet || {})) {
    const line = sheet.insertRow();
    line.insertCell().textContent = row;
    const cell = line.insertCell();
    cell.className = "num";
    cell.textContent = value;
  }
  const files = [].concat(state.reports || [], state.backups || []);
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

$("start").onclick = () => post("start");
$("halt").onclick = () => post("stop");
document.querySelectorAll("[data-tool]").forEach((button) => {
  button.onclick = async () => {
    const name = button.dataset.tool;
    $("tool").textContent = "…" + button.textContent;
    const answer = await post("tool/" + name, { host: $("host").value.trim() });
    $("tool").textContent = "";
    const block = document.createElement("pre");
    block.style.whiteSpace = "pre-wrap";
    block.style.margin = "0";
    block.dir = "ltr";
    block.textContent = JSON.stringify(answer, null, 1);
    $("tool").appendChild(block);
    if (answer.findings) answer.findings.forEach((found) => render({ kind: "finding", ...found }));
  };
});
poll();
</script>
</html>
"""
