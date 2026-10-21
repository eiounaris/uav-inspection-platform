#!/usr/bin/env python3
"""Create local secrets once without printing them or overwriting an existing .env."""

import os
from pathlib import Path
import secrets
import sys

ROOT = Path(__file__).resolve().parents[2]


def main():
    destination = ROOT / ".env"
    generated = {
        "DB_PASSWORD": secrets.token_hex(24),
        "MYSQL_ROOT_PASSWORD": secrets.token_hex(24),
        "REDIS_PASSWORD": secrets.token_hex(24),
        "RABBITMQ_PASSWORD": secrets.token_hex(24),
        "S3_ACCESS_KEY": "GK" + secrets.token_hex(16),
        "S3_SECRET_KEY": secrets.token_hex(32),
        "GARAGE_RPC_SECRET": secrets.token_hex(32),
        "JWT_SECRET": secrets.token_hex(32),
    }
    lines = []
    for line in (ROOT / ".env.example").read_text().splitlines():
        key, separator, value = line.partition("=")
        if separator and key in generated and not value:
            line = key + "=" + generated[key]
        lines.append(line)
    try:
        descriptor = os.open(destination, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
    except FileExistsError:
        print(".env already exists; preserved. Fill missing values locally if needed.")
        return 0
    with os.fdopen(descriptor, "w") as stream:
        stream.write("\n".join(lines) + "\n")
    print("Created .env with random local credentials (mode 0600); secrets are not displayed.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
