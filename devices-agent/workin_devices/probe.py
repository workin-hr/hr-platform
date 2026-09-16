"""The site-visit kit: find the terminals on a LAN and read what each one is, without changing any.

Scanning a customer's network is something to ask permission for first; the runbook says so.
Every probe here is a read: a TCP connect, a ZK CONNECT/EXIT, an HTTP GET.
"""
from __future__ import annotations

import ipaddress
import json
import socket
import struct
import urllib.error
import urllib.request
from concurrent.futures import ThreadPoolExecutor
from datetime import datetime

from . import zk4370 as zk

PORTS = {4370: "ZKTeco 4370", 80: "HTTP", 443: "HTTPS", 8000: "Hikvision SDK", 8080: "HTTP alt",
         37777: "Dahua", 5010: "Anviz"}


def _open(ip: str, port: int, timeout: float) -> bool:
    try:
        with socket.create_connection((ip, port), timeout):
            return True
    except OSError:
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


def probe_host(ip: str, timeout: float = 0.6, udp_probe: bool = True) -> dict | None:
    open_ports = [port for port in PORTS if _open(ip, port, timeout)]
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
    model uses can be read off a real log rather than assumed. Raw events go to dump_path."""
    from datetime import timedelta
    from collections import Counter
    from .sources import HikvisionSource
    source = HikvisionSource(device)
    info = source.describe()
    end = datetime.now()
    events = source.events(end - timedelta(days=days), end)
    if dump_path:
        with open(dump_path, "w", encoding="utf-8") as handle:
            json.dump(events, handle, indent=1, ensure_ascii=False)
    by_minor = Counter(str(event.get("minor")) for event in events)
    with_employee = Counter(str(event.get("minor")) for event in events if event.get("employeeNoString") or event.get("employeeNo"))
    return {"serial": info.serial, "model": info.model, "firmware": info.firmware, "events": len(events),
            "events_by_minor": dict(by_minor), "events_with_employee_by_minor": dict(with_employee),
            "attendance_minors_configured": list(device.attendance_minors)}
