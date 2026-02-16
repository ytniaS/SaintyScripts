package com.osmb.script.chickenkiller.task;

import com.osmb.api.item.ItemGroupResult;
import com.osmb.api.item.ItemID;
import com.osmb.api.item.ItemSearchResult;
import com.osmb.api.utils.RandomUtils;
import com.osmb.script.chickenkiller.ChickenScript;

import java.util.Set;

public class DropItemsTask extends Task {
    private static final Set<Integer> UNWANTED_ITEMS = Set.of(ItemID.BONES, ItemID.RAW_CHICKEN);
    private static final int DROP_ACTION_TIMEOUT_MS = 2000;

    private final ChickenScript script;

    public DropItemsTask(ChickenScript script) {
        super(script);
        this.script = script;
    }

    @Override
    public boolean canExecute() {
        ItemGroupResult inventory = script.getWidgetManager().getInventory().search(UNWANTED_ITEMS);
        return inventory != null && inventory.containsAny(UNWANTED_ITEMS);
    }

    @Override
    public boolean execute() {
        script.log(getClass(), "Dropping unwanted items");

        ItemGroupResult inventory = script.getWidgetManager().getInventory().search(UNWANTED_ITEMS);
        if (inventory == null) return true;

        boolean droppedAnyItem = false;

        for (int itemId : UNWANTED_ITEMS) {
            ItemSearchResult itemToDrop = inventory.getItem(itemId);
            while (itemToDrop != null) {
                if (script.stopped()) return false;

                itemToDrop.interact("Drop");
                droppedAnyItem = true;

                boolean itemDisappeared = waitForItemToDisappearFromInventory(itemId, DROP_ACTION_TIMEOUT_MS);
                if (!itemDisappeared) {
                    script.log(getClass(), "Failed to drop item, will retry");
                    break;
                }

                inventory = script.getWidgetManager().getInventory().search(UNWANTED_ITEMS);
                if (inventory == null) break;
                itemToDrop = inventory.getItem(itemId);
            }
        }

        return droppedAnyItem;
    }

    private boolean waitForItemToDisappearFromInventory(int itemId, int timeoutMs) {
        int countBeforeDrop = getInventoryCountOf(itemId);
        long dropStartTime = System.currentTimeMillis();

        while (System.currentTimeMillis() - dropStartTime < timeoutMs) {
            if (script.stopped()) return false;

            int currentCount = getInventoryCountOf(itemId);
            if (currentCount < countBeforeDrop) {
                return true;
            }

            script.pollFramesHuman(() -> true, RandomUtils.gaussianRandom(50, 100, 75, 15));
        }

        return false;
    }

    private int getInventoryCountOf(int itemId) {
        ItemGroupResult inventory = script.getWidgetManager().getInventory().search(Set.of(itemId));
        return inventory != null ? inventory.getAmount(itemId) : 0;
    }
}
