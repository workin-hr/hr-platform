import os
import tempfile
import unittest

from workin_devices import agent, gateway as gw
from workin_devices.config import Config, DeviceConfig
from workin_devices.sim.zk4370 import Emulator, Terminal, populate
from workin_devices.spool import Spool
from tests.support import FakePlatform


class AgentDeliversEveryRecordOnceAndLosesNone(unittest.TestCase):

    def setUp(self):
        self.dir = tempfile.TemporaryDirectory()
        self.platform = FakePlatform()
        self.terminal = Terminal(serial="ZK-BRANCH-1")
        populate(self.terminal, ["1001", "1002", "1003"], days=10)
        self.emulator = Emulator(self.terminal, port=0).start()
        self.spool = Spool(os.path.join(self.dir.name, "spool.sqlite3"))
        self.config = Config(server_url=self.platform.url, token=self.platform.token, spool_path="unused",
                             batch_size=25, devices=[DeviceConfig(serial="ZK-BRANCH-1", kind="zk", host="127.0.0.1",
                                                                  port=self.emulator.port, timeout_seconds=5)])
        self.gateway = gw.Gateway(self.platform.url, self.platform.token, timeout=5)

    def tearDown(self):
        self.emulator.stop()
        self.spool.close()
        self.platform.close()
        self.dir.cleanup()

    def once(self):
        results = agent.run_once(self.config, self.spool, self.gateway)
        return results[0] if len(results) == 1 else results

    def test_the_whole_log_is_delivered_in_batches_and_a_second_pass_sends_nothing(self):
        result = self.once()
        self.assertTrue(result.healthy, result.error)
        self.assertEqual(result.read, len(self.terminal.records))
        self.assertEqual(result.stored, len(self.terminal.records))
        self.assertEqual(result.pending, 0)
        self.assertGreater(len(self.platform.submissions), 1, "delivered in more than one batch")
        self.assertTrue(all(len(s["lines"]) <= 25 for s in self.platform.submissions))

        submitted = len(self.platform.submissions)
        again = self.once()
        self.assertEqual((again.new, again.stored), (0, 0))
        self.assertEqual(len(self.platform.submissions), submitted, "nothing re-sent")

        self.terminal.add_punch("1002", in_out=1)
        self.assertEqual(self.once().stored, 1, "a new scan is the only thing sent next pass")
        self.assertEqual(self.terminal.write_attempts, [])

    def test_a_server_outage_keeps_every_record_for_the_next_pass(self):
        self.platform.fail_with = 503
        failed = self.once()
        self.assertIn("retry", failed.error)
        self.assertEqual(failed.pending, len(self.terminal.records))
        self.platform.fail_with = None
        recovered = self.once()
        self.assertEqual((recovered.stored, recovered.pending), (len(self.terminal.records), 0))

    def test_a_terminal_that_is_not_the_one_configured_is_never_submitted_for(self):
        self.config.devices[0].serial = "ZK-SOMEWHERE-ELSE"
        result = self.once()
        self.assertIn("reports ZK-BRANCH-1", result.error)
        self.assertEqual(self.platform.submissions, [])

    def test_a_batch_the_server_finds_too_large_is_split_rather_than_dropped(self):
        self.platform.max_lines = 10
        result = self.once()
        self.assertEqual((result.stored, result.pending), (len(self.terminal.records), 0))
        self.assertTrue(all(len(s["lines"]) <= 10 for s in self.platform.submissions))

    def test_an_unreachable_terminal_is_reported_in_the_heartbeat_and_owed_records_still_go(self):
        self.once()
        self.emulator.stop()
        self.spool.add("ZK-BRANCH-1", [__import__("workin_devices.spool", fromlist=["Punch"]).Punch("1001", "2026-01-01 08:00:00", 0, 1)])
        result = self.once()
        self.assertFalse(result.reachable)
        self.assertEqual(result.stored, 1, "a record read earlier is delivered even while the terminal is down")
        entry = self.platform.heartbeats[-1]["devices"][0]
        self.assertEqual((entry["serial"], entry["reachable"]), ("ZK-BRANCH-1", False))
        self.assertIn("error", entry)
        self.emulator = Emulator(self.terminal, port=0).start()

    def test_one_terminal_refused_or_failing_does_not_stop_the_others(self):
        second = Terminal(serial="ZK-BRANCH-2")
        populate(second, ["2001"], days=2)
        with Emulator(second, port=0) as emulator:
            self.config.devices.insert(0, DeviceConfig(serial="ZK-BROKEN", kind="file", path="/nonexistent/attlog.tsv"))
            self.config.devices.append(DeviceConfig(serial="ZK-BRANCH-2", kind="zk", host="127.0.0.1",
                                                    port=emulator.port, timeout_seconds=5))
            self.platform.registered = {"ZK-BRANCH-2"}
            results = {result.serial: result for result in self.once()}
        self.assertIn("ZK-BROKEN", results)
        self.assertIn("not registered", results["ZK-BRANCH-1"].error)
        self.assertEqual(results["ZK-BRANCH-2"].stored, len(second.records), "the terminal after them still delivers")
        self.assertEqual(len(self.platform.heartbeats[-1]["devices"]), 3)

    def test_the_heartbeat_describes_what_the_terminal_said_about_itself(self):
        self.once()
        entry = self.platform.heartbeats[-1]["devices"][0]
        self.assertEqual(entry["model"], "K40")
        self.assertEqual(entry["records"], len(self.terminal.records))
        self.assertEqual(entry["record_capacity"], 100000)
        self.assertTrue(entry["reachable"])


if __name__ == "__main__":
    unittest.main()
