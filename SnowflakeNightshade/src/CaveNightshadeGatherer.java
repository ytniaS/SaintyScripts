package com.osmb.script.cavenightshade;

import java.awt.Color;
import java.awt.Font;
import java.util.Collections;
import java.util.Set;

import com.osmb.api.item.ItemGroupResult;
import com.osmb.api.location.position.types.WorldPosition;
import com.osmb.api.scene.RSObject;
import com.osmb.api.script.Script;
import com.osmb.api.script.ScriptDefinition;
import com.osmb.api.script.SkillCategory;
import com.osmb.api.shape.Polygon;
import com.osmb.api.ui.component.tabs.SettingsTabComponent;
import com.osmb.api.visual.drawing.Canvas;
import com.osmb.api.walker.WalkConfig;

@ScriptDefinition(
		name = "Cave Nightshade Gatherer",
		author = "Sainty",
		version = 1.2,
		description = "Gathers Cave Nightshade and banks it for snowflakes.",
		skillCategory = SkillCategory.OTHER
)
public class CaveNightshadeGatherer extends Script {
	private static final int CAVE_NIGHTSHADE = 2398;
	private static final int CAVE_REGION = 10131;
	private static final int[] PRIORITY_REGIONS = {
			9776, 10032, 10131, 10031
	};
	private static final WorldPosition BANK_TILE =
			new WorldPosition(2614, 3092, 0);
	private static final WorldPosition GATE_POSITION =
			new WorldPosition(2549, 3028, 0);
	private static final WorldPosition GATE_NORTH_APPROACH =
			new WorldPosition(2555, 3057, 0); // bank side
	private static final WorldPosition GATE_SOUTH_APPROACH =
			new WorldPosition(2547, 3022, 0); // cave side
	private static final WorldPosition PRE_CAVE_TILE =
			new WorldPosition(2530, 3012, 0);
	private static final WorldPosition NIGHTSHADE_TILE =
			new WorldPosition(2528, 9415, 0);
	
	private boolean isSouthSide(WorldPosition me) {
		return me.getY() <= 3027;
	}
	
	private boolean isNorthSide(WorldPosition me) {
		return me.getY() >= 3028;
	}
	
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
	private long totalCollected;
	private boolean enteringCave = false;
	
	private boolean canHop() {
		WorldPosition pos = getWorldPosition();
		return pos != null && pos.getRegionID() == CAVE_REGION;
	}
	
	public CaveNightshadeGatherer(Object core) {
		super(core);
	}
	
	@Override
	public void onStart() {
		startTime = System.currentTimeMillis();
		if (!VersionChecker.isExactVersion(this)) {
			stop();
			return;
		}
		ensureMaxZoom();
	}
	
	public int poll() {
		ItemGroupResult inv =
				getWidgetManager().getInventory().search(Set.of(CAVE_NIGHTSHADE));
		if (inv == null) {return 0;}
		WorldPosition me = getWorldPosition();
		if (me == null) {return 0;}
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
	
	private void ensureMaxZoom() {
		if (!getWidgetManager().getSettings()
				.openSubTab(SettingsTabComponent.SettingsSubTabType.DISPLAY_TAB)) {
			log("CaveNightShade", "Failed to open display settings tab");
			return;
		}
		var zoomResult = getWidgetManager()
				.getSettings()
				.getZoomLevel();
		Integer zoom = zoomResult != null ? zoomResult.get() : null;
		if (zoom == null) {
			log("CaveNightShade", "Failed to read zoom level");
			return;
		}
		log("CaveNightShade", "Current zoom level: " + zoom);
		if (zoom < 5) {
			if (getWidgetManager().getSettings().setZoomLevel(5)) {
				log("CaveNightShade", "Zoom set to maximum (5)");
				sleep(random(400, 600));
			} else {
				log("CaveNightShade", "Failed to set zoom level");
			}
		}
	}
	
	private boolean inCave() {
		WorldPosition pos = getWorldPosition();
		return pos != null && pos.getRegionID() == CAVE_REGION && pos.getY() > 9000;
	}
	
	private boolean gateOnCooldown() {
		if (!gateLocked) {return false;}
		if (System.currentTimeMillis() > gateLockUntil) {
			gateLocked = false;
			gateLockUntil = 0;
			return false;
		}
		return true;
	}
	
	private void crossGate() {
		WorldPosition me = getWorldPosition();
		if (me == null) {return;}
		if (gateOnCooldown()) {
			WorldPosition escape = headingToBank ? BANK_TILE : PRE_CAVE_TILE;
			if (me.distanceTo(escape) > 4) {
				walkTo(escape);
			}
			return;
		}
		if (headingToBank && isSouthSide(me)) {
			if (me.distanceTo(GATE_POSITION) <= 3) {
				RSObject gate = getObjectManager().getClosestObject(me, "City gate");
				if (gate != null && gate.interact("Open")) {
					gateLocked = true;
					gateLockUntil = System.currentTimeMillis() + 2500;
					pollFramesHuman(() -> false, random(3500, 4000));
				}
				return;
			}
			if (me.distanceTo(GATE_SOUTH_APPROACH) > 2) {
				walkTo(GATE_SOUTH_APPROACH);
				return;
			}
			RSObject gate = getObjectManager().getClosestObject(me, "City gate");
			if (gate != null && gate.interact("Open")) {
				gateLocked = true;
				gateLockUntil = System.currentTimeMillis() + 2500;
				pollFramesHuman(() -> false, random(3500, 4000));
			}
			return;
		}
		if (!headingToBank && isNorthSide(me)) {
			if (me.distanceTo(GATE_POSITION) <= 3) {
				RSObject gate = getObjectManager().getClosestObject(me, "City gate");
				if (gate != null && gate.interact("Open")) {
					gateLocked = true;
					gateLockUntil = System.currentTimeMillis() + 2500;
					pollFramesHuman(() -> false, random(3500, 4000));
				}
				return;
			}
			if (me.distanceTo(GATE_NORTH_APPROACH) > 2) {
				walkTo(GATE_NORTH_APPROACH);
				return;
			}
			RSObject gate = getObjectManager().getClosestObject(me, "City gate");
			if (gate != null && gate.interact("Open")) {
				gateLocked = true;
				gateLockUntil = System.currentTimeMillis() + 2500;
				pollFramesHuman(() -> false, random(3500, 4000));
			}
			return;
		}
	}
	
	private void enterCave() {
		WorldPosition me = getWorldPosition();
		if (me == null) {return;}
		if (inCave()) {
			enteringCave = false;
			state = State.PICKUP;
			return;
		}
		if (enteringCave) {
			return;
		}
		if (me.distanceTo(PRE_CAVE_TILE) > 4) {
			walkTo(PRE_CAVE_TILE);
			return;
		}
		RSObject entrance =
				getObjectManager().getClosestObject(me, "Cave entrance");
		if (entrance != null && entrance.interact("Enter")) {
			enteringCave = true; // prevent re-clicking while transitioning
			pollFramesHuman(() -> false, random(600, 900));
		}
	}
	
	private void leaveCave() {
		WorldPosition me = getWorldPosition();
		if (me == null) {return;}
		if (!inCave()) {return;}
		RSObject exit =
				getObjectManager().getClosestObject(me, "Cave exit");
		if (exit != null && exit.interact("Leave")) {
			pollFramesHuman(() -> false, random(3000, 4000));
		}
	}
	
	private void pickupNightshade() {
		if (!inCave()) {
			return;
		}
		WorldPosition me = getWorldPosition();
		if (me == null) {return;}
		if (me.distanceTo(NIGHTSHADE_TILE) > 1) {
			walkTo(NIGHTSHADE_TILE);
			return;
		}
		ItemGroupResult inv =
				getWidgetManager().getInventory().search(Set.of(CAVE_NIGHTSHADE));
		int before = inv == null ? 0 : inv.getAmount(CAVE_NIGHTSHADE);
		Polygon tilePoly = getSceneProjector().getTilePoly(NIGHTSHADE_TILE);
		if (tilePoly == null) {
			log("Cave Nightshade missing — hopping world.");
			if (canHop()) {
				getProfileManager().forceHop();
			}
			return;
		}
		Polygon tight = tilePoly.getResized(0.4);
		if (tight == null ||
				!getWidgetManager().insideGameScreen(tight, Collections.emptyList())) {
			log("Cave Nightshade not visible — hopping world.");
			if (canHop()) {
				getProfileManager().forceHop();
			}
			return;
		}
		getFinger().tapGameScreen(tight, "Take");
		boolean success = pollFramesUntil(() -> {
			ItemGroupResult after =
					getWidgetManager().getInventory().search(Set.of(CAVE_NIGHTSHADE));
			int now = after == null ? 0 : after.getAmount(CAVE_NIGHTSHADE);
			return now > before;
		}, 1200);
		if (success) {
			totalCollected++;
			log("Picked up Cave Nightshade — hopping world.");
		} else {
			log("Cave Nightshade missing — hopping world.");
		}
		if (canHop()) {
			getProfileManager().forceHop();
		}
	}
	
	private void handleBanking(ItemGroupResult inv) {
		WorldPosition me = getWorldPosition();
		if (me == null) {return;}
		if (me.distanceTo(BANK_TILE) > 3) {
			walkTo(BANK_TILE);
			return;
		}
		Polygon bankPoly = getSceneProjector().getTilePoly(BANK_TILE);
		if (bankPoly == null) {return;}
		getFinger().tap(bankPoly, "Bank");
		boolean opened = pollFramesUntil(() ->
				                                 getWidgetManager().getBank().isVisible(), 4000);
		if (!opened) {return;}
		int amt = inv.getAmount(CAVE_NIGHTSHADE);
		if (amt > 0) {
			getWidgetManager().getBank().deposit(CAVE_NIGHTSHADE, amt);
		}
		getWidgetManager().getBank().close();
		gateLocked = false;
		gateLockUntil = 0;
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
	
	@Override
	public void onPaint(Canvas c) {
		long elapsed = System.currentTimeMillis() - startTime;
		if (elapsed <= 0) {return;}
		int x = 16;
		int y = 40;
		int w = 300;
		int headerH = 45;
		int lineH = 14;
		int BG = new Color(12, 14, 20, 235).getRGB();
		int BORDER = new Color(100, 100, 110, 180).getRGB();
		int DIVIDER = new Color(255, 255, 255, 40).getRGB();
		int bodyH = 84;
		c.fillRect(x, y, w, headerH + bodyH, BG, 0.95);
		c.drawRect(x, y, w, headerH + bodyH, BORDER);
		drawHeader(c, "Sainty", "Cave Nightshade", x + 14, y + 16);
		c.fillRect(x + 10, y + headerH, w - 20, 1, DIVIDER);
		int ty = y + headerH + 18;
		long perHour = (long) ((totalCollected * 3_600_000D) / elapsed);
		Font body = new Font("Segoe UI", Font.PLAIN, 13);
		c.drawText("State: " + state, x + 14, ty, 0xFFAAAAFF, body);
		ty += lineH;
		c.drawText(
				"Heading: " + (headingToBank ? "BANK" : "CAVE"),
				x + 14,
				ty,
				0xFFFFAA00,
				body
		          );
		ty += lineH;
		c.drawText(
				"Runtime: " + format(elapsed),
				x + 14,
				ty,
				0xFFDDDDDD,
				body
		          );
		ty += lineH;
		c.drawText(
				"Collected: " + totalCollected,
				x + 14,
				ty,
				0xFF66FF66,
				body
		          );
		ty += lineH;
		c.drawText(
				"Per hour: " + perHour,
				x + 14,
				ty,
				0xFF66CCFF,
				body
		          );
	}
	
	private String format(long ms) {
		long s = ms / 1000;
		long m = s / 60;
		long h = m / 60;
		return String.format("%02d:%02d:%02d", h, m % 60, s % 60);
	}
	
	@Override
	public int[] regionsToPrioritise() {
		return PRIORITY_REGIONS;
	}
}