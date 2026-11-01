package com.astralsmp.mysb;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.nbt.CompoundTag;

public class CustomScoreboardData {
    private final UUID playerId;
    private String customObjectiveName;
    private String customDisplayName;
    private final Map<String, Integer> customScores = new ConcurrentHashMap<>();
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
    
    public CompoundTag toNbt() {
        CompoundTag nbt = new CompoundTag();
        nbt.putBoolean("enabled", enabled);
        
        if (customObjectiveName != null) {
            nbt.putString("customObjectiveName", customObjectiveName);
        }
        if (customDisplayName != null) {
            nbt.putString("customDisplayName", customDisplayName);
        }
        
        // カスタムスコアを保存
        CompoundTag scoresNbt = new CompoundTag();
        for (Map.Entry<String, Integer> entry : customScores.entrySet()) {
            scoresNbt.putInt(entry.getKey(), entry.getValue());
        }
        nbt.put("customScores", scoresNbt);
        
        return nbt;
    }
    
    public void fromNbt(CompoundTag nbt) {
        this.enabled = nbt.getBooleanOr("enabled", false);
        
        if (nbt.contains("customObjectiveName")) {
            this.customObjectiveName = nbt.getStringOr("customObjectiveName", "");
        }
        if (nbt.contains("customDisplayName")) {
            this.customDisplayName = nbt.getStringOr("customDisplayName", "");
        }
        
        // カスタムスコアを読み込み
        customScores.clear();
        if (nbt.contains("customScores")) {
            CompoundTag scoresNbt = nbt.getCompoundOrEmpty("customScores");
            for (String key : scoresNbt.keySet()) {
                customScores.put(key, scoresNbt.getIntOr(key, 0));
            }
        }
    }
}