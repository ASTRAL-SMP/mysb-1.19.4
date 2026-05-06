# 開発ガイド

MySB をビルド・テスト・拡張するための開発者向け手順。

---

## 前提

- **JDK 17**（Minecraft 1.19.4 は Java 17 でビルドする）
- Gradle は同梱 wrapper を使う（追加インストール不要）
- 動作確認には Fabric Server が必要。`test-servers/` が自動セットアップに対応

---

## ビルド

```bash
# 通常ビルド
./gradlew build

# クリーンビルド
./gradlew clean build

# 開発サーバー起動（loom の runServer タスク）
./gradlew runServer
```

成果物は `build/libs/` に出力される:

- `mysb-1.19.4-<version>.jar` — 本番用 JAR（JDA を含む shade 済み）
- `mysb-1.19.4-<version>-sources.jar` — ソース付き

`archives_base_name=mysb-1.19.4` は `gradle.properties` で管理される。

### バージョン更新

`gradle.properties` の `mod_version` を更新する。`fabric.mod.json` 側は `${version}` テンプレートで `processResources` タスクが展開する。

---

## プロジェクト構成

```
.
├── build.gradle              ビルド定義
├── gradle.properties         バージョン・依存バージョン
├── src/main/
│   ├── java/com/astralsmp/mysb/   Java ソース（パッケージ）
│   └── resources/
│       ├── fabric.mod.json   MOD メタデータ（version はテンプレート）
│       └── assets/mysb/icon.png
├── docs/                     開発ドキュメント（このディレクトリ）
├── test-servers/             複数バージョン向けローカルテストサーバー
└── README.md
```

---

## 依存関係

`build.gradle` から抜粋:

| 依存 | バージョン | 用途 |
|------|-----------|------|
| Minecraft | `1.19.4` | ゲーム本体 |
| Yarn mappings | `1.19.4+build.2` | マッピング |
| Fabric Loader | `0.19.1` | MOD ローダ |
| Fabric API | `0.87.0+1.19.4` | イベント・コマンド API |
| JDA | `5.0.0-beta.18` | Discord Bot（`opus-java` 除外） |
| nv-websocket-client | `2.14` | JDA の依存 |
| OkHttp / Okio | `4.12.0 / 3.6.0` | JDA の依存 |
| Jackson | `2.16.0` | JSON（JDA 内で使用） |
| Kotlin stdlib | `1.9.21` | JDA の依存 |
| trove4j | `3.0.3` | JDA の依存 |
| commons-collections4 | `4.4` | JDA の依存 |

`include` で指定した依存は Fabric Loom が生成する nested jar として同梱される（実行時に Loader がネストで展開する）。

---

## ローカルでのテスト

### 1. `./gradlew runServer` を使う

Fabric Loom が `run/` 配下に開発サーバーを構築する。`build.gradle` のデフォルト設定で Minecraft 1.19.4 + Fabric がセットアップされる。

### 2. `test-servers/` を使う

`test-servers/<version>/` に各バージョン向けのサーバー起動スクリプトが用意されている（1.18.2 / 1.19.2 / 1.19.4 / 1.20 / 1.21 / 1.21.4）。`test-servers/lib/start-server.sh` が共通ロジック。

```bash
cd test-servers/1.19.4
./start.sh
# NixOS なら:
nix-shell --run ./start.sh
```

初回起動時に自動で以下が行われる:

1. Fabric Server Launcher JAR を `meta.fabricmc.net` からダウンロード
2. `eula.txt` を `eula=true` で生成
3. `server.properties` を最小構成で生成（offline-mode / peaceful / flat）
4. `mods/` に MySB JAR が無い場合、優先順位に従って配置
   - ローカルリポジトリの `build/libs/mysb-<mcVer>-<modVer>.jar`
   - `gh release download v<modVer>-mc<mcVer>` で GitHub Releases から取得
5. `fabric-api` を自動取得
6. サーバ起動

複数バージョンを並列起動したい場合は `PORT` を変えて起動する（個別に上書き可能）。

詳しくは `test-servers/README.md` 参照。

### 後片付け

```bash
cd test-servers/1.19.4
rm -rf world server.jar logs mods/mysb-*.jar
```

---

## 拡張ポイント

### 新しい統計タイプを追加する

1. `TotalStatsManager#getPlayerStatTotal(ServerPlayerEntity, String)` にタイプ名ごとの switch 分岐を追加
2. 必要なら集計対象アイテム/ブロックのリストをクラス定数に追加
3. `/mysb total add <id> <displayName> <新タイプ>` で動作確認
4. `docs/commands.md` の statType 一覧にも追記

### 新しいコマンドを追加する

1. `ServerScoreboardCommands#register` 内で `CommandManager.literal("...")` のツリーに追加
2. `requires(Permissions.require(level))` で権限を指定
3. 実行ロジックは同ファイル内の private メソッドに切り出す
4. `RateLimiter#isRateLimited(uuid, "actionName")` を通す

### 新しい GUI ページを追加する

1. `GUIConstants` に使用するスロット番号を定義
2. 対応するページ Enum を GUI クラスに追加（例: `ServerScoreboardGUIv2.GUIPage`）
3. スロット 0 の切替処理で新しいページへ遷移するよう `onClickSwitchSlot()` を更新

### Discord 投稿フォーマットを変える

`DiscordStatsPublisher.java` 内の整形ロジックを修正する。2000 文字超えの分割は `DiscordConfig.getContinuationIds(statId)` / `setContinuationIds(...)` で管理される。

---

## ロギング

`ServerScoreboardLogger` 経由で出力する（`SLF4J` の薄いラッパー）。

```java
ServerScoreboardLogger.info("message");
ServerScoreboardLogger.warn("message");
ServerScoreboardLogger.error("message", exception);
```

サーバーコンソールには `[MySB] ...` のプレフィックスで表示される。

---

## スレッドセーフティ

- プレイヤー状態を保持する各マネージャは `ConcurrentHashMap` を使用
- `BulkUpdateManager` / `BatchedScoreboardUpdater` / `RateLimiter` はプレイヤー切断時に `clearPlayer(uuid)` で解放する
- Discord 関連の I/O は専用スレッド（`mysb-discord-config-save`）で非同期処理

Fabric のイベントはサーバースレッドで呼ばれる。NBT 操作やパケット送信もサーバースレッドから行う。バックグラウンドスレッド（Discord REST レスポンス等）で Minecraft API を触る場合は `server.execute(() -> ...)` でサーバースレッドに戻すこと。

---

## コーディング規約

- **Java 17**
- **エンコーディング**: UTF-8（`JavaCompile.options.encoding = "UTF-8"` が指定済）
- **メッセージ**: `Text.literal("日本語テキスト")` を直接記述
- **try-catch**: 外部 I/O や NBT 読み書きは `try-catch` で囲み `ServerScoreboardLogger` でログ出力
- **パッケージ**: `com.astralsmp.mysb[.discord]`

---

## リリース

1. `gradle.properties` の `mod_version` を上げる
2. `./gradlew clean build` で JAR を生成
3. `build/libs/mysb-1.19.4-<ver>.jar` を GitHub Releases にアップロード
4. タグ形式は `v<modVer>-mc<mcVer>`（例: `v2.3-mc1.19.4`）。`test-servers/*/start.sh` がこのタグを参照する

---

## よくある落とし穴

- **`player_stats_cache.json` は `config/` 配下ではない**: `<world>/mysb/player_stats_cache.json` に置かれる。他のスコアボード系データ（`<world>/config/mysb/` 配下）とは別ディレクトリ。
- **Discord 設定はワールドから独立**: `<serverRoot>/config/mysb/discord_config.properties`。ワールドを入れ替えても設定は残る（意図的な設計）。
- **JDA の `opus-java` を除外している**: 音声機能を使うコードを追加するとビルドが通らなくなる。音声機能は現状サポート外。
