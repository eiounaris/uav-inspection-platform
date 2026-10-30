#!/usr/bin/env python3
"""Create initial business-account credentials once without printing or overwriting passwords."""

import argparse
import json
import os
from pathlib import Path
import secrets
import stat
import sys

from backend_env import backend_environment

USERNAMES = ("owner-admin", "owner-reviewer", "owner-approver", "operator-admin", "operator-user")


def main():
    argparse.ArgumentParser(description=__doc__).parse_args()
    destination = Path(backend_environment()["BOOTSTRAP_CREDENTIALS_PATH"])
    destination.parent.mkdir(parents=True, exist_ok=True, mode=0o700)
    if destination.exists():
        if not destination.is_file() or stat.S_IMODE(destination.stat().st_mode) != 0o600:
            raise RuntimeError("Existing account credentials must be a regular file with mode 0600")
        print(f"Account credentials already exist; preserved: {destination}")
        return
    data = {"formatVersion": 1,
            "passwords": {name: secrets.token_urlsafe(24) for name in USERNAMES}}
    descriptor = os.open(destination, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
    with os.fdopen(descriptor, "w") as stream:
        json.dump(data, stream, indent=2)
        stream.write("\n")
        stream.flush()
        os.fsync(stream.fileno())
    print(f"Created {destination} (0600); passwords are not printed.")


if __name__ == "__main__":
    try:
        main()
    except (OSError, RuntimeError) as error:
        print("[FAIL] " + str(error), file=sys.stderr)
        sys.exit(1)
