"""The agent's durable memory: every record it has read, and whether the server has it yet.

A ZK terminal read over 4370 has no cursor -- every read returns its whole log -- so the spool
is the cursor: a record already here was seen before. It is also what makes delivery safe to
retry: a record is marked delivered only after the server answered 200, and a crash between
reading and delivering leaves it pending for the next pass.

The key mirrors the server's dedup key (serial, PIN, local time, in/out state), so the spool
collapses exactly what the server would.
"""
from __future__ import annotations

import sqlite3
from dataclasses import dataclass

SCHEMA = """
CREATE TABLE IF NOT EXISTS record (
    key         TEXT PRIMARY KEY,
    serial      TEXT NOT NULL,
    pin         TEXT NOT NULL,
    local_time  TEXT NOT NULL,
    status      INTEGER,
    verify      INTEGER,
    delivered   INTEGER NOT NULL DEFAULT 0,
    read_at     TEXT NOT NULL DEFAULT (datetime('now')),
    delivered_at TEXT
);
CREATE INDEX IF NOT EXISTS record_pending_idx ON record (serial, delivered, local_time);
"""


@dataclass(frozen=True)
class Punch:
    """A normalised punch: the terminal's own wall clock, no offset, as the server stores it."""
    pin: str
    local_time: str
    status: int | None = None
    verify: int | None = None


def key_of(serial: str, punch: Punch) -> str:
    return "\x1f".join((serial, punch.pin, punch.local_time, "" if punch.status is None else str(punch.status)))


class Spool:
    def __init__(self, path: str):
        self.db = sqlite3.connect(path, isolation_level=None)
        self.db.execute("PRAGMA journal_mode=WAL")
        self.db.executescript(SCHEMA)

    def close(self) -> None:
        self.db.close()

    def add(self, serial: str, punches: list[Punch]) -> int:
        """@return how many were new to the spool."""
        added = 0
        self.db.execute("BEGIN")
        try:
            for punch in punches:
                cursor = self.db.execute(
                    "INSERT OR IGNORE INTO record (key, serial, pin, local_time, status, verify) VALUES (?, ?, ?, ?, ?, ?)",
                    (key_of(serial, punch), serial, punch.pin, punch.local_time, punch.status, punch.verify))
                added += cursor.rowcount
            self.db.execute("COMMIT")
        except BaseException:
            self.db.execute("ROLLBACK")
            raise
        return added

    def pending(self, serial: str, limit: int) -> list[tuple[str, Punch]]:
        rows = self.db.execute(
            "SELECT key, pin, local_time, status, verify FROM record WHERE serial = ? AND delivered = 0 "
            "ORDER BY local_time LIMIT ?", (serial, limit)).fetchall()
        return [(row[0], Punch(row[1], row[2], row[3], row[4])) for row in rows]

    def mark_delivered(self, keys: list[str]) -> None:
        """Only ever called after the server answered 200 for exactly these records."""
        self.db.execute("BEGIN")
        try:
            self.db.executemany(
                "UPDATE record SET delivered = 1, delivered_at = datetime('now') WHERE key = ?",
                [(key,) for key in keys])
            self.db.execute("COMMIT")
        except BaseException:
            self.db.execute("ROLLBACK")
            raise

    def counts(self, serial: str) -> dict[str, int]:
        rows = self.db.execute(
            "SELECT delivered, COUNT(*) FROM record WHERE serial = ? GROUP BY delivered", (serial,)).fetchall()
        result = {"pending": 0, "delivered": 0}
        for delivered, count in rows:
            result["delivered" if delivered else "pending"] = count
        return result


def to_attlog(punches: list[Punch]) -> str:
    """The ZKTeco ATTLOG line the server parses: PIN, time, in/out state, verification method."""
    lines = []
    for punch in punches:
        status = "" if punch.status is None else str(punch.status)
        verify = "" if punch.verify is None else str(punch.verify)
        lines.append(f"{punch.pin}\t{punch.local_time}\t{status}\t{verify}")
    return "\n".join(lines) + ("\n" if lines else "")
