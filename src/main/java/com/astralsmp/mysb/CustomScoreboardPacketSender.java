package com.astralsmp.mysb;

import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.numbers.NumberFormat;
import net.minecraft.network.protocol.game.ClientboundResetScorePacket;
import net.minecraft.network.protocol.game.ClientboundSetDisplayObjectivePacket;
import net.minecraft.network.protocol.game.ClientboundSetObjectivePacket;
import net.minecraft.network.protocol.game.ClientboundSetScorePacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.ServerScoreboard;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.scores.DisplaySlot;
import net.minecraft.world.scores.Objective;
import net.minecraft.world.scores.PlayerScoreEntry;
import net.minecraft.world.scores.ScoreHolder;
import net.minecraft.world.scores.criteria.ObjectiveCriteria;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class CustomScoreboardPacketSender {
    // プレイヤー毎の変換済みスコアボードキャッシュ: プレイヤーUUID -> オブジェクティブ名 -> プレイヤー名 -> 直近送信した変換済みエントリ
    private static final Map<String, Map<String, Map<String, PlayerScoreEntry>>> transformedScoreCache = new ConcurrentHashMap<>();

    // Fake Player除外フィルタ（FAKE_PLAYER_SCORE_ENABLED が false の場合のみ除外）
    private static Map<String, Integer> filterFakePlayers(Map<String, Integer> source) {
        if (ServerScoreboardConfig.FAKE_PLAYER_SCORE_ENABLED || source == null || source.isEmpty()) {
            return source;
        }
        Map<String, Integer> filtered = new HashMap<>(source.size());
        for (Map.Entry<String, Integer> entry : source.entrySet()) {
            if (!FakePlayerDetector.isFakePlayerName(entry.getKey())) {
                filtered.put(entry.getKey(), entry.getValue());
            }
        }
        return filtered;
    }
    
    // バニラのスコアボードを変換して送信する（サーバー側データを変更しない）
    public static void sendTransformedScoreboard(ServerPlayer player, String originalObjectiveName, ScoreboardTransformData transformData) {
        MinecraftServer server = player.level().getServer();
        if (server == null) return;
        
        ServerScoreboard serverScoreboard = server.getScoreboard();
        Objective originalObjective = serverScoreboard.getObjective(originalObjectiveName);
        if (originalObjective == null) return;
        Map<String, PlayerScoreEntry> originalEntries = new HashMap<>();
        serverScoreboard.listPlayerScores(originalObjective)
            .forEach(entry -> originalEntries.put(entry.owner(), entry));
        
        // 変換された表示名を取得
        String transformedDisplayName = transformData.getTransformedDisplayName(originalObjectiveName);
        if (transformedDisplayName == null) {
            transformedDisplayName = originalObjective.getDisplayName().getString();
        }
        
        // プレイヤー専用の仮想オブジェクティブ名
        String virtualObjectiveName = "mysb_virtual_" + player.getStringUUID().substring(0, 8);
        
        // 仮想オブジェクティブをクライアントにのみ作成（サーバー側スコアボードには追加しない）
        Objective virtualObjective = new VirtualObjective(
            virtualObjectiveName,
            originalObjective.getCriteria(),
            Component.literal(transformedDisplayName),
            originalObjective.getRenderType(),
            originalObjective.numberFormat()
        );
        
        // プレイヤーにオブジェクティブを送信
        player.connection.send(new ClientboundSetObjectivePacket(virtualObjective, 0));
        
        // サイドバーに表示
        player.connection.send(new ClientboundSetDisplayObjectivePacket(DisplaySlot.SIDEBAR, virtualObjective));
        
        // scoreboard.datから直接スコアデータを読み込んで変換
        Map<String, Integer> scoreData = ScoreboardDataReader.getAllPlayersScoresForObjective(server, originalObjectiveName);
        // Fake Playerが無効化されている場合は除外
        scoreData = filterFakePlayers(scoreData);
        ServerScoreboardLogger.info("Found " + scoreData.size() + " scores for objective " + originalObjectiveName);

        if (scoreData.isEmpty()) {
            // scoreboard.datにデータがない場合、サーバーメモリからデータを取得
            ServerScoreboard scoreboard = server.getScoreboard();
            Objective objective = scoreboard.getObjective(originalObjectiveName);
            if (objective != null) {
                Map<String, Integer> memoryScoreData = new HashMap<>();
                boolean filterFakePlayers = !ServerScoreboardConfig.FAKE_PLAYER_SCORE_ENABLED;
                server.getPlayerList().getPlayers().forEach(serverPlayer -> {
                    if (filterFakePlayers && FakePlayerDetector.isFakePlayer(serverPlayer)) {
                        return;
                    }
                    String playerName = serverPlayer.getName().getString();
                    PlayerScoreEntry originalEntry = originalEntries.get(playerName);
                    // プレイヤーがスコアを持っていない場合はスキップ
                    if (originalEntry != null) {
                        memoryScoreData.put(playerName, originalEntry.value());
                    }
                });
                // 差分変換スコアボード送信
                sendDifferentialTransformedScores(player, virtualObjectiveName, originalObjectiveName, memoryScoreData, originalEntries, transformData);
            }
        } else {
            // 差分変換スコアボード送信
            sendDifferentialTransformedScores(player, virtualObjectiveName, originalObjectiveName, scoreData, originalEntries, transformData);
        }
        
        ServerScoreboardLogger.info("Sent virtual transformed scoreboard " + originalObjectiveName + " to player " + player.getName().getString());
    }
    
    // 差分変換スコアボード送信（パケット数削減）
    private static void sendDifferentialTransformedScores(ServerPlayer player, String virtualObjectiveName, 
                                                         String originalObjectiveName, Map<String, Integer> scoreData, 
                                                         Map<String, PlayerScoreEntry> originalEntries,
                                                         ScoreboardTransformData transformData) {
        // レート制限チェック（DDOS対策）
        if (!RateLimiter.canSendScoreboardPacket(player.getUUID())) {
            ServerScoreboardLogger.warn("Rate limit exceeded for transformed scoreboard for player " + player.getName().getString());
            return;
        }
        
        String playerUuid = player.getStringUUID();
        
        // プレイヤーの変換済みスコアキャッシュを取得または作成
        Map<String, Map<String, PlayerScoreEntry>> playerCache = transformedScoreCache.computeIfAbsent(playerUuid, k -> new ConcurrentHashMap<>());
        Map<String, PlayerScoreEntry> objectiveCache = playerCache.computeIfAbsent(originalObjectiveName, k -> new ConcurrentHashMap<>());
        
        Set<String> currentPlayerNames = new HashSet<>();
        int updateCount = 0;
        int removeCount = 0;
        
        // 更新または新規追加された変換済みスコアのみを送信
        for (Map.Entry<String, Integer> entry : scoreData.entrySet()) {
            String playerName = entry.getKey();
            int originalScore = entry.getValue();
            int transformedScore = transformData.getTransformedScoreValue(originalObjectiveName, playerName, originalScore);
            PlayerScoreEntry transformedEntry = createTransformedEntry(playerName, transformedScore, originalEntries.get(playerName));
            currentPlayerNames.add(playerName);
            
            PlayerScoreEntry cachedEntry = objectiveCache.get(playerName);
            if (cachedEntry == null || !cachedEntry.equals(transformedEntry)) {
                // 変更があった場合のみパケットを送信（レート制限チェック付き）
                if (RateLimiter.canSendPacket(player.getUUID())) {
                    player.connection.send(new ClientboundSetScorePacket(
                        transformedEntry.owner(),
                        virtualObjectiveName,
                        transformedEntry.value(),
                        Optional.ofNullable(transformedEntry.display()),
                        Optional.ofNullable(transformedEntry.numberFormatOverride())
                    ));
                    objectiveCache.put(playerName, transformedEntry);
                    updateCount++;
                } else {
                }
            }
        }
        
        // 削除されたプレイヤーのスコアを削除
        Set<String> cachedPlayerNames = new HashSet<>(objectiveCache.keySet());
        for (String cachedPlayerName : cachedPlayerNames) {
            if (!currentPlayerNames.contains(cachedPlayerName)) {
                // プレイヤーが削除された場合（レート制限チェック付き）
                if (RateLimiter.canSendPacket(player.getUUID())) {
                    player.connection.send(new ClientboundResetScorePacket(
                        cachedPlayerName,
                        virtualObjectiveName
                    ));
                    objectiveCache.remove(cachedPlayerName);
                    removeCount++;
                } else {
                }
            }
        }
        
        if (updateCount > 0 || removeCount > 0) {
            ServerScoreboardLogger.info("Sent differential transformed update for " + originalObjectiveName + " to " + player.getName().getString() + 
                ": " + updateCount + " updates, " + removeCount + " removes (total scores: " + scoreData.size() + ")");
            
            // 変換済みスコアボード表示を確実に維持
            MinecraftServer server = player.level().getServer();
            if (server != null) {
                Objective originalObjective = server.getScoreboard().getObjective(originalObjectiveName);
                if (originalObjective != null) {
                    Objective virtualObjective = new VirtualObjective(
                        virtualObjectiveName,
                        originalObjective.getCriteria(),
                        originalObjective.getDisplayName(),
                        originalObjective.getRenderType(),
                        originalObjective.numberFormat()
                    );
                    player.connection.send(new ClientboundSetDisplayObjectivePacket(DisplaySlot.SIDEBAR, virtualObjective));
                }
            }
        }
    }
    
    // 仮想オブジェクティブクラス（サーバー側スコアボードに影響しない）
    private static class VirtualObjective extends Objective {
        public VirtualObjective(String name, ObjectiveCriteria criterion, Component displayName, ObjectiveCriteria.RenderType renderType,
                                NumberFormat numberFormat) {
            super(null, name, criterion, displayName, renderType, false, numberFormat);
        }
    }

    private static PlayerScoreEntry createTransformedEntry(String playerName, int transformedScore, PlayerScoreEntry originalEntry) {
        return new PlayerScoreEntry(
            playerName,
            transformedScore,
            originalEntry != null ? originalEntry.display() : null,
            originalEntry != null ? originalEntry.numberFormatOverride() : null
        );
    }
    
    public static void clearTransformedScoreboard(ServerPlayer player) {
        String virtualObjectiveName = "mysb_virtual_" + player.getStringUUID().substring(0, 8);
        
        // 仮想オブジェクティブを作成してクリアパケットを送信
        Objective virtualObjective = new VirtualObjective(
            virtualObjectiveName,
            ObjectiveCriteria.DUMMY,
            Component.literal(""),
            ObjectiveCriteria.RenderType.INTEGER,
            null
        );
        
        // サイドバーをクリア
        player.connection.send(new ClientboundSetDisplayObjectivePacket(DisplaySlot.SIDEBAR, null));
        
        // クライアント側のオブジェクティブを削除
        player.connection.send(new ClientboundSetObjectivePacket(virtualObjective, 1));
        
        ServerScoreboardLogger.info("Cleared virtual transformed scoreboard for player " + player.getName().getString());
        
        // 変換済みスコアキャッシュをクリア
        clearTransformedScoreCache(player.getStringUUID());
    }
    
    // プレイヤーの変換済みスコアキャッシュをクリア
    public static void clearTransformedScoreCache(String playerUuid) {
        transformedScoreCache.remove(playerUuid);
    }
    
    // 特定のオブジェクティブの変換済みキャッシュをクリア
    public static void clearTransformedObjectiveCache(String objectiveName) {
        for (Map<String, Map<String, PlayerScoreEntry>> playerCache : transformedScoreCache.values()) {
            playerCache.remove(objectiveName);
        }
    }
    
    // カスタムスコアボード機能（既存）
    public static void sendCustomScoreboard(ServerPlayer player, CustomScoreboardData data) {
        if (!data.isEnabled()) {
            clearCustomScoreboard(player);
            return;
        }
        
        MinecraftServer server = player.level().getServer();
        if (server == null) return;
        
        String objectiveName = "mysb_custom_" + player.getStringUUID().substring(0, 8);
        Component displayName = Component.literal(data.getCustomDisplayName() != null ? data.getCustomDisplayName() : "Custom Scoreboard");
        
        ServerScoreboard scoreboard = server.getScoreboard();
        
        // 既存のオブジェクティブを削除
        Objective existingObjective = scoreboard.getObjective(objectiveName);
        if (existingObjective != null) {
            scoreboard.removeObjective(existingObjective);
        }
        
        // 新しいオブジェクティブを作成
        Objective objective = scoreboard.addObjective(
            objectiveName,
            ObjectiveCriteria.DUMMY,
            displayName,
            ObjectiveCriteria.RenderType.INTEGER,
            false,
            null
        );
        
        // プレイヤーにオブジェクティブを送信（レート制限付き）
        if (RateLimiter.canSendPacket(player.getUUID())) {
            player.connection.send(new ClientboundSetObjectivePacket(objective, 0));
            
            // サイドバーに表示
            player.connection.send(new ClientboundSetDisplayObjectivePacket(DisplaySlot.SIDEBAR, objective));
        } else {
            ServerScoreboardLogger.warn("Cannot send custom scoreboard due to rate limit for player " + player.getName().getString());
            return;
        }
        
        // カスタムスコアを送信（レート制限付き）
        for (Map.Entry<String, Integer> entry : data.getCustomScores().entrySet()) {
            if (RateLimiter.canSendPacket(player.getUUID())) {
                scoreboard.getOrCreatePlayerScore(ScoreHolder.forNameOnly(entry.getKey()), objective).set(entry.getValue());
                player.connection.send(new ClientboundSetScorePacket(
                    entry.getKey(),
                    objectiveName,
                    entry.getValue(),
                    Optional.empty(),
                    Optional.empty()
                ));
            } else {
                break; // レート制限に達したら停止
            }
        }
        
        ServerScoreboardLogger.info("Sent custom scoreboard to player " + player.getName().getString());
    }
    
    public static void clearCustomScoreboard(ServerPlayer player) {
        MinecraftServer server = player.level().getServer();
        if (server == null) return;
        
        String objectiveName = "mysb_custom_" + player.getStringUUID().substring(0, 8);
        ServerScoreboard scoreboard = server.getScoreboard();
        
        Objective objective = scoreboard.getObjective(objectiveName);
        if (objective != null) {
            player.connection.send(new ClientboundSetDisplayObjectivePacket(DisplaySlot.SIDEBAR, null));
            player.connection.send(new ClientboundSetObjectivePacket(objective, 1));
            scoreboard.removeObjective(objective);
        }
        
        ServerScoreboardLogger.info("Cleared custom scoreboard for player " + player.getName().getString());
    }
}