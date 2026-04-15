#!/usr/bin/env bash
# 共通 Fabric サーバ起動スクリプト。
# 各バージョンの start.sh は MC_VERSION / JAVA_CMD を export してからこれを exec する。
set -euo pipefail

: "${MC_VERSION:?MC_VERSION を設定してください (例: 1.21.4)}"
LOADER_VERSION="${LOADER_VERSION:-0.19.1}"
MOD_VERSION="${MOD_VERSION:-2.3}"
JAVA_CMD="${JAVA_CMD:-java}"
JVM_ARGS="${JVM_ARGS:--Xmx2G -Xms512M}"

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SERVER_DIR="$(pwd)"

echo "========================================"
echo "  MySB Fabric test server"
echo "  MC ${MC_VERSION} / Loader ${LOADER_VERSION}"
echo "  dir: ${SERVER_DIR}"
echo "========================================"

# Fabric installer の最新版を動的解決
if ! command -v curl >/dev/null; then
  echo "curl が必要です (nix-shell 経由で起動してください)" >&2
  exit 1
fi
if [ -z "${INSTALLER_VERSION:-}" ]; then
  if command -v jq >/dev/null 2>&1; then
    INSTALLER_VERSION=$(curl -sSf https://meta.fabricmc.net/v2/versions/installer | jq -r '.[0].version')
  else
    # jq がなければ grep/sed で抽出 (SIGPIPE を避けて一時ファイル経由)
    _tmp=$(mktemp)
    curl -sSf https://meta.fabricmc.net/v2/versions/installer > "$_tmp"
    INSTALLER_VERSION=$(sed -n 's/.*"version":"\([^"]*\)".*/\1/p' "$_tmp" | head -1)
    rm -f "$_tmp"
  fi
fi
echo "Installer: ${INSTALLER_VERSION}"

# Fabric server launcher JAR
if [ ! -f server.jar ]; then
  url="https://meta.fabricmc.net/v2/versions/loader/${MC_VERSION}/${LOADER_VERSION}/${INSTALLER_VERSION}/server/jar"
  echo "Downloading server launcher from: ${url}"
  curl -L -o server.jar "${url}"
fi

# EULA
[ -f eula.txt ] || echo "eula=true" > eula.txt

# server.properties (初回のみ生成)
if [ ! -f server.properties ]; then
  cat > server.properties <<EOF
server-port=${PORT:-25565}
online-mode=false
max-players=5
motd=MySB MC ${MC_VERSION} test server
enable-command-block=true
difficulty=peaceful
spawn-protection=0
level-type=minecraft\:flat
allow-flight=true
enforce-whitelist=false
EOF
fi

mkdir -p mods

# MySB jar 配置: 優先順位 = 手動配置 > GitHub Release > ローカルビルド
# (ローカルビルドは古い状態が残っている場合があるので Release を優先)
JAR_NAME="mysb-${MC_VERSION}-${MOD_VERSION}.jar"
if ! ls mods/mysb-*.jar >/dev/null 2>&1; then
  REPO_ROOT="$(cd "${SCRIPT_DIR}/../.." && pwd)"
  got=0
  if command -v gh >/dev/null 2>&1; then
    echo "Fetching from GitHub Releases: v${MOD_VERSION}-mc${MC_VERSION}"
    if (cd "${REPO_ROOT}" && gh release download "v${MOD_VERSION}-mc${MC_VERSION}" \
          --pattern "${JAR_NAME}" --dir "${SERVER_DIR}/mods/" 2>/dev/null); then
      got=1
    fi
  fi
  if [ "$got" -eq 0 ]; then
    LOCAL_JAR="${REPO_ROOT}/build/libs/${JAR_NAME}"
    if [ -f "${LOCAL_JAR}" ]; then
      echo "Fallback: using local build ${LOCAL_JAR}"
      cp "${LOCAL_JAR}" "mods/${JAR_NAME}"
    else
      echo "(mods/ に ${JAR_NAME} を手動配置するか、GitHub Release または gradlew build が必要)" >&2
      exit 1
    fi
  fi
fi

# fabric-api 自動取得 (MySB は depends に fabric-api を宣言しているので必須)
if ! ls mods/fabric-api-*.jar >/dev/null 2>&1; then
  echo "Fetching fabric-api for MC ${MC_VERSION}..."
  FA_QUERY="https://api.modrinth.com/v2/project/fabric-api/version?game_versions=%5B%22${MC_VERSION}%22%5D&loaders=%5B%22fabric%22%5D"
  FA_URL=$(curl -sSfL "$FA_QUERY" | jq -r '.[0].files[0].url // empty')
  if [ -n "$FA_URL" ]; then
    FA_NAME=$(basename "${FA_URL}")
    curl -sSfL -o "mods/${FA_NAME}" "$FA_URL"
    echo "Installed fabric-api: ${FA_NAME}"
  else
    echo "(fabric-api version の解決に失敗、mods/ に手動配置してください)" >&2
  fi
fi

echo "Starting server..."
exec ${JAVA_CMD} ${JVM_ARGS} -jar server.jar nogui
