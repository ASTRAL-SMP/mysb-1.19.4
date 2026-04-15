#!/usr/bin/env bash
export MC_VERSION="1.18.2"
exec "$(cd "$(dirname "$0")/.." && pwd)/lib/start-server.sh"
