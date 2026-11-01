package com.astralsmp.mysb.discord;

import com.astralsmp.mysb.GUIConstants;
import com.astralsmp.mysb.ServerScoreboardLogger;
import com.astralsmp.mysb.TotalStatsManager;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.ChatFormatting;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ItemLore;

/**
 * Discord設定GUI
 * /mysbdiscord コマンドで開く
 */
public class DiscordSettingsGUI implements GUIConstants {

    // 追加のスロット定義
    private static final int SLOT_INFO = 0;       // チャンネルID情報
    private static final int SLOT_STATUS = 4;     // 接続ステータス
    private static final int SLOT_CLOSE_BTN = 8;  // 閉じるボタン

    public static void openFor(ServerPlayer player) {
        SimpleContainer inventory = new SimpleContainer(GUI_SIZE);
        setupGUI(inventory, player, 0);

        player.openMenu(new net.minecraft.world.SimpleMenuProvider(
            (syncId, playerInventory, playerEntity) -> new DiscordScreenHandler(syncId, playerInventory, inventory, player),
            Component.literal("Discord設定")
        ));
    }

    private static void setupGUI(SimpleContainer inventory, ServerPlayer player, int page) {
        inventory.clearContent();

        // スロット0: チャンネルID情報
        ItemStack infoItem = new ItemStack(Items.BOOK);
        infoItem.set(DataComponents.CUSTOM_NAME, Component.literal("フォーラムチャンネル設定").withStyle(ChatFormatting.GOLD));
        List<Component> infoLore = new ArrayList<>();
        if (DiscordConfig.hasForumChannelId()) {
            infoLore.add(Component.literal("チャンネルID: " + DiscordConfig.getForumChannelId()).withStyle(ChatFormatting.GREEN));
        } else {
            infoLore.add(Component.literal("未設定").withStyle(ChatFormatting.RED));
            infoLore.add(Component.literal("/mysbdiscord channel <ID> で設定").withStyle(ChatFormatting.GRAY));
        }
        setLore(infoItem, infoLore);
        inventory.setItem(SLOT_INFO, infoItem);

        // スロット4: 接続ステータス
        boolean connected = DiscordManager.isConnectionVerified();
        ItemStack statusItem = new ItemStack(connected ? Items.LIME_CONCRETE : Items.RED_CONCRETE);
        statusItem.set(DataComponents.CUSTOM_NAME, Component.literal("接続ステータス").withStyle(connected ? ChatFormatting.GREEN : ChatFormatting.RED));
        List<Component> statusLore = new ArrayList<>();
        if (DiscordConfig.hasValidToken()) {
            statusLore.add(Component.literal(connected ? "接続中" : "未接続").withStyle(connected ? ChatFormatting.GREEN : ChatFormatting.RED));
            if (!connected) {
                statusLore.add(Component.literal("/mysbdiscord reconnect で再接続").withStyle(ChatFormatting.GRAY));
            }
        } else {
            statusLore.add(Component.literal("トークン未設定").withStyle(ChatFormatting.RED));
            statusLore.add(Component.literal("config/mysb/discord_config.properties").withStyle(ChatFormatting.GRAY));
        }
        // 次回更新までの時間
        if (connected) {
            statusLore.add(Component.literal("次回更新: " + DiscordScheduler.getTimeUntilNextUpdateFormatted()).withStyle(ChatFormatting.AQUA));
        }
        setLore(statusItem, statusLore);
        inventory.setItem(SLOT_STATUS, statusItem);

        // スロット8: 閉じるボタン
        ItemStack closeButton = new ItemStack(Items.REDSTONE);
        closeButton.set(DataComponents.CUSTOM_NAME, Component.literal("閉じる").withStyle(ChatFormatting.RED));
        inventory.setItem(SLOT_CLOSE_BTN, closeButton);

        // 行1: 区切り線（ガラス）
        for (int i = 9; i < 18; i++) {
            ItemStack glassPane = new ItemStack(Items.GRAY_STAINED_GLASS_PANE);
            glassPane.set(DataComponents.CUSTOM_NAME, Component.literal(" "));
            inventory.setItem(i, glassPane);
        }

        // 行2: ページナビゲーション
        Map<String, String> allStats = TotalStatsManager.getAllAvailableStats();
        List<Map.Entry<String, String>> statsList = new ArrayList<>(allStats.entrySet());

        if (page > 0) {
            ItemStack prevButton = new ItemStack(Items.ARROW);
            prevButton.set(DataComponents.CUSTOM_NAME, Component.literal("前のページ").withStyle(ChatFormatting.AQUA));
            inventory.setItem(SLOT_PREV, prevButton);
        }

        if ((page + 1) * ITEMS_PER_PAGE < statsList.size()) {
            ItemStack nextButton = new ItemStack(Items.ARROW);
            nextButton.set(DataComponents.CUSTOM_NAME, Component.literal("次のページ").withStyle(ChatFormatting.AQUA));
            inventory.setItem(SLOT_NEXT, nextButton);
        }

        // 行2の残りをガラスで埋める
        for (int i = 18; i < 27; i++) {
            if (inventory.getItem(i).isEmpty()) {
                ItemStack glassPane = new ItemStack(Items.GRAY_STAINED_GLASS_PANE);
                glassPane.set(DataComponents.CUSTOM_NAME, Component.literal(" "));
                inventory.setItem(i, glassPane);
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
                statItem.set(DataComponents.ENCHANTMENT_GLINT_OVERRIDE, true);
            }

            statItem.set(DataComponents.CUSTOM_NAME, Component.literal(displayName)
                .withStyle(isEnabled ? ChatFormatting.GOLD : ChatFormatting.GRAY));

            List<Component> lore = new ArrayList<>();
            lore.add(Component.literal("ID: " + statId).withStyle(ChatFormatting.DARK_GRAY));
            lore.add(Component.literal("Discord: " + (isEnabled ? "ON" : "OFF"))
                .withStyle(isEnabled ? ChatFormatting.GREEN : ChatFormatting.RED));
            lore.add(Component.literal("クリックで切り替え").withStyle(ChatFormatting.GRAY));
            setLore(statItem, lore);

            inventory.setItem(SLOT_ITEMS_START + (i - startIndex), statItem);
        }

        // 空きスロットをガラスで埋める
        for (int i = 0; i < GUI_SIZE; i++) {
            if (inventory.getItem(i).isEmpty()) {
                ItemStack glassPane = new ItemStack(Items.GRAY_STAINED_GLASS_PANE);
                glassPane.set(DataComponents.CUSTOM_NAME, Component.literal(" "));
                inventory.setItem(i, glassPane);
            }
        }
    }

    private static void setLore(ItemStack item, List<Component> lore) {
        item.set(DataComponents.LORE, new ItemLore(lore));
    }

    public static class DiscordScreenHandler extends ChestMenu implements GUIConstants {
        private final ServerPlayer player;
        private final SimpleContainer inventory;
        private int pageNumber = 0;

        public DiscordScreenHandler(int syncId, Inventory playerInventory, Container inventory, ServerPlayer player) {
            super(MenuType.GENERIC_9x6, syncId, playerInventory, inventory, 6);
            this.player = player;
            this.inventory = (SimpleContainer) inventory;
        }

        @Override
        public boolean stillValid(Player player) {
            return true;
        }

        @Override
        public ItemStack quickMoveStack(Player player, int slot) {
            return ItemStack.EMPTY;
        }

        @Override
        public boolean canTakeItemForPickAll(ItemStack stack, Slot slot) {
            return false;
        }

        @Override
        public void clicked(int slotIndex, int button, ContainerInput actionType, Player clickingPlayer) {
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
                    player.closeContainer();
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
                        player.sendSystemMessage(Component.literal("Discord送信を有効化: " + statId)
                            .withStyle(ChatFormatting.GREEN));
                    } else {
                        player.sendSystemMessage(Component.literal("Discord送信を無効化: " + statId)
                            .withStyle(ChatFormatting.RED));
                    }

                    refreshGUI();
                }
            } catch (Exception e) {
                ServerScoreboardLogger.error("Error handling Discord GUI item selection", e);
                player.sendSystemMessage(Component.literal("エラーが発生しました").withStyle(ChatFormatting.RED));
            }
        }

        private void refreshGUI() {
            setupGUI(inventory, player, pageNumber);
        }
    }
}
