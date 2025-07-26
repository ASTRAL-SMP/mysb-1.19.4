package com.scserver.serverscoreboard;

import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.inventory.Inventory;
import net.minecraft.inventory.SimpleInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.screen.GenericContainerScreenHandler;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.ScreenHandlerType;
import net.minecraft.screen.slot.Slot;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

import java.util.List;
import java.util.ArrayList;

public class ServerScoreboardGUI {
    private static final int GUI_SIZE = 54; // 6行のチェスト

    public static void openFor(ServerPlayerEntity player) {
        SimpleInventory inventory = new SimpleInventory(GUI_SIZE);
        setupGUI(inventory, player, 0);

        // サーバーサイドでのGUI実装
        player.openHandledScreen(new net.minecraft.screen.SimpleNamedScreenHandlerFactory(
                (syncId, playerInventory, playerEntity) -> new ServerScoreboardScreenHandler(syncId, playerInventory, inventory, player),
                Text.literal("スコアボード設定")
        ));
    }

    private static void setupGUI(SimpleInventory inventory, ServerPlayerEntity player, int page) {
        // 全スロットをクリア
        inventory.clear();

        // スロット0と8: 赤石の閉じるボタン
        ItemStack closeButton = new ItemStack(Items.REDSTONE);
        closeButton.setCustomName(Text.literal("閉じる").formatted(Formatting.RED));
        inventory.setStack(0, closeButton);
        inventory.setStack(8, closeButton);

        // スロット4: バリアブロック（リセットボタン）
        ItemStack resetButton = new ItemStack(Items.BARRIER);
        resetButton.setCustomName(Text.literal("デフォルト設定にリセット").formatted(Formatting.YELLOW));
        inventory.setStack(4, resetButton);

        // スロット18: 前のページ
        ItemStack prevButton = new ItemStack(Items.ARROW);
        prevButton.setCustomName(Text.literal("前のページ").formatted(Formatting.AQUA));
        if (page > 0) {
            inventory.setStack(18, prevButton);
        }

        // サーバーからObjectiveリストを取得
        List<String> objectives = getObjectivesList(player);

        // スロット26: 次のページ
        ItemStack nextButton = new ItemStack(Items.ARROW);
        nextButton.setCustomName(Text.literal("次のページ").formatted(Formatting.AQUA));
        if ((page + 1) * 27 < objectives.size()) {
            inventory.setStack(26, nextButton);
        }

        // スロット27-53: Objectiveアイテム（27スロット）
        int startIndex = page * 27;
        int endIndex = Math.min(startIndex + 27, objectives.size());

        for (int i = startIndex; i < endIndex; i++) {
            String objective = objectives.get(i);
            ItemStack objectiveItem;
            
            // トータル統計は金のリンゴで表示
            if (TotalStatsManager.isTotalObjective(objective)) {
                objectiveItem = new ItemStack(Items.GOLDEN_APPLE);
                String displayName = TotalStatsManager.getTotalDisplayName(objective);
                objectiveItem.setCustomName(Text.literal(displayName).formatted(Formatting.GOLD, Formatting.BOLD));
            } else {
                objectiveItem = new ItemStack(Items.PAPER);
                objectiveItem.setCustomName(Text.literal(objective).formatted(Formatting.WHITE));
            }

            inventory.setStack(27 + (i - startIndex), objectiveItem);
        }

        // 空のスロットをガラス板で埋める
        for (int i = 27 + (endIndex - startIndex); i < 54; i++) {
            ItemStack glassPane = new ItemStack(Items.GRAY_STAINED_GLASS_PANE);
            glassPane.setCustomName(Text.literal(" "));
            inventory.setStack(i, glassPane);
        }
    }

    private static List<String> getObjectivesList(ServerPlayerEntity player) {
        List<String> list = new ArrayList<>();

        // サーバーのスコアボードからObjectiveを取得
        player.getServer().getScoreboard().getObjectives().forEach(objective -> {
            list.add(objective.getName());
        });
        
        // トータル統計も追加（最初に表示）
        List<String> totalStats = TotalStatsManager.getAllTotalObjectives();
        totalStats.sort(String::compareTo);
        list.addAll(0, totalStats);

        return list;
    }

    public static class ServerScoreboardScreenHandler extends GenericContainerScreenHandler {
        private static final int GUI_SIZE = 54; // 6行のチェスト
        private final ServerPlayerEntity player;
        private final SimpleInventory inventory;
        private int currentPage = 0;

        public ServerScoreboardScreenHandler(int syncId, PlayerInventory playerInventory, Inventory inventory, ServerPlayerEntity player) {
            super(ScreenHandlerType.GENERIC_9X6, syncId, playerInventory, inventory, 6);
            this.player = player;
            this.inventory = (SimpleInventory) inventory;
        }

        @Override
        public boolean canUse(PlayerEntity player) {
            return true;
        }

        @Override
        public ItemStack quickMove(PlayerEntity player, int slot) {
            // クイックムーブを無効化
            return ItemStack.EMPTY;
        }

        @Override
        public boolean canInsertIntoSlot(ItemStack stack, Slot slot) {
            // アイテムの挿入を無効化
            return false;
        }

        @Override
        public void onSlotClick(int slotIndex, int button, SlotActionType actionType, PlayerEntity clickingPlayer) {
            if (slotIndex < 0 || slotIndex >= this.slots.size()) {
                return;
            }
            
            // GUI内のアイテムのみ処理（プレイヤーインベントリは無視）
            if (slotIndex < GUI_SIZE) {
                handleSlotClick(slotIndex);
            }
            
            // アイテムの移動を禁止
            return;
        }

        private void handleSlotClick(int slotIndex) {
            List<String> objectives = getObjectivesList(player);

            switch (slotIndex) {
                case 0:
                case 8:
                    // 閉じるボタン
                    player.closeHandledScreen();
                    break;

                case 4:
                    // リセットボタン
                    resetToDefault();
                    break;

                case 18:
                    // 前のページ
                    if (currentPage > 0) {
                        currentPage--;
                        refreshGUI();
                    }
                    break;

                case 26:
                    // 次のページ
                    if ((currentPage + 1) * 27 < objectives.size()) {
                        currentPage++;
                        refreshGUI();
                    }
                    break;

                default:
                    // Objectiveスロット（27-53）
                    if (slotIndex >= 27 && slotIndex <= 53) {
                        int objectiveIndex = currentPage * 27 + (slotIndex - 27);
                        if (objectiveIndex < objectives.size()) {
                            String selectedObjective = objectives.get(objectiveIndex);
                            selectObjective(selectedObjective);
                        }
                    }
                    break;
            }
        }

        private void resetToDefault() {
            // デフォルト設定にリセット
            try {
                ServerScoreboardManager.resetPlayerScoreboard(player.getUuid());
                player.sendMessage(Text.literal("スコアボード設定をデフォルトにリセットしました").formatted(Formatting.GREEN));
                player.closeHandledScreen();
            } catch (Exception e) {
                ServerScoreboardLogger.error("Failed to reset scoreboard", e);
                player.sendMessage(Text.literal("リセットに失敗しました").formatted(Formatting.RED));
            }
        }

        private void selectObjective(String objective) {
            // Objectiveを選択
            try {
                ServerScoreboardManager.setClientDisplayObjective(player.getUuid(), objective);
                player.sendMessage(Text.literal("クライアントスコアボードを " + objective + " に設定しました").formatted(Formatting.GREEN));
                player.closeHandledScreen();
            } catch (Exception e) {
                ServerScoreboardLogger.error("Failed to select objective: " + objective, e);
                player.sendMessage(Text.literal("スコアボードの設定に失敗しました").formatted(Formatting.RED));
            }
        }

        private void refreshGUI() {
            setupGUI(inventory, player, currentPage);
        }
    }
}