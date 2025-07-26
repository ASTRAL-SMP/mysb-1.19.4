package com.scserver.serverscoreboard;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class RateLimiter {
    private static final ConcurrentHashMap<String, Long> lastActionTime = new ConcurrentHashMap<>();
    
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
     * プレイヤーのレート制限情報をクリア
     */
    public static void clearPlayer(UUID playerId) {
        lastActionTime.entrySet().removeIf(entry -> entry.getKey().startsWith(playerId.toString()));
    }
    
    /**
     * すべてのレート制限情報をクリア
     */
    public static void clearAll() {
        lastActionTime.clear();
    }
}