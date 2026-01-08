package com.osmb.script.crafting.chartercrafting.handles;

import com.osmb.api.ScriptCore;
import com.osmb.api.item.ItemID;
import com.osmb.api.location.area.Area;
import com.osmb.api.location.position.types.WorldPosition;
import com.osmb.api.scene.RSObject;
import com.osmb.api.ui.chatbox.dialogue.Dialogue;
import com.osmb.api.ui.chatbox.dialogue.DialogueType;
import com.osmb.api.utils.RandomUtils;
import com.osmb.api.walker.WalkConfig;
import com.osmb.script.crafting.chartercrafting.Method;

import java.util.Comparator;
import java.util.List;

import static com.osmb.script.crafting.chartercrafting.Config.selectedDock;
import static com.osmb.script.crafting.chartercrafting.Config.selectedMethod;
import static com.osmb.script.crafting.chartercrafting.State.smelt;
import static com.osmb.script.crafting.chartercrafting.utils.Utilities.waitUntilFinishedProducing;

public class FurnaceHandler {
    
    private final ScriptCore core;
    private final Dialogue dialogue;
    
    public FurnaceHandler(ScriptCore core) {
        this.core = core;
        this.dialogue = core.getWidgetManager().getDialogue();
    }
    
    public void poll() {
        smelt = true;
        
        Area furnaceArea = selectedDock.getFurnaceArea();
        if (furnaceArea == null) {
            throw new RuntimeException("No furnace area for selected dock.");
        }
        
        WorldPosition me = core.getWorldPosition();
        if (me == null) {
            return;
        }
        
        RSObject furnace = getClosestFurnace(me);
        if (furnace == null) {
            walkToFurnace();
            return;
        }
        
        if (furnaceArea.contains(me)) {
            DialogueType dialogueType = dialogue.getDialogueType();
            
            if (dialogueType == DialogueType.ITEM_OPTION) {
                boolean selectedOption = dialogue.selectItem(ItemID.MOLTEN_GLASS);
                if (!selectedOption) {
                    core.log(FurnaceHandler.class,
                             "No option selected, can't find item in dialogue...");
                    return;
                }
                
                waitUntilFinishedProducing(
                    core,
                    Integer.MAX_VALUE,
                    selectedMethod == Method.SUPER_GLASS_MAKE
                    ? ItemID.SEAWEED
                    : ItemID.SODA_ASH,
                    ItemID.BUCKET_OF_SAND
                                          );
                return;
            }
        }
        
        if (furnace.interact("Smelt")) {
            core.pollFramesHuman(
                () -> dialogue.getDialogueType() == DialogueType.ITEM_OPTION,
                RandomUtils.uniformRandom(2000, 7000)
                                );
        }
    }
    
    private RSObject getClosestFurnace(WorldPosition me) {
        List<RSObject> furnaces = core.getObjectManager()
            .getObjects(o -> "Furnace".equalsIgnoreCase(o.getName()));
        
        if (furnaces.isEmpty()) {
            return null;
        }
        
        return furnaces.stream()
            .min(Comparator.comparingInt(o -> o.getTileDistance(me)))
            .orElse(null);
    }
    
    private void walkToFurnace() {
        core.log(FurnaceHandler.class, "Walking to furnace");
        
        WalkConfig.Builder walkConfig = new WalkConfig.Builder();
        walkConfig.breakCondition(() -> {
            WorldPosition pos = core.getWorldPosition();
            return pos != null && selectedDock.getFurnaceArea().contains(pos);
        });
        
        core.getWalker().walkTo(
            selectedDock.getFurnaceArea().getRandomPosition(),
            walkConfig.build()
                               );
    }
}
