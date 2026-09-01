#!/usr/bin/env python3
"""Binary fixtures for the multipart cases: a PNG, a PDF, and a punch log.

The PNG and PDF are built rather than downloaded so the bytes are known and the
MIME sniff is predictable -- uploadFile() gates on mime_content_type(), which
reads the bytes, not the filename.
"""
import html
import struct
import subprocess
import sys
import zipfile
import zlib
from pathlib import Path

here, db = Path(sys.argv[1]), sys.argv[2]
fixtures = here / "fixtures"
fixtures.mkdir(exist_ok=True)


def png_chunk(kind: bytes, data: bytes) -> bytes:
    return (struct.pack(">I", len(data)) + kind + data
            + struct.pack(">I", zlib.crc32(kind + data) & 0xFFFFFFFF))


png = (b"\x89PNG\r\n\x1a\n"
       + png_chunk(b"IHDR", struct.pack(">IIBBBBB", 1, 1, 8, 2, 0, 0, 0))
       + png_chunk(b"IDAT", zlib.compress(b"\x00\xff\x00\x00"))
       + png_chunk(b"IEND", b""))
(fixtures / "parity.png").write_bytes(png)

pdf = (b"%PDF-1.4\n1 0 obj<</Type/Catalog/Pages 2 0 R>>endobj\n"
       b"2 0 obj<</Type/Pages/Kids[3 0 R]/Count 1>>endobj\n"
       b"3 0 obj<</Type/Page/Parent 2 0 R/MediaBox[0 0 72 72]>>endobj\n"
       b"trailer<</Root 1 0 R>>\n%%EOF\n")
(fixtures / "parity.pdf").write_bytes(pdf)

# The punch log has to name an employee_code that exists in the seeded snapshot,
# so it is keyed to the database rather than shipped as a static file.
code = subprocess.run(
    ["docker", "exec", db, "mariadb", "-uroot", "-pparity", "-N", "-B", "-e",
     "SELECT employee_code FROM workin.employees "
     "WHERE company_id=214 AND employee_code IS NOT NULL AND employee_code<>'' "
     "ORDER BY id LIMIT 1"],
    capture_output=True, text=True).stdout.strip().splitlines()
code = [c for c in code if not c.startswith("Warning")]
if not code:
    raise SystemExit("FATAL: no employee_code in company 214 -- reseed first.")
code = code[0]

# Column names come from the frozen PHP's own alias lists:
# attendance_import_employee_code_column_aliases() (xlsx_parser.php:395) and
# attendance_punch_log_datetime_column_aliases() (attendance_excel_analyzer.php:30).
rows = [("employee_code", "datetime"),
        (code, "2026-09-10 08:00:00"),
        (code, "2026-09-10 17:00:00")]

order, index = [], {}
for row in rows:
    for value in row:
        if value not in index:
            index[value] = len(order)
            order.append(value)

body = "".join(
    f'<row r="{i+1}">' + "".join(
        f'<c r="{chr(65+ci)}{i+1}" t="s"><v>{index[v]}</v></c>' for ci, v in enumerate(row)
    ) + "</row>"
    for i, row in enumerate(rows))

parts = {
    "[Content_Types].xml":
        '<?xml version="1.0" encoding="UTF-8" standalone="yes"?>'
        '<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">'
        '<Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>'
        '<Default Extension="xml" ContentType="application/xml"/>'
        '<Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/>'
        '<Override PartName="/xl/worksheets/sheet1.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/>'
        '<Override PartName="/xl/sharedStrings.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sharedStrings+xml"/>'
        "</Types>",
    "_rels/.rels":
        '<?xml version="1.0" encoding="UTF-8" standalone="yes"?>'
        '<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">'
        '<Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="xl/workbook.xml"/>'
        "</Relationships>",
    "xl/workbook.xml":
        '<?xml version="1.0" encoding="UTF-8" standalone="yes"?>'
        '<workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" '
        'xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships">'
        '<sheets><sheet name="Punches" sheetId="1" r:id="rId1"/></sheets></workbook>',
    "xl/_rels/workbook.xml.rels":
        '<?xml version="1.0" encoding="UTF-8" standalone="yes"?>'
        '<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">'
        '<Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet1.xml"/>'
        '<Relationship Id="rId2" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/sharedStrings" Target="sharedStrings.xml"/>'
        "</Relationships>",
    "xl/sharedStrings.xml":
        '<?xml version="1.0" encoding="UTF-8" standalone="yes"?>'
        f'<sst xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" count="{len(order)}" uniqueCount="{len(order)}">'
        + "".join(f"<si><t>{html.escape(s)}</t></si>" for s in order) + "</sst>",
    "xl/worksheets/sheet1.xml":
        '<?xml version="1.0" encoding="UTF-8" standalone="yes"?>'
        '<worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">'
        f"<sheetData>{body}</sheetData></worksheet>",
}
with zipfile.ZipFile(fixtures / "attendance-punches.xlsx", "w", zipfile.ZIP_DEFLATED) as archive:
    for name, content in parts.items():
        archive.writestr(name, content)

print(f"  fixtures/parity.png  ({len(png)} bytes)")
print(f"  fixtures/parity.pdf  ({len(pdf)} bytes)")
print(f"  fixtures/attendance-punches.xlsx  (employee_code {code!r})")
