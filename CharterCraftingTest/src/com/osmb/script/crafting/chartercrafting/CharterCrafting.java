package com.osmb.script.crafting.chartercrafting;

import com.osmb.api.item.ItemGroupResult;
import com.osmb.api.item.ItemID;
import com.osmb.api.item.ItemSearchResult;
import com.osmb.api.script.Script;
import com.osmb.api.script.ScriptDefinition;
import com.osmb.api.script.SkillCategory;
import com.osmb.script.crafting.chartercrafting.handles.*;
import com.osmb.script.crafting.chartercrafting.javafx.UI;

import java.util.Set;

import static com.osmb.script.crafting.chartercrafting.Config.*;
import static com.osmb.script.crafting.chartercrafting.Constants.ITEM_IDS_TO_RECOGNISE;
import static com.osmb.script.crafting.chartercrafting.State.*;

@ScriptDefinition(
        name = "OSMBs Charter Crafting",
        author = "joe",
        version = 1.0,
        description = "",
        skillCategory = SkillCategory.CRAFTING
)
public class CharterCrafting extends Script {
    private ShopHandler shopHandler;
    private FurnaceHandler furnaceHandler;
    private BankHandler bankHandler;
    private CraftHandler craftHandler;
    private DepositBoxHandler depositBoxHandler;
    private boolean hopping = false;
    private int lastWorld = -1;

    public CharterCrafting(Object scriptCore) {
        super(scriptCore);
    }

    @Override
    public void onStart() {
        lastWorld = getCurrentWorld() != null ? getCurrentWorld() : -1;
        this.shopHandler = new ShopHandler(this);
        this.furnaceHandler = new FurnaceHandler(this);
        this.bankHandler = new BankHandler(this);
        this.craftHandler = new CraftHandler(this);
        this.depositBoxHandler = new DepositBoxHandler(this);
        UI ui = UI.show(this);
        selectedDock = ui.getSelectedDock();
        // workaround as highlights aren't working for charter crew members
        npcs = NPC.getNpcsForDock(selectedDock);
        selectedMethod = ui.getSelectedMethod();
        selectedGlassBlowingItem = ui.getSelectedGlassBlowingItem();
        ITEM_IDS_TO_RECOGNISE.add(selectedGlassBlowingItem.getItemId());
        combinationItemID = selectedMethod == Method.SUPER_GLASS_MAKE ? ItemID.SEAWEED : ItemID.SODA_ASH;
    }

    @Override
    public int poll() {
        Integer world = getCurrentWorld();
        if (hopping && world != null && world != lastWorld) {
            hopping = false;
            lastWorld = world;
        }

        if (hasNoHopProfile()) {
            log(CharterCrafting.class, "No hop profile selected, make sure to select one before running the script.");
            stop();
        }
        decideTask();
        return 0;
    }

    private boolean shouldOpenShop(ItemGroupResult inventorySnapshot) {
        int bucketsOfSand = inventorySnapshot.getAmount(ItemID.BUCKET_OF_SAND);
        int combinationItems = inventorySnapshot.getAmount(combinationItemID);
        return switch (selectedMethod) {
            case BUY_AND_BANK -> inventorySnapshot.getFreeSlots() >= 2;
            case BUY_AND_FURNACE_CRAFT -> inventorySnapshot.getFreeSlots() >= 2 && !smelt
                    || bucketsOfSand == 0
                    || combinationItems == 0;
            case SUPER_GLASS_MAKE -> {
                boolean hasFinished =
                        inventorySnapshot.contains(selectedGlassBlowingItem.getItemId());
                boolean hasMolten =
                        inventorySnapshot.contains(ItemID.MOLTEN_GLASS);
                if (hasFinished || hasMolten) {
                    yield false;
                }
                yield bucketsOfSand < craftAmount
                        || combinationItems < craftAmount;
            }
        };
    }

    private void hopWorlds() {
        if (hopping) {
            return;
        }
        if (hasNoHopProfile()) {
            return;
        }

        hopping = true;
        hopFlag = false;
        getProfileManager().forceHop();
    }


    @Override
    public boolean canHopWorlds() {
        return hopFlag || hopping;
    }

    @Override
    public int[] regionsToPrioritise() {
        log(CharterCrafting.class, "Prioritised region:" + selectedDock.getRegionID());
        return new int[]{selectedDock.getRegionID()};
    }

    private boolean canCraft(ItemGroupResult inventorySnapshot) {
        return inventorySnapshot.contains(ItemID.MOLTEN_GLASS) && (selectedMethod == Method.BUY_AND_FURNACE_CRAFT && !smelt
                || selectedMethod == Method.SUPER_GLASS_MAKE && !inventorySnapshot.containsAll(
                Set.of(ItemID.BUCKET_OF_SAND, combinationItemID)));
    }

    private void decideTask() {
        if (hopFlag) {
            // close interfaces first then hop? Brute force method to get hopping working
            if (getWidgetManager().getBank().isVisible()) {
                getWidgetManager().getBank().close();
                return;
            }
            if (getWidgetManager().getDepositBox().isVisible()) {
                getWidgetManager().getDepositBox().close();
                return;
            }
            if (shopHandler != null && shopHandler.interfaceVisible()) {
                shopHandler.shopInterface.close();
                return;
            }

            hopWorlds();
            return;
        }

        if (depositing) {
            depositBoxHandler.handle();
            return;
        }
        if (hasNoHopProfile()) {
            return;
        }
        if (getWidgetManager().getDepositBox().isVisible()) {
            depositing = true;
            depositBoxHandler.handle();
            return;
        }
        if (getWidgetManager().getBank().isVisible()) {
            bankHandler.handleInterface();
            return;
        }
        if (shopHandler.interfaceVisible()) {
            shopHandler.handleInterface();
            return;
        }
        inventorySnapshot = getWidgetManager()
                .getInventory()
                .search(ITEM_IDS_TO_RECOGNISE);
        if (inventorySnapshot == null) {
            log(CharterCrafting.class, "Unable to snapshot inventory...");
            return;
        }
        if (shouldOpenShop(inventorySnapshot)) {
            if (hopFlag) {
                if (shopHandler.interfaceVisible()) {
                    shopHandler.shopInterface.close();
                    return;
                }

                hopWorlds();
                return;
            } else {
                shopHandler.open();
                return;
            }
        }
        switch (selectedMethod) {
            case BUY_AND_BANK -> depositBoxHandler.handle();
            case BUY_AND_FURNACE_CRAFT -> furnaceHandler.poll();
            case SUPER_GLASS_MAKE -> {
                boolean hasMoltenGlass =
                        inventorySnapshot.contains(ItemID.MOLTEN_GLASS);
                boolean hasFinishedItems =
                        inventorySnapshot.contains(selectedGlassBlowingItem.getItemId());
                if (hasFinishedItems && !hasMoltenGlass) {
                    depositBoxHandler.handle();
                    return;
                }
                if (hasMoltenGlass) {
                    ItemSearchResult pipe =
                            inventorySnapshot.getItem(ItemID.GLASSBLOWING_PIPE);
                    ItemSearchResult molten =
                            inventorySnapshot.getRandomItem(ItemID.MOLTEN_GLASS);
                    if (pipe == null) {
                        log(CharterCrafting.class,
                                "No glassblowing pipe found, stopping script.");
                        stop();
                        return;
                    }
                    craftHandler.craft(pipe, molten);
                    return;
                }
                craftHandler.superGlassMake();
                return;
            }
        }
    }

    private boolean hasNoHopProfile() {
        if (!getProfileManager().hasHopProfile()) {
            log(CharterCrafting.class,
                    "No hop profile set, please make sure to select a hop profile when running this script.");
            stop();
            return true;
        }
        return false;
    }

    public static class DropResult {
        public final int amount;
        public final int itemId;

        public DropResult(int itemId, int amount) {
            this.amount = amount;
            this.itemId = itemId;
        }
    }

    enum Task {
        OPEN_SHOP,
        BUY_ITEMS,
        OPEN_BANK,
        BANK_ITEMS,
        HANDLE_BANK_INTERFACE,
        HANDLE_SHOP_INTERFACE,
        WALK_TO_FURNACE,
        SMELT,
        CRAFT
    }
}





