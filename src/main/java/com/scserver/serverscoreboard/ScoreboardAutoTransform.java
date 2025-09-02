package com.scserver.serverscoreboard;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.WorldSavePath;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.nio.file.Path;
import java.util.*;

public class ScoreboardAutoTransform {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static AutoTransformConfig config = new AutoTransformConfig();
    private static MinecraftServer server;
    private static File configFile;

    public static void init(MinecraftServer minecraftServer) {
        server = minecraftServer;
        loadConfig();
    }

    public static void loadConfig() {
        if (server == null) return;
        
        Path configDir = server.getSavePath(WorldSavePath.ROOT).resolve("config/mysb");
        configFile = configDir.resolve("auto_transform.json").toFile();
        
        try {
            configDir.toFile().mkdirs();
            
            if (configFile.exists()) {
                try (FileReader reader = new FileReader(configFile)) {
                    JsonObject json = JsonParser.parseReader(reader).getAsJsonObject();
                    config = GSON.fromJson(json, AutoTransformConfig.class);
                    ServerScoreboardLogger.info("Loaded auto-transform configuration");
                }
            } else {
                // デフォルト設定を作成
                createDefaultConfig();
                saveConfig();
                ServerScoreboardLogger.info("Created default auto-transform configuration");
            }
        } catch (Exception e) {
            ServerScoreboardLogger.error("Failed to load auto-transform config", e);
            config = new AutoTransformConfig();
        }
    }

    public static void saveConfig() {
        if (configFile == null) return;
        
        try {
            configFile.getParentFile().mkdirs();
            try (FileWriter writer = new FileWriter(configFile)) {
                GSON.toJson(config, writer);
                ServerScoreboardLogger.info("Saved auto-transform configuration");
            }
        } catch (Exception e) {
            ServerScoreboardLogger.error("Failed to save auto-transform config", e);
        }
    }

    private static void createDefaultConfig() {
        config = new AutoTransformConfig();
        config.enabled = false; // デフォルトで無効
        config.autoApplyToNewPlayers = false;
        
        // デフォルトの変換ルール例
        TransformRule pointsRule = new TransformRule();
        pointsRule.objectiveName = "test_points";
        pointsRule.newDisplayName = "ポイントランキング";
        pointsRule.scoreOffsets = new HashMap<>();
        pointsRule.scoreOffsets.put("Sim_256", 1000); // 1000点ボーナス
        
        TransformRule killsRule = new TransformRule();
        killsRule.objectiveName = "test_kills";
        killsRule.newDisplayName = "撃破数ランキング";
        killsRule.scoreOffsets = new HashMap<>();
        
        TransformRule deathsRule = new TransformRule();
        deathsRule.objectiveName = "deaths";
        deathsRule.newDisplayName = "デス数";
        deathsRule.scoreOffsets = new HashMap<>();
        
        config.transformRules = Arrays.asList(pointsRule, killsRule, deathsRule);
    }

    public static boolean shouldAutoTransform(String objectiveName) {
        if (!config.enabled) return false;
        
        return config.transformRules.stream()
                .anyMatch(rule -> rule.objectiveName.equals(objectiveName));
    }

    public static ScoreboardTransformData createTransformData(UUID playerId, String objectiveName) {
        if (!config.enabled) return null;
        
        TransformRule rule = config.transformRules.stream()
                .filter(r -> r.objectiveName.equals(objectiveName))
                .findFirst()
                .orElse(null);
        
        if (rule == null) return null;
        
        ScoreboardTransformData transformData = new ScoreboardTransformData(playerId);
        
        // 表示名の変換を設定
        if (rule.newDisplayName != null && !rule.newDisplayName.isEmpty()) {
            transformData.setObjectiveDisplayNameMapping(objectiveName, rule.newDisplayName);
        }
        
        // スコアオフセットを設定
        if (rule.scoreOffsets != null) {
            rule.scoreOffsets.forEach((playerName, offset) -> {
                transformData.setScoreValueOffset(objectiveName, playerName, offset);
            });
        }
        
        transformData.setEnabled(true);
        return transformData;
    }

    public static boolean isAutoApplyEnabled() {
        return config.enabled && config.autoApplyToNewPlayers;
    }

    public static void reloadConfig() {
        loadConfig();
        ServerScoreboardLogger.info("Reloaded auto-transform configuration");
    }

    public static AutoTransformConfig getConfig() {
        return config;
    }

    public static void setConfig(AutoTransformConfig newConfig) {
        config = newConfig;
        saveConfig();
    }

    public static class AutoTransformConfig {
        public boolean enabled = true;
        public boolean autoApplyToNewPlayers = true;
        public List<TransformRule> transformRules = new ArrayList<>();
    }

    public static class TransformRule {
        public String objectiveName;
        public String newDisplayName;
        public Map<String, Integer> scoreOffsets = new HashMap<>();
    }
}