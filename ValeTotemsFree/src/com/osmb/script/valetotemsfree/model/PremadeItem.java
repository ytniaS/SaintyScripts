package com.osmb.script.valetotemsfree.model;

public class PremadeItem {
    private final String name;
    private final int itemId;

    public PremadeItem(String name, int itemId) {
        this.name = name;
        this.itemId = itemId;
    }

    public String getName() {
        return name;
    }

    public int getItemId() {
        return itemId;
    }

    @Override
    public String toString() {
        return name;
    }
}
