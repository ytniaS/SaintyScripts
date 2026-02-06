package com.osmb.script.valetotemsfree.handler;

import com.osmb.api.input.MenuEntry;
import com.osmb.api.item.ItemGroupResult;
import com.osmb.api.location.area.Area;
import com.osmb.api.location.position.Position;
import com.osmb.api.location.position.types.WorldPosition;
import com.osmb.api.scene.RSObject;
import com.osmb.api.utils.RandomUtils;
import com.osmb.api.walker.WalkConfig;
import com.osmb.script.valetotemsfree.util.AreaDefinitions;

import java.util.Comparator;
import java.util.HashSet;
import java.util.Set;
import java.util.function.Predicate;

public class ValeTotemsBankingHandler {
    private static final int FLETCHING_KNIFE_ID = 946;
    private static final int FLETCHING_KNIFE_ID_ALT = 31043; // Alternative fletching knife
    private static final Set<Integer> FLETCHING_KNIFE_IDS = Set.of(FLETCHING_KNIFE_ID, FLETCHING_KNIFE_ID_ALT);
    private static final int OFFERING_ID = 31054;
    private static final Set<Integer> LOG_BASKET_IDS = Set.of(28140, 28142, 28143, 28145);

    // Resource requirements
    private static final int REQUIRED_LOGS_PREMADE = 5;
    private static final int REQUIRED_PREMADE_ITEMS = 20;
    private static final int REQUIRED_BOWS_AUBURNVALE = 20;
    private static final int REQUIRED_LOGS_AUBURNVALE = 5;
    private static final int REQUIRED_BOWS_BUFFALO = 12;
    private static final int INVENTORY_SIZE = 27;
    private static final int KNIFE_WITHDRAW_COUNT = 1;

    private static final double BANK_INTERACTION_DISTANCE = 15;
    private static final double BANK_APPROACH_DISTANCE = 9;
    private static final int TILE_RANDOMISATION_RADIUS_MEDIUM = 2;

    private final ValeTotemsContext context;
    private final ValeTotemsFletchingHandler fletchingHandler;
    private int offeringsCount = 0;

    public ValeTotemsBankingHandler(ValeTotemsContext context, ValeTotemsFletchingHandler fletchingHandler) {
        this.context = context;
        this.fletchingHandler = fletchingHandler;
    }

    public void setOfferingsCount(int count) {
        this.offeringsCount = count;
    }

    public int getOfferingsCount() {
        return offeringsCount;
    }

    public boolean handleBankArea(Area area) {
        // Check if we can skip banking entirely (have enough resources to fletch remaining bows)
        if (canSkipBanking(area)) {
            if (context.getScript().getWidgetManager().getBank().isVisible()) {
                context.getScript().getWidgetManager().getBank().close();
            }

            // If we need to fletch more bows but have enough logs, fletch now (without opening bank)
            if (!context.isUsePreMadeItems()) {
                if (area.equals(AreaDefinitions.BANK_AREA)) {
                    int currentBows = getItemCount(context.getSelectedProductId());
                    if (currentBows < REQUIRED_BOWS_AUBURNVALE) {
                        log("Skipping bank - fletching " + (REQUIRED_BOWS_AUBURNVALE - currentBows) + " more bows with existing logs");
                        if (!fletchingHandler.performFletching(REQUIRED_BOWS_AUBURNVALE)) {
                            log("Fletching failed when skipping bank");
                            return false;
                        }
                    }
                } else if (area.equals(AreaDefinitions.BUFFALO_AREA) && !context.isUseLogBasket()) {
                    int currentBows = getItemCount(context.getSelectedProductId());
                    if (currentBows < REQUIRED_BOWS_BUFFALO) {
                        log("Skipping bank - fletching " + (REQUIRED_BOWS_BUFFALO - currentBows) + " more bows with existing logs");
                        if (!fletchingHandler.performFletching(REQUIRED_BOWS_BUFFALO)) {
                            log("Fletching failed when skipping bank");
                            return false;
                        }
                    }
                }
            }

            return true;
        }

        RSObject bankObject = findBankObject(area);
        if (!openBankIfNeeded(area, bankObject)) {
            return false;
        }

        depositUnwantedItems(area);

        if (!withdrawKnifeIfNeeded()) {
            return true;
        }

        if (context.isUsePreMadeItems()) {
            return handlePreMadeItemsBanking(area);
        } else {
            // Different banking logic for Auburnvale vs Buffalo
            if (area.equals(AreaDefinitions.BANK_AREA)) {
                return handleAuburnvaleBanking(area, bankObject);
            } else if (area.equals(AreaDefinitions.BUFFALO_AREA)) {
                if (context.isUseLogBasket()) {
                    // Skip Buffalo if basket enabled
                    return true;
                }
                return handleBuffaloBanking(area, bankObject);
            }
            return false;
        }
    }

    private boolean canSkipBanking(Area area) {
        if (!hasKnife()) {
            log("canSkipBanking: No knife, cannot skip");
            return false;
        }

        if (context.isUsePreMadeItems()) {
            return false; // Always need to bank for premade items
        }

        if (area.equals(AreaDefinitions.BANK_AREA)) {
            int bows = getItemCount(context.getSelectedProductId());
            int logs = getItemCount(context.getSelectedLogId());
            boolean basketOk = !context.isUseLogBasket() || context.isBasketRefilledThisLoop();

            if (bows >= REQUIRED_BOWS_AUBURNVALE && logs >= REQUIRED_LOGS_AUBURNVALE && basketOk) {
                return true;
            }

            if (bows < REQUIRED_BOWS_AUBURNVALE && basketOk) {
                int bowsNeeded = REQUIRED_BOWS_AUBURNVALE - bows;
                int totalLogsNeeded = bowsNeeded + REQUIRED_LOGS_AUBURNVALE;
                if (logs >= totalLogsNeeded) {
                    return true;
                }
            }

            return false;
        } else if (area.equals(AreaDefinitions.BUFFALO_AREA)) {
            if (context.isUseLogBasket()) {
                return true; // Skip Buffalo if basket enabled
            }
            int bows = getItemCount(context.getSelectedProductId());
            int logs = getItemCount(context.getSelectedLogId());

            // If we already have enough bows AND minimum logs, skip banking
            if (bows >= REQUIRED_BOWS_BUFFALO && logs >= REQUIRED_LOGS_AUBURNVALE) {
                return true;
            }
            return false;
        }

        return false;
    }

    private RSObject findBankObject(Area area) {
        String objectName = area == AreaDefinitions.BUFFALO_AREA ? "buffalo" : "Bank booth";
        Predicate<RSObject> predicate = rSObject ->
                rSObject != null &&
                        rSObject.getName() != null &&
                        rSObject.getName().toLowerCase().contains(objectName.toLowerCase()) &&
                        area.contains(rSObject.getWorldPosition());
        return context.getScript().getObjectManager().getObjects(predicate).stream()
                .min(Comparator.comparingDouble(rSObject ->
                        rSObject.getWorldPosition().distanceTo((Position) context.getScript().getWorldPosition())))
                .orElse(null);
    }

    private boolean openBankIfNeeded(Area area, RSObject bankObject) {
        if (context.getScript().getWidgetManager().getBank().isVisible()) {
            return true;
        }

        if (bankObject != null && isInteractable(bankObject) && bankObject.distance(context.getScript().getWorldPosition()) < BANK_INTERACTION_DISTANCE) {
            boolean interacted = bankObject.interact(list -> {
                for (MenuEntry menuEntry : list) {
                    String action = menuEntry.getAction();
                    if (action == null) continue;
                    String actionLower = action.toLowerCase();
                    if (actionLower.contains("bank") || actionLower.contains("use") || actionLower.contains("buffalo")) {
                        return menuEntry;
                    }
                }
                return null;
            });

            if (interacted && context.getScript().pollFramesUntil(() -> context.getScript().getWidgetManager().getBank().isVisible(), getDialogueTimeout(), false, false)) {
                // Count offerings when depositing (offerings are already counted when collected, so we just log)
                int offeringsToDeposit = getItemCount(OFFERING_ID);
                if (offeringsToDeposit > 0) {
                    log("Depositing " + offeringsToDeposit + " offerings. Total collected: " + offeringsCount);
                }
                return true;
            }
            return false;
        }

        if (area.contains(context.getScript().getWorldPosition())) {
            // Check if stuck in problematic area
            WorldPosition currentPos = context.getScript().getWorldPosition();
            if (currentPos != null && AreaDefinitions.PROBLEMATIC_WALK_AREA.contains(currentPos)) {
                // Walk to a safe position first (north of problematic area, towards bank)
                WorldPosition waypoint = new WorldPosition(1365, 3360, 0);
                context.getScript().getWalker().walkTo((Position) waypoint);
                return false;
            }
            if (bankObject != null) {
                context.getScript().getWalker().walkTo((Position) bankObject.getWorldPosition());
            } else {
                context.getScript().getWalker().walkTo((Position) area.getRandomPosition());
            }
            return false;
        }

        String objectName = area == AreaDefinitions.BUFFALO_AREA ? "buffalo" : "Bank booth";
        Predicate<RSObject> predicate = rSObject ->
                rSObject != null &&
                        rSObject.getName() != null &&
                        rSObject.getName().toLowerCase().contains(objectName.toLowerCase()) &&
                        area.contains(rSObject.getWorldPosition());

        WalkConfig.Builder builder = new WalkConfig.Builder().tileRandomisationRadius(TILE_RANDOMISATION_RADIUS_MEDIUM);
        builder.breakCondition(() -> {
            RSObject obj = context.getScript().getObjectManager().getObjects(predicate).stream()
                    .min(Comparator.comparingDouble(rSObject ->
                            rSObject.getWorldPosition().distanceTo((Position) context.getScript().getWorldPosition())))
                    .orElse(null);
            return isInteractable(obj) && obj.distance(context.getScript().getWorldPosition()) < BANK_APPROACH_DISTANCE;
        });
        context.getScript().getWalker().walkTo((Position) area.getRandomPosition(), builder.build());
        return false;
    }

    private void depositUnwantedItems(Area area) {
        // Deposit items individually to ensure we never deposit the knife or basket
        boolean hadKnifeBefore = hasKnife();
        int offeringCount = getItemCount(OFFERING_ID);
        if (offeringCount > 0) {
            context.getScript().getWidgetManager().getBank().deposit(OFFERING_ID, offeringCount);
        }

        if (context.isUsePreMadeItems()) {
            // Premade mode: deposit all logs and premade items
            int logCount = getItemCount(context.getSelectedLogId());
            if (logCount > 0) {
                context.getScript().getWidgetManager().getBank().deposit(context.getSelectedLogId(), logCount);
            }
            if (context.getSelectedPreMadeItemId() > 0) {
                int premadeCount = getItemCount(context.getSelectedPreMadeItemId());
                if (premadeCount > 0) {
                    context.getScript().getWidgetManager().getBank().deposit(context.getSelectedPreMadeItemId(), premadeCount);
                }
            }
        } else {
            // Fletching mode: only deposit excess products, never deposit logs
            int productCount = getItemCount(context.getSelectedProductId());
            int maxProducts = REQUIRED_BOWS_AUBURNVALE; // Default to Auburnvale requirement
            if (area.equals(AreaDefinitions.BUFFALO_AREA)) {
                maxProducts = REQUIRED_BOWS_BUFFALO;
            }

            if (productCount > maxProducts) {
                int excess = productCount - maxProducts;
                context.getScript().getWidgetManager().getBank().deposit(context.getSelectedProductId(), excess);
            }
            // Never deposit logs
        }

        context.getScript().pollFramesHuman(() -> true, RandomUtils.gaussianRandom(400, 1500, 350, 350), false);

        // Knife sometimes accidentally deposited? Should prevent
        if (hadKnifeBefore && !hasKnife()) {
            log("WARNING: Knife was accidentally deposited! Attempting to withdraw...");
            // Try to withdraw knife back (tries both knife types)
            ItemGroupResult knifeResult = context.getScript().getWidgetManager().getBank().search(FLETCHING_KNIFE_IDS);
            if (knifeResult != null) {
                for (int knifeId : FLETCHING_KNIFE_IDS) {
                    if (knifeResult.getAmount(new int[]{knifeId}) > 0) {
                        context.getScript().getWidgetManager().getBank().withdraw(knifeId, KNIFE_WITHDRAW_COUNT);
                        context.getScript().pollFramesHuman(() -> true, RandomUtils.gaussianRandom(400, 1800, 400, 400), false);
                        break;
                    }
                }
            }
        }

        // Safety check: verify basket is still in inventory (if enabled)
        if (context.isUseLogBasket() && !context.isUsePreMadeItems()) {
            ItemGroupResult basketSearch = context.getScript().getWidgetManager().getInventory().search(LOG_BASKET_IDS);
            boolean hasBasket = false;
            if (basketSearch != null) {
                for (int basketId : LOG_BASKET_IDS) {
                    if (basketSearch.getAmount(new int[]{basketId}) > 0) {
                        hasBasket = true;
                        break;
                    }
                }
            }
            if (!hasBasket) {
                log("WARNING: Log basket was accidentally deposited! Attempting to withdraw...");
                ItemGroupResult bankBasketSearch = context.getScript().getWidgetManager().getBank().search(LOG_BASKET_IDS);
                if (bankBasketSearch != null) {
                    for (int basketId : LOG_BASKET_IDS) {
                        if (bankBasketSearch.getAmount(new int[]{basketId}) > 0) {
                            context.getScript().getWidgetManager().getBank().withdraw(basketId, 1);
                            context.getScript().pollFramesHuman(() -> true, RandomUtils.gaussianRandom(400, 1800, 400, 400), false);
                            break;
                        }
                    }
                }
            }
        }
    }

    private boolean withdrawKnifeIfNeeded() {
        if (context.isFletchingKnifeEquipped() || hasKnife()) {
            return true;
        }

        ItemGroupResult knifeResult = context.getScript().getWidgetManager().getBank().search(FLETCHING_KNIFE_IDS);
        if (knifeResult == null) {
            log("No Knife in bank.");
            context.getScript().stop();
            return false;
        }

        for (int knifeId : FLETCHING_KNIFE_IDS) {
            if (knifeResult.getAmount(new int[]{knifeId}) > 0) {
                context.getScript().getWidgetManager().getBank().withdraw(knifeId, KNIFE_WITHDRAW_COUNT);
                context.getScript().pollFramesHuman(() -> true, RandomUtils.gaussianRandom(400, 1800, 400, 400), false);
                return true;
            }
        }

        log("No Knife in bank.");
        context.getScript().stop();
        return false;
    }

    private boolean handlePreMadeItemsBanking(Area area) {
        int logs = getItemCount(context.getSelectedLogId());

        // Adjust logs to required amount
        if (logs < REQUIRED_LOGS_PREMADE) {
            if (!withdrawItem(context.getSelectedLogId(), REQUIRED_LOGS_PREMADE - logs)) {
                return true;
            }
        } else if (logs > REQUIRED_LOGS_PREMADE) {
            context.getScript().getWidgetManager().getBank().deposit(context.getSelectedLogId(), logs - REQUIRED_LOGS_PREMADE);
            context.getScript().pollFramesUntil(() -> getItemCount(context.getSelectedLogId()) <= REQUIRED_LOGS_PREMADE, getBankWithdrawTimeout(), false, false);
        }

        // Withdraw premade items if needed
        if (context.getSelectedPreMadeItemId() > 0 && getItemCount(context.getSelectedPreMadeItemId()) < REQUIRED_PREMADE_ITEMS) {
            int needed = REQUIRED_PREMADE_ITEMS - getItemCount(context.getSelectedPreMadeItemId());
            if (!withdrawItem(context.getSelectedPreMadeItemId(), needed)) {
                return true;
            }
            context.getScript().pollFramesUntil(() -> getItemCount(context.getSelectedPreMadeItemId()) >= REQUIRED_PREMADE_ITEMS, getBankWithdrawTimeout(), false, false);
        }

        // Verify we have everything needed
        if (getItemCount(context.getSelectedLogId()) >= REQUIRED_LOGS_PREMADE &&
                getItemCount(context.getSelectedPreMadeItemId()) >= REQUIRED_PREMADE_ITEMS) {
            return true;
        }
        return false;
    }

    private boolean withdrawItem(int itemId, int amount) {
        ItemGroupResult result = context.getScript().getWidgetManager().getBank().search(Set.of(itemId));
        if (result == null || result.getAmount(new int[]{itemId}) <= 0) {
            log("No " + (itemId == context.getSelectedLogId() ? "logs" : "items") + " in bank.");
            context.getScript().stop();
            return false;
        }
        context.getScript().getWidgetManager().getBank().withdraw(itemId, amount);
        return true;
    }

    private void depositAllExceptEssentials() {
        if (!context.getScript().getWidgetManager().getBank().isVisible()) {
            return;
        }
        Set<Integer> keep = new HashSet<>();
        keep.add(context.getSelectedLogId());
        keep.add(context.getSelectedProductId());
        if (context.getSelectedPreMadeItemId() > 0) {
            keep.add(context.getSelectedPreMadeItemId());
        }
        keep.addAll(FLETCHING_KNIFE_IDS);
        keep.addAll(LOG_BASKET_IDS);
        context.getScript().getWidgetManager().getBank().depositAll(keep);
        context.getScript().pollFramesHuman(() -> true, RandomUtils.gaussianRandom(400, 1500, 350, 350), false);
    }

    private boolean handleAuburnvaleBanking(Area area, RSObject bankObject) {
        if (context.isUseLogBasket() && !context.isBasketRefilledThisLoop()) {
            if (!fletchingHandler.fillBasketWithLogs()) {
                return false;
            }
            context.setBasketRefilledThisLoop(true);
            context.getScript().pollFramesHuman(() -> true, RandomUtils.gaussianRandom(50, 300, 80, 80), false);
        }

        int currentBows = getItemCount(context.getSelectedProductId());
        int currentLogs = getItemCount(context.getSelectedLogId());
        log("handleAuburnvaleBanking: Starting with " + currentBows + " bows, " + currentLogs + " logs");

        boolean hasEnoughBowsToLeave = currentBows >= REQUIRED_BOWS_AUBURNVALE;
        if (hasEnoughBowsToLeave && currentLogs >= REQUIRED_LOGS_AUBURNVALE && hasKnife() && (!context.isUseLogBasket() || context.isBasketRefilledThisLoop())) {
            log("handleAuburnvaleBanking: Already have enough resources (" + currentBows + " bows, " + currentLogs + " logs), leaving");
            return true;
        }

        // Need to fletch more bows (fletch at bank if we have less than 20)
        if (currentBows < REQUIRED_BOWS_AUBURNVALE) {
            log("handleAuburnvaleBanking: Need to fletch more bows (have " + currentBows + ", need " + REQUIRED_BOWS_AUBURNVALE + ")");
            int bowsNeeded = REQUIRED_BOWS_AUBURNVALE - currentBows;
            // Calculate logs needed: 1 log = 1 bow
            int logsNeededForBows = bowsNeeded;

            // Calculate total logs needed: logs for fletching + minimum logs after fletching
            int totalLogsNeeded = logsNeededForBows + REQUIRED_LOGS_AUBURNVALE;
            if (currentLogs < totalLogsNeeded) {
                log("Withdrawing all logs (need " + logsNeededForBows + " for fletching + " + REQUIRED_LOGS_AUBURNVALE + " minimum after)");
                ItemGroupResult logResult = context.getScript().getWidgetManager().getBank().search(Set.of(context.getSelectedLogId()));
                if (logResult == null || logResult.getAmount(new int[]{context.getSelectedLogId()}) <= 0) {
                    log("No logs in bank.");
                    context.getScript().stop();
                    return false;
                }
                context.getScript().getWidgetManager().getBank().withdraw(context.getSelectedLogId(), INVENTORY_SIZE);
                boolean logsWithdrawn = context.getScript().pollFramesUntil(() -> getItemCount(context.getSelectedLogId()) >= totalLogsNeeded, getBankWithdrawTimeout(), false, false);
                if (!logsWithdrawn) {
                    log("Failed to withdraw enough logs for fletching - timeout. Depositing non-essentials and retrying.");
                    depositAllExceptEssentials();
                    context.getScript().getWidgetManager().getBank().withdraw(context.getSelectedLogId(), INVENTORY_SIZE);
                    logsWithdrawn = context.getScript().pollFramesUntil(() -> getItemCount(context.getSelectedLogId()) >= totalLogsNeeded, getBankWithdrawTimeout(), false, false);
                    if (!logsWithdrawn) {
                        log("Failed to withdraw enough logs after cleanup - timeout");
                        return false;
                    }
                }
                currentLogs = getItemCount(context.getSelectedLogId());
            }

            log("Fletching at bank: have " + currentBows + " bows, " + currentLogs + " logs, need " + REQUIRED_BOWS_AUBURNVALE + " bows");
            int bowsBeforeFletching = currentBows;

            // Ensure we have logs to fletch
            if (currentLogs < bowsNeeded) {
                log("ERROR: Not enough logs to fletch! Have " + currentLogs + " logs, need " + bowsNeeded + " for fletching");
                return false;
            }

            if (!fletchingHandler.performFletching(REQUIRED_BOWS_AUBURNVALE)) {
                log("Fletching failed - returning false");
                return false;
            }

            currentBows = getItemCount(context.getSelectedProductId());
            currentLogs = getItemCount(context.getSelectedLogId());
            log("After fletching: have " + currentBows + " bows, " + currentLogs + " logs (started with " + bowsBeforeFletching + ")");

            if (currentBows == bowsBeforeFletching) {
                log("Fletching did not complete - still have " + currentBows + " bows. Expected at least " + (bowsBeforeFletching + 1));
                return false;
            }

            if (currentBows < REQUIRED_BOWS_AUBURNVALE) {
                log("Fletching incomplete - only have " + currentBows + " bows, need " + REQUIRED_BOWS_AUBURNVALE);
                return false;
            }
            log("handleAuburnvaleBanking: Fletching complete, have " + currentBows + " bows");
        } else {
            log("handleAuburnvaleBanking: Already have " + currentBows + " bows (>= " + REQUIRED_BOWS_AUBURNVALE + "), skipping fletching");
        }

        // After fletching, we should already have the minimum logs if we withdrew correctly above
        // This check is just a safety net
        if (currentLogs < REQUIRED_LOGS_AUBURNVALE) {
            log("WARNING: After fletching, only have " + currentLogs + " logs, need " + REQUIRED_LOGS_AUBURNVALE + ". Please report this");
            if (!context.getScript().getWidgetManager().getBank().isVisible()) {
                // Find bank object if we don't have a reference
                if (bankObject == null) {
                    bankObject = findBankObject(area);
                }

                if (bankObject != null && isInteractable(bankObject) && bankObject.distance(context.getScript().getWorldPosition()) < BANK_INTERACTION_DISTANCE) {
                    boolean bankOpened = bankObject.interact(list -> {
                        for (MenuEntry menuEntry : list) {
                            String action = menuEntry.getAction();
                            if (action == null) continue;
                            String actionLower = action.toLowerCase();
                            if (actionLower.contains("bank") || actionLower.contains("use")) {
                                return menuEntry;
                            }
                        }
                        return null;
                    });

                    if (bankOpened) {
                        boolean bankVisible = context.getScript().pollFramesUntil(() -> context.getScript().getWidgetManager().getBank().isVisible(), getDialogueTimeout(), false, false);
                        if (!bankVisible) {
                            log("Failed to re-open bank after fletching");
                            return false;
                        }
                    } else {
                        log("Failed to interact with bank to re-open after fletching");
                        return false;
                    }
                } else {
                    log("Bank object not accessible to re-open after fletching");
                    return false;
                }
            }

            ItemGroupResult logResult = context.getScript().getWidgetManager().getBank().search(Set.of(context.getSelectedLogId()));
            if (logResult == null || logResult.getAmount(new int[]{context.getSelectedLogId()}) <= 0) {
                log("No logs in bank.");
                context.getScript().stop();
                return false;
            }
            context.getScript().getWidgetManager().getBank().withdraw(context.getSelectedLogId(), INVENTORY_SIZE);
            boolean additionalLogsWithdrawn = context.getScript().pollFramesUntil(() -> getItemCount(context.getSelectedLogId()) >= REQUIRED_LOGS_AUBURNVALE, getBankWithdrawTimeout(), false, false);
            if (!additionalLogsWithdrawn) {
                log("Failed to withdraw additional logs - timeout waiting for logs");
                return false;
            }
        }

        currentBows = getItemCount(context.getSelectedProductId());
        currentLogs = getItemCount(context.getSelectedLogId());
        boolean knifePresent = hasKnife();
        boolean basketReady = !context.isUseLogBasket() || context.isBasketRefilledThisLoop();

        boolean hasEnoughBows = currentBows >= REQUIRED_BOWS_AUBURNVALE;
        log("Final check: " + currentBows + " bows, " + currentLogs + " logs, knife: " + knifePresent + ", basket: " + basketReady);
        return hasEnoughBows && currentLogs >= REQUIRED_LOGS_AUBURNVALE && knifePresent && basketReady;
    }

    private boolean handleBuffaloBanking(Area area, RSObject bankObject) {
        if (context.isUseLogBasket()) {
            return true;
        }

        int currentBows = getItemCount(context.getSelectedProductId());
        int currentLogs = getItemCount(context.getSelectedLogId());

        // If we already have enough bows AND minimum logs, we're ready
        if (currentBows >= REQUIRED_BOWS_BUFFALO && currentLogs >= REQUIRED_LOGS_AUBURNVALE) {
            return true;
        }

        // Have enough bows but no/few logs (e.g. over-fletched before Buffalo) — withdraw minimum logs only
        if (currentBows >= REQUIRED_BOWS_BUFFALO && currentLogs < REQUIRED_LOGS_AUBURNVALE) {
            ItemGroupResult logResult = context.getScript().getWidgetManager().getBank().search(Set.of(context.getSelectedLogId()));
            if (logResult == null || logResult.getAmount(new int[]{context.getSelectedLogId()}) <= 0) {
                log("No logs in bank.");
                context.getScript().stop();
                return false;
            }
            context.getScript().getWidgetManager().getBank().withdraw(context.getSelectedLogId(), INVENTORY_SIZE);
            boolean logsWithdrawn = context.getScript().pollFramesUntil(() -> getItemCount(context.getSelectedLogId()) >= REQUIRED_LOGS_AUBURNVALE, getBankWithdrawTimeout(), false, false);
            if (!logsWithdrawn) {
                log("Failed to withdraw logs at Buffalo bank (have enough bows, needed minimum logs)");
                return false;
            }
            return true;
        }

        // Need to fletch more bows
        int bowsNeeded = REQUIRED_BOWS_BUFFALO - currentBows;
        // Calculate logs needed: 1 log = 1 bow
        int logsNeededForBows = bowsNeeded;

        if (currentLogs < logsNeededForBows) {
            ItemGroupResult logResult = context.getScript().getWidgetManager().getBank().search(Set.of(context.getSelectedLogId()));
            if (logResult == null || logResult.getAmount(new int[]{context.getSelectedLogId()}) <= 0) {
                log("No logs in bank.");
                context.getScript().stop();
                return false;
            }
            context.getScript().getWidgetManager().getBank().withdraw(context.getSelectedLogId(), INVENTORY_SIZE);
            boolean logsWithdrawn = context.getScript().pollFramesUntil(() -> getItemCount(context.getSelectedLogId()) >= logsNeededForBows, getBankWithdrawTimeout(), false, false);
            if (!logsWithdrawn) {
                log("Failed to withdraw logs at Buffalo bank");
                return false;
            }
            currentLogs = getItemCount(context.getSelectedLogId());
        }

        if (!fletchingHandler.performFletching(REQUIRED_BOWS_BUFFALO)) {
            return false;
        }
        currentBows = getItemCount(context.getSelectedProductId());
        currentLogs = getItemCount(context.getSelectedLogId());

        if (currentBows < REQUIRED_BOWS_BUFFALO) {
            log("Fletching incomplete at Buffalo - only have " + currentBows + " bows");
            return false;
        }

        // Ensure we have minimum logs before leaving
        if (currentLogs < REQUIRED_LOGS_AUBURNVALE) {
            log("Not enough logs at Buffalo - have " + currentLogs + ", need " + REQUIRED_LOGS_AUBURNVALE);
            // Withdraw more logs to meet minimum requirement
            int additionalLogsNeeded = REQUIRED_LOGS_AUBURNVALE - currentLogs;
            ItemGroupResult logResult = context.getScript().getWidgetManager().getBank().search(Set.of(context.getSelectedLogId()));
            if (logResult == null || logResult.getAmount(new int[]{context.getSelectedLogId()}) <= 0) {
                log("No logs in bank.");
                context.getScript().stop();
                return false;
            }
            context.getScript().getWidgetManager().getBank().withdraw(context.getSelectedLogId(), INVENTORY_SIZE);
            boolean logsWithdrawn = context.getScript().pollFramesUntil(() -> getItemCount(context.getSelectedLogId()) >= REQUIRED_LOGS_AUBURNVALE, getBankWithdrawTimeout(), false, false);
            if (!logsWithdrawn) {
                log("Failed to withdraw additional logs at Buffalo bank");
                return false;
            }
            currentLogs = getItemCount(context.getSelectedLogId());
        }

        // Final check: must have both required bows and minimum logs
        return currentBows >= REQUIRED_BOWS_BUFFALO && currentLogs >= REQUIRED_LOGS_AUBURNVALE;
    }

    // Helper methods
    private int getItemCount(int itemId) {
        ItemGroupResult itemGroupResult = context.getScript().getWidgetManager().getInventory().search(Set.of(itemId));
        return itemGroupResult != null ? itemGroupResult.getAmount(new int[]{itemId}) : 0;
    }

    private boolean hasKnife() {
        if (context.isFletchingKnifeEquipped()) {
            return true;
        }
        ItemGroupResult knifeResult = context.getScript().getWidgetManager().getInventory().search(FLETCHING_KNIFE_IDS);
        if (knifeResult == null) {
            return false;
        }
        int totalKnives = 0;
        for (int knifeId : FLETCHING_KNIFE_IDS) {
            totalKnives += knifeResult.getAmount(new int[]{knifeId});
        }
        return totalKnives > 0;
    }

    private boolean isInteractable(RSObject rSObject) {
        return rSObject != null && rSObject.isInteractableOnScreen();
    }

    private int getBankWithdrawTimeout() {
        return RandomUtils.uniformRandom(2500, 3500);
    }

    private int getDialogueTimeout() {
        return RandomUtils.uniformRandom(3500, 4500);
    }

    private void log(String message) {
        context.getScript().log("ValeTotems", message);
    }
}
