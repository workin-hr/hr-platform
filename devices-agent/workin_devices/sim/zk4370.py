"""A pretend ZKTeco terminal speaking the 4370 binary protocol, over TCP and UDP.

For the lab and the tests: the agent, pyzk and the field probe all talk to it exactly as they
would to a terminal on a branch LAN. It is built from the protocol, not from a capture, so it
proves the agent's logic and the transport -- not that any particular firmware behaves this way.
That is what the site visit is for.

Anything that would change a real terminal (clear the log, set the clock, write a user, restart)
is refused and recorded in `write_attempts`, so a test can assert nothing ever tried.
"""
from __future__ import annotations

import random
import socketserver
import struct
import threading
from dataclasses import dataclass, field
from datetime import datetime, timedelta

from .. import zk4370 as zk

CMD_USERTEMP_RRQ = 9
CMD_ENABLEDEVICE = 1002
CMD_DISABLEDEVICE = 1003
CMD_ACK_UNKNOWN = 0xFFFF
FCT_USER = 5
WRITE_COMMANDS = {
    8: "USER_WRQ", 14: "CLEAR_DATA", 15: "CLEAR_ATTLOG", 18: "DELETE_USER", 19: "DELETE_USERTEMP",
    20: "CLEAR_ADMIN", 31: "UNLOCK", 61: "STARTENROLL", 66: "WRITE_LCD", 202: "SET_TIME",
    1004: "RESTART", 1005: "POWEROFF", 1013: "REFRESHDATA",
}
INLINE_LIMIT = 1000
UDP_PACKET = 1024


@dataclass
class Record:
    user_id: str
    when: datetime
    verify: int = 1
    in_out: int = 0


@dataclass
class Terminal:
    serial: str = "SIM-ZK4370-001"
    firmware: str = "Ver 6.60 Apr 28 2021"
    platform: str = "ZLM60_TFT"
    device_name: str = "K40"
    mac: str = "00:17:61:10:20:30"
    comm_key: int = 0
    record_size: int = 40
    records_capacity: int = 100000
    clock_offset: timedelta = timedelta(0)
    users: list[str] = field(default_factory=list)
    records: list[Record] = field(default_factory=list)
    write_attempts: list[str] = field(default_factory=list)
    commands_seen: list[int] = field(default_factory=list)
    lock: threading.Lock = field(default_factory=threading.Lock)

    def add_punch(self, user_id: str, when: datetime | None = None, in_out: int = 0, verify: int = 1) -> Record:
        record = Record(user_id, (when or datetime.now() + self.clock_offset).replace(microsecond=0), verify, in_out)
        with self.lock:
            self.records.append(record)
            if user_id not in self.users:
                self.users.append(user_id)
        return record

    def attlog_buffer(self) -> bytes:
        with self.lock:
            records = list(self.records)
            users = list(self.users)
        body = bytearray()
        for record in records:
            stamp = zk.encode_time(record.when)
            if self.record_size == 40:
                uid = users.index(record.user_id) + 1
                body += struct.pack("<H24sB4sB8s", uid, record.user_id.encode("ascii"), record.verify, stamp,
                                    record.in_out, b"\x00" * 8)
            elif self.record_size == 16:
                body += struct.pack("<I4sBB2sI", int(record.user_id), stamp, record.verify, record.in_out, b"\x00\x00", 0)
            else:
                uid = users.index(record.user_id) + 1
                body += struct.pack("<HB4sB", uid, record.verify, stamp, record.in_out)
        return struct.pack("<I", len(body)) + bytes(body)

    def user_buffer(self) -> bytes:
        """The 72-byte user format pyzk reads before attendance. Synthetic names, no passwords."""
        with self.lock:
            users = list(self.users)
        body = bytearray()
        for uid, user_id in enumerate(users, start=1):
            body += struct.pack("<HB8s24sIx7sx24s", uid, 0, b"", f"Employee {user_id}".encode("ascii"), 0, b"1",
                                user_id.encode("ascii"))
        return struct.pack("<I", len(body)) + bytes(body)

    def sizes(self) -> bytes:
        with self.lock:
            fields = [0] * 20
            fields[4] = len(self.users)
            fields[6] = len(self.users)
            fields[8] = len(self.records)
            fields[14] = 3000
            fields[15] = 3000
            fields[16] = self.records_capacity
            fields[17] = 3000 - len(self.users)
            fields[18] = 3000 - len(self.users)
            fields[19] = self.records_capacity - len(self.records)
        return struct.pack("<20i", *fields) + struct.pack("<3i", 0, 0, 0)

    def option(self, name: str) -> str | None:
        return {
            "~SerialNumber": self.serial, "~Platform": self.platform, "~DeviceName": self.device_name,
            "MAC": self.mac, "~ZKFPVersion": "10", "ZKFaceVersion": "0", "~ExtendFmt": "0",
            "~UserExtFmt": "0", "FaceFunOn": "0", "CompatOldFirmware": "0",
        }.get(name)


def populate(terminal: Terminal, pins: list[str], days: int, now: datetime | None = None, seed: int = 7) -> None:
    """A realistic log: each PIN arrives around 08:00 and leaves around 17:00 on each past day."""
    rng = random.Random(seed)
    today = (now or datetime.now()).replace(hour=0, minute=0, second=0, microsecond=0)
    for offset in range(days, 0, -1):
        day = today - timedelta(days=offset)
        if day.weekday() == 4:
            continue
        for pin in pins:
            terminal.add_punch(pin, day + timedelta(hours=8, minutes=rng.randint(0, 40), seconds=rng.randint(0, 59)), 0)
            terminal.add_punch(pin, day + timedelta(hours=16, minutes=rng.randint(40, 59), seconds=rng.randint(0, 59)), 1)


class _Session:
    def __init__(self):
        self.id = random.randint(1, 60000)
        self.authenticated = False
        self.buffer = b""


def _packet(code: int, session: int, reply: int, data: bytes = b"") -> bytes:
    unsigned = struct.pack("<4H", code, 0, session, reply) + data
    return struct.pack("<4H", code, zk.checksum(unsigned), session, reply) + data


def handle(terminal: Terminal, session: _Session, packet: bytes, udp: bool) -> list[bytes]:
    """Every answer to one request, in order. Most requests have one; a UDP chunk read has several."""
    command, _, _, reply = struct.unpack("<4H", packet[:8])
    payload = packet[8:]
    terminal.commands_seen.append(command)
    sid = session.id

    def ok(data: bytes = b"") -> list[bytes]:
        return [_packet(zk.CMD_ACK_OK, sid, reply, data)]

    if command == zk.CMD_CONNECT:
        session.authenticated = terminal.comm_key == 0
        return [_packet(zk.CMD_ACK_OK if session.authenticated else zk.CMD_ACK_UNAUTH, sid, reply)]
    if command == zk.CMD_AUTH:
        session.authenticated = payload[:4] == zk.make_commkey(terminal.comm_key, sid)
        return [_packet(zk.CMD_ACK_OK if session.authenticated else zk.CMD_ACK_UNAUTH, sid, reply)]
    if not session.authenticated:
        return [_packet(zk.CMD_ACK_UNAUTH, sid, reply)]
    if command in WRITE_COMMANDS:
        terminal.write_attempts.append(WRITE_COMMANDS[command])
        return [_packet(zk.CMD_ACK_ERROR, sid, reply)]
    if command == zk.CMD_EXIT or command in (CMD_ENABLEDEVICE, CMD_DISABLEDEVICE, zk.CMD_FREE_DATA):
        return ok()
    if command == zk.CMD_GET_VERSION:
        return ok(terminal.firmware.encode("ascii") + b"\x00")
    if command == zk.CMD_OPTIONS_RRQ:
        name = payload.split(b"\x00")[0].decode("ascii", "replace")
        value = terminal.option(name)
        return ok(f"{name}={value}".encode("ascii") + b"\x00") if value is not None \
            else [_packet(zk.CMD_ACK_ERROR, sid, reply)]
    if command == zk.CMD_GET_FREE_SIZES:
        return ok(terminal.sizes())
    if command == zk.CMD_GET_TIME:
        return ok(zk.encode_time(datetime.now() + terminal.clock_offset))
    if command == zk.CMD_PREPARE_BUFFER:
        _, requested, fct, _ = struct.unpack("<bhii", payload[:11])
        if requested == zk.CMD_ATTLOG_RRQ:
            session.buffer = terminal.attlog_buffer()
        elif requested == CMD_USERTEMP_RRQ and fct == FCT_USER:
            session.buffer = terminal.user_buffer()
        else:
            return [_packet(zk.CMD_ACK_ERROR, sid, reply)]
        if len(session.buffer) <= INLINE_LIMIT:
            return [_packet(zk.CMD_DATA, sid, reply, session.buffer)]
        return ok(b"\x00" + struct.pack("<I", len(session.buffer)) + b"\x00" * 4)
    if command == zk.CMD_READ_BUFFER:
        start, size = struct.unpack("<ii", payload[:8])
        chunk = session.buffer[start:start + size]
        if not udp:
            return [_packet(zk.CMD_DATA, sid, reply, chunk)]
        answers = [_packet(zk.CMD_PREPARE_DATA, sid, reply, struct.pack("<I", len(chunk)))]
        for offset in range(0, len(chunk), UDP_PACKET):
            answers.append(_packet(zk.CMD_DATA, sid, reply, chunk[offset:offset + UDP_PACKET]))
        answers.append(_packet(zk.CMD_ACK_OK, sid, reply))
        return answers
    return [_packet(CMD_ACK_UNKNOWN, sid, reply)]


class _TcpHandler(socketserver.BaseRequestHandler):
    def handle(self):
        session = _Session()
        while True:
            top = self._read(8)
            if not top:
                return
            magic1, magic2, length = struct.unpack("<HHI", top)
            if (magic1, magic2) != zk.TCP_MAGIC:
                return
            packet = self._read(length)
            if not packet:
                return
            for answer in handle(self.server.terminal, session, packet, udp=False):
                self.request.sendall(struct.pack("<HHI", *zk.TCP_MAGIC, len(answer)) + answer)
            if struct.unpack("<H", packet[:2])[0] == zk.CMD_EXIT:
                return

    def _read(self, count):
        data = bytearray()
        while len(data) < count:
            chunk = self.request.recv(count - len(data))
            if not chunk:
                return None
            data += chunk
        return bytes(data)


class _UdpHandler(socketserver.BaseRequestHandler):
    def handle(self):
        packet, sock = self.request
        sessions = self.server.sessions
        session = sessions.setdefault(self.client_address, _Session())
        for answer in handle(self.server.terminal, session, packet, udp=True):
            sock.sendto(answer, self.client_address)
        if struct.unpack("<H", packet[:2])[0] == zk.CMD_EXIT:
            sessions.pop(self.client_address, None)


class _TcpServer(socketserver.ThreadingTCPServer):
    allow_reuse_address = True
    daemon_threads = True


class _UdpServer(socketserver.ThreadingUDPServer):
    allow_reuse_address = True
    daemon_threads = True


class Emulator:
    """TCP and UDP on the same port, as a real terminal listens."""

    def __init__(self, terminal: Terminal, host: str = "127.0.0.1", port: int = 4370):
        self.terminal = terminal
        self.tcp = _TcpServer((host, port), _TcpHandler)
        port = self.tcp.server_address[1]
        self.udp = _UdpServer((host, port), _UdpHandler)
        for server in (self.tcp, self.udp):
            server.terminal = terminal
        self.udp.sessions = {}
        self.host, self.port = host, port
        self._threads: list[threading.Thread] = []

    def start(self) -> "Emulator":
        for server in (self.tcp, self.udp):
            thread = threading.Thread(target=server.serve_forever, daemon=True)
            thread.start()
            self._threads.append(thread)
        return self

    def stop(self) -> None:
        for server in (self.tcp, self.udp):
            server.shutdown()
            server.server_close()

    def __enter__(self):
        return self.start()

    def __exit__(self, *exc):
        self.stop()
        return False
