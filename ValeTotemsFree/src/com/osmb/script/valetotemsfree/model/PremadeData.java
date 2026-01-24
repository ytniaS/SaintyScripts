package com.osmb.script.valetotemsfree.model;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class PremadeData {
    public static final Map<LogType, List<PremadeItem>> DATA = new HashMap<>();

    static {
        // Oak
        List<PremadeItem> oak = new ArrayList<>();
        oak.add(new PremadeItem("Oak shortbow (u)", 54));
        oak.add(new PremadeItem("Oak shortbow", 841));
        oak.add(new PremadeItem("Oak longbow (u)", 56));
        oak.add(new PremadeItem("Oak longbow", 839));
        oak.add(new PremadeItem("Oak shield", 25611));
        oak.add(new PremadeItem("Oak stock", 9440));
        DATA.put(LogType.OAK, oak);

        // Willow
        List<PremadeItem> willow = new ArrayList<>();
        willow.add(new PremadeItem("Willow shortbow (u)", 60));
        willow.add(new PremadeItem("Willow shortbow", 843));
        willow.add(new PremadeItem("Willow longbow (u)", 58));
        willow.add(new PremadeItem("Willow longbow", 849));
        willow.add(new PremadeItem("Willow shield", 25613));
        willow.add(new PremadeItem("Willow stock", 9444));
        DATA.put(LogType.WILLOW, willow);

        // Maple
        List<PremadeItem> maple = new ArrayList<>();
        maple.add(new PremadeItem("Maple shortbow (u)", 64));
        maple.add(new PremadeItem("Maple shortbow", 849));
        maple.add(new PremadeItem("Maple longbow (u)", 62));
        maple.add(new PremadeItem("Maple longbow", 851));
        maple.add(new PremadeItem("Maple shield", 25616));
        maple.add(new PremadeItem("Maple stock", 9448));
        DATA.put(LogType.MAPLE, maple);

        // Yew
        List<PremadeItem> yew = new ArrayList<>();
        yew.add(new PremadeItem("Yew shortbow (u)", 68));
        yew.add(new PremadeItem("Yew shortbow", 853));
        yew.add(new PremadeItem("Yew longbow (u)", 66));
        yew.add(new PremadeItem("Yew longbow", 855));
        yew.add(new PremadeItem("Yew shield", 25618));
        yew.add(new PremadeItem("Yew stock", 9452));
        DATA.put(LogType.YEW, yew);

        // Magic
        List<PremadeItem> magic = new ArrayList<>();
        magic.add(new PremadeItem("Magic shortbow (u)", 72));
        magic.add(new PremadeItem("Magic shortbow", 857));
        magic.add(new PremadeItem("Magic longbow (u)", 70));
        magic.add(new PremadeItem("Magic longbow", 859));
        magic.add(new PremadeItem("Magic shield", 25620));
        magic.add(new PremadeItem("Magic stock", 9454));
        DATA.put(LogType.MAGIC, magic);

        // Redwood
        List<PremadeItem> redwood = new ArrayList<>();
        redwood.add(new PremadeItem("Redwood Shield", 25622));
        redwood.add(new PremadeItem("Redwood Hiking Staff", 31049));
        DATA.put(LogType.REDWOOD, redwood);
    }
}
