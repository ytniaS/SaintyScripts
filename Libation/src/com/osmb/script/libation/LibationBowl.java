package com.osmb.script.libation;

import com.osmb.api.item.ItemGroupResult;
import com.osmb.api.item.ItemSearchResult;
import com.osmb.api.location.position.types.WorldPosition;
import com.osmb.api.scene.RSObject;
import com.osmb.api.script.Script;
import com.osmb.api.script.ScriptDefinition;
import com.osmb.api.script.SkillCategory;
import com.osmb.api.shape.Polygon;
import com.osmb.api.trackers.experience.XPTracker;
import com.osmb.api.ui.component.tabs.skill.SkillType;
import com.osmb.api.utils.RandomUtils;
import com.osmb.api.visual.drawing.Canvas;
import com.osmb.api.walker.WalkConfig;
import com.sainty.common.Telemetry;
import com.sainty.common.VersionChecker;
import javafx.scene.Scene;

import java.awt.*;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

@ScriptDefinition(
        name = "LibationBowl",
        author = "Sainty",
        version = 4.0,
        description = "Buys wine, optionally converts to Sunfire wine, blesses, sacrifices, banks jugs.",
        skillCategory = SkillCategory.PRAYER
)
public class LibationBowl extends Script {

    private static final int COINS = 995;
    private static final int BONE_SHARDS = 29381;
    private static final int JUG_OF_WINE = 1993;
    private static final int BLESSED_WINE = 29386;
    private static final int EMPTY_JUG = 1935;
    private static final int SUNFIRE_SPLINTER = 28924;
    private static final int PESTLE_AND_MORTAR = 233;
    private static final int SUNFIRE_WINE = 29382;
    private static final int BLESSED_SUNFIRE_WINE = 29384;

    private static final int REGION_ALDARIN = 5421;
    private static final int REGION_TEOMAT = 5681;

    private static final WorldPosition QUETZAL_ALDARIN = new WorldPosition(1389, 2899, 0);
    private static final WorldPosition QUETZAL_TEOMAT = new WorldPosition(1437, 3169, 0);
    private static final WorldPosition BARTENDER_TILE = new WorldPosition(1376, 2927, 0);
    private static final WorldPosition BARTENDER_APPROACH_TILE = new WorldPosition(1385, 2927, 0);
    private static final WorldPosition BANKER_TILE = new WorldPosition(1401, 2928, 0);
    private static final WorldPosition BOWL_TILE = new WorldPosition(1458, 3187, 0);
    private static final WorldPosition BOWL_STAGING_TILE = new WorldPosition(1457, 3189, 0);

    private static final Rectangle BARTENDER_AREA = new Rectangle(1378, 2925, 3, 3);
    private static final Rectangle BARTENDER_APPROACH_AREA = new Rectangle(1385, 2924, 6, 5);
    private static final Rectangle BANKER_AREA = new Rectangle(1396, 2925, 3, 3);
    private static final Rectangle ALTAR_AREA = new Rectangle(1433, 3147, 5, 5);
    private static final Rectangle SHRINE_AREA = new Rectangle(1448, 3172, 5, 4);
    private static final Rectangle BOWL_AREA = new Rectangle(1457, 3188, 1, 3);

    private static final Rectangle ALDARIN_RECT = new Rectangle(273, 494, 27, 18);
    private static final Rectangle TEOMAT_RECT = new Rectangle(284, 375, 19, 20);

    private static final int MIN_PRAYER_POINTS = 2;
    private boolean shopPurchasing = false;
    private long shopPurchaseStarted = 0;

    private static final String SCRIPT_NAME = "LibationBowl";

    private long travelCooldownUntil = 0;
    private long walkCooldownUntil = 0;
    private long sunfireStartedAt = 0;
    private long scriptStartTime;

    private boolean useSunfire = false;
    private boolean useBankedWine = false;

    private XPTracker prayerXP;
    private WineShopInterface wineShop;

    private static final Set<Integer> INVENTORY_IDS = new HashSet<>();
    private static final Set<Integer> SHOP_WINE_IDS = new HashSet<>();

    private enum SunfireState {
        IDLE, SPLINTER_SELECTED, PROCESSING
    }

    private enum LibationTask {
        BASK_AT_SHRINE,
        HANDLE_WINE_SHOP,
        USE_LIBATION_BOWL,
        HANDLE_SUNFIRE,
        BLESS_WINE,
        ACQUIRE_WINE
    }

    private static class LibationContext {
        final ItemGroupResult inv;
        final Integer prayer;
        final boolean shopVisible;
        final long now;

        LibationContext(ItemGroupResult inv, Integer prayer, boolean shopVisible, long now) {
            this.inv = inv;
            this.prayer = prayer;
            this.shopVisible = shopVisible;
            this.now = now;
        }
    }

    private SunfireState sunfireState = SunfireState.IDLE;


    public LibationBowl(Object core) {
        super(core);
    }

    private long getTravelCooldown() {
        return RandomUtils.uniformRandom(4000, 5000);
    }

    private long getWalkCooldown() {
        return RandomUtils.uniformRandom(2500, 3500);
    }

    private long getShopPurchaseTimeout() {
        return RandomUtils.uniformRandom(4500, 5500);
    }

    @Override
    public void onStart() {
        if (!VersionChecker.isExactVersion(this)) {
            stop();
            return;
        }

        scriptStartTime = System.currentTimeMillis();
        Telemetry.sessionStart(SCRIPT_NAME);

        ScriptOptions ui = new ScriptOptions();
        getStageController().show(new Scene(ui), "Libation Bowl Options", false);

        useSunfire = ui.useSunfire();
        useBankedWine = ui.useBankedWine();

        prayerXP = getXPTrackers().get(SkillType.PRAYER);

        INVENTORY_IDS.clear();
        Collections.addAll(
                INVENTORY_IDS,
                COINS, BONE_SHARDS, JUG_OF_WINE, BLESSED_WINE,
                EMPTY_JUG, SUNFIRE_SPLINTER, PESTLE_AND_MORTAR,
                SUNFIRE_WINE, BLESSED_SUNFIRE_WINE
        );

        SHOP_WINE_IDS.clear();
        SHOP_WINE_IDS.add(JUG_OF_WINE);

        wineShop = new WineShopInterface(this);
        getWidgetManager().getInventory().registerInventoryComponent(wineShop);

        log("LibationBowl",
                "Started. Sunfire=" + useSunfire + ", BankedWine=" + useBankedWine);
    }

    @Override
    public int poll() {
        handleHousekeeping();

        if (travelOnCooldown()) return 0;

        LibationContext ctx = collectContext();
        if (ctx == null) return 0;

        if (shouldStop(ctx)) {
            stop();
            return 0;
        }

        LibationTask task = decideTask(ctx);
        if (task == null) return 0;

        return executeTask(task, ctx);
    }

    private void handleHousekeeping() {
        Telemetry.tick(
                SCRIPT_NAME,
                scriptStartTime,
                getPrayerXpGained(),
                "prayer_xp_gained"
        );
    }

    private LibationContext collectContext() {
        Integer prayer = safePrayer();
        boolean shopVisible = wineShop != null && wineShop.isVisible();
        ItemGroupResult inv = shopVisible ? null : safeSearch(INVENTORY_IDS);
        long now = System.currentTimeMillis();

        return new LibationContext(inv, prayer, shopVisible, now);
    }

    private boolean shouldStop(LibationContext ctx) {
        if (ctx.inv == null) {
            return false;
        }
        return !ctx.inv.contains(COINS) || !ctx.inv.contains(BONE_SHARDS);
    }

    private LibationTask decideTask(LibationContext ctx) {
        // Check prayer
        if (ctx.prayer != null && ctx.prayer <= MIN_PRAYER_POINTS) {
            return LibationTask.BASK_AT_SHRINE;
        }

        // Handle shop if visible
        if (ctx.shopVisible) {
            return LibationTask.HANDLE_WINE_SHOP;
        }

        if (ctx.inv == null) {
            return null;
        }

        // Use libation bowl if we have blessed wine
        if (ctx.inv.contains(BLESSED_WINE) || ctx.inv.contains(BLESSED_SUNFIRE_WINE)) {
            return LibationTask.USE_LIBATION_BOWL;
        }

        // Handle sunfire conversion if enabled and possible
        if (useSunfire && ctx.inv.contains(JUG_OF_WINE) && canMakeSunfire(ctx.inv)) {
            return LibationTask.HANDLE_SUNFIRE;
        } else {
            sunfireState = SunfireState.IDLE;
        }

        // Bless wine if we have unblessed wine
        if (ctx.inv.contains(JUG_OF_WINE) || ctx.inv.contains(SUNFIRE_WINE)) {
            return LibationTask.BLESS_WINE;
        }

        // Acquire more wine
        return LibationTask.ACQUIRE_WINE;
    }

    private int executeTask(LibationTask task, LibationContext ctx) {
        return switch (task) {
            case BASK_AT_SHRINE -> {
                baskAtShrine();
                yield 0;
            }
            case HANDLE_WINE_SHOP -> {
                handleWineShop();
                yield 0;
            }
            case USE_LIBATION_BOWL -> {
                useLibationBowl();
                yield 0;
            }
            case HANDLE_SUNFIRE -> {
                handleSunfireState(ctx.inv);
                yield 0;
            }
            case BLESS_WINE -> {
                blessWine();
                yield 0;
            }
            case ACQUIRE_WINE -> {
                acquireMoreWine();
                yield 0;
            }
        };
    }

    private void handleSunfireState(ItemGroupResult inv) {
        // State 1: select splinter
        if (sunfireState == SunfireState.IDLE) {
            ItemSearchResult splinter = inv.getItem(SUNFIRE_SPLINTER);
            if (splinter != null && splinter.interact()) {
                sunfireState = SunfireState.SPLINTER_SELECTED;
            }
            return;
        }

        // State 2: select wine
        if (sunfireState == SunfireState.SPLINTER_SELECTED) {
            ItemSearchResult wine = inv.getItem(JUG_OF_WINE);
            if (wine != null && wine.interact()) {
                sunfireState = SunfireState.PROCESSING;
                sunfireStartedAt = System.currentTimeMillis();
            }
            return;
        }

        // State 3: processing
        if (sunfireState == SunfireState.PROCESSING) {
            if (System.currentTimeMillis() - sunfireStartedAt > 12_000 ||
                    !inv.contains(JUG_OF_WINE)) {
                sunfireState = SunfireState.IDLE;
            }
        }
    }

    private long getPrayerXpGained() {
        return prayerXP == null ? 0 : (long) prayerXP.getXpGained();
    }

    @Override
    public boolean promptBankTabDialogue() {
        return true;
    }

    private boolean canMakeSunfire(ItemGroupResult inv) {
        return inv.contains(SUNFIRE_SPLINTER)
                && inv.contains(PESTLE_AND_MORTAR)
                && inv.contains(JUG_OF_WINE);
    }

    private void blessWine() {
        if (!inRegion(REGION_TEOMAT)) {
            travelToTeomat();
            return;
        }
        ItemGroupResult inv = safeSearch(INVENTORY_IDS);
        if (inv == null) {
            return;
        }
        boolean hasNormal = inv.contains(JUG_OF_WINE);
        boolean hasSunfire = inv.contains(SUNFIRE_WINE);
        if (!hasNormal && !hasSunfire) {
            return;
        }
        WorldPosition me = getWorldPosition();
        if (me == null) {
            return;
        }
        RSObject altar = getObjectManager().getClosestObject(me, "Exposed altar");
        if (altar == null) {
            walkToArea(ALTAR_AREA);
            return;
        }
        Polygon poly = altar.getConvexHull();
        if (poly == null) {
            walkToArea(ALTAR_AREA);
            return;
        }
        Polygon resized = poly.getResized(0.7);
        if (resized == null ||
                getWidgetManager().insideGameScreenFactor(resized, Collections.emptyList()) < 0.2) {
            walkToArea(ALTAR_AREA);
            return;
        }
        log("LibationBowl", "Blessing " + (hasSunfire ? "Sunfire wine" : "wine"));
        getFinger().tapGameScreen(resized, "Bless");
    }

    private boolean travelOnCooldown() {
        return System.currentTimeMillis() < travelCooldownUntil;
    }

    private boolean walkOnCooldown() {
        return System.currentTimeMillis() < walkCooldownUntil;
    }

    private void triggerTravelCooldown() {
        travelCooldownUntil = System.currentTimeMillis() + getTravelCooldown();
        walkCooldownUntil = System.currentTimeMillis() + getWalkCooldown();
    }

    private void useLibationBowl() {
        Integer prayer = safePrayer();
        if (prayer != null && prayer <= MIN_PRAYER_POINTS) {
            baskAtShrine();
            return;
        }
        if (!inRegion(REGION_TEOMAT)) {
            travelToTeomat();
            return;
        }
        WorldPosition me = getWorldPosition();
        if (me == null) {
            return;
        }
        // Always move to staging area first to avoid pathing issues
        if (me.distanceTo(BOWL_STAGING_TILE) > 3) {
            walkToArea(BOWL_AREA);
            return;
        }
        RSObject bowl = getObjectManager().getClosestObject(me, "Libation bowl");
        if (bowl == null) {
            walkToPosition(BOWL_TILE);
            return;
        }
        Polygon poly = bowl.getConvexHull();
        if (poly == null) {
            walkToPosition(BOWL_TILE);
            return;
        }
        Polygon resized = poly.getResized(0.7);
        if (resized == null ||
                getWidgetManager().insideGameScreenFactor(resized, Collections.emptyList()) < 0.2) {
            walkToPosition(BOWL_TILE);
            return;
        }
        log("LibationBowl", "Using Libation bowl.");
        getFinger().tapGameScreen(resized);
        // After sacrificing -> check for ANY blessed wine left
        ItemGroupResult inv = safeSearch(new HashSet<>(Set.of(BLESSED_WINE, BLESSED_SUNFIRE_WINE)));
        if (inv == null) {
            log("LibationBowl", "Inventory unavailable after sacrifice — retrying next tick.");
            return; // don't travel on a bad tick
        }
        if (!inv.contains(BLESSED_WINE) && !inv.contains(BLESSED_SUNFIRE_WINE)) {
            log("LibationBowl", "Out of blessed wine — returning to Aldarin.");
            travelToAldarin();
        }
    }

    private void baskAtShrine() {
        if (!inRegion(REGION_TEOMAT)) {
            travelToTeomat();
            return;
        }
        WorldPosition me = getWorldPosition();
        if (me == null) {
            return;
        }
        RSObject shrine = getObjectManager().getClosestObject(me, "Shrine of Ralos");
        if (shrine == null) {
            walkToArea(SHRINE_AREA);
            return;
        }
        Polygon p = shrine.getConvexHull();
        if (p == null) {
            walkToArea(SHRINE_AREA);
            return;
        }
        Polygon resized = p.getResized(0.7);
        if (resized == null ||
                getWidgetManager().insideGameScreenFactor(resized, Collections.emptyList()) < 0.2) {
            walkToArea(SHRINE_AREA);
            return;
        }
        log("LibationBowl", "Restoring prayer...");
        shrine.interact("Bask");
        pollFramesHuman(() -> true, RandomUtils.gaussianRandom(500, 2500, 500, 500));
        // Wait until prayer is above threshold, or timeout
        pollFramesUntil(() -> {
            Integer pr = safePrayer();
            return pr != null && pr > MIN_PRAYER_POINTS + 2;
        }, 1200);
        walkToArea(BOWL_AREA);
    }

    private boolean bankJugsAndWithdrawWineIfPossible() {
        ItemGroupResult inv = safeSearch(INVENTORY_IDS);
        if (inv == null) return false;

        boolean hasJugs = inv.contains(EMPTY_JUG);
        boolean needsWine = !inv.contains(JUG_OF_WINE) && !inv.contains(SUNFIRE_WINE);

        // Nothing to do
        if (!hasJugs && (!useBankedWine || !needsWine)) {
            return false;
        }

        // Ensure Aldarin
        if (!inRegion(REGION_ALDARIN)) {
            travelToAldarin();
            return true;
        }

        if (!openAnyBank()) {
            WorldPosition me = getWorldPosition();
            if (me == null) return true;
            Polygon poly = getSceneProjector().getTilePoly(BANKER_TILE);
            if (poly == null ||
                    getWidgetManager().insideGameScreenFactor(poly, Collections.emptyList()) < 0.25) {
                walkToArea(BANKER_AREA);
            }
            return true;
        }

        // deposit empty jugs
        if (hasJugs) {
            depositIfPresent(EMPTY_JUG);
        }

        boolean withdrewWine = false;

        // withdraw wine if enabled
        if (useBankedWine && needsWine) {

            // Sunfire first
            if (useSunfire) {
                ItemGroupResult bankSunfire =
                        getWidgetManager().getBank().search(Set.of(SUNFIRE_WINE));
                if (bankSunfire != null && bankSunfire.getAmount(SUNFIRE_WINE) > 0) {
                    getWidgetManager().getBank()
                            .withdraw(SUNFIRE_WINE,
                                    Math.min(27, bankSunfire.getAmount(SUNFIRE_WINE)));
                    withdrewWine = true;
                }
            }

            // Fallback to normal wine
            if (!withdrewWine) {
                ItemGroupResult bankWine =
                        getWidgetManager().getBank().search(Set.of(JUG_OF_WINE));
                if (bankWine != null && bankWine.getAmount(JUG_OF_WINE) > 0) {
                    getWidgetManager().getBank()
                            .withdraw(JUG_OF_WINE,
                                    Math.min(27, bankWine.getAmount(JUG_OF_WINE)));
                    withdrewWine = true;
                }
            }

            // Bank has no usable wine then disable setting
            if (!withdrewWine) {
                useBankedWine = false;
                log("LibationBowl",
                        "No wine found in bank — disabling banked wine usage.");
            }
        }

        getWidgetManager().getBank().close();
        return true;
    }

    private void acquireMoreWine() {
        // One bank visit handles jugs + wine
        if (bankJugsAndWithdrawWineIfPossible()) {
            return;
        }

        //else use shop
        if (wineShop != null && wineShop.isVisible()) {
            handleWineShop();
            return;
        }
        if (!inRegion(REGION_ALDARIN)) {
            travelToAldarin();
            return;
        }
        WorldPosition me = getWorldPosition();
        if (me == null) {
            return;
        }
        // Approach from the east to avoid the door — use area instead of single tile
        if (me.distanceTo(BARTENDER_APPROACH_TILE) > 10) {
            walkToArea(BARTENDER_APPROACH_AREA);
            return;
        }
        if (me.distanceTo(BARTENDER_TILE) > 5) {
            walkToArea(BARTENDER_AREA);
            return;
        }
        Polygon poly = getSceneProjector().getTilePoly(BARTENDER_TILE);
        if (poly == null) {
            walkToArea(BARTENDER_AREA);
            return;
        }
        Polygon resized = poly.getResized(0.5);
        if (resized == null) {
            walkToArea(BARTENDER_AREA);
            return;
        }
        if (!getFinger().tapGameScreen(resized, "Trade")) {
            return;
        }
        // Wait human-like for the shop to appear
        pollFramesHuman(() -> wineShop != null && wineShop.isVisible(), RandomUtils.gaussianRandom(2500, 4500, 500, 500));
    }

    private void handleWineShop() {
        if (shopPurchasing) {
            if (System.currentTimeMillis() - shopPurchaseStarted > getShopPurchaseTimeout()) {
                log("LibationBowl", "Purchase timeout - resetting");
                shopPurchasing = false;
                return;
            }

            ItemGroupResult inv = getWidgetManager().getInventory().search(INVENTORY_IDS);
            if (inv != null) {
                ItemGroupResult shop = wineShop.search(SHOP_WINE_IDS);
                if (shop != null) {
                    ItemSearchResult wineItem = shop.getItem(JUG_OF_WINE);
                    if (wineItem != null) {
                        shopPurchasing = false;
                        log("LibationBowl", "Wine purchased");
                    }
                }
            }
            return;
        }
        ItemGroupResult inv = getWidgetManager().getInventory().search(INVENTORY_IDS);
        if (inv == null || inv.getFreeSlots() == 0) {
            log("LibationBowl", "Inventory full - closing shop");
            wineShop.close();
            return;
        }

        ItemGroupResult shop = wineShop.search(SHOP_WINE_IDS);
        if (shop == null) {
            log("LibationBowl", "Shop not visible - closing");
            wineShop.close();
            return;
        }

        ItemSearchResult wineItem = shop.getItem(JUG_OF_WINE);
        if (wineItem == null || wineItem.getStackAmount() <= 0) {
            log("LibationBowl", "No wine in stock - closing shop");
            wineShop.close();
            return;
        }

        wineShop.setSelectedAmount(10);
        if (wineItem.interact()) {
            log("LibationBowl", "Purchasing wine...");
            shopPurchasing = true;
            shopPurchaseStarted = System.currentTimeMillis();
        } else {
            log("LibationBowl", "Failed to click wine");
        }
    }

    private void travelToAldarin() {
        if (!inRegion(REGION_TEOMAT)) {
            walkToPosition(QUETZAL_TEOMAT);
            return;
        }
        WorldPosition pos = getWorldPosition();
        if (pos == null) {
            return;
        }
        if (pos.distanceTo(QUETZAL_TEOMAT) > 5) {
            walkToPosition(QUETZAL_TEOMAT);
            return;
        }
        Polygon cube = getSceneProjector().getTileCube(QUETZAL_TEOMAT, 130);
        if (cube == null) {
            walkToPosition(QUETZAL_TEOMAT);
            return;
        }
        Polygon resized = cube.getResized(0.5);
        if (resized == null) {
            walkToPosition(QUETZAL_TEOMAT);
            return;
        }
        getFinger().tapGameScreen(resized, "Travel");
        triggerTravelCooldown();
        // Small delay, then click Aldarin on the Quetzal map
        pollFramesHuman(() -> true, RandomUtils.gaussianRandom(500, 2500, 500, 500));
        getFinger().tap(randomPointIn(ALDARIN_RECT));
        triggerTravelCooldown();
    }

    private void travelToTeomat() {
        if (!inRegion(REGION_ALDARIN)) {
            walkToPosition(QUETZAL_ALDARIN);
            return;
        }
        WorldPosition pos = getWorldPosition();
        if (pos == null) {
            return;
        }
        if (pos.distanceTo(QUETZAL_ALDARIN) > 5) {
            walkToPosition(QUETZAL_ALDARIN);
            return;
        }
        Polygon cube = getSceneProjector().getTileCube(QUETZAL_ALDARIN, 130);
        if (cube == null) {
            walkToPosition(QUETZAL_ALDARIN);
            return;
        }
        Polygon resized = cube.getResized(0.5);
        if (resized == null) {
            walkToPosition(QUETZAL_ALDARIN);
            return;
        }
        getFinger().tapGameScreen(resized, "Travel");
        triggerTravelCooldown();
        // Small delay, then click Teomat on the Quetzal map
        pollFramesHuman(() -> true, RandomUtils.gaussianRandom(500, 2500, 500, 500));
        getFinger().tap(randomPointIn(TEOMAT_RECT));
        triggerTravelCooldown();
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
            log("LibationBowl", "No bank objects found");
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

        return pollFramesUntil(
                () -> getWidgetManager().getBank().isVisible(),
                RandomUtils.gaussianRandom(
                        RandomUtils.uniformRandom(1500, 2000),
                        RandomUtils.uniformRandom(3000, 3500),
                        350, 350
                )
        );
    }

    private boolean inRegion(int region) {
        WorldPosition pos = getWorldPosition();
        return pos != null && pos.getRegionID() == region;
    }

    private Integer safePrayer() {
        return getWidgetManager().getMinimapOrbs().getPrayerPoints();
    }

    private ItemGroupResult safeSearch(Set<Integer> ids) {
        for (int i = 0; i < 3; i++) {
            ItemGroupResult r = getWidgetManager().getInventory().search(ids);
            if (r != null) {
                return r;
            }
            pollFramesHuman(() -> true, RandomUtils.gaussianRandom(100, 800, 200, 200));
        }
        return null;
    }

    private void walkToPosition(WorldPosition target) {
        if (target == null) {
            return;
        }
        if (walkOnCooldown()) {
            log("LibationBowl", "Walk on cooldown — skipping walk this tick.");
            return;
        }
        // Immediately after Quetzal flights, these can be null briefly which can cause a crash
        if (getLocalPosition() == null || getWorldPosition() == null) {
            log("LibationBowl", "World/local position null (loading) — skipping walk.");
            return;
        }
        WalkConfig cfg = new WalkConfig.Builder()
                .setWalkMethods(false, true)
                .build();
        getWalker().walkTo(target, cfg);
        walkCooldownUntil = System.currentTimeMillis() + getWalkCooldown();
    }

    private void walkToArea(Rectangle area) {
        if (area == null) {
            return;
        }
        int x = area.x + RandomUtils.uniformRandom(0, area.width);
        int y = area.y + RandomUtils.uniformRandom(0, area.height);
        WorldPosition target = new WorldPosition(x, y, 0);
        walkToPosition(target);
    }

    private Point randomPointIn(Rectangle r) {
        int x = r.x + RandomUtils.uniformRandom(0, r.width);
        int y = r.y + RandomUtils.uniformRandom(0, r.height);
        return new Point(x, y);
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
        if (prayerXP == null) {
            prayerXP = getXPTrackers().get(SkillType.PRAYER);
            if (prayerXP == null) {
                return;
            }
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
        drawHeader(c, "Sainty", "Libation Bowl", x + 14, y + 16);
        c.fillRect(x + 10, y + headerH, w - 20, 1, DIVIDER);
        int ty = y + headerH + 18;
        c.drawText("Runtime: " + formatRuntime(), x + 14, ty, 0xFFFFFFFF, bodyFont);
        ty += 14;
        c.drawText("XP gained: " + (int) prayerXP.getXpGained(), x + 14, ty, 0xFF66FF66, bodyFont);
        ty += 14;
        c.drawText("XP/hr: " + prayerXP.getXpPerHour(), x + 14, ty, 0xFF66CCFF, bodyFont);
        ty += 14;
        c.drawText(
                "Lvl " + prayerXP.getLevel() + " → TNL: " + prayerXP.timeToNextLevelString(),
                x + 14,
                ty,
                0xFFFFAA00,
                bodyFont
        );
    }

    private String formatRuntime() {
        long ms = System.currentTimeMillis() - getStartTime();
        long s = ms / 1000;
        long m = s / 60;
        long h = m / 60;
        return String.format("%02d:%02d:%02d", h, m % 60, s % 60);
    }

    @Override
    public int[] regionsToPrioritise() {
        return new int[]{REGION_ALDARIN, REGION_TEOMAT};
    }

    public void onStop() {
        Telemetry.sessionEnd(SCRIPT_NAME);
    }
}