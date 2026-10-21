#!/usr/bin/env bash
set -euo pipefail

# Fabric runtime images contain Bash but no curl. Query only local operations HTTP.
probe_port="${1:?operations port is required}"
exec 3<>"/dev/tcp/127.0.0.1/${probe_port}"
printf 'GET /healthz HTTP/1.0\r\nHost: localhost\r\n\r\n' >&3
IFS= read -r health_line <&3
[[ "$health_line" == *" 200 "* ]]
