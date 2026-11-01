package com.astralsmp.mysb;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;
import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

public class ScoreboardDataReader {
    
    public static Map<String, Map<String, Integer>> readScoreboardData(MinecraftServer server, String objectiveName) {
        Map<String, Map<String, Integer>> result = new HashMap<>();
        
        try {
            File scoreboardFile = server.getWorldPath(LevelResource.ROOT)
                    .resolve("data/minecraft/scoreboard.dat").toFile();
            
            if (!scoreboardFile.exists()) {
                ServerScoreboardLogger.warn("scoreboard.dat not found");
                return result;
            }
            
            CompoundTag nbt = NbtIo.readCompressed(scoreboardFile.toPath(), NbtAccounter.unlimitedHeap());
            if (!nbt.contains("data")) {
                ServerScoreboardLogger.warn("No data section in scoreboard.dat");
                return result;
            }
            
            CompoundTag data = nbt.getCompoundOrEmpty("data");
            
            // PlayerScoresセクションからスコアデータを読み取り
            if (data.contains("PlayerScores")) {
                CompoundTag playerScores = data.getCompoundOrEmpty("PlayerScores");
                
                for (String playerUuid : playerScores.keySet()) {
                    CompoundTag playerData = playerScores.getCompoundOrEmpty(playerUuid);
                    
                    if (playerData.contains(objectiveName)) {
                        CompoundTag objectiveData = playerData.getCompoundOrEmpty(objectiveName);
                        
                        if (objectiveData.contains("Name") && objectiveData.contains("Score")) {
                            String playerName = objectiveData.getStringOr("Name", "");
                            int score = objectiveData.getIntOr("Score", 0);
                            
                            Map<String, Integer> objectiveScores = result.computeIfAbsent(objectiveName, k -> new HashMap<>());
                            objectiveScores.put(playerName, score);
                        }
                    }
                }
            }
            
            
        } catch (IOException e) {
            ServerScoreboardLogger.error("Failed to read scoreboard.dat", e);
        }
        
        return result;
    }
    
    public static Map<String, String> readObjectiveDisplayNames(MinecraftServer server) {
        Map<String, String> result = new HashMap<>();
        
        try {
            File scoreboardFile = server.getWorldPath(LevelResource.ROOT)
                    .resolve("data/minecraft/scoreboard.dat").toFile();
            
            if (!scoreboardFile.exists()) {
                return result;
            }
            
            CompoundTag nbt = NbtIo.readCompressed(scoreboardFile.toPath(), NbtAccounter.unlimitedHeap());
            if (!nbt.contains("data")) {
                return result;
            }
            
            CompoundTag data = nbt.getCompoundOrEmpty("data");
            
            // Objectivesセクションからオブジェクティブ情報を読み取り
            if (data.contains("Objectives")) {
                ListTag objectives = data.getListOrEmpty("Objectives");
                
                for (int i = 0; i < objectives.size(); i++) {
                    CompoundTag objective = objectives.getCompoundOrEmpty(i);
                    
                    if (objective.contains("Name") && objective.contains("DisplayName")) {
                        String name = objective.getStringOr("Name", "");
                        String displayName = objective.getStringOr("DisplayName", "");
                        result.put(name, displayName);
                    }
                }
            }
            
        } catch (IOException e) {
            ServerScoreboardLogger.error("Failed to read objective display names from scoreboard.dat", e);
        }
        
        return result;
    }
    
    public static Map<String, Integer> getAllPlayersScoresForObjective(MinecraftServer server, String objectiveName) {
        Map<String, Integer> result = new HashMap<>();
        
        try {
            File scoreboardFile = server.getWorldPath(LevelResource.ROOT)
                    .resolve("data/minecraft/scoreboard.dat").toFile();
            
            if (!scoreboardFile.exists()) {
                return result;
            }
            
            CompoundTag nbt = NbtIo.readCompressed(scoreboardFile.toPath(), NbtAccounter.unlimitedHeap());
            if (!nbt.contains("data")) {
                return result;
            }
            
            CompoundTag data = nbt.getCompoundOrEmpty("data");
            
            // PlayerScoresセクションからスコアデータを読み取り
            if (data.contains("PlayerScores")) {
                CompoundTag playerScores = data.getCompoundOrEmpty("PlayerScores");
                
                for (String playerUuid : playerScores.keySet()) {
                    CompoundTag playerData = playerScores.getCompoundOrEmpty(playerUuid);
                    
                    if (playerData.contains(objectiveName)) {
                        CompoundTag objectiveData = playerData.getCompoundOrEmpty(objectiveName);
                        
                        if (objectiveData.contains("Name") && objectiveData.contains("Score")) {
                            String playerName = objectiveData.getStringOr("Name", "");
                            int score = objectiveData.getIntOr("Score", 0);
                            result.put(playerName, score);
                        }
                    }
                }
            }
            
        } catch (IOException e) {
            ServerScoreboardLogger.error("Failed to read player scores from scoreboard.dat", e);
        }
        
        return result;
    }
}