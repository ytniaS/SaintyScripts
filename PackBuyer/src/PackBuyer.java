package com.osmb.script.packbuyer;

import java.awt.Color;
import java.awt.Font;
import java.util.Collections;
import java.util.Set;

import com.osmb.api.item.ItemGroupResult;
import com.osmb.api.item.ItemSearchResult;
import com.osmb.api.location.area.impl.RectangleArea;
import com.osmb.api.location.position.types.WorldPosition;
import com.osmb.api.script.Script;
import com.osmb.api.script.ScriptDefinition;
import com.osmb.api.script.SkillCategory;
import com.osmb.api.shape.Polygon;
import com.osmb.api.visual.drawing.Canvas;
import com.osmb.script.packbuyer.javafx.ScriptOptions;
import javafx.scene.Scene;

@ScriptDefinition(
		name = "Pack Buyer",
		author = "Sainty",
		version = 1.1,
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
	private static final Font PAINT_FONT = new Font("Arial", Font.PLAIN, 13);
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
					50
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
					50
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
					50
			);
	private static final PackModeConfig AMYLASE_GRACE =
			new PackModeConfig(
					BuyMode.AMYLASE, // ← new enum value
					"Amylase packs – Grace (Rogues' Den)",
					"grace's graceful clothing",
					new RectangleArea(3046, 4961, 6, 4, 1),
					12109,
					AMYLASE_PACK,
					AMYLASE_CRYSTAL,
					1000,
					50
			);
	private PackModeConfig config;
	private GenericShopInterface shop;
	private boolean hopFlag;
	private boolean menuDesync;
	private long startTime;
	private static final WorldPosition GRACE_EXCLUDED_TILE =
			new WorldPosition(3051, 4963, 1);
	
	public PackBuyer(Object core) {
		super(core);
	}
	
	@Override
	public void onStart() {
		if (!VersionChecker.isExactVersion(this)) {
			stop();
			return;
		}
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
	}
	
	@Override
	public int[] regionsToPrioritise() {
		if (config == null || config.region <= 0) {return new int[0];}
		return new int[] {config.region};
	}
	
	@Override
	public int poll() {
		// ─── Coins check ─────────────────────────────
		if (!hasCurrency()) {
			log("Out of coins → stopping");
			closeShop();
			stop();
			return 0;
		}
		// ─── Menu desync recovery ────────────────────
		if (menuDesync) {
			log("Menu desync → closing + hopping");
			closeShop();
			getProfileManager().forceHop();
			menuDesync = false;
			return 0;
		}
		// ─── PRIORITY: if packs exist, close shop and open ───
		if (hasPack()) {
			log("Pack detected in inventory");
			if (shop.isVisible()) {
				log("Closing shop to open packs");
				closeShop();
				return 0;
			}
			openPack();
			return 0;
		}
		// ─── Stop only when finished AND empty ───────
		if (config.totalOpened >= config.targetTotal) {
			log("Target reached → stopping");
			closeShop();
			stop();
			return 0;
		}
		// ─── Handle open shop ────────────────────────
		if (shop.isVisible()) {
			log("Shop visible → handleShop()");
			handleShop();
			return 0;
		}
		// ─── Hop if flagged ──────────────────────────
		if (hopFlag) {
			log("Hop flag set → hopping");
			getProfileManager().forceHop();
			hopFlag = false;
			return 0;
		}
		// ─── Attempt to open shop ────────────────────
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
		ItemGroupResult inv =
				getWidgetManager().getInventory().search(Set.of(currencyId));
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
		int before = getInventoryAmount(config.packItemId);
		if (!shopItem.interact()) {
			log("Interact failed → menuDesync");
			menuDesync = true;
			return;
		}
		boolean success = pollFramesUntil(
				() -> getInventoryAmount(config.packItemId) > before,
				3500
		                                 );
		if (success) {
			int after = getInventoryAmount(config.packItemId);
			int gained = Math.max(0, after - before);
			config.totalOpened += gained;
			log("Bought pack x" + gained);
			closeShop();
		} else {
			log("No buy possible → hop");
			closeShop();
			hopFlag = true;
		}
	}
	
	private void openPack() {
		ItemGroupResult inv = getWidgetManager().getInventory().search(Set.of(config.packItemId));
		if (inv == null) {return;}
		ItemSearchResult pack = inv.getItem(config.packItemId);
		if (pack == null) {return;}
		int before = getInventoryAmount(config.openedItemId);
		if (!pack.interact("Open") && !pack.interact()) {return;}
		if (pollFramesUntil(() -> getInventoryAmount(config.openedItemId) > before, 3500)) {
			int after = getInventoryAmount(config.openedItemId);
			int gained = Math.max(0, after - before);
			config.totalOpened += gained;
			log("Opened pack → gained " + gained);
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
		if (npcPositions == null) {return;}
		WorldPosition me = getWorldPosition();
		if (me == null) {return;}
		for (WorldPosition pos : npcPositions) {
			if (config.mode == BuyMode.AMYLASE &&
					pos.equals(GRACE_EXCLUDED_TILE)) {
				continue;
			}
			if (me.distanceTo(pos) > 6) {continue;}
			Polygon cube = getSceneProjector().getTileCube(pos, 90);
			if (cube == null) {continue;}
			Polygon resized = cube.getResized(0.6);
			if (resized == null) {continue;}
			if (!getWidgetManager().insideGameScreen(resized, Collections.emptyList())) {continue;}
			if (getFinger().tapGameScreen(resized, "Trade")) {
				log("Attempted to open shop");
				pollFramesHuman(() -> shop.isVisible(), random(3000, 6000));
				return;
			}
		}
	}
	
	private void closeShop() {
		if (shop != null && shop.isVisible()) {shop.close();}
	}
	
	@Override
	public void onPaint(Canvas c) {
		long elapsed = System.currentTimeMillis() - startTime;
		if (elapsed <= 0) {return;}
		int x = 16;
		int y = 40;
		int w = 240;
		int headerH = 45;
		int bodyH = 55;
		int BG = new Color(12, 14, 20, 235).getRGB();
		int BORDER = new Color(100, 100, 110, 180).getRGB();
		int DIVIDER = new Color(255, 255, 255, 40).getRGB();
		Font bodyFont = new Font("Segoe UI", Font.PLAIN, 13);
		c.fillRect(x, y, w, headerH + bodyH, BG, 0.95);
		c.drawRect(x, y, w, headerH + bodyH, BORDER);
		c.fillRect(x + 10, y + headerH, w - 20, 1, DIVIDER);
		int ty = y + headerH + 18;
		c.drawText("Opened: " + config.totalOpened + " / " + config.targetTotal,
		           x + 14, ty, 0xFFFFFFFF, bodyFont);
	}
}
