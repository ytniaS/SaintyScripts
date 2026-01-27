package com.osmb.script.oneclick50fmv2.data;

import com.osmb.api.item.ItemID;
import com.osmb.api.visual.SearchablePixel;


public enum Tree {
    NORMAL("Tree", ItemID.LOGS, PixelProvider.TREE_CLUSTER_NORMAL_OAK, 1, 1),
    OAK("Oak tree", ItemID.OAK_LOGS, PixelProvider.TREE_CLUSTER_NORMAL_OAK, 15, 15),
    WILLOW("Willow tree", ItemID.WILLOW_LOGS, PixelProvider.TREE_CLUSTER_WILLOW, 30, 30);

    private final String objectName;
    private final int logID;
    private final SearchablePixel[] cluster;
    private final int wcRequirement;
    private final int fmRequirement;

    Tree(String objectName, int logID, SearchablePixel[] cluster, int wcRequirement, int fmRequirement) {
        this.objectName = objectName;
        this.logID = logID;
        this.cluster = cluster;
        this.wcRequirement = wcRequirement;
        this.fmRequirement = fmRequirement;
    }

    public static Tree getBestTreeForLevels(int wcLevel, int fmLevel) {
        int limitingLevel = Math.min(wcLevel, fmLevel);

        Tree[] trees = values();
        for (int i = trees.length - 1; i >= 0; i--) {
            Tree tree = trees[i];
            if (limitingLevel >= tree.wcRequirement && limitingLevel >= tree.fmRequirement) {
                return tree;
            }
        }

        return null;
    }

    public Tree getFallback() {
        return switch (this) {
            case WILLOW -> OAK;
            case OAK -> NORMAL;
            case NORMAL -> null;
        };
    }

    public String getObjectName() {
        return objectName;
    }

    public int getLogID() {
        return logID;
    }

    public SearchablePixel[] getCluster() {
        return cluster;
    }

    public int getWcRequirement() {
        return wcRequirement;
    }

    public int getFmRequirement() {
        return fmRequirement;
    }
}
