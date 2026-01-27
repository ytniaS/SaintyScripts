package com.osmb.script.oneclick50fmv2.tasks;

import com.osmb.api.item.ItemGroupResult;
import com.osmb.api.item.ItemID;
import com.osmb.api.location.position.types.WorldPosition;
import com.osmb.api.script.Script;
import com.osmb.api.ui.component.tabs.skill.SkillType;
import com.osmb.script.oneclick50fmv2.OneClick50FM;
import com.osmb.script.oneclick50fmv2.utils.Task;

import java.util.Set;

public class Setup extends Task {

    public Setup(Script script) {
        super(script);
    }

    @Override
    public boolean activate() {
        return !OneClick50FM.setupComplete;
    }

    @Override
    public boolean execute() {
        script.log(getClass(), "SETUP START");

        var wm = script.getWidgetManager();
        if (wm == null) {
            script.log(getClass(), "ERROR: Widget manager unavailable");
            script.stop();
            return false;
        }

        var invWidget = wm.getInventory();
        if (invWidget == null) {
            script.log(getClass(), "ERROR: Inventory unavailable");
            script.stop();
            return false;
        }
        ItemGroupResult inv = invWidget.search(Set.of(ItemID.TINDERBOX));
        if (inv == null || !inv.contains(ItemID.TINDERBOX)) {
            script.log(getClass(), "ERROR: No tinderbox found in inventory");
            script.stop();
            return false;
        }

        var skillTab = wm.getSkillTab();
        if (skillTab == null) {
            script.log(getClass(), "ERROR: Skill tab unavailable");
            script.stop();
            return false;
        }
        var wcSkill = skillTab.getSkillLevel(SkillType.WOODCUTTING);
        var fmSkill = skillTab.getSkillLevel(SkillType.FIREMAKING);

        if (wcSkill == null || fmSkill == null) {
            script.log(getClass(), "ERROR: Could not read skill levels");
            script.stop();
            return false;
        }

        OneClick50FM.initialWcLevel = wcSkill.getLevel();
        OneClick50FM.initialFmLevel = fmSkill.getLevel();

        script.log(getClass(), "Woodcutting: " + OneClick50FM.initialWcLevel);
        script.log(getClass(), "Firemaking: " + OneClick50FM.initialFmLevel);

        if (OneClick50FM.initialFmLevel >= 50) {
            script.log(getClass(), "Already level 50+ Firemaking - stopping script");
            script.stop();
            return false;
        }

        WorldPosition pos = script.getWorldPosition();
        if (pos == null) {
            script.log(getClass(), "ERROR: World position is null");
            script.stop();
            return false;
        }
        int region = pos.getRegionID();
        if (region != 9776) {
            script.log(getClass(), "ERROR: Not in Castle Wars region (9776), current: " + region);
            script.stop();
            return false;
        }

        script.log(getClass(), "All checks passed - starting training");
        OneClick50FM.cachedWcLevel = OneClick50FM.initialWcLevel;
        OneClick50FM.cachedFmLevel = OneClick50FM.initialFmLevel;
        OneClick50FM.setupComplete = true;

        return true;
    }
}
