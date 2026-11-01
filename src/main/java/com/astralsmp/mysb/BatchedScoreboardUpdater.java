package com.astralsmp.mysb;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.network.protocol.game.ClientboundResetScorePacket;
import net.minecraft.network.protocol.game.ClientboundSetScorePacket;
import net.minecraft.server.level.ServerPlayer;

public class BatchedScoreboardUpdater {
    // Map<UUID, Map<"objectiveName:playerName", PendingUpdate>> - O(1)ルックアップ
    private static final Map<UUID, Map<String, PendingUpdate>> pendingUpdatesMap = new ConcurrentHashMap<>();
    private static final int MAX_BATCH_SIZE = 50; // 最大バッチサイズ（高速表示用に増加）
    private static final long BATCH_TIMEOUT_MS = 20; // バッチタイムアウト（20ms）- 超高速フラッシュ
    private static final long INSTANT_FLUSH_THRESHOLD = 5; // 5ms以内の変更は即座にフラッシュ

    public static class PendingUpdate {
        public final String objectiveName;
        public final String playerName;
        public final int score;
        public final boolean isRemoval;
        public final long timestamp;

        public PendingUpdate(String objectiveName, String playerName, int score, boolean isRemoval) {
            this.objectiveName = objectiveName;
            this.playerName = playerName;
            this.score = score;
            this.isRemoval = isRemoval;
            this.timestamp = System.currentTimeMillis();
        }

        public String getKey() {
            return objectiveName + ":" + playerName;
        }
    }

    // スコア更新をバッチに追加
    public static void addToBatch(ServerPlayer player, String objectiveName, String playerName, int score, boolean isRemoval) {
        UUID playerId = player.getUUID();
        Map<String, PendingUpdate> updates = pendingUpdatesMap.computeIfAbsent(playerId, k -> new ConcurrentHashMap<>());

        PendingUpdate newUpdate = new PendingUpdate(objectiveName, playerName, score, isRemoval);
        // O(1)で同じキーの更新を上書き
        updates.put(newUpdate.getKey(), newUpdate);

        // 即座フラッシュの条件をチェック
        boolean shouldInstantFlush = updates.size() >= MAX_BATCH_SIZE ||
                                   shouldFlushBatch(updates) ||
                                   shouldInstantFlush(updates);

        if (shouldInstantFlush) {
            flushBatch(player, updates);
        }
    }
    
    // バッチを強制的にフラッシュ
    public static void flushBatch(ServerPlayer player, Map<String, PendingUpdate> updates) {
        if (updates.isEmpty()) return;

        // レート制限チェック
        if (!RateLimiter.canSendScoreboardPacket(player.getUUID())) {
            ServerScoreboardLogger.warn("Cannot flush batch due to rate limit for player " + player.getName().getString());
            return;
        }

        int sentCount = 0;
        List<String> keysToRemove = new ArrayList<>();

        for (Map.Entry<String, PendingUpdate> entry : updates.entrySet()) {
            PendingUpdate update = entry.getValue();
            if (RateLimiter.canSendPacket(player.getUUID())) {
                if (update.isRemoval) {
                    player.connection.send(new ClientboundResetScorePacket(
                        update.playerName,
                        update.objectiveName
                    ));
                } else {
                    player.connection.send(new ClientboundSetScorePacket(
                        update.playerName,
                        update.objectiveName,
                        update.score,
                        Optional.empty(),
                        Optional.empty()
                    ));
                }
                keysToRemove.add(entry.getKey());
                sentCount++;
            } else {
                break; // レート制限に達したら停止
            }
        }

        // 送信済みのエントリを削除
        keysToRemove.forEach(updates::remove);
    }

    private static boolean shouldFlushBatch(Map<String, PendingUpdate> updates) {
        if (updates.isEmpty()) return false;
        // 最も古いタイムスタンプを見つける
        long oldestTimestamp = updates.values().stream()
            .mapToLong(u -> u.timestamp)
            .min()
            .orElse(System.currentTimeMillis());
        return System.currentTimeMillis() - oldestTimestamp >= BATCH_TIMEOUT_MS;
    }

    // 即座フラッシュが必要かチェック
    private static boolean shouldInstantFlush(Map<String, PendingUpdate> updates) {
        if (updates.isEmpty()) return false;
        // 最も新しいタイムスタンプを見つける
        long newestTimestamp = updates.values().stream()
            .mapToLong(u -> u.timestamp)
            .max()
            .orElse(0);
        return System.currentTimeMillis() - newestTimestamp <= INSTANT_FLUSH_THRESHOLD;
    }

    // 定期的なバッチフラッシュ（ServerTickEventで呼び出し）
    public static void flushAllBatches() {
        pendingUpdatesMap.entrySet().removeIf(entry -> {
            UUID playerId = entry.getKey();
            Map<String, PendingUpdate> updates = entry.getValue();

            // プレイヤーがオンラインかチェック
            ServerPlayer player = ServerScoreboardManager.server.getPlayerList().getPlayer(playerId);
            if (player != null && shouldFlushBatch(updates)) {
                flushBatch(player, updates);
            }

            return player == null; // オフラインプレイヤーのエントリを削除
        });
    }

    public static void clearPlayer(UUID playerId) {
        pendingUpdatesMap.remove(playerId);
    }
}