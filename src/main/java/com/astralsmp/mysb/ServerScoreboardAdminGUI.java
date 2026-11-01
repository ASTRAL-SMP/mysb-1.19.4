package com.astralsmp.mysb;

import java.util.List;
import java.util.ArrayList;
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

public class ServerScoreboardAdminGUI implements GUIConstants {
    
    public enum AdminPage {
        STATS,
        OBJECTIVES
    }
    
    public static void openFor(ServerPlayer player, AdminPage page) {
        SimpleContainer inventory = new SimpleContainer(GUI_SIZE);
        
        switch (page) {
            case STATS:
                setupStatsGUI(inventory, player, 0);
                break;
            case OBJECTIVES:
                setupObjectivesGUI(inventory, player, 0);
                break;
        }
        
        player.openMenu(new net.minecraft.world.SimpleMenuProvider(
                (syncId, playerInventory, playerEntity) -> new AdminScreenHandler(syncId, playerInventory, inventory, player, page),
                Component.literal(page == AdminPage.STATS ? "統計管理" : "スコアボード管理")
        ));
    }
    
    private static void setupStatsGUI(SimpleContainer inventory, ServerPlayer player, int page) {
        inventory.clearContent();
        
        // Slot SLOT_SWITCH: スコアボード管理へ切り替え
        ItemStack switchButton = new ItemStack(Items.COMPASS);
        switchButton.set(DataComponents.CUSTOM_NAME, Component.literal("スコアボード管理へ").withStyle(ChatFormatting.AQUA));
        inventory.setItem(SLOT_SWITCH, switchButton);

        // Slot SLOT_CLOSE: Close button
        ItemStack closeButton = new ItemStack(Items.REDSTONE);
        closeButton.set(DataComponents.CUSTOM_NAME, Component.literal("閉じる").withStyle(ChatFormatting.RED));
        inventory.setItem(SLOT_CLOSE, closeButton);
        
        // Get all available stats
        Map<String, String> allStats = TotalStatsManager.getAllAvailableStats();
        Set<String> enabledStats = TotalStatsManager.getEnabledStats();
        List<Map.Entry<String, String>> statsList = new ArrayList<>(allStats.entrySet());
        
        // Navigation
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

        // Display stats
        int startIndex = page * ITEMS_PER_PAGE;
        int endIndex = Math.min(startIndex + ITEMS_PER_PAGE, statsList.size());

        for (int i = startIndex; i < endIndex; i++) {
            Map.Entry<String, String> stat = statsList.get(i);
            String statId = stat.getKey();
            String displayName = stat.getValue();
            boolean isEnabled = enabledStats.contains(statId);

            ItemStack statItem = new ItemStack(isEnabled ? Items.GOLDEN_APPLE : Items.APPLE);
            statItem.set(DataComponents.CUSTOM_NAME, Component.literal(displayName)
                    .withStyle(isEnabled ? ChatFormatting.GOLD : ChatFormatting.GRAY));
            List<Component> lore = new ArrayList<>();
            lore.add(Component.literal("ID: " + statId).withStyle(ChatFormatting.DARK_GRAY));
            lore.add(Component.literal(isEnabled ? "有効" : "無効")
                    .withStyle(isEnabled ? ChatFormatting.GREEN : ChatFormatting.RED));
            lore.add(Component.literal("クリックで切り替え").withStyle(ChatFormatting.GRAY));

            inventory.setItem(SLOT_ITEMS_START + (i - startIndex), statItem);
        }

        // Fill empty slots
        for (int i = 0; i < GUI_SIZE; i++) {
            if (inventory.getItem(i).isEmpty()) {
                ItemStack glassPane = new ItemStack(Items.GRAY_STAINED_GLASS_PANE);
                glassPane.set(DataComponents.CUSTOM_NAME, Component.literal(" "));
                inventory.setItem(i, glassPane);
            }
        }
    }
    
    private static void setupObjectivesGUI(SimpleContainer inventory, ServerPlayer player, int page) {
        inventory.clearContent();
        
        // Slot SLOT_SWITCH: 統計管理へ切り替え
        ItemStack switchButton = new ItemStack(Items.BOOK);
        switchButton.set(DataComponents.CUSTOM_NAME, Component.literal("統計管理へ").withStyle(ChatFormatting.AQUA));
        inventory.setItem(SLOT_SWITCH, switchButton);

        // Slot SLOT_CLOSE: Close button
        ItemStack closeButton = new ItemStack(Items.REDSTONE);
        closeButton.set(DataComponents.CUSTOM_NAME, Component.literal("閉じる").withStyle(ChatFormatting.RED));
        inventory.setItem(SLOT_CLOSE, closeButton);
        
        // Get objectives list
        List<String> objectives = getObjectivesList(player);
        
        // Navigation
        if (page > 0) {
            ItemStack prevButton = new ItemStack(Items.ARROW);
            prevButton.set(DataComponents.CUSTOM_NAME, Component.literal("前のページ").withStyle(ChatFormatting.AQUA));
            inventory.setItem(SLOT_PREV, prevButton);
        }

        if ((page + 1) * ITEMS_PER_PAGE < objectives.size()) {
            ItemStack nextButton = new ItemStack(Items.ARROW);
            nextButton.set(DataComponents.CUSTOM_NAME, Component.literal("次のページ").withStyle(ChatFormatting.AQUA));
            inventory.setItem(SLOT_NEXT, nextButton);
        }

        // Display objectives
        int startIndex = page * ITEMS_PER_PAGE;
        int endIndex = Math.min(startIndex + ITEMS_PER_PAGE, objectives.size());

        for (int i = startIndex; i < endIndex; i++) {
            String objective = objectives.get(i);
            ItemStack objectiveItem = new ItemStack(Items.PAPER);
            objectiveItem.set(DataComponents.CUSTOM_NAME, Component.literal(objective).withStyle(ChatFormatting.WHITE));
            inventory.setItem(SLOT_ITEMS_START + (i - startIndex), objectiveItem);
        }

        // Fill empty slots
        for (int i = 0; i < GUI_SIZE; i++) {
            if (inventory.getItem(i).isEmpty()) {
                ItemStack glassPane = new ItemStack(Items.GRAY_STAINED_GLASS_PANE);
                glassPane.set(DataComponents.CUSTOM_NAME, Component.literal(" "));
                inventory.setItem(i, glassPane);
            }
        }
    }
    
    private static List<String> getObjectivesList(ServerPlayer player) {
        List<String> list = new ArrayList<>();
        
        player.level().getServer().getScoreboard().getObjectives().forEach(objective -> {
            list.add(objective.getName());
        });
        
        return list;
    }
    
    public static class AdminScreenHandler extends ChestMenu implements GUIConstants {
        private final ServerPlayer player;
        private final SimpleContainer inventory;
        private final AdminPage currentPage;
        private int pageNumber = 0;
        
        public AdminScreenHandler(int syncId, Inventory playerInventory, Container inventory, ServerPlayer player, AdminPage currentPage) {
            super(MenuType.GENERIC_9x6, syncId, playerInventory, inventory, 6);
            this.player = player;
            this.inventory = (SimpleContainer) inventory;
            this.currentPage = currentPage;
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
                case SLOT_SWITCH:
                    // Switch between pages
                    if (currentPage == AdminPage.STATS) {
                        player.closeContainer();
                        ServerScoreboardAdminGUI.openFor(player, AdminPage.OBJECTIVES);
                    } else {
                        player.closeContainer();
                        ServerScoreboardAdminGUI.openFor(player, AdminPage.STATS);
                    }
                    break;

                case SLOT_CLOSE:
                    // Close button
                    player.closeContainer();
                    break;

                case SLOT_PREV:
                    // Previous page
                    if (pageNumber > 0) {
                        pageNumber--;
                        refreshGUI();
                    }
                    break;

                case SLOT_NEXT:
                    // Next page
                    int itemCount = 0;
                    if (currentPage == AdminPage.STATS) {
                        itemCount = TotalStatsManager.getAllAvailableStats().size();
                    } else {
                        itemCount = getObjectivesList(player).size();
                    }

                    if ((pageNumber + 1) * ITEMS_PER_PAGE < itemCount) {
                        pageNumber++;
                        refreshGUI();
                    }
                    break;

                default:
                    // Item selection (SLOT_ITEMS_START - SLOT_ITEMS_END)
                    if (slotIndex >= SLOT_ITEMS_START && slotIndex <= SLOT_ITEMS_END) {
                        handleItemSelection(slotIndex);
                    }
                    break;
            }
        }
        
        private void handleItemSelection(int slotIndex) {
            int itemIndex = pageNumber * ITEMS_PER_PAGE + (slotIndex - SLOT_ITEMS_START);

            try {
                if (currentPage == AdminPage.STATS) {
                    // 統計ページの場合
                    Map<String, String> allStats = TotalStatsManager.getAllAvailableStats();
                    List<Map.Entry<String, String>> statsList = new ArrayList<>(allStats.entrySet());

                    if (itemIndex < statsList.size()) {
                        String statId = statsList.get(itemIndex).getKey();
                        Set<String> enabledStats = TotalStatsManager.getEnabledStats();

                        if (enabledStats.contains(statId)) {
                            // 現在有効なので無効化
                            TotalStatsManager.disableStat(statId);
                            player.sendSystemMessage(Component.literal("統計「" + statId + "」を無効化しました")
                                    .withStyle(ChatFormatting.RED));
                        } else {
                            // 現在無効なので有効化
                            TotalStatsManager.enableStat(statId);
                            player.sendSystemMessage(Component.literal("統計「" + statId + "」を有効化しました")
                                    .withStyle(ChatFormatting.GREEN));
                        }

                        // GUIを更新
                        refreshGUI();
                    }
                } else {
                    // スコアボードページの場合
                    List<String> objectives = getObjectivesList(player);

                    if (itemIndex < objectives.size()) {
                        String objective = objectives.get(itemIndex);
                        // ここでは特に何もしない（将来の拡張用）
                        player.sendSystemMessage(Component.literal("スコアボード: " + objective).withStyle(ChatFormatting.AQUA));
                    }
                }
            } catch (Exception e) {
                ServerScoreboardLogger.error("Error handling item selection in admin GUI", e);
                player.sendSystemMessage(Component.literal("エラーが発生しました").withStyle(ChatFormatting.RED));
            }
        }
        
        private void refreshGUI() {
            if (currentPage == AdminPage.STATS) {
                setupStatsGUI(inventory, player, pageNumber);
            } else {
                setupObjectivesGUI(inventory, player, pageNumber);
            }
        }
    }
}