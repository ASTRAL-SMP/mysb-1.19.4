package com.scserver.serverscoreboard;

import net.minecraft.nbt.NbtCompound;
import java.util.*;

public class ScoreboardTransformData {
    private final UUID playerId;
    private final Map<String, String> objectiveDisplayNameMappings = new HashMap<>();
    private final Map<String, Map<String, Integer>> scoreValueOffsets = new HashMap<>();
    private boolean enabled = false;

    public ScoreboardTransformData(UUID playerId) {
        this.playerId = playerId;
    }

    public void setObjectiveDisplayNameMapping(String objectiveName, String newDisplayName) {
        objectiveDisplayNameMappings.put(objectiveName, newDisplayName);
    }

    public void setScoreValueOffset(String objectiveName, String scoreName, int offset) {
        scoreValueOffsets.computeIfAbsent(objectiveName, k -> new HashMap<>())
                .put(scoreName, offset);
    }

    public String getTransformedDisplayName(String objectiveName) {
        return objectiveDisplayNameMappings.get(objectiveName);
    }

    public int getTransformedScoreValue(String objectiveName, String scoreName, int originalValue) {
        Map<String, Integer> offsets = scoreValueOffsets.get(objectiveName);
        if (offsets != null) {
            Integer offset = offsets.get(scoreName);
            if (offset != null) {
                return originalValue + offset;
            }
        }
        return originalValue;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void clear() {
        objectiveDisplayNameMappings.clear();
        scoreValueOffsets.clear();
        enabled = false;
    }

    public UUID getPlayerId() {
        return playerId;
    }
    
    public NbtCompound toNbt() {
        NbtCompound nbt = new NbtCompound();
        nbt.putBoolean("enabled", enabled);
        
        // オブジェクティブ表示名マッピングを保存
        NbtCompound displayMappingsNbt = new NbtCompound();
        for (Map.Entry<String, String> entry : objectiveDisplayNameMappings.entrySet()) {
            displayMappingsNbt.putString(entry.getKey(), entry.getValue());
        }
        nbt.put("objectiveDisplayNameMappings", displayMappingsNbt);
        
        // スコア値オフセットを保存
        NbtCompound offsetsNbt = new NbtCompound();
        for (Map.Entry<String, Map<String, Integer>> objEntry : scoreValueOffsets.entrySet()) {
            NbtCompound objOffsetsNbt = new NbtCompound();
            for (Map.Entry<String, Integer> scoreEntry : objEntry.getValue().entrySet()) {
                objOffsetsNbt.putInt(scoreEntry.getKey(), scoreEntry.getValue());
            }
            offsetsNbt.put(objEntry.getKey(), objOffsetsNbt);
        }
        nbt.put("scoreValueOffsets", offsetsNbt);
        
        return nbt;
    }
    
    public void fromNbt(NbtCompound nbt) {
        this.enabled = nbt.getBoolean("enabled");
        
        // オブジェクティブ表示名マッピングを読み込み
        objectiveDisplayNameMappings.clear();
        if (nbt.contains("objectiveDisplayNameMappings")) {
            NbtCompound displayMappingsNbt = nbt.getCompound("objectiveDisplayNameMappings");
            for (String key : displayMappingsNbt.getKeys()) {
                objectiveDisplayNameMappings.put(key, displayMappingsNbt.getString(key));
            }
        }
        
        // スコア値オフセットを読み込み
        scoreValueOffsets.clear();
        if (nbt.contains("scoreValueOffsets")) {
            NbtCompound offsetsNbt = nbt.getCompound("scoreValueOffsets");
            for (String objName : offsetsNbt.getKeys()) {
                NbtCompound objOffsetsNbt = offsetsNbt.getCompound(objName);
                Map<String, Integer> scoreOffsets = new HashMap<>();
                for (String scoreName : objOffsetsNbt.getKeys()) {
                    scoreOffsets.put(scoreName, objOffsetsNbt.getInt(scoreName));
                }
                scoreValueOffsets.put(objName, scoreOffsets);
            }
        }
    }
}