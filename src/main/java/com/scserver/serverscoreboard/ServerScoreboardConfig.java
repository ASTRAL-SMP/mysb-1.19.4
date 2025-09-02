package com.scserver.serverscoreboard;

public class ServerScoreboardConfig {
    // セキュリティ設定
    public static final int MAX_OBJECTIVES_PER_PLAYER = 50;
    public static final int MAX_OBJECTIVE_NAME_LENGTH = 64;
    public static final int MAX_SCORES_PER_OBJECTIVE = 100;
    
    // レート制限設定
    public static final int COMMAND_COOLDOWN_MS = 500; // コマンド実行のクールダウン（ミリ秒）
    public static final int GUI_OPEN_COOLDOWN_MS = 1000; // GUI開くクールダウン（ミリ秒）
    
    // 更新頻度設定
    public static final int UPDATE_INTERVAL_TICKS = 1; // スコアボード更新間隔（毎tick）
    public static final int STATS_UPDATE_INTERVAL_TICKS = 1; // 統計更新間隔（毎tick）- 高速表示用
    
    // 自己変更機能の権限設定
    public static final boolean ALLOW_SELF_MODIFICATION = true; // プレイヤーが自分のスコアボードを変更できるか
    public static final boolean REQUIRE_OP_FOR_SELF_CUSTOM = false; // カスタムスコアボードの自己変更にOP権限が必要か
    public static final int SELF_MODIFICATION_OP_LEVEL = 0; // 自己変更に必要なOPレベル（0=全員可能）
    
    // Discord Bot設定
    public static String DISCORD_BOT_TOKEN = ""; // Discord Botトークン
    public static String DISCORD_FORUM_CHANNEL_ID = ""; // フォーラムチャンネルID
    public static boolean DISCORD_BOT_ENABLED = false; // Discord Bot機能の有効/無効
    public static int DISCORD_COMMAND_OP_LEVEL = 2; // Discord連携コマンドに必要なOPレベル
    
    
    public static boolean isValidObjectiveName(String name) {
        if (name == null || name.trim().isEmpty()) {
            return false;
        }
        if (name.length() > MAX_OBJECTIVE_NAME_LENGTH) {
            return false;
        }
        // 危険な文字のチェック
        return name.matches("^[a-zA-Z0-9_\\-\\.]+$");
    }
    
    public static boolean isValidScore(int score) {
        // Minecraftのスコアボードの制限に従う
        return score >= Integer.MIN_VALUE && score <= Integer.MAX_VALUE;
    }
}