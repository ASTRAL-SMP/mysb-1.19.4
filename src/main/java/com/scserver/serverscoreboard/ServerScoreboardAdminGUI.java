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
import java.util.Map;
import java.util.Set;

public class ServerScoreboardAdminGUI {
    private static final int GUI_SIZE = 54;
    
    public enum AdminPage {
        STATS,
        OBJECTIVES
    }
    
    public static void openFor(ServerPlayerEntity player, AdminPage page) {
        SimpleInventory inventory = new SimpleInventory(GUI_SIZE);
        
        switch (page) {
            case STATS:
                setupStatsGUI(inventory, player, 0);
                break;
            case OBJECTIVES:
                setupObjectivesGUI(inventory, player, 0);
                break;
        }
        
        player.openHandledScreen(new net.minecraft.screen.SimpleNamedScreenHandlerFactory(
                (syncId, playerInventory, playerEntity) -> new AdminScreenHandler(syncId, playerInventory, inventory, player, page),
                Text.literal(page == AdminPage.STATS ? "統計管理" : "スコアボード管理")
        ));
    }
    
    private static void setupStatsGUI(SimpleInventory inventory, ServerPlayerEntity player, int page) {
        inventory.clear();
        
        // Slot 0: スコアボード管理へ切り替え
        ItemStack switchButton = new ItemStack(Items.COMPASS);
        switchButton.setCustomName(Text.literal("スコアボード管理へ").formatted(Formatting.AQUA));
        inventory.setStack(0, switchButton);
        
        // Slot 8: Close button
        ItemStack closeButton = new ItemStack(Items.REDSTONE);
        closeButton.setCustomName(Text.literal("閉じる").formatted(Formatting.RED));
        inventory.setStack(8, closeButton);
        
        // Get all available stats
        Map<String, String> allStats = TotalStatsManager.getAllAvailableStats();
        Set<String> enabledStats = TotalStatsManager.getEnabledStats();
        List<Map.Entry<String, String>> statsList = new ArrayList<>(allStats.entrySet());
        
        // Navigation
        if (page > 0) {
            ItemStack prevButton = new ItemStack(Items.ARROW);
            prevButton.setCustomName(Text.literal("前のページ").formatted(Formatting.AQUA));
            inventory.setStack(18, prevButton);
        }
        
        if ((page + 1) * 27 < statsList.size()) {
            ItemStack nextButton = new ItemStack(Items.ARROW);
            nextButton.setCustomName(Text.literal("次のページ").formatted(Formatting.AQUA));
            inventory.setStack(26, nextButton);
        }
        
        // Display stats
        int startIndex = page * 27;
        int endIndex = Math.min(startIndex + 27, statsList.size());
        
        for (int i = startIndex; i < endIndex; i++) {
            Map.Entry<String, String> stat = statsList.get(i);
            String statId = stat.getKey();
            String displayName = stat.getValue();
            boolean isEnabled = enabledStats.contains(statId);
            
            ItemStack statItem = new ItemStack(isEnabled ? Items.GOLDEN_APPLE : Items.APPLE);
            statItem.setCustomName(Text.literal(displayName)
                    .formatted(isEnabled ? Formatting.GOLD : Formatting.GRAY));
            List<Text> lore = new ArrayList<>();
            lore.add(Text.literal("ID: " + statId).formatted(Formatting.DARK_GRAY));
            lore.add(Text.literal(isEnabled ? "有効" : "無効")
                    .formatted(isEnabled ? Formatting.GREEN : Formatting.RED));
            lore.add(Text.literal("クリックで切り替え").formatted(Formatting.GRAY));
            statItem.setNbt(statItem.getOrCreateNbt());
            
            inventory.setStack(27 + (i - startIndex), statItem);
        }
        
        // Fill empty slots
        for (int i = 0; i < 54; i++) {
            if (inventory.getStack(i).isEmpty()) {
                ItemStack glassPane = new ItemStack(Items.GRAY_STAINED_GLASS_PANE);
                glassPane.setCustomName(Text.literal(" "));
                inventory.setStack(i, glassPane);
            }
        }
    }
    
    private static void setupObjectivesGUI(SimpleInventory inventory, ServerPlayerEntity player, int page) {
        inventory.clear();
        
        // Slot 0: 統計管理へ切り替え
        ItemStack switchButton = new ItemStack(Items.BOOK);
        switchButton.setCustomName(Text.literal("統計管理へ").formatted(Formatting.AQUA));
        inventory.setStack(0, switchButton);
        
        // Slot 8: Close button
        ItemStack closeButton = new ItemStack(Items.REDSTONE);
        closeButton.setCustomName(Text.literal("閉じる").formatted(Formatting.RED));
        inventory.setStack(8, closeButton);
        
        // Get objectives list
        List<String> objectives = getObjectivesList(player);
        
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
        for (int i = 0; i < 54; i++) {
            if (inventory.getStack(i).isEmpty()) {
                ItemStack glassPane = new ItemStack(Items.GRAY_STAINED_GLASS_PANE);
                glassPane.setCustomName(Text.literal(" "));
                inventory.setStack(i, glassPane);
            }
        }
    }
    
    private static List<String> getObjectivesList(ServerPlayerEntity player) {
        List<String> list = new ArrayList<>();
        
        player.getServer().getScoreboard().getObjectives().forEach(objective -> {
            list.add(objective.getName());
        });
        
        return list;
    }
    
    public static class AdminScreenHandler extends GenericContainerScreenHandler {
        private final ServerPlayerEntity player;
        private final SimpleInventory inventory;
        private final AdminPage currentPage;
        private int pageNumber = 0;
        
        public AdminScreenHandler(int syncId, PlayerInventory playerInventory, Inventory inventory, ServerPlayerEntity player, AdminPage currentPage) {
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
                    // Switch between pages
                    if (currentPage == AdminPage.STATS) {
                        player.closeHandledScreen();
                        ServerScoreboardAdminGUI.openFor(player, AdminPage.OBJECTIVES);
                    } else {
                        player.closeHandledScreen();
                        ServerScoreboardAdminGUI.openFor(player, AdminPage.STATS);
                    }
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
                    int itemCount = 0;
                    if (currentPage == AdminPage.STATS) {
                        itemCount = TotalStatsManager.getAllAvailableStats().size();
                    } else {
                        itemCount = getObjectivesList(player).size();
                    }
                    
                    if ((pageNumber + 1) * 27 < itemCount) {
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
        
        private void handleItemSelection(int slotIndex) {
            int itemIndex = pageNumber * 27 + (slotIndex - 27);
            
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
                        player.sendMessage(Text.literal("統計「" + statId + "」を無効化しました")
                                .formatted(Formatting.RED));
                    } else {
                        // 現在無効なので有効化
                        TotalStatsManager.enableStat(statId);
                        player.sendMessage(Text.literal("統計「" + statId + "」を有効化しました")
                                .formatted(Formatting.GREEN));
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
                    player.sendMessage(Text.literal("スコアボード: " + objective).formatted(Formatting.AQUA));
                }
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