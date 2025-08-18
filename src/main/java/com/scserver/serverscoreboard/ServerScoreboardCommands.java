package com.scserver.serverscoreboard;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.scoreboard.ScoreboardObjective;

import java.util.concurrent.CompletableFuture;
import java.util.Set;
import java.util.Map;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import com.google.gson.Gson;
import com.google.gson.JsonObject;

public class ServerScoreboardCommands {

    public static void register(CommandDispatcher<ServerCommandSource> dispatcher) {
        dispatcher.register(CommandManager.literal("mysb")
                .executes(ServerScoreboardCommands::openGUIForSender) // デフォルトでGUIを開く
                .then(CommandManager.literal("reload")
                        .requires(source -> source.hasPermissionLevel(4)) // OPレベル4（最高権限）のみ
                        .executes(ServerScoreboardCommands::reloadScoreboard))
                .then(CommandManager.literal("total")
                        .requires(source -> source.hasPermissionLevel(0)) // 権限レベル0（全員使用可能）
                        .executes(ServerScoreboardCommands::showTotalHelp) // /mysb totalでヘルプ表示
                        .then(CommandManager.literal("add")
                                .then(CommandManager.argument("id", StringArgumentType.word())
                                        .suggests((context, builder) -> {
                                            // IDのサジェスト
                                            builder.suggest("my_custom_stat", Text.literal("カスタム統計のID（英数字）"));
                                            return builder.buildFuture();
                                        })
                                        .then(CommandManager.argument("displayName", StringArgumentType.string())
                                                .suggests((context, builder) -> {
                                                    // 表示名のサジェスト
                                                    String id = StringArgumentType.getString(context, "id");
                                                    builder.suggest("\"My Custom Stat\"", Text.literal("表示名（スペースを含む場合は\"\"で囲む）"));
                                                    return builder.buildFuture();
                                                })
                                                .then(CommandManager.argument("statType", StringArgumentType.word())
                                                        .suggests(ServerScoreboardCommands::suggestStatTypes)
                                                        .executes(ServerScoreboardCommands::addTotalStat)))))
                        .then(CommandManager.literal("list")
                                .executes(ServerScoreboardCommands::listTotalStats))
                        .then(CommandManager.literal("update")
                                .executes(ServerScoreboardCommands::updateTotalStats))
                        .then(CommandManager.literal("remove")
                                .then(CommandManager.argument("id", StringArgumentType.word())
                                        .suggests(ServerScoreboardCommands::suggestCustomStats)
                                        .executes(ServerScoreboardCommands::removeTotalStat))))
                .then(CommandManager.literal("admin")
                        .requires(source -> source.hasPermissionLevel(0)) // 権限レベル0（全員使用可能）
                        .then(CommandManager.literal("gui")
                                .executes(ServerScoreboardCommands::openAdminGUI))
                        .then(CommandManager.literal("exclude")
                                .then(CommandManager.argument("player", StringArgumentType.word())
                                        .suggests(ServerScoreboardCommands::suggestOnlinePlayers)
                                        .executes(ServerScoreboardCommands::excludePlayer))
                                .then(CommandManager.literal("list")
                                        .executes(ServerScoreboardCommands::listExcludedPlayers)))
                        .then(CommandManager.literal("include")
                                .then(CommandManager.argument("player", StringArgumentType.word())
                                        .suggests(ServerScoreboardCommands::suggestExcludedPlayers)
                                        .executes(ServerScoreboardCommands::includePlayer)))
                        .then(CommandManager.literal("stats")
                                .then(CommandManager.literal("enable")
                                        .then(CommandManager.argument("stat", StringArgumentType.word())
                                                .suggests(ServerScoreboardCommands::suggestAvailableStats)
                                                .executes(ServerScoreboardCommands::enableStat)))
                                .then(CommandManager.literal("disable")
                                        .then(CommandManager.argument("stat", StringArgumentType.word())
                                                .suggests(ServerScoreboardCommands::suggestEnabledStats)
                                                .executes(ServerScoreboardCommands::disableStat)))
                                .then(CommandManager.literal("list")
                                        .executes(ServerScoreboardCommands::listStatStatus))))
                .then(CommandManager.literal("discord")
                        .requires(source -> source.hasPermissionLevel(0)) // 権限レベル0（全員使用可能）
                        .then(CommandManager.literal("setchannel")
                                .requires(source -> source.hasPermissionLevel(4)) // 権限レベル4
                                .then(CommandManager.argument("channelId", StringArgumentType.string())
                                        .executes(ServerScoreboardCommands::setForumChannel)))
                        .then(CommandManager.literal("add")
                                .requires(source -> source.hasPermissionLevel(0)) // 権限レベル0
                                .then(CommandManager.argument("objective", StringArgumentType.word())
                                        .suggests(ServerScoreboardCommands::suggestObjectives)
                                        .executes(ServerScoreboardCommands::addDiscordObjective)))
                        .then(CommandManager.literal("remove")
                                .requires(source -> source.hasPermissionLevel(0)) // 権限レベル0
                                .then(CommandManager.argument("objective", StringArgumentType.word())
                                        .suggests(ServerScoreboardCommands::suggestDiscordObjectives)
                                        .executes(ServerScoreboardCommands::removeDiscordObjective)))
                        .then(CommandManager.literal("list")
                                .requires(source -> source.hasPermissionLevel(0)) // 権限レベル0
                                .executes(ServerScoreboardCommands::listDiscordObjectives))
                        .then(CommandManager.literal("update")
                                .requires(source -> source.hasPermissionLevel(0)) // 権限レベル0
                                .then(CommandManager.argument("objective", StringArgumentType.word())
                                        .suggests(ServerScoreboardCommands::suggestDiscordObjectives)
                                        .executes(ServerScoreboardCommands::updateDiscordObjective)))
                        .then(CommandManager.literal("status")
                                .requires(source -> source.hasPermissionLevel(4)) // 権限レベル4
                                .executes(ServerScoreboardCommands::showDiscordStatus))
                        .then(CommandManager.literal("reload")
                                .requires(source -> source.hasPermissionLevel(4)) // OP権限レベル4
                                .executes(ServerScoreboardCommands::reloadDiscordBot)))
                .then(CommandManager.literal("debug")
                        .requires(source -> source.hasPermissionLevel(2)) // OP権限レベル2
                        .executes(ServerScoreboardCommands::toggleDebugMode)
                        .then(CommandManager.literal("on")
                                .executes(ServerScoreboardCommands::enableDebugMode))
                        .then(CommandManager.literal("off")
                                .executes(ServerScoreboardCommands::disableDebugMode)))
                .then(CommandManager.literal("version")
                        .executes(ServerScoreboardCommands::showVersion))
        );
    }

    private static int openAdminGUI(CommandContext<ServerCommandSource> context) {
        try {
            ServerCommandSource source = context.getSource();
            if (source.getEntity() instanceof ServerPlayerEntity player) {
                // Rate limit check
                if (!RateLimiter.canPerformAction(player.getUuid(), "gui", ServerScoreboardConfig.GUI_OPEN_COOLDOWN_MS)) {
                    source.sendError(Text.literal("コマンドを実行するには少し待ってください"));
                    return 0;
                }
                
                // Open Admin GUI (統計管理から開始)
                ServerScoreboardAdminGUI.openFor(player, ServerScoreboardAdminGUI.AdminPage.STATS);
                return 1;
            } else {
                source.sendError(Text.literal("このコマンドはプレイヤーのみ実行できます"));
                return 0;
            }
        } catch (Exception e) {
            ServerScoreboardLogger.error("Error executing admin GUI command", e);
            context.getSource().sendError(Text.literal("コマンド実行中にエラーが発生しました: " + e.getMessage()));
            return 0;
        }
    }

    private static int openGUIForSender(CommandContext<ServerCommandSource> context) {
        try {
            ServerCommandSource source = context.getSource();
            if (source.getEntity() instanceof ServerPlayerEntity player) {
                // Rate limit check
                if (!RateLimiter.canPerformAction(player.getUuid(), "gui", ServerScoreboardConfig.GUI_OPEN_COOLDOWN_MS)) {
                    source.sendError(Text.literal("コマンドを実行するには少し待ってください"));
                    return 0;
                }
                
                // Open GUI for sender (統計ページから開始)
                ServerScoreboardGUIv2.openFor(player, ServerScoreboardGUIv2.GUIPage.STATISTICS);
                return 1;
            } else {
                source.sendError(Text.literal("このコマンドはプレイヤーのみ実行できます"));
                return 0;
            }
        } catch (Exception e) {
            ServerScoreboardLogger.error("Error executing GUI command", e);
            context.getSource().sendError(Text.literal("コマンド実行中にエラーが発生しました: " + e.getMessage()));
            return 0;
        }
    }

    private static int reloadScoreboard(CommandContext<ServerCommandSource> context) {
        try {
            // レート制限チェック（コンソールからの実行も含む）
            ServerCommandSource source = context.getSource();
            if (source.getEntity() instanceof ServerPlayerEntity player) {
                if (!RateLimiter.canPerformAction(player.getUuid(), "reload", ServerScoreboardConfig.COMMAND_COOLDOWN_MS * 10)) {
                    source.sendError(Text.literal("リロードコマンドを実行するには少し待ってください"));
                    return 0;
                }
            }
            
            ServerScoreboardManager.loadScoreboardData(context.getSource().getServer());

            context.getSource().sendFeedback(
                    Text.literal("スコアボードデータを再読み込みしました"),
                    false
            );

            return 1;
        } catch (Exception e) {
            ServerScoreboardLogger.error("Error reloading scoreboard data", e);
            context.getSource().sendError(Text.literal("スコアボードデータの再読み込み中にエラーが発生しました: " + e.getMessage()));
            return 0;
        }
    }
    
    private static int addTotalStat(CommandContext<ServerCommandSource> context) {
        try {
            String id = StringArgumentType.getString(context, "id");
            String displayName = StringArgumentType.getString(context, "displayName");
            String statType = StringArgumentType.getString(context, "statType");
            
            TotalStatsManager.addCustomTotalStat(id, displayName, statType);
            
            context.getSource().sendFeedback(
                Text.literal("トータル統計「" + displayName + "」を追加しました"),
                true
            );
            
            return 1;
        } catch (Exception e) {
            ServerScoreboardLogger.error("Error adding total stat", e);
            context.getSource().sendError(Text.literal("トータル統計の追加中にエラーが発生しました: " + e.getMessage()));
            return 0;
        }
    }
    
    private static int listTotalStats(CommandContext<ServerCommandSource> context) {
        try {
            var objectives = TotalStatsManager.getAllTotalObjectives();
            
            if (objectives.isEmpty()) {
                context.getSource().sendFeedback(Text.literal("トータル統計が登録されていません"), false);
                return 1;
            }
            
            context.getSource().sendFeedback(Text.literal("=== トータル統計一覧 ==="), false);
            for (String objName : objectives) {
                String displayName = TotalStatsManager.getTotalDisplayName(objName);
                context.getSource().sendFeedback(
                    Text.literal("- " + objName + " (" + displayName + ")"),
                    false
                );
            }
            
            return 1;
        } catch (Exception e) {
            ServerScoreboardLogger.error("Error listing total stats", e);
            context.getSource().sendError(Text.literal("トータル統計の一覧表示中にエラーが発生しました: " + e.getMessage()));
            return 0;
        }
    }
    
    private static int updateTotalStats(CommandContext<ServerCommandSource> context) {
        try {
            TotalStatsManager.updateAllTotalStats();
            
            context.getSource().sendFeedback(
                Text.literal("全てのトータル統計を更新しました"),
                true
            );
            
            return 1;
        } catch (Exception e) {
            ServerScoreboardLogger.error("Error updating total stats", e);
            context.getSource().sendError(Text.literal("トータル統計の更新中にエラーが発生しました: " + e.getMessage()));
            return 0;
        }
    }
    
    private static CompletableFuture<Suggestions> suggestAvailableStats(CommandContext<ServerCommandSource> context, SuggestionsBuilder builder) {
        Map<String, String> availableStats = TotalStatsManager.getAllAvailableStats();
        Set<String> enabledStats = TotalStatsManager.getEnabledStats();
        
        for (Map.Entry<String, String> entry : availableStats.entrySet()) {
            String statId = entry.getKey();
            String displayName = entry.getValue();
            // Only suggest stats that are not already enabled
            if (!enabledStats.contains(statId)) {
                builder.suggest(statId, Text.literal(displayName));
            }
        }
        
        return builder.buildFuture();
    }
    
    private static CompletableFuture<Suggestions> suggestEnabledStats(CommandContext<ServerCommandSource> context, SuggestionsBuilder builder) {
        Set<String> enabledStats = TotalStatsManager.getEnabledStats();
        Map<String, String> availableStats = TotalStatsManager.getAllAvailableStats();
        
        for (String statId : enabledStats) {
            String displayName = availableStats.getOrDefault(statId, statId);
            builder.suggest(statId, Text.literal(displayName));
        }
        
        return builder.buildFuture();
    }
    
    private static int enableStat(CommandContext<ServerCommandSource> context) {
        try {
            String statId = StringArgumentType.getString(context, "stat");
            
            if (!TotalStatsManager.getAllAvailableStats().containsKey(statId)) {
                context.getSource().sendError(Text.literal("不明な統計: " + statId));
                return 0;
            }
            
            TotalStatsManager.enableStat(statId);
            
            context.getSource().sendFeedback(
                Text.literal("統計を有効化しました: " + statId),
                true
            );
            
            return 1;
        } catch (Exception e) {
            ServerScoreboardLogger.error("Error enabling stat", e);
            context.getSource().sendError(Text.literal("統計の有効化中にエラーが発生しました: " + e.getMessage()));
            return 0;
        }
    }
    
    private static int disableStat(CommandContext<ServerCommandSource> context) {
        try {
            String statId = StringArgumentType.getString(context, "stat");
            
            TotalStatsManager.disableStat(statId);
            
            context.getSource().sendFeedback(
                Text.literal("統計を無効化しました: " + statId),
                true
            );
            
            return 1;
        } catch (Exception e) {
            ServerScoreboardLogger.error("Error disabling stat", e);
            context.getSource().sendError(Text.literal("統計の無効化中にエラーが発生しました: " + e.getMessage()));
            return 0;
        }
    }
    
    private static int listStatStatus(CommandContext<ServerCommandSource> context) {
        try {
            Map<String, String> allStats = TotalStatsManager.getAllAvailableStats();
            Set<String> enabledStats = TotalStatsManager.getEnabledStats();
            
            context.getSource().sendFeedback(Text.literal("=== 統計の状態 ==="), false);
            
            context.getSource().sendFeedback(Text.literal("有効:").formatted(Formatting.GREEN), false);
            for (String statId : enabledStats) {
                String displayName = allStats.getOrDefault(statId, statId);
                context.getSource().sendFeedback(
                    Text.literal("  - " + statId + " (" + displayName + ")").formatted(Formatting.GREEN),
                    false
                );
            }
            
            context.getSource().sendFeedback(Text.literal("\n無効:").formatted(Formatting.RED), false);
            for (Map.Entry<String, String> entry : allStats.entrySet()) {
                if (!enabledStats.contains(entry.getKey())) {
                    context.getSource().sendFeedback(
                        Text.literal("  - " + entry.getKey() + " (" + entry.getValue() + ")").formatted(Formatting.RED),
                        false
                    );
                }
            }
            
            return 1;
        } catch (Exception e) {
            ServerScoreboardLogger.error("Error listing stat status", e);
            context.getSource().sendError(Text.literal("統計の一覧表示中にエラーが発生しました: " + e.getMessage()));
            return 0;
        }
    }
    
    private static int showTotalHelp(CommandContext<ServerCommandSource> context) {
        context.getSource().sendFeedback(Text.literal("=== トータル統計コマンドの使い方 ===").formatted(Formatting.GOLD), false);
        context.getSource().sendFeedback(Text.literal(""), false);
        context.getSource().sendFeedback(Text.literal("/mysb total add <id> <displayName> <statType>").formatted(Formatting.YELLOW), false);
        context.getSource().sendFeedback(Text.literal("  新しいトータル統計を追加").formatted(Formatting.GRAY), false);
        context.getSource().sendFeedback(Text.literal("  例: /mysb total add damage \"Total Damage Dealt\" damage_dealt").formatted(Formatting.DARK_GRAY), false);
        context.getSource().sendFeedback(Text.literal(""), false);
        context.getSource().sendFeedback(Text.literal("/mysb total list").formatted(Formatting.YELLOW), false);
        context.getSource().sendFeedback(Text.literal("  登録されているトータル統計を一覧表示").formatted(Formatting.GRAY), false);
        context.getSource().sendFeedback(Text.literal(""), false);
        context.getSource().sendFeedback(Text.literal("/mysb total update").formatted(Formatting.YELLOW), false);
        context.getSource().sendFeedback(Text.literal("  トータル統計を手動更新").formatted(Formatting.GRAY), false);
        context.getSource().sendFeedback(Text.literal(""), false);
        context.getSource().sendFeedback(Text.literal("/mysb total remove <id>").formatted(Formatting.YELLOW), false);
        context.getSource().sendFeedback(Text.literal("  カスタムトータル統計を削除").formatted(Formatting.GRAY), false);
        context.getSource().sendFeedback(Text.literal(""), false);
        context.getSource().sendFeedback(Text.literal("利用可能な統計タイプ:").formatted(Formatting.AQUA), false);
        context.getSource().sendFeedback(Text.literal("  mined, placed, killed, deaths, damage_dealt,").formatted(Formatting.DARK_AQUA), false);
        context.getSource().sendFeedback(Text.literal("  damage_taken, play_time, walk_one_cm, jump, fish_caught").formatted(Formatting.DARK_AQUA), false);
        return 1;
    }
    
    private static CompletableFuture<Suggestions> suggestStatTypes(CommandContext<ServerCommandSource> context, SuggestionsBuilder builder) {
        // 利用可能な統計タイプをサジェスト
        Map<String, String> commonStats = TotalStatsManager.COMMON_STATS;
        for (Map.Entry<String, String> entry : commonStats.entrySet()) {
            builder.suggest(entry.getKey(), Text.literal(entry.getValue()));
        }
        return builder.buildFuture();
    }
    
    private static CompletableFuture<Suggestions> suggestCustomStats(CommandContext<ServerCommandSource> context, SuggestionsBuilder builder) {
        // カスタム統計のIDをサジェスト（デフォルト以外）
        Map<String, String> allStats = TotalStatsManager.getAllAvailableStats();
        for (String statId : allStats.keySet()) {
            // デフォルト統計以外をサジェスト
            if (!statId.equals("mined") && !statId.equals("placed") && 
                !statId.equals("killed") && !statId.equals("deaths")) {
                builder.suggest(statId, Text.literal(allStats.get(statId)));
            }
        }
        return builder.buildFuture();
    }
    
    private static int removeTotalStat(CommandContext<ServerCommandSource> context) {
        try {
            String id = StringArgumentType.getString(context, "id");
            
            // デフォルト統計は削除できない
            if (id.equals("mined") || id.equals("placed") || id.equals("killed") || id.equals("deaths")) {
                context.getSource().sendError(Text.literal("デフォルト統計は削除できません"));
                return 0;
            }
            
            TotalStatsManager.disableStat(id);
            
            context.getSource().sendFeedback(
                Text.literal("トータル統計「" + id + "」を削除しました"),
                true
            );
            
            return 1;
        } catch (Exception e) {
            ServerScoreboardLogger.error("Error removing total stat", e);
            context.getSource().sendError(Text.literal("トータル統計の削除中にエラーが発生しました: " + e.getMessage()));
            return 0;
        }
    }
    
    private static int excludePlayer(CommandContext<ServerCommandSource> context) {
        try {
            String playerName = StringArgumentType.getString(context, "player");
            
            if (TotalStatsManager.isPlayerExcluded(playerName)) {
                context.getSource().sendError(Text.literal("プレイヤー " + playerName + " は既に除外されています"));
                return 0;
            }
            
            TotalStatsManager.excludePlayer(playerName);
            
            context.getSource().sendFeedback(
                Text.literal("プレイヤー " + playerName + " を統計から除外しました"),
                true
            );
            
            return 1;
        } catch (Exception e) {
            ServerScoreboardLogger.error("Error excluding player", e);
            context.getSource().sendError(Text.literal("プレイヤーの除外中にエラーが発生しました: " + e.getMessage()));
            return 0;
        }
    }
    
    private static int includePlayer(CommandContext<ServerCommandSource> context) {
        try {
            String playerName = StringArgumentType.getString(context, "player");
            
            if (!TotalStatsManager.isPlayerExcluded(playerName)) {
                context.getSource().sendError(Text.literal("プレイヤー " + playerName + " は除外されていません"));
                return 0;
            }
            
            TotalStatsManager.includePlayer(playerName);
            
            context.getSource().sendFeedback(
                Text.literal("プレイヤー " + playerName + " を統計に含めるようにしました"),
                true
            );
            
            return 1;
        } catch (Exception e) {
            ServerScoreboardLogger.error("Error including player", e);
            context.getSource().sendError(Text.literal("プレイヤーの包含中にエラーが発生しました: " + e.getMessage()));
            return 0;
        }
    }
    
    private static int listExcludedPlayers(CommandContext<ServerCommandSource> context) {
        try {
            Set<String> excludedPlayers = TotalStatsManager.getExcludedPlayers();
            
            if (excludedPlayers.isEmpty()) {
                context.getSource().sendFeedback(Text.literal("除外されているプレイヤーはいません"), false);
                return 1;
            }
            
            context.getSource().sendFeedback(Text.literal("=== 除外されているプレイヤー ===").formatted(Formatting.GOLD), false);
            for (String playerName : excludedPlayers) {
                context.getSource().sendFeedback(
                    Text.literal("- " + playerName).formatted(Formatting.YELLOW),
                    false
                );
            }
            
            return 1;
        } catch (Exception e) {
            ServerScoreboardLogger.error("Error listing excluded players", e);
            context.getSource().sendError(Text.literal("除外プレイヤーリストの表示中にエラーが発生しました: " + e.getMessage()));
            return 0;
        }
    }
    
    private static CompletableFuture<Suggestions> suggestOnlinePlayers(CommandContext<ServerCommandSource> context, SuggestionsBuilder builder) {
        for (ServerPlayerEntity player : context.getSource().getServer().getPlayerManager().getPlayerList()) {
            String playerName = player.getName().getString();
            if (!TotalStatsManager.isPlayerExcluded(playerName)) {
                builder.suggest(playerName);
            }
        }
        return builder.buildFuture();
    }
    
    private static CompletableFuture<Suggestions> suggestExcludedPlayers(CommandContext<ServerCommandSource> context, SuggestionsBuilder builder) {
        for (String playerName : TotalStatsManager.getExcludedPlayers()) {
            builder.suggest(playerName);
        }
        return builder.buildFuture();
    }
    
    // Discord Bot関連コマンド
    private static int setForumChannel(CommandContext<ServerCommandSource> context) {
        String channelId = StringArgumentType.getString(context, "channelId");
        SimpleDiscordBot.getInstance().setForumChannel(channelId);
        context.getSource().sendFeedback(Text.literal("フォーラムチャンネルを設定しました: " + channelId).formatted(Formatting.GREEN), true);
        return 1;
    }
    
    private static int addDiscordObjective(CommandContext<ServerCommandSource> context) {
        String objective = StringArgumentType.getString(context, "objective");
        
        try {
            SimpleDiscordBot.getInstance().addScoreboard(objective);
            context.getSource().sendFeedback(Text.literal("Discord連携を追加しました: " + objective).formatted(Formatting.GREEN), true);
        } catch (IllegalStateException e) {
            context.getSource().sendError(Text.literal(e.getMessage()));
            return 0;
        }
        
        return 1;
    }
    
    private static int removeDiscordObjective(CommandContext<ServerCommandSource> context) {
        String objective = StringArgumentType.getString(context, "objective");
        SimpleDiscordBot.getInstance().removeScoreboard(objective);
        context.getSource().sendFeedback(Text.literal("Discord連携を削除しました: " + objective).formatted(Formatting.YELLOW), true);
        return 1;
    }
    
    private static int listDiscordObjectives(CommandContext<ServerCommandSource> context) {
        var threads = SimpleDiscordBot.getInstance().getForumThreads();
        
        if (threads.isEmpty()) {
            context.getSource().sendFeedback(Text.literal("Discord連携されているスコアボードはありません"), false);
            return 1;
        }
        
        context.getSource().sendFeedback(Text.literal("=== Discord連携スコアボード ===").formatted(Formatting.AQUA), false);
        for (var entry : threads.entrySet()) {
            context.getSource().sendFeedback(Text.literal("• " + entry.getKey()).formatted(Formatting.GREEN), false);
        }
        
        return 1;
    }
    
    private static int updateDiscordObjective(CommandContext<ServerCommandSource> context) {
        String objective = StringArgumentType.getString(context, "objective");
        SimpleDiscordBot.getInstance().updateScoreboardData(objective);
        context.getSource().sendFeedback(Text.literal("Discord投稿を更新しました: " + objective).formatted(Formatting.GREEN), true);
        return 1;
    }
    
    private static int showDiscordStatus(CommandContext<ServerCommandSource> context) {
        ServerCommandSource source = context.getSource();
        
        source.sendFeedback(Text.literal("=== Discord Bot ステータス ===").formatted(Formatting.AQUA), false);
        source.sendFeedback(Text.literal("状態: " + (SimpleDiscordBot.getInstance().isRunning() ? "起動中" : "停止中"))
            .formatted(SimpleDiscordBot.getInstance().isRunning() ? Formatting.GREEN : Formatting.RED), false);
        
        String forumChannelId = SimpleDiscordBot.getInstance().getForumChannelId();
        source.sendFeedback(Text.literal("フォーラムチャンネル: " + (forumChannelId != null ? forumChannelId : "未設定"))
            .formatted(forumChannelId != null ? Formatting.GREEN : Formatting.YELLOW), false);
        
        return 1;
    }
    
    static String loadDiscordToken() {
        try {
            File file = new File("config/serverscoreboard/discord_bot.json");
            if (!file.exists()) return "";
            
            try (FileReader reader = new FileReader(file)) {
                JsonObject root = new Gson().fromJson(reader, JsonObject.class);
                if (root.has("token")) {
                    return root.get("token").getAsString();
                }
            }
        } catch (Exception e) {
            ServerScoreboardLogger.error("Failed to load Discord token: " + e.getMessage());
        }
        return "";
    }
    
    private static CompletableFuture<Suggestions> suggestObjectives(CommandContext<ServerCommandSource> context, SuggestionsBuilder builder) {
        for (ScoreboardObjective objective : context.getSource().getServer().getScoreboard().getObjectives()) {
            builder.suggest(objective.getName());
        }
        return builder.buildFuture();
    }
    
    private static CompletableFuture<Suggestions> suggestDiscordObjectives(CommandContext<ServerCommandSource> context, SuggestionsBuilder builder) {
        for (String objective : SimpleDiscordBot.getInstance().getForumThreads().keySet()) {
            builder.suggest(objective);
        }
        return builder.buildFuture();
    }
    
    private static int reloadDiscordBot(CommandContext<ServerCommandSource> context) {
        try {
            ServerCommandSource source = context.getSource();
            source.sendFeedback(Text.literal("Discord Botを再起動しています...").formatted(Formatting.YELLOW), true);
            
            SimpleDiscordBot.getInstance().reload(source.getServer());
            
            // 再起動完了メッセージを表示
            source.sendFeedback(Text.literal("Discord Botの再起動が完了しました").formatted(Formatting.GREEN), true);
            ServerScoreboardLogger.info("Discord Bot reloaded successfully");
            
            return 1;
        } catch (Exception e) {
            ServerScoreboardLogger.error("Error reloading Discord bot", e);
            context.getSource().sendError(Text.literal("Discord Botの再起動中にエラーが発生しました: " + e.getMessage()));
            return 0;
        }
    }
    
    private static int toggleDebugMode(CommandContext<ServerCommandSource> context) {
        ServerScoreboardConfig.DEBUG_MODE_ENABLED = !ServerScoreboardConfig.DEBUG_MODE_ENABLED;
        
        if (ServerScoreboardConfig.DEBUG_MODE_ENABLED) {
            context.getSource().sendFeedback(
                Text.literal("デバッグモードが有効になりました").formatted(Formatting.GREEN), 
                true
            );
            ServerScoreboardLogger.info("Debug mode ENABLED by " + context.getSource().getName());
        } else {
            context.getSource().sendFeedback(
                Text.literal("デバッグモードが無効になりました").formatted(Formatting.RED), 
                true
            );
            ServerScoreboardLogger.info("Debug mode DISABLED by " + context.getSource().getName());
        }
        
        return 1;
    }
    
    private static int enableDebugMode(CommandContext<ServerCommandSource> context) {
        ServerScoreboardConfig.DEBUG_MODE_ENABLED = true;
        context.getSource().sendFeedback(
            Text.literal("デバッグモードが有効になりました").formatted(Formatting.GREEN), 
            true
        );
        ServerScoreboardLogger.info("Debug mode ENABLED by " + context.getSource().getName());
        
        // 設定の詳細を表示
        context.getSource().sendFeedback(
            Text.literal("デバッグメッセージは").formatted(Formatting.GRAY)
                .append(ServerScoreboardConfig.DEBUG_BROADCAST_TO_OPS ? 
                    Text.literal("OP権限者のみ").formatted(Formatting.YELLOW) : 
                    Text.literal("全員").formatted(Formatting.YELLOW))
                .append(Text.literal("に送信されます").formatted(Formatting.GRAY)), 
            false
        );
        
        return 1;
    }
    
    private static int disableDebugMode(CommandContext<ServerCommandSource> context) {
        ServerScoreboardConfig.DEBUG_MODE_ENABLED = false;
        context.getSource().sendFeedback(
            Text.literal("デバッグモードが無効になりました").formatted(Formatting.RED), 
            true
        );
        ServerScoreboardLogger.info("Debug mode DISABLED by " + context.getSource().getName());
        return 1;
    }
    
    private static int showVersion(CommandContext<ServerCommandSource> context) {
        context.getSource().sendFeedback(
            Text.literal("MySB - My Scoreboard").formatted(Formatting.GOLD)
                .append(Text.literal(" Version: ").formatted(Formatting.GRAY))
                .append(Text.literal(ServerOnlyScoreboardMod.getModVersion()).formatted(Formatting.AQUA)),
            false
        );
        return 1;
    }
}