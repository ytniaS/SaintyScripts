package com.osmb.script.chickenkiller.task;

import com.osmb.api.item.ItemGroupResult;
import com.osmb.api.item.ItemID;
import com.osmb.api.location.area.impl.RectangleArea;
import com.osmb.api.location.position.types.WorldPosition;
import com.osmb.api.shape.Polygon;
import com.osmb.api.ui.overlay.HealthOverlay;
import com.osmb.api.utils.RandomUtils;
import com.osmb.api.walker.pathing.CollisionManager;
import com.osmb.script.chickenkiller.ChickenScript;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Random;
import java.util.Set;

public class KillChickenTask extends Task {
    private static final int ATTACK_COOLDOWN_MS = 3000;
    private static final long WORLD_HOP_COOLDOWN_MS = 30_000;
    private static final int CONSECUTIVE_EMPTY_SPAWN_CYCLES_BEFORE_WORLD_HOP = 5;
    private static final RectangleArea CHICKEN_COOP_AREA = new RectangleArea(3220, 3287, 16, 14, 0);

    private final ChickenScript script;
    private long lastAttackTimeMs = 0;
    private long lastWorldHopTimeMs = 0;
    private int consecutiveEmptySpawnCycles = 0;
    private final Random random;

    public KillChickenTask(ChickenScript script) {
        super(script);
        this.script = script;
        this.random = new Random();
    }

    @Override
    public boolean canExecute() {
        long currentTimeMs = System.currentTimeMillis();
        return currentTimeMs - lastAttackTimeMs >= ATTACK_COOLDOWN_MS;
    }

    @Override
    public boolean execute() {
        if (!waitForPlayerToStopMoving(1000, 3000)) {
            script.log(getClass(), "Failed to stop moving");
            return false;
        }

        HealthOverlay healthOverlay = new HealthOverlay(script);

        if (!isFighting(healthOverlay)) {
            boolean attacked = attackChicken(healthOverlay);
            if (!attacked) {
                return false;
            }
        }

        boolean overlayVisible = waitForConditionWithTimeout(() -> healthOverlay.isVisible(), 800, 2000);
        if (!overlayVisible) {
            script.log(getClass(), "Health overlay didn't appear - might have misclicked");
            return false;
        }

        boolean killed = waitForChickenToDie(healthOverlay, 16000);

        if (!killed) {
            script.log(getClass(), "Failed to kill chicken");
            return false;
        }

        script.incrementKills();
        script.log(getClass(), "Chicken killed");
        return true;
    }

    private boolean attackChicken(HealthOverlay healthOverlay) {
        WorldPosition playerPosition = script.getWorldPosition();
        if (playerPosition == null) return false;

        List<WorldPosition> otherPlayerPositions = script.getWidgetManager().getMinimap().getPlayerPositions().asList();
        List<WorldPosition> chickenPositions = script.getWidgetManager().getMinimap().getNPCPositions().asList();

        if (chickenPositions == null || chickenPositions.isEmpty()) {
            consecutiveEmptySpawnCycles++;
            if (worldHopCooldownHasElapsed()) {
                script.log(getClass(), "No chickens found - hopping worlds");
                script.getProfileManager().forceHop();
                lastWorldHopTimeMs = System.currentTimeMillis();
                consecutiveEmptySpawnCycles = 0;
            }
            return false;
        }

        List<WorldPosition> unoccupiedChickens = new ArrayList<>();

        for (WorldPosition chickenPosition : chickenPositions) {
            if (!CHICKEN_COOP_AREA.contains(chickenPosition)) continue;

            if (healthOverlay.isVisible() && CollisionManager.isCardinallyAdjacent(chickenPosition, playerPosition)) {
                continue;
            }

            boolean chickenIsBeingAttackedByAnotherPlayer = false;
            if (otherPlayerPositions != null) {
                for (WorldPosition otherPlayer : otherPlayerPositions) {
                    if (CollisionManager.isCardinallyAdjacent(chickenPosition, otherPlayer)) {
                        chickenIsBeingAttackedByAnotherPlayer = true;
                        break;
                    }
                }
            }

            if (!chickenIsBeingAttackedByAnotherPlayer) {
                unoccupiedChickens.add(chickenPosition);
            }
        }

        if (unoccupiedChickens.isEmpty()) {
            consecutiveEmptySpawnCycles++;
            if (worldHopCooldownHasElapsed()) {
                script.log(getClass(), "All chickens occupied - hopping worlds");
                script.getProfileManager().forceHop();
                lastWorldHopTimeMs = System.currentTimeMillis();
                consecutiveEmptySpawnCycles = 0;
            }
            return false;
        }

        consecutiveEmptySpawnCycles = 0;

        int maxCandidates = Math.min(6, unoccupiedChickens.size());
        int minCandidates = Math.min(3, maxCandidates);
        int candidateCount = maxCandidates > 1 ? random.nextInt(minCandidates, maxCandidates + 1) : 1;

        unoccupiedChickens = unoccupiedChickens.stream()
                .sorted(Comparator.comparingDouble(chicken -> chicken.distanceTo(playerPosition)))
                .limit(candidateCount)
                .toList();

        if (unoccupiedChickens.isEmpty()) {
            script.log(getClass(), "No unoccupied chickens available");
            return false;
        }

        WorldPosition attackedChicken = null;

        for (WorldPosition chickenPosition : unoccupiedChickens) {
            Polygon chickenTilePolygon;
            try {
                chickenTilePolygon = script.getSceneProjector().getTileCube(chickenPosition, 50).getResized(0.7);
            } catch (NullPointerException e) {
                continue;
            }

            boolean chickenStillPresent = false;
            for (WorldPosition currentNpcPosition : script.getWidgetManager().getMinimap().getNPCPositions()) {
                if (currentNpcPosition.equals(chickenPosition)) {
                    chickenStillPresent = true;
                    break;
                }
            }

            if (!chickenStillPresent) continue;

            if (script.getFinger().tapGameScreen(chickenTilePolygon, menuEntries -> menuEntries.stream()
                    .filter(entry -> entry.getRawText().toLowerCase().contains("attack"))
                    .filter(entry -> entry.getRawText().toLowerCase().contains("chicken"))
                    .findFirst()
                    .orElse(null))) {
                attackedChicken = chickenPosition;
                lastAttackTimeMs = System.currentTimeMillis();
                break;
            }
        }

        if (attackedChicken == null) {
            script.log(getClass(), "Failed to attack chicken");
            return false;
        }

        if (!waitToReachChicken(attackedChicken, (int) playerPosition.distanceTo(attackedChicken) * 1000)) {
            script.log(getClass(), "Failed to reach chicken");
            return false;
        }

        return true;
    }

    private boolean isFighting(HealthOverlay healthOverlay) {
        if (healthOverlay == null || !healthOverlay.isVisible()) return false;
        Integer hitpoints = getHealthOverlayHitpoints(healthOverlay);
        return hitpoints != null && hitpoints > 0;
    }

    private Integer getHealthOverlayHitpoints(HealthOverlay healthOverlay) {
        HealthOverlay.HealthResult healthResult = (HealthOverlay.HealthResult) healthOverlay.getValue(HealthOverlay.HEALTH);
        if (healthResult == null) return null;
        return healthResult.getCurrentHitpoints();
    }

    private boolean waitForChickenToDie(HealthOverlay healthOverlay, int maxWaitTimeMs) {
        long fightStartTime = System.currentTimeMillis();
        long lastFeatherCheckTime = 0;

        while (System.currentTimeMillis() - fightStartTime < maxWaitTimeMs) {
            if (script.stopped()) return false;

            if (!healthOverlay.isVisible()) {
                return true;
            }

            Integer hitpoints = getHealthOverlayHitpoints(healthOverlay);
            if (hitpoints == null || hitpoints == 0) {
                return true;
            }

            // Check for feathers every 2 seconds during combat
            if (System.currentTimeMillis() - lastFeatherCheckTime > 2000) {
                lastFeatherCheckTime = System.currentTimeMillis();
                lootNearbyFeathers();
            }

            script.pollFramesHuman(() -> true, RandomUtils.gaussianRandom(100, 200, 150, 30));
        }

        return false;
    }

    private void lootNearbyFeathers() {
        WorldPosition playerPosition = script.getWorldPosition();
        if (playerPosition == null) return;

        ItemGroupResult inventory = script.getWidgetManager().getInventory().search(Set.of());
        if (inventory == null || inventory.getFreeSlots() == 0) return;

        List<WorldPosition> groundItems = script.getWidgetManager().getMinimap().getItemPositions().asList();
        if (groundItems == null || groundItems.isEmpty()) return;

        WorldPosition closestFeather = null;
        double closestDistance = Double.MAX_VALUE;

        for (WorldPosition itemPos : groundItems) {
            if (!CHICKEN_COOP_AREA.contains(itemPos)) continue;
            double distance = playerPosition.distanceTo(itemPos);
            if (distance <= 3 && distance < closestDistance) {
                closestDistance = distance;
                closestFeather = itemPos;
            }
        }

        if (closestFeather == null) return;

        Polygon tilePoly = script.getSceneProjector().getTileCube(closestFeather, 10);
        if (tilePoly == null) return;

        tilePoly = tilePoly.getResized(0.6);

        ItemGroupResult invBefore = script.getWidgetManager().getInventory().search(Set.of(ItemID.FEATHER));
        int countBefore = invBefore != null ? invBefore.getAmount(ItemID.FEATHER) : 0;

        boolean clicked = script.getFinger().tapGameScreen(tilePoly, menuEntries -> menuEntries.stream()
                .filter(entry -> entry.getRawText().toLowerCase().contains("take"))
                .filter(entry -> entry.getRawText().toLowerCase().contains("feather"))
                .findFirst()
                .orElse(null));

        if (clicked) {
            // Quick wait for pickup
            long pickupStart = System.currentTimeMillis();
            while (System.currentTimeMillis() - pickupStart < 1500) {
                ItemGroupResult invAfter = script.getWidgetManager().getInventory().search(Set.of(ItemID.FEATHER));
                int countAfter = invAfter != null ? invAfter.getAmount(ItemID.FEATHER) : 0;
                if (countAfter > countBefore) {
                    script.incrementFeathers(countAfter - countBefore);
                    script.log(getClass(), "Looted feathers during combat");
                    break;
                }
                script.pollFramesHuman(() -> true, 50);
            }
        }
    }

    private boolean waitForPlayerToStopMoving(int minStationaryTimeMs, int maxWaitTimeMs) {
        WorldPosition lastPlayerPosition = script.getWorldPosition();
        if (lastPlayerPosition == null) return false;

        long stationarityCheckStartTime = System.currentTimeMillis();

        while (System.currentTimeMillis() - stationarityCheckStartTime < maxWaitTimeMs) {
            if (script.stopped()) return false;

            script.pollFramesHuman(() -> true, RandomUtils.gaussianRandom(100, 200, 150, 30));

            WorldPosition currentPlayerPosition = script.getWorldPosition();
            if (currentPlayerPosition == null) continue;

            if (currentPlayerPosition.equals(lastPlayerPosition) && System.currentTimeMillis() - stationarityCheckStartTime >= minStationaryTimeMs) {
                return true;
            }

            if (!currentPlayerPosition.equals(lastPlayerPosition)) {
                lastPlayerPosition = currentPlayerPosition;
                stationarityCheckStartTime = System.currentTimeMillis();
            }
        }

        return false;
    }

    private boolean waitToReachChicken(WorldPosition chickenPosition, int maxWaitTimeMs) {
        long approachStartTime = System.currentTimeMillis();

        while (System.currentTimeMillis() - approachStartTime < maxWaitTimeMs) {
            if (script.stopped()) return false;

            script.pollFramesHuman(() -> true, RandomUtils.gaussianRandom(100, 200, 150, 30));

            WorldPosition currentPlayerPosition = script.getWorldPosition();
            if (currentPlayerPosition == null) continue;

            if (CollisionManager.isCardinallyAdjacent(currentPlayerPosition, chickenPosition)) {
                return true;
            }
        }

        return false;
    }

    private boolean waitForConditionWithTimeout(java.util.function.Supplier<Boolean> condition, int minDelayMs, int maxWaitTimeMs) {
        long waitStartTime = System.currentTimeMillis();

        while (System.currentTimeMillis() - waitStartTime < maxWaitTimeMs) {
            if (script.stopped()) return false;

            script.pollFramesHuman(() -> true, RandomUtils.gaussianRandom(50, 100, 75, 15));

            if (condition.get() && System.currentTimeMillis() - waitStartTime >= minDelayMs) {
                return true;
            }
        }

        return false;
    }

    private boolean worldHopCooldownHasElapsed() {
        return consecutiveEmptySpawnCycles >= CONSECUTIVE_EMPTY_SPAWN_CYCLES_BEFORE_WORLD_HOP
                && System.currentTimeMillis() - lastWorldHopTimeMs > WORLD_HOP_COOLDOWN_MS;
    }
}
