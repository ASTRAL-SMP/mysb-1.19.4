package com.scserver.serverscoreboard;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

public class NetworkLoadMonitor {
    private static final AtomicLong totalPacketsSent = new AtomicLong(0);
    private static final AtomicLong totalBytesEstimated = new AtomicLong(0);
    private static final AtomicInteger currentTps = new AtomicInteger(20);
    
    // 負荷計算用の時間窓
    private static final long WINDOW_SIZE_MS = 5000; // 5秒間
    private static volatile long windowStart = System.currentTimeMillis();
    private static final AtomicLong windowPackets = new AtomicLong(0);
    
    // しきい値設定
    private static final int HIGH_LOAD_THRESHOLD = 70; // 高負荷しきい値（%）
    private static final int CRITICAL_LOAD_THRESHOLD = 90; // 危険負荷しきい値（%）
    
    public static void recordPacketSent(int estimatedBytes) {
        totalPacketsSent.incrementAndGet();
        totalBytesEstimated.addAndGet(estimatedBytes);
        
        // 現在の時間窓での統計を更新
        long currentTime = System.currentTimeMillis();
        if (currentTime - windowStart > WINDOW_SIZE_MS) {
            // 新しい時間窓を開始
            windowStart = currentTime;
            windowPackets.set(1);
        } else {
            windowPackets.incrementAndGet();
        }
    }
    
    public static int getCurrentLoadPercentage() {
        long currentTime = System.currentTimeMillis();
        if (currentTime - windowStart > WINDOW_SIZE_MS) {
            return 0; // 時間窓が古い場合は負荷0%
        }
        
        long packetsInWindow = windowPackets.get();
        long windowDurationMs = currentTime - windowStart;
        
        if (windowDurationMs == 0) return 0;
        
        // パケット/秒を計算
        double packetsPerSecond = (double) packetsInWindow / (windowDurationMs / 1000.0);
        
        // 基準値に対する負荷率を計算（基準: 100パケット/秒 = 100%負荷）
        int loadPercentage = (int) Math.min(100, (packetsPerSecond / 100.0) * 100);
        return loadPercentage;
    }
    
    public static LoadLevel getCurrentLoadLevel() {
        int loadPercentage = getCurrentLoadPercentage();
        
        if (loadPercentage >= CRITICAL_LOAD_THRESHOLD) {
            return LoadLevel.CRITICAL;
        } else if (loadPercentage >= HIGH_LOAD_THRESHOLD) {
            return LoadLevel.HIGH;
        } else if (loadPercentage >= 30) {
            return LoadLevel.MEDIUM;
        } else {
            return LoadLevel.LOW;
        }
    }
    
    public static boolean shouldThrottlePackets() {
        return getCurrentLoadLevel() == LoadLevel.CRITICAL;
    }
    
    public static int getRecommendedRateLimit() {
        LoadLevel level = getCurrentLoadLevel();
        switch (level) {
            case CRITICAL:
                return 5;  // 非常に制限的
            case HIGH:
                return 15; // 制限的
            case MEDIUM:
                return 30; // やや制限的
            case LOW:
            default:
                return 50; // 通常
        }
    }
    
    public static void updateTps(int tps) {
        currentTps.set(tps);
        
        // TPS低下時は負荷が高いと判断
        if (tps < 15) {
            // 強制的に高負荷状態として扱う
            recordPacketSent(0); // ダミーパケットで負荷を上げる
        }
    }
    
    public static String getNetworkStatistics() {
        return String.format(
            "Network Load: %d%% | Load Level: %s | Total Packets: %d | Estimated Bytes: %dKB | TPS: %d | Recommended Rate Limit: %d",
            getCurrentLoadPercentage(),
            getCurrentLoadLevel(),
            totalPacketsSent.get(),
            totalBytesEstimated.get() / 1024,
            currentTps.get(),
            getRecommendedRateLimit()
        );
    }
    
    public static void resetStatistics() {
        totalPacketsSent.set(0);
        totalBytesEstimated.set(0);
        windowStart = System.currentTimeMillis();
        windowPackets.set(0);
        ServerScoreboardLogger.info("Network statistics reset");
    }
    
    public enum LoadLevel {
        LOW, MEDIUM, HIGH, CRITICAL
    }
}