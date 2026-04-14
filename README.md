# MySB - My Scoreboard Mod

## 概要 (Overview)
このmodは**サーバー側専用**のスコアボード管理modです。
バニラクライアントと完全に互換性があり、プレイヤーは追加のmodを導入する必要がありません。
プレイヤーは自分自身のスコアボード表示を変更できます。

This mod is a **server-side only** scoreboard management mod.
It is fully compatible with vanilla clients, and players do not need to install any additional mods.
Players can modify their own scoreboard display.

## 特徴 (Features)
- ✅ サーバー側のみで動作
- ✅ バニラクライアント対応
- ✅ GUI付きスコアボード選択
- ✅ プレイヤー設定の永続化
- ✅ 権限管理
- ✅ レート制限
- ✅ **プレイヤー自身によるスコアボード表示変更機能**
- ✅ **トータル統計機能** - サーバー全体の統計を表示（競争意識の向上）
- ✅ **Discord Bot連携** - 統計データをDiscordに自動投稿

## 更新履歴 (Update History)
- **バグ修正**: サーバー停止時にプロセスが正常に終了しない問題を修正
    - Discord Bot のスケジューラーが適切にシャットダウンされていなかった問題を解決
    - Windows環境でサーバー停止後に「キーを押してください」のプロンプトが表示されない問題を修正

## インストール (Installation)
**重要**: このmodは**サーバー側にのみ**インストールしてください。
クライアント側にインストールしても何も効果がありません。

**Important**: Install this mod **only on the server side**.
Installing on the client side will have no effect.

### サーバー側 (Server Side)
1. `mysb-1.0.0.jar` をサーバーの `mods` フォルダに配置
2. サーバーを起動

### クライアント側 (Client Side)
- **何もインストールする必要がありません**
- **No installation required**

## 使用方法 (Usage)

### コマンド (Commands)
```
/mysb                             # スコアボード選択GUIを開く（プレイヤー用） / Open scoreboard selection GUI
/mysb reload                      # 設定再読み込み（OPレベル4限定） / Reload configuration (OP4)
/mysb version                     # バージョン表示 / Show version
/mysb total list                  # トータル統計の一覧表示（OP限定） / List total statistics
/mysb total update                # トータル統計を手動更新（OP限定） / Manually update total stats
/mysb total add <id> <displayName> <statType>  # カスタムトータル統計を追加 / Add custom total stat
/mysb total remove <id>           # カスタムトータル統計を削除 / Remove custom total stat
/mysb admin gui                   # 管理者GUIを開く（OP3以上） / Open admin GUI (OP3+)
/mysb admin stats enable <stat>   # 統計を有効化 / Enable a statistic
/mysb admin stats disable <stat>  # 統計を無効化 / Disable a statistic
/mysb admin stats list            # 統計の有効/無効状態を表示 / Show enabled/disabled stats
/mysb admin exclude <player>      # プレイヤーを統計から除外 / Exclude player from stats
/mysb admin include <player>      # 除外プレイヤーを戻す / Include excluded player
/mysb admin fakeplayerscore enable|disable  # Carpet fake player 表示切替 / Toggle fake player display

# Discord連携（独立コマンド、OP3以上） / Discord integration (standalone, OP3+)
/mysbdiscord                      # Discord設定GUIを開く / Open Discord settings GUI
/mysbdiscord channel <ID>         # フォーラムチャンネル設定 / Set Discord forum channel
/mysbdiscord status               # 連携ステータス表示 / Show Discord integration status
/mysbdiscord reconnect            # Bot再接続 / Reconnect Discord Bot
/mysbdiscord test                 # テストメッセージ送信 / Send test message
/mysbdiscord update               # 統計の強制更新 / Force update statistics
```

#### 利用可能な統計タイプ (Available Stat Types)
- `Total Blocks Mined`
- `Total Blocks Placed`
- `Total Deepslate Mined`
- `Total Coral Blocks Mined`
- `Total Stained Glass Placed`
- `Total Anvils Placed`
- `Total Villager Trades`
- `Total All Ores Mined`

### GUI操作 (GUI Operation)
`/mysb` コマンドを実行すると、チェスト型のGUIが開きます： / Execute `/mysb` to open a chest-type GUI:

#### スコアボードページ / Scoreboard Page:
- **本**: 統計ページへ移動 / Book: Navigate to statistics page
- **紙**: スコアボードオブジェクティブ / Paper: Scoreboard objectives
- **バリアブロック**: デフォルトにリセット / Barrier: Reset to default
- **赤石**: 閉じる / Redstone: Close
- **矢印**: ページナビゲーション / Arrows: Page navigation

#### 統計ページ / Statistics Page:
- **コンパス**: スコアボードページへ移動 / Compass: Navigate to scoreboard page
- **金のリンゴ**: トータル統計 / Golden Apple: Total statistics
- **バリアブロック**: デフォルトにリセット / Barrier: Reset to default
- **赤石**: 閉じる / Redstone: Close
- **矢印**: ページナビゲーション / Arrows: Page navigation

アイテムをクリックすると、選択したスコアボードが自分のクライアントに表示されます。 / Click an item to display the selected scoreboard on your client.

### トータル統計機能 (Total Statistics)
サーバー全体のプレイヤーの統計を合計して表示する機能です。 / Shows server-wide player statistics totals.

- 各統計には個別プレイヤーのスコアとサーバー合計が表示されます / Each statistic shows individual player scores and server total
- 統計は5秒ごとに自動更新されます / Statistics update automatically every 5 seconds
- GUIには別ページがあり、ナビゲーション可能です / GUI has separate pages with navigation

### Discord Bot連携 (Discord Bot Integration)
統計データをDiscordのフォーラムチャンネルに自動投稿する機能です。 / Automatically posts statistics to Discord forum channels.

1. **Discord Botの設定** / **Discord Bot Setup**:
    - `config/mysb/discord_config.properties` にBotトークンを設定 / Set bot token in config file
    - Botに必要な権限: メッセージ送信、スレッド作成、スラッシュコマンド / Required permissions: Send messages, Create threads, Slash commands

2. **使用方法** / **Usage**:
    - `/mysbdiscord channel <ID>` でフォーラムチャンネルを設定 / Set forum channel
    - `/mysbdiscord` で設定GUIを開いて管理 / Open settings GUI
    - 1時間ごとに統計を自動更新 / Statistics update automatically every hour

## 権限設定 (Permission Settings)
`ServerScoreboardConfig.java` で以下の設定を変更できます：

- `ALLOW_SELF_MODIFICATION` (デフォルト: true) - プレイヤーの自己変更機能の有効/無効
- `SELF_MODIFICATION_OP_LEVEL` (デフォルト: 0) - 自己変更に必要なOPレベル（0=全員可能）

## 技術仕様 (Technical Specifications)
- **対応バージョン**: Minecraft 1.19.4
- **必要MOD**: Fabric API
- **環境**: サーバー専用
- **クライアント互換性**: バニラクライアント

## ライセンス (License)
MIT License

## 注意事項 (Important Notes)
- このmodはサーバー側にのみインストールしてください
- クライアント側への導入は不要で、効果もありません
- プレイヤーは追加のmodを導入する必要がありません
- サーバー側で設定されているスコアボードのみ選択可能です

---
**Server-Side Only Mod - No Client Installation Required**

