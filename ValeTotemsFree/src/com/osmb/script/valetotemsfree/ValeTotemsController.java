package com.osmb.script.valetotemsfree;

import com.osmb.api.input.MenuEntry;
import com.osmb.api.input.MenuHook;
import com.osmb.api.item.ItemGroupResult;
import com.osmb.api.item.ItemSearchResult;
import com.osmb.api.location.area.Area;
import com.osmb.api.location.position.Position;
import com.osmb.api.location.position.types.WorldPosition;
import com.osmb.api.scene.RSObject;
import com.osmb.api.script.Script;
import com.osmb.api.shape.Polygon;
import com.osmb.api.shape.Rectangle;
import com.osmb.api.shape.Shape;
import com.osmb.api.trackers.experience.XPTracker;
import com.osmb.api.ui.chatbox.ChatboxFilterTab;
import com.osmb.api.ui.chatbox.dialogue.DialogueType;
import com.osmb.api.ui.component.tabs.skill.SkillType;
import com.osmb.api.utils.RandomUtils;
import com.osmb.api.utils.UIResultList;
import com.osmb.api.visual.SearchablePixel;
import com.osmb.api.visual.color.ColorModel;
import com.osmb.api.visual.color.tolerance.ToleranceComparator;
import com.osmb.api.visual.color.tolerance.impl.SingleThresholdComparator;
import com.osmb.api.visual.drawing.Canvas;
import com.osmb.api.visual.ocr.fonts.Font;
import com.osmb.api.walker.WalkConfig;
import com.osmb.script.valetotemsfree.ui.SettingsUI;
import com.osmb.script.valetotemsfree.util.AreaDefinitions;

import java.awt.*;
import java.util.*;
import java.util.List;
import java.util.function.Predicate;
import java.util.stream.Collectors;


public class ValeTotemsController {
    private final Script script;
    private boolean sessionEnded = false;
    private final Object sessionLock = new Object();
    private static final int FLETCHING_KNIFE_ID = 946;
    private static final int FLETCHING_KNIFE_ID_ALT = 31043; // Fletching knife
    private static final Set<Integer> FLETCHING_KNIFE_IDS = Set.of(FLETCHING_KNIFE_ID, FLETCHING_KNIFE_ID_ALT);
    private static final int OAK_LOG_ID = 1521; //fallback log
    private static final int OFFERING_ID = 31054;
    private static final int OFFERING_SITE_ID = 56056;
    private static final Set<Integer> LOG_BASKET_IDS = Set.of(28140, 28142, 28143, 28145);

    // Resource requirements
    private static final int REQUIRED_LOGS_FLETCHING = 26;
    private static final int REQUIRED_LOGS_PREMADE = 5;
    private static final int REQUIRED_PREMADE_ITEMS = 20;
    private static final int MIN_PRODUCT_COUNT = 4;
    private static final int MIN_PREMADE_COUNT = 4;
    private static final int MIN_LOG_COUNT = 1;
    private static final int MIN_LOGS_FOR_FLETCHING = 5;
    private static final int INVENTORY_SIZE = 27;
    private static final int KNIFE_WITHDRAW_COUNT = 1;

    // Auburnvale bank requirements (start of loop)
    private static final int REQUIRED_BOWS_AUBURNVALE = 20;
    private static final int REQUIRED_LOGS_AUBURNVALE = 5;

    // Post-Totem-5 requirements (for Totems 6-8)
    private static final int REQUIRED_BOWS_POST_TOTEM_5 = 12;
    private static final int REQUIRED_LOGS_POST_TOTEM_5 = 3;

    // Buffalo bank requirements (if basket disabled)
    private static final int REQUIRED_BOWS_BUFFALO = 12;

    // Timeouts and delays (milliseconds)
    private static final int DECORATION_COMPLETION_TIMEOUT = 10000;
    private static final int BUILD_CARVE_ACTION_TIMEOUT = 3000;
    private static final int BANK_WITHDRAW_TIMEOUT = 3000;

    private static final int DIALOGUE_TIMEOUT = 4000;
    private static final int FLETCH_COMPLETION_TIMEOUT = 30000; // Increased for larger amounts (e.g., 20 bows)

    private static final int WALK_COMPLETION_TIMEOUT = 20000;
    private static final int DEPOSIT_DELAY = 700;
    private static final int WALK_COMPLETION_DELAY = 600;
    private static final int FLETCH_INTERACTION_DELAY = 150;
    private static final int SPIRIT_DETECTION_DELAY = 80;
    private static final int SPIRIT_SELECTION_DELAY_SHORT = 80;
    private static final int SPIRIT_SELECTION_DELAY_MIN = 100;
    private static final int SPIRIT_SELECTION_DELAY_MAX = 300;
    private static final int HUMAN_DELAY = 45;
    private static final int KNIFE_WITHDRAW_DELAY = 800;


    // Distance thresholds
    private static final double TOTEM_INTERACTION_DISTANCE = 9;
    private static final double TOTEM_INTERACTION_DISTANCE_EXTENDED = 11;
    private static final double BANK_INTERACTION_DISTANCE = 15;
    private static final double TOTEM_POSITION_CHECK_DISTANCE = 2;
    private static final double BANK_APPROACH_DISTANCE = 9;


    // Spirit detection
    private static final int COLOR_TOLERANCE_HIGH = 60;
    private static final int COLOR_TOLERANCE_MEDIUM = 50;
    private static final int PIXEL_COUNT_THRESHOLD = 15;
    private static final int CHATBOX_LINES_TO_CHECK = 3;
    private static final int SPIRIT_TEXT_PADDING = 3;
    private static final int MIN_SPIRIT_TEXT_LENGTH = 4;
    private static final int ESTIMATED_FONT_HEIGHT = 6; // Approx font height for spirit text

    // Trip/Offering
    private static final int BASE_TRIPS_UNTIL_OFFERING = 7;
    private static final int RANDOM_TRIPS_RANGE = 3;

    // XP Tracking
    private static final long XP_TO_99 = 13_034_431L;

    // Geometry/UI
    private static final double POLYGON_RESIZE_FACTOR = 0.8;
    private static final int TILE_RANDOMISATION_RADIUS_SMALL = 1;
    private static final int TILE_RANDOMISATION_RADIUS_MEDIUM = 2;

    private int selectedLogId = OAK_LOG_ID; //fallback to oak if somehow none selected
    private int selectedProductId = 62; //fallback
    private boolean usePreMadeItems = false;
    private boolean useLogBasket = false;
    private int offeringsCount = 0;
    private boolean fletchingKnifeEquipped = false;
    private int selectedPreMadeItemId = -1;

    // Loop state tracking
    private boolean basketRefilledThisLoop = false;
    private boolean basketEmptiedAfterTotem5 = false;
    private boolean postTotem5FletchingDone = false;
    private int logsBeforeBasketEmpty = -1;
    private String selectedLogName = "Loading...";
    private String selectedItemName = "Loading...";
    private long startTime;
    private int totemsCompleted = 0;
    private int tripCount = 0;
    private int tripsUntilOffering = BASE_TRIPS_UNTIL_OFFERING;
    private boolean shouldCollectOfferings = false;
    private boolean ready = false;
    private long lastProgressTime = System.currentTimeMillis();
    private XPTracker fletchingXP;
    private final int SPIRIT_TEXT_COLOR = -16385800;
    private static final Map<String, Integer> SPIRIT_OPTION_MAP = Map.of("buffalo", 1, "jaguar", 2, "eagle", 3, "snake", 4, "scorpion", 5);
    private static final Set<String> VALID_SPIRITS = Set.of("jaguar", "buffalo", "snake", "eagle", "scorpion");
    private Set<String> detectedSpirits = new LinkedHashSet<>();
    // Log balance positions - between Totem 1 and Totem 2
    private final WorldPosition LOG_BALANCE_START = new WorldPosition(1453, 3335, 0);
    private boolean firstRun = true;
    private List<TaskType> taskSequence = new ArrayList<>();
    private int currentTaskIndex = 0;
    private TaskType currentTask = TaskType.IDLE;
    private TotemAction currentAction = TotemAction.NONE;
    private long lastPositionChangeTime = 0;
    private WorldPosition lastStuckPosition = null;
    private TaskType lastTask = null;
    private long lastTaskChangeTime = System.currentTimeMillis();
    private boolean spiritsReadyLogged = false;

    private enum TotemAction {
        NONE, BUILD, CARVE, DECORATE
    }

    public ValeTotemsController(Script script) {
        this.script = script;
        startTime = System.currentTimeMillis();
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

        // Reset loop state
        basketRefilledThisLoop = false;
        basketEmptiedAfterTotem5 = false;
        postTotem5FletchingDone = false;

        if (usePreMadeItems && settings.premadeItem != null) {
            selectedPreMadeItemId = settings.premadeItem.getItemId();
            selectedItemName = settings.premadeItem.getName();
            selectedProductId = -1;
        } else {
            selectedPreMadeItemId = -1;
            selectedProductId = settings.productType.getProductId(settings.logType);
            selectedItemName = settings.productType.name();
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

        script.pollFramesHuman(() -> true, HUMAN_DELAY, false);
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
        return nextTask == TaskType.IDLE ? 1000 : 300;
    }

    public void onNewFrame() {
        if (fletchingXP == null) {
            var xpTrackers = script.getXPTrackers();
            if (xpTrackers != null) {
                fletchingXP = xpTrackers.get(SkillType.FLETCHING);
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
            boolean basketOk = !useLogBasket || basketRefilledThisLoop;
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
            case BANK -> handleBankArea(AreaDefinitions.BANK_AREA);
            case BUFFALO -> handleBankArea(AreaDefinitions.BUFFALO_AREA);
            case TOTEM_1 -> handleTotemArea(AreaDefinitions.TOTEM_AREA_1);
            case TOTEM_2 -> handleTotemArea(AreaDefinitions.TOTEM_AREA_2);
            case TOTEM_3 -> handleTotemArea(AreaDefinitions.TOTEM_AREA_3);
            case TOTEM_4 -> handleTotemArea(AreaDefinitions.TOTEM_AREA_4);
            case TOTEM_5 -> handleTotemArea(AreaDefinitions.TOTEM_AREA_5);
            case EMPTY_BASKET -> processBasketAfterTotem5();
            case TOTEM_6 -> handleTotemArea(AreaDefinitions.TOTEM_AREA_6);
            case TOTEM_7 -> handleTotemArea(AreaDefinitions.TOTEM_AREA_7);
            case TOTEM_8 -> handleTotemArea(AreaDefinitions.TOTEM_AREA_8);
            case LOG_BALANCE ->
                    walkToObject(LOG_BALANCE_START, "Log balance", "Walk-across", AreaDefinitions.LOG_BALANCE_AREA);
            default -> false;
        };

        if (completed) {
            advanceToNextTask(task);
        }
    }

    private void advanceToNextTask(TaskType task) {
        currentTaskIndex++;
        log("Task complete: " + task + "- Step:" + currentTaskIndex);
        currentAction = TotemAction.NONE;
        detectedSpirits.clear();
    }

    private boolean handleTotemArea(Area area) {
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
            // This ensures we don't carry over spirits from previous totems accidentally
            detectedSpirits.clear();
            // Once in area and ready, scan for spirits (can cache them early to avoid dialogue box hiding them maybe)
            scanForSpirits();
        }

        if (script.getWidgetManager().getDialogue().isVisible()) {
            // Check for "no logs" message before handling spirit dialogue
            if (checkForNoLogsMessage()) {
                return handleNoLogsDetected("Detected 'no logs' message in dialogue");
            }
            handleSpiritDialogue();
            currentAction = TotemAction.NONE;
            return false;
        }


        if (handleBuildOrCarveAction()) {
            return false;
        }

        if (totemObject != null) {
            scanForSpirits();
        }

        if (totemObject != null) {
            return handleTotemInteraction(totemObject);
        }

        return false;
    }

    private boolean handleDecorationCompletion(Area area) {
        boolean decorationComplete = script.pollFramesUntil(() -> {
            UIResultList chatboxText = script.getWidgetManager().getChatbox().getText();
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
        }, DECORATION_COMPLETION_TIMEOUT, false, false);

        if (decorationComplete) {
            ++totemsCompleted;
            script.pollFramesHuman(() -> true, 150, false);

            // Claim offerings only during the offering collection phase (every X randomized loops)
            if (shouldCollectOfferings) {
                log("Collecting offerings after totem completion (totem " + totemsCompleted + ")");
                claimOffering(area);
            }

            // Update trip count after completing totem 8
            if (area.equals(AreaDefinitions.TOTEM_AREA_8)) {
                updateTripCount(area);
                // Reset loop state for next loop
                basketRefilledThisLoop = false;
                basketEmptiedAfterTotem5 = false;
                postTotem5FletchingDone = false;
            }
        }

        currentAction = TotemAction.NONE;
        ready = false;
        detectedSpirits.clear();
        return true;
    }

    private RSObject findTotemObject(Area area) {
        return script.getObjectManager().getRSObject(rSObject ->
                rSObject.getName() != null &&
                        rSObject.getName().toLowerCase().contains("totem") &&
                        area.contains(rSObject.getWorldPosition()));
    }

    private boolean walkToTotemAreaIfNeeded(Area area, RSObject totemObject) {
        boolean canInteractWithTotem = totemObject != null &&
                isInteractable(totemObject) &&
                totemObject.distance(script.getWorldPosition()) < TOTEM_INTERACTION_DISTANCE;

        if (area.contains(script.getWorldPosition()) || canInteractWithTotem) {
            return true;
        }

        ready = false;
        detectedSpirits.clear();

        // Avoid problematic area by using a waypoint if path would go through it
        WorldPosition currentPos = script.getWorldPosition();
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
                script.getWalker().walkTo((Position) waypoint);
                script.pollFramesHuman(() -> true, 250, false);
            }
        }

        WalkConfig.Builder builder = new WalkConfig.Builder().tileRandomisationRadius(TILE_RANDOMISATION_RADIUS_SMALL);
        builder.breakCondition(() -> {
            WorldPosition currentPos2 = script.getWorldPosition();
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

        if (script.getLastPositionChangeMillis() > 1500L) {
            script.getWalker().walkTo(targetPosition, builder.build());
        }
        return false;
    }

    private boolean checkAndInitializeResources(Area area) {
        int itemCount = getItemCount(selectedLogId);

        if (usePreMadeItems) {
            return checkPreMadeResources(area, itemCount);
        } else {
            return checkFletchingResources(area, itemCount);
        }
    }

    private boolean checkPreMadeResources(Area area, int itemCount) {
        int premadeCount = getItemCount(selectedPreMadeItemId);
        if (premadeCount < MIN_PREMADE_COUNT || itemCount < MIN_LOG_COUNT) {
            if (area.equals(AreaDefinitions.BANK_AREA) || area.equals(AreaDefinitions.BUFFALO_AREA)) {
                ready = false;
                return true;
            }
            log("Out of resources - Returning to closest bank.");
            goToClosestBank();
            return false;
        }
        ready = true;
        detectedSpirits.clear();
        return true;
    }

    private boolean checkFletchingResources(Area area, int itemCount) {
        // Skip resource check at totem sites - banking logic ensures we have correct resources
        if (!area.equals(AreaDefinitions.BANK_AREA) && !area.equals(AreaDefinitions.BUFFALO_AREA)) {
            ready = true;
            detectedSpirits.clear();
            return true;
        }

        // Only check resources when at bank areas
        int productCount = getItemCount(selectedProductId);
        int logCount = getItemCount(selectedLogId);

        if (productCount < MIN_PRODUCT_COUNT && logCount < MIN_LOG_COUNT) {
            ready = false;
            return true; // At bank, will handle banking
        }

        if (itemCount < MIN_LOG_COUNT && logCount < MIN_LOG_COUNT) {
            ready = false;
            return true;
        }
        ready = true;
        detectedSpirits.clear();
        return true;
    }

    private boolean handleBuildOrCarveAction() {
        if (currentAction == TotemAction.BUILD || currentAction == TotemAction.CARVE) {
            boolean dialogueVisible = script.pollFramesUntil(() ->
                    script.getWidgetManager().getDialogue().isVisible(), BUILD_CARVE_ACTION_TIMEOUT, false, false);
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
            script.getWalker().walkTo((Position) totemObject.getWorldPosition());
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
            if (totemObject.getWorldPosition().distanceTo((Position) script.getWorldPosition()) > TOTEM_POSITION_CHECK_DISTANCE) {
                script.getWalker().walkTo((Position) totemObject.getWorldPosition());
            } else {
                Polygon polygon = totemObject.getConvexHull();
                if (polygon != null) {
                    script.getFinger().tapGameScreen((Shape) polygon.getResized(POLYGON_RESIZE_FACTOR));
                }
            }
        }
    }

    private boolean walkToObject(WorldPosition targetPosition, String objectName, String action, Area targetArea) {
        RSObject targetObject = script.getObjectManager().getRSObject(rSObject -> rSObject.getName() != null && rSObject.getName().equalsIgnoreCase(objectName) && rSObject.getWorldPosition().distanceTo((Position) targetPosition) == 0.0);

        // For log balance, check if we've successfully crossed to the other side (ends at 1453, 3329)
        if (objectName.equalsIgnoreCase("Log balance") && targetArea.equals(AreaDefinitions.LOG_BALANCE_AREA)) {
            WorldPosition currentPos = script.getWorldPosition();
            WorldPosition logBalanceEnd = new WorldPosition(1453, 3329, 0);
            // If we're at or near the end position, we've crossed
            if (currentPos.distanceTo((Position) logBalanceEnd) < 3.0 || AreaDefinitions.TOTEM_AREA_2.contains(currentPos)) {
                return true;
            }
        } else {
            if (targetArea.contains(script.getWorldPosition())) {
                if (targetObject != null && isInteractable(targetObject)) {
                } else {
                    return true;
                }
            }
        }

        if (targetObject != null && (isInteractable(targetObject) || targetObject.distance(script.getWorldPosition()) < TOTEM_INTERACTION_DISTANCE_EXTENDED)) {
            if (targetObject.interact(new String[]{action})) {
                boolean success = script.pollFramesUntil(() -> {
                    // For log balance, check if we're at the end position (1453, 3329)
                    if (objectName.equalsIgnoreCase("Log balance") && targetArea.equals(AreaDefinitions.LOG_BALANCE_AREA)) {
                        WorldPosition pos = script.getWorldPosition();
                        WorldPosition logBalanceEnd = new WorldPosition(1453, 3329, 0);
                        return pos.distanceTo((Position) logBalanceEnd) < 3.0 || AreaDefinitions.TOTEM_AREA_2.contains(pos);
                    }
                    RSObject objAfter = script.getObjectManager().getRSObject(rSObject -> rSObject.getName() != null && rSObject.getName().equalsIgnoreCase(objectName) && rSObject.getWorldPosition().distanceTo((Position) targetPosition) == 0.0);
                    return targetArea.contains(script.getWorldPosition()) && (objAfter == null || !isInteractable(objAfter));
                }, WALK_COMPLETION_TIMEOUT, false, false);
                if (success) {
                    script.pollFramesHuman(() -> true, WALK_COMPLETION_DELAY, false);
                    return true;
                }
            }
        } else {
            WalkConfig.Builder builder = new WalkConfig.Builder().tileRandomisationRadius(TILE_RANDOMISATION_RADIUS_SMALL);
            builder.breakCondition(() -> {
                RSObject obj = script.getObjectManager().getRSObject(rSObject -> rSObject.getName() != null && rSObject.getName().equalsIgnoreCase(objectName) && rSObject.getWorldPosition().distanceTo((Position) targetPosition) == 0.0);
                return isInteractable(obj);
            });
            script.getWalker().walkTo((Position) targetPosition, builder.build());
        }
        return false;
    }

    private boolean handleBankArea(Area area) {
        // Check if we can skip banking entirely (have enough resources to fletch remaining bows)
        if (canSkipBanking(area)) {
            if (script.getWidgetManager().getBank().isVisible()) {
                script.getWidgetManager().getBank().close();
            }

            // If we need to fletch more bows but have enough logs, fletch now (without opening bank)
            if (!usePreMadeItems) {
                if (area.equals(AreaDefinitions.BANK_AREA)) {
                    int currentBows = getItemCount(selectedProductId);
                    if (currentBows < REQUIRED_BOWS_AUBURNVALE) {
                        log("Skipping bank - fletching " + (REQUIRED_BOWS_AUBURNVALE - currentBows) + " more bows with existing logs");
                        if (!performFletching(REQUIRED_BOWS_AUBURNVALE)) {
                            log("Fletching failed when skipping bank");
                            return false;
                        }
                    }
                } else if (area.equals(AreaDefinitions.BUFFALO_AREA) && !useLogBasket) {
                    int currentBows = getItemCount(selectedProductId);
                    if (currentBows < REQUIRED_BOWS_BUFFALO) {
                        log("Skipping bank - fletching " + (REQUIRED_BOWS_BUFFALO - currentBows) + " more bows with existing logs");
                        if (!performFletching(REQUIRED_BOWS_BUFFALO)) {
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

        if (usePreMadeItems) {
            return handlePreMadeItemsBanking(area);
        } else {
            // Different banking logic for Auburnvale vs Buffalo
            if (area.equals(AreaDefinitions.BANK_AREA)) {
                return handleAuburnvaleBanking(area, bankObject);
            } else if (area.equals(AreaDefinitions.BUFFALO_AREA)) {
                if (useLogBasket) {
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

        if (usePreMadeItems) {
            return false; // Always need to bank for premade items
        }

        if (area.equals(AreaDefinitions.BANK_AREA)) {
            int bows = getItemCount(selectedProductId);
            int logs = getItemCount(selectedLogId);
            boolean basketOk = !useLogBasket || basketRefilledThisLoop;

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
            if (useLogBasket) {
                return true; // Skip Buffalo if basket enabled
            }
            int bows = getItemCount(selectedProductId);
            int logs = getItemCount(selectedLogId);

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
        return script.getObjectManager().getObjects(predicate).stream()
                .min(Comparator.comparingDouble(rSObject ->
                        rSObject.getWorldPosition().distanceTo((Position) script.getWorldPosition())))
                .orElse(null);
    }


    private boolean openBankIfNeeded(Area area, RSObject bankObject) {
        if (script.getWidgetManager().getBank().isVisible()) {
            return true;
        }

        if (bankObject != null && isInteractable(bankObject) && bankObject.distance(script.getWorldPosition()) < BANK_INTERACTION_DISTANCE) {
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

            if (interacted && script.pollFramesUntil(() -> script.getWidgetManager().getBank().isVisible(), DIALOGUE_TIMEOUT, false, false)) {
                // Count offerings when depositing
                int offeringsToDeposit = getItemCount(OFFERING_ID);
                if (offeringsToDeposit > 0) {
                    offeringsCount += offeringsToDeposit;
                    log("Depositing " + offeringsToDeposit + " offerings. Total collected: " + offeringsCount);
                }
                return true;
            }
            return false;
        }

        if (area.contains(script.getWorldPosition())) {
            // Check if stuck in problematic area
            WorldPosition currentPos = script.getWorldPosition();
            if (currentPos != null && AreaDefinitions.PROBLEMATIC_WALK_AREA.contains(currentPos)) {
                // Walk to a safe position first (north of problematic area, towards bank)
                WorldPosition waypoint = new WorldPosition(1365, 3360, 0);
                script.getWalker().walkTo((Position) waypoint);
                script.pollFramesHuman(() -> true, 250, false);
                return false;
            }
            if (bankObject != null) {
                script.getWalker().walkTo((Position) bankObject.getWorldPosition());
            } else {
                script.getWalker().walkTo((Position) area.getRandomPosition());
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
            RSObject obj = script.getObjectManager().getObjects(predicate).stream()
                    .min(Comparator.comparingDouble(rSObject ->
                            rSObject.getWorldPosition().distanceTo((Position) script.getWorldPosition())))
                    .orElse(null);
            return isInteractable(obj) && obj.distance(script.getWorldPosition()) < BANK_APPROACH_DISTANCE;
        });
        script.getWalker().walkTo((Position) area.getRandomPosition(), builder.build());
        return false;
    }

    private void depositUnwantedItems(Area area) {
        // Deposit items individually to ensure we never deposit the knife or basket
        boolean hadKnifeBefore = hasKnife();
        int offeringCount = getItemCount(OFFERING_ID);
        if (offeringCount > 0) {
            script.getWidgetManager().getBank().deposit(OFFERING_ID, offeringCount);
        }

        if (usePreMadeItems) {
            // Premade mode: deposit all logs and premade items
            int logCount = getItemCount(selectedLogId);
            if (logCount > 0) {
                script.getWidgetManager().getBank().deposit(selectedLogId, logCount);
            }
            if (selectedPreMadeItemId > 0) {
                int premadeCount = getItemCount(selectedPreMadeItemId);
                if (premadeCount > 0) {
                    script.getWidgetManager().getBank().deposit(selectedPreMadeItemId, premadeCount);
                }
            }
        } else {
            // Fletching mode: only deposit excess products, never deposit logs
            int productCount = getItemCount(selectedProductId);
            int maxProducts = REQUIRED_BOWS_AUBURNVALE; // Default to Auburnvale requirement
            if (area.equals(AreaDefinitions.BUFFALO_AREA)) {
                maxProducts = REQUIRED_BOWS_BUFFALO;
            }

            if (productCount > maxProducts) {
                int excess = productCount - maxProducts;
                script.getWidgetManager().getBank().deposit(selectedProductId, excess);
            }
            // Never deposit logs
        }

        script.pollFramesHuman(() -> true, DEPOSIT_DELAY, false);

        // Knife sometimes accidentally deposited? Should prevent
        if (hadKnifeBefore && !hasKnife()) {
            log("WARNING: Knife was accidentally deposited! Attempting to withdraw...");
            // Try to withdraw knife back (tries both knife types)
            ItemGroupResult knifeResult = script.getWidgetManager().getBank().search(FLETCHING_KNIFE_IDS);
            if (knifeResult != null) {
                for (int knifeId : FLETCHING_KNIFE_IDS) {
                    if (knifeResult.getAmount(new int[]{knifeId}) > 0) {
                        script.getWidgetManager().getBank().withdraw(knifeId, KNIFE_WITHDRAW_COUNT);
                        script.pollFramesHuman(() -> true, KNIFE_WITHDRAW_DELAY, false);
                        break;
                    }
                }
            }
        }

        // Safety check: verify basket is still in inventory (if enabled)
        if (useLogBasket && !usePreMadeItems) {
            ItemGroupResult basketSearch = script.getWidgetManager().getInventory().search(LOG_BASKET_IDS);
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
                ItemGroupResult bankBasketSearch = script.getWidgetManager().getBank().search(LOG_BASKET_IDS);
                if (bankBasketSearch != null) {
                    for (int basketId : LOG_BASKET_IDS) {
                        if (bankBasketSearch.getAmount(new int[]{basketId}) > 0) {
                            script.getWidgetManager().getBank().withdraw(basketId, 1);
                            script.pollFramesHuman(() -> true, KNIFE_WITHDRAW_DELAY, false);
                            break;
                        }
                    }
                }
            }
        }

    }

    private boolean withdrawKnifeIfNeeded() {
        if (fletchingKnifeEquipped || hasKnife()) {
            return true;
        }

        ItemGroupResult knifeResult = script.getWidgetManager().getBank().search(FLETCHING_KNIFE_IDS);
        if (knifeResult == null) {
            log("No Knife in bank.");
            script.stop();
            return false;
        }

        for (int knifeId : FLETCHING_KNIFE_IDS) {
            if (knifeResult.getAmount(new int[]{knifeId}) > 0) {
                script.getWidgetManager().getBank().withdraw(knifeId, KNIFE_WITHDRAW_COUNT);
                script.pollFramesHuman(() -> true, KNIFE_WITHDRAW_DELAY, false);
                return true;
            }
        }

        log("No Knife in bank.");
        script.stop();
        return false;
    }

    private boolean handlePreMadeItemsBanking(Area area) {
        int logs = getItemCount(selectedLogId);

        // Adjust logs to required amount
        if (logs < REQUIRED_LOGS_PREMADE) {
            if (!withdrawItem(selectedLogId, REQUIRED_LOGS_PREMADE - logs)) {
                return true;
            }
        } else if (logs > REQUIRED_LOGS_PREMADE) {
            script.getWidgetManager().getBank().deposit(selectedLogId, logs - REQUIRED_LOGS_PREMADE);
            script.pollFramesUntil(() -> getItemCount(selectedLogId) <= REQUIRED_LOGS_PREMADE, BANK_WITHDRAW_TIMEOUT, false, false);
        }

        // Withdraw premade items if needed
        if (selectedPreMadeItemId > 0 && getItemCount(selectedPreMadeItemId) < REQUIRED_PREMADE_ITEMS) {
            int needed = REQUIRED_PREMADE_ITEMS - getItemCount(selectedPreMadeItemId);
            if (!withdrawItem(selectedPreMadeItemId, needed)) {
                return true;
            }
            script.pollFramesUntil(() -> getItemCount(selectedPreMadeItemId) >= REQUIRED_PREMADE_ITEMS, BANK_WITHDRAW_TIMEOUT, false, false);
        }

        // Verify we have everything needed
        if (getItemCount(selectedLogId) >= REQUIRED_LOGS_PREMADE &&
                getItemCount(selectedPreMadeItemId) >= REQUIRED_PREMADE_ITEMS) {
            return true;
        }
        return false;
    }

    private boolean withdrawItem(int itemId, int amount) {
        ItemGroupResult result = script.getWidgetManager().getBank().search(Set.of(itemId));
        if (result == null || result.getAmount(new int[]{itemId}) <= 0) {
            log("No " + (itemId == selectedLogId ? "logs" : "items") + " in bank.");
            script.stop();
            return false;
        }
        script.getWidgetManager().getBank().withdraw(itemId, amount);
        return true;
    }

    private void depositAllExceptEssentials() {
        if (!script.getWidgetManager().getBank().isVisible()) {
            return;
        }
        Set<Integer> keep = new HashSet<>();
        keep.add(selectedLogId);
        keep.add(selectedProductId);
        if (selectedPreMadeItemId > 0) {
            keep.add(selectedPreMadeItemId);
        }
        keep.addAll(FLETCHING_KNIFE_IDS);
        keep.addAll(LOG_BASKET_IDS);
        script.getWidgetManager().getBank().depositAll(keep);
        script.pollFramesHuman(() -> true, DEPOSIT_DELAY, false);
    }

    private boolean handleAuburnvaleBanking(Area area, RSObject bankObject) {
        if (useLogBasket && !basketRefilledThisLoop) {
            if (!fillBasketWithLogs()) {
                return false;
            }
            basketRefilledThisLoop = true;
            script.pollFramesHuman(() -> true, 50, false);
        }

        int currentBows = getItemCount(selectedProductId);
        int currentLogs = getItemCount(selectedLogId);
        log("handleAuburnvaleBanking: Starting with " + currentBows + " bows, " + currentLogs + " logs");

        boolean hasEnoughBowsToLeave = currentBows >= REQUIRED_BOWS_AUBURNVALE;
        if (hasEnoughBowsToLeave && currentLogs >= REQUIRED_LOGS_AUBURNVALE && hasKnife() && (!useLogBasket || basketRefilledThisLoop)) {
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
                ItemGroupResult logResult = script.getWidgetManager().getBank().search(Set.of(selectedLogId));
                if (logResult == null || logResult.getAmount(new int[]{selectedLogId}) <= 0) {
                    log("No logs in bank.");
                    script.stop();
                    return false;
                }
                script.getWidgetManager().getBank().withdraw(selectedLogId, INVENTORY_SIZE);
                boolean logsWithdrawn = script.pollFramesUntil(() -> getItemCount(selectedLogId) >= totalLogsNeeded, BANK_WITHDRAW_TIMEOUT, false, false);
                if (!logsWithdrawn) {
                    log("Failed to withdraw enough logs for fletching - timeout. Depositing non-essentials and retrying.");
                    depositAllExceptEssentials();
                    script.getWidgetManager().getBank().withdraw(selectedLogId, INVENTORY_SIZE);
                    logsWithdrawn = script.pollFramesUntil(() -> getItemCount(selectedLogId) >= totalLogsNeeded, BANK_WITHDRAW_TIMEOUT, false, false);
                    if (!logsWithdrawn) {
                        log("Failed to withdraw enough logs after cleanup - timeout");
                        return false;
                    }
                }
                currentLogs = getItemCount(selectedLogId);
            }

            log("Fletching at bank: have " + currentBows + " bows, " + currentLogs + " logs, need " + REQUIRED_BOWS_AUBURNVALE + " bows");
            int bowsBeforeFletching = currentBows;

            // Ensure we have logs to fletch
            if (currentLogs < bowsNeeded) {
                log("ERROR: Not enough logs to fletch! Have " + currentLogs + " logs, need " + bowsNeeded + " for fletching");
                return false;
            }

            if (!performFletching(REQUIRED_BOWS_AUBURNVALE)) {
                log("Fletching failed - returning false");
                return false;
            }

            currentBows = getItemCount(selectedProductId);
            currentLogs = getItemCount(selectedLogId);
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
            if (!script.getWidgetManager().getBank().isVisible()) {
                // Find bank object if we don't have a reference
                if (bankObject == null) {
                    bankObject = findBankObject(area);
                }

                if (bankObject != null && isInteractable(bankObject) && bankObject.distance(script.getWorldPosition()) < BANK_INTERACTION_DISTANCE) {
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
                        boolean bankVisible = script.pollFramesUntil(() -> script.getWidgetManager().getBank().isVisible(), DIALOGUE_TIMEOUT, false, false);
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

            ItemGroupResult logResult = script.getWidgetManager().getBank().search(Set.of(selectedLogId));
            if (logResult == null || logResult.getAmount(new int[]{selectedLogId}) <= 0) {
                log("No logs in bank.");
                script.stop();
                return false;
            }
            script.getWidgetManager().getBank().withdraw(selectedLogId, INVENTORY_SIZE);
            boolean additionalLogsWithdrawn = script.pollFramesUntil(() -> getItemCount(selectedLogId) >= REQUIRED_LOGS_AUBURNVALE, BANK_WITHDRAW_TIMEOUT, false, false);
            if (!additionalLogsWithdrawn) {
                log("Failed to withdraw additional logs - timeout waiting for logs");
                return false;
            }
        }

        currentBows = getItemCount(selectedProductId);
        currentLogs = getItemCount(selectedLogId);
        boolean knifePresent = hasKnife();
        boolean basketReady = !useLogBasket || basketRefilledThisLoop;


        boolean hasEnoughBows = currentBows >= REQUIRED_BOWS_AUBURNVALE;
        log("Final check: " + currentBows + " bows, " + currentLogs + " logs, knife: " + knifePresent + ", basket: " + basketReady);
        return hasEnoughBows && currentLogs >= REQUIRED_LOGS_AUBURNVALE && knifePresent && basketReady;
    }

    private boolean handleBuffaloBanking(Area area, RSObject bankObject) {
        if (useLogBasket) {
            return true;
        }

        int currentBows = getItemCount(selectedProductId);
        int currentLogs = getItemCount(selectedLogId);

        // If we already have enough bows AND minimum logs, we're ready
        if (currentBows >= REQUIRED_BOWS_BUFFALO && currentLogs >= REQUIRED_LOGS_AUBURNVALE) {
            return true;
        }

        // Need to fletch more bows
        int bowsNeeded = REQUIRED_BOWS_BUFFALO - currentBows;
        // Calculate logs needed: 1 log = 1 bow
        int logsNeededForBows = bowsNeeded;

        if (currentLogs < logsNeededForBows) {
            ItemGroupResult logResult = script.getWidgetManager().getBank().search(Set.of(selectedLogId));
            if (logResult == null || logResult.getAmount(new int[]{selectedLogId}) <= 0) {
                log("No logs in bank.");
                script.stop();
                return false;
            }
            script.getWidgetManager().getBank().withdraw(selectedLogId, INVENTORY_SIZE);
            boolean logsWithdrawn = script.pollFramesUntil(() -> getItemCount(selectedLogId) >= logsNeededForBows, BANK_WITHDRAW_TIMEOUT, false, false);
            if (!logsWithdrawn) {
                log("Failed to withdraw logs at Buffalo bank");
                return false;
            }
            currentLogs = getItemCount(selectedLogId);
        }

        if (!performFletching(REQUIRED_BOWS_BUFFALO)) {
            return false;
        }
        currentBows = getItemCount(selectedProductId);
        currentLogs = getItemCount(selectedLogId);

        if (currentBows < REQUIRED_BOWS_BUFFALO) {
            log("Fletching incomplete at Buffalo - only have " + currentBows + " bows");
            return false;
        }

        // Ensure we have minimum logs before leaving
        if (currentLogs < REQUIRED_LOGS_AUBURNVALE) {
            log("Not enough logs at Buffalo - have " + currentLogs + ", need " + REQUIRED_LOGS_AUBURNVALE);
            // Withdraw more logs to meet minimum requirement
            int additionalLogsNeeded = REQUIRED_LOGS_AUBURNVALE - currentLogs;
            ItemGroupResult logResult = script.getWidgetManager().getBank().search(Set.of(selectedLogId));
            if (logResult == null || logResult.getAmount(new int[]{selectedLogId}) <= 0) {
                log("No logs in bank.");
                script.stop();
                return false;
            }
            script.getWidgetManager().getBank().withdraw(selectedLogId, INVENTORY_SIZE);
            boolean logsWithdrawn = script.pollFramesUntil(() -> getItemCount(selectedLogId) >= REQUIRED_LOGS_AUBURNVALE, BANK_WITHDRAW_TIMEOUT, false, false);
            if (!logsWithdrawn) {
                log("Failed to withdraw additional logs at Buffalo bank");
                return false;
            }
            currentLogs = getItemCount(selectedLogId);
        }

        // Final check: must have both required bows and minimum logs
        return currentBows >= REQUIRED_BOWS_BUFFALO && currentLogs >= REQUIRED_LOGS_AUBURNVALE;
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

    // Perform fletching at bank or post-Totem-5 location
    private boolean performFletching(int requiredBows) {
        if (script.getWidgetManager().getBank().isVisible()) {
            script.getWidgetManager().getBank().close();
            script.pollFramesHuman(() -> true, 100, false);
        }

        script.getWidgetManager().getInventory().open();
        ItemGroupResult knifeSearch = script.getWidgetManager().getInventory().search(FLETCHING_KNIFE_IDS);
        ItemGroupResult logSearch = script.getWidgetManager().getInventory().search(Set.of(selectedLogId));
        if (knifeSearch == null || logSearch == null) {
            return false;
        }

        ItemSearchResult knifeItem = null;
        for (int knifeId : FLETCHING_KNIFE_IDS) {
            knifeItem = knifeSearch.getItem(new int[]{knifeId});
            if (knifeItem != null) break;
        }
        ItemSearchResult logItem = logSearch.getItem(new int[]{selectedLogId});
        if (knifeItem == null || logItem == null) {
            return false;
        }

        script.getFinger().tap((Shape) knifeItem.getBounds());
        script.pollFramesHuman(() -> true, Math.min(FLETCH_INTERACTION_DELAY, 80), false);
        script.getFinger().tap((Shape) logItem.getBounds());

        boolean dialogueAppeared = script.pollFramesUntil(() -> script.getWidgetManager().getDialogue().isVisible(), DIALOGUE_TIMEOUT, false, false);
        if (!dialogueAppeared) {
            log("Fletching dialogue did not appear");
            return false;
        }

        if (script.getWidgetManager().getDialogue().selectItem(new int[]{selectedProductId})) {
            int startingBows = getItemCount(selectedProductId);
            int bowsToFletch = requiredBows - startingBows;
            // Calculate timeout based on number of bows to fletch (roughly 1 second per bow)
            int timeout = Math.max(FLETCH_COMPLETION_TIMEOUT, bowsToFletch * 1000 + 5000);
            log("Fletching " + bowsToFletch + " bows, timeout: " + timeout + "ms");
            boolean fletchingComplete = script.pollFramesUntil(() -> getItemCount(selectedProductId) >= requiredBows, timeout, false, false);
            int finalBows = getItemCount(selectedProductId);
            if (!fletchingComplete && finalBows == startingBows) {
                log("Fletching may have failed - no progress detected");
            } else if (!fletchingComplete) {
                log("Fletching incomplete - timeout reached. Have " + finalBows + " bows, need " + requiredBows);
            }
            return fletchingComplete || finalBows >= requiredBows;
        }
        return false;
    }

    // Helper to find log basket in inventory
    private ItemSearchResult findBasketInInventory() {
        ItemGroupResult basketResult = script.getWidgetManager().getInventory().search(LOG_BASKET_IDS);
        if (basketResult == null) {
            log("Log basket not found in inventory!");
            return null;
        }

        for (int basketId : LOG_BASKET_IDS) {
            ItemSearchResult item = basketResult.getItem(new int[]{basketId});
            if (item != null) {
                return item;
            }
        }

        log("Could not find basket item!");
        return null;
    }

    // Fill log basket with logs from inventory at Auburnvale
    private boolean fillBasketWithLogs() {
        ItemSearchResult basketItem = findBasketInInventory();
        if (basketItem == null) {
            return false;
        }

        int availableLogs = getItemCount(selectedLogId);
        if (availableLogs < REQUIRED_LOGS_FLETCHING) {
            if (!withdrawItem(selectedLogId, INVENTORY_SIZE)) {
                return false;
            }
            script.pollFramesUntil(() -> getItemCount(selectedLogId) >= REQUIRED_LOGS_FLETCHING, BANK_WITHDRAW_TIMEOUT, false, false);
        }

        if (script.getWidgetManager().getBank().isVisible()) {
            script.getWidgetManager().getBank().close();
            script.pollFramesHuman(() -> true, 200, false);
        }

        int initialLogCount = getItemCount(selectedLogId);
        if (basketItem.interact("Fill")) {
            boolean logsTransferred = script.pollFramesUntil(() -> getItemCount(selectedLogId) < initialLogCount, 3000, false, false);
            int afterFillCount = getItemCount(selectedLogId);
            if (!logsTransferred && afterFillCount == initialLogCount) {
                // don't retry indefinitely and time out every 3s (safeguard for full basket at start).
                log("Basket already full or no logs transferred - continuing");
                reopenBankAfterBasketFill();
                return true;
            }
            script.pollFramesHuman(() -> true, 150 + RandomUtils.weightedRandom(0, 100, 0.0017), false);
            reopenBankAfterBasketFill();
            return logsTransferred || getItemCount(selectedLogId) < initialLogCount;
        }
        return false;
    }

    private void reopenBankAfterBasketFill() {
        RSObject bankObj = findBankObject(AreaDefinitions.BANK_AREA);
        if (bankObj != null) {
            MenuHook bankMenuHook = list -> {
                if (list == null || list.isEmpty()) return null;
                for (MenuEntry entry : list) {
                    String action = entry.getAction();
                    if (action != null) {
                        String lower = action.toLowerCase();
                        if (lower.contains("bank") || lower.contains("use")) {
                            return entry;
                        }
                    }
                }
                return null;
            };
            if (bankObj.interact(bankMenuHook)) {
                script.pollFramesUntil(() -> script.getWidgetManager().getBank().isVisible(), DIALOGUE_TIMEOUT, false, false);
            }
        }
    }

    // Process basket emptying and fletching after Totem 5
    private boolean processBasketAfterTotem5() {
        if (!useLogBasket) {
            return true;
        }

        if (basketEmptiedAfterTotem5 && postTotem5FletchingDone) {
            return true;
        }

        ItemSearchResult basketItem = findBasketInInventory();
        if (basketItem == null) {
            return false;
        }

        if (script.getWidgetManager().getDialogue().isVisible()) {
            DialogueType dialogueType = script.getWidgetManager().getDialogue().getDialogueType();
            if (dialogueType == DialogueType.TAP_HERE_TO_CONTINUE) {
                if (!basketEmptiedAfterTotem5 && logsBeforeBasketEmpty >= 0) {
                    int currentLogs = getItemCount(selectedLogId);
                    if (currentLogs == logsBeforeBasketEmpty) {
                        // Basket was empty or no logs transferred; treat as emptied to prevent looping
                        basketEmptiedAfterTotem5 = true;
                    } else if (currentLogs > logsBeforeBasketEmpty) {
                        basketEmptiedAfterTotem5 = true;
                    }
                    logsBeforeBasketEmpty = -1;
                }
                script.getWidgetManager().getDialogue().continueChatDialogue();
                script.pollFramesHuman(() -> true, 200, false);
                return false;
            }
            if (dialogueType == DialogueType.TEXT_OPTION) {
                int logsBeforeEmpty = getItemCount(selectedLogId);
                script.getWidgetManager().getDialogue().selectOption("Yes");
                boolean logsReceived = script.pollFramesUntil(() -> getItemCount(selectedLogId) > logsBeforeEmpty, 3000, false, false);
                if (logsReceived || getItemCount(selectedLogId) > logsBeforeEmpty) {
                    basketEmptiedAfterTotem5 = true;
                }
                logsBeforeBasketEmpty = -1;
                return false;
            }
        }

        if (!basketEmptiedAfterTotem5) {
            if (basketItem.interact("Empty") || basketItem.interact("Check")) {
                logsBeforeBasketEmpty = getItemCount(selectedLogId);
                script.pollFramesUntil(() -> script.getWidgetManager().getDialogue().isVisible(), 3000, false, false);
                return false;
            }
        }

        if (basketEmptiedAfterTotem5 && !postTotem5FletchingDone) {
            int currentBows = getItemCount(selectedProductId);
            int currentLogs = getItemCount(selectedLogId);

            if (currentBows < REQUIRED_BOWS_POST_TOTEM_5 && currentLogs >= MIN_LOGS_FOR_FLETCHING) {
                if (performFletching(REQUIRED_BOWS_POST_TOTEM_5)) {
                    int finalBows = getItemCount(selectedProductId);
                    if (finalBows >= REQUIRED_BOWS_POST_TOTEM_5) {
                        postTotem5FletchingDone = true;
                    } else {
                        log("Post-Totem-5 fletching incomplete - have " + finalBows + ", need " + REQUIRED_BOWS_POST_TOTEM_5);
                    }
                }
                return false;
            } else if (currentBows >= REQUIRED_BOWS_POST_TOTEM_5 && currentLogs >= REQUIRED_LOGS_POST_TOTEM_5) {
                postTotem5FletchingDone = true;
                return true;
            }
        }

        return false;
    }

    private boolean isSpiritOptionSelected(Rectangle rectangle) {
        SearchablePixel searchablePixel = new SearchablePixel(new Color(180, 50, 50).getRGB(), (ToleranceComparator) new SingleThresholdComparator(COLOR_TOLERANCE_MEDIUM), ColorModel.RGB);
        return script.getPixelAnalyzer().findPixels((Shape) rectangle, new SearchablePixel[]{searchablePixel}).size() > PIXEL_COUNT_THRESHOLD;
    }

    private void handleSpiritDialogue() {
        if (detectedSpirits.size() < 3) {
            spiritsReadyLogged = false;
            scanForSpirits();
        }
        if (detectedSpirits.size() >= 3) {
            if (!spiritsReadyLogged) {
                log("All 3 Spirits found: " + String.valueOf(detectedSpirits));
                log("Human delay before selecting...");
                spiritsReadyLogged = true;
            }
            script.pollFramesHuman(() -> true, SPIRIT_SELECTION_DELAY_MIN + RandomUtils.weightedRandom(0, SPIRIT_SELECTION_DELAY_MAX - SPIRIT_SELECTION_DELAY_MIN, 0.0017), false);
            for (String spirit : detectedSpirits) {
                if (script.getWidgetManager().getDialogue().isVisible()) {
                    Integer optionIndexObj = SPIRIT_OPTION_MAP.get(spirit.toLowerCase());
                    if (optionIndexObj == null) continue;
                    int optionIndex = optionIndexObj;
                    Rectangle rectangle = getSpiritOptionBounds(optionIndex);
                    if (rectangle == null || isSpiritOptionSelected(rectangle)) continue;
                    if (script.getFinger().tap(true, (Shape) rectangle)) {
                        script.pollFramesUntil(() -> isSpiritOptionSelected(rectangle), 1500, false, false);
                    }
                    script.pollFramesHuman(() -> true, SPIRIT_SELECTION_DELAY_SHORT, false);
                    continue;
                }
                break;
            }
        } else {
            script.pollFramesHuman(() -> true, SPIRIT_DETECTION_DELAY, false);
        }
    }

    private void countOfferings(int previousCount) {
        ItemGroupResult itemGroupResult = script.getWidgetManager().getInventory().search(Set.of(OFFERING_ID));
        if (itemGroupResult == null) {
            return;
        }
        ItemSearchResult itemSearchResult = itemGroupResult.getItem(new int[]{OFFERING_ID});
        if (itemSearchResult == null) {
            return;
        }
        int currentCount = itemGroupResult.getAmount(new int[]{itemSearchResult.getId()});
        int newlyCollected = currentCount - previousCount;
        if (newlyCollected > 0) {
            offeringsCount += newlyCollected;
            log("Collected " + newlyCollected + " new offerings. Total: " + offeringsCount);
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


    private void claimOffering(Area area) {
        // claim offerings at each site
        WorldPosition currentPos = script.getWorldPosition();
        if (currentPos == null) {
            log("WARNING: Cannot claim offerings - current position is null");
            return;
        }

        // Search for Offering site by ID within the totem area
        RSObject offeringsObject = script.getObjectManager().getRSObject(rSObject ->
                rSObject != null &&
                        rSObject.getId() == OFFERING_SITE_ID &&
                        area.contains(rSObject.getWorldPosition()));

        // Fallback: search by name within area
        if (offeringsObject == null) {
            RSObject found = script.getObjectManager().getObjects(rSObject ->
                            rSObject != null &&
                                    rSObject.getName() != null &&
                                    rSObject.getName().equalsIgnoreCase("Offering site") &&
                                    area.contains(rSObject.getWorldPosition())
                    ).stream()
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
            script.getWalker().walkTo((Position) finalOfferingsObject.getWorldPosition());
            boolean reached = script.pollFramesUntil(() -> {
                WorldPosition pos = script.getWorldPosition();
                return pos != null && finalOfferingsObject.distance(pos) <= TOTEM_INTERACTION_DISTANCE_EXTENDED;
            }, WALK_COMPLETION_TIMEOUT, false, false);
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
            log("WARNING: Could not find 'Claim' action in menu. Available actions: " +
                    list.stream().map(e -> e.getAction()).filter(a -> a != null).collect(Collectors.joining(", ")));
            return null;
        };

        // Get count before claiming to calculate only newly collected offerings
        int offeringsBefore = getItemCount(OFFERING_ID);

        if (finalOfferingsObject.interact(menuHook)) {
            log("Successfully clicked Claim Offerings");
            script.pollFramesUntil(() -> false, 1500, false, false);
            // After claiming, count only the newly collected offerings
            countOfferings(offeringsBefore);
        } else {
            log("WARNING: Failed to interact with Offerings object");
        }
    }

    private void scanForSpirits() {
        if (detectedSpirits.size() >= 3) return;

        List<String> detected = detectSpirits();
        if (detected.size() > 3) {
            // If we somehow detect more than 3, clear and rescan once
            detectedSpirits.clear();
            spiritsReadyLogged = false;
            detected = detectSpirits();
        }
        for (String spirit : detected) {
            if (detectedSpirits.size() >= 3) {
                break;
            }
            if (detectedSpirits.add(spirit)) {
                log("Spirit detected: " + spirit + " (" + detectedSpirits.size() + "/3)");
            }
        }
    }

    private List<String> detectSpirits() {
        // Close inventory if open so NPCs aren't hidden behind it
        if (script.getWidgetManager().getInventory() != null && script.getWidgetManager().getInventory().isVisible()) {
            script.getWidgetManager().getInventory().close();
            script.pollFramesHuman(() -> true, 50, false);
        }

        LinkedHashSet<String> detected = new LinkedHashSet<>();
        SearchablePixel searchablePixel = new SearchablePixel(SPIRIT_TEXT_COLOR, (ToleranceComparator) new SingleThresholdComparator(COLOR_TOLERANCE_HIGH), ColorModel.RGB);
        List<Point> pixels = script.getPixelAnalyzer().findPixels((Shape) script.getScreen().getBounds(), new SearchablePixel[]{searchablePixel});
        if (pixels.isEmpty()) {
            return new ArrayList<>();
        }

        Rectangle screenBounds = script.getScreen().getBounds();
        for (Rectangle rectangle : groupPixelsIntoRectangles(pixels)) {
            // Calculate OCR bounds with padding
            int x = Math.max(0, rectangle.x - SPIRIT_TEXT_PADDING);
            int y = Math.max(0, rectangle.y - SPIRIT_TEXT_PADDING);
            int width = Math.min(screenBounds.width - x, rectangle.width + (SPIRIT_TEXT_PADDING * 2));
            int height = Math.min(screenBounds.height - y, rectangle.height + (SPIRIT_TEXT_PADDING * 2));

            if (width <= 0 || height <= 0) {
                continue;
            }

            Rectangle ocrBounds = new Rectangle(x, y, width, height);
            String text = script.getOCR().getText(Font.STANDARD_FONT_BOLD, ocrBounds, new int[]{SPIRIT_TEXT_COLOR});

            if (text == null || text.trim().isEmpty()) {
                continue;
            }

            String textLower = text.toLowerCase().trim();

            // Validate text looks like a spirit name (must contain "spirit" and be long enough)
            if (!textLower.contains("spirit") || textLower.length() < MIN_SPIRIT_TEXT_LENGTH) {
                continue;
            }

            // Check for valid spirit names
            for (String validSpirit : VALID_SPIRITS) {
                if (textLower.contains(validSpirit)) {
                    detected.add(validSpirit);
                    break; // Only one spirit per text block
                }
            }
        }
        return new ArrayList<>(detected);
    }

    private List<Rectangle> groupPixelsIntoRectangles(List<Point> pixels) {
        if (pixels.isEmpty()) {
            return new ArrayList<>();
        }

        List<Rectangle> rectangles = new ArrayList<>();
        // Calculate merge distance based on estimated font height (results in ~12px)
        int mergeDistance = Math.max(8, ESTIMATED_FONT_HEIGHT * 2);

        // Process pixels and merge nearby ones into rectangles
        for (Point point : pixels) {
            Rectangle newRect = new Rectangle(point.x, point.y, 1, 1);
            boolean merged = false;

            // Try to merge
            for (int i = 0; i < rectangles.size(); i++) {
                Rectangle existing = rectangles.get(i);


                int dx = Math.max(existing.x - point.x, point.x - (existing.x + existing.width));
                int dy = Math.max(existing.y - point.y, point.y - (existing.y + existing.height));

                if (dx <= mergeDistance && dy <= mergeDistance) {
                    rectangles.set(i, existing.union(newRect));
                    merged = true;
                    break;
                }
            }

            if (!merged) {
                rectangles.add(newRect);
            }
        }

        return rectangles;
    }

    private Rectangle getSpiritOptionBounds(int optionIndex) {
        Rectangle dialogueBounds = script.getWidgetManager().getDialogue().getBounds();
        if (dialogueBounds == null) {
            return null;
        }


        int columns = 5;
        double cellWidth = (double) dialogueBounds.width / columns;
        double iconRowY = dialogueBounds.y + (dialogueBounds.height * 0.45) + 9;

        double colCenter = dialogueBounds.x + (optionIndex - 0.5) * cellWidth;

        int boxSize = Math.min(44, (int) Math.round(cellWidth * 0.5));
        int half = boxSize / 2;

        int left = (int) Math.round(colCenter) - half;
        int top = (int) Math.round(iconRowY) - half;

        return new Rectangle(left, top, boxSize, boxSize);
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
                || totem.distance(script.getWorldPosition()) < 10.0;
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
                script.pollFramesHuman(() -> true, 1000, false);
                lastStuckPosition = null;
                lastPositionChangeTime = System.currentTimeMillis();
            }
        } else {
            lastStuckPosition = currentPos;
            lastPositionChangeTime = System.currentTimeMillis();
        }
    }

    private boolean checkForNoLogsMessage() {
        boolean hasTapHereDialogue = script.getWidgetManager().getDialogue().isVisible() &&
                script.getWidgetManager().getDialogue().getDialogueType() == DialogueType.TAP_HERE_TO_CONTINUE;

        UIResultList chatboxText = script.getWidgetManager().getChatbox().getText();
        if (chatboxText == null) {
            return hasTapHereDialogue;
        }
        List<String> chatLines = chatboxText.asList();
        if (chatLines == null || chatLines.isEmpty()) {
            return hasTapHereDialogue;
        }
        // If we have a TAP_HERE_TO_CONTINUE dialogue and no logs in inventory, it's likely the "no logs" message
        if (hasTapHereDialogue && getItemCount(selectedLogId) == 0) {
            return true;
        }

        return false;
    }

    private boolean handleNoLogsDetected(String reason) {
        log(reason + " - returning to closest bank.");
        ready = false;
        currentAction = TotemAction.NONE;
        if (script.getWidgetManager().getDialogue().isVisible()) {
            DialogueType dialogueType = script.getWidgetManager().getDialogue().getDialogueType();
            if (dialogueType == DialogueType.TAP_HERE_TO_CONTINUE) {
                script.getWidgetManager().getDialogue().continueChatDialogue();
                script.pollFramesHuman(() -> true, 100, false);
            }
        }
        goToClosestBank();
        return false;
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

