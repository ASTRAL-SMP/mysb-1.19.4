package com.astralsmp.mysb;

import net.minecraft.scoreboard.Scoreboard;
import net.minecraft.scoreboard.ScoreboardCriterion;
import net.minecraft.scoreboard.ScoreboardObjective;
import net.minecraft.scoreboard.ScoreboardPlayerScore;
import java.util.Collection;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.stat.Stat;
import net.minecraft.stat.Stats;
import net.minecraft.text.Text;
import net.minecraft.registry.Registries;
import net.minecraft.block.Block;
import net.minecraft.block.Blocks;
import net.minecraft.item.BlockItem;
import net.minecraft.item.Item;
import net.minecraft.item.Items;
import net.minecraft.util.Identifier;

import java.util.*;
import java.util.stream.Collectors;
import java.util.concurrent.ConcurrentHashMap;

public class TotalStatsManager {
    private static final String TOTAL_PREFIX = "total_";
    private static final String TOTAL_DISPLAY_PREFIX = "Total ";

    private static MinecraftServer server;
    private static final Map<String, TotalStatConfig> totalStats = new ConcurrentHashMap<>();
    private static final Map<String, Integer> cachedTotals = new ConcurrentHashMap<>();
    private static final Set<String> enabledStats = ConcurrentHashMap.newKeySet();
    private static int updateCounter = 0;
    private static final Map<String, Map<String, Integer>> lastPlayerStats = new ConcurrentHashMap<>();
    private static final Set<String> excludedPlayers = ConcurrentHashMap.newKeySet();

    // 遅延更新システム
    private static final Queue<Integer> pendingUpdates = new LinkedList<>();
    private static int currentTick = 0;

    // レジストリキャッシュ（起動時に一度だけ初期化）
    private static Set<Block> allBlocksCache = null;
    private static Set<Item> blockItemsCache = null;

    // 正規表現のプリコンパイル（パフォーマンス最適化）
    // FakePlayerDetector に委譲するため、ここでは保持しない
    
    // Common statistics - 指定された統計のみ
    public static final Map<String, String> COMMON_STATS = new HashMap<>();
    static {
        COMMON_STATS.put("mined", "Blocks Mined");
        COMMON_STATS.put("placed", "Blocks Placed");
        COMMON_STATS.put("total_all_ores_mined", "Total All Ores Mined");
        COMMON_STATS.put("coral_block_mined", "Coral Blocks Mined");
        COMMON_STATS.put("glass_placed", "Stained Glass Placed");
        COMMON_STATS.put("carved_pumpkin_placed", "Carved Pumpkins Placed");
        COMMON_STATS.put("placed_anvil", "Anvils Placed");
        COMMON_STATS.put("traded_with_villager", "Villager Trades");
        COMMON_STATS.put("deepslate_mined", "Deepslate Mined");
    }
    
    public static void init(MinecraftServer minecraftServer) {
        server = minecraftServer;
        ServerScoreboardLogger.info("TotalStatsManager initialized");

        // レジストリキャッシュを初期化（起動時に一度だけ）
        initializeRegistryCaches();

        // デフォルトの統計を作成
        createDefaultTotalStats();
    }

    private static void initializeRegistryCaches() {
        if (allBlocksCache == null) {
            allBlocksCache = new HashSet<>();
            Registries.BLOCK.forEach(allBlocksCache::add);
            ServerScoreboardLogger.info("Cached " + allBlocksCache.size() + " blocks for statistics");
        }
        if (blockItemsCache == null) {
            blockItemsCache = new HashSet<>();
            Registries.ITEM.forEach(item -> {
                if (item instanceof BlockItem) {
                    blockItemsCache.add(item);
                }
            });
            ServerScoreboardLogger.info("Cached " + blockItemsCache.size() + " block items for statistics");
        }
    }
    
    private static void createDefaultTotalStats() {
        // 指定された統計のみを登録
        registerTotalStat("mined", "Total Blocks Mined", "mined");
        registerTotalStat("placed", "Total Blocks Placed", "placed");
        registerTotalStat("total_all_ores_mined", "Total All Ores Mined", "total_all_ores_mined");
        registerTotalStat("coral_block_mined", "Total Coral Blocks Mined", "coral_block_mined");
        registerTotalStat("glass_placed", "Total Stained Glass Placed", "glass_placed");
        registerTotalStat("carved_pumpkin_placed", "Total Carved Pumpkins Placed", "carved_pumpkin_placed");
        registerTotalStat("placed_anvil", "Total Anvils Placed", "placed_anvil");
        registerTotalStat("traded_with_villager", "Total Villager Trades", "traded_with_villager");
        registerTotalStat("deepslate_mined", "Total Deepslate Mined", "deepslate_mined");
        
        // 登録した統計をすべて有効化
        enabledStats.add("mined");
        enabledStats.add("placed");
        enabledStats.add("total_all_ores_mined");
        enabledStats.add("coral_block_mined");
        enabledStats.add("glass_placed");
        enabledStats.add("carved_pumpkin_placed");
        enabledStats.add("placed_anvil");
        enabledStats.add("traded_with_villager");
        enabledStats.add("deepslate_mined");
    }
    
    public static void registerTotalStat(String id, String displayName, String statType) {
        TotalStatConfig config = new TotalStatConfig(id, displayName, statType);
        totalStats.put(id, config);
        
        // スコアボードオブジェクティブを作成
        createTotalObjective(config);
        
        ServerScoreboardLogger.info("Registered total stat: " + id + " (" + displayName + ")");
    }
    
    private static void createTotalObjective(TotalStatConfig config) {
        Scoreboard scoreboard = server.getScoreboard();
        String objectiveName = TOTAL_PREFIX + config.id;
        
        // 既存のオブジェクティブをチェック
        if (scoreboard.getObjective(objectiveName) != null) {
            return;
        }
        
        // 新しいオブジェクティブを作成
        ScoreboardObjective objective = scoreboard.addObjective(
            objectiveName,
            ScoreboardCriterion.DUMMY,
            Text.literal(config.displayName),
            ScoreboardCriterion.RenderType.INTEGER
        );
        
        ServerScoreboardLogger.info("Created total objective: " + objectiveName);
    }
    
    public static void updateAllTotalStats() {
        if (server == null) return;
        
        // 現在のティックを更新
        currentTick++;
        
        // 遅延更新の処理
        while (!pendingUpdates.isEmpty() && pendingUpdates.peek() <= currentTick) {
            pendingUpdates.poll(); // 期限が来た更新を削除
            performActualUpdate();
            break; // 1回の呼び出しで1回だけ更新
        }
        
        // 通常の定期更新（変更がある場合のみ）
        for (String statId : enabledStats) {
            TotalStatConfig config = totalStats.get(statId);
            if (config != null) {
                updateTotalStat(config);
            }
        }
    }
    
    // 遅延更新をスケジュール
    public static void scheduleDelayedUpdate(int delayTicks) {
        if (server == null) return;
        pendingUpdates.offer(currentTick + delayTicks);
    }
    
    // 即座更新をスケジュール（次のtickで実行）
    public static void scheduleInstantUpdate() {
        if (server == null) return;
        pendingUpdates.offer(currentTick + 1);
    }
    
    // 実際の強制更新処理
    private static void performActualUpdate() {
        // キャッシュをクリアして強制更新
        lastPlayerStats.clear();
        cachedTotals.clear();
        
        for (String statId : enabledStats) {
            TotalStatConfig config = totalStats.get(statId);
            if (config != null) {
                updateTotalStat(config);
            }
        }
    }
    
    public static void forceUpdateAllStats() {
        if (server == null) return;
        
        // キャッシュをクリアして強制更新
        lastPlayerStats.clear();
        cachedTotals.clear();
        
        for (String statId : enabledStats) {
            TotalStatConfig config = totalStats.get(statId);
            if (config != null) {
                updateTotalStat(config);
            }
        }
    }
    
    private static void updateTotalStat(TotalStatConfig config) {
        Scoreboard scoreboard = server.getScoreboard();
        String objectiveName = TOTAL_PREFIX + config.id;
        ScoreboardObjective objective = scoreboard.getObjective(objectiveName);
        
        if (objective == null) {
            createTotalObjective(config);
            objective = scoreboard.getObjective(objectiveName);
            if (objective == null) return;
        }
        
        // Get current stats
        Map<String, Integer> playerStats = new HashMap<>();
        int total = 0;
        boolean hasChanged = false;
        
        // オンラインプレイヤーの統計を更新・取得
        for (ServerPlayerEntity player : server.getPlayerManager().getPlayerList()) {
            String playerName = player.getName().getString();
            // 除外リストに含まれているプレイヤーはスキップ
            if (excludedPlayers.contains(playerName)) {
                continue;
            }
            // Fake Playerのスコア表示が無効化されている場合はスキップ（エンティティクラスベース判定）
            if (!ServerScoreboardConfig.FAKE_PLAYER_SCORE_ENABLED && FakePlayerDetector.isFakePlayer(player)) {
                continue;
            }

            int playerTotal = getPlayerStatTotal(player, config.statType);
            // 0でもキャッシュに保存（統計がリセットされた場合のため）
            playerStats.put(playerName, playerTotal);
            total += playerTotal;
            // キャッシュに保存
            PlayerStatsCache.updatePlayerStats(playerName, config.statType, playerTotal);
        }
        
        // オフラインプレイヤーのキャッシュデータも含める
        Map<String, Integer> cachedStats = PlayerStatsCache.getAllPlayerStats(config.statType);
        for (Map.Entry<String, Integer> entry : cachedStats.entrySet()) {
            String playerName = entry.getKey();
            // 除外リストに含まれているプレイヤーはスキップ
            if (excludedPlayers.contains(playerName)) {
                continue;
            }
            // Fake Playerのスコア表示が無効化されている場合はスキップ
            if (!ServerScoreboardConfig.FAKE_PLAYER_SCORE_ENABLED && isFakePlayer(playerName)) {
                continue;
            }
            // オンラインプレイヤーのデータは既に含まれているのでスキップ
            if (!playerStats.containsKey(playerName)) {
                int cachedValue = entry.getValue();
                if (cachedValue > 0) {
                    playerStats.put(playerName, cachedValue);
                    total += cachedValue;
                }
            }
        }
        
        // Check if stats have changed
        Map<String, Integer> lastStats = lastPlayerStats.get(config.id);
        if (lastStats == null || !lastStats.equals(playerStats)) {
            hasChanged = true;
            lastPlayerStats.put(config.id, new HashMap<>(playerStats));
        }
        
        // Only update if changed
        if (!hasChanged && cachedTotals.getOrDefault(config.id, -1) == total) {
            return;
        }
        
        // Clear old scores
        Collection<ScoreboardPlayerScore> oldScores = scoreboard.getAllPlayerScores(objective);
        for (ScoreboardPlayerScore oldScore : oldScores) {
            scoreboard.resetPlayerScore(oldScore.getPlayerName(), objective);
        }
        
        {
            // 通常の統計表示
            ScoreboardPlayerScore totalScore = scoreboard.getPlayerScore("  §6§l$SERVER_TOTAL", objective);
            int oldTotal = totalScore.getScore();
            totalScore.setScore(total);
            
            // デバッグログ: 合計スコアの変更
            
            for (Map.Entry<String, Integer> entry : playerStats.entrySet()) {
                if (entry.getValue() > 0) { // 0の値は表示しない
                    ScoreboardPlayerScore score = scoreboard.getPlayerScore(entry.getKey(), objective);
                    int oldValue = score.getScore();
                    score.setScore(entry.getValue());
                    
                }
            }
        }
        
        cachedTotals.put(config.id, total);
        
        // 統計スコアボードを表示しているプレイヤーに更新を送信
        ServerScoreboardManager.updateTotalStatsForWatchers();
    }
    
    
    
    public static int getPlayerStatTotal(ServerPlayerEntity player, String statType) {
        int total = 0;

        // キャッシュが未初期化の場合は初期化
        if (allBlocksCache == null || blockItemsCache == null) {
            initializeRegistryCaches();
        }

        // 統計タイプに基づいて適切な値を取得
        switch (statType.toLowerCase()) {
            case "mined":
                // 全ブロックの採掘数を合計（キャッシュを使用）
                try {
                    for (Block block : allBlocksCache) {
                        Stat<Block> stat = Stats.MINED.getOrCreateStat(block);
                        total += player.getStatHandler().getStat(stat);
                    }
                } catch (Exception e) {
                    // エラーを無視
                }
                break;
            case "placed":
            case "used":
                // 全アイテムの使用数を合計（キャッシュを使用）
                try {
                    for (Item item : blockItemsCache) {
                        Stat<Item> stat = Stats.USED.getOrCreateStat(item);
                        total += player.getStatHandler().getStat(stat);
                    }
                } catch (Exception e) {
                    // エラーを無視
                }
                break;
            case "total_all_ores_mined":
                // All ores mined (including regular, deepslate, nether, and ancient debris)
                String[] allOres = {
                    "coal_ore", "deepslate_coal_ore", "iron_ore", "deepslate_iron_ore",
                    "copper_ore", "deepslate_copper_ore", "gold_ore", "deepslate_gold_ore",
                    "redstone_ore", "deepslate_redstone_ore", "emerald_ore", "deepslate_emerald_ore",
                    "lapis_ore", "deepslate_lapis_ore", "diamond_ore", "deepslate_diamond_ore",
                    "nether_gold_ore", "nether_quartz_ore", "ancient_debris"
                };
                try {
                    for (String oreName : allOres) {
                        Block block = Registries.BLOCK.get(new Identifier("minecraft", oreName));
                        if (block != null && block != Blocks.AIR) {
                            total += player.getStatHandler().getStat(Stats.MINED.getOrCreateStat(block));
                        }
                    }
                } catch (Exception e) {
                    // エラーを無視
                }
                break;
            case "deepslate_mined":
                // Deepslate blocks mined (the stone itself, not the ores)
                try {
                    Block deepslateBlock = Registries.BLOCK.get(new Identifier("minecraft", "deepslate"));
                    if (deepslateBlock != null && deepslateBlock != Blocks.AIR) {
                        total = player.getStatHandler().getStat(Stats.MINED.getOrCreateStat(deepslateBlock));
                    }
                } catch (Exception e) {
                    // エラーを無視
                }
                break;
            case "coral_block_mined":
                // Coral blocks mined
                String[] coralBlocksForMining = {
                    "tube_coral_block", "brain_coral_block", "bubble_coral_block", 
                    "fire_coral_block", "horn_coral_block",
                    "dead_tube_coral_block", "dead_brain_coral_block", "dead_bubble_coral_block", 
                    "dead_fire_coral_block", "dead_horn_coral_block"
                };
                try {
                    for (String blockName : coralBlocksForMining) {
                        Block block = Registries.BLOCK.get(new Identifier("minecraft", blockName));
                        if (block != null && block != Blocks.AIR) {
                            total += player.getStatHandler().getStat(Stats.MINED.getOrCreateStat(block));
                        }
                    }
                } catch (Exception e) {
                    // エラーを無視
                }
                break;
            case "glass_placed":
                // Glass (including all stained glass) placed
                String[] glassBlocks = {
                    "glass", "white_stained_glass", "orange_stained_glass", "magenta_stained_glass", "light_blue_stained_glass",
                    "yellow_stained_glass", "lime_stained_glass", "pink_stained_glass", "gray_stained_glass",
                    "light_gray_stained_glass", "cyan_stained_glass", "purple_stained_glass", "blue_stained_glass",
                    "brown_stained_glass", "green_stained_glass", "red_stained_glass", "black_stained_glass",
                    "tinted_glass"
                };
                try {
                    for (String blockName : glassBlocks) {
                        Item item = Registries.ITEM.get(new Identifier("minecraft", blockName));
                        if (item != null && item != Items.AIR && item instanceof BlockItem) {
                            total += player.getStatHandler().getStat(Stats.USED.getOrCreateStat(item));
                        }
                    }
                } catch (Exception e) {
                    // エラーを無視
                }
                break;
            case "carved_pumpkin_placed":
                // Carved pumpkins placed
                try {
                    var carvedPumpkin = Items.CARVED_PUMPKIN;
                    total = player.getStatHandler().getStat(Stats.USED.getOrCreateStat(carvedPumpkin));
                } catch (Exception e) {
                    // ignore lookup errors
                }
                break;
            case "placed_anvil":
                try {
                    var anvil = Registries.ITEM.get(new net.minecraft.util.Identifier("minecraft", "anvil"));
                    if (anvil != null) {
                        total = player.getStatHandler().getStat(Stats.USED.getOrCreateStat(anvil));
                    }
                } catch (Exception e) {}
                break;
            case "traded_with_villager":
                total = player.getStatHandler().getStat(Stats.CUSTOM.getOrCreateStat(Stats.TRADED_WITH_VILLAGER));
                break;
        }
        
        return total;
    }
    
    
    public static List<String> getAllTotalObjectives() {
        List<String> objectives = new ArrayList<>();
        
        for (String id : totalStats.keySet()) {
            objectives.add(TOTAL_PREFIX + id);
        }
        
        return objectives;
    }
    
    public static List<String> getEnabledTotalObjectives() {
        List<String> objectives = new ArrayList<>();
        
        for (String id : enabledStats) {
            objectives.add(TOTAL_PREFIX + id);
        }
        
        return objectives;
    }
    
    public static boolean isTotalObjective(String objectiveName) {
        return objectiveName != null && objectiveName.startsWith(TOTAL_PREFIX);
    }
    
    public static String getTotalDisplayName(String objectiveName) {
        if (!isTotalObjective(objectiveName)) {
            return objectiveName;
        }
        
        String id = objectiveName.substring(TOTAL_PREFIX.length());
        TotalStatConfig config = totalStats.get(id);
        
        return config != null ? config.displayName : objectiveName;
    }
    
    public static void addCustomTotalStat(String id, String displayName, String statType) {
        // statTypeが有効か確認
        if (isValidStatType(statType)) {
            registerTotalStat(id, displayName, statType);
            updateAllTotalStats();
        }
    }
    
    private static boolean isValidStatType(String statType) {
        // Check if it's a registered total stat type
        for (TotalStatConfig config : totalStats.values()) {
            if (config.statType.equals(statType)) {
                return true;
            }
        }
        
        // Also check common stat types
        switch (statType.toLowerCase()) {
            case "mined":
            case "placed":
            case "used":
            case "total_all_ores_mined":
            case "coral_block_mined":
            case "glass_placed":
            case "carved_pumpkin_placed":
            case "placed_anvil":
            case "traded_with_villager":
            case "deepslate_mined":
                return true;
            default:
                // Check if it's a block group stat type
                if (statType.contains("_placed") || statType.contains("_mined")) {
                    return true;
                }
                // Check if it's a custom stat
                return COMMON_STATS.containsKey(statType);
        }
    }
    
    private static class TotalStatConfig {
        final String id;
        final String displayName;
        final String statType;
        
        TotalStatConfig(String id, String displayName, String statType) {
            this.id = id;
            this.displayName = displayName;
            this.statType = statType;
        }
    }
    
    // Stat management methods
    public static void enableStat(String statId) {
        if (totalStats.containsKey(statId)) {
            enabledStats.add(statId);
            TotalStatConfig config = totalStats.get(statId);
            createTotalObjective(config);
            ServerScoreboardLogger.info("Enabled stat: " + statId);
            // 即座に統計を更新
            updateTotalStat(config);
        }
    }
    
    public static void disableStat(String statId) {
        enabledStats.remove(statId);
        // Remove the objective from scoreboard
        Scoreboard scoreboard = server.getScoreboard();
        String objectiveName = TOTAL_PREFIX + statId;
        ScoreboardObjective objective = scoreboard.getObjective(objectiveName);
        if (objective != null) {
            scoreboard.removeObjective(objective);
        }
        // キャッシュからも削除
        lastPlayerStats.remove(statId);
        cachedTotals.remove(statId);
        ServerScoreboardLogger.info("Disabled stat: " + statId);
    }
    
    public static Set<String> getEnabledStats() {
        return new HashSet<>(enabledStats);
    }
    
    public static Map<String, String> getAllAvailableStats() {
        Map<String, String> available = new HashMap<>();
        for (Map.Entry<String, TotalStatConfig> entry : totalStats.entrySet()) {
            available.put(entry.getKey(), entry.getValue().displayName);
        }
        return available;
    }
    
    // プレイヤー除外管理メソッド
    public static void excludePlayer(String playerName) {
        excludedPlayers.add(playerName);
        ServerScoreboardLogger.info("Excluded player from statistics: " + playerName);
        // 全ての統計を強制更新
        forceUpdateAllStats();
    }
    
    public static void includePlayer(String playerName) {
        excludedPlayers.remove(playerName);
        ServerScoreboardLogger.info("Included player in statistics: " + playerName);
        // 全ての統計を強制更新
        forceUpdateAllStats();
    }
    
    public static Set<String> getExcludedPlayers() {
        return new HashSet<>(excludedPlayers);
    }
    
    public static boolean isPlayerExcluded(String playerName) {
        return excludedPlayers.contains(playerName);
    }
    
    public static boolean isFakePlayer(String playerName) {
        return FakePlayerDetector.isFakePlayerName(playerName);
    }

    public static boolean isFakePlayer(ServerPlayerEntity player) {
        return FakePlayerDetector.isFakePlayer(player);
    }
}
