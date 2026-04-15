# MySB test servers

各 Minecraft バージョン向けの Fabric サーバを、ローカル（特に NixOS）で簡単に起動するためのスクリプト群。

## 起動方法

### NixOS (推奨)

各バージョンディレクトリに `shell.nix` があるので、その中で `start.sh` を実行します:

```bash
cd test-servers/1.21.4
nix-shell --run ./start.sh
```

必要な JDK（1.20 以下は 17、1.21+ は 21）と `curl` / `gh` / `jq` が shell に自動で入ります。

### 通常の Linux / macOS

適切な JDK と `curl` を先に入れておけば、`./start.sh` 単体でも起動できます:

```bash
cd test-servers/1.21.4
./start.sh
```

## 初回起動時の動作

1. Fabric Server Launcher JAR を `https://meta.fabricmc.net` から取得 (`server.jar`)
2. `eula.txt` を `eula=true` で生成
3. `server.properties` を最低限の設定（offline-mode / peaceful / flat）で生成
4. `mods/` に MySB の JAR が無ければ、以下の優先順位で配置:
   - ローカルリポジトリの `build/libs/mysb-<ver>-2.3.jar`
   - `gh release download v2.3-mc<ver>` で GitHub Releases から取得
5. サーバ起動

2 回目以降は 1〜4 はスキップ、そのまま起動。

## カスタマイズ

- `server.properties` を編集すれば次回起動に反映
- `mods/` に他の mod を追加可能
- `JVM_ARGS` 環境変数でメモリ等を上書き: `JVM_ARGS="-Xmx4G" ./start.sh`

## ディレクトリ構造

```
test-servers/
├── lib/start-server.sh     # 共通起動ロジック
├── 1.18.2/                 # 各バージョン (JDK 17)
├── 1.19.2/                 # 〃
├── 1.19.4/                 # 〃
├── 1.20/                   # 〃
├── 1.21/                   # JDK 21
└── 1.21.4/                 # JDK 21
```

## 後片付け

```bash
cd test-servers/1.21.4
rm -rf world server.jar logs mods/mysb-*.jar  # 再起動で再取得される
```

また、`world/` を消すと次回起動で新規生成されます。
