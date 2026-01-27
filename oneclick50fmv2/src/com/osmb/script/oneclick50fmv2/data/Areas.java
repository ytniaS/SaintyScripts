package com.osmb.script.oneclick50fmv2.data;

import com.osmb.api.location.area.impl.PolyArea;
import com.osmb.api.location.area.impl.RectangleArea;
import com.osmb.api.location.position.types.WorldPosition;

import java.util.List;


public class Areas {

    public static final PolyArea CASTLE_WARS_MAIN = new PolyArea(List.of(
            new WorldPosition(2465, 3118, 0),
            new WorldPosition(2462, 3122, 0),
            new WorldPosition(2455, 3125, 0),
            new WorldPosition(2447, 3127, 0),
            new WorldPosition(2444, 3129, 0),
            new WorldPosition(2440, 3128, 0),
            new WorldPosition(2439, 3121, 0),
            new WorldPosition(2439, 3109, 0),
            new WorldPosition(2441, 3105, 0),
            new WorldPosition(2448, 3103, 0),
            new WorldPosition(2452, 3098, 0),
            new WorldPosition(2451, 3094, 0),
            new WorldPosition(2451, 3084, 0),
            new WorldPosition(2451, 3080, 0),
            new WorldPosition(2452, 3078, 0),
            new WorldPosition(2458, 3078, 0),
            new WorldPosition(2470, 3078, 0),
            new WorldPosition(2476, 3086, 0),
            new WorldPosition(2479, 3097, 0),
            new WorldPosition(2474, 3109, 0)
    ));

    public static final PolyArea NORMAL_TREES = CASTLE_WARS_MAIN;

    public static final RectangleArea OAK_TREES = new RectangleArea(2448, 3110, 10, 12, 0);

    public static final RectangleArea WILLOW_TREES = new RectangleArea(2442, 3115, 8, 10, 0);

    public static final PolyArea BONFIRE_AREA = new PolyArea(List.of(
            new WorldPosition(2458, 3095, 0),
            new WorldPosition(2458, 3105, 0),
            new WorldPosition(2465, 3105, 0),
            new WorldPosition(2470, 3100, 0),
            new WorldPosition(2470, 3090, 0),
            new WorldPosition(2465, 3090, 0)
    ));
}
