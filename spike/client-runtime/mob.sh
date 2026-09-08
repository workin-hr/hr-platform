#!/usr/bin/env bash
# Tiny adb driver for the mobile runtime run: tap, type, capture.
export PATH="/home/afaqy/dev/sdk/android/platform-tools:$PATH"
tap()  { adb shell input tap "$1" "$2"; sleep "${3:-2}"; }
typ()  { adb shell input text "$(printf '%s' "$1" | sed 's/ /%s/g')"; sleep 1; }
key()  { adb shell input keyevent "$1"; sleep "${2:-1}"; }
swipe(){ adb shell input swipe "$1" "$2" "$3" "$4" "${5:-300}"; sleep "${6:-2}"; }
shot() { adb exec-out screencap -p > "/tmp/$1.png"; python3 -c "
from PIL import Image; im=Image.open('/tmp/$1.png'); im.thumbnail((520,1160)); im.save('/tmp/$1-s.png')"; }
