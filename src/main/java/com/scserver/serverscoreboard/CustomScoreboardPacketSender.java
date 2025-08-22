package com.scserver.serverscoreboard;

import net.minecraft.network.packet.s2c.play.ScoreboardDisplayS2CPacket;
import net.minecraft.network.packet.s2c.play.ScoreboardObjectiveUpdateS2CPacket;
import net.minecraft.network.packet.s2c.play.ScoreboardPlayerUpdateS2CPacket;
import net.minecraft.scoreboard.ScoreboardCriterion;
import net.minecraft.scoreboard.ScoreboardObjective;
import net.minecraft.scoreboard.ServerScoreboard;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class CustomScoreboardPacketSender {
    // プレイヤー毎の変換済みスコアボードキャッシュ: プレイヤーUUID -> オブジェクティブ名 -> プレイヤー名 -> スコア値
    private static final Map<String, Map<String, Map<String, Integer>>> transformedScoreCache = new ConcurrentHashMap<>();
    
    // バニラのスコアボードを変換して送信する（サーバー側データを変更しない）
    public static void sendTransformedScoreboard(ServerPlayerEntity player, String originalObjectiveName, ScoreboardTransformData transformData) {
        MinecraftServer server = player.getServer();
        if (server == null) return;
        
        ServerScoreboard serverScoreboard = server.getScoreboard();
        ScoreboardObjective originalObjective = serverScoreboard.getObjective(originalObjectiveName);
        if (originalObjective == null) return;
        
        // 変換された表示名を取得
        String transformedDisplayName = transformData.getTransformedDisplayName(originalObjectiveName);
        if (transformedDisplayName == null) {
            transformedDisplayName = originalObjective.getDisplayName().getString();
        }
        
        // プレイヤー専用の仮想オブジェクティブ名
        String virtualObjectiveName = "mysb_virtual_" + player.getUuidAsString().substring(0, 8);
        
        // 仮想オブジェクティブをクライアントにのみ作成（サーバー側スコアボードには追加しない）
        ScoreboardObjective virtualObjective = new VirtualObjective(
            virtualObjectiveName,
            originalObjective.getCriterion(),
            Text.literal(transformedDisplayName),
            originalObjective.getRenderType()
        );
        
        // プレイヤーにオブジェクティブを送信
        player.networkHandler.sendPacket(new ScoreboardObjectiveUpdateS2CPacket(virtualObjective, 0));
        
        // サイドバーに表示
        player.networkHandler.sendPacket(new ScoreboardDisplayS2CPacket(1, virtualObjective));
        
        // scoreboard.datから直接スコアデータを読み込んで変換
        Map<String, Integer> scoreData = ScoreboardDataReader.getAllPlayersScoresForObjective(server, originalObjectiveName);
        ServerScoreboardLogger.info("Found " + scoreData.size() + " scores for objective " + originalObjectiveName);
        
        if (scoreData.isEmpty()) {
            // scoreboard.datにデータがない場合、サーバーメモリからデータを取得
            ServerScoreboard scoreboard = server.getScoreboard();
            ScoreboardObjective objective = scoreboard.getObjective(originalObjectiveName);
            if (objective != null) {
                Map<String, Integer> memoryScoreData = new HashMap<>();
                server.getPlayerManager().getPlayerList().forEach(serverPlayer -> {
                    String playerName = serverPlayer.getName().getString();
                    try {
                        int originalScore = serverScoreboard.getPlayerScore(playerName, objective).getScore();
                        memoryScoreData.put(playerName, originalScore);
                    } catch (Exception e) {
                        // プレイヤーがスコアを持っていない場合はスキップ
                    }
                });
                // 差分変換スコアボード送信
                sendDifferentialTransformedScores(player, virtualObjectiveName, originalObjectiveName, memoryScoreData, transformData);
            }
        } else {
            // 差分変換スコアボード送信
            sendDifferentialTransformedScores(player, virtualObjectiveName, originalObjectiveName, scoreData, transformData);
        }
        
        ServerScoreboardLogger.info("Sent virtual transformed scoreboard " + originalObjectiveName + " to player " + player.getName().getString());
    }
    
    // 差分変換スコアボード送信（パケット数削減）
    private static void sendDifferentialTransformedScores(ServerPlayerEntity player, String virtualObjectiveName, 
                                                         String originalObjectiveName, Map<String, Integer> scoreData, 
                                                         ScoreboardTransformData transformData) {
        // レート制限チェック（DDOS対策）
        if (!RateLimiter.canSendScoreboardPacket(player.getUuid())) {
            ServerScoreboardLogger.warn("Rate limit exceeded for transformed scoreboard for player " + player.getName().getString());
            return;
        }
        
        String playerUuid = player.getUuidAsString();
        
        // プレイヤーの変換済みスコアキャッシュを取得または作成
        Map<String, Map<String, Integer>> playerCache = transformedScoreCache.computeIfAbsent(playerUuid, k -> new ConcurrentHashMap<>());
        Map<String, Integer> objectiveCache = playerCache.computeIfAbsent(originalObjectiveName, k -> new ConcurrentHashMap<>());
        
        Set<String> currentPlayerNames = new HashSet<>();
        int updateCount = 0;
        int removeCount = 0;
        
        // 更新または新規追加された変換済みスコアのみを送信
        for (Map.Entry<String, Integer> entry : scoreData.entrySet()) {
            String playerName = entry.getKey();
            int originalScore = entry.getValue();
            int transformedScore = transformData.getTransformedScoreValue(originalObjectiveName, playerName, originalScore);
            currentPlayerNames.add(playerName);
            
            Integer cachedScore = objectiveCache.get(playerName);
            if (cachedScore == null || !cachedScore.equals(transformedScore)) {
                // 変更があった場合のみパケットを送信（レート制限チェック付き）
                if (RateLimiter.canSendPacket(player.getUuid())) {
                    player.networkHandler.sendPacket(new ScoreboardPlayerUpdateS2CPacket(
                        ServerScoreboard.UpdateMode.CHANGE,
                        virtualObjectiveName,
                        playerName,
                        transformedScore
                    ));
                    objectiveCache.put(playerName, transformedScore);
                    updateCount++;
                    ServerScoreboardLogger.debug("Updated transformed score: " + playerName + " = " + transformedScore + " (was: " + cachedScore + ")");
                } else {
                    ServerScoreboardLogger.debug("Skipped update due to rate limit: " + playerName);
                }
            }
        }
        
        // 削除されたプレイヤーのスコアを削除
        Set<String> cachedPlayerNames = new HashSet<>(objectiveCache.keySet());
        for (String cachedPlayerName : cachedPlayerNames) {
            if (!currentPlayerNames.contains(cachedPlayerName)) {
                // プレイヤーが削除された場合（レート制限チェック付き）
                if (RateLimiter.canSendPacket(player.getUuid())) {
                    player.networkHandler.sendPacket(new ScoreboardPlayerUpdateS2CPacket(
                        ServerScoreboard.UpdateMode.REMOVE,
                        virtualObjectiveName,
                        cachedPlayerName,
                        0
                    ));
                    objectiveCache.remove(cachedPlayerName);
                    removeCount++;
                    ServerScoreboardLogger.debug("Removed transformed score: " + cachedPlayerName);
                } else {
                    ServerScoreboardLogger.debug("Skipped removal due to rate limit: " + cachedPlayerName);
                }
            }
        }
        
        if (updateCount > 0 || removeCount > 0) {
            ServerScoreboardLogger.info("Sent differential transformed update for " + originalObjectiveName + " to " + player.getName().getString() + 
                ": " + updateCount + " updates, " + removeCount + " removes (total scores: " + scoreData.size() + ")");
            
            // 変換済みスコアボード表示を確実に維持
            MinecraftServer server = player.getServer();
            if (server != null) {
                ScoreboardObjective originalObjective = server.getScoreboard().getObjective(originalObjectiveName);
                if (originalObjective != null) {
                    ScoreboardObjective virtualObjective = new VirtualObjective(
                        virtualObjectiveName,
                        originalObjective.getCriterion(),
                        originalObjective.getDisplayName(),
                        originalObjective.getRenderType()
                    );
                    player.networkHandler.sendPacket(new ScoreboardDisplayS2CPacket(1, virtualObjective));
                }
            }
        }
    }
    
    // 仮想オブジェクティブクラス（サーバー側スコアボードに影響しない）
    private static class VirtualObjective extends ScoreboardObjective {
        public VirtualObjective(String name, ScoreboardCriterion criterion, Text displayName, ScoreboardCriterion.RenderType renderType) {
            super(null, name, criterion, displayName, renderType);
        }
    }
    
    public static void clearTransformedScoreboard(ServerPlayerEntity player) {
        String virtualObjectiveName = "mysb_virtual_" + player.getUuidAsString().substring(0, 8);
        
        // 仮想オブジェクティブを作成してクリアパケットを送信
        ScoreboardObjective virtualObjective = new VirtualObjective(
            virtualObjectiveName,
            ScoreboardCriterion.DUMMY,
            Text.literal(""),
            ScoreboardCriterion.RenderType.INTEGER
        );
        
        // サイドバーをクリア
        player.networkHandler.sendPacket(new ScoreboardDisplayS2CPacket(1, null));
        
        // クライアント側のオブジェクティブを削除
        player.networkHandler.sendPacket(new ScoreboardObjectiveUpdateS2CPacket(virtualObjective, 1));
        
        ServerScoreboardLogger.info("Cleared virtual transformed scoreboard for player " + player.getName().getString());
        
        // 変換済みスコアキャッシュをクリア
        clearTransformedScoreCache(player.getUuidAsString());
    }
    
    // プレイヤーの変換済みスコアキャッシュをクリア
    public static void clearTransformedScoreCache(String playerUuid) {
        transformedScoreCache.remove(playerUuid);
        ServerScoreboardLogger.debug("Cleared transformed score cache for player: " + playerUuid);
    }
    
    // 特定のオブジェクティブの変換済みキャッシュをクリア
    public static void clearTransformedObjectiveCache(String objectiveName) {
        for (Map<String, Map<String, Integer>> playerCache : transformedScoreCache.values()) {
            playerCache.remove(objectiveName);
        }
        ServerScoreboardLogger.debug("Cleared transformed cache for objective: " + objectiveName);
    }
    
    // カスタムスコアボード機能（既存）
    public static void sendCustomScoreboard(ServerPlayerEntity player, CustomScoreboardData data) {
        if (!data.isEnabled()) {
            clearCustomScoreboard(player);
            return;
        }
        
        MinecraftServer server = player.getServer();
        if (server == null) return;
        
        String objectiveName = "mysb_custom_" + player.getUuidAsString().substring(0, 8);
        Text displayName = Text.literal(data.getCustomDisplayName() != null ? data.getCustomDisplayName() : "Custom Scoreboard");
        
        ServerScoreboard scoreboard = server.getScoreboard();
        
        // 既存のオブジェクティブを削除
        ScoreboardObjective existingObjective = scoreboard.getObjective(objectiveName);
        if (existingObjective != null) {
            scoreboard.removeObjective(existingObjective);
        }
        
        // 新しいオブジェクティブを作成
        ScoreboardObjective objective = scoreboard.addObjective(
            objectiveName,
            ScoreboardCriterion.DUMMY,
            displayName,
            ScoreboardCriterion.RenderType.INTEGER
        );
        
        // プレイヤーにオブジェクティブを送信（レート制限付き）
        if (RateLimiter.canSendPacket(player.getUuid())) {
            player.networkHandler.sendPacket(new ScoreboardObjectiveUpdateS2CPacket(objective, 0));
            
            // サイドバーに表示
            player.networkHandler.sendPacket(new ScoreboardDisplayS2CPacket(1, objective));
        } else {
            ServerScoreboardLogger.warn("Cannot send custom scoreboard due to rate limit for player " + player.getName().getString());
            return;
        }
        
        // カスタムスコアを送信（レート制限付き）
        for (Map.Entry<String, Integer> entry : data.getCustomScores().entrySet()) {
            if (RateLimiter.canSendPacket(player.getUuid())) {
                scoreboard.getPlayerScore(entry.getKey(), objective).setScore(entry.getValue());
                player.networkHandler.sendPacket(new ScoreboardPlayerUpdateS2CPacket(
                    ServerScoreboard.UpdateMode.CHANGE,
                    objectiveName,
                    entry.getKey(),
                    entry.getValue()
                ));
            } else {
                ServerScoreboardLogger.debug("Skipped custom score due to rate limit: " + entry.getKey());
                break; // レート制限に達したら停止
            }
        }
        
        ServerScoreboardLogger.info("Sent custom scoreboard to player " + player.getName().getString());
    }
    
    public static void clearCustomScoreboard(ServerPlayerEntity player) {
        MinecraftServer server = player.getServer();
        if (server == null) return;
        
        String objectiveName = "mysb_custom_" + player.getUuidAsString().substring(0, 8);
        ServerScoreboard scoreboard = server.getScoreboard();
        
        ScoreboardObjective objective = scoreboard.getObjective(objectiveName);
        if (objective != null) {
            player.networkHandler.sendPacket(new ScoreboardDisplayS2CPacket(1, null));
            player.networkHandler.sendPacket(new ScoreboardObjectiveUpdateS2CPacket(objective, 1));
            scoreboard.removeObjective(objective);
        }
        
        ServerScoreboardLogger.info("Cleared custom scoreboard for player " + player.getName().getString());
    }
}