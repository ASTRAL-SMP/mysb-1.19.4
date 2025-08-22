package com.scserver.serverscoreboard;

import java.util.*;

public class ScoreboardPriorityManager {
    
    public enum Priority {
        CRITICAL(1),    // 重要なスタッツ（死亡数、キル数など）
        HIGH(2),        // 頻繁に更新されるが重要なスタッツ
        NORMAL(3),      // 通常のスタッツ
        LOW(4);         // あまり重要でないスタッツ
        
        private final int level;
        Priority(int level) { this.level = level; }
        public int getLevel() { return level; }
    }
    
    // オブジェクティブ名に基づく優先度マッピング
    private static final Map<String, Priority> objectivePriorities = Map.of(
        "playerKillCount", Priority.CRITICAL,
        "deaths", Priority.CRITICAL,
        "health", Priority.HIGH,
        "score", Priority.HIGH,
        "level", Priority.NORMAL,
        "totalPlayTime", Priority.LOW
    );
    
    // プレイヤー名に基づく優先度（管理者、VIPなど）
    private static final Map<String, Priority> playerPriorities = new HashMap<>();
    
    public static Priority getObjectivePriority(String objectiveName) {
        // 完全一致を最初にチェック
        if (objectivePriorities.containsKey(objectiveName)) {
            return objectivePriorities.get(objectiveName);
        }
        
        // パターンマッチング
        for (Map.Entry<String, Priority> entry : objectivePriorities.entrySet()) {
            if (objectiveName.toLowerCase().contains(entry.getKey().toLowerCase())) {
                return entry.getValue();
            }
        }
        
        return Priority.NORMAL;
    }
    
    public static Priority getPlayerPriority(String playerName) {
        return playerPriorities.getOrDefault(playerName, Priority.NORMAL);
    }
    
    public static Priority calculateUpdatePriority(String objectiveName, String playerName) {
        Priority objPriority = getObjectivePriority(objectiveName);
        Priority playerPriority = getPlayerPriority(playerName);
        
        // より高い優先度を採用
        return objPriority.getLevel() <= playerPriority.getLevel() ? objPriority : playerPriority;
    }
    
    public static void setPlayerPriority(String playerName, Priority priority) {
        if (priority == Priority.NORMAL) {
            playerPriorities.remove(playerName);
        } else {
            playerPriorities.put(playerName, priority);
        }
        ServerScoreboardLogger.info("Set priority for player " + playerName + ": " + priority);
    }
    
    public static boolean shouldSkipUpdate(String objectiveName, String playerName, int currentLoad) {
        Priority priority = calculateUpdatePriority(objectiveName, playerName);
        
        // 負荷に基づいて更新をスキップするかどうか決定
        switch (priority) {
            case CRITICAL:
                return false; // 常に更新
            case HIGH:
                return currentLoad > 80; // 負荷80%超過時はスキップ
            case NORMAL:
                return currentLoad > 60; // 負荷60%超過時はスキップ
            case LOW:
                return currentLoad > 40; // 負荷40%超過時はスキップ
            default:
                return false;
        }
    }
}