"""One pass over the configured terminals, and the loop that repeats it."""
from __future__ import annotations

import logging
import time
from dataclasses import dataclass, field
from datetime import datetime

from . import gateway as gw
from .config import Config, DeviceConfig
from .sources import Description, SourceError, open_source
from .spool import Spool, to_attlog

log = logging.getLogger("workin_devices")


class SerialMismatch(Exception):
    """The terminal at this address is not the one configured.

    Refusing is the only safe answer: submitting under the configured serial would put one
    terminal's punches on another's registry row, and possibly another company's.
    """


@dataclass
class DeviceResult:
    serial: str
    reachable: bool = False
    read: int = 0
    new: int = 0
    stored: int = 0
    pending: int = 0
    error: str | None = None
    description: Description = field(default_factory=Description)

    @property
    def healthy(self) -> bool:
        return self.error is None

    def heartbeat_entry(self) -> dict:
        entry = {"serial": self.serial, "reachable": self.reachable}
        entry.update(self.description.as_report())
        entry["serial"] = self.serial
        if self.error:
            entry["error"] = self.error[:200]
        return entry


def cycle(config: Config, device: DeviceConfig, spool: Spool, gateway: gw.Gateway) -> DeviceResult:
    result = DeviceResult(device.serial)
    try:
        with open_source(device, config.in_out_field) as source:
            result.description = source.describe()
            result.reachable = True
            reported = (result.description.serial or "").strip()
            if device.kind != "file" and reported and reported != device.serial:
                raise SerialMismatch(
                    f"configured as {device.serial} but the terminal reports {reported}; "
                    "nothing from it is submitted until the config names it correctly")
            punches = source.read()
        result.read = len(punches)
        result.new = spool.add(device.serial, punches)
        _log_clock_skew(device, result.description)
    except (SourceError, SerialMismatch) as exc:
        result.error = str(exc)
        log.error("%s", exc)
    except Exception as exc:  # noqa: BLE001 - one terminal's surprise must not stop the others
        result.error = f"unexpected: {exc}"
        log.exception("%s: unexpected failure reading the terminal", device.serial)
    # Deliver what is pending even when the terminal could not be read this pass: records read
    # on an earlier pass are still owed to the server.
    try:
        result.stored = deliver(config, device.serial, spool, gateway)
    except gw.NotRegistered as exc:
        result.error = result.error or f"not registered: {exc}"
        log.error("%s: not an active device of this company on the server -- allocate it in the dashboard", device.serial)
    except gw.Retryable as exc:
        result.error = result.error or f"retry: {exc}"
        log.warning("%s: %s; records kept and retried next pass", device.serial, exc)
    except gw.Refused as exc:
        result.error = result.error or f"refused: {exc}"
        log.error("%s: the server refused the delivery (%s); records kept", device.serial, exc)
    result.pending = spool.counts(device.serial)["pending"]
    return result


def deliver(config: Config, serial: str, spool: Spool, gateway: gw.Gateway) -> int:
    """Send pending records in batches; a batch is marked delivered only after the server's 200."""
    stored = 0
    batch_size = config.batch_size
    while True:
        batch = spool.pending(serial, batch_size)
        if not batch:
            return stored
        try:
            answer = gateway.submit(serial, to_attlog([punch for _, punch in batch]))
        except gw.TooLarge:
            if batch_size == 1:
                raise gw.Retryable("the server refused a single record as too large")
            batch_size = max(1, batch_size // 2)
            continue
        spool.mark_delivered([key for key, _ in batch])
        stored += int(answer.get("stored", 0))
        if answer.get("malformed"):
            log.warning("%s: the server could not read %s line(s); they are kept on the server for review",
                        serial, answer["malformed"])
        if len(batch) < batch_size:
            return stored


def _log_clock_skew(device: DeviceConfig, description: Description) -> None:
    if not description.device_time:
        return
    try:
        device_time = datetime.strptime(description.device_time, "%Y-%m-%d %H:%M:%S")
    except ValueError:
        return
    skew = (device_time - datetime.now()).total_seconds()
    if abs(skew) > 120:
        # Reported, never corrected: this agent does not write to terminals.
        log.warning("%s: terminal clock is %+.0f seconds from this computer's", device.serial, skew)


def run_once(config: Config, spool: Spool | None = None, gateway: gw.Gateway | None = None) -> list[DeviceResult]:
    owns_spool = spool is None
    spool = spool or Spool(config.spool_path)
    gateway = gateway or gw.Gateway(config.server_url, config.token, ca_file=config.ca_file,
                                    insecure_skip_tls_verify=config.insecure_skip_tls_verify)
    try:
        results = [cycle(config, device, spool, gateway) for device in config.devices]
        try:
            gateway.heartbeat([result.heartbeat_entry() for result in results])
        except (gw.Retryable, gw.Refused) as exc:
            log.warning("heartbeat not delivered: %s", exc)
        return results
    finally:
        if owns_spool:
            spool.close()


UNAUTHORIZED_WAIT_SECONDS = 900


def run_forever(config: Config, sleep=time.sleep) -> None:  # pragma: no cover - the loop around run_once
    """Never exits on its own. A revoked token waits and re-reads the token file rather than exiting,
    because a service manager would restart it straight into the same refusal, and every restart
    re-reads every terminal."""
    from .config import reload_token
    spool = Spool(config.spool_path)
    gateway = gw.Gateway(config.server_url, config.token, ca_file=config.ca_file,
                         insecure_skip_tls_verify=config.insecure_skip_tls_verify)
    failures = 0
    try:
        while True:
            try:
                results = run_once(config, spool, gateway)
            except gw.Unauthorized:
                log.critical("the server refused this agent's token. Issue a new one in the dashboard and put it in "
                             "the token file; checking every %d seconds, without reading any terminal meanwhile.",
                             UNAUTHORIZED_WAIT_SECONDS)
                _wait_for_a_token_the_server_accepts(config, gateway, sleep, reload_token)
                continue
            failing = [result for result in results if not result.healthy]
            failures = failures + 1 if failing and len(failing) == len(results) else 0
            for result in results:
                log.info("%s reachable=%s read=%d new=%d stored=%d pending=%d%s", result.serial, result.reachable,
                         result.read, result.new, result.stored, result.pending,
                         f" error={result.error}" if result.error else "")
            # Backing off never polls faster than healthy operation does.
            wait = config.poll_interval_seconds
            if failures:
                wait = max(wait, gw.backoff_seconds(failures))
            sleep(wait)
    finally:
        spool.close()


def _wait_for_a_token_the_server_accepts(config, gateway, sleep, reload_token) -> None:  # pragma: no cover
    """An empty heartbeat is the probe: it reads no terminal and delivers nothing."""
    while True:
        sleep(UNAUTHORIZED_WAIT_SECONDS)
        if reload_token(config):
            gateway.token = config.token
            log.info("the token file changed; trying the new token")
        try:
            gateway.heartbeat([])
            log.info("the server accepts the token again; resuming")
            return
        except gw.Unauthorized:
            continue
        except (gw.Retryable, gw.Refused) as exc:
            log.warning("could not check the token: %s", exc)
