"""The agent's HTTP client for the platform's /api/v1/device-agents surface.

The one rule: records are delivered only on a 200. Every other answer -- 401, 404, 413, 5xx,
a timeout, a refused connection -- leaves the spool untouched, because those punches may exist
nowhere else yet.
"""
from __future__ import annotations

import json
import random
import ssl
import urllib.error
import urllib.parse
import urllib.request

from . import VERSION

MIN_BACKOFF_SECONDS = 30
MAX_BACKOFF_SECONDS = 600


class Unauthorized(Exception):
    """The token is wrong or revoked. Retrying cannot help until an administrator issues a new one."""


class NotRegistered(Exception):
    """The serial is not an active device of this agent's company. An administrator must allocate it."""


class TooLarge(Exception):
    """The batch was above the server's cap; send it in smaller pieces."""


class Refused(Exception):
    """A 400: the request itself is wrong, and sending it again will not change the answer."""


class Retryable(Exception):
    """The server or the network failed. Hold the records and try again later."""


class Gateway:
    def __init__(self, server_url: str, token: str, timeout: float = 30.0, ca_file: str | None = None,
                 insecure_skip_tls_verify: bool = False):
        self.base = server_url.rstrip("/") + "/api/v1/device-agents"
        self.token = token
        self.timeout = timeout
        if insecure_skip_tls_verify:
            self.context = ssl._create_unverified_context()  # noqa: S323 - opt-in, for a lab's self-signed edge
        else:
            self.context = ssl.create_default_context(cafile=ca_file)

    def submit(self, serial: str, attlog: str, delivery: str = "agent") -> dict:
        query = urllib.parse.urlencode({"serial": serial, "delivery": delivery})
        return self._post(f"{self.base}/punches?{query}", attlog.encode("utf-8"),
                          "text/plain; charset=utf-8", serial)

    def heartbeat(self, devices: list[dict]) -> dict:
        body = json.dumps({"agent_version": VERSION, "devices": devices}).encode("utf-8")
        return self._post(f"{self.base}/heartbeat", body, "application/json", None)

    def _post(self, url: str, body: bytes, content_type: str, serial: str | None) -> dict:
        request = urllib.request.Request(url, data=body, method="POST")
        request.add_header("Authorization", f"Bearer {self.token}")
        request.add_header("Content-Type", content_type)
        request.add_header("User-Agent", f"workin-devices-agent/{VERSION}")
        try:
            handler = urllib.request.HTTPSHandler(context=self.context)
            opener = urllib.request.build_opener(handler)
            with opener.open(request, timeout=self.timeout) as response:
                payload = response.read()
        except urllib.error.HTTPError as exc:
            detail = _error_code(exc)
            if exc.code == 401:
                raise Unauthorized("the server refused the agent token") from exc
            if exc.code == 404:
                raise NotRegistered(f"{serial} is not an active device of this agent's company") from exc
            if exc.code == 413:
                raise TooLarge(detail or "batch too large") from exc
            if exc.code == 400:
                raise Refused(detail or "bad request") from exc
            raise Retryable(f"server answered {exc.code} {detail}".strip()) from exc
        except (urllib.error.URLError, OSError, TimeoutError) as exc:
            raise Retryable(f"server unreachable: {exc}") from exc
        try:
            return json.loads(payload.decode("utf-8")) if payload else {}
        except ValueError as exc:
            raise Retryable("the server answered 200 with a body that is not JSON") from exc


def _error_code(error: urllib.error.HTTPError) -> str:
    try:
        return json.loads(error.read().decode("utf-8")).get("error", "")
    except Exception:  # noqa: BLE001 - the status code already says what matters
        return ""


def backoff_seconds(failures: int) -> float:
    """Exponential with jitter, capped: every branch reconnects at once after an outage."""
    base = min(MIN_BACKOFF_SECONDS * (2 ** max(0, failures - 1)), MAX_BACKOFF_SECONDS)
    return base / 2 + random.random() * (base / 2)
