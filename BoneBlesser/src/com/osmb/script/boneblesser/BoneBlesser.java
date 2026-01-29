package com.osmb.script.boneblesser;

import com.osmb.api.input.MenuEntry;
import com.osmb.api.input.MenuHook;
import com.osmb.api.item.ItemGroupResult;
import com.osmb.api.item.ItemSearchResult;
import com.osmb.api.location.position.types.WorldPosition;
import com.osmb.api.scene.RSObject;
import com.osmb.api.script.Script;
import com.osmb.api.script.ScriptDefinition;
import com.osmb.api.script.SkillCategory;
import com.osmb.api.shape.Polygon;
import com.osmb.api.shape.Rectangle;
import com.osmb.api.ui.chatbox.dialogue.Dialogue;
import com.osmb.api.ui.chatbox.dialogue.DialogueType;
import com.osmb.api.ui.component.tabs.SettingsTabComponent;
import com.osmb.api.utils.RandomUtils;
import com.osmb.api.visual.drawing.Canvas;
import com.osmb.api.walker.WalkConfig;
import com.sainty.common.Telemetry;
import com.sainty.common.VersionChecker;

import java.awt.*;
import java.util.*;
import java.util.List;

@ScriptDefinition(
        name = "Bone Blesser",
        author = "Sainty",
        version = 4.3,
        description = "Unnotes, blesses and chisels bones.",
        skillCategory = SkillCategory.PRAYER
)
public class BoneBlesser extends Script {
    private static final int REGION_TEOMAT = 5681;
    private static final int CHISEL_ID = 1755;
    private static final Rectangle ALTAR_AREA =
            new Rectangle(1433, 3147, 5, 5);
    private static final Rectangle VIRILIS_RECT =
            new Rectangle(1441, 3154, 5, 4);
    //To prevent clicking Renu for those that have done the quest
    private static final Rectangle RENU_RECT =
            new Rectangle(1436, 3168, 3, 2);
    //virilis wander area
    private static final Rectangle VIRILIS_AREA =
            new Rectangle(1433, 3146, 23, 21);
    private static final int BONE_SHARDS_ID = 29381;
    private static final long STABLE_MS = 500;
    private static final long DEBUG_LOG_MS = 2000;
    private static final int MAX_NPC_DISTANCE = 10;
    private static final double NPC_CUBE_RESIZE = 0.6;
    private static final java.awt.Font PAINT_FONT =
            new java.awt.Font("Arial", java.awt.Font.PLAIN, 12);
    private long scriptStartTime;
    private int totalShards;
    private int lastShardCount = -1;
    private int remainingBones;
    private BoneType selectedBone = null;
    private boolean detectionTimedOut = false;
    private static final Set<Integer> COIN_SET = Collections.singleton(995);
    private static final Set<Integer> INV_IDS = new HashSet<>();
    private static final Set<Integer> ALL_BONE_IDS = new HashSet<>();
    private long lastPollAt = 0;
    private static final String SCRIPT_NAME = "BoneBlesser";

    static {
        for (BoneType t : BoneType.values()) {
            ALL_BONE_IDS.add(t.unblessedId);
            ALL_BONE_IDS.add(t.notedId);
            ALL_BONE_IDS.add(t.blessedId);
        }
    }

    private WorldPosition lastPos;
    private long lastPosChange;
    private long lastWalkAt;
    private long lastUnnoteAt;
    private long lastBlessAt;
    private long lastDebugAt;
    private long lastSuccessfulUnnoteAt = 0;
    private int lastNotedCount = -1;
    private int unnoteRetryCount = 0;
    private static final int MAX_UNNOTE_RETRIES = 3;

    private enum ChiselState {
        IDLE, CLICKED_CHISEL
    }

    private enum BoneBlessingTask {
        CHISEL_BONES,
        BLESS_BONES,
        UNNOTE_BONES,
        HANDLE_DIALOGUE,
        WALK_TO_ALTAR,
        WALK_TO_VIRILIS
    }

    private static class BoneContext {
        final ItemGroupResult inv;
        final WorldPosition position;
        final ItemSearchResult noted;
        final ItemSearchResult unblessed;
        final ItemSearchResult blessed;
        final ItemSearchResult chisel;
        final long now;

        BoneContext(ItemGroupResult inv, WorldPosition position, ItemSearchResult noted,
                    ItemSearchResult unblessed, ItemSearchResult blessed, ItemSearchResult chisel, long now) {
            this.inv = inv;
            this.position = position;
            this.noted = noted;
            this.unblessed = unblessed;
            this.blessed = blessed;
            this.chisel = chisel;
            this.now = now;
        }
    }

    private ChiselState chiselState = ChiselState.IDLE;

    public BoneBlesser(Object core) {
        super(core);
    }

    @Override
    public void onStart() {
        if (!VersionChecker.isExactVersion(this)) {
            stop();
            return;
        }
        ensureMaxZoom();
        scriptStartTime = System.currentTimeMillis();
        totalShards = 0;
        lastShardCount = -1;
        detectionTimedOut = false;
        lastSuccessfulUnnoteAt = System.currentTimeMillis();
        lastNotedCount = -1;
        Telemetry.sessionStart(SCRIPT_NAME);
        INV_IDS.clear();
        for (BoneType t : BoneType.values()) {
            INV_IDS.add(t.unblessedId);
            INV_IDS.add(t.notedId);
            INV_IDS.add(t.blessedId);
        }
        INV_IDS.add(CHISEL_ID);
        log("BoneBlesser", "Started – auto-detecting bone type from inventory");
    }

    @Override
    public int[] regionsToPrioritise() {
        return new int[]{REGION_TEOMAT};
    }

    private int getTotalShards() {
        return totalShards;
    }

    private int getPrayerXpNoSunfire() {
        return totalShards * 5;
    }

    private int getPrayerXpWithSunfire() {
        return totalShards * 6;
    }

    private long getDetectionTimeout() {
        return RandomUtils.uniformRandom(85000, 95000);
    }

    private boolean recentlyMoved() {
        WorldPosition now = getWorldPosition();
        if (now == null) {
            return true;
        }
        if (!now.equals(lastPos)) {
            lastPos = now;
            lastPosChange = System.currentTimeMillis();
            return true;
        }
        return System.currentTimeMillis() - lastPosChange < STABLE_MS;
    }

    private long getWalkCooldown() {
        return RandomUtils.uniformRandom(5000, 7000);
    }

    private long getUnnoteCooldown() {
        return RandomUtils.uniformRandom(7000, 9000);
    }

    private long getBlessCooldown() {
        return RandomUtils.uniformRandom(7000, 9000);
    }

    private boolean canInteract() {
        if (recentlyMoved()) {
            return false;
        }
        var d = getWidgetManager().getDialogue();
        return d == null || !d.isVisible();
    }

    private boolean inRect(WorldPosition p, Rectangle r) {
        return p != null &&
                p.getX() >= r.x &&
                p.getX() < r.x + r.width &&
                p.getY() >= r.y &&
                p.getY() < r.y + r.height;
    }

    private void walkToRect(Rectangle r) {
        long now = System.currentTimeMillis();
        if (now - lastWalkAt < getWalkCooldown()) {
            return;
        }
        lastWalkAt = now;
        int x = r.x + RandomUtils.uniformRandom(0, r.width);
        int y = r.y + RandomUtils.uniformRandom(0, r.height);
        getWalker().walkTo(
                new WorldPosition(x, y, 0),
                new WalkConfig.Builder()
                        .setWalkMethods(false, true)
                        .build()
        );
    }

    private void detectBoneType(ItemGroupResult inv) {
        if (inv == null) {
            return;
        }

        //if we have bones, check it exists
        if (selectedBone != null) {
            if (inventoryContainsBone(selectedBone, inv)) {
                return;
            }

            // Bones not found in search - check if they exist but are selected/clicked
            var invWidget = getWidgetManager().getInventory();
            if (invWidget != null) {
                ItemGroupResult notedSearch = invWidget.search(Collections.singleton(selectedBone.notedId));
                ItemSearchResult noted = notedSearch != null ? notedSearch.getItem(selectedBone.notedId) : null;

                ItemGroupResult unblessedSearch = invWidget.search(Collections.singleton(selectedBone.unblessedId));
                ItemSearchResult unblessed = unblessedSearch != null ? unblessedSearch.getItem(selectedBone.unblessedId) : null;

                ItemGroupResult blessedSearch = invWidget.search(Collections.singleton(selectedBone.blessedId));
                ItemSearchResult blessed = blessedSearch != null ? blessedSearch.getItem(selectedBone.blessedId) : null;

                // If any bone exists (even if selected), don't reset bone type
                if (noted != null || unblessed != null || blessed != null) {
                    return;
                }
            }

            // Only reset if we're not moving and bones aren't selected
            if (!recentlyMoved()) {
                log("BoneBlesser", "Bone type changed — re-detecting");
                selectedBone = null;
                lastNotedCount = -1;
                lastShardCount = -1;
            } else {
                return;
            }
        }

        for (BoneType t : BoneType.values()) {
            if (inv.getItem(t.notedId) != null) {
                selectedBone = t;
                return;
            }
        }

        for (BoneType t : BoneType.values()) {
            if (inv.getItem(t.blessedId) != null) {
                selectedBone = t;
                return;
            }
        }

        for (BoneType t : BoneType.values()) {
            if (inv.getItem(t.unblessedId) != null) {
                selectedBone = t;
                return;
            }
        }
    }

    //Sometimes the clicking isn't accurate/can't reach NPCs if not fully zoomed, ensure this is set to max
    private void ensureMaxZoom() {
        if (!getWidgetManager().getSettings()
                .openSubTab(SettingsTabComponent.SettingsSubTabType.DISPLAY_TAB)) {
            log("BoneBlesser", "Failed to open display settings tab");
            return;
        }
        var zoomResult = getWidgetManager()
                .getSettings()
                .getZoomLevel();
        Integer zoom = zoomResult != null ? zoomResult.get() : null;
        if (zoom == null) {
            log("BoneBlesser", "Failed to read zoom level");
            return;
        }
        log("BoneBlesser", "Current zoom level: " + zoom);
        // Force max zoom-out
        if (zoom > 1) {
            if (getWidgetManager().getSettings().setZoomLevel(0)) {
                log("BoneBlesser", "Zoom set to maximum (1)");
                pollFramesHuman(() -> true, RandomUtils.gaussianRandom(300, 2000, 425, 425));
            } else {
                log("BoneBlesser", "Failed to set zoom level");
            }
        }
    }

    @Override
    public int poll() {
        handleHousekeeping();

        BoneContext ctx = collectContext();
        if (ctx == null) return RandomUtils.gaussianRandom(120, 1800, 420, 420);

        updateTracking(ctx);

        if (shouldStop(ctx)) {
            stop();
            return -1;
        }

        BoneBlessingTask task = decideTask(ctx);
        if (task == null) return RandomUtils.gaussianRandom(200, 2500, 575, 575);

        return executeTask(task, ctx);
    }

    private void handleHousekeeping() {
        Telemetry.flush(
                SCRIPT_NAME,
                scriptStartTime,
                Map.of(
                        "bone_shards_gained", (long) getTotalShards(),
                        "potential_prayer_xp_no_sunfire", (long) getPrayerXpNoSunfire(),
                        "potential_prayer_xp_with_sunfire", (long) getPrayerXpWithSunfire()
                )
        );
        long now = System.currentTimeMillis();
        if (lastPollAt != 0 && now - lastPollAt > 60_000) {
            // Large gap is likely a break, count the timeout from when the last poll was so it doesn't instantly timeout on
            // logging in
            lastSuccessfulUnnoteAt = now;
            lastNotedCount = -1;
            log("BoneBlesser", "Resumed after pause — reset unnote stall timer");
        }
        lastPollAt = now;
    }

    private BoneContext collectContext() {
        // Unselect when idle so chisel doesn't stay stuck when selected but no bones remain.
        // Skip when chisel is selected (CLICKED_CHISEL) so we don't deselect before clicking a bone.
        if (chiselState != ChiselState.CLICKED_CHISEL) {
            getWidgetManager().getInventory().unSelectItemIfSelected();
        }

        ItemGroupResult inv = getWidgetManager().getInventory().search(INV_IDS);
        if (inv == null) {
            return null;
        }

        detectBoneType(inv);
        if (selectedBone == null) {
            long now = System.currentTimeMillis();
            if (!detectionTimedOut && now - scriptStartTime > getDetectionTimeout()) {
                log("BoneBlesser", "No bones detected after timeout. Stopping.");
                stop();
            }
            return null;
        }

        long now = System.currentTimeMillis();
        // Search for bones - handle null results safely (can happen if items are selected)
        ItemGroupResult notedSearch = getWidgetManager().getInventory()
                .search(Collections.singleton(selectedBone.notedId));
        ItemSearchResult noted = notedSearch != null ? notedSearch.getItem(selectedBone.notedId) : null;

        ItemGroupResult unblessedSearch = getWidgetManager().getInventory()
                .search(Collections.singleton(selectedBone.unblessedId));
        ItemSearchResult unblessed = unblessedSearch != null ? unblessedSearch.getItem(selectedBone.unblessedId) : null;

        ItemGroupResult blessedSearch = getWidgetManager().getInventory()
                .search(Collections.singleton(selectedBone.blessedId));
        ItemSearchResult blessed = blessedSearch != null ? blessedSearch.getItem(selectedBone.blessedId) : null;

        ItemSearchResult chisel = inv.getItem(CHISEL_ID);
        WorldPosition position = getWorldPosition();

        return new BoneContext(inv, position, noted, unblessed, blessed, chisel, now);
    }

    private void updateTracking(BoneContext ctx) {
        if (ctx.noted != null) {
            int currentNoted = ctx.noted.getStackAmount();
            if (lastNotedCount != -1 && currentNoted < lastNotedCount) {
                lastSuccessfulUnnoteAt = ctx.now;
            }
            lastNotedCount = currentNoted;
        }

        if (ctx.blessed != null) {
            ItemSearchResult shardStack = getWidgetManager().getInventory()
                    .search(Collections.singleton(BONE_SHARDS_ID))
                    .getItem(BONE_SHARDS_ID);
            if (shardStack != null) {
                int current = shardStack.getStackAmount();
                if (lastShardCount != -1 && current > lastShardCount) {
                    totalShards += (current - lastShardCount);
                }
                lastShardCount = current;
            }
        }

        remainingBones = 0;
        if (ctx.noted != null) {
            remainingBones += Math.max(1, ctx.noted.getStackAmount());
        }
        List<ItemSearchResult> unblessedList = ctx.inv.getAllOfItem(selectedBone.unblessedId);
        if (unblessedList != null) {
            remainingBones += unblessedList.size();
        }
        List<ItemSearchResult> blessedList = ctx.inv.getAllOfItem(selectedBone.blessedId);
        if (blessedList != null) {
            remainingBones += blessedList.size();
        }

        if (ctx.now - lastDebugAt > DEBUG_LOG_MS) {
            log("BoneBlesser",
                    "State | noted=" + (ctx.noted != null) +
                            " unblessed=" + (ctx.unblessed != null) +
                            " blessed=" + (ctx.blessed != null) +
                            " free=" + ctx.inv.getFreeSlots() +
                            " moved=" + recentlyMoved()
            );
            lastDebugAt = ctx.now;
        }
    }

    private boolean shouldStop(BoneContext ctx) {
        if (ctx.noted != null) {
            long sinceLastUnnote = ctx.now - lastSuccessfulUnnoteAt;
            if (sinceLastUnnote > 180000) {
                log("BoneBlesser", "Stopping: Unnoting stalled for 180 seconds.");
                return true;
            }
        }
        return false;
    }

    private BoneBlessingTask decideTask(BoneContext ctx) {
        // Handle dialogue first (highest priority)
        var dialogue = getWidgetManager().getDialogue();
        if (dialogue != null && dialogue.isVisible()) {
            return BoneBlessingTask.HANDLE_DIALOGUE;
        }

        if (ctx.position == null) {
            return null;
        }

        // Handle chisel state machine
        if (chiselState == ChiselState.CLICKED_CHISEL) {
            // Check if we still have bones to chisel, if not reset state
            ItemSearchResult target = findChiselTarget();
            if (target == null) {
                log("BoneBlesser", "Chisel clicked but no bones to chisel - resetting state");
                chiselState = ChiselState.IDLE;
            } else {
                return BoneBlessingTask.CHISEL_BONES;
            }
        }

        boolean needChisel = ctx.chisel != null && ctx.blessed != null;
        boolean needBless = ctx.unblessed != null;
        boolean needUnnote = ctx.noted != null && ctx.unblessed == null && ctx.inv.getFreeSlots() > 0;

        // Priority: Chisel > Bless > Unnote
        // Only chisel if we have bones to chisel
        if (needChisel && canInteract() && chiselState == ChiselState.IDLE) {
            ItemSearchResult target = findChiselTarget();
            if (target != null) {
                return BoneBlessingTask.CHISEL_BONES;
            }
        }

        if (needBless && ctx.now - lastBlessAt > getBlessCooldown()) {
            RSObject altar = getObjectManager().getClosestObject(ctx.position, "Exposed altar");
            if (altar != null && canInteract()) {
                Polygon poly = altar.getConvexHull();
                if (poly != null) {
                    poly = poly.getResized(0.6);
                    if (getWidgetManager().insideGameScreen(poly, Collections.emptyList())) {
                        return BoneBlessingTask.BLESS_BONES;
                    }
                }
            }
            if (!inRect(ctx.position, ALTAR_AREA)) {
                return BoneBlessingTask.WALK_TO_ALTAR;
            }
        }

        if (needUnnote && ctx.now - lastUnnoteAt > getUnnoteCooldown()) {
            WorldPosition validNPC = findClosestValidNPC(ctx.position);
            if (validNPC != null) {
                return BoneBlessingTask.UNNOTE_BONES;
            }
            if (!inRect(ctx.position, VIRILIS_AREA)) {
                return BoneBlessingTask.WALK_TO_VIRILIS;
            }
        }

        return null;
    }

    private int executeTask(BoneBlessingTask task, BoneContext ctx) {
        return switch (task) {
            case HANDLE_DIALOGUE -> handleDialogue();
            case CHISEL_BONES -> chiselBones(ctx);
            case BLESS_BONES -> blessBones(ctx);
            case UNNOTE_BONES -> unnoteBones(ctx);
            case WALK_TO_ALTAR -> walkToAltar();
            case WALK_TO_VIRILIS -> walkToVirilis();
        };
    }

    private int handleDialogue() {
        var dialogue = getWidgetManager().getDialogue();
        if (dialogue != null && dialogue.isVisible()) {
            var b = dialogue.getBounds();
            if (b != null) {
                getFinger().tap(
                        b.x + b.width / 2,
                        b.y + (int) (b.height * 0.58)
                );
                return RandomUtils.gaussianRandom(300, 2000, 425, 425);
            }
        }
        return RandomUtils.gaussianRandom(200, 2500, 575, 575);
    }

    private int chiselBones(BoneContext ctx) {
        if (chiselState == ChiselState.CLICKED_CHISEL) {
            // State 2: click target bone
            ItemSearchResult target = findChiselTarget();
            if (target != null && target.interact()) {
                chiselState = ChiselState.IDLE;
            } else if (target == null) {
                // No bones left to chisel - reset state to avoid getting stuck
                log("BoneBlesser", "No bones to chisel - resetting chisel state");
                chiselState = ChiselState.IDLE;
            }
            return RandomUtils.gaussianRandom(200, 1500, 350, 350);
        }

        // State 1: click chisel (only if we have bones to chisel)
        if (ctx.chisel != null && canInteract() && chiselState == ChiselState.IDLE) {
            // Check if we actually have bones to chisel before clicking chisel
            ItemSearchResult target = findChiselTarget();
            if (target == null) {
                // No bones to chisel - don't click chisel
                return RandomUtils.gaussianRandom(200, 2500, 575, 575);
            }
            if (ctx.chisel.interact()) {
                chiselState = ChiselState.CLICKED_CHISEL;
            }
            return RandomUtils.gaussianRandom(200, 1500, 350, 350);
        }

        return RandomUtils.gaussianRandom(200, 2500, 575, 575);
    }

    private ItemSearchResult findChiselTarget() {
        if (selectedBone == null) return null;

        List<ItemSearchResult> allBlessed = getWidgetManager().getInventory()
                .search(Collections.singleton(selectedBone.blessedId))
                .getAllOfItem(selectedBone.blessedId);
        if (allBlessed != null && !allBlessed.isEmpty()) {
            for (ItemSearchResult r : allBlessed) {
                if (r != null && r.getSlot() == 27) {
                    return r;
                }
            }
            return allBlessed.get(0);
        }
        return null;
    }

    private int blessBones(BoneContext ctx) {
        RSObject altar = getObjectManager().getClosestObject(ctx.position, "Exposed altar");
        if (altar != null && canInteract()) {
            Polygon poly = altar.getConvexHull();
            if (poly != null) {
                poly = poly.getResized(0.6);
                if (getWidgetManager().insideGameScreen(poly, Collections.emptyList())) {
                    getFinger().tapGameScreen(poly, "Bless");
                    lastBlessAt = ctx.now;
                    return RandomUtils.gaussianRandom(120, 1800, 420, 420);
                }
            }
        }
        return RandomUtils.gaussianRandom(200, 2500, 575, 575);
    }

    private int unnoteBones(BoneContext ctx) {
        if (ctx.noted == null || selectedBone == null) {
            return RandomUtils.gaussianRandom(200, 2500, 575, 575);
        }

        // Find the closest valid NPC before clicking bone
        WorldPosition npcPos = findClosestValidNPC(ctx.position);
        if (npcPos == null) {
            return RandomUtils.gaussianRandom(200, 2500, 575, 575);
        }

        // Click noted bones first
        if (!ctx.noted.interact()) {
            return RandomUtils.gaussianRandom(200, 2500, 575, 575);
        }

        // Wait after clicking bone
        pollFramesHuman(() -> true, RandomUtils.gaussianRandom(100, 800, 200, 200));

        // Re-find NPC position AFTER the delay, as NPC may have moved
        WorldPosition myPos = getWorldPosition();
        if (myPos == null) {
            return RandomUtils.gaussianRandom(200, 2500, 575, 575);
        }

        WorldPosition currentNPCPos = findClosestValidNPC(myPos);
        if (currentNPCPos == null) {
            return RandomUtils.gaussianRandom(200, 2500, 575, 575);
        }

        // Get fresh polygon for current NPC position
        Polygon cube = getSceneProjector().getTileCube(currentNPCPos, 90);
        if (cube == null) {
            return RandomUtils.gaussianRandom(200, 2500, 575, 575);
        }

        Polygon resized = cube.getResized(NPC_CUBE_RESIZE);
        if (resized == null) {
            return RandomUtils.gaussianRandom(200, 2500, 575, 575);
        }

        if (!getWidgetManager().insideGameScreen(resized, Collections.emptyList())) {
            return RandomUtils.gaussianRandom(200, 2500, 575, 575);
        }

        // Use MenuHook to verify we're clicking "Use <bonetype>" -> "Virilis" not "Talk"
        MenuHook menuHook = createUnnoteMenuHook();

        // Click NPC with menu verification
        if (getFinger().tapGameScreen(resized, menuHook)) {
            // Wait for dialogue to appear - give more time if player is moving
            // Also check if we're still near the NPC (might need to walk closer)
            int dialogueTimeout = recentlyMoved()
                    ? (int) RandomUtils.gaussianRandom(8000, 12000, 10000, 1000)  // More time if moving (8-12s)
                    : (int) RandomUtils.gaussianRandom(7000, 10000, 8500, 750); // Normal time if stationary (7-10s)

            boolean dialogueAppeared = pollFramesUntil(() -> {
                Dialogue dialogue = getWidgetManager().getDialogue();
                return dialogue != null && dialogue.isVisible();
            }, dialogueTimeout);

            // If dialogue didn't appear, check if we moved too far from NPC
            if (!dialogueAppeared) {
                WorldPosition me = getWorldPosition();
                if (me != null && currentNPCPos != null) {
                    double distance = me.distanceTo(currentNPCPos);
                    if (distance > MAX_NPC_DISTANCE + 2) {
                        log("BoneBlesser", "Moved too far from NPC (" + String.format("%.1f", distance) + " tiles) - dialogue won't appear");
                    }
                }
            }

            if (dialogueAppeared) {
                // Handle "Exchange all" dialogue option
                handleExchangeAllDialogue();
                // Reset retry count on success
                unnoteRetryCount = 0;
            } else {
                // Dialogue didn't appear - might have clicked wrong option
                unnoteRetryCount++;
                log("BoneBlesser", "Dialogue did not appear after clicking NPC (retry " + unnoteRetryCount + "/" + MAX_UNNOTE_RETRIES + ")");
                if (unnoteRetryCount >= MAX_UNNOTE_RETRIES) {
                    log("BoneBlesser", "Max retries reached for unnoting - resetting retry count");
                    unnoteRetryCount = 0;
                }
                return RandomUtils.gaussianRandom(200, 2500, 575, 575);
            }

            lastUnnoteAt = ctx.now;
            return RandomUtils.gaussianRandom(600, 2500, 475, 475);
        } else {
            // Menu option not found - retry
            unnoteRetryCount++;
            log("BoneBlesser", "Could not find correct menu option (retry " + unnoteRetryCount + "/" + MAX_UNNOTE_RETRIES + ")");
            if (unnoteRetryCount >= MAX_UNNOTE_RETRIES) {
                log("BoneBlesser", "Max retries reached - resetting retry count");
                unnoteRetryCount = 0;
            }
            return RandomUtils.gaussianRandom(200, 2500, 575, 575);
        }
    }

    private MenuHook createUnnoteMenuHook() {
        return entries -> {
            if (entries == null || selectedBone == null) return null;

            // Normalize bone name for flexible matching (handle "BIG BONES" vs "BIGBONES")
            String boneNameNormalized = normalizeBoneName(selectedBone.name);

            for (MenuEntry entry : entries) {
                String rawText = entry.getRawText();
                if (rawText == null) continue;

                String textLower = rawText.toLowerCase();
                String textNormalized = normalizeBoneName(textLower);

                // Look for "Use <bonetype>" -> "Virilis" option
                if (textLower.contains("use") &&
                        textNormalized.contains(boneNameNormalized) &&
                        textLower.contains("virilis")) {
                    log("BoneBlesser", "Found menu option: " + rawText);
                    return entry;
                }
            }

            // Log available menu options for debugging
            log("BoneBlesser", "Could not find 'Use " + selectedBone.name + " -> Virilis' menu option");
            if (!entries.isEmpty()) {
                log("BoneBlesser", "Available menu options:");
                for (MenuEntry entry : entries) {
                    String rawText = entry.getRawText();
                    if (rawText != null) {
                        log("BoneBlesser", "  - " + rawText);
                    }
                }
            }
            return null;
        };
    }

    private String normalizeBoneName(String boneName) {
        if (boneName == null) return "";
        // Remove spaces and convert to lowercase for flexible matching across all bones
        return boneName.toLowerCase().replaceAll("\\s+", "");
    }

    private void handleExchangeAllDialogue() {
        Dialogue dialogue = getWidgetManager().getDialogue();
        if (dialogue == null || !dialogue.isVisible()) {
            return;
        }

        DialogueType dialogueType = dialogue.getDialogueType();
        if (dialogueType != DialogueType.TEXT_OPTION) {
            return;
        }

        // Use selectOption to click "Exchange all" rather than clicking coords
        if (dialogue.selectOption("Exchange all")) {
            log("BoneBlesser", "Selected 'Exchange all' option");
        } else {
            log("BoneBlesser", "Could not find 'Exchange all' option in dialogue");
        }
    }

    private WorldPosition findClosestValidNPC(WorldPosition myPos) {
        if (myPos == null) {
            return null;
        }

        var npcPositions = getWidgetManager().getMinimap().getNPCPositions();
        if (npcPositions == null || npcPositions.isNotVisible()) {
            return null;
        }

        WorldPosition closestNPC = null;
        double closestDistance = Double.MAX_VALUE;

        for (WorldPosition npc : npcPositions) {
            // Filter by area
            if (!inRect(npc, VIRILIS_AREA)) {
                continue;
            }

            // Exclude Renu
            if (inRect(npc, RENU_RECT)) {
                continue;
            }

            // Check distance
            double distance = myPos.distanceTo(npc);
            if (distance > MAX_NPC_DISTANCE) {
                continue;
            }

            // Validate NPC is visible on screen
            Polygon cube = getSceneProjector().getTileCube(npc, 90);
            if (cube == null) {
                continue;
            }

            Polygon resized = cube.getResized(NPC_CUBE_RESIZE);
            if (resized == null) {
                continue;
            }

            if (!getWidgetManager().insideGameScreen(resized, Collections.emptyList())) {
                continue;
            }

            // Track closest valid NPC
            if (distance < closestDistance) {
                closestDistance = distance;
                closestNPC = npc;
            }
        }

        return closestNPC;
    }

    private int walkToAltar() {
        walkToRect(ALTAR_AREA);
        return RandomUtils.gaussianRandom(200, 2500, 575, 575);
    }

    private int walkToVirilis() {
        walkToRect(VIRILIS_RECT);
        return RandomUtils.gaussianRandom(200, 2500, 575, 575);
    }

    private boolean inventoryContainsBone(BoneType t, ItemGroupResult inv) {
        return inv.getItem(t.notedId) != null
                || inv.getItem(t.unblessedId) != null
                || inv.getItem(t.blessedId) != null;
    }


    private void drawHeader(Canvas c, String author, String title, int x, int y) {
        Font authorFont = new Font("Segoe UI", Font.PLAIN, 16);
        Font titleFont = new Font("Segoe UI", Font.BOLD, 20);
        c.drawText(author, x + 1, y + 1, 0xAA000000, authorFont);
        c.drawText(title, x + 1, y + 25 + 1, 0xAA000000, titleFont);
        c.drawText(author, x, y, 0xFFB0B0B0, authorFont);
        c.drawText(title, x, y + 25, 0xFFD0D0D0, titleFont);
        c.drawText(title, x - 1, y + 24, 0xFFFFFFFF, titleFont);
    }

    @Override
    public void onPaint(Canvas c) {
        long elapsed = System.currentTimeMillis() - scriptStartTime;
        if (elapsed <= 0) {
            return;
        }
        int x = 16;
        int y = 40;
        int w = 240;
        int headerH = 50;
        int bodyH = 95;
        int BG = new Color(12, 14, 20, 235).getRGB();
        int BORDER = new Color(100, 100, 110, 180).getRGB();
        int DIVIDER = new Color(255, 255, 255, 40).getRGB();
        c.fillRect(x, y, w, headerH + bodyH, BG, 1);
        c.drawRect(x, y, w, headerH + bodyH, BORDER);
        drawHeader(c, "Sainty", "Bone Blesser", x + 14, y + 18);
        c.fillRect(x + 10, y + headerH, w - 20, 1, DIVIDER);
        int ty = y + headerH + 18;
        if (selectedBone == null) {
            c.drawText("Detecting bones...", x + 14, ty, 0xFFFFCC00, PAINT_FONT);
            return;
        }
        double hours = elapsed / 3_600_000D;
        int shardsPerHour = hours > 0 ? (int) (totalShards / hours) : 0;
        int bonesPerHour = selectedBone.shardsPerBone > 0
                ? shardsPerHour / selectedBone.shardsPerBone
                : 0;
        String timeLeft = "N/A";
        if (bonesPerHour > 0 && remainingBones > 0) {
            long mins = (remainingBones * 60L) / bonesPerHour;
            timeLeft = (mins / 60) + "h " + (mins % 60) + "m";
        }
        c.drawText("Type: " + selectedBone.name, x + 14, ty, 0xFF66FF66, PAINT_FONT);
        ty += 14;
        c.drawText("Shards: " + totalShards, x + 14, ty, 0xFFFFFFFF, PAINT_FONT);
        ty += 14;
        c.drawText("Shards/hr: " + shardsPerHour, x + 14, ty, 0xFF66CCFF, PAINT_FONT);
        ty += 14;
        c.drawText("Time left: " + timeLeft, x + 14, ty, 0xFFFFAA00, PAINT_FONT);
    }

    public enum BoneType {
        NORMAL_BONES("Bones", 526, 527, 29344, 4),
        BAT_BONES("Bat bones", 530, 531, 29346, 5),
        BIG_BONES("Big bones", 532, 533, 29348, 12),
        ZOGRE_BONES("Zogre bones", 4812, 4813, 29350, 18),
        BABYDRAGON_BONES("Babydragon bones", 534, 535, 29352, 24),
        WYRMLING_BONES("Wyrmling bones", 28899, 28900, 29354, 21),
        DRAGON_BONES("Dragon bones", 536, 537, 29356, 58),
        LAVA_DRAGON_BONES("Lava dragon bones", 11943, 11944, 29358, 68),
        WYVERN_BONES("Wyvern bones", 6812, 6813, 29360, 58),
        SUPERIOR_DRAGON_BONES("Superior dragon bones", 22124, 22125, 29362, 121),
        WYRM_BONES("Wyrm bones", 22780, 22781, 29364, 42),
        DRAKE_BONES("Drake bones", 22783, 22784, 29366, 67),
        HYDRA_BONES("Hydra bones", 22786, 22787, 29368, 93),
        FAYRG_BONES("Fayrg bones", 4830, 4831, 29370, 67),
        RAURG_BONES("Raurg bones", 4832, 4833, 29372, 77),
        OURG_BONES("Ourg bones", 4834, 4835, 29374, 115),
        DAGANNOTH_BONES("Dagannoth bones", 6729, 6730, 29376, 100),
        STRYKWYRM_BONES("Strykwyrm bones", 31726, 31727, 31264, 37),
        FROST_DRAGON_BONES("Frost dragon bones", 31729, 31730, 31266, 84);
        public final String name;
        public final int unblessedId;
        public final int notedId;
        public final int blessedId;
        public final int shardsPerBone;

        BoneType(String n, int u, int no, int b, int shards) {
            name = n;
            unblessedId = u;
            notedId = no;
            blessedId = b;
            shardsPerBone = shards;
        }
    }
}