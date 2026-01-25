package com.osmb.script.pestcontrol;

import com.osmb.api.definition.MapDefinition;
import com.osmb.api.location.area.impl.RectangleArea;
import com.osmb.api.location.position.types.WorldPosition;
import com.osmb.api.scene.RSObject;
import com.osmb.api.script.Script;
import com.osmb.api.script.ScriptDefinition;
import com.osmb.api.script.SkillCategory;
import com.osmb.api.shape.Polygon;
import com.osmb.api.ui.overlay.HealthOverlay;
import com.osmb.api.utils.RandomUtils;
import com.osmb.api.visual.drawing.Canvas;
import com.osmb.api.walker.WalkConfig;
import com.sainty.common.Telemetry;
import com.sainty.common.VersionChecker;
import javafx.geometry.Insets;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.RadioButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.layout.VBox;

import java.awt.*;
import java.util.*;
import java.util.List;
import java.util.stream.Collectors;

@ScriptDefinition(
        name = "Dumb PestControl",
        author = "Sainty",
        version = 3.2,
        description = "Dumb Pest control - fights monsters around the void knight",
        skillCategory = SkillCategory.COMBAT
)
public class PestControl extends Script {
    private static final int REGION_LOBBY = 10537;
    private static final int REGION_GAME = 10536;
    private static final int PEST_CONTROL_WORLD = 344;
    private static final int BOAT_DECK_OBJECT = 14256;

    private static final RectangleArea COMBAT_AREA =
            new RectangleArea(2645, 2587, 24, 20, 0);
    private static final Rectangle VOID_KNIGHT_RECT =
            new Rectangle(2654, 2590, 5, 5);
    private static final WorldPosition VOID_KNIGHT_TILE =
            new WorldPosition(2656, 2592, 0);
    private static final WorldPosition SQUIRE_TILE =
            new WorldPosition(2655, 2607, 0);
    private static final int EDGE_BUFFER = 2;

    private static final RectangleArea SAFE_COMBAT_AREA =
            new RectangleArea(
                    COMBAT_AREA.getX() + EDGE_BUFFER,
                    COMBAT_AREA.getY() + EDGE_BUFFER,
                    COMBAT_AREA.getWidth() - (EDGE_BUFFER * 2),
                    COMBAT_AREA.getHeight() - (EDGE_BUFFER * 2),
                    0
            );

    private static final int BOARD_CLICK_COOLDOWN_MS = 3500;
    private static final int BOARDING_TIMEOUT_MS = 95_000;
    private static final int SCENE_STABLE_TIME_MS = 600;
    private static final int RECOVER_COOLDOWN_MS = 1200;
    private static final int WALK_COOLDOWN_MS = 1000;
    private static final int WALK_COOLDOWN_LOBBY_MS = 1200;
    private static final int RESULT_OBSERVE_TIME_MS = 2000;
    private static final int INSTANCE_SETTLE_MIN_MS = 4500;
    private static final int INSTANCE_SETTLE_MAX_MS = 6000;
    private static final int INSTANCE_SETTLE_MEAN_MS = 5100;
    private static final int INSTANCE_SETTLE_STDDEV_MS = 375;

    private static final String SCRIPT_NAME = "PestControl";
    private static final long TELEMETRY_INTERVAL_MS = 30_000;

    private long lastBoardClick = 0;
    private long boardingStart = 0;
    private boolean boardingInProgress = false;
    private boolean inGame = false;
    private boolean instanceSettled = false;
    private long instanceResolveUntil = 0;

    private boolean attacking = false;
    private HealthOverlay targetOverlay;

    private WorldPosition lastStablePos = null;
    private long lastStableTime = 0;
    private long lastWalkAt = 0;

    private Integer lastHp = null;
    private boolean deathFlag = false;

    private long lastRecoverAttempt = 0;
    private boolean recoveringToCombat = false;
    private WorldPosition recoverTarget = null;

    private int gamesWon = 0;
    private int totalPoints = 0;
    private int gamesLost = 0;

    private boolean winDetected = false;
    private int lastRegion = -1;
    private boolean awaitingResult = false;
    private boolean resultProcessed = false;
    private long awaitingResultStart = 0;

    private long startTime;
    private long scriptStartTime;
    private long lastTelemetryFlushMs = 0;

    private enum Boat {
        NOVICE("Novice", new WorldPosition(2658, 2639, 0), 2),
        INTERMEDIATE("Intermediate", new WorldPosition(2643, 2644, 0), 3),
        VETERAN("Veteran", new WorldPosition(2637, 2653, 0), 4);

        final String name;
        final WorldPosition plank;
        final int points;

        Boat(String name, WorldPosition plank, int points) {
            this.name = name;
            this.plank = plank;
            this.points = points;
        }
    }

    private Boat selectedBoat = Boat.NOVICE;

    public PestControl(Object core) {
        super(core);
    }

    @Override
    public void onStart() {
        if (!VersionChecker.isExactVersion(this)) {
            stop();
            return;
        }

        scriptStartTime = System.currentTimeMillis();
        Telemetry.sessionStart(SCRIPT_NAME);

        addCustomMap(new MapDefinition(2624, 2560, 64, 64, 0, 0));
        addCustomMap(new MapDefinition(2624, 2624, 64, 64, 0, 0));

        PestOptions opts = new PestOptions();
        Scene scene = new Scene(opts);
        scene.getStylesheets().add("style.css");
        getStageController().show(scene, "Pest Control", true);

        selectedBoat = opts.getSelectedBoat();
        startTime = System.currentTimeMillis();
    }

    @Override
    public int poll() {
        // Telemetry (threaded, non-blocking)
        long now = System.currentTimeMillis();
        if (now - lastTelemetryFlushMs >= TELEMETRY_INTERVAL_MS) {
            lastTelemetryFlushMs = now;
            long start = scriptStartTime;
            long points = totalPoints;
            long wins = gamesWon;

            Thread t = new Thread(() -> Telemetry.flush(
                    SCRIPT_NAME, start, Map.of("PC_points_gained", points, "games_won", wins)
            ));
            t.setDaemon(true);
            t.setName("PestControl-telemetry");
            t.start();
        }

        WorldPosition me = getWorldPosition();
        if (me == null) {
            return RandomUtils.gaussianRandom(250, 350, 300, 25);
        }

        int region = me.getRegionID();

        // Detect game end (game -> lobby transition)
        if (lastRegion == REGION_GAME && region == REGION_LOBBY) {
            awaitingResult = true;
            resultProcessed = false;
            awaitingResultStart = System.currentTimeMillis();
            boardingInProgress = false;
            inGame = true;
            winDetected = false;
            recoveringToCombat = false;
            recoverTarget = null;
        }
        lastRegion = region;

        // Handle game region
        if (region == REGION_GAME) {
            if (!inGame) {
                inGame = true;
                instanceSettled = false;
                instanceResolveUntil = System.currentTimeMillis() +
                        RandomUtils.gaussianRandom(
                                INSTANCE_SETTLE_MIN_MS,
                                INSTANCE_SETTLE_MAX_MS,
                                INSTANCE_SETTLE_MEAN_MS,
                                INSTANCE_SETTLE_STDDEV_MS
                        );
                return RandomUtils.gaussianRandom(250, 350, 300, 25);
            }

            if (!instanceSettled) {
                if (System.currentTimeMillis() < instanceResolveUntil) {
                    return RandomUtils.gaussianRandom(250, 350, 300, 25);
                }
                instanceSettled = true;
            }

            handleGame();
        }

        // Handle lobby region
        if (region == REGION_LOBBY) {
            if (ensureCorrectWorld()) {
                return RandomUtils.gaussianRandom(500, 700, 600, 50);
            }
            handleLobby();
        }

        return RandomUtils.gaussianRandom(40, 80, 55, 10);
    }

    private void handleGame() {
        if (!sceneIsStable()) {
            return;
        }

        trackDeath();

        WorldPosition me = getWorldPosition();
        if (me == null) {
            return;
        }

        // Check if recovered to safe area
        if (recoveringToCombat && SAFE_COMBAT_AREA.contains(me)) {
            recoveringToCombat = false;
            recoverTarget = null;
        }

        // If outside safe area, walk back
        if (!SAFE_COMBAT_AREA.contains(me)) {
            attacking = false;
            targetOverlay = null;

            if (recoveringToCombat) {
                tryWalkInsideCombatArea(recoverTarget != null ? recoverTarget : randomIn(VOID_KNIGHT_RECT));
                return;
            }

            if (System.currentTimeMillis() - lastRecoverAttempt < RECOVER_COOLDOWN_MS) {
                return;
            }

            lastRecoverAttempt = System.currentTimeMillis();
            recoverTarget = randomIn(VOID_KNIGHT_RECT);
            recoveringToCombat = true;
            tryWalkInsideCombatArea(recoverTarget);
            return;
        }

        // Check if still in combat
        if (attacking) {
            if (targetOverlay == null || !targetOverlay.isVisible()) {
                attacking = false;
                targetOverlay = null;
            } else {
                return;
            }
        }

        attacking = false;
        targetOverlay = null;
        attackNpc();
    }

    private boolean attackNpc() {
        WorldPosition me = getWorldPosition();
        if (me == null) {
            return false;
        }

        var wm = getWidgetManager();
        if (wm == null) {
            return false;
        }

        var minimap = wm.getMinimap();
        if (minimap == null) {
            return false;
        }

        List<WorldPosition> npcs = minimap.getNPCPositions().asList();

        List<WorldPosition> targets = npcs.stream()
                .filter(COMBAT_AREA::contains)
                .filter(p -> !p.equals(VOID_KNIGHT_TILE))
                .filter(p -> !p.equals(SQUIRE_TILE))
                .sorted(Comparator.comparingDouble(p -> p.distanceTo(me)))
                .limit(6)
                .collect(Collectors.toList());

        for (WorldPosition npc : targets) {
            Polygon poly = getSceneProjector().getTileCube(npc, 75);
            if (poly == null) {
                continue;
            }

            Polygon resized = poly.getResized(0.7);
            if (resized == null || resized.getBounds() == null) {
                continue;
            }

            if (!wm.insideGameScreen(resized, Collections.emptyList())) {
                continue;
            }

            if (getFinger().tapGameScreen(resized, "Attack")) {
                attacking = true;
                targetOverlay = new HealthOverlay(this);
                return true;
            }
        }
        return false;
    }

    private boolean tryWalkInsideCombatArea(WorldPosition target) {
        if (!instanceSettled || target == null) {
            return false;
        }

        if (!COMBAT_AREA.contains(target)) {
            target = randomIn(VOID_KNIGHT_RECT);
        }

        long now = System.currentTimeMillis();
        if (now - lastWalkAt < WALK_COOLDOWN_MS) {
            return true;
        }

        WorldPosition finalTarget = target;
        WalkConfig cfg = new WalkConfig.Builder()
                .setWalkMethods(false, true)
                .tileRandomisationRadius(1)
                .breakDistance(1)
                .breakCondition(() -> {
                    WorldPosition current = getWorldPosition();
                    return current != null && SAFE_COMBAT_AREA.contains(current);
                })
                .build();

        boolean issued = getWalker().walkTo(finalTarget, cfg);
        if (issued) {
            lastWalkAt = now;
        }

        return issued;
    }

    private WorldPosition randomIn(Rectangle r) {
        return new WorldPosition(
                r.x + RandomUtils.gaussianRandom(0, r.width, r.width / 2, r.width / 6),
                r.y + RandomUtils.gaussianRandom(0, r.height, r.height / 2, r.height / 6),
                0
        );
    }

    private void handleLobby() {
        // If already on boat, clear boarding flag
        if (isOnBoat()) {
            boardingInProgress = false;
            return;
        }

        // If boarding in progress, check timeout
        if (boardingInProgress) {
            if (System.currentTimeMillis() - boardingStart > BOARDING_TIMEOUT_MS) {
                boardingInProgress = false;
            }
            return;
        }

        // If awaiting game result, process it
        if (awaitingResult) {
            checkGameResult();
            return;
        }

        WorldPosition me = getWorldPosition();
        if (me == null) {
            return;
        }

        // Walk to plank if too far
        if (me.distanceTo(selectedBoat.plank) > 3) {
            tryWalkToLobby(selectedBoat.plank);
            return;
        }

        // Cooldown check before clicking plank
        if (System.currentTimeMillis() - lastBoardClick < BOARD_CLICK_COOLDOWN_MS) {
            return;
        }

        // Get plank polygon
        Polygon cube = getSceneProjector().getTileCube(selectedBoat.plank, 60);
        if (cube == null) {
            return;
        }

        Polygon click = cube.getResized(0.35);
        if (click == null) {
            return;
        }

        if (!getWidgetManager().insideGameScreen(click, Collections.emptyList())) {
            return;
        }

        boolean crossed = getFinger().tapGameScreen(click, menu -> {
            if (menu == null || menu.isEmpty()) {
                return null;
            }
            return menu.stream()
                    .filter(m -> {
                        String t = m.getRawText();
                        return t != null && t.toLowerCase().startsWith("cross");
                    })
                    .findFirst()
                    .orElse(null);
        });

        if (!crossed) {
            return;
        }

        // Set boarding state
        lastBoardClick = System.currentTimeMillis();
        boardingInProgress = true;
        boardingStart = lastBoardClick;
        inGame = true;
        instanceSettled = false;
        instanceResolveUntil = System.currentTimeMillis() +
                RandomUtils.gaussianRandom(
                        INSTANCE_SETTLE_MIN_MS,
                        INSTANCE_SETTLE_MAX_MS,
                        INSTANCE_SETTLE_MEAN_MS,
                        INSTANCE_SETTLE_STDDEV_MS
                );
        recoveringToCombat = false;
        recoverTarget = null;

    }

    private boolean ensureCorrectWorld() {
        if (!canBreak()) {
            return false;
        }

        Integer current = getCurrentWorld();
        if (current != null && current == PEST_CONTROL_WORLD) {
            return false;
        }

        getProfileManager().forceHop(worlds ->
                worlds.stream()
                        .filter(w -> w != null && w.getId() == PEST_CONTROL_WORLD)
                        .findFirst()
                        .orElse(null)
        );
        return true;
    }

    private boolean tryWalkToLobby(WorldPosition target) {
        if (target == null) {
            return false;
        }

        long now = System.currentTimeMillis();
        if (now - lastWalkAt < WALK_COOLDOWN_LOBBY_MS) {
            return true;
        }

        WalkConfig cfg = new WalkConfig.Builder()
                .tileRandomisationRadius(1)
                .breakDistance(1)
                .breakCondition(() -> {
                    WorldPosition current = getWorldPosition();
                    return current != null && current.distanceTo(target) <= 3;
                })
                .build();

        boolean issued = getWalker().walkTo(target, cfg);
        if (issued) {
            lastWalkAt = now;
        }
        return issued;
    }

    private boolean isOnBoat() {
        WorldPosition me = getWorldPosition();
        if (me == null) {
            return false;
        }

        List<RSObject> decks = getObjectManager().getObjects(BOAT_DECK_OBJECT);
        if (decks == null || decks.isEmpty()) {
            return false;
        }

        for (RSObject o : decks) {
            WorldPosition p = o.getWorldPosition();
            if (p != null && me.distanceTo(p) <= 2) {
                return true;
            }
        }
        return false;
    }

    private void checkGameResult() {
        if (resultProcessed) {
            return;
        }

        var dialogue = getWidgetManager().getDialogue();
        boolean foundWin = false;

        if (dialogue != null && dialogue.isVisible()) {
            var text = dialogue.getText();
            if (text.isFound() &&
                    text.get().toLowerCase().contains("void knight commendation")) {
                gamesWon++;
                String digits = text.get().replaceAll("\\D+", "");
                totalPoints += digits.isEmpty()
                        ? selectedBoat.points
                        : Integer.parseInt(digits);
                foundWin = true;
                winDetected = true;
            }
        }

        if (foundWin ||
                System.currentTimeMillis() - awaitingResultStart > RESULT_OBSERVE_TIME_MS) {
            if (!winDetected) {
                gamesLost++;
            }
            awaitingResult = false;
            resultProcessed = true;
            inGame = false;
            instanceSettled = false;
            recoveringToCombat = false;
            recoverTarget = null;
        }
    }

    private boolean sceneIsStable() {
        WorldPosition now = getWorldPosition();
        if (!Objects.equals(now, lastStablePos)) {
            lastStablePos = now;
            lastStableTime = System.currentTimeMillis();
            return false;
        }
        return System.currentTimeMillis() - lastStableTime >= SCENE_STABLE_TIME_MS;
    }

    private void trackDeath() {
        Integer hp = getWidgetManager().getMinimapOrbs().getHitpoints();
        if (hp == null) {
            return;
        }

        if (lastHp != null) {
            if (lastHp > 0 && hp == 0) {
                deathFlag = true;
            }
            if (deathFlag && lastHp == 0 && hp > 0) {
                attacking = false;
                targetOverlay = null;
                deathFlag = false;
                recoveringToCombat = false;
                recoverTarget = null;
            }
        }
        lastHp = hp;
    }

    @Override
    public int[] regionsToPrioritise() {
        return new int[]{REGION_LOBBY, REGION_GAME};
    }

    @Override
    public boolean canBreak() {
        return !inGame && !boardingInProgress && !awaitingResult;
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
        long elapsed = System.currentTimeMillis() - startTime;
        if (elapsed <= 0) {
            return;
        }

        int x = 16;
        int y = 40;
        int w = 240;
        int headerH = 45;
        int bodyH = 95;
        int BG = new Color(12, 14, 20, 235).getRGB();
        int BORDER = new Color(100, 100, 110, 180).getRGB();
        int DIVIDER = new Color(255, 255, 255, 40).getRGB();
        Font bodyFont = new Font("Segoe UI", Font.PLAIN, 13);

        c.fillRect(x, y, w, headerH + bodyH, BG, 1);
        c.drawRect(x, y, w, headerH + bodyH, BORDER);

        drawHeader(c, "Sainty", "Pest Control", x + 14, y + 16);

        c.fillRect(x + 10, y + headerH, w - 20, 1, DIVIDER);

        int ty = y + headerH + 18;
        long seconds = elapsed / 1000;
        long minutes = seconds / 60;
        long hours = minutes / 60;
        String runtime = String.format(
                "%02d:%02d:%02d",
                hours,
                minutes % 60,
                seconds % 60
        );

        c.drawText("Runtime: " + runtime, x + 14, ty, 0xFFFFFFFF, bodyFont);
        ty += 14;

        if (selectedBoat != null) {
            c.drawText("Boat: " + selectedBoat.name, x + 14, ty, 0xFF66CCFF, bodyFont);
            ty += 14;
        }

        c.drawText("Games won: " + gamesWon, x + 14, ty, 0xFF66FF66, bodyFont);
        ty += 14;
        c.drawText("Games lost: " + gamesLost, x + 14, ty, 0xFFFF6666, bodyFont);
        ty += 14;
        c.drawText("Points: " + totalPoints, x + 14, ty, 0xFFFFAA00, bodyFont);
    }

    private static class PestOptions extends VBox {
        private Boat selected = Boat.NOVICE;

        PestOptions() {
            getStyleClass().add("script-options");
            getStyleClass().add("dark-dialogue");
            setPadding(new Insets(10));
            setSpacing(8);

            ToggleGroup group = new ToggleGroup();
            RadioButton n = new RadioButton("Novice");
            RadioButton i = new RadioButton("Intermediate");
            RadioButton v = new RadioButton("Veteran");
            n.setToggleGroup(group);
            i.setToggleGroup(group);
            v.setToggleGroup(group);
            n.setSelected(true);

            Button confirm = new Button("Confirm");
            confirm.getStyleClass().add("action-bar-button");
            confirm.setOnAction(e -> {
                selected = v.isSelected() ? Boat.VETERAN :
                        i.isSelected() ? Boat.INTERMEDIATE :
                                Boat.NOVICE;
                getScene().getWindow().hide();
            });

            getChildren().addAll(
                    new Label("Select boat:"),
                    n, i, v, confirm
            );
        }

        Boat getSelectedBoat() {
            return selected;
        }
    }
}