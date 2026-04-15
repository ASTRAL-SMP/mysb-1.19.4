package com.astralsmp.mysb.discord;

import com.astralsmp.mysb.ServerScoreboardLogger;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.WorldSavePath;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Discord設定の読み書きを管理するクラス
 * 設定ファイル: config/mysb/discord_config.properties
 */
public class DiscordConfig {
    private static final String CONFIG_DIR = "config/mysb";
    private static final String CONFIG_FILE = "discord_config.properties";

    private static Path configPath;
    private static Properties properties = new Properties();

    // 設定キー
    private static final String KEY_DISCORD_TOKEN = "DISCORD_TOKEN";
    private static final String KEY_FORUM_CHANNEL_ID = "DISCORD_FORUM_CHANNEL_ID";
    private static final String KEY_THREAD_ID_PREFIX = "THREAD_ID_";
    private static final String KEY_MESSAGE_ID_PREFIX = "MESSAGE_ID_";
    private static final String KEY_DISCORD_ENABLED_PREFIX = "DISCORD_ENABLED_";
    private static final String KEY_CONTINUATION_IDS_PREFIX = "CONTINUATION_IDS_";
    private static final String KEY_BOT_ENABLED = "DISCORD_BOT_ENABLED";

    // Discord有効な統計のキャッシュ（これだけはSetで管理）
    private static final Set<String> discordEnabledStats = ConcurrentHashMap.newKeySet();

    // 保存のスレッドセーフティ用ロック
    private static final Object saveLock = new Object();

    // 保存の合体（コアレシング）用
    private static volatile boolean savePending = false;
    private static ScheduledExecutorService saveExecutor;

    /**
     * 設定を初期化・読み込み
     */
    public static void initialize(MinecraftServer server) {
        try {
            // config/mysb ディレクトリを作成
            Path configDir = server.getRunDirectory().resolve(CONFIG_DIR);
            Files.createDirectories(configDir);

            configPath = configDir.resolve(CONFIG_FILE);

            // 保存スケジューラを初期化
            if (saveExecutor == null || saveExecutor.isShutdown()) {
                saveExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
                    Thread t = new Thread(r, "mysb-discord-config-save");
                    t.setDaemon(true);
                    return t;
                });
            }

            load();
            ServerScoreboardLogger.info("Discord config initialized: " + configPath);
        } catch (Exception e) {
            ServerScoreboardLogger.error("Failed to initialize Discord config", e);
        }
    }

    /**
     * 設定ファイルを読み込み
     */
    public static void load() {
        discordEnabledStats.clear();

        if (configPath == null) {
            return;
        }

        try {
            if (Files.exists(configPath)) {
                // 既存のファイルから読み込み
                Properties loadedProperties = new Properties();
                try (InputStream is = Files.newInputStream(configPath)) {
                    loadedProperties.load(is);
                }

                // 読み込み成功した場合のみ更新
                properties = loadedProperties;

                // Discord有効な統計をロード
                for (String key : properties.stringPropertyNames()) {
                    if (key.startsWith(KEY_DISCORD_ENABLED_PREFIX)) {
                        String statId = key.substring(KEY_DISCORD_ENABLED_PREFIX.length());
                        if ("true".equalsIgnoreCase(properties.getProperty(key))) {
                            discordEnabledStats.add(statId);
                        }
                    }
                }

                ServerScoreboardLogger.info("Discord config loaded successfully");
            } else {
                // ファイルが存在しない場合のみデフォルト設定で作成
                properties = new Properties();
                createDefaultConfig();
            }
        } catch (Exception e) {
            ServerScoreboardLogger.error("Failed to load Discord config", e);
        }
    }

    /**
     * デフォルト設定ファイルを作成
     */
    private static void createDefaultConfig() {
        properties.setProperty(KEY_DISCORD_TOKEN, "YOUR_BOT_TOKEN_HERE");
        properties.setProperty(KEY_FORUM_CHANNEL_ID, "");
        properties.setProperty(KEY_BOT_ENABLED, "true");
        save();
        ServerScoreboardLogger.info("Created default Discord config file");
    }

    /**
     * 設定ファイルを保存（コアレシング付き）
     * 100ms以内の複数呼び出しは1回の書き込みにまとめられる
     */
    public static void save() {
        if (configPath == null) {
            return;
        }
        if (!savePending && saveExecutor != null && !saveExecutor.isShutdown()) {
            savePending = true;
            saveExecutor.schedule(DiscordConfig::doSave, 100, TimeUnit.MILLISECONDS);
        }
    }

    /**
     * 実際のファイル書き込み処理（synchronized）
     */
    private static void doSave() {
        savePending = false;
        if (configPath == null) {
            return;
        }
        synchronized (saveLock) {
            try {
                // Discord有効な統計を保存
                // 既存のDISCORD_ENABLED_プロパティをクリア
                properties.stringPropertyNames().stream()
                    .filter(k -> k.startsWith(KEY_DISCORD_ENABLED_PREFIX))
                    .toList()
                    .forEach(properties::remove);

                // 現在の状態を保存
                for (String statId : discordEnabledStats) {
                    properties.setProperty(KEY_DISCORD_ENABLED_PREFIX + statId, "true");
                }

                try (OutputStream os = Files.newOutputStream(configPath)) {
                    properties.store(os, "MySB Discord Configuration");
                }
            } catch (Exception e) {
                ServerScoreboardLogger.error("Failed to save Discord config", e);
            }
        }
    }

    /**
     * 保存スケジューラをシャットダウン
     * サーバー停止時に呼び出す
     */
    public static void shutdown() {
        doSave(); // 未保存データをフラッシュ
        if (saveExecutor != null && !saveExecutor.isShutdown()) {
            saveExecutor.shutdown();
        }
    }

    // ========== Token ==========

    public static String getToken() {
        String token = properties.getProperty(KEY_DISCORD_TOKEN, "");
        if (token.isEmpty() || "YOUR_BOT_TOKEN_HERE".equals(token)) {
            return null;
        }
        return token;
    }

    public static void setToken(String token) {
        properties.setProperty(KEY_DISCORD_TOKEN, token != null ? token : "");
        save();
    }

    public static boolean hasValidToken() {
        String token = getToken();
        return token != null && !token.isEmpty();
    }

    // ========== Forum Channel ID ==========

    public static String getForumChannelId() {
        return properties.getProperty(KEY_FORUM_CHANNEL_ID, "");
    }

    public static void setForumChannelId(String channelId) {
        properties.setProperty(KEY_FORUM_CHANNEL_ID, channelId != null ? channelId : "");
        save();
    }

    public static boolean hasForumChannelId() {
        String channelId = properties.getProperty(KEY_FORUM_CHANNEL_ID, "");
        return !channelId.isEmpty();
    }

    // ========== Bot Enabled ==========

    public static boolean isBotEnabled() {
        return "true".equalsIgnoreCase(properties.getProperty(KEY_BOT_ENABLED, "true"));
    }

    public static void setBotEnabled(boolean enabled) {
        properties.setProperty(KEY_BOT_ENABLED, String.valueOf(enabled));
        save();
    }

    // ========== Thread ID (per stat) ==========

    public static String getThreadId(String statId) {
        return properties.getProperty(KEY_THREAD_ID_PREFIX + statId, "");
    }

    public static void setThreadId(String statId, String threadId) {
        properties.setProperty(KEY_THREAD_ID_PREFIX + statId, threadId);
        save();
    }

    // ========== Message ID (per stat) ==========

    public static String getMessageId(String statId) {
        return properties.getProperty(KEY_MESSAGE_ID_PREFIX + statId, "");
    }

    public static void setMessageId(String statId, String messageId) {
        properties.setProperty(KEY_MESSAGE_ID_PREFIX + statId, messageId);
        save();
    }

    // ========== Continuation Message IDs (per stat) ==========

    /**
     * 続きメッセージIDの一覧を取得
     */
    public static List<String> getContinuationMessageIds(String statId) {
        String value = properties.getProperty(KEY_CONTINUATION_IDS_PREFIX + statId, "");
        if (value.isEmpty()) {
            return new ArrayList<>();
        }
        return new ArrayList<>(Arrays.asList(value.split(",")));
    }

    /**
     * 続きメッセージIDの一覧を保存
     */
    public static void setContinuationMessageIds(String statId, List<String> messageIds) {
        if (messageIds == null || messageIds.isEmpty()) {
            properties.remove(KEY_CONTINUATION_IDS_PREFIX + statId);
        } else {
            properties.setProperty(KEY_CONTINUATION_IDS_PREFIX + statId, String.join(",", messageIds));
        }
        save();
    }

    // ========== Discord Enabled (per stat) ==========

    public static boolean isDiscordEnabled(String statId) {
        return discordEnabledStats.contains(statId);
    }

    public static void setDiscordEnabled(String statId, boolean enabled) {
        if (enabled) {
            discordEnabledStats.add(statId);
        } else {
            discordEnabledStats.remove(statId);
        }
        save();
    }

    public static void toggleDiscordEnabled(String statId) {
        if (discordEnabledStats.contains(statId)) {
            discordEnabledStats.remove(statId);
        } else {
            discordEnabledStats.add(statId);
        }
        save();
    }

    public static Set<String> getDiscordEnabledStats() {
        return Set.copyOf(discordEnabledStats);
    }

    /**
     * 設定の状態をログ出力
     */
    public static void logStatus() {
        ServerScoreboardLogger.info("=== Discord Config Status ===");
        ServerScoreboardLogger.info("Bot enabled: " + isBotEnabled());
        ServerScoreboardLogger.info("Token configured: " + hasValidToken());
        ServerScoreboardLogger.info("Forum Channel ID: " + (hasForumChannelId() ? getForumChannelId() : "(not set)"));
        ServerScoreboardLogger.info("Discord enabled stats: " + discordEnabledStats);
    }
}
