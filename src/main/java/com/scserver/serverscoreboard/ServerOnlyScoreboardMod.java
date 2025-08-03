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
import net.fabricmc.fabric.api.event.player.UseItemCallback;
import net.fabricmc.fabric.api.event.player.AttackEntityCallback;
import net.minecraft.util.ActionResult;
import net.minecraft.util.TypedActionResult;
import net.fabricmc.loader.api.FabricLoader;

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
                // デバッグログ
                ServerScoreboardLogger.debug(String.format("ブロック破壊: %s が %s を破壊しました (位置: %s)", 
                    player.getName().getString(), 
                    state.getBlock().getName().getString(), 
                    pos.toString()));
                
                // ブロック破壊時に統計を強制更新
                world.getServer().execute(() -> TotalStatsManager.forceUpdateAllStats());
            }
        });
        
        UseBlockCallback.EVENT.register((player, world, hand, hitResult) -> {
            if (!world.isClient && player instanceof ServerPlayerEntity) {
                // デバッグログ（ブロック設置の可能性）
                if (player.getStackInHand(hand) != null && !player.getStackInHand(hand).isEmpty()) {
                    ServerScoreboardLogger.debug(String.format("ブロック使用: %s が %s を使用しました (位置: %s)", 
                        player.getName().getString(), 
                        player.getStackInHand(hand).getName().getString(), 
                        hitResult.getBlockPos().toString()));
                }
                
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
        
        // アイテム使用時のイベント
        UseItemCallback.EVENT.register((player, world, hand) -> {
            if (!world.isClient && player instanceof ServerPlayerEntity) {
                // アイテム使用時に統計を強制更新（1tick後）
                if (player.getServer() != null) {
                    player.getServer().execute(() -> {
                        player.getServer().execute(() -> TotalStatsManager.forceUpdateAllStats());
                    });
                }
            }
            return TypedActionResult.pass(player.getStackInHand(hand));
        });
        
        // エンティティ攻撃時のイベント（キル統計用）
        AttackEntityCallback.EVENT.register((player, world, hand, entity, hitResult) -> {
            if (!world.isClient && player instanceof ServerPlayerEntity) {
                // 攻撃時に統計を強制更新（2tick後、キルが確定してから）
                if (player.getServer() != null) {
                    player.getServer().execute(() -> {
                        player.getServer().execute(() -> {
                            player.getServer().execute(() -> TotalStatsManager.forceUpdateAllStats());
                        });
                    });
                }
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
        
        // Discord Botの初期化
        SimpleDiscordBot.getInstance().initialize(server);
        
        // デバッグモードの状態をログに記録
        if (ServerScoreboardConfig.DEBUG_MODE_ENABLED) {
            ServerScoreboardLogger.info("Debug mode is ENABLED");
        }
    }

    private void onServerStopping(MinecraftServer server) {
        // サーバー停止時にデータを保存
        ServerScoreboardManager.saveScoreboardData(server);
        
        // プレイヤー統計キャッシュを保存
        PlayerStatsCache.saveCache();
        
        // Discord Botのシャットダウン
        if (SimpleDiscordBot.getInstance().isRunning()) {
            SimpleDiscordBot.getInstance().shutdown();
        }
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
        
        // 5分ごとにキャッシュを保存（300秒 * 20 ticks/秒 = 6000 ticks）
        if (server.getTicks() % 6000 == 0) {
            PlayerStatsCache.saveCache();
        }
    }
}