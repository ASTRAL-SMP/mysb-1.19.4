# コマンドリファレンス

MySB が提供するすべてのコマンドを記載する。  
実装は `src/main/java/com/astralsmp/mysb/ServerScoreboardCommands.java` と `src/main/java/com/astralsmp/mysb/discord/SlashCommandListener.java` にある。

> **権限レベル表記について**  
> `OP0` = 全員実行可能（非 OP も含む）、`OP3` = `level=3`、`OP4` = `level=4`。  
> Minecraft の `ops.json` に登録されたプレイヤーの `level` が該当値以上であれば実行できる。

---

## /mysb

プレイヤー向けのスコアボード/統計操作コマンド。ルート引数なしで実行すると GUI が開く。

### /mysb

- **権限**: OP0
- **挙動**: 統計/スコアボード選択 GUI（6 行 54 スロットのチェスト UI）を開く。初期ページは「統計」。
- **制限**: `GUI_OPEN_COOLDOWN_MS` (1000 ms) のクールダウンあり。

### /mysb reload

- **権限**: OP4
- **挙動**: 次のデータを再読み込みする。
  - `auto_transform.json`（自動変換ルール）
  - `player_scoreboards.dat` / `total_stats_config.dat`（NBT 永続化データ）
- **補足**: ディスク上の値を即座にメモリへ反映する。運用中に設定ファイルを手で書き換えたときの再読込用。

### /mysb version

- **権限**: OP0
- **挙動**: MOD のバージョン文字列を表示（`fabric.mod.json` の `version` を読み出す）。

---

## /mysb area

チャンク範囲を指定し、その範囲内で起きたブロック破壊数・設置数を月別にスコアボードへ反映する。範囲は同一ディメンション内のチャンク X/Z 矩形で、Y 座標は見ない。

### /mysb area pos1 `<id>`

- **権限**: OP3
- **挙動**: 実行者が現在いるチャンクを、指定 ID の範囲始点として記録する。

### /mysb area pos2 `<id>`

- **権限**: OP3
- **挙動**: 実行者が現在いるチャンクを、指定 ID の範囲終点として記録する。

### /mysb area create `<id>` `<displayName>`

- **権限**: OP3
- **挙動**: `pos1` / `pos2` で記録したチャンク範囲から、月間採掘・月間設置のスコアボード objective を作成する。
- **作成される objective**: 採掘は `mam_<id由来>`, 設置は `map_<id由来>`。

### /mysb area create `<id>` `<displayName>` `<x1>` `<z1>` `<x2>` `<z2>`

- **権限**: OP3
- **挙動**: チャンク座標を直接指定して範囲を作成する。ディメンションはコマンド実行元のワールドになる。

### /mysb area month `<yyyy-MM|current>`

- **権限**: OP3
- **挙動**: 既存の月間範囲スコアボードへ表示する月を切り替える。例: `/mysb area month 2026-05`。

### /mysb area list

- **権限**: OP3
- **挙動**: 登録済み範囲、チャンク座標、作成済み objective 名、現在の表示月を一覧表示する。

### /mysb area remove `<id>`

- **権限**: OP3
- **挙動**: 指定範囲と対応する月間採掘・設置 objective を削除する。

### /mysb area update

- **権限**: OP3
- **挙動**: 月間範囲スコアボードを手動で再反映する。

---

## /mysb total

サーバー全体の累計統計（Total Stats）の定義を管理する。GUI の「統計ページ」に表示される項目は、ここで追加・削除する。

### /mysb total

- **権限**: OP0
- **挙動**: サブコマンド一覧を表示。

### /mysb total add `<id>` `<displayName>` `<statType>`

- **権限**: OP0
- **引数**:
  - `id`: 統計の一意 ID（英数字、スペース不可）
  - `displayName`: GUI 表示名（スペースを含める場合は `"..."` で囲む）
  - `statType`: 集計対象の種類。詳細は下記「利用可能な statType」
- **挙動**: 新規統計を登録し、対応するスコアボードオブジェクティブを作成する。

### /mysb total list

- **権限**: OP0
- **挙動**: 登録済みのトータル統計を列挙する。

### /mysb total update

- **権限**: OP0
- **挙動**: すべてのトータル統計について、全プレイヤー分を強制再計算してスコアボードに反映する。  
  通常は tick イベントで自動更新されるため、手動実行は障害対応・確認用。

### /mysb total remove `<id>`

- **権限**: OP0
- **挙動**: 指定 ID のカスタム統計を削除する。デフォルト統計（`mined` 等）は削除不可。

### 利用可能な statType

`TotalStatsManager#getPlayerStatTotal` で定義されている集計タイプ。

| statType | 集計内容 |
|----------|----------|
| `mined` | 全ブロック破壊数合計 |
| `placed` | 全アイテム使用（設置を含む）数合計 |
| `total_all_ores_mined` | 鉱石類（coal/iron/copper/gold/redstone/lapis/diamond/emerald/quartz/nether_gold の通常/deepslate 版、計 19 種）の合計 |
| `coral_block_mined` | サンゴブロック（通常/dead の 10 種）の合計 |
| `glass_placed` | 色付きガラス（17 色）の設置合計 |
| `carved_pumpkin_placed` | 彫られたカボチャの設置 |
| `placed_anvil` | 金床の設置 |
| `traded_with_villager` | 村人との取引回数（`CUSTOM` 統計） |
| `deepslate_mined` | 深層岩の破壊 |

---

## /mysb admin

管理者向けのコマンド群。統計の有効/無効切替、プレイヤー除外、Fake Player 表示制御。

### /mysb admin gui

- **権限**: OP0
- **挙動**: 管理者 GUI を開く。統計管理ページ / スコアボード管理ページを切り替え可能。

### /mysb admin exclude `<player>`

- **権限**: OP0
- **挙動**: 指定プレイヤーをトータル統計の集計対象から除外する。

### /mysb admin exclude list

- **権限**: OP0
- **挙動**: 現在除外されているプレイヤーを一覧表示。

### /mysb admin include `<player>`

- **権限**: OP0
- **挙動**: 除外リストから外し、再度集計対象に戻す。

### /mysb admin stats enable `<stat>`

- **権限**: OP0
- **挙動**: 指定統計 ID を有効化する。GUI に表示されるようになる。

### /mysb admin stats disable `<stat>`

- **権限**: OP0
- **挙動**: 指定統計 ID を無効化する（データは保持されるが非表示）。

### /mysb admin stats list

- **権限**: OP0
- **挙動**: 全統計とその有効/無効状態を表示。

### /mysb admin fakeplayerscore enable|disable

- **権限**: OP3
- **挙動**: Carpet 等で生成される Fake Player のスコア表示を切替。設定は `ServerScoreboardConfig.FAKE_PLAYER_SCORE_ENABLED` に反映される。

---

## /mysbdiscord

Discord Bot 連携のためのコマンド群。`/mysb` とは独立したルートコマンド。

### /mysbdiscord

- **権限**: OP3
- **挙動**: Discord 設定 GUI を開く。

### /mysbdiscord channel `<id>`

- **権限**: OP3
- **引数**: Discord のフォーラムチャンネル ID（数値）。
- **挙動**: 統計投稿先のフォーラムチャンネルを設定する。設定値は `discord_config.properties` の `DISCORD_FORUM_CHANNEL_ID` に書き出される。

### /mysbdiscord status

- **権限**: OP3
- **挙動**: Bot の接続状態、設定値、有効統計の一覧を表示する。

### /mysbdiscord reconnect

- **権限**: OP3
- **挙動**: Bot の接続テストと再接続を非同期で実行する。

### /mysbdiscord test

- **権限**: OP3
- **挙動**: 設定済みチャンネルへテスト投稿を行う（`DiscordStatsPublisher#testPublish`）。

### /mysbdiscord update

- **権限**: OP3
- **挙動**: 全統計を即座に投稿/更新する。`DiscordScheduler` が行う 1 時間ごとの自動更新と同等の処理を手動起動する。

---

## Discord スラッシュコマンド（Bot 側）

Discord クライアントから叩くスラッシュコマンド。`SlashCommandListener` で登録される。

### /refresh

- **実行場所**: Discord
- **挙動**: 統計を再計算し、スレッドへ投稿する。Discord の 3 秒応答制限に合わせて defer し、`CompletableFuture` で非同期処理する。
- **応答**: Ephemeral（実行者のみに見える）。

---

## 運用上の注意

- すべてのコマンドは `RateLimiter` でクールダウン管理される（`COMMAND_COOLDOWN_MS` = 500 ms）。
- GUI を開くコマンドは `player` コンテキスト必須。コンソールから `/mysb` 単体を叩くと GUI は開けない。
- `/mysb total add` で作成した統計は、スコアボードオブジェクティブとして永続化される。一度作った ID は `remove` しない限り残る。
