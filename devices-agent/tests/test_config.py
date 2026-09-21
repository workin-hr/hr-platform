import os
import tempfile
import unittest

from workin_devices import config as cfg


class ConfigRefusesWhatWouldBeUnsafe(unittest.TestCase):

    def setUp(self):
        self.dir = tempfile.TemporaryDirectory()
        self.token = os.path.join(self.dir.name, "agent.token")
        with open(self.token, "w") as handle:
            handle.write("wda_" + "x" * 43)
        os.chmod(self.token, 0o600)

    def tearDown(self):
        self.dir.cleanup()

    def raw(self, **overrides):
        raw = {"server_url": "https://api.example.com", "token_file": "agent.token",
               "devices": [{"serial": "ZK1", "kind": "zk", "host": "192.168.1.201"}]}
        raw.update(overrides)
        return raw

    def test_a_valid_config_loads_with_its_defaults(self):
        config = cfg.parse(self.raw(), base_dir=self.dir.name)
        self.assertEqual(config.token, "wda_" + "x" * 43)
        self.assertEqual(config.devices[0].port, 4370)
        self.assertEqual(config.in_out_field, "punch")

    def test_plain_http_is_refused_except_to_this_computer(self):
        with self.assertRaises(cfg.ConfigError):
            cfg.parse(self.raw(server_url="http://api.example.com"), base_dir=self.dir.name)
        for loopback in ("http://127.0.0.1:18180", "http://localhost:8080"):
            self.assertTrue(cfg.parse(self.raw(server_url=loopback), base_dir=self.dir.name))
        self.assertTrue(cfg.parse(self.raw(server_url="http://192.168.1.5:8080"), base_dir=self.dir.name,
                                  allow_plain_http=True))

    @unittest.skipUnless(os.name == "posix", "file modes are a POSIX control")
    def test_a_token_file_others_can_read_is_refused(self):
        os.chmod(self.token, 0o644)
        with self.assertRaises(cfg.ConfigError):
            cfg.parse(self.raw(), base_dir=self.dir.name)

    def test_the_token_is_never_inline(self):
        raw = self.raw()
        raw.pop("token_file")
        raw["token"] = "wda_" + "x" * 43
        with self.assertRaises(cfg.ConfigError):
            cfg.parse(raw, base_dir=self.dir.name)

    def test_a_serial_configured_twice_is_refused(self):
        devices = [{"serial": "ZK1", "kind": "zk", "host": "10.0.0.1"}, {"serial": "ZK1", "kind": "zk", "host": "10.0.0.2"}]
        with self.assertRaises(cfg.ConfigError):
            cfg.parse(self.raw(devices=devices), base_dir=self.dir.name)

    def test_a_serial_the_platform_would_refuse_stops_the_agent_at_start_up(self):
        for bad in ("ZK 1", "-ZK1", "Z" * 65, "ZK1/2"):
            with self.subTest(serial=bad):
                with self.assertRaises(cfg.ConfigError):
                    cfg.parse(self.raw(devices=[{"serial": bad, "kind": "zk", "host": "10.0.0.1"}]), base_dir=self.dir.name)

    def test_the_serial_rule_ends_at_the_end_of_the_string(self):
        """`_device` strips what it reads from a file, but `visit.py` matches this same pattern
        against a serial taken straight out of a push handshake's query string, where nothing
        strips anything. Python's `$` also matches immediately before a trailing newline, so an
        `SN=ZK1%0A` handshake used to pass the guard and put a line break into the report's
        title, its filename and the results table."""
        self.assertTrue(cfg.SERIAL.match("ZK1"))
        self.assertIsNone(cfg.SERIAL.match("ZK1\n"))
        self.assertIsNone(cfg.SERIAL.match("ZK1\nZK2"))

    def test_a_replaced_token_file_is_picked_up(self):
        config = cfg.parse(self.raw(), base_dir=self.dir.name)
        self.assertFalse(cfg.reload_token(config))
        with open(self.token, "w") as handle:
            handle.write("wda_" + "y" * 43)
        self.assertTrue(cfg.reload_token(config))
        self.assertEqual(config.token, "wda_" + "y" * 43)

    def test_a_batch_above_the_servers_cap_is_refused(self):
        with self.assertRaises(cfg.ConfigError):
            cfg.parse(self.raw(batch_size=6000), base_dir=self.dir.name)

    def test_a_terminals_password_is_never_in_a_repr(self):
        """A `DeviceConfig` reaching an exception message or a log line would put the terminal's
        password in it, and the site visit writes exception text into a report meant to be pasted
        into an issue."""
        device = cfg.DeviceConfig(serial="H1", kind="hikvision", host="10.0.0.9",
                                  username="admin", password="s3cret-pass")
        self.assertNotIn("s3cret-pass", repr(device))
        self.assertNotIn("s3cret-pass", f"{RuntimeError(f'reading {device!r}')!r}")
        self.assertEqual(device.password, "s3cret-pass", "and the value is still there to be used")

    def test_a_hikvision_terminal_needs_its_credentials_in_a_file(self):
        with self.assertRaises(cfg.ConfigError):
            cfg.parse(self.raw(devices=[{"serial": "H1", "kind": "hikvision", "host": "10.0.0.9", "username": "admin"}]),
                      base_dir=self.dir.name)


if __name__ == "__main__":
    unittest.main()
