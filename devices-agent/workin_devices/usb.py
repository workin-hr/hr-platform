"""Importing a terminal's USB export (attlog.dat) through the agent.

For a terminal on no network, or a backlog from before a terminal was connected. Every line is
sent, including ones this build cannot read, so the server quarantines them where an operator
can see them rather than the agent dropping them unseen. Re-running an import stores nothing
twice: the server's dedup key is the same whichever path a punch arrives by.
"""
from __future__ import annotations

from . import gateway as gw


def normalised_lines(content: bytes) -> list[str]:
    text = content.decode("utf-8", "replace").lstrip("﻿")
    lines = []
    for line in text.splitlines():
        if not line.strip():
            continue
        lines.append("\t".join(field.strip() for field in line.split("\t")))
    return lines


def import_file(gateway: gw.Gateway, serial: str, content: bytes, batch_size: int = 2000) -> dict:
    lines = normalised_lines(content)
    totals = {"lines": len(lines), "stored": 0, "duplicates": 0, "unmatched": 0, "malformed": 0}
    start = 0
    size = batch_size
    while start < len(lines):
        batch = lines[start:start + size]
        try:
            answer = gateway.submit(serial, "\n".join(batch) + "\n", delivery="file")
        except gw.TooLarge:
            if size == 1:
                raise
            size = max(1, size // 2)
            continue
        for key in ("stored", "duplicates", "unmatched", "malformed"):
            totals[key] += int(answer.get(key, 0))
        start += len(batch)
    return totals
