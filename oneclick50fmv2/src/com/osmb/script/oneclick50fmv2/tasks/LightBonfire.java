package com.osmb.script.oneclick50fmv2.tasks;

import com.osmb.api.item.ItemGroupResult;
import com.osmb.api.item.ItemID;
import com.osmb.api.item.ItemSearchResult;
import com.osmb.api.location.position.types.LocalPosition;
import com.osmb.api.location.position.types.WorldPosition;
import com.osmb.api.scene.RSObject;
import com.osmb.api.script.Script;
import com.osmb.api.walker.WalkConfig;
import com.osmb.script.oneclick50fmv2.OneClick50FM;
import com.osmb.script.oneclick50fmv2.data.Areas;
import com.osmb.script.oneclick50fmv2.utils.Task;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;

import static com.osmb.api.utils.RandomUtils.gaussianRandom;
import static com.osmb.api.utils.RandomUtils.uniformRandom;


public class LightBonfire extends Task {


    private static final int FREE_SLOTS_MAX_TO_LIGHT = 2;

    public LightBonfire(Script script) {
        super(script);
    }

    @Override
    public boolean activate() {
        if (!OneClick50FM.setupComplete) return false;
        if (OneClick50FM.bonfirePosition != null) return false;

        var wm = script.getWidgetManager();
        if (wm == null) return false;

        ItemGroupResult fullInv = wm.getInventory().search(Collections.emptySet());
        if (fullInv == null) return false;
        if (fullInv.getFreeSlots() > FREE_SLOTS_MAX_TO_LIGHT && !fullInv.isFull()) return false;

        Set<Integer> ids = Set.of(ItemID.TINDERBOX, ItemID.LOGS, ItemID.OAK_LOGS, ItemID.WILLOW_LOGS);
        ItemGroupResult inv = wm.getInventory().search(ids);
        if (inv == null || !inv.contains(ItemID.TINDERBOX)) return false;

        return inv.contains(ItemID.LOGS) || inv.contains(ItemID.OAK_LOGS) || inv.contains(ItemID.WILLOW_LOGS);
    }

    @Override
    public boolean execute() {
        OneClick50FM.currentTask = "Lighting bonfire";

        WorldPosition playerPos = script.getWorldPosition();
        if (playerPos == null) return false;

        var wm = script.getWidgetManager();
        if (wm == null) return false;

        if (!Areas.CASTLE_WARS_MAIN.contains(playerPos)) {
            script.log(getClass(), "Walking to Castle Wars area");
            walkToCastleWars();
            return true;
        }

        if (OneClick50FM.forceNewLightPosition) {
            script.log(getClass(), "Moving to new position after failed light");
            walkToNewLightPosition();
            OneClick50FM.forceNewLightPosition = false;
            return true;
        }

        Set<Integer> ids = Set.of(ItemID.TINDERBOX, ItemID.LOGS, ItemID.OAK_LOGS, ItemID.WILLOW_LOGS);
        ItemGroupResult inv = wm.getInventory().search(ids);

        if (inv == null) return false;

        ItemSearchResult tinderbox = inv.getItem(ItemID.TINDERBOX);
        if (tinderbox == null) {
            script.log(getClass(), "ERROR: No tinderbox");
            script.stop();
            return false;
        }

        ItemSearchResult log = getAnyLog(inv);
        if (log == null) {
            script.log(getClass(), "No logs to burn");
            return false;
        }

        return lightFire(tinderbox, log);
    }


    private boolean lightFire(ItemSearchResult tinderbox, ItemSearchResult log) {
        script.log(getClass(), "Lighting initial fire...");
        OneClick50FM.fireLitFromChat = false;

        WorldPosition lightPosition = script.getWorldPosition();
        if (lightPosition == null) return false;

        if (!tinderbox.interact()) return false;
        if (!log.interact()) return false;

        boolean lit = script.pollFramesUntil(() -> {
            if (script instanceof OneClick50FM) ((OneClick50FM) script).pumpChatbox();
            if (OneClick50FM.fireLitFromChat) return true;
            return fireObjectAtTile(lightPosition);
        }, uniformRandom(14000, 16000));
        OneClick50FM.fireLitFromChat = false;

        if (!lit) {
            script.log(getClass(), "Failed to light fire - will move to new tile and retry (no chop)");
            OneClick50FM.forceNewLightPosition = true;
            walkToNewLightPosition();
            return true;
        }

        script.log(getClass(), "Fire lit successfully - staying to burn (no chop)");
        OneClick50FM.bonfirePosition = lightPosition;
        OneClick50FM.bonfireSetAtMs = System.currentTimeMillis();
        OneClick50FM.logsBurnt++;
        OneClick50FM.forceNewLightPosition = false;

        script.pollFramesHuman(() -> true, gaussianRandom(600, 1200, 800, 200));
        return true;
    }


    private boolean fireObjectAtTile(WorldPosition tile) {
        if (tile == null) return false;
        var objMgr = script.getObjectManager();
        if (objMgr == null) return false;
        List<RSObject> at = objMgr.getObjects(obj -> {
            if (obj == null || obj.getWorldPosition() == null) return false;
            if (!obj.getWorldPosition().equals(tile)) return false;
            String name = obj.getName();
            return name != null && (name.equalsIgnoreCase("Fire") || name.equalsIgnoreCase("Forester's Campfire"));
        });
        return at != null && !at.isEmpty();
    }


    private ItemSearchResult getAnyLog(ItemGroupResult inv) {
        if (inv == null) return null;
        List<ItemSearchResult> logs = new ArrayList<>();

        if (inv.contains(ItemID.WILLOW_LOGS)) {
            logs.add(inv.getItem(ItemID.WILLOW_LOGS));
        }
        if (inv.contains(ItemID.OAK_LOGS)) {
            logs.add(inv.getItem(ItemID.OAK_LOGS));
        }
        if (inv.contains(ItemID.LOGS)) {
            logs.add(inv.getItem(ItemID.LOGS));
        }

        if (logs.isEmpty()) return null;

        return logs.get(uniformRandom(logs.size()));
    }

    private void walkToCastleWars() {
        if (script.getWorldPosition() == null) return;
        WorldPosition target = Areas.CASTLE_WARS_MAIN.getRandomPosition();
        if (target == null) return;

        var walker = script.getWalker();
        if (walker == null) return;

        WalkConfig config = new WalkConfig.Builder()
                .breakDistance(1)
                .tileRandomisationRadius(1)
                .breakCondition(() -> {
                    WorldPosition pos = script.getWorldPosition();
                    return pos != null && Areas.CASTLE_WARS_MAIN.contains(pos);
                })
                .timeout(8000)
                .build();

        walker.walkTo(target, config);
    }

    private void walkToNewLightPosition() {
        LocalPosition localPos = script.getLocalPosition();
        if (localPos == null) {
            script.log(getClass(), "Local position null, cannot find reachable tiles");
            return;
        }

        var walker = script.getWalker();
        if (walker == null) return;

        var collisionMgr = walker.getCollisionManager();
        if (collisionMgr == null) return;

        List<LocalPosition> reachable = collisionMgr.findReachableTiles(localPos, 6);
        if (reachable == null || reachable.isEmpty()) {
            script.log(getClass(), "No reachable tiles found");
            return;
        }

        LocalPosition target = reachable.get(uniformRandom(reachable.size()));
        WalkConfig config = new WalkConfig.Builder()
                .breakDistance(1)
                .tileRandomisationRadius(1)
                .build();
        walker.walkTo(target, config);
    }
}
