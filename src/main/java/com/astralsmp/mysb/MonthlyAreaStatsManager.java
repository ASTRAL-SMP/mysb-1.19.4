package com.astralsmp.mysb;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.ServerScoreboard;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.scores.Objective;
import net.minecraft.world.scores.PlayerScoreEntry;
import net.minecraft.world.scores.ScoreAccess;
import net.minecraft.world.scores.ScoreHolder;
import net.minecraft.world.scores.Scoreboard;
import net.minecraft.world.scores.criteria.ObjectiveCriteria;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

public class MonthlyAreaStatsManager {
    private static final String MINED_OBJECTIVE_PREFIX = "mam_";
    private static final String PLACED_OBJECTIVE_PREFIX = "map_";
    private static final String SERVER_TOTAL_NAME = "  §6§l$SERVER_TOTAL";
    private static final Pattern AREA_ID_PATTERN = Pattern.compile("[A-Za-z0-9_-]{1,32}");

    private static MinecraftServer server;
    private static final Map<String, AreaConfig> areas = new ConcurrentHashMap<>();
    private static final Map<String, Map<String, Map<String, PlayerMonthStats>>> monthlyStats = new ConcurrentHashMap<>();
    private static final Map<UUID, AreaSelection> selections = new ConcurrentHashMap<>();
    private static String displayedMonth = currentMonth();
    private static volatile boolean dirty = false;

    public static void init(MinecraftServer minecraftServer) {
        server = minecraftServer;
        load();
        createObjectivesForAllAreas();
        updateAllScoreboards();
        ServerScoreboardLogger.info("MonthlyAreaStatsManager initialized");
    }

    public static void reload() {
        areas.clear();
        monthlyStats.clear();
        selections.clear();
        dirty = false;
        load();
        createObjectivesForAllAreas();
        updateAllScoreboards();
    }

    public static void save() {
        if (server == null) {
            return;
        }

        try {
            Path configDir = getConfigDirectory();
            Files.createDirectories(configDir);
            CompoundTag root = new CompoundTag();
            root.putString("displayedMonth", displayedMonth);

            ListTag areaList = new ListTag();
            for (AreaConfig area : areas.values()) {
                areaList.add(area.toNbt());
            }
            root.put("areas", areaList);

            ListTag statList = new ListTag();
            for (Map.Entry<String, Map<String, Map<String, PlayerMonthStats>>> areaEntry : monthlyStats.entrySet()) {
                String areaId = areaEntry.getKey();
                for (Map.Entry<String, Map<String, PlayerMonthStats>> monthEntry : areaEntry.getValue().entrySet()) {
                    String month = monthEntry.getKey();
                    for (Map.Entry<String, PlayerMonthStats> playerEntry : monthEntry.getValue().entrySet()) {
                        CompoundTag statNbt = new CompoundTag();
                        statNbt.putString("areaId", areaId);
                        statNbt.putString("month", month);
                        statNbt.putString("player", playerEntry.getKey());
                        statNbt.putInt("mined", playerEntry.getValue().mined);
                        statNbt.putInt("placed", playerEntry.getValue().placed);
                        statList.add(statNbt);
                    }
                }
            }
            root.put("stats", statList);

            NbtIo.writeCompressed(root, getDataFile().toPath());
            dirty = false;
        } catch (IOException e) {
            ServerScoreboardLogger.error("Failed to save monthly area stats", e);
        }
    }

    public static void saveIfDirty() {
        if (dirty) {
            save();
        }
    }

    public static AreaChunkPos setSelectionPos(ServerPlayer player, String areaId, boolean firstPos) {
        validateAreaId(areaId);
        AreaChunkPos pos = AreaChunkPos.fromBlockPos(player.blockPosition());
        String dimension = player.level().dimension().identifier().toString();
        AreaSelection selection = selections.computeIfAbsent(player.getUUID(), ignored -> new AreaSelection());
        if (firstPos) {
            selection.areaId = areaId;
            selection.pos1 = pos;
            selection.dimension1 = dimension;
        } else {
            selection.areaId = areaId;
            selection.pos2 = pos;
            selection.dimension2 = dimension;
        }
        return pos;
    }

    public static AreaConfig createAreaFromSelection(ServerPlayer player, String areaId, String displayName) {
        validateAreaId(areaId);
        AreaSelection selection = selections.get(player.getUUID());
        if (selection == null || selection.pos1 == null || selection.pos2 == null || !areaId.equals(selection.areaId)) {
            throw new IllegalArgumentException("先に /mysb area pos1 " + areaId + " と /mysb area pos2 " + areaId + " を実行してください");
        }
        if (!selection.dimension1.equals(selection.dimension2)) {
            throw new IllegalArgumentException("pos1 と pos2 は同じディメンションで指定してください");
        }
        return createArea(areaId, displayName, selection.dimension1, selection.pos1, selection.pos2);
    }

    public static AreaConfig createArea(String areaId, String displayName, String dimension, AreaChunkPos pos1, AreaChunkPos pos2) {
        validateAreaId(areaId);
        if (displayName == null || displayName.trim().isEmpty()) {
            throw new IllegalArgumentException("表示名は空にできません");
        }
        if (areas.containsKey(areaId)) {
            throw new IllegalArgumentException("既に存在する範囲 ID です: " + areaId);
        }

        AreaConfig area = AreaConfig.create(areaId, displayName, dimension, pos1, pos2);
        validateObjectiveNamesAvailable(area);
        areas.put(areaId, area);
        monthlyStats.computeIfAbsent(areaId, ignored -> new ConcurrentHashMap<>());
        createObjectives(area);
        updateAreaScoreboards(area);
        save();
        return area;
    }

    public static boolean removeArea(String areaId) {
        AreaConfig removed = areas.remove(areaId);
        if (removed == null) {
            return false;
        }
        monthlyStats.remove(areaId);

        Scoreboard scoreboard = server.getScoreboard();
        removeObjectiveIfPresent(scoreboard, removed.getMinedObjectiveName());
        removeObjectiveIfPresent(scoreboard, removed.getPlacedObjectiveName());
        save();
        return true;
    }

    public static void recordBlockMined(ServerPlayer player, BlockPos blockPos) {
        record(player, blockPos, true);
    }

    public static void recordBlockPlaced(ServerPlayer player, BlockPos blockPos) {
        record(player, blockPos, false);
    }

    public static List<AreaConfig> getAreas() {
        List<AreaConfig> result = new ArrayList<>(areas.values());
        result.sort(Comparator.comparing(area -> area.id));
        return result;
    }

    public static String getDisplayedMonth() {
        return displayedMonth;
    }

    public static void setDisplayedMonth(String month) {
        displayedMonth = normalizeMonth(month);
        updateAllScoreboards();
        save();
    }

    public static void setDisplayedMonthToCurrent() {
        setDisplayedMonth(currentMonth());
    }

    public static Set<String> getKnownMonths() {
        Set<String> months = new HashSet<>();
        months.add(currentMonth());
        months.add(displayedMonth);
        for (Map<String, Map<String, PlayerMonthStats>> areaStats : monthlyStats.values()) {
            months.addAll(areaStats.keySet());
        }
        return months;
    }

    public static void updateAllScoreboards() {
        if (server == null) {
            return;
        }
        createObjectivesForAllAreas();
        for (AreaConfig area : areas.values()) {
            updateAreaScoreboards(area);
        }
    }

    private static void record(ServerPlayer player, BlockPos blockPos, boolean mined) {
        if (server == null || areas.isEmpty()) {
            return;
        }

        String playerName = player.getName().getString();
        if (TotalStatsManager.isPlayerExcluded(playerName)) {
            return;
        }
        if (!ServerScoreboardConfig.FAKE_PLAYER_SCORE_ENABLED && TotalStatsManager.isFakePlayer(playerName)) {
            return;
        }

        String dimension = player.level().dimension().identifier().toString();
        AreaChunkPos chunk = AreaChunkPos.fromBlockPos(blockPos);
        String month = currentMonth();
        boolean changed = false;

        for (AreaConfig area : areas.values()) {
            if (!area.contains(dimension, chunk)) {
                continue;
            }
            PlayerMonthStats stats = monthlyStats
                    .computeIfAbsent(area.id, ignored -> new ConcurrentHashMap<>())
                    .computeIfAbsent(month, ignored -> new ConcurrentHashMap<>())
                    .computeIfAbsent(playerName, ignored -> new PlayerMonthStats());
            if (mined) {
                stats.mined++;
            } else {
                stats.placed++;
            }
            updateAreaScoreboards(area);
            changed = true;
        }

        if (changed) {
            dirty = true;
        }
    }

    private static void load() {
        if (server == null) {
            return;
        }

        File dataFile = getDataFile();
        if (!dataFile.exists()) {
            displayedMonth = currentMonth();
            return;
        }

        try {
            CompoundTag root = NbtIo.readCompressed(dataFile.toPath(), NbtAccounter.unlimitedHeap());
            displayedMonth = root.contains("displayedMonth") ? normalizeMonth(root.getStringOr("displayedMonth", "")) : currentMonth();

            if (root.contains("areas")) {
                ListTag areaList = root.getListOrEmpty("areas");
                for (int i = 0; i < areaList.size(); i++) {
                    AreaConfig area = AreaConfig.fromNbt(areaList.getCompoundOrEmpty(i));
                    areas.put(area.id, area);
                }
            }

            if (root.contains("stats")) {
                ListTag statList = root.getListOrEmpty("stats");
                for (int i = 0; i < statList.size(); i++) {
                    CompoundTag statNbt = statList.getCompoundOrEmpty(i);
                    String areaId = statNbt.getStringOr("areaId", "");
                    String month = normalizeMonth(statNbt.getStringOr("month", ""));
                    String playerName = statNbt.getStringOr("player", "");
                    PlayerMonthStats stats = new PlayerMonthStats();
                    stats.mined = statNbt.getIntOr("mined", 0);
                    stats.placed = statNbt.getIntOr("placed", 0);
                    monthlyStats
                            .computeIfAbsent(areaId, ignored -> new ConcurrentHashMap<>())
                            .computeIfAbsent(month, ignored -> new ConcurrentHashMap<>())
                            .put(playerName, stats);
                }
            }
        } catch (Exception e) {
            displayedMonth = currentMonth();
            ServerScoreboardLogger.error("Failed to load monthly area stats", e);
        }
    }

    private static void createObjectivesForAllAreas() {
        for (AreaConfig area : areas.values()) {
            createObjectives(area);
        }
    }

    private static void createObjectives(AreaConfig area) {
        Scoreboard scoreboard = server.getScoreboard();
        ensureObjective(scoreboard, area.getMinedObjectiveName(), area.getMinedDisplayName(displayedMonth));
        ensureObjective(scoreboard, area.getPlacedObjectiveName(), area.getPlacedDisplayName(displayedMonth));
    }

    private static void ensureObjective(Scoreboard scoreboard, String objectiveName, String displayName) {
        Objective objective = scoreboard.getObjective(objectiveName);
        if (objective == null) {
            scoreboard.addObjective(
                    objectiveName,
                    ObjectiveCriteria.DUMMY,
                    Component.literal(displayName),
                    ObjectiveCriteria.RenderType.INTEGER,
                    false,
                    null
            );
        } else {
            setObjectiveDisplayName(scoreboard, objective, displayName);
        }
    }

    private static void updateAreaScoreboards(AreaConfig area) {
        Scoreboard scoreboard = server.getScoreboard();
        Objective minedObjective = scoreboard.getObjective(area.getMinedObjectiveName());
        Objective placedObjective = scoreboard.getObjective(area.getPlacedObjectiveName());
        if (minedObjective == null || placedObjective == null) {
            createObjectives(area);
            minedObjective = scoreboard.getObjective(area.getMinedObjectiveName());
            placedObjective = scoreboard.getObjective(area.getPlacedObjectiveName());
        }
        if (minedObjective != null) {
            setObjectiveDisplayName(scoreboard, minedObjective, area.getMinedDisplayName(displayedMonth));
            updateObjectiveScores(scoreboard, minedObjective, getStatsFor(area.id, displayedMonth), true);
        }
        if (placedObjective != null) {
            setObjectiveDisplayName(scoreboard, placedObjective, area.getPlacedDisplayName(displayedMonth));
            updateObjectiveScores(scoreboard, placedObjective, getStatsFor(area.id, displayedMonth), false);
        }
    }

    private static void setObjectiveDisplayName(Scoreboard scoreboard, Objective objective, String displayName) {
        Component text = Component.literal(displayName);
        if (!objective.getDisplayName().equals(text)) {
            objective.setDisplayName(text);
            if (scoreboard instanceof ServerScoreboard serverScoreboard) {
                serverScoreboard.onObjectiveChanged(objective);
            }
        }
    }

    private static Map<String, PlayerMonthStats> getStatsFor(String areaId, String month) {
        return monthlyStats
                .getOrDefault(areaId, Map.of())
                .getOrDefault(month, Map.of());
    }

    private static void updateObjectiveScores(Scoreboard scoreboard, Objective objective, Map<String, PlayerMonthStats> stats, boolean mined) {
        Collection<PlayerScoreEntry> oldScores = new ArrayList<>(scoreboard.listPlayerScores(objective));
        for (PlayerScoreEntry oldScore : oldScores) {
            scoreboard.resetSinglePlayerScore(ScoreHolder.forNameOnly(oldScore.owner()), objective);
        }

        boolean filterFakePlayers = !ServerScoreboardConfig.FAKE_PLAYER_SCORE_ENABLED;
        int total = 0;
        for (Map.Entry<String, PlayerMonthStats> entry : stats.entrySet()) {
            // Fake Playerが無効化されている場合は、過去に記録された bot のデータも合計から除外
            if (filterFakePlayers && FakePlayerDetector.isFakePlayerName(entry.getKey())) {
                continue;
            }
            total += mined ? entry.getValue().mined : entry.getValue().placed;
        }

        ScoreAccess totalScore = scoreboard.getOrCreatePlayerScore(ScoreHolder.forNameOnly(SERVER_TOTAL_NAME), objective);
        totalScore.set(total);

        for (Map.Entry<String, PlayerMonthStats> entry : stats.entrySet()) {
            String playerName = entry.getKey();
            if (filterFakePlayers && FakePlayerDetector.isFakePlayerName(playerName)) {
                continue;
            }
            int value = mined ? entry.getValue().mined : entry.getValue().placed;
            if (value > 0) {
                scoreboard.getOrCreatePlayerScore(ScoreHolder.forNameOnly(playerName), objective).set(value);
            }
        }
    }

    private static void removeObjectiveIfPresent(Scoreboard scoreboard, String objectiveName) {
        Objective objective = scoreboard.getObjective(objectiveName);
        if (objective != null) {
            scoreboard.removeObjective(objective);
        }
    }

    private static void validateObjectiveNamesAvailable(AreaConfig area) {
        String minedObjectiveName = area.getMinedObjectiveName();
        String placedObjectiveName = area.getPlacedObjectiveName();
        for (AreaConfig existingArea : areas.values()) {
            if (existingArea.getMinedObjectiveName().equals(minedObjectiveName)
                    || existingArea.getPlacedObjectiveName().equals(placedObjectiveName)) {
                throw new IllegalArgumentException("objective 名が既存の範囲と衝突しています。別の ID を指定してください");
            }
        }

        Scoreboard scoreboard = server.getScoreboard();
        if (scoreboard.getObjective(minedObjectiveName) != null || scoreboard.getObjective(placedObjectiveName) != null) {
            throw new IllegalArgumentException("同名の scoreboard objective が既に存在します。別の ID を指定してください");
        }
    }

    private static Path getConfigDirectory() {
        return server.getWorldPath(LevelResource.ROOT).resolve("config/mysb");
    }

    private static File getDataFile() {
        return getConfigDirectory().resolve("monthly_area_stats.dat").toFile();
    }

    private static String currentMonth() {
        return YearMonth.now(ZoneId.systemDefault()).toString();
    }

    private static String normalizeMonth(String month) {
        return YearMonth.parse(month).toString();
    }

    private static void validateAreaId(String areaId) {
        if (areaId == null || !AREA_ID_PATTERN.matcher(areaId).matches()) {
            throw new IllegalArgumentException("範囲 ID は英数字、_、- の 1-32 文字で指定してください");
        }
    }

    public static class AreaConfig {
        public final String id;
        public final String displayName;
        public final String dimension;
        public final int minChunkX;
        public final int minChunkZ;
        public final int maxChunkX;
        public final int maxChunkZ;

        private AreaConfig(String id, String displayName, String dimension, int minChunkX, int minChunkZ,
                           int maxChunkX, int maxChunkZ) {
            this.id = id;
            this.displayName = displayName;
            this.dimension = dimension;
            this.minChunkX = minChunkX;
            this.minChunkZ = minChunkZ;
            this.maxChunkX = maxChunkX;
            this.maxChunkZ = maxChunkZ;
        }

        public static AreaConfig create(String id, String displayName, String dimension, AreaChunkPos pos1, AreaChunkPos pos2) {
            return new AreaConfig(
                    id,
                    displayName,
                    dimension,
                    Math.min(pos1.x, pos2.x),
                    Math.min(pos1.z, pos2.z),
                    Math.max(pos1.x, pos2.x),
                    Math.max(pos1.z, pos2.z)
            );
        }

        public boolean contains(String dimension, AreaChunkPos pos) {
            return this.dimension.equals(dimension)
                    && pos.x >= minChunkX && pos.x <= maxChunkX
                    && pos.z >= minChunkZ && pos.z <= maxChunkZ;
        }

        public String getMinedObjectiveName() {
            return MINED_OBJECTIVE_PREFIX + objectiveSuffix(id);
        }

        public String getPlacedObjectiveName() {
            return PLACED_OBJECTIVE_PREFIX + objectiveSuffix(id);
        }

        public String getMinedDisplayName(String month) {
            return month + " " + displayName + " Mined";
        }

        public String getPlacedDisplayName(String month) {
            return month + " " + displayName + " Placed";
        }

        public CompoundTag toNbt() {
            CompoundTag nbt = new CompoundTag();
            nbt.putString("id", id);
            nbt.putString("displayName", displayName);
            nbt.putString("dimension", dimension);
            nbt.putInt("minChunkX", minChunkX);
            nbt.putInt("minChunkZ", minChunkZ);
            nbt.putInt("maxChunkX", maxChunkX);
            nbt.putInt("maxChunkZ", maxChunkZ);
            return nbt;
        }

        public static AreaConfig fromNbt(CompoundTag nbt) {
            int minX = nbt.contains("minChunkX") ? nbt.getIntOr("minChunkX", 0) : nbt.getIntOr("minSectionX", 0);
            int minZ = nbt.contains("minChunkZ") ? nbt.getIntOr("minChunkZ", 0) : nbt.getIntOr("minSectionZ", 0);
            int maxX = nbt.contains("maxChunkX") ? nbt.getIntOr("maxChunkX", 0) : nbt.getIntOr("maxSectionX", 0);
            int maxZ = nbt.contains("maxChunkZ") ? nbt.getIntOr("maxChunkZ", 0) : nbt.getIntOr("maxSectionZ", 0);
            return new AreaConfig(
                    nbt.getStringOr("id", ""),
                    nbt.getStringOr("displayName", ""),
                    nbt.getStringOr("dimension", ""),
                    minX,
                    minZ,
                    maxX,
                    maxZ
            );
        }

        private static String objectiveSuffix(String id) {
            String sanitized = id.toLowerCase().replaceAll("[^a-z0-9_]", "_");
            if (sanitized.isEmpty()) {
                sanitized = "area";
            }
            if (sanitized.length() > 8) {
                sanitized = sanitized.substring(0, 8);
            }
            String hash = Integer.toHexString(id.hashCode());
            if (hash.length() < 4) {
                hash = ("0000" + hash);
            }
            return sanitized + hash.substring(0, 4);
        }
    }

    public static class AreaChunkPos {
        public final int x;
        public final int z;

        public AreaChunkPos(int x, int z) {
            this.x = x;
            this.z = z;
        }

        public static AreaChunkPos fromBlockPos(BlockPos pos) {
            return new AreaChunkPos(
                    Math.floorDiv(pos.getX(), 16),
                    Math.floorDiv(pos.getZ(), 16)
            );
        }

        public String format() {
            return x + " " + z;
        }
    }

    private static class AreaSelection {
        String areaId;
        AreaChunkPos pos1;
        AreaChunkPos pos2;
        String dimension1;
        String dimension2;
    }

    private static class PlayerMonthStats {
        int mined;
        int placed;
    }
}
