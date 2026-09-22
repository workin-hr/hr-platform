"""A read-only client for the ZKTeco binary protocol (TCP or UDP, port 4370).

Written for this agent rather than taken from pyzk for two reasons. pyzk is
GPL-2.0, and this client ships inside an executable handed to customers. And
pyzk's attendance read fetches the terminal's user table first -- names and
device passwords included -- to map one old record format; this client asks for
nothing it does not store.

Every command it can send is in READ_ONLY_COMMANDS. There is no clear, no
set-time, no user write, no enrol, no restart, no unlock: a terminal on a
customer's wall is read, never changed. `_send` refuses anything else, so a
future edit cannot add a write by accident.

The packet layout and the handshake follow the protocol as the ZKTeco SDK and
the community specification describe it
(https://github.com/adrobinoga/zk-protocol). Two details copy what pyzk does in
the field rather than what a specification says, because pyzk is what has been
run against real terminals: the checksum is computed before the reply id is
advanced, and the first reply id sent is 0.
"""
from __future__ import annotations

import errno
import socket
import struct
import time
from dataclasses import dataclass
from datetime import datetime

CMD_CONNECT = 1000
CMD_EXIT = 1001
CMD_AUTH = 1102
CMD_GET_VERSION = 1100
CMD_OPTIONS_RRQ = 11
CMD_ATTLOG_RRQ = 13
CMD_GET_FREE_SIZES = 50
CMD_GET_TIME = 201
CMD_PREPARE_BUFFER = 1503
CMD_READ_BUFFER = 1504
CMD_FREE_DATA = 1502

CMD_PREPARE_DATA = 1500
CMD_DATA = 1501
CMD_ACK_OK = 2000
CMD_ACK_ERROR = 2001
CMD_ACK_UNAUTH = 2005

USHRT_MAX = 65535
TCP_MAGIC = (0x5050, 0x7D82)
TCP_CHUNK = 0xFFC0
UDP_CHUNK = 16 * 1024

# A terminal usually sits on a customer's Wi-Fi. When an ARP exchange is lost there the
# kernel marks the address unreachable and then answers EHOSTUNREACH instantly -- to every
# connect, until the neighbour entry is probed again -- for a terminal that is present and
# answers a moment later. Treating that first instant refusal as the terminal's answer ends
# a site visit that could have continued, so these errors alone are retried; every other
# one, a refusal or a timeout included, is the answer.
TRANSIENT_CONNECT_ERRNOS = frozenset({errno.EHOSTUNREACH, errno.ENETUNREACH, errno.EHOSTDOWN})
CONNECT_ATTEMPTS = 3
CONNECT_RETRY_SECONDS = 2.0

READ_ONLY_COMMANDS = frozenset({
    CMD_CONNECT, CMD_EXIT, CMD_AUTH, CMD_GET_VERSION, CMD_OPTIONS_RRQ, CMD_GET_FREE_SIZES,
    CMD_GET_TIME, CMD_PREPARE_BUFFER, CMD_READ_BUFFER, CMD_FREE_DATA,
})

# The buffered reads this client may ask for. The user table (9) is absent on purpose.
READABLE_BUFFERS = frozenset({CMD_ATTLOG_RRQ})


class ZkError(Exception):
    """The terminal refused, answered something unexpected, or did not answer."""


class ZkAuthError(ZkError):
    """The terminal wants a communication key and the configured one is wrong or missing."""


@dataclass(frozen=True)
class Sizes:
    users: int
    fingers: int
    records: int
    cards: int
    fingers_capacity: int
    users_capacity: int
    records_capacity: int
    faces: int | None = None


@dataclass(frozen=True)
class RawAttendance:
    """One record as the terminal stores it.

    `status` and `punch` are the record's two one-byte codes, named as pyzk
    names them so that a field mapping written against pyzk means the same here.
    Which one is the in/out key and which the verification method has not been
    confirmed on hardware for every record format; the ZKTeco SDK's 40-byte
    layout puts the verification method first (pyzk's `status`) and the in/out
    state after the time (pyzk's `punch`). The mapping is the caller's
    (config `in_out_field`), not this module's.
    """
    user_id: str
    timestamp: datetime
    status: int
    punch: int
    record_size: int


def checksum(data: bytes) -> int:
    total = 0
    length = len(data)
    index = 0
    while length > 1:
        total += struct.unpack("<H", data[index:index + 2])[0]
        index += 2
        if total > USHRT_MAX:
            total -= USHRT_MAX
        length -= 2
    if length:
        total += data[-1]
    while total > USHRT_MAX:
        total -= USHRT_MAX
    total = ~total
    while total < 0:
        total += USHRT_MAX
    return total & 0xFFFF


def make_commkey(key: int, session_id: int, ticks: int = 50) -> bytes:
    """The communication-key answer to CMD_ACK_UNAUTH, as the SDK computes it."""
    k = 0
    for bit in range(32):
        k = (k << 1 | 1) if key & (1 << bit) else k << 1
    k = (k + session_id) & 0xFFFFFFFF
    b = struct.unpack("<4B", struct.pack("<I", k))
    b = struct.pack("<4B", b[0] ^ ord("Z"), b[1] ^ ord("K"), b[2] ^ ord("S"), b[3] ^ ord("O"))
    h = struct.unpack("<2H", b)
    b = struct.unpack("<4B", struct.pack("<2H", h[1], h[0]))
    t = 0xFF & ticks
    return struct.pack("<4B", b[0] ^ t, b[1] ^ t, t, b[3] ^ t)


def decode_time(raw: bytes) -> datetime:
    """A corrupt record (day 31 of February, say) is a ZkError like any other bad answer."""
    try:
        return _decode_time(raw)
    except (ValueError, struct.error) as exc:
        raise ZkError(f"the terminal returned an impossible time: {raw.hex()}") from exc


def _decode_time(raw: bytes) -> datetime:
    value = struct.unpack("<I", raw)[0]
    second = value % 60
    value //= 60
    minute = value % 60
    value //= 60
    hour = value % 24
    value //= 24
    day = value % 31 + 1
    value //= 31
    month = value % 12 + 1
    value //= 12
    return datetime(value + 2000, month, day, hour, minute, second)


def encode_time(when: datetime) -> bytes:
    """The inverse of decode_time. Used by the emulator; this client never writes a time."""
    value = ((when.year % 100) * 12 * 31 + (when.month - 1) * 31 + when.day - 1) * 86400 \
        + (when.hour * 60 + when.minute) * 60 + when.second
    return struct.pack("<I", value)


class ZkClient:
    def __init__(self, host: str, port: int = 4370, timeout: float = 10.0, password: int = 0,
                 udp: bool = False):
        self.host = host
        self.port = port
        self.timeout = timeout
        self.password = password
        self.udp = udp
        self._sock: socket.socket | None = None
        self._session = 0
        self._reply = USHRT_MAX - 1
        self.connected = False

    # -- lifecycle -------------------------------------------------------------------------

    def __enter__(self) -> "ZkClient":
        self.connect()
        return self

    def __exit__(self, *exc) -> bool:
        self.disconnect()
        return False

    def connect(self) -> None:
        self._open_socket()
        if not self.udp:
            self._connect_tcp()
        self._session = 0
        self._reply = USHRT_MAX - 1
        code, session, _ = self._send(CMD_CONNECT)
        self._session = session
        if code == CMD_ACK_UNAUTH:
            code, _, _ = self._send(CMD_AUTH, make_commkey(self.password, self._session))
            if code != CMD_ACK_OK:
                self._close()
                raise ZkAuthError("the terminal refused the communication key (Comm Key in its menu)")
        elif code != CMD_ACK_OK:
            self._close()
            raise ZkError(f"the terminal answered {code} to connect")
        self.connected = True

    def disconnect(self) -> None:
        if self._sock is None:
            return
        try:
            if self.connected:
                self._send(CMD_EXIT)
        except (ZkError, OSError):
            pass
        finally:
            self.connected = False
            self._close()

    def _open_socket(self) -> None:
        family = socket.SOCK_DGRAM if self.udp else socket.SOCK_STREAM
        self._sock = socket.socket(socket.AF_INET, family)
        self._sock.settimeout(self.timeout)

    def _connect_tcp(self) -> None:
        for attempt in range(CONNECT_ATTEMPTS):
            try:
                self._sock.connect((self.host, self.port))
                return
            except OSError as exc:
                self._close()
                if exc.errno not in TRANSIENT_CONNECT_ERRNOS or attempt == CONNECT_ATTEMPTS - 1:
                    raise ZkError(f"cannot reach {self.host}:{self.port} over TCP: {exc}") from exc
            time.sleep(CONNECT_RETRY_SECONDS)
            self._open_socket()

    def _close(self) -> None:
        if self._sock is not None:
            try:
                self._sock.close()
            finally:
                self._sock = None

    # -- what the agent reads --------------------------------------------------------------

    def firmware_version(self) -> str:
        code, _, data = self._send(CMD_GET_VERSION)
        self._expect_ok(code, "firmware version")
        return data.split(b"\x00")[0].decode("ascii", "replace")

    def option(self, name: str) -> str | None:
        """A configuration value by name, e.g. ~SerialNumber, ~Platform, ~DeviceName, MAC."""
        code, _, data = self._send(CMD_OPTIONS_RRQ, name.encode("ascii") + b"\x00")
        if code != CMD_ACK_OK:
            return None
        value = data.split(b"\x00")[0].split(b"=", 1)[-1]
        return value.decode("ascii", "replace").strip() or None

    def serial_number(self) -> str | None:
        return self.option("~SerialNumber")

    def sizes(self) -> Sizes:
        code, _, data = self._send(CMD_GET_FREE_SIZES)
        self._expect_ok(code, "record counts")
        if len(data) < 80:
            raise ZkError(f"record counts were {len(data)} bytes, expected at least 80")
        f = struct.unpack("<20i", data[:80])
        faces = struct.unpack("<3i", data[80:92])[0] if len(data) >= 92 else None
        return Sizes(users=f[4], fingers=f[6], records=f[8], cards=f[12], fingers_capacity=f[14],
                     users_capacity=f[15], records_capacity=f[16], faces=faces)

    def device_time(self) -> datetime:
        code, _, data = self._send(CMD_GET_TIME)
        self._expect_ok(code, "clock")
        return decode_time(data[:4])

    def attendance(self) -> list[RawAttendance]:
        records = self.sizes().records
        if records <= 0:
            return []
        buffer = self._read_buffer(CMD_ATTLOG_RRQ)
        if len(buffer) < 4:
            return []
        total = struct.unpack("<I", buffer[:4])[0]
        body = buffer[4:4 + total]
        record_size = total // records if records else 0
        if record_size not in (8, 16, 40) or record_size * records != total:
            raise ZkError(f"{total} bytes for {records} records is not a record format this client knows")
        out: list[RawAttendance] = []
        for offset in range(0, record_size * records, record_size):
            chunk = body[offset:offset + record_size]
            if len(chunk) < record_size:
                break
            if record_size == 40:
                _, user, status, stamp, punch, _ = struct.unpack("<H24sB4sB8s", chunk)
                user_id = user.split(b"\x00")[0].decode("ascii", "replace")
            elif record_size == 16:
                number, stamp, status, punch, _, _ = struct.unpack("<I4sBB2sI", chunk)
                user_id = str(number)
            else:
                # The oldest format names the user by internal slot, not by PIN.
                # Mapping it needs the user table, which this client does not
                # read; the slot is reported and the record says so.
                slot, status, stamp, punch = struct.unpack("<HB4sB", chunk)
                user_id = f"uid:{slot}"
            out.append(RawAttendance(user_id, decode_time(stamp), status, punch, record_size))
        return out

    # -- transport -------------------------------------------------------------------------

    def _read_buffer(self, command: int) -> bytes:
        if command not in READABLE_BUFFERS:
            raise ZkError(f"buffer {command} is not one this client reads")
        code, _, data = self._send(CMD_PREPARE_BUFFER, struct.pack("<bhii", 1, command, 0, 0))
        if code == CMD_DATA:
            return data
        if code != CMD_ACK_OK or len(data) < 5:
            raise ZkError(f"the terminal answered {code} to a buffered read")
        size = struct.unpack("<I", data[1:5])[0]
        chunk = UDP_CHUNK if self.udp else TCP_CHUNK
        out = bytearray()
        start = 0
        while start < size:
            length = min(chunk, size - start)
            out += self._read_chunk(start, length)
            start += length
        self._send(CMD_FREE_DATA)
        return bytes(out)

    def _read_chunk(self, start: int, length: int) -> bytes:
        for _ in range(3):
            code, _, data = self._send(CMD_READ_BUFFER, struct.pack("<ii", start, length))
            if code == CMD_DATA:
                return data
            if code == CMD_PREPARE_DATA:
                out = bytearray()
                while True:
                    next_code, _, next_data = self._receive()
                    if next_code == CMD_DATA:
                        out += next_data
                    elif next_code == CMD_ACK_OK:
                        return bytes(out)
                    else:
                        break
        raise ZkError(f"could not read {length} bytes at offset {start}")

    def _send(self, command: int, payload: bytes = b"") -> tuple[int, int, bytes]:
        if command not in READ_ONLY_COMMANDS:
            raise ZkError(f"command {command} is not a read and is never sent")
        if self._sock is None:
            raise ZkError("not connected")
        unsigned = struct.pack("<4H", command, 0, self._session, self._reply) + payload
        sum_ = checksum(unsigned)
        self._reply = (self._reply + 1) % USHRT_MAX
        packet = struct.pack("<4H", command, sum_, self._session, self._reply) + payload
        try:
            if self.udp:
                self._sock.sendto(packet, (self.host, self.port))
            else:
                self._sock.sendall(struct.pack("<HHI", *TCP_MAGIC, len(packet)) + packet)
        except OSError as exc:
            raise ZkError(f"send failed: {exc}") from exc
        return self._receive()

    def _receive(self) -> tuple[int, int, bytes]:
        try:
            if self.udp:
                packet, _ = self._sock.recvfrom(65535)
            else:
                top = self._read_exactly(8)
                magic1, magic2, length = struct.unpack("<HHI", top)
                if (magic1, magic2) != TCP_MAGIC:
                    raise ZkError("the answer did not start with the protocol's TCP header")
                packet = self._read_exactly(length)
        except socket.timeout as exc:
            raise ZkError("the terminal did not answer in time") from exc
        except OSError as exc:
            raise ZkError(f"receive failed: {exc}") from exc
        if len(packet) < 8:
            raise ZkError("the answer was shorter than a header")
        code, _, session, reply = struct.unpack("<4H", packet[:8])
        self._reply = reply
        return code, session, packet[8:]

    def _read_exactly(self, count: int) -> bytes:
        out = bytearray()
        while len(out) < count:
            chunk = self._sock.recv(count - len(out))
            if not chunk:
                raise ZkError("the terminal closed the connection")
            out += chunk
        return bytes(out)

    @staticmethod
    def _expect_ok(code: int, what: str) -> None:
        if code != CMD_ACK_OK:
            raise ZkError(f"the terminal answered {code} when asked for its {what}")
