package com.astralsmp.mysb.discord;

import com.astralsmp.mysb.ServerScoreboardLogger;
import net.minecraft.server.MinecraftServer;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 1時間ごとにDiscord投稿を実行するスケジューラ
 * リアル時間ベースで動作（ゲーム時間ではない）
 */
public class DiscordScheduler {
    // 1時間 = 3600000ミリ秒
    private static final long UPDATE_INTERVAL_MS = 3600000L;

    private static MinecraftServer server;
    private static final AtomicLong lastUpdateTime = new AtomicLong(0);
    private static final AtomicBoolean isUpdating = new AtomicBoolean(false);

    /**
     * スケジューラを初期化
     */
    public static void initialize(MinecraftServer minecraftServer) {
        server = minecraftServer;
        lastUpdateTime.set(System.currentTimeMillis());
        ServerScoreboardLogger.info("Discord scheduler initialized");
    }

    /**
     * サーバーティックごとに呼び出される
     * リアル時間をチェックして1時間経過していれば更新を実行
     */
    public static void onServerTick() {
        // Discord設定が有効でなければスキップ
        if (!DiscordConfig.hasValidToken() || !DiscordConfig.hasForumChannelId()) {
            return;
        }

        // Discord有効な統計がなければスキップ
        if (DiscordConfig.getDiscordEnabledStats().isEmpty()) {
            return;
        }

        // 現在時刻をチェック
        long currentTime = System.currentTimeMillis();
        long lastUpdate = lastUpdateTime.get();

        // 1時間経過していなければスキップ
        if (currentTime - lastUpdate < UPDATE_INTERVAL_MS) {
            return;
        }

        // 既に更新中ならスキップ
        if (!isUpdating.compareAndSet(false, true)) {
            return;
        }

        // 次回更新時刻を更新
        lastUpdateTime.set(currentTime);

        // 非同期で統計を投稿
        CompletableFuture.runAsync(() -> {
            try {
                ServerScoreboardLogger.info("Discord scheduled update started");
                DiscordStatsPublisher.publishAllStats().join();
                ServerScoreboardLogger.info("Discord scheduled update completed");
            } catch (Exception e) {
                ServerScoreboardLogger.error("Discord scheduled update failed", e);
            } finally {
                isUpdating.set(false);
            }
        });
    }

    /**
     * 手動で更新を実行
     */
    public static CompletableFuture<Void> forceUpdate() {
        if (!isUpdating.compareAndSet(false, true)) {
            ServerScoreboardLogger.warn("Discord update already in progress");
            return CompletableFuture.completedFuture(null);
        }

        lastUpdateTime.set(System.currentTimeMillis());

        return CompletableFuture.runAsync(() -> {
            try {
                ServerScoreboardLogger.info("Discord manual update started");
                DiscordStatsPublisher.publishAllStats().join();
                ServerScoreboardLogger.info("Discord manual update completed");
            } catch (Exception e) {
                ServerScoreboardLogger.error("Discord manual update failed", e);
            } finally {
                isUpdating.set(false);
            }
        });
    }

    /**
     * 次回更新までの残り時間（ミリ秒）
     */
    public static long getTimeUntilNextUpdate() {
        long currentTime = System.currentTimeMillis();
        long lastUpdate = lastUpdateTime.get();
        long nextUpdate = lastUpdate + UPDATE_INTERVAL_MS;
        return Math.max(0, nextUpdate - currentTime);
    }

    /**
     * 次回更新までの残り時間を文字列で取得
     */
    public static String getTimeUntilNextUpdateFormatted() {
        long remaining = getTimeUntilNextUpdate();
        long minutes = (remaining / 60000) % 60;
        long seconds = (remaining / 1000) % 60;
        return String.format("%02d:%02d", minutes, seconds);
    }

    /**
     * 更新中かどうか
     */
    public static boolean isUpdating() {
        return isUpdating.get();
    }

    /**
     * 最後の更新時刻をリセット（次のティックで即更新される）
     */
    public static void resetLastUpdateTime() {
        lastUpdateTime.set(0);
    }
}
