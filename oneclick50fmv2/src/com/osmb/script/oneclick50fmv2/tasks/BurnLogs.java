package com.osmb.script.oneclick50fmv2.tasks;

import com.osmb.api.input.MenuEntry;
import com.osmb.api.input.MenuHook;
import com.osmb.api.item.ItemGroupResult;
import com.osmb.api.item.ItemID;
import com.osmb.api.item.ItemSearchResult;
import com.osmb.api.scene.RSObject;
import com.osmb.api.script.Script;
import com.osmb.api.shape.Polygon;
import com.osmb.api.ui.chatbox.dialogue.DialogueType;
import com.osmb.api.walker.WalkConfig;
import com.osmb.script.oneclick50fmv2.OneClick50FM;
import com.osmb.script.oneclick50fmv2.utils.Task;

import java.awt.*;
import java.util.*;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Pattern;

import static com.osmb.api.utils.RandomUtils.uniformRandom;

public class BurnLogs extends Task {


    private static final Pattern USE_LOG_PATTERN = Pattern.compile(
            "^use\\s+.+?\\s*(?:->|→)\\s*(fire|forester['\u2019]?s?\\s*campfire)$",
            Pattern.CASE_INSENSITIVE
    );

    public BurnLogs(Script script) {
        super(script);
    }

    @Override
    public boolean activate() {
        if (!OneClick50FM.setupComplete) return false;

        if (OneClick50FM.bonfirePosition == null) return false;

        var wm = script.getWidgetManager();
        if (wm == null) return false;

        Set<Integer> burnable = getBurnableLogIds();
        if (burnable.isEmpty()) return false;
        ItemGroupResult inv = wm.getInventory().search(burnable);

        return inv != null && inv.containsAny(burnable);
    }

    @Override
    public boolean execute() {
        OneClick50FM.currentTask = "Burning logs";

        var wm = script.getWidgetManager();
        if (wm == null) return false;

        if (isDialogueVisible()) {
            handleDialogue();
            return true;
        }

        if (!isBonfireAlive()) {
            script.log(getClass(), "Bonfire extinguished");
            OneClick50FM.bonfirePosition = null;
            return false;
        }

        Polygon tapPoly = getBonfireTapPolygon();
        if (tapPoly == null || !wm.insideGameScreen(tapPoly, Collections.emptyList())) {
            script.log(getClass(), "Walking to bonfire");
            walkToBonfire();
            return true;
        }

        return useLogsOnBonfire(tapPoly);
    }

    private Polygon getBonfireTapPolygon() {
        RSObject fireObj = getBonfireObject();
        if (fireObj != null) {
            Polygon hull = fireObj.getConvexHull();
            if (hull != null) return hull;
        }
        return getBonfireTile();
    }

    private RSObject getBonfireObject() {
        if (OneClick50FM.bonfirePosition == null) return null;
        var objMgr = script.getObjectManager();
        if (objMgr == null) return null;
        List<RSObject> fires = objMgr.getObjects(obj -> {
            if (obj == null || obj.getWorldPosition() == null) return false;
            if (!obj.getWorldPosition().equals(OneClick50FM.bonfirePosition)) return false;
            String name = obj.getName();
            return name != null &&
                    (name.equalsIgnoreCase("Fire") || name.equalsIgnoreCase("Forester's Campfire"));
        });
        return (fires == null || fires.isEmpty()) ? null : fires.get(0);
    }

    private boolean useLogsOnBonfire(Polygon bonfireTapPoly) {
        if (bonfireTapPoly == null) return false;
        var wm = script.getWidgetManager();
        if (wm == null) return false;

        Set<Integer> burnable = getBurnableLogIds();
        if (burnable.isEmpty()) return false;
        ItemGroupResult inv = wm.getInventory().search(burnable);

        if (inv == null) return false;

        ItemSearchResult log = null;

        if (inv.getSelectedSlot() != null) {
            log = getSelectedLog(inv, burnable);

            if (log == null) {
                var invWidget = wm.getInventory();
                if (invWidget != null) invWidget.unSelectItemIfSelected();
                return true;
            }
        }

        if (log == null) {
            log = getWorstBurnableLog(inv, burnable);
        }

        if (log == null) {
            script.log(getClass(), "No logs to burn");
            return false;
        }

        if (!log.interact()) {
            return true;
        }

        var screen = script.getScreen();
        if (screen == null) return false;
        var finger = script.getFinger();
        if (finger == null) return false;

        screen.queueCanvasDrawable("bonfire", canvas ->
                canvas.drawPolygon(bonfireTapPoly, Color.GREEN.getRGB(), 1)
        );

        // Only accept "Use [Logs/Oak logs/Willow logs] -> Fire" or "-> Forester's Campfire". Prefer Fire first (initial fire), then Forester's Campfire.
        String logName = getLogName(log.getId());
        String useOnFire = "Use " + logName + " -> Fire";
        String useOnForester = "Use " + logName + " -> Forester's Campfire";
        MenuHook useLogOnFireOnlyHook = entries -> {
            if (entries == null) return null;
            for (MenuEntry entry : entries) {
                String text = entry.getRawText();
                if (text == null) continue;
                String t = text.trim();
                if (t.equalsIgnoreCase(useOnFire)) return entry;
                if (t.equalsIgnoreCase(useOnForester)) return entry;
                if (USE_LOG_PATTERN.matcher(t).matches()) return entry;
            }
            return null;
        };

        boolean tapped = finger.tapGameScreen(bonfireTapPoly, useOnFire)
                || finger.tapGameScreen(bonfireTapPoly, useOnForester)
                || finger.tapGameScreen(bonfireTapPoly, useLogOnFireOnlyHook);

        if (!tapped) {
            screen.removeCanvasDrawable("bonfire");
            return false;
        }

        screen.removeCanvasDrawable("bonfire");
        script.log(getClass(), "Waiting for use-log-on-fire dialogue...");

        boolean dialogueAppeared = script.pollFramesUntil(
                this::isDialogueVisible,
                uniformRandom(5000, 8000)
        );
        if (!dialogueAppeared) {
            script.log(getClass(), "Use-log-on-fire: dialogue did not appear - will retry");
            return false;
        }
        return true;
    }

    private void handleDialogue() {
        script.log(getClass(), "Selecting logs in dialogue");

        var wm = script.getWidgetManager();
        if (wm == null) return;

        Set<Integer> burnable = getBurnableLogIds();
        if (burnable.isEmpty()) return;
        ItemGroupResult inv = wm.getInventory().search(burnable);

        if (inv == null) return;

        ItemSearchResult log = getWorstBurnableLog(inv, burnable);
        if (log == null) return;

        var dialogue = wm.getDialogue();
        if (dialogue == null) return;
        boolean selected = dialogue.selectItem(log.getId());

        if (!selected) {
            script.log(getClass(), "Failed to select log in dialogue");
            return;
        }

        waitUntilFinishedBurning(burnable);
    }

    private void waitUntilFinishedBurning(Set<Integer> allLogs) {
        script.log(getClass(), "Burning logs on bonfire...");

        AtomicInteger previousAmount = new AtomicInteger(-1);
        AtomicLong lastChangeTime = new AtomicLong(System.currentTimeMillis());

        script.pollFramesUntil(() -> {
            var w = script.getWidgetManager();
            if (w == null) return false;
            var dialogue = w.getDialogue();
            if (dialogue == null) return false;
            DialogueType dialogueType = dialogue.getDialogueType();
            if (dialogueType != null && dialogueType == DialogueType.TAP_HERE_TO_CONTINUE) {
                return false;
            }

            ItemGroupResult inv = w.getInventory().search(allLogs);
            if (inv == null) return false;

            int total = 0;
            for (int logId : allLogs) {
                total += inv.getAmount(logId);
            }

            if (total == 0) {
                script.log(getClass(), "All logs burned");
                OneClick50FM.bonfirePosition = null;
                return true;
            }

            int prev = previousAmount.get();

            if (prev == -1) {
                previousAmount.set(total);
                lastChangeTime.set(System.currentTimeMillis());
            } else if (total < prev) {
                int burned = prev - total;
                OneClick50FM.logsBurnt += burned;

                previousAmount.set(total);
                lastChangeTime.set(System.currentTimeMillis());
            }

            if (System.currentTimeMillis() - lastChangeTime.get() > uniformRandom(7000, 9000)) {
                script.log(getClass(), "Burn timeout - bonfire may have extinguished");
                OneClick50FM.bonfirePosition = null;
                return true;
            }

            return false;
        }, uniformRandom(155000, 165000), false, true);
    }

    private Polygon getBonfireTile() {
        if (OneClick50FM.bonfirePosition == null) return null;

        var proj = script.getSceneProjector();
        if (proj == null) return null;
        Polygon tilePoly = proj.getTilePoly(OneClick50FM.bonfirePosition, true);
        if (tilePoly == null) return null;

        Polygon resized = tilePoly.getResized(0.4);
        return resized != null ? resized : tilePoly;
    }

    private static final int BONFIRE_GRACE_PERIOD_MS = 8000;

    private boolean isBonfireAlive() {
        if (OneClick50FM.bonfirePosition == null) return false;

        if (OneClick50FM.bonfireSetAtMs > 0
                && System.currentTimeMillis() - OneClick50FM.bonfireSetAtMs < BONFIRE_GRACE_PERIOD_MS) {
            return true;
        }

        var objMgr = script.getObjectManager();
        if (objMgr == null) return false;

        List<RSObject> fires = objMgr.getObjects(obj -> {
            if (obj == null || obj.getWorldPosition() == null) return false;
            if (!obj.getWorldPosition().equals(OneClick50FM.bonfirePosition)) return false;

            String name = obj.getName();
            return name != null &&
                    (name.equalsIgnoreCase("Fire") || name.equalsIgnoreCase("Forester's Campfire"));
        });

        return fires != null && !fires.isEmpty();
    }


    private void walkToBonfire() {
        if (OneClick50FM.bonfirePosition == null) return;
        if (script.getWorldPosition() == null) return;

        var walker = script.getWalker();
        if (walker == null) return;

        WalkConfig config = new WalkConfig.Builder()
                .breakDistance(2)
                .tileRandomisationRadius(2)
                .breakCondition(() -> getBonfireTile() != null)
                .timeout(8000)
                .build();

        walker.walkTo(OneClick50FM.bonfirePosition, config);
    }


    private boolean isDialogueVisible() {
        var wm = script.getWidgetManager();
        if (wm == null) return false;
        var dialogue = wm.getDialogue();
        return dialogue != null && dialogue.getDialogueType() == DialogueType.ITEM_OPTION;
    }

    private ItemSearchResult getSelectedLog(ItemGroupResult inv, Set<Integer> allowedLogIds) {
        if (inv == null || allowedLogIds == null) return null;
        var items = inv.getRecognisedItems();
        if (items == null) return null;
        for (ItemSearchResult item : items) {
            if (item != null && item.isSelected()) {
                int id = item.getId();
                if (allowedLogIds.contains(id)) return item;
                return null; // selected something that's not burnable
            }
        }
        return null;
    }

    private static String getLogName(int itemId) {
        if (itemId == ItemID.WILLOW_LOGS) return "Willow logs";
        if (itemId == ItemID.OAK_LOGS) return "Oak logs";
        return "Logs";
    }

    private Set<Integer> getBurnableLogIds() {
        int fmLevel = OneClick50FM.cachedFmLevel;
        Set<Integer> out = new HashSet<>();
        if (fmLevel >= 1) out.add(ItemID.LOGS);
        if (fmLevel >= 15) out.add(ItemID.OAK_LOGS);
        if (fmLevel >= 30) out.add(ItemID.WILLOW_LOGS);
        return out;
    }

    private ItemSearchResult getWorstBurnableLog(ItemGroupResult inv, Set<Integer> allowedLogIds) {
        if (inv == null || allowedLogIds == null || allowedLogIds.isEmpty()) return null;
        if (allowedLogIds.contains(ItemID.LOGS) && inv.contains(ItemID.LOGS)) return inv.getItem(ItemID.LOGS);
        if (allowedLogIds.contains(ItemID.OAK_LOGS) && inv.contains(ItemID.OAK_LOGS))
            return inv.getItem(ItemID.OAK_LOGS);
        if (allowedLogIds.contains(ItemID.WILLOW_LOGS) && inv.contains(ItemID.WILLOW_LOGS))
            return inv.getItem(ItemID.WILLOW_LOGS);
        return null;
    }

    private ItemSearchResult getAnyLog(ItemGroupResult inv, Set<Integer> allowedLogIds) {
        if (inv == null || allowedLogIds == null || allowedLogIds.isEmpty()) return null;
        List<ItemSearchResult> logs = new ArrayList<>();

        if (allowedLogIds.contains(ItemID.WILLOW_LOGS) && inv.contains(ItemID.WILLOW_LOGS)) {
            logs.add(inv.getItem(ItemID.WILLOW_LOGS));
        }
        if (allowedLogIds.contains(ItemID.OAK_LOGS) && inv.contains(ItemID.OAK_LOGS)) {
            logs.add(inv.getItem(ItemID.OAK_LOGS));
        }
        if (allowedLogIds.contains(ItemID.LOGS) && inv.contains(ItemID.LOGS)) {
            logs.add(inv.getItem(ItemID.LOGS));
        }

        if (logs.isEmpty()) return null;

        return logs.get(uniformRandom(logs.size()));
    }
}
