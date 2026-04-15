package com.astralsmp.mysb;

import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtList;
import net.minecraft.nbt.NbtSizeTracker;
import net.minecraft.nbt.NbtString;
import net.minecraft.network.packet.s2c.play.ScoreboardScoreResetS2CPacket;
import net.minecraft.network.packet.s2c.play.ScoreboardScoreUpdateS2CPacket;
import net.minecraft.scoreboard.Scoreboard;
import net.minecraft.scoreboard.ScoreboardCriterion;
import net.minecraft.scoreboard.ScoreboardDisplaySlot;
import net.minecraft.scoreboard.ScoreboardEntry;
import net.minecraft.scoreboard.ScoreboardObjective;
import net.minecraft.scoreboard.ServerScoreboard;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.WorldSavePath;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.HashMap;
import java.util.Optional;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Set;
import java.util.HashSet;
import java.util.Objects;

public class ServerScoreboardManager {
    private static final Map<UUID, PlayerScoreboardData> playerData = new ConcurrentHashMap<>();
    private static final Map<String, Set<UUID>> objectiveWatchers = new ConcurrentHashMap<>();
    private static final Map<UUID, CustomScoreboardData> customScoreboardData = new ConcurrentHashMap<>();
    private static final Map<UUID, ScoreboardTransformData> transformData = new ConcurrentHashMap<>();
    private static final Set<UUID> playersToUpdate = new HashSet<>();
    // スコアボードキャッシュ: プレイヤーUUID -> オブジェクティブ名 -> プレイヤー名 -> スコア値
    private static final Map<UUID, Map<String, Map<String, Integer>>> playerScoreboardCache = new ConcurrentHashMap<>();
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
                NbtCompound nbt = NbtIo.readCompressed(playerDataFile.toPath(), NbtSizeTracker.ofUnlimitedBytes());
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
            File scoreboardFile = server.getSavePath(WorldSavePath.ROOT)
                    .resolve("data/scoreboard.dat").toFile();
            if (scoreboardFile.exists()) {
                NbtCompound nbt = NbtIo.readCompressed(scoreboardFile.toPath(), NbtSizeTracker.ofUnlimitedBytes());
                parseScoreboardData(nbt);
            }
        } catch (IOException e) {
            ServerScoreboardLogger.error("Failed to load vanilla scoreboard data", e);
        }
    }

    private static Path getConfigDirectory() {
        return server.getSavePath(WorldSavePath.ROOT).resolve("config/mysb");
    }

    private static void parseScoreboardData(NbtCompound nbt) {
        // scoreboard.datの構造に基づいてデータを解析
        if (nbt.contains("data")) {
            NbtCompound data = nbt.getCompound("data");
            // Objectives情報をキャッシュに保存（GUI表示用）
            if (data.contains("Objectives")) {
                NbtList objectives = data.getList("Objectives", 10); // 10 = Compound
                // 必要に応じて処理
            }
        }
    }

    private static void loadPlayerData(NbtCompound nbt) {
        if (nbt.contains("players")) {
            NbtCompound players = nbt.getCompound("players");
            for (String uuidStr : players.getKeys()) {
                try {
                    UUID playerId = UUID.fromString(uuidStr);
                    NbtCompound playerNbt = players.getCompound(uuidStr);
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
            NbtCompound customScoreboards = nbt.getCompound("customScoreboards");
            for (String uuidStr : customScoreboards.getKeys()) {
                try {
                    UUID playerId = UUID.fromString(uuidStr);
                    NbtCompound customNbt = customScoreboards.getCompound(uuidStr);
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
            NbtCompound transforms = nbt.getCompound("transformData");
            for (String uuidStr : transforms.getKeys()) {
                try {
                    UUID playerId = UUID.fromString(uuidStr);
                    NbtCompound transformNbt = transforms.getCompound(uuidStr);
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

        NbtCompound nbt = new NbtCompound();
        NbtCompound players = new NbtCompound();

        for (Map.Entry<UUID, PlayerScoreboardData> entry : playerData.entrySet()) {
            players.put(entry.getKey().toString(), entry.getValue().toNbt());
        }

        nbt.put("players", players);
        
        // カスタムスコアボードデータを保存
        NbtCompound customScoreboards = new NbtCompound();
        for (Map.Entry<UUID, CustomScoreboardData> entry : customScoreboardData.entrySet()) {
            customScoreboards.put(entry.getKey().toString(), entry.getValue().toNbt());
        }
        nbt.put("customScoreboards", customScoreboards);
        
        // 変換データを保存
        NbtCompound transforms = new NbtCompound();
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

        ServerPlayerEntity player = server.getPlayerManager().getPlayer(playerId);
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

        ServerPlayerEntity player = server.getPlayerManager().getPlayer(playerId);
        if (player != null) {
            updatePlayerScoreboard(player, data);
        }
    }
    
    public static void updateCustomScore(UUID playerId, String scoreName, int score) {
        CustomScoreboardData data = customScoreboardData.get(playerId);
        if (data != null && data.isEnabled()) {
            data.setCustomScore(scoreName, score);
            
            ServerPlayerEntity player = server.getPlayerManager().getPlayer(playerId);
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

    private static void updatePlayerScoreboard(ServerPlayerEntity player, PlayerScoreboardData data) {
        String objectiveName = data.getDisplayObjective();
        
        if (objectiveName.isEmpty()) {
            // 表示をクリア
            sendScoreboardDisplayPacketOnly(player, null);
            return;
        }
        
        // 通常のスコアボード表示（パケットベース）
        Scoreboard scoreboard = server.getScoreboard();
        ScoreboardObjective objective = scoreboard.getNullableObjective(objectiveName);
        
        if (objective != null) {
            // 前のオブジェクティブの監視を停止
            PlayerScoreboardData oldData = playerData.get(player.getUuid());
            if (oldData != null && !oldData.getDisplayObjective().isEmpty()) {
                removeObjectiveWatcher(player.getUuid(), oldData.getDisplayObjective());
            }
            
            // 新しいオブジェクティブの監視を開始
            addObjectiveWatcher(player.getUuid(), objectiveName);
            
            // 強制的にスコアボードを再同期
            syncScoreboardToPlayer(player, objective);
        } else {
            ServerScoreboardLogger.warn("Objective '" + objectiveName + "' not found for player " + player.getName().getString());
            sendScoreboardDisplayPacketOnly(player, null);
        }
    }
    
    private static void syncScoreboardToPlayer(ServerPlayerEntity player, ScoreboardObjective objective) {
        // より簡単で信頼性の高い方法：直接元のオブジェクティブを使用
        sendScoreboardDisplayPacketOnly(player, objective);
    }
    
    
    private static void sendScoreboardDisplayPacketOnly(ServerPlayerEntity player, ScoreboardObjective objective) {
        try {
            if (objective != null) {
                UUID playerId = player.getUuid();
                String objectiveName = objective.getName();
                
                // 永続表示システム：常にスコアボードを表示保持
                String currentActive = playerActiveObjectives.get(playerId);
                boolean needsInitialization = !objectiveName.equals(currentActive);
                
                // プレイヤーの現在のアクティブオブジェクティブを更新
                playerActiveObjectives.put(playerId, objectiveName);
                
                if (needsInitialization) {
                    // 初回またはオブジェクト切り替え時の永続表示設定
                    
                    // 1. オブジェクト作成
                    player.networkHandler.sendPacket(new net.minecraft.network.packet.s2c.play.ScoreboardObjectiveUpdateS2CPacket(objective, 0));
                    
                    // 2. 永続的にサイドバー表示（消えないように）
                    player.networkHandler.sendPacket(new net.minecraft.network.packet.s2c.play.ScoreboardDisplayS2CPacket(ScoreboardDisplaySlot.SIDEBAR, objective));
                    
                    // 3. キャッシュをクリアして初期同期
                    Map<String, Map<String, Integer>> playerCache = playerScoreboardCache.computeIfAbsent(playerId, k -> new ConcurrentHashMap<>());
                    playerCache.remove(objectiveName);
                    
                    // 4. 全スコアを送信（永続表示の基盤）
                    sendMinimalScoreUpdate(player, objective, true);
                }
                
                // 永続表示保持システム：定期的に表示を確認・維持
                maintainPersistentDisplay(player, objective);
            } else {
                // パケットのみでスコアボードをクリア
                UUID playerId = player.getUuid();
                playerActiveObjectives.remove(playerId); // アクティブオブジェクティブをクリア
                player.networkHandler.sendPacket(new net.minecraft.network.packet.s2c.play.ScoreboardDisplayS2CPacket(ScoreboardDisplaySlot.SIDEBAR, null));
            }
        } catch (Exception e) {
            ServerScoreboardLogger.error("Failed to send scoreboard display packet to player " + player.getName().getString(), e);
        }
    }
    
    
    private static void clearCustomObjectiveForPlayer(ServerPlayerEntity player) {
        try {
            PlayerScoreboardData data = playerData.get(player.getUuid());
            if (data != null) {
                data.setDisplayObjective("");
            }
        } catch (Exception e) {
            ServerScoreboardLogger.error("Failed to clear custom objective for player " + player.getName().getString(), e);
        }
    }
    


    public static void onPlayerJoin(ServerPlayerEntity player) {
        UUID playerId = player.getUuid();
        
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

    public static void onPlayerDisconnect(ServerPlayerEntity player) {
        // プレイヤー切断時のクリーンアップ
        UUID playerId = player.getUuid();
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
        for (ServerPlayerEntity player : server.getPlayerManager().getPlayerList()) {
            UUID playerId = player.getUuid();
            String activeObjective = playerActiveObjectives.get(playerId);
            
            if (activeObjective != null && !activeObjective.isEmpty()) {
                ScoreboardObjective objective = server.getScoreboard().getNullableObjective(activeObjective);
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
        for (ServerPlayerEntity player : server.getPlayerManager().getPlayerList()) {
            PlayerScoreboardData data = playerData.get(player.getUuid());
            if (data != null && data.isDirty()) {
                playersToUpdate.add(player.getUuid());
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
            ServerPlayerEntity player = server.getPlayerManager().getPlayer(playerId);
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
            
            ServerPlayerEntity player = server.getPlayerManager().getPlayer(playerId);
            if (player != null) {
                ScoreboardObjective objective = server.getScoreboard().getNullableObjective(objectiveName);
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

        ServerPlayerEntity player = server.getPlayerManager().getPlayer(playerId);
        if (player != null) {
            // サーバーの現在のデフォルトスコアボードに戻す
            ScoreboardObjective currentDefault = server.getScoreboard().getObjectiveForSlot(ScoreboardDisplaySlot.SIDEBAR);
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
    
    private static void forceServerScoreboardSync(ServerPlayerEntity player) {
        // サーバーの全てのスコアボード情報を再送信
        ServerScoreboard scoreboard = server.getScoreboard();
        
        // サイドバーのスコアボードを取得
        ScoreboardObjective sidebarObjective = scoreboard.getObjectiveForSlot(ScoreboardDisplaySlot.SIDEBAR);
        if (sidebarObjective != null) {
            // オブジェクティブを再送信
            player.networkHandler.sendPacket(new net.minecraft.network.packet.s2c.play.ScoreboardObjectiveUpdateS2CPacket(sidebarObjective, 0));
            
            // 全スコアを再送信
            scoreboard.getScoreboardEntries(sidebarObjective).forEach(score -> {
                player.networkHandler.sendPacket(new ScoreboardScoreUpdateS2CPacket(
                    score.owner(),
                    sidebarObjective.getName(),
                    score.value(),
                    Optional.empty(),
                    Optional.empty()
                ));
            });
            
            // サイドバーに表示
            player.networkHandler.sendPacket(new net.minecraft.network.packet.s2c.play.ScoreboardDisplayS2CPacket(ScoreboardDisplaySlot.SIDEBAR, sidebarObjective));
        }
    }
    
    // カスタムスコアボード関連のメソッド
    public static void setCustomScoreboard(UUID playerId, String objectiveName, String displayName) {
        CustomScoreboardData data = customScoreboardData.computeIfAbsent(playerId, CustomScoreboardData::new);
        data.setCustomObjective(objectiveName, displayName);
        
        ServerPlayerEntity player = server.getPlayerManager().getPlayer(playerId);
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
            
            ServerPlayerEntity player = server.getPlayerManager().getPlayer(playerId);
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
            
            ServerPlayerEntity player = server.getPlayerManager().getPlayer(playerId);
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
        ServerPlayerEntity player = server.getPlayerManager().getPlayer(playerId);
        if (player != null) {
            CustomScoreboardPacketSender.sendTransformedScoreboard(player, objectiveName, data);
        }
    }
    
    
    public static void setScoreValueOffset(UUID playerId, String objectiveName, String scoreName, int offset) {
        ScoreboardTransformData data = transformData.computeIfAbsent(playerId, ScoreboardTransformData::new);
        data.setScoreValueOffset(objectiveName, scoreName, offset);
        data.setEnabled(true);
        
        // 変換を適用
        ServerPlayerEntity player = server.getPlayerManager().getPlayer(playerId);
        if (player != null) {
            CustomScoreboardPacketSender.sendTransformedScoreboard(player, objectiveName, data);
        }
    }
    
    public static void clearScoreboardTransforms(UUID playerId) {
        ScoreboardTransformData data = transformData.get(playerId);
        if (data != null) {
            data.clear();
            
            // 変換されたスコアボードをクリア
            ServerPlayerEntity player = server.getPlayerManager().getPlayer(playerId);
            if (player != null) {
                CustomScoreboardPacketSender.clearTransformedScoreboard(player);
            }
        }
    }
    
    public static void refreshTransformedScoreboard(UUID playerId, String objectiveName) {
        ScoreboardTransformData data = transformData.get(playerId);
        if (data != null && data.isEnabled()) {
            ServerPlayerEntity player = server.getPlayerManager().getPlayer(playerId);
            if (player != null) {
                CustomScoreboardPacketSender.sendTransformedScoreboard(player, objectiveName, data);
            }
        }
    }
    
    // 自動変換関連のメソッド
    public static void checkAndApplyAutoTransforms(ServerPlayerEntity player) {
        if (!ScoreboardAutoTransform.isAutoApplyEnabled()) return; 
        
        // サーバーのスコアボードから現在表示中のオブジェクティブを取得
        server.getScoreboard().getObjectives().forEach(objective -> {
            String objectiveName = objective.getName();
            if (ScoreboardAutoTransform.shouldAutoTransform(objectiveName)) {
                ScoreboardTransformData autoTransformData = ScoreboardAutoTransform.createTransformData(
                    player.getUuid(), objectiveName);
                
                if (autoTransformData != null) {
                    transformData.put(player.getUuid(), autoTransformData);
                    CustomScoreboardPacketSender.sendTransformedScoreboard(player, objectiveName, autoTransformData);
                }
            }
        });
    }
    
    public static void reloadAutoTransforms() {
        ScoreboardAutoTransform.reloadConfig();
        
        // 全プレイヤーに自動変換を再適用
        server.getPlayerManager().getPlayerList().forEach(player -> {
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

        public NbtCompound toNbt() {
            NbtCompound nbt = new NbtCompound();
            nbt.putString("displayObjective", displayObjective);
            nbt.putBoolean("enabled", enabled);
            
            NbtCompound scoresNbt = new NbtCompound();
            for (Map.Entry<String, Integer> entry : scores.entrySet()) {
                scoresNbt.putInt(entry.getKey(), entry.getValue());
            }
            nbt.put("scores", scoresNbt);
            
            return nbt;
        }

        public void fromNbt(NbtCompound nbt) {
            displayObjective = nbt.getString("displayObjective");
            enabled = nbt.getBoolean("enabled");
            
            scores.clear();
            if (nbt.contains("scores")) {
                NbtCompound scoresNbt = nbt.getCompound("scores");
                for (String key : scoresNbt.getKeys()) {
                    scores.put(key, scoresNbt.getInt(key));
                }
            }
        }
    }

    // 統計スコアボードを表示しているすべてのプレイヤーに更新を送信
    public static void updateTotalStatsForWatchers() {
        for (ServerPlayerEntity player : server.getPlayerManager().getPlayerList()) {
            PlayerScoreboardData data = playerData.get(player.getUuid());
            if (data != null && data.isEnabled() && !data.getDisplayObjective().isEmpty()) {
                String objective = data.getDisplayObjective();
                if (TotalStatsManager.isTotalObjective(objective)) {
                    playersToUpdate.add(player.getUuid());
                }
            }
        }
    }
    
    // 統計スコアボードの更新パケットを送信（表示設定は変更しない）
    private static void sendScoreboardUpdatePackets(ServerPlayerEntity player, ScoreboardObjective objective) {
        if (objective != null) {
            // プレイヤーがこのオブジェクティブを表示しているか確認
            UUID playerId = player.getUuid();
            String playerActiveObjective = playerActiveObjectives.get(playerId);
            
            if (playerActiveObjective != null && playerActiveObjective.equals(objective.getName())) {
                // 差分スコアデータを送信
                sendDifferentialScoreboardUpdate(player, objective);
            } else {
            }
        }
    }
    
    // 永続表示保持システム（スコアボードが消えないように維持）
    private static void maintainPersistentDisplay(ServerPlayerEntity player, ScoreboardObjective objective) {
        // 毎回表示パケットを送信してスコアボード表示を強制維持
        // これにより他のパケットの影響でスコアボードが消えることを防ぐ
        player.networkHandler.sendPacket(new net.minecraft.network.packet.s2c.play.ScoreboardDisplayS2CPacket(ScoreboardDisplaySlot.SIDEBAR, objective));
    }
    
    // 最小限のスコア更新（通信量最適化版）
    private static void sendMinimalScoreUpdate(ServerPlayerEntity player, ScoreboardObjective objective, boolean forceFullSync) {
        UUID playerId = player.getUuid();
        String objectiveName = objective.getName();
        
        // プレイヤーのスコアキャッシュを取得
        Map<String, Map<String, Integer>> playerCache = playerScoreboardCache.computeIfAbsent(playerId, k -> new ConcurrentHashMap<>());
        Map<String, Integer> objectiveCache = playerCache.computeIfAbsent(objectiveName, k -> new ConcurrentHashMap<>());
        
        // 現在のスコアを取得
        Collection<ScoreboardEntry> currentScores = server.getScoreboard().getScoreboardEntries(objective);
        Set<String> currentPlayerNames = new HashSet<>();
        int packetCount = 0;
        
        // スコア変更のみ送信（バニラと同じ動作）
        for (ScoreboardEntry score : currentScores) {
            String playerName = score.owner();
            int currentValue = score.value();
            currentPlayerNames.add(playerName);
            
            Integer cachedValue = objectiveCache.get(playerName);
            if (forceFullSync || cachedValue == null || !cachedValue.equals(currentValue)) {
                // スコア更新パケットのみ（表示パケットは送信しない）
                player.networkHandler.sendPacket(new ScoreboardScoreUpdateS2CPacket(
                    playerName,
                    objectiveName,
                    currentValue,
                    Optional.empty(),
                    Optional.empty()
                ));
                objectiveCache.put(playerName, currentValue);
                packetCount++;
            }
        }
        
        // 削除されたスコア
        var iterator = objectiveCache.entrySet().iterator();
        while (iterator.hasNext()) {
            var entry = iterator.next();
            String playerName = entry.getKey();
            if (!currentPlayerNames.contains(playerName)) {
                player.networkHandler.sendPacket(new ScoreboardScoreResetS2CPacket(
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
    private static void sendDifferentialScoreboardUpdate(ServerPlayerEntity player, ScoreboardObjective objective) {
        // パケット制限を撤廃（リアルタイム更新でスムーズな表示を実現）
        UUID playerId = player.getUuid();
        String objectiveName = objective.getName();
        
        // プレイヤーがこのオブジェクティブを表示しているかチェック
        String playerActiveObjective = playerActiveObjectives.get(playerId);
        if (playerActiveObjective == null || !playerActiveObjective.equals(objectiveName)) {
            // このプレイヤーは別のオブジェクティブを表示中、または何も表示していない
            return;
        }
        
        // プレイヤーのスコアキャッシュを取得または作成
        Map<String, Map<String, Integer>> playerCache = playerScoreboardCache.computeIfAbsent(playerId, k -> new ConcurrentHashMap<>());
        Map<String, Integer> objectiveCache = playerCache.computeIfAbsent(objectiveName, k -> new ConcurrentHashMap<>());
        
        // 現在のスコアデータを取得
        Collection<ScoreboardEntry> currentScores = server.getScoreboard().getScoreboardEntries(objective);
        Set<String> currentPlayerNames = new HashSet<>();
        int updateCount = 0;
        int removeCount = 0;
        
        // 更新または新規追加されたスコアのみを送信
        for (ScoreboardEntry score : currentScores) {
            String playerName = score.owner();
            int currentScore = score.value();
            currentPlayerNames.add(playerName);
            
            Integer cachedScore = objectiveCache.get(playerName);
            if (cachedScore == null || !cachedScore.equals(currentScore)) {
                // 変更があった場合のみパケットを送信（制限なし）
                player.networkHandler.sendPacket(new ScoreboardScoreUpdateS2CPacket(
                    playerName,
                    objectiveName,
                    currentScore,
                    Optional.empty(),
                    Optional.empty()
                ));
                objectiveCache.put(playerName, currentScore);
                updateCount++;
            }
        }
        
        // 削除されたプレイヤーのスコアを削除
        Set<String> cachedPlayerNames = new HashSet<>(objectiveCache.keySet());
        for (String cachedPlayerName : cachedPlayerNames) {
            if (!currentPlayerNames.contains(cachedPlayerName)) {
                // プレイヤーが削除された場合（制限なし）
                player.networkHandler.sendPacket(new ScoreboardScoreResetS2CPacket(
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
        for (Map<String, Map<String, Integer>> playerCache : playerScoreboardCache.values()) {
            playerCache.remove(objectiveName);
        }
    }
    
    private static void loadTotalStatsConfig(Path configDir) {
        File statsConfigFile = configDir.resolve("total_stats_config.dat").toFile();
        if (statsConfigFile.exists()) {
            try {
                NbtCompound nbt = NbtIo.readCompressed(statsConfigFile.toPath(), NbtSizeTracker.ofUnlimitedBytes());
                
                // 有効な統計を読み込み
                if (nbt.contains("enabledStats")) {
                    NbtList enabledList = nbt.getList("enabledStats", 8); // 8 = String
                    for (int i = 0; i < enabledList.size(); i++) {
                        String statId = enabledList.getString(i);
                        TotalStatsManager.enableStat(statId);
                    }
                }
                
                // 除外プレイヤーを読み込み
                if (nbt.contains("excludedPlayers")) {
                    NbtList excludedList = nbt.getList("excludedPlayers", 8); // 8 = String
                    for (int i = 0; i < excludedList.size(); i++) {
                        String playerName = excludedList.getString(i);
                        TotalStatsManager.excludePlayer(playerName);
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
        
        NbtCompound nbt = new NbtCompound();
        
        // 有効な統計を保存
        NbtList enabledList = new NbtList();
        for (String statId : TotalStatsManager.getEnabledStats()) {
            enabledList.add(NbtString.of(statId));
        }
        nbt.put("enabledStats", enabledList);
        
        // 除外プレイヤーを保存
        NbtList excludedList = new NbtList();
        for (String playerName : TotalStatsManager.getExcludedPlayers()) {
            excludedList.add(NbtString.of(playerName));
        }
        nbt.put("excludedPlayers", excludedList);
        
        try {
            NbtIo.writeCompressed(nbt, statsConfigFile.toPath());
        } catch (IOException e) {
            ServerScoreboardLogger.error("Failed to save total stats config", e);
        }
    }
    
    // スコアボード表示保持システム
    private static void maintainScoreboardDisplay() {
        long currentTime = System.currentTimeMillis();
        
        for (ServerPlayerEntity player : server.getPlayerManager().getPlayerList()) {
            UUID playerId = player.getUuid();
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
    private static void refreshScoreboardDisplay(ServerPlayerEntity player, String objectiveName) {
        try {
            ServerScoreboard scoreboard = server.getScoreboard();
            ScoreboardObjective objective = scoreboard.getNullableObjective(objectiveName);
            
            if (objective != null) {
                // サイドバー表示を再送信
                player.networkHandler.sendPacket(new net.minecraft.network.packet.s2c.play.ScoreboardDisplayS2CPacket(ScoreboardDisplaySlot.SIDEBAR, objective));
            } else {
                // オブジェクトが存在しない場合は再設定を試行
                PlayerScoreboardData data = playerData.get(player.getUuid());
                if (data != null && !data.getDisplayObjective().isEmpty()) {
                    setClientDisplayObjective(player.getUuid(), data.getDisplayObjective());
                }
            }
        } catch (Exception e) {
            ServerScoreboardLogger.error("Failed to refresh scoreboard display for " + player.getName().getString(), e);
        }
    }
}
