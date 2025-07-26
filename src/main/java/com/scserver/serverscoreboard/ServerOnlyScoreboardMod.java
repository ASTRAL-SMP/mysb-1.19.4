package com.scserver.serverscoreboard;

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
import net.minecraft.util.ActionResult;

public class ServerOnlyScoreboardMod implements DedicatedServerModInitializer {
    public static final String MOD_ID = "mysb";

    @Override
    public void onInitializeServer() {
        // サーバーサイドの初期化のみ
        ServerScoreboardLogger.info("MySB (My Scoreboard) Mod initializing on server...");
        
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
                // ブロック破壊時に統計を強制更新
                world.getServer().execute(() -> TotalStatsManager.forceUpdateAllStats());
            }
        });
        
        UseBlockCallback.EVENT.register((player, world, hand, hitResult) -> {
            if (!world.isClient && player instanceof ServerPlayerEntity) {
                // ブロック設置時に統計を強制更新（2tick後）
                if (world.getServer() != null) {
                    world.getServer().execute(() -> {
                        // 少し遅延させて統計が確実に更新されるようにする
                        world.getServer().execute(() -> TotalStatsManager.forceUpdateAllStats());
                    });
                }
            }
            return ActionResult.PASS;
        });
        
        ServerLivingEntityEvents.AFTER_DEATH.register((entity, damageSource) -> {
            // エンティティ死亡時に統計を強制更新
            if (entity.getServer() != null) {
                entity.getServer().execute(() -> TotalStatsManager.forceUpdateAllStats());
            }
        });
    }

    private void onServerStarted(MinecraftServer server) {
        // トータル統計システムの初期化（データ読み込み前に必要）
        TotalStatsManager.init(server);
        
        // scoreboard.datファイルの読み込み（TotalStatsManager設定も含む）
        ServerScoreboardManager.loadScoreboardData(server);
    }

    private void onServerStopping(MinecraftServer server) {
        // サーバー停止時にデータを保存
        ServerScoreboardManager.saveScoreboardData(server);
    }

    private void onPlayerJoin(net.minecraft.server.network.ServerPlayNetworkHandler handler, net.fabricmc.fabric.api.networking.v1.PacketSender sender, MinecraftServer server) {
        // プレイヤーログイン時の処理
        ServerScoreboardManager.onPlayerJoin(handler.getPlayer());
    }

    private void onPlayerDisconnect(net.minecraft.server.network.ServerPlayNetworkHandler handler, MinecraftServer server) {
        // プレイヤー切断時の処理
        ServerScoreboardManager.onPlayerDisconnect(handler.getPlayer());
    }

    private void onServerTick(MinecraftServer server) {
        // 定期的にクライアントのスコアボード状態を更新
        ServerScoreboardManager.updateClientScoreboards(server);
        
        // 毎ティックで統計をチェック（変更がある場合のみ更新）
        TotalStatsManager.updateAllTotalStats();
    }
}