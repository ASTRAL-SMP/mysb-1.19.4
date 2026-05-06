package com.astralsmp.mysb.discord;

import com.astral.asthub.api.AstHubApi;
import com.astralsmp.mysb.ServerScoreboardLogger;
import net.fabricmc.loader.api.FabricLoader;

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
            AstHubApi.register("mysb")
                .addCommand("refresh", "統計を即座に更新してDiscordへ投稿", ctx -> {
                    if (!DiscordConfig.hasForumChannelId()) {
                        ctx.reply("Discord Forum Channel IDが設定されていません", true);
                        return;
                    }
                    if (DiscordConfig.getDiscordEnabledStats().isEmpty()) {
                        ctx.reply("Discord連携が有効な統計がありません", true);
                        return;
                    }
                    if (DiscordScheduler.isUpdating()) {
                        ctx.reply("現在更新中です。しばらくお待ちください", true);
                        return;
                    }

                    ctx.event().deferReply(true).queue(hook ->
                        DiscordScheduler.forceUpdate().whenComplete((ignored, throwable) -> {
                            if (throwable != null) {
                                ServerScoreboardLogger.error("AST Hub /mysb refresh failed", throwable);
                                hook.editOriginal("エラー: " + rootMessage(throwable)).queue();
                            } else {
                                hook.editOriginal("統計を更新しました").queue();
                            }
                        })
                    );
                })
                .finish();
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
            return AstHubApi.token("mysb");
        } catch (Throwable throwable) {
            return null;
        }
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
