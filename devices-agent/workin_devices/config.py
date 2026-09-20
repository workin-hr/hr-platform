"""Agent configuration: a TOML file, validated at load so a mistake stops the agent at start-up.

A misconfigured agent that runs is worse than one that refuses: it can submit one terminal's
punches under another's serial, or send its bearer token over plain HTTP.
"""
from __future__ import annotations

import ipaddress
import re
import os
import stat
import tomllib
from dataclasses import dataclass, field
from urllib.parse import urlparse

DEFAULT_POLL_SECONDS = 60
DEFAULT_BATCH = 2000
DEFAULT_WINDOW_HOURS = 48
# Hikvision minor codes for a successful identification: valid card (0x01), fingerprint
# match (0x26), face match (0x4b). Anything else -- a refused card, a door left open -- is
# not attendance. Confirm against a real terminal's log before widening this.
DEFAULT_HIK_ATTENDANCE_MINORS = (1, 38, 75)
KINDS = ("zk", "hikvision", "file")
# The platform's rule for a serial (DeviceInput.SERIAL_NUMBER). A serial it would refuse must stop
# the agent at start-up, not answer 400 on every pass.
SERIAL = re.compile(r"^[A-Za-z0-9][A-Za-z0-9._:@-]{0,63}$")


class ConfigError(Exception):
    pass


@dataclass
class DeviceConfig:
    serial: str
    kind: str
    host: str | None = None
    port: int | None = None
    comm_key: int = 0
    udp: bool = False
    timeout_seconds: float = 10.0
    username: str | None = None
    # Never in a repr: a DeviceConfig reaching a log line or an exception message would put the
    # terminal's password in it.
    password: str | None = field(default=None, repr=False)
    window_hours: int = DEFAULT_WINDOW_HOURS
    page_size: int = 30
    attendance_minors: tuple[int, ...] = DEFAULT_HIK_ATTENDANCE_MINORS
    path: str | None = None
    https: bool = False


@dataclass
class Config:
    server_url: str
    token: str
    spool_path: str
    token_path: str | None = None
    poll_interval_seconds: int = DEFAULT_POLL_SECONDS
    batch_size: int = DEFAULT_BATCH
    in_out_field: str = "punch"
    ca_file: str | None = None
    insecure_skip_tls_verify: bool = False
    devices: list[DeviceConfig] = field(default_factory=list)


def load(path: str, allow_plain_http: bool = False) -> Config:
    try:
        with open(path, "rb") as handle:
            raw = tomllib.load(handle)
    except FileNotFoundError as exc:
        raise ConfigError(f"{path} does not exist") from exc
    except tomllib.TOMLDecodeError as exc:
        raise ConfigError(f"{path} is not valid TOML: {exc}") from exc
    return parse(raw, base_dir=os.path.dirname(os.path.abspath(path)), allow_plain_http=allow_plain_http)


def parse(raw: dict, base_dir: str = ".", allow_plain_http: bool = False) -> Config:
    server_url = str(raw.get("server_url", "")).rstrip("/")
    if not server_url:
        raise ConfigError("server_url is required, e.g. https://api.example.com")
    parsed = urlparse(server_url)
    if parsed.scheme not in ("http", "https") or not parsed.hostname:
        raise ConfigError(f"server_url {server_url} is not an http(s) URL")
    if parsed.scheme == "http" and not allow_plain_http and not _is_loopback(parsed.hostname):
        raise ConfigError(
            f"server_url {server_url} is plain http. The agent token is a bearer credential and must "
            "not cross a network in clear; use https, or pass --allow-plain-http for a lab on a trusted LAN.")

    token = _secret(raw, "token_file", base_dir, required=True)
    token_path = _path(raw["token_file"], base_dir)
    in_out = raw.get("in_out_field", "punch")
    if in_out not in ("punch", "status"):
        raise ConfigError("in_out_field must be \"punch\" or \"status\"")
    batch = int(raw.get("batch_size", DEFAULT_BATCH))
    if not 1 <= batch <= 5000:
        raise ConfigError("batch_size must be between 1 and 5000, the server's per-upload cap")

    config = Config(
        server_url=server_url,
        token=token,
        spool_path=_path(raw.get("spool_path", "spool.sqlite3"), base_dir),
        token_path=token_path,
        poll_interval_seconds=int(raw.get("poll_interval_seconds", DEFAULT_POLL_SECONDS)),
        batch_size=batch,
        in_out_field=in_out,
        ca_file=_path(raw["ca_file"], base_dir) if raw.get("ca_file") else None,
        insecure_skip_tls_verify=bool(raw.get("insecure_skip_tls_verify", False)),
    )
    for entry in raw.get("devices", []) or []:
        config.devices.append(_device(entry, base_dir))
    serials = [device.serial for device in config.devices]
    duplicates = sorted({serial for serial in serials if serials.count(serial) > 1})
    if duplicates:
        raise ConfigError(f"a serial is configured twice: {duplicates}")
    if config.poll_interval_seconds < 10:
        raise ConfigError("poll_interval_seconds below 10 would hammer the terminals")
    return config


def _device(entry: dict, base_dir: str) -> DeviceConfig:
    serial = str(entry.get("serial", "")).strip()
    if not serial:
        raise ConfigError("every device needs the serial it is registered under")
    if not SERIAL.match(serial):
        raise ConfigError(f"{serial!r} is not a serial the platform accepts (letters, digits and ._:@- , at most 64)")
    kind = entry.get("kind", "zk")
    if kind not in KINDS:
        raise ConfigError(f"{serial}: kind must be one of {KINDS}")
    device = DeviceConfig(serial=serial, kind=kind)
    if kind in ("zk", "hikvision"):
        device.host = str(entry.get("host", "")).strip()
        if not device.host:
            raise ConfigError(f"{serial}: host is required for a {kind} terminal")
        device.timeout_seconds = float(entry.get("timeout_seconds", 10))
    if kind == "zk":
        device.port = int(entry.get("port", 4370))
        device.comm_key = int(entry.get("comm_key", 0))
        device.udp = bool(entry.get("udp", False))
    elif kind == "hikvision":
        device.https = bool(entry.get("https", False))
        device.port = int(entry.get("port", 443 if device.https else 80))
        device.username = entry.get("username")
        device.password = _secret(entry, "password_file", base_dir, required=True, label=f"{serial}: password_file")
        if not device.username:
            raise ConfigError(f"{serial}: a hikvision terminal needs username and password_file")
        device.window_hours = int(entry.get("window_hours", DEFAULT_WINDOW_HOURS))
        device.page_size = int(entry.get("page_size", 30))
        device.attendance_minors = tuple(int(m) for m in entry.get("attendance_minors", DEFAULT_HIK_ATTENDANCE_MINORS))
    else:
        if not entry.get("path"):
            raise ConfigError(f"{serial}: a file source needs path")
        device.path = _path(entry["path"], base_dir)
    return device


def _secret(raw: dict, key: str, base_dir: str, required: bool, label: str | None = None) -> str | None:
    """A credential lives in its own file, never inline in a config that gets copied into tickets."""
    label = label or key
    value = raw.get(key)
    if not value:
        if required:
            raise ConfigError(f"{label} is required; keep the secret in its own file, not in the config")
        return None
    path = _path(value, base_dir)
    if not os.path.exists(path):
        raise ConfigError(f"{label} {path} does not exist")
    if os.name == "posix":
        mode = stat.S_IMODE(os.stat(path).st_mode)
        if mode & 0o077:
            raise ConfigError(f"{label} {path} is mode {mode:o}; it holds a credential, chmod 600 it")
    with open(path, encoding="utf-8") as handle:
        secret = handle.read().strip()
    if not secret:
        raise ConfigError(f"{label} {path} is empty")
    return secret


def _path(value: str, base_dir: str) -> str:
    return value if os.path.isabs(value) else os.path.join(base_dir, value)


def _is_loopback(host: str) -> bool:
    if host == "localhost":
        return True
    try:
        return ipaddress.ip_address(host).is_loopback
    except ValueError:
        return False


def reload_token(config: Config) -> bool:
    """Re-reads the token file; true when it now holds a different token. A replaced token is picked
    up without restarting the agent."""
    if not config.token_path:
        return False
    try:
        base = os.path.dirname(config.token_path)
        token = _secret({"token_file": os.path.basename(config.token_path)}, "token_file", base, required=True)
    except ConfigError:
        return False
    changed = token != config.token
    config.token = token
    return changed
