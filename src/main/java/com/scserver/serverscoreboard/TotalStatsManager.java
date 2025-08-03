package com.scserver.serverscoreboard;

import net.minecraft.scoreboard.Scoreboard;
import net.minecraft.scoreboard.ScoreboardCriterion;
import net.minecraft.scoreboard.ScoreboardObjective;
import net.minecraft.scoreboard.ScoreboardPlayerScore;
import java.util.Collection;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.stat.Stat;
import net.minecraft.stat.StatType;
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
    private static final Set<String> enabledStats = new HashSet<>();
    private static int updateCounter = 0;
    private static final Map<String, Map<String, Integer>> lastPlayerStats = new ConcurrentHashMap<>();
    private static final Set<String> excludedPlayers = new HashSet<>();
    
    // Common statistics
    public static final Map<String, String> COMMON_STATS = new HashMap<>();
    static {
        // 基本統計
        COMMON_STATS.put("mined", "Blocks Mined");
        COMMON_STATS.put("placed", "Blocks Placed");
        COMMON_STATS.put("killed", "Mobs Killed");
        COMMON_STATS.put("deaths", "Deaths");
        COMMON_STATS.put("damage_dealt", "Damage Dealt");
        COMMON_STATS.put("damage_taken", "Damage Taken");
        COMMON_STATS.put("play_time", "Play Time");
        
        // 移動系統計
        COMMON_STATS.put("walk_one_cm", "Distance Walked");
        COMMON_STATS.put("sprint_one_cm", "Distance Sprinted");
        COMMON_STATS.put("swim_one_cm", "Distance Swum");
        COMMON_STATS.put("fall_one_cm", "Distance Fallen");
        COMMON_STATS.put("climb_one_cm", "Distance Climbed");
        COMMON_STATS.put("fly_one_cm", "Distance Flown");
        
        // アクション系統計
        COMMON_STATS.put("jump", "Times Jumped");
        COMMON_STATS.put("drop", "Items Dropped");
        COMMON_STATS.put("fish_caught", "Fish Caught");
        COMMON_STATS.put("animals_bred", "Animals Bred");
        COMMON_STATS.put("leave_game", "Times Left Game");
        COMMON_STATS.put("sleep_in_bed", "Times Slept");
        COMMON_STATS.put("enchant_item", "Items Enchanted");
        
        // ブロック操作系統計
        COMMON_STATS.put("pot_flower", "Flowers Potted");
        COMMON_STATS.put("trigger_trapped_chest", "Trapped Chests Triggered");
        COMMON_STATS.put("open_enderchest", "Ender Chests Opened");
        COMMON_STATS.put("open_chest", "Chests Opened");
        COMMON_STATS.put("open_barrel", "Barrels Opened");
        COMMON_STATS.put("open_shulker_box", "Shulker Boxes Opened");
        
        // 作業台系統計
        COMMON_STATS.put("interact_with_anvil", "Anvils Used");
        COMMON_STATS.put("interact_with_brewingstand", "Brewing Stands Used");
        COMMON_STATS.put("interact_with_beacon", "Beacons Used");
        COMMON_STATS.put("interact_with_crafting_table", "Crafting Tables Used");
        COMMON_STATS.put("interact_with_furnace", "Furnaces Used");
        COMMON_STATS.put("interact_with_blast_furnace", "Blast Furnaces Used");
        COMMON_STATS.put("interact_with_smoker", "Smokers Used");
        COMMON_STATS.put("interact_with_campfire", "Campfires Used");
        COMMON_STATS.put("interact_with_cartography_table", "Cartography Tables Used");
        COMMON_STATS.put("interact_with_loom", "Looms Used");
        COMMON_STATS.put("interact_with_stonecutter", "Stonecutters Used");
        COMMON_STATS.put("interact_with_smithing_table", "Smithing Tables Used");
        COMMON_STATS.put("interact_with_grindstone", "Grindstones Used");
        COMMON_STATS.put("interact_with_lectern", "Lecterns Used");
        
        // その他の統計
        COMMON_STATS.put("bell_ring", "Bells Rung");
        COMMON_STATS.put("raid_trigger", "Raids Triggered");
        COMMON_STATS.put("raid_win", "Raids Won");
        COMMON_STATS.put("talked_to_villager", "Villager Interactions");
        COMMON_STATS.put("traded_with_villager", "Villager Trades");
        
        // 特定ブロック用
        COMMON_STATS.put("placed_anvil", "Anvils Placed");
        COMMON_STATS.put("mined_anvil", "Anvils Mined");
        
        // 追加の移動系統計
        COMMON_STATS.put("aviate_one_cm", "Distance by Elytra");
        COMMON_STATS.put("boat_one_cm", "Distance by Boat");
        COMMON_STATS.put("crouch_one_cm", "Distance Crouched");
        COMMON_STATS.put("horse_one_cm", "Distance by Horse");
        COMMON_STATS.put("minecart_one_cm", "Distance by Minecart");
        COMMON_STATS.put("pig_one_cm", "Distance by Pig");
        COMMON_STATS.put("strider_one_cm", "Distance by Strider");
        COMMON_STATS.put("walk_on_water_one_cm", "Distance on Water");
        COMMON_STATS.put("walk_under_water_one_cm", "Distance Under Water");
        
        // 戦闘系統計
        COMMON_STATS.put("mob_kills", "Mob Kills");
        COMMON_STATS.put("player_kills", "Player Kills");
        COMMON_STATS.put("damage_absorbed", "Damage Absorbed");
        COMMON_STATS.put("damage_blocked_by_shield", "Damage Blocked");
        COMMON_STATS.put("damage_resisted", "Damage Resisted");
        COMMON_STATS.put("damage_dealt_absorbed", "Absorbed Damage Dealt");
        COMMON_STATS.put("damage_dealt_resisted", "Resisted Damage Dealt");
        
        // 時間系統計
        COMMON_STATS.put("time_since_death", "Time Since Death");
        COMMON_STATS.put("time_since_rest", "Time Since Rest");
        COMMON_STATS.put("sneak_time", "Sneak Time");
        
        // その他
        COMMON_STATS.put("target_hit", "Targets Hit");
    }
    
    public static void init(MinecraftServer minecraftServer) {
        server = minecraftServer;
        ServerScoreboardLogger.info("TotalStatsManager initialized");
        
        // デフォルトの統計を作成
        createDefaultTotalStats();
    }
    
    private static void createDefaultTotalStats() {
        // 基本統計を登録（すべて無効状態で開始）
        registerTotalStat("mined", "Total Blocks Mined", "mined");
        registerTotalStat("placed", "Total Blocks Placed", "placed");
        registerTotalStat("killed", "Total Mobs Killed", "killed");
        registerTotalStat("deaths", "Total Deaths", "deaths");
        registerTotalStat("damage_dealt", "Total Damage Dealt", "damage_dealt");
        registerTotalStat("damage_taken", "Total Damage Taken", "damage_taken");
        registerTotalStat("play_time", "Total Play Time", "play_time");
        registerTotalStat("walk_one_cm", "Total Distance Walked", "walk_one_cm");
        registerTotalStat("sprint_one_cm", "Total Distance Sprinted", "sprint_one_cm");
        registerTotalStat("swim_one_cm", "Total Distance Swum", "swim_one_cm");
        registerTotalStat("fall_one_cm", "Total Distance Fallen", "fall_one_cm");
        registerTotalStat("climb_one_cm", "Total Distance Climbed", "climb_one_cm");
        registerTotalStat("fly_one_cm", "Total Distance Flown", "fly_one_cm");
        registerTotalStat("jump", "Total Jumps", "jump");
        registerTotalStat("drop", "Total Items Dropped", "drop");
        registerTotalStat("fish_caught", "Total Fish Caught", "fish_caught");
        registerTotalStat("animals_bred", "Total Animals Bred", "animals_bred");
        registerTotalStat("leave_game", "Total Times Left Game", "leave_game");
        registerTotalStat("sleep_in_bed", "Total Times Slept", "sleep_in_bed");
        registerTotalStat("enchant_item", "Total Items Enchanted", "enchant_item");
        registerTotalStat("pot_flower", "Total Flowers Potted", "pot_flower");
        registerTotalStat("trigger_trapped_chest", "Total Trapped Chests Triggered", "trigger_trapped_chest");
        registerTotalStat("open_enderchest", "Total Ender Chests Opened", "open_enderchest");
        registerTotalStat("inspect_hopper", "Total Hoppers Inspected", "inspect_hopper");
        registerTotalStat("inspect_dispenser", "Total Dispensers Inspected", "inspect_dispenser");
        registerTotalStat("inspect_dropper", "Total Droppers Inspected", "inspect_dropper");
        registerTotalStat("play_noteblock", "Total Note Blocks Played", "play_noteblock");
        registerTotalStat("tune_noteblock", "Total Note Blocks Tuned", "tune_noteblock");
        registerTotalStat("play_record", "Total Records Played", "play_record");
        registerTotalStat("interact_with_brewingstand", "Total Brewing Stands Used", "interact_with_brewingstand");
        registerTotalStat("interact_with_beacon", "Total Beacons Used", "interact_with_beacon");
        registerTotalStat("interact_with_crafting_table", "Total Crafting Tables Used", "interact_with_crafting_table");
        registerTotalStat("interact_with_furnace", "Total Furnaces Used", "interact_with_furnace");
        registerTotalStat("interact_with_blast_furnace", "Total Blast Furnaces Used", "interact_with_blast_furnace");
        registerTotalStat("interact_with_smoker", "Total Smokers Used", "interact_with_smoker");
        registerTotalStat("interact_with_campfire", "Total Campfires Used", "interact_with_campfire");
        registerTotalStat("interact_with_cartography_table", "Total Cartography Tables Used", "interact_with_cartography_table");
        registerTotalStat("interact_with_loom", "Total Looms Used", "interact_with_loom");
        registerTotalStat("interact_with_stonecutter", "Total Stonecutters Used", "interact_with_stonecutter");
        registerTotalStat("interact_with_smithing_table", "Total Smithing Tables Used", "interact_with_smithing_table");
        registerTotalStat("interact_with_grindstone", "Total Grindstones Used", "interact_with_grindstone");
        registerTotalStat("interact_with_lectern", "Total Lecterns Used", "interact_with_lectern");
        registerTotalStat("interact_with_anvil", "Total Anvils Used", "interact_with_anvil");
        registerTotalStat("bell_ring", "Total Bells Rung", "bell_ring");
        registerTotalStat("raid_trigger", "Total Raids Triggered", "raid_trigger");
        registerTotalStat("raid_win", "Total Raids Won", "raid_win");
        registerTotalStat("eat_cake_slice", "Total Cake Slices Eaten", "eat_cake_slice");
        registerTotalStat("fill_cauldron", "Total Cauldrons Filled", "fill_cauldron");
        registerTotalStat("use_cauldron", "Total Cauldrons Used", "use_cauldron");
        registerTotalStat("clean_armor", "Total Armor Cleaned", "clean_armor");
        registerTotalStat("clean_banner", "Total Banners Cleaned", "clean_banner");
        registerTotalStat("clean_shulker_box", "Total Shulker Boxes Cleaned", "clean_shulker_box");
        registerTotalStat("open_barrel", "Total Barrels Opened", "open_barrel");
        registerTotalStat("open_chest", "Total Chests Opened", "open_chest");
        registerTotalStat("open_shulker_box", "Total Shulker Boxes Opened", "open_shulker_box");
        registerTotalStat("talked_to_villager", "Total Villager Interactions", "talked_to_villager");
        registerTotalStat("traded_with_villager", "Total Villager Trades", "traded_with_villager");
        
        // 特定ブロックの詳細統計例（金床）
        registerTotalStat("placed_anvil", "Total Anvils Placed", "placed_anvil");
        registerTotalStat("mined_anvil", "Total Anvils Mined", "mined_anvil");
        registerTotalStat("anvil_used", "Total Anvils Used", "interact_with_anvil");
        
        // 追加の移動系統計
        registerTotalStat("aviate_one_cm", "Total Distance by Elytra", "aviate_one_cm");
        registerTotalStat("boat_one_cm", "Total Distance by Boat", "boat_one_cm");
        registerTotalStat("crouch_one_cm", "Total Distance Crouched", "crouch_one_cm");
        registerTotalStat("horse_one_cm", "Total Distance by Horse", "horse_one_cm");
        registerTotalStat("minecart_one_cm", "Total Distance by Minecart", "minecart_one_cm");
        registerTotalStat("pig_one_cm", "Total Distance by Pig", "pig_one_cm");
        registerTotalStat("strider_one_cm", "Total Distance by Strider", "strider_one_cm");
        registerTotalStat("walk_on_water_one_cm", "Total Distance on Water", "walk_on_water_one_cm");
        registerTotalStat("walk_under_water_one_cm", "Total Distance Under Water", "walk_under_water_one_cm");
        
        // 戦闘・サバイバル系統計
        registerTotalStat("mob_kills", "Total Mob Kills", "mob_kills");
        registerTotalStat("player_kills", "Total Player Kills", "player_kills");
        registerTotalStat("damage_absorbed", "Total Damage Absorbed", "damage_absorbed");
        registerTotalStat("damage_blocked_by_shield", "Total Damage Blocked", "damage_blocked_by_shield");
        registerTotalStat("damage_resisted", "Total Damage Resisted", "damage_resisted");
        registerTotalStat("damage_dealt_absorbed", "Total Absorbed Damage Dealt", "damage_dealt_absorbed");
        registerTotalStat("damage_dealt_resisted", "Total Resisted Damage Dealt", "damage_dealt_resisted");
        
        // クラフト・資源系統計
        registerTotalStat("break_item", "Total Items Broken", "break_item");
        registerTotalStat("craft_item", "Total Items Crafted", "craft_item");
        registerTotalStat("use_item", "Total Items Used", "use_item");
        registerTotalStat("pick_up_item", "Total Items Picked Up", "pick_up_item");
        registerTotalStat("drop_item", "Total Items Dropped", "drop_item");
        registerTotalStat("kill_entity", "Total Entities Killed", "kill_entity");
        registerTotalStat("entity_killed_by", "Total Deaths by Entities", "entity_killed_by");
        registerTotalStat("target_hit", "Total Targets Hit", "target_hit");
        
        // 時間系統計
        registerTotalStat("time_since_death", "Time Since Last Death", "time_since_death");
        registerTotalStat("time_since_rest", "Time Since Last Rest", "time_since_rest");
        registerTotalStat("sneak_time", "Total Sneak Time", "sneak_time");
        
        // ブロックグループ統計
        registerTotalStat("wool_placed", "Total Wool Placed", "wool_placed");
        registerTotalStat("wool_mined", "Total Wool Mined", "wool_mined");
        registerTotalStat("concrete_placed", "Total Concrete Placed", "concrete_placed");
        registerTotalStat("concrete_mined", "Total Concrete Mined", "concrete_mined");
        registerTotalStat("concrete_powder_placed", "Total Concrete Powder Placed", "concrete_powder_placed");
        registerTotalStat("concrete_powder_mined", "Total Concrete Powder Mined", "concrete_powder_mined");
        registerTotalStat("terracotta_placed", "Total Terracotta Placed", "terracotta_placed");
        registerTotalStat("terracotta_mined", "Total Terracotta Mined", "terracotta_mined");
        registerTotalStat("glazed_terracotta_placed", "Total Glazed Terracotta Placed", "glazed_terracotta_placed");
        registerTotalStat("glazed_terracotta_mined", "Total Glazed Terracotta Mined", "glazed_terracotta_mined");
        registerTotalStat("glass_placed", "Total Glass Placed", "glass_placed");
        registerTotalStat("glass_mined", "Total Glass Mined", "glass_mined");
        registerTotalStat("glass_pane_placed", "Total Glass Panes Placed", "glass_pane_placed");
        registerTotalStat("glass_pane_mined", "Total Glass Panes Mined", "glass_pane_mined");
        registerTotalStat("coral_placed", "Total Coral Placed", "coral_placed");
        registerTotalStat("coral_mined", "Total Coral Mined", "coral_mined");
        registerTotalStat("coral_block_placed", "Total Coral Blocks Placed", "coral_block_placed");
        registerTotalStat("coral_block_mined", "Total Coral Blocks Mined", "coral_block_mined");
        registerTotalStat("bed_placed", "Total Beds Placed", "bed_placed");
        registerTotalStat("bed_mined", "Total Beds Mined", "bed_mined");
        registerTotalStat("banner_placed", "Total Banners Placed", "banner_placed");
        registerTotalStat("banner_mined", "Total Banners Mined", "banner_mined");
        registerTotalStat("shulker_box_placed", "Total Shulker Boxes Placed", "shulker_box_placed");
        registerTotalStat("shulker_box_mined", "Total Shulker Boxes Mined", "shulker_box_mined");
        registerTotalStat("candle_placed", "Total Candles Placed", "candle_placed");
        registerTotalStat("candle_mined", "Total Candles Mined", "candle_mined");
        registerTotalStat("carpet_placed", "Total Carpets Placed", "carpet_placed");
        registerTotalStat("carpet_mined", "Total Carpets Mined", "carpet_mined");
        registerTotalStat("wood_placed", "Total Wood Placed", "wood_placed");
        registerTotalStat("wood_mined", "Total Wood Mined", "wood_mined");
        registerTotalStat("planks_placed", "Total Planks Placed", "planks_placed");
        registerTotalStat("planks_mined", "Total Planks Mined", "planks_mined");
        registerTotalStat("log_placed", "Total Logs Placed", "log_placed");
        registerTotalStat("log_mined", "Total Logs Mined", "log_mined");
        registerTotalStat("leaves_placed", "Total Leaves Placed", "leaves_placed");
        registerTotalStat("leaves_mined", "Total Leaves Mined", "leaves_mined");
        registerTotalStat("sapling_placed", "Total Saplings Placed", "sapling_placed");
        registerTotalStat("sapling_mined", "Total Saplings Mined", "sapling_mined");
        registerTotalStat("flower_placed", "Total Flowers Placed", "flower_placed");
        registerTotalStat("flower_mined", "Total Flowers Mined", "flower_mined");
        registerTotalStat("ore_mined", "Total Ores Mined", "ore_mined");
        registerTotalStat("deepslate_ore_mined", "Total Deepslate Ores Mined", "deepslate_ore_mined");
        
        // 追加の統計タイプ
        registerTotalStat("total_world_time", "Total World Time", "total_world_time");
        registerTotalStat("flower_potted", "Total Flowers Potted", "flower_potted");
        registerTotalStat("item_enchanted", "Total Items Enchanted", "item_enchanted");
        registerTotalStat("village_raid_hero", "Total Raid Hero Status", "village_raid_hero");
        
        // 特定の移動統計
        registerTotalStat("fly_with_elytra", "Total Elytra Flight Distance", "fly_with_elytra");
        registerTotalStat("sneak_one_cm", "Total Sneak Distance", "sneak_one_cm");
        
        // デフォルトでは全て無効（必要に応じて有効化）
        // enabledStats は空のままにしておく
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
        
        // 即座に更新する
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
        
        // 時間系統計の場合は特別な表示処理
        if (config.statType.equals("play_time") || config.statType.equals("sneak_time") || 
            config.statType.equals("time_since_death") || config.statType.equals("time_since_rest")) {
            // プレイ時間を名前の後ろに表示、スコアは0に設定
            for (Map.Entry<String, Integer> entry : playerStats.entrySet()) {
                String playerName = entry.getKey();
                int ticks = entry.getValue();
                String timeFormatted = formatTimeShort(ticks);
                String displayName = playerName + " §7" + timeFormatted;
                ScoreboardPlayerScore score = scoreboard.getPlayerScore(displayName, objective);
                score.setScore(0);
            }
            
            // サーバー合計も同様に表示
            String totalTimeFormatted = formatTimeShort(total);
            String totalDisplayName = "  §6§l$SERVER_TOTAL §7" + totalTimeFormatted;
            ScoreboardPlayerScore totalScore = scoreboard.getPlayerScore(totalDisplayName, objective);
            totalScore.setScore(0);
        } else {
            // 通常の統計表示
            ScoreboardPlayerScore totalScore = scoreboard.getPlayerScore("  §6§l$SERVER_TOTAL", objective);
            int oldTotal = totalScore.getScore();
            totalScore.setScore(total);
            
            // デバッグログ: 合計スコアの変更
            if (oldTotal != total) {
                ServerScoreboardLogger.debugScoreChange("合計スコア更新", config.id, "$SERVER_TOTAL", oldTotal, total);
            }
            
            for (Map.Entry<String, Integer> entry : playerStats.entrySet()) {
                if (entry.getValue() > 0) { // 0の値は表示しない
                    ScoreboardPlayerScore score = scoreboard.getPlayerScore(entry.getKey(), objective);
                    int oldValue = score.getScore();
                    score.setScore(entry.getValue());
                    
                    // デバッグログ: 個別スコアの変更
                    if (oldValue != entry.getValue()) {
                        ServerScoreboardLogger.debugScoreChange("スコア更新", config.id, entry.getKey(), oldValue, entry.getValue());
                    }
                }
            }
        }
        
        cachedTotals.put(config.id, total);
        ServerScoreboardLogger.debug("Updated " + config.id + " - Total: " + total + ", Players: " + playerStats.size());
        
        // 統計スコアボードを表示しているプレイヤーに更新を送信
        ServerScoreboardManager.updateTotalStatsForWatchers();
    }
    
    private static int calculateTotalForStat(TotalStatConfig config) {
        int total = 0;
        
        // 全プレイヤーの統計を合計
        for (ServerPlayerEntity player : server.getPlayerManager().getPlayerList()) {
            // 除外リストに含まれているプレイヤーはスキップ
            if (excludedPlayers.contains(player.getName().getString())) {
                continue;
            }
            total += getPlayerStatTotal(player, config.statType);
        }
        
        return total;
    }
    
    // プレイ時間をフォーマット（tick -> 日時分）
    private static String formatPlayTime(int ticks) {
        int totalSeconds = ticks / 20;
        int days = totalSeconds / 86400;
        int hours = (totalSeconds % 86400) / 3600;
        int minutes = (totalSeconds % 3600) / 60;
        
        if (days > 0) {
            return String.format("%dd %dh %dm", days, hours, minutes);
        } else if (hours > 0) {
            return String.format("%dh %dm", hours, minutes);
        } else {
            return String.format("%dm", minutes);
        }
    }
    
    // 時間をスコア値にエンコード（表示は日時分形式になるが、実際は分単位の値）
    private static int encodeTimeAsScore(int ticks) {
        int totalMinutes = ticks / 1200; // 20 ticks/秒 * 60秒 = 1200 ticks/分
        return totalMinutes;
    }
    
    // 短い時間表示フォーマット（nd nh nm形式）
    private static String formatTimeShort(int ticks) {
        int totalSeconds = ticks / 20;
        int days = totalSeconds / 86400;
        int hours = (totalSeconds % 86400) / 3600;
        int minutes = (totalSeconds % 3600) / 60;
        
        if (days > 0) {
            return String.format("%dd %dh %dm", days, hours, minutes);
        } else if (hours > 0) {
            return String.format("%dh %dm", hours, minutes);
        } else {
            return String.format("%dm", minutes);
        }
    }
    
    public static int getPlayerStatTotal(ServerPlayerEntity player, String statType) {
        int total = 0;
        
        // 統計タイプに基づいて適切な値を取得
        switch (statType.toLowerCase()) {
            case "mined":
                // 全ブロックの採掘数を合計
                try {
                    for (var block : Registries.BLOCK) {
                        Stat<net.minecraft.block.Block> stat = Stats.MINED.getOrCreateStat(block);
                        total += player.getStatHandler().getStat(stat);
                    }
                } catch (Exception e) {
                    // エラーを無視
                }
                break;
            case "placed":
            case "used":
                // 全アイテムの使用数を合計（ブロック設置を含む）
                try {
                    for (var item : Registries.ITEM) {
                        if (item instanceof net.minecraft.item.BlockItem) {
                            Stat<net.minecraft.item.Item> stat = Stats.USED.getOrCreateStat(item);
                            total += player.getStatHandler().getStat(stat);
                        }
                    }
                } catch (Exception e) {
                    // エラーを無視
                }
                break;
            case "killed":
                // 全エンティティの撃破数を合計
                try {
                    for (var entityType : Registries.ENTITY_TYPE) {
                        Stat<net.minecraft.entity.EntityType<?>> stat = Stats.KILLED.getOrCreateStat(entityType);
                        total += player.getStatHandler().getStat(stat);
                    }
                } catch (Exception e) {
                    // エラーを無視
                }
                break;
            // 特定ブロックの統計
            case "placed_anvil":
                try {
                    var anvil = Registries.ITEM.get(new net.minecraft.util.Identifier("minecraft", "anvil"));
                    if (anvil != null) {
                        total = player.getStatHandler().getStat(Stats.USED.getOrCreateStat(anvil));
                    }
                } catch (Exception e) {}
                break;
            case "mined_anvil":
                try {
                    var anvilBlock = Registries.BLOCK.get(new net.minecraft.util.Identifier("minecraft", "anvil"));
                    if (anvilBlock != null) {
                        total = player.getStatHandler().getStat(Stats.MINED.getOrCreateStat(anvilBlock));
                    }
                } catch (Exception e) {}
                break;
            // カスタム統計
            case "deaths":
                total = player.getStatHandler().getStat(Stats.CUSTOM.getOrCreateStat(Stats.DEATHS));
                break;
            case "damage_dealt":
                total = player.getStatHandler().getStat(Stats.CUSTOM.getOrCreateStat(Stats.DAMAGE_DEALT));
                break;
            case "damage_taken":
                total = player.getStatHandler().getStat(Stats.CUSTOM.getOrCreateStat(Stats.DAMAGE_TAKEN));
                break;
            case "play_time":
                total = player.getStatHandler().getStat(Stats.CUSTOM.getOrCreateStat(Stats.PLAY_TIME));
                break;
            case "walk_one_cm":
                total = player.getStatHandler().getStat(Stats.CUSTOM.getOrCreateStat(Stats.WALK_ONE_CM));
                break;
            case "sprint_one_cm":
                total = player.getStatHandler().getStat(Stats.CUSTOM.getOrCreateStat(Stats.SPRINT_ONE_CM));
                break;
            case "swim_one_cm":
                total = player.getStatHandler().getStat(Stats.CUSTOM.getOrCreateStat(Stats.SWIM_ONE_CM));
                break;
            case "fall_one_cm":
                total = player.getStatHandler().getStat(Stats.CUSTOM.getOrCreateStat(Stats.FALL_ONE_CM));
                break;
            case "climb_one_cm":
                total = player.getStatHandler().getStat(Stats.CUSTOM.getOrCreateStat(Stats.CLIMB_ONE_CM));
                break;
            case "fly_one_cm":
                total = player.getStatHandler().getStat(Stats.CUSTOM.getOrCreateStat(Stats.FLY_ONE_CM));
                break;
            case "jump":
                total = player.getStatHandler().getStat(Stats.CUSTOM.getOrCreateStat(Stats.JUMP));
                break;
            case "drop":
                total = player.getStatHandler().getStat(Stats.CUSTOM.getOrCreateStat(Stats.DROP));
                break;
            case "fish_caught":
                total = player.getStatHandler().getStat(Stats.CUSTOM.getOrCreateStat(Stats.FISH_CAUGHT));
                break;
            case "animals_bred":
                total = player.getStatHandler().getStat(Stats.CUSTOM.getOrCreateStat(Stats.ANIMALS_BRED));
                break;
            case "leave_game":
                total = player.getStatHandler().getStat(Stats.CUSTOM.getOrCreateStat(Stats.LEAVE_GAME));
                break;
            case "sleep_in_bed":
                total = player.getStatHandler().getStat(Stats.CUSTOM.getOrCreateStat(Stats.SLEEP_IN_BED));
                break;
            case "enchant_item":
                total = player.getStatHandler().getStat(Stats.CUSTOM.getOrCreateStat(Stats.ENCHANT_ITEM));
                break;
            case "pot_flower":
                total = player.getStatHandler().getStat(Stats.CUSTOM.getOrCreateStat(Stats.POT_FLOWER));
                break;
            case "trigger_trapped_chest":
                total = player.getStatHandler().getStat(Stats.CUSTOM.getOrCreateStat(Stats.TRIGGER_TRAPPED_CHEST));
                break;
            case "open_enderchest":
                total = player.getStatHandler().getStat(Stats.CUSTOM.getOrCreateStat(Stats.OPEN_ENDERCHEST));
                break;
            case "open_chest":
                total = player.getStatHandler().getStat(Stats.CUSTOM.getOrCreateStat(Stats.OPEN_CHEST));
                break;
            case "open_barrel":
                total = player.getStatHandler().getStat(Stats.CUSTOM.getOrCreateStat(Stats.OPEN_BARREL));
                break;
            case "open_shulker_box":
                total = player.getStatHandler().getStat(Stats.CUSTOM.getOrCreateStat(Stats.OPEN_SHULKER_BOX));
                break;
            case "interact_with_anvil":
                total = player.getStatHandler().getStat(Stats.CUSTOM.getOrCreateStat(Stats.INTERACT_WITH_ANVIL));
                break;
            case "interact_with_brewingstand":
                total = player.getStatHandler().getStat(Stats.CUSTOM.getOrCreateStat(Stats.INTERACT_WITH_BREWINGSTAND));
                break;
            case "interact_with_beacon":
                total = player.getStatHandler().getStat(Stats.CUSTOM.getOrCreateStat(Stats.INTERACT_WITH_BEACON));
                break;
            case "interact_with_crafting_table":
                total = player.getStatHandler().getStat(Stats.CUSTOM.getOrCreateStat(Stats.INTERACT_WITH_CRAFTING_TABLE));
                break;
            case "interact_with_furnace":
                total = player.getStatHandler().getStat(Stats.CUSTOM.getOrCreateStat(Stats.INTERACT_WITH_FURNACE));
                break;
            case "interact_with_blast_furnace":
                total = player.getStatHandler().getStat(Stats.CUSTOM.getOrCreateStat(Stats.INTERACT_WITH_BLAST_FURNACE));
                break;
            case "interact_with_smoker":
                total = player.getStatHandler().getStat(Stats.CUSTOM.getOrCreateStat(Stats.INTERACT_WITH_SMOKER));
                break;
            case "interact_with_campfire":
                total = player.getStatHandler().getStat(Stats.CUSTOM.getOrCreateStat(Stats.INTERACT_WITH_CAMPFIRE));
                break;
            case "interact_with_cartography_table":
                total = player.getStatHandler().getStat(Stats.CUSTOM.getOrCreateStat(Stats.INTERACT_WITH_CARTOGRAPHY_TABLE));
                break;
            case "interact_with_loom":
                total = player.getStatHandler().getStat(Stats.CUSTOM.getOrCreateStat(Stats.INTERACT_WITH_LOOM));
                break;
            case "interact_with_stonecutter":
                total = player.getStatHandler().getStat(Stats.CUSTOM.getOrCreateStat(Stats.INTERACT_WITH_STONECUTTER));
                break;
            case "interact_with_smithing_table":
                total = player.getStatHandler().getStat(Stats.CUSTOM.getOrCreateStat(Stats.INTERACT_WITH_SMITHING_TABLE));
                break;
            case "interact_with_grindstone":
                total = player.getStatHandler().getStat(Stats.CUSTOM.getOrCreateStat(Stats.INTERACT_WITH_GRINDSTONE));
                break;
            case "interact_with_lectern":
                total = player.getStatHandler().getStat(Stats.CUSTOM.getOrCreateStat(Stats.INTERACT_WITH_LECTERN));
                break;
            case "bell_ring":
                total = player.getStatHandler().getStat(Stats.CUSTOM.getOrCreateStat(Stats.BELL_RING));
                break;
            case "raid_trigger":
                total = player.getStatHandler().getStat(Stats.CUSTOM.getOrCreateStat(Stats.RAID_TRIGGER));
                break;
            case "raid_win":
                total = player.getStatHandler().getStat(Stats.CUSTOM.getOrCreateStat(Stats.RAID_WIN));
                break;
            case "talked_to_villager":
                total = player.getStatHandler().getStat(Stats.CUSTOM.getOrCreateStat(Stats.TALKED_TO_VILLAGER));
                break;
            case "traded_with_villager":
                total = player.getStatHandler().getStat(Stats.CUSTOM.getOrCreateStat(Stats.TRADED_WITH_VILLAGER));
                break;
            // 追加の移動系統計
            case "aviate_one_cm":
                total = player.getStatHandler().getStat(Stats.CUSTOM.getOrCreateStat(Stats.AVIATE_ONE_CM));
                break;
            case "boat_one_cm":
                total = player.getStatHandler().getStat(Stats.CUSTOM.getOrCreateStat(Stats.BOAT_ONE_CM));
                break;
            case "crouch_one_cm":
                total = player.getStatHandler().getStat(Stats.CUSTOM.getOrCreateStat(Stats.CROUCH_ONE_CM));
                break;
            case "horse_one_cm":
                total = player.getStatHandler().getStat(Stats.CUSTOM.getOrCreateStat(Stats.HORSE_ONE_CM));
                break;
            case "minecart_one_cm":
                total = player.getStatHandler().getStat(Stats.CUSTOM.getOrCreateStat(Stats.MINECART_ONE_CM));
                break;
            case "pig_one_cm":
                total = player.getStatHandler().getStat(Stats.CUSTOM.getOrCreateStat(Stats.PIG_ONE_CM));
                break;
            case "strider_one_cm":
                total = player.getStatHandler().getStat(Stats.CUSTOM.getOrCreateStat(Stats.STRIDER_ONE_CM));
                break;
            case "walk_on_water_one_cm":
                total = player.getStatHandler().getStat(Stats.CUSTOM.getOrCreateStat(Stats.WALK_ON_WATER_ONE_CM));
                break;
            case "walk_under_water_one_cm":
                total = player.getStatHandler().getStat(Stats.CUSTOM.getOrCreateStat(Stats.WALK_UNDER_WATER_ONE_CM));
                break;
            // 戦闘系統計
            case "mob_kills":
                total = player.getStatHandler().getStat(Stats.CUSTOM.getOrCreateStat(Stats.MOB_KILLS));
                break;
            case "player_kills":
                total = player.getStatHandler().getStat(Stats.CUSTOM.getOrCreateStat(Stats.PLAYER_KILLS));
                break;
            case "damage_absorbed":
                total = player.getStatHandler().getStat(Stats.CUSTOM.getOrCreateStat(Stats.DAMAGE_ABSORBED));
                break;
            case "damage_blocked_by_shield":
                total = player.getStatHandler().getStat(Stats.CUSTOM.getOrCreateStat(Stats.DAMAGE_BLOCKED_BY_SHIELD));
                break;
            case "damage_resisted":
                total = player.getStatHandler().getStat(Stats.CUSTOM.getOrCreateStat(Stats.DAMAGE_RESISTED));
                break;
            case "damage_dealt_absorbed":
                total = player.getStatHandler().getStat(Stats.CUSTOM.getOrCreateStat(Stats.DAMAGE_DEALT_ABSORBED));
                break;
            case "damage_dealt_resisted":
                total = player.getStatHandler().getStat(Stats.CUSTOM.getOrCreateStat(Stats.DAMAGE_DEALT_RESISTED));
                break;
            // 時間系統計（特別な表示処理が必要）
            case "time_since_death":
                total = player.getStatHandler().getStat(Stats.CUSTOM.getOrCreateStat(Stats.TIME_SINCE_DEATH));
                break;
            case "time_since_rest":
                total = player.getStatHandler().getStat(Stats.CUSTOM.getOrCreateStat(Stats.TIME_SINCE_REST));
                break;
            case "sneak_time":
                total = player.getStatHandler().getStat(Stats.CUSTOM.getOrCreateStat(Stats.SNEAK_TIME));
                break;
            // その他
            case "target_hit":
                total = player.getStatHandler().getStat(Stats.CUSTOM.getOrCreateStat(Stats.TARGET_HIT));
                break;
            case "clean_shulker_box":
                total = player.getStatHandler().getStat(Stats.CUSTOM.getOrCreateStat(Stats.CLEAN_SHULKER_BOX));
                break;
            case "eat_cake_slice":
                total = player.getStatHandler().getStat(Stats.CUSTOM.getOrCreateStat(Stats.EAT_CAKE_SLICE));
                break;
            case "fill_cauldron":
                total = player.getStatHandler().getStat(Stats.CUSTOM.getOrCreateStat(Stats.FILL_CAULDRON));
                break;
            case "use_cauldron":
                total = player.getStatHandler().getStat(Stats.CUSTOM.getOrCreateStat(Stats.USE_CAULDRON));
                break;
            case "clean_armor":
                total = player.getStatHandler().getStat(Stats.CUSTOM.getOrCreateStat(Stats.CLEAN_ARMOR));
                break;
            case "clean_banner":
                total = player.getStatHandler().getStat(Stats.CUSTOM.getOrCreateStat(Stats.CLEAN_BANNER));
                break;
            case "inspect_hopper":
                total = player.getStatHandler().getStat(Stats.CUSTOM.getOrCreateStat(Stats.INSPECT_HOPPER));
                break;
            case "inspect_dispenser":
                total = player.getStatHandler().getStat(Stats.CUSTOM.getOrCreateStat(Stats.INSPECT_DISPENSER));
                break;
            case "inspect_dropper":
                total = player.getStatHandler().getStat(Stats.CUSTOM.getOrCreateStat(Stats.INSPECT_DROPPER));
                break;
            case "play_noteblock":
                total = player.getStatHandler().getStat(Stats.CUSTOM.getOrCreateStat(Stats.PLAY_NOTEBLOCK));
                break;
            case "tune_noteblock":
                total = player.getStatHandler().getStat(Stats.CUSTOM.getOrCreateStat(Stats.TUNE_NOTEBLOCK));
                break;
            case "play_record":
                total = player.getStatHandler().getStat(Stats.CUSTOM.getOrCreateStat(Stats.PLAY_RECORD));
                break;
                
            // ブロックグループ統計
            case "wool_placed":
                total = getBlockGroupTotal(player, "placed", "wool");
                break;
            case "wool_mined":
                total = getBlockGroupTotal(player, "mined", "wool");
                break;
            case "concrete_placed":
                total = getBlockGroupTotal(player, "placed", "concrete");
                break;
            case "concrete_mined":
                total = getBlockGroupTotal(player, "mined", "concrete");
                break;
            case "concrete_powder_placed":
                total = getBlockGroupTotal(player, "placed", "concrete_powder");
                break;
            case "concrete_powder_mined":
                total = getBlockGroupTotal(player, "mined", "concrete_powder");
                break;
            case "terracotta_placed":
                total = getBlockGroupTotal(player, "placed", "terracotta");
                break;
            case "terracotta_mined":
                total = getBlockGroupTotal(player, "mined", "terracotta");
                break;
            case "glazed_terracotta_placed":
                total = getBlockGroupTotal(player, "placed", "glazed_terracotta");
                break;
            case "glazed_terracotta_mined":
                total = getBlockGroupTotal(player, "mined", "glazed_terracotta");
                break;
            case "glass_placed":
                total = getBlockGroupTotal(player, "placed", "glass");
                break;
            case "glass_mined":
                total = getBlockGroupTotal(player, "mined", "glass");
                break;
            case "glass_pane_placed":
                total = getBlockGroupTotal(player, "placed", "glass_pane");
                break;
            case "glass_pane_mined":
                total = getBlockGroupTotal(player, "mined", "glass_pane");
                break;
            case "coral_placed":
                total = getBlockGroupTotal(player, "placed", "coral");
                break;
            case "coral_mined":
                total = getBlockGroupTotal(player, "mined", "coral");
                break;
            case "bed_placed":
                total = getBlockGroupTotal(player, "placed", "bed");
                break;
            case "bed_mined":
                total = getBlockGroupTotal(player, "mined", "bed");
                break;
            case "banner_placed":
                total = getBlockGroupTotal(player, "placed", "banner");
                break;
            case "banner_mined":
                total = getBlockGroupTotal(player, "mined", "banner");
                break;
            case "shulker_box_placed":
                total = getBlockGroupTotal(player, "placed", "shulker_box");
                break;
            case "shulker_box_mined":
                total = getBlockGroupTotal(player, "mined", "shulker_box");
                break;
            case "candle_placed":
                total = getBlockGroupTotal(player, "placed", "candle");
                break;
            case "candle_mined":
                total = getBlockGroupTotal(player, "mined", "candle");
                break;
            case "carpet_placed":
                total = getBlockGroupTotal(player, "placed", "carpet");
                break;
            case "carpet_mined":
                total = getBlockGroupTotal(player, "mined", "carpet");
                break;
            case "wood_placed":
                total = getBlockGroupTotal(player, "placed", "wood");
                break;
            case "wood_mined":
                total = getBlockGroupTotal(player, "mined", "wood");
                break;
            case "planks_placed":
                total = getBlockGroupTotal(player, "placed", "planks");
                break;
            case "planks_mined":
                total = getBlockGroupTotal(player, "mined", "planks");
                break;
            case "ore_mined":
                total = getBlockGroupTotal(player, "mined", "ore");
                break;
            case "deepslate_ore_mined":
                total = getBlockGroupTotal(player, "mined", "deepslate_ore");
                break;
            case "log_placed":
                total = getBlockGroupTotal(player, "placed", "log");
                break;
            case "log_mined":
                total = getBlockGroupTotal(player, "mined", "log");
                break;
            case "leaves_placed":
                total = getBlockGroupTotal(player, "placed", "leaves");
                break;
            case "leaves_mined":
                total = getBlockGroupTotal(player, "mined", "leaves");
                break;
            case "sapling_placed":
                total = getBlockGroupTotal(player, "placed", "sapling");
                break;
            case "sapling_mined":
                total = getBlockGroupTotal(player, "mined", "sapling");
                break;
            case "flower_placed":
                total = getBlockGroupTotal(player, "placed", "flower");
                break;
            case "flower_mined":
                total = getBlockGroupTotal(player, "mined", "flower");
                break;
            case "coral_block_placed":
                total = getBlockGroupTotal(player, "placed", "coral_block");
                break;
            case "coral_block_mined":
                total = getBlockGroupTotal(player, "mined", "coral_block");
                break;
                
            // 追加した統計タイプの処理
            case "total_world_time":
                total = player.getStatHandler().getStat(Stats.CUSTOM.getOrCreateStat(Stats.TOTAL_WORLD_TIME));
                break;
            case "flower_potted":
                total = player.getStatHandler().getStat(Stats.CUSTOM.getOrCreateStat(Stats.POT_FLOWER));
                break;
            case "item_enchanted":
                total = player.getStatHandler().getStat(Stats.CUSTOM.getOrCreateStat(Stats.ENCHANT_ITEM));
                break;
            case "fly_with_elytra":
                total = player.getStatHandler().getStat(Stats.CUSTOM.getOrCreateStat(Stats.AVIATE_ONE_CM));
                break;
            case "sneak_one_cm":
                total = player.getStatHandler().getStat(Stats.CUSTOM.getOrCreateStat(Stats.CROUCH_ONE_CM));
                break;
            case "craft_item":
                // 全アイテムのクラフト数を合計
                try {
                    for (var item : Registries.ITEM) {
                        Stat<net.minecraft.item.Item> stat = Stats.CRAFTED.getOrCreateStat(item);
                        total += player.getStatHandler().getStat(stat);
                    }
                } catch (Exception e) {
                    // エラーを無視
                }
                break;
            case "break_item":
                // 全アイテムの破損数を合計
                try {
                    for (var item : Registries.ITEM) {
                        Stat<net.minecraft.item.Item> stat = Stats.BROKEN.getOrCreateStat(item);
                        total += player.getStatHandler().getStat(stat);
                    }
                } catch (Exception e) {
                    // エラーを無視
                }
                break;
            case "use_item":
                // 全アイテムの使用数を合計
                try {
                    for (var item : Registries.ITEM) {
                        Stat<net.minecraft.item.Item> stat = Stats.USED.getOrCreateStat(item);
                        total += player.getStatHandler().getStat(stat);
                    }
                } catch (Exception e) {
                    // エラーを無視
                }
                break;
            case "pick_up_item":
                // 全アイテムの拾得数を合計
                try {
                    for (var item : Registries.ITEM) {
                        Stat<net.minecraft.item.Item> stat = Stats.PICKED_UP.getOrCreateStat(item);
                        total += player.getStatHandler().getStat(stat);
                    }
                } catch (Exception e) {
                    // エラーを無視
                }
                break;
            case "drop_item":
                // 全アイテムのドロップ数を合計
                try {
                    for (var item : Registries.ITEM) {
                        Stat<net.minecraft.item.Item> stat = Stats.DROPPED.getOrCreateStat(item);
                        total += player.getStatHandler().getStat(stat);
                    }
                } catch (Exception e) {
                    // エラーを無視
                }
                break;
            case "kill_entity":
                // 全エンティティの撃破数を合計（killedと同じ）
                try {
                    for (var entityType : Registries.ENTITY_TYPE) {
                        Stat<net.minecraft.entity.EntityType<?>> stat = Stats.KILLED.getOrCreateStat(entityType);
                        total += player.getStatHandler().getStat(stat);
                    }
                } catch (Exception e) {
                    // エラーを無視
                }
                break;
            case "entity_killed_by":
                // プレイヤーが各エンティティに倒された回数を合計
                try {
                    for (var entityType : Registries.ENTITY_TYPE) {
                        Stat<net.minecraft.entity.EntityType<?>> stat = Stats.KILLED_BY.getOrCreateStat(entityType);
                        total += player.getStatHandler().getStat(stat);
                    }
                } catch (Exception e) {
                    // エラーを無視
                }
                break;
            case "village_raid_hero":
                // レイドヒーロー状態は特殊な統計ではないため、0を返す
                total = 0;
                break;
                
            // その他の統計も必要に応じて追加可能
        }
        
        return total;
    }
    
    private static int getBlockGroupTotal(ServerPlayerEntity player, String action, String groupName) {
        int total = 0;
        
        // 各グループのブロックリストを定義
        String[] blocks = switch (groupName) {
            case "wool" -> new String[] {
                "white_wool", "orange_wool", "magenta_wool", "light_blue_wool",
                "yellow_wool", "lime_wool", "pink_wool", "gray_wool",
                "light_gray_wool", "cyan_wool", "purple_wool", "blue_wool",
                "brown_wool", "green_wool", "red_wool", "black_wool"
            };
            case "concrete" -> new String[] {
                "white_concrete", "orange_concrete", "magenta_concrete", "light_blue_concrete",
                "yellow_concrete", "lime_concrete", "pink_concrete", "gray_concrete",
                "light_gray_concrete", "cyan_concrete", "purple_concrete", "blue_concrete",
                "brown_concrete", "green_concrete", "red_concrete", "black_concrete"
            };
            case "concrete_powder" -> new String[] {
                "white_concrete_powder", "orange_concrete_powder", "magenta_concrete_powder", "light_blue_concrete_powder",
                "yellow_concrete_powder", "lime_concrete_powder", "pink_concrete_powder", "gray_concrete_powder",
                "light_gray_concrete_powder", "cyan_concrete_powder", "purple_concrete_powder", "blue_concrete_powder",
                "brown_concrete_powder", "green_concrete_powder", "red_concrete_powder", "black_concrete_powder"
            };
            case "terracotta" -> new String[] {
                "terracotta", "white_terracotta", "orange_terracotta", "magenta_terracotta", "light_blue_terracotta",
                "yellow_terracotta", "lime_terracotta", "pink_terracotta", "gray_terracotta",
                "light_gray_terracotta", "cyan_terracotta", "purple_terracotta", "blue_terracotta",
                "brown_terracotta", "green_terracotta", "red_terracotta", "black_terracotta"
            };
            case "glazed_terracotta" -> new String[] {
                "white_glazed_terracotta", "orange_glazed_terracotta", "magenta_glazed_terracotta", "light_blue_glazed_terracotta",
                "yellow_glazed_terracotta", "lime_glazed_terracotta", "pink_glazed_terracotta", "gray_glazed_terracotta",
                "light_gray_glazed_terracotta", "cyan_glazed_terracotta", "purple_glazed_terracotta", "blue_glazed_terracotta",
                "brown_glazed_terracotta", "green_glazed_terracotta", "red_glazed_terracotta", "black_glazed_terracotta"
            };
            case "glass" -> new String[] {
                "glass", "white_stained_glass", "orange_stained_glass", "magenta_stained_glass", "light_blue_stained_glass",
                "yellow_stained_glass", "lime_stained_glass", "pink_stained_glass", "gray_stained_glass",
                "light_gray_stained_glass", "cyan_stained_glass", "purple_stained_glass", "blue_stained_glass",
                "brown_stained_glass", "green_stained_glass", "red_stained_glass", "black_stained_glass",
                "tinted_glass"
            };
            case "glass_pane" -> new String[] {
                "glass_pane", "white_stained_glass_pane", "orange_stained_glass_pane", "magenta_stained_glass_pane", 
                "light_blue_stained_glass_pane", "yellow_stained_glass_pane", "lime_stained_glass_pane", "pink_stained_glass_pane",
                "gray_stained_glass_pane", "light_gray_stained_glass_pane", "cyan_stained_glass_pane", "purple_stained_glass_pane",
                "blue_stained_glass_pane", "brown_stained_glass_pane", "green_stained_glass_pane", "red_stained_glass_pane",
                "black_stained_glass_pane"
            };
            case "coral" -> new String[] {
                "tube_coral", "brain_coral", "bubble_coral", "fire_coral", "horn_coral",
                "tube_coral_block", "brain_coral_block", "bubble_coral_block", "fire_coral_block", "horn_coral_block",
                "dead_tube_coral", "dead_brain_coral", "dead_bubble_coral", "dead_fire_coral", "dead_horn_coral",
                "dead_tube_coral_block", "dead_brain_coral_block", "dead_bubble_coral_block", "dead_fire_coral_block", "dead_horn_coral_block"
            };
            case "bed" -> new String[] {
                "white_bed", "orange_bed", "magenta_bed", "light_blue_bed",
                "yellow_bed", "lime_bed", "pink_bed", "gray_bed",
                "light_gray_bed", "cyan_bed", "purple_bed", "blue_bed",
                "brown_bed", "green_bed", "red_bed", "black_bed"
            };
            case "banner" -> new String[] {
                "white_banner", "orange_banner", "magenta_banner", "light_blue_banner",
                "yellow_banner", "lime_banner", "pink_banner", "gray_banner",
                "light_gray_banner", "cyan_banner", "purple_banner", "blue_banner",
                "brown_banner", "green_banner", "red_banner", "black_banner"
            };
            case "shulker_box" -> new String[] {
                "shulker_box", "white_shulker_box", "orange_shulker_box", "magenta_shulker_box",
                "light_blue_shulker_box", "yellow_shulker_box", "lime_shulker_box", "pink_shulker_box",
                "gray_shulker_box", "light_gray_shulker_box", "cyan_shulker_box", "purple_shulker_box",
                "blue_shulker_box", "brown_shulker_box", "green_shulker_box", "red_shulker_box", "black_shulker_box"
            };
            case "candle" -> new String[] {
                "candle", "white_candle", "orange_candle", "magenta_candle", "light_blue_candle",
                "yellow_candle", "lime_candle", "pink_candle", "gray_candle",
                "light_gray_candle", "cyan_candle", "purple_candle", "blue_candle",
                "brown_candle", "green_candle", "red_candle", "black_candle"
            };
            case "carpet" -> new String[] {
                "white_carpet", "orange_carpet", "magenta_carpet", "light_blue_carpet",
                "yellow_carpet", "lime_carpet", "pink_carpet", "gray_carpet",
                "light_gray_carpet", "cyan_carpet", "purple_carpet", "blue_carpet",
                "brown_carpet", "green_carpet", "red_carpet", "black_carpet", "moss_carpet"
            };
            case "wood" -> new String[] {
                "oak_log", "spruce_log", "birch_log", "jungle_log", "acacia_log", "dark_oak_log",
                "mangrove_log", "cherry_log", "oak_wood", "spruce_wood", "birch_wood", "jungle_wood",
                "acacia_wood", "dark_oak_wood", "mangrove_wood", "cherry_wood",
                "stripped_oak_log", "stripped_spruce_log", "stripped_birch_log", "stripped_jungle_log",
                "stripped_acacia_log", "stripped_dark_oak_log", "stripped_mangrove_log", "stripped_cherry_log",
                "stripped_oak_wood", "stripped_spruce_wood", "stripped_birch_wood", "stripped_jungle_wood",
                "stripped_acacia_wood", "stripped_dark_oak_wood", "stripped_mangrove_wood", "stripped_cherry_wood"
            };
            case "planks" -> new String[] {
                "oak_planks", "spruce_planks", "birch_planks", "jungle_planks",
                "acacia_planks", "dark_oak_planks", "mangrove_planks", "cherry_planks",
                "bamboo_planks", "crimson_planks", "warped_planks"
            };
            case "ore" -> new String[] {
                "coal_ore", "deepslate_coal_ore", "iron_ore", "deepslate_iron_ore",
                "copper_ore", "deepslate_copper_ore", "gold_ore", "deepslate_gold_ore",
                "redstone_ore", "deepslate_redstone_ore", "emerald_ore", "deepslate_emerald_ore",
                "lapis_ore", "deepslate_lapis_ore", "diamond_ore", "deepslate_diamond_ore",
                "nether_gold_ore", "nether_quartz_ore", "ancient_debris"
            };
            case "deepslate_ore" -> new String[] {
                "deepslate_coal_ore", "deepslate_iron_ore", "deepslate_copper_ore", 
                "deepslate_gold_ore", "deepslate_redstone_ore", "deepslate_emerald_ore",
                "deepslate_lapis_ore", "deepslate_diamond_ore"
            };
            case "coral_block" -> new String[] {
                "tube_coral_block", "brain_coral_block", "bubble_coral_block", 
                "fire_coral_block", "horn_coral_block",
                "dead_tube_coral_block", "dead_brain_coral_block", "dead_bubble_coral_block", 
                "dead_fire_coral_block", "dead_horn_coral_block"
            };
            case "log" -> new String[] {
                "oak_log", "spruce_log", "birch_log", "jungle_log", "acacia_log", "dark_oak_log",
                "mangrove_log", "cherry_log", "crimson_stem", "warped_stem",
                "stripped_oak_log", "stripped_spruce_log", "stripped_birch_log", "stripped_jungle_log",
                "stripped_acacia_log", "stripped_dark_oak_log", "stripped_mangrove_log", "stripped_cherry_log",
                "stripped_crimson_stem", "stripped_warped_stem"
            };
            case "leaves" -> new String[] {
                "oak_leaves", "spruce_leaves", "birch_leaves", "jungle_leaves", "acacia_leaves", 
                "dark_oak_leaves", "mangrove_leaves", "cherry_leaves", "azalea_leaves", "flowering_azalea_leaves"
            };
            case "sapling" -> new String[] {
                "oak_sapling", "spruce_sapling", "birch_sapling", "jungle_sapling", "acacia_sapling",
                "dark_oak_sapling", "mangrove_propagule", "cherry_sapling", "azalea", "flowering_azalea"
            };
            case "flower" -> new String[] {
                "dandelion", "poppy", "blue_orchid", "allium", "azure_bluet", "red_tulip",
                "orange_tulip", "white_tulip", "pink_tulip", "oxeye_daisy", "cornflower",
                "lily_of_the_valley", "wither_rose", "sunflower", "lilac", "rose_bush", "peony",
                "torchflower", "pitcher_plant", "spore_blossom", "pink_petals"
            };
            default -> new String[0];
        };
        
        // 各ブロックの統計を合計
        try {
            if (action.equals("placed")) {
                // ブロック設置の場合はアイテムの使用統計を使用
                for (String blockName : blocks) {
                    Item item = Registries.ITEM.get(new Identifier("minecraft", blockName));
                    if (item != null && item != Items.AIR && item instanceof BlockItem) {
                        total += player.getStatHandler().getStat(Stats.USED.getOrCreateStat(item));
                    }
                }
            } else if (action.equals("mined")) {
                // ブロック破壊の場合はブロックの採掘統計を使用
                for (String blockName : blocks) {
                    Block block = Registries.BLOCK.get(new Identifier("minecraft", blockName));
                    if (block != null && block != Blocks.AIR) {
                        total += player.getStatHandler().getStat(Stats.MINED.getOrCreateStat(block));
                    }
                }
            }
        } catch (Exception e) {
            ServerScoreboardLogger.error("Failed to get block group stats for " + groupName + " (" + action + ")", e);
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
            case "killed":
            case "deaths":
            case "damage_dealt":
            case "damage_taken":
            case "play_time":
            case "walk_one_cm":
            case "jump":
            case "fish_caught":
            case "craft_item":
            case "crafted":
            case "deepslate_ore_mined":
            case "coral_placed":
            case "coral_mined":
            case "coral_block_placed":
            case "coral_block_mined":
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
}