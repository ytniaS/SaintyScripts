package com.osmb.script.valetotemsfree.util;

import com.osmb.api.location.area.Area;
import com.osmb.api.location.area.impl.RectangleArea;

public class AreaDefinitions {
    // Buffalo bank area - X/Y: 1386, 3308
    public static final Area BUFFALO_AREA =
            new RectangleArea(1380, 3302, 12, 12, 0);

    // Auburnvale Bank (northeast) - X/Y: 1416, 3353
    public static final Area BANK_AREA = new RectangleArea(1410, 3347, 12, 12, 0);

    // Totem 1: X/Y: 1451, 3342
    public static final Area TOTEM_AREA_1 = new RectangleArea(1445, 3336, 12, 12, 0);
    // Totem 2: X/Y: 1476, 3333
    public static final Area TOTEM_AREA_2 = new RectangleArea(1470, 3327, 12, 12, 0);
    // Totem 3: X/Y: 1437, 3305
    public static final Area TOTEM_AREA_3 = new RectangleArea(1431, 3299, 12, 12, 0);
    // Totem 4: X/Y: 1411, 3287
    public static final Area TOTEM_AREA_4 = new RectangleArea(1405, 3281, 12, 12, 0);
    // Totem 5: X/Y: 1383, 3274
    public static final Area TOTEM_AREA_5 = new RectangleArea(1377, 3268, 12, 12, 0);
    // Totem 6: X/Y: 1350, 3320
    public static final Area TOTEM_AREA_6 = new RectangleArea(1344, 3314, 12, 12, 0);
    // Totem 7: X/Y: 1368, 3374
    public static final Area TOTEM_AREA_7 = new RectangleArea(1366, 3370, 8, 9, 0);
    // Totem 8: X/Y: 1401, 3328
    public static final Area TOTEM_AREA_8 = new RectangleArea(1395, 3322, 12, 12, 0);

    // Log balance area - between Totem 1 and Totem 2, near LOG_BALANCE_START (1453, 3335)
    public static final Area LOG_BALANCE_AREA = new RectangleArea(1448, 3330, 10, 10, 0);
    // Problematic area where pathfinding gets stuck - avoid this area
    public static final Area PROBLEMATIC_WALK_AREA = new RectangleArea(1359, 3347, 6, 7, 0);
}
