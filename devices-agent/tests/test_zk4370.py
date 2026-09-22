import errno
import socket
import struct
import unittest
from datetime import datetime
from unittest import mock

from workin_devices import zk4370 as zk
from workin_devices.sim.zk4370 import Emulator, Terminal, populate

PINS = ["1001", "1002", "1003", "2001", "2002"]


class ReadOnlyClientAgainstTheEmulator(unittest.TestCase):

    def terminal(self, **kwargs):
        terminal = Terminal(**kwargs)
        populate(terminal, PINS, days=30)
        return terminal

    def test_every_record_format_and_transport_reads_the_same_log(self):
        for record_size in (40, 16):
            for udp in (False, True):
                with self.subTest(record_size=record_size, udp=udp):
                    terminal = self.terminal(record_size=record_size)
                    with Emulator(terminal, port=0) as emulator:
                        with zk.ZkClient("127.0.0.1", emulator.port, timeout=5, udp=udp) as client:
                            records = client.attendance()
                            self.assertEqual(client.serial_number(), terminal.serial)
                            self.assertEqual(client.sizes().records, len(terminal.records))
                    self.assertEqual([(r.user_id, r.timestamp, r.punch, r.status) for r in records],
                                     [(r.user_id, r.when, r.in_out, r.verify) for r in terminal.records])
                    self.assertGreater(len(terminal.attlog_buffer()), 1000, "the chunked read path is exercised")

    def test_a_comm_key_is_answered_and_a_wrong_one_refused(self):
        terminal = self.terminal(comm_key=4321)
        with Emulator(terminal, port=0) as emulator:
            with zk.ZkClient("127.0.0.1", emulator.port, timeout=5, password=4321) as client:
                self.assertTrue(client.attendance())
            with self.assertRaises(zk.ZkAuthError):
                zk.ZkClient("127.0.0.1", emulator.port, timeout=5, password=1).connect()

    def test_the_oldest_format_reports_the_slot_because_the_user_table_is_never_read(self):
        terminal = self.terminal(record_size=8)
        with Emulator(terminal, port=0) as emulator:
            with zk.ZkClient("127.0.0.1", emulator.port, timeout=5) as client:
                records = client.attendance()
        self.assertTrue(all(record.user_id.startswith("uid:") for record in records))
        self.assertNotIn(9, terminal.commands_seen, "the user table (names, device passwords) is not requested")

    def test_nothing_the_client_sends_can_change_a_terminal(self):
        terminal = self.terminal()
        with Emulator(terminal, port=0) as emulator:
            with zk.ZkClient("127.0.0.1", emulator.port, timeout=5) as client:
                client.attendance()
                client.device_time()
                client.firmware_version()
                for write in (15, 202, 8, 18, 14, 1004, 1005, 31):
                    with self.assertRaises(zk.ZkError):
                        client._send(write)
        self.assertEqual(terminal.write_attempts, [])
        self.assertTrue(set(terminal.commands_seen) <= zk.READ_ONLY_COMMANDS)

    def test_the_clock_and_the_time_encoding_round_trip(self):
        when = datetime(2026, 9, 16, 23, 59, 58)
        self.assertEqual(zk.decode_time(zk.encode_time(when)), when)

    def test_an_impossible_time_is_a_terminal_error_not_a_crash(self):
        # 2026-02-31: day index 30 of month index 1, which datetime refuses.
        value = ((26 * 12 * 31) + (1 * 31) + 30) * 86400
        with self.assertRaises(zk.ZkError):
            zk.decode_time(struct.pack("<I", value))

    def test_an_unreachable_terminal_is_an_error_not_a_hang(self):
        with self.assertRaises(zk.ZkError):
            zk.ZkClient("127.0.0.1", 9, timeout=1).connect()

    def test_a_lost_arp_exchange_is_retried_rather_than_ending_the_read(self):
        """A Wi-Fi site answers EHOSTUNREACH at once for a terminal that is there."""
        terminal = self.terminal()
        connect = socket.socket.connect
        # Two literal refusals, not `CONNECT_ATTEMPTS - 1`: deriving the injection from the constant
        # makes the test agree with whatever the constant says, so `CONNECT_ATTEMPTS = 1` -- the
        # field defect restored -- kept the whole suite green.
        refusals = iter([errno.EHOSTUNREACH] * 2)

        def flaky(sock, address):
            code = next(refusals, None)
            if code is not None:
                raise OSError(code, "No route to host")
            return connect(sock, address)

        with Emulator(terminal, port=0) as emulator:
            with mock.patch.object(zk, "CONNECT_RETRY_SECONDS", 0), \
                 mock.patch.object(socket.socket, "connect", flaky):
                with zk.ZkClient("127.0.0.1", emulator.port, timeout=5) as client:
                    records = client.attendance()
        self.assertEqual(len(records), len(terminal.records))

    def test_a_terminal_that_stays_unreachable_is_still_the_same_error(self):
        unreachable = mock.Mock(side_effect=OSError(errno.EHOSTUNREACH, "No route to host"))
        with mock.patch.object(zk, "CONNECT_RETRY_SECONDS", 0), \
             mock.patch.object(socket.socket, "connect", unreachable):
            with self.assertRaises(zk.ZkError) as caught:
                zk.ZkClient("192.0.2.1", 4370, timeout=1).connect()
        self.assertEqual(unreachable.call_count, 3)
        self.assertIn("No route to host", str(caught.exception))

    def test_the_retry_is_bounded_to_the_four_seconds_the_runbook_promises(self):
        """D-274, the runbook and the pull request all promise a terminal that is genuinely absent
        costs at most four extra seconds. Nothing pinned that: the two tests above patched the
        interval to zero and read the attempt count off the constant, so both 1 attempt and 30 were
        green -- one restores the defect this change exists to fix, the other spends a minute per
        connect on a visit that reads several terminals."""
        self.assertEqual(zk.CONNECT_ATTEMPTS, 3)
        self.assertEqual(zk.CONNECT_RETRY_SECONDS, 2.0)
        CONNECT_RETRY_SECONDS = zk.CONNECT_RETRY_SECONDS
        waited = []
        unreachable = mock.Mock(side_effect=OSError(errno.EHOSTUNREACH, "No route to host"))
        with mock.patch.object(zk.time, "sleep", waited.append), \
             mock.patch.object(socket.socket, "connect", unreachable):
            with self.assertRaises(zk.ZkError):
                zk.ZkClient("192.0.2.1", 4370, timeout=1).connect()
        self.assertEqual(unreachable.call_count, 3)
        # The waits only; the connects themselves fail instantly on these errnos. `zk.time` is the
        # `time` module, so this mock is process-wide for the duration of the test -- another
        # thread's `time.sleep` would land in the same list. Asserted as "the two retry waits are
        # there and the total is within budget" rather than as list equality, so a stray entry
        # cannot make this flake.
        self.assertEqual(waited.count(CONNECT_RETRY_SECONDS), 2, waited)
        self.assertLessEqual(sum(waited), 4.0, f"an absent terminal waits {sum(waited)}s")

    def test_a_refused_connection_is_answered_not_retried(self):
        """Only a lost ARP exchange is worth a second attempt; a refusal is the terminal's answer."""
        refused = mock.Mock(side_effect=OSError(errno.ECONNREFUSED, "Connection refused"))
        with mock.patch.object(zk, "CONNECT_RETRY_SECONDS", 0), \
             mock.patch.object(socket.socket, "connect", refused):
            with self.assertRaises(zk.ZkError):
                zk.ZkClient("127.0.0.1", 9, timeout=1).connect()
        self.assertEqual(refused.call_count, 1)

    def test_the_checksum_and_the_comm_key_match_pyzks_on_known_vectors(self):
        # Computed with pyzk 0.9, which has been run against real terminals.
        self.assertEqual(zk.checksum(struct.pack("<4H", 1000, 0, 0, 65534)), 64535)
        self.assertEqual(zk.checksum(struct.pack("<4H", 11, 0, 4321, 7) + b"~SerialNumber\x00"), 42504)
        self.assertEqual(zk.checksum(struct.pack("<4H", 50, 0, 65000, 65533) + b"\x01"), 485)
        self.assertEqual(zk.make_commkey(0, 1).hex(), "617d3279")
        self.assertEqual(zk.make_commkey(12345, 4321).hex(), "6de13269")
        self.assertEqual(zk.make_commkey(999999, 65000).hex(), "22813294")


class PyzkAgreesWithTheEmulator(unittest.TestCase):
    """pyzk has been run against real terminals; agreeing with it is the emulator's best credential."""

    def setUp(self):
        try:
            from zk import ZK  # noqa: F401
        except ImportError:
            self.skipTest("pyzk is not installed; pip install pyzk==0.9 to run this cross-check")

    def test_pyzk_and_this_client_read_the_same_records(self):
        from zk import ZK
        for udp in (False, True):
            for key in (0, 999):
                with self.subTest(udp=udp, key=key):
                    terminal = Terminal(comm_key=key)
                    populate(terminal, PINS, days=20)
                    with Emulator(terminal, port=0) as emulator:
                        connection = ZK("127.0.0.1", port=emulator.port, timeout=5, password=key, force_udp=udp,
                                        ommit_ping=True).connect()
                        theirs = connection.get_attendance()
                        connection.disconnect()
                        with zk.ZkClient("127.0.0.1", emulator.port, timeout=5, password=key, udp=udp) as client:
                            ours = client.attendance()
                    self.assertEqual([(a.user_id, a.timestamp, a.status, a.punch) for a in theirs],
                                     [(r.user_id, r.timestamp, r.status, r.punch) for r in ours])


if __name__ == "__main__":
    unittest.main()
