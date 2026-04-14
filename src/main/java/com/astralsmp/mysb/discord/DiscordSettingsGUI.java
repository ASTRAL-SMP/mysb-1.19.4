package com.astralsmp.mysb.discord;

import com.astralsmp.mysb.GUIConstants;
import com.astralsmp.mysb.ServerScoreboardLogger;
import com.astralsmp.mysb.TotalStatsManager;
import net.minecraft.enchantment.Enchantments;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.inventory.Inventory;
import net.minecraft.inventory.SimpleInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.nbt.NbtList;
import net.minecraft.screen.GenericContainerScreenHandler;
import net.minecraft.screen.ScreenHandlerType;
import net.minecraft.screen.slot.Slot;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Discord設定GUI
 * /mysbdiscord コマンドで開く
 */
public class DiscordSettingsGUI implements GUIConstants {

    // 追加のスロット定義
    private static final int SLOT_INFO = 0;       // チャンネルID情報
    private static final int SLOT_STATUS = 4;     // 接続ステータス
    private static final int SLOT_CLOSE_BTN = 8;  // 閉じるボタン

    public static void openFor(ServerPlayerEntity player) {
        SimpleInventory inventory = new SimpleInventory(GUI_SIZE);
        setupGUI(inventory, player, 0);

        player.openHandledScreen(new net.minecraft.screen.SimpleNamedScreenHandlerFactory(
            (syncId, playerInventory, playerEntity) -> new DiscordScreenHandler(syncId, playerInventory, inventory, player),
            Text.literal("Discord設定")
        ));
    }

    private static void setupGUI(SimpleInventory inventory, ServerPlayerEntity player, int page) {
        inventory.clear();

        // スロット0: チャンネルID情報
        ItemStack infoItem = new ItemStack(Items.BOOK);
        infoItem.setCustomName(Text.literal("フォーラムチャンネル設定").formatted(Formatting.GOLD));
        List<Text> infoLore = new ArrayList<>();
        if (DiscordConfig.hasForumChannelId()) {
            infoLore.add(Text.literal("チャンネルID: " + DiscordConfig.getForumChannelId()).formatted(Formatting.GREEN));
        } else {
            infoLore.add(Text.literal("未設定").formatted(Formatting.RED));
            infoLore.add(Text.literal("/mysbdiscord channel <ID> で設定").formatted(Formatting.GRAY));
        }
        setLore(infoItem, infoLore);
        inventory.setStack(SLOT_INFO, infoItem);

        // スロット4: 接続ステータス
        boolean connected = DiscordManager.isConnectionVerified();
        ItemStack statusItem = new ItemStack(connected ? Items.LIME_CONCRETE : Items.RED_CONCRETE);
        statusItem.setCustomName(Text.literal("接続ステータス").formatted(connected ? Formatting.GREEN : Formatting.RED));
        List<Text> statusLore = new ArrayList<>();
        if (DiscordConfig.hasValidToken()) {
            statusLore.add(Text.literal(connected ? "接続中" : "未接続").formatted(connected ? Formatting.GREEN : Formatting.RED));
            if (!connected) {
                statusLore.add(Text.literal("/mysbdiscord reconnect で再接続").formatted(Formatting.GRAY));
            }
        } else {
            statusLore.add(Text.literal("トークン未設定").formatted(Formatting.RED));
            statusLore.add(Text.literal("config/mysb/discord_config.properties").formatted(Formatting.GRAY));
        }
        // 次回更新までの時間
        if (connected) {
            statusLore.add(Text.literal("次回更新: " + DiscordScheduler.getTimeUntilNextUpdateFormatted()).formatted(Formatting.AQUA));
        }
        setLore(statusItem, statusLore);
        inventory.setStack(SLOT_STATUS, statusItem);

        // スロット8: 閉じるボタン
        ItemStack closeButton = new ItemStack(Items.REDSTONE);
        closeButton.setCustomName(Text.literal("閉じる").formatted(Formatting.RED));
        inventory.setStack(SLOT_CLOSE_BTN, closeButton);

        // 行1: 区切り線（ガラス）
        for (int i = 9; i < 18; i++) {
            ItemStack glassPane = new ItemStack(Items.GRAY_STAINED_GLASS_PANE);
            glassPane.setCustomName(Text.literal(" "));
            inventory.setStack(i, glassPane);
        }

        // 行2: ページナビゲーション
        Map<String, String> allStats = TotalStatsManager.getAllAvailableStats();
        List<Map.Entry<String, String>> statsList = new ArrayList<>(allStats.entrySet());

        if (page > 0) {
            ItemStack prevButton = new ItemStack(Items.ARROW);
            prevButton.setCustomName(Text.literal("前のページ").formatted(Formatting.AQUA));
            inventory.setStack(SLOT_PREV, prevButton);
        }

        if ((page + 1) * ITEMS_PER_PAGE < statsList.size()) {
            ItemStack nextButton = new ItemStack(Items.ARROW);
            nextButton.setCustomName(Text.literal("次のページ").formatted(Formatting.AQUA));
            inventory.setStack(SLOT_NEXT, nextButton);
        }

        // 行2の残りをガラスで埋める
        for (int i = 18; i < 27; i++) {
            if (inventory.getStack(i).isEmpty()) {
                ItemStack glassPane = new ItemStack(Items.GRAY_STAINED_GLASS_PANE);
                glassPane.setCustomName(Text.literal(" "));
                inventory.setStack(i, glassPane);
            }
        }

        // 行3-5: 統計アイテム
        Set<String> discordEnabled = DiscordConfig.getDiscordEnabledStats();
        int startIndex = page * ITEMS_PER_PAGE;
        int endIndex = Math.min(startIndex + ITEMS_PER_PAGE, statsList.size());

        for (int i = startIndex; i < endIndex; i++) {
            Map.Entry<String, String> stat = statsList.get(i);
            String statId = stat.getKey();
            String displayName = stat.getValue();
            boolean isEnabled = discordEnabled.contains(statId);

            ItemStack statItem = new ItemStack(isEnabled ? Items.GOLDEN_APPLE : Items.APPLE);

            // Discord ONの場合はエンチャント効果を追加
            if (isEnabled) {
                statItem.addEnchantment(Enchantments.UNBREAKING, 1);
                // エンチャントのツールチップを非表示
                statItem.getOrCreateNbt().putInt("HideFlags", 1);
            }

            statItem.setCustomName(Text.literal(displayName)
                .formatted(isEnabled ? Formatting.GOLD : Formatting.GRAY));

            List<Text> lore = new ArrayList<>();
            lore.add(Text.literal("ID: " + statId).formatted(Formatting.DARK_GRAY));
            lore.add(Text.literal("Discord: " + (isEnabled ? "ON" : "OFF"))
                .formatted(isEnabled ? Formatting.GREEN : Formatting.RED));
            lore.add(Text.literal("クリックで切り替え").formatted(Formatting.GRAY));
            setLore(statItem, lore);

            inventory.setStack(SLOT_ITEMS_START + (i - startIndex), statItem);
        }

        // 空きスロットをガラスで埋める
        for (int i = 0; i < GUI_SIZE; i++) {
            if (inventory.getStack(i).isEmpty()) {
                ItemStack glassPane = new ItemStack(Items.GRAY_STAINED_GLASS_PANE);
                glassPane.setCustomName(Text.literal(" "));
                inventory.setStack(i, glassPane);
            }
        }
    }

    private static void setLore(ItemStack item, List<Text> lore) {
        NbtList loreList = new NbtList();
        for (Text line : lore) {
            loreList.add(net.minecraft.nbt.NbtString.of(Text.Serializer.toJson(line)));
        }
        item.getOrCreateSubNbt("display").put("Lore", loreList);
    }

    public static class DiscordScreenHandler extends GenericContainerScreenHandler implements GUIConstants {
        private final ServerPlayerEntity player;
        private final SimpleInventory inventory;
        private int pageNumber = 0;

        public DiscordScreenHandler(int syncId, PlayerInventory playerInventory, Inventory inventory, ServerPlayerEntity player) {
            super(ScreenHandlerType.GENERIC_9X6, syncId, playerInventory, inventory, 6);
            this.player = player;
            this.inventory = (SimpleInventory) inventory;
        }

        @Override
        public boolean canUse(PlayerEntity player) {
            return true;
        }

        @Override
        public ItemStack transferSlot(PlayerEntity player, int slot) {
            return ItemStack.EMPTY;
        }

        @Override
        public boolean canInsertIntoSlot(ItemStack stack, Slot slot) {
            return false;
        }

        @Override
        public void onSlotClick(int slotIndex, int button, SlotActionType actionType, PlayerEntity clickingPlayer) {
            if (slotIndex < 0 || slotIndex >= this.slots.size()) {
                return;
            }

            if (slotIndex < GUI_SIZE) {
                handleSlotClick(slotIndex);
            }
        }

        private void handleSlotClick(int slotIndex) {
            switch (slotIndex) {
                case SLOT_CLOSE_BTN:
                    player.closeHandledScreen();
                    break;

                case SLOT_PREV:
                    if (pageNumber > 0) {
                        pageNumber--;
                        refreshGUI();
                    }
                    break;

                case SLOT_NEXT:
                    int itemCount = TotalStatsManager.getAllAvailableStats().size();
                    if ((pageNumber + 1) * ITEMS_PER_PAGE < itemCount) {
                        pageNumber++;
                        refreshGUI();
                    }
                    break;

                default:
                    if (slotIndex >= SLOT_ITEMS_START && slotIndex <= SLOT_ITEMS_END) {
                        handleItemSelection(slotIndex);
                    }
                    break;
            }
        }

        private void handleItemSelection(int slotIndex) {
            int itemIndex = pageNumber * ITEMS_PER_PAGE + (slotIndex - SLOT_ITEMS_START);

            try {
                Map<String, String> allStats = TotalStatsManager.getAllAvailableStats();
                List<Map.Entry<String, String>> statsList = new ArrayList<>(allStats.entrySet());

                if (itemIndex < statsList.size()) {
                    String statId = statsList.get(itemIndex).getKey();
                    boolean wasEnabled = DiscordConfig.isDiscordEnabled(statId);

                    // トグル
                    DiscordConfig.toggleDiscordEnabled(statId);

                    boolean isNowEnabled = DiscordConfig.isDiscordEnabled(statId);

                    if (isNowEnabled) {
                        player.sendMessage(Text.literal("Discord送信を有効化: " + statId)
                            .formatted(Formatting.GREEN));
                    } else {
                        player.sendMessage(Text.literal("Discord送信を無効化: " + statId)
                            .formatted(Formatting.RED));
                    }

                    refreshGUI();
                }
            } catch (Exception e) {
                ServerScoreboardLogger.error("Error handling Discord GUI item selection", e);
                player.sendMessage(Text.literal("エラーが発生しました").formatted(Formatting.RED));
            }
        }

        private void refreshGUI() {
            setupGUI(inventory, player, pageNumber);
        }
    }
}
