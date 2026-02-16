package com.osmb.script.chickenkiller.task;

import com.osmb.api.item.ItemGroupResult;
import com.osmb.api.item.ItemID;
import com.osmb.api.location.area.impl.RectangleArea;
import com.osmb.api.location.position.types.WorldPosition;
import com.osmb.api.shape.Polygon;
import com.osmb.api.utils.RandomUtils;
import com.osmb.script.chickenkiller.ChickenScript;

import java.util.List;
import java.util.Set;

public class LootFeathersTask extends Task {
    private static final Set<Integer> FEATHER_ITEM = Set.of(ItemID.FEATHER);
    private static final int FEATHER_PICKUP_RANGE_TILES = 3;
    private static final RectangleArea CHICKEN_COOP_AREA = new RectangleArea(3220, 3287, 16, 14, 0);

    private final ChickenScript script;

    public LootFeathersTask(ChickenScript script) {
        super(script);
        this.script = script;
    }

    @Override
    public boolean canExecute() {
        ItemGroupResult inventory = script.getWidgetManager().getInventory().search(Set.of());
        if (inventory == null || inventory.getFreeSlots() == 0) return false;

        WorldPosition playerPosition = script.getWorldPosition();
        if (playerPosition == null) return false;

        List<WorldPosition> groundItemPositions = script.getWidgetManager().getMinimap().getItemPositions().asList();
        if (groundItemPositions == null || groundItemPositions.isEmpty()) return false;

        for (WorldPosition itemPosition : groundItemPositions) {
            if (!CHICKEN_COOP_AREA.contains(itemPosition)) continue;
            if (playerPosition.distanceTo(itemPosition) <= FEATHER_PICKUP_RANGE_TILES) {
                return true;
            }
        }

        return false;
    }

    @Override
    public boolean execute() {
        WorldPosition playerPosition = script.getWorldPosition();
        if (playerPosition == null) return false;

        ItemGroupResult inventoryBefore = script.getWidgetManager().getInventory().search(FEATHER_ITEM);
        int featherCountBefore = inventoryBefore != null ? inventoryBefore.getAmount(ItemID.FEATHER) : 0;

        List<WorldPosition> groundItemPositions = script.getWidgetManager().getMinimap().getItemPositions().asList();
        if (groundItemPositions == null || groundItemPositions.isEmpty()) return false;

        WorldPosition closestFeather = null;
        double closestFeatherDistance = Double.MAX_VALUE;

        for (WorldPosition itemPosition : groundItemPositions) {
            if (!CHICKEN_COOP_AREA.contains(itemPosition)) continue;
            double distance = playerPosition.distanceTo(itemPosition);
            if (distance <= FEATHER_PICKUP_RANGE_TILES && distance < closestFeatherDistance) {
                closestFeatherDistance = distance;
                closestFeather = itemPosition;
            }
        }

        if (closestFeather == null) return false;

        Polygon tilePolygon = script.getSceneProjector().getTileCube(closestFeather, 10);
        if (tilePolygon == null) return false;

        tilePolygon = tilePolygon.getResized(0.6);
        boolean clickedTakeFeather = script.getFinger().tapGameScreen(tilePolygon, menuEntries -> menuEntries.stream()
                .filter(entry -> entry.getRawText().toLowerCase().contains("take"))
                .filter(entry -> entry.getRawText().toLowerCase().contains("feather"))
                .findFirst()
                .orElse(null));

        if (!clickedTakeFeather) {
            return false;
        }

        boolean featherWasPickedUp = waitForFeatherToAppearInInventory(featherCountBefore, 3000);

        if (featherWasPickedUp) {
            ItemGroupResult inventoryAfter = script.getWidgetManager().getInventory().search(FEATHER_ITEM);
            int featherCountAfter = inventoryAfter != null ? inventoryAfter.getAmount(ItemID.FEATHER) : 0;
            int feathersCollected = featherCountAfter - featherCountBefore;
            if (feathersCollected > 0) {
                script.incrementFeathers(feathersCollected);
                script.log(getClass(), "Looted " + feathersCollected + " feathers");
                return true;
            }
        }

        return false;
    }

    private boolean waitForFeatherToAppearInInventory(int previousFeatherCount, int timeoutMs) {
        long pickupStartTime = System.currentTimeMillis();
        int inventoryCheckIntervalMs = 50;

        while (System.currentTimeMillis() - pickupStartTime < timeoutMs) {
            if (script.stopped()) return false;

            script.pollFramesHuman(() -> true, inventoryCheckIntervalMs);

            ItemGroupResult inventory = script.getWidgetManager().getInventory().search(Set.of(ItemID.FEATHER));
            int currentFeatherCount = inventory != null ? inventory.getAmount(ItemID.FEATHER) : 0;

            if (currentFeatherCount > previousFeatherCount) {
                return true;
            }
        }
        return false;
    }
}
