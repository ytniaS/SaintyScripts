package com.osmb.script.packbuyer;

import com.osmb.api.item.ItemGroupResult;
import com.osmb.api.item.ItemSearchResult;
import com.osmb.api.location.area.impl.RectangleArea;
import com.osmb.api.location.position.types.WorldPosition;
import com.osmb.api.script.Script;
import com.osmb.api.script.ScriptDefinition;
import com.osmb.api.script.SkillCategory;
import com.osmb.api.shape.Polygon;
import com.osmb.api.utils.RandomUtils;
import com.osmb.api.visual.drawing.Canvas;
import com.osmb.script.packbuyer.javafx.ScriptOptions;
import com.sainty.common.Telemetry;
import com.sainty.common.VersionChecker;
import javafx.scene.Scene;

import java.awt.*;
import java.util.Collections;
import java.util.Map;
import java.util.Set;

@ScriptDefinition(
        name = "Pack Buyer",
        author = "Sainty",
        version = 2.1,
        description = "Buys and opens feather packs or broad arrowhead packs using base stock logic",
        skillCategory = SkillCategory.OTHER
)
public class PackBuyer extends Script {
    private static final int COINS = 995;
    private static final int FEATHER_PACK = 11881;
    private static final int FEATHERS = 314;
    private static final int BROAD_ARROW_PACK = 11885;
    private static final int BROAD_ARROWHEADS = 11874;
    private static final int AMYLASE_PACK = 12641;
    private static final int AMYLASE_CRYSTAL = 12640;
    private static final int MARK_OF_GRACE = 11849;
    private static final String SCRIPT_NAME = "PackBuyer";
    private static final Font PAINT_FONT = new Font("Arial", Font.PLAIN, 13);
    private static final int MAX_NPC_DISTANCE = 6;
    private static final double TILE_CUBE_RESIZE = 0.6;

    private static final WorldPosition GRACE_EXCLUDED_TILE = new WorldPosition(3051, 4963, 1);

    private static final PackModeConfig FEATHER_GERRANT =
            new PackModeConfig(
                    BuyMode.FEATHERS,
                    "Feathers – Gerrant (Port Sarim)",
                    "gerrant's fishy business",
                    new RectangleArea(3011, 3219, 6, 10, 0),
                    12082,
                    FEATHER_PACK,
                    FEATHERS,
                    100,
                    50,
                    200
            );

    private static final PackModeConfig BROAD_SPIRA =
            new PackModeConfig(
                    BuyMode.BROAD_ARROWHEADS,
                    "Broad packs – Spira (Draynor)",
                    "slayer equipment",
                    new RectangleArea(3089, 3265, 6, 3, 0),
                    12339,
                    BROAD_ARROW_PACK,
                    BROAD_ARROWHEADS,
                    800,
                    50,
                    5500
            );

    private static final PackModeConfig BROAD_TURAEL =
            new PackModeConfig(
                    BuyMode.BROAD_ARROWHEADS,
                    "Broad packs – Turael (Burthorpe)",
                    "slayer equipment",
                    new RectangleArea(2930, 3535, 3, 3, 0),
                    11575,
                    BROAD_ARROW_PACK,
                    BROAD_ARROWHEADS,
                    800,
                    50,
                    5500
            );

    private static final PackModeConfig AMYLASE_GRACE =
            new PackModeConfig(
                    BuyMode.AMYLASE,
                    "Amylase packs – Grace (Rogues' Den)",
                    "grace's graceful clothing",
                    new RectangleArea(3046, 4961, 6, 4, 1),
                    12109,
                    AMYLASE_PACK,
                    AMYLASE_CRYSTAL,
                    1000,
                    50,
                    10
            );

    private PackModeConfig config;
    private GenericShopInterface shop;
    private boolean hopFlag = false;
    private boolean menuDesync = false;
    private Integer lastWorld = null;

    private long totalCost = 0;
    private long packsOpened = 0;
    private long itemsGained = 0;
    private long scriptStartTime;
    private long startTime;

    private int cachedPackCount = 0;

    public PackBuyer(Object core) {
        super(core);
    }

    private int getShopOpenTimeout() {
        return RandomUtils.gaussianRandom(
                RandomUtils.uniformRandom(2500, 3500),
                RandomUtils.uniformRandom(5500, 6500),
                750, 750
        );
    }

    @Override
    public void onStart() {
        if (!VersionChecker.isExactVersion(this)) {
            stop();
            return;
        }

        scriptStartTime = System.currentTimeMillis();
        Telemetry.sessionStart(SCRIPT_NAME);

        ScriptOptions ui = new ScriptOptions(
                FEATHER_GERRANT,
                BROAD_SPIRA,
                BROAD_TURAEL,
                AMYLASE_GRACE,
                selected -> {
                    config = selected;
                    shop = new GenericShopInterface(this, config.shopTitle);
                    startTime = System.currentTimeMillis();
                    log("Config received | mode=" + config.mode +
                            " perWorld=" + config.perWorld +
                            " target=" + config.targetTotal);
                }
        );

        Scene scene = new Scene(ui);
        scene.getStylesheets().add("style.css");
        getStageController().show(scene, "Pack Buyer", true);

        hopFlag = false;
        menuDesync = false;
    }

    @Override
    public int[] regionsToPrioritise() {
        if (config == null || config.region <= 0) {
            return new int[0];
        }
        return new int[]{config.region};
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

        Telemetry.flush(
                SCRIPT_NAME,
                scriptStartTime,
                Map.of(
                        "packs_opened", packsOpened,
                        "items_gained", itemsGained,
                        "gp_spent", totalCost
                )
        );

        if (menuDesync) {
            log("Menu desync → closing + hopping");
            closeShop();
            hopFlag = true;
            menuDesync = false;
            return 0;
        }

        if (shop.isVisible()) {
            log("Shop visible → handleShop()");
            handleShop();
            return 0;
        }

        if (hasPack()) {
            log("Pack detected in inventory");
            openPack();
            return 0;
        }

        if (config.totalOpened >= config.targetTotal) {
            log("Target reached → stopping");
            stop();
            return 0;
        }

        if (!hasCurrency()) {
            log("Insufficient currency → stopping");
            stop();
            return 0;
        }

        if (hopFlag) {
            log("Hop flag set → hopping");
            getProfileManager().forceHop();
            return 0;
        }

        cachedPackCount = getInventoryAmount(config.packItemId);
        openShop();
        return 0;
    }

    private boolean hasCurrency() {
        int currencyId;
        int requiredAmount;

        if (config.mode == BuyMode.AMYLASE) {
            currencyId = MARK_OF_GRACE;
            requiredAmount = 10;
        } else {
            currencyId = COINS;
            requiredAmount = 10000;
        }

        ItemGroupResult inv = getWidgetManager().getInventory().search(Set.of(currencyId));
        return inv != null && inv.getAmount(currencyId) >= requiredAmount;
    }

    private void handleShop() {
        if (!shop.setSelectedAmount(config.buyAmount)) {
            log("Failed to set buy amount");
            return;
        }

        ItemGroupResult shopGroup = shop.search(Set.of(config.packItemId));
        if (shopGroup == null) {
            log("ShopGroup null");
            return;
        }

        ItemSearchResult shopItem = shopGroup.getItem(config.packItemId);
        if (shopItem == null) {
            log("ShopItem null");
            return;
        }

        int stock = shopItem.getStackAmount();
        int alreadyBought = config.baseStock - stock;

        if (config.perWorld > 0 && alreadyBought >= config.perWorld) {
            log("Per-world cap reached → hop");
            closeShop();
            hopFlag = true;
            return;
        }

        if (!shopItem.interact()) {
            log("Interact failed → menuDesync");
            menuDesync = true;
            return;
        }

        pollFramesHuman(() -> true, RandomUtils.gaussianRandom(400, 2000, 400, 400));

        closeShop();

        boolean success = pollFramesUntil(() -> !shop.isVisible(), RandomUtils.uniformRandom(1800, 2200));
        if (!success) {
            log("Shop didn't close");
            return;
        }

        int after = getInventoryAmount(config.packItemId);
        int gained = Math.max(0, after - cachedPackCount);

        if (gained <= 0) {
            log("Inventory delta zero → hop");
            hopFlag = true;
            return;
        }

        long batchCost = calculateBatchCost(config.basePrice, alreadyBought, gained);
        totalCost += batchCost;
        log("Bought pack x" + gained + " | cost=" + batchCost);
    }

    private void openPack() {
        ItemGroupResult inv = getWidgetManager().getInventory().search(Set.of(config.packItemId));
        if (inv == null) {
            return;
        }

        ItemSearchResult pack = inv.getItem(config.packItemId);
        if (pack == null) {
            return;
        }

        int before = getInventoryAmount(config.openedItemId);

        if (!getFinger().tap(pack.getBounds())) {
            log("Failed to tap pack");
            return;
        }

        if (pollFramesUntil(
                () -> getInventoryAmount(config.openedItemId) > before,
                RandomUtils.uniformRandom(3000, 4000)
        )) {
            int after = getInventoryAmount(config.openedItemId);
            packsOpened++;
            int gained = Math.max(0, after - before);
            itemsGained += gained;
            config.totalOpened += gained;
            log("Opened pack → items gained=" + gained);
        }
    }

    private int getInventoryAmount(int id) {
        ItemGroupResult inv = getWidgetManager().getInventory().search(Set.of(id));
        return inv == null ? 0 : inv.getAmount(id);
    }

    private boolean hasPack() {
        ItemGroupResult inv = getWidgetManager().getInventory().search(Set.of(config.packItemId));
        return inv != null && inv.contains(config.packItemId);
    }

    private void openShop() {
        var npcPositions = getWidgetManager().getMinimap().getNPCPositions();
        if (npcPositions == null) {
            return;
        }

        WorldPosition me = getWorldPosition();
        if (me == null) {
            return;
        }

        for (WorldPosition pos : npcPositions) {
            if (config.mode == BuyMode.AMYLASE && pos.equals(GRACE_EXCLUDED_TILE)) {
                continue;
            }

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
                log("Attempted to open shop");
                pollFramesHuman(
                        () -> shop.isVisible(),
                        getShopOpenTimeout()
                );
                return;
            }
        }
    }

    private void closeShop() {
        if (shop != null && shop.isVisible()) {
            shop.close();
        }
    }

    private long calculateBatchCost(int basePrice, int alreadyBought, int quantity) {
        double total = 0;
        for (int i = 0; i < quantity; i++) {
            double multiplier = 1.0 + ((alreadyBought + i) * 0.001);
            total += basePrice * multiplier;
        }
        return Math.round(total);
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

    private String formatRuntime(long ms) {
        long totalSeconds = ms / 1000;
        long hours = totalSeconds / 3600;
        long minutes = (totalSeconds % 3600) / 60;
        long seconds = totalSeconds % 60;
        return String.format("%02d:%02d:%02d", hours, minutes, seconds);
    }

    private String formatETA(long ms) {
        if (ms <= 0) {
            return "00:00:00";
        }
        return formatRuntime(ms);
    }

    private String formatCost(long gp) {
        if (gp >= 1_000_000) {
            return String.format("%.2fM gp", gp / 1_000_000D);
        }
        if (gp >= 1_000) {
            return String.format("%.1fK gp", gp / 1_000D);
        }
        return gp + " gp";
    }

    @Override
    public void onPaint(Canvas c) {
        long elapsed = System.currentTimeMillis() - startTime;
        if (elapsed <= 0) {
            return;
        }

        double hours = elapsed / 3_600_000D;
        long perHour = hours > 0.01 ? (long) (config.totalOpened / hours) : 0;

        int remaining = Math.max(0, config.targetTotal - config.totalOpened);
        String eta = "—";
        if (perHour > 0 && remaining > 0) {
            long etaMs = (long) ((remaining / (double) perHour) * 3_600_000D);
            eta = formatETA(etaMs);
        }

        double avgCostPerItem = config.totalOpened > 0 ? totalCost / (double) config.totalOpened : 0;
        long estimatedRemainingCost = (long) (remaining * avgCostPerItem);
        long estimatedTotalCost = totalCost + estimatedRemainingCost;

        int x = 16;
        int y = 40;
        int w = 240;
        int headerH = 65;
        int lineH = 14;
        int lines = 5;
        int bodyH = (lines * lineH) + 10;

        int BG = new Color(12, 14, 20, 235).getRGB();
        int BORDER = new Color(100, 100, 110, 180).getRGB();
        int DIVIDER = new Color(255, 255, 255, 40).getRGB();

        Font bodyFont = new Font("Segoe UI", Font.PLAIN, 13);

        c.fillRect(x, y, w, headerH + bodyH, BG, 1);
        c.drawRect(x, y, w, headerH + bodyH, BORDER);

        drawHeader(c, "Sainty", "Pack Buyer", x + 14, y + 16);

        c.fillRect(x + 10, y + headerH, w - 20, 1, DIVIDER);

        int ty = y + headerH + 18;
        c.drawText("Opened: " + config.totalOpened + " / " + config.targetTotal, x + 14, ty, 0xFFFFFFFF, bodyFont);
        ty += lineH;
        c.drawText("Rate: " + perHour + "/hr", x + 14, ty, 0xFF66CCFF, bodyFont);
        ty += lineH;
        c.drawText("Time to target: " + eta, x + 14, ty, 0xFFFFAA00, bodyFont);
        ty += lineH;
        c.drawText("Cost so far: " + formatCost(totalCost), x + 14, ty, 0xFFFFFFFF, bodyFont);
        ty += lineH;
        c.drawText("Est. total cost: " + formatCost(estimatedTotalCost), x + 14, ty, 0xFFAA66FF, bodyFont);
    }
}