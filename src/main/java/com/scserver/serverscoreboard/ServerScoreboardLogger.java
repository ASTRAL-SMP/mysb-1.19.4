package com.scserver.serverscoreboard;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

public class ServerScoreboardLogger {
    private static final Logger LOGGER = LoggerFactory.getLogger(ServerOnlyScoreboardMod.MOD_ID);
    private static MinecraftServer server;
    
    public static void setServer(MinecraftServer minecraftServer) {
        server = minecraftServer;
    }
    
    public static void info(String message) {
        LOGGER.info(message);
    }
    
    public static void warn(String message) {
        LOGGER.warn(message);
    }
    
    public static void error(String message) {
        LOGGER.error(message);
    }
    
    public static void error(String message, Throwable throwable) {
        LOGGER.error(message, throwable);
    }
    
    public static void debug(String message) {
        LOGGER.debug(message);
        
        // デバッグモードが有効な場合、サーバーメッセージとして送信
        if (ServerScoreboardConfig.DEBUG_MODE_ENABLED && server != null) {
            broadcastDebugMessage(message);
        }
    }
    
    public static void debugScore(String action, String objectiveName, String playerName, int value) {
        if (!ServerScoreboardConfig.DEBUG_MODE_ENABLED) {
            return;
        }
        
        String message = String.format("[MySB Debug] %s: %s - %s = %d", action, objectiveName, playerName, value);
        debug(message);
    }
    
    public static void debugScoreChange(String action, String objectiveName, String playerName, int oldValue, int newValue) {
        if (!ServerScoreboardConfig.DEBUG_MODE_ENABLED) {
            return;
        }
        
        String message = String.format("[MySB Debug] %s: %s - %s: %d → %d (変化: %+d)", 
            action, objectiveName, playerName, oldValue, newValue, (newValue - oldValue));
        debug(message);
    }
    
    private static void broadcastDebugMessage(String message) {
        if (server == null) return;
        
        Text debugText = Text.literal("[MySB Debug] ").formatted(Formatting.GRAY)
            .append(Text.literal(message).formatted(Formatting.AQUA));
        
        if (ServerScoreboardConfig.DEBUG_BROADCAST_TO_OPS) {
            // OP権限者にのみ送信
            for (ServerPlayerEntity player : server.getPlayerManager().getPlayerList()) {
                if (player.hasPermissionLevel(ServerScoreboardConfig.DEBUG_LOG_OP_LEVEL)) {
                    player.sendMessage(debugText, false);
                }
            }
        } else {
            // 全員に送信
            server.getPlayerManager().broadcast(debugText, false);
        }
    }
}