# 設定ファイルリファレンス

MySB が読み書きするファイルとその内容をまとめる。

---

## 1. Java 定数（`ServerScoreboardConfig.java`）

現状この MOD には **ランタイム可変の config ファイル（ymlなど）は用意されていない**。挙動を変更したい場合は `ServerScoreboardConfig.java` を書き換えて再ビルドする。

| 定数名 | デフォルト | 意味 |
|--------|-----------|------|
| `MAX_OBJECTIVES_PER_PLAYER` | `50` | 1 プレイヤーが作れる仮想オブジェクティブの最大数 |
| `MAX_OBJECTIVE_NAME_LENGTH` | `64` | オブジェクティブ名の最大文字数 |
| `MAX_SCORES_PER_OBJECTIVE` | `100` | 1 オブジェクティブに登録できる最大スコア数 |
| `COMMAND_COOLDOWN_MS` | `500` | コマンド実行の連打抑制（ms） |
| `GUI_OPEN_COOLDOWN_MS` | `1000` | GUI オープンの連打抑制（ms） |
| `UPDATE_INTERVAL_TICKS` | `1` | スコアボードフル更新の間隔 |
| `STATS_UPDATE_INTERVAL_TICKS` | `1` | 統計更新の間隔 |
| `ALLOW_SELF_MODIFICATION` | `true` | プレイヤーが自分のスコアボードを変更できるか |
| `REQUIRE_OP_FOR_SELF_CUSTOM` | `false` | 自己カスタムスコアボードに OP が必要か |
| `SELF_MODIFICATION_OP_LEVEL` | `0` | 自己変更に必要な OP レベル |
| `FAKE_PLAYER_SCORE_ENABLED` | `true` | Fake Player（Carpet 等）をスコアに含めるか。**これだけ実行時可変** |

`isValidObjectiveName(String)` はオブジェクティブ名を `[a-zA-Z0-9_\-.]+` で制限するバリデータ。ユーザーから受ける名前はこの関数を通る。

---

## 2. Discord 設定（`discord_config.properties`）

**保存場所**: `<serverRoot>/config/mysb/discord_config.properties`  
**形式**: Java Properties（`key=value`）  
**書き込み元**: `DiscordConfig.java`

### キー一覧

| キー | 型 | 意味 |
|------|----|------|
| `DISCORD_BOT_ENABLED` | boolean | Bot（JDA Gateway）の起動可否。`false` ならスラッシュコマンドは使えないが、REST 投稿は動く |
| `DISCORD_TOKEN` | string | Discord Bot トークン |
| `DISCORD_FORUM_CHANNEL_ID` | number | 投稿先フォーラムチャンネル ID |
| `DISCORD_ENABLED_<statId>` | boolean | 統計 ID ごとの投稿可否（例: `DISCORD_ENABLED_mined=true`） |
| `THREAD_ID_<statId>` | number | 自動作成済みスレッド ID |
| `MESSAGE_ID_<statId>` | number | 編集対象の主メッセージ ID |
| `CONTINUATION_IDS_<statId>` | csv | 2000 文字を超えた際の継続メッセージ ID リスト（カンマ区切り） |

### サンプル

```properties
DISCORD_BOT_ENABLED=true
DISCORD_TOKEN=YOUR_BOT_TOKEN_HERE
DISCORD_FORUM_CHANNEL_ID=1234567890123456789

DISCORD_ENABLED_mined=true
THREAD_ID_mined=2345678901234567890
MESSAGE_ID_mined=3456789012345678901

DISCORD_ENABLED_placed=false
```

### 書き込み方法

プロセス内からの変更は `DiscordConfig#setXxx(...)` が debounced で非同期保存する（`mysb-discord-config-save` スレッド）。サーバー停止時 (`SERVER_STOPPING`) にも `DiscordConfig.shutdown()` で確実にフラッシュされる。

直接編集する場合はサーバー停止中に行うのが安全。

---

## 3. 自動変換ルール（`auto_transform.json`）

**保存場所**: `<world>/config/mysb/auto_transform.json`  
**形式**: JSON  
**書き込み元**: `ScoreboardAutoTransform.java`

### スキーマ

```json
{
  "enabled": false,
  "autoApplyToNewPlayers": false,
  "transformRules": [
    {
      "objectiveName": "<ソースとなるバニラオブジェクティブ名>",
      "newDisplayName": "<クライアントに見せる表示名>",
      "scoreOffsets": {
        "<プレイヤー名>": <加算する整数>
      }
    }
  ]
}
```

### フィールド

| キー | 型 | 意味 |
|------|----|------|
| `enabled` | boolean | 自動変換全体の on/off |
| `autoApplyToNewPlayers` | boolean | プレイヤー JOIN 時にルールを自動適用するか |
| `transformRules[].objectiveName` | string | 対象となるスコアボードオブジェクティブの名前 |
| `transformRules[].newDisplayName` | string | そのプレイヤーの画面に表示される名前（§記号で色付け可） |
| `transformRules[].scoreOffsets` | map | プレイヤー名 → 加算値。負の値で減算もできる |

### 実体

ファイルを書き換えた後は `/mysb reload` で再読み込みできる。`ServerScoreboardManager#checkAndApplyAutoTransforms` が JOIN 時に呼ばれるため、実際の反映はプレイヤー再ログイン or `reload` の後。

仮想オブジェクティブ名は `mysb_virtual_<UUID先頭8文字>` でプレイヤーごとにユニーク。

---

## 4. NBT データ（`*.dat`）

### `<world>/config/mysb/player_scoreboards.dat`

- **書き込み元**: `ServerScoreboardManager`
- **保存内容**:
  - プレイヤーが選択中のスコアボード（UUID → objective 名）
  - 各プレイヤーのカスタムスコアボード (`CustomScoreboardData`)
  - 各プレイヤー用の変換データ (`ScoreboardTransformData`)

### `<world>/config/mysb/total_stats_config.dat`

- **書き込み元**: `TotalStatsManager`
- **保存内容**:
  - 有効化された統計 ID のリスト
  - 除外プレイヤー（UUID）のリスト
  - カスタム追加された統計定義（id / displayName / statType）

どちらも NBT バイナリなので手で編集するのは推奨しない。変更したいときはコマンドや GUI を使うこと。

---

## 5. オフライン統計キャッシュ（`player_stats_cache.json`）

**保存場所**: `<world>/mysb/player_stats_cache.json`（`config/` ではない点に注意）  
**形式**: JSON  
**書き込み元**: `PlayerStatsCache.java`

オフラインプレイヤーの統計値をキャッシュする。構造は `プレイヤー名 → { 統計 ID → 値 }` のネストマップ。

### 旧パス移行

旧パッケージ時代の `<world>/serverscoreboard/player_stats_cache.json` が見つかった場合、起動時に自動で新パスへ移動する（`PlayerStatsCache#migrateLegacyCacheIfNeeded`）。既存のワールドから乗り換える場合は、特別な作業は不要。

---

## 6. 保存タイミングまとめ

| データ | タイミング |
|--------|-----------|
| `player_scoreboards.dat` | 5 分ごと + サーバー停止時 |
| `total_stats_config.dat` | コマンド実行による変更時 + サーバー停止時 |
| `auto_transform.json` | 明示的な保存 API が呼ばれたとき |
| `player_stats_cache.json` | 5 分ごと + サーバー停止時 |
| `discord_config.properties` | 値の変更時に debounced で非同期 + サーバー停止時 |

---

## 7. 注意事項

- **ワールドごとに分離される** データとそうでないデータが混在している。マルチワールド運用をしている場合、Discord 設定だけはサーバールート共通になる点に注意。
- `*.dat` を手動削除するとプレイヤー設定は失われる。バックアップ推奨。
- `auto_transform.json` は `enabled=true` になっていないとどのルールも適用されない。誤って有効化しないためのデフォルトガード。
