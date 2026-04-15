#!/usr/bin/env bash
export MC_VERSION="1.21.4"
export PORT="25566"
exec "$(cd "$(dirname "$0")/.." && pwd)/lib/start-server.sh"
