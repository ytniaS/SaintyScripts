package com.osmb.script.cavenightshade;

import com.osmb.api.item.ItemGroupResult;
import com.osmb.api.location.position.types.WorldPosition;
import com.osmb.api.scene.RSObject;
import com.osmb.api.script.Script;
import com.osmb.api.script.ScriptDefinition;
import com.osmb.api.script.SkillCategory;
import com.osmb.api.shape.Polygon;
import com.osmb.api.ui.component.tabs.SettingsTabComponent;
import com.osmb.api.utils.RandomUtils;
import com.osmb.api.visual.drawing.Canvas;
import com.osmb.api.walker.WalkConfig;
import com.sainty.common.Telemetry;
import com.sainty.common.VersionChecker;

import java.awt.*;
import java.util.Collections;
import java.util.Set;

@ScriptDefinition(
        name = "Cave Nightshade Gatherer",
        author = "Sainty",
        version = 2.0,
        description = "Gathers Cave Nightshade and banks it for snowflakes.",
        skillCategory = SkillCategory.OTHER
)
public class CaveNightshadeGatherer extends Script {
    private static final int CAVE_NIGHTSHADE = 2398;
    private static final int CAVE_REGION = 10131;
    private static final int[] PRIORITY_REGIONS = {9776, 10032, 10131, 10031};
    private static final String SCRIPT_NAME = "CaveNightshade";

    private static final int GATE_COOLDOWN = 2500;
    private static final int GATE_CROSS_TIMEOUT = 6000;
    private static final int CAVE_ENTER_DELAY_MIN = 600;
    private static final int CAVE_ENTER_DELAY_MAX = 900;
    private static final int CAVE_EXIT_DELAY_MIN = 3000;
    private static final int CAVE_EXIT_DELAY_MAX = 4000;
    private static final int PICKUP_TIMEOUT = 1200;
    private static final int BANK_OPEN_TIMEOUT = 4000;
    private static final int ZOOM_SLEEP_MIN = 400;
    private static final int ZOOM_SLEEP_MAX = 600;
    private static final int WALK_DISTANCE_THRESHOLD = 4;
    private static final int GATE_DISTANCE_THRESHOLD = 3;
    private static final int GATE_APPROACH_DISTANCE = 2;
    private static final int NIGHTSHADE_DISTANCE = 1;
    private static final int BANK_DISTANCE = 3;
    private static final double NIGHTSHADE_POLY_RESIZE = 0.4;
    private static final int CAVE_Y_THRESHOLD = 9000;
    private static final int NORTH_SOUTH_BOUNDARY = 3027;
    private static final int NORTH_BOUNDARY = 3028;
    private static final int MAX_ZOOM = 5;

    private static final WorldPosition BANK_TILE = new WorldPosition(2614, 3092, 0);
    private static final WorldPosition GATE_POSITION = new WorldPosition(2549, 3028, 0);
    private static final WorldPosition GATE_NORTH_APPROACH = new WorldPosition(2555, 3057, 0);
    private static final WorldPosition GATE_SOUTH_APPROACH = new WorldPosition(2547, 3022, 0);
    private static final WorldPosition PRE_CAVE_TILE = new WorldPosition(2530, 3012, 0);
    private static final WorldPosition NIGHTSHADE_TILE = new WorldPosition(2528, 9415, 0);

    private enum State {
        ENTER_CAVE,
        PICKUP,
        LEAVE_CAVE,
        CROSS_GATE,
        BANK
    }

    private State state = State.ENTER_CAVE;
    private boolean headingToBank = false;
    private boolean gateLocked = false;
    private long gateLockUntil = 0;
    private long startTime;
    private long scriptStartTime;
    private long totalCollected;
    private boolean enteringCave = false;
    private boolean hopFlag = false;
    private Integer lastWorld = null;

    public CaveNightshadeGatherer(Object core) {
        super(core);
    }

    @Override
    public void onStart() {
        if (!VersionChecker.isExactVersion(this)) {
            stop();
            return;
        }

        startTime = System.currentTimeMillis();
        scriptStartTime = System.currentTimeMillis();

        Telemetry.sessionStart(SCRIPT_NAME);
        ensureMaxZoom();

        hopFlag = false;
    }

    @Override
    public int[] regionsToPrioritise() {
        return PRIORITY_REGIONS;
    }

    @Override
    public boolean canHopWorlds() {
        return hopFlag;
    }

    public int poll() {
        Integer world = getCurrentWorld();
        if (world != null && !world.equals(lastWorld)) {
            hopFlag = false;
            lastWorld = world;
        }

        Telemetry.tick(
                SCRIPT_NAME,
                scriptStartTime,
                getTotalNightshadeCollected(),
                "nightshade_collected"
        );

        ItemGroupResult inv = getWidgetManager().getInventory().search(Set.of(CAVE_NIGHTSHADE));
        if (inv == null) {
            return 0;
        }

        WorldPosition me = getWorldPosition();
        if (me == null) {
            return 0;
        }

        headingToBank = inv.getFreeSlots() == 0;

        if (headingToBank) {
            if (inCave()) {
                state = State.LEAVE_CAVE;
            } else if (isNorthSide(me)) {
                state = State.BANK;
            } else {
                state = State.CROSS_GATE;
            }
        } else {
            if (inCave()) {
                state = State.PICKUP;
            } else if (enteringCave) {
                state = State.ENTER_CAVE;
            } else if (isSouthSide(me)) {
                state = State.ENTER_CAVE;
            } else {
                state = State.CROSS_GATE;
            }
        }

        if (hopFlag) {
            getProfileManager().forceHop();
            return 0;
        }

        switch (state) {
            case ENTER_CAVE:
                enterCave();
                break;
            case PICKUP:
                pickupNightshade();
                break;
            case LEAVE_CAVE:
                leaveCave();
                break;
            case CROSS_GATE:
                crossGate();
                break;
            case BANK:
                handleBanking(inv);
                break;
        }

        return 0;
    }

    private boolean isSouthSide(WorldPosition me) {
        return me.getY() <= NORTH_SOUTH_BOUNDARY;
    }

    private boolean isNorthSide(WorldPosition me) {
        return me.getY() >= NORTH_BOUNDARY;
    }

    private boolean inCave() {
        WorldPosition pos = getWorldPosition();
        return pos != null && pos.getRegionID() == CAVE_REGION && pos.getY() > CAVE_Y_THRESHOLD;
    }

    private boolean gateOnCooldown() {
        if (!gateLocked) {
            return false;
        }
        if (System.currentTimeMillis() > gateLockUntil) {
            gateLocked = false;
            gateLockUntil = 0;
            return false;
        }
        return true;
    }

    private long getTotalNightshadeCollected() {
        return totalCollected;
    }

    private void ensureMaxZoom() {
        if (!getWidgetManager().getSettings()
                .openSubTab(SettingsTabComponent.SettingsSubTabType.DISPLAY_TAB)) {
            log("CaveNightShade", "Failed to open display settings tab");
            return;
        }

        var zoomResult = getWidgetManager().getSettings().getZoomLevel();
        Integer zoom = zoomResult != null ? zoomResult.get() : null;
        if (zoom == null) {
            log("CaveNightShade", "Failed to read zoom level");
            return;
        }

        log("CaveNightShade", "Current zoom level: " + zoom);
        if (zoom < MAX_ZOOM) {
            if (getWidgetManager().getSettings().setZoomLevel(MAX_ZOOM)) {
                log("CaveNightShade", "Zoom set to maximum (" + MAX_ZOOM + ")");
                pollFramesHuman(
                        () -> true,
                        RandomUtils.uniformRandom(ZOOM_SLEEP_MIN, ZOOM_SLEEP_MAX)
                );
            } else {
                log("CaveNightShade", "Failed to set zoom level");
            }
        }
    }

    private void crossGate() {
        WorldPosition me = getWorldPosition();
        if (me == null) {
            return;
        }

        if (gateOnCooldown()) {
            WorldPosition escape = headingToBank ? BANK_TILE : PRE_CAVE_TILE;
            if (me.distanceTo(escape) > WALK_DISTANCE_THRESHOLD) {
                walkTo(escape);
            }
            return;
        }

        if (headingToBank && isSouthSide(me)) {
            if (me.distanceTo(GATE_POSITION) <= GATE_DISTANCE_THRESHOLD) {
                RSObject gate = getObjectManager().getClosestObject(me, "City gate");
                if (gate != null && gate.interact("Open")) {
                    gateLocked = true;
                    gateLockUntil = System.currentTimeMillis() + GATE_COOLDOWN;
                    pollFramesUntil(() -> {
                        WorldPosition pos = getWorldPosition();
                        return pos != null && isNorthSide(pos);
                    }, 6000);
                }
                return;
            }
            if (me.distanceTo(GATE_SOUTH_APPROACH) > GATE_APPROACH_DISTANCE) {
                walkTo(GATE_SOUTH_APPROACH);
                return;
            }
            RSObject gate = getObjectManager().getClosestObject(me, "City gate");
            if (gate != null && gate.interact("Open")) {
                gateLocked = true;
                gateLockUntil = System.currentTimeMillis() + GATE_COOLDOWN;
                pollFramesUntil(() -> {
                    WorldPosition pos = getWorldPosition();
                    return pos != null && isNorthSide(pos);
                }, 6000);
            }
            return;
        }

        if (!headingToBank && isNorthSide(me)) {
            if (me.distanceTo(GATE_POSITION) <= GATE_DISTANCE_THRESHOLD) {
                RSObject gate = getObjectManager().getClosestObject(me, "City gate");
                if (gate != null && gate.interact("Open")) {
                    gateLocked = true;
                    gateLockUntil = System.currentTimeMillis() + GATE_COOLDOWN;
                    pollFramesUntil(() -> {
                        WorldPosition pos = getWorldPosition();
                        return pos != null && isSouthSide(pos);
                    }, 6000);
                }
                return;
            }
            if (me.distanceTo(GATE_NORTH_APPROACH) > GATE_APPROACH_DISTANCE) {
                walkTo(GATE_NORTH_APPROACH);
                return;
            }
            RSObject gate = getObjectManager().getClosestObject(me, "City gate");
            if (gate != null && gate.interact("Open")) {
                gateLocked = true;
                gateLockUntil = System.currentTimeMillis() + GATE_COOLDOWN;
                pollFramesUntil(() -> {
                    WorldPosition pos = getWorldPosition();
                    return pos != null && isSouthSide(pos);
                }, 6000);
            }
            return;
        }
    }

    private void enterCave() {
        WorldPosition me = getWorldPosition();
        if (me == null) {
            return;
        }

        if (inCave()) {
            enteringCave = false;
            state = State.PICKUP;
            return;
        }

        if (enteringCave) {
            return;
        }

        if (me.distanceTo(PRE_CAVE_TILE) > WALK_DISTANCE_THRESHOLD) {
            walkTo(PRE_CAVE_TILE);
            return;
        }

        RSObject entrance = getObjectManager().getClosestObject(me, "Cave entrance");
        if (entrance != null && entrance.interact("Enter")) {
            enteringCave = true;
            pollFramesHuman(
                    () -> true,
                    RandomUtils.uniformRandom(CAVE_ENTER_DELAY_MIN, CAVE_ENTER_DELAY_MAX)
            );
        }
    }

    private void leaveCave() {
        WorldPosition me = getWorldPosition();
        if (me == null) {
            return;
        }

        if (!inCave()) {
            return;
        }

        RSObject exit = getObjectManager().getClosestObject(me, "Cave exit");
        if (exit != null && exit.interact("Leave")) {
            pollFramesHuman(
                    () -> true,
                    RandomUtils.uniformRandom(CAVE_EXIT_DELAY_MIN, CAVE_EXIT_DELAY_MAX)
            );
        }
    }

    private void pickupNightshade() {
        if (!inCave()) {
            return;
        }

        WorldPosition me = getWorldPosition();
        if (me == null) {
            return;
        }

        if (me.distanceTo(NIGHTSHADE_TILE) > NIGHTSHADE_DISTANCE) {
            walkTo(NIGHTSHADE_TILE);
            return;
        }

        ItemGroupResult inv = getWidgetManager().getInventory().search(Set.of(CAVE_NIGHTSHADE));
        int before = inv == null ? 0 : inv.getAmount(CAVE_NIGHTSHADE);

        Polygon tilePoly = getSceneProjector().getTilePoly(NIGHTSHADE_TILE);
        if (tilePoly == null) {
            log("Cave Nightshade missing — hopping world.");
            hopFlag = true;
            return;
        }

        Polygon tight = tilePoly.getResized(NIGHTSHADE_POLY_RESIZE);
        if (tight == null || !getWidgetManager().insideGameScreen(tight, Collections.emptyList())) {
            log("Cave Nightshade not visible — hopping world.");
            hopFlag = true;
            return;
        }

        getFinger().tapGameScreen(tight, "Take");

        boolean success = pollFramesUntil(() -> {
            ItemGroupResult after = getWidgetManager().getInventory().search(Set.of(CAVE_NIGHTSHADE));
            int now = after == null ? 0 : after.getAmount(CAVE_NIGHTSHADE);
            return now > before;
        }, PICKUP_TIMEOUT);

        if (success) {
            totalCollected++;
            log("Picked up Cave Nightshade — hopping world.");
        } else {
            log("Cave Nightshade missing — hopping world.");
        }

        hopFlag = true;
    }

    private void handleBanking(ItemGroupResult inv) {
        WorldPosition me = getWorldPosition();
        if (me == null) {
            return;
        }

        if (me.distanceTo(BANK_TILE) > BANK_DISTANCE) {
            walkTo(BANK_TILE);
            return;
        }

        Polygon bankPoly = getSceneProjector().getTilePoly(BANK_TILE);
        if (bankPoly == null) {
            return;
        }

        getFinger().tap(bankPoly, "Bank");

        boolean opened = pollFramesUntil(
                () -> getWidgetManager().getBank().isVisible(),
                BANK_OPEN_TIMEOUT
        );

        if (!opened) {
            return;
        }

        int amt = inv.getAmount(CAVE_NIGHTSHADE);
        if (amt > 0) {
            getWidgetManager().getBank().deposit(CAVE_NIGHTSHADE, amt);
        }

        getWidgetManager().getBank().close();

        gateLocked = false;
        gateLockUntil = 0;
        enteringCave = false;
    }

    private void walkTo(WorldPosition pos) {
        WalkConfig cfg = new WalkConfig.Builder()
                .setWalkMethods(false, true)
                .build();
        getWalker().walkTo(pos, cfg);
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

    private String format(long ms) {
        long s = ms / 1000;
        long m = s / 60;
        long h = m / 60;
        return String.format("%02d:%02d:%02d", h, m % 60, s % 60);
    }

    @Override
    public void onPaint(Canvas c) {
        long elapsed = System.currentTimeMillis() - startTime;
        if (elapsed <= 0) {
            return;
        }

        int x = 16;
        int y = 40;
        int w = 300;
        int headerH = 45;
        int lineH = 14;
        int bodyH = 84;

        int BG = new Color(12, 14, 20, 235).getRGB();
        int BORDER = new Color(100, 100, 110, 180).getRGB();
        int DIVIDER = new Color(255, 255, 255, 40).getRGB();

        Font body = new Font("Segoe UI", Font.PLAIN, 13);

        c.fillRect(x, y, w, headerH + bodyH, BG, 1);
        c.drawRect(x, y, w, headerH + bodyH, BORDER);

        drawHeader(c, "Sainty", "Cave Nightshade", x + 14, y + 16);

        c.fillRect(x + 10, y + headerH, w - 20, 1, DIVIDER);

        int ty = y + headerH + 18;
        long perHour = (long) ((totalCollected * 3_600_000D) / elapsed);

        c.drawText("State: " + state, x + 14, ty, 0xFFAAAAFF, body);
        ty += lineH;
        c.drawText("Heading: " + (headingToBank ? "BANK" : "CAVE"), x + 14, ty, 0xFFFFAA00, body);
        ty += lineH;
        c.drawText("Runtime: " + format(elapsed), x + 14, ty, 0xFFDDDDDD, body);
        ty += lineH;
        c.drawText("Collected: " + totalCollected, x + 14, ty, 0xFF66FF66, body);
        ty += lineH;
        c.drawText("Per hour: " + perHour, x + 14, ty, 0xFF66CCFF, body);
    }
}