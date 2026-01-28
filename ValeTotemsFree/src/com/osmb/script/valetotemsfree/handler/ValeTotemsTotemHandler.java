package com.osmb.script.valetotemsfree.handler;

import com.osmb.api.input.MenuEntry;
import com.osmb.api.input.MenuHook;
import com.osmb.api.item.ItemGroupResult;
import com.osmb.api.location.area.Area;
import com.osmb.api.location.position.Position;
import com.osmb.api.location.position.types.WorldPosition;
import com.osmb.api.scene.RSObject;
import com.osmb.api.shape.Polygon;
import com.osmb.api.shape.Shape;
import com.osmb.api.ui.chatbox.dialogue.DialogueType;
import com.osmb.api.utils.RandomUtils;
import com.osmb.api.utils.UIResultList;
import com.osmb.api.walker.WalkConfig;
import com.osmb.script.valetotemsfree.util.AreaDefinitions;

import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

public class ValeTotemsTotemHandler {
    private static final int OFFERING_ID = 31054;
    private static final int OFFERING_SITE_ID = 56056;
    private static final int CHATBOX_LINES_TO_CHECK = 3;
    private static final int MIN_PRODUCT_COUNT = 4;
    private static final int MIN_PREMADE_COUNT = 4;
    private static final int MIN_LOG_COUNT = 1;

    // Distance thresholds
    private static final double TOTEM_INTERACTION_DISTANCE = 9;
    private static final double TOTEM_INTERACTION_DISTANCE_EXTENDED = 11;
    private static final double TOTEM_POSITION_CHECK_DISTANCE = 2;
    private static final double POLYGON_RESIZE_FACTOR = 0.8;
    private static final int TILE_RANDOMISATION_RADIUS_SMALL = 1;

    private final ValeTotemsContext context;
    private final ValeTotemsSpiritDetector spiritDetector;
    private TotemAction currentAction = TotemAction.NONE;
    private boolean ready = false;
    private Runnable goToClosestBankCallback;
    private Runnable onTotemCompletedCallback;
    private Consumer<Area> onOfferingsCollectedCallback;

    public enum TotemAction {
        NONE, BUILD, CARVE, DECORATE
    }

    public ValeTotemsTotemHandler(ValeTotemsContext context, ValeTotemsSpiritDetector spiritDetector) {
        this.context = context;
        this.spiritDetector = spiritDetector;
    }

    public void setGoToClosestBankCallback(Runnable callback) {
        this.goToClosestBankCallback = callback;
    }

    public void setOnTotemCompletedCallback(Runnable callback) {
        this.onTotemCompletedCallback = callback;
    }

    public void setOnOfferingsCollectedCallback(Consumer<Area> callback) {
        this.onOfferingsCollectedCallback = callback;
    }

    public TotemAction getCurrentAction() {
        return currentAction;
    }

    public void setCurrentAction(TotemAction action) {
        this.currentAction = action;
    }

    public boolean isReady() {
        return ready;
    }

    public void setReady(boolean ready) {
        this.ready = ready;
    }

    public boolean handleTotemArea(Area area) {
        if (currentAction == TotemAction.DECORATE) {
            return handleDecorationCompletion(area);
        }

        RSObject totemObject = findTotemObject(area);

        if (!walkToTotemAreaIfNeeded(area, totemObject)) {
            return false;
        }

        // Check for "no logs" message in dialogue
        if (checkForNoLogsMessage()) {
            return handleNoLogsDetected("Detected 'no logs' message");
        }

        if (!ready) {
            if (!checkAndInitializeResources(area)) {
                return false;
            }
            // Clear spirits cache when first entering a new totem area
            spiritDetector.clearDetectedSpirits();
            // Once in area and ready, scan for spirits
            spiritDetector.scanForSpirits();
        }

        if (context.getScript().getWidgetManager().getDialogue().isVisible()) {
            // Check for "no logs" message before handling spirit dialogue
            if (checkForNoLogsMessage()) {
                return handleNoLogsDetected("Detected 'no logs' message in dialogue");
            }
            spiritDetector.handleSpiritDialogue();
            currentAction = TotemAction.NONE;
            return false;
        }

        if (handleBuildOrCarveAction()) {
            return false;
        }

        if (totemObject != null) {
            spiritDetector.scanForSpirits();
        }

        if (totemObject != null) {
            return handleTotemInteraction(totemObject);
        }

        return false;
    }

    public boolean handleDecorationCompletion(Area area) {
        boolean decorationComplete = context.getScript().pollFramesUntil(() -> {
            UIResultList chatboxText = context.getScript().getWidgetManager().getChatbox().getText();
            if (chatboxText == null) {
                return false;
            }
            List<String> chatLines = chatboxText.asList();
            if (chatLines == null || chatLines.isEmpty()) {
                return false;
            }
            int linesToCheck = Math.min(CHATBOX_LINES_TO_CHECK, chatLines.size());
            for (int i = 0; i < linesToCheck; i++) {
                String line = chatLines.get(i);
                if (line != null && line.contains("You add the final decoration")) {
                    return true;
                }
            }
            return false;
        }, getDecorationTimeout(), false, false);

        if (decorationComplete) {
            if (onTotemCompletedCallback != null) {
                onTotemCompletedCallback.run();
            }

            context.getScript().pollFramesHuman(() -> true, RandomUtils.gaussianRandom(100, 600, 150, 150), false);

            // Claim offerings if callback is set (controller will handle the logic)
            if (onOfferingsCollectedCallback != null) {
                onOfferingsCollectedCallback.accept(area);
            }
        }

        currentAction = TotemAction.NONE;
        ready = false;
        spiritDetector.clearDetectedSpirits();
        return true;
    }

    public boolean walkToObject(WorldPosition targetPosition, String objectName, String action, Area targetArea) {
        RSObject targetObject = context.getScript().getObjectManager().getRSObject(rSObject ->
                rSObject.getName() != null &&
                        rSObject.getName().equalsIgnoreCase(objectName) &&
                        rSObject.getWorldPosition().distanceTo((Position) targetPosition) == 0.0);

        // For log balance, check if we've successfully crossed to the other side (ends at 1453, 3329)
        if (objectName.equalsIgnoreCase("Log balance") && targetArea.equals(AreaDefinitions.LOG_BALANCE_AREA)) {
            WorldPosition currentPos = context.getScript().getWorldPosition();
            WorldPosition logBalanceEnd = new WorldPosition(1453, 3329, 0);
            // If we're at or near the end position, we've crossed
            if (currentPos.distanceTo((Position) logBalanceEnd) < 3.0 || AreaDefinitions.TOTEM_AREA_2.contains(currentPos)) {
                return true;
            }
        } else {
            if (targetArea.contains(context.getScript().getWorldPosition())) {
                if (targetObject != null && isInteractable(targetObject)) {
                } else {
                    return true;
                }
            }
        }

        if (targetObject != null && (isInteractable(targetObject) || targetObject.distance(context.getScript().getWorldPosition()) < TOTEM_INTERACTION_DISTANCE_EXTENDED)) {
            if (targetObject.interact(new String[]{action})) {
                boolean success = context.getScript().pollFramesUntil(() -> {
                    // For log balance, check if we're at the end position (1453, 3329)
                    if (objectName.equalsIgnoreCase("Log balance") && targetArea.equals(AreaDefinitions.LOG_BALANCE_AREA)) {
                        WorldPosition pos = context.getScript().getWorldPosition();
                        WorldPosition logBalanceEnd = new WorldPosition(1453, 3329, 0);
                        return pos.distanceTo((Position) logBalanceEnd) < 3.0 || AreaDefinitions.TOTEM_AREA_2.contains(pos);
                    }
                    RSObject objAfter = context.getScript().getObjectManager().getRSObject(rSObject ->
                            rSObject.getName() != null &&
                                    rSObject.getName().equalsIgnoreCase(objectName) &&
                                    rSObject.getWorldPosition().distanceTo((Position) targetPosition) == 0.0);
                    return targetArea.contains(context.getScript().getWorldPosition()) && (objAfter == null || !isInteractable(objAfter));
                }, getWalkTimeout(), false, false);
                if (success) {
                    context.getScript().pollFramesHuman(() -> true, RandomUtils.gaussianRandom(400, 1200, 250, 250), false);
                    return true;
                }
            }
        } else {
            WalkConfig.Builder builder = new WalkConfig.Builder().tileRandomisationRadius(TILE_RANDOMISATION_RADIUS_SMALL);
            builder.breakCondition(() -> {
                RSObject obj = context.getScript().getObjectManager().getRSObject(rSObject ->
                        rSObject.getName() != null &&
                                rSObject.getName().equalsIgnoreCase(objectName) &&
                                rSObject.getWorldPosition().distanceTo((Position) targetPosition) == 0.0);
                return isInteractable(obj);
            });
            context.getScript().getWalker().walkTo((Position) targetPosition, builder.build());
        }
        return false;
    }

    private RSObject findTotemObject(Area area) {
        return context.getScript().getObjectManager().getRSObject(rSObject ->
                rSObject.getName() != null &&
                        rSObject.getName().toLowerCase().contains("totem") &&
                        area.contains(rSObject.getWorldPosition()));
    }

    private boolean walkToTotemAreaIfNeeded(Area area, RSObject totemObject) {
        boolean canInteractWithTotem = totemObject != null &&
                isInteractable(totemObject) &&
                totemObject.distance(context.getScript().getWorldPosition()) < TOTEM_INTERACTION_DISTANCE;

        if (area.contains(context.getScript().getWorldPosition()) || canInteractWithTotem) {
            return true;
        }

        ready = false;
        spiritDetector.clearDetectedSpirits();

        // Avoid problematic area by using a waypoint if path would go through it
        WorldPosition currentPos = context.getScript().getWorldPosition();
        Position targetPosition;
        if (totemObject != null) {
            targetPosition = (Position) totemObject.getWorldPosition();
        } else {
            targetPosition = (Position) area.getRandomPosition();
        }

        // If we're near or in the problematic area, use a waypoint to avoid it
        if (currentPos != null && AreaDefinitions.PROBLEMATIC_WALK_AREA.contains(currentPos)) {
            // Walk to a safe position first (north of problematic area)
            WorldPosition waypoint = new WorldPosition(1365, 3360, 0);
            if (!waypoint.equals(currentPos)) {
                context.getScript().getWalker().walkTo((Position) waypoint);
            }
        }

        WalkConfig.Builder builder = new WalkConfig.Builder().tileRandomisationRadius(TILE_RANDOMISATION_RADIUS_SMALL);
        builder.breakCondition(() -> {
            WorldPosition currentPos2 = context.getScript().getWorldPosition();
            if (currentPos2 == null) {
                return false;
            }
            if (area.contains(currentPos2)) {
                return true;
            }
            // Try to find totem object again in case it wasn't found initially
            RSObject foundTotem = findTotemObject(area);
            return foundTotem != null &&
                    isInteractable(foundTotem) &&
                    foundTotem.distance(currentPos2) < TOTEM_INTERACTION_DISTANCE;
        });

        if (context.getScript().getLastPositionChangeMillis() > 1500L) {
            context.getScript().getWalker().walkTo(targetPosition, builder.build());
        }
        return false;
    }

    private boolean checkAndInitializeResources(Area area) {
        int itemCount = getItemCount(context.getSelectedLogId());

        if (context.isUsePreMadeItems()) {
            return checkPreMadeResources(area, itemCount);
        } else {
            return checkFletchingResources(area, itemCount);
        }
    }

    private boolean checkPreMadeResources(Area area, int itemCount) {
        int premadeCount = getItemCount(context.getSelectedPreMadeItemId());
        if (premadeCount < MIN_PREMADE_COUNT || itemCount < MIN_LOG_COUNT) {
            if (area.equals(AreaDefinitions.BANK_AREA) || area.equals(AreaDefinitions.BUFFALO_AREA)) {
                ready = false;
                return true;
            }
            log("Out of resources - Returning to closest bank.");
            if (goToClosestBankCallback != null) {
                goToClosestBankCallback.run();
            }
            return false;
        }
        ready = true;
        spiritDetector.clearDetectedSpirits();
        return true;
    }

    private boolean checkFletchingResources(Area area, int itemCount) {
        // Skip resource check at totem sites - banking logic ensures we have correct resources
        if (!area.equals(AreaDefinitions.BANK_AREA) && !area.equals(AreaDefinitions.BUFFALO_AREA)) {
            ready = true;
            spiritDetector.clearDetectedSpirits();
            return true;
        }

        // Only check resources when at bank areas
        int productCount = getItemCount(context.getSelectedProductId());
        int logCount = getItemCount(context.getSelectedLogId());

        if (productCount < MIN_PRODUCT_COUNT && logCount < MIN_LOG_COUNT) {
            ready = false;
            return true; // At bank, will handle banking
        }

        if (itemCount < MIN_LOG_COUNT && logCount < MIN_LOG_COUNT) {
            ready = false;
            return true;
        }
        ready = true;
        spiritDetector.clearDetectedSpirits();
        return true;
    }

    private boolean handleBuildOrCarveAction() {
        if (currentAction == TotemAction.BUILD || currentAction == TotemAction.CARVE) {
            boolean dialogueVisible = context.getScript().pollFramesUntil(() ->
                    context.getScript().getWidgetManager().getDialogue().isVisible(), getBuildCarveTimeout(), false, false);
            if (!dialogueVisible) {
                currentAction = TotemAction.NONE;
            } else {
                // Check for "no logs" error message in the dialogue
                if (checkForNoLogsMessage()) {
                    return handleNoLogsDetected("Detected 'no logs' message in build/carve dialogue");
                }
            }
            return true;
        }
        return false;
    }

    private boolean handleTotemInteraction(RSObject totemObject) {
        if (canReachTotem(totemObject)) {
            if (!isCurrentlyInteracting()) {
                interactWithTotem(totemObject);
            }
        } else {
            context.getScript().getWalker().walkTo((Position) totemObject.getWorldPosition());
        }
        return false;
    }

    private void interactWithTotem(RSObject totemObject) {
        MenuHook menuHook = list -> {
            for (MenuEntry menuEntry : list) {
                String action = menuEntry.getAction();
                if (action == null) continue;
                String actionLower = action.toLowerCase();
                if (actionLower.contains("build")) {
                    currentAction = TotemAction.BUILD;
                    return menuEntry;
                }
                if (actionLower.contains("carve")) {
                    currentAction = TotemAction.CARVE;
                    return menuEntry;
                }
                if (actionLower.contains("decorate")) {
                    currentAction = TotemAction.DECORATE;
                    return menuEntry;
                }
            }
            return null;
        };
        boolean interacted = totemObject.interact(menuHook);
        if (!interacted) {
            if (totemObject.getWorldPosition().distanceTo((Position) context.getScript().getWorldPosition()) > TOTEM_POSITION_CHECK_DISTANCE) {
                context.getScript().getWalker().walkTo((Position) totemObject.getWorldPosition());
            } else {
                Polygon polygon = totemObject.getConvexHull();
                if (polygon != null) {
                    context.getScript().getFinger().tapGameScreen((Shape) polygon.getResized(POLYGON_RESIZE_FACTOR));
                }
            }
        }
    }

    public void claimOffering(Area area) {
        WorldPosition currentPos = context.getScript().getWorldPosition();
        if (currentPos == null) {
            log("WARNING: Cannot claim offerings - current position is null");
            return;
        }

        // Search for Offering site by ID within the totem area
        RSObject offeringsObject = context.getScript().getObjectManager().getRSObject(rSObject ->
                rSObject != null &&
                        rSObject.getId() == OFFERING_SITE_ID &&
                        area.contains(rSObject.getWorldPosition()));

        // Fallback: search by name within area
        if (offeringsObject == null) {
            List<RSObject> objects = context.getScript().getObjectManager().getObjects(rSObject ->
                            rSObject != null &&
                                    rSObject.getName() != null &&
                                    rSObject.getName().equalsIgnoreCase("Offering site") &&
                                    area.contains(rSObject.getWorldPosition()));
            RSObject found = objects.stream()
                    .min(Comparator.comparingDouble(rSObject -> rSObject.getWorldPosition().distanceTo((Position) currentPos)))
                    .orElse(null);
            offeringsObject = found;
        }

        if (offeringsObject == null) {
            log("WARNING: Could not find 'Offering site' in area: " + area);
            return;
        }

        // Walk to offerings object if needed
        final RSObject finalOfferingsObject = offeringsObject;
        if (finalOfferingsObject.distance(currentPos) > TOTEM_INTERACTION_DISTANCE_EXTENDED) {
            context.getScript().getWalker().walkTo((Position) finalOfferingsObject.getWorldPosition());
            boolean reached = context.getScript().pollFramesUntil(() -> {
                WorldPosition pos = context.getScript().getWorldPosition();
                return pos != null && finalOfferingsObject.distance(pos) <= TOTEM_INTERACTION_DISTANCE_EXTENDED;
            }, getWalkTimeout(), false, false);
            if (!reached) {
                log("WARNING: Failed to reach Offerings object");
                return;
            }
        }

        MenuHook menuHook = list -> {
            if (list == null || list.isEmpty()) {
                return null;
            }
            for (MenuEntry menuEntry : list) {
                if (menuEntry.getAction() == null) continue;
                String action = menuEntry.getAction().toLowerCase();
                if (action.contains("claim")) {
                    return menuEntry;
                }
            }
            log("WARNING: Could not find 'Claim' action in menu.");
            return null;
        };

        if (finalOfferingsObject.interact(menuHook)) {
            log("Successfully clicked Claim Offerings");
            context.getScript().pollFramesUntil(() -> false, 1500, false, false);
        } else {
            log("WARNING: Failed to interact with Offerings object");
        }
    }

    private boolean checkForNoLogsMessage() {
        boolean hasTapHereDialogue = context.getScript().getWidgetManager().getDialogue().isVisible() &&
                context.getScript().getWidgetManager().getDialogue().getDialogueType() == DialogueType.TAP_HERE_TO_CONTINUE;

        UIResultList chatboxText = context.getScript().getWidgetManager().getChatbox().getText();
        if (chatboxText == null) {
            return hasTapHereDialogue;
        }
        List<String> chatLines = chatboxText.asList();
        if (chatLines == null || chatLines.isEmpty()) {
            return hasTapHereDialogue;
        }
        // If we have a TAP_HERE_TO_CONTINUE dialogue and no logs in inventory, it's likely the "no logs" message
        if (hasTapHereDialogue && getItemCount(context.getSelectedLogId()) == 0) {
            return true;
        }

        return false;
    }

    private boolean handleNoLogsDetected(String reason) {
        log(reason + " - returning to closest bank.");
        ready = false;
        currentAction = TotemAction.NONE;
        if (context.getScript().getWidgetManager().getDialogue().isVisible()) {
            DialogueType dialogueType = context.getScript().getWidgetManager().getDialogue().getDialogueType();
            if (dialogueType == DialogueType.TAP_HERE_TO_CONTINUE) {
                context.getScript().getWidgetManager().getDialogue().continueChatDialogue();
                context.getScript().pollFramesHuman(() -> true, RandomUtils.gaussianRandom(80, 500, 120, 120), false);
            }
        }
        if (goToClosestBankCallback != null) {
            goToClosestBankCallback.run();
        }
        return false;
    }

    // Helper methods
    private int getItemCount(int itemId) {
        ItemGroupResult itemGroupResult = context.getScript().getWidgetManager().getInventory().search(Set.of(itemId));
        return itemGroupResult != null ? itemGroupResult.getAmount(new int[]{itemId}) : 0;
    }

    private boolean isInteractable(RSObject rSObject) {
        return rSObject != null && rSObject.isInteractableOnScreen();
    }

    private boolean isCurrentlyInteracting() {
        return currentAction == TotemAction.DECORATE
                || currentAction == TotemAction.BUILD
                || currentAction == TotemAction.CARVE;
    }

    private boolean canReachTotem(RSObject totem) {
        if (totem == null) {
            return false;
        }
        return isInteractable(totem)
                || totem.distance(context.getScript().getWorldPosition()) < 10.0;
    }

    private int getDecorationTimeout() {
        return RandomUtils.uniformRandom(9000, 11000);
    }

    private int getBuildCarveTimeout() {
        return RandomUtils.uniformRandom(2500, 3500);
    }

    private int getWalkTimeout() {
        return RandomUtils.uniformRandom(18000, 22000);
    }

    private void log(String message) {
        context.getScript().log("ValeTotems", message);
    }
}
