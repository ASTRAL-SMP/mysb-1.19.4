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
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.Inflater;
import java.util.zip.DataFormatException;
import java.util.function.Consumer;

public class SimpleDiscordBot implements WebSocket.Listener {
    private static SimpleDiscordBot instance;
    private final HttpClient httpClient = HttpClient.newBuilder().build();
    private ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(3);
    private final Map<String, ForumThreadInfo> forumThreads = new ConcurrentHashMap<>();
    private final Gson gson = new GsonBuilder().setPrettyPrinting().create();
    private final AtomicInteger sequence = new AtomicInteger(0);
    private final ByteArrayOutputStream buffer = new ByteArrayOutputStream();
    private final Inflater inflater = new Inflater();
    
    private String botToken;
    private String applicationId;
    private String forumChannelId;
    private MinecraftServer server;
    private boolean isRunning = false;
    private WebSocket webSocket;
    private String sessionId;
    private ScheduledFuture<?> heartbeatTask;
    
    private SimpleDiscordBot() {}
    
    public static SimpleDiscordBot getInstance() {
        if (instance == null) {
            instance = new SimpleDiscordBot();
        }
        return instance;
    }
    
    public void initialize(MinecraftServer server) {
        this.server = server;
        
        // schedulerが未作成またはシャットダウンされている場合は新規作成
        if (scheduler == null || scheduler.isShutdown()) {
            scheduler = Executors.newScheduledThreadPool(3);
        }
        
        // 設定からトークンを読み込む
        loadBotToken();
        if (botToken == null || botToken.isEmpty()) {
            ServerScoreboardLogger.error("Discord bot token not found in config");
            return;
        }
        
        // Bot情報を取得
        fetchBotInfo();
        
        // 設定を読み込む
        loadConfig();
        
        // スラッシュコマンドを登録
        registerSlashCommands();
        
        // Gateway WebSocketに接続
        connectToGateway();
        
        // 定期更新をスケジュール
        scheduleDaily5AM();
        
        // Gateway接続が完了するまで少し待つ
        scheduler.schedule(() -> {
            isRunning = true;
            ServerScoreboardLogger.info("Discord Bot initialization complete");
        }, 3, TimeUnit.SECONDS);
    }
    
    private void fetchBotInfo() {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("https://discord.com/api/v10/users/@me"))
                .header("Authorization", "Bot " + botToken)
                .GET()
                .build();
            
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 200) {
                JsonObject botInfo = JsonParser.parseString(response.body()).getAsJsonObject();
                applicationId = botInfo.get("id").getAsString();
                ServerScoreboardLogger.info("Bot connected as: " + botInfo.get("username").getAsString() + " (ID: " + applicationId + ")");
            } else {
                ServerScoreboardLogger.error("Failed to fetch bot info. Status code: " + response.statusCode() + ", Response: " + response.body());
            }
        } catch (Exception e) {
            ServerScoreboardLogger.error("Failed to fetch bot info: " + e.getMessage());
            e.printStackTrace();
        }
    }
    
    public void setForumChannel(String channelId) {
        this.forumChannelId = channelId;
        saveConfig();
    }
    
    public void addScoreboard(String objectiveName) {
        if (forumChannelId == null) {
            throw new IllegalStateException("フォーラムチャンネルが設定されていません");
        }
        
        // フォーラムにスレッドを作成
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
            message.addProperty("content", "このスレッドには毎朝5時に統計データが更新されます。");
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
                
                // 初回の統計を投稿
                updateScoreboardData(objectiveName);
            }
        } catch (Exception e) {
            ServerScoreboardLogger.error("Failed to create forum thread: " + e.getMessage());
        }
    }
    
    public void updateScoreboardData(String objectiveName) {
        ForumThreadInfo info = forumThreads.get(objectiveName);
        if (info == null || server == null) return;
        
        server.execute(() -> {
            ScoreboardObjective objective = server.getScoreboard().getObjective(objectiveName);
            if (objective == null) return;
            
            String data = getFormattedScoreboardDataForDiscord(objective);
            if (data.isEmpty()) {
                ServerScoreboardLogger.info("No data to display for objective: " + objectiveName);
                return;
            }
            
            String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm:ss"));
            
            // 表示名を取得（色コードを除去）
            String displayName = TotalStatsManager.getTotalDisplayName(objectiveName);
            if (displayName == null) {
                displayName = objective.getDisplayName().getString();
            }
            displayName = displayName.replaceAll("§[0-9a-fklmnor]", "");
            
            JsonObject embed = new JsonObject();
            embed.addProperty("title", "【" + displayName + "】");
            embed.addProperty("description", data);
            embed.addProperty("color", 0x5865F2);
            
            JsonObject footer = new JsonObject();
            footer.addProperty("text", "最終更新: " + timestamp);
            embed.add("footer", footer);
            
            JsonArray embeds = new JsonArray();
            embeds.add(embed);
            
            JsonObject payload = new JsonObject();
            payload.add("embeds", embeds);
            
            if (info.lastMessageId == null) {
                // 新規投稿
                sendMessage(info.threadId, payload.toString(), messageId -> {
                    info.lastMessageId = messageId;
                    saveConfig();
                });
            } else {
                // 既存メッセージを編集
                editMessage(info.threadId, info.lastMessageId, payload.toString());
            }
        });
    }
    
    private void sendMessage(String channelId, String payload, Consumer<String> onSuccess) {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("https://discord.com/api/v10/channels/" + channelId + "/messages"))
                .header("Authorization", "Bot " + botToken)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(payload))
                .build();
            
            httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .thenAccept(response -> {
                    if (response.statusCode() == 200) {
                        JsonObject message = JsonParser.parseString(response.body()).getAsJsonObject();
                        String messageId = message.get("id").getAsString();
                        onSuccess.accept(messageId);
                    }
                });
        } catch (Exception e) {
            ServerScoreboardLogger.error("Failed to send message: " + e.getMessage());
        }
    }
    
    private void editMessage(String channelId, String messageId, String payload) {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("https://discord.com/api/v10/channels/" + channelId + "/messages/" + messageId))
                .header("Authorization", "Bot " + botToken)
                .header("Content-Type", "application/json")
                .method("PATCH", HttpRequest.BodyPublishers.ofString(payload))
                .build();
            
            httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString());
        } catch (Exception e) {
            ServerScoreboardLogger.error("Failed to edit message: " + e.getMessage());
        }
    }
    
    private void scheduleDaily5AM() {
        Calendar calendar = Calendar.getInstance();
        calendar.set(Calendar.HOUR_OF_DAY, 5);
        calendar.set(Calendar.MINUTE, 0);
        calendar.set(Calendar.SECOND, 0);
        
        if (calendar.getTimeInMillis() < System.currentTimeMillis()) {
            calendar.add(Calendar.DAY_OF_MONTH, 1);
        }
        
        long initialDelay = calendar.getTimeInMillis() - System.currentTimeMillis();
        
        scheduler.scheduleAtFixedRate(() -> {
            for (String objectiveName : forumThreads.keySet()) {
                updateScoreboardData(objectiveName);
            }
        }, initialDelay, TimeUnit.DAYS.toMillis(1), TimeUnit.MILLISECONDS);
    }
    
    private void loadBotToken() {
        try {
            File configDir = new File("config/serverscoreboard");
            if (!configDir.exists()) {
                configDir.mkdirs();
            }
            
            File file = new File(configDir, "discord_bot.json");
            if (!file.exists()) {
                // デフォルトの設定ファイルを作成
                JsonObject defaultConfig = new JsonObject();
                defaultConfig.addProperty("token", "YOUR_DISCORD_BOT_TOKEN_HERE");
                
                try (FileWriter writer = new FileWriter(file)) {
                    gson.toJson(defaultConfig, writer);
                }
                
                ServerScoreboardLogger.info("Created default Discord bot config file at: " + file.getAbsolutePath());
                ServerScoreboardLogger.info("Please set your Discord bot token in: config/serverscoreboard/discord_bot.json");
                return;
            }
            
            try (FileReader reader = new FileReader(file)) {
                JsonObject config = gson.fromJson(reader, JsonObject.class);
                if (config.has("token")) {
                    botToken = config.get("token").getAsString();
                    if (botToken.equals("YOUR_DISCORD_BOT_TOKEN_HERE")) {
                        ServerScoreboardLogger.warn("Discord bot token is not configured. Please set your token in: config/serverscoreboard/discord_bot.json");
                        botToken = null;
                    }
                }
            }
        } catch (Exception e) {
            ServerScoreboardLogger.error("Failed to load bot token: " + e.getMessage());
        }
    }
    
    private void connectToGateway() {
        try {
            // Gateway URLを取得
            HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("https://discord.com/api/v10/gateway/bot"))
                .header("Authorization", "Bot " + botToken)
                .GET()
                .build();
            
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 200) {
                JsonObject gateway = JsonParser.parseString(response.body()).getAsJsonObject();
                String wsUrl = gateway.get("url").getAsString() + "?v=10&encoding=json";
                
                // WebSocket接続
                httpClient.newWebSocketBuilder()
                    .buildAsync(URI.create(wsUrl), this)
                    .thenAccept(ws -> {
                        this.webSocket = ws;
                        ServerScoreboardLogger.info("WebSocket connection established");
                    })
                    .exceptionally(ex -> {
                        ServerScoreboardLogger.error("Failed to establish WebSocket connection: " + ex.getMessage());
                        return null;
                    });
            }
        } catch (Exception e) {
            ServerScoreboardLogger.error("Failed to connect to Discord Gateway: " + e.getMessage());
        }
    }
    
    private void registerSlashCommands() {
        try {
            JsonArray commands = new JsonArray();
            
            // /scoreboard コマンド
            JsonObject scoreboardCmd = new JsonObject();
            scoreboardCmd.addProperty("name", "scoreboard");
            scoreboardCmd.addProperty("description", "スコアボードのデータを取得");
            JsonArray options = new JsonArray();
            JsonObject option = new JsonObject();
            option.addProperty("name", "objective");
            option.addProperty("description", "スコアボード名");
            option.addProperty("type", 3); // STRING
            option.addProperty("required", true);
            option.addProperty("autocomplete", true);
            options.add(option);
            scoreboardCmd.add("options", options);
            commands.add(scoreboardCmd);
            
            // /scoreboard-setchannel コマンド
            JsonObject setChannelCmd = new JsonObject();
            setChannelCmd.addProperty("name", "scoreboard-setchannel");
            setChannelCmd.addProperty("description", "フォーラムチャンネルを設定");
            JsonArray channelOptions = new JsonArray();
            JsonObject channelOption = new JsonObject();
            channelOption.addProperty("name", "channel");
            channelOption.addProperty("description", "フォーラムチャンネル");
            channelOption.addProperty("type", 7); // CHANNEL
            channelOption.addProperty("required", true);
            channelOptions.add(channelOption);
            setChannelCmd.add("options", channelOptions);
            commands.add(setChannelCmd);
            
            HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("https://discord.com/api/v10/applications/" + applicationId + "/commands"))
                .header("Authorization", "Bot " + botToken)
                .header("Content-Type", "application/json")
                .PUT(HttpRequest.BodyPublishers.ofString(commands.toString()))
                .build();
            
            httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (Exception e) {
            ServerScoreboardLogger.error("Failed to register slash commands: " + e.getMessage());
        }
    }
    
    // WebSocket callbacks
    @Override
    public void onOpen(WebSocket webSocket) {
        ServerScoreboardLogger.info("Connected to Discord Gateway");
        this.webSocket = webSocket;  // WebSocketインスタンスを保存
        webSocket.request(1);
    }
    
    @Override
    public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
        try {
            String message = data.toString();
            handleGatewayMessage(message);
        } catch (Exception e) {
            ServerScoreboardLogger.error("Error handling Gateway message: " + e.getMessage());
        }
        webSocket.request(1);
        return null;
    }
    
    @Override
    public void onError(WebSocket webSocket, Throwable error) {
        ServerScoreboardLogger.error("WebSocket error: " + error.getMessage());
    }
    
    private void handleGatewayMessage(String message) {
        JsonObject payload = JsonParser.parseString(message).getAsJsonObject();
        int op = payload.get("op").getAsInt();
        
        switch (op) {
            case 0: // Dispatch
                handleDispatch(payload);
                break;
            case 10: // Hello
                handleHello(payload);
                break;
            case 11: // Heartbeat ACK
                // 正常
                break;
        }
    }
    
    private void handleHello(JsonObject payload) {
        JsonObject d = payload.getAsJsonObject("d");
        int heartbeatInterval = d.get("heartbeat_interval").getAsInt();
        
        // Heartbeatを開始
        startHeartbeat(heartbeatInterval);
        
        // Identify送信
        sendIdentify();
    }
    
    private void startHeartbeat(int interval) {
        heartbeatTask = scheduler.scheduleAtFixedRate(() -> {
            if (webSocket != null) {
                JsonObject heartbeat = new JsonObject();
                heartbeat.addProperty("op", 1);
                heartbeat.add("d", sequence.get() == 0 ? JsonNull.INSTANCE : new JsonPrimitive(sequence.get()));
                webSocket.sendText(heartbeat.toString(), true);
            }
        }, interval, interval, TimeUnit.MILLISECONDS);
    }
    
    private void sendIdentify() {
        if (webSocket != null) {
            JsonObject identify = new JsonObject();
            identify.addProperty("op", 2);
            
            JsonObject d = new JsonObject();
            d.addProperty("token", botToken);
            d.addProperty("intents", 1 << 0 | 1 << 9); // GUILDS | GUILD_MESSAGES
            
            JsonObject properties = new JsonObject();
            properties.addProperty("os", "linux");
            properties.addProperty("browser", "mysb");
            properties.addProperty("device", "mysb");
            d.add("properties", properties);
            
            identify.add("d", d);
            webSocket.sendText(identify.toString(), true);
        }
    }
    
    private void handleDispatch(JsonObject payload) {
        String t = payload.get("t").getAsString();
        JsonObject d = payload.getAsJsonObject("d");
        sequence.set(payload.get("s").getAsInt());
        
        switch (t) {
            case "READY":
                sessionId = d.get("session_id").getAsString();
                ServerScoreboardLogger.info("Discord Bot ready - Session ID: " + sessionId);
                // READYイベントを受信したらBot IDを取得
                if (d.has("user")) {
                    JsonObject user = d.getAsJsonObject("user");
                    applicationId = user.get("id").getAsString();
                    ServerScoreboardLogger.info("Bot ID confirmed: " + applicationId);
                }
                break;
            case "INTERACTION_CREATE":
                handleInteraction(d);
                break;
        }
    }
    
    private void handleInteraction(JsonObject interaction) {
        int type = interaction.get("type").getAsInt();
        
        if (type == 2) { // APPLICATION_COMMAND
            handleSlashCommand(interaction);
        } else if (type == 4) { // APPLICATION_COMMAND_AUTOCOMPLETE
            handleAutocomplete(interaction);
        }
    }
    
    private void handleSlashCommand(JsonObject interaction) {
        String commandName = interaction.getAsJsonObject("data").get("name").getAsString();
        
        switch (commandName) {
            case "scoreboard":
                handleScoreboardCommand(interaction);
                break;
            case "scoreboard-setchannel":
                handleSetChannelCommand(interaction);
                break;
        }
    }
    
    private void handleScoreboardCommand(JsonObject interaction) {
        JsonObject data = interaction.getAsJsonObject("data");
        String objectiveName = data.getAsJsonArray("options").get(0).getAsJsonObject().get("value").getAsString();
        
        // Minecraftサーバーでスコアボードデータを取得
        server.execute(() -> {
            ScoreboardObjective objective = server.getScoreboard().getObjective(objectiveName);
            if (objective == null) {
                sendInteractionResponse(interaction, "指定されたスコアボードが見つかりません: " + objectiveName, true);
                return;
            }
            
            ForumThreadInfo threadInfo = forumThreads.get(objectiveName);
            if (threadInfo != null && forumChannelId != null) {
                // フォーラムスレッドへのリンクを返す
                String response = "スコアボードのデータはこちらで確認できます: <#" + threadInfo.threadId + ">";
                sendInteractionResponse(interaction, response, true);
                
                // データを更新
                updateScoreboardData(objectiveName);
            } else {
                // 直接データを返す
                String scoreboardData = getFormattedScoreboardDataForDiscord(objective);
                if (scoreboardData.isEmpty()) {
                    sendInteractionResponse(interaction, "表示するデータがありません。", true);
                    return;
                }
                
                String displayName = TotalStatsManager.getTotalDisplayName(objectiveName);
                if (displayName == null) {
                    displayName = objective.getDisplayName().getString();
                }
                // 色コードを除去
                displayName = displayName.replaceAll("§[0-9a-fklmnor]", "");
                
                JsonObject embed = new JsonObject();
                embed.addProperty("title", "【" + displayName + "】");
                embed.addProperty("description", scoreboardData);
                embed.addProperty("color", 0x5865F2);
                
                sendInteractionResponseWithEmbed(interaction, embed, true);
            }
        });
    }
    
    private void handleSetChannelCommand(JsonObject interaction) {
        JsonObject data = interaction.getAsJsonObject("data");
        String channelId = data.getAsJsonArray("options").get(0).getAsJsonObject().get("value").getAsString();
        
        setForumChannel(channelId);
        sendInteractionResponse(interaction, "フォーラムチャンネルを設定しました: <#" + channelId + ">", false);
    }
    
    private void handleAutocomplete(JsonObject interaction) {
        JsonObject data = interaction.getAsJsonObject("data");
        String commandName = data.get("name").getAsString();
        
        if (commandName.equals("scoreboard")) {
            JsonArray choices = new JsonArray();
            
            // Minecraftのスコアボードを取得
            if (server != null) {
                Collection<ScoreboardObjective> objectives = server.getScoreboard().getObjectives();
                String focused = data.getAsJsonArray("options").get(0).getAsJsonObject().get("value").getAsString().toLowerCase();
                
                objectives.stream()
                    .filter(obj -> obj.getName().toLowerCase().contains(focused))
                    .limit(25)
                    .forEach(obj -> {
                        JsonObject choice = new JsonObject();
                        choice.addProperty("name", obj.getDisplayName().getString() + " (" + obj.getName() + ")");
                        choice.addProperty("value", obj.getName());
                        choices.add(choice);
                    });
            }
            
            sendAutocompleteResponse(interaction, choices);
        }
    }
    
    private void sendInteractionResponse(JsonObject interaction, String content, boolean ephemeral) {
        JsonObject response = new JsonObject();
        response.addProperty("type", 4); // CHANNEL_MESSAGE_WITH_SOURCE
        
        JsonObject data = new JsonObject();
        data.addProperty("content", content);
        if (ephemeral) {
            data.addProperty("flags", 64); // EPHEMERAL
        }
        response.add("data", data);
        
        sendInteractionCallback(interaction, response);
    }
    
    private void sendInteractionResponseWithEmbed(JsonObject interaction, JsonObject embed, boolean ephemeral) {
        JsonObject response = new JsonObject();
        response.addProperty("type", 4); // CHANNEL_MESSAGE_WITH_SOURCE
        
        JsonObject data = new JsonObject();
        JsonArray embeds = new JsonArray();
        embeds.add(embed);
        data.add("embeds", embeds);
        if (ephemeral) {
            data.addProperty("flags", 64); // EPHEMERAL
        }
        response.add("data", data);
        
        sendInteractionCallback(interaction, response);
    }
    
    private void sendAutocompleteResponse(JsonObject interaction, JsonArray choices) {
        JsonObject response = new JsonObject();
        response.addProperty("type", 8); // APPLICATION_COMMAND_AUTOCOMPLETE_RESULT
        
        JsonObject data = new JsonObject();
        data.add("choices", choices);
        response.add("data", data);
        
        sendInteractionCallback(interaction, response);
    }
    
    private void sendInteractionCallback(JsonObject interaction, JsonObject response) {
        String interactionId = interaction.get("id").getAsString();
        String interactionToken = interaction.get("token").getAsString();
        
        try {
            HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("https://discord.com/api/v10/interactions/" + interactionId + "/" + interactionToken + "/callback"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(response.toString()))
                .build();
            
            httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString());
        } catch (Exception e) {
            ServerScoreboardLogger.error("Failed to send interaction response: " + e.getMessage());
        }
    }
    
    private void saveConfig() {
        try {
            File configDir = new File("config/serverscoreboard");
            if (!configDir.exists()) configDir.mkdirs();
            
            File file = new File(configDir, "discord_bot_config.json");
            JsonObject root = new JsonObject();
            root.addProperty("forumChannelId", forumChannelId);
            
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
            if (!file.exists()) return;
            
            try (FileReader reader = new FileReader(file)) {
                JsonObject root = gson.fromJson(reader, JsonObject.class);
                
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
                        
                        forumThreads.put(objectiveName, 
                            new ForumThreadInfo(objectiveName, threadId, lastMessageId));
                    }
                }
            }
        } catch (Exception e) {
            ServerScoreboardLogger.error("Failed to load config: " + e.getMessage());
        }
    }
    
    public Map<String, ForumThreadInfo> getForumThreads() {
        return new HashMap<>(forumThreads);
    }
    
    public String getForumChannelId() {
        return forumChannelId;
    }
    
    public boolean isRunning() {
        return isRunning;
    }
    
    public void shutdown() {
        isRunning = false;
        
        // Heartbeatタスクをキャンセル
        if (heartbeatTask != null) {
            heartbeatTask.cancel(true);
            heartbeatTask = null;
        }
        
        // WebSocketを閉じる
        if (webSocket != null) {
            try {
                webSocket.sendClose(WebSocket.NORMAL_CLOSURE, "Shutting down").join();
            } catch (Exception e) {
                // 無視
            }
            webSocket = null;
        }
        
        // スケジューラーをシャットダウン
        if (scheduler != null && !scheduler.isShutdown()) {
            scheduler.shutdown();
            try {
                // 5秒待機してタスクの完了を待つ
                if (!scheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                    scheduler.shutdownNow();
                }
            } catch (InterruptedException e) {
                scheduler.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }
    }
    
    public void reload(MinecraftServer server) {
        // 既存の接続をシャットダウン
        shutdown();
        
        // 新しいスケジューラーを作成（shutdown()でシャットダウンされるため常に新規作成）
        scheduler = Executors.newScheduledThreadPool(3);
        
        // 少し待機してから再初期化
        scheduler.schedule(() -> {
            initialize(server);
        }, 1, TimeUnit.SECONDS);
    }
    
    private String getFormattedScoreboardDataForDiscord(ScoreboardObjective objective) {
        if (server == null) return "";
        
        // スコアボードのエントリを収集
        Map<String, Integer> scores = new LinkedHashMap<>();
        int serverTotal = 0;
        
        // 統計タイプを取得
        String statType = null;
        if (objective.getName().startsWith("total_")) {
            statType = objective.getName().substring(6); // "total_" を除去
            if (!TotalStatsManager.getEnabledStats().contains(statType)) {
                return ""; // 無効な統計
            }
            
            // キャッシュされたデータからも取得
            Map<String, Integer> cachedStats = PlayerStatsCache.getAllPlayerStats(statType);
            for (Map.Entry<String, Integer> entry : cachedStats.entrySet()) {
                String playerName = entry.getKey();
                if (!TotalStatsManager.isPlayerExcluded(playerName) && entry.getValue() > 0) {
                    scores.put(playerName, entry.getValue());
                    serverTotal += entry.getValue();
                }
            }
        } else {
            // 通常のスコアボードの場合
            for (String playerName : server.getScoreboard().getKnownPlayers()) {
                ScoreboardPlayerScore score = server.getScoreboard().getPlayerScore(playerName, objective);
                if (score != null && score.getScore() > 0) {
                    // 除外プレイヤーはスキップ
                    if (!TotalStatsManager.isPlayerExcluded(playerName)) {
                        scores.put(playerName, score.getScore());
                        serverTotal += score.getScore();
                    }
                }
            }
        }
        
        if (scores.isEmpty() && serverTotal == 0) {
            return "";
        }
        
        // スコア順にソート（降順）
        List<Map.Entry<String, Integer>> sortedScores = new ArrayList<>(scores.entrySet());
        sortedScores.sort(Map.Entry.<String, Integer>comparingByValue().reversed());
        
        // フォーマット
        StringBuilder sb = new StringBuilder();
        sb.append("```\n");
        
        // サーバー合計を最上部に表示（$SERVER_TOTALで左揃え）
        if (objective.getName().contains("play_time")) {
            int totalMinutes = serverTotal / 20 / 60; // ticks -> minutes
            int hours = totalMinutes / 60;
            int minutes = totalMinutes % 60;
            sb.append(String.format("$SERVER_TOTAL%16s\n", String.format("%dh %dm", hours, minutes)));
        } else {
            sb.append(String.format("$SERVER_TOTAL%16d\n", serverTotal));
        }
        
        // 横線を追加
        sb.append("─────────────────────────────\n");
        
        // プレイヤースコア（スコア順）
        for (Map.Entry<String, Integer> entry : sortedScores) {
            String playerName = entry.getKey();
            int score = entry.getValue();
            
            // play_timeの場合は時間フォーマット
            if (objective.getName().contains("play_time")) {
                int totalMinutes = score / 20 / 60; // ticks -> minutes
                int hours = totalMinutes / 60;
                int minutes = totalMinutes % 60;
                sb.append(String.format("%-16s%13s\n", playerName, String.format("%dh %dm", hours, minutes)));
            } else {
                sb.append(String.format("%-16s%13d\n", playerName, score));
            }
        }
        
        sb.append("```");
        return sb.toString();
    }
    
    public static class ForumThreadInfo {
        public final String objectiveName;
        public final String threadId;
        public String lastMessageId;
        
        ForumThreadInfo(String objectiveName, String threadId, String lastMessageId) {
            this.objectiveName = objectiveName;
            this.threadId = threadId;
            this.lastMessageId = lastMessageId;
        }
    }
}