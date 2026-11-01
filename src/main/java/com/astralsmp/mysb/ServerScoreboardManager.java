package com.astralsmp.mysb;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.StringTag;
import net.minecraft.network.protocol.game.ClientboundResetScorePacket;
import net.minecraft.network.protocol.game.ClientboundSetScorePacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.ServerScoreboard;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.scores.DisplaySlot;
import net.minecraft.world.scores.Objective;
import net.minecraft.world.scores.PlayerScoreEntry;
import net.minecraft.world.scores.Scoreboard;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Set;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Objects;

public class ServerScoreboardManager {
    private static final Map<UUID, PlayerScoreboardData> playerData = new ConcurrentHashMap<>();
    private static final Map<String, Set<UUID>> objectiveWatchers = new ConcurrentHashMap<>();
    private static final Map<UUID, CustomScoreboardData> customScoreboardData = new ConcurrentHashMap<>();
    private static final Map<UUID, ScoreboardTransformData> transformData = new ConcurrentHashMap<>();
    private static final Set<UUID> playersToUpdate = new HashSet<>();
    // スコアボードキャッシュ: プレイヤーUUID -> オブジェクティブ名 -> プレイヤー名 -> 直近送信したスコアエントリ
    private static final Map<UUID, Map<String, Map<String, PlayerScoreEntry>>> playerScoreboardCache = new ConcurrentHashMap<>();
    // プレイヤー毎の現在表示中のオブジェクティブを追跡
    private static final Map<UUID, String> playerActiveObjectives = new ConcurrentHashMap<>();
    // スコアボード表示保持システム
    private static final Map<UUID, Long> lastScoreboardDisplay = new ConcurrentHashMap<>();
    private static final long SCOREBOARD_REFRESH_INTERVAL = 5000; // 5秒ごとにリフレッシュ
    public static MinecraftServer server;
    private static int tickCounter = 0;

    public static void loadScoreboardData(MinecraftServer minecraftServer) {
        server = minecraftServer;
        playerData.clear();
        customScoreboardData.clear();
        transformData.clear();
        
        // 自動変換設定を初期化
        ScoreboardAutoTransform.init(server);

        // configディレクトリの確認と作成
        Path configDir = getConfigDirectory();
        try {
            Files.createDirectories(configDir);
        } catch (IOException e) {
            ServerScoreboardLogger.error("Failed to create config directory", e);
        }

        // プレイヤーデータの読み込み
        File playerDataFile = configDir.resolve("player_scoreboards.dat").toFile();
        if (playerDataFile.exists()) {
            try {
                CompoundTag nbt = NbtIo.readCompressed(playerDataFile.toPath(), NbtAccounter.unlimitedHeap());
                loadPlayerData(nbt);
            } catch (IOException e) {
                ServerScoreboardLogger.error("Failed to load player scoreboard data", e);
            }
        } else {
        }
        
        // TotalStatsManager設定の読み込み
        loadTotalStatsConfig(configDir);

        // scoreboard.datからオブジェクティブ情報を取得
        try {
            File scoreboardFile = server.getWorldPath(LevelResource.ROOT)
                    .resolve("data/minecraft/scoreboard.dat").toFile();
            if (scoreboardFile.exists()) {
                CompoundTag nbt = NbtIo.readCompressed(scoreboardFile.toPath(), NbtAccounter.unlimitedHeap());
                parseScoreboardData(nbt);
            }
        } catch (IOException e) {
            ServerScoreboardLogger.error("Failed to load vanilla scoreboard data", e);
        }
    }

    private static Path getConfigDirectory() {
        return server.getWorldPath(LevelResource.ROOT).resolve("config/mysb");
    }

    private static void parseScoreboardData(CompoundTag nbt) {
        // scoreboard.datの構造に基づいてデータを解析
        if (nbt.contains("data")) {
            CompoundTag data = nbt.getCompoundOrEmpty("data");
            // Objectives情報をキャッシュに保存（GUI表示用）
            if (data.contains("Objectives")) {
                ListTag objectives = data.getListOrEmpty("Objectives");
                // 必要に応じて処理
            }
        }
    }

    private static void loadPlayerData(CompoundTag nbt) {
        if (nbt.contains("players")) {
            CompoundTag players = nbt.getCompoundOrEmpty("players");
            for (String uuidStr : players.keySet()) {
                try {
                    UUID playerId = UUID.fromString(uuidStr);
                    CompoundTag playerNbt = players.getCompoundOrEmpty(uuidStr);
                    PlayerScoreboardData data = new PlayerScoreboardData();
                    data.fromNbt(playerNbt);
                    playerData.put(playerId, data);
                } catch (Exception e) {
                    ServerScoreboardLogger.error("Failed to load player data for UUID: " + uuidStr, e);
                }
            }
        }
        
        // カスタムスコアボードデータを読み込み
        if (nbt.contains("customScoreboards")) {
            CompoundTag customScoreboards = nbt.getCompoundOrEmpty("customScoreboards");
            for (String uuidStr : customScoreboards.keySet()) {
                try {
                    UUID playerId = UUID.fromString(uuidStr);
                    CompoundTag customNbt = customScoreboards.getCompoundOrEmpty(uuidStr);
                    CustomScoreboardData customData = new CustomScoreboardData(playerId);
                    customData.fromNbt(customNbt);
                    customScoreboardData.put(playerId, customData);
                } catch (Exception e) {
                    ServerScoreboardLogger.error("Failed to load custom scoreboard data for UUID: " + uuidStr, e);
                }
            }
        }
        
        // 変換データを読み込み
        if (nbt.contains("transformData")) {
            CompoundTag transforms = nbt.getCompoundOrEmpty("transformData");
            for (String uuidStr : transforms.keySet()) {
                try {
                    UUID playerId = UUID.fromString(uuidStr);
                    CompoundTag transformNbt = transforms.getCompoundOrEmpty(uuidStr);
                    ScoreboardTransformData transform = new ScoreboardTransformData(playerId);
                    transform.fromNbt(transformNbt);
                    transformData.put(playerId, transform);
                } catch (Exception e) {
                    ServerScoreboardLogger.error("Failed to load transform data for UUID: " + uuidStr, e);
                }
            }
        }
    }

    public static void saveScoreboardData(MinecraftServer server) {
        Path configDir = getConfigDirectory();
        File playerDataFile = configDir.resolve("player_scoreboards.dat").toFile();

        CompoundTag nbt = new CompoundTag();
        CompoundTag players = new CompoundTag();

        for (Map.Entry<UUID, PlayerScoreboardData> entry : playerData.entrySet()) {
            players.put(entry.getKey().toString(), entry.getValue().toNbt());
        }

        nbt.put("players", players);
        
        // カスタムスコアボードデータを保存
        CompoundTag customScoreboards = new CompoundTag();
        for (Map.Entry<UUID, CustomScoreboardData> entry : customScoreboardData.entrySet()) {
            customScoreboards.put(entry.getKey().toString(), entry.getValue().toNbt());
        }
        nbt.put("customScoreboards", customScoreboards);
        
        // 変換データを保存
        CompoundTag transforms = new CompoundTag();
        for (Map.Entry<UUID, ScoreboardTransformData> entry : transformData.entrySet()) {
            transforms.put(entry.getKey().toString(), entry.getValue().toNbt());
        }
        nbt.put("transformData", transforms);

        try {
            NbtIo.writeCompressed(nbt, playerDataFile.toPath());
        } catch (IOException e) {
            ServerScoreboardLogger.error("Failed to save player scoreboard data", e);
        }
        
        // TotalStatsManager設定の保存
        saveTotalStatsConfig(configDir);
    }

    public static void setClientDisplayObjective(UUID playerId, String objectiveName) {
        if (objectiveName == null || objectiveName.trim().isEmpty()) {
            ServerScoreboardLogger.warn("Attempted to set empty objective name for player " + playerId);
            return;
        }
        
        // セキュリティチェック
        if (!ServerScoreboardConfig.isValidObjectiveName(objectiveName)) {
            ServerScoreboardLogger.warn("Invalid objective name '" + objectiveName + "' for player " + playerId);
            return;
        }
        
        PlayerScoreboardData data = getOrCreatePlayerData(playerId);
        
        // 既に同じオブジェクティブが設定されている場合はスキップ
        if (objectiveName.equals(data.getDisplayObjective())) {
            return;
        }
        
        // オブジェクティブ数制限チェック
        if (data.getObjectiveCount() >= ServerScoreboardConfig.MAX_OBJECTIVES_PER_PLAYER) {
            ServerScoreboardLogger.warn("Player " + playerId + " has reached maximum objective limit");
            return;
        }
        
        data.setDisplayObjective(objectiveName);

        ServerPlayer player = server.getPlayerList().getPlayer(playerId);
        if (player != null) {
            updatePlayerScoreboard(player, data);
        }
    }

    public static void updateClientScore(UUID playerId, String objectiveName, int score) {
        // セキュリティチェック
        if (!ServerScoreboardConfig.isValidObjectiveName(objectiveName)) {
            ServerScoreboardLogger.warn("Invalid objective name '" + objectiveName + "' for score update");
            return;
        }
        
        if (!ServerScoreboardConfig.isValidScore(score)) {
            ServerScoreboardLogger.warn("Invalid score value " + score + " for player " + playerId);
            return;
        }
        
        PlayerScoreboardData data = getOrCreatePlayerData(playerId);
        
        // スコア数制限チェック
        if (data.getScoreCount() >= ServerScoreboardConfig.MAX_SCORES_PER_OBJECTIVE) {
            ServerScoreboardLogger.warn("Player " + playerId + " has reached maximum score limit");
            return;
        }
        
        data.setScore(objectiveName, score);

        ServerPlayer player = server.getPlayerList().getPlayer(playerId);
        if (player != null) {
            updatePlayerScoreboard(player, data);
        }
    }
    
    public static void updateCustomScore(UUID playerId, String scoreName, int score) {
        CustomScoreboardData data = customScoreboardData.get(playerId);
        if (data != null && data.isEnabled()) {
            data.setCustomScore(scoreName, score);
            
            ServerPlayer player = server.getPlayerList().getPlayer(playerId);
            if (player != null) {
                // カスタムスコアボードを再送信
                CustomScoreboardPacketSender.sendCustomScoreboard(player, data);
            }
        }
    }
    
    public static PlayerScoreboardData getOrCreatePlayerData(UUID playerId) {
        return playerData.computeIfAbsent(playerId, k -> new PlayerScoreboardData());
    }
    
    public static PlayerScoreboardData getPlayerData(UUID playerId) {
        return playerData.get(playerId);
    }

    private static void updatePlayerScoreboard(ServerPlayer player, PlayerScoreboardData data) {
        String objectiveName = data.getDisplayObjective();
        
        if (objectiveName.isEmpty()) {
            // 表示をクリア
            sendScoreboardDisplayPacketOnly(player, null);
            return;
        }
        
        // 通常のスコアボード表示（パケットベース）
        Scoreboard scoreboard = server.getScoreboard();
        Objective objective = scoreboard.getObjective(objectiveName);
        
        if (objective != null) {
            // 前のオブジェクティブの監視を停止
            PlayerScoreboardData oldData = playerData.get(player.getUUID());
            if (oldData != null && !oldData.getDisplayObjective().isEmpty()) {
                removeObjectiveWatcher(player.getUUID(), oldData.getDisplayObjective());
            }
            
            // 新しいオブジェクティブの監視を開始
            addObjectiveWatcher(player.getUUID(), objectiveName);
            
            // 強制的にスコアボードを再同期
            syncScoreboardToPlayer(player, objective);
        } else {
            ServerScoreboardLogger.warn("Objective '" + objectiveName + "' not found for player " + player.getName().getString());
            sendScoreboardDisplayPacketOnly(player, null);
        }
    }
    
    private static void syncScoreboardToPlayer(ServerPlayer player, Objective objective) {
        // より簡単で信頼性の高い方法：直接元のオブジェクティブを使用
        sendScoreboardDisplayPacketOnly(player, objective);
    }
    
    
    private static void sendScoreboardDisplayPacketOnly(ServerPlayer player, Objective objective) {
        try {
            if (objective != null) {
                UUID playerId = player.getUUID();
                String objectiveName = objective.getName();
                
                // 永続表示システム：常にスコアボードを表示保持
                String currentActive = playerActiveObjectives.get(playerId);
                boolean needsInitialization = !objectiveName.equals(currentActive);
                
                // プレイヤーの現在のアクティブオブジェクティブを更新
                playerActiveObjectives.put(playerId, objectiveName);
                
                if (needsInitialization) {
                    // 初回またはオブジェクト切り替え時の永続表示設定
                    
                    // 1. オブジェクト作成
                    player.connection.send(new net.minecraft.network.protocol.game.ClientboundSetObjectivePacket(objective, 0));
                    
                    // 2. 永続的にサイドバー表示（消えないように）
                    player.connection.send(new net.minecraft.network.protocol.game.ClientboundSetDisplayObjectivePacket(DisplaySlot.SIDEBAR, objective));
                    
                    // 3. キャッシュをクリアして初期同期
                    Map<String, Map<String, PlayerScoreEntry>> playerCache = playerScoreboardCache.computeIfAbsent(playerId, k -> new ConcurrentHashMap<>());
                    playerCache.remove(objectiveName);
                    
                    // 4. 全スコアを送信（永続表示の基盤）
                    sendMinimalScoreUpdate(player, objective, true);
                }
                
                // 永続表示保持システム：定期的に表示を確認・維持
                maintainPersistentDisplay(player, objective);
            } else {
                // パケットのみでスコアボードをクリア
                UUID playerId = player.getUUID();
                playerActiveObjectives.remove(playerId); // アクティブオブジェクティブをクリア
                player.connection.send(new net.minecraft.network.protocol.game.ClientboundSetDisplayObjectivePacket(DisplaySlot.SIDEBAR, null));
            }
        } catch (Exception e) {
            ServerScoreboardLogger.error("Failed to send scoreboard display packet to player " + player.getName().getString(), e);
        }
    }
    
    
    private static void clearCustomObjectiveForPlayer(ServerPlayer player) {
        try {
            PlayerScoreboardData data = playerData.get(player.getUUID());
            if (data != null) {
                data.setDisplayObjective("");
            }
        } catch (Exception e) {
            ServerScoreboardLogger.error("Failed to clear custom objective for player " + player.getName().getString(), e);
        }
    }
    


    public static void onPlayerJoin(ServerPlayer player) {
        UUID playerId = player.getUUID();
        
        PlayerScoreboardData data = playerData.get(playerId);
        CustomScoreboardData customData = customScoreboardData.get(playerId);
        
        // プレイヤー個別のスコアボード初期化
        try {
            // カスタムスコアボードがある場合は優先的に表示
            if (customData != null && customData.isEnabled()) {
                server.execute(() -> {
                    CustomScoreboardPacketSender.sendCustomScoreboard(player, customData);
                });
            } else if (data != null && data.isEnabled() && !data.getDisplayObjective().isEmpty()) {
                // 保存されている設定を適用
                // 少し遅延を入れてクライアントの初期化を待つ
                server.execute(() -> {
                    try {
                        Thread.sleep(100); // 100ms待機
                    } catch (InterruptedException e) {
                        // 無視
                    }
                    updatePlayerScoreboard(player, data);
                });
            } else {
                // 設定がない場合は、サーバーのデフォルトをそのまま使用
            }
        } catch (Exception e) {
            ServerScoreboardLogger.error("Failed to initialize scoreboard for player " + player.getName().getString(), e);
        }
    }

    public static void onPlayerDisconnect(ServerPlayer player) {
        // プレイヤー切断時のクリーンアップ
        UUID playerId = player.getUUID();
        String playerName = player.getName().getString();

        // プレイヤーの統計をキャッシュに保存
        if (!TotalStatsManager.isPlayerExcluded(playerName)) {
            Map<String, String> allStats = TotalStatsManager.getAllAvailableStats();
            for (String statId : allStats.keySet()) {
                int statValue = TotalStatsManager.getPlayerStatTotal(player, statId);
                if (statValue > 0) {
                    PlayerStatsCache.updatePlayerStats(playerName, statId, statValue);
                }
            }
        }

        // オブジェクティブの監視を停止
        clearPlayerWatchers(playerId);

        // スコアボードキャッシュをクリア
        clearPlayerScoreboardCache(playerId);

        // アクティブオブジェクティブ情報をクリア
        playerActiveObjectives.remove(playerId);

        // 表示キャッシュのクリーンアップ（設定データはサーバー停止時に保存するため残す）
        lastScoreboardDisplay.remove(playerId);
    }

    // リアルタイム差分更新メソッド（毎tick実行）- 永続表示版
    public static void updateClientScoreboardsDifferential(MinecraftServer server) {
        // 全プレイヤーの表示中スコアボードに対して永続表示 + スコア差分更新
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            UUID playerId = player.getUUID();
            String activeObjective = playerActiveObjectives.get(playerId);
            
            if (activeObjective != null && !activeObjective.isEmpty()) {
                Objective objective = server.getScoreboard().getObjective(activeObjective);
                if (objective != null) {
                    // 永続表示維持 + スコア値のみ更新
                    maintainPersistentDisplay(player, objective);
                    sendMinimalScoreUpdate(player, objective, false);
                }
            }
        }
    }

    public static void updateClientScoreboards(MinecraftServer server) {
        tickCounter++;
        
        // 全プレイヤーのクライアントスコアボードを定期更新
        // パフォーマンスのため、必要なときだけ更新
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            PlayerScoreboardData data = playerData.get(player.getUUID());
            if (data != null && data.isDirty()) {
                playersToUpdate.add(player.getUUID());
                data.setDirty(false);
            }
        }
        
        // スコアボードの変更をチェックして更新
        checkAndUpdateScoreboards();

        // スコアボード表示保持システム（100tick = 5秒ごと）
        if (tickCounter % 100 == 0) {
            maintainScoreboardDisplay();
        }

        // 更新キューの処理頻度を上げる（10tickごと = 0.5秒ごと）
        if (tickCounter % 10 == 0) {
            processScoreboardUpdateQueue();
        }
    }

    public static void processScoreboardUpdateQueue() {
        if (playersToUpdate.isEmpty()) {
            return;
        }


        for (UUID playerId : playersToUpdate) {
            ServerPlayer player = server.getPlayerList().getPlayer(playerId);
            if (player != null) {
                PlayerScoreboardData data = playerData.get(playerId);
                if (data != null) {
                    updatePlayerScoreboard(player, data);
                }
            }
        }
        playersToUpdate.clear();
    }
    
    private static void checkAndUpdateScoreboards() {
        // 各オブジェクティブの変更をチェックして、関連するプレイヤーに更新を送信
        // 特に統計スコアボードを表示しているプレイヤーに対して更新を送信
        updateTotalStatsForWatchers();
        
        // アクティブなオブジェクティブを表示しているプレイヤーのスコアボードを更新
        for (Map.Entry<UUID, String> entry : playerActiveObjectives.entrySet()) {
            UUID playerId = entry.getKey();
            String objectiveName = entry.getValue();
            
            ServerPlayer player = server.getPlayerList().getPlayer(playerId);
            if (player != null) {
                Objective objective = server.getScoreboard().getObjective(objectiveName);
                if (objective != null) {
                    sendScoreboardUpdatePackets(player, objective);
                }
            }
        }
    }
    
    private static void addObjectiveWatcher(UUID playerId, String objectiveName) {
        objectiveWatchers.computeIfAbsent(objectiveName, k -> new HashSet<>()).add(playerId);
    }
    
    private static void removeObjectiveWatcher(UUID playerId, String objectiveName) {
        Set<UUID> watchers = objectiveWatchers.get(objectiveName);
        if (watchers != null) {
            watchers.remove(playerId);
            if (watchers.isEmpty()) {
                objectiveWatchers.remove(objectiveName);
            }
        }
    }
    
    private static void clearPlayerWatchers(UUID playerId) {
        objectiveWatchers.forEach((objectiveName, watchers) -> watchers.remove(playerId));
        objectiveWatchers.entrySet().removeIf(entry -> entry.getValue().isEmpty());
    }

    public static void resetPlayerScoreboard(UUID playerId) {
        PlayerScoreboardData data = getOrCreatePlayerData(playerId);
        // オブジェクティブの監視を停止
        if (!data.getDisplayObjective().isEmpty()) {
            removeObjectiveWatcher(playerId, data.getDisplayObjective());
        }
        
        data.reset();

        ServerPlayer player = server.getPlayerList().getPlayer(playerId);
        if (player != null) {
            // サーバーの現在のデフォルトスコアボードに戻す
            Objective currentDefault = server.getScoreboard().getDisplayObjective(DisplaySlot.SIDEBAR);
            if (currentDefault != null) {
                // サーバーのデフォルトスコアボードを強制的に再送信
                forceServerScoreboardSync(player);
            } else {
                // デフォルトがない場合のみクリア
                sendScoreboardDisplayPacketOnly(player, null);
            }
        }
        
        // カスタムスコアボードもクリア
        CustomScoreboardData customData = customScoreboardData.get(playerId);
        if (customData != null) {
            customData.disable();
        }
        
        // 変換データもクリア
        transformData.remove(playerId);
    }
    
    private static void forceServerScoreboardSync(ServerPlayer player) {
        // サーバーの全てのスコアボード情報を再送信
        ServerScoreboard scoreboard = server.getScoreboard();
        
        // サイドバーのスコアボードを取得
        Objective sidebarObjective = scoreboard.getDisplayObjective(DisplaySlot.SIDEBAR);
        if (sidebarObjective != null) {
            // オブジェクティブを再送信
            player.connection.send(new net.minecraft.network.protocol.game.ClientboundSetObjectivePacket(sidebarObjective, 0));
            
            // 全スコアを再送信
            scoreboard.listPlayerScores(sidebarObjective).forEach(score -> {
                sendScoreUpdatePacket(player, sidebarObjective.getName(), score);
            });
            
            // サイドバーに表示
            player.connection.send(new net.minecraft.network.protocol.game.ClientboundSetDisplayObjectivePacket(DisplaySlot.SIDEBAR, sidebarObjective));
        }
    }
    
    // カスタムスコアボード関連のメソッド
    public static void setCustomScoreboard(UUID playerId, String objectiveName, String displayName) {
        CustomScoreboardData data = customScoreboardData.computeIfAbsent(playerId, CustomScoreboardData::new);
        data.setCustomObjective(objectiveName, displayName);
        
        ServerPlayer player = server.getPlayerList().getPlayer(playerId);
        if (player != null) {
            CustomScoreboardPacketSender.sendCustomScoreboard(player, data);
        }
    }
    
    public static void setCustomScore(UUID playerId, String scoreName, int score) {
        updateCustomScore(playerId, scoreName, score);
    }
    
    public static void removeCustomScore(UUID playerId, String scoreName) {
        CustomScoreboardData data = customScoreboardData.get(playerId);
        if (data != null && data.isEnabled()) {
            data.removeCustomScore(scoreName);
            
            ServerPlayer player = server.getPlayerList().getPlayer(playerId);
            if (player != null) {
                // カスタムスコアボードを再送信
                CustomScoreboardPacketSender.sendCustomScoreboard(player, data);
            }
        }
    }
    
    public static void clearCustomScoreboard(UUID playerId) {
        CustomScoreboardData data = customScoreboardData.get(playerId);
        if (data != null) {
            data.disable();
            
            ServerPlayer player = server.getPlayerList().getPlayer(playerId);
            if (player != null) {
                CustomScoreboardPacketSender.clearCustomScoreboard(player);
            }
        }
    }
    
    public static CustomScoreboardData getCustomScoreboardData(UUID playerId) {
        return customScoreboardData.get(playerId);
    }
    
    // スコアボード変換データ関連のメソッド
    public static ScoreboardTransformData getScoreboardTransformData(UUID playerId) {
        return transformData.get(playerId);
    }
    
    public static void setScoreboardTransform(UUID playerId, String objectiveName, String newDisplayName) {
        ScoreboardTransformData data = transformData.computeIfAbsent(playerId, ScoreboardTransformData::new);
        data.setObjectiveDisplayNameMapping(objectiveName, newDisplayName);
        data.setEnabled(true);
        
        // すぐに変換されたスコアボードを表示
        ServerPlayer player = server.getPlayerList().getPlayer(playerId);
        if (player != null) {
            CustomScoreboardPacketSender.sendTransformedScoreboard(player, objectiveName, data);
        }
    }
    
    
    public static void setScoreValueOffset(UUID playerId, String objectiveName, String scoreName, int offset) {
        ScoreboardTransformData data = transformData.computeIfAbsent(playerId, ScoreboardTransformData::new);
        data.setScoreValueOffset(objectiveName, scoreName, offset);
        data.setEnabled(true);
        
        // 変換を適用
        ServerPlayer player = server.getPlayerList().getPlayer(playerId);
        if (player != null) {
            CustomScoreboardPacketSender.sendTransformedScoreboard(player, objectiveName, data);
        }
    }
    
    public static void clearScoreboardTransforms(UUID playerId) {
        ScoreboardTransformData data = transformData.get(playerId);
        if (data != null) {
            data.clear();
            
            // 変換されたスコアボードをクリア
            ServerPlayer player = server.getPlayerList().getPlayer(playerId);
            if (player != null) {
                CustomScoreboardPacketSender.clearTransformedScoreboard(player);
            }
        }
    }
    
    public static void refreshTransformedScoreboard(UUID playerId, String objectiveName) {
        ScoreboardTransformData data = transformData.get(playerId);
        if (data != null && data.isEnabled()) {
            ServerPlayer player = server.getPlayerList().getPlayer(playerId);
            if (player != null) {
                CustomScoreboardPacketSender.sendTransformedScoreboard(player, objectiveName, data);
            }
        }
    }
    
    // 自動変換関連のメソッド
    public static void checkAndApplyAutoTransforms(ServerPlayer player) {
        if (!ScoreboardAutoTransform.isAutoApplyEnabled()) return; 
        
        // サーバーのスコアボードから現在表示中のオブジェクティブを取得
        server.getScoreboard().getObjectives().forEach(objective -> {
            String objectiveName = objective.getName();
            if (ScoreboardAutoTransform.shouldAutoTransform(objectiveName)) {
                ScoreboardTransformData autoTransformData = ScoreboardAutoTransform.createTransformData(
                    player.getUUID(), objectiveName);
                
                if (autoTransformData != null) {
                    transformData.put(player.getUUID(), autoTransformData);
                    CustomScoreboardPacketSender.sendTransformedScoreboard(player, objectiveName, autoTransformData);
                }
            }
        });
    }
    
    public static void reloadAutoTransforms() {
        ScoreboardAutoTransform.reloadConfig();
        
        // 全プレイヤーに自動変換を再適用
        server.getPlayerList().getPlayers().forEach(player -> {
            checkAndApplyAutoTransforms(player);
        });
        
    }

    public static class PlayerScoreboardData {
        private final Map<String, Integer> scores = new HashMap<>();
        private String displayObjective = "";
        private boolean enabled = true;
        private boolean dirty = false;

        public void setScore(String objective, int score) {
            scores.put(objective, score);
            dirty = true;
        }

        public void setDisplayObjective(String objective) {
            this.displayObjective = objective;
            dirty = true;
        }

        public String getDisplayObjective() {
            return displayObjective;
        }

        public Map<String, Integer> getScores() {
            return new HashMap<>(scores);
        }
        
        public int getObjectiveCount() {
            return scores.size();
        }
        
        public int getScoreCount() {
            return scores.size();
        }
        

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
            dirty = true;
        }

        public boolean isDirty() {
            return dirty;
        }

        public void setDirty(boolean dirty) {
            this.dirty = dirty;
        }

        public void reset() {
            scores.clear();
            displayObjective = "";
            enabled = true;
            dirty = true;
        }

        public CompoundTag toNbt() {
            CompoundTag nbt = new CompoundTag();
            nbt.putString("displayObjective", displayObjective);
            nbt.putBoolean("enabled", enabled);
            
            CompoundTag scoresNbt = new CompoundTag();
            for (Map.Entry<String, Integer> entry : scores.entrySet()) {
                scoresNbt.putInt(entry.getKey(), entry.getValue());
            }
            nbt.put("scores", scoresNbt);
            
            return nbt;
        }

        public void fromNbt(CompoundTag nbt) {
            displayObjective = nbt.getStringOr("displayObjective", "");
            enabled = nbt.getBooleanOr("enabled", false);
            
            scores.clear();
            if (nbt.contains("scores")) {
                CompoundTag scoresNbt = nbt.getCompoundOrEmpty("scores");
                for (String key : scoresNbt.keySet()) {
                    scores.put(key, scoresNbt.getIntOr(key, 0));
                }
            }
        }
    }

    // 統計スコアボードを表示しているすべてのプレイヤーに更新を送信
    public static void updateTotalStatsForWatchers() {
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            PlayerScoreboardData data = playerData.get(player.getUUID());
            if (data != null && data.isEnabled() && !data.getDisplayObjective().isEmpty()) {
                String objective = data.getDisplayObjective();
                if (TotalStatsManager.isTotalObjective(objective)) {
                    playersToUpdate.add(player.getUUID());
                }
            }
        }
    }
    
    // 統計スコアボードの更新パケットを送信（表示設定は変更しない）
    private static void sendScoreboardUpdatePackets(ServerPlayer player, Objective objective) {
        if (objective != null) {
            // プレイヤーがこのオブジェクティブを表示しているか確認
            UUID playerId = player.getUUID();
            String playerActiveObjective = playerActiveObjectives.get(playerId);
            
            if (playerActiveObjective != null && playerActiveObjective.equals(objective.getName())) {
                // 差分スコアデータを送信
                sendDifferentialScoreboardUpdate(player, objective);
            } else {
            }
        }
    }
    
    // 永続表示保持システム（スコアボードが消えないように維持）
    private static void maintainPersistentDisplay(ServerPlayer player, Objective objective) {
        // 毎回表示パケットを送信してスコアボード表示を強制維持
        // これにより他のパケットの影響でスコアボードが消えることを防ぐ
        player.connection.send(new net.minecraft.network.protocol.game.ClientboundSetDisplayObjectivePacket(DisplaySlot.SIDEBAR, objective));
    }

    private static void sendScoreUpdatePacket(ServerPlayer player, String objectiveName, PlayerScoreEntry entry) {
        player.connection.send(new ClientboundSetScorePacket(
            entry.owner(),
            objectiveName,
            entry.value(),
            Optional.ofNullable(entry.display()),
            Optional.ofNullable(entry.numberFormatOverride())
        ));
    }
    
    // 最小限のスコア更新（通信量最適化版）
    private static void sendMinimalScoreUpdate(ServerPlayer player, Objective objective, boolean forceFullSync) {
        UUID playerId = player.getUUID();
        String objectiveName = objective.getName();
        
        // プレイヤーのスコアキャッシュを取得
        Map<String, Map<String, PlayerScoreEntry>> playerCache = playerScoreboardCache.computeIfAbsent(playerId, k -> new ConcurrentHashMap<>());
        Map<String, PlayerScoreEntry> objectiveCache = playerCache.computeIfAbsent(objectiveName, k -> new ConcurrentHashMap<>());
        
        // 現在のスコアを取得
        Collection<PlayerScoreEntry> currentScores = server.getScoreboard().listPlayerScores(objective);
        Set<String> currentPlayerNames = new HashSet<>();
        int packetCount = 0;
        
        // スコア変更のみ送信（バニラと同じ動作）
        for (PlayerScoreEntry score : currentScores) {
            String playerName = score.owner();
            currentPlayerNames.add(playerName);
            
            PlayerScoreEntry cachedEntry = objectiveCache.get(playerName);
            if (forceFullSync || cachedEntry == null || !cachedEntry.equals(score)) {
                // スコア更新パケットのみ（表示パケットは送信しない）
                sendScoreUpdatePacket(player, objectiveName, score);
                objectiveCache.put(playerName, score);
                packetCount++;
            }
        }
        
        // 削除されたスコア
        var iterator = objectiveCache.entrySet().iterator();
        while (iterator.hasNext()) {
            var entry = iterator.next();
            String playerName = entry.getKey();
            if (!currentPlayerNames.contains(playerName)) {
                player.connection.send(new ClientboundResetScorePacket(
                    playerName,
                    objectiveName
                ));
                iterator.remove();
                packetCount++;
            }
        }
        
        // 通信量をデバッグ出力（10パケット以上の場合のみ）
        if (packetCount >= 10) {
        }
    }
    
    // 差分スコアボード更新を送信（パケット数削減）
    private static void sendDifferentialScoreboardUpdate(ServerPlayer player, Objective objective) {
        // パケット制限を撤廃（リアルタイム更新でスムーズな表示を実現）
        UUID playerId = player.getUUID();
        String objectiveName = objective.getName();
        
        // プレイヤーがこのオブジェクティブを表示しているかチェック
        String playerActiveObjective = playerActiveObjectives.get(playerId);
        if (playerActiveObjective == null || !playerActiveObjective.equals(objectiveName)) {
            // このプレイヤーは別のオブジェクティブを表示中、または何も表示していない
            return;
        }
        
        // プレイヤーのスコアキャッシュを取得または作成
        Map<String, Map<String, PlayerScoreEntry>> playerCache = playerScoreboardCache.computeIfAbsent(playerId, k -> new ConcurrentHashMap<>());
        Map<String, PlayerScoreEntry> objectiveCache = playerCache.computeIfAbsent(objectiveName, k -> new ConcurrentHashMap<>());
        
        // 現在のスコアデータを取得
        Collection<PlayerScoreEntry> currentScores = server.getScoreboard().listPlayerScores(objective);
        Set<String> currentPlayerNames = new HashSet<>();
        int updateCount = 0;
        int removeCount = 0;
        
        // 更新または新規追加されたスコアのみを送信
        for (PlayerScoreEntry score : currentScores) {
            String playerName = score.owner();
            currentPlayerNames.add(playerName);
            
            PlayerScoreEntry cachedEntry = objectiveCache.get(playerName);
            if (cachedEntry == null || !cachedEntry.equals(score)) {
                // 変更があった場合のみパケットを送信（制限なし）
                sendScoreUpdatePacket(player, objectiveName, score);
                objectiveCache.put(playerName, score);
                updateCount++;
            }
        }
        
        // 削除されたプレイヤーのスコアを削除
        Set<String> cachedPlayerNames = new HashSet<>(objectiveCache.keySet());
        for (String cachedPlayerName : cachedPlayerNames) {
            if (!currentPlayerNames.contains(cachedPlayerName)) {
                // プレイヤーが削除された場合（制限なし）
                player.connection.send(new ClientboundResetScorePacket(
                    cachedPlayerName,
                    objectiveName
                ));
                objectiveCache.remove(cachedPlayerName);
                removeCount++;
            }
        }
        
        // 不要な表示パケットは削除（スコアパケットのみで十分）
    }
    
    // プレイヤーのスコアボードキャッシュをクリア
    public static void clearPlayerScoreboardCache(UUID playerId) {
        playerScoreboardCache.remove(playerId);
        playerActiveObjectives.remove(playerId);
    }
    
    // 特定のオブジェクティブのキャッシュをクリア
    public static void clearObjectiveCache(String objectiveName) {
        for (Map<String, Map<String, PlayerScoreEntry>> playerCache : playerScoreboardCache.values()) {
            playerCache.remove(objectiveName);
        }
    }
    
    private static void loadTotalStatsConfig(Path configDir) {
        File statsConfigFile = configDir.resolve("total_stats_config.dat").toFile();
        if (statsConfigFile.exists()) {
            try {
                CompoundTag nbt = NbtIo.readCompressed(statsConfigFile.toPath(), NbtAccounter.unlimitedHeap());

                // 有効な統計を読み込み
                if (nbt.contains("enabledStats")) {
                    ListTag enabledList = nbt.getListOrEmpty("enabledStats");
                    for (int i = 0; i < enabledList.size(); i++) {
                        String statId = enabledList.getStringOr(i, "");
                        TotalStatsManager.enableStat(statId);
                    }
                }

                // 除外プレイヤーを読み込み
                if (nbt.contains("excludedPlayers")) {
                    ListTag excludedList = nbt.getListOrEmpty("excludedPlayers");
                    for (int i = 0; i < excludedList.size(); i++) {
                        String playerName = excludedList.getStringOr(i, "");
                        TotalStatsManager.excludePlayer(playerName);
                    }
                }

                // Fake Player スコア表示設定を読み込み（再起動でリセットされないように永続化）
                if (nbt.contains("fakePlayerScoreEnabled")) {
                    ServerScoreboardConfig.FAKE_PLAYER_SCORE_ENABLED = nbt.getBooleanOr("fakePlayerScoreEnabled", false);
                }

                // 既知の Carpet fake player 名を読み込み（オフライン時の名前ベース判定用）
                if (nbt.contains("knownFakePlayerNames")) {
                    ListTag fakeList = nbt.getListOrEmpty("knownFakePlayerNames");
                    for (int i = 0; i < fakeList.size(); i++) {
                        FakePlayerDetector.addKnownFakePlayerName(fakeList.getStringOr(i, ""));
                    }
                }

            } catch (IOException e) {
                ServerScoreboardLogger.error("Failed to load total stats config", e);
            }
        } else {
        }
    }
    
    private static void saveTotalStatsConfig(Path configDir) {
        File statsConfigFile = configDir.resolve("total_stats_config.dat").toFile();

        CompoundTag nbt = new CompoundTag();

        // 有効な統計を保存
        ListTag enabledList = new ListTag();
        for (String statId : TotalStatsManager.getEnabledStats()) {
            enabledList.add(StringTag.valueOf(statId));
        }
        nbt.put("enabledStats", enabledList);

        // 除外プレイヤーを保存
        ListTag excludedList = new ListTag();
        for (String playerName : TotalStatsManager.getExcludedPlayers()) {
            excludedList.add(StringTag.valueOf(playerName));
        }
        nbt.put("excludedPlayers", excludedList);

        // Fake Player スコア表示設定を保存（再起動後も維持）
        nbt.putBoolean("fakePlayerScoreEnabled", ServerScoreboardConfig.FAKE_PLAYER_SCORE_ENABLED);

        // 既知の Carpet fake player 名を保存
        ListTag fakeList = new ListTag();
        for (String name : FakePlayerDetector.getKnownFakePlayerNames()) {
            fakeList.add(StringTag.valueOf(name));
        }
        nbt.put("knownFakePlayerNames", fakeList);

        try {
            NbtIo.writeCompressed(nbt, statsConfigFile.toPath());
        } catch (IOException e) {
            ServerScoreboardLogger.error("Failed to save total stats config", e);
        }
    }
    
    // スコアボード表示保持システム
    private static void maintainScoreboardDisplay() {
        long currentTime = System.currentTimeMillis();
        
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            UUID playerId = player.getUUID();
            String activeObjective = playerActiveObjectives.get(playerId);
            
            if (activeObjective != null && !activeObjective.isEmpty()) {
                Long lastDisplayTime = lastScoreboardDisplay.get(playerId);
                
                // 表示から5秒経過したか、初回表示の場合はリフレッシュ
                if (lastDisplayTime == null || currentTime - lastDisplayTime > SCOREBOARD_REFRESH_INTERVAL) {
                    refreshScoreboardDisplay(player, activeObjective);
                    lastScoreboardDisplay.put(playerId, currentTime);
                }
            }
        }
    }
    
    // スコアボード表示をリフレッシュ（消失防止）
    private static void refreshScoreboardDisplay(ServerPlayer player, String objectiveName) {
        try {
            ServerScoreboard scoreboard = server.getScoreboard();
            Objective objective = scoreboard.getObjective(objectiveName);
            
            if (objective != null) {
                // サイドバー表示を再送信
                player.connection.send(new net.minecraft.network.protocol.game.ClientboundSetDisplayObjectivePacket(DisplaySlot.SIDEBAR, objective));
            } else {
                // オブジェクトが存在しない場合は再設定を試行
                PlayerScoreboardData data = playerData.get(player.getUUID());
                if (data != null && !data.getDisplayObjective().isEmpty()) {
                    setClientDisplayObjective(player.getUUID(), data.getDisplayObjective());
                }
            }
        } catch (Exception e) {
            ServerScoreboardLogger.error("Failed to refresh scoreboard display for " + player.getName().getString(), e);
        }
    }
}