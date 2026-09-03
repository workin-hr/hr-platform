#!/usr/bin/env python3
"""Drive the real desktop client through a fixed journey with synthetic input.

The point of a fixed script is comparability: the same clicks, in the same
order, against Java and against PHP, so the app's own request/response log can
be diffed. The app logs every ENDPOINT, QUERY, BODY and Response itself, so the
log is the evidence rather than anything this script asserts.

Coordinates are window-relative; the window is located at run time.
"""
import re
import subprocess
import sys
import time
from pathlib import Path


def sh(*args):
    return subprocess.run(args, capture_output=True, text=True).stdout.strip()


def window():
    for _ in range(30):
        out = sh('xdotool', 'search', '--name', '^Work In$')
        if out:
            wid = out.splitlines()[-1]
            geo = sh('xdotool', 'getwindowgeometry', wid)
            pos = re.search(r'Position: (-?\d+),(-?\d+)', geo)
            return wid, int(pos.group(1)), int(pos.group(2))
        time.sleep(2)
    raise SystemExit('FATAL: the app window never appeared')


def main():
    label = sys.argv[1] if len(sys.argv) > 1 else 'run'
    wid, ox, oy = window()
    sh('xdotool', 'windowactivate', '--sync', wid)
    time.sleep(2)

    def click(x, y, pause=3.0, note=''):
        subprocess.run(['xdotool', 'mousemove', str(ox + x), str(oy + y), 'click', '1'])
        if note:
            print(f'  click {note}')
        time.sleep(pause)

    def type_text(text):
        subprocess.run(['xdotool', 'type', '--delay', '60', text])
        time.sleep(1)

    def shot(name):
        subprocess.run([sys.executable, '-c', f'''
from Xlib import display, X
from PIL import Image
d = display.Display(); w = d.create_resource_object("window", {wid}); g = w.get_geometry()
raw = w.get_image(0, 0, g.width, g.height, X.ZPixmap, 0xffffffff)
Image.frombytes("RGB", (g.width, g.height), raw.data, "raw", "BGRX").save("/tmp/{label}-{name}.png")
'''])

    print('login')
    click(916, 453, 1.5, 'phone field')
    type_text('01555781818')
    click(985, 536, 1.5, 'password field')
    type_text('harness-only-Pass123!')
    shot('01-login-filled')
    click(985, 593, 8.0, 'sign in')
    shot('02-home')

    print('navigation')
    click(1218, 295, 6.0, 'Dashboard')
    shot('03-dashboard')
    click(1210, 387, 2.5, 'Employees menu')
    click(1217, 432, 6.0, 'Employees list')
    shot('04-employees')
    click(1205, 477, 6.0, 'Employee requests')
    shot('05-requests')
    click(1210, 522, 6.0, 'Leave balances')
    shot('06-leave-balances')
    click(1218, 567, 6.0, 'Penalties')
    shot('07-penalties')
    click(1190, 612, 6.0, 'Administrative decisions')
    shot('08-admin-decisions')
    click(1224, 657, 6.0, 'Assets')
    shot('09-assets')
    click(1212, 341, 2.5, 'Branches & shifts menu')
    shot('10-branches-menu')
    click(1219, 432, 6.0, 'Branches')
    shot('11-branches')
    print('done')


if __name__ == '__main__':
    main()
