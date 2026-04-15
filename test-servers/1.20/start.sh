#!/usr/bin/env bash
export MC_VERSION="1.20"
export PORT="25564"
exec "$(cd "$(dirname "$0")/.." && pwd)/lib/start-server.sh"
