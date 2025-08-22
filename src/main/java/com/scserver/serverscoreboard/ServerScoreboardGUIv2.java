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

public class ServerScoreboardGUIv2 {
    private static final int GUI_SIZE = 54;
    
    public enum GUIPage {
        STATISTICS,
        SCOREBOARD
    }
    
    public static void openFor(ServerPlayerEntity player, GUIPage page) {
        SimpleInventory inventory = new SimpleInventory(GUI_SIZE);
        
        switch (page) {
            case STATISTICS:
                setupStatisticsGUI(inventory, player, 0);
                break;
            case SCOREBOARD:
                setupScoreboardGUI(inventory, player, 0);
                break;
        }
        
        player.openHandledScreen(new net.minecraft.screen.SimpleNamedScreenHandlerFactory(
                (syncId, playerInventory, playerEntity) -> new ServerScoreboardScreenHandler(syncId, playerInventory, inventory, player, page),
                Text.literal(page == GUIPage.STATISTICS ? "統計選択" : "スコアボード選択")
        ));
    }
    
    private static void setupStatisticsGUI(SimpleInventory inventory, ServerPlayerEntity player, int page) {
        inventory.clear();
        
        // Slot 0: Scoreboard button (compass icon)
        ItemStack scoreboardButton = new ItemStack(Items.COMPASS);
        scoreboardButton.setCustomName(Text.literal("スコアボードを表示").formatted(Formatting.AQUA));
        inventory.setStack(0, scoreboardButton);
        
        // Slot 4: Reset button
        ItemStack resetButton = new ItemStack(Items.BARRIER);
        resetButton.setCustomName(Text.literal("デフォルトにリセット").formatted(Formatting.YELLOW));
        inventory.setStack(4, resetButton);
        
        // Slot 8: Close button
        ItemStack closeButton = new ItemStack(Items.REDSTONE);
        closeButton.setCustomName(Text.literal("閉じる").formatted(Formatting.RED));
        inventory.setStack(8, closeButton);
        
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
            prevButton.setCustomName(Text.literal("前のページ").formatted(Formatting.AQUA));
            inventory.setStack(18, prevButton);
        }
        
        if ((page + 1) * 27 < totalStats.size()) {
            ItemStack nextButton = new ItemStack(Items.ARROW);
            nextButton.setCustomName(Text.literal("次のページ").formatted(Formatting.AQUA));
            inventory.setStack(26, nextButton);
        }
        
        // Display statistics
        int startIndex = page * 27;
        int endIndex = Math.min(startIndex + 27, totalStats.size());
        
        for (int i = startIndex; i < endIndex; i++) {
            String statObjective = totalStats.get(i);
            ItemStack statItem = new ItemStack(Items.GOLDEN_APPLE);
            String displayName = TotalStatsManager.getTotalDisplayName(statObjective);
            statItem.setCustomName(Text.literal(displayName).formatted(Formatting.GOLD, Formatting.BOLD));
            inventory.setStack(27 + (i - startIndex), statItem);
        }
        
        // Fill empty slots
        for (int i = 27 + (endIndex - startIndex); i < 54; i++) {
            ItemStack glassPane = new ItemStack(Items.GRAY_STAINED_GLASS_PANE);
            glassPane.setCustomName(Text.literal(" "));
            inventory.setStack(i, glassPane);
        }
    }
    
    private static void setupScoreboardGUI(SimpleInventory inventory, ServerPlayerEntity player, int page) {
        inventory.clear();
        
        // Slot 0: Statistics button (book icon)
        ItemStack statsButton = new ItemStack(Items.BOOK);
        statsButton.setCustomName(Text.literal("統計を表示").formatted(Formatting.AQUA));
        inventory.setStack(0, statsButton);
        
        // Slot 4: Reset button
        ItemStack resetButton = new ItemStack(Items.BARRIER);
        resetButton.setCustomName(Text.literal("デフォルトにリセット").formatted(Formatting.YELLOW));
        inventory.setStack(4, resetButton);
        
        // Slot 8: Close button
        ItemStack closeButton = new ItemStack(Items.REDSTONE);
        closeButton.setCustomName(Text.literal("閉じる").formatted(Formatting.RED));
        inventory.setStack(8, closeButton);
        
        // Get objectives list (excluding total stats)
        List<String> objectives = getObjectivesList(player, false);
        
        // Navigation
        if (page > 0) {
            ItemStack prevButton = new ItemStack(Items.ARROW);
            prevButton.setCustomName(Text.literal("前のページ").formatted(Formatting.AQUA));
            inventory.setStack(18, prevButton);
        }
        
        if ((page + 1) * 27 < objectives.size()) {
            ItemStack nextButton = new ItemStack(Items.ARROW);
            nextButton.setCustomName(Text.literal("次のページ").formatted(Formatting.AQUA));
            inventory.setStack(26, nextButton);
        }
        
        // Display objectives
        int startIndex = page * 27;
        int endIndex = Math.min(startIndex + 27, objectives.size());
        
        for (int i = startIndex; i < endIndex; i++) {
            String objective = objectives.get(i);
            ItemStack objectiveItem = new ItemStack(Items.PAPER);
            objectiveItem.setCustomName(Text.literal(objective).formatted(Formatting.WHITE));
            inventory.setStack(27 + (i - startIndex), objectiveItem);
        }
        
        // Fill empty slots
        for (int i = 27 + (endIndex - startIndex); i < 54; i++) {
            ItemStack glassPane = new ItemStack(Items.GRAY_STAINED_GLASS_PANE);
            glassPane.setCustomName(Text.literal(" "));
            inventory.setStack(i, glassPane);
        }
    }
    
    private static List<String> getObjectivesList(ServerPlayerEntity player, boolean includeTotalStats) {
        List<String> list = new ArrayList<>();
        
        player.getServer().getScoreboard().getObjectives().forEach(objective -> {
            String name = objective.getName();
            if (includeTotalStats || !TotalStatsManager.isTotalObjective(name)) {
                list.add(name);
            }
        });
        
        return list;
    }
    
    public static class ServerScoreboardScreenHandler extends GenericContainerScreenHandler {
        private static final int GUI_SIZE = 54;
        private final ServerPlayerEntity player;
        private final SimpleInventory inventory;
        private final GUIPage currentPage;
        private int pageNumber = 0;
        
        public ServerScoreboardScreenHandler(int syncId, PlayerInventory playerInventory, Inventory inventory, ServerPlayerEntity player, GUIPage currentPage) {
            super(ScreenHandlerType.GENERIC_9X6, syncId, playerInventory, inventory, 6);
            this.player = player;
            this.inventory = (SimpleInventory) inventory;
            this.currentPage = currentPage;
        }
        
        @Override
        public boolean canUse(PlayerEntity player) {
            return true;
        }
        
        @Override
        public ItemStack quickMove(PlayerEntity player, int slot) {
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
                case 0:
                    // Switch between statistics and scoreboard
                    if (currentPage == GUIPage.STATISTICS) {
                        player.closeHandledScreen();
                        ServerScoreboardGUIv2.openFor(player, GUIPage.SCOREBOARD);
                    } else {
                        player.closeHandledScreen();
                        ServerScoreboardGUIv2.openFor(player, GUIPage.STATISTICS);
                    }
                    break;
                    
                case 4:
                    // Reset button
                    resetToDefault();
                    break;
                    
                case 8:
                    // Close button
                    player.closeHandledScreen();
                    break;
                    
                case 18:
                    // Previous page
                    if (pageNumber > 0) {
                        pageNumber--;
                        refreshGUI();
                    }
                    break;
                    
                case 26:
                    // Next page
                    List<String> items = currentPage == GUIPage.STATISTICS ? 
                        getAllTotalStats() :
                        getObjectivesList(player, false);
                    if ((pageNumber + 1) * 27 < items.size()) {
                        pageNumber++;
                        refreshGUI();
                    }
                    break;
                    
                default:
                    // Item selection (27-53)
                    if (slotIndex >= 27 && slotIndex <= 53) {
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
            int itemIndex = pageNumber * 27 + (slotIndex - 27);
            
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
                ServerScoreboardManager.setClientDisplayObjective(player.getUuid(), objective);
                player.sendMessage(Text.literal("スコアボードを " + objective + " に設定しました").formatted(Formatting.GREEN));
                player.closeHandledScreen();
            } catch (Exception e) {
                ServerScoreboardLogger.error("Failed to select objective: " + objective, e);
                player.sendMessage(Text.literal("スコアボードの設定に失敗しました").formatted(Formatting.RED));
            }
        }
        
        private void resetToDefault() {
            try {
                ServerScoreboardManager.resetPlayerScoreboard(player.getUuid());
                player.sendMessage(Text.literal("スコアボードをデフォルトにリセットしました").formatted(Formatting.GREEN));
                player.closeHandledScreen();
            } catch (Exception e) {
                ServerScoreboardLogger.error("Failed to reset scoreboard", e);
                player.sendMessage(Text.literal("リセットに失敗しました").formatted(Formatting.RED));
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