package com.osmb.script.valetotemsfree.model;

public enum LogType {
    OAK(1521),
    WILLOW(1519),
    MAPLE(1517),
    YEW(1515),
    MAGIC(1513),
    REDWOOD(19669);

    private final int itemId;

    LogType(int itemId) {
        this.itemId = itemId;
    }

    public int getItemId() {
        return itemId;
    }
}
