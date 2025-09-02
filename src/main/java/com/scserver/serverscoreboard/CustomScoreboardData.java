package com.scserver.serverscoreboard;

import net.minecraft.nbt.NbtCompound;
import java.util.*;

public class CustomScoreboardData {
    private final UUID playerId;
    private String customObjectiveName;
    private String customDisplayName;
    private final Map<String, Integer> customScores = new HashMap<>();
    private boolean enabled = false;

    public CustomScoreboardData(UUID playerId) {
        this.playerId = playerId;
    }

    public void setCustomObjective(String name, String displayName) {
        this.customObjectiveName = name;
        this.customDisplayName = displayName;
        this.enabled = true;
    }

    public void setCustomScore(String playerName, int score) {
        customScores.put(playerName, score);
    }

    public void removeCustomScore(String playerName) {
        customScores.remove(playerName);
    }

    public void clearCustomScores() {
        customScores.clear();
    }

    public void disable() {
        this.enabled = false;
        this.customObjectiveName = null;
        this.customDisplayName = null;
        this.customScores.clear();
    }

    public boolean isEnabled() {
        return enabled;
    }

    public String getCustomObjectiveName() {
        return customObjectiveName;
    }

    public String getCustomDisplayName() {
        return customDisplayName;
    }

    public Map<String, Integer> getCustomScores() {
        return new HashMap<>(customScores);
    }

    public UUID getPlayerId() {
        return playerId;
    }
    
    public NbtCompound toNbt() {
        NbtCompound nbt = new NbtCompound();
        nbt.putBoolean("enabled", enabled);
        
        if (customObjectiveName != null) {
            nbt.putString("customObjectiveName", customObjectiveName);
        }
        if (customDisplayName != null) {
            nbt.putString("customDisplayName", customDisplayName);
        }
        
        // カスタムスコアを保存
        NbtCompound scoresNbt = new NbtCompound();
        for (Map.Entry<String, Integer> entry : customScores.entrySet()) {
            scoresNbt.putInt(entry.getKey(), entry.getValue());
        }
        nbt.put("customScores", scoresNbt);
        
        return nbt;
    }
    
    public void fromNbt(NbtCompound nbt) {
        this.enabled = nbt.getBoolean("enabled");
        
        if (nbt.contains("customObjectiveName")) {
            this.customObjectiveName = nbt.getString("customObjectiveName");
        }
        if (nbt.contains("customDisplayName")) {
            this.customDisplayName = nbt.getString("customDisplayName");
        }
        
        // カスタムスコアを読み込み
        customScores.clear();
        if (nbt.contains("customScores")) {
            NbtCompound scoresNbt = nbt.getCompound("customScores");
            for (String key : scoresNbt.getKeys()) {
                customScores.put(key, scoresNbt.getInt(key));
            }
        }
    }
}