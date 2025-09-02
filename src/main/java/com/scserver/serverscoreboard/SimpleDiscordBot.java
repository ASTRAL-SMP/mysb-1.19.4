package com.scserver.serverscoreboard;

import com.google.gson.*;
import net.minecraft.scoreboard.ScoreboardObjective;
import net.minecraft.scoreboard.ScoreboardPlayerScore;
import net.minecraft.server.MinecraftServer;

import java.io.*;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.*;

/**
 * 軽量版Discord Bot
 * WebSocket接続、スラッシュコマンドを削除し、HTTP APIのみを使用
 * フォーラム投稿、メッセージ編集機能は維持
 */
public class SimpleDiscordBot {
    private static SimpleDiscordBot instance;
    private final HttpClient httpClient = HttpClient.newBuilder().build();
    private ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(1);
    private final Map<String, ForumThreadInfo> forumThreads = new ConcurrentHashMap<>();
    private final Gson gson = new GsonBuilder().setPrettyPrinting().create();
    
    private String botToken;
    private String forumChannelId;
    private MinecraftServer server;
    private volatile boolean isRunning = false;
    private ScheduledFuture<?> updateTask;
    
    private SimpleDiscordBot() {}
    
    public static SimpleDiscordBot getInstance() {
        if (instance == null) {
            instance = new SimpleDiscordBot();
        }
        return instance;
    }
    
    public void start(MinecraftServer minecraftServer) {
        this.server = minecraftServer;
        loadConfig();
        
        if (botToken == null || botToken.isEmpty()) {
            ServerScoreboardLogger.info("Discord Bot token not configured - Discord features disabled");
            return;
        }
        
        // 新しいScheduledExecutorServiceを作成
        if (scheduler == null || scheduler.isShutdown()) {
            scheduler = Executors.newScheduledThreadPool(1);
        }
        
        isRunning = true;
        ServerScoreboardLogger.info("Lightweight Discord Bot started");
        
        // 古いスコアボードデータのクリーンアップ
        cleanupInvalidObjectives();
        
        // 1時間ごとの定期更新を開始
        scheduleHourlyUpdates();
    }
    
    public void stop() {
        if (updateTask != null) {
            updateTask.cancel(false);
        }
        if (scheduler != null && !scheduler.isShutdown()) {
            scheduler.shutdown();
            try {
                if (!scheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                    scheduler.shutdownNow();
                    ServerScoreboardLogger.warn("Discord Bot scheduler forced shutdown");
                }
            } catch (InterruptedException e) {
                scheduler.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }
        isRunning = false;
        ServerScoreboardLogger.info("Lightweight Discord Bot stopped");
    }
    
    public void reload(MinecraftServer minecraftServer) {
        ServerScoreboardLogger.info("Reloading Lightweight Discord Bot...");
        stop();
        start(minecraftServer);
    }
    
    private void scheduleHourlyUpdates() {
        // 毎時0分に実行（1時間間隔）
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime nextHour = now.plusHours(1).withMinute(0).withSecond(0).withNano(0);
        long initialDelay = java.time.Duration.between(now, nextHour).toMillis();
        
        updateTask = scheduler.scheduleAtFixedRate(() -> {
            try {
                updateAllScoreboards();
            } catch (Exception e) {
                ServerScoreboardLogger.error("Error during hourly Discord update", e);
            }
        }, initialDelay, TimeUnit.HOURS.toMillis(1), TimeUnit.MILLISECONDS);
        
        ServerScoreboardLogger.info("Scheduled hourly Discord updates (next update: " + 
            nextHour.format(DateTimeFormatter.ofPattern("HH:mm")) + ")");
    }
    
    public void addScoreboard(String objectiveName) {
        if (forumChannelId == null || forumChannelId.isEmpty()) {
            throw new IllegalStateException("フォーラムチャンネルが設定されていません");
        }
        
        createForumThread(objectiveName);
    }
    
    public void removeScoreboard(String objectiveName) {
        forumThreads.remove(objectiveName);
        saveConfig();
    }
    
    private void createForumThread(String objectiveName) {
        try {
            JsonObject payload = new JsonObject();
            payload.addProperty("name", objectiveName + " - 統計データ");
            
            // 初期メッセージ
            JsonObject message = new JsonObject();
            message.addProperty("content", "このスレッドには毎時統計データが更新されます。");
            payload.add("message", message);
            
            HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("https://discord.com/api/v10/channels/" + forumChannelId + "/threads"))
                .header("Authorization", "Bot " + botToken)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(payload.toString()))
                .build();
            
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 201) {
                JsonObject thread = JsonParser.parseString(response.body()).getAsJsonObject();
                String threadId = thread.get("id").getAsString();
                
                ForumThreadInfo info = new ForumThreadInfo(objectiveName, threadId, null);
                forumThreads.put(objectiveName, info);
                saveConfig();
                
                ServerScoreboardLogger.info("Created forum thread for: " + objectiveName);
                
                // 初回の統計を投稿
                updateScoreboardData(objectiveName);
            } else {
                ServerScoreboardLogger.error("Failed to create forum thread. Status: " + response.statusCode() + 
                    ", Body: " + response.body());
            }
        } catch (Exception e) {
            ServerScoreboardLogger.error("Failed to create forum thread: " + e.getMessage());
        }
    }
    
    public void updateScoreboardData(String objectiveName) {
        ForumThreadInfo info = forumThreads.get(objectiveName);
        if (info == null) {
            ServerScoreboardLogger.warn("Forum thread not found for objective: " + objectiveName);
            return;
        }
        
        try {
            String scoreboardData = generateScoreboardData(objectiveName);
            if (scoreboardData == null) {
                return;
            }
            
            if (info.lastMessageId != null) {
                // メッセージを編集（通知なし）
                editMessage(info.threadId, info.lastMessageId, scoreboardData);
            } else {
                // 新規メッセージを投稿
                String messageId = sendMessage(info.threadId, scoreboardData);
                if (messageId != null) {
                    info.lastMessageId = messageId;
                    saveConfig();
                }
            }
            
        } catch (Exception e) {
            ServerScoreboardLogger.error("Failed to update scoreboard data for " + objectiveName, e);
        }
    }
    
    private String sendMessage(String channelId, String content) {
        try {
            JsonObject payload = new JsonObject();
            payload.addProperty("content", content);
            
            HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("https://discord.com/api/v10/channels/" + channelId + "/messages"))
                .header("Authorization", "Bot " + botToken)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(payload.toString()))
                .build();
            
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 200) {
                JsonObject responseJson = JsonParser.parseString(response.body()).getAsJsonObject();
                return responseJson.get("id").getAsString();
            }
            
        } catch (Exception e) {
            ServerScoreboardLogger.error("Failed to send message", e);
        }
        return null;
    }
    
    private void editMessage(String channelId, String messageId, String newContent) {
        try {
            JsonObject payload = new JsonObject();
            payload.addProperty("content", newContent);
            
            HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("https://discord.com/api/v10/channels/" + channelId + "/messages/" + messageId))
                .header("Authorization", "Bot " + botToken)
                .header("Content-Type", "application/json")
                .method("PATCH", HttpRequest.BodyPublishers.ofString(payload.toString()))
                .build();
            
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                ServerScoreboardLogger.error("Failed to edit message. Status: " + response.statusCode());
            }
            
        } catch (Exception e) {
            ServerScoreboardLogger.error("Failed to edit message", e);
        }
    }
    
    private String generateScoreboardData(String objectiveName) {
        if (server == null) {
            return null;
        }
        
        ScoreboardObjective objective = server.getScoreboard().getObjective(objectiveName);
        if (objective == null) {
            ServerScoreboardLogger.warn("Objective not found: " + objectiveName);
            return null;
        }
        
        StringBuilder content = new StringBuilder();
        content.append("**").append(objective.getDisplayName().getString()).append("**\n");
        content.append("更新時刻: ").append(LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm"))).append("\n\n");
        
        // スコアを取得してソート
        Collection<ScoreboardPlayerScore> scores = server.getScoreboard().getAllPlayerScores(objective);
        List<ScoreboardPlayerScore> sortedScores = new ArrayList<>(scores);
        sortedScores.sort((a, b) -> Integer.compare(b.getScore(), a.getScore()));
        
        // 上位20位まで表示
        int count = 0;
        content.append("```\n");
        for (ScoreboardPlayerScore score : sortedScores) {
            if (count >= 20) break;
            if (score.getScore() > 0) {
                content.append(String.format("%-20s %,d\n", score.getPlayerName(), score.getScore()));
                count++;
            }
        }
        content.append("```");
        
        return content.toString();
    }
    
    private void updateAllScoreboards() {
        if (!isRunning || forumThreads.isEmpty()) {
            return;
        }
        
        ServerScoreboardLogger.info("Starting hourly Discord scoreboard updates...");
        
        for (String objectiveName : forumThreads.keySet()) {
            updateScoreboardData(objectiveName);
            
            // レート制限対策で少し待機
            try {
                Thread.sleep(1000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        
        ServerScoreboardLogger.info("Completed hourly Discord scoreboard updates");
    }
    
    public void setForumChannel(String channelId) {
        this.forumChannelId = channelId;
        saveConfig();
        ServerScoreboardLogger.info("Forum channel set to: " + channelId);
    }
    
    public String getForumChannelId() {
        return forumChannelId;
    }
    
    public Map<String, ForumThreadInfo> getForumThreads() {
        return new HashMap<>(forumThreads);
    }
    
    public boolean isRunning() {
        return isRunning && botToken != null && !botToken.isEmpty();
    }
    
    private void saveConfig() {
        try {
            File dir = new File("config/serverscoreboard");
            if (!dir.exists()) {
                dir.mkdirs();
            }
            
            File file = new File(dir, "discord_bot_config.json");
            JsonObject root = new JsonObject();
            
            root.addProperty("botToken", botToken != null ? botToken : "");
            root.addProperty("forumChannelId", forumChannelId != null ? forumChannelId : "");
            
            JsonArray threads = new JsonArray();
            for (Map.Entry<String, ForumThreadInfo> entry : forumThreads.entrySet()) {
                JsonObject thread = new JsonObject();
                thread.addProperty("objectiveName", entry.getKey());
                thread.addProperty("threadId", entry.getValue().threadId);
                if (entry.getValue().lastMessageId != null) {
                    thread.addProperty("lastMessageId", entry.getValue().lastMessageId);
                }
                threads.add(thread);
            }
            root.add("forumThreads", threads);
            
            try (FileWriter writer = new FileWriter(file)) {
                gson.toJson(root, writer);
            }
        } catch (Exception e) {
            ServerScoreboardLogger.error("Failed to save config: " + e.getMessage());
        }
    }
    
    private void loadConfig() {
        try {
            File file = new File("config/serverscoreboard/discord_bot_config.json");
            if (!file.exists()) {
                loadLegacyConfig(); // 既存の設定ファイルから読み込み
                return;
            }
            
            try (FileReader reader = new FileReader(file)) {
                JsonObject root = JsonParser.parseReader(reader).getAsJsonObject();
                
                if (root.has("botToken")) {
                    botToken = root.get("botToken").getAsString();
                }
                if (root.has("forumChannelId")) {
                    forumChannelId = root.get("forumChannelId").getAsString();
                }
                
                if (root.has("forumThreads")) {
                    JsonArray threads = root.getAsJsonArray("forumThreads");
                    for (JsonElement element : threads) {
                        JsonObject thread = element.getAsJsonObject();
                        String objectiveName = thread.get("objectiveName").getAsString();
                        String threadId = thread.get("threadId").getAsString();
                        String lastMessageId = thread.has("lastMessageId") ? 
                            thread.get("lastMessageId").getAsString() : null;
                        
                        ForumThreadInfo info = new ForumThreadInfo(objectiveName, threadId, lastMessageId);
                        forumThreads.put(objectiveName, info);
                    }
                }
            }
        } catch (Exception e) {
            ServerScoreboardLogger.error("Failed to load config: " + e.getMessage());
        }
    }
    
    private void loadLegacyConfig() {
        try {
            File file = new File("config/serverscoreboard/discord_bot.json");
            if (!file.exists()) return;
            
            try (FileReader reader = new FileReader(file)) {
                JsonObject root = JsonParser.parseReader(reader).getAsJsonObject();
                if (root.has("token")) {
                    botToken = root.get("token").getAsString();
                }
            }
        } catch (Exception e) {
            ServerScoreboardLogger.error("Failed to load legacy config: " + e.getMessage());
        }
    }
    
    private void cleanupInvalidObjectives() {
        if (server == null) {
            return;
        }
        
        // 現在有効な統計オブジェクティブを取得
        Set<String> validObjectives = new HashSet<>();
        var scoreboard = server.getScoreboard();
        
        // サーバーのスコアボードに存在するオブジェクティブのみを有効とする
        for (var objective : scoreboard.getObjectives()) {
            validObjectives.add(objective.getName());
        }
        
        // 無効なオブジェクティブを削除
        Set<String> toRemove = new HashSet<>();
        for (String objectiveName : forumThreads.keySet()) {
            if (!validObjectives.contains(objectiveName)) {
                toRemove.add(objectiveName);
                ServerScoreboardLogger.info("Removing invalid Discord objective: " + objectiveName);
            }
        }
        
        // 削除実行
        for (String objectiveName : toRemove) {
            forumThreads.remove(objectiveName);
        }
        
        // 変更があった場合は保存
        if (!toRemove.isEmpty()) {
            saveConfig();
            ServerScoreboardLogger.info("Cleaned up " + toRemove.size() + " invalid Discord objectives");
        }
    }
    
    public static class ForumThreadInfo {
        public final String objectiveName;
        public final String threadId;
        public String lastMessageId;
        
        public ForumThreadInfo(String objectiveName, String threadId, String lastMessageId) {
            this.objectiveName = objectiveName;
            this.threadId = threadId;
            this.lastMessageId = lastMessageId;
        }
    }
}