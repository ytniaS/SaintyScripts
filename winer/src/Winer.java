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
        version = 1.1,
        description = "Buys wines or mixes them with Sunfire splinters.",
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
    private static final Rectangle BARTENDER_AREA =
            new Rectangle(1378, 2925, 3, 3);

    private static final WorldPosition BARTENDER_TILE =
            new WorldPosition(1376, 2927, 0);

    private static final long WALK_COOLDOWN_MS = 2500;
    private long walkCooldownUntil = 0;

    private static final int REGION_ALDARIN = 5421;

    private static final Set<Integer> INVENTORY_IDS = new HashSet<>();

    private WineMode mode;
    private WineShopInterface wineShop;
    private long scriptStartTime;

    private boolean mixing = false;
    private long mixingStartedAt = 0;

    public Winer(Object core) {
        super(core);
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
    public int poll() {

        Telemetry.tick(
                SCRIPT_NAME,
                scriptStartTime,
                System.currentTimeMillis() - scriptStartTime,
                "runtime_ms"
        );

        if (mode == null) {
            return random(200, 300);
        }

        ItemGroupResult inv =
                getWidgetManager().getInventory().search(INVENTORY_IDS);
        if (inv == null) {
            return random(120, 200);
        }

        switch (mode) {
            case BUY_WINES -> handleBuyWines(inv);
            case MIX_WINES -> handleMixWines(inv);
        }

        return random(140, 260);
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

        ItemGroupResult shop =
                wineShop.search(Collections.singleton(JUG_OF_WINE));
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
            if (!inv.contains(JUG_OF_WINE)) {
                mixing = false;
                log("Winer", "Finished Sunfire wine batch");
            }
            return;
        }

        if (!inv.contains(JUG_OF_WINE)
                || !inv.contains(SUNFIRE_SPLINTER)
                || !inv.contains(PESTLE_AND_MORTAR)) {

            if (ensureBankOpen()) {
                depositMixOutputs();
                withdrawMixMaterials();
                getWidgetManager().getBank().close();
            }

            // Re-check inventory AFTER banking
            ItemGroupResult after =
                    getWidgetManager().getInventory().search(INVENTORY_IDS);

            if (after == null
                    || !after.contains(JUG_OF_WINE)
                    || !after.contains(SUNFIRE_SPLINTER)
                    || !after.contains(PESTLE_AND_MORTAR)) {

                log("Winer",
                        "Missing mix materials after banking — stopping");
                stop();
            }

            return;
        }

        ItemSearchResult splinter = inv.getItem(SUNFIRE_SPLINTER);
        ItemSearchResult wine = inv.getItem(JUG_OF_WINE);

        if (splinter == null || wine == null) return;

        splinter.interact();
        pollFramesHuman(() -> false, random(120, 200));
        wine.interact();

        mixing = true;
    }


    private void bankAll() {

        if (!openAnyBank()) return;

        depositIfPresent(JUG_OF_WINE);
        depositIfPresent(SUNFIRE_WINE);
        depositIfPresent(EMPTY_JUG);

        pollFramesHuman(() -> false, random(200, 400));
        getWidgetManager().getBank().close();
    }

    private boolean openAnyBank() {

        if (getWidgetManager().getBank().isVisible()) {
            return true;
        }

        List<RSObject> banks =
                getObjectManager().getObjects(obj ->
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

        RSObject bankObj =
                (RSObject) getUtils().getClosest(banks);

        if (bankObj == null) return false;

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
                random(1800, 3200)
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
        try {
            ItemGroupResult inv =
                    getWidgetManager().getInventory().search(Set.of(itemId));
            if (inv == null) return;
            int amt = inv.getAmount(itemId);
            if (amt <= 0) return;
            getWidgetManager().getBank().deposit(itemId, amt);
        } catch (Exception ignored) {
        }
    }

    private void withdrawMixMaterials() {

        getWidgetManager().getBank().withdraw(SUNFIRE_SPLINTER, 27);
        getWidgetManager().getBank().withdraw(PESTLE_AND_MORTAR, 1);
        getWidgetManager().getBank().withdraw(JUG_OF_WINE, 27);
    }


    private void openWineShop() {

        if (wineShop != null && wineShop.isVisible()) {
            return;
        }

        WorldPosition me = getWorldPosition();
        if (me == null || me.getRegionID() != REGION_ALDARIN) {
            return;
        }

        // Walk to bartender first
        if (!BARTENDER_AREA.contains(me.getX(), me.getY())) {
            walkToAreaMinimap(BARTENDER_AREA);
            return;
        }

        Polygon poly = getSceneProjector().getTilePoly(BARTENDER_TILE);
        if (poly == null) {
            return;
        }

        Polygon resized = poly.getResized(0.5);
        if (resized == null) {
            return;
        }

        getFinger().tap(resized, "Trade");

        pollFramesHuman(
                () -> wineShop != null && wineShop.isVisible(),
                random(2000, 3500)
        );
    }


    private void walkToAreaMinimap(Rectangle area) {
        if (System.currentTimeMillis() < walkCooldownUntil) return;

        WorldPosition me = getWorldPosition();
        if (me == null) return;

        int x = area.x + random(area.width);
        int y = area.y + random(area.height);

        getWalker().walkTo(
                new WorldPosition(x, y, 0),
                new WalkConfig.Builder()
                        .setWalkMethods(false, true)
                        .build()
        );

        walkCooldownUntil = System.currentTimeMillis() + WALK_COOLDOWN_MS;
    }


    @Override
    public int[] regionsToPrioritise() {
        return new int[]{REGION_ALDARIN};
    }

    @Override
    public boolean promptBankTabDialogue() {
        return true;
    }

    public void onStop() {
        Telemetry.sessionEnd(SCRIPT_NAME);
    }
}
