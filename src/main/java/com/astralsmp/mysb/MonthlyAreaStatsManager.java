package com.astralsmp.mysb;

import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtList;
import net.minecraft.scoreboard.Scoreboard;
import net.minecraft.scoreboard.ScoreboardCriterion;
import net.minecraft.scoreboard.ScoreboardObjective;
import net.minecraft.scoreboard.ScoreboardPlayerScore;
import net.minecraft.scoreboard.ServerScoreboard;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.WorldSavePath;
import net.minecraft.util.math.BlockPos;

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
            NbtCompound root = new NbtCompound();
            root.putString("displayedMonth", displayedMonth);

            NbtList areaList = new NbtList();
            for (AreaConfig area : areas.values()) {
                areaList.add(area.toNbt());
            }
            root.put("areas", areaList);

            NbtList statList = new NbtList();
            for (Map.Entry<String, Map<String, Map<String, PlayerMonthStats>>> areaEntry : monthlyStats.entrySet()) {
                String areaId = areaEntry.getKey();
                for (Map.Entry<String, Map<String, PlayerMonthStats>> monthEntry : areaEntry.getValue().entrySet()) {
                    String month = monthEntry.getKey();
                    for (Map.Entry<String, PlayerMonthStats> playerEntry : monthEntry.getValue().entrySet()) {
                        NbtCompound statNbt = new NbtCompound();
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

            NbtIo.writeCompressed(root, getDataFile());
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

    public static AreaChunkPos setSelectionPos(ServerPlayerEntity player, String areaId, boolean firstPos) {
        validateAreaId(areaId);
        AreaChunkPos pos = AreaChunkPos.fromBlockPos(player.getBlockPos());
        String dimension = player.getWorld().getRegistryKey().getValue().toString();
        AreaSelection selection = selections.computeIfAbsent(player.getUuid(), ignored -> new AreaSelection());
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

    public static AreaConfig createAreaFromSelection(ServerPlayerEntity player, String areaId, String displayName) {
        validateAreaId(areaId);
        AreaSelection selection = selections.get(player.getUuid());
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

    public static void recordBlockMined(ServerPlayerEntity player, BlockPos blockPos) {
        record(player, blockPos, true);
    }

    public static void recordBlockPlaced(ServerPlayerEntity player, BlockPos blockPos) {
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

    private static void record(ServerPlayerEntity player, BlockPos blockPos, boolean mined) {
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

        String dimension = player.getWorld().getRegistryKey().getValue().toString();
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
            NbtCompound root = NbtIo.readCompressed(dataFile);
            displayedMonth = root.contains("displayedMonth") ? normalizeMonth(root.getString("displayedMonth")) : currentMonth();

            if (root.contains("areas")) {
                NbtList areaList = root.getList("areas", 10);
                for (int i = 0; i < areaList.size(); i++) {
                    AreaConfig area = AreaConfig.fromNbt(areaList.getCompound(i));
                    areas.put(area.id, area);
                }
            }

            if (root.contains("stats")) {
                NbtList statList = root.getList("stats", 10);
                for (int i = 0; i < statList.size(); i++) {
                    NbtCompound statNbt = statList.getCompound(i);
                    String areaId = statNbt.getString("areaId");
                    String month = normalizeMonth(statNbt.getString("month"));
                    String playerName = statNbt.getString("player");
                    PlayerMonthStats stats = new PlayerMonthStats();
                    stats.mined = statNbt.getInt("mined");
                    stats.placed = statNbt.getInt("placed");
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
        ScoreboardObjective objective = scoreboard.getObjective(objectiveName);
        if (objective == null) {
            scoreboard.addObjective(
                    objectiveName,
                    ScoreboardCriterion.DUMMY,
                    Text.literal(displayName),
                    ScoreboardCriterion.RenderType.INTEGER
            );
        } else {
            setObjectiveDisplayName(scoreboard, objective, displayName);
        }
    }

    private static void updateAreaScoreboards(AreaConfig area) {
        Scoreboard scoreboard = server.getScoreboard();
        ScoreboardObjective minedObjective = scoreboard.getObjective(area.getMinedObjectiveName());
        ScoreboardObjective placedObjective = scoreboard.getObjective(area.getPlacedObjectiveName());
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

    private static void setObjectiveDisplayName(Scoreboard scoreboard, ScoreboardObjective objective, String displayName) {
        Text text = Text.literal(displayName);
        if (!objective.getDisplayName().equals(text)) {
            objective.setDisplayName(text);
            if (scoreboard instanceof ServerScoreboard serverScoreboard) {
                serverScoreboard.updateExistingObjective(objective);
            }
        }
    }

    private static Map<String, PlayerMonthStats> getStatsFor(String areaId, String month) {
        return monthlyStats
                .getOrDefault(areaId, Map.of())
                .getOrDefault(month, Map.of());
    }

    private static void updateObjectiveScores(Scoreboard scoreboard, ScoreboardObjective objective, Map<String, PlayerMonthStats> stats, boolean mined) {
        Collection<ScoreboardPlayerScore> oldScores = new ArrayList<>(scoreboard.getAllPlayerScores(objective));
        for (ScoreboardPlayerScore oldScore : oldScores) {
            scoreboard.resetPlayerScore(oldScore.getPlayerName(), objective);
        }

        int total = 0;
        for (PlayerMonthStats playerStats : stats.values()) {
            total += mined ? playerStats.mined : playerStats.placed;
        }

        ScoreboardPlayerScore totalScore = scoreboard.getPlayerScore(SERVER_TOTAL_NAME, objective);
        totalScore.setScore(total);

        for (Map.Entry<String, PlayerMonthStats> entry : stats.entrySet()) {
            int value = mined ? entry.getValue().mined : entry.getValue().placed;
            if (value > 0) {
                scoreboard.getPlayerScore(entry.getKey(), objective).setScore(value);
            }
        }
    }

    private static void removeObjectiveIfPresent(Scoreboard scoreboard, String objectiveName) {
        ScoreboardObjective objective = scoreboard.getObjective(objectiveName);
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
        return server.getSavePath(WorldSavePath.ROOT).resolve("config/mysb");
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

        public NbtCompound toNbt() {
            NbtCompound nbt = new NbtCompound();
            nbt.putString("id", id);
            nbt.putString("displayName", displayName);
            nbt.putString("dimension", dimension);
            nbt.putInt("minChunkX", minChunkX);
            nbt.putInt("minChunkZ", minChunkZ);
            nbt.putInt("maxChunkX", maxChunkX);
            nbt.putInt("maxChunkZ", maxChunkZ);
            return nbt;
        }

        public static AreaConfig fromNbt(NbtCompound nbt) {
            int minX = nbt.contains("minChunkX") ? nbt.getInt("minChunkX") : nbt.getInt("minSectionX");
            int minZ = nbt.contains("minChunkZ") ? nbt.getInt("minChunkZ") : nbt.getInt("minSectionZ");
            int maxX = nbt.contains("maxChunkX") ? nbt.getInt("maxChunkX") : nbt.getInt("maxSectionX");
            int maxZ = nbt.contains("maxChunkZ") ? nbt.getInt("maxChunkZ") : nbt.getInt("maxSectionZ");
            return new AreaConfig(
                    nbt.getString("id"),
                    nbt.getString("displayName"),
                    nbt.getString("dimension"),
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
