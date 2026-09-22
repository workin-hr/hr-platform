"""The site-visit kit: find the terminals on a LAN and read what each one is, without changing any.

Scanning a customer's network is something to ask permission for first; the runbook says so.
Every probe here is a read: a TCP connect, a ZK CONNECT/EXIT, an HTTP GET, an ICMP echo, and two
files the kernel keeps about this laptop's own link.
"""
from __future__ import annotations

import ipaddress
import json
import re
import time
import socket
import struct
import urllib.error
import urllib.request
from concurrent.futures import ThreadPoolExecutor
from datetime import datetime
from pathlib import Path

from . import capture
from . import zk4370 as zk

ZK_PORT = 4370
PORTS = {ZK_PORT: "ZKTeco 4370", 80: "HTTP", 443: "HTTPS", 8000: "Hikvision SDK", 8080: "HTTP alt",
         37777: "Dahua", 5010: "Anviz"}


def _open(ip: str, port: int, timeout: float, retry_transient: bool = False) -> bool:
    """A TCP connect, answered or not.

    `retry_transient` off by default because a scan asks this of every port of every address on a
    /22 and a closed port is the normal answer. On one address the operator typed, it is on: this
    check runs *before* ZkClient.connect(), so without it a lost ARP exchange makes a terminal that
    is there vanish from the answer, which is the failure the client's own retry exists to stop."""
    for attempt in range(zk.CONNECT_ATTEMPTS if retry_transient else 1):
        try:
            with socket.create_connection((ip, port), timeout):
                return True
        except OSError as exc:
            if exc.errno not in zk.TRANSIENT_CONNECT_ERRNOS:
                return False
        if attempt + 1 < (zk.CONNECT_ATTEMPTS if retry_transient else 1):
            time.sleep(zk.CONNECT_RETRY_SECONDS)
    return False


def zk_udp_answers(ip: str, port: int = 4370, timeout: float = 1.0) -> bool:
    """A CONNECT over UDP, answered or not. Followed by nothing: the terminal drops the session itself."""
    packet_body = struct.pack("<4H", zk.CMD_CONNECT, 0, 0, zk.USHRT_MAX - 1)
    packet = struct.pack("<4H", zk.CMD_CONNECT, zk.checksum(packet_body), 0, 0)
    with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as sock:
        sock.settimeout(timeout)
        try:
            sock.sendto(packet, (ip, port))
            data, _ = sock.recvfrom(1024)
        except OSError:
            return False
    return len(data) >= 8 and struct.unpack("<H", data[:2])[0] in (zk.CMD_ACK_OK, zk.CMD_ACK_UNAUTH)


def _http(url: str, timeout: float) -> tuple[int | None, dict, bytes]:
    try:
        with urllib.request.urlopen(urllib.request.Request(url, headers={"User-Agent": "workin-probe"}), timeout=timeout) as r:
            return r.status, dict(r.headers.items()), r.read(4096)
    except urllib.error.HTTPError as exc:
        return exc.code, dict((exc.headers or {}).items()), b""
    except Exception:  # noqa: BLE001 - a probe that fails is an answer, not an error
        return None, {}, b""


def zk_summary(ip: str, port: int = 4370, comm_key: int = 0, udp: bool = False, timeout: float = 5.0) -> dict:
    try:
        with zk.ZkClient(ip, port, timeout, comm_key, udp) as client:
            sizes = client.sizes()
            return {"serial": client.serial_number(), "firmware": client.firmware_version(),
                    "platform": client.option("~Platform"), "device_name": client.option("~DeviceName"),
                    "mac": client.option("MAC"), "users": sizes.users, "records": sizes.records,
                    "records_capacity": sizes.records_capacity, "fingers": sizes.fingers, "faces": sizes.faces,
                    "device_time": client.device_time().isoformat(sep=" "),
                    "laptop_time": datetime.now().replace(microsecond=0).isoformat(sep=" "),
                    "transport": "udp" if udp else "tcp"}
    except zk.ZkAuthError:
        return {"comm_key_required": True, "transport": "udp" if udp else "tcp"}
    except zk.ZkError as exc:
        return {"error": str(exc), "transport": "udp" if udp else "tcp"}


def http_fingerprint(ip: str, port: int, timeout: float) -> dict:
    scheme = "https" if port == 443 else "http"
    base = f"{scheme}://{ip}:{port}"
    status, headers, body = _http(base + "/ISAPI/System/deviceInfo", timeout)
    authenticate = headers.get("WWW-Authenticate", "")
    if status in (200, 401) and ("Digest" in authenticate or b"DeviceInfo" in body):
        return {"guess": "hikvision (ISAPI)", "status": status, "realm": authenticate[:80]}
    status, headers, body = _http(base + "/cgi-bin/magicBox.cgi?action=getDeviceType", timeout)
    if status == 401 or (status == 200 and body.startswith(b"type=")):
        return {"guess": "dahua (CGI)", "status": status}
    status, headers, body = _http(base + "/", timeout)
    if status is None:
        return {}
    text = body.decode("utf-8", "replace")
    title = text[text.lower().find("<title>") + 7:text.lower().find("</title>")] if "<title>" in text.lower() else ""
    guess = "zkteco web" if any(word in text for word in ("ZKTeco", "ZKSoftware", "iClock")) else "unknown http"
    return {"guess": guess, "status": status, "server": headers.get("Server", ""), "title": title.strip()[:80]}


def probe_host(ip: str, timeout: float = 0.6, udp_probe: bool = True,
               retry_transient: bool = False) -> dict | None:
    # The retry is for the address, not for each port: EHOSTUNREACH is an ARP failure, which is per
    # address, and `_open` returns instantly on it. Asked of all seven ports it would spend 28 s on
    # an address that is simply not there, so it is asked of the one port this tool exists for --
    # which is what keeps the cost of an absent host to the four seconds the runbook promises.
    open_ports = [port for port in PORTS
                  if _open(ip, port, timeout, retry_transient and port == ZK_PORT)]
    udp = udp_probe and zk_udp_answers(ip, timeout=timeout)
    if not open_ports and not udp:
        return None
    found: dict = {"ip": ip, "open_ports": {port: PORTS[port] for port in open_ports}}
    if 4370 in open_ports:
        found["zk_tcp"] = zk_summary(ip, timeout=max(timeout * 5, 3))
    elif udp:
        found["zk_udp"] = zk_summary(ip, udp=True, timeout=max(timeout * 5, 3))
    for port in (80, 8080, 443):
        if port in open_ports:
            found[f"http_{port}"] = http_fingerprint(ip, port, max(timeout * 3, 2))
    return found


VIRTUAL_INTERFACES = ("lo", "docker", "br-", "veth", "virbr", "tun", "wg", "ppp", "tailscale", "zt")


def lan_networks(ip_output: str | None = None) -> list[tuple[str, str]]:
    """This computer's addresses on a real LAN, each with its network: what a terminal is told as
    its server address, and what a scan covers. Every interface, not only the default route's: at
    a site the laptop is often on the customer's LAN by cable and on a phone's hotspot for the
    internet, and the terminal can reach only the first."""
    if ip_output is None:
        import subprocess
        try:
            ip_output = subprocess.run(["ip", "-o", "-4", "addr", "show"], capture_output=True, text=True,
                                       timeout=5).stdout
        except (OSError, subprocess.SubprocessError):
            ip_output = ""
    found = []
    for line in ip_output.splitlines():
        fields = line.split()
        if len(fields) < 4 or fields[2] != "inet" or fields[1].startswith(VIRTUAL_INTERFACES):
            continue
        interface = ipaddress.ip_interface(fields[3])
        # A link-local address means DHCP failed: offering it as a scan range, or as the server
        # address to type into a terminal, sends the operator after a network that is not there.
        # `inet A peer B/32` is point-to-point, and its /32 is not a LAN either.
        if interface.ip.is_link_local or (interface.network.prefixlen == 32 and "peer" in fields):
            continue
        found.append((str(interface.ip), str(interface.network)))
    if found:
        return found
    try:
        with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as sock:
            sock.connect(("10.255.255.255", 1))
            address = sock.getsockname()[0]
    except OSError:
        return []
    return [] if address.startswith("127.") else [(address, str(ipaddress.ip_interface(f"{address}/24").network))]


NEIGHBOUR_STATES = ("REACHABLE", "STALE", "DELAY", "PROBE", "FAILED", "INCOMPLETE", "NOARP", "PERMANENT")

# /proc/net/wireless's data columns after the interface name, in the kernel's own order. Its header
# spells them: status | link level noise | nwid crypt frag retry misc | beacon. `misc` is the
# counter that actually moves on a marginal link; `missed_beacon` stays 0 on several drivers, so a
# reader that watches only the last column concludes the link is healthy while packets are dropping.
WIRELESS_COLUMNS = ("status", "link", "level", "noise", "nwid", "crypt", "frag", "retry", "misc",
                    "missed_beacon")


def arp_state(ip: str, neigh_output: str | None = None) -> str | None:
    """What the kernel currently thinks of that address's hardware address, or None when it holds no
    entry and when there is no `ip` command to ask.

    This is the fact that separates a terminal that is refusing from a laptop that cannot ask. On
    Wi-Fi one lost ARP exchange is enough to drive the entry to FAILED, and a FAILED entry answers
    every connect with EHOSTUNREACH *instantly*, for a terminal that is on the wall and answering
    -- so a visit reads two identical "no route to host" errors seconds apart and blames the wall."""
    if neigh_output is None:
        import subprocess
        try:
            neigh_output = subprocess.run(["ip", "neigh", "show", ip], capture_output=True, text=True,
                                          timeout=5).stdout
        except (OSError, subprocess.SubprocessError):
            return None
    for line in neigh_output.splitlines():
        fields = line.split()
        if not fields or fields[0] != ip:
            continue
        # The state is the last field, but `router`, `proxy` and `extern_learn` can follow the
        # address, so match the vocabulary rather than the position.
        for field in reversed(fields):
            if field in NEIGHBOUR_STATES:
                return field
    return None


def egress(ip: str, route_output: str | None = None) -> str | None:
    """The interface the kernel would send to `ip` through, or None when it cannot be asked.

    Which interface carries the traffic decides whether this laptop's Wi-Fi is evidence at all: on a
    visit run over the customer's Ethernet with a phone hotspot also up, the hotspot's signal says
    nothing about the link that failed, and a remedy naming it sends the operator to the wrong
    router."""
    if route_output is None:
        import subprocess
        try:
            route_output = subprocess.run(["ip", "route", "get", ip], capture_output=True, text=True,
                                          timeout=5).stdout
        except (OSError, subprocess.SubprocessError):
            return None
    fields = route_output.split()
    return fields[fields.index("dev") + 1] if "dev" in fields[:-1] else None


def wifi_link(proc_wireless: str | None = None, interface: str | None = None) -> dict | None:
    """This laptop's Wi-Fi link as the driver reports it, or None when it has no Wi-Fi -- and on
    Windows, where the file does not exist. `level` is dBm: about -50 is a strong link, -70 is
    where a site visit starts losing exchanges. With `interface`, only that one: a laptop can hold
    two wireless links up, and only the one carrying the traffic is evidence."""
    if proc_wireless is None:
        try:
            proc_wireless = Path("/proc/net/wireless").read_text(encoding="utf-8")
        except OSError:
            return None
    for line in proc_wireless.splitlines()[2:]:
        name, _, rest = line.partition(":")
        fields = rest.split()
        if not name.strip() or len(fields) < len(WIRELESS_COLUMNS):
            continue
        if interface is not None and name.strip() != interface:
            continue
        link = {"interface": name.strip()}
        for column, value in zip(WIRELESS_COLUMNS, fields):
            link[column] = int(float(value.rstrip(".")))
        return link
    return None


def ping_facts(ip: str, count: int = 10, ping_output: str | None = None) -> dict:
    """Loss, duplicates and the spread of round-trip times to one address.

    Duplicates are why this exists next to a loss count: a repeated or bridged Wi-Fi answers every
    echo and still loses ARP, so `0% packet loss` alone reads as a healthy network. A duplicate
    reply, or a round trip that ranges over two orders of magnitude, is the fabric saying otherwise.

    Unparsed output is reported as unavailable rather than as a healthy result: ping's summary
    differs between platforms (Windows counts with -n), and a wrong zero here would send an
    operator back to the terminal."""
    if ping_output is None:
        import subprocess
        try:
            ping_output = subprocess.run(["ping", "-c", str(count), "-W", "1", ip], capture_output=True,
                                         text=True, timeout=count + 5).stdout
        except (OSError, subprocess.SubprocessError) as exc:
            return {"unavailable": f"ping did not run: {exc}"}
    summary = re.search(r"(\d+) packets transmitted, (\d+) (?:packets )?received,"
                        r"(?: \+(\d+) duplicates,)?.*?([\d.]+)% packet loss", ping_output, re.S)
    if not summary:
        return {"unavailable": "ping's summary was not in a shape this reads"}
    facts = {"transmitted": int(summary.group(1)), "received": int(summary.group(2)),
             "duplicates": int(summary.group(3) or 0), "loss_percent": float(summary.group(4))}
    times = re.search(r"min/avg/max/mdev = ([\d.]+)/([\d.]+)/([\d.]+)/([\d.]+)", ping_output)
    if times:
        facts["rtt_ms"] = {name: float(times.group(index)) for index, name in
                           enumerate(("min", "avg", "max", "mdev"), 1)}
    return facts


def laptop_network(ip: str | None = None, ping_count: int = 10) -> dict:
    """Everything this laptop can say about its own side of the link, and about one address on it.

    A visit that cannot reach a terminal reports this beside the terminal's error, so the operator
    reads which of the two was at fault instead of filing the laptop's Wi-Fi as a device finding."""
    facts: dict = {"addresses": [{"ip": address, "network": network} for address, network in lan_networks()]}
    route = egress(ip) if ip else None
    facts["interface"] = route
    # Wi-Fi facts only for the interface that reaches this address. Asked without one, any wireless
    # link is what the laptop has; asked about a target reached over Ethernet, there is nothing
    # wireless to report and saying so is the answer.
    facts["wifi"] = wifi_link(interface=route) if route else (None if ip else wifi_link())
    if ip and route and facts["wifi"] is None:
        facts["wifi"] = {"unavailable": f"الجهاز بيتوصل عن طريق {route}، وده مش واي فاي"}
    if ip:
        facts["target"] = ip
        facts["arp"] = arp_state(ip)
        facts["ping"] = ping_facts(ip, ping_count)
    return facts


def scan(cidr: str, timeout: float = 0.6, workers: int = 96, out=print) -> list[dict]:
    network = ipaddress.ip_network(cidr, strict=False)
    if network.num_addresses > 1024:
        raise ValueError(f"{cidr} is {network.num_addresses} addresses; scan a /22 or smaller")
    hosts = [str(host) for host in network.hosts()]
    out(f"scanning {len(hosts)} addresses on {cidr} for ports {sorted(PORTS)} and ZK over UDP ...")
    results = []
    with ThreadPoolExecutor(max_workers=workers) as pool:
        for found in pool.map(lambda ip: probe_host(ip, timeout), hosts):
            if found:
                results.append(found)
                out(json.dumps(found, ensure_ascii=False))
    return results


def backup_attendance(ip: str, path: str, port: int = 4370, comm_key: int = 0, udp: bool = False) -> int:
    """Every record on the terminal, to a local TSV, before anything about the terminal is changed."""
    with zk.ZkClient(ip, port, 15, comm_key, udp) as client:
        records = client.attendance()
    with open(path, "w", encoding="utf-8") as handle:
        handle.write("# user_id\ttimestamp\tstatus(pyzk)\tpunch(pyzk)\trecord_size\n")
        for record in records:
            handle.write(f"{record.user_id}\t{record.timestamp:%Y-%m-%d %H:%M:%S}\t{record.status}\t{record.punch}\t{record.record_size}\n")
    return len(records)


def hik_summary(device, days: int = 7, dump_path: str | None = None) -> dict:
    """deviceInfo, and a count of the terminal's events by minor code, so the attendance codes this
    model uses can be read off a real log rather than assumed. dump_path gets each event's structure
    (capture.structure): codes, times and in/out, never a name, card number, employee or picture."""
    from datetime import timedelta
    from collections import Counter
    from .sources import HikvisionSource
    source = HikvisionSource(device)
    info = source.describe()
    end = datetime.now()
    events = source.events(end - timedelta(days=days), end)
    if dump_path:
        with open(dump_path, "w", encoding="utf-8") as handle:
            json.dump([capture.structure(event) for event in events], handle, indent=1, ensure_ascii=False)
    by_minor = Counter(str(event.get("minor")) for event in events)
    with_employee = Counter(str(event.get("minor")) for event in events if event.get("employeeNoString") or event.get("employeeNo"))
    return {"serial": info.serial, "model": info.model, "firmware": info.firmware, "events": len(events),
            "events_by_minor": dict(by_minor), "events_with_employee_by_minor": dict(with_employee),
            "attendance_minors_configured": list(device.attendance_minors)}
