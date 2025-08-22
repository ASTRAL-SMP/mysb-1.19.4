package com.scserver.serverscoreboard;

import com.google.gson.*;
import net.minecraft.server.MinecraftServer;
import net.minecraft.stat.Stat;
import net.minecraft.stat.StatType;
import net.minecraft.stat.Stats;
import net.minecraft.util.Identifier;
import net.minecraft.registry.Registries;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class PlayerStatsCache {
    private static final Map<String, Map<String, Integer>> playerStatsCache = new ConcurrentHashMap<>();
    private static final Gson gson = new GsonBuilder().setPrettyPrinting().create();
    private static MinecraftServer server;
    private static Path cacheFile;
    
    public static void initialize(MinecraftServer minecraftServer) {
        server = minecraftServer;
        cacheFile = server.getSavePath(net.minecraft.util.WorldSavePath.ROOT)
            .resolve("serverscoreboard")
            .resolve("player_stats_cache.json");
        
        // ディレクトリを作成
        try {
            Files.createDirectories(cacheFile.getParent());
        } catch (IOException e) {
            ServerScoreboardLogger.error("Failed to create cache directory", e);
        }
        
        loadCache();
    }
    
    // プレイヤーの統計を更新
    public static void updatePlayerStats(String playerName, String statId, int value) {
        Map<String, Integer> playerStats = playerStatsCache.computeIfAbsent(playerName, k -> new ConcurrentHashMap<>());
        playerStats.put(statId, value);
    }
    
    // プレイヤーの統計を取得
    public static int getPlayerStat(String playerName, String statId) {
        Map<String, Integer> playerStats = playerStatsCache.get(playerName);
        if (playerStats != null) {
            return playerStats.getOrDefault(statId, 0);
        }
        return 0;
    }
    
    // すべてのプレイヤーの特定の統計を取得
    public static Map<String, Integer> getAllPlayerStats(String statId) {
        Map<String, Integer> result = new HashMap<>();
        
        // キャッシュされたデータから取得
        for (Map.Entry<String, Map<String, Integer>> entry : playerStatsCache.entrySet()) {
            String playerName = entry.getKey();
            Map<String, Integer> playerStats = entry.getValue();
            int value = playerStats.getOrDefault(statId, 0);
            if (value > 0) {
                result.put(playerName, value);
            }
        }
        
        return result;
    }
    
    // プレイヤーのすべての統計を取得
    public static Map<String, Integer> getPlayerAllStats(String playerName) {
        return playerStatsCache.getOrDefault(playerName, new HashMap<>());
    }
    
    // キャッシュを保存
    public static void saveCache() {
        try {
            JsonObject root = new JsonObject();
            
            for (Map.Entry<String, Map<String, Integer>> playerEntry : playerStatsCache.entrySet()) {
                JsonObject playerData = new JsonObject();
                for (Map.Entry<String, Integer> statEntry : playerEntry.getValue().entrySet()) {
                    playerData.addProperty(statEntry.getKey(), statEntry.getValue());
                }
                root.add(playerEntry.getKey(), playerData);
            }
            
            try (FileWriter writer = new FileWriter(cacheFile.toFile())) {
                gson.toJson(root, writer);
            }
            
            ServerScoreboardLogger.info("Saved player stats cache with " + playerStatsCache.size() + " players");
        } catch (Exception e) {
            ServerScoreboardLogger.error("Failed to save player stats cache", e);
        }
    }
    
    // キャッシュを読み込み
    private static void loadCache() {
        if (!Files.exists(cacheFile)) {
            return;
        }
        
        try {
            try (FileReader reader = new FileReader(cacheFile.toFile())) {
                JsonObject root = gson.fromJson(reader, JsonObject.class);
                if (root != null) {
                    for (Map.Entry<String, JsonElement> playerEntry : root.entrySet()) {
                        String playerName = playerEntry.getKey();
                        JsonObject playerData = playerEntry.getValue().getAsJsonObject();
                        
                        Map<String, Integer> stats = new ConcurrentHashMap<>();
                        for (Map.Entry<String, JsonElement> statEntry : playerData.entrySet()) {
                            stats.put(statEntry.getKey(), statEntry.getValue().getAsInt());
                        }
                        
                        playerStatsCache.put(playerName, stats);
                    }
                }
            }
            
            ServerScoreboardLogger.info("Loaded player stats cache with " + playerStatsCache.size() + " players");
        } catch (Exception e) {
            ServerScoreboardLogger.error("Failed to load player stats cache", e);
        }
    }
    
    // すべてのプレイヤー名を取得
    public static Set<String> getAllPlayerNames() {
        return new HashSet<>(playerStatsCache.keySet());
    }
    
    // プレイヤーを削除
    public static void removePlayer(String playerName) {
        playerStatsCache.remove(playerName);
    }
    
    // すべてクリア
    public static void clear() {
        playerStatsCache.clear();
    }
}