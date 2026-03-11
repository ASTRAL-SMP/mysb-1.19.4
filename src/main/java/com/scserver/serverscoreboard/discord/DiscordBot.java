package com.scserver.serverscoreboard.discord;

import com.scserver.serverscoreboard.ServerScoreboardLogger;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.JDABuilder;
import net.dv8tion.jda.api.entities.Activity;
import net.dv8tion.jda.api.interactions.commands.build.Commands;
import net.dv8tion.jda.api.requests.GatewayIntent;

import java.util.concurrent.CompletableFuture;

/**
 * Discord Bot の初期化・管理を行うクラス
 * JDAを使用してDiscord Gatewayに接続し、スラッシュコマンドを処理する
 */
public class DiscordBot {
    private static JDA jda;
    private static boolean initialized = false;

    /**
     * Discord Botを初期化
     * サーバー起動時に呼び出す
     */
    public static void initialize() {
        if (initialized) {
            ServerScoreboardLogger.warn("Discord Bot is already initialized");
            return;
        }

        String token = DiscordConfig.getToken();
        if (token == null || token.isEmpty()) {
            ServerScoreboardLogger.info("Discord Bot token not configured, skipping initialization");
            return;
        }

        if (!DiscordConfig.isBotEnabled()) {
            ServerScoreboardLogger.info("Discord Bot is disabled in config, skipping initialization");
            return;
        }

        try {
            ServerScoreboardLogger.info("Initializing Discord Bot...");

            jda = JDABuilder.createLight(token, GatewayIntent.GUILD_MESSAGES)
                .setActivity(Activity.playing("MySB Server"))
                .addEventListeners(new SlashCommandListener())
                .build();

            // スラッシュコマンドを登録
            jda.updateCommands()
                .addCommands(
                    Commands.slash("refresh", "統計を即座に更新")
                )
                .queue(
                    commands -> ServerScoreboardLogger.info("Registered " + commands.size() + " slash commands"),
                    error -> ServerScoreboardLogger.error("Failed to register slash commands", error)
                );

            // 接続完了を待機（非同期）
            CompletableFuture.runAsync(() -> {
                try {
                    jda.awaitReady();
                    initialized = true;
                    ServerScoreboardLogger.info("Discord Bot connected successfully (ID: " + jda.getSelfUser().getId() + ")");
                } catch (InterruptedException e) {
                    ServerScoreboardLogger.error("Discord Bot initialization interrupted", e);
                    Thread.currentThread().interrupt();
                }
            });

        } catch (Throwable e) {
            // NoClassDefFoundError等のErrorもキャッチしてサーバーがクラッシュしないようにする
            ServerScoreboardLogger.error("Failed to initialize Discord Bot (missing dependencies?)", e);
            ServerScoreboardLogger.warn("Discord Bot will be disabled. Server will continue without Discord integration.");
        }
    }

    /**
     * Discord Botをシャットダウン
     * サーバー停止時に呼び出す
     */
    public static void shutdown() {
        if (jda != null) {
            ServerScoreboardLogger.info("Shutting down Discord Bot...");
            try {
                jda.shutdown();
                // 最大5秒待機
                if (!jda.awaitShutdown(java.time.Duration.ofSeconds(5))) {
                    ServerScoreboardLogger.warn("Discord Bot did not shutdown gracefully, forcing shutdown");
                    jda.shutdownNow();
                }
            } catch (Exception e) {
                ServerScoreboardLogger.error("Error during Discord Bot shutdown", e);
            }
            jda = null;
            initialized = false;
            ServerScoreboardLogger.info("Discord Bot shutdown complete");
        }
    }

    /**
     * Botが初期化されているか
     */
    public static boolean isInitialized() {
        return initialized && jda != null;
    }

    /**
     * JDAインスタンスを取得
     */
    public static JDA getJda() {
        return jda;
    }
}
