package com.astralsmp.mysb;

import java.util.List;
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
import java.util.ArrayList;

public class ServerScoreboardGUIv2 implements GUIConstants {
    
    public enum GUIPage {
        STATISTICS,
        SCOREBOARD
    }
    
    public static void openFor(ServerPlayer player, GUIPage page) {
        SimpleContainer inventory = new SimpleContainer(GUI_SIZE);
        
        switch (page) {
            case STATISTICS:
                setupStatisticsGUI(inventory, player, 0);
                break;
            case SCOREBOARD:
                setupScoreboardGUI(inventory, player, 0);
                break;
        }
        
        player.openMenu(new net.minecraft.world.SimpleMenuProvider(
                (syncId, playerInventory, playerEntity) -> new ServerScoreboardScreenHandler(syncId, playerInventory, inventory, player, page),
                Component.literal(page == GUIPage.STATISTICS ? "統計選択" : "スコアボード選択")
        ));
    }
    
    private static void setupStatisticsGUI(SimpleContainer inventory, ServerPlayer player, int page) {
        inventory.clearContent();
        
        // Slot SLOT_SWITCH: Scoreboard button (compass icon)
        ItemStack scoreboardButton = new ItemStack(Items.COMPASS);
        scoreboardButton.set(DataComponents.CUSTOM_NAME, Component.literal("スコアボードを表示").withStyle(ChatFormatting.AQUA));
        inventory.setItem(SLOT_SWITCH, scoreboardButton);

        // Slot SLOT_RESET: Reset button
        ItemStack resetButton = new ItemStack(Items.BARRIER);
        resetButton.set(DataComponents.CUSTOM_NAME, Component.literal("デフォルトにリセット").withStyle(ChatFormatting.YELLOW));
        inventory.setItem(SLOT_RESET, resetButton);

        // Slot SLOT_CLOSE: Close button
        ItemStack closeButton = new ItemStack(Items.REDSTONE);
        closeButton.set(DataComponents.CUSTOM_NAME, Component.literal("閉じる").withStyle(ChatFormatting.RED));
        inventory.setItem(SLOT_CLOSE, closeButton);
        
        // Get only enabled total stats
        List<String> totalStats = new ArrayList<>();
        for (String objective : TotalStatsManager.getAllTotalObjectives()) {
            String id = objective.substring(6); // Remove "total_" prefix
            if (TotalStatsManager.getEnabledStats().contains(id)) {
                totalStats.add(objective);
            }
        }
        totalStats.sort(String::compareTo);
        
        // Navigation
        if (page > 0) {
            ItemStack prevButton = new ItemStack(Items.ARROW);
            prevButton.set(DataComponents.CUSTOM_NAME, Component.literal("前のページ").withStyle(ChatFormatting.AQUA));
            inventory.setItem(SLOT_PREV, prevButton);
        }

        if ((page + 1) * ITEMS_PER_PAGE < totalStats.size()) {
            ItemStack nextButton = new ItemStack(Items.ARROW);
            nextButton.set(DataComponents.CUSTOM_NAME, Component.literal("次のページ").withStyle(ChatFormatting.AQUA));
            inventory.setItem(SLOT_NEXT, nextButton);
        }

        // Display statistics
        int startIndex = page * ITEMS_PER_PAGE;
        int endIndex = Math.min(startIndex + ITEMS_PER_PAGE, totalStats.size());

        for (int i = startIndex; i < endIndex; i++) {
            String statObjective = totalStats.get(i);
            ItemStack statItem = new ItemStack(Items.GOLDEN_APPLE);
            String displayName = TotalStatsManager.getTotalDisplayName(statObjective);
            statItem.set(DataComponents.CUSTOM_NAME, Component.literal(displayName).withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD));
            inventory.setItem(SLOT_ITEMS_START + (i - startIndex), statItem);
        }

        // Fill empty slots
        for (int i = SLOT_ITEMS_START + (endIndex - startIndex); i < GUI_SIZE; i++) {
            ItemStack glassPane = new ItemStack(Items.GRAY_STAINED_GLASS_PANE);
            glassPane.set(DataComponents.CUSTOM_NAME, Component.literal(" "));
            inventory.setItem(i, glassPane);
        }
    }
    
    private static void setupScoreboardGUI(SimpleContainer inventory, ServerPlayer player, int page) {
        inventory.clearContent();
        
        // Slot SLOT_SWITCH: Statistics button (book icon)
        ItemStack statsButton = new ItemStack(Items.BOOK);
        statsButton.set(DataComponents.CUSTOM_NAME, Component.literal("統計を表示").withStyle(ChatFormatting.AQUA));
        inventory.setItem(SLOT_SWITCH, statsButton);

        // Slot SLOT_RESET: Reset button
        ItemStack resetButton = new ItemStack(Items.BARRIER);
        resetButton.set(DataComponents.CUSTOM_NAME, Component.literal("デフォルトにリセット").withStyle(ChatFormatting.YELLOW));
        inventory.setItem(SLOT_RESET, resetButton);

        // Slot SLOT_CLOSE: Close button
        ItemStack closeButton = new ItemStack(Items.REDSTONE);
        closeButton.set(DataComponents.CUSTOM_NAME, Component.literal("閉じる").withStyle(ChatFormatting.RED));
        inventory.setItem(SLOT_CLOSE, closeButton);
        
        // Get objectives list (excluding total stats)
        List<String> objectives = getObjectivesList(player, false);
        
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
        for (int i = SLOT_ITEMS_START + (endIndex - startIndex); i < GUI_SIZE; i++) {
            ItemStack glassPane = new ItemStack(Items.GRAY_STAINED_GLASS_PANE);
            glassPane.set(DataComponents.CUSTOM_NAME, Component.literal(" "));
            inventory.setItem(i, glassPane);
        }
    }
    
    private static List<String> getObjectivesList(ServerPlayer player, boolean includeTotalStats) {
        List<String> list = new ArrayList<>();
        
        player.level().getServer().getScoreboard().getObjectives().forEach(objective -> {
            String name = objective.getName();
            if (includeTotalStats || !TotalStatsManager.isTotalObjective(name)) {
                list.add(name);
            }
        });
        
        return list;
    }
    
    public static class ServerScoreboardScreenHandler extends ChestMenu implements GUIConstants {
        private final ServerPlayer player;
        private final SimpleContainer inventory;
        private final GUIPage currentPage;
        private int pageNumber = 0;
        
        public ServerScoreboardScreenHandler(int syncId, Inventory playerInventory, Container inventory, ServerPlayer player, GUIPage currentPage) {
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
                    // Switch between statistics and scoreboard
                    if (currentPage == GUIPage.STATISTICS) {
                        player.closeContainer();
                        ServerScoreboardGUIv2.openFor(player, GUIPage.SCOREBOARD);
                    } else {
                        player.closeContainer();
                        ServerScoreboardGUIv2.openFor(player, GUIPage.STATISTICS);
                    }
                    break;

                case SLOT_RESET:
                    // Reset button
                    resetToDefault();
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
                    List<String> items = currentPage == GUIPage.STATISTICS ?
                        getAllTotalStats() :
                        getObjectivesList(player, false);
                    if ((pageNumber + 1) * ITEMS_PER_PAGE < items.size()) {
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
        
        private List<String> getAllTotalStats() {
            List<String> totalStats = new ArrayList<>();
            for (String objective : TotalStatsManager.getAllTotalObjectives()) {
                String id = objective.substring(6); // Remove "total_" prefix
                if (TotalStatsManager.getEnabledStats().contains(id)) {
                    totalStats.add(objective);
                }
            }
            totalStats.sort(String::compareTo);
            return totalStats;
        }
        
        private void handleItemSelection(int slotIndex) {
            int itemIndex = pageNumber * ITEMS_PER_PAGE + (slotIndex - SLOT_ITEMS_START);
            
            if (currentPage == GUIPage.STATISTICS) {
                List<String> totalStats = getAllTotalStats();
                if (itemIndex < totalStats.size()) {
                    String selectedStat = totalStats.get(itemIndex);
                    selectObjective(selectedStat);
                }
            } else {
                List<String> objectives = getObjectivesList(player, false);
                if (itemIndex < objectives.size()) {
                    String selectedObjective = objectives.get(itemIndex);
                    selectObjective(selectedObjective);
                }
            }
        }
        
        private void selectObjective(String objective) {
            try {
                ServerScoreboardManager.setClientDisplayObjective(player.getUUID(), objective);
                player.sendSystemMessage(Component.literal("スコアボードを " + objective + " に設定しました").withStyle(ChatFormatting.GREEN));
                player.closeContainer();
            } catch (Exception e) {
                ServerScoreboardLogger.error("Failed to select objective: " + objective, e);
                player.sendSystemMessage(Component.literal("スコアボードの設定に失敗しました").withStyle(ChatFormatting.RED));
            }
        }
        
        private void resetToDefault() {
            try {
                ServerScoreboardManager.resetPlayerScoreboard(player.getUUID());
                player.sendSystemMessage(Component.literal("スコアボードをデフォルトにリセットしました").withStyle(ChatFormatting.GREEN));
                player.closeContainer();
            } catch (Exception e) {
                ServerScoreboardLogger.error("Failed to reset scoreboard", e);
                player.sendSystemMessage(Component.literal("リセットに失敗しました").withStyle(ChatFormatting.RED));
            }
        }
        
        private void refreshGUI() {
            if (currentPage == GUIPage.STATISTICS) {
                setupStatisticsGUI(inventory, player, pageNumber);
            } else {
                setupScoreboardGUI(inventory, player, pageNumber);
            }
        }
    }
}