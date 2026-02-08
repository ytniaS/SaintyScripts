package com.osmb.script.libation;

import com.osmb.api.ScriptCore;
import com.osmb.api.shape.Rectangle;
import com.osmb.api.utils.RandomUtils;
import com.osmb.api.visual.SearchablePixel;
import com.osmb.api.visual.color.ColorModel;
import com.osmb.api.visual.color.tolerance.impl.SingleThresholdComparator;

import java.awt.*;
import java.util.List;

public class QuetzalMapInterface {

    private final ScriptCore core;

    // Map dimensions
    private static final int TEMPLATE_WIDTH = 492;
    private static final int TEMPLATE_HEIGHT = 298;

    // Normalized target positions (averaged from 894×540 and 894×700 measurements)
    private static final double ALDARIN_REL_X = 0.38217;
    private static final double ALDARIN_REL_Y = 0.79277;
    private static final double TEOMAT_REL_X = 0.41145;
    private static final double TEOMAT_REL_Y = 0.38730;

    // Background scroll colour
    private static final int SCROLL_PIXEL_COLOR = -11578025;
    private static final int SCROLL_PIXEL_MIN_COUNT = 10;

    // X button detection
    private static final int MAP_X_BUTTON_COLOR = -2049926;
    private static final int MAP_X_OFFSET_FROM_MAP_RIGHT = 24;

    private Rectangle cachedBounds = null;

    public QuetzalMapInterface(ScriptCore core) {
        this.core = core;
    }

    public boolean isVisible() {
        return getMapBounds() != null;
    }

    public Rectangle getMapBounds() {
        if (cachedBounds != null && hasScrollPixel(cachedBounds)) {
            return cachedBounds;
        }
        cachedBounds = inferBoundsFromPixels();
        return cachedBounds;
    }

    public Point getAldarinClickPoint() {
        return randomPointInNormalizedTarget(ALDARIN_REL_X, ALDARIN_REL_Y);
    }

    public Point getTeomatClickPoint() {
        return randomPointInNormalizedTarget(TEOMAT_REL_X, TEOMAT_REL_Y);
    }

    // Convert normalized (0..1) coords to a small click box inside bounds
    private Point randomPointInNormalizedTarget(double relX, double relY) {
        Rectangle bounds = getMapBounds();
        if (bounds == null) {
            return null;
        }

        int centerX = bounds.x + (int) Math.round(bounds.width * relX);
        int centerY = bounds.y + (int) Math.round(bounds.height * relY);

        int boxW = Math.max(6, (int) Math.round(bounds.width * 0.02));
        int boxH = Math.max(6, (int) Math.round(bounds.height * 0.02));

        int x = centerX - boxW / 2 + RandomUtils.uniformRandom(0, boxW);
        int y = centerY - boxH / 2 + RandomUtils.uniformRandom(0, boxH);

        return new Point(x, y);
    }

    private boolean hasScrollPixel(Rectangle bounds) {
        if (bounds == null) return false;

        SearchablePixel pixel = new SearchablePixel(
                SCROLL_PIXEL_COLOR,
                new SingleThresholdComparator(2),
                ColorModel.RGB
        );

        List<Point> list = core.getPixelAnalyzer().findPixels(bounds, new SearchablePixel[]{pixel});
        return list != null && list.size() >= SCROLL_PIXEL_MIN_COUNT;
    }

    // Find X button by pixel colour, infer map rect, confirm with scroll pixel
    private Rectangle inferBoundsFromPixels() {
        Rectangle screen = core.getScreen() != null ? core.getScreen().getBounds() : null;
        if (screen == null) return null;

        SearchablePixel xPixel = new SearchablePixel(
                MAP_X_BUTTON_COLOR,
                new SingleThresholdComparator(2),
                ColorModel.RGB
        );

        List<Point> xPoints = core.getPixelAnalyzer().findPixels(screen, new SearchablePixel[]{xPixel});
        if (xPoints == null || xPoints.size() < 5) return null;

        // Find bounding box of X button pixels
        int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE;

        for (Point p : xPoints) {
            minX = Math.min(minX, p.x);
            minY = Math.min(minY, p.y);
            maxX = Math.max(maxX, p.x);
            maxY = Math.max(maxY, p.y);
        }

        int btnW = maxX - minX + 1;
        int btnH = maxY - minY + 1;
        if (btnW > 35 || btnH > 30) return null;

        // Calculate map position from X button location
        int mapX = minX - (TEMPLATE_WIDTH - MAP_X_OFFSET_FROM_MAP_RIGHT);
        int mapY = minY;
        mapX = Math.max(0, Math.min(mapX, screen.width - TEMPLATE_WIDTH));
        mapY = Math.max(0, Math.min(mapY, screen.height - TEMPLATE_HEIGHT));

        Rectangle inferred = new Rectangle(mapX, mapY, TEMPLATE_WIDTH, TEMPLATE_HEIGHT);

        // Confirm with scroll pixel check
        if (!hasScrollPixel(inferred)) return null;

        return inferred;
    }
}