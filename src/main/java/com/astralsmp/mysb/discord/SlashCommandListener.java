package com.astralsmp.mysb.discord;

import com.astralsmp.mysb.ServerScoreboardLogger;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;

/**
 * Discordスラッシュコマンドを処理するリスナー
 */
public class SlashCommandListener extends ListenerAdapter {

    @Override
    public void onSlashCommandInteraction(SlashCommandInteractionEvent event) {
        String commandName = event.getName();

        switch (commandName) {
            case "refresh":
                handleRefresh(event);
                break;
            default:
                event.reply("Unknown command: " + commandName)
                    .setEphemeral(true)
                    .queue();
                break;
        }
    }

    /**
     * /refresh コマンドを処理
     * 統計データを即座に更新してDiscordに投稿
     */
    private void handleRefresh(SlashCommandInteractionEvent event) {
        // Discord設定が有効かチェック
        if (!DiscordConfig.hasValidToken()) {
            event.reply("Discord Botトークンが設定されていません")
                .setEphemeral(true)
                .queue();
            return;
        }

        if (!DiscordConfig.hasForumChannelId()) {
            event.reply("Discord Forum Channel IDが設定されていません")
                .setEphemeral(true)
                .queue();
            return;
        }

        if (DiscordConfig.getDiscordEnabledStats().isEmpty()) {
            event.reply("Discord連携が有効な統計がありません")
                .setEphemeral(true)
                .queue();
            return;
        }

        // 既に更新中かチェック
        if (DiscordScheduler.isUpdating()) {
            event.reply("現在更新中です。しばらくお待ちください")
                .setEphemeral(true)
                .queue();
            return;
        }

        // 即座に応答（Discordは3秒以内に応答が必要）
        event.deferReply(true).queue();

        ServerScoreboardLogger.info("Discord /refresh command received from: " + event.getUser().getName());

        // 非同期で統計更新
        DiscordScheduler.forceUpdate().thenAccept(v -> {
            event.getHook().editOriginal("統計を更新しました").queue();
            ServerScoreboardLogger.info("Discord /refresh command completed successfully");
        }).exceptionally(e -> {
            String errorMessage = e.getCause() != null ? e.getCause().getMessage() : e.getMessage();
            event.getHook().editOriginal("エラー: " + errorMessage).queue();
            ServerScoreboardLogger.error("Discord /refresh command failed", e);
            return null;
        });
    }
}
