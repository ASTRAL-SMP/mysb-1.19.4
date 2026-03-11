package com.scserver.serverscoreboard.discord;

import com.scserver.serverscoreboard.PlayerStatsCache;
import com.scserver.serverscoreboard.ServerScoreboardLogger;
import com.scserver.serverscoreboard.TotalStatsManager;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;

import java.text.NumberFormat;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.CompletableFuture;

/**
 * 統計データをフォーマットしてDiscordに投稿するクラス
 */
public class DiscordStatsPublisher {
    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
    private static final ZoneId JAPAN_ZONE = ZoneId.of("Asia/Tokyo");
    private static final NumberFormat NUMBER_FORMAT = NumberFormat.getNumberInstance(Locale.US);
    private static final int MAX_MESSAGE_LENGTH = 1800; // Discordの2000文字制限に余裕を持たせる

    private static MinecraftServer server;

    public static void initialize(MinecraftServer minecraftServer) {
        server = minecraftServer;
    }

    /**
     * Discord有効な全統計を投稿/更新
     */
    public static CompletableFuture<Void> publishAllStats() {
        Set<String> enabledStats = DiscordConfig.getDiscordEnabledStats();

        if (enabledStats.isEmpty()) {
            ServerScoreboardLogger.info("No stats enabled for Discord publishing");
            return CompletableFuture.completedFuture(null);
        }

        if (!DiscordConfig.hasForumChannelId()) {
            ServerScoreboardLogger.warn("Discord forum channel ID not configured");
            return CompletableFuture.completedFuture(null);
        }

        List<CompletableFuture<Void>> futures = new ArrayList<>();

        for (String statId : enabledStats) {
            futures.add(publishStat(statId));
        }

        return CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
            .thenRun(() -> ServerScoreboardLogger.info("Discord stats publishing completed"));
    }

    /**
     * 単一の統計を投稿/更新
     */
    public static CompletableFuture<Void> publishStat(String statId) {
        return CompletableFuture.runAsync(() -> {
            try {
                String forumChannelId = DiscordConfig.getForumChannelId();
                if (forumChannelId == null || forumChannelId.isEmpty()) {
                    ServerScoreboardLogger.warn("Forum channel ID not set");
                    return;
                }

                // 統計データを取得
                Map<String, String> allStats = TotalStatsManager.getAllAvailableStats();
                String displayName = allStats.getOrDefault(statId, statId);

                // プレイヤーランキングを取得
                Map<String, Integer> playerStats = getPlayerStats(statId);
                int serverTotal = playerStats.values().stream().mapToInt(Integer::intValue).sum();

                // メッセージをフォーマット（複数メッセージに分割される可能性あり）
                List<String> messages = formatStatsMessages(displayName, serverTotal, playerStats);

                if (messages.isEmpty()) {
                    ServerScoreboardLogger.warn("No messages to publish for stat: " + statId);
                    return;
                }

                // 既存のスレッドIDを確認
                String threadId = DiscordConfig.getThreadId(statId);
                String messageId = DiscordConfig.getMessageId(statId);

                if (threadId != null && !threadId.isEmpty()) {
                    // スレッドが存在するか確認
                    Boolean exists = DiscordManager.threadExists(threadId).join();
                    if (exists) {
                        // 既存スレッドを更新
                        boolean updated = updateExistingThread(threadId, messageId, messages, statId);
                        if (updated) {
                            return;
                        }
                    }
                }

                // スレッドが存在しないか編集失敗の場合、新規作成
                String threadName = displayName;
                String newThreadId = DiscordManager.createForumThread(forumChannelId, threadName, messages.get(0)).join();

                if (newThreadId != null) {
                    DiscordConfig.setThreadId(statId, newThreadId);
                    // フォーラムスレッド作成時の最初のメッセージIDを取得
                    String newMsgId = DiscordManager.getFirstMessageId(newThreadId).join();
                    if (newMsgId != null) {
                        DiscordConfig.setMessageId(statId, newMsgId);
                    }

                    // 追加メッセージを投稿し、IDを保存
                    List<String> continuationIds = new ArrayList<>();
                    for (int i = 1; i < messages.size(); i++) {
                        String contMsgId = DiscordManager.sendMessage(newThreadId, messages.get(i)).join();
                        if (contMsgId != null) {
                            continuationIds.add(contMsgId);
                        }
                    }
                    if (!continuationIds.isEmpty()) {
                        DiscordConfig.setContinuationMessageIds(statId, continuationIds);
                    }

                    ServerScoreboardLogger.info("Created Discord thread for stat: " + statId + " (" + messages.size() + " messages)");
                }
            } catch (Exception e) {
                ServerScoreboardLogger.error("Error publishing stat to Discord: " + statId, e);
            }
        });
    }

    /**
     * 既存のスレッドを更新
     */
    private static boolean updateExistingThread(String threadId, String messageId, List<String> messages, String statId) {
        try {
            // 1. 最初のメッセージを編集
            String targetMessageId = messageId;
            if (targetMessageId == null || targetMessageId.isEmpty()) {
                targetMessageId = DiscordManager.getFirstMessageId(threadId).join();
            }

            if (targetMessageId == null) {
                return false;
            }

            Boolean success = DiscordManager.editMessage(threadId, targetMessageId, messages.get(0)).join();
            if (!success) {
                return false;
            }
            DiscordConfig.setMessageId(statId, targetMessageId);

            // 2. 続きメッセージを処理
            List<String> existingContinuationIds = DiscordConfig.getContinuationMessageIds(statId);
            List<String> newContinuationIds = new ArrayList<>();
            int continuationCount = messages.size() - 1; // 最初のメッセージを除く

            for (int i = 0; i < continuationCount; i++) {
                String continuationContent = messages.get(i + 1);

                if (i < existingContinuationIds.size()) {
                    // 既存の続きメッセージを編集
                    String contMsgId = existingContinuationIds.get(i);
                    Boolean editSuccess = DiscordManager.editMessage(threadId, contMsgId, continuationContent).join();
                    if (editSuccess) {
                        newContinuationIds.add(contMsgId);
                    } else {
                        // 編集失敗（削除された等）→新規送信
                        String newId = DiscordManager.sendMessage(threadId, continuationContent).join();
                        if (newId != null) {
                            newContinuationIds.add(newId);
                        }
                    }
                } else {
                    // 新しい続きメッセージを送信（メッセージ数が増えた場合）
                    String newId = DiscordManager.sendMessage(threadId, continuationContent).join();
                    if (newId != null) {
                        newContinuationIds.add(newId);
                    }
                }
            }

            // 3. 不要になった続きメッセージを削除（メッセージ数が減った場合）
            for (int i = continuationCount; i < existingContinuationIds.size(); i++) {
                DiscordManager.deleteMessage(threadId, existingContinuationIds.get(i)).join();
            }

            // 4. 続きメッセージIDを更新・保存
            DiscordConfig.setContinuationMessageIds(statId, newContinuationIds);

            ServerScoreboardLogger.info("Updated Discord message for stat: " + statId + " (" + messages.size() + " messages)");
            return true;
        } catch (Exception e) {
            ServerScoreboardLogger.error("Error updating existing thread", e);
        }
        return false;
    }

    /**
     * プレイヤー統計を取得
     */
    private static Map<String, Integer> getPlayerStats(String statId) {
        Map<String, Integer> playerStats = new LinkedHashMap<>();

        if (server == null) {
            return playerStats;
        }

        // オンラインプレイヤーの統計を取得
        for (ServerPlayerEntity player : server.getPlayerManager().getPlayerList()) {
            String playerName = player.getName().getString();
            if (TotalStatsManager.isPlayerExcluded(playerName)) {
                continue;
            }
            int value = TotalStatsManager.getPlayerStatTotal(player, statId);
            if (value > 0) {
                playerStats.put(playerName, value);
            }
        }

        // オフラインプレイヤーのキャッシュからも取得
        Map<String, Integer> cachedStats = PlayerStatsCache.getAllPlayerStats(statId);
        for (Map.Entry<String, Integer> entry : cachedStats.entrySet()) {
            String playerName = entry.getKey();
            if (!playerStats.containsKey(playerName) && !TotalStatsManager.isPlayerExcluded(playerName)) {
                int value = entry.getValue();
                if (value > 0) {
                    playerStats.put(playerName, value);
                }
            }
        }

        // 値でソート（降順）
        List<Map.Entry<String, Integer>> sortedList = new ArrayList<>(playerStats.entrySet());
        sortedList.sort((a, b) -> b.getValue().compareTo(a.getValue()));

        Map<String, Integer> sortedMap = new LinkedHashMap<>();
        for (Map.Entry<String, Integer> entry : sortedList) {
            sortedMap.put(entry.getKey(), entry.getValue());
        }

        return sortedMap;
    }

    /**
     * 統計メッセージをフォーマット（複数メッセージ対応）
     * @return フォーマット済みメッセージのリスト
     */
    private static List<String> formatStatsMessages(String displayName, int serverTotal, Map<String, Integer> playerStats) {
        List<String> messages = new ArrayList<>();

        // ヘッダー部分を作成
        StringBuilder header = new StringBuilder();
        header.append("# ").append(displayName).append("\n\n");

        // 最終更新時刻
        ZonedDateTime now = ZonedDateTime.now(JAPAN_ZONE);
        header.append("**Last Updated:** ").append(now.format(DATE_FORMATTER)).append(" (JST)\n\n");

        // サーバー合計
        header.append("## Server Total\n");
        header.append("```\n");
        header.append(NUMBER_FORMAT.format(serverTotal)).append("\n");
        header.append("```\n\n");

        // プレイヤーランキングのヘッダー
        header.append("## Player Ranking\n");

        // 名前とスコアの最大幅を計算してアラインメント
        int maxNameLen = 0;
        int maxValueLen = 0;
        for (Map.Entry<String, Integer> entry : playerStats.entrySet()) {
            maxNameLen = Math.max(maxNameLen, entry.getKey().length());
            maxValueLen = Math.max(maxValueLen, NUMBER_FORMAT.format(entry.getValue()).length());
        }
        maxNameLen = Math.min(maxNameLen, 20); // 上限

        // ランキング行を生成
        List<String> rankLines = new ArrayList<>();
        int rank = 1;
        for (Map.Entry<String, Integer> entry : playerStats.entrySet()) {
            rankLines.add(formatRankLine(rank, entry.getKey(), entry.getValue(), maxNameLen, maxValueLen));
            rank++;
        }

        // メッセージを分割して構築
        StringBuilder currentMessage = new StringBuilder(header);
        currentMessage.append("```\n");

        int linesInCurrentBlock = 0;

        for (int i = 0; i < rankLines.size(); i++) {
            String line = rankLines.get(i);
            String lineWithNewline = line + "\n";

            // 現在のメッセージにこの行を追加した場合の長さを計算
            int potentialLength = currentMessage.length() + lineWithNewline.length() + "```".length();

            if (potentialLength > MAX_MESSAGE_LENGTH && linesInCurrentBlock > 0) {
                // 現在のブロックを閉じてメッセージを完成
                currentMessage.append("```");
                messages.add(currentMessage.toString());

                // 新しいメッセージを開始
                currentMessage = new StringBuilder();
                currentMessage.append("## Player Ranking (continued)\n");
                currentMessage.append("```\n");
                linesInCurrentBlock = 0;
            }

            currentMessage.append(lineWithNewline);
            linesInCurrentBlock++;
        }

        // 最後のメッセージを追加
        currentMessage.append("```");
        messages.add(currentMessage.toString());

        return messages;
    }

    /**
     * ランキング行をフォーマット（カラムアラインメント付き）
     */
    private static String formatRankLine(int rank, String name, int value, int maxNameLen, int maxValueLen) {
        String rankStr = String.format("%3d.", rank);
        String valueStr = NUMBER_FORMAT.format(value);
        String displayName = name.length() > maxNameLen ? name.substring(0, maxNameLen) : name;
        String paddedName = String.format("%-" + maxNameLen + "s", displayName);
        String paddedValue = String.format("%" + maxValueLen + "s", valueStr);
        return rankStr + " " + paddedName + "  " + paddedValue;
    }

    /**
     * テスト投稿を実行
     */
    public static CompletableFuture<Boolean> testPublish() {
        return CompletableFuture.supplyAsync(() -> {
            try {
                String forumChannelId = DiscordConfig.getForumChannelId();
                if (forumChannelId == null || forumChannelId.isEmpty()) {
                    ServerScoreboardLogger.warn("Forum channel ID not set for test");
                    return false;
                }

                // テストメッセージを作成
                ZonedDateTime now = ZonedDateTime.now(JAPAN_ZONE);
                String testMessage = "# MySB Discord Test\n\n" +
                    "**テスト投稿:** " + now.format(DATE_FORMATTER) + " (JST)\n\n" +
                    "Discord連携が正常に動作しています。\n\n" +
                    "---\n*MySB MOD - テスト投稿*";

                // テストスレッドを作成
                String threadId = DiscordManager.createForumThread(forumChannelId, "MySB Test - " + now.format(DATE_FORMATTER), testMessage).join();

                if (threadId != null) {
                    ServerScoreboardLogger.info("Test publish successful: Thread ID = " + threadId);
                    return true;
                } else {
                    ServerScoreboardLogger.error("Test publish failed");
                    return false;
                }
            } catch (Exception e) {
                ServerScoreboardLogger.error("Test publish error", e);
                return false;
            }
        });
    }
}
