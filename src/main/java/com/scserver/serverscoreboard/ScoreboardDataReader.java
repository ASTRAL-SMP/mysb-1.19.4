package com.scserver.serverscoreboard;

import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtList;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.WorldSavePath;

import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

public class ScoreboardDataReader {
    
    public static Map<String, Map<String, Integer>> readScoreboardData(MinecraftServer server, String objectiveName) {
        Map<String, Map<String, Integer>> result = new HashMap<>();
        
        try {
            File scoreboardFile = server.getSavePath(WorldSavePath.ROOT)
                    .resolve("data/scoreboard.dat").toFile();
            
            if (!scoreboardFile.exists()) {
                ServerScoreboardLogger.warn("scoreboard.dat not found");
                return result;
            }
            
            NbtCompound nbt = NbtIo.readCompressed(scoreboardFile);
            if (!nbt.contains("data")) {
                ServerScoreboardLogger.warn("No data section in scoreboard.dat");
                return result;
            }
            
            NbtCompound data = nbt.getCompound("data");
            
            // PlayerScoresセクションからスコアデータを読み取り
            if (data.contains("PlayerScores")) {
                NbtCompound playerScores = data.getCompound("PlayerScores");
                
                for (String playerUuid : playerScores.getKeys()) {
                    NbtCompound playerData = playerScores.getCompound(playerUuid);
                    
                    if (playerData.contains(objectiveName)) {
                        NbtCompound objectiveData = playerData.getCompound(objectiveName);
                        
                        if (objectiveData.contains("Name") && objectiveData.contains("Score")) {
                            String playerName = objectiveData.getString("Name");
                            int score = objectiveData.getInt("Score");
                            
                            Map<String, Integer> objectiveScores = result.computeIfAbsent(objectiveName, k -> new HashMap<>());
                            objectiveScores.put(playerName, score);
                        }
                    }
                }
            }
            
            ServerScoreboardLogger.debug("Read " + result.size() + " player scores for objective " + objectiveName);
            
        } catch (IOException e) {
            ServerScoreboardLogger.error("Failed to read scoreboard.dat", e);
        }
        
        return result;
    }
    
    public static Map<String, String> readObjectiveDisplayNames(MinecraftServer server) {
        Map<String, String> result = new HashMap<>();
        
        try {
            File scoreboardFile = server.getSavePath(WorldSavePath.ROOT)
                    .resolve("data/scoreboard.dat").toFile();
            
            if (!scoreboardFile.exists()) {
                return result;
            }
            
            NbtCompound nbt = NbtIo.readCompressed(scoreboardFile);
            if (!nbt.contains("data")) {
                return result;
            }
            
            NbtCompound data = nbt.getCompound("data");
            
            // Objectivesセクションからオブジェクティブ情報を読み取り
            if (data.contains("Objectives")) {
                NbtList objectives = data.getList("Objectives", 10); // 10 = Compound
                
                for (int i = 0; i < objectives.size(); i++) {
                    NbtCompound objective = objectives.getCompound(i);
                    
                    if (objective.contains("Name") && objective.contains("DisplayName")) {
                        String name = objective.getString("Name");
                        String displayName = objective.getString("DisplayName");
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
            File scoreboardFile = server.getSavePath(WorldSavePath.ROOT)
                    .resolve("data/scoreboard.dat").toFile();
            
            if (!scoreboardFile.exists()) {
                return result;
            }
            
            NbtCompound nbt = NbtIo.readCompressed(scoreboardFile);
            if (!nbt.contains("data")) {
                return result;
            }
            
            NbtCompound data = nbt.getCompound("data");
            
            // PlayerScoresセクションからスコアデータを読み取り
            if (data.contains("PlayerScores")) {
                NbtCompound playerScores = data.getCompound("PlayerScores");
                
                for (String playerUuid : playerScores.getKeys()) {
                    NbtCompound playerData = playerScores.getCompound(playerUuid);
                    
                    if (playerData.contains(objectiveName)) {
                        NbtCompound objectiveData = playerData.getCompound(objectiveName);
                        
                        if (objectiveData.contains("Name") && objectiveData.contains("Score")) {
                            String playerName = objectiveData.getString("Name");
                            int score = objectiveData.getInt("Score");
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