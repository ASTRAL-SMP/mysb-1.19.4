#!/usr/bin/env bash
export MC_VERSION="1.19.4"
exec "$(cd "$(dirname "$0")/.." && pwd)/lib/start-server.sh"
