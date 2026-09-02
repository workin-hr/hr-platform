#!/usr/bin/env python3
"""Binary fixtures for the multipart cases: a PNG, a PDF, and a punch log.

The PNG and PDF are built rather than downloaded so the bytes are known and the
MIME sniff is predictable -- uploadFile() gates on mime_content_type(), which
reads the bytes, not the filename.
"""
import html
import re
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


# ---------------------------------------------------------------------------
# The employee sheet the import cases replay.
#
# The template is the application's own output (D-085), and only a data row is
# added -- filling it by hand would test this script's idea of the format. The
# row's shift, branch, department and job title are looked up from the seeded
# company rather than hard-coded, because employee_excel_row_to_payload()
# resolves all four by NAME and a stale name fails the row instead of importing
# it, which would leave the case green while covering nothing.
# ---------------------------------------------------------------------------
def one(sql: str) -> str:
    out = subprocess.run(
        ["docker", "exec", db, "mariadb", "-uroot", "-pparity", "-N", "-B", "-e", sql],
        capture_output=True, text=True).stdout
    rows = [r for r in out.strip().splitlines() if r and not r.startswith("Warning")]
    if not rows:
        raise SystemExit(f"FATAL: no row for: {sql}")
    return rows[0]


COMPANY = 214
shift = one(f"SELECT name FROM workin.shifts WHERE company_id={COMPANY} ORDER BY id LIMIT 1")
# The four names must be mutually consistent or row_to_payload rejects the row:
# the job title must belong to the department (job_title_department_mismatch)
# and the department must be on the branch (department_branch_mismatch). One
# query picks the triple, and the names are read back by id -- interpolating a
# name into the next query would break on the first branch called O'Brien, and
# mariadb's batch output escapes a tab, so a CONCAT separator does not survive
# the round trip either.
job_title_id = one(
    "SELECT j.id FROM workin.job_titles j "
    "JOIN workin.departments d ON d.id = j.department_id "
    "JOIN workin.department_branches db ON db.department_id = d.id "
    "JOIN workin.branches b ON b.id = db.branch_id AND b.is_active=1 "
    f"WHERE j.company_id={COMPANY} AND d.company_id={COMPANY} AND d.is_active=1 "
    f"AND b.company_id={COMPANY} ORDER BY j.id LIMIT 1")
job_title = one(f"SELECT name FROM workin.job_titles WHERE id={job_title_id}")
department = one(
    f"SELECT d.name FROM workin.departments d "
    f"JOIN workin.job_titles j ON j.department_id = d.id WHERE j.id={job_title_id}")
branch = one(
    "SELECT b.name FROM workin.branches b "
    "JOIN workin.department_branches db ON db.branch_id = b.id "
    "JOIN workin.job_titles j ON j.department_id = db.department_id "
    f"WHERE j.id={job_title_id} AND b.is_active=1 ORDER BY b.id LIMIT 1")

# Free of the snapshot: employee_code_exists and phone_exists_globally both
# fail the row, and a collision would turn the success case into a refusal that
# still answered 200 -- covering nothing while looking covered.
IMPORT_CODE = "990100"
IMPORT_PHONE = "01099911001"
for label, sql in (
        ("employee_code", f"SELECT COUNT(*) FROM workin.employees WHERE company_id={COMPANY} AND employee_code='{IMPORT_CODE}'"),
        ("phone", f"SELECT COUNT(*) FROM workin.employees WHERE phone='{IMPORT_PHONE}'")):
    if one(sql) != "0":
        raise SystemExit(f"FATAL: the import fixture's {label} is already taken in the snapshot.")

# Column letters are the template's own order, read from its header row rather
# than assumed, so a template that gains a column does not silently shift every
# value one cell to the left.
template = fixtures / "employees-template.xlsx"
sheet = zipfile.ZipFile(template).read("xl/worksheets/sheet1.xml").decode()
# Opening tags only: an empty header cell is written self-closing, and a
# pattern that also required a closing tag swallowed the cells between two of
# them and reported 7 columns instead of 28.
header_cells = re.findall(r'<c r="([A-Z]+)2"', sheet)
values = {
    "employee_code": IMPORT_CODE, "first_name": "Parity", "last_name": "Import",
    "country_code": "+20", "phone": IMPORT_PHONE, "password": "123456",
    "shift_name": shift, "is_mobile_attendance_enabled": "\u0646\u0639\u0645",
    "branch_name": branch, "department_name": department, "job_title_name": job_title,
    "expected_daily_hours": "8", "contract_duration_years": "1", "salary_basic": "5000",
}
# The header text carries the Arabic label plus an example, so the column key
# comes from position: the template writes the columns in employee_excel_columns_meta()
# order, which is the order this list mirrors.
KEYS = ["employee_code", "first_name", "last_name", "country_code", "phone", "password",
        "shift_name", "national_id", "birth_date", "gender", "address",
        "is_mobile_attendance_enabled", "hire_date", "branch_name", "department_name",
        "job_title_name", "expected_daily_hours", "contract_duration_years", "salary_basic"]
if len(header_cells) < len(KEYS):
    raise SystemExit(
        f"FATAL: the employee template has {len(header_cells)} columns, fewer than the "
        f"{len(KEYS)} this fixture fills. Re-read employee_excel_columns_meta().")
row_xml = "".join(
    f'<c r="{header_cells[i]}3" t="inlineStr"><is><t>{html.escape(values[key])}</t></is></c>'
    for i, key in enumerate(KEYS) if values.get(key))
filled = fixtures / "employees-filled.xlsx"
source = zipfile.ZipFile(template)
with zipfile.ZipFile(filled, "w", zipfile.ZIP_DEFLATED) as out:
    for item in source.infolist():
        content = source.read(item.filename)
        if item.filename == "xl/worksheets/sheet1.xml":
            content = content.replace(
                b"</sheetData>", f'<row r="3">{row_xml}</row>'.encode() + b"</sheetData>")
        out.writestr(item, content)

print(f"  fixtures/parity.png  ({len(png)} bytes)")
print(f"  fixtures/parity.pdf  ({len(pdf)} bytes)")
print(f"  fixtures/attendance-punches.xlsx  (employee_code {code!r})")
print(f"  fixtures/employees-filled.xlsx  ({shift} / {branch} / {department} / {job_title})")
