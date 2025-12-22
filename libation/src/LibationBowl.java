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
import com.osmb.api.visual.drawing.Canvas;
import com.osmb.api.walker.WalkConfig;
import com.osmb.script.libation.javafx.ScriptOptions;
import javafx.scene.Scene;

import java.awt.*;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

@ScriptDefinition(
		name = "LibationBowl",
		author = "Sainty",
		version = 1.3,
		description = "Buys wine, optionally converts to Sunfire wine, blesses, sacrifices, banks jugs.",
		skillCategory = SkillCategory.PRAYER
)
public class LibationBowl extends Script {
	
	// ========= ITEM IDS ==========
	private static final int COINS        = 995;
	private static final int BONE_SHARDS  = 29381;
	private static final int JUG_OF_WINE  = 1993;
	private static final int BLESSED_WINE = 29386;
	private static final int EMPTY_JUG    = 1935;
	
	// Sunfire items
	private static final int SUNFIRE_SPLINTER      = 28924;
	private static final int PESTLE_AND_MORTAR     = 233;
	private static final int SUNFIRE_WINE          = 29382;
	private static final int BLESSED_SUNFIRE_WINE  = 29384;
	
	// ========= REGIONS ==========
	private static final int REGION_ALDARIN = 5421;
	private static final int REGION_TEOMAT  = 5681;
	
	// ========= POSITIONS ==========
	private static final WorldPosition QUETZAL_ALDARIN = new WorldPosition(1389, 2899, 0);
	private static final WorldPosition QUETZAL_TEOMAT  = new WorldPosition(1437, 3169, 0);
	
	private static final WorldPosition BARTENDER_TILE          = new WorldPosition(1376, 2927, 0);
	private static final WorldPosition BARTENDER_APPROACH_TILE = new WorldPosition(1385, 2927, 0);
	
	private static final WorldPosition BANKER_TILE    = new WorldPosition(1401, 2928, 0);
	private static final WorldPosition BANK_SAFE_TILE = new WorldPosition(1399, 2928, 0);
	
	private static final WorldPosition ALTAR_TILE        = new WorldPosition(1436, 3146, 0);
	private static final WorldPosition BOWL_TILE         = new WorldPosition(1458, 3187, 0);
	private static final WorldPosition BOWL_STAGING_TILE = new WorldPosition(1457, 3189, 0);
	private static final WorldPosition SHRINE_TILE       = new WorldPosition(1451, 3172, 0);
	
	private static final Rectangle BARTENDER_AREA =
			new Rectangle(1378, 2925, 3, 3);
	
	private static final Rectangle BARTENDER_APPROACH_AREA =
			new Rectangle(1385, 2924, 6, 5);
	
	private static final Rectangle BANKER_AREA =
			new Rectangle(1396, 2925, 3, 3);
	
	private static final Rectangle ALTAR_AREA =
			new Rectangle(1433, 3147, 5, 5);
	
	private static final Rectangle SHRINE_AREA =
			new Rectangle(1448, 3172, 5, 4);
	
	private static final Rectangle BOWL_AREA =
			new Rectangle(1457, 3188, 1, 3);
	
	private static final Rectangle ALDARIN_RECT =
			new Rectangle(273, 494, 27, 18);
	private static final Rectangle TEOMAT_RECT =
			new Rectangle(284, 375, 19, 20);
	

	
	private static final int MIN_PRAYER_POINTS = 2;
	
	// ========= STATE ==========
	private boolean useSunfire = false;
	private XPTracker prayerXP;
	
	private static final Set<Integer> INVENTORY_IDS = new HashSet<>();
	private static final Set<Integer> SHOP_WINE_IDS = new HashSet<>();
	
	private WineShopInterface wineShop;
	
	public LibationBowl(Object core) {
		super(core);
	}
	
	public Object getScriptOptions() {
		return new ScriptOptions();
	}
	
	// ========= STARTUP ==========
	@Override
	public void onStart() {
		
		// ===== SHOW OPTIONS UI =====
		ScriptOptions ui = new ScriptOptions();
		Scene scene = new Scene(ui);
		getStageController().show(scene, "Libation Bowl Options", false);
		
		useSunfire = ui.useSunfire();
		
		// ===== NORMAL STARTUP =====
		prayerXP = getXPTrackers().get(SkillType.PRAYER);
		
		INVENTORY_IDS.clear();
		INVENTORY_IDS.add(COINS);
		INVENTORY_IDS.add(BONE_SHARDS);
		INVENTORY_IDS.add(JUG_OF_WINE);
		INVENTORY_IDS.add(BLESSED_WINE);
		INVENTORY_IDS.add(EMPTY_JUG);
		
		// Sunfire items
		INVENTORY_IDS.add(SUNFIRE_SPLINTER);
		INVENTORY_IDS.add(PESTLE_AND_MORTAR);
		INVENTORY_IDS.add(SUNFIRE_WINE);
		INVENTORY_IDS.add(BLESSED_SUNFIRE_WINE);
		
		SHOP_WINE_IDS.clear();
		SHOP_WINE_IDS.add(JUG_OF_WINE);
		
		wineShop = new WineShopInterface(this);
		getWidgetManager().getInventory().registerInventoryComponent(wineShop);
		
		log("LibationBowl", "Started. Sunfire mode = " + useSunfire);
	}
	
	// ========= MAIN LOOP ==========
	@Override
	public int poll() {
		
		Integer prayer = safePrayer();
		if (prayer != null && prayer <= MIN_PRAYER_POINTS) {
			baskAtShrine();
			return 0;
		}
		
		ItemGroupResult inv = safeSearch(INVENTORY_IDS);
		if (inv == null) {
			log("LibationBowl", "Inventory temporarily unavailable — retry next tick.");
			return 0;
		}
		
		if (!inv.contains(COINS) || !inv.contains(BONE_SHARDS)) {
			log("LibationBowl", "Missing shard/coins — stopping.");
			stop();
			return 0;
		}
		
		// PRIORITY 1: Any blessed wine (normal or Sunfire) → sacrifice
		if (inv.contains(BLESSED_WINE) || inv.contains(BLESSED_SUNFIRE_WINE)) {
			useLibationBowl();
			return 0;
		}
		
		// PRIORITY 2: Convert to Sunfire *before* blessing, if enabled and we have the items
		if (useSunfire &&
				inv.contains(JUG_OF_WINE) &&
				inv.contains(SUNFIRE_SPLINTER) &&
				inv.contains(PESTLE_AND_MORTAR)) {
			
			convertToSunfireWine(inv);
			return 0;
		}
		
		// PRIORITY 3: Bless wine (normal or Sunfire)
		if (inv.contains(JUG_OF_WINE) || inv.contains(SUNFIRE_WINE)) {
			blessWine();
			return 0;
		}
		
		// PRIORITY 4: No wine → go and buy more
		acquireMoreWine(inv);
		return 0;
	}
	
	// ========= MAKE SUNFIRE WINE ==========
	private void convertToSunfireWine(ItemGroupResult inv) {
		
		ItemSearchResult splinter = inv.getItem(SUNFIRE_SPLINTER);
		ItemSearchResult wine     = inv.getItem(JUG_OF_WINE);
		ItemSearchResult pestle   = inv.getItem(PESTLE_AND_MORTAR);
		
		if (splinter == null || wine == null || pestle == null)
			return;
		
		log("LibationBowl", "Creating Sunfire wine...");
		
		if (!splinter.interact()) {
			log("LibationBowl", "Failed selecting Sunfire Splinter");
			return;
		}
		
		// Frame-based delay instead of sleep
		pollFramesHuman(() -> false, random(150, 300));
		
		if (!wine.interact()) {
			log("LibationBowl", "Failed applying splinter to wine");
			return;
		}
		
		// process the combine to prevent using on the same item
		pollFramesHuman(() -> false, random(400, 700));
	}
	
	// ========= BLESS WINE ==========
	private void blessWine() {
		
		if (!inRegion(REGION_TEOMAT)) {
			travelToTeomat();
			return;
		}
		
		ItemGroupResult inv = safeSearch(INVENTORY_IDS);
		if (inv == null) return;
		
		boolean hasNormal  = inv.contains(JUG_OF_WINE);
		boolean hasSunfire = inv.contains(SUNFIRE_WINE);
		
		if (!hasNormal && !hasSunfire)
			return;
		
		WorldPosition me = getWorldPosition();
		if (me == null) return;
		
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
	
	// ========= USE LIBATION BOWL ==========
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
		if (me == null) return;
		
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
		
		// After sacrificing → check for ANY blessed wine left
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
	
	// ========= PRAYER RESTORE ==========
	private void baskAtShrine() {
		if (!inRegion(REGION_TEOMAT)) {
			travelToTeomat();
			return;
		}
		
		WorldPosition me = getWorldPosition();
		if (me == null) return;
		
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
		
		pollFramesHuman(() -> false, random(700, 1000));
		
		// Wait until prayer is above threshold, or timeout
		pollFramesUntil(() -> {
			Integer pr = safePrayer();
			return pr != null && pr > MIN_PRAYER_POINTS + 2;
		}, 1200);
		
		walkToArea(BOWL_AREA);
	}
	
	// ========= BUYING WINE ==========
	private void acquireMoreWine(ItemGroupResult invSnapshot) {
		
		// 1) Always handle empty jugs first — this preempts shopping
		ItemGroupResult jugCheck = getWidgetManager().getInventory().search(Collections.singleton(EMPTY_JUG));
		if (jugCheck != null && jugCheck.contains(EMPTY_JUG)) {
			
			depositEmptyJugs();
			// After banking, let the next poll() decide the next action
			return;
		}
		
		// 2) If the shop is already open, handle the buy loop
		if (wineShop != null && wineShop.isVisible()) {
			handleWineShop(invSnapshot);
			return;
		}
		
		// 3) Must be in Aldarin to buy wine
		if (!inRegion(REGION_ALDARIN)) {
			travelToAldarin();
			return;
		}
		
		WorldPosition me = getWorldPosition();
		if (me == null)
			return;
		
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
		
		if (!getFinger().tap(resized, "Trade"))
			return;
		
		// Wait human-like for the shop to appear
		pollFramesHuman(() -> wineShop != null && wineShop.isVisible(), random(2500, 4500));
	}
	
	// ========= SHOP BUY LOOP ==========
	private void handleWineShop(ItemGroupResult invBefore) {
		
		while (true) {
			
			ItemGroupResult inv = getWidgetManager().getInventory().search(INVENTORY_IDS);
			if (inv == null || inv.getFreeSlots() == 0)
				break;
			
			ItemGroupResult shop = wineShop.search(SHOP_WINE_IDS);
			if (shop == null)
				break;
			
			ItemSearchResult wineItem = shop.getItem(JUG_OF_WINE);
			if (wineItem == null || wineItem.getStackAmount() <= 0)
				break;
			
			// Always buy 10 at a time
			wineShop.setSelectedAmount(10);
			
			int freeBefore = inv.getFreeSlots();
			int wineBefore = inv.getAmount(JUG_OF_WINE);
			
			wineItem.interact();
			
			boolean success = pollFramesUntil(() -> {
				ItemGroupResult after = getWidgetManager().getInventory().search(INVENTORY_IDS);
				if (after == null) return false;
				return after.getFreeSlots() < freeBefore ||
						after.getAmount(JUG_OF_WINE) > wineBefore;
			}, 5000);
			
			if (!success)
				continue;
		}
		
		wineShop.close();
	}
	
	// ========= BANKING ==========
	private void depositEmptyJugs() {
		
		ItemGroupResult jugs = getWidgetManager().getInventory().search(Collections.singleton(EMPTY_JUG));
		if (jugs == null || !jugs.contains(EMPTY_JUG))
			return;
		
		if (inRegion(REGION_TEOMAT)) {
			log("LibationBowl", "Need to bank empty jugs — travelling to Aldarin.");
			travelToAldarin();
			return;
		}
		
		log("LibationBowl", "Empty jugs detected — banking with Banker NPC.");
		
		WorldPosition me = getWorldPosition();
		if (me == null)
			return;
		
		// Use the tile of the banker NPC to get a clickable polygon
		Polygon poly = getSceneProjector().getTilePoly(BANKER_TILE);
		
		// If banker tile is off-screen / not clickable, move to a safe area first
		if (poly == null ||
				getWidgetManager().insideGameScreenFactor(poly, Collections.emptyList()) < 0.25) {
			
			log("LibationBowl", "Banker off-screen — walking to banker area.");
			walkToArea(BANKER_AREA);
			return;
		}
		
		Polygon resized = poly.getResized(0.4);
		if (resized == null) {
			walkToArea(BANKER_AREA);
			return;
		}
		
		// Banker tile is visible — click "Bank"
		log("LibationBowl", "Banker visible — opening bank.");
		getFinger().tap(resized, "Bank");
		
		boolean opened = pollFramesUntil(() -> getWidgetManager().getBank().isVisible(), 5000);
		if (!opened) {
			log("LibationBowl", "Bank failed to open.");
			return;
		}
		
		// Bank only the EMPTY_JUG items - we need coins/boneshards or it breaks
		ItemGroupResult bankable = getWidgetManager().getInventory().search(Collections.singleton(EMPTY_JUG));
		if (bankable != null && bankable.contains(EMPTY_JUG)) {
			log("LibationBowl", "Depositing EMPTY_JUG items only.");
			getWidgetManager().getBank().deposit(EMPTY_JUG, bankable.getAmount(EMPTY_JUG));
		}
		
		// Small human-like delay before closing
		pollFramesHuman(() -> false, random(200, 400));
		getWidgetManager().getBank().close();
	}
	
	// ========= TRAVEL ==========
	private void travelToAldarin() {
		
		if (!inRegion(REGION_TEOMAT)) {
			walkToPosition(QUETZAL_TEOMAT);
			return;
		}
		
		WorldPosition pos = getWorldPosition();
		if (pos == null)
			return;
		
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
		
		getFinger().tap(resized, "Travel");
		
		// Small delay, then click Aldarin on the Quetzal map
		pollFramesHuman(() -> false, random(700, 1200));
		getFinger().tap(randomPointIn(ALDARIN_RECT));
	}
	
	private void travelToTeomat() {
		
		if (!inRegion(REGION_ALDARIN)) {
			walkToPosition(QUETZAL_ALDARIN);
			return;
		}
		
		WorldPosition pos = getWorldPosition();
		if (pos == null)
			return;
		
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
		
		getFinger().tap(resized, "Travel");
		
		// Small delay, then click Teomat on the Quetzal map
		pollFramesHuman(() -> false, random(700, 1200));
		getFinger().tap(randomPointIn(TEOMAT_RECT));
	}
	
	// ========= HELPERS ==========
	private boolean inRegion(int region) {
		WorldPosition pos = getWorldPosition();
		return pos != null && pos.getRegionID() == region;
	}
	
	private Integer safePrayer() {
		try {
			return getWidgetManager().getMinimapOrbs().getPrayerPoints();
		} catch (Exception e) {
			return null;
		}
	}
	
	private ItemGroupResult safeSearch(Set<Integer> ids) {
		for (int i = 0; i < 3; i++) {
			try {
				ItemGroupResult r = getWidgetManager().getInventory().search(ids);
				if (r != null)
					return r;
			} catch (Exception ignored) {}
			// Frame-based retry delay instead of sleep
			pollFramesUntil(() -> false, 120);
		}
		return null;
	}
	
	private void walkToPosition(WorldPosition target) {
		if (target == null) return;
		
		// Immediately after Quetzal flights, these can be null briefly and caused a crash
		if (getLocalPosition() == null || getWorldPosition() == null) {
			log("LibationBowl", "World/local position null (likely loading/teleport) — skipping walk this tick.");
			return;
		}
		
		WalkConfig.Builder cfg = new WalkConfig.Builder()
				.tileRandomisationRadius(2)
				.breakDistance(2);
		
		getWalker().walkTo(target, cfg.build());
	}
	

	private void walkToArea(Rectangle area) {
		if (area == null) return;
		
		int x = area.x + random(area.width);
		int y = area.y + random(area.height);
		WorldPosition target = new WorldPosition(x, y, 0);
		walkToPosition(target);
	}
	
	
	private Point randomPointIn(Rectangle r) {
		int x = r.x + random(r.width);
		int y = r.y + random(r.height);
		return new Point(x, y);
	}
	
	// ========= XP Tracker ==========
	@Override
	public void onPaint(Canvas c) {
		if (prayerXP == null) {
			prayerXP = getXPTrackers().get(SkillType.PRAYER);
			if (prayerXP == null)
				return;
		}
		
		c.fillRect(5, 25, 220, 90, 0x88000000, 0.8);
		c.drawRect(5, 25, 220, 90, Color.WHITE.getRGB());
		
		Font f = new Font("Arial", Font.PLAIN, 13);
		int y = 45;
		
		c.drawText("LibationBowl running...", 10, y, Color.WHITE.getRGB(), f); y += 16;
		c.drawText("Time: " + formatRuntime(), 10, y, Color.WHITE.getRGB(), f); y += 16;
		c.drawText("XP Gained: " + (int)prayerXP.getXpGained(), 10, y, Color.WHITE.getRGB(), f); y += 16;
		c.drawText("XP/hr: " + prayerXP.getXpPerHour(), 10, y, Color.WHITE.getRGB(), f); y += 16;
		c.drawText("Lvl " + prayerXP.getLevel() + " → TNL: " + prayerXP.timeToNextLevelString(),
		           10, y, Color.WHITE.getRGB(), f);
	}
	
	private String formatRuntime() {
		long ms = System.currentTimeMillis() - getStartTime();
		long s  = ms / 1000;
		long m  = s / 60;
		long h  = m / 60;
		return String.format("%02d:%02d:%02d", h, m % 60, s % 60);
	}
	
	// ========= REGION PRIORITY ==========
	@Override
	public int[] regionsToPrioritise() {
		return new int[]{REGION_ALDARIN, REGION_TEOMAT};
	}
}
