package com.osmb.script.boneblesser;

import com.osmb.api.item.*;
import com.osmb.api.location.position.types.WorldPosition;
import com.osmb.api.scene.RSObject;
import com.osmb.api.script.*;
import com.osmb.api.shape.*;
import com.osmb.api.walker.WalkConfig;
import javafx.geometry.Insets;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.layout.VBox;

import java.util.*;

@ScriptDefinition(
		name = "Bone Blesser",
		author = "Sainty",
		version = 1.3,
		description = "Unnotes, blesses and chisels bones.",
		skillCategory = SkillCategory.PRAYER
)
public class boneBlesser extends Script {
	
	private static final int REGION_TEOMAT = 5681;
	private static final int CHISEL_ID = 1755;
	
	private static final Rectangle ALTAR_AREA =
			new Rectangle(1433, 3147, 5, 5);
	
	private static final long WALK_COOLDOWN_MS = 1200;
	private static final long STABLE_MS = 500;
	private static final long CHISEL_COOLDOWN_MS = 800;
	
	private BoneType selectedBone = BoneType.NORMAL_BONES;
	private static final Set<Integer> INV_IDS = new HashSet<>();
	
	private WorldPosition lastPos = null;
	private long lastPosChange = 0;
	private long lastWalkAt = 0;
	private long lastChiselAt = 0;
	
	public boneBlesser(Object core) {
		super(core);
	}
	
	@Override
	public void onStart() {
		
		ComboBox<BoneType> box = new ComboBox<>();
		box.getItems().addAll(BoneType.values());
		box.setValue(BoneType.NORMAL_BONES);
		
		Button confirm = new Button("Confirm");
		confirm.setOnAction(e -> {
			selectedBone = box.getValue();
			confirm.getScene().getWindow().hide();
			
			INV_IDS.clear();
			INV_IDS.add(selectedBone.unblessedId);
			INV_IDS.add(selectedBone.notedId);
			INV_IDS.add(selectedBone.blessedId);
			INV_IDS.add(CHISEL_ID);
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
		return !recentlyMoved();
	}
	
	private void tryWalkToArea(Rectangle area) {
		long now = System.currentTimeMillis();
		if (now - lastWalkAt < WALK_COOLDOWN_MS)
			return;
		
		lastWalkAt = now;
		
		int x = area.x + random(area.width);
		int y = area.y + random(area.height);
		
		WalkConfig cfg = new WalkConfig.Builder()
				.tileRandomisationRadius(2)
				.breakDistance(2)
				.build();
		
		getWalker().walkTo(new WorldPosition(x, y, 0), cfg);
	}
	
	private boolean inArea(WorldPosition p, Rectangle r) {
		if (p == null || r == null)
			return false;
		
		return p.getX() >= r.x &&
				p.getX() <  r.x + r.width &&
				p.getY() >= r.y &&
				p.getY() <  r.y + r.height;
	}
	
	@Override
	public int poll() {
		
		var dialogue = getWidgetManager().getDialogue();
		if (dialogue != null && dialogue.isVisible()) {
			var b = dialogue.getBounds();
			if (b != null) {
				getFinger().tap(
						b.x + b.width / 2,
						b.y + (int) (b.height * 0.58)
				               );
				return random(400, 600);
			}
		}
		
		ItemGroupResult inv = getWidgetManager().getInventory().search(INV_IDS);
		if (inv == null)
			return random(120, 180);
		
		ItemSearchResult unblessed = inv.getItem(selectedBone.unblessedId);
		ItemSearchResult noted = inv.getItem(selectedBone.notedId);
		ItemSearchResult blessed = inv.getItem(selectedBone.blessedId);
		ItemSearchResult chisel = inv.getItem(CHISEL_ID);
		
		WorldPosition me = getWorldPosition();
		if (me == null)
			return random(120, 180);
		
		boolean needChisel = chisel != null && blessed != null && unblessed == null;
		boolean needBless = unblessed != null;
		boolean needUnnote = noted != null && blessed == null && inv.getFreeSlots() > 0;
		
		if (needChisel) {
			if (!canInteract())
				return random(120, 180);
			
			if (System.currentTimeMillis() - lastChiselAt < CHISEL_COOLDOWN_MS)
				return random(120, 180);
			
			chisel.interact();
			pollFramesHuman(() -> false, random(120, 200));
			blessed.interact();
			lastChiselAt = System.currentTimeMillis();
			return random(500, 800);
		}
		
		if (needBless) {
			
			if (!inArea(me, ALTAR_AREA)) {
				tryWalkToArea(ALTAR_AREA);
				return random(200, 300);
			}
			
			RSObject altar = getObjectManager().getClosestObject(me, "Exposed altar");
			if (altar == null || !canInteract())
				return random(200, 300);
			
			Polygon poly = altar.getConvexHull();
			if (poly == null)
				return random(120, 180);
			
			poly = poly.getResized(0.6);
			if (!getWidgetManager().insideGameScreen(poly, Collections.emptyList()))
				return random(120, 180);
			
			getFinger().tapGameScreen(poly, "Bless");
			return random(500, 800);
		}
		
		if (needUnnote) {
			
			if (!canInteract())
				return random(120, 180);
			
			noted.interact();
			pollFramesHuman(() -> false, random(120, 200));
			
			for (WorldPosition npc : getWidgetManager().getMinimap().getNPCPositions().asList()) {
				Polygon cube = getSceneProjector().getTileCube(npc, 75);
				if (cube != null &&
						getWidgetManager().insideGameScreen(cube, Collections.emptyList())) {
					getFinger().tapGameScreen(cube);
					return random(600, 900);
				}
			}
		}
		
		return random(200, 300);
	}
	/* enum from roe script */
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
		
		BoneType(String name, int unblessedId, int notedId, int blessedId) {
			this.name = name;
			this.unblessedId = unblessedId;
			this.notedId = notedId;
			this.blessedId = blessedId;
		}
		
		@Override
		public String toString() {
			return name;
		}
	}
}