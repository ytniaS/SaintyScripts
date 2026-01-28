package com.osmb.script.babayaga;

import com.osmb.api.item.ItemGroupResult;
import com.osmb.api.item.ItemSearchResult;
import com.osmb.api.location.position.types.WorldPosition;
import com.osmb.api.script.Script;
import com.osmb.api.script.ScriptDefinition;
import com.osmb.api.script.SkillCategory;
import com.osmb.api.shape.Polygon;
import com.osmb.api.ui.chatbox.dialogue.Dialogue;
import com.osmb.api.utils.RandomUtils;
import com.osmb.api.utils.UIResult;
import com.osmb.api.visual.drawing.Canvas;
import com.sainty.common.Telemetry;
import com.sainty.common.VersionChecker;
import javafx.scene.Scene;

import java.awt.*;
import java.util.*;
import java.util.List;

@ScriptDefinition(
        name = "BabaYaga Rune Buyer",
        author = "Sainty",
        version = 2.0,
        description = "Buys runes from Baba Yaga using base stock logic",
        skillCategory = SkillCategory.OTHER
)
public class BabaYagaRuneBuyer extends Script {
    private static final int REGION = 9800;
    private static final int COINS = 995;
    private static final int SEAL_OF_PASSAGE = 9083;
    private static final Font PAINT_FONT = new Font("Arial", Font.PLAIN, 13);
    private static final String SCRIPT_NAME = "BabaYagaRuneBuyer";

    private static final int MAX_NPC_DISTANCE = 6;
    private static final double TILE_CUBE_RESIZE = 0.6;

    private final List<RuneConfig> runes = new ArrayList<>();
    private RuneShopInterface shop;
    private boolean hopFlag = false;
    private boolean menuDesync = false;
    private long startTime;
    private Integer lastWorld = null;

    private final Map<Integer, Integer> inventoryBeforeShop = new HashMap<>();

    public BabaYagaRuneBuyer(Object core) {
        super(core);
    }

    @Override
    public void onStart() {
        if (!VersionChecker.isExactVersion(this)) {
            stop();
            return;
        }

        initializeRunes();

        ScriptOptions ui = new ScriptOptions(runes);
        Scene scene = new Scene(ui);
        scene.getStylesheets().add("style.css");
        getStageController().show(scene, "Baba Yaga Rune Buyer", true);

        shop = new RuneShopInterface(this);
        startTime = System.currentTimeMillis();
        Telemetry.sessionStart(SCRIPT_NAME);

        hopFlag = false;
        menuDesync = false;
    }

    private void initializeRunes() {
        runes.add(new RuneConfig(556, "Air Rune", 5000));
        runes.add(new RuneConfig(555, "Water Rune", 5000));
        runes.add(new RuneConfig(557, "Earth Rune", 5000));
        runes.add(new RuneConfig(554, "Fire Rune", 5000));
        runes.add(new RuneConfig(558, "Mind Rune", 5000));
        runes.add(new RuneConfig(559, "Body Rune", 5000));
        runes.add(new RuneConfig(562, "Chaos Rune", 250));
        runes.add(new RuneConfig(561, "Nature Rune", 250));
        runes.add(new RuneConfig(560, "Death Rune", 250));
        runes.add(new RuneConfig(563, "Law Rune", 250));
        runes.add(new RuneConfig(565, "Blood Rune", 250));
        runes.add(new RuneConfig(566, "Soul Rune", 250));
        runes.add(new RuneConfig(9075, "Astral Rune", 250));
    }

    @Override
    public int[] regionsToPrioritise() {
        return new int[]{REGION};
    }

    @Override
    public boolean canHopWorlds() {
        return hopFlag;
    }

    @Override
    public int poll() {
        Integer world = getCurrentWorld();
        if (world != null && !world.equals(lastWorld)) {
            hopFlag = false;
            lastWorld = world;
        }

        Telemetry.tick(SCRIPT_NAME, startTime, getTotalRunesBought(), "runes_bought");

        if (menuDesync) {
            handleMenuDesync();
            return 0;
        }

        if (shop.isVisible()) {
            handleShop();
            return 0;
        }

        updatePurchaseTracking();
        cacheInventoryState();

        if (!hasEnoughCoins()) {
            log("Less than 1000 coins remaining — stopping script.");
            stop();
            return 0;
        }

        ensureSealEquipped();

        if (allRunesCompleted()) {
            log("All selected runes purchased — stopping script.");
            stop();
            return 0;
        }

        if (hopFlag) {
            getProfileManager().forceHop();
            return 0;
        }

        openShop();
        return 0;
    }

    private void cacheInventoryState() {
        inventoryBeforeShop.clear();
        for (RuneConfig r : runes) {
            if (!r.enabled) {
                continue;
            }
            ItemGroupResult inv = getWidgetManager().getInventory().search(Set.of(r.itemId));
            int amount = inv == null ? 0 : inv.getAmount(r.itemId);
            inventoryBeforeShop.put(r.itemId, amount);
        }
    }

    private void updatePurchaseTracking() {
        for (RuneConfig r : runes) {
            if (!r.enabled) {
                continue;
            }

            int beforeAmount = inventoryBeforeShop.getOrDefault(r.itemId, 0);

            ItemGroupResult inv = getWidgetManager().getInventory().search(Set.of(r.itemId));
            int currentAmount = inv == null ? 0 : inv.getAmount(r.itemId);

            int gained = currentAmount - beforeAmount;
            if (gained > 0) {
                r.totalBought += gained;
            }
        }
    }

    private boolean hasEnoughCoins() {
        ItemGroupResult coins = getWidgetManager().getInventory().search(Set.of(COINS));
        return coins != null && coins.getAmount(COINS) >= 1000;
    }

    private void handleMenuDesync() {
        if (shop != null && shop.isVisible()) {
            shop.close();
        }
        hopFlag = true;
        menuDesync = false;
    }

    private boolean allRunesCompleted() {
        for (RuneConfig r : runes) {
            if (!r.enabled) {
                continue;
            }

            ItemGroupResult inv = getWidgetManager().getInventory().search(Set.of(r.itemId));
            int invAmt = inv == null ? 0 : inv.getAmount(r.itemId);

            if (invAmt < r.targetTotal) {
                return false;
            }
        }
        return true;
    }

    private void handleShop() {
        if (!shop.setSelectedAmount(50)) {
            log("Failed to select amount 50.");
            return;
        }

        if (isOutOfCoins()) {
            log("Out of coins detected — stopping script.");
            menuDesync = true;
            stop();
            return;
        }

        boolean boughtThisTick = false;

        for (RuneConfig r : runes) {
            if (!r.enabled) {
                continue;
            }

            int invAmt = inventoryBeforeShop.getOrDefault(r.itemId, 0);

            if (invAmt >= r.targetTotal) {
                continue;
            }

            ItemGroupResult shopGroup = shop.search(Set.of(r.itemId));
            if (shopGroup == null) {
                log("Shop scan returned null (grid mismatch?) — retrying.");
                continue;
            }

            ItemSearchResult shopItem = shopGroup.getItem(r.itemId);
            if (shopItem == null) {
                log("Rune not found in shop scan: " + r.name + " (" + r.itemId + ")");
                continue;
            }

            int currentStock = shopItem.getStackAmount();
            int alreadyBoughtThisWorld = r.baseStock - currentStock;

            if (alreadyBoughtThisWorld >= r.perWorld) {
                continue;
            }

            if (!shopItem.interact()) {
                log("Menu not found / interact failed — forcing shop close + hop");
                menuDesync = true;
                return;
            }

            pollFramesHuman(() -> true, RandomUtils.gaussianRandom(400, 2000, 400, 400));

            boughtThisTick = true;
            break;
        }

        if (!boughtThisTick) {
            log("No purchase possible — forcing shop close + hop");
            menuDesync = true;
        }
    }

    private boolean isOutOfCoins() {
        Dialogue dialogue = getWidgetManager().getDialogue();
        if (dialogue == null || !dialogue.isVisible()) {
            return false;
        }

        UIResult<String> textResult = dialogue.getText();
        if (textResult == null || !textResult.isFound()) {
            return false;
        }

        String text = textResult.get().toLowerCase();
        return text.contains("can't afford") ||
                text.contains("don't have enough") ||
                text.contains("need more coins");
    }

    private void openShop() {
        var npcPositions = getWidgetManager().getMinimap().getNPCPositions();
        if (npcPositions == null || npcPositions.isNotVisible()) {
            return;
        }

        WorldPosition me = getWorldPosition();
        if (me == null) {
            return;
        }

        for (WorldPosition pos : npcPositions) {
            if (me.distanceTo(pos) > MAX_NPC_DISTANCE) {
                continue;
            }

            Polygon cube = getSceneProjector().getTileCube(pos, 90);
            if (cube == null) {
                continue;
            }

            Polygon resized = cube.getResized(TILE_CUBE_RESIZE);
            if (resized == null) {
                continue;
            }

            if (!getWidgetManager().insideGameScreen(resized, Collections.emptyList())) {
                continue;
            }

            if (getFinger().tapGameScreen(resized, "Trade")) {
                pollFramesHuman(
                        () -> shop.isVisible(),
                        RandomUtils.gaussianRandom(
                                RandomUtils.uniformRandom(2500, 3500),
                                RandomUtils.uniformRandom(5500, 6500),
                                750, 750
                        )
                );
                return;
            }
        }
    }

    private void ensureSealEquipped() {
        ItemGroupResult inv = getWidgetManager().getInventory().search(Set.of(SEAL_OF_PASSAGE));

        if (inv != null && inv.contains(SEAL_OF_PASSAGE)) {
            ItemSearchResult seal = inv.getItem(SEAL_OF_PASSAGE);

            if (seal != null) {
                seal.interact("Wear");
            }
        }
    }

    private int getTotalRunesBought() {
        int total = 0;
        for (RuneConfig r : runes) {
            total += r.totalBought;
        }
        return total;
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
        int w = 300;
        int headerH = 45;
        int lineH = 14;

        int visibleLines = 0;
        for (RuneConfig r : runes) {
            if (r.enabled && r.totalBought > 0) {
                visibleLines++;
            }
        }

        int bodyH = Math.max(24, visibleLines * lineH + 10);

        int BG = new Color(12, 14, 20, 235).getRGB();
        int BORDER = new Color(100, 100, 110, 180).getRGB();
        int DIVIDER = new Color(255, 255, 255, 40).getRGB();

        c.fillRect(x, y, w, headerH + bodyH, BG, 1);
        c.drawRect(x, y, w, headerH + bodyH, BORDER);

        drawHeader(c, "Sainty", "Baba Yaga Rune Buyer", x + 14, y + 16);

        c.fillRect(x + 10, y + headerH, w - 20, 1, DIVIDER);

        int ty = y + headerH + 18;
        for (RuneConfig r : runes) {
            if (!r.enabled || r.totalBought == 0) {
                continue;
            }

            long perHour = (long) ((r.totalBought * 3_600_000D) / elapsed);
            c.drawText(
                    r.name + ": " + r.totalBought + " (" + perHour + "/hr)",
                    x + 14,
                    ty,
                    0xFFFFFFFF,
                    PAINT_FONT
            );
            ty += lineH;
        }
    }
}