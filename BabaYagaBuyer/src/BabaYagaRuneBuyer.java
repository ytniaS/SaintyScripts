package com.osmb.script.babayaga;

import com.osmb.api.item.ItemGroupResult;
import com.osmb.api.item.ItemSearchResult;
import com.osmb.api.location.position.types.WorldPosition;
import com.osmb.api.script.Script;
import com.osmb.api.script.ScriptDefinition;
import com.osmb.api.script.SkillCategory;
import com.osmb.api.shape.Polygon;
import com.osmb.api.visual.drawing.Canvas;


import javafx.scene.Scene;

import java.awt.Font;
import java.util.*;

@ScriptDefinition(
		name = "BabaYaga Rune Buyer",
		author = "Sainty",
		version = 1.0,
		description = "Buys runes from Baba Yaga using base stock logic",
		skillCategory = SkillCategory.OTHER
)
public class BabaYagaRuneBuyer extends Script {
	
	private static final int REGION = 9800;
	private static final int COINS = 995;
	private static final int SEAL_OF_PASSAGE = 9083;
	
	private static final Font PAINT_FONT = new Font("Arial", Font.PLAIN, 13);
	
	private final List<RuneConfig> runes = new ArrayList<>();
	private RuneShopInterface shop;
	
	private boolean hopFlag = false;
	private boolean menuDesync = false;
	
	
	
	private long startTime;
	
	public BabaYagaRuneBuyer(Object core) {
		super(core);
	}
	
	@Override
	public void onStart() {
		
		// 5000 base stock
		runes.add(new RuneConfig(556, "Air Rune", 5000));
		runes.add(new RuneConfig(555, "Water Rune", 5000));
		runes.add(new RuneConfig(557, "Earth Rune", 5000));
		runes.add(new RuneConfig(554, "Fire Rune", 5000));
		runes.add(new RuneConfig(558, "Mind Rune", 5000));
		runes.add(new RuneConfig(559, "Body Rune", 5000));
		
		// 250 base stock
		runes.add(new RuneConfig(562, "Chaos Rune", 250));
		runes.add(new RuneConfig(561, "Nature Rune", 250));
		runes.add(new RuneConfig(560, "Death Rune", 250));
		runes.add(new RuneConfig(563, "Law Rune", 250));
		runes.add(new RuneConfig(565, "Blood Rune", 250));
		runes.add(new RuneConfig(566, "Soul Rune", 250));
		runes.add(new RuneConfig(9075, "Astral Rune", 250));
		
		ScriptOptions ui = new ScriptOptions(runes);
		
		Scene scene = new Scene(ui);
		
		scene.getStylesheets().add("style.css");
		getStageController().show(scene, "Baba Yaga Rune Buyer", true);

		
		shop = new RuneShopInterface(this);
		
		startTime = System.currentTimeMillis();
	}
	
	@Override
	public int[] regionsToPrioritise() {
		return new int[]{REGION};
	}
	

	
	private boolean allRunesCompleted() {
		for (RuneConfig r : runes) {
			if (!r.enabled)
				continue;
			
			ItemGroupResult inv =
					getWidgetManager().getInventory().search(Set.of(r.itemId));
			
			int invAmt = inv == null ? 0 : inv.getAmount(r.itemId);
			
			if (invAmt < r.targetTotal)
				return false;
		}
		return true;
	}
	
	
	@Override
	public int poll() {
		
		ItemGroupResult coins = getWidgetManager().getInventory().search(Set.of(COINS));
		if (coins == null || coins.getAmount(COINS) < 1000) {
			log("Less than 1000 coins remaining — stopping script.");
			if (shop != null && shop.isVisible())
				shop.close();
			stop();
			return 0;
		}
		
		if (menuDesync) {
			if (shop != null && shop.isVisible())
				shop.close();
			
			getProfileManager().forceHop();
			menuDesync = false;
			return 0;
		}
		
		
		ensureSealEquipped();

		if (shop.isVisible()) {
			handleShop();
			return 0;
		}
		
		if (allRunesCompleted()) {
			log("All selected runes purchased — stopping script.");
			stop();
			return 0;
		}
		
		
		if (hopFlag) {
			getProfileManager().forceHop();
			hopFlag = false;
			return 0;
		}
		
		openShop();
		return 0;
	}
	
	private void handleShop() {
		

		if (!shop.setSelectedAmount(50)) {
			log("Failed to select amount 50.");
			return;
		}
		
		boolean boughtThisTick = false;
		
		for (RuneConfig r : runes) {
			
			if (!r.enabled)
				continue;
			
			ItemGroupResult inv = getWidgetManager().getInventory().search(Set.of(r.itemId));
			int invAmt = inv == null ? 0 : inv.getAmount(r.itemId);
			
			if (invAmt >= r.targetTotal)
				continue;
			
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
			
			if (alreadyBoughtThisWorld >= r.perWorld)
				continue;
			
			int before = invAmt;
			
			if (!shopItem.interact()) {
				log("Menu not found / interact failed — forcing shop close + hop");
				menuDesync = true;
				return;
			}
			
			boolean success = pollFramesUntil(() -> {
				ItemGroupResult after = getWidgetManager().getInventory().search(Set.of(r.itemId));
				return after != null && after.getAmount(r.itemId) > before;
			}, 4000);
			
			if (success) {
				int afterAmt = getWidgetManager().getInventory().search(Set.of(r.itemId)).getAmount(r.itemId);
				int gained = Math.max(0, afterAmt - before);
				
				r.totalBought += gained;
				boughtThisTick = true;
			}
			
			break; // one buy per poll
		}
		
		if (!boughtThisTick) {
			log("No purchase possible — forcing shop close + hop");
			menuDesync = true;
		}
	}
	
	private void openShop() {
		
		var npcPositions = getWidgetManager().getMinimap().getNPCPositions();
		if (npcPositions == null || npcPositions.isNotVisible())
			return;
		
		WorldPosition me = getWorldPosition();
		if (me == null)
			return;
		
		for (WorldPosition pos : npcPositions) {
			

			if (me.distanceTo(pos) > 6)
				continue;
			
			Polygon cube = getSceneProjector().getTileCube(pos, 90);
			if (cube == null)
				continue;
			
			Polygon resized = cube.getResized(0.6);
			if (resized == null)
				continue;
			
			if (!getWidgetManager().insideGameScreen(resized, Collections.emptyList()))
				continue;
			
			if (getFinger().tapGameScreen(resized, "Trade")) {
				pollFramesHuman(() -> shop.isVisible(), random(3000, 6000));
				return;
			}
		}
	}
	
	private void ensureSealEquipped() {
		ItemGroupResult inv = getWidgetManager().getInventory().search(Set.of(SEAL_OF_PASSAGE));
		if (inv != null && inv.contains(SEAL_OF_PASSAGE)) {
			ItemSearchResult seal = inv.getItem(SEAL_OF_PASSAGE);
			if (seal != null) seal.interact("Wear");
		}
	}
	
	@Override
	public void onPaint(Canvas c) {
		
		long elapsed = System.currentTimeMillis() - startTime;
		if (elapsed <= 0)
			return;
		
		int x = 10;
		int y = 40;
		
		c.fillRect(5, y - 25, 320, 20 + runes.size() * 14, 0x88000000, 0.9);
		c.drawRect(5, y - 25, 320, 20 + runes.size() * 14, 0xFFFFFFFF);
		
		c.drawText("Baba Yaga Rune Buyer", x, y, 0xFFFFFFFF, PAINT_FONT);
		y += 16;
		
		for (RuneConfig r : runes) {
			if (!r.enabled || r.totalBought == 0)
				continue;
			
			long perHour = (long) ((r.totalBought * 3600000D) / elapsed);
			
			c.drawText(
					r.name + ": " + r.totalBought + " (" + perHour + "/hr)",
					x,
					y,
					0xFFFFFFFF,
					PAINT_FONT
			          );
			y += 14;
			
			}
			
		}
	}
