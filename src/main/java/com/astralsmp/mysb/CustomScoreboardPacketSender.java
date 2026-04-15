package com.astralsmp.mysb;

import net.minecraft.network.packet.s2c.play.ScoreboardDisplayS2CPacket;
import net.minecraft.network.packet.s2c.play.ScoreboardObjectiveUpdateS2CPacket;
import net.minecraft.network.packet.s2c.play.ScoreboardScoreResetS2CPacket;
import net.minecraft.network.packet.s2c.play.ScoreboardScoreUpdateS2CPacket;
import net.minecraft.scoreboard.ReadableScoreboardScore;
import net.minecraft.scoreboard.ScoreAccess;
import net.minecraft.scoreboard.ScoreboardCriterion;
import net.minecraft.scoreboard.ScoreboardDisplaySlot;
import net.minecraft.scoreboard.ScoreboardEntry;
import net.minecraft.scoreboard.ScoreHolder;
import net.minecraft.scoreboard.ScoreboardObjective;
import net.minecraft.scoreboard.ServerScoreboard;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class CustomScoreboardPacketSender {
    // プレイヤー毎の変換済みスコアボードキャッシュ: プレイヤーUUID -> オブジェクティブ名 -> プレイヤー名 -> 直近送信した変換済みエントリ
    private static final Map<String, Map<String, Map<String, ScoreboardEntry>>> transformedScoreCache = new ConcurrentHashMap<>();
    
    // バニラのスコアボードを変換して送信する（サーバー側データを変更しない）
    public static void sendTransformedScoreboard(ServerPlayerEntity player, String originalObjectiveName, ScoreboardTransformData transformData) {
        MinecraftServer server = player.getServer();
        if (server == null) return;
        
        ServerScoreboard serverScoreboard = server.getScoreboard();
        ScoreboardObjective originalObjective = serverScoreboard.getNullableObjective(originalObjectiveName);
        if (originalObjective == null) return;
        Map<String, ScoreboardEntry> originalEntries = new HashMap<>();
        serverScoreboard.getScoreboardEntries(originalObjective)
            .forEach(entry -> originalEntries.put(entry.owner(), entry));
        
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
            originalObjective.getRenderType(),
            originalObjective.getNumberFormat()
        );
        
        // プレイヤーにオブジェクティブを送信
        player.networkHandler.sendPacket(new ScoreboardObjectiveUpdateS2CPacket(virtualObjective, 0));
        
        // サイドバーに表示
        player.networkHandler.sendPacket(new ScoreboardDisplayS2CPacket(ScoreboardDisplaySlot.SIDEBAR, virtualObjective));
        
        // scoreboard.datから直接スコアデータを読み込んで変換
        Map<String, Integer> scoreData = ScoreboardDataReader.getAllPlayersScoresForObjective(server, originalObjectiveName);
        ServerScoreboardLogger.info("Found " + scoreData.size() + " scores for objective " + originalObjectiveName);
        
        if (scoreData.isEmpty()) {
            // scoreboard.datにデータがない場合、サーバーメモリからデータを取得
            ServerScoreboard scoreboard = server.getScoreboard();
            ScoreboardObjective objective = scoreboard.getNullableObjective(originalObjectiveName);
            if (objective != null) {
                Map<String, Integer> memoryScoreData = new HashMap<>();
                scoreboard.getScoreboardEntries(objective)
                    .forEach(entry -> memoryScoreData.put(entry.owner(), entry.value()));
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
    private static void sendDifferentialTransformedScores(ServerPlayerEntity player, String virtualObjectiveName, 
                                                         String originalObjectiveName, Map<String, Integer> scoreData, 
                                                         Map<String, ScoreboardEntry> originalEntries,
                                                         ScoreboardTransformData transformData) {
        // レート制限チェック（DDOS対策）
        if (!RateLimiter.canSendScoreboardPacket(player.getUuid())) {
            ServerScoreboardLogger.warn("Rate limit exceeded for transformed scoreboard for player " + player.getName().getString());
            return;
        }
        
        String playerUuid = player.getUuidAsString();
        
        // プレイヤーの変換済みスコアキャッシュを取得または作成
        Map<String, Map<String, ScoreboardEntry>> playerCache = transformedScoreCache.computeIfAbsent(playerUuid, k -> new ConcurrentHashMap<>());
        Map<String, ScoreboardEntry> objectiveCache = playerCache.computeIfAbsent(originalObjectiveName, k -> new ConcurrentHashMap<>());
        
        Set<String> currentPlayerNames = new HashSet<>();
        int updateCount = 0;
        int removeCount = 0;
        
        // 更新または新規追加された変換済みスコアのみを送信
        for (Map.Entry<String, Integer> entry : scoreData.entrySet()) {
            String playerName = entry.getKey();
            int originalScore = entry.getValue();
            int transformedScore = transformData.getTransformedScoreValue(originalObjectiveName, playerName, originalScore);
            ScoreboardEntry transformedEntry = createTransformedEntry(playerName, transformedScore, originalEntries.get(playerName));
            currentPlayerNames.add(playerName);
            
            ScoreboardEntry cachedEntry = objectiveCache.get(playerName);
            if (cachedEntry == null || !cachedEntry.equals(transformedEntry)) {
                // 変更があった場合のみパケットを送信（レート制限チェック付き）
                if (RateLimiter.canSendPacket(player.getUuid())) {
                    player.networkHandler.sendPacket(new ScoreboardScoreUpdateS2CPacket(
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
                if (RateLimiter.canSendPacket(player.getUuid())) {
                    player.networkHandler.sendPacket(new ScoreboardScoreResetS2CPacket(
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
            MinecraftServer server = player.getServer();
            if (server != null) {
                ScoreboardObjective originalObjective = server.getScoreboard().getNullableObjective(originalObjectiveName);
                if (originalObjective != null) {
                    ScoreboardObjective virtualObjective = new VirtualObjective(
                        virtualObjectiveName,
                        originalObjective.getCriterion(),
                        originalObjective.getDisplayName(),
                        originalObjective.getRenderType(),
                        originalObjective.getNumberFormat()
                    );
                    player.networkHandler.sendPacket(new ScoreboardDisplayS2CPacket(ScoreboardDisplaySlot.SIDEBAR, virtualObjective));
                }
            }
        }
    }
    
    // 仮想オブジェクティブクラス（サーバー側スコアボードに影響しない）
    private static class VirtualObjective extends ScoreboardObjective {
        public VirtualObjective(String name, ScoreboardCriterion criterion, Text displayName, ScoreboardCriterion.RenderType renderType,
                                net.minecraft.scoreboard.number.NumberFormat numberFormat) {
            super(null, name, criterion, displayName, renderType, false, numberFormat);
        }
    }

    private static ScoreboardEntry createTransformedEntry(String playerName, int transformedScore, ScoreboardEntry originalEntry) {
        return new ScoreboardEntry(
            playerName,
            transformedScore,
            originalEntry != null ? originalEntry.display() : null,
            originalEntry != null ? originalEntry.numberFormatOverride() : null
        );
    }
    
    public static void clearTransformedScoreboard(ServerPlayerEntity player) {
        String virtualObjectiveName = "mysb_virtual_" + player.getUuidAsString().substring(0, 8);
        
        // 仮想オブジェクティブを作成してクリアパケットを送信
        ScoreboardObjective virtualObjective = new VirtualObjective(
            virtualObjectiveName,
            ScoreboardCriterion.DUMMY,
            Text.literal(""),
            ScoreboardCriterion.RenderType.INTEGER,
            null
        );
        
        // サイドバーをクリア
        player.networkHandler.sendPacket(new ScoreboardDisplayS2CPacket(ScoreboardDisplaySlot.SIDEBAR, null));
        
        // クライアント側のオブジェクティブを削除
        player.networkHandler.sendPacket(new ScoreboardObjectiveUpdateS2CPacket(virtualObjective, 1));
        
        ServerScoreboardLogger.info("Cleared virtual transformed scoreboard for player " + player.getName().getString());
        
        // 変換済みスコアキャッシュをクリア
        clearTransformedScoreCache(player.getUuidAsString());
    }
    
    // プレイヤーの変換済みスコアキャッシュをクリア
    public static void clearTransformedScoreCache(String playerUuid) {
        transformedScoreCache.remove(playerUuid);
    }
    
    // 特定のオブジェクティブの変換済みキャッシュをクリア
    public static void clearTransformedObjectiveCache(String objectiveName) {
        for (Map<String, Map<String, ScoreboardEntry>> playerCache : transformedScoreCache.values()) {
            playerCache.remove(objectiveName);
        }
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
        ScoreboardObjective existingObjective = scoreboard.getNullableObjective(objectiveName);
        if (existingObjective != null) {
            scoreboard.removeObjective(existingObjective);
        }
        
        // 新しいオブジェクティブを作成
        ScoreboardObjective objective = scoreboard.addObjective(
            objectiveName,
            ScoreboardCriterion.DUMMY,
            displayName,
            ScoreboardCriterion.RenderType.INTEGER,
            false,
            null
        );
        
        // プレイヤーにオブジェクティブを送信（レート制限付き）
        if (RateLimiter.canSendPacket(player.getUuid())) {
            player.networkHandler.sendPacket(new ScoreboardObjectiveUpdateS2CPacket(objective, 0));
            
            // サイドバーに表示
            player.networkHandler.sendPacket(new ScoreboardDisplayS2CPacket(ScoreboardDisplaySlot.SIDEBAR, objective));
        } else {
            ServerScoreboardLogger.warn("Cannot send custom scoreboard due to rate limit for player " + player.getName().getString());
            return;
        }
        
        // カスタムスコアを送信（レート制限付き）
        for (Map.Entry<String, Integer> entry : data.getCustomScores().entrySet()) {
            if (RateLimiter.canSendPacket(player.getUuid())) {
                ScoreHolder scoreHolder = ScoreHolder.fromName(entry.getKey());
                ScoreAccess scoreAccess = scoreboard.getOrCreateScore(scoreHolder, objective);
                scoreAccess.setScore(entry.getValue());
                ReadableScoreboardScore readableScore = scoreboard.getScore(scoreHolder, objective);
                player.networkHandler.sendPacket(new ScoreboardScoreUpdateS2CPacket(
                    entry.getKey(),
                    objectiveName,
                    entry.getValue(),
                    Optional.ofNullable(scoreAccess.getDisplayText()),
                    Optional.ofNullable(readableScore != null ? readableScore.getNumberFormat() : null)
                ));
            } else {
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
        
        ScoreboardObjective objective = scoreboard.getNullableObjective(objectiveName);
        if (objective != null) {
            player.networkHandler.sendPacket(new ScoreboardDisplayS2CPacket(ScoreboardDisplaySlot.SIDEBAR, null));
            player.networkHandler.sendPacket(new ScoreboardObjectiveUpdateS2CPacket(objective, 1));
            scoreboard.removeObjective(objective);
        }
        
        ServerScoreboardLogger.info("Cleared custom scoreboard for player " + player.getName().getString());
    }
}
