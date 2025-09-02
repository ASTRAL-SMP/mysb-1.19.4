package com.scserver.serverscoreboard;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 連続ブロック設置時の高速バルク更新システム
 * 短時間での大量更新を効率的に処理
 */
public class BulkUpdateManager {
    // バルク更新の設定
    private static final long BULK_TIME_WINDOW_MS = 1000; // 1秒間のバルクウィンドウ
    private static final int BULK_UPDATE_THRESHOLD = 5; // 5個以上でバルク処理
    
    // プレイヤーごとの更新履歴
    private static final Map<UUID, List<UpdateEvent>> playerUpdateHistory = new ConcurrentHashMap<>();
    
    // 更新イベントの記録
    public static class UpdateEvent {
        public final long timestamp;
        public final String type; // "block_place", "block_break", "kill", etc.
        
        public UpdateEvent(String type) {
            this.timestamp = System.currentTimeMillis();
            this.type = type;
        }
    }
    
    // 更新イベントを記録し、バルク処理が必要かチェック
    public static boolean recordUpdate(UUID playerId, String updateType) {
        if (playerId == null) return false;
        
        List<UpdateEvent> history = playerUpdateHistory.computeIfAbsent(playerId, k -> new ArrayList<>());
        
        synchronized (history) {
            // 新しい更新を記録
            history.add(new UpdateEvent(updateType));
            
            // 古い履歴をクリーンアップ
            long currentTime = System.currentTimeMillis();
            history.removeIf(event -> currentTime - event.timestamp > BULK_TIME_WINDOW_MS);
            
            // バルク処理が必要かチェック
            return history.size() >= BULK_UPDATE_THRESHOLD;
        }
    }
    
    // バルク更新を実行
    public static void executeBulkUpdate(UUID playerId) {
        if (playerId == null) return;
        
        List<UpdateEvent> history = playerUpdateHistory.get(playerId);
        if (history == null || history.isEmpty()) return;
        
        synchronized (history) {
            int eventCount = history.size();
            if (eventCount >= BULK_UPDATE_THRESHOLD) {
                
                // 統計を強制更新（一度だけ）
                TotalStatsManager.scheduleInstantUpdate();
                
                // 履歴をクリア
                history.clear();
                
                ServerScoreboardLogger.debug("Bulk update completed for player " + playerId);
            }
        }
    }
    
    // 指定プレイヤーの更新履歴をクリア
    public static void clearPlayerHistory(UUID playerId) {
        playerUpdateHistory.remove(playerId);
    }
    
    // 全履歴をクリア
    public static void clearAllHistory() {
        playerUpdateHistory.clear();
    }
    
    // プレイヤーの現在の更新回数を取得
    public static int getCurrentUpdateCount(UUID playerId) {
        List<UpdateEvent> history = playerUpdateHistory.get(playerId);
        if (history == null) return 0;
        
        synchronized (history) {
            // 最新1秒間のイベント数を数える
            long currentTime = System.currentTimeMillis();
            return (int) history.stream()
                .filter(event -> currentTime - event.timestamp <= BULK_TIME_WINDOW_MS)
                .count();
        }
    }
    
    // デバッグ情報を取得
    public static String getDebugInfo() {
        StringBuilder sb = new StringBuilder();
        sb.append("Bulk Update Manager Status:\n");
        sb.append("Active players: ").append(playerUpdateHistory.size()).append("\n");
        
        for (Map.Entry<UUID, List<UpdateEvent>> entry : playerUpdateHistory.entrySet()) {
            int count = getCurrentUpdateCount(entry.getKey());
            if (count > 0) {
                sb.append("Player ").append(entry.getKey().toString().substring(0, 8))
                  .append(": ").append(count).append(" events\n");
            }
        }
        
        return sb.toString();
    }
}