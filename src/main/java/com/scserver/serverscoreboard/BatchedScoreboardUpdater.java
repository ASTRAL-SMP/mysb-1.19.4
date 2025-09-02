package com.scserver.serverscoreboard;

import net.minecraft.network.packet.s2c.play.ScoreboardPlayerUpdateS2CPacket;
import net.minecraft.scoreboard.ServerScoreboard;
import net.minecraft.server.network.ServerPlayerEntity;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class BatchedScoreboardUpdater {
    private static final Map<UUID, List<PendingUpdate>> pendingUpdates = new ConcurrentHashMap<>();
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
    }
    
    // スコア更新をバッチに追加
    public static void addToBatch(ServerPlayerEntity player, String objectiveName, String playerName, int score, boolean isRemoval) {
        UUID playerId = player.getUuid();
        List<PendingUpdate> updates = pendingUpdates.computeIfAbsent(playerId, k -> new ArrayList<>());
        
        synchronized (updates) {
            // 同じプレイヤー・オブジェクトの更新がある場合は最新のもので上書き（差分送信）
            updates.removeIf(existing -> 
                existing.objectiveName.equals(objectiveName) && 
                existing.playerName.equals(playerName));
            
            updates.add(new PendingUpdate(objectiveName, playerName, score, isRemoval));
            
            // 即座フラッシュの条件をチェック
            boolean shouldInstantFlush = updates.size() >= MAX_BATCH_SIZE || 
                                       shouldFlushBatch(updates) ||
                                       shouldInstantFlush(updates);
            
            if (shouldInstantFlush) {
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
        
    }
    
    private static boolean shouldFlushBatch(List<PendingUpdate> updates) {
        if (updates.isEmpty()) return false;
        long oldestTimestamp = updates.get(0).timestamp;
        return System.currentTimeMillis() - oldestTimestamp >= BATCH_TIMEOUT_MS;
    }
    
    // 即座フラッシュが必要かチェック
    private static boolean shouldInstantFlush(List<PendingUpdate> updates) {
        if (updates.isEmpty()) return false;
        long newestTimestamp = updates.get(updates.size() - 1).timestamp;
        return System.currentTimeMillis() - newestTimestamp <= INSTANT_FLUSH_THRESHOLD;
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