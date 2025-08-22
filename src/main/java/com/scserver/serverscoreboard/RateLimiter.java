package com.scserver.serverscoreboard;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.Map;

public class RateLimiter {
    private static final ConcurrentHashMap<String, Long> lastActionTime = new ConcurrentHashMap<>();
    // パケット数制限: プレイヤーUUID -> 直近1秒間のパケット数
    private static final ConcurrentHashMap<UUID, PacketCounter> packetCounters = new ConcurrentHashMap<>();
    
    // 設定可能な制限値（動的に調整される）
    private static volatile int maxPacketsPerSecond = 50; // 1秒間の最大パケット数
    private static volatile int maxScoreboardUpdatesPerSecond = 10; // スコアボード更新の最大回数
    
    // 基準値
    private static final int BASE_MAX_PACKETS_PER_SECOND = 50;
    private static final int BASE_MAX_SCOREBOARD_UPDATES_PER_SECOND = 10;
    
    // パケットカウンター内部クラス
    private static class PacketCounter {
        private final AtomicInteger count = new AtomicInteger(0);
        private volatile long windowStart = System.currentTimeMillis();
        
        public boolean incrementAndCheck(int maxPackets) {
            long currentTime = System.currentTimeMillis();
            
            // 1秒経過したらカウンターをリセット
            if (currentTime - windowStart >= 1000) {
                count.set(0);
                windowStart = currentTime;
            }
            
            int currentCount = count.incrementAndGet();
            boolean allowed = currentCount <= maxPackets;
            
            if (allowed) {
                // パケット送信統計を記録
                NetworkLoadMonitor.recordPacketSent(estimatePacketSize());
            }
            
            return allowed;
        }
        
        private int estimatePacketSize() {
            // スコアボードパケットの推定サイズ（バイト）
            return 64; // 平均的なスコアボードパケットサイズ
        }
        
        public int getCurrentCount() {
            long currentTime = System.currentTimeMillis();
            if (currentTime - windowStart >= 1000) {
                return 0; // 期限切れの場合は0を返す
            }
            return count.get();
        }
    }
    
    /**
     * プレイヤーのアクションがレート制限に引っかかるかチェック
     * @param playerId プレイヤーのUUID
     * @param actionType アクションタイプ（"command", "gui"など）
     * @param cooldownMs クールダウン時間（ミリ秒）
     * @return true: アクション可能、false: レート制限中
     */
    public static boolean canPerformAction(UUID playerId, String actionType, int cooldownMs) {
        String key = playerId.toString() + ":" + actionType;
        long currentTime = System.currentTimeMillis();
        
        Long lastTime = lastActionTime.get(key);
        if (lastTime == null || currentTime - lastTime >= cooldownMs) {
            lastActionTime.put(key, currentTime);
            return true;
        }
        
        return false;
    }
    
    /**
     * スコアボード更新パケットの送信可否をチェック（DDOS対策）
     * @param playerId プレイヤーのUUID
     * @return true: 送信可能、false: レート制限により送信不可
     */
    public static boolean canSendScoreboardPacket(UUID playerId) {
        // 動的レート制限を適用
        updateDynamicRateLimits();
        
        PacketCounter counter = packetCounters.computeIfAbsent(playerId, k -> new PacketCounter());
        return counter.incrementAndCheck(maxScoreboardUpdatesPerSecond);
    }
    
    /**
     * 一般的なパケット送信の可否をチェック
     * @param playerId プレイヤーのUUID
     * @return true: 送信可能、false: レート制限により送信不可
     */
    public static boolean canSendPacket(UUID playerId) {
        // 動的レート制限を適用
        updateDynamicRateLimits();
        
        PacketCounter counter = packetCounters.computeIfAbsent(playerId, k -> new PacketCounter());
        return counter.incrementAndCheck(maxPacketsPerSecond);
    }
    
    /**
     * プレイヤーの現在のパケット送信数を取得（デバッグ用）
     * @param playerId プレイヤーのUUID
     * @return 現在の1秒間のパケット数
     */
    public static int getCurrentPacketCount(UUID playerId) {
        PacketCounter counter = packetCounters.get(playerId);
        return counter != null ? counter.getCurrentCount() : 0;
    }
    
    /**
     * プレイヤーのレート制限情報をクリア
     */
    public static void clearPlayer(UUID playerId) {
        lastActionTime.entrySet().removeIf(entry -> entry.getKey().startsWith(playerId.toString()));
        packetCounters.remove(playerId);
    }
    
    /**
     * すべてのレート制限情報をクリア
     */
    public static void clearAll() {
        lastActionTime.clear();
        packetCounters.clear();
    }
    
    /**
     * レート制限の設定値を取得（監視用）
     */
    public static Map<String, Integer> getRateLimitSettings() {
        return Map.of(
            "maxPacketsPerSecond", maxPacketsPerSecond,
            "maxScoreboardUpdatesPerSecond", maxScoreboardUpdatesPerSecond,
            "baseMaxPacketsPerSecond", BASE_MAX_PACKETS_PER_SECOND,
            "baseMaxScoreboardUpdatesPerSecond", BASE_MAX_SCOREBOARD_UPDATES_PER_SECOND
        );
    }
    
    // ネットワーク負荷に基づいて動的にレート制限を調整
    private static void updateDynamicRateLimits() {
        NetworkLoadMonitor.LoadLevel loadLevel = NetworkLoadMonitor.getCurrentLoadLevel();
        
        switch (loadLevel) {
            case CRITICAL:
                maxPacketsPerSecond = Math.max(5, BASE_MAX_PACKETS_PER_SECOND / 10);
                maxScoreboardUpdatesPerSecond = Math.max(1, BASE_MAX_SCOREBOARD_UPDATES_PER_SECOND / 10);
                break;
            case HIGH:
                maxPacketsPerSecond = Math.max(15, BASE_MAX_PACKETS_PER_SECOND / 3);
                maxScoreboardUpdatesPerSecond = Math.max(3, BASE_MAX_SCOREBOARD_UPDATES_PER_SECOND / 3);
                break;
            case MEDIUM:
                maxPacketsPerSecond = Math.max(25, BASE_MAX_PACKETS_PER_SECOND / 2);
                maxScoreboardUpdatesPerSecond = Math.max(5, BASE_MAX_SCOREBOARD_UPDATES_PER_SECOND / 2);
                break;
            case LOW:
            default:
                maxPacketsPerSecond = BASE_MAX_PACKETS_PER_SECOND;
                maxScoreboardUpdatesPerSecond = BASE_MAX_SCOREBOARD_UPDATES_PER_SECOND;
                break;
        }
    }
    
    public static String getCurrentLimits() {
        return String.format("Current Limits - Packets/sec: %d, Scoreboard Updates/sec: %d", 
            maxPacketsPerSecond, maxScoreboardUpdatesPerSecond);
    }
}