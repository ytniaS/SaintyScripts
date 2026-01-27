package com.osmb.script.oneclick50fmv2.tasks;

import com.osmb.api.item.ItemGroupResult;
import com.osmb.api.location.area.Area;
import com.osmb.api.location.position.types.WorldPosition;
import com.osmb.api.scene.RSObject;
import com.osmb.api.script.Script;
import com.osmb.api.shape.Polygon;
import com.osmb.api.ui.component.chatbox.ChatboxComponent;
import com.osmb.api.walker.WalkConfig;
import com.osmb.script.oneclick50fmv2.utils.Task;
import com.osmb.script.oneclick50fmv2.OneClick50FM;
import com.osmb.script.oneclick50fmv2.data.Areas;
import com.osmb.script.oneclick50fmv2.data.Tree;

import java.util.*;
import java.util.stream.Collectors;

import static com.osmb.api.utils.RandomUtils.gaussianRandom;
import static com.osmb.api.utils.RandomUtils.uniformRandom;

public class ChopTrees extends Task {

    private static final long TREE_BLACKLIST_TIMEOUT_MS = 10000;
    private static final long LEVEL_CHECK_INTERVAL_MS = 5 * 60 * 1000;
    private static final int RESPAWN_WAIT_MIN_MS = 6000;
    private static final int RESPAWN_WAIT_MAX_MS = 10000;
    private static final int WALK_STABLE_MIN_MS = 1000;
    private static final int WALK_STABLE_MAX_MS = 3000;
    private static final int REACH_TREE_TIMEOUT_MIN_MS = 5000;
    private static final int REACH_TREE_TIMEOUT_MAX_MS = 12000;
    private static final int CHOP_TIMEOUT_MIN_MS = 55000;
    private static final int CHOP_TIMEOUT_MAX_MS = 95000;
    private static final int POST_CHOP_PAUSE_MIN_MS = 800;
    private static final int POST_CHOP_PAUSE_MAX_MS = 2000;
    private static final int POST_CHOP_PAUSE_MEAN_MS = 1400;
    private static final int POST_CHOP_PAUSE_STD_MS = 300;
    private static final int POST_CHOP_PAUSE_CHANCE_DENOM = 4;
    private static final Map<WorldPosition, Long> treeBlacklist = new HashMap<>();

    private long lastLevelCheckMs = 0;

    public ChopTrees(Script script) {
        super(script);
    }

    @Override
    public boolean activate() {
        if (!OneClick50FM.setupComplete) return false;

        var wm = script.getWidgetManager();
        if (wm == null) return false;

        ItemGroupResult inv = wm.getInventory().search(Collections.emptySet());
        if (inv == null) return false;

        return !inv.isFull();
    }

    @Override
    public boolean execute() {
        OneClick50FM.currentTask = "Chopping trees";

        long now = System.currentTimeMillis();
        boolean needTreePick = (OneClick50FM.selectedTree == null);
        boolean intervalElapsed = (now - lastLevelCheckMs) >= LEVEL_CHECK_INTERVAL_MS;
        if (needTreePick || intervalElapsed) {
            updateTreeSelection();
            lastLevelCheckMs = now;
        }

        if (OneClick50FM.selectedTree == null) {
            script.log(getClass(), "ERROR: No valid tree for current levels");
            script.stop();
            return false;
        }

        cleanBlacklist();

        WorldPosition playerPos = script.getWorldPosition();
        if (playerPos == null) {
            return false;
        }

        Area treeArea = getTreeArea(OneClick50FM.selectedTree);

        if (!treeArea.contains(playerPos)) {
            script.log(getClass(), "Walking to " + OneClick50FM.selectedTree.getObjectName() + " area");
            walkToTreeArea(treeArea);
            return true;
        }

        List<RSObject> trees = findTrees(OneClick50FM.selectedTree);

        if (trees.isEmpty()) {
            script.log(getClass(), "No " + OneClick50FM.selectedTree.getObjectName() + " trees found");
            return handleMissingTrees();
        }

        List<RSObject> visibleTrees = getVisibleTrees(trees, playerPos, treeArea);
        List<RSObject> activeTrees = getActiveTrees(visibleTrees);

        if (activeTrees.isEmpty()) {
            trees.removeAll(visibleTrees);
            if (!trees.isEmpty()) {
                trees.sort(Comparator.comparingDouble(t -> t.distance(playerPos)));
                RSObject nearest = trees.get(0);
                script.log(getClass(), "Walking to off-screen tree");

                if (script.getWorldPosition() == null) return false;
                var walker = script.getWalker();
                if (walker == null) return false;

                WalkConfig config = new WalkConfig.Builder()
                        .breakCondition(() -> {
                            WorldPosition pos = script.getWorldPosition();
                            return pos != null && nearest.getTileDistance(pos) <= 1;
                        })
                        .tileRandomisationRadius(2)
                        .timeout(8000)
                        .build();

                walker.walkTo(nearest, config);
                return true;
            }

            Tree fallback = OneClick50FM.selectedTree.getFallback();
            if (fallback != null) {
                return handleMissingTrees();
            }
            script.log(getClass(), "All trees are stumps - waiting for respawn...");
            script.pollFramesHuman(() -> true, uniformRandom(RESPAWN_WAIT_MIN_MS, RESPAWN_WAIT_MAX_MS));
            return true;
        }

        RSObject target = activeTrees.get(0);
        return chopTree(target);
    }

    private void updateTreeSelection() {
        int wcLevel = Math.max(OneClick50FM.initialWcLevel, OneClick50FM.cachedWcLevel);
        int fmLevel = Math.max(OneClick50FM.initialFmLevel, OneClick50FM.cachedFmLevel);

        Tree optimal = Tree.getBestTreeForLevels(wcLevel, fmLevel);

        if (optimal != null && optimal != OneClick50FM.selectedTree) {
            if (OneClick50FM.selectedTree != null) {
                script.log(getClass(), "Level up! Switching: " + OneClick50FM.selectedTree.getObjectName() +
                        " -> " + optimal.getObjectName());
            } else {
                script.log(getClass(), "Tree selected: " + optimal.getObjectName() + " (WC " + wcLevel + ", FM " + fmLevel + ")");
            }
            OneClick50FM.selectedTree = optimal;
        }
    }


    private boolean handleMissingTrees() {
        Tree fallback = OneClick50FM.selectedTree.getFallback();

        if (fallback == null) {
            script.log(getClass(), "ERROR: No trees available at all - stopping");
            script.stop();
            return false;
        }

        script.log(getClass(), OneClick50FM.selectedTree.getObjectName() + " trees unavailable - falling back to " +
                fallback.getObjectName());

        OneClick50FM.selectedTree = fallback;
        return true;
    }


    private Area getTreeArea(Tree tree) {
        return switch (tree) {
            case WILLOW -> Areas.WILLOW_TREES;
            case OAK -> Areas.OAK_TREES;
            case NORMAL -> Areas.NORMAL_TREES;
        };
    }


    private List<RSObject> findTrees(Tree tree) {
        if (tree == null) return Collections.emptyList();
        Area treeArea = getTreeArea(tree);
        if (treeArea == null) return Collections.emptyList();
        var objMgr = script.getObjectManager();
        if (objMgr == null) return Collections.emptyList();

        List<RSObject> list = objMgr.getObjects(obj -> {
            String name = obj.getName();
            if (name == null) return false;

            if (!name.equalsIgnoreCase(tree.getObjectName())) return false;

            WorldPosition pos = obj.getWorldPosition();
            return pos != null && treeArea.contains(pos);
        });
        return list != null ? list : Collections.emptyList();
    }

    private List<RSObject> getVisibleTrees(List<RSObject> trees, WorldPosition playerPos, Area treeArea) {
        if (trees == null || playerPos == null || treeArea == null) return Collections.emptyList();
        var wm = script.getWidgetManager();
        if (wm == null) return Collections.emptyList();

        return trees.stream()
                .filter(tree -> {
                    WorldPosition pos = tree.getWorldPosition();
                    if (pos == null) return false;

                    if (treeBlacklist.containsKey(pos)) return false;

                    if (!treeArea.contains(pos)) return false;

                    if (!tree.canReach() || tree.getTileDistance(playerPos) > 15) return false;

                    Polygon hull = tree.getConvexHull();
                    if (hull == null) return false;

                    hull = hull.getResized(0.5);
                    if (hull == null) return false;

                    double visibility = wm.insideGameScreenFactor(
                            hull,
                            List.of(ChatboxComponent.class)
                    );

                    return visibility >= 0.5;
                })
                .sorted(Comparator.comparingDouble(t -> t.distance(playerPos)))
                .collect(Collectors.toList());
    }

    private List<RSObject> getActiveTrees(List<RSObject> trees) {
        if (trees == null || OneClick50FM.selectedTree == null) return Collections.emptyList();

        var pixelAnalyzer = script.getPixelAnalyzer();
        if (pixelAnalyzer == null) return Collections.emptyList();

        List<RSObject> active = new ArrayList<>();

        for (RSObject tree : trees) {
            Polygon hull = tree.getConvexHull();
            if (hull == null) continue;

            hull = hull.getResized(0.5);
            if (hull == null) continue;

            int pixelCount = pixelAnalyzer.findPixels(
                    hull,
                    OneClick50FM.selectedTree.getCluster()
            ).size();

            if (pixelCount >= 20) {
                active.add(tree);
            }
        }

        return active;
    }

    private boolean chopTree(RSObject tree) {
        if (tree == null || OneClick50FM.selectedTree == null) return false;

        Polygon hull = tree.getConvexHull();
        if (hull == null) return false;

        var finger = script.getFinger();
        if (finger == null) return false;

        String action = "Chop down";
        if (!finger.tapGameScreen(hull, action + " " + OneClick50FM.selectedTree.getObjectName())) {
            WorldPosition treePos = tree.getWorldPosition();
            if (treePos != null) treeBlacklist.put(treePos, System.currentTimeMillis());
            return false;
        }

        return waitUntilFinishedChopping(tree);
    }

    private boolean waitUntilFinishedChopping(RSObject tree) {
        if (tree == null) return false;

        WorldPosition playerPos = script.getWorldPosition();
        if (playerPos == null) return false;

        if (tree.getTileDistance(playerPos) > 1) {
            script.log(getClass(), "Walking to tree...");

            script.pollFramesUntil(() ->
                            script.getLastPositionChangeMillis() < 600,
                    uniformRandom(WALK_STABLE_MIN_MS, WALK_STABLE_MAX_MS)
            );

            script.pollFramesUntil(() -> {
                WorldPosition pos = script.getWorldPosition();
                if (pos == null) return false;
                return script.getLastPositionChangeMillis() > 800 && tree.getTileDistance(pos) <= 1;
            }, uniformRandom(REACH_TREE_TIMEOUT_MIN_MS, REACH_TREE_TIMEOUT_MAX_MS));
        }

        playerPos = script.getWorldPosition();
        if (playerPos == null || tree.getTileDistance(playerPos) > 1) {
            script.log(getClass(), "Did not reach tree");
            return false;
        }

        script.log(getClass(), "Chopping...");

        script.pollFramesUntil(() -> {
            var pa = script.getPixelAnalyzer();
            if (pa == null) return true;

            Polygon hull = tree.getConvexHull();
            if (hull == null) return true;

            hull = hull.getResized(0.5);
            if (hull == null) return true;

            int pixelCount = pa.findPixels(
                    hull,
                    OneClick50FM.selectedTree.getCluster()
            ).size();

            if (pixelCount < 20) {
                script.log(getClass(), "Tree cut down");
                return true;
            }

            var wm = script.getWidgetManager();
            if (wm != null) {
                ItemGroupResult inv = wm.getInventory().search(Collections.emptySet());
                if (inv != null && inv.isFull()) {
                    script.log(getClass(), "Inventory full");
                    return true;
                }
            }

            return false;
        }, uniformRandom(CHOP_TIMEOUT_MIN_MS, CHOP_TIMEOUT_MAX_MS));

        if (uniformRandom(0, POST_CHOP_PAUSE_CHANCE_DENOM - 1) == 0) {
            script.pollFramesHuman(() -> true,
                    gaussianRandom(POST_CHOP_PAUSE_MIN_MS, POST_CHOP_PAUSE_MAX_MS, POST_CHOP_PAUSE_MEAN_MS, POST_CHOP_PAUSE_STD_MS));
        }

        return true;
    }


    private void walkToTreeArea(Area treeArea) {
        if (treeArea == null) return;
        if (script.getWorldPosition() == null) return;
        WorldPosition target = treeArea.getRandomPosition();
        if (target == null) return;

        var walker = script.getWalker();
        if (walker == null) return;

        WalkConfig config = new WalkConfig.Builder()
                .tileRandomisationRadius(2)
                .breakCondition(() -> {
                    WorldPosition pos = script.getWorldPosition();
                    return pos != null && treeArea.contains(pos);
                })
                .timeout(10000)
                .build();

        walker.walkTo(target, config);
    }


    private void cleanBlacklist() {
        long now = System.currentTimeMillis();
        treeBlacklist.entrySet().removeIf(entry ->
                now - entry.getValue() > TREE_BLACKLIST_TIMEOUT_MS
        );
    }
}
