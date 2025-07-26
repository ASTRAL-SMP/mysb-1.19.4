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

public class CustomScoreboardPacketSender {
    
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
                server.getPlayerManager().getPlayerList().forEach(serverPlayer -> {
                    String playerName = serverPlayer.getName().getString();
                    try {
                        int originalScore = serverScoreboard.getPlayerScore(playerName, objective).getScore();
                        int transformedScore = transformData.getTransformedScoreValue(originalObjectiveName, playerName, originalScore);
                        
                        player.networkHandler.sendPacket(new ScoreboardPlayerUpdateS2CPacket(
                            ServerScoreboard.UpdateMode.CHANGE,
                            virtualObjectiveName,
                            playerName,
                            transformedScore
                        ));
                        ServerScoreboardLogger.debug("Sent score for " + playerName + ": " + transformedScore);
                    } catch (Exception e) {
                        // プレイヤーがスコアを持っていない場合はスキップ
                    }
                });
            }
        } else {
            scoreData.forEach((playerName, originalScore) -> {
                int transformedScore = transformData.getTransformedScoreValue(originalObjectiveName, playerName, originalScore);
                
                // クライアントにのみスコアを送信（サーバー側スコアボードには保存しない）
                player.networkHandler.sendPacket(new ScoreboardPlayerUpdateS2CPacket(
                    ServerScoreboard.UpdateMode.CHANGE,
                    virtualObjectiveName,
                    playerName,
                    transformedScore
                ));
                ServerScoreboardLogger.debug("Sent score from scoreboard.dat for " + playerName + ": " + transformedScore);
            });
        }
        
        ServerScoreboardLogger.info("Sent virtual transformed scoreboard " + originalObjectiveName + " to player " + player.getName().getString());
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
        
        // プレイヤーにオブジェクティブを送信
        player.networkHandler.sendPacket(new ScoreboardObjectiveUpdateS2CPacket(objective, 0));
        
        // サイドバーに表示
        player.networkHandler.sendPacket(new ScoreboardDisplayS2CPacket(1, objective));
        
        // カスタムスコアを送信
        for (Map.Entry<String, Integer> entry : data.getCustomScores().entrySet()) {
            scoreboard.getPlayerScore(entry.getKey(), objective).setScore(entry.getValue());
            player.networkHandler.sendPacket(new ScoreboardPlayerUpdateS2CPacket(
                ServerScoreboard.UpdateMode.CHANGE,
                objectiveName,
                entry.getKey(),
                entry.getValue()
            ));
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