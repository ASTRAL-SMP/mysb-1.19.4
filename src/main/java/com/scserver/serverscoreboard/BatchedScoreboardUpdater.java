package com.scserver.serverscoreboard;

import net.minecraft.network.packet.s2c.play.ScoreboardPlayerUpdateS2CPacket;
import net.minecraft.scoreboard.ServerScoreboard;
import net.minecraft.server.network.ServerPlayerEntity;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class BatchedScoreboardUpdater {
    private static final Map<UUID, List<PendingUpdate>> pendingUpdates = new ConcurrentHashMap<>();
    private static final int MAX_BATCH_SIZE = 20; // 最大バッチサイズ
    private static final long BATCH_TIMEOUT_MS = 100; // バッチタイムアウト（100ms）
    
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
    }
    
    // スコア更新をバッチに追加
    public static void addToBatch(ServerPlayerEntity player, String objectiveName, String playerName, int score, boolean isRemoval) {
        UUID playerId = player.getUuid();
        List<PendingUpdate> updates = pendingUpdates.computeIfAbsent(playerId, k -> new ArrayList<>());
        
        synchronized (updates) {
            updates.add(new PendingUpdate(objectiveName, playerName, score, isRemoval));
            
            // バッチサイズまたはタイムアウトに達した場合、即座に送信
            if (updates.size() >= MAX_BATCH_SIZE || shouldFlushBatch(updates)) {
                flushBatch(player, updates);
            }
        }
    }
    
    // バッチを強制的にフラッシュ
    public static void flushBatch(ServerPlayerEntity player, List<PendingUpdate> updates) {
        if (updates.isEmpty()) return;
        
        // レート制限チェック
        if (!RateLimiter.canSendScoreboardPacket(player.getUuid())) {
            ServerScoreboardLogger.warn("Cannot flush batch due to rate limit for player " + player.getName().getString());
            return;
        }
        
        int sentCount = 0;
        synchronized (updates) {
            for (PendingUpdate update : updates) {
                if (RateLimiter.canSendPacket(player.getUuid())) {
                    player.networkHandler.sendPacket(new ScoreboardPlayerUpdateS2CPacket(
                        update.isRemoval ? ServerScoreboard.UpdateMode.REMOVE : ServerScoreboard.UpdateMode.CHANGE,
                        update.objectiveName,
                        update.playerName,
                        update.score
                    ));
                    sentCount++;
                } else {
                    break; // レート制限に達したら停止
                }
            }
            updates.clear();
        }
        
        ServerScoreboardLogger.info("Flushed batch for player " + player.getName().getString() + ": " + sentCount + " updates sent");
    }
    
    private static boolean shouldFlushBatch(List<PendingUpdate> updates) {
        if (updates.isEmpty()) return false;
        long oldestTimestamp = updates.get(0).timestamp;
        return System.currentTimeMillis() - oldestTimestamp >= BATCH_TIMEOUT_MS;
    }
    
    // 定期的なバッチフラッシュ（ServerTickEventで呼び出し）
    public static void flushAllBatches() {
        pendingUpdates.entrySet().removeIf(entry -> {
            UUID playerId = entry.getKey();
            List<PendingUpdate> updates = entry.getValue();
            
            // プレイヤーがオンラインかチェック
            ServerPlayerEntity player = ServerScoreboardManager.server.getPlayerManager().getPlayer(playerId);
            if (player != null && shouldFlushBatch(updates)) {
                flushBatch(player, updates);
            }
            
            return player == null; // オフラインプレイヤーのエントリを削除
        });
    }
    
    public static void clearPlayer(UUID playerId) {
        pendingUpdates.remove(playerId);
    }
}