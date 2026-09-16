"""The executable's entry point. PyInstaller runs a script, not a package, so this imports the CLI."""
import sys

from workin_devices.__main__ import main

if __name__ == "__main__":
    sys.exit(main())
