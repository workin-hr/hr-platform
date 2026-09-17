import http.client
import http.server
import json
import os
import tempfile
import threading
import unittest
from datetime import datetime, timedelta

from workin_devices import capture, gateway as gw, usb
from workin_devices.config import DeviceConfig
from workin_devices.sim.hikvision import HikTerminal, serve
from workin_devices.sources import HikvisionSource, parse_attlog_lines
from tests.support import FakePlatform


class HikvisionPullReadsAttendanceAndNothingElse(unittest.TestCase):

    def setUp(self):
        self.terminal = HikTerminal(password="s3cret")
        self.terminal.populate(["1001", "1002"], days=3)
        self.server = serve(self.terminal)
        self.device = DeviceConfig(serial=self.terminal.serial, kind="hikvision", host="127.0.0.1",
                                   port=self.server.server_address[1], username="admin", password="s3cret",
                                   window_hours=24 * 5, page_size=4, timeout_seconds=5)

    def tearDown(self):
        self.server.shutdown()
        self.server.server_close()

    def test_digest_authentication_paging_and_the_attendance_allowlist(self):
        with HikvisionSource(self.device) as source:
            description = source.describe()
            punches = source.read()
        self.assertEqual(description.serial, self.terminal.serial)
        self.assertEqual(len(punches), 3 * 2 * 2, "check-in and check-out per employee per day; no door or refused-card events")
        self.assertEqual({punch.status for punch in punches}, {0, 1})
        self.assertTrue(all(len(punch.local_time) == 19 and "T" not in punch.local_time for punch in punches))

    def test_a_wrong_password_is_an_error(self):
        self.device.password = "wrong"
        with self.assertRaises(Exception):
            HikvisionSource(self.device).describe()


class UsbImport(unittest.TestCase):

    def setUp(self):
        self.platform = FakePlatform()
        self.gateway = gw.Gateway(self.platform.url, self.platform.token, timeout=5)

    def tearDown(self):
        self.platform.close()

    def test_every_line_goes_marked_as_a_file_in_batches_the_server_accepts(self):
        export = "".join(f"{1000 + n:>9}\t2026-09-{10 + n % 5:02d} 08:00:00\t0\t1\t0\t0\r\n" for n in range(50))
        export += "garbage line\r\n"
        self.platform.max_lines = 16
        totals = usb.import_file(self.gateway, "ZK1", export.encode(), batch_size=40)
        self.assertEqual(totals["lines"], 51)
        self.assertTrue(all(s["query"]["delivery"] == "file" for s in self.platform.submissions))
        sent = [line for s in self.platform.submissions for line in s["lines"]]
        self.assertIn("garbage line", sent, "an unreadable line goes to the server to be quarantined, not dropped")
        self.assertTrue(sent[0].startswith("1000\t"), "the PIN's padding is removed")

    def test_the_file_source_reads_the_same_shape(self):
        punches = parse_attlog_lines("﻿     1001\t2026-09-16 08:00:00\t0\t1\n\nbad\n")
        self.assertEqual([(p.pin, p.local_time, p.status, p.verify) for p in punches], [("1001", "2026-09-16 08:00:00", 0, 1)])


class CaptureRecordsWhatATerminalSends(unittest.TestCase):

    def setUp(self):
        self.dir = tempfile.TemporaryDirectory()
        self.received = []
        received = self.received

        class Upstream(http.server.BaseHTTPRequestHandler):
            def log_message(self, *args):
                pass

            def do_POST(self):
                body = self.rfile.read(int(self.headers.get("Content-Length") or 0))
                received.append((self.path, self.headers.get("Host"), self.headers.get("Content-Type"), body))
                self.send_response(200)
                self.send_header("Content-Length", "5")
                self.end_headers()
                self.wfile.write(b"OK: 1")

        self.upstream = http.server.ThreadingHTTPServer(("127.0.0.1", 0), Upstream)
        threading.Thread(target=self.upstream.serve_forever, daemon=True).start()

    def tearDown(self):
        self.upstream.shutdown()
        self.upstream.server_close()
        self.dir.cleanup()

    def start(self, upstream):
        server = capture.serve("127.0.0.1", 0, self.dir.name, upstream, "devices.localhost", out=lambda *_: None)
        threading.Thread(target=server.serve_forever, daemon=True).start()
        return server

    def post(self, server, path, body, content_type="application/x-www-form-urlencoded"):
        connection = http.client.HTTPConnection("127.0.0.1", server.server_address[1], timeout=5)
        try:
            connection.request("POST", path, body=body, headers={"Content-Type": content_type})
            response = connection.getresponse()
            return response.status, response.read()
        finally:
            connection.close()

    def test_a_forwarded_upload_reaches_the_receiver_intact_under_its_own_hostname_and_is_recorded(self):
        server = self.start(f"http://127.0.0.1:{self.upstream.server_address[1]}")
        body = b"1001\t2026-09-16 08:00:00\t0\t1\t0\t0\t0\r\n"
        status, answer = self.post(server, "/iclock/cdata?SN=CGE123&table=ATTLOG&Stamp=9", body)
        server.shutdown()
        server.server_close()
        self.assertEqual((status, answer), (200, b"OK: 1"))
        self.assertEqual(self.received, [("/iclock/cdata?SN=CGE123&table=ATTLOG&Stamp=9", "devices.localhost",
                                          "application/x-www-form-urlencoded", body)])
        files = sorted(os.listdir(os.path.join(self.dir.name, "CGE123")))
        self.assertEqual(len(files), 3, files)
        with open(os.path.join(self.dir.name, "CGE123", [f for f in files if f.endswith(".request.bin")][0]), "rb") as handle:
            self.assertEqual(handle.read(), body)

    def test_nothing_but_the_receiver_is_forwarded_and_no_credential_is_recorded(self):
        server = self.start(f"http://127.0.0.1:{self.upstream.server_address[1]}")
        connection = http.client.HTTPConnection("127.0.0.1", server.server_address[1], timeout=5)
        try:
            connection.request("POST", "/admin/login", body=b"password=secret",
                               headers={"Authorization": "Bearer wda_x", "Cookie": "WORKIN_ADMIN_SESSION=abc"})
            response = connection.getresponse()
            status = response.status
            response.read()
        finally:
            connection.close()
        server.shutdown()
        server.server_close()
        self.assertEqual(status, 404)
        self.assertEqual(self.received, [], "a path outside /iclock never reaches the platform")
        recorded = ""
        for root, _, files in os.walk(self.dir.name):
            for name in files:
                if name.endswith(".json"):
                    with open(os.path.join(root, name), encoding="utf-8") as handle:
                        recorded += handle.read()
        self.assertNotIn("wda_x", recorded)
        self.assertNotIn("WORKIN_ADMIN_SESSION", recorded)

    def test_when_the_platform_is_down_the_terminal_is_told_error_never_ok(self):
        server = self.start("http://127.0.0.1:9")
        status, answer = self.post(server, "/iclock/cdata?SN=CGE123&table=ATTLOG", b"1\t2026-01-01 00:00:00")
        server.shutdown()
        server.server_close()
        self.assertEqual(status, 502)
        self.assertNotIn(b"OK", answer)

    def recorded(self, suffix):
        found = {}
        for root, _, files in os.walk(self.dir.name):
            for name in files:
                if name.endswith(suffix):
                    with open(os.path.join(root, name), "rb") as handle:
                        found[name] = handle.read()
        return found

    def test_biometric_and_enrolment_records_are_forwarded_but_never_written_or_printed(self):
        printed = []
        server = capture.serve("127.0.0.1", 0, self.dir.name, f"http://127.0.0.1:{self.upstream.server_address[1]}",
                               "devices.localhost", out=printed.append)
        threading.Thread(target=server.serve_forever, daemon=True).start()
        operlog = (b"OPLOG 4\t0\t2026-09-16 08:00:00\t0\t0\t0\t0\r\n"
                   b"USER PIN=1001\tName=Ahmed Ali\tPasswd=4321\tCard=998877\r\n"
                   b"FP PIN=1001\tFID=6\tSize=1024\tValid=1\tTMP=TEMPLATEBYTES\r\n"
                   b"face PIN=1001\tFID=50\tTMP=FACEBYTES\r\n")
        template = b"PIN=1001\tFID=6\tTMP=BIODATABYTES"
        photo = b"\xff\xd8\xff\xe0JFIFPHOTOBYTES"
        self.post(server, "/iclock/cdata?SN=CGE123&table=OPERLOG&Stamp=1", operlog)
        self.post(server, "/iclock/cdata?SN=CGE123&table=BIODATA&Stamp=1", template)
        self.post(server, "/iclock/fdata?SN=CGE123&table=ATTPHOTO", photo, "application/octet-stream")
        server.shutdown()
        server.server_close()
        self.assertEqual([body for *_, body in self.received], [operlog, template, photo],
                         "the platform still receives every upload unchanged; it discards these itself")
        on_disk = b"".join(self.recorded(".request.bin").values()) + b"".join(self.recorded(".json").values())
        on_screen = "\n".join(printed)
        for secret in (b"TEMPLATEBYTES", b"FACEBYTES", b"BIODATABYTES", b"PHOTOBYTES", b"Ahmed Ali", b"4321", b"998877"):
            self.assertNotIn(secret, on_disk)
            self.assertNotIn(secret.decode("latin-1"), on_screen)
        self.assertIn(b"OPLOG 4\t0\t2026-09-16 08:00:00", on_disk, "operation lines are the evidence and stay")
        notes = [json.loads(body).get("request_body_withheld") for body in self.recorded(".json").values()]
        self.assertIn({"lines": {"USER": 1, "FP": 1, "FACE": 1}}, notes)
        self.assertIn({"table": "BIODATA", "bytes": len(template)}, notes)
        self.assertIn({"table": "ATTPHOTO", "bytes": len(photo)}, notes)

    def test_only_what_the_platform_keeps_is_recorded_whatever_the_upload_is_called(self):
        long_template = b"QUJD" * 100
        cases = [
            ("/iclock/cdata?SN=X&table=IDCARD", "text/plain",
             b"IDCARD PIN=1\tIDNum=29001011234567\tName=Ahmed Ali\tPhoto=PHOTOB64"),
            ("/iclock/cdata?SN=X&table=tabledata&tablename=templatev10", "text/plain",
             b"templatev10 size=1\r\npin=1\tfingerid=6\ttemplate=TEMPLATEB64"),
            ("/iclock/cdata?SN=X&table=OTHER", "application/octet-stream", b"TMP=QUJDREVGR0g="),
            ("/iclock/cdata?SN=X", "text/plain", b"PIN=1\tName=Ahmed Ali"),
            ("/iclock/querydata?SN=X&type=tabledata", "text/plain", b"user uid=1\tname=Ahmed Ali"),
        ]
        for path, content_type, body in cases:
            kept, note = capture.recordable(path, content_type, body)
            self.assertEqual(kept, b"", path)
            self.assertEqual(note["bytes"], len(body), path)
        kept, note = capture.recordable("/iclock/cdata?SN=X&table=OPERLOG", "text/plain",
                                        b"OPLOG 4\t0\t2026-09-16 08:00:00\t0\r\nIDCARD PIN=1\tIDNum=29001011234567\r\n")
        self.assertEqual(kept, b"OPLOG 4\t0\t2026-09-16 08:00:00\t0\r\n")
        self.assertEqual(note, {"lines": {"IDCARD": 1}})
        kept, note = capture.recordable("/iclock/cdata?SN=X&table=ATTLOG", "text/plain",
                                        b"1001\t2026-09-16 08:00:00\t0\t1\t" + long_template + b"\r\n")
        self.assertNotIn(long_template, kept, "a long encoded run is withheld even inside a kept upload")
        self.assertTrue(kept.startswith(b"1001\t2026-09-16 08:00:00\t0\t1\t"))
        self.assertEqual(note, {"encoded_runs": 1})
        kept, note = capture.recordable("/iclock/devicecmd?SN=X", "text/plain",
                                        b"ID=1&Return=0&CMD=INFO\nPIN=1\tName=Ahmed Ali\n")
        self.assertEqual((kept, note), (b"ID=1&Return=0&CMD=INFO\n", {"lines": {"other": 1}}))
        kept, note = capture.recordable("/hik/DS-K1T", "application/json",
                                        b'{"eventType":"AccessControllerEvent","faceData":"' + long_template + b'"}')
        self.assertIn(b'"eventType":"AccessControllerEvent"', kept)
        self.assertNotIn(long_template, kept)
        for content_type in ("text/plain", "application/x-www-form-urlencoded", ""):
            kept, _ = capture.recordable("/other/brand", content_type, b"name=Ahmed Ali&photo=PHOTO")
            self.assertEqual(kept, b"", content_type)

    def test_standalone_keeps_an_events_text_parts_and_withholds_its_pictures(self):
        server = capture.serve("127.0.0.1", 0, self.dir.name, None, None, out=lambda *_: None)
        threading.Thread(target=server.serve_forever, daemon=True).start()
        body = (b"--MIME_boundary\r\n"
                b"Content-Disposition: form-data; name=\"event_log\"\r\n"
                b"Content-Type: application/json\r\n\r\n"
                b"{\"eventType\":\"AccessControllerEvent\",\"majorEventType\":5,\"subEventType\":75}\r\n"
                b"--MIME_boundary\r\n"
                b"Content-Disposition: form-data; name=\"Picture\"; filename=\"face.jpg\"\r\n"
                b"Content-Type: image/jpeg\r\n\r\n"
                b"\xff\xd8\xff\xe0FACEPICTUREBYTES\r\n"
                b"--MIME_boundary--\r\n")
        status, _ = self.post(server, "/hik/DS-K1T", body, "multipart/form-data; boundary=MIME_boundary")
        self.post(server, "/hik/DS-K1T", b"\xff\xd8\xff\xe0RAWPICTUREBYTES", "image/jpeg")
        server.shutdown()
        server.server_close()
        self.assertEqual(status, 503)
        on_disk = b"".join(self.recorded(".request.bin").values()) + b"".join(self.recorded(".json").values())
        self.assertIn(b"\"subEventType\":75", on_disk, "the event's shape is what the visit records")
        self.assertNotIn(b"FACEPICTUREBYTES", on_disk)
        self.assertNotIn(b"RAWPICTUREBYTES", on_disk)
        self.assertIn(b"image/jpeg", on_disk, "a withheld part is still named, so the recording shows it was sent")

    def test_standalone_it_handshakes_without_a_time_zone(self):
        status, body = capture.standalone_answer("GET", "/iclock/cdata?SN=CGE123&options=all")
        self.assertEqual(status, 200)
        self.assertTrue(body.startswith(b"GET OPTION FROM: CGE123\r\n"))
        self.assertNotIn(b"TimeZone", body, "a recorder must not reconfigure the terminal's clock")
        status, _ = capture.standalone_answer("POST", "/iclock/cdata?SN=CGE123&table=ATTLOG")
        self.assertEqual(status, 503, "an upload is never acknowledged, so the terminal keeps it for its own system")
        status, _ = capture.standalone_answer("POST", "/hik/DS-K1T")
        self.assertEqual(status, 503)


if __name__ == "__main__":
    unittest.main()
