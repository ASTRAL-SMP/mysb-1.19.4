package com.scserver.serverscoreboard.discord;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.scserver.serverscoreboard.ServerScoreboardLogger;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Discord REST APIを呼び出すマネージャークラス
 * Java標準のHttpClientを使用（JDA不要で軽量）
 */
public class DiscordManager {
    private static final String DISCORD_API_BASE = "https://discord.com/api/v10";
    private static final Gson gson = new Gson();

    private static HttpClient httpClient;
    private static final AtomicBoolean initialized = new AtomicBoolean(false);
    private static final AtomicBoolean connectionVerified = new AtomicBoolean(false);

    /**
     * HttpClientを初期化（遅延初期化）
     */
    private static void ensureInitialized() {
        if (!initialized.get()) {
            synchronized (DiscordManager.class) {
                if (!initialized.get()) {
                    httpClient = HttpClient.newBuilder()
                        .connectTimeout(Duration.ofSeconds(10))
                        .build();
                    initialized.set(true);
                    ServerScoreboardLogger.info("Discord HttpClient initialized");
                }
            }
        }
    }

    /**
     * Discord接続をテスト（Bot情報を取得）
     */
    public static CompletableFuture<Boolean> testConnection() {
        return CompletableFuture.supplyAsync(() -> {
            String token = DiscordConfig.getToken();
            if (token == null) {
                ServerScoreboardLogger.warn("Discord token not configured");
                return false;
            }

            ensureInitialized();

            try {
                HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(DISCORD_API_BASE + "/users/@me"))
                    .header("Authorization", "Bot " + token)
                    .header("Content-Type", "application/json")
                    .GET()
                    .timeout(Duration.ofSeconds(10))
                    .build();

                HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

                if (response.statusCode() == 200) {
                    JsonObject json = JsonParser.parseString(response.body()).getAsJsonObject();
                    String botName = json.has("username") ? json.get("username").getAsString() : "Unknown";
                    ServerScoreboardLogger.info("Discord bot connected: " + botName);
                    connectionVerified.set(true);
                    return true;
                } else {
                    ServerScoreboardLogger.error("Discord connection failed: HTTP " + response.statusCode());
                    connectionVerified.set(false);
                    return false;
                }
            } catch (Exception e) {
                ServerScoreboardLogger.error("Discord connection error", e);
                connectionVerified.set(false);
                return false;
            }
        });
    }

    /**
     * フォーラムチャンネルにスレッドを作成
     * @return スレッドID（失敗時はnull）
     */
    public static CompletableFuture<String> createForumThread(String channelId, String threadName, String initialMessage) {
        return CompletableFuture.supplyAsync(() -> {
            String token = DiscordConfig.getToken();
            if (token == null) {
                return null;
            }

            ensureInitialized();

            try {
                // フォーラムスレッド作成用のJSONを構築
                JsonObject messageObj = new JsonObject();
                messageObj.addProperty("content", initialMessage);

                JsonObject requestBody = new JsonObject();
                requestBody.addProperty("name", threadName);
                requestBody.add("message", messageObj);

                HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(DISCORD_API_BASE + "/channels/" + channelId + "/threads"))
                    .header("Authorization", "Bot " + token)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(gson.toJson(requestBody)))
                    .timeout(Duration.ofSeconds(30))
                    .build();

                HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

                if (response.statusCode() == 201 || response.statusCode() == 200) {
                    JsonObject json = JsonParser.parseString(response.body()).getAsJsonObject();
                    String threadId = json.get("id").getAsString();
                    ServerScoreboardLogger.info("Created forum thread: " + threadName + " (ID: " + threadId + ")");
                    return threadId;
                } else {
                    ServerScoreboardLogger.error("Failed to create forum thread: HTTP " + response.statusCode() + " - " + response.body());
                    return null;
                }
            } catch (Exception e) {
                ServerScoreboardLogger.error("Error creating forum thread", e);
                return null;
            }
        });
    }

    /**
     * スレッドにメッセージを投稿
     * @return メッセージID（失敗時はnull）
     */
    public static CompletableFuture<String> sendMessage(String channelId, String content) {
        return CompletableFuture.supplyAsync(() -> {
            String token = DiscordConfig.getToken();
            if (token == null) {
                return null;
            }

            ensureInitialized();

            try {
                JsonObject requestBody = new JsonObject();
                requestBody.addProperty("content", content);

                HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(DISCORD_API_BASE + "/channels/" + channelId + "/messages"))
                    .header("Authorization", "Bot " + token)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(gson.toJson(requestBody)))
                    .timeout(Duration.ofSeconds(30))
                    .build();

                HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

                if (response.statusCode() == 200 || response.statusCode() == 201) {
                    JsonObject json = JsonParser.parseString(response.body()).getAsJsonObject();
                    return json.get("id").getAsString();
                } else {
                    ServerScoreboardLogger.error("Failed to send message: HTTP " + response.statusCode() + " - " + response.body());
                    return null;
                }
            } catch (Exception e) {
                ServerScoreboardLogger.error("Error sending message", e);
                return null;
            }
        });
    }

    /**
     * メッセージを編集
     */
    public static CompletableFuture<Boolean> editMessage(String channelId, String messageId, String newContent) {
        return CompletableFuture.supplyAsync(() -> {
            String token = DiscordConfig.getToken();
            if (token == null) {
                return false;
            }

            ensureInitialized();

            try {
                JsonObject requestBody = new JsonObject();
                requestBody.addProperty("content", newContent);

                HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(DISCORD_API_BASE + "/channels/" + channelId + "/messages/" + messageId))
                    .header("Authorization", "Bot " + token)
                    .header("Content-Type", "application/json")
                    .method("PATCH", HttpRequest.BodyPublishers.ofString(gson.toJson(requestBody)))
                    .timeout(Duration.ofSeconds(30))
                    .build();

                HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

                if (response.statusCode() == 200) {
                    return true;
                } else {
                    ServerScoreboardLogger.error("Failed to edit message: HTTP " + response.statusCode() + " - " + response.body());
                    return false;
                }
            } catch (Exception e) {
                ServerScoreboardLogger.error("Error editing message", e);
                return false;
            }
        });
    }

    /**
     * メッセージを削除
     */
    public static CompletableFuture<Boolean> deleteMessage(String channelId, String messageId) {
        return CompletableFuture.supplyAsync(() -> {
            String token = DiscordConfig.getToken();
            if (token == null) {
                return false;
            }

            ensureInitialized();

            try {
                HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(DISCORD_API_BASE + "/channels/" + channelId + "/messages/" + messageId))
                    .header("Authorization", "Bot " + token)
                    .header("Content-Type", "application/json")
                    .DELETE()
                    .timeout(Duration.ofSeconds(30))
                    .build();

                HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

                // 204 = 削除成功, 404 = 既に存在しない（問題なし）
                if (response.statusCode() == 204 || response.statusCode() == 404) {
                    return true;
                } else {
                    ServerScoreboardLogger.error("Failed to delete message: HTTP " + response.statusCode() + " - " + response.body());
                    return false;
                }
            } catch (Exception e) {
                ServerScoreboardLogger.error("Error deleting message", e);
                return false;
            }
        });
    }

    /**
     * スレッドが存在するかチェック
     */
    public static CompletableFuture<Boolean> threadExists(String threadId) {
        return CompletableFuture.supplyAsync(() -> {
            String token = DiscordConfig.getToken();
            if (token == null || threadId == null || threadId.isEmpty()) {
                return false;
            }

            ensureInitialized();

            try {
                HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(DISCORD_API_BASE + "/channels/" + threadId))
                    .header("Authorization", "Bot " + token)
                    .header("Content-Type", "application/json")
                    .GET()
                    .timeout(Duration.ofSeconds(10))
                    .build();

                HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

                return response.statusCode() == 200;
            } catch (Exception e) {
                ServerScoreboardLogger.error("Error checking thread existence", e);
                return false;
            }
        });
    }

    /**
     * スレッドの最初のメッセージIDを取得
     */
    public static CompletableFuture<String> getFirstMessageId(String threadId) {
        return CompletableFuture.supplyAsync(() -> {
            String token = DiscordConfig.getToken();
            if (token == null) {
                return null;
            }

            ensureInitialized();

            try {
                // after=0 で最も古いメッセージから取得（Snowflake IDは0より大きい）
                HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(DISCORD_API_BASE + "/channels/" + threadId + "/messages?limit=1&after=0"))
                    .header("Authorization", "Bot " + token)
                    .header("Content-Type", "application/json")
                    .GET()
                    .timeout(Duration.ofSeconds(10))
                    .build();

                HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

                if (response.statusCode() == 200) {
                    JsonArray messages = JsonParser.parseString(response.body()).getAsJsonArray();
                    if (messages.size() > 0) {
                        return messages.get(0).getAsJsonObject().get("id").getAsString();
                    }
                }
                return null;
            } catch (Exception e) {
                ServerScoreboardLogger.error("Error getting first message ID", e);
                return null;
            }
        });
    }

    /**
     * 接続が確認されているか
     */
    public static boolean isConnectionVerified() {
        return connectionVerified.get();
    }

    /**
     * 接続状態をリセット
     */
    public static void resetConnection() {
        connectionVerified.set(false);
    }
}
