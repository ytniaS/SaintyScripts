package com.osmb.script.boneblesser;

import java.awt.Color;
import java.awt.Font;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import com.osmb.api.item.ItemGroupResult;
import com.osmb.api.item.ItemSearchResult;
import com.osmb.api.location.position.types.WorldPosition;
import com.osmb.api.scene.RSObject;
import com.osmb.api.script.Script;
import com.osmb.api.script.ScriptDefinition;
import com.osmb.api.script.SkillCategory;
import com.osmb.api.shape.Polygon;
import com.osmb.api.shape.Rectangle;
import com.osmb.api.ui.component.tabs.SettingsTabComponent;
import com.osmb.api.visual.drawing.Canvas;
import com.osmb.api.walker.WalkConfig;

@ScriptDefinition(
		name = "Bone Blesser",
		author = "Sainty",
		version = 2.6,
		description = "Unnotes, blesses and chisels bones.",
		skillCategory = SkillCategory.PRAYER
)
public class BoneBlesser extends Script {
	private static final int REGION_TEOMAT = 5681;
	private static final int CHISEL_ID = 1755;
	private static final Rectangle ALTAR_AREA =
			new Rectangle(1433, 3147, 5, 5);
	private static final Rectangle VIRILIS_RECT =
			new Rectangle(1441, 3154, 5, 4);
	//To prevent clicking Renu for those that have done the quest
	private static final Rectangle RENU_RECT =
			new Rectangle(1436, 3168, 3, 2);
	private static final int BONE_SHARDS_ID = 29381;
	private static final long WALK_COOLDOWN_MS = 6000;
	private static final long STABLE_MS = 500;
	private static final long UNNOTE_COOLDOWN_MS = 8000;
	private static final long BLESS_COOLDOWN_MS = 8000;
	private static final long DEBUG_LOG_MS = 2000;
	private static final long DETECTION_TIMEOUT_MS = 90000;
	private static final java.awt.Font PAINT_FONT =
			new java.awt.Font("Arial", java.awt.Font.PLAIN, 12);
	private long scriptStartTime;
	private int totalShards;
	private int lastShardCount = -1;
	private int remainingBones;
	private BoneType selectedBone = null;
	private boolean detectionTimedOut = false;
	private static final Set<Integer> COIN_SET = Collections.singleton(995);
	private static final Set<Integer> INV_IDS = new HashSet<>();
	private static final Set<Integer> ALL_BONE_IDS = new HashSet<>();
	private long lastPollAt = 0;
	
	static {
		for (BoneType t : BoneType.values()) {
			ALL_BONE_IDS.add(t.unblessedId);
			ALL_BONE_IDS.add(t.notedId);
			ALL_BONE_IDS.add(t.blessedId);
		}
	}
	
	private WorldPosition lastPos;
	private long lastPosChange;
	private long lastWalkAt;
	private long lastUnnoteAt;
	private long lastBlessAt;
	private long lastDebugAt;
	private long lastSuccessfulUnnoteAt = 0;
	private int lastNotedCount = -1;
	
	public BoneBlesser(Object core) {
		super(core);
	}
	
	@Override
	public void onStart() {
		if (!VersionChecker.isExactVersion(this)) {
			stop();
			return;
		}
		ensureMaxZoom();
		scriptStartTime = System.currentTimeMillis();
		totalShards = 0;
		lastShardCount = -1;
		detectionTimedOut = false;
		lastSuccessfulUnnoteAt = System.currentTimeMillis();
		lastNotedCount = -1;
		INV_IDS.clear();
		for (BoneType t : BoneType.values()) {
			INV_IDS.add(t.unblessedId);
			INV_IDS.add(t.notedId);
			INV_IDS.add(t.blessedId);
		}
		INV_IDS.add(CHISEL_ID);
		log("BoneBlesser", "Started – auto-detecting bone type from inventory");
	}
	
	@Override
	public int[] regionsToPrioritise() {
		return new int[] {REGION_TEOMAT};
	}
	
	private boolean recentlyMoved() {
		WorldPosition now = getWorldPosition();
		if (now == null) {return true;}
		if (!now.equals(lastPos)) {
			lastPos = now;
			lastPosChange = System.currentTimeMillis();
			return true;
		}
		return System.currentTimeMillis() - lastPosChange < STABLE_MS;
	}
	
	private boolean canInteract() {
		if (recentlyMoved()) {return false;}
		var d = getWidgetManager().getDialogue();
		return d == null || !d.isVisible();
	}
	
	private boolean inRect(WorldPosition p, Rectangle r) {
		return p != null &&
				p.getX() >= r.x &&
				p.getX() < r.x + r.width &&
				p.getY() >= r.y &&
				p.getY() < r.y + r.height;
	}
	
	private void walkToRect(Rectangle r) {
		long now = System.currentTimeMillis();
		if (now - lastWalkAt < WALK_COOLDOWN_MS) {return;}
		lastWalkAt = now;
		int x = r.x + random(r.width);
		int y = r.y + random(r.height);
		getWalker().walkTo(
				new WorldPosition(x, y, 0),
				new WalkConfig.Builder()
						.setWalkMethods(false, true)
						.build()
		                  );
	}
	
	private void detectBoneType(ItemGroupResult inv) {
		if (selectedBone != null) {return;}
		for (BoneType t : BoneType.values()) {
			if (getWidgetManager().getInventory()
					.search(Collections.singleton(t.notedId))
					.getItem(t.notedId) != null) {
				selectedBone = t;
				return;
			}
		}
		for (BoneType t : BoneType.values()) {
			if (getWidgetManager().getInventory()
					.search(Collections.singleton(t.blessedId))
					.getItem(t.blessedId) != null) {
				selectedBone = t;
				return;
			}
		}
		for (BoneType t : BoneType.values()) {
			if (getWidgetManager().getInventory()
					.search(Collections.singleton(t.unblessedId))
					.getItem(t.unblessedId) != null) {
				selectedBone = t;
				return;
			}
		}
	}
	
	//Sometimes the clicking isn't accurate/can't reach NPCs if not fully zoomed, ensure this is set to max
	private void ensureMaxZoom() {
		if (!getWidgetManager().getSettings()
				.openSubTab(SettingsTabComponent.SettingsSubTabType.DISPLAY_TAB)) {
			log("BoneBlesser", "Failed to open display settings tab");
			return;
		}
		var zoomResult = getWidgetManager()
				.getSettings()
				.getZoomLevel();
		Integer zoom = zoomResult != null ? zoomResult.get() : null;
		if (zoom == null) {
			log("BoneBlesser", "Failed to read zoom level");
			return;
		}
		log("BoneBlesser", "Current zoom level: " + zoom);
		// Force max zoom-out
		if (zoom < 5) {
			if (getWidgetManager().getSettings().setZoomLevel(5)) {
				log("BoneBlesser", "Zoom set to maximum (5)");
				sleep(random(400, 600));
			} else {
				log("BoneBlesser", "Failed to set zoom level");
			}
		}
	}
	
	@Override
	public int poll() {
		long now = System.currentTimeMillis();
		if (lastPollAt != 0 && now - lastPollAt > 60_000) {
			// Large gap is likely a break, count the timeout from when the last poll was so it doesn't instantly timeout on
			// logging in
			lastSuccessfulUnnoteAt = now;
			lastNotedCount = -1;
			log("BoneBlesser", "Resumed after pause — reset unnote stall timer");
		}
		lastPollAt = now;
		ItemGroupResult inv =
				getWidgetManager().getInventory().search(INV_IDS);
		if (inv == null) {return random(120, 180);}
		detectBoneType(inv);
		if (selectedBone == null) {
			if (!detectionTimedOut && now - scriptStartTime > DETECTION_TIMEOUT_MS) {
				log("BoneBlesser",
				    "No bones detected after timeout. Stopping.");
				// Avoid running indefinitely if the inventory never contains a valid bone type
				stop();
			}
			return random(250, 400);
		}
		ItemSearchResult noted =
				getWidgetManager().getInventory()
						.search(Collections.singleton(selectedBone.notedId))
						.getItem(selectedBone.notedId);
		ItemSearchResult unblessed =
				getWidgetManager().getInventory()
						.search(Collections.singleton(selectedBone.unblessedId))
						.getItem(selectedBone.unblessedId);
		ItemSearchResult blessed =
				getWidgetManager().getInventory()
						.search(Collections.singleton(selectedBone.blessedId))
						.getItem(selectedBone.blessedId);
		ItemSearchResult chisel = inv.getItem(CHISEL_ID);
		if (noted != null) {
			int currentNoted = noted.getStackAmount();
			if (lastNotedCount != -1 && currentNoted < lastNotedCount) {
				lastSuccessfulUnnoteAt = now;
			}
			lastNotedCount = currentNoted;
		}
		if (blessed != null) {
			ItemSearchResult shardStack =
					getWidgetManager().getInventory()
							.search(Collections.singleton(BONE_SHARDS_ID))
							.getItem(BONE_SHARDS_ID);
			if (shardStack != null) {
				int current = shardStack.getStackAmount();
				if (lastShardCount != -1 && current > lastShardCount) {totalShards += (current - lastShardCount);}
				lastShardCount = current;
			}
		}
		remainingBones = 0;
		if (noted != null) {remainingBones += Math.max(1, noted.getStackAmount());}
		List<ItemSearchResult> unblessedList = inv.getAllOfItem(selectedBone.unblessedId);
		if (unblessedList != null) {remainingBones += unblessedList.size();}
		List<ItemSearchResult> blessedList = inv.getAllOfItem(selectedBone.blessedId);
		if (blessedList != null) {remainingBones += blessedList.size();}
		if (noted != null) {
			long sinceLastUnnote = now - lastSuccessfulUnnoteAt;
			if (sinceLastUnnote > 180000) {
				log("BoneBlesser", "Stopping: Unnoting stalled for 180 seconds."); //timeout to prevent infinite loops
				stop();
				return -1;
			}
		}
		var dialogue = getWidgetManager().getDialogue();
		if (dialogue != null && dialogue.isVisible()) {
			var b = dialogue.getBounds();
			if (b != null) {
				getFinger().tap(
						b.x + b.width / 2,
						b.y + (int) (b.height * 0.58)
				               );
				return random(300, 450);
			}
		}
		WorldPosition me = getWorldPosition();
		if (me == null) {return random(120, 180);}
		boolean needChisel = chisel != null && blessed != null;
		boolean needBless = unblessed != null;
		boolean needUnnote = noted != null && unblessed == null && inv.getFreeSlots() > 0;
		if (now - lastDebugAt > DEBUG_LOG_MS) {
			log("BoneBlesser",
			    "State | noted=" + (noted != null) +
					    " unblessed=" + (unblessed != null) +
					    " blessed=" + (blessed != null) +
					    " free=" + inv.getFreeSlots() +
					    " moved=" + recentlyMoved()
			   );
			lastDebugAt = now;
		}
		if (needChisel && canInteract()) {
			ItemSearchResult target = null;
			List<ItemSearchResult> allBlessed =
					getWidgetManager().getInventory()
							.search(Collections.singleton(selectedBone.blessedId))
							.getAllOfItem(selectedBone.blessedId);
			if (allBlessed != null && !allBlessed.isEmpty()) {
				for (ItemSearchResult r : allBlessed) {
					if (r != null && r.getSlot() == 27) {
						target = r;
						break;
					}
				}
				if (target == null) {target = allBlessed.get(0);}
			}
			if (target == null) {return random(40, 80);}
			if (!chisel.interact()) {return random(60, 90);}
			pollFramesHuman(() -> false, random(40, 70));
			target.interact();
			return random(80, 120);
		}
		if (needBless && now - lastBlessAt > BLESS_COOLDOWN_MS) {
			RSObject altar =
					getObjectManager().getClosestObject(me, "Exposed altar");
			if (altar != null && canInteract()) {
				Polygon poly = altar.getConvexHull();
				if (poly != null) {
					poly = poly.getResized(0.6);
					if (getWidgetManager().insideGameScreen(poly, Collections.emptyList())) {
						getFinger().tapGameScreen(poly, "Bless");
						lastBlessAt = now;
						return random(120, 180);
					}
				}
			}
			if (!inRect(me, ALTAR_AREA)) {
				walkToRect(ALTAR_AREA);
				return random(200, 300);
			}
		}
		if (needUnnote && now - lastUnnoteAt > UNNOTE_COOLDOWN_MS) {
			for (WorldPosition npc :
					getWidgetManager().getMinimap().getNPCPositions().asList()) {
				if (inRect(npc, RENU_RECT)) {continue;}
				Polygon cube = getSceneProjector().getTileCube(npc, 120);
				if (cube == null) {continue;}
				cube = cube.getResized(0.7);
				if (!getWidgetManager().insideGameScreen(cube, Collections.emptyList())) {continue;}
				noted.interact();
				pollFramesHuman(() -> false, random(10, 30));
				getFinger().tapGameScreen(cube);
				lastUnnoteAt = now;
				return random(600, 900);
			}
			if (!inRect(me, VIRILIS_RECT)) {walkToRect(VIRILIS_RECT);}
		}
		return random(200, 300);
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
		long elapsed = System.currentTimeMillis() - scriptStartTime;
		if (elapsed <= 0) {return;}
		int x = 16;
		int y = 40;
		int w = 240;
		int headerH = 50;
		int bodyH = 95;
		int BG = new Color(12, 14, 20, 235).getRGB();
		int BORDER = new Color(100, 100, 110, 180).getRGB();
		int DIVIDER = new Color(255, 255, 255, 40).getRGB();
		c.fillRect(x, y, w, headerH + bodyH, BG, 0.95);
		c.drawRect(x, y, w, headerH + bodyH, BORDER);
		drawHeader(c, "Sainty", "Bone Blesser", x + 14, y + 18);
		c.fillRect(x + 10, y + headerH, w - 20, 1, DIVIDER);
		int ty = y + headerH + 18;
		if (selectedBone == null) {
			c.drawText("Detecting bones...", x + 14, ty, 0xFFFFCC00, PAINT_FONT);
			return;
		}
		double hours = elapsed / 3_600_000D;
		int shardsPerHour = hours > 0 ? (int) (totalShards / hours) : 0;
		int bonesPerHour = selectedBone.shardsPerBone > 0
		                   ? shardsPerHour / selectedBone.shardsPerBone
		                   : 0;
		String timeLeft = "N/A";
		if (bonesPerHour > 0 && remainingBones > 0) {
			long mins = (remainingBones * 60L) / bonesPerHour;
			timeLeft = (mins / 60) + "h " + (mins % 60) + "m";
		}
		c.drawText("Type: " + selectedBone.name, x + 14, ty, 0xFF66FF66, PAINT_FONT);
		ty += 14;
		c.drawText("Shards: " + totalShards, x + 14, ty, 0xFFFFFFFF, PAINT_FONT);
		ty += 14;
		c.drawText("Shards/hr: " + shardsPerHour, x + 14, ty, 0xFF66CCFF, PAINT_FONT);
		ty += 14;
		c.drawText("Time left: " + timeLeft, x + 14, ty, 0xFFFFAA00, PAINT_FONT);
	}
	
	public enum BoneType {
		NORMAL_BONES("Bones", 526, 527, 29344, 4),
		BAT_BONES("Bat bones", 530, 531, 29346, 5),
		BIG_BONES("Big bones", 532, 533, 29348, 12),
		ZOGRE_BONES("Zogre bones", 4812, 4813, 29350, 18),
		BABYDRAGON_BONES("Babydragon bones", 534, 535, 29352, 24),
		WYRMLING_BONES("Wyrmling bones", 28899, 28900, 29354, 21),
		DRAGON_BONES("Dragon bones", 536, 537, 29356, 58),
		LAVA_DRAGON_BONES("Lava dragon bones", 11943, 11944, 29358, 68),
		WYVERN_BONES("Wyvern bones", 6812, 6813, 29360, 58),
		SUPERIOR_DRAGON_BONES("Superior dragon bones", 22124, 22125, 29362, 121),
		WYRM_BONES("Wyrm bones", 22780, 22781, 29364, 42),
		DRAKE_BONES("Drake bones", 22783, 22784, 29366, 67),
		HYDRA_BONES("Hydra bones", 22786, 22787, 29368, 93),
		FAYRG_BONES("Fayrg bones", 4830, 4831, 29370, 67),
		RAURG_BONES("Raurg bones", 4832, 4833, 29372, 77),
		OURG_BONES("Ourg bones", 4834, 4835, 29374, 115),
		DAGANNOTH_BONES("Dagannoth bones", 6729, 6730, 29376, 100),
		STRYKWYRM_BONES("Strykwyrm bones", 31726, 31727, 31264, 37),
		FROST_DRAGON_BONES("Frost dragon bones", 31729, 31730, 31266, 84);
		public final String name;
		public final int unblessedId;
		public final int notedId;
		public final int blessedId;
		public final int shardsPerBone;
		
		BoneType(String n, int u, int no, int b, int shards) {
			name = n;
			unblessedId = u;
			notedId = no;
			blessedId = b;
			shardsPerBone = shards;
		}
	}
}

