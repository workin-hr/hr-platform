import os
import tempfile
import unittest

from workin_devices import gateway as gw
from workin_devices.spool import Punch, Spool, to_attlog
from tests.support import FakePlatform


class SpoolIsTheCursor(unittest.TestCase):

    def setUp(self):
        self.dir = tempfile.TemporaryDirectory()
        self.spool = Spool(os.path.join(self.dir.name, "spool.sqlite3"))

    def tearDown(self):
        self.spool.close()
        self.dir.cleanup()

    def test_a_record_read_again_is_not_new_and_one_differing_only_in_state_is(self):
        first = [Punch("1001", "2026-09-16 08:00:00", 0, 1), Punch("1001", "2026-09-16 17:00:00", 1, 1)]
        self.assertEqual(self.spool.add("ZK1", first), 2)
        self.assertEqual(self.spool.add("ZK1", first), 0)
        self.assertEqual(self.spool.add("ZK1", [Punch("1001", "2026-09-16 08:00:00", 1, 1)]), 1,
                         "same second, other in/out state: the server keeps both, so must the spool")
        self.assertEqual(self.spool.add("ZK2", first), 2, "another terminal's identical record is its own")

    def test_only_what_is_marked_delivered_stops_being_pending(self):
        self.spool.add("ZK1", [Punch("1", f"2026-09-16 08:00:0{n}", 0, 1) for n in range(5)])
        batch = self.spool.pending("ZK1", 3)
        self.assertEqual([punch.local_time[-1] for _, punch in batch], ["0", "1", "2"])
        self.spool.mark_delivered([key for key, _ in batch])
        self.assertEqual(self.spool.counts("ZK1"), {"pending": 2, "delivered": 3})

    def test_the_wire_shape_is_the_attlog_line(self):
        self.assertEqual(to_attlog([Punch("1001", "2026-09-16 08:00:00", 0, None)]), "1001\t2026-09-16 08:00:00\t0\t\n")


class GatewayNeverClaimsADeliveryItDidNotGet(unittest.TestCase):

    def setUp(self):
        self.platform = FakePlatform()
        self.gateway = gw.Gateway(self.platform.url, self.platform.token, timeout=5)

    def tearDown(self):
        self.platform.close()

    def test_a_200_carries_the_counts_and_the_request_carries_the_token_serial_and_delivery(self):
        answer = self.gateway.submit("ZK1", "1001\t2026-09-16 08:00:00\t0\t1\n", delivery="file")
        self.assertEqual(answer["stored"], 1)
        self.assertEqual(self.platform.submissions[0]["query"], {"serial": "ZK1", "delivery": "file"})

    def test_each_refusal_is_its_own_exception(self):
        cases = {401: gw.Unauthorized, 404: gw.NotRegistered, 413: gw.TooLarge, 400: gw.Refused, 503: gw.Retryable,
                 500: gw.Retryable}
        for status, exception in cases.items():
            with self.subTest(status=status):
                self.platform.fail_with = status
                with self.assertRaises(exception):
                    self.gateway.submit("ZK1", "1\t2026-01-01 00:00:00\n")
        self.platform.fail_with = None
        with self.assertRaises(gw.Unauthorized):
            gw.Gateway(self.platform.url, "wda_wrong", timeout=5).submit("ZK1", "")

    def test_an_unreachable_server_is_retryable(self):
        with self.assertRaises(gw.Retryable):
            gw.Gateway("http://127.0.0.1:9", "t", timeout=2).submit("ZK1", "")

    def test_backoff_grows_and_is_capped(self):
        self.assertLessEqual(gw.backoff_seconds(1), gw.MIN_BACKOFF_SECONDS)
        self.assertLessEqual(gw.backoff_seconds(50), gw.MAX_BACKOFF_SECONDS)
        self.assertGreaterEqual(gw.backoff_seconds(50), gw.MAX_BACKOFF_SECONDS / 2)


if __name__ == "__main__":
    unittest.main()
