package com.scserver.serverscoreboard;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import net.minecraft.server.MinecraftServer;

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
    
    
}