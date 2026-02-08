package com.osmb.script.winer;

import com.osmb.api.item.ItemGroupResult;
import com.osmb.api.item.ItemSearchResult;
import com.osmb.api.location.position.types.WorldPosition;
import com.osmb.api.scene.RSObject;
import com.osmb.api.script.Script;
import com.osmb.api.script.ScriptDefinition;
import com.osmb.api.script.SkillCategory;
import com.osmb.api.shape.Polygon;
import com.osmb.api.shape.Rectangle;
import com.osmb.api.utils.RandomUtils;
import com.osmb.api.walker.WalkConfig;
import com.sainty.common.Telemetry;
import javafx.scene.Scene;

import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

@ScriptDefinition(
        name = "Winer",
        author = "Sainty",
        version = 3.1,
        threadUrl = "https://wiki.osmb.co.uk/article/saintys-winer",
        skillCategory = SkillCategory.OTHER
)
public class Winer extends Script {

    private static final String SCRIPT_NAME = "Winer";

    private static final int COINS = 995;
    private static final int JUG_OF_WINE = 1993;
    private static final int EMPTY_JUG = 1935;
    private static final int SUNFIRE_SPLINTER = 28924;
    private static final int PESTLE_AND_MORTAR = 233;
    private static final int SUNFIRE_WINE = 29382;
    private static final int REGION_ALDARIN = 5421;
    private static final double BARTENDER_RESIZE = 0.5;

    private static final Rectangle BARTENDER_AREA = new Rectangle(1378, 2925, 3, 3);
    private static final WorldPosition BARTENDER_TILE = new WorldPosition(1376, 2927, 0);

    private static final Set<Integer> INVENTORY_IDS = new HashSet<>();

    private WineMode mode;
    private WineShopInterface wineShop;
    private long scriptStartTime;
    private long walkCooldownUntil = 0;

    private boolean mixing = false;
    private long mixingStartedAt = 0;
    private int wineCountWhenMixingStarted = -1;
    private long lastWineCountCheck = 0;

    private long getWineCountCheckInterval() {
        return RandomUtils.gaussianRandom(10_000, 15_000, 12_500, 1_250);
    }

    public Winer(Object core) {
        super(core);
    }

    private long getWalkCooldown() {
        return RandomUtils.uniformRandom(2000, 3000);
    }

    private int getBankOpenTimeout() {
        return RandomUtils.gaussianRandom(
                RandomUtils.uniformRandom(1500, 2000),
                RandomUtils.uniformRandom(3000, 3500),
                350, 350
        );
    }

    private int getShopOpenTimeout() {
        return RandomUtils.gaussianRandom(
                RandomUtils.uniformRandom(1800, 2200),
                RandomUtils.uniformRandom(3200, 3800),
                350, 350
        );
    }

    @Override
    public void onStart() {
        scriptStartTime = System.currentTimeMillis();
        Telemetry.sessionStart(SCRIPT_NAME);

        WinerOptions ui = new WinerOptions(selectedMode -> mode = selectedMode);
        Scene scene = new Scene(ui);
        scene.getStylesheets().add("style.css");
        getStageController().show(scene, "Winer Options", false);

        INVENTORY_IDS.clear();
        Collections.addAll(
                INVENTORY_IDS,
                COINS,
                JUG_OF_WINE,
                EMPTY_JUG,
                SUNFIRE_SPLINTER,
                PESTLE_AND_MORTAR,
                SUNFIRE_WINE
        );

        wineShop = new WineShopInterface(this);
        getWidgetManager().getInventory().registerInventoryComponent(wineShop);

        log("Winer", "Started. Mode=" + mode);
    }

    @Override
    public int[] regionsToPrioritise() {
        return new int[]{REGION_ALDARIN};
    }

    @Override
    public boolean promptBankTabDialogue() {
        return true;
    }

    @Override
    public int poll() {
        Telemetry.tick(
                SCRIPT_NAME,
                scriptStartTime,
                System.currentTimeMillis() - scriptStartTime,
                "runtime_ms"
        );

        if (mode == null) {
            return RandomUtils.gaussianRandom(200, 1200, 300, 300);
        }

        ItemGroupResult inv = getWidgetManager().getInventory().search(INVENTORY_IDS);
        if (inv == null) {
            return RandomUtils.gaussianRandom(150, 1000, 250, 250);
        }

        switch (mode) {
            case BUY_WINES -> handleBuyWines(inv);
            case MIX_WINES -> handleMixWines(inv);
        }

        return RandomUtils.gaussianRandom(200, 1200, 300, 300);
    }

    private void handleBuyWines(ItemGroupResult inv) {
        if (!inv.contains(COINS)) {
            log("Winer", "Out of coins — stopping BUY_WINES mode");
            stop();
            return;
        }

        if (inv.getFreeSlots() == 0 && inv.contains(JUG_OF_WINE)) {
            bankAll();
            return;
        }

        if (wineShop != null && wineShop.isVisible()) {
            buyWineLoop();
            return;
        }

        openWineShop();
    }

    private void buyWineLoop() {
        ItemGroupResult shop = wineShop.search(Collections.singleton(JUG_OF_WINE));
        if (shop == null) {
            wineShop.close();
            return;
        }

        ItemSearchResult wine = shop.getItem(JUG_OF_WINE);
        if (wine == null || wine.getStackAmount() <= 0) {
            wineShop.close();
            return;
        }

        wineShop.setSelectedAmount(10);
        wine.interact();
    }

    private void handleMixWines(ItemGroupResult inv) {
        if (mixing) {
            long now = System.currentTimeMillis();
            int currentWineCount = inv.getAmount(JUG_OF_WINE);
            int currentSplinterCount = inv.getAmount(SUNFIRE_SPLINTER);

            // Finished: no more regular wine to convert
            if (currentWineCount == 0) {
                mixing = false;
                mixingStartedAt = 0;
                wineCountWhenMixingStarted = -1;
                lastWineCountCheck = 0;
                log("Winer", "Finished Sunfire wine batch - out of regular wine");
                // fall through to banking / restart logic below
            }
            // Not enough splinters left to perform at least one more mix
            else if (currentSplinterCount < 2) {
                log("Winer", "Only " + currentSplinterCount + " splinter(s) left while mixing - resetting state to rebank");
                mixing = false;
                mixingStartedAt = 0;
                wineCountWhenMixingStarted = -1;
                lastWineCountCheck = 0;
                // fall through to banking / restart logic below
            }
            // Check if wine count has changed (mixing is progressing)
            else if (wineCountWhenMixingStarted >= 0) {
                // Check if enough time has passed to verify progress
                if (now - lastWineCountCheck >= getWineCountCheckInterval()) {
                    if (currentWineCount >= wineCountWhenMixingStarted) {
                        // Wine count hasn't decreased - mixing may have stalled, retry
                        log("Winer", "Wine count hasn't decreased (" + currentWineCount + " >= " + wineCountWhenMixingStarted + ") - retrying mixing");
                        mixing = false;
                        mixingStartedAt = 0;
                        wineCountWhenMixingStarted = -1;
                        lastWineCountCheck = 0;
                        // fall through to banking / restart logic below
                    } else {
                        // Wine count decreased - mixing is progressing
                        wineCountWhenMixingStarted = currentWineCount;
                        lastWineCountCheck = now;
                        return;
                    }
                } else {
                    // Not yet time to re-check progress
                    return;
                }
            } else {
                // Initialize tracking if not set
                wineCountWhenMixingStarted = currentWineCount;
                lastWineCountCheck = now;
                return;
            }
        }

        // Ensure we have enough materials BEFORE attempting another batch.
        int splinters = inv.getAmount(SUNFIRE_SPLINTER);
        if (!inv.contains(JUG_OF_WINE)
                || !inv.contains(PESTLE_AND_MORTAR)
                || splinters < 2) {

            if (ensureBankOpen()) {
                depositMixOutputs();
                withdrawMixMaterials();
                getWidgetManager().getBank().close();
            }

            ItemGroupResult after = getWidgetManager().getInventory().search(INVENTORY_IDS);

            if (after == null
                    || !after.contains(JUG_OF_WINE)
                    || !after.contains(SUNFIRE_SPLINTER)
                    || !after.contains(PESTLE_AND_MORTAR)) {

                log("Winer", "Missing mix materials after banking — stopping");
                stop();
            }

            return;
        }

        ItemSearchResult splinter = inv.getItem(SUNFIRE_SPLINTER);
        ItemSearchResult wine = inv.getItem(JUG_OF_WINE);

        if (splinter == null || wine == null) {
            return;
        }

        splinter.interact();
        pollFramesHuman(() -> true, RandomUtils.gaussianRandom(150, 1200, 300, 300));
        wine.interact();

        mixing = true;
        mixingStartedAt = System.currentTimeMillis();
        wineCountWhenMixingStarted = inv.getAmount(JUG_OF_WINE);
        lastWineCountCheck = System.currentTimeMillis();
    }

    private void bankAll() {
        if (!openAnyBank()) {
            return;
        }

        depositIfPresent(JUG_OF_WINE);
        depositIfPresent(SUNFIRE_WINE);
        depositIfPresent(EMPTY_JUG);

        pollFramesHuman(() -> true, RandomUtils.gaussianRandom(200, 1200, 300, 300));
        getWidgetManager().getBank().close();
    }

    private boolean openAnyBank() {
        if (getWidgetManager().getBank().isVisible()) {
            return true;
        }

        List<RSObject> banks = getObjectManager().getObjects(obj ->
                obj != null && (
                        "Bank booth".equals(obj.getName()) ||
                                "Bank chest".equals(obj.getName()) ||
                                "Closed booth".equals(obj.getName()) ||
                                "Bank table".equals(obj.getName())
                )
        );

        if (banks.isEmpty()) {
            log("Winer", "No bank objects found");
            return false;
        }

        RSObject bankObj = (RSObject) getUtils().getClosest(banks);

        if (bankObj == null) {
            return false;
        }

        if ("Closed booth".equals(bankObj.getName())) {
            if (!bankObj.interact("Bank booth", new String[]{"Bank"})) {
                return false;
            }
        } else {
            if (!bankObj.interact("Bank")) {
                return false;
            }
        }

        return pollFramesHuman(
                () -> getWidgetManager().getBank().isVisible(),
                getBankOpenTimeout()
        );
    }

    private boolean ensureBankOpen() {
        if (getWidgetManager().getBank().isVisible()) {
            return true;
        }

        return openAnyBank();
    }

    private void depositMixOutputs() {
        depositIfPresent(SUNFIRE_WINE);
        depositIfPresent(EMPTY_JUG);
    }

    private void depositIfPresent(int itemId) {
        ItemGroupResult inv = getWidgetManager().getInventory().search(Set.of(itemId));
        if (inv == null) {
            return;
        }

        int amt = inv.getAmount(itemId);
        if (amt <= 0) {
            return;
        }

        getWidgetManager().getBank().deposit(itemId, amt);
    }

    private void withdrawMixMaterials() {
        // Ensure we have at least 2 splinters total (bank + inventory) before attempting to mix
        ItemGroupResult invSplinters = getWidgetManager().getInventory().search(Set.of(SUNFIRE_SPLINTER));
        int invSplinterCount = invSplinters == null ? 0 : invSplinters.getAmount(SUNFIRE_SPLINTER);

        ItemGroupResult bankSplinters = getWidgetManager().getBank().search(Set.of(SUNFIRE_SPLINTER));
        int bankSplinterCount = bankSplinters == null ? 0 : bankSplinters.getAmount(SUNFIRE_SPLINTER);

        int totalSplinters = invSplinterCount + bankSplinterCount;
        if (totalSplinters < 2) {
            log("Winer", "Not enough Sunfire splinters to continue mixing (total=" + totalSplinters + ") — stopping");
            stop();
            return;
        }

        // Target up to 54 splinters in inventory (2 per wine for 27 wines), but never more than total available
        int targetSplintersInInv = Math.min(54, totalSplinters);
        int needSplinters = Math.max(0, targetSplintersInInv - invSplinterCount);
        if (needSplinters > 0 && bankSplinterCount > 0) {
            getWidgetManager().getBank().withdraw(SUNFIRE_SPLINTER, Math.min(needSplinters, bankSplinterCount));
        }

        // Always ensure we have a pestle and mortar
        getWidgetManager().getBank().withdraw(PESTLE_AND_MORTAR, 1);

        // Withdraw up to 27 wines (or as many as available)
        ItemGroupResult bankWines = getWidgetManager().getBank().search(Set.of(JUG_OF_WINE));
        int bankWineCount = bankWines == null ? 0 : bankWines.getAmount(JUG_OF_WINE);
        if (bankWineCount > 0) {
            getWidgetManager().getBank().withdraw(JUG_OF_WINE, Math.min(27, bankWineCount));
        }
    }

    private void openWineShop() {
        if (wineShop != null && wineShop.isVisible()) {
            return;
        }

        WorldPosition me = getWorldPosition();
        if (me == null || me.getRegionID() != REGION_ALDARIN) {
            return;
        }

        if (!BARTENDER_AREA.contains(me.getX(), me.getY())) {
            walkToAreaMinimap(BARTENDER_AREA);
            return;
        }

        Polygon poly = getSceneProjector().getTilePoly(BARTENDER_TILE);
        if (poly == null) {
            return;
        }

        Polygon resized = poly.getResized(BARTENDER_RESIZE);
        if (resized == null) {
            return;
        }

        getFinger().tapGameScreen(resized, "Trade");

        pollFramesHuman(
                () -> wineShop != null && wineShop.isVisible(),
                getShopOpenTimeout()
        );
    }

    private void walkToAreaMinimap(Rectangle area) {
        if (System.currentTimeMillis() < walkCooldownUntil) {
            return;
        }

        WorldPosition me = getWorldPosition();
        if (me == null) {
            return;
        }

        int x = area.x + RandomUtils.uniformRandom(0, area.width);
        int y = area.y + RandomUtils.uniformRandom(0, area.height);

        getWalker().walkTo(
                new WorldPosition(x, y, 0),
                new WalkConfig.Builder()
                        .setWalkMethods(false, true)
                        .build()
        );

        walkCooldownUntil = System.currentTimeMillis() + getWalkCooldown();
    }


    public void onStop() {
        Telemetry.sessionEnd(SCRIPT_NAME);
    }
}