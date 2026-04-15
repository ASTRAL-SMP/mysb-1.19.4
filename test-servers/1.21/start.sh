#!/usr/bin/env bash
export MC_VERSION="1.21"
export PORT="25565"
exec "$(cd "$(dirname "$0")/.." && pwd)/lib/start-server.sh"
