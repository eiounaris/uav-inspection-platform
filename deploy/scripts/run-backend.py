#!/usr/bin/env python3
"""Start the database-enabled backend with explicit local or prod profiles."""

import argparse
import os
from pathlib import Path
import sys

from backend_env import ROOT, backend_environment, fabric_environment, java21


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--profile", choices=("local", "prod"), default="local")
    parser.add_argument("--demo", action="store_true", help="Initialize local demo accounts and one device")
    parser.add_argument("--ledger", action="store_true", help="Enable real Fabric business anchoring")
    args = parser.parse_args()
    if args.demo and args.profile != "local":
        parser.error("--demo is only available with --profile local")
    environment = backend_environment()
    if args.ledger:
        environment = fabric_environment(environment, local=args.profile == "local")
    java = java21(environment)
    jar = ROOT / "backend/target/inspection-backend-0.1.0.jar"
    if not jar.is_file():
        raise RuntimeError("Build backend/pom.xml with Maven clean verify first")
    if args.demo and not Path(environment["DEMO_CREDENTIALS_PATH"]).is_file():
        raise RuntimeError("Run python3 deploy/scripts/init-demo-credentials.py first")
    profiles = args.profile + ",database,storage,messaging" + (",demo" if args.demo else "")
    if args.ledger:
        profiles += ",ledger"
    os.chdir(ROOT)
    os.execve(java, [java, "-jar", str(jar), "--spring.profiles.active=" + profiles], environment)


if __name__ == "__main__":
    try:
        main()
    except (OSError, RuntimeError, ValueError) as error:
        print("[FAIL] " + str(error), file=sys.stderr)
        sys.exit(1)
