# アーキテクチャ解説（開発者向け）

MySB は **Fabric サーバー専用 MOD**。クライアントに MOD を入れずに、パケットレベルで「見た目だけ」プレイヤーごとに差し替える。  
本ドキュメントでは中核コンポーネント、tick ループ、仮想オブジェクティブ/変換の仕組み、Discord 連携の構造を説明する。

**対象パッケージ**: `com.astralsmp.mysb`（ソース: `src/main/java/com/astralsmp/mysb/`）

---

## 全体構造

```
┌────────────────────────────────────────────────────────────────────────┐
│  Fabric Dedicated Server                                               │
│                                                                        │
│  ServerOnlyScoreboardMod (DedicatedServerModInitializer)               │
│       │                                                                │
│       ├─ CommandRegistrationCallback → ServerScoreboardCommands        │
│       ├─ ServerLifecycleEvents (START / STOP)                          │
│       ├─ ServerPlayConnectionEvents (JOIN / DISCONNECT)                │
│       ├─ ServerTickEvents.END_SERVER_TICK                              │
│       └─ Player action events                                          │
│              (BreakBlock / UseBlock / UseItem / AttackEntity / Death)  │
│                                                                        │
│  Core                                                                  │
│       ServerScoreboardManager   — スコアボード読込/送信/差分更新        │
│       TotalStatsManager         — 累計統計の計算・反映                  │
│       CustomScoreboardPacketSender — 仮想オブジェクティブ送信           │
│       ScoreboardAutoTransform   — auto_transform.json ルール           │
│       PlayerStatsCache          — オフラインプレイヤー統計 JSON キャッシュ│
│                                                                        │
│  Throughput control                                                    │
│       BatchedScoreboardUpdater  — パケットバッチング                    │
│       BulkUpdateManager         — 連続アクションの集約                  │
│       RateLimiter               — プレイヤー/アクション単位 cooldown     │
│       NetworkLoadMonitor        — 適応的スロットリング                  │
│                                                                        │
│  GUI (ChestScreenHandler ベース)                                       │
│       ServerScoreboardGUIv2, ServerScoreboardAdminGUI,                 │
│       discord/DiscordSettingsGUI                                       │
│                                                                        │
│  Discord                                                               │
│       DiscordBot, DiscordConfig, DiscordManager,                       │
│       DiscordScheduler, DiscordStatsPublisher, SlashCommandListener    │
└────────────────────────────────────────────────────────────────────────┘
```

---

## エントリーポイントとイベント登録

`ServerOnlyScoreboardMod#onInitializeServer` (`ServerOnlyScoreboardMod.java:36`) が Fabric から呼ばれる。ここで登録されるものは以下。

| イベント | ハンドラ | 目的 |
|----------|----------|------|
| `CommandRegistrationCallback` | `ServerScoreboardCommands.register` | `/mysb`, `/mysbdiscord` 登録 |
| `ServerLifecycleEvents.SERVER_STARTED` | `onServerStarted` | 各マネージャ初期化・NBT 読込・Discord Bot 起動 |
| `ServerLifecycleEvents.SERVER_STOPPING` | `onServerStopping` | 保存・Bot シャットダウン |
| `ServerPlayConnectionEvents.JOIN` | `onPlayerJoin` | プレイヤーにスコアボードを送信 |
| `ServerPlayConnectionEvents.DISCONNECT` | `onPlayerDisconnect` | プレイヤー毎のキャッシュを解放（メモリリーク防止） |
| `ServerTickEvents.END_SERVER_TICK` | `onServerTick` | 後述の tick ループ |
| `PlayerBlockBreakEvents.AFTER` | (lambda) | ブロック破壊 → BulkUpdate or 即時更新 |
| `UseBlockCallback` | (lambda) | ブロック設置 → BulkUpdate or 即時更新 |
| `ServerLivingEntityEvents.AFTER_DEATH` | (lambda) | キル/デス統計の即時更新 |
| `UseItemCallback` | (lambda) | アイテム使用時の統計即時更新 |
| `AttackEntityCallback` | (lambda) | 攻撃時の統計即時更新 |

---

## Tick ループ

`ServerOnlyScoreboardMod#onServerTick` (`ServerOnlyScoreboardMod.java:175`) が毎 tick 実行される。

```
// 毎 tick
ServerScoreboardManager.updateClientScoreboardsDifferential(server);
TotalStatsManager.updateAllTotalStats();
BatchedScoreboardUpdater.flushAllBatches();
DiscordScheduler.onServerTick();

// server.getTicks() % UPDATE_INTERVAL_TICKS == 0
ServerScoreboardManager.updateClientScoreboards(server);

// server.getTicks() % 6000 == 0 (5 分)
PlayerStatsCache.saveCache();
ServerScoreboardManager.saveScoreboardData(server);
```

### Differential vs Full 更新

- **Differential (`updateClientScoreboardsDifferential`)**: 前フレームと比較して変更のあったスコアだけパケットを飛ばす。バニラの挙動に近い。
- **Full (`updateClientScoreboards`)**: `UPDATE_INTERVAL_TICKS`（デフォルト 1）ごとに、表示中のオブジェクティブ全体を再送信。切替処理や新規参加時の整合性確保用。

### 統計即時更新の流れ

プレイヤーアクション → `BulkUpdateManager.recordUpdate(uuid, kind)` → 1 秒内に 5 件超えたら `executeBulkUpdate` で一括、それ未満なら `TotalStatsManager.scheduleInstantUpdate()` で即時 tick 反映。

---

## コア: ServerScoreboardManager

`ServerScoreboardManager.java`（約 1060 行）。MOD の中核。内部構造は大きく分けて:

- `Map<UUID, PlayerScoreboardData>`: プレイヤー毎の「表示中オブジェクティブ」情報
- `Map<String, ScoreboardObjective>`: 通常スコアボードのキャッシュ
- `ScoreboardAutoTransform` への参照（変換ルール）
- NBT I/O（`<world>/config/mysb/player_scoreboards.dat`）

主要な公開 API:

| メソッド | 役割 |
|---------|------|
| `loadScoreboardData(server)` / `saveScoreboardData(server)` | NBT 永続化 |
| `onPlayerJoin(player)` / `onPlayerDisconnect(player)` | セッション管理 |
| `setClientDisplayObjective(player, objName)` | あるプレイヤーにだけ指定 obj を表示 |
| `updateClientScore(...)` | 単一スコアの反映 |
| `updateClientScoreboards(server)` | 全員分のフル送信 |
| `updateClientScoreboardsDifferential(server)` | 差分送信 |
| `setCustomScoreboard(...)` / `setCustomScore(...)` | 仮想スコアボードの追加・更新 |
| `checkAndApplyAutoTransforms(player)` | 新規プレイヤーに自動変換を適用 |

---

## 仮想オブジェクティブ（Custom Scoreboard）

「サーバー側のスコアボードデータは変更せず、クライアントにだけ別の見た目を送る」仕組み。

### データ構造

- `CustomScoreboardData` (`CustomScoreboardData.java`): プレイヤーの UUID を持つ個別のオブジェクティブ定義。
  - 内部名: `mysb_custom_<UUID先頭8文字>`
  - `displayName`, スコア一覧（`Map<String, Integer>`）を保持。
  - NBT でシリアライズされ `player_scoreboards.dat` に保存される。

### 送信（CustomScoreboardPacketSender）

- `ScoreboardObjectiveUpdateS2CPacket`（add/remove/update）
- `ScoreboardDisplayS2CPacket`（サイドバーに割り当て）
- `ScoreboardPlayerUpdateS2CPacket`（スコア設定）

これらを対象プレイヤーの `ServerPlayNetworkHandler.sendPacket` で直接飛ばす。サーバーの `ScoreboardManager` には触らないので、他のプレイヤーには影響しない。

### 差分変換送信

`sendDifferentialTransformedScores`: 前回送信したスコアをキャッシュし、差分のみを再送する（デルタ圧縮）。プレイヤー切断時には `clearTransformedScoreCache(uuid)` で破棄する。

---

## 変換システム（Transform）

プレイヤーが見る「オブジェクティブ表示名」や「スコア値」を、個別にオフセットできる仕組み。

### 3 つのクラス

| クラス | 役割 |
|--------|------|
| `ScoreboardTransformData` | 変換ルールの in-memory 表現。`objective名 → 新表示名` と `player名 → ±オフセット` のペア |
| `ScoreboardAutoTransform` | `auto_transform.json` のロード・書き出し |
| `ScoreboardDataReader` | バニラの `scoreboard.dat` から NBT を直接読み、プレイヤーごとのスコアを取り出す |

### auto_transform.json の構造

```json
{
  "enabled": false,
  "autoApplyToNewPlayers": false,
  "transformRules": [
    {
      "objectiveName": "test_points",
      "newDisplayName": "ポイントランキング",
      "scoreOffsets": {
        "Sim_256": 1000
      }
    }
  ]
}
```

`enabled=true` かつ `autoApplyToNewPlayers=true` の場合、プレイヤー JOIN 時に `ServerScoreboardManager#checkAndApplyAutoTransforms` が呼ばれ、マッチするルールを当該プレイヤー専用の `ScoreboardTransformData` として適用する。仮想オブジェクティブ名は `mysb_virtual_<UUID先頭8文字>`。

---

## TotalStatsManager

サーバー全体の累計統計を管理する。`TotalStatsManager.java`（約 613 行）。

### 登録された統計タイプ

`getPlayerStatTotal(player, statType)` が振る舞いを切り替える。現在サポートされているのは [`commands.md#利用可能な-stattype`](commands.md#利用可能な-stattype) を参照。

### オンライン vs オフライン

- **オンラインプレイヤー**: `player.getStatHandler().getStat(...)` で取得
- **オフラインプレイヤー**: `PlayerStatsCache` に事前に集計してキャッシュした値を使用（世界の `stats/<uuid>.json` を直接パースする必要はない）

### 更新フロー

1. `updateAllTotalStats()` が毎 tick 呼ばれる
2. 登録中の各統計について全対象プレイヤーの値を取得
3. サーバースコアボードの対応するオブジェクティブへ書き込み
4. `updateClientScoreboardsDifferential` が差分をクライアントに送信

---

## スループット制御

### BatchedScoreboardUpdater

プレイヤー毎にスコア更新を 1 tick 内で溜め込み、まとめて 1 パケットに近い形で送る。`MAX_BATCH_SIZE` と `BATCH_TIMEOUT_MS` で挙動を制御。プレイヤー切断時は `clearPlayer(uuid)`。

### BulkUpdateManager

連続的なブロック破壊/設置（1 秒あたり 5 件以上）を検出し、通常の「即時更新」を集約して一括計算に切り替える。逐次更新による CPU スパイクを防ぐ。

### RateLimiter

`UUID × アクション種別` で cooldown を管理。コマンド実行と GUI オープンに使う。`ServerScoreboardConfig.COMMAND_COOLDOWN_MS / GUI_OPEN_COOLDOWN_MS` を読み取る。プレイヤー切断時は `clearPlayer(uuid)`。

### NetworkLoadMonitor

5 秒スライディングウィンドウでパケットレートを計測し、4 段階の負荷レベル（LOW / MEDIUM / HIGH / CRITICAL）を返す。TPS 15 未満なら強制的に高負荷扱い。`CRITICAL` で送信をスロットリングする。

---

## GUI システム

Chest 型 `SimpleGui`（54 スロット、6 行）を使う。`GUIConstants` にスロット位置を定義:

```java
SLOT_SWITCH = 0   // 左上: ページ切替
SLOT_RESET  = 4   // 上段中央: デフォルトに戻す
SLOT_CLOSE  = 8   // 右上: 閉じる
SLOT_PREV   = 18  // 前のページ
SLOT_NEXT   = 26  // 次のページ
// 27..53 がアイテム表示スロット（ITEMS_PER_PAGE = 27）
```

### ServerScoreboardGUIv2

- ページ: `STATISTICS` / `SCOREBOARD`
- アイコン: 紙（オブジェクティブ）、金のリンゴ（トータル統計）、本/コンパス（ページ切替）

### ServerScoreboardAdminGUI

- ページ: `STATS`（統計の有効/無効トグル）/ `OBJECTIVES`（スコアボード一覧）
- `/mysb admin gui` から開く（OP3 不要、OP0 で開けるが個別サブコマンドに権限差がある）

### DiscordSettingsGUI

- Discord 設定を表示・編集するためのフォーム UI
- `discord_config.properties` の値を読み書きする

---

## Discord 連携

### 構成

```
Minecraft Server (Fabric MOD)
  │
  ├─ DiscordBot (JDA: Gateway WebSocket 接続)
  │    └─ SlashCommandListener  → /refresh を Bot が受信
  │
  ├─ DiscordConfig      ─ Properties 永続化
  ├─ DiscordManager     ─ REST API (java.net.http.HttpClient)
  ├─ DiscordScheduler   ─ 1 時間ごとの自動更新
  └─ DiscordStatsPublisher ─ フォーラムチャンネルへの投稿ロジック
```

`DiscordBot` は JDA（5.0.0-beta.18）を使って Gateway に接続する（スラッシュコマンド受付のため）。一方 **投稿側**（`DiscordManager`/`DiscordStatsPublisher`）は JDA を介さず、標準 `HttpClient` で Discord REST API v10 を叩いているので、JDA がオフラインのときでも投稿自体は走る。

### 投稿の仕組み

- 投稿先は「フォーラムチャンネル」。統計 ID ごとに 1 つのスレッドが作られ、そこに「単一メッセージを編集し続ける」形で更新する。
- メッセージ長（2000 文字）を超えた場合は `CONTINUATION_IDS_<statId>` に追加メッセージの ID を保存し、続きを別メッセージとして管理する。
- レート制限（429）を受けた場合は `DiscordManager` 側で backoff しつつリトライする。

### 設定キー（`discord_config.properties`）

| キー | 意味 |
|------|------|
| `DISCORD_BOT_ENABLED` | Bot（JDA Gateway 接続）を有効にするか |
| `DISCORD_TOKEN` | Bot トークン |
| `DISCORD_FORUM_CHANNEL_ID` | 投稿先フォーラムチャンネル ID |
| `DISCORD_ENABLED_<statId>` | 統計ごとの投稿有効フラグ |
| `THREAD_ID_<statId>` | 自動作成されたスレッドの ID |
| `MESSAGE_ID_<statId>` | 編集対象の主メッセージ ID |
| `CONTINUATION_IDS_<statId>` | 継続メッセージの ID リスト（カンマ区切り） |

---

## データ永続化サマリー

| 種別 | パス | 形式 | 書き込み元 |
|------|------|------|-----------|
| プレイヤー設定 / カスタム / 変換 | `<world>/config/mysb/player_scoreboards.dat` | NBT | `ServerScoreboardManager` |
| トータル統計設定 | `<world>/config/mysb/total_stats_config.dat` | NBT | `TotalStatsManager` |
| 自動変換ルール | `<world>/config/mysb/auto_transform.json` | JSON | `ScoreboardAutoTransform` |
| オフライン統計キャッシュ | `<world>/mysb/player_stats_cache.json` | JSON | `PlayerStatsCache` |
| Discord 設定 | `<serverRoot>/config/mysb/discord_config.properties` | Properties | `DiscordConfig` |

> **パスに関する注意**  
> スコアボード系データは **ワールドフォルダ配下** の `config/mysb/` に置かれる（`MinecraftServer#getSavePath(WorldSavePath.ROOT)` 起点）。  
> Discord 設定のみ、サーバールート（`runDirectory`）配下の `config/mysb/` に置かれる。別のワールドに切り替えても Bot 設定は維持される。

保存タイミング:
- 5 分ごと（6000 tick）に自動保存
- `SERVER_STOPPING` で最終保存
- `DiscordConfig` は書き込みが発生するたびに debounced で非同期保存（`mysb-discord-config-save` スレッド）
