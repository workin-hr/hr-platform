#!/usr/bin/env python3
"""Create -> update -> delete through the real desktop UI, on ONE record.

The record is identified by a unique name and reached through the list's own
SEARCH box before every action, so each step operates on the row it created.

An earlier version clicked the first row by position. The list re-sorts after an
update, so the delete landed on an unrelated department -- the flow was verified
but the journey was not reproducible, which is the point of scripting it.

The app's own log records every request; this script asserts nothing about the
server, it only drives the UI. Verify afterwards with the id the log reports.
"""
import re
from pathlib import Path
import subprocess
import sys
import time

UNIQUE = f'RuntimeVerify {int(time.time())}'


def sh(*args):
    return subprocess.run(args, capture_output=True, text=True).stdout.strip()


def window():
    for _ in range(30):
        out = sh('xdotool', 'search', '--name', '^Work In$')
        if out:
            wid = out.splitlines()[-1]
            pos = re.search(r'Position: (-?\d+),(-?\d+)', sh('xdotool', 'getwindowgeometry', wid))
            return wid, int(pos.group(1)), int(pos.group(2))
        time.sleep(2)
    raise SystemExit('FATAL: the app window never appeared')


def main():
    wid, ox, oy = window()
    sh('xdotool', 'windowactivate', '--sync', wid)
    time.sleep(2)

    def click(x, y, pause=3.0, note=''):
        subprocess.run(['xdotool', 'mousemove', str(ox + x), str(oy + y), 'click', '1'])
        if note:
            print(f'  {note}')
        time.sleep(pause)

    def type_text(text):
        subprocess.run(['xdotool', 'type', '--delay', '55', text])
        time.sleep(1)

    def capture():
        from Xlib import display as _d, X as _X
        from PIL import Image as _I
        w = _d.Display().create_resource_object("window", int(wid))
        g = w.get_geometry()
        raw = w.get_image(0, 0, g.width, g.height, _X.ZPixmap, 0xffffffff)
        return _I.frombytes("RGB", (g.width, g.height), raw.data, "raw", "BGRX")

    def shot(name):
        subprocess.run([sys.executable, '-c', f'''
from Xlib import display, X
from PIL import Image
d = display.Display(); w = d.create_resource_object("window", {wid}); g = w.get_geometry()
raw = w.get_image(0, 0, g.width, g.height, X.ZPixmap, 0xffffffff)
Image.frombytes("RGB", (g.width, g.height), raw.data, "raw", "BGRX").save("/tmp/crud-{name}.png")
'''])

    ROW1_Y, ROW_H = 470, 62

    def list_calls(log='/tmp/crud-java.log'):
        return Path(log).read_text(errors='replace').count('departments/list?')

    def row_menu_x(y):
        """x of the row's kebab menu, found by its glyph rather than assumed.

        The table re-lays out its columns to fit content, so the menu column
        moves when a name grows -- renaming the department shifted it from 467
        to 410 and the delete click landed on empty canvas. The glyph is drawn
        in a flat #333333 that no neighbouring text or border uses.
        """
        img = capture()
        for x in range(300, 620):
            if all(img.getpixel((x, y + dy)) == (51, 51, 51) for dy in (-4, 0, 4)):
                return x
        raise SystemExit(f'FATAL: no row menu glyph found on the row at y={y}')

    def refetch_and_take_row1(note):
        """Refetch from the server, then act on row 1.

        The app does not re-fetch after a create (it splices the new row in
        locally) and its log truncates the list response, so neither is usable
        as an oracle. What is reliable is the endpoint's own ordering --
        `ORDER BY created_at DESC, id DESC` in departments/list.php -- which puts
        the department this journey created first on any fresh fetch, and the
        rename does not touch created_at so it stays first. The in-app refresh
        button forces that fetch; assert_id() then proves the row that was
        clicked really was the created one.
        """
        before = list_calls()
        click(286, 171, 5.0, 'refresh data')
        if list_calls() <= before:
            raise SystemExit('FATAL: the refresh button did not refetch the list')
        mx = row_menu_x(470)
        click(mx, 470, 3.0, note)
        return mx

    def assert_id(action, want, log='/tmp/crud-java.log'):
        """The mutation must have carried the id this journey created."""
        for line in reversed(Path(log).read_text(errors='replace').splitlines()):
            if f'departments/{action}' in line and 'ENDPOINT' in line:
                got = re.search(r'id=(\d+)', line)
                got = got.group(1) if got else None
                if got != str(want):
                    raise SystemExit(f'FATAL: {action} hit id={got}, not the created id={want}')
                print(f'  {action} carried id={want}')
                return
        raise SystemExit(f'FATAL: no departments/{action} request was sent')

    # login
    click(916, 453, 1.5, 'phone')
    type_text('01555781818')
    click(985, 536, 1.5, 'password')
    type_text('harness-only-Pass123!')
    click(985, 593, 9.0, 'sign in')

    # departments
    click(1212, 341, 2.5, 'Branches & shifts menu')
    click(1217, 430, 6.0, 'Departments')

    # create
    click(133, 171, 4.0, 'add new department')
    click(530, 246, 1.5, 'name')
    type_text(UNIQUE)
    click(530, 330, 3.0, 'branch picker')
    click(928, 457, 1.5, 'tick a branch')
    click(519, 680, 3.0, 'choose')
    shot('01-create-ready')
    click(530, 390, 7.0, f'create {UNIQUE!r}')
    shot('02-created')

    # update -- found by search, not by position
    def created_id(log='/tmp/crud-java.log'):
        """The id the create response itself returned -- not the list's first row."""
        lines = Path(log).read_text(errors='replace').splitlines()
        for i, line in enumerate(lines):
            if 'departments/create' in line and 'ENDPOINT' in line:
                for follow in lines[i:i + 6]:
                    if follow.startswith('Response::: 201'):
                        return re.search(r'id: (\d+)', follow).group(1)
        raise SystemExit('FATAL: departments/create did not return 201')

    created = created_id()
    print(f'  created department id={created}')
    shot('03-found-for-edit')
    mx = refetch_and_take_row1('row menu')
    click(mx - 50, 543, 4.0, 'edit')
    click(530, 246, 1.0, 'name')
    subprocess.run(['xdotool', 'key', '--clearmodifiers', 'ctrl+a'])
    type_text(f'{UNIQUE} RENAMED')
    click(530, 390, 7.0, 'save')
    shot('04-updated')

    # delete -- found by search again, because the list re-sorted
    shot('05-found-for-delete')
    assert_id('update', created)
    mx = refetch_and_take_row1('row menu')
    click(mx - 50, 578, 3.5, 'delete')
    click(781, 517, 7.0, 'confirm')
    assert_id('delete', created)
    shot('06-deleted')
    print(f'\nunique name used: {UNIQUE}')


if __name__ == '__main__':
    main()
