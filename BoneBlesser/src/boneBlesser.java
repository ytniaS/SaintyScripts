package com.osmb.script.boneblesser;

import com.osmb.api.item.*;
import com.osmb.api.location.position.types.WorldPosition;
import com.osmb.api.script.*;
import com.osmb.api.shape.*;
import com.osmb.api.visual.drawing.Canvas;
import com.osmb.api.walker.WalkConfig;
import com.osmb.api.scene.RSObject;
import javafx.geometry.Insets;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.layout.VBox;
import java.util.*;



@ScriptDefinition(
		name = "Bone Blesser",
		author = "Sainty",
		version = 1.8,
		description = "Unnotes, blesses and chisels bones.",
		skillCategory = SkillCategory.PRAYER
)
public class boneBlesser extends Script {
	
	private static final int REGION_TEOMAT = 5681;
	private static final int CHISEL_ID = 1755;
	
	private static final Rectangle ALTAR_AREA =
			new Rectangle(1433, 3147, 5, 5);
	
	private static final Rectangle VIRILIS_RECT =
			new Rectangle(1441, 3154, 5, 4);
	
	private static final int BONE_SHARDS_ID = 29381;
	
	private long scriptStartTime;
	private int totalShards;
	private int lastShardCount = -1;
	
	private static final long WALK_COOLDOWN_MS = 3200;
	private static final long STABLE_MS = 500;
	private static final long UNNOTE_COOLDOWN_MS = 1200;
	private static final long BLESS_COOLDOWN_MS = 6000;
	private static final long DEBUG_LOG_MS = 2000;
	private static final java.awt.Font PAINT_FONT =
			new java.awt.Font("Arial", java.awt.Font.PLAIN, 12);
	
	private BoneType selectedBone = BoneType.NORMAL_BONES;
	
	private static final Set<Integer> COIN_SET = Collections.singleton(995);
	private static final Set<Integer> INV_IDS = new HashSet<>();
	private static final Set<Integer> ALL_BONE_IDS = new HashSet<>();
	
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
	
	public boneBlesser(Object core) {
		super(core);
	}
	
	@Override
	public void onStart() {
		
		scriptStartTime = System.currentTimeMillis();
		totalShards = 0;
		lastShardCount = -1;
		
		
		INV_IDS.clear();
		for (BoneType t : BoneType.values()) {
			INV_IDS.add(t.unblessedId);
			INV_IDS.add(t.notedId);
			INV_IDS.add(t.blessedId);
		}
		INV_IDS.add(CHISEL_ID);
		
		ComboBox<BoneType> box = new ComboBox<>();
		box.getItems().addAll(BoneType.values());
		box.setValue(BoneType.NORMAL_BONES);
		
		Button confirm = new Button("Confirm");
		confirm.setOnAction(e -> {
			selectedBone = box.getValue();
			confirm.getScene().getWindow().hide();
			log("BoneBlesser", "Selected bone type: " + selectedBone.name);
		});
		
		VBox root = new VBox(10,
		                     new Label("Select Bone Type"),
		                     box,
		                     confirm
		);
		
		root.setPadding(new Insets(10));
		getStageController().show(new Scene(root), "Bone Blesser", true);
	}
	
	@Override
	public int[] regionsToPrioritise() {
		return new int[]{REGION_TEOMAT};
	}
	
	
	private boolean recentlyMoved() {
		WorldPosition now = getWorldPosition();
		if (now == null) return true;
		
		if (!now.equals(lastPos)) {
			lastPos = now;
			lastPosChange = System.currentTimeMillis();
			return true;
		}
		return System.currentTimeMillis() - lastPosChange < STABLE_MS;
	}
	
	private boolean canInteract() {
		if (recentlyMoved())
			return false;
		
		var d = getWidgetManager().getDialogue();
		return d == null || !d.isVisible();
	}
	
	private boolean canBlessInteract() {
		var d = getWidgetManager().getDialogue();
		return d == null || !d.isVisible();
	}
	
	private boolean canChiselInteract() {
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
		if (now - lastWalkAt < WALK_COOLDOWN_MS)
			return;
		
		lastWalkAt = now;
		
		int x = r.x + random(r.width);
		int y = r.y + random(r.height);
		
		getWalker().walkTo(new WorldPosition(x, y, 0),
		                   new WalkConfig.Builder().build());
	}
	
	private boolean validateRequirements(ItemGroupResult inv) {
		
		ItemSearchResult coins =
				getWidgetManager().getInventory().search(COIN_SET).getItem(995);
		
		if (coins == null || coins.getStackAmount() < 10) {
			log("BoneBlesser", "Stopping: Less than 10 coins.");
			stop();
			return false;
		}
		
		ItemSearchResult chisel = inv.getItem(CHISEL_ID);
		if (chisel == null || chisel.getSlot() != 26) {
			log("BoneBlesser", "Stopping: Chisel must be in slot 26.");
			stop();
			return false;
		}
		
		ItemGroupResult bones =
				getWidgetManager().getInventory().search(ALL_BONE_IDS);
		
		if (bones == null || bones.isEmpty()) {
			log("BoneBlesser", "Stopping: No bones found.");
			stop();
			return false;
		}
		
		return true;
	}
	
	@Override
	public int poll() {
		
		ItemGroupResult inv =
				getWidgetManager().getInventory().search(INV_IDS);
		if (inv == null) return random(120, 180);
		if (!validateRequirements(inv)) return -1;
		
		ItemSearchResult unblessed = inv.getItem(selectedBone.unblessedId);
		ItemSearchResult noted = inv.getItem(selectedBone.notedId);
		ItemSearchResult blessed = inv.getItem(selectedBone.blessedId);
		ItemSearchResult chisel = inv.getItem(CHISEL_ID);
		
		ItemSearchResult shardStack =
				getWidgetManager()
						.getInventory()
						.search(Collections.singleton(BONE_SHARDS_ID))
						.getItem(BONE_SHARDS_ID);
		
		if (unblessed == null && noted == null && blessed == null) {
			log("BoneBlesser", "Stopping: Out of bones.");
			stop();
			return -1;
		}
		
		if (shardStack != null) {
			int current = shardStack.getStackAmount();
			
			if (lastShardCount != -1 && current > lastShardCount) {
				totalShards += (current - lastShardCount);
			}
			
			lastShardCount = current;
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
		if (me == null) return random(120, 180);
		
		boolean needChisel = chisel != null && blessed != null && unblessed == null;
		boolean needBless = unblessed != null;
		boolean needUnnote = noted != null && unblessed == null && inv.getFreeSlots() > 0;
		
		long now = System.currentTimeMillis();
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
		
		if (needChisel) {
			
			if (!canChiselInteract())
				return random(40, 70);
			
			ItemSearchResult target = null;
			
			// Prefer slot 27 specifically (last slot)
			List<ItemSearchResult> allBlessed = inv.getAllOfItem(selectedBone.blessedId);
			if (allBlessed != null && !allBlessed.isEmpty()) {
				for (ItemSearchResult r : allBlessed) {
					if (r != null && r.getSlot() == 27) {
						target = r;
						break;
					}
				}
				// Fallback: any blessed bone
				if (target == null)
					target = allBlessed.get(0);
			}
			
			if (target == null)
				return random(40, 80);
			
			if (!chisel.interact())
				return random(60, 90);
			
			pollFramesHuman(() -> false, random(40, 70));
			
			target.interact();
			return random(80, 120);
		}
		
		
		if (needBless && now - lastBlessAt > BLESS_COOLDOWN_MS) {
			
			RSObject altar =
					getObjectManager().getClosestObject(me, "Exposed altar");
			
			if (altar != null && canBlessInteract()) {
				
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
			
			return random(150, 220);
		}
		
		if (needUnnote) {
			
			if ((dialogue != null && dialogue.isVisible()) ||
					now - lastUnnoteAt < UNNOTE_COOLDOWN_MS)
				return random(120, 180);
			
			for (WorldPosition npc :
					getWidgetManager().getMinimap().getNPCPositions().asList()) {
				
				Polygon cube = getSceneProjector().getTileCube(npc, 75);
				if (cube == null) continue;
				
				if (!getWidgetManager().insideGameScreen(cube, Collections.emptyList()))
					continue;
				
				noted.interact();
				pollFramesHuman(() -> false, random(40, 80));
				getFinger().tapGameScreen(cube);
				
				lastUnnoteAt = now;
				return random(600, 900);
			}
			
			if (!inRect(me, VIRILIS_RECT))
				walkToRect(VIRILIS_RECT);
			
			return random(300, 450);
		}
		
		return random(200, 300);
	}
	@Override
	public void onPaint(Canvas c) {
		
		long elapsed = System.currentTimeMillis() - scriptStartTime;
		if (elapsed <= 0)
			return;
		
		double hours = elapsed / 3_600_000D;
		int perHour = hours > 0 ? (int) (totalShards / hours) : 0;
		
		int x = 10;
		int y = 40;
		
		// Background
		c.fillRect(5, y - 25, 220, 70, 0x88000000, 0.9);
		c.drawRect(5, y - 25, 220, 70, 0xFFFFFFFF);
		
		// Text (TEXT, X, Y, COLOR, FONT)
		c.drawText("Bone Blesser", x, y, 0xFFFFFFFF, PAINT_FONT);
		y += 14;
		
		c.drawText(
				"Shards: " + totalShards,
				x,
				y,
				0xFF00FF00,
				PAINT_FONT
		          );
		y += 14;
		
		c.drawText(
				"Shards/hr: " + perHour,
				x,
				y,
				0xFF00FFFF,
				PAINT_FONT
		          );
	}

	public enum BoneType {
		NORMAL_BONES("Bones", 526, 527, 29344),
		BAT_BONES("Bat bones", 530, 531, 29346),
		BIG_BONES("Big bones", 532, 533, 29348),
		ZOGRE_BONES("Zogre bones", 4812, 4813, 29350),
		BABYDRAGON_BONES("Babydragon bones", 534, 535, 29352),
		WYRMLING_BONES("Wyrmling bones", 28899, 28900, 29354),
		DRAGON_BONES("Dragon bones", 536, 537, 29356),
		LAVA_DRAGON_BONES("Lava dragon bones", 11943, 11944, 29358),
		WYVERN_BONES("Wyvern bones", 6812, 6813, 29360),
		SUPERIOR_DRAGON_BONES("Superior dragon bones", 22124, 22125, 29362),
		WYRM_BONES("Wyrm bones", 22780, 22781, 29364),
		DRAKE_BONES("Drake bones", 22783, 22784, 29366),
		HYDRA_BONES("Hydra bones", 22786, 22787, 29368),
		FAYRG_BONES("Fayrg bones", 4830, 4831, 29370),
		RAURG_BONES("Raurg bones", 4832, 4833, 29372),
		OURG_BONES("Ourg bones", 4834, 4835, 29374),
		DAGANNOTH_BONES("Dagannoth bones", 6729, 6730, 29376),
		STRYKWYRM_BONES("Strykwyrm bones", 31726, 31727, 31264),
		FROST_DRAGON_BONES("Frost dragon bones", 31729, 31730, 31266);
		
		public final String name;
		public final int unblessedId;
		public final int notedId;
		public final int blessedId;
		
		BoneType(String n, int u, int no, int b) {
			name = n;
			unblessedId = u;
			notedId = no;
			blessedId = b;
		}
		
		@Override
		public String toString() {
			return name;
		}
		
		
	}
	
}


