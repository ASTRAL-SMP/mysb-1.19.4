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
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import java.util.Set;
import java.util.Map;

public class ServerScoreboardCommands {

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        // /mysb コマンド
        dispatcher.register(Commands.literal("mysb")
                .executes(ServerScoreboardCommands::openGUIForSender) // デフォルトでGUIを開く
                .then(Commands.literal("reload")
                        .requires(Commands.hasPermission(Commands.LEVEL_OWNERS)) // OPレベル4（最高権限）のみ
                        .executes(ServerScoreboardCommands::reloadScoreboard))
                .then(Commands.literal("total")
                        .requires(Commands.hasPermission(Commands.LEVEL_ALL)) // 権限レベル0（全員使用可能）
                        .executes(ServerScoreboardCommands::showTotalHelp) // /mysb totalでヘルプ表示
                        .then(Commands.literal("add")
                                .then(Commands.argument("id", StringArgumentType.word())
                                        .suggests((context, builder) -> {
                                            // IDのサジェスト
                                            builder.suggest("my_custom_stat", Component.literal("カスタム統計のID（英数字）"));
                                            return builder.buildFuture();
                                        })
                                        .then(Commands.argument("displayName", StringArgumentType.string())
                                                .suggests((context, builder) -> {
                                                    // 表示名のサジェスト
                                                    String id = StringArgumentType.getString(context, "id");
                                                    builder.suggest("\"My Custom Stat\"", Component.literal("表示名（スペースを含む場合は\"\"で囲む）"));
                                                    return builder.buildFuture();
                                                })
                                                .then(Commands.argument("statType", StringArgumentType.word())
                                                        .suggests(ServerScoreboardCommands::suggestStatTypes)
                                                        .executes(ServerScoreboardCommands::addTotalStat)))))
                        .then(Commands.literal("list")
                                .executes(ServerScoreboardCommands::listTotalStats))
                        .then(Commands.literal("update")
                                .executes(ServerScoreboardCommands::updateTotalStats))
	                        .then(Commands.literal("remove")
	                                .then(Commands.argument("id", StringArgumentType.word())
	                                        .suggests(ServerScoreboardCommands::suggestCustomStats)
	                                        .executes(ServerScoreboardCommands::removeTotalStat))))
	                .then(Commands.literal("area")
	                        .requires(Commands.hasPermission(Commands.LEVEL_ADMINS))
	                        .executes(ServerScoreboardCommands::showAreaHelp)
	                        .then(Commands.literal("pos1")
	                                .then(Commands.argument("id", StringArgumentType.word())
	                                        .executes(context -> setAreaSelectionPos(context, true))))
	                        .then(Commands.literal("pos2")
	                                .then(Commands.argument("id", StringArgumentType.word())
	                                        .executes(context -> setAreaSelectionPos(context, false))))
		                        .then(Commands.literal("create")
		                                .then(Commands.argument("id", StringArgumentType.word())
		                                        .then(Commands.argument("displayName", StringArgumentType.string())
		                                                .executes(ServerScoreboardCommands::createAreaFromSelection)
		                                                .then(Commands.argument("x1", IntegerArgumentType.integer())
		                                                        .then(Commands.argument("z1", IntegerArgumentType.integer())
		                                                                .then(Commands.argument("x2", IntegerArgumentType.integer())
		                                                                        .then(Commands.argument("z2", IntegerArgumentType.integer())
		                                                                                .executes(ServerScoreboardCommands::createAreaFromCoordinates))))))))
	                        .then(Commands.literal("list")
	                                .executes(ServerScoreboardCommands::listAreas))
	                        .then(Commands.literal("remove")
	                                .then(Commands.argument("id", StringArgumentType.word())
	                                        .suggests(ServerScoreboardCommands::suggestAreas)
	                                        .executes(ServerScoreboardCommands::removeArea)))
	                        .then(Commands.literal("month")
	                                .then(Commands.literal("current")
	                                        .executes(ServerScoreboardCommands::setAreaMonthCurrent))
	                                .then(Commands.argument("month", StringArgumentType.string())
	                                        .suggests(ServerScoreboardCommands::suggestAreaMonths)
	                                        .executes(ServerScoreboardCommands::setAreaMonth)))
	                        .then(Commands.literal("update")
	                                .executes(ServerScoreboardCommands::updateAreas)))
	                .then(Commands.literal("admin")
	                        .requires(Commands.hasPermission(Commands.LEVEL_ALL)) // 権限レベル0（全員使用可能）
                        .then(Commands.literal("gui")
                                .executes(ServerScoreboardCommands::openAdminGUI))
                        .then(Commands.literal("exclude")
                                .then(Commands.argument("player", StringArgumentType.word())
                                        .suggests(ServerScoreboardCommands::suggestOnlinePlayers)
                                        .executes(ServerScoreboardCommands::excludePlayer))
                                .then(Commands.literal("list")
                                        .executes(ServerScoreboardCommands::listExcludedPlayers)))
                        .then(Commands.literal("include")
                                .then(Commands.argument("player", StringArgumentType.word())
                                        .suggests(ServerScoreboardCommands::suggestExcludedPlayers)
                                        .executes(ServerScoreboardCommands::includePlayer)))
                        .then(Commands.literal("stats")
                                .then(Commands.literal("enable")
                                        .then(Commands.argument("stat", StringArgumentType.word())
                                                .suggests(ServerScoreboardCommands::suggestAvailableStats)
                                                .executes(ServerScoreboardCommands::enableStat)))
                                .then(Commands.literal("disable")
                                        .then(Commands.argument("stat", StringArgumentType.word())
                                                .suggests(ServerScoreboardCommands::suggestEnabledStats)
                                                .executes(ServerScoreboardCommands::disableStat)))
                                .then(Commands.literal("list")
                                        .executes(ServerScoreboardCommands::listStatStatus)))
                        .then(Commands.literal("fakeplayerscore")
                                .then(Commands.literal("enable")
                                        .requires(Commands.hasPermission(Commands.LEVEL_ADMINS)) // OPレベル3以上
                                        .executes(ServerScoreboardCommands::enableFakePlayerScore))
                                .then(Commands.literal("disable")
                                        .requires(Commands.hasPermission(Commands.LEVEL_ADMINS)) // OPレベル3以上
                                        .executes(ServerScoreboardCommands::disableFakePlayerScore))))
                .then(Commands.literal("version")
                        .executes(ServerScoreboardCommands::showVersion))
        );

        // /mysbdiscord コマンド（Discord連携専用）
        dispatcher.register(Commands.literal("mysbdiscord")
                .requires(Commands.hasPermission(Commands.LEVEL_ADMINS)) // OP3以上
                .executes(ServerScoreboardCommands::openDiscordGUI) // デフォルトでGUIを開く
                .then(Commands.literal("channel")
                        .then(Commands.argument("id", StringArgumentType.word())
                                .executes(ServerScoreboardCommands::setDiscordChannel)))
                .then(Commands.literal("status")
                        .executes(ServerScoreboardCommands::showDiscordStatus))
                .then(Commands.literal("reconnect")
                        .executes(ServerScoreboardCommands::reconnectDiscord))
                .then(Commands.literal("test")
                        .executes(ServerScoreboardCommands::testDiscord))
                .then(Commands.literal("update")
                        .executes(ServerScoreboardCommands::forceDiscordUpdate))
        );
    }

    private static int openAdminGUI(CommandContext<CommandSourceStack> context) {
        try {
            CommandSourceStack source = context.getSource();
            if (source.getEntity() instanceof ServerPlayer player) {
                // Rate limit check
                if (!RateLimiter.canPerformAction(player.getUUID(), "gui", ServerScoreboardConfig.GUI_OPEN_COOLDOWN_MS)) {
                    source.sendFailure(Component.literal("コマンドを実行するには少し待ってください"));
                    return 0;
                }

                // Open Admin GUI (統計管理から開始)
                ServerScoreboardAdminGUI.openFor(player, ServerScoreboardAdminGUI.AdminPage.STATS);
                return 1;
            } else {
                source.sendFailure(Component.literal("このコマンドはプレイヤーのみ実行できます"));
                return 0;
            }
        } catch (Exception e) {
            ServerScoreboardLogger.error("Error executing admin GUI command", e);
            context.getSource().sendFailure(Component.literal("コマンド実行中にエラーが発生しました: " + e.getMessage()));
            return 0;
        }
    }

    private static int openGUIForSender(CommandContext<CommandSourceStack> context) {
        try {
            CommandSourceStack source = context.getSource();
            if (source.getEntity() instanceof ServerPlayer player) {
                // Rate limit check
                if (!RateLimiter.canPerformAction(player.getUUID(), "gui", ServerScoreboardConfig.GUI_OPEN_COOLDOWN_MS)) {
                    source.sendFailure(Component.literal("コマンドを実行するには少し待ってください"));
                    return 0;
                }

                // Open GUI for sender (統計ページから開始)
                ServerScoreboardGUIv2.openFor(player, ServerScoreboardGUIv2.GUIPage.STATISTICS);
                return 1;
            } else {
                source.sendFailure(Component.literal("このコマンドはプレイヤーのみ実行できます"));
                return 0;
            }
        } catch (Exception e) {
            ServerScoreboardLogger.error("Error executing GUI command", e);
            context.getSource().sendFailure(Component.literal("コマンド実行中にエラーが発生しました: " + e.getMessage()));
            return 0;
        }
    }

    private static int reloadScoreboard(CommandContext<CommandSourceStack> context) {
        try {
            // レート制限チェック（コンソールからの実行も含む）
            CommandSourceStack source = context.getSource();
            if (source.getEntity() instanceof ServerPlayer player) {
                if (!RateLimiter.canPerformAction(player.getUUID(), "reload", ServerScoreboardConfig.COMMAND_COOLDOWN_MS * 10)) {
                    source.sendFailure(Component.literal("リロードコマンドを実行するには少し待ってください"));
                    return 0;
                }
	            }

	            ServerScoreboardManager.loadScoreboardData(context.getSource().getServer());
	            MonthlyAreaStatsManager.reload();

	            context.getSource().sendSuccess(() -> 
	                    Component.literal("スコアボードデータを再読み込みしました"),
                    false
            );

            return 1;
        } catch (Exception e) {
            ServerScoreboardLogger.error("Error reloading scoreboard data", e);
            context.getSource().sendFailure(Component.literal("スコアボードデータの再読み込み中にエラーが発生しました: " + e.getMessage()));
            return 0;
	        }
	    }

	    private static int showAreaHelp(CommandContext<CommandSourceStack> context) {
	        context.getSource().sendSuccess(() -> Component.literal("=== 月間範囲統計コマンド ===").withStyle(ChatFormatting.GOLD), false);
	        context.getSource().sendSuccess(() -> Component.literal("/mysb area pos1 <id>").withStyle(ChatFormatting.YELLOW), false);
        context.getSource().sendSuccess(() -> Component.literal("  現在位置のチャンクを始点に設定").withStyle(ChatFormatting.GRAY), false);
	        context.getSource().sendSuccess(() -> Component.literal("/mysb area pos2 <id>").withStyle(ChatFormatting.YELLOW), false);
        context.getSource().sendSuccess(() -> Component.literal("  現在位置のチャンクを終点に設定").withStyle(ChatFormatting.GRAY), false);
	        context.getSource().sendSuccess(() -> Component.literal("/mysb area create <id> <displayName>").withStyle(ChatFormatting.YELLOW), false);
	        context.getSource().sendSuccess(() -> Component.literal("  pos1/pos2 から月間採掘・設置スコアボードを作成").withStyle(ChatFormatting.GRAY), false);
        context.getSource().sendSuccess(() -> Component.literal("/mysb area create <id> <displayName> <x1> <z1> <x2> <z2>").withStyle(ChatFormatting.YELLOW), false);
        context.getSource().sendSuccess(() -> Component.literal("  チャンク座標を直接指定して作成").withStyle(ChatFormatting.GRAY), false);
	        context.getSource().sendSuccess(() -> Component.literal("/mysb area month <yyyy-MM|current>").withStyle(ChatFormatting.YELLOW), false);
	        context.getSource().sendSuccess(() -> Component.literal("  スコアボードへ表示する月を切り替え").withStyle(ChatFormatting.GRAY), false);
	        return 1;
	    }

	    private static int setAreaSelectionPos(CommandContext<CommandSourceStack> context, boolean firstPos) {
	        try {
	            CommandSourceStack source = context.getSource();
	            if (!(source.getEntity() instanceof ServerPlayer player)) {
	                source.sendFailure(Component.literal("このコマンドはプレイヤーのみ実行できます"));
	                return 0;
	            }

	            String id = StringArgumentType.getString(context, "id");
            MonthlyAreaStatsManager.AreaChunkPos pos = MonthlyAreaStatsManager.setSelectionPos(player, id, firstPos);
            source.sendSuccess(() -> 
                    Component.literal((firstPos ? "pos1" : "pos2") + " を設定しました: " + id + " chunk=" + pos.format()),
	                    false
	            );
	            return 1;
	        } catch (Exception e) {
	            ServerScoreboardLogger.error("Error setting monthly area selection", e);
	            context.getSource().sendFailure(Component.literal("範囲位置の設定中にエラーが発生しました: " + e.getMessage()));
	            return 0;
	        }
	    }

	    private static int createAreaFromSelection(CommandContext<CommandSourceStack> context) {
	        try {
	            CommandSourceStack source = context.getSource();
	            if (!(source.getEntity() instanceof ServerPlayer player)) {
	                source.sendFailure(Component.literal("このコマンドはプレイヤーのみ実行できます"));
	                return 0;
	            }

	            String id = StringArgumentType.getString(context, "id");
	            String displayName = StringArgumentType.getString(context, "displayName");
	            MonthlyAreaStatsManager.AreaConfig area = MonthlyAreaStatsManager.createAreaFromSelection(player, id, displayName);
	            sendAreaCreatedFeedback(source, area);
	            return 1;
	        } catch (Exception e) {
	            ServerScoreboardLogger.error("Error creating monthly area from selection", e);
	            context.getSource().sendFailure(Component.literal("月間範囲統計の作成中にエラーが発生しました: " + e.getMessage()));
	            return 0;
	        }
	    }

	    private static int createAreaFromCoordinates(CommandContext<CommandSourceStack> context) {
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
	            String dimension = context.getSource().getLevel().dimension().identifier().toString();
	            MonthlyAreaStatsManager.AreaConfig area = MonthlyAreaStatsManager.createArea(id, displayName, dimension, pos1, pos2);
	            sendAreaCreatedFeedback(context.getSource(), area);
	            return 1;
	        } catch (Exception e) {
	            ServerScoreboardLogger.error("Error creating monthly area from coordinates", e);
	            context.getSource().sendFailure(Component.literal("月間範囲統計の作成中にエラーが発生しました: " + e.getMessage()));
	            return 0;
	        }
	    }

	    private static void sendAreaCreatedFeedback(CommandSourceStack source, MonthlyAreaStatsManager.AreaConfig area) {
	        source.sendSuccess(() -> 
	                Component.literal("月間範囲統計を作成しました: " + area.id
	                        + " mined=" + area.getMinedObjectiveName()
	                        + " placed=" + area.getPlacedObjectiveName()),
	                true
	        );
	    }

	    private static int listAreas(CommandContext<CommandSourceStack> context) {
	        try {
	            var areas = MonthlyAreaStatsManager.getAreas();
	            if (areas.isEmpty()) {
	                context.getSource().sendSuccess(() -> Component.literal("月間範囲統計は登録されていません"), false);
	                return 1;
	            }

	            context.getSource().sendSuccess(() -> Component.literal("=== 月間範囲統計一覧 / 表示月: " + MonthlyAreaStatsManager.getDisplayedMonth() + " ===").withStyle(ChatFormatting.GOLD), false);
	            for (MonthlyAreaStatsManager.AreaConfig area : areas) {
	                context.getSource().sendSuccess(() -> 
	                        Component.literal("- " + area.id + " (" + area.displayName + ") "
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
	            context.getSource().sendFailure(Component.literal("月間範囲統計の一覧表示中にエラーが発生しました: " + e.getMessage()));
	            return 0;
	        }
	    }

	    private static int removeArea(CommandContext<CommandSourceStack> context) {
	        try {
	            String id = StringArgumentType.getString(context, "id");
	            if (!MonthlyAreaStatsManager.removeArea(id)) {
	                context.getSource().sendFailure(Component.literal("不明な範囲 ID です: " + id));
	                return 0;
	            }
	            context.getSource().sendSuccess(() -> Component.literal("月間範囲統計を削除しました: " + id), true);
	            return 1;
	        } catch (Exception e) {
	            ServerScoreboardLogger.error("Error removing monthly area", e);
	            context.getSource().sendFailure(Component.literal("月間範囲統計の削除中にエラーが発生しました: " + e.getMessage()));
	            return 0;
	        }
	    }

	    private static int setAreaMonth(CommandContext<CommandSourceStack> context) {
	        try {
	            String month = StringArgumentType.getString(context, "month");
	            MonthlyAreaStatsManager.setDisplayedMonth(month);
	            context.getSource().sendSuccess(() -> Component.literal("月間範囲統計の表示月を " + MonthlyAreaStatsManager.getDisplayedMonth() + " に変更しました"), true);
	            return 1;
	        } catch (Exception e) {
	            context.getSource().sendFailure(Component.literal("表示月は yyyy-MM 形式で指定してください"));
	            return 0;
	        }
	    }

	    private static int setAreaMonthCurrent(CommandContext<CommandSourceStack> context) {
	        MonthlyAreaStatsManager.setDisplayedMonthToCurrent();
	        context.getSource().sendSuccess(() -> Component.literal("月間範囲統計の表示月を現在月 " + MonthlyAreaStatsManager.getDisplayedMonth() + " に変更しました"), true);
	        return 1;
	    }

	    private static int updateAreas(CommandContext<CommandSourceStack> context) {
	        MonthlyAreaStatsManager.updateAllScoreboards();
	        context.getSource().sendSuccess(() -> Component.literal("月間範囲統計のスコアボードを更新しました"), true);
	        return 1;
	    }

	    private static CompletableFuture<Suggestions> suggestAreas(CommandContext<CommandSourceStack> context, SuggestionsBuilder builder) {
	        for (MonthlyAreaStatsManager.AreaConfig area : MonthlyAreaStatsManager.getAreas()) {
	            builder.suggest(area.id, Component.literal(area.displayName));
	        }
	        return builder.buildFuture();
	    }

	    private static CompletableFuture<Suggestions> suggestAreaMonths(CommandContext<CommandSourceStack> context, SuggestionsBuilder builder) {
	        ArrayList<String> months = new ArrayList<>(MonthlyAreaStatsManager.getKnownMonths());
	        months.sort(Comparator.reverseOrder());
	        for (String month : months) {
	            builder.suggest(month);
	        }
	        return builder.buildFuture();
	    }

	    private static int addTotalStat(CommandContext<CommandSourceStack> context) {
        try {
            String id = StringArgumentType.getString(context, "id");
            String displayName = StringArgumentType.getString(context, "displayName");
            String statType = StringArgumentType.getString(context, "statType");

            TotalStatsManager.addCustomTotalStat(id, displayName, statType);

            context.getSource().sendSuccess(() -> 
                Component.literal("トータル統計「" + displayName + "」を追加しました"),
                true
            );

            return 1;
        } catch (Exception e) {
            ServerScoreboardLogger.error("Error adding total stat", e);
            context.getSource().sendFailure(Component.literal("トータル統計の追加中にエラーが発生しました: " + e.getMessage()));
            return 0;
        }
    }

    private static int listTotalStats(CommandContext<CommandSourceStack> context) {
        try {
            var objectives = TotalStatsManager.getAllTotalObjectives();

            if (objectives.isEmpty()) {
                context.getSource().sendSuccess(() -> Component.literal("トータル統計が登録されていません"), false);
                return 1;
            }

            context.getSource().sendSuccess(() -> Component.literal("=== トータル統計一覧 ==="), false);
            for (String objName : objectives) {
                String displayName = TotalStatsManager.getTotalDisplayName(objName);
                context.getSource().sendSuccess(() -> 
                    Component.literal("- " + objName + " (" + displayName + ")"),
                    false
                );
            }

            return 1;
        } catch (Exception e) {
            ServerScoreboardLogger.error("Error listing total stats", e);
            context.getSource().sendFailure(Component.literal("トータル統計の一覧表示中にエラーが発生しました: " + e.getMessage()));
            return 0;
        }
    }

    private static int updateTotalStats(CommandContext<CommandSourceStack> context) {
        try {
            TotalStatsManager.updateAllTotalStats();

            context.getSource().sendSuccess(() -> 
                Component.literal("全てのトータル統計を更新しました"),
                true
            );

            return 1;
        } catch (Exception e) {
            ServerScoreboardLogger.error("Error updating total stats", e);
            context.getSource().sendFailure(Component.literal("トータル統計の更新中にエラーが発生しました: " + e.getMessage()));
            return 0;
        }
    }

    private static CompletableFuture<Suggestions> suggestAvailableStats(CommandContext<CommandSourceStack> context, SuggestionsBuilder builder) {
        Map<String, String> availableStats = TotalStatsManager.getAllAvailableStats();
        Set<String> enabledStats = TotalStatsManager.getEnabledStats();

        for (Map.Entry<String, String> entry : availableStats.entrySet()) {
            String statId = entry.getKey();
            String displayName = entry.getValue();
            // Only suggest stats that are not already enabled
            if (!enabledStats.contains(statId)) {
                builder.suggest(statId, Component.literal(displayName));
            }
        }

        return builder.buildFuture();
    }

    private static CompletableFuture<Suggestions> suggestEnabledStats(CommandContext<CommandSourceStack> context, SuggestionsBuilder builder) {
        Set<String> enabledStats = TotalStatsManager.getEnabledStats();
        Map<String, String> availableStats = TotalStatsManager.getAllAvailableStats();

        for (String statId : enabledStats) {
            String displayName = availableStats.getOrDefault(statId, statId);
            builder.suggest(statId, Component.literal(displayName));
        }

        return builder.buildFuture();
    }

    private static int enableStat(CommandContext<CommandSourceStack> context) {
        try {
            String statId = StringArgumentType.getString(context, "stat");

            if (!TotalStatsManager.getAllAvailableStats().containsKey(statId)) {
                context.getSource().sendFailure(Component.literal("不明な統計: " + statId));
                return 0;
            }

            TotalStatsManager.enableStat(statId);

            context.getSource().sendSuccess(() -> 
                Component.literal("統計を有効化しました: " + statId),
                true
            );

            return 1;
        } catch (Exception e) {
            ServerScoreboardLogger.error("Error enabling stat", e);
            context.getSource().sendFailure(Component.literal("統計の有効化中にエラーが発生しました: " + e.getMessage()));
            return 0;
        }
    }

    private static int disableStat(CommandContext<CommandSourceStack> context) {
        try {
            String statId = StringArgumentType.getString(context, "stat");

            TotalStatsManager.disableStat(statId);

            context.getSource().sendSuccess(() -> 
                Component.literal("統計を無効化しました: " + statId),
                true
            );

            return 1;
        } catch (Exception e) {
            ServerScoreboardLogger.error("Error disabling stat", e);
            context.getSource().sendFailure(Component.literal("統計の無効化中にエラーが発生しました: " + e.getMessage()));
            return 0;
        }
    }

    private static int listStatStatus(CommandContext<CommandSourceStack> context) {
        try {
            Map<String, String> allStats = TotalStatsManager.getAllAvailableStats();
            Set<String> enabledStats = TotalStatsManager.getEnabledStats();

            context.getSource().sendSuccess(() -> Component.literal("=== 統計の状態 ==="), false);

            context.getSource().sendSuccess(() -> Component.literal("有効:").withStyle(ChatFormatting.GREEN), false);
            for (String statId : enabledStats) {
                String displayName = allStats.getOrDefault(statId, statId);
                context.getSource().sendSuccess(() -> 
                    Component.literal("  - " + statId + " (" + displayName + ")").withStyle(ChatFormatting.GREEN),
                    false
                );
            }

            context.getSource().sendSuccess(() -> Component.literal("\n無効:").withStyle(ChatFormatting.RED), false);
            for (Map.Entry<String, String> entry : allStats.entrySet()) {
                if (!enabledStats.contains(entry.getKey())) {
                    context.getSource().sendSuccess(() -> 
                        Component.literal("  - " + entry.getKey() + " (" + entry.getValue() + ")").withStyle(ChatFormatting.RED),
                        false
                    );
                }
            }

            return 1;
        } catch (Exception e) {
            ServerScoreboardLogger.error("Error listing stat status", e);
            context.getSource().sendFailure(Component.literal("統計の一覧表示中にエラーが発生しました: " + e.getMessage()));
            return 0;
        }
    }

    private static int showTotalHelp(CommandContext<CommandSourceStack> context) {
        context.getSource().sendSuccess(() -> Component.literal("=== トータル統計コマンドの使い方 ===").withStyle(ChatFormatting.GOLD), false);
        context.getSource().sendSuccess(() -> Component.literal(""), false);
        context.getSource().sendSuccess(() -> Component.literal("/mysb total add <id> <displayName> <statType>").withStyle(ChatFormatting.YELLOW), false);
        context.getSource().sendSuccess(() -> Component.literal("  新しいトータル統計を追加").withStyle(ChatFormatting.GRAY), false);
        context.getSource().sendSuccess(() -> Component.literal("  例: /mysb total add damage \"Total Damage Dealt\" damage_dealt").withStyle(ChatFormatting.DARK_GRAY), false);
        context.getSource().sendSuccess(() -> Component.literal(""), false);
        context.getSource().sendSuccess(() -> Component.literal("/mysb total list").withStyle(ChatFormatting.YELLOW), false);
        context.getSource().sendSuccess(() -> Component.literal("  登録されているトータル統計を一覧表示").withStyle(ChatFormatting.GRAY), false);
        context.getSource().sendSuccess(() -> Component.literal(""), false);
        context.getSource().sendSuccess(() -> Component.literal("/mysb total update").withStyle(ChatFormatting.YELLOW), false);
        context.getSource().sendSuccess(() -> Component.literal("  トータル統計を手動更新").withStyle(ChatFormatting.GRAY), false);
        context.getSource().sendSuccess(() -> Component.literal(""), false);
        context.getSource().sendSuccess(() -> Component.literal("/mysb total remove <id>").withStyle(ChatFormatting.YELLOW), false);
        context.getSource().sendSuccess(() -> Component.literal("  カスタムトータル統計を削除").withStyle(ChatFormatting.GRAY), false);
        context.getSource().sendSuccess(() -> Component.literal(""), false);
        context.getSource().sendSuccess(() -> Component.literal("利用可能な統計タイプ:").withStyle(ChatFormatting.AQUA), false);
        context.getSource().sendSuccess(() -> Component.literal("  mined, placed, killed, deaths, damage_dealt,").withStyle(ChatFormatting.DARK_AQUA), false);
        context.getSource().sendSuccess(() -> Component.literal("  damage_taken, play_time, walk_one_cm, jump, fish_caught").withStyle(ChatFormatting.DARK_AQUA), false);
        return 1;
    }

    private static CompletableFuture<Suggestions> suggestStatTypes(CommandContext<CommandSourceStack> context, SuggestionsBuilder builder) {
        // 利用可能な統計タイプをサジェスト
        Map<String, String> commonStats = TotalStatsManager.COMMON_STATS;
        for (Map.Entry<String, String> entry : commonStats.entrySet()) {
            builder.suggest(entry.getKey(), Component.literal(entry.getValue()));
        }
        return builder.buildFuture();
    }

    private static CompletableFuture<Suggestions> suggestCustomStats(CommandContext<CommandSourceStack> context, SuggestionsBuilder builder) {
        // カスタム統計のIDをサジェスト（デフォルト以外）
        Map<String, String> allStats = TotalStatsManager.getAllAvailableStats();
        for (String statId : allStats.keySet()) {
            // デフォルト統計以外をサジェスト
            if (!statId.equals("mined") && !statId.equals("placed") &&
                !statId.equals("killed") && !statId.equals("deaths")) {
                builder.suggest(statId, Component.literal(allStats.get(statId)));
            }
        }
        return builder.buildFuture();
    }

    private static int removeTotalStat(CommandContext<CommandSourceStack> context) {
        try {
            String id = StringArgumentType.getString(context, "id");

            // デフォルト統計は削除できない
            if (id.equals("mined") || id.equals("placed") || id.equals("killed") || id.equals("deaths")) {
                context.getSource().sendFailure(Component.literal("デフォルト統計は削除できません"));
                return 0;
            }

            TotalStatsManager.disableStat(id);

            context.getSource().sendSuccess(() -> 
                Component.literal("トータル統計「" + id + "」を削除しました"),
                true
            );

            return 1;
        } catch (Exception e) {
            ServerScoreboardLogger.error("Error removing total stat", e);
            context.getSource().sendFailure(Component.literal("トータル統計の削除中にエラーが発生しました: " + e.getMessage()));
            return 0;
        }
    }

    private static int excludePlayer(CommandContext<CommandSourceStack> context) {
        try {
            String playerName = StringArgumentType.getString(context, "player");

            if (TotalStatsManager.isPlayerExcluded(playerName)) {
                context.getSource().sendFailure(Component.literal("プレイヤー " + playerName + " は既に除外されています"));
                return 0;
            }

            TotalStatsManager.excludePlayer(playerName);

            context.getSource().sendSuccess(() -> 
                Component.literal("プレイヤー " + playerName + " を統計から除外しました"),
                true
            );

            return 1;
        } catch (Exception e) {
            ServerScoreboardLogger.error("Error excluding player", e);
            context.getSource().sendFailure(Component.literal("プレイヤーの除外中にエラーが発生しました: " + e.getMessage()));
            return 0;
        }
    }

    private static int includePlayer(CommandContext<CommandSourceStack> context) {
        try {
            String playerName = StringArgumentType.getString(context, "player");

            if (!TotalStatsManager.isPlayerExcluded(playerName)) {
                context.getSource().sendFailure(Component.literal("プレイヤー " + playerName + " は除外されていません"));
                return 0;
            }

            TotalStatsManager.includePlayer(playerName);

            context.getSource().sendSuccess(() -> 
                Component.literal("プレイヤー " + playerName + " を統計に含めるようにしました"),
                true
            );

            return 1;
        } catch (Exception e) {
            ServerScoreboardLogger.error("Error including player", e);
            context.getSource().sendFailure(Component.literal("プレイヤーの包含中にエラーが発生しました: " + e.getMessage()));
            return 0;
        }
    }

    private static int listExcludedPlayers(CommandContext<CommandSourceStack> context) {
        try {
            Set<String> excludedPlayers = TotalStatsManager.getExcludedPlayers();

            if (excludedPlayers.isEmpty()) {
                context.getSource().sendSuccess(() -> Component.literal("除外されているプレイヤーはいません"), false);
                return 1;
            }

            context.getSource().sendSuccess(() -> Component.literal("=== 除外されているプレイヤー ===").withStyle(ChatFormatting.GOLD), false);
            for (String playerName : excludedPlayers) {
                context.getSource().sendSuccess(() -> 
                    Component.literal("- " + playerName).withStyle(ChatFormatting.YELLOW),
                    false
                );
            }

            return 1;
        } catch (Exception e) {
            ServerScoreboardLogger.error("Error listing excluded players", e);
            context.getSource().sendFailure(Component.literal("除外プレイヤーリストの表示中にエラーが発生しました: " + e.getMessage()));
            return 0;
        }
    }

    private static CompletableFuture<Suggestions> suggestOnlinePlayers(CommandContext<CommandSourceStack> context, SuggestionsBuilder builder) {
        for (ServerPlayer player : context.getSource().getServer().getPlayerList().getPlayers()) {
            String playerName = player.getName().getString();
            if (!TotalStatsManager.isPlayerExcluded(playerName)) {
                builder.suggest(playerName);
            }
        }
        return builder.buildFuture();
    }

    private static CompletableFuture<Suggestions> suggestExcludedPlayers(CommandContext<CommandSourceStack> context, SuggestionsBuilder builder) {
        for (String playerName : TotalStatsManager.getExcludedPlayers()) {
            builder.suggest(playerName);
        }
        return builder.buildFuture();
    }

    private static int showVersion(CommandContext<CommandSourceStack> context) {
        context.getSource().sendSuccess(() -> 
            Component.literal("MySB - My Scoreboard").withStyle(ChatFormatting.GOLD)
                .append(Component.literal(" Version: ").withStyle(ChatFormatting.GRAY))
                .append(Component.literal(ServerOnlyScoreboardMod.getModVersion()).withStyle(ChatFormatting.AQUA)),
            false
        );
        return 1;
    }

    private static int enableFakePlayerScore(CommandContext<CommandSourceStack> context) {
        try {
            ServerScoreboardConfig.FAKE_PLAYER_SCORE_ENABLED = true;
            // 設定を即座に永続化
            ServerScoreboardManager.saveScoreboardData(context.getSource().getServer());
            // 統計を即座に再計算（fake player を再表示するため）
            TotalStatsManager.scheduleInstantUpdate();

            context.getSource().sendSuccess(() -> 
                Component.literal("Fake Player のスコア表示を有効にしました").withStyle(ChatFormatting.GREEN),
                true
            );

            return 1;
        } catch (Exception e) {
            ServerScoreboardLogger.error("Error enabling fake player score", e);
            context.getSource().sendFailure(Component.literal("Fake Player スコア有効化中にエラーが発生しました: " + e.getMessage()));
            return 0;
        }
    }

    private static int disableFakePlayerScore(CommandContext<CommandSourceStack> context) {
        try {
            ServerScoreboardConfig.FAKE_PLAYER_SCORE_ENABLED = false;
            // 現在オンラインの Carpet fake player をすべて検出して既知リストに登録
            for (ServerPlayer online : context.getSource().getServer().getPlayerList().getPlayers()) {
                FakePlayerDetector.registerIfFakePlayer(online);
            }
            // 設定を即座に永続化
            ServerScoreboardManager.saveScoreboardData(context.getSource().getServer());
            // 統計を即座に再計算（fake player のスコアをスコアボードから消すため）
            TotalStatsManager.scheduleInstantUpdate();
            // 月間範囲統計のスコアボードも再計算
            MonthlyAreaStatsManager.updateAllScoreboards();

            context.getSource().sendSuccess(() -> 
                Component.literal("Fake Player のスコア表示を無効にしました").withStyle(ChatFormatting.YELLOW),
                true
            );

            return 1;
        } catch (Exception e) {
            ServerScoreboardLogger.error("Error disabling fake player score", e);
            context.getSource().sendFailure(Component.literal("Fake Player スコア無効化中にエラーが発生しました: " + e.getMessage()));
            return 0;
        }
    }

    // ========== Discord Commands ==========

    private static int openDiscordGUI(CommandContext<CommandSourceStack> context) {
        try {
            CommandSourceStack source = context.getSource();
            if (source.getEntity() instanceof ServerPlayer player) {
                // Rate limit check
                if (!RateLimiter.canPerformAction(player.getUUID(), "gui", ServerScoreboardConfig.GUI_OPEN_COOLDOWN_MS)) {
                    source.sendFailure(Component.literal("コマンドを実行するには少し待ってください"));
                    return 0;
                }

                DiscordSettingsGUI.openFor(player);
                return 1;
            } else {
                source.sendFailure(Component.literal("このコマンドはプレイヤーのみ実行できます"));
                return 0;
            }
        } catch (Exception e) {
            ServerScoreboardLogger.error("Error opening Discord GUI", e);
            context.getSource().sendFailure(Component.literal("Discord GUI を開く際にエラーが発生しました: " + e.getMessage()));
            return 0;
        }
    }

    private static int setDiscordChannel(CommandContext<CommandSourceStack> context) {
        try {
            String channelId = StringArgumentType.getString(context, "id");

            // チャンネルIDのバリデーション（数字のみ）
            if (!channelId.matches("\\d+")) {
                context.getSource().sendFailure(Component.literal("無効なチャンネルID: 数字のみを入力してください"));
                return 0;
            }

            DiscordConfig.setForumChannelId(channelId);

            context.getSource().sendSuccess(() -> 
                Component.literal("Discordフォーラムチャンネルを設定しました: " + channelId).withStyle(ChatFormatting.GREEN),
                true
            );

            return 1;
        } catch (Exception e) {
            ServerScoreboardLogger.error("Error setting Discord channel", e);
            context.getSource().sendFailure(Component.literal("チャンネル設定中にエラーが発生しました: " + e.getMessage()));
            return 0;
        }
    }

    private static int showDiscordStatus(CommandContext<CommandSourceStack> context) {
        try {
            CommandSourceStack source = context.getSource();

            source.sendSuccess(() -> Component.literal("=== Discord連携ステータス ===").withStyle(ChatFormatting.GOLD), false);

            // Token設定状態
            boolean hasToken = DiscordConfig.hasValidToken();
            source.sendSuccess(() -> 
                Component.literal("Bot Token: ").withStyle(ChatFormatting.GRAY)
                    .append(Component.literal(hasToken ? "設定済み" : "未設定").withStyle(hasToken ? ChatFormatting.GREEN : ChatFormatting.RED)),
                false
            );

            // フォーラムチャンネル
            boolean hasChannel = DiscordConfig.hasForumChannelId();
            source.sendSuccess(() -> 
                Component.literal("フォーラムチャンネル: ").withStyle(ChatFormatting.GRAY)
                    .append(Component.literal(hasChannel ? DiscordConfig.getForumChannelId() : "未設定")
                        .withStyle(hasChannel ? ChatFormatting.GREEN : ChatFormatting.RED)),
                false
            );

            // 接続状態
            boolean connected = DiscordManager.isConnectionVerified();
            source.sendSuccess(() -> 
                Component.literal("接続状態: ").withStyle(ChatFormatting.GRAY)
                    .append(Component.literal(connected ? "接続中" : "未接続").withStyle(connected ? ChatFormatting.GREEN : ChatFormatting.RED)),
                false
            );

            // Discord有効統計
            Set<String> enabledStats = DiscordConfig.getDiscordEnabledStats();
            source.sendSuccess(() -> 
                Component.literal("Discord有効統計: ").withStyle(ChatFormatting.GRAY)
                    .append(Component.literal(enabledStats.isEmpty() ? "なし" : String.join(", ", enabledStats))
                        .withStyle(enabledStats.isEmpty() ? ChatFormatting.YELLOW : ChatFormatting.AQUA)),
                false
            );

            // 次回更新
            if (connected && !enabledStats.isEmpty()) {
                source.sendSuccess(() -> 
                    Component.literal("次回更新まで: ").withStyle(ChatFormatting.GRAY)
                        .append(Component.literal(DiscordScheduler.getTimeUntilNextUpdateFormatted()).withStyle(ChatFormatting.AQUA)),
                    false
                );
            }

            return 1;
        } catch (Exception e) {
            ServerScoreboardLogger.error("Error showing Discord status", e);
            context.getSource().sendFailure(Component.literal("ステータス表示中にエラーが発生しました: " + e.getMessage()));
            return 0;
        }
    }

    private static int reconnectDiscord(CommandContext<CommandSourceStack> context) {
        try {
            if (!DiscordConfig.hasValidToken()) {
                context.getSource().sendFailure(Component.literal("Bot Tokenが設定されていません"));
                return 0;
            }

            context.getSource().sendSuccess(() -> 
                Component.literal("Discord接続をテスト中...").withStyle(ChatFormatting.YELLOW),
                false
            );

            DiscordManager.resetConnection();
            DiscordManager.testConnection().thenAccept(success -> {
                if (success) {
                    context.getSource().sendSuccess(() -> 
                        Component.literal("Discord接続に成功しました").withStyle(ChatFormatting.GREEN),
                        true
                    );
                } else {
                    context.getSource().sendFailure(Component.literal("Discord接続に失敗しました"));
                }
            });

            return 1;
        } catch (Exception e) {
            ServerScoreboardLogger.error("Error reconnecting Discord", e);
            context.getSource().sendFailure(Component.literal("再接続中にエラーが発生しました: " + e.getMessage()));
            return 0;
        }
    }

    private static int testDiscord(CommandContext<CommandSourceStack> context) {
        try {
            if (!DiscordConfig.hasValidToken()) {
                context.getSource().sendFailure(Component.literal("Bot Tokenが設定されていません"));
                return 0;
            }

            if (!DiscordConfig.hasForumChannelId()) {
                context.getSource().sendFailure(Component.literal("フォーラムチャンネルIDが設定されていません"));
                return 0;
            }

            context.getSource().sendSuccess(() -> 
                Component.literal("テスト投稿を実行中...").withStyle(ChatFormatting.YELLOW),
                false
            );

            DiscordStatsPublisher.testPublish().thenAccept(success -> {
                if (success) {
                    context.getSource().sendSuccess(() -> 
                        Component.literal("テスト投稿に成功しました").withStyle(ChatFormatting.GREEN),
                        true
                    );
                } else {
                    context.getSource().sendFailure(Component.literal("テスト投稿に失敗しました"));
                }
            });

            return 1;
        } catch (Exception e) {
            ServerScoreboardLogger.error("Error testing Discord", e);
            context.getSource().sendFailure(Component.literal("テスト投稿中にエラーが発生しました: " + e.getMessage()));
            return 0;
        }
    }

    private static int forceDiscordUpdate(CommandContext<CommandSourceStack> context) {
        try {
            if (!DiscordConfig.hasValidToken()) {
                context.getSource().sendFailure(Component.literal("Bot Tokenが設定されていません"));
                return 0;
            }

            if (!DiscordConfig.hasForumChannelId()) {
                context.getSource().sendFailure(Component.literal("フォーラムチャンネルIDが設定されていません"));
                return 0;
            }

            Set<String> enabledStats = DiscordConfig.getDiscordEnabledStats();
            if (enabledStats.isEmpty()) {
                context.getSource().sendFailure(Component.literal("Discord有効な統計がありません"));
                return 0;
            }

            if (DiscordScheduler.isUpdating()) {
                context.getSource().sendFailure(Component.literal("更新処理が既に実行中です"));
                return 0;
            }

            context.getSource().sendSuccess(() -> 
                Component.literal("Discord統計を更新中...").withStyle(ChatFormatting.YELLOW),
                false
            );

            DiscordScheduler.forceUpdate().thenRun(() -> {
                context.getSource().sendSuccess(() -> 
                    Component.literal("Discord統計の更新が完了しました").withStyle(ChatFormatting.GREEN),
                    true
                );
            });

            return 1;
        } catch (Exception e) {
            ServerScoreboardLogger.error("Error forcing Discord update", e);
            context.getSource().sendFailure(Component.literal("更新中にエラーが発生しました: " + e.getMessage()));
            return 0;
        }
    }
}
