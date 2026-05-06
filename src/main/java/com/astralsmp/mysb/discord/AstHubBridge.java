package com.astralsmp.mysb.discord;

import com.astralsmp.mysb.ServerScoreboardLogger;
import net.fabricmc.loader.api.FabricLoader;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;

import java.lang.reflect.Method;
import java.util.function.Consumer;

public final class AstHubBridge {
    private static volatile boolean registered;

    private AstHubBridge() {
    }

    public static boolean isAvailable() {
        return FabricLoader.getInstance().isModLoaded("asthub");
    }

    public static void register() {
        if (registered) {
            return;
        }

        try {
            Class<?> apiClass = Class.forName("com.astral.asthub.api.AstHubApi");
            Object builder = apiClass.getMethod("register", String.class).invoke(null, "mysb");
            Method addCommand = builder.getClass().getMethod("addCommand", String.class, String.class, Consumer.class);
            addCommand.invoke(builder, "refresh", "統計を即座に更新してDiscordへ投稿", (Consumer<Object>) AstHubBridge::handleRefresh);
            builder.getClass().getMethod("finish").invoke(builder);
            registered = true;
            ServerScoreboardLogger.info("Registered MySB commands on AST DiscordHub");
        } catch (Throwable throwable) {
            ServerScoreboardLogger.warn("AST DiscordHub is present but MySB command registration failed: " + throwable);
        }
    }

    public static String getSharedTokenOrNull() {
        if (!isAvailable()) {
            return null;
        }
        try {
            Class<?> apiClass = Class.forName("com.astral.asthub.api.AstHubApi");
            return (String) apiClass.getMethod("token", String.class).invoke(null, "mysb");
        } catch (Throwable throwable) {
            return null;
        }
    }

    private static void handleRefresh(Object ctx) {
        try {
            if (!DiscordConfig.hasForumChannelId()) {
                reply(ctx, "Discord Forum Channel IDが設定されていません");
                return;
            }
            if (DiscordConfig.getDiscordEnabledStats().isEmpty()) {
                reply(ctx, "Discord連携が有効な統計がありません");
                return;
            }
            if (DiscordScheduler.isUpdating()) {
                reply(ctx, "現在更新中です。しばらくお待ちください");
                return;
            }

            Object event = ctx.getClass().getMethod("event").invoke(ctx);
            if (!(event instanceof SlashCommandInteractionEvent slashEvent)) {
                reply(ctx, "Discordイベントの取得に失敗しました");
                return;
            }

            slashEvent.deferReply(true).queue(hook ->
                DiscordScheduler.forceUpdate().whenComplete((ignored, throwable) -> {
                    if (throwable != null) {
                        ServerScoreboardLogger.error("AST Hub /mysb refresh failed", throwable);
                        hook.editOriginal("エラー: " + rootMessage(throwable)).queue();
                    } else {
                        hook.editOriginal("統計を更新しました").queue();
                    }
                })
            );
        } catch (Throwable throwable) {
            ServerScoreboardLogger.error("AST Hub /mysb refresh failed before scheduling update", throwable);
        }
    }

    private static void reply(Object ctx, String message) throws ReflectiveOperationException {
        ctx.getClass().getMethod("reply", String.class, boolean.class).invoke(ctx, message, true);
    }

    private static String rootMessage(Throwable throwable) {
        Throwable root = throwable;
        while (root.getCause() != null) {
            root = root.getCause();
        }
        String message = root.getMessage();
        return message == null || message.isBlank() ? root.getClass().getSimpleName() : message;
    }
}
