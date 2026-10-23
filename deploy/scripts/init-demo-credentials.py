#!/usr/bin/env python3
"""Create local demo secrets once; never print or overwrite passwords or device tokens."""

import json
import os
from pathlib import Path
import secrets
import sys

ROOT = Path(__file__).resolve().parents[2]
DESTINATION = ROOT / ".local/demo-credentials.json"
USERNAMES = ("owner-admin", "owner-reviewer", "owner-approver", "operator-admin",
             "operator-user", "operator-outsider")


def main():
    DESTINATION.parent.mkdir(parents=True, exist_ok=True, mode=0o700)
    if DESTINATION.exists():
        if DESTINATION.stat().st_mode & 0o777 != 0o600:
            raise RuntimeError("Existing demo credentials must have mode 0600")
        print("Demo credentials already exist; preserved: .local/demo-credentials.json")
        return
    data = {"formatVersion": 1, "passwords": {name: secrets.token_urlsafe(24) for name in USERNAMES},
            "droneTokens": {"UAV-DEMO-001": secrets.token_hex(32)}}
    descriptor = os.open(DESTINATION, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
    with os.fdopen(descriptor, "w") as stream:
        json.dump(data, stream, indent=2)
        stream.write("\n")
        stream.flush()
        os.fsync(stream.fileno())
    print("Created .local/demo-credentials.json (0600); secrets are not printed.")


if __name__ == "__main__":
    try:
        main()
    except (OSError, RuntimeError) as error:
        print("[FAIL] " + str(error), file=sys.stderr)
        sys.exit(1)
