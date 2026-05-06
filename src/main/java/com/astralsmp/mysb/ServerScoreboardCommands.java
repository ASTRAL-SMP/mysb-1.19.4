package com.astralsmp.mysb;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import com.astralsmp.mysb.discord.DiscordConfig;
import com.astralsmp.mysb.discord.DiscordManager;
import com.astralsmp.mysb.discord.DiscordScheduler;
import com.astralsmp.mysb.discord.DiscordSettingsGUI;
import com.astralsmp.mysb.discord.DiscordStatsPublisher;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.scoreboard.ScoreboardObjective;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.concurrent.CompletableFuture;
import java.util.Set;
import java.util.Map;

public class ServerScoreboardCommands {

    public static void register(CommandDispatcher<ServerCommandSource> dispatcher) {
        // /mysb コマンド
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
	                .then(CommandManager.literal("area")
	                        .requires(source -> source.hasPermissionLevel(3))
	                        .executes(ServerScoreboardCommands::showAreaHelp)
	                        .then(CommandManager.literal("pos1")
	                                .then(CommandManager.argument("id", StringArgumentType.word())
	                                        .executes(context -> setAreaSelectionPos(context, true))))
	                        .then(CommandManager.literal("pos2")
	                                .then(CommandManager.argument("id", StringArgumentType.word())
	                                        .executes(context -> setAreaSelectionPos(context, false))))
		                        .then(CommandManager.literal("create")
		                                .then(CommandManager.argument("id", StringArgumentType.word())
		                                        .then(CommandManager.argument("displayName", StringArgumentType.string())
		                                                .executes(ServerScoreboardCommands::createAreaFromSelection)
		                                                .then(CommandManager.argument("x1", IntegerArgumentType.integer())
		                                                        .then(CommandManager.argument("z1", IntegerArgumentType.integer())
		                                                                .then(CommandManager.argument("x2", IntegerArgumentType.integer())
		                                                                        .then(CommandManager.argument("z2", IntegerArgumentType.integer())
		                                                                                .executes(ServerScoreboardCommands::createAreaFromCoordinates))))))))
	                        .then(CommandManager.literal("list")
	                                .executes(ServerScoreboardCommands::listAreas))
	                        .then(CommandManager.literal("remove")
	                                .then(CommandManager.argument("id", StringArgumentType.word())
	                                        .suggests(ServerScoreboardCommands::suggestAreas)
	                                        .executes(ServerScoreboardCommands::removeArea)))
	                        .then(CommandManager.literal("month")
	                                .then(CommandManager.literal("current")
	                                        .executes(ServerScoreboardCommands::setAreaMonthCurrent))
	                                .then(CommandManager.argument("month", StringArgumentType.string())
	                                        .suggests(ServerScoreboardCommands::suggestAreaMonths)
	                                        .executes(ServerScoreboardCommands::setAreaMonth)))
	                        .then(CommandManager.literal("update")
	                                .executes(ServerScoreboardCommands::updateAreas)))
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
                                        .executes(ServerScoreboardCommands::listStatStatus)))
                        .then(CommandManager.literal("fakeplayerscore")
                                .then(CommandManager.literal("enable")
                                        .requires(source -> source.hasPermissionLevel(3)) // OPレベル3以上
                                        .executes(ServerScoreboardCommands::enableFakePlayerScore))
                                .then(CommandManager.literal("disable")
                                        .requires(source -> source.hasPermissionLevel(3)) // OPレベル3以上
                                        .executes(ServerScoreboardCommands::disableFakePlayerScore))))
                .then(CommandManager.literal("version")
                        .executes(ServerScoreboardCommands::showVersion))
        );

        // /mysbdiscord コマンド（Discord連携専用）
        dispatcher.register(CommandManager.literal("mysbdiscord")
                .requires(source -> source.hasPermissionLevel(3)) // OP3以上
                .executes(ServerScoreboardCommands::openDiscordGUI) // デフォルトでGUIを開く
                .then(CommandManager.literal("channel")
                        .then(CommandManager.argument("id", StringArgumentType.word())
                                .executes(ServerScoreboardCommands::setDiscordChannel)))
                .then(CommandManager.literal("status")
                        .executes(ServerScoreboardCommands::showDiscordStatus))
                .then(CommandManager.literal("reconnect")
                        .executes(ServerScoreboardCommands::reconnectDiscord))
                .then(CommandManager.literal("test")
                        .executes(ServerScoreboardCommands::testDiscord))
                .then(CommandManager.literal("update")
                        .executes(ServerScoreboardCommands::forceDiscordUpdate))
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
	            MonthlyAreaStatsManager.reload();

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

	    private static int showAreaHelp(CommandContext<ServerCommandSource> context) {
	        context.getSource().sendFeedback(Text.literal("=== 月間範囲統計コマンド ===").formatted(Formatting.GOLD), false);
	        context.getSource().sendFeedback(Text.literal("/mysb area pos1 <id>").formatted(Formatting.YELLOW), false);
        context.getSource().sendFeedback(Text.literal("  現在位置のチャンクを始点に設定").formatted(Formatting.GRAY), false);
	        context.getSource().sendFeedback(Text.literal("/mysb area pos2 <id>").formatted(Formatting.YELLOW), false);
        context.getSource().sendFeedback(Text.literal("  現在位置のチャンクを終点に設定").formatted(Formatting.GRAY), false);
	        context.getSource().sendFeedback(Text.literal("/mysb area create <id> <displayName>").formatted(Formatting.YELLOW), false);
	        context.getSource().sendFeedback(Text.literal("  pos1/pos2 から月間採掘・設置スコアボードを作成").formatted(Formatting.GRAY), false);
        context.getSource().sendFeedback(Text.literal("/mysb area create <id> <displayName> <x1> <z1> <x2> <z2>").formatted(Formatting.YELLOW), false);
        context.getSource().sendFeedback(Text.literal("  チャンク座標を直接指定して作成").formatted(Formatting.GRAY), false);
	        context.getSource().sendFeedback(Text.literal("/mysb area month <yyyy-MM|current>").formatted(Formatting.YELLOW), false);
	        context.getSource().sendFeedback(Text.literal("  スコアボードへ表示する月を切り替え").formatted(Formatting.GRAY), false);
	        return 1;
	    }

	    private static int setAreaSelectionPos(CommandContext<ServerCommandSource> context, boolean firstPos) {
	        try {
	            ServerCommandSource source = context.getSource();
	            if (!(source.getEntity() instanceof ServerPlayerEntity player)) {
	                source.sendError(Text.literal("このコマンドはプレイヤーのみ実行できます"));
	                return 0;
	            }

	            String id = StringArgumentType.getString(context, "id");
            MonthlyAreaStatsManager.AreaChunkPos pos = MonthlyAreaStatsManager.setSelectionPos(player, id, firstPos);
            source.sendFeedback(
                    Text.literal((firstPos ? "pos1" : "pos2") + " を設定しました: " + id + " chunk=" + pos.format()),
	                    false
	            );
	            return 1;
	        } catch (Exception e) {
	            ServerScoreboardLogger.error("Error setting monthly area selection", e);
	            context.getSource().sendError(Text.literal("範囲位置の設定中にエラーが発生しました: " + e.getMessage()));
	            return 0;
	        }
	    }

	    private static int createAreaFromSelection(CommandContext<ServerCommandSource> context) {
	        try {
	            ServerCommandSource source = context.getSource();
	            if (!(source.getEntity() instanceof ServerPlayerEntity player)) {
	                source.sendError(Text.literal("このコマンドはプレイヤーのみ実行できます"));
	                return 0;
	            }

	            String id = StringArgumentType.getString(context, "id");
	            String displayName = StringArgumentType.getString(context, "displayName");
	            MonthlyAreaStatsManager.AreaConfig area = MonthlyAreaStatsManager.createAreaFromSelection(player, id, displayName);
	            sendAreaCreatedFeedback(source, area);
	            return 1;
	        } catch (Exception e) {
	            ServerScoreboardLogger.error("Error creating monthly area from selection", e);
	            context.getSource().sendError(Text.literal("月間範囲統計の作成中にエラーが発生しました: " + e.getMessage()));
	            return 0;
	        }
	    }

	    private static int createAreaFromCoordinates(CommandContext<ServerCommandSource> context) {
	        try {
	            String id = StringArgumentType.getString(context, "id");
	            String displayName = StringArgumentType.getString(context, "displayName");
            MonthlyAreaStatsManager.AreaChunkPos pos1 = new MonthlyAreaStatsManager.AreaChunkPos(
                    IntegerArgumentType.getInteger(context, "x1"),
                    IntegerArgumentType.getInteger(context, "z1")
            );
            MonthlyAreaStatsManager.AreaChunkPos pos2 = new MonthlyAreaStatsManager.AreaChunkPos(
                    IntegerArgumentType.getInteger(context, "x2"),
                    IntegerArgumentType.getInteger(context, "z2")
            );
	            String dimension = context.getSource().getWorld().getRegistryKey().getValue().toString();
	            MonthlyAreaStatsManager.AreaConfig area = MonthlyAreaStatsManager.createArea(id, displayName, dimension, pos1, pos2);
	            sendAreaCreatedFeedback(context.getSource(), area);
	            return 1;
	        } catch (Exception e) {
	            ServerScoreboardLogger.error("Error creating monthly area from coordinates", e);
	            context.getSource().sendError(Text.literal("月間範囲統計の作成中にエラーが発生しました: " + e.getMessage()));
	            return 0;
	        }
	    }

	    private static void sendAreaCreatedFeedback(ServerCommandSource source, MonthlyAreaStatsManager.AreaConfig area) {
	        source.sendFeedback(
	                Text.literal("月間範囲統計を作成しました: " + area.id
	                        + " mined=" + area.getMinedObjectiveName()
	                        + " placed=" + area.getPlacedObjectiveName()),
	                true
	        );
	    }

	    private static int listAreas(CommandContext<ServerCommandSource> context) {
	        try {
	            var areas = MonthlyAreaStatsManager.getAreas();
	            if (areas.isEmpty()) {
	                context.getSource().sendFeedback(Text.literal("月間範囲統計は登録されていません"), false);
	                return 1;
	            }

	            context.getSource().sendFeedback(Text.literal("=== 月間範囲統計一覧 / 表示月: " + MonthlyAreaStatsManager.getDisplayedMonth() + " ===").formatted(Formatting.GOLD), false);
	            for (MonthlyAreaStatsManager.AreaConfig area : areas) {
	                context.getSource().sendFeedback(
	                        Text.literal("- " + area.id + " (" + area.displayName + ") "
	                                + area.dimension + " ["
	                                + area.minChunkX + " " + area.minChunkZ + "] -> ["
	                                + area.maxChunkX + " " + area.maxChunkZ + "] "
	                                + "mined=" + area.getMinedObjectiveName() + " placed=" + area.getPlacedObjectiveName()),
	                        false
	                );
	            }
	            return 1;
	        } catch (Exception e) {
	            ServerScoreboardLogger.error("Error listing monthly areas", e);
	            context.getSource().sendError(Text.literal("月間範囲統計の一覧表示中にエラーが発生しました: " + e.getMessage()));
	            return 0;
	        }
	    }

	    private static int removeArea(CommandContext<ServerCommandSource> context) {
	        try {
	            String id = StringArgumentType.getString(context, "id");
	            if (!MonthlyAreaStatsManager.removeArea(id)) {
	                context.getSource().sendError(Text.literal("不明な範囲 ID です: " + id));
	                return 0;
	            }
	            context.getSource().sendFeedback(Text.literal("月間範囲統計を削除しました: " + id), true);
	            return 1;
	        } catch (Exception e) {
	            ServerScoreboardLogger.error("Error removing monthly area", e);
	            context.getSource().sendError(Text.literal("月間範囲統計の削除中にエラーが発生しました: " + e.getMessage()));
	            return 0;
	        }
	    }

	    private static int setAreaMonth(CommandContext<ServerCommandSource> context) {
	        try {
	            String month = StringArgumentType.getString(context, "month");
	            MonthlyAreaStatsManager.setDisplayedMonth(month);
	            context.getSource().sendFeedback(Text.literal("月間範囲統計の表示月を " + MonthlyAreaStatsManager.getDisplayedMonth() + " に変更しました"), true);
	            return 1;
	        } catch (Exception e) {
	            context.getSource().sendError(Text.literal("表示月は yyyy-MM 形式で指定してください"));
	            return 0;
	        }
	    }

	    private static int setAreaMonthCurrent(CommandContext<ServerCommandSource> context) {
	        MonthlyAreaStatsManager.setDisplayedMonthToCurrent();
	        context.getSource().sendFeedback(Text.literal("月間範囲統計の表示月を現在月 " + MonthlyAreaStatsManager.getDisplayedMonth() + " に変更しました"), true);
	        return 1;
	    }

	    private static int updateAreas(CommandContext<ServerCommandSource> context) {
	        MonthlyAreaStatsManager.updateAllScoreboards();
	        context.getSource().sendFeedback(Text.literal("月間範囲統計のスコアボードを更新しました"), true);
	        return 1;
	    }

	    private static CompletableFuture<Suggestions> suggestAreas(CommandContext<ServerCommandSource> context, SuggestionsBuilder builder) {
	        for (MonthlyAreaStatsManager.AreaConfig area : MonthlyAreaStatsManager.getAreas()) {
	            builder.suggest(area.id, Text.literal(area.displayName));
	        }
	        return builder.buildFuture();
	    }

	    private static CompletableFuture<Suggestions> suggestAreaMonths(CommandContext<ServerCommandSource> context, SuggestionsBuilder builder) {
	        ArrayList<String> months = new ArrayList<>(MonthlyAreaStatsManager.getKnownMonths());
	        months.sort(Comparator.reverseOrder());
	        for (String month : months) {
	            builder.suggest(month);
	        }
	        return builder.buildFuture();
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

    private static int showVersion(CommandContext<ServerCommandSource> context) {
        context.getSource().sendFeedback(
            Text.literal("MySB - My Scoreboard").formatted(Formatting.GOLD)
                .append(Text.literal(" Version: ").formatted(Formatting.GRAY))
                .append(Text.literal(ServerOnlyScoreboardMod.getModVersion()).formatted(Formatting.AQUA)),
            false
        );
        return 1;
    }

    private static int enableFakePlayerScore(CommandContext<ServerCommandSource> context) {
        try {
            ServerScoreboardConfig.FAKE_PLAYER_SCORE_ENABLED = true;

            context.getSource().sendFeedback(
                Text.literal("Fake Player のスコア表示を有効にしました").formatted(Formatting.GREEN),
                true
            );

            return 1;
        } catch (Exception e) {
            ServerScoreboardLogger.error("Error enabling fake player score", e);
            context.getSource().sendError(Text.literal("Fake Player スコア有効化中にエラーが発生しました: " + e.getMessage()));
            return 0;
        }
    }

    private static int disableFakePlayerScore(CommandContext<ServerCommandSource> context) {
        try {
            ServerScoreboardConfig.FAKE_PLAYER_SCORE_ENABLED = false;

            context.getSource().sendFeedback(
                Text.literal("Fake Player のスコア表示を無効にしました").formatted(Formatting.YELLOW),
                true
            );

            return 1;
        } catch (Exception e) {
            ServerScoreboardLogger.error("Error disabling fake player score", e);
            context.getSource().sendError(Text.literal("Fake Player スコア無効化中にエラーが発生しました: " + e.getMessage()));
            return 0;
        }
    }

    // ========== Discord Commands ==========

    private static int openDiscordGUI(CommandContext<ServerCommandSource> context) {
        try {
            ServerCommandSource source = context.getSource();
            if (source.getEntity() instanceof ServerPlayerEntity player) {
                // Rate limit check
                if (!RateLimiter.canPerformAction(player.getUuid(), "gui", ServerScoreboardConfig.GUI_OPEN_COOLDOWN_MS)) {
                    source.sendError(Text.literal("コマンドを実行するには少し待ってください"));
                    return 0;
                }

                DiscordSettingsGUI.openFor(player);
                return 1;
            } else {
                source.sendError(Text.literal("このコマンドはプレイヤーのみ実行できます"));
                return 0;
            }
        } catch (Exception e) {
            ServerScoreboardLogger.error("Error opening Discord GUI", e);
            context.getSource().sendError(Text.literal("Discord GUI を開く際にエラーが発生しました: " + e.getMessage()));
            return 0;
        }
    }

    private static int setDiscordChannel(CommandContext<ServerCommandSource> context) {
        try {
            String channelId = StringArgumentType.getString(context, "id");

            // チャンネルIDのバリデーション（数字のみ）
            if (!channelId.matches("\\d+")) {
                context.getSource().sendError(Text.literal("無効なチャンネルID: 数字のみを入力してください"));
                return 0;
            }

            DiscordConfig.setForumChannelId(channelId);

            context.getSource().sendFeedback(
                Text.literal("Discordフォーラムチャンネルを設定しました: " + channelId).formatted(Formatting.GREEN),
                true
            );

            return 1;
        } catch (Exception e) {
            ServerScoreboardLogger.error("Error setting Discord channel", e);
            context.getSource().sendError(Text.literal("チャンネル設定中にエラーが発生しました: " + e.getMessage()));
            return 0;
        }
    }

    private static int showDiscordStatus(CommandContext<ServerCommandSource> context) {
        try {
            ServerCommandSource source = context.getSource();

            source.sendFeedback(Text.literal("=== Discord連携ステータス ===").formatted(Formatting.GOLD), false);

            // Token設定状態
            boolean hasToken = DiscordConfig.hasValidToken();
            source.sendFeedback(
                Text.literal("Bot Token: ").formatted(Formatting.GRAY)
                    .append(Text.literal(hasToken ? "設定済み" : "未設定").formatted(hasToken ? Formatting.GREEN : Formatting.RED)),
                false
            );

            // フォーラムチャンネル
            boolean hasChannel = DiscordConfig.hasForumChannelId();
            source.sendFeedback(
                Text.literal("フォーラムチャンネル: ").formatted(Formatting.GRAY)
                    .append(Text.literal(hasChannel ? DiscordConfig.getForumChannelId() : "未設定")
                        .formatted(hasChannel ? Formatting.GREEN : Formatting.RED)),
                false
            );

            // 接続状態
            boolean connected = DiscordManager.isConnectionVerified();
            source.sendFeedback(
                Text.literal("接続状態: ").formatted(Formatting.GRAY)
                    .append(Text.literal(connected ? "接続中" : "未接続").formatted(connected ? Formatting.GREEN : Formatting.RED)),
                false
            );

            // Discord有効統計
            Set<String> enabledStats = DiscordConfig.getDiscordEnabledStats();
            source.sendFeedback(
                Text.literal("Discord有効統計: ").formatted(Formatting.GRAY)
                    .append(Text.literal(enabledStats.isEmpty() ? "なし" : String.join(", ", enabledStats))
                        .formatted(enabledStats.isEmpty() ? Formatting.YELLOW : Formatting.AQUA)),
                false
            );

            // 次回更新
            if (connected && !enabledStats.isEmpty()) {
                source.sendFeedback(
                    Text.literal("次回更新まで: ").formatted(Formatting.GRAY)
                        .append(Text.literal(DiscordScheduler.getTimeUntilNextUpdateFormatted()).formatted(Formatting.AQUA)),
                    false
                );
            }

            return 1;
        } catch (Exception e) {
            ServerScoreboardLogger.error("Error showing Discord status", e);
            context.getSource().sendError(Text.literal("ステータス表示中にエラーが発生しました: " + e.getMessage()));
            return 0;
        }
    }

    private static int reconnectDiscord(CommandContext<ServerCommandSource> context) {
        try {
            if (!DiscordConfig.hasValidToken()) {
                context.getSource().sendError(Text.literal("Bot Tokenが設定されていません"));
                return 0;
            }

            context.getSource().sendFeedback(
                Text.literal("Discord接続をテスト中...").formatted(Formatting.YELLOW),
                false
            );

            DiscordManager.resetConnection();
            DiscordManager.testConnection().thenAccept(success -> {
                if (success) {
                    context.getSource().sendFeedback(
                        Text.literal("Discord接続に成功しました").formatted(Formatting.GREEN),
                        true
                    );
                } else {
                    context.getSource().sendError(Text.literal("Discord接続に失敗しました"));
                }
            });

            return 1;
        } catch (Exception e) {
            ServerScoreboardLogger.error("Error reconnecting Discord", e);
            context.getSource().sendError(Text.literal("再接続中にエラーが発生しました: " + e.getMessage()));
            return 0;
        }
    }

    private static int testDiscord(CommandContext<ServerCommandSource> context) {
        try {
            if (!DiscordConfig.hasValidToken()) {
                context.getSource().sendError(Text.literal("Bot Tokenが設定されていません"));
                return 0;
            }

            if (!DiscordConfig.hasForumChannelId()) {
                context.getSource().sendError(Text.literal("フォーラムチャンネルIDが設定されていません"));
                return 0;
            }

            context.getSource().sendFeedback(
                Text.literal("テスト投稿を実行中...").formatted(Formatting.YELLOW),
                false
            );

            DiscordStatsPublisher.testPublish().thenAccept(success -> {
                if (success) {
                    context.getSource().sendFeedback(
                        Text.literal("テスト投稿に成功しました").formatted(Formatting.GREEN),
                        true
                    );
                } else {
                    context.getSource().sendError(Text.literal("テスト投稿に失敗しました"));
                }
            });

            return 1;
        } catch (Exception e) {
            ServerScoreboardLogger.error("Error testing Discord", e);
            context.getSource().sendError(Text.literal("テスト投稿中にエラーが発生しました: " + e.getMessage()));
            return 0;
        }
    }

    private static int forceDiscordUpdate(CommandContext<ServerCommandSource> context) {
        try {
            if (!DiscordConfig.hasValidToken()) {
                context.getSource().sendError(Text.literal("Bot Tokenが設定されていません"));
                return 0;
            }

            if (!DiscordConfig.hasForumChannelId()) {
                context.getSource().sendError(Text.literal("フォーラムチャンネルIDが設定されていません"));
                return 0;
            }

            Set<String> enabledStats = DiscordConfig.getDiscordEnabledStats();
            if (enabledStats.isEmpty()) {
                context.getSource().sendError(Text.literal("Discord有効な統計がありません"));
                return 0;
            }

            if (DiscordScheduler.isUpdating()) {
                context.getSource().sendError(Text.literal("更新処理が既に実行中です"));
                return 0;
            }

            context.getSource().sendFeedback(
                Text.literal("Discord統計を更新中...").formatted(Formatting.YELLOW),
                false
            );

            DiscordScheduler.forceUpdate().thenRun(() -> {
                context.getSource().sendFeedback(
                    Text.literal("Discord統計の更新が完了しました").formatted(Formatting.GREEN),
                    true
                );
            });

            return 1;
        } catch (Exception e) {
            ServerScoreboardLogger.error("Error forcing Discord update", e);
            context.getSource().sendError(Text.literal("更新中にエラーが発生しました: " + e.getMessage()));
            return 0;
        }
    }
}
