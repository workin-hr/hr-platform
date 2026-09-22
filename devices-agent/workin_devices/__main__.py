"""workin-devices: the agent, the site-visit kit and the lab simulators, one command each.

  agent       run | once | doctor | import-usb
  site visit  web (the visit and every step, in a browser) | visit (the same, in this terminal)
              scan | zk-info | hik-info | capture
  lab         sim-zk | sim-push | sim-hik | sim-usb
"""
from __future__ import annotations

import argparse
import json
import logging
import os
import signal
import sys
import time
from datetime import datetime

from . import VERSION


def _config(args):
    from . import config as cfg
    try:
        return cfg.load(args.config, allow_plain_http=args.allow_plain_http)
    except cfg.ConfigError as exc:
        sys.exit(f"config error: {exc}")


def _gateway(config):
    from . import gateway as gw
    return gw.Gateway(config.server_url, config.token, ca_file=config.ca_file,
                      insecure_skip_tls_verify=config.insecure_skip_tls_verify)


def cmd_run(args):
    from . import agent
    config = _config(args)
    logging.getLogger("workin_devices").info("workin-devices %s -> %s, %d terminal(s), every %ss", VERSION,
                                             config.server_url, len(config.devices), config.poll_interval_seconds)
    agent.run_forever(config)


def cmd_once(args):
    from . import agent, gateway as gw
    config = _config(args)
    try:
        results = agent.run_once(config)
    except gw.Unauthorized as exc:
        sys.exit(f"unauthorized: {exc}")
    for result in results:
        print(f"  {result.serial:<24} reachable={result.reachable} read={result.read} new={result.new} "
              f"stored={result.stored} pending={result.pending}" + (f"  ERROR {result.error}" if result.error else ""))
    return 0 if all(result.healthy for result in results) else 1


def cmd_doctor(args):
    """Reads every configured terminal and says what it is. Delivers nothing, changes nothing."""
    from .sources import SourceError, open_source
    config = _config(args)
    bad = 0
    for device in config.devices:
        try:
            with open_source(device, config.in_out_field) as source:
                description = source.describe()
                punches = source.read()
            mismatch = device.kind != "file" and description.serial and description.serial != device.serial
            print(f"  {device.serial:<24} {device.kind:<10} OK  reports serial={description.serial!r} "
                  f"model={description.model!r} firmware={description.firmware!r} records={description.records} "
                  f"capacity={description.record_capacity} clock={description.device_time} read={len(punches)}"
                  + ("  SERIAL MISMATCH" if mismatch else ""))
            for punch in punches[-3:]:
                print(f"      last: PIN {punch.pin} {punch.local_time} in/out={punch.status} verify={punch.verify}")
            bad |= bool(mismatch)
        except SourceError as exc:
            print(f"  {device.serial:<24} {device.kind:<10} FAIL {exc}")
            bad = 1
    return bad


def cmd_import_usb(args):
    from . import gateway as gw, usb
    config = _config(args)
    with open(args.file, "rb") as handle:
        content = handle.read()
    try:
        totals = usb.import_file(_gateway(config), args.serial, content, config.batch_size)
    except (gw.Unauthorized, gw.NotRegistered, gw.Refused, gw.Retryable, gw.TooLarge) as exc:
        sys.exit(f"import failed: {exc}")
    print(json.dumps(totals))


def cmd_scan(args):
    from . import probe
    results = probe.scan(args.cidr, timeout=args.timeout)
    os.makedirs(args.out, exist_ok=True)
    path = os.path.join(args.out, f"scan-{datetime.now():%Y%m%d-%H%M%S}.json")
    with open(path, "w", encoding="utf-8") as handle:
        json.dump(results, handle, indent=2, ensure_ascii=False)
    print(f"{len(results)} host(s) with device ports -> {path}")


def cmd_zk_info(args):
    from . import probe
    summary = probe.zk_summary(args.host, args.port, args.comm_key, args.udp, timeout=args.timeout)
    print(json.dumps(summary, indent=2, ensure_ascii=False))
    if args.backup and "error" not in summary and not summary.get("comm_key_required"):
        count = probe.backup_attendance(args.host, args.backup, args.port, args.comm_key, args.udp)
        print(f"backed up {count} record(s) -> {args.backup}")
    return 1 if "error" in summary or summary.get("comm_key_required") else 0


def cmd_hik_info(args):
    from . import probe
    from .config import DeviceConfig
    with open(args.password_file, encoding="utf-8") as handle:
        password = handle.read().strip()
    device = DeviceConfig(serial="probe", kind="hikvision", host=args.host, https=args.https,
                          port=args.port or (443 if args.https else 80), username=args.username, password=password,
                          timeout_seconds=args.timeout)
    print(json.dumps(probe.hik_summary(device, args.days, args.dump), indent=2, ensure_ascii=False))


def cmd_capture(args):
    from . import capture, probe
    host, _, port = args.listen.rpartition(":")
    server = capture.serve(host or "0.0.0.0", int(port), args.out, args.upstream, args.host_header)
    print("=" * 72)
    print(f"  recording on {args.listen} -> {os.path.abspath(args.out)}/<serial>/")
    print(f"  {'forwarding to ' + args.upstream + ' as Host ' + str(args.host_header) if args.upstream else 'standalone: answering like an unknown receiver'}")
    for address, _network in probe.lan_networks():
        print(f"  on the terminal: Server Address = {address}   Server Port = {port}")
    print("  Ctrl-C to stop")
    print("=" * 72, flush=True)
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        server.shutdown()


def cmd_visit(args):
    from . import visit
    return visit.Visit(out_dir=args.out).run()


def cmd_web(args):
    from . import web
    return web.run(args.host, args.port, args.out, not args.no_open)


def cmd_sim_zk(args):
    from .sim.zk4370 import Emulator, Terminal, populate
    from datetime import timedelta
    terminal = Terminal(serial=args.serial, comm_key=args.comm_key, record_size=args.record_size,
                        clock_offset=timedelta(minutes=args.clock_offset_minutes))
    pins = _pins(args.pins)
    populate(terminal, pins, args.days)
    emulator = Emulator(terminal, args.host, args.port).start()
    print(f"pretend ZKTeco terminal {args.serial} on {args.host}:{emulator.port} (TCP and UDP), "
          f"{len(terminal.records)} records for PINs {','.join(pins)}", flush=True)
    try:
        index = 0
        while True:
            time.sleep(args.live_every if args.live_every > 0 else 3600)
            if args.live_every > 0:
                record = terminal.add_punch(pins[index % len(pins)], in_out=index % 2)
                index += 1
                print(f"  {record.when:%H:%M:%S} new scan PIN {record.user_id}", flush=True)
    except KeyboardInterrupt:
        emulator.stop()
        if terminal.write_attempts:
            print(f"WARNING: something tried to change the terminal: {terminal.write_attempts}")


def cmd_sim_push(args):
    from .sim import adms
    terminal = adms.PushTerminal(args.server, args.serial, args.host_header)
    pins = _pins(args.pins)
    if args.live:
        adms.live(terminal, pins, args.every, args.live)
        return 0
    names = adms.SCENARIOS if args.scenario == "all" else args.scenario.split(",")
    print(f"pretend push terminal {args.serial} -> {args.server}" + (f" (Host: {args.host_header})" if args.host_header else ""))
    results = [adms.run_scenario(terminal, name, pins) for name in names]
    return 0 if all(results) else 1


def cmd_sim_hik(args):
    from .sim.hikvision import HikTerminal, serve
    with open(args.password_file, encoding="utf-8") as handle:
        password = handle.read().strip()
    terminal = HikTerminal(serial=args.serial, username=args.username, password=password)
    terminal.populate(_pins(args.pins), args.days)
    server = serve(terminal, args.host, args.port)
    print(f"pretend Hikvision terminal {args.serial} on http://{args.host}:{server.server_address[1]} "
          f"({len(terminal.events)} events)", flush=True)
    signal.pause() if hasattr(signal, "pause") else time.sleep(10 ** 9)


def cmd_sim_usb(args):
    from .sim import adms
    lines = adms.backlog(_pins(args.pins), args.days)
    with open(args.out, "w", encoding="ascii", newline="\r\n") as handle:
        for line in lines:
            pin, rest = line.split("\t", 1)
            handle.write(f"{pin:>9}\t{rest}\n")
    print(f"{len(lines)} lines -> {args.out}")


def _pins(text: str) -> list[str]:
    return [pin.strip() for pin in text.split(",") if pin.strip()]


def main(argv=None):
    parser = argparse.ArgumentParser(prog="workin-devices", description=__doc__,
                                     formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--verbose", action="store_true")
    parser.add_argument("--log-file", help="also write the log here, rotated at 5 MB (a Windows task has no console)")
    commands = parser.add_subparsers(dest="command", required=True)

    def with_config(name, handler, help_text):
        sub = commands.add_parser(name, help=help_text)
        sub.add_argument("--config", default="agent.toml")
        sub.add_argument("--allow-plain-http", action="store_true",
                         help="allow an http:// server_url that is not loopback (a lab on a trusted LAN only)")
        sub.set_defaults(handler=handler)
        return sub

    with_config("run", cmd_run, "run the agent until stopped")
    with_config("once", cmd_once, "one pass over every terminal, then exit")
    with_config("doctor", cmd_doctor, "read every terminal and report; delivers nothing")
    sub = with_config("import-usb", cmd_import_usb, "import a USB export (attlog.dat) for one registered terminal")
    sub.add_argument("--serial", required=True)
    sub.add_argument("--file", required=True)

    sub = commands.add_parser("scan", help="find terminals on a LAN (ask the customer first)")
    sub.add_argument("--cidr", required=True)
    sub.add_argument("--timeout", type=float, default=0.6)
    sub.add_argument("--out", default="field-report")
    sub.set_defaults(handler=cmd_scan)

    sub = commands.add_parser("zk-info", help="read a ZKTeco terminal's identity and counts; optionally back up its log")
    sub.add_argument("--host", required=True)
    sub.add_argument("--port", type=int, default=4370)
    sub.add_argument("--comm-key", type=int, default=0)
    sub.add_argument("--udp", action="store_true")
    sub.add_argument("--timeout", type=float, default=8.0)
    sub.add_argument("--backup", help="write every attendance record to this TSV file")
    sub.set_defaults(handler=cmd_zk_info)

    sub = commands.add_parser("hik-info", help="read a Hikvision terminal's identity and event codes")
    sub.add_argument("--host", required=True)
    sub.add_argument("--port", type=int)
    sub.add_argument("--https", action="store_true")
    sub.add_argument("--username", required=True)
    sub.add_argument("--password-file", required=True)
    sub.add_argument("--days", type=int, default=7)
    sub.add_argument("--dump", help="write each event's structure (codes, times, in/out; no name, employee, card "
                                     "or picture) to this JSON file")
    sub.add_argument("--timeout", type=float, default=10.0)
    sub.set_defaults(handler=cmd_hik_info)

    sub = commands.add_parser("capture", help="record what a terminal sends, never biometrics (site visit)")
    sub.add_argument("--listen", default="0.0.0.0:8081")
    sub.add_argument("--upstream", help="forward to the platform, e.g. http://127.0.0.1:80")
    sub.add_argument("--host-header", help="the name the platform's device receiver answers on, e.g. devices.localhost")
    sub.add_argument("--out", default="captures")
    sub.set_defaults(handler=cmd_capture)

    sub = commands.add_parser("visit", help="the whole site visit, step by step, ending in a report (Mode A: the lab)")
    sub.add_argument("--out", help="where the visit's files go (default: devices-agent/field-report)")
    sub.set_defaults(handler=cmd_visit)

    sub = commands.add_parser("web", help="the site visit in a browser, plus every step on its own button")
    sub.add_argument("--host", default="127.0.0.1",
                     help="loopback only; this page drives terminal reads and holds the lab token")
    sub.add_argument("--port", type=int, default=18100)
    sub.add_argument("--out", help="where the visit's files go (default: devices-agent/field-report)")
    sub.add_argument("--no-open", action="store_true", help="do not open a browser; print the address")
    sub.set_defaults(handler=cmd_web)

    sub = commands.add_parser("sim-zk", help="lab: a pretend ZKTeco terminal on port 4370")
    sub.add_argument("--host", default="127.0.0.1")
    sub.add_argument("--port", type=int, default=4370)
    sub.add_argument("--serial", default="SIM-ZK4370-001")
    sub.add_argument("--pins", default="1001,1002,1003")
    sub.add_argument("--days", type=int, default=14)
    sub.add_argument("--record-size", type=int, choices=(8, 16, 40), default=40)
    sub.add_argument("--comm-key", type=int, default=0)
    sub.add_argument("--clock-offset-minutes", type=int, default=0)
    sub.add_argument("--live-every", type=float, default=0, help="add a scan every N seconds")
    sub.set_defaults(handler=cmd_sim_zk)

    sub = commands.add_parser("sim-push", help="lab: a pretend push terminal against the platform's /iclock")
    sub.add_argument("--server", required=True)
    sub.add_argument("--host-header")
    sub.add_argument("--serial", default="SIM-PUSH-001")
    sub.add_argument("--pins", default="1001,1002,1003")
    sub.add_argument("--scenario", default="all", help="all, or a comma list of: " + ",".join(
        __import__("workin_devices.sim.adms", fromlist=["SCENARIOS"]).SCENARIOS))
    sub.add_argument("--live", type=int, default=0, help="instead: send this many live scans")
    sub.add_argument("--every", type=float, default=5)
    sub.set_defaults(handler=cmd_sim_push)

    sub = commands.add_parser("sim-hik", help="lab: a pretend Hikvision terminal (ISAPI)")
    sub.add_argument("--host", default="127.0.0.1")
    sub.add_argument("--port", type=int, default=18190)
    sub.add_argument("--serial", default="SIM-HIK-001")
    sub.add_argument("--username", default="admin")
    sub.add_argument("--password-file", required=True)
    sub.add_argument("--pins", default="1001,1002,1003")
    sub.add_argument("--days", type=int, default=7)
    sub.set_defaults(handler=cmd_sim_hik)

    sub = commands.add_parser("sim-usb", help="lab: write a USB export (attlog.dat) to import")
    sub.add_argument("--out", default="1_attlog.dat")
    sub.add_argument("--pins", default="1001,1002,1003")
    sub.add_argument("--days", type=int, default=5)
    sub.set_defaults(handler=cmd_sim_usb)

    commands.add_parser("version", help="print the version").set_defaults(handler=lambda args: print(VERSION))

    args = parser.parse_args(argv)
    handlers = [logging.StreamHandler()]
    if args.log_file:
        from logging.handlers import RotatingFileHandler
        handlers.append(RotatingFileHandler(args.log_file, maxBytes=5 * 1024 * 1024, backupCount=5, encoding="utf-8"))
    logging.basicConfig(level=logging.DEBUG if args.verbose else logging.INFO,
                        format="%(asctime)s %(levelname)-7s %(message)s", handlers=handlers)
    return args.handler(args) or 0


if __name__ == "__main__":
    sys.exit(main())
