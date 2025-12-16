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
import com.osmb.api.visual.drawing.Canvas;
import com.osmb.api.walker.WalkConfig;

import javafx.geometry.Insets;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.VBox;

import java.awt.*;
import java.util.*;
import java.util.List;
import java.util.stream.Collectors;

@ScriptDefinition(
		name = "Lazy PestControl",
		author = "Sainty",
		version = 1.0,
		description = "Lazy Pest control - fights monsters around the void knight",
		skillCategory = SkillCategory.COMBAT
)
public class pestcontrol extends Script {
	
	private static final int REGION_LOBBY = 10537;
	private static final int REGION_GAME  = 10536;
	private static final int PEST_CONTROL_WORLD = 344;
	
	private static final int BOAT_DECK_OBJECT = 14256;
	
	// Combat area
	private static final RectangleArea COMBAT_AREA =
			new RectangleArea(2645, 2587, 24, 20, 0);
	
	private static final Rectangle VOID_KNIGHT_RECT =
			new Rectangle(2654, 2590, 5, 5);
	
	private static final WorldPosition VOID_KNIGHT_TILE =
			new WorldPosition(2656, 2592, 0);
	
	private static final WorldPosition SQUIRE_TILE =
			new WorldPosition(2655, 2607, 0);
	
	// Timing
	private static final int BOARD_CLICK_COOLDOWN_MS = 3500;
	private static final int BOARDING_MAX_MS = 65_000;
	private static final int SCENE_STABLE_TIME = 600;
	private static final int RECOVER_COOLDOWN_MS = 1200;
	private static final int RESULT_OBSERVE_TIME = 2000;
	
	// State tracking
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
	
	private Integer lastHp = null;
	private boolean deathFlag = false;
	
	private long lastWalkAt = 0;
	private long lastRecoverAttempt = 0;
	
	private int gamesWon = 0;
	private int totalPoints = 0;
	
	private int lastRegion = -1;
	private boolean awaitingResult = false;
	private boolean resultProcessed = false;
	private long awaitingResultStart = 0;
	
	private long startTime;
	
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
	
	public pestcontrol(Object core) {
		super(core);
	}
	
	@Override
	public void onStart() {
		
		// Custom map definitions to try help loading, no clue if this many helps lol
		addCustomMap(new MapDefinition(2624, 2560, 63, 57, 0, 0));
		addCustomMap(new MapDefinition(2645, 2600, 25, 25, 0, 0));
		addCustomMap(new MapDefinition(2624, 2562, 64, 61, 0, 0));
		addCustomMap(new MapDefinition(2562, 2498, 60, 124, 0, 0));
		addCustomMap(new MapDefinition(2624, 2560, 63, 57, 3, 0));
		addCustomMap(new MapDefinition(2645, 2600, 25, 25, 3, 0));
		
		PestOptions opts = new PestOptions();
		getStageController().show(new Scene(opts), "Pest Control", true);
		selectedBoat = opts.getSelectedBoat();
		
		startTime = System.currentTimeMillis();
	}
	
	@Override
	public int poll() {
		
		WorldPosition me = getWorldPosition();
		if (me == null)
			return 300;
		
		int region = me.getRegionID();
		

		if (lastRegion == REGION_GAME && region == REGION_LOBBY) {
			awaitingResult = true;
			resultProcessed = false;
			awaitingResultStart = System.currentTimeMillis();
			boardingInProgress = false;
			inGame = true;
		}
		
		lastRegion = region;
		
		if (region == REGION_GAME) {
			
			if (!inGame) {
				inGame = true;
				instanceSettled = false;
				instanceResolveUntil = System.currentTimeMillis() + random(4500, 6000);
				return 300;
			}
			

			if (!instanceSettled) {
				if (System.currentTimeMillis() < instanceResolveUntil)
					return 300;
				instanceSettled = true;
			}
			
			handleGame();
		}
		
		if (region == REGION_LOBBY) {
			if (ensureCorrectWorld())
				return 600;
			handleLobby();
		}
		
		return random(40, 80);
	}
	
	@Override
	public boolean canBreak() {
		return !boardingInProgress && !isOnBoat() && !inGame && !awaitingResult;
	}
	
	@Override
	public int[] regionsToPrioritise() {
		return new int[]{REGION_LOBBY, REGION_GAME};
	}
	
	private void handleGame() {
		
		if (!sceneIsStable())
			return;
		
		trackDeath();
		

		if (!COMBAT_AREA.contains(getWorldPosition())) {
			
			attacking = false;
			targetOverlay = null;
			
			if (System.currentTimeMillis() - lastRecoverAttempt < RECOVER_COOLDOWN_MS)
				return;
			
			lastRecoverAttempt = System.currentTimeMillis();
			tryWalkTo(randomIn(VOID_KNIGHT_RECT));
			return;
		}
		

		if (attacking && targetOverlay != null && targetOverlay.isVisible())
			return;
		
		attacking = false;
		targetOverlay = null;
		
		attackNpc();
	}
	
	private boolean attackNpc() {
		
		WorldPosition me = getWorldPosition();
		List<WorldPosition> npcs =
				getWidgetManager().getMinimap().getNPCPositions().asList();
		
		// Filter and sort targets
		List<WorldPosition> targets = npcs.stream()
				.filter(COMBAT_AREA::contains)
				.filter(p -> !p.equals(VOID_KNIGHT_TILE))
				.filter(p -> !p.equals(SQUIRE_TILE))
				.sorted(Comparator.comparingDouble(p -> p.distanceTo(me)))
				.limit(6)
				.collect(Collectors.toList());
		
		for (WorldPosition npc : targets) {
			
			Polygon poly = getSceneProjector().getTileCube(npc, 75);
			if (poly == null)
				continue;
			
			poly = poly.getResized(0.7);
			if (!getWidgetManager().insideGameScreen(poly, Collections.emptyList()))
				continue;
			

			if (getFinger().tapGameScreen(poly, "Attack")) {
				attacking = true;
				targetOverlay = new HealthOverlay(this);
				return true;
			}
		}
		return false;
	}
	
	private boolean tryWalkTo(WorldPosition pos) {
		
		if (!instanceSettled)
			return false;
		
		if (System.currentTimeMillis() - lastWalkAt < 1000)
			return false;
		
		WalkConfig cfg = new WalkConfig.Builder()
				.tileRandomisationRadius(2)
				.breakDistance(2)
				.build();
		
		boolean ok = getWalker().walkTo(pos, cfg);
		if (ok)
			lastWalkAt = System.currentTimeMillis();
		
		return ok;
	}
	
	private WorldPosition randomIn(Rectangle r) {
		return new WorldPosition(
				r.x + random(r.width),
				r.y + random(r.height),
				0
		);
	}
	
	private void handleLobby() {
		
		if (isOnBoat()) {
			boardingInProgress = false;
			return;
		}
		
		if (boardingInProgress) {
			if (System.currentTimeMillis() - boardingStart > BOARDING_MAX_MS)
				boardingInProgress = false;
			return;
		}
		
		if (awaitingResult) {
			checkGameResult();
			return;
		}
		

		if (getWorldPosition().distanceTo(selectedBoat.plank) > 5) {
			tryWalkTo(selectedBoat.plank);
			return;
		}
		
		if (System.currentTimeMillis() - lastBoardClick < BOARD_CLICK_COOLDOWN_MS)
			return;
		

		Polygon cube = getSceneProjector().getTileCube(selectedBoat.plank, 100);
		if (cube == null)
			return;
		
		Polygon click = cube.getResized(0.7);
		if (!getWidgetManager().insideGameScreen(click, Collections.emptyList()))
			return;
		

		boolean crossed = getFinger().tapGameScreen(click, menu -> menu.stream() .filter(m -> m.getRawText() != null && m.getRawText().toLowerCase().startsWith("cross")) .findFirst() .orElse(null) );
		
		if (crossed) {
			lastBoardClick = System.currentTimeMillis();
			boardingInProgress = true;
			boardingStart = lastBoardClick;
			
			inGame = true;
			instanceSettled = false;
			instanceResolveUntil = System.currentTimeMillis() + random(4500, 6000);
		}
	}
	
	private boolean ensureCorrectWorld() {
		
		if (!canBreak())
			return false;
		
		Integer current = getCurrentWorld();
		if (current != null && current == PEST_CONTROL_WORLD)
			return false;
		
		// Force hop to a PC world
		getProfileManager().forceHop(worlds ->
				                             worlds.stream()
						                             .filter(w -> w != null && w.getId() == PEST_CONTROL_WORLD)
						                             .findFirst()
						                             .orElse(null)
		                            );
		return true;
	}
	
	private boolean isOnBoat() {
		
		WorldPosition me = getWorldPosition();
		if (me == null)
			return false;
		

		List<RSObject> decks = getObjectManager().getObjects(BOAT_DECK_OBJECT);
		if (decks == null || decks.isEmpty())
			return false;
		
		for (RSObject o : decks) {
			WorldPosition p = o.getWorldPosition();
			if (p != null && me.distanceTo(p) <= 2)
				return true;
		}
		return false;
	}
	
	private void checkGameResult() {
		
		if (resultProcessed)
			return;
		
		var dialogue = getWidgetManager().getDialogue();
		boolean foundWin = false;
		
		if (dialogue != null && dialogue.isVisible()) {
			var text = dialogue.getText();
			// Check dialogue for win text
			if (text.isFound() &&
					text.get().toLowerCase().contains("void knight commendation")) {
				
				gamesWon++;
				// Extract points
				String digits = text.get().replaceAll("\\D+", "");
				totalPoints += digits.isEmpty()
				               ? selectedBoat.points
				               : Integer.parseInt(digits);
				foundWin = true;
			}
		}
		

		if (foundWin ||
				System.currentTimeMillis() - awaitingResultStart > RESULT_OBSERVE_TIME) {
			
			awaitingResult = false;
			resultProcessed = true;
			inGame = false;
			instanceSettled = false;
		}
	}
	
	private boolean sceneIsStable() {
		
		WorldPosition now = getWorldPosition();
		// Check if position is stable
		if (!Objects.equals(now, lastStablePos)) {
			lastStablePos = now;
			lastStableTime = System.currentTimeMillis();
			return false;
		}
		return System.currentTimeMillis() - lastStableTime >= SCENE_STABLE_TIME;
	}
	
	private void trackDeath() {
		
		Integer hp = getWidgetManager().getMinimapOrbs().getHitpoints();
		if (hp == null)
			return;
		
		if (lastHp != null) {

			if (lastHp > 0 && hp == 0)
				deathFlag = true;
			
			// Reset state after respawn/heal
			if (deathFlag && lastHp == 0 && hp > 0) {
				attacking = false;
				targetOverlay = null;
				deathFlag = false;
			}
		}
		lastHp = hp;
	}
	
	@Override
	public void onPaint(Canvas c) {
		
		Font f = new Font("Arial", Font.PLAIN, 13);
		
		// Draw overlay box
		c.fillRect(6, 20, 240, 110, 0x66000000, 0.7);
		c.drawRect(6, 20, 240, 110, Color.WHITE.getRGB());
		
		long run = System.currentTimeMillis() - startTime;
		long s = run / 1000, m = s / 60, h = m / 60;
		
		// Display stats
		c.drawText("Pest Control", 10, 40, Color.WHITE.getRGB(), f);
		c.drawText("Time: " + String.format("%02d:%02d:%02d", h, m % 60, s % 60),
		           10, 56, Color.WHITE.getRGB(), f);
		c.drawText("Boat: " + selectedBoat.name, 10, 72, Color.WHITE.getRGB(), f);
		c.drawText("Games won: " + gamesWon, 10, 88, Color.WHITE.getRGB(), f);
		c.drawText("Points: " + totalPoints, 10, 104, Color.WHITE.getRGB(), f);
	}
	
	private static class PestOptions extends VBox {
		
		private Boat selected = Boat.NOVICE;
		
		PestOptions() {
			
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