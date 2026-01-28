package com.osmb.script.valetotemsfree;

import com.osmb.api.item.ItemGroupResult;
import com.osmb.api.location.area.Area;
import com.osmb.api.location.position.Position;
import com.osmb.api.location.position.types.WorldPosition;
import com.osmb.api.script.Script;
import com.osmb.api.trackers.experience.XPTracker;
import com.osmb.api.ui.chatbox.ChatboxFilterTab;
import com.osmb.api.ui.component.tabs.skill.SkillType;
import com.osmb.api.utils.RandomUtils;
import com.osmb.api.visual.drawing.Canvas;
import com.osmb.script.valetotemsfree.handler.*;
import com.osmb.script.valetotemsfree.ui.SettingsUI;
import com.osmb.script.valetotemsfree.util.AreaDefinitions;

import java.awt.Color;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;


public class ValeTotemsController {
    private final Script script;
    private boolean sessionEnded = false;
    private final Object sessionLock = new Object();
    
    // Handlers
    private ValeTotemsContext context;
    private ValeTotemsBankingHandler bankingHandler;
    private ValeTotemsFletchingHandler fletchingHandler;
    private ValeTotemsSpiritDetector spiritDetector;
    private ValeTotemsTotemHandler totemHandler;
    
    private static final int FLETCHING_KNIFE_ID = 946;
    private static final int FLETCHING_KNIFE_ID_ALT = 31043; // Fletching knife
    private static final Set<Integer> FLETCHING_KNIFE_IDS = Set.of(FLETCHING_KNIFE_ID, FLETCHING_KNIFE_ID_ALT);
    private static final int OAK_LOG_ID = 1521;
    private static final int OFFERING_ID = 31054;

    // Resource requirements
    private static final int REQUIRED_LOGS_PREMADE = 5;
    private static final int REQUIRED_PREMADE_ITEMS = 20;

    // Auburnvale bank requirements (start of loop)
    private static final int REQUIRED_BOWS_AUBURNVALE = 20;
    private static final int REQUIRED_LOGS_AUBURNVALE = 5;

    // Buffalo bank requirements (if basket disabled)
    private static final int REQUIRED_BOWS_BUFFALO = 12;

    // Trip/Offering
    private static final int BASE_TRIPS_UNTIL_OFFERING = 7;
    private static final int RANDOM_TRIPS_RANGE = 3;

    // XP Tracking
    private static final long XP_TO_99 = 13_034_431L;

    private int selectedLogId = OAK_LOG_ID;
    private int selectedProductId = 62;
    private boolean usePreMadeItems = false;
    private boolean useLogBasket = false;
    private int offeringsCount = 0;
    private boolean fletchingKnifeEquipped = false;
    private int selectedPreMadeItemId = -1;

    private String selectedLogName = "Loading...";
    private String selectedItemName = "Loading...";
    private long startTime;
    private int totemsCompleted = 0;
    private int tripCount = 0;
    private int tripsUntilOffering = BASE_TRIPS_UNTIL_OFFERING;
    private boolean shouldCollectOfferings = false;
    private long lastProgressTime = System.currentTimeMillis();
    private XPTracker fletchingXP;
    // Log balance positions - between Totem 1 and Totem 2
    private final WorldPosition LOG_BALANCE_START = new WorldPosition(1453, 3335, 0);
    private boolean firstRun = true;
    private List<TaskType> taskSequence = new ArrayList<>();
    private int currentTaskIndex = 0;
    private TaskType currentTask = TaskType.IDLE;
    private long lastPositionChangeTime = 0;
    private WorldPosition lastStuckPosition = null;
    private TaskType lastTask = null;
    private long lastTaskChangeTime = System.currentTimeMillis();

    public ValeTotemsController(Script script) {
        this.script = script;
        startTime = System.currentTimeMillis();
        
        // Initialize context and handlers
        context = new ValeTotemsContext(script);
        spiritDetector = new ValeTotemsSpiritDetector(context);
        fletchingHandler = new ValeTotemsFletchingHandler(context);
        bankingHandler = new ValeTotemsBankingHandler(context, fletchingHandler);
        totemHandler = new ValeTotemsTotemHandler(context, spiritDetector);
        
        // Wire up callbacks
        fletchingHandler.setWithdrawItemCallback((itemId, amount) -> {
            ItemGroupResult result = script.getWidgetManager().getBank().search(Set.of(itemId));
            if (result == null || result.getAmount(new int[]{itemId}) <= 0) {
                return false;
            }
            script.getWidgetManager().getBank().withdraw(itemId, amount);
            return true;
        });
        
        totemHandler.setGoToClosestBankCallback(() -> goToClosestBank());
        totemHandler.setOnTotemCompletedCallback(() -> {
            ++totemsCompleted;
        });
        totemHandler.setOnOfferingsCollectedCallback((area) -> {
            // Claim offerings only during the offering collection phase (every X randomized loops)
            if (shouldCollectOfferings) {
                log("Collecting offerings after totem completion (totem " + totemsCompleted + ")");
                int offeringsBefore = getItemCount(OFFERING_ID);
                totemHandler.claimOffering(area);
                context.getScript().pollFramesUntil(() -> false, 1500, false, false);
                int offeringsAfter = getItemCount(OFFERING_ID);
                int newlyCollected = offeringsAfter - offeringsBefore;
                if (newlyCollected > 0) {
                    offeringsCount += newlyCollected;
                    log("Collected " + newlyCollected + " new offerings. Total: " + offeringsCount);
                    bankingHandler.setOfferingsCount(offeringsCount);
                }
            }
        });
        
        // Update banking handler with offerings count
        bankingHandler.setOfferingsCount(offeringsCount);
    }

    private void log(String message) {
        script.log("ValeTotems", message);
    }

    private SettingsUI.Settings settings;
    private String mode;

    public void initialize(SettingsUI.Settings settings) {
        synchronized (sessionLock) {
            sessionEnded = false;
        }
        // Initialize native XPTracker
        var xpTrackers = script.getXPTrackers();
        if (xpTrackers != null) {
            fletchingXP = xpTrackers.get(SkillType.FLETCHING);
            context.setFletchingXP(fletchingXP);
            if (fletchingXP != null) {
                log("XP Tracker initialized successfully for Fletching");
            } else {
                log("WARNING: XP Tracker for Fletching is null");
            }
        } else {
            log("WARNING: getXPTrackers() returned null");
        }
        lastProgressTime = System.currentTimeMillis();

        this.settings = settings;
        selectedLogId = settings.logType.getItemId();
        usePreMadeItems = settings.mode == SettingsUI.Mode.PRE_MADE;
        useLogBasket = settings.useLogBasket && !usePreMadeItems; // Basket only for fletching mode
        fletchingKnifeEquipped = settings.knifeEquipped;
        selectedLogName = settings.logType.name();

        // Update context
        context.setSelectedLogId(selectedLogId);
        context.setUsePreMadeItems(usePreMadeItems);
        context.setUseLogBasket(useLogBasket);
        context.setFletchingKnifeEquipped(fletchingKnifeEquipped);

        // Reset loop state
        context.setBasketRefilledThisLoop(false);
        context.setBasketEmptiedAfterTotem5(false);
        context.setPostTotem5FletchingDone(false);

        if (usePreMadeItems && settings.premadeItem != null) {
            selectedPreMadeItemId = settings.premadeItem.getItemId();
            selectedItemName = settings.premadeItem.getName();
            selectedProductId = -1;
            context.setSelectedPreMadeItemId(selectedPreMadeItemId);
            context.setSelectedProductId(-1);
        } else {
            selectedPreMadeItemId = -1;
            selectedProductId = settings.productType.getProductId(settings.logType);
            selectedItemName = settings.productType.name();
            context.setSelectedPreMadeItemId(-1);
            context.setSelectedProductId(selectedProductId);
        }

        mode = settings.mode == SettingsUI.Mode.PRE_MADE ? "Pre-Made" : "Fletching";

        if (usePreMadeItems) {
            log("Mode Pre-Made: ON. Item: " + selectedItemName + " (ID: " + selectedPreMadeItemId + ")");
            if (fletchingKnifeEquipped) {
                log("Knife Equipped");
            }
        }
        tripsUntilOffering = BASE_TRIPS_UNTIL_OFFERING + RandomUtils.uniformRandom(0, RANDOM_TRIPS_RANGE);
        shouldCollectOfferings = false;
        taskSequence.clear();
        setupChatbox();
        log("Script started successfully");
    }

    private void setupChatbox() {
        if (script.getWidgetManager() == null || script.getWidgetManager().getChatbox() == null) {
            log("Chatbox not available");
            return;
        }
        ChatboxFilterTab chatboxFilterTab = script.getWidgetManager().getChatbox().getActiveFilterTab();
        log("Current chatbox tab: " + (chatboxFilterTab != null ? chatboxFilterTab.name() : "NULL"));
        if (chatboxFilterTab != ChatboxFilterTab.GAME) {
            log("Fixing tab...");
            boolean success = script.getWidgetManager().getChatbox().openFilterTab(ChatboxFilterTab.GAME);
            if (success) {
                log("Success.");
            } else {
                log("Failed.");
            }
        } else {
            log("Chatbox set to GAME");
        }
    }

    public int execute() {
        TaskType nextTask;
        checkProgress();
        if (script.getWorldPosition() == null) {
            return 1000;
        }

        // Check if stuck in problematic area and update position tracking
        WorldPosition currentPos = script.getWorldPosition();
        if (currentPos != null) {
            if (AreaDefinitions.PROBLEMATIC_WALK_AREA.contains(currentPos)) {
                handleStuckInProblematicArea(currentPos);
            } else {
                // Reset stuck tracking if we're not in the problematic area
                if (lastStuckPosition != null && !currentPos.equals(lastStuckPosition)) {
                    lastStuckPosition = null;
                    lastPositionChangeTime = System.currentTimeMillis();
                }
            }
        }

        script.pollFramesHuman(() -> true, RandomUtils.gaussianRandom(50, 400, 100, 100), false);
        currentTask = nextTask = getNextTask();
        if (currentTask != lastTask) {
            lastTask = currentTask;
            lastTaskChangeTime = System.currentTimeMillis();
        } else {
            long elapsed = System.currentTimeMillis() - lastTaskChangeTime;
            if (elapsed >= 600_000L) {
                log("No task change for 10 minutes (" + currentTask + ") - stopping script.");
                script.stop();
                return 1000;
            }
        }
        executeTask(nextTask);
        return nextTask == TaskType.IDLE
                ? RandomUtils.uniformRandom(900, 1100)
                : RandomUtils.uniformRandom(250, 350);
    }

    public void onNewFrame() {
        if (fletchingXP == null) {
            var xpTrackers = script.getXPTrackers();
            if (xpTrackers != null) {
                fletchingXP = xpTrackers.get(SkillType.FLETCHING);
                context.setFletchingXP(fletchingXP);
            }
        }
    }

    public void onStop() {
        synchronized (sessionLock) {
            if (sessionEnded) {
                return;
            }
            sessionEnded = true;
        }
    }

    private TaskType getNextTask() {
        if (firstRun) {
            firstRun = false;
        }
        if (taskSequence.isEmpty()) {
            buildTaskSequence();
        }
        if (currentTaskIndex >= taskSequence.size()) {
            currentTaskIndex = 0;
        }

        // Skip initial bank if we already have enough resources
        TaskType nextTask = taskSequence.get(currentTaskIndex);
        if (nextTask == TaskType.BANK && hasEnoughResourcesToStart()) {
            log("Skipping initial bank - already have enough resources.");
            ++currentTaskIndex;
            if (currentTaskIndex >= taskSequence.size()) {
                currentTaskIndex = 0;
            }
            return taskSequence.get(currentTaskIndex);
        }

        return nextTask;
    }

    private boolean hasEnoughResourcesToStart() {
        boolean hasKnife = hasKnife();

        if (usePreMadeItems) {
            int currentLogs = getItemCount(selectedLogId);
            int premadeItems = selectedPreMadeItemId > 0 ? getItemCount(selectedPreMadeItemId) : 0;
            return hasKnife && currentLogs >= REQUIRED_LOGS_PREMADE && premadeItems >= REQUIRED_PREMADE_ITEMS;
        } else {
            int currentBows = getItemCount(selectedProductId);
            int currentLogs = getItemCount(selectedLogId);
            boolean basketOk = !useLogBasket || context.isBasketRefilledThisLoop();
            return hasKnife && basketOk &&
                    currentBows >= REQUIRED_BOWS_AUBURNVALE &&
                    currentLogs >= REQUIRED_LOGS_AUBURNVALE;
        }
    }

    private void buildTaskSequence() {
        taskSequence.clear();
        currentTaskIndex = 0;

        // Main Loop from osrs wiki guide
        taskSequence.add(TaskType.BANK);           // 1. Bank at Auburnvale
        taskSequence.add(TaskType.TOTEM_1);        // 2. Totem 1
        taskSequence.add(TaskType.LOG_BALANCE);    // 3. Use log shortcut
        taskSequence.add(TaskType.TOTEM_2);        // 4. Totem 2
        taskSequence.add(TaskType.TOTEM_3);        // 5. Totem 3
        taskSequence.add(TaskType.TOTEM_4);        // 6. Totem 4
        taskSequence.add(TaskType.TOTEM_5);        // 7. Totem 5
        if (useLogBasket) {
            taskSequence.add(TaskType.EMPTY_BASKET); // 8. Empty basket and fletch (if basket enabled)
        } else {
            taskSequence.add(TaskType.BUFFALO);     // 8. Bank at Buffalo (if no basket)
        }
        taskSequence.add(TaskType.TOTEM_6);        // 9. Totem 6
        taskSequence.add(TaskType.TOTEM_7);        // 10. Totem 7
        taskSequence.add(TaskType.TOTEM_8);        // 11. Totem 8
        log("Sequence: " + taskSequence.size() + " steps.");
    }

    private void goToClosestBank() {
        if (script.getWorldPosition() == null) {
            currentTaskIndex = 0;
            return;
        }

        WorldPosition currentPos = script.getWorldPosition();
        WorldPosition auburnvaleBank = new WorldPosition(1416, 3353, 0);
        WorldPosition buffaloBank = new WorldPosition(1386, 3308, 0);

        double distToAuburnvale = currentPos.distanceTo((Position) auburnvaleBank);
        double distToBuffalo = currentPos.distanceTo((Position) buffaloBank);

        // Find which bank task index to use
        int bankTaskIndex = -1;
        int buffaloTaskIndex = -1;
        for (int i = 0; i < taskSequence.size(); i++) {
            if (taskSequence.get(i) == TaskType.BANK) {
                bankTaskIndex = i;
            }
            if (taskSequence.get(i) == TaskType.BUFFALO) {
                buffaloTaskIndex = i;
            }
        }

        // Set task index to closest bank
        if (distToBuffalo < distToAuburnvale && buffaloTaskIndex != -1) {
            currentTaskIndex = buffaloTaskIndex;
            log("Selected Buffalo Bank (closer: " + String.format("%.1f", distToBuffalo) + " vs " + String.format("%.1f", distToAuburnvale) + ")");
        } else if (bankTaskIndex != -1) {
            currentTaskIndex = bankTaskIndex;
            log("Selected Auburnvale Bank (closer: " + String.format("%.1f", distToAuburnvale) + " vs " + String.format("%.1f", distToBuffalo) + ")");
        } else {
            // Fallback to first bank task
            currentTaskIndex = 0;
        }
    }

    private void executeTask(TaskType task) {
        boolean completed = switch (task) {
            case BANK -> bankingHandler.handleBankArea(AreaDefinitions.BANK_AREA);
            case BUFFALO -> bankingHandler.handleBankArea(AreaDefinitions.BUFFALO_AREA);
            case TOTEM_1 -> totemHandler.handleTotemArea(AreaDefinitions.TOTEM_AREA_1);
            case TOTEM_2 -> totemHandler.handleTotemArea(AreaDefinitions.TOTEM_AREA_2);
            case TOTEM_3 -> totemHandler.handleTotemArea(AreaDefinitions.TOTEM_AREA_3);
            case TOTEM_4 -> totemHandler.handleTotemArea(AreaDefinitions.TOTEM_AREA_4);
            case TOTEM_5 -> totemHandler.handleTotemArea(AreaDefinitions.TOTEM_AREA_5);
            case EMPTY_BASKET -> fletchingHandler.processBasketAfterTotem5();
            case TOTEM_6 -> totemHandler.handleTotemArea(AreaDefinitions.TOTEM_AREA_6);
            case TOTEM_7 -> totemHandler.handleTotemArea(AreaDefinitions.TOTEM_AREA_7);
            case TOTEM_8 -> {
                boolean result = totemHandler.handleTotemArea(AreaDefinitions.TOTEM_AREA_8);
                if (result) {
                    updateTripCount(AreaDefinitions.TOTEM_AREA_8);
                    // Reset loop state for next loop
                    context.setBasketRefilledThisLoop(false);
                    context.setBasketEmptiedAfterTotem5(false);
                    context.setPostTotem5FletchingDone(false);
                    // Update banking handler with latest offerings count
                    bankingHandler.setOfferingsCount(offeringsCount);
                }
                yield result;
            }
            case LOG_BALANCE ->
                    totemHandler.walkToObject(LOG_BALANCE_START, "Log balance", "Walk-across", AreaDefinitions.LOG_BALANCE_AREA);
            default -> false;
        };

        if (completed) {
            advanceToNextTask(task);
        }
    }

    private void advanceToNextTask(TaskType task) {
        currentTaskIndex++;
        log("Task complete: " + task + "- Step:" + currentTaskIndex);
        totemHandler.setCurrentAction(ValeTotemsTotemHandler.TotemAction.NONE);
        spiritDetector.clearDetectedSpirits();
    }

    private void updateTripCount(Area area) {
        // Increment trip count when completing route
        if (area.equals(AreaDefinitions.TOTEM_AREA_8)) {
            if (shouldCollectOfferings) {
                // Just finished a loop where we collected offerings - reset for next cycle
                shouldCollectOfferings = false;
                tripCount = 0;
                tripsUntilOffering = BASE_TRIPS_UNTIL_OFFERING + RandomUtils.uniformRandom(0, RANDOM_TRIPS_RANGE);
                log("Offering collection cycle complete. Next offerings in " + tripsUntilOffering + " trips.");
            } else {
                // Normal trip completion - increment counter
                ++tripCount;
                log("Trip count (" + tripCount + "/" + tripsUntilOffering + ")");
                if (tripCount >= tripsUntilOffering) {
                    // Time to collect offerings in the next loop
                    shouldCollectOfferings = true;
                    log("Starting offering collection cycle - will collect after each totem this loop.");
                }
            }
        }
    }

    private void checkProgress() {
        if (fletchingXP == null) {
            return;
        }
        long currentXP = (long) fletchingXP.getXpGained();
        if (currentXP > 0) {
            lastProgressTime = System.currentTimeMillis();
        }
        long timeSinceLastProgress = System.currentTimeMillis() - lastProgressTime;
        if (timeSinceLastProgress > 480000L) {
            log("No progress detected in 8 mins. Stopping script.");
            script.stop();
        }
    }


    private int getItemCount(int itemId) {
        ItemGroupResult itemGroupResult = script.getWidgetManager().getInventory().search(Set.of(itemId));
        return itemGroupResult != null ? itemGroupResult.getAmount(new int[]{itemId}) : 0;
    }

    private boolean hasKnife() {
        if (fletchingKnifeEquipped) {
            return true;
        }
        ItemGroupResult knifeResult = script.getWidgetManager().getInventory().search(FLETCHING_KNIFE_IDS);
        if (knifeResult == null) {
            return false;
        }
        int totalKnives = 0;
        for (int knifeId : FLETCHING_KNIFE_IDS) {
            totalKnives += knifeResult.getAmount(new int[]{knifeId});
        }
        return totalKnives > 0;
    }

    public boolean canHopWorlds() {
        if (script.getWidgetManager().getBank().isVisible()) {
            return false;
        }
        WorldPosition currentPos = script.getWorldPosition();
        if (currentPos == null) {
            return false;
        }
        if (currentTask == null) {
            return true;
        }
        switch (currentTask) {
            case LOG_BALANCE: {
                return false;
            }
            case TOTEM_7: {
                return !AreaDefinitions.TOTEM_AREA_7.contains(currentPos);
            }
            case TOTEM_6: {
                return !AreaDefinitions.TOTEM_AREA_6.contains(currentPos);
            }
            case TOTEM_5: {
                return !AreaDefinitions.TOTEM_AREA_5.contains(currentPos);
            }
            case TOTEM_4: {
                return !AreaDefinitions.TOTEM_AREA_4.contains(currentPos);
            }
            case TOTEM_8: {
                return !AreaDefinitions.BANK_AREA.contains(currentPos);
            }
            case TOTEM_1: {
                return !AreaDefinitions.TOTEM_AREA_1.contains(currentPos);
            }
            case TOTEM_2: {
                return !AreaDefinitions.TOTEM_AREA_2.contains(currentPos);
            }
            case TOTEM_3: {
                return !AreaDefinitions.TOTEM_AREA_3.contains(currentPos);
            }
            default:
                break;
        }
        return true;
    }

    public boolean canAFK() {
        return canHopWorlds();
    }

    public int[] getRegionsToPrioritise() {
        return new int[]{5427, 5428, 5684, 5683};
    }

    public void onPaint(Canvas c) {

        long elapsed = System.currentTimeMillis() - startTime;
        if (elapsed <= 0) return;

        int x = 16;
        int y = 40;
        int w = 300;
        int headerH = 45;
        int bodyH = 200;
        int lineH = 16;

        int BG = new Color(12, 14, 20, 235).getRGB();
        int BORDER = new Color(100, 100, 110, 180).getRGB();
        int DIVIDER = new Color(255, 255, 255, 40).getRGB();

        c.fillRect(x, y, w, headerH + bodyH, BG, 1);
        c.drawRect(x, y, w, headerH + bodyH, BORDER);

        drawHeader(c, "Sainty", "Vale Totems Free", x + 14, y + 16);
        c.fillRect(x + 10, y + headerH, w - 20, 1, DIVIDER);

        java.awt.Font body = new java.awt.Font("Segoe UI", java.awt.Font.PLAIN, 13);
        int ty = y + headerH + 18;

        String step = currentTask != null ? currentTask.name() : "IDLE";
        c.drawText("State: " + step, x + 14, ty, 0xFFAAAAFF, body);
        ty += lineH;
        c.drawText("Mode: " + (mode != null ? mode : "Unknown"), x + 14, ty, 0xFFFFAA00, body);
        ty += lineH;
        c.drawText("Runtime: " + formatRuntime(elapsed),
                x + 14, ty, 0xFFDDDDDD, body);
        ty += lineH;
        c.drawText("Totems: " + totemsCompleted,
                x + 14, ty, 0xFF66FF66, body);
        ty += lineH;
        c.drawText("Totems/hr: " + (long) ((totemsCompleted * 3_600_000D) / elapsed),
                x + 14, ty, 0xFF66CCFF, body);
        ty += lineH;
        if (fletchingXP != null) {
            long xpGained = (long) fletchingXP.getXpGained();
            c.drawText("XP gained: " + formatNumber(xpGained),
                    x + 14, ty, 0xFF66FF66, body);
            ty += lineH;
            long xpPerHour = fletchingXP.getXpPerHour();
            c.drawText("XP/hr: " + formatNumber(xpPerHour),
                    x + 14, ty, 0xFF66CCFF, body);
            ty += lineH;
            c.drawText("Time to level: " + fletchingXP.timeToNextLevelString(),
                    x + 14, ty, 0xFF66CCFF, body);
            ty += lineH;
            // Calculate time to 99
            long currentXP = (long) fletchingXP.getXp();
            long xpTo99 = XP_TO_99 - currentXP;
            if (xpTo99 > 0 && xpPerHour > 0) {
                long totalMinutesTo99 = (xpTo99 * 60) / xpPerHour;
                long hoursTo99 = totalMinutesTo99 / 60;
                long minutesTo99 = totalMinutesTo99 % 60;
                String timeTo99Str = String.format("%d:%02d", hoursTo99, minutesTo99);
                c.drawText("Time to 99: " + timeTo99Str,
                        x + 14, ty, 0xFFFFD700, body);
                ty += lineH;
            } else if (xpTo99 > 0) {
                c.drawText("Time to 99: --:--",
                        x + 14, ty, 0xFFFFD700, body);
                ty += lineH;
            } else {
                c.drawText("Time to 99: Complete",
                        x + 14, ty, 0xFFFFD700, body);
                ty += lineH;
            }
        } else {
            c.drawText("XP gained: --",
                    x + 14, ty, 0xFF66FF66, body);
            ty += lineH;
            c.drawText("XP/hr: --",
                    x + 14, ty, 0xFF66CCFF, body);
            ty += lineH;
            c.drawText("Time to level: --",
                    x + 14, ty, 0xFF66CCFF, body);
            ty += lineH;
            c.drawText("Time to 99: --",
                    x + 14, ty, 0xFFFFD700, body);
            ty += lineH;
        }
        c.drawText("Offerings: " + offeringsCount,
                x + 14, ty, 0xFFFFD700, body);
        ty += lineH;
        // Show trips until next offering collection
        if (shouldCollectOfferings) {
            c.drawText("Collecting offerings this loop",
                    x + 14, ty, 0xFFFFD700, body);
        } else {
            int tripsRemaining = tripsUntilOffering - tripCount;
            c.drawText("Next offerings in: " + tripsRemaining + " trip" + (tripsRemaining != 1 ? "s" : ""),
                    x + 14, ty, 0xFFFFD700, body);
        }
        ty += lineH;
        String next = currentTask != null ? currentTask.name() : "None";
        c.drawText("Next: " + next,
                x + 14, ty, 0xFFCCCCCC, body);
    }

    private String formatRuntime(long milliseconds) {
        long totalSeconds = milliseconds / 1000;
        long hours = totalSeconds / 3600;
        long minutes = (totalSeconds % 3600) / 60;
        long seconds = totalSeconds % 60;
        return String.format("%02d:%02d:%02d", hours, minutes, seconds);
    }

    private String formatNumber(long number) {
        return String.format("%,d", number);
    }

    private void handleStuckInProblematicArea(WorldPosition currentPos) {
        // If we've been stuck in the same position for more than 3 seconds, try to escape
        if (lastStuckPosition != null && lastStuckPosition.equals(currentPos)) {
            long timeSinceLastChange = System.currentTimeMillis() - lastPositionChangeTime;
            if (timeSinceLastChange > 3000) {
                log("Detected stuck in problematic area - attempting to escape");
                // Walk to a safe position north of the problematic area
                WorldPosition escapePos = new WorldPosition(1365, 3360, 0);
                script.getWalker().walkTo((Position) escapePos);
                lastStuckPosition = null;
                lastPositionChangeTime = System.currentTimeMillis();
            }
        } else {
            lastStuckPosition = currentPos;
            lastPositionChangeTime = System.currentTimeMillis();
        }
    }

    private void drawHeader(Canvas c, String author, String title, int x, int y) {
        java.awt.Font a = new java.awt.Font("Segoe UI", java.awt.Font.PLAIN, 16);
        java.awt.Font t = new java.awt.Font("Segoe UI", java.awt.Font.BOLD, 20);
        c.drawText(author, x + 1, y + 1, 0xAA000000, a);
        c.drawText(title, x + 1, y + 26, 0xAA000000, t);
        c.drawText(author, x, y, 0xFFB0B0B0, a);
        c.drawText(title, x, y + 25, 0xFFD0D0D0, t);
        c.drawText(title, x - 1, y + 24, 0xFFFFFFFF, t);
    }

    public int getTotemsCompleted() {
        return totemsCompleted;
    }

    public int getOfferingsCount() {
        return offeringsCount;
    }

    public int getXP() {
        if (fletchingXP != null) {
            return (int) fletchingXP.getXpGained();
        }
        return 0;
    }


    enum TaskType {
        IDLE,
        BANK,
        BUFFALO,
        TOTEM_1,
        TOTEM_2,
        TOTEM_3,
        TOTEM_4,
        TOTEM_5,
        EMPTY_BASKET,
        TOTEM_6,
        TOTEM_7,
        TOTEM_8,
        LOG_BALANCE
    }

}

