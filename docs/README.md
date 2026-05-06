# MySB ドキュメント

Minecraft 1.19.4 用 Fabric サーバーサイド MOD「**MySB (My Scoreboard)**」の開発者/運用者向けドキュメント。

プロジェクト本体の概要は [`../README.md`](../README.md) を参照。ここでは実装と運用の詳細に踏み込む。

---

## ドキュメント一覧

| ファイル | 内容 |
|---------|------|
| [commands.md](commands.md) | `/mysb` / `/mysbdiscord` の完全コマンドリファレンス。構文・権限・挙動 |
| [architecture.md](architecture.md) | 開発者向けアーキテクチャ解説。中核クラス・tick フロー・仮想オブジェクティブ・変換システム・Discord 連携 |
| [configuration.md](configuration.md) | 設定ファイルリファレンス。Java 定数 / `discord_config.properties` / `auto_transform.json` / NBT / JSON キャッシュ |
| [development.md](development.md) | 開発ガイド。ビルド・テストサーバー・拡張ポイント・リリース手順 |

---

## クイックリンク

- **コマンドを調べたい** → [commands.md](commands.md)
- **コードの構造を把握したい** → [architecture.md](architecture.md)
- **設定ファイルのフォーマットを知りたい** → [configuration.md](configuration.md)
- **ビルドしたい / 新機能を追加したい** → [development.md](development.md)

---

## プロジェクトの基本情報

- **MOD ID**: `mysb`
- **バージョン**: `gradle.properties` の `mod_version` を参照（現状 `2.3`）
- **パッケージ**: `com.astralsmp.mysb`
- **エントリーポイント**: `com.astralsmp.mysb.ServerOnlyScoreboardMod`（`DedicatedServerModInitializer`）
- **環境**: `server` 専用（`fabric.mod.json`）
- **依存**: Minecraft 1.19.4 / Fabric Loader 0.14+ / Fabric API

クライアントに MOD を入れずにスコアボード表示を差し替えられるのが本 MOD の特徴。内部ではサーバー側のスコアボードデータを変更せず、**クライアントへ送るパケットだけを差し替える**実装になっている。詳しくは [architecture.md](architecture.md) の「仮想オブジェクティブ」節を参照。
