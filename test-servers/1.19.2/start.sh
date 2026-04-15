#!/usr/bin/env bash
export MC_VERSION="1.19.2"
export PORT="25562"
exec "$(cd "$(dirname "$0")/.." && pwd)/lib/start-server.sh"
