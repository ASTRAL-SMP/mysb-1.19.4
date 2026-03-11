package com.scserver.serverscoreboard;

import com.scserver.serverscoreboard.discord.DiscordBot;
import com.scserver.serverscoreboard.discord.DiscordConfig;
import com.scserver.serverscoreboard.discord.DiscordScheduler;
import com.scserver.serverscoreboard.discord.DiscordStatsPublisher;
import net.fabricmc.api.DedicatedServerModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.network.ServerPlayNetworkHandler;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.event.player.UseItemCallback;
import net.fabricmc.fabric.api.event.player.AttackEntityCallback;
import net.minecraft.util.ActionResult;
import net.minecraft.util.TypedActionResult;
import net.fabricmc.loader.api.FabricLoader;
import java.util.UUID;

public class ServerOnlyScoreboardMod implements DedicatedServerModInitializer {
    public static final String MOD_ID = "mysb";
    
    public static String getModVersion() {
        return FabricLoader.getInstance()
                .getModContainer(MOD_ID)
                .map(modContainer -> modContainer.getMetadata().getVersion().getFriendlyString())
                .orElse("Unknown");
    }

    @Override
    public void onInitializeServer() {
        // サーバーサイドの初期化のみ
        
        // コマンド登録
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {
            ServerScoreboardCommands.register(dispatcher);
        });

        // サーバー開始時の処理
        ServerLifecycleEvents.SERVER_STARTED.register(this::onServerStarted);

        // サーバー停止時の処理
        ServerLifecycleEvents.SERVER_STOPPING.register(this::onServerStopping);

        // プレイヤー接続・切断イベント
        ServerPlayConnectionEvents.JOIN.register(this::onPlayerJoin);
        ServerPlayConnectionEvents.DISCONNECT.register(this::onPlayerDisconnect);

        // サーバーティック処理（定期的な同期など）
        ServerTickEvents.END_SERVER_TICK.register(this::onServerTick);
        
        // プレイヤーのアクションイベント（統計のリアルタイム更新用）
        PlayerBlockBreakEvents.AFTER.register((world, player, pos, state, entity) -> {
            if (!world.isClient && player instanceof ServerPlayerEntity) {
                
                // バルク更新システムを使用
                UUID playerId = ((ServerPlayerEntity) player).getUuid();
                boolean needsBulkUpdate = BulkUpdateManager.recordUpdate(playerId, "block_break");
                
                if (needsBulkUpdate) {
                    // バルク処理を実行
                    BulkUpdateManager.executeBulkUpdate(playerId);
                } else {
                    // 通常の即座更新
                    TotalStatsManager.scheduleInstantUpdate();
                }
            }
        });
        
        UseBlockCallback.EVENT.register((player, world, hand, hitResult) -> {
            if (!world.isClient && player instanceof ServerPlayerEntity) {
                
                // バルク更新システムを使用（ブロック設置）
                UUID playerId = ((ServerPlayerEntity) player).getUuid();
                boolean needsBulkUpdate = BulkUpdateManager.recordUpdate(playerId, "block_place");
                
                if (needsBulkUpdate) {
                    // バルク処理を実行
                    BulkUpdateManager.executeBulkUpdate(playerId);
                } else {
                    // 通常の即座更新
                    TotalStatsManager.scheduleInstantUpdate();
                }
            }
            return ActionResult.PASS;
        });
        
        ServerLivingEntityEvents.AFTER_DEATH.register((entity, damageSource) -> {
            // エンティティ死亡時の統計更新（バルクなし、即座実行）
            TotalStatsManager.scheduleInstantUpdate();
        });
        
        // アイテム使用時のイベント
        UseItemCallback.EVENT.register((player, world, hand) -> {
            if (!world.isClient && player instanceof ServerPlayerEntity) {
                // アイテム使用時の統計更新（即座実行）
                TotalStatsManager.scheduleInstantUpdate();
            }
            return TypedActionResult.pass(player.getStackInHand(hand));
        });
        
        // エンティティ攻撃時のイベント（キル統計用）
        AttackEntityCallback.EVENT.register((player, world, hand, entity, hitResult) -> {
            if (!world.isClient && player instanceof ServerPlayerEntity) {
                // 攻撃時の統計更新（即座実行）
                TotalStatsManager.scheduleInstantUpdate();
            }
            return ActionResult.PASS;
        });
    }

    private void onServerStarted(MinecraftServer server) {
        // ロガーにサーバーを設定
        ServerScoreboardLogger.setServer(server);

        // トータル統計システムの初期化（データ読み込み前に必要）
        TotalStatsManager.init(server);

        // プレイヤー統計キャッシュの初期化
        PlayerStatsCache.initialize(server);

        // scoreboard.datファイルの読み込み（TotalStatsManager設定も含む）
        ServerScoreboardManager.loadScoreboardData(server);

        // Discord連携の初期化
        DiscordConfig.initialize(server);
        DiscordStatsPublisher.initialize(server);
        DiscordScheduler.initialize(server);

        // Discord Botの初期化（スラッシュコマンド用）
        if (DiscordConfig.isBotEnabled()) {
            DiscordBot.initialize();
        }
        ServerScoreboardLogger.info("Discord integration initialized");
    }

    private void onServerStopping(MinecraftServer server) {
        // Discord Botをシャットダウン
        DiscordBot.shutdown();

        // サーバー停止時にデータを保存
        ServerScoreboardManager.saveScoreboardData(server);

        // プレイヤー統計キャッシュを保存
        PlayerStatsCache.saveCache();

        // Discord設定を保存
        DiscordConfig.save();
    }

    private void onPlayerJoin(net.minecraft.server.network.ServerPlayNetworkHandler handler, net.fabricmc.fabric.api.networking.v1.PacketSender sender, MinecraftServer server) {
        // プレイヤーログイン時の処理
        ServerScoreboardManager.onPlayerJoin(handler.getPlayer());
    }

    private void onPlayerDisconnect(net.minecraft.server.network.ServerPlayNetworkHandler handler, MinecraftServer server) {
        // プレイヤー切断時の処理
        ServerPlayerEntity player = handler.getPlayer();
        ServerScoreboardManager.onPlayerDisconnect(player);

        // バルク更新履歴をクリーンアップ
        BulkUpdateManager.clearPlayerHistory(player.getUuid());

        // メモリリーク防止: 各システムのプレイヤーデータをクリーンアップ
        RateLimiter.clearPlayer(player.getUuid());
        CustomScoreboardPacketSender.clearTransformedScoreCache(player.getUuidAsString());
        BatchedScoreboardUpdater.clearPlayer(player.getUuid());
    }

    private void onServerTick(MinecraftServer server) {
        // リアルタイム差分更新：毎tick実行（通常のScoreboardのような動作）
        ServerScoreboardManager.updateClientScoreboardsDifferential(server);
        TotalStatsManager.updateAllTotalStats();

        // スコアボード切り替え処理（INTERVAL毎に実行）
        if (server.getTicks() % ServerScoreboardConfig.UPDATE_INTERVAL_TICKS == 0) {
            ServerScoreboardManager.updateClientScoreboards(server);
        }

        // バッチ処理のフラッシュを定期的に実行
        BatchedScoreboardUpdater.flushAllBatches();

        // 5分ごとにキャッシュとスコアボードデータを保存（300秒 * 20 ticks/秒 = 6000 ticks）
        if (server.getTicks() % 6000 == 0) {
            PlayerStatsCache.saveCache();
            ServerScoreboardManager.saveScoreboardData(server);
        }

        // Discordスケジューラのティック処理（1時間ごとの自動更新）
        DiscordScheduler.onServerTick();
    }
}